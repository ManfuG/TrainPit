package io.github.manfug.trainpit.data

import io.github.manfug.trainpit.model.*
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal object RevisionRules {
    fun deviceIndex(owner: String, device: String) = "$owner/$device"

    fun validateDevice(device: DeviceKey) {
        require(device.ownerId.length in 1..128 && device.deviceId.length in 1..128 && device.publicKey.length <= 1024) { "Invalid registered device identity." }
        val bytes = try { Base64.getDecoder().decode(device.publicKey) }
            catch (e: Exception) { throw IllegalArgumentException("Invalid registered public key.", e) }
        val key = try { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes)) as ECPublicKey }
            catch (e: Exception) { throw IllegalArgumentException("The registered key is not an EC signing key.", e) }
        val expected = java.security.AlgorithmParameters.getInstance("EC").apply {
            init(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        require(key.params.curve == expected.curve && key.params.generator == expected.generator &&
            key.params.order == expected.order && key.params.cofactor == expected.cofactor &&
            device.deviceId == fingerprint(bytes)) { "The device fingerprint or P-256 signing key is invalid." }
    }

    fun verify(entry: Entry, devices: Map<String, DeviceKey>) {
        validateEntry(entry)
        val device = devices[deviceIndex(entry.ownerId, entry.deviceId)]
            ?: throw IllegalArgumentException("A record uses an unverified device. Connect and synchronize registered signing keys before importing.")
        require(!device.revoked) { "A record was signed by a revoked device. It cannot be accepted." }
        val valid = try {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(device.publicKey)))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key); update(entry.signingBytes()); verify(Base64.getDecoder().decode(entry.signature))
            }
        } catch (e: Exception) { throw IllegalArgumentException("A record signature is malformed; nothing was imported.", e) }
        require(valid) { "A record signature does not match its registered owner/device; nothing was imported." }
    }

    /** Validates a whole batch before writes; complete parents must exist, in any input order. */
    fun validateMerge(
        incoming: List<Entry>, existing: Map<String, Entry>, devices: Map<String, DeviceKey>,
        profile: Profile, relationships: List<Relationship>,
    ): List<Entry> {
        val unique = LinkedHashMap<String, Entry>()
        incoming.forEach { entry ->
            verify(entry, devices)
            require(canReadEntry(profile, relationships, entry)) { "This record is outside your active relationship or body-weight consent." }
            if (entry.ownerId == profile.uid) {
                require((entry.kind == EntryKind.PLAN) == (profile.role == Role.TRAINER)) { "The record kind does not match the account role." }
            }
            val prior = existing[entry.id] ?: unique[entry.id]
            require(prior == null || prior == entry) { "A revision identifier has different signed contents. No records were changed." }
            unique[entry.id] = entry
        }
        val additions = unique.filterKeys { it !in existing }
        fun lookup(id: String) = additions[id] ?: existing[id]
        additions.values.forEach { entry ->
            val root = lookup(entry.rootId) ?: throw IllegalArgumentException("The root revision is missing. Synchronize complete history first.")
            require(entry.ownerId == root.ownerId && entry.subjectId == root.subjectId && entry.kind == root.kind) { "A root cannot change owner, client or record kind." }
            require((entry.parents.isEmpty() && entry.id == entry.rootId) || (entry.parents.isNotEmpty() && entry.id != entry.rootId)) { "A root must start with its root identifier; later revisions must name parents." }
            entry.parents.forEach { parentId ->
                val parent = lookup(parentId) ?: throw IllegalArgumentException("A parent revision is missing. Synchronize complete history before importing or editing.")
                require(parent.rootId == entry.rootId && parent.ownerId == entry.ownerId && parent.subjectId == entry.subjectId && parent.kind == entry.kind) { "A parent revision belongs to another root, owner, client or record kind." }
                // Stored revisions were verified when committed; new dependencies are verified above.
            }
            val inherited = entry.parents.flatMap { lookup(it)!!.slotParents }.toSet()
            require(entry.slotParents.containsAll(inherited)) { "A revision cannot discard an earlier slot resolution." }
            require(entry.parents.all { entry.schemaVersion >= lookup(it)!!.schemaVersion }) { "A revision cannot downgrade its record schema." }
            entry.slotParents.forEach { parentId ->
                val parent = lookup(parentId) ?: throw IllegalArgumentException("A slot resolution dependency is missing. Synchronize complete history first.")
                require(parent.rootId != entry.rootId && parent.ownerId == entry.ownerId && parent.subjectId == entry.subjectId && parent.kind == entry.kind) {
                    "A slot resolution crossed a root, owner, client or record-kind boundary."
                }
                require(parentId in inherited || recordSlot(parent) == recordSlot(entry)) { "A slot resolution cannot retire a record from another date or week." }
            }
            // Plan references are historical annotations, not authorization dependencies.
            entry.session?.planRevisionId?.let { reference ->
                lookup(reference)?.let { plan ->
                    require(plan.kind == EntryKind.PLAN && plan.subjectId == entry.subjectId) { "An available plan annotation belongs to another record kind or client." }
                }
            }
        }
        // Iterative topological ordering avoids stack overflow for long, valid histories.
        fun dependencies(entry: Entry) = entry.parents + entry.slotParents
        val indegree = additions.mapValues { (_, e) -> dependencies(e).count { it in additions } }.toMutableMap()
        val children = HashMap<String, MutableList<String>>()
        additions.values.forEach { e -> dependencies(e).filter { it in additions }.forEach { children.getOrPut(it) { ArrayList() }.add(e.id) } }
        val ready = ArrayDeque(indegree.filterValues { it == 0 }.keys)
        val ordered = ArrayList<Entry>(additions.size)
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst()
            ordered.add(additions.getValue(id))
            children[id].orEmpty().forEach { child ->
                val remaining = indegree.getValue(child) - 1
                indegree[child] = remaining
                if (remaining == 0) ready.addLast(child)
            }
        }
        require(ordered.size == additions.size) { "Revision ancestry contains a cycle. Nothing was imported." }
        return ordered
    }

    fun archiveEntries(manifest: ArchiveManifest) {
        require(manifest.format == 1 && manifest.archiveId.length in 1..128 && manifest.exporterId.length in 1..128 && manifest.subjectId.length in 1..128 && manifest.createdAt >= 0) { "Invalid archive manifest identity or version." }
        require(manifest.entries.isNotEmpty() && manifest.entries.size <= ArchiveCodec.MAX_ENTRIES) { "The archive must contain 1–50,000 revisions." }
        require(manifest.entries.map { it.id }.distinct().size == manifest.entries.size) { "The archive contains duplicate revision identifiers." }
        require(manifest.entries.all { it.subjectId == manifest.subjectId }) { "The archive includes another client's records." }
        val entries = manifest.entries.associateBy { it.id }
        manifest.entries.forEach { e -> require((e.parents + e.slotParents).all { it in entries }) { "The archive omits required causal ancestry." } }
        if (manifest.selectedSessions) {
            require(manifest.entries.none { it.kind == EntryKind.WEIGHT }) { "Selected-session archives cannot contain body weight." }
            val sessions = manifest.entries.filter { it.kind == EntryKind.SESSION }
            require(sessions.isNotEmpty()) { "A selected-session archive must contain sessions." }
            val reachablePlans = HashSet<String>()
            val queue = ArrayDeque(sessions.mapNotNull { it.session?.planRevisionId }.filter { it in entries })
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                if (!reachablePlans.add(id)) continue
                val plan = entries.getValue(id)
                require(plan.kind == EntryKind.PLAN && plan.subjectId == manifest.subjectId) { "A session references an invalid included plan." }
                queue.addAll(plan.parents + plan.slotParents)
            }
            require(manifest.entries.filter { it.kind == EntryKind.PLAN }.all { it.id in reachablePlans }) { "Selected-session archives cannot contain unrelated plans." }
        }
    }
}
