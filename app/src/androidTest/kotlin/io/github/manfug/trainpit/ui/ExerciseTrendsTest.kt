package io.github.manfug.trainpit.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.manfug.trainpit.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Pure synthetic inputs: no account, repository, Firebase connection or production data. */
@RunWith(AndroidJUnit4::class)
class ExerciseTrendsTest {
    private val client = Profile("client", "Client", Role.CLIENT)

    private fun set(name: String, reps: Int, grams: Long, prescription: String? = null) =
        SetResult(exerciseName = name, repetitions = reps, loadGrams = grams, prescribedExerciseId = prescription)

    private fun session(id: String, date: String, sets: List<SetResult>, owner: String = client.uid,
        root: String = id, parents: List<String> = emptyList(), deleted: Boolean = false) = Entry(
        id = id, rootId = root, ownerId = owner, subjectId = owner, kind = EntryKind.SESSION,
        deviceId = "synthetic-device", createdAt = 1, parents = parents, deleted = deleted,
        session = TrainingSession(date, "Workout", sets),
    )

    @Test fun dailyMaxAndRepTotalsSpanWeeksAndIndependentSessionsIncludingZeros() {
        val first = session("first", "2026-10-05", listOf(set("  Squat ", 5, 70_000, "week-one"), set("Squat", 5, 75_000, "week-one")))
        val sameDay = session("same-day", "2026-10-05", listOf(set("squat", 2, 60_000)))
        val second = session("second", "2026-10-19", listOf(set("SQUAT", 6, 80_000, "week-three")))
        val zero = session("zero", "2026-11-02", listOf(set("Squat", 0, 0)))
        val entries = listOf(second, zero, first, sameDay)
        val result = exerciseTrends(client.uid, zero.session!!, recordHeads(entries), client, emptyList()).single()
        assertEquals(listOf(TrendPoint("2026-10-05", 75.0), TrendPoint("2026-10-19", 80.0), TrendPoint("2026-11-02", 0.0)), result.maxLoad)
        assertEquals(listOf(TrendPoint("2026-10-05", 12.0), TrendPoint("2026-10-19", 6.0), TrendPoint("2026-11-02", 0.0)), result.totalRepetitions)
        assertEquals(result, exerciseTrends(client.uid, zero.session!!, recordHeads(entries.reversed()), client, emptyList()).single())
    }

    @Test fun onlyCurrentAuthorizedClientHeadsCount() {
        val trainer = Profile("trainer", "Trainer", Role.TRAINER)
        val relationship = Relationship("relationship", trainer.uid, client.uid, trainer.displayName, client.displayName, RelationshipStatus.ACTIVE)
        val old = session("old", "2026-10-05", listOf(set("Squat", 100, 100_000)))
        val corrected = session("corrected", "2026-10-05", listOf(set("Squat", 4, 40_000)), root = old.rootId, parents = listOf(old.id))
        val conflict = session("conflict", "2026-10-12", listOf(set("Squat", 100, 100_000)))
        val competing = conflict.copy(id = "competing")
        val deleted = session("deleted", "2026-10-19", listOf(set("Squat", 100, 100_000)), deleted = true)
        val other = session("other", "2026-10-26", listOf(set("Squat", 100, 100_000)), owner = "other-client")
        val heads = recordHeads(listOf(old, corrected, conflict, competing, deleted, other))
        val result = exerciseTrends(client.uid, corrected.session!!, heads, trainer, listOf(relationship)).single()
        assertEquals(listOf(TrendPoint("2026-10-05", 40.0)), result.maxLoad)
        assertEquals(listOf(TrendPoint("2026-10-05", 4.0)), result.totalRepetitions)
        assertTrue(exerciseTrends(client.uid, corrected.session!!, heads, trainer, emptyList()).single().maxLoad.isEmpty())
        assertTrue(exerciseTrends(client.uid, corrected.session!!, heads, trainer,
            listOf(relationship.copy(status = RelationshipStatus.ENDED))).single().maxLoad.isEmpty())
    }

    @Test fun sameNamePrescriptionsAndIndependentExerciseStaySeparateWhenAmbiguous() {
        val opened = session("current", "2026-11-02", listOf(
            set("Row", 5, 10_000, "row-a"), set("row", 6, 20_000, "row-b"), set("ROW", 7, 30_000),
        ))
        val earlier = session("earlier", "2026-10-19", listOf(
            set(" row ", 8, 11_000, "row-a"), set("Row", 9, 21_000, "row-b"), set("Row", 10, 31_000),
        ))
        val result = exerciseTrends(client.uid, opened.session!!, recordHeads(listOf(opened, earlier)), client, emptyList())
        assertEquals(3, result.size)
        assertTrue(result.all { it.separatePrescription })
        assertEquals(setOf(listOf(31.0, 30.0), listOf(11.0, 10.0), listOf(21.0, 20.0)), result.map { it.maxLoad.map { p -> p.value } }.toSet())
        assertEquals(setOf(listOf(10.0, 7.0), listOf(8.0, 5.0), listOf(9.0, 6.0)), result.map { it.totalRepetitions.map { p -> p.value } }.toSet())
    }

    @Test fun reusedPrescriptionDoesNotMergeDifferentlyNamedExercises() {
        val squat = session("squat", "2026-10-19", listOf(set("Squat", 5, 100_000, "prescription")))
        val press = session("press", "2026-11-02", listOf(set("Bench press", 6, 50_000, "prescription")))
        val result = exerciseTrends(client.uid, press.session!!, recordHeads(listOf(squat, press)), client, emptyList()).single()
        assertEquals(listOf(TrendPoint("2026-11-02", 50.0)), result.maxLoad)
        assertEquals(listOf(TrendPoint("2026-11-02", 6.0)), result.totalRepetitions)
    }
}
