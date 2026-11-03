package io.github.manfug.trainpit.ui

import io.github.manfug.trainpit.model.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class WeeklyScheduleTest {
    private val client = Profile("client", "Client", Role.CLIENT)
    private val relationship = Relationship("trainer_client", "trainer", "client", "Trainer", "Client", RelationshipStatus.ACTIVE)
    private val today = LocalDate.of(2026, 11, 3)
    private fun plan(id: String, week: String) = Entry(id = id, rootId = id, ownerId = "trainer", subjectId = client.uid,
        kind = EntryKind.PLAN, deviceId = "device", signature = "fixture",
        plan = WorkoutPlan(client.uid, id, week, listOf(WorkoutDay(2, listOf(ExercisePrescription("squat", "Squat", 2, 8))))))
    private fun visible(entries: List<Entry>, connections: List<Relationship> = listOf(relationship)) =
        currentWeekPlans(client, LocalState(client, connections, entries), client.uid, today)

    @Test fun inheritedScheduleKeepsSignedSourceUntilCurrentWeekIsPublished() {
        val prior = plan("prior", "2026-10-19")
        val future = plan("future", "2026-11-09")
        assertEquals(listOf(prior), visible(listOf(prior, future)))
        val current = plan("current", "2026-11-02")
        assertEquals(listOf(current), visible(listOf(prior, current, future)))
        assertEquals("2026-10-19", prior.plan!!.weekStart)
    }

    @Test fun deletedOrConflictedLatestWeekNeverFallsBackToAnOlderPlan() {
        val prior = plan("prior", "2026-10-19")
        val current = plan("current", "2026-11-02")
        val deletion = current.copy(id = "deleted", parents = listOf(current.id), deleted = true)
        assertTrue(visible(listOf(prior, current, deletion)).isEmpty())
        assertTrue(visible(listOf(prior, current, plan("duplicate", "2026-11-02"))).isEmpty())
        assertTrue(visible(listOf(prior, plan("duplicate-prior", "2026-10-19"))).isEmpty())
    }

    @Test fun inheritedScheduleRequiresActiveConsentAndStaysInTheClientPartition() {
        val prior = plan("prior", "2026-10-19")
        assertTrue(visible(listOf(prior), listOf(relationship.copy(status = RelationshipStatus.ENDED))).isEmpty())
        assertTrue(visible(listOf(prior), emptyList()).isEmpty())
        val foreign = prior.copy(id = "foreign", rootId = "foreign", subjectId = "another-client", plan = prior.plan!!.copy(clientId = "another-client"))
        assertEquals(listOf(prior), visible(listOf(prior, foreign)))
    }
}
