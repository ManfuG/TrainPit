package io.github.manfug.trainpit.model

import kotlinx.coroutines.flow.StateFlow

interface LocalRepository {
    val profile: Profile
    val device: DeviceKey
    val state: StateFlow<LocalState>
    suspend fun savePlan(plan: WorkoutPlan, rootId: String? = null)
    suspend fun saveSession(session: TrainingSession, rootId: String? = null)
    suspend fun saveWeight(weight: BodyWeight, rootId: String? = null)
    suspend fun deleteRecord(rootId: String)
    suspend fun resolveConflict(rootId: String, keepRevisionId: String)
    suspend fun deleteAllOwnedRecords()
    suspend fun pending(limit: Int = 100, afterId: String? = null): List<Entry>
    suspend fun markUploaded(revisionIds: List<String>)
    suspend fun replaceRelationships(relationships: List<Relationship>)
    /** Trusted only after authentication against Firebase device registration, never from an archive. */
    suspend fun cacheDevices(devices: List<DeviceKey>)
    suspend fun applyRemote(entries: List<Entry>, cursorKey: String? = null, cursor: RemoteCursor? = null): Int
    suspend fun cursor(key: String): RemoteCursor?
    suspend fun exportArchive(subjectId: String, sessionRootIds: Set<String>? = null, password: String): ByteArray
    suspend fun previewArchive(bytes: ByteArray, password: String): ArchivePreview
    suspend fun importArchive(preview: ArchivePreview): ImportResult
    suspend fun eraseLocal()
    fun close()
}
