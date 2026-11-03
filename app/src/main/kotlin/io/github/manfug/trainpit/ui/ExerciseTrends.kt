package io.github.manfug.trainpit.ui

import io.github.manfug.trainpit.model.*
import java.text.Normalizer
import java.util.Locale

internal data class ExerciseTrend(
    val name: String,
    val maxLoad: List<TrendPoint>,
    val totalRepetitions: List<TrendPoint>,
    val separatePrescription: Boolean,
)

private data class ExerciseKey(val name: String, val prescription: String?)
private data class DailyExercise(var maxGrams: Long = 0, var repetitions: Long = 0)
private val exerciseWhitespace = Regex("\\s+")
private fun normalizedExerciseName(name: String): String =
    Normalizer.normalize(name, Normalizer.Form.NFKC).trim().replace(exerciseWhitespace, " ").lowercase(Locale.ROOT)

/** Derives statistics only from readable, single, live heads; historical revisions never count. */
internal fun exerciseTrends(
    subject: String,
    opened: TrainingSession,
    heads: Map<String, List<Entry>>,
    profile: Profile,
    relationships: List<Relationship>,
): List<ExerciseTrend> {
    val sessions = heads.values.mapNotNull { branches ->
        branches.singleOrNull()?.takeIf {
            !it.deleted && it.subjectId == subject && it.kind == EntryKind.SESSION && canReadEntry(profile, relationships, it)
        }
    }.sortedWith(compareBy<Entry> { it.session!!.date }.thenBy { it.rootId }).map { it.session!! }
    // Distinct same-name prescriptions in one workout must not be mistaken for one exercise.
    // Otherwise exact normalized names bridge new prescription IDs in later weeks and independent sessions.
    val separateNames = (sessions.asSequence() + sequenceOf(opened)).flatMap { session ->
        session.sets.groupBy { normalizedExerciseName(it.exerciseName) }.asSequence()
            .filter { (_, sets) -> sets.map { it.prescribedExerciseId }.distinct().size > 1 }
            .map { it.key }
    }.toSet()
    fun key(set: SetResult): ExerciseKey {
        val name = normalizedExerciseName(set.exerciseName)
        return ExerciseKey(name, set.prescribedExerciseId.takeIf { name in separateNames })
    }
    val requested = opened.sets.groupBy(::key).toSortedMap(compareBy<ExerciseKey> { it.name }.thenBy { it.prescription.orEmpty() })
    val daily = requested.keys.associateWith { sortedMapOf<String, DailyExercise>() }
    sessions.forEach { session ->
        session.sets.forEach { set ->
            daily[key(set)]?.getOrPut(session.date) { DailyExercise() }?.let { total ->
                total.maxGrams = maxOf(total.maxGrams, set.loadGrams)
                total.repetitions += set.repetitions
            }
        }
    }
    val nameCounts = requested.keys.groupingBy { it.name }.eachCount()
    val nameIndices = mutableMapOf<String, Int>()
    return requested.map { (key, sets) ->
        val index = (nameIndices[key.name] ?: 0) + 1
        nameIndices[key.name] = index
        val label = sets.map { it.exerciseName.trim().replace(exerciseWhitespace, " ") }.sorted().first()
        val suffix = if ((nameCounts[key.name] ?: 0) > 1) {
            if (key.prescription == null) " · independent exercise" else " · planned exercise $index"
        } else ""
        val values = daily.getValue(key)
        ExerciseTrend(
            name = label + suffix,
            maxLoad = values.map { (date, value) -> TrendPoint(date, value.maxGrams / 1000.0) },
            totalRepetitions = values.map { (date, value) -> TrendPoint(date, value.repetitions.toDouble()) },
            separatePrescription = key.name in separateNames,
        )
    }
}
