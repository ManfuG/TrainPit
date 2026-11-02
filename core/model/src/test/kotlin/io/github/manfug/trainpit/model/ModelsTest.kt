package io.github.manfug.trainpit.model

import org.junit.Assert.*
import org.junit.Test

class ModelsTest {
    private val client = Profile("client", "Client", Role.CLIENT)
    private val trainer = Profile("trainer", "Trainer", Role.TRAINER)
    private val relation = Relationship("trainer_client", "trainer", "client", "Trainer", "Client", RelationshipStatus.ACTIVE)
    private fun weight(id: String = "root", parents: List<String> = emptyList(), deleted: Boolean = false) = Entry(
        id = id, rootId = "root", ownerId = "client", subjectId = "client", kind = EntryKind.WEIGHT,
        deviceId = "device", parents = parents, deleted = deleted, weight = BodyWeight("2026-11-02", 80_000), signature = "test-signature",
    )

    @Test fun kilogramsAreExactAndZeroIsNotAnUnknownBodyWeight() {
        assertEquals(82_375L, parseKilograms("82,375"))
        assertEquals("82.375", formatKilograms(82_375))
        assertEquals(0L, parseKilograms("0", allowZero = true))
        listOf("0", "-1", "1000.001", "0.0001", "NaN", "").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { parseKilograms(value) }
        }
    }

    @Test fun onlyExplicitClientConsentRevealsWeightToTheIntendedTrainer() {
        val entry = weight()
        assertTrue(canReadEntry(client, emptyList(), entry))
        assertFalse(canReadEntry(trainer, listOf(relation), entry))
        assertTrue(canReadEntry(trainer, listOf(relation.copy(shareBodyWeight = true)), entry))
        assertFalse(canReadEntry(trainer, listOf(relation.copy(status = RelationshipStatus.ENDED, shareBodyWeight = true)), entry))
        assertFalse(canReadEntry(Profile("another-trainer", "Another", Role.TRAINER), listOf(relation.copy(shareBodyWeight = true)), entry))
    }

    @Test fun divergentRevisionsStayUnresolvedUntilBothParentsAreNamed() {
        val root = weight()
        val left = weight("left", listOf("root"))
        val right = weight("right", listOf("root"), deleted = true)
        assertEquals(setOf("left", "right"), revisionHeads(listOf(root, left, right)).getValue("root").map { it.id }.toSet())
        assertTrue(activeEntries(listOf(root, left, right)).isEmpty())
        val resolved = weight("resolved", listOf("left", "right"))
        assertEquals(listOf(resolved), activeEntries(listOf(root, left, right, resolved)))
        assertTrue(activeEntries(listOf(root, weight("deleted", listOf("root"), deleted = true))).isEmpty())
    }

    @Test fun aParentReferenceCannotHideAnUnrelatedRoot() {
        val first = weight()
        val other = weight("other").copy(rootId = "other", parents = listOf("root"))
        assertEquals(setOf("root", "other"), revisionHeads(listOf(first, other)).keys)
        assertEquals(listOf(first), revisionHeads(listOf(first, other)).getValue("root"))
    }

    @Test fun plansRequireAnUnambiguousMondayWeekAndUniquePrescriptions() {
        val exercise = ExercisePrescription("exercise", "Squat", 3, 8, 60_000)
        val plan = WorkoutPlan("client", "Strength", "2026-11-02", listOf(WorkoutDay(1, listOf(exercise))))
        validatePlan(plan)
        assertThrows(IllegalArgumentException::class.java) { validatePlan(plan.copy(weekStart = "2026-11-03")) }
        assertThrows(IllegalArgumentException::class.java) { validatePlan(plan.copy(days = listOf(WorkoutDay(1, listOf(exercise)), WorkoutDay(3, listOf(exercise))))) }
        val noSets = plan.copy(days = listOf(WorkoutDay(1, listOf(exercise.copy(sets = 0)))))
        assertThrows(IllegalArgumentException::class.java) { validatePlan(noSets) }
    }

    @Test fun signedRecordBoundariesRejectCrossOwnerMetricsUnknownSchemasAndPaths() {
        validateEntry(weight())
        assertThrows(IllegalArgumentException::class.java) { validateEntry(weight().copy(ownerId = "trainer")) }
        assertThrows(IllegalArgumentException::class.java) { validateEntry(weight().copy(schemaVersion = 2)) }
        assertThrows(IllegalArgumentException::class.java) { validateEntry(weight().copy(id = "nested/path")) }
        assertThrows(IllegalArgumentException::class.java) { validateEntry(weight().copy(parents = listOf("bad/path"))) }
        assertThrows(IllegalArgumentException::class.java) { validateEntry(weight().copy(weight = BodyWeight("2026-11-02", 0))) }
    }

    @Test fun historyDatesUseFixedWidthCalendarYearsSoSortingAndChartsAgree() {
        listOf("+10000-01-01", "-0001-01-01", "0000-01-01").forEach { date ->
            assertThrows(IllegalArgumentException::class.java) { validateEntry(weight().copy(weight = BodyWeight(date, 80_000))) }
        }
        validateEntry(weight().copy(weight = BodyWeight("0001-01-01", 80_000)))
        validateEntry(weight().copy(weight = BodyWeight("9999-12-31", 80_000)))
    }
}
