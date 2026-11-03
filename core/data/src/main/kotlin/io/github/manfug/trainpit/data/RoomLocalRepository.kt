package io.github.manfug.trainpit.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import io.github.manfug.trainpit.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class RoomLocalRepository private constructor(
    override val profile: Profile,
    private val db: LocalDatabase,
    private val crypto: KeystoreCrypto,
    private val applicationContext: Context,
    private val registryKey: String,
) : LocalRepository {
    override val device: DeviceKey get() = crypto.device
    private val dao = db.local()
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>()
    private val roots = HashMap<String, MutableList<Entry>>()
    private val pendingIds = LinkedHashSet<String>()
    private val devices = LinkedHashMap<String, DeviceKey>()
    private val cursors = HashMap<String, RemoteCursor>()
    private var relationships = emptyList<Relationship>()
    private var recordGeneration = 0L
    private var carriedGeneration = -1L
    private var carriedWeek: String? = null
    private val mutableState = MutableStateFlow(LocalState(profile = profile))
    override val state: StateFlow<LocalState> = mutableState.asStateFlow()
    @Volatile private var closed = false
    // All lifecycle fields below are guarded by registryLock, never held over suspension.
    private var references = 1
    private var activeOperations = 0
    private var disposing = false
    private var eraseOnClose = false
    private val disposed = CompletableDeferred<Unit>()

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            synchronized(registryLock) {
                check(!closed) { "This account's local store is closed. Sign in again." }
                activeOperations++
            }
            try { block() } finally {
                val dispose = synchronized(registryLock) {
                    activeOperations--
                    claimDisposal()
                }
                if (dispose) disposeStore()
            }
        }
    }

    /** Called only while holding registryLock. */
    private fun claimDisposal(): Boolean {
        if (!closed || activeOperations != 0 || disposing) return false
        disposing = true
        return true
    }

    private fun disposeStore() {
        try {
            db.close()
            if (eraseOnClose) {
                crypto.destroyKeys()
                check(applicationContext.deleteDatabase(databaseName(profile.uid)) ||
                    !applicationContext.getDatabasePath(databaseName(profile.uid)).exists()) {
                    "The account keys were deleted, but Android could not remove the encrypted database. Uninstall TrainPit to finish removing this device's data."
                }
            }
            synchronized(registryLock) {
                if (repositories[registryKey] === this) repositories.remove(registryKey)
            }
            disposed.complete(Unit)
        } catch (error: Exception) {
            disposed.completeExceptionally(error)
            throw error
        }
    }

    private fun encodeMeta(key: String, value: String) = MetadataRow(key, crypto.encrypt("metadata:$key", value.toByteArray(Charsets.UTF_8)))
    private fun decodeMeta(row: MetadataRow) = crypto.decrypt("metadata:${row.key}", row.ciphertext).toString(Charsets.UTF_8)
    private fun row(entry: Entry, pending: Boolean) = RevisionRow(entry.id, entry.rootId, entry.ownerId, entry.subjectId, entry.kind.name, pending,
        crypto.encrypt("revision:${entry.id}", TrainPitJson.encodeToString(entry).toByteArray(Charsets.UTF_8)))
    private fun remember(entry: Entry, pending: Boolean) {
        entries[entry.id] = entry
        roots.getOrPut(entry.rootId) { ArrayList() }.add(entry)
        if (pending) pendingIds.add(entry.id)
        recordGeneration++
    }
    private fun publish() { mutableState.value = LocalState(profile, relationships, entries.values.toList(), pendingIds.size) }
    private fun heads(root: String): List<Entry> {
        val revisions = roots[root].orEmpty()
        val superseded = revisions.flatMapTo(HashSet()) { it.parents }
        return revisions.filter { it.id !in superseded }
    }

    private suspend fun initialize() {
        val metadata = dao.metadata().associateBy { it.key }
        metadata["profile"]?.let {
            val stored = TrainPitJson.decodeFromString<Profile>(decodeMeta(it))
            check(stored.uid == profile.uid && stored.role == profile.role) { "The cached account identity or role differs. Do not merge these accounts." }
        }
        relationships = metadata["relationships"]?.let { TrainPitJson.decodeFromString<List<Relationship>>(decodeMeta(it)) }.orEmpty()
        metadata["devices"]?.let { row ->
            TrainPitJson.decodeFromString<List<DeviceKey>>(decodeMeta(row)).forEach {
                RevisionRules.validateDevice(it)
                devices[RevisionRules.deviceIndex(it.ownerId, it.deviceId)] = it
            }
        }
        val ownIndex = RevisionRules.deviceIndex(profile.uid, device.deviceId)
        val ownCached = devices[ownIndex]
        check(ownCached == null || ownCached.publicKey == device.publicKey) { "The local signing key no longer matches the cached device. Keys were not replaced." }
        devices[ownIndex] = ownCached ?: device
        metadata.filterKeys { it.startsWith("cursor:") }.forEach { (key, value) ->
            cursors[key.removePrefix("cursor:")] = TrainPitJson.decodeFromString(decodeMeta(value))
        }
        val unauthorized = ArrayList<String>()
        dao.revisions().forEach { row ->
            val entry = TrainPitJson.decodeFromString<Entry>(crypto.decrypt("revision:${row.id}", row.ciphertext).toString(Charsets.UTF_8))
            check(entry.id == row.id && entry.rootId == row.rootId && entry.ownerId == row.ownerId && entry.subjectId == row.subjectId && entry.kind.name == row.kind) { "Local record indices were modified. No data has been reset." }
            if (canReadEntry(profile, relationships, entry)) remember(entry, row.pending && entry.ownerId == profile.uid)
            else unauthorized.add(entry.id)
        }
        db.withTransaction {
            dao.put(encodeMeta("profile", TrainPitJson.encodeToString(profile)))
            dao.put(encodeMeta("devices", TrainPitJson.encodeToString(devices.values.toList())))
            unauthorized.chunked(500).forEach { dao.remove(it) }
            if (unauthorized.isNotEmpty()) dao.clearCursors()
        }
        if (unauthorized.isNotEmpty()) cursors.clear()
        publish()
    }

    override suspend fun savePlan(plan: WorkoutPlan, rootId: String?) = locked {
        require(profile.role == Role.TRAINER) { "Only trainers can publish training plans." }
        validatePlan(plan)
        require(relationships.any { it.trainerId == profile.uid && it.clientId == plan.clientId && it.status == RelationshipStatus.ACTIVE }) { "The client must accept an active relationship before you publish a plan." }
        val slot = RecordSlot(profile.uid, plan.clientId, EntryKind.PLAN, plan.weekStart)
        save(EntryKind.PLAN, plan.clientId, rootForSlot(slot, rootId), plan = plan, restoreDeleted = rootId == null)
    }
    override suspend fun saveSession(session: TrainingSession, rootId: String?) = locked {
        require(profile.role == Role.CLIENT) { "Only clients can record their sessions." }
        validateSession(session)
        save(EntryKind.SESSION, profile.uid, rootId, session = session)
    }
    override suspend fun saveWeight(weight: BodyWeight, rootId: String?) = locked {
        require(profile.role == Role.CLIENT) { "Only clients can record their body weight." }
        val slot = RecordSlot(profile.uid, profile.uid, EntryKind.WEIGHT, weight.date)
        save(EntryKind.WEIGHT, profile.uid, rootForSlot(slot, rootId), weight = weight, restoreDeleted = rootId == null)
    }

    private fun rootForSlot(slot: RecordSlot, requestedRoot: String?): String? {
        val logical = recordHeads(entries.values.toList())
        val physical = logical.values.flatten().groupBy { it.rootId }
        val occupants = physical.filterValues { heads -> heads.any { recordSlot(it) == slot } }
        val live = occupants.filterValues { heads -> heads.any { !it.deleted } }
        val label = if (slot.kind == EntryKind.PLAN) "weekly plan" else "daily weight"
        if (requestedRoot != null) {
            ownedHeads(requestedRoot)
            val group = logical.values.singleOrNull { heads -> heads.any { it.rootId == requestedRoot } }
                ?: throw IllegalArgumentException("This record was superseded by a slot resolution. Open the current record instead.")
            val current = group.filter { it.rootId == requestedRoot }
            require(current.size == 1 && !current.single().deleted) { "Resolve this record's conflict or deletion before editing it." }
            require(group.size == 1) { "Resolve the conflicting $label records in History before editing." }
            val moving = recordSlot(current.single()) != slot
            require((if (moving) occupants else live).keys.none { it != requestedRoot }) { "That date already has a $label record. Open that record instead, or choose another date." }
            return requestedRoot
        }
        require(live.size <= 1 && live.values.all { it.size == 1 }) { "Resolve the conflicting $label records in History before saving." }
        val existing = live.keys.singleOrNull()
        if (existing != null) {
            val group = logical.values.single { heads -> heads.any { it.rootId == existing } }
            require(group.size == 1) { "Resolve the conflicting $label records in History before saving." }
            return existing
        }
        if (occupants.size == 1 && occupants.values.single().size == 1) return occupants.keys.single()
        require(occupants.isEmpty()) { "Resolve the conflicting $label deletion records in History before saving." }
        return null
    }

    override suspend fun carryForwardPlans(today: String): Int = locked {
        if (profile.role != Role.TRAINER) return@locked 0
        val week = mondayOfWeek(today)
        if (carriedWeek == week && carriedGeneration == recordGeneration) return@locked 0
        val logical = recordHeads(entries.values.toList()).values
        val physical = logical.flatten().filter { it.ownerId == profile.uid && it.kind == EntryKind.PLAN }
        val copies = ArrayList<Entry>()
        relationships.filter { it.trainerId == profile.uid && it.status == RelationshipStatus.ACTIVE }.forEach { relationship ->
            val clientHeads = physical.filter { it.subjectId == relationship.clientId }
            // Any current-week head, including a tombstone, reserves the slot.
            if (clientHeads.any { it.plan!!.weekStart == week }) return@forEach
            val priorWeek = clientHeads.map { it.plan!!.weekStart }.filter { it < week }.maxOrNull() ?: return@forEach
            val candidates = logical.filter { heads -> heads.any {
                it.ownerId == profile.uid && it.subjectId == relationship.clientId && it.plan?.weekStart == priorWeek && !it.deleted
            } }
            val source = candidates.singleOrNull()?.singleOrNull()?.takeUnless { it.deleted } ?: return@forEach
            val plan = source.plan!!.copy(weekStart = week)
            validatePlan(plan)
            val id = newId()
            copies.add(crypto.sign(Entry(id = id, rootId = id, ownerId = profile.uid, subjectId = relationship.clientId,
                kind = EntryKind.PLAN, deviceId = device.deviceId, plan = plan)))
        }
        if (copies.isNotEmpty()) commit(copies)
        carriedWeek = week
        carriedGeneration = recordGeneration
        copies.size
    }

    private suspend fun save(kind: EntryKind, subjectId: String, rootId: String?, plan: WorkoutPlan? = null, session: TrainingSession? = null, weight: BodyWeight? = null, restoreDeleted: Boolean = false) {
        val id = newId()
        val parents = if (rootId == null) emptyList() else {
            val heads = logicalHeads(rootId)
            require(heads.size == 1) { "Resolve this record's conflicting revisions before editing it." }
            require(!heads.single().deleted || restoreDeleted) { "This record is deleted. Create a new measurement or plan for that date to restore it instead of editing an old copy." }
            require(heads.single().kind == kind && heads.single().subjectId == subjectId) { "A revision cannot change its record kind or client." }
            heads
        }
        val previous = parents.singleOrNull()
        commit(listOf(crypto.sign(Entry(id = id, rootId = rootId ?: id, ownerId = profile.uid, subjectId = subjectId, kind = kind,
            deviceId = device.deviceId, parents = parents.map { it.id }, plan = plan, session = session, weight = weight,
            schemaVersion = previous?.schemaVersion ?: 1, slotParents = previous?.slotParents.orEmpty()))))
    }

    private fun ownedHeads(rootId: String): List<Entry> {
        val result = heads(rootId)
        require(result.isNotEmpty() && result.all { it.ownerId == profile.uid }) { "Only the owner can change or delete this record." }
        return result
    }
    private fun authoredCopy(source: Entry, parents: List<Entry>, deleted: Boolean): Entry {
        val slotParents = (source.slotParents + parents.flatMap { it.slotParents }).distinct().sorted()
        return crypto.sign(source.copy(id = newId(), deviceId = device.deviceId, createdAt = System.currentTimeMillis(),
            parents = parents.map { it.id }.sorted(), deleted = deleted, signature = "",
            schemaVersion = maxOf(source.schemaVersion, parents.maxOf { it.schemaVersion }, if (slotParents.isEmpty()) 1 else 2),
            slotParents = slotParents))
    }

    private fun resolutionChain(source: Entry, heads: List<Entry>, deleted: Boolean): List<Entry> {
        val result = ArrayList<Entry>()
        var consumed = minOf(100, heads.size)
        var merged = authoredCopy(source, heads.take(consumed), deleted)
        result.add(merged)
        while (consumed < heads.size) {
            val next = minOf(consumed + 99, heads.size)
            merged = authoredCopy(source, listOf(merged) + heads.subList(consumed, next), deleted)
            result.add(merged)
            consumed = next
        }
        return result
    }

    private fun logicalHeads(rootId: String): List<Entry> {
        ownedHeads(rootId)
        return recordHeads(entries.values.toList()).values.singleOrNull { group -> group.any { it.rootId == rootId } }
            ?: throw IllegalArgumentException("This record was superseded by a slot resolution. Open the current record instead.")
    }

    private fun slotResolution(source: Entry, group: List<Entry>, deleted: Boolean): List<Entry> {
        val slotParents = (source.slotParents + group.filter {
            it.rootId != source.rootId && recordSlot(it) == recordSlot(source)
        }.map { it.id }).distinct().sorted()
        val chosen = source.copy(slotParents = slotParents, schemaVersion = if (slotParents.isEmpty()) source.schemaVersion else 2)
        return resolutionChain(chosen, group.filter { it.rootId == source.rootId }, deleted)
    }

    override suspend fun deleteRecord(rootId: String) = locked<Unit> {
        val group = logicalHeads(rootId)
        if (group.size == 1 && group.single().deleted) return@locked
        commit(slotResolution(group.first(), group, deleted = true))
    }
    override suspend fun resolveConflict(rootId: String, keepRevisionId: String) = locked<Unit> {
        val group = logicalHeads(rootId)
        require(group.size > 1) { "This record has no unresolved conflict." }
        val chosen = group.singleOrNull { it.id == keepRevisionId } ?: throw IllegalArgumentException("Choose one of this record's current conflict branches.")
        if (!chosen.deleted && chosen.kind == EntryKind.PLAN) require(relationships.any {
            it.trainerId == profile.uid && it.clientId == chosen.subjectId && it.status == RelationshipStatus.ACTIVE
        }) { "An active client relationship is required to restore or publish a plan." }
        commit(slotResolution(chosen, group, chosen.deleted))
    }
    override suspend fun deleteAllOwnedRecords() = locked<Unit> {
        val tombstones = recordHeads(entries.values.toList()).values.flatMap { group ->
            group.groupBy { it.rootId }.values.flatMap { heads ->
                if (heads.first().ownerId != profile.uid || (heads.size == 1 && heads.single().deleted)) emptyList()
                else resolutionChain(heads.first(), heads, deleted = true)
            }
        }
        if (tombstones.isNotEmpty()) commit(tombstones)
    }

    override suspend fun pending(limit: Int, afterId: String?): List<Entry> = locked {
        require(limit in 1..1000) { "The outbox page size must be 1–1000." }
        require(afterId == null || afterId.length in 1..128) { "Invalid outbox page cursor." }
        dao.pendingIds(profile.uid, limit, afterId).map { entries.getValue(it) }
    }
    override suspend fun markUploaded(revisionIds: List<String>) = locked {
        require(revisionIds.all { entries[it]?.ownerId == profile.uid }) { "Only this account's revisions can be acknowledged as uploaded." }
        db.withTransaction { revisionIds.chunked(500).forEach { dao.markUploaded(it) } }
        pendingIds.removeAll(revisionIds.toSet()); publish()
    }

    override suspend fun replaceRelationships(relationships: List<Relationship>) = locked {
        require(relationships.map { it.id }.distinct().size == relationships.size && relationships.all {
            it.includes(profile.uid) && it.trainerId != it.clientId && it.id == relationshipId(it.trainerId, it.clientId) &&
                ((profile.role == Role.TRAINER && it.trainerId == profile.uid) || (profile.role == Role.CLIENT && it.clientId == profile.uid)) &&
                (it.status == RelationshipStatus.ACTIVE || !it.shareBodyWeight)
        }) { "The relationship response does not match this account or its consent scope." }
        if (this.relationships == relationships) return@locked
        val removed = entries.values.filter { !canReadEntry(profile, relationships, it) }.map { it.id }
        // Clearing cursors on any scope change also reseeds newly authorized categories safely.
        val scopeChanged = this.relationships.map { Triple(it.id, it.status, it.shareBodyWeight) }.toSet() != relationships.map { Triple(it.id, it.status, it.shareBodyWeight) }.toSet()
        db.withTransaction {
            dao.put(encodeMeta("relationships", TrainPitJson.encodeToString(relationships)))
            removed.chunked(500).forEach { dao.remove(it) }
            if (scopeChanged) dao.clearCursors()
        }
        this.relationships = relationships.toList()
        recordGeneration++
        removed.forEach { id ->
            val entry = entries.remove(id)!!
            roots[entry.rootId]?.remove(entry)
            if (roots[entry.rootId].isNullOrEmpty()) roots.remove(entry.rootId)
            pendingIds.remove(id)
        }
        if (scopeChanged) cursors.clear()
        publish()
    }

    override suspend fun cacheDevices(devices: List<DeviceKey>) = locked {
        if (devices.all { this.devices[RevisionRules.deviceIndex(it.ownerId, it.deviceId)] == it }) return@locked
        val updated = LinkedHashMap(this.devices)
        devices.forEach { device ->
            RevisionRules.validateDevice(device)
            val index = RevisionRules.deviceIndex(device.ownerId, device.deviceId)
            val previous = updated[index]
            require(previous == null || previous.publicKey == device.publicKey) { "A registered device attempted to replace its immutable signing key." }
            require(previous?.revoked != true || device.revoked) { "A revoked signing key cannot be reactivated." }
            updated[index] = device
        }
        dao.put(encodeMeta("devices", TrainPitJson.encodeToString(updated.values.toList())))
        this.devices.clear(); this.devices.putAll(updated)
    }

    private suspend fun commit(incoming: List<Entry>, cursorKey: String? = null, cursor: RemoteCursor? = null, enqueueOwn: Boolean = true): Int {
        val additions = RevisionRules.validateMerge(incoming, entries, devices, profile, relationships)
        require((cursorKey == null) == (cursor == null)) { "A receive cursor requires both a stream key and a position." }
        if (cursorKey != null && cursor != null) {
            require(cursorKey.length in 1..512 && cursor.seconds >= 0 && cursor.nanoseconds in 0..999_999_999 && cursor.documentId.length in 1..128) { "Invalid cloud receive cursor." }
            val old = cursors[cursorKey]
            require(old == null || compareCursor(cursor, old) >= 0) { "The cloud receive cursor cannot move backwards. Retry synchronization." }
        }
        if (additions.isEmpty() && cursorKey == null) return 0
        val rows = additions.map { row(it, enqueueOwn && it.ownerId == profile.uid) }
        db.withTransaction {
            rows.chunked(500).forEach { dao.insert(it) }
            if (cursorKey != null && cursor != null) dao.put(encodeMeta("cursor:$cursorKey", TrainPitJson.encodeToString(cursor)))
        }
        additions.forEach { remember(it, enqueueOwn && it.ownerId == profile.uid) }
        if (cursorKey != null && cursor != null) cursors[cursorKey] = cursor
        if (additions.isNotEmpty()) publish()
        return additions.size
    }
    override suspend fun applyRemote(entries: List<Entry>, cursorKey: String?, cursor: RemoteCursor?): Int = locked { commit(entries, cursorKey, cursor, enqueueOwn = false) }
    override suspend fun cursor(key: String): RemoteCursor? = locked { cursors[key] }

    private fun requireSubject(subjectId: String) {
        require((profile.role == Role.CLIENT && subjectId == profile.uid) || (profile.role == Role.TRAINER && relationships.any {
            it.trainerId == profile.uid && it.clientId == subjectId && it.status == RelationshipStatus.ACTIVE
        })) { "Select your own history or a currently consenting client." }
    }

    override suspend fun exportArchive(subjectId: String, sessionRootIds: Set<String>?, password: String): ByteArray = locked {
        requireSubject(subjectId)
        val selected = LinkedHashMap<String, Entry>()
        val queue = ArrayDeque<Entry>()
        if (sessionRootIds == null) queue.addAll(entries.values.filter { it.subjectId == subjectId && canReadEntry(profile, relationships, it) })
        else {
            require(sessionRootIds.isNotEmpty()) { "Select at least one session to export." }
            sessionRootIds.forEach { root ->
                val revisions = roots[root].orEmpty()
                require(revisions.isNotEmpty() && revisions.all { it.subjectId == subjectId && it.kind == EntryKind.SESSION && canReadEntry(profile, relationships, it) }) { "A selected session is missing or outside this client's consent." }
                queue.addAll(revisions)
            }
        }
        while (queue.isNotEmpty()) {
            val entry = queue.removeFirst()
            if (selected.putIfAbsent(entry.id, entry) != null) continue
            require(selected.size <= ArchiveCodec.MAX_ENTRIES) { "Too many revisions. Export fewer sessions." }
            (entry.parents + entry.slotParents).forEach { id ->
                val parent = entries[id] ?: throw IllegalStateException("A parent revision is unavailable. Synchronize complete history before export.")
                require(parent.subjectId == subjectId && canReadEntry(profile, relationships, parent)) { "Required ancestry is no longer authorized for export." }
                queue.addLast(parent)
            }
            entry.session?.planRevisionId?.let { id ->
                entries[id]?.takeIf { it.kind == EntryKind.PLAN && it.subjectId == subjectId && canReadEntry(profile, relationships, it) }?.let(queue::addLast)
            }
        }
        require(selected.isNotEmpty()) { "There are no authorized records to export." }
        val keys = selected.values.map { entry ->
            devices[RevisionRules.deviceIndex(entry.ownerId, entry.deviceId)] ?: throw IllegalStateException("A signing key is missing. Synchronize before exporting.")
        }.distinctBy { RevisionRules.deviceIndex(it.ownerId, it.deviceId) }
        val manifest = ArchiveManifest(exporterId = profile.uid, subjectId = subjectId, selectedSessions = sessionRootIds != null, entries = selected.values.toList(), devices = keys)
        validateArchive(manifest)
        ArchiveCodec.encode(manifest, password)
    }

    private fun validateArchive(manifest: ArchiveManifest) {
        requireSubject(manifest.subjectId)
        RevisionRules.archiveEntries(manifest)
        val included = HashMap<String, DeviceKey>()
        manifest.devices.forEach { key ->
            RevisionRules.validateDevice(key)
            val index = RevisionRules.deviceIndex(key.ownerId, key.deviceId)
            require(included.put(index, key) == null) { "The archive repeats a signing key." }
            val trusted = devices[index]
            require(trusted != null && !trusted.revoked && !key.revoked && trusted.publicKey == key.publicKey) { "An archive key is unverified or revoked. Synchronize authenticated signing keys first; files cannot establish trust." }
        }
        require(manifest.entries.all { RevisionRules.deviceIndex(it.ownerId, it.deviceId) in included }) { "The archive omits a record's public signing key." }
        RevisionRules.validateMerge(manifest.entries, entries, devices, profile, relationships)
    }
    override suspend fun previewArchive(bytes: ByteArray, password: String): ArchivePreview = locked {
        val manifest = ArchiveCodec.decode(bytes, password)
        validateArchive(manifest)
        ArchivePreview(manifest)
    }
    override suspend fun importArchive(preview: ArchivePreview): ImportResult = locked {
        // Preview is not a capability: revalidate current consent, cached keys and collisions at commit.
        val manifest = preview.manifest
        validateArchive(manifest)
        val added = commit(manifest.entries)
        val importedRoots = manifest.entries.mapTo(HashSet()) { it.rootId }
        val conflicts = recordHeads(entries.values.toList()).values.count { heads -> heads.size > 1 && heads.any { it.rootId in importedRoots } }
        ImportResult(added, manifest.entries.size - added, conflicts)
    }
    override suspend fun eraseLocal() = locked {
        // Explicit device erasure invalidates every retained worker/UI reference before deletion.
        synchronized(registryLock) {
            closed = true
            references = 0
            eraseOnClose = true
        }
        entries.clear(); roots.clear(); pendingIds.clear(); devices.clear(); cursors.clear(); relationships = emptyList()
        mutableState.value = LocalState()
        // locked's finally closes the connection, destroys keys and removes DB/WAL/SHM.
    }
    override fun close() {
        val dispose = synchronized(registryLock) {
            if (references > 0) references--
            if (references == 0) closed = true
            claimDisposal()
        }
        if (dispose) disposeStore()
    }

    companion object {
        private val creationMutex = Mutex()
        private val registryLock = Any()
        private val repositories = HashMap<String, RoomLocalRepository>()
        private fun databaseName(uid: String) = "trainpit-${fingerprint(uid.toByteArray(Charsets.UTF_8))}.db"
        private fun database(context: Context, uid: String) = Room.databaseBuilder(context.applicationContext, LocalDatabase::class.java, databaseName(uid)).build()
        suspend fun open(context: Context, profile: Profile): RoomLocalRepository {
            var acquired: RoomLocalRepository? = null
            try {
                return withContext(Dispatchers.IO) {
                    acquire(context, profile).also { acquired = it }
                }
            } catch (error: Exception) {
                // Cancellation at the dispatcher return boundary must not leak a retained account.
                acquired?.close()
                throw error
            }
        }
        private suspend fun acquire(context: Context, profile: Profile): RoomLocalRepository {
            require(profile.uid.length in 1..128 && profile.displayName.isNotBlank()) { "The authenticated profile is incomplete." }
            val app = context.applicationContext
            val key = app.getDatabasePath(databaseName(profile.uid)).absolutePath
            return creationMutex.withLock {
                var retained: RoomLocalRepository? = null
                while (retained == null) {
                    val previous = synchronized(registryLock) {
                        repositories[key]?.also {
                            if (!it.closed) {
                                check(it.profile == profile) { "The authenticated profile changed. Close this account before reopening its local store." }
                                it.references++
                                retained = it
                            }
                        }
                    }
                    if (retained != null) break
                    if (previous != null) {
                        previous.disposed.await()
                        continue
                    }
                    val exists = app.getDatabasePath(databaseName(profile.uid)).exists()
                    val crypto = KeystoreCrypto(profile.uid, create = !exists)
                    val db = database(app, profile.uid)
                    val repository = RoomLocalRepository(profile, db, crypto, app, key)
                    try { repository.initialize() }
                    catch (error: Exception) { db.close(); throw error }
                    synchronized(registryLock) { repositories[key] = repository }
                    retained = repository
                }
                checkNotNull(retained)
            }
        }
        suspend fun cachedProfile(context: Context, uid: String): Profile? = withContext(Dispatchers.IO) {
            require(uid.length in 1..128) { "Invalid cached account identifier." }
            val app = context.applicationContext
            val key = app.getDatabasePath(databaseName(uid)).absolutePath
            creationMutex.withLock {
                val existing = synchronized(registryLock) { repositories[key] }
                if (existing != null) {
                    if (!existing.closed) return@withLock existing.profile
                    existing.disposed.await()
                }
                if (!app.getDatabasePath(databaseName(uid)).exists()) return@withLock null
                val crypto = KeystoreCrypto(uid, create = false)
                val db = database(app, uid)
                try {
                    db.local().metadata("profile")?.let { row ->
                        TrainPitJson.decodeFromString<Profile>(crypto.decrypt("metadata:profile", row.ciphertext).toString(Charsets.UTF_8)).also {
                            check(it.uid == uid) { "The cached profile belongs to another account." }
                        }
                    }
                } finally { db.close() }
            }
        }
        private fun compareCursor(a: RemoteCursor, b: RemoteCursor): Int = compareValuesBy(a, b, RemoteCursor::seconds, RemoteCursor::nanoseconds, RemoteCursor::documentId)
    }
}
