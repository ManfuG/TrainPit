package io.github.manfug.trainpit.sync

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import io.github.manfug.trainpit.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString

object FirebaseEnvironment {
    @Volatile private var initialized = false
    @Synchronized fun initialize(context: Context, emulators: Boolean = false) {
        if (initialized) return
        FirebaseApp.initializeApp(context)
        val auth = FirebaseAuth.getInstance()
        val firestore = FirebaseFirestore.getInstance()
        // Room is the encrypted durable source of truth. Never persist a second plaintext cache.
        firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
        if (emulators) {
            auth.useEmulator("10.0.2.2", 9099)
            firestore.useEmulator("10.0.2.2", 8180)
        }
        initialized = true
    }
}

class FirebaseGateway {
    companion object { private val synchronizationMutex = Mutex() }
    val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val cloud = FirebaseFirestore.getInstance()
    private fun profiles() = cloud.collection("profiles")
    private fun entries(ownerId: String) = profiles().document(ownerId).collection("entries")
    private fun relationships() = cloud.collection("relationships")
    private fun currentUid(): String = requireNotNull(auth.currentUser?.uid) { "Sign in to synchronize." }

    suspend fun profile(uid: String = currentUid()): Profile = withTimeout(25_000) {
        val doc = profiles().document(uid).get(Source.SERVER).await()
        require(doc.exists()) { "Finish account setup using Create account with the same email and password." }
        readProfile(doc)
    }

    suspend fun signIn(email: String, password: String): Profile = withTimeout(30_000) {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
        profile()
    }

    suspend fun register(name: String, email: String, password: String, role: Role): Profile = withTimeout(40_000) {
        require(name.trim().length in 1..100) { "Name must contain 1–100 characters." }
        require(password.length >= 10) { "Use a password with at least 10 characters." }
        try {
            auth.createUserWithEmailAndPassword(email.trim(), password).await()
        } catch (_: FirebaseAuthUserCollisionException) {
            // Recover an interrupted Auth/profile registration without changing an existing role.
            auth.signInWithEmailAndPassword(email.trim(), password).await()
        }
        val uid = currentUid()
        val ref = profiles().document(uid)
        val existing = ref.get(Source.SERVER).await()
        if (existing.exists()) return@withTimeout readProfile(existing)
        val profile = Profile(uid, name.trim(), role)
        ref.set(mapOf("uid" to uid, "displayName" to profile.displayName, "role" to role.name)).await()
        profile
    }

    suspend fun resetPassword(email: String) = withTimeout(25_000) {
        require(email.trim().isNotEmpty()) { "Enter your email address." }
        auth.sendPasswordResetEmail(email.trim()).await()
        Unit
    }

    suspend fun requestClient(clientId: String) = withTimeout(30_000) {
        val trainer = profile()
        require(trainer.role == Role.TRAINER) { "Only a trainer can request a client connection." }
        require(clientId.isNotBlank() && clientId.length <= 128 && clientId != trainer.uid) { "Enter the client's account ID." }
        val client = profile(clientId.trim())
        require(client.role == Role.CLIENT) { "That account is not a client." }
        val id = relationshipId(trainer.uid, client.uid)
        val ref = relationships().document(id)
        // Rules atomically allow a first request or ENDED -> PENDING; no nonexistent
        // relationship needs to be readable, and a concurrent active grant cannot be reset.
        ref.set(mapOf(
            "id" to id, "trainerId" to trainer.uid, "clientId" to client.uid,
            "trainerName" to trainer.displayName, "clientName" to client.displayName,
            "members" to listOf(trainer.uid, client.uid), "status" to RelationshipStatus.PENDING.name,
            "shareBodyWeight" to false,
        )).await()
        Unit
    }

    suspend fun respondRelationship(id: String, accept: Boolean) = withTimeout(25_000) {
        changeRelationship(id) { relation ->
            require(relation.clientId == currentUid() && relation.status == RelationshipStatus.PENDING) { "Only the invited client can respond to this request." }
            mapOf("status" to (if (accept) RelationshipStatus.ACTIVE else RelationshipStatus.ENDED).name, "shareBodyWeight" to false)
        }
    }

    suspend fun endRelationship(id: String) = withTimeout(25_000) {
        changeRelationship(id) { relation ->
            require(relation.includes(currentUid())) { "This relationship belongs to another account." }
            mapOf("status" to RelationshipStatus.ENDED.name, "shareBodyWeight" to false)
        }
    }

    suspend fun setWeightSharing(id: String, enabled: Boolean) = withTimeout(25_000) {
        changeRelationship(id) { relation ->
            require(relation.clientId == currentUid() && relation.status == RelationshipStatus.ACTIVE) { "Only the connected client controls body-weight sharing." }
            mapOf("shareBodyWeight" to enabled)
        }
    }

    private suspend fun changeRelationship(id: String, change: (Relationship) -> Map<String, Any>) {
        val ref = relationships().document(id)
        cloud.runTransaction { transaction ->
            val snapshot = transaction.get(ref)
            require(snapshot.exists()) { "Connection not found. Synchronize and try again." }
            transaction.update(ref, change(readRelationship(snapshot)))
        }.await()
    }

    private suspend fun fetchRelationships(uid: String): List<Relationship> {
        val result = ArrayList<Relationship>()
        var last: DocumentSnapshot? = null
        do {
            var query = relationships().whereArrayContains("members", uid).orderBy(FieldPath.documentId()).limit(200)
            last?.let { query = query.startAfter(it) }
            val page = query.get(Source.SERVER).await().documents
            page.forEach { doc ->
                val relation = readRelationship(doc)
                require(relation.includes(uid)) { "Invalid relationship audience." }
                result.add(relation)
            }
            last = page.lastOrNull()
        } while (page.size == 200)
        return result
    }

    private suspend fun registerDevice(device: DeviceKey) {
        require(device.ownerId == currentUid()) { "Device identity does not match the signed-in account." }
        val ref = profiles().document(device.ownerId).collection("devices").document(device.deviceId)
        cloud.runTransaction { transaction ->
            val existing = transaction.get(ref)
            if (existing.exists()) {
                require(existing.getString("publicKey") == device.publicKey && existing.getBoolean("revoked") == false) { "This device key is no longer authorized. Sign out and remove its local data before enrolling again." }
            } else {
                transaction.set(ref, mapOf("ownerId" to device.ownerId, "deviceId" to device.deviceId, "publicKey" to device.publicKey, "revoked" to false))
            }
        }.await()
    }

    private suspend fun fetchDevices(uid: String): List<DeviceKey> {
        val result = ArrayList<DeviceKey>()
        var last: DocumentSnapshot? = null
        do {
            var query = profiles().document(uid).collection("devices").orderBy(FieldPath.documentId()).limit(200)
            last?.let { query = query.startAfter(it) }
            val page = query.get(Source.SERVER).await().documents
            page.forEach { d ->
                require(d.getString("ownerId") == uid && d.getString("deviceId") == d.id) { "Invalid signing-key registration." }
                result.add(DeviceKey(uid, d.id, requireNotNull(d.getString("publicKey")), d.getBoolean("revoked") ?: true))
            }
            last = page.lastOrNull()
        } while (page.size == 200)
        return result
    }

    /** Idempotent immutable uploads; the outbox is acknowledged only after a server commit. */
    suspend fun synchronize(local: LocalRepository): SyncReport = synchronizationMutex.withLock {
        require(local.profile.uid == currentUid()) { "The active account changed. Sign in again." }
        withTimeout(30_000) { registerDevice(local.device) }
        val relations = withTimeout(30_000) { fetchRelationships(local.profile.uid) }
        local.replaceRelationships(relations)
        val owners = (relations.filter { it.status == RelationshipStatus.ACTIVE }.map { it.otherId(local.profile.uid) } + local.profile.uid).distinct()
        val keys = coroutineScope {
            val permits = Semaphore(4)
            owners.map { uid -> async { permits.withPermit { withTimeout(30_000) { fetchDevices(uid) } } } }.map { it.await() }.flatten()
        }
        local.cacheDevices(keys)
        var uploaded = 0
        var blocked = 0
        var pendingCursor: String? = null
        while (true) {
            val page = local.pending(100, pendingCursor)
            if (page.isEmpty()) break
            pendingCursor = page.last().id
            require(page.all { it.ownerId == local.profile.uid }) { "A foreign record cannot enter this account's outbox." }
            val allowed = page.filter { entry ->
                entry.kind != EntryKind.PLAN || relations.any {
                    it.trainerId == entry.ownerId && it.clientId == entry.subjectId && it.status == RelationshipStatus.ACTIVE
                }
            }
            blocked += page.size - allowed.size
            // Shared rule reads stay bounded even when a trainer has many clients/devices.
            for (pending in allowed.groupBy { it.deviceId to it.subjectId }.values) {
                val payloads = pending.map { TrainPitJson.encodeToString(it) }
                require(payloads.all { it.length <= 262_144 }) { "A training record exceeds the cloud size limit. Reduce its number of sets." }
                withTimeout(35_000) {
                    cloud.runTransaction { transaction ->
                        val refs = pending.map { entries(it.ownerId).document(it.id) }
                        val existing = refs.map { transaction.get(it) }
                        pending.forEachIndexed { index, entry ->
                            val doc = existing[index]
                            if (doc.exists()) {
                                require(doc.getString("payload") == payloads[index]) { "Record identity collision. No data was overwritten." }
                            } else {
                                transaction.set(refs[index], mapOf(
                                    "ownerId" to entry.ownerId, "subjectId" to entry.subjectId, "kind" to entry.kind.name,
                                    "rootId" to entry.rootId, "deviceId" to entry.deviceId, "parents" to entry.parents,
                                    "createdAt" to entry.createdAt, "deleted" to entry.deleted,
                                    "payload" to payloads[index], "serverAt" to FieldValue.serverTimestamp(),
                                ))
                            }
                        }
                    }.await()
                }
                local.markUploaded(pending.map { it.id })
                uploaded += pending.size
            }
        }
        val known = local.state.value.entries.associateByTo(HashMap()) { it.id }
        var downloaded = pull(local, local.profile.uid, null, null, known)
        for (relation in relations.filter { it.status == RelationshipStatus.ACTIVE }) {
            if (local.profile.role == Role.CLIENT) {
                downloaded += pull(local, relation.trainerId, local.profile.uid, EntryKind.PLAN, known)
            } else {
                downloaded += pull(local, relation.clientId, relation.clientId, EntryKind.SESSION, known)
                if (relation.shareBodyWeight) downloaded += pull(local, relation.clientId, relation.clientId, EntryKind.WEIGHT, known)
            }
        }
        SyncReport(uploaded, downloaded, blocked)
    }

    private suspend fun pull(local: LocalRepository, owner: String, subject: String?, kind: EntryKind?, known: MutableMap<String, Entry>): Int {
        val cursorKey = "$owner|${subject ?: "*"}|${kind?.name ?: "*"}"
        var cursor = local.cursor(cursorKey)
        var count = 0
        do {
            var query: Query = entries(owner)
            if (subject != null) query = query.whereEqualTo("subjectId", subject)
            if (kind != null) query = query.whereEqualTo("kind", kind.name)
            query = query.orderBy("serverAt").orderBy(FieldPath.documentId()).limit(200)
            cursor?.let { query = query.startAfter(Timestamp(it.seconds, it.nanoseconds), it.documentId) }
            val page = withTimeout(30_000) { query.get(Source.SERVER).await().documents }
            if (page.isEmpty()) break
            val decoded = page.map { decodeEntry(owner, it) }
            val batch = decoded.associateBy { it.id }
            val closure = LinkedHashMap<String, Entry>()
            val visiting = HashSet<String>()
            for (record in decoded) {
                val stack = ArrayDeque<Pair<Entry, Boolean>>()
                stack.addLast(record to false)
                while (stack.isNotEmpty()) {
                    val (entry, expanded) = stack.removeLast()
                    val prior = known[entry.id] ?: closure[entry.id]
                    if (prior != null) {
                        require(prior == entry) { "Conflicting immutable record identity." }
                        continue
                    }
                    if (expanded) {
                        visiting.remove(entry.id)
                        closure[entry.id] = entry
                        require(closure.size <= 50_000) { "History is too large for one synchronized page." }
                        continue
                    }
                    require(visiting.add(entry.id) && visiting.size <= 50_000) { "Invalid or excessive revision ancestry." }
                    stack.addLast(entry to true)
                    for (parentId in entry.parents.asReversed()) {
                        val parent = known[parentId] ?: closure[parentId] ?: batch[parentId] ?: withTimeout(25_000) {
                            decodeEntry(entry.ownerId, entries(entry.ownerId).document(parentId).get(Source.SERVER).await())
                        }
                        require(parent.ownerId == entry.ownerId && parent.rootId == entry.rootId && parent.subjectId == entry.subjectId && parent.kind == entry.kind) { "Revision ancestry crossed an ownership boundary." }
                        stack.addLast(parent to false)
                    }
                }
            }
            val last = page.last()
            val stamp = requireNotNull(last.getTimestamp("serverAt")) { "Missing authoritative cloud timestamp." }
            val next = RemoteCursor(stamp.seconds, stamp.nanoseconds, last.id)
            count += local.applyRemote(closure.values.toList(), cursorKey, next)
            known.putAll(closure)
            cursor = next
        } while (page.size == 200)
        return count
    }

    private fun decodeEntry(owner: String, doc: DocumentSnapshot): Entry {
        require(doc.exists()) { "A required historical revision is unavailable." }
        val payload = requireNotNull(doc.getString("payload")) { "Invalid cloud record." }
        require(payload.length <= 262_144) { "Cloud record exceeds the supported size." }
        val entry = TrainPitJson.decodeFromString<Entry>(payload)
        validateEntry(entry)
        require(doc.id == entry.id && owner == entry.ownerId && doc.getString("ownerId") == entry.ownerId &&
            doc.getString("subjectId") == entry.subjectId && doc.getString("kind") == entry.kind.name &&
            doc.getString("rootId") == entry.rootId && doc.getString("deviceId") == entry.deviceId &&
            doc.getLong("createdAt") == entry.createdAt && doc.getBoolean("deleted") == entry.deleted &&
            doc.get("parents") == entry.parents) { "Cloud metadata does not match its signed contents." }
        return entry
    }

    private fun readProfile(doc: DocumentSnapshot): Profile {
        require(doc.exists() && doc.getString("uid") == doc.id) { "Invalid account profile." }
        return Profile(doc.id, requireNotNull(doc.getString("displayName")), Role.valueOf(requireNotNull(doc.getString("role"))))
    }

    private fun readRelationship(doc: DocumentSnapshot): Relationship {
        val relation = Relationship(
            id = doc.id,
            trainerId = requireNotNull(doc.getString("trainerId")), clientId = requireNotNull(doc.getString("clientId")),
            trainerName = requireNotNull(doc.getString("trainerName")), clientName = requireNotNull(doc.getString("clientName")),
            status = RelationshipStatus.valueOf(requireNotNull(doc.getString("status"))),
            shareBodyWeight = doc.getBoolean("shareBodyWeight") ?: false,
        )
        require(doc.getString("id") == relation.id && relation.id == relationshipId(relation.trainerId, relation.clientId) &&
            doc.get("members") == listOf(relation.trainerId, relation.clientId)) { "Invalid relationship identity." }
        return relation
    }
}
