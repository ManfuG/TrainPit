package io.github.manfug.trainpit.model

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val TrainPitJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }
fun newId(): String = UUID.randomUUID().toString()

@Serializable enum class Role { TRAINER, CLIENT }
@Serializable enum class RelationshipStatus { PENDING, ACTIVE, ENDED }
@Serializable enum class EntryKind { PLAN, SESSION, WEIGHT }

@Serializable data class Profile(val uid: String, val displayName: String, val role: Role)
@Serializable data class Relationship(
    val id: String,
    val trainerId: String,
    val clientId: String,
    val trainerName: String,
    val clientName: String,
    val status: RelationshipStatus = RelationshipStatus.PENDING,
    val shareBodyWeight: Boolean = false,
) {
    fun includes(uid: String) = uid == trainerId || uid == clientId
    fun otherId(uid: String) = if (uid == trainerId) clientId else trainerId
    fun otherName(uid: String) = if (uid == trainerId) clientName else trainerName
}

@Serializable data class ExercisePrescription(
    val id: String = newId(),
    val name: String,
    val sets: Int,
    val repetitions: Int,
    val loadGrams: Long? = null,
)
@Serializable data class WorkoutDay(val dayOfWeek: Int, val exercises: List<ExercisePrescription>)
@Serializable data class WorkoutPlan(
    val clientId: String,
    val title: String,
    val weekStart: String,
    val days: List<WorkoutDay>,
)
@Serializable data class SetResult(
    val id: String = newId(),
    val exerciseName: String,
    val repetitions: Int,
    val loadGrams: Long,
    val prescribedExerciseId: String? = null,
)
@Serializable data class TrainingSession(
    val date: String,
    val title: String,
    val sets: List<SetResult>,
    val planRevisionId: String? = null,
    val dayOfWeek: Int? = null,
)
@Serializable data class BodyWeight(val date: String, val grams: Long)

/** Immutable signed revision. Parent ancestry, never wall-clock time, resolves ordering. */
@Serializable data class Entry(
    val id: String = newId(),
    val rootId: String,
    val ownerId: String,
    val subjectId: String,
    val kind: EntryKind,
    val deviceId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val parents: List<String> = emptyList(),
    val deleted: Boolean = false,
    val plan: WorkoutPlan? = null,
    val session: TrainingSession? = null,
    val weight: BodyWeight? = null,
    val signature: String = "",
    val schemaVersion: Int = 1,
) {
    fun signingBytes(): ByteArray = TrainPitJson.encodeToString(copy(signature = "")).toByteArray(Charsets.UTF_8)
}

@Serializable data class DeviceKey(
    val ownerId: String,
    val deviceId: String,
    val publicKey: String,
    val revoked: Boolean = false,
)
@Serializable data class RemoteCursor(val seconds: Long, val nanoseconds: Int, val documentId: String)
@Serializable data class ArchiveManifest(
    val format: Int = 1,
    val archiveId: String = newId(),
    val exporterId: String,
    val subjectId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val selectedSessions: Boolean,
    val entries: List<Entry>,
    val devices: List<DeviceKey>,
)
data class ArchivePreview(val manifest: ArchiveManifest) {
    val plans get() = manifest.entries.count { it.kind == EntryKind.PLAN && !it.deleted }
    val sessions get() = manifest.entries.count { it.kind == EntryKind.SESSION && !it.deleted }
    val weights get() = manifest.entries.count { it.kind == EntryKind.WEIGHT && !it.deleted }
}
data class ImportResult(val added: Int, val duplicates: Int, val conflicts: Int)
data class LocalState(
    val profile: Profile? = null,
    val relationships: List<Relationship> = emptyList(),
    val entries: List<Entry> = emptyList(),
    val pendingCount: Int = 0,
) {
    val activeRelationships get() = relationships.filter { it.status == RelationshipStatus.ACTIVE }
}
data class SyncReport(val uploaded: Int, val downloaded: Int, val blocked: Int = 0)

fun relationshipId(trainerId: String, clientId: String) = "${trainerId}_${clientId}"

fun canReadEntry(profile: Profile, relationships: List<Relationship>, entry: Entry): Boolean {
    if (entry.ownerId == profile.uid) return true
    return relationships.any { r ->
        r.status == RelationshipStatus.ACTIVE && r.includes(profile.uid) && r.clientId == entry.subjectId &&
            when (entry.kind) {
                EntryKind.PLAN -> entry.ownerId == r.trainerId && profile.uid == r.clientId
                EntryKind.SESSION -> entry.ownerId == r.clientId && profile.uid == r.trainerId
                EntryKind.WEIGHT -> entry.ownerId == r.clientId && profile.uid == r.trainerId && r.shareBodyWeight
            }
    }
}

/** Every unresolved branch is kept. Deleted heads are not silently resurrected. */
fun revisionHeads(entries: List<Entry>): Map<String, List<Entry>> = entries.groupBy { it.rootId }.mapValues { (_, revisions) ->
    val parents = revisions.flatMapTo(HashSet()) { it.parents }
    revisions.filter { it.id !in parents }
}
fun activeEntries(entries: List<Entry>): List<Entry> = revisionHeads(entries).values
    .mapNotNull { heads -> heads.singleOrNull()?.takeUnless { it.deleted } }

fun parseKilograms(value: String, allowZero: Boolean = false): Long {
    val grams = try { BigDecimal(value.trim().replace(',', '.')).movePointRight(3).longValueExact() }
    catch (_: Exception) { throw IllegalArgumentException("Enter kilograms with at most three decimal places.") }
    require(grams in (if (allowZero) 0L else 1L)..1_000_000L) { if (allowZero) "Weight must be 0–1000 kg." else "Weight must be greater than 0 and at most 1000 kg." }
    return grams
}
fun formatKilograms(grams: Long): String = BigDecimal.valueOf(grams, 3).stripTrailingZeros().toPlainString()

private fun validId(value: String): Boolean = value.length in 1..128 && value.isNotBlank() &&
    value != "." && value != ".." && value.none { it == '/' || it.isISOControl() }

private fun calendarDate(value: String): LocalDate {
    require(value.length == 10) { "Use a date in YYYY-MM-DD format." }
    return LocalDate.parse(value).also { require(it.year in 1..9999) { "Use a year from 0001 to 9999." } }
}

fun validatePlan(plan: WorkoutPlan) {
    require(validId(plan.clientId)) { "Select a client." }
    require(plan.title.isNotBlank() && plan.title.length <= 100) { "Plan title must contain 1–100 characters." }
    val start = calendarDate(plan.weekStart)
    require(start.dayOfWeek.value == 1) { "The training week must start on Monday." }
    require(plan.days.isNotEmpty() && plan.days.size <= 7 && plan.days.map { it.dayOfWeek }.distinct().size == plan.days.size) { "Use distinct training days." }
    val exerciseIds = HashSet<String>()
    plan.days.forEach { day ->
        require(day.dayOfWeek in 1..7 && day.exercises.isNotEmpty() && day.exercises.size <= 40) { "Each selected day needs 1–40 exercises." }
        day.exercises.forEach { e ->
            require(validId(e.id) && exerciseIds.add(e.id)) { "Exercise identifiers must be unique." }
            require(e.name.isNotBlank() && e.name.length <= 100 && e.sets in 1..30 && e.repetitions in 1..1000) { "Enter an exercise name, 1–30 sets and 1–1000 repetitions." }
            require(e.loadGrams == null || e.loadGrams in 0..1_000_000L) { "Invalid target weight." }
        }
    }
}
fun validateSession(session: TrainingSession) {
    calendarDate(session.date)
    require(session.title.isNotBlank() && session.title.length <= 100) { "Session title must contain 1–100 characters." }
    require(session.sets.isNotEmpty() && session.sets.size <= 400) { "Record 1–400 completed sets." }
    require(session.dayOfWeek == null || session.dayOfWeek in 1..7) { "Invalid training day." }
    require(session.planRevisionId == null || validId(session.planRevisionId)) { "Invalid historical plan reference." }
    require(session.sets.map { it.id }.distinct().size == session.sets.size) { "Set identifiers must be unique." }
    session.sets.forEach { s ->
        require(validId(s.id) && (s.prescribedExerciseId == null || validId(s.prescribedExerciseId)) && s.exerciseName.isNotBlank() && s.exerciseName.length <= 100 && s.repetitions in 0..1000 && s.loadGrams in 0..1_000_000L) { "Each set needs an exercise, 0–1000 repetitions and 0–1000 kg." }
    }
}
fun validateEntry(entry: Entry) {
    require(entry.schemaVersion == 1) { "This record schema is not supported. Update TrainPit before importing it." }
    require(validId(entry.id) && validId(entry.rootId) && validId(entry.ownerId) && validId(entry.subjectId) && validId(entry.deviceId)) { "Invalid record identity." }
    require(entry.parents.size <= 100 && entry.parents.all(::validId) && entry.parents.distinct().size == entry.parents.size && entry.id !in entry.parents) { "Invalid revision parents." }
    require(entry.signature.length in 1..2048 && entry.createdAt in 0..253_402_300_799_999L) { "Invalid signed revision." }
    when (entry.kind) {
        EntryKind.PLAN -> { require(entry.plan != null && entry.session == null && entry.weight == null); validatePlan(entry.plan); require(entry.subjectId == entry.plan.clientId) }
        EntryKind.SESSION -> { require(entry.session != null && entry.plan == null && entry.weight == null && entry.ownerId == entry.subjectId); validateSession(entry.session) }
        EntryKind.WEIGHT -> { require(entry.weight != null && entry.plan == null && entry.session == null && entry.ownerId == entry.subjectId); calendarDate(entry.weight.date); require(entry.weight.grams in 1..1_000_000L) { "Body weight must be greater than 0 and at most 1000 kg." } }
    }
    require(TrainPitJson.encodeToString(entry).length <= 262_144) { "A record exceeds the supported size. Reduce its number of exercises or sets." }
}
