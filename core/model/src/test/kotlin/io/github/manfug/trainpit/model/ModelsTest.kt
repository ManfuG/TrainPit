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
        assertThrows(IllegalArgumentException::class.java) { validateEntry(weight().copy(schemaVersion = 3)) }
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

    @Test fun independentFirstSavesAndLegacyRootsShareOneConflictedDailySlot() {
        val first = weight()
        val second = weight("legacy-uuid").copy(rootId = "legacy-uuid", createdAt = 0, weight = BodyWeight("2026-11-02", 81_000))
        val heads = recordHeads(listOf(first, second))
        assertEquals(1, heads.size)
        assertEquals(setOf(first, second), heads.values.single().toSet())
        assertEquals(heads, recordHeads(listOf(second, first)))
        assertTrue(activeEntries(listOf(first, second)).isEmpty())
        assertEquals(2, revisionHeads(listOf(first, second)).size)
    }

    @Test fun datesAreCurrentAssignmentsNotPermanentRootAliases() {
        val original = weight()
        val moved = weight("moved", listOf("root")).copy(weight = BodyWeight("2026-11-03", 80_000))
        val other = weight("other-day").copy(rootId = "other-day")
        assertEquals(2, activeEntries(listOf(original, moved, other)).size)
        val collision = other.copy(weight = BodyWeight("2026-11-03", 79_000))
        assertTrue(activeEntries(listOf(original, moved, collision)).isEmpty())
    }

    @Test fun weeklySlotsRemainIndependentBetweenTrainersAndClients() {
        val plan = WorkoutPlan("client", "Strength", "2026-11-02",
            listOf(WorkoutDay(1, listOf(ExercisePrescription("squat", "Squat", 3, 8)))))
        val first = Entry(id = "first", rootId = "first", ownerId = "trainer", subjectId = "client",
            kind = EntryKind.PLAN, deviceId = "device", plan = plan, signature = "signed")
        val duplicate = first.copy(id = "duplicate", rootId = "duplicate", createdAt = 999)
        val otherTrainer = first.copy(id = "other-trainer", rootId = "other-trainer", ownerId = "trainer-2")
        val otherClient = first.copy(id = "other-client", rootId = "other-client", subjectId = "client-2", plan = plan.copy(clientId = "client-2"))
        val history = listOf(first, duplicate, otherTrainer, otherClient)
        assertEquals(3, recordHeads(history).size)
        assertEquals(setOf(otherTrainer, otherClient), activeEntries(history).toSet())
        assertEquals("2026-11-02", mondayOfWeek("2026-11-08"))
        assertEquals("2026-11-09", mondayOfWeek("2026-11-09"))
    }

    @Test fun schemaTwoResolutionNamesExactHeadsAndLaterConcurrentDescendantsReopenConflict() {
        val original = weight()
        val duplicate = weight("duplicate").copy(rootId = "duplicate")
        val resolved = weight("resolved", listOf("root")).copy(schemaVersion = 2, slotParents = listOf("duplicate"))
        val history = listOf(original, duplicate, resolved)
        assertEquals(listOf(resolved), activeEntries(history))
        val concurrent = duplicate.copy(id = "concurrent", parents = listOf("duplicate"), weight = BodyWeight("2026-11-02", 82_000))
        assertTrue(activeEntries(history + concurrent).isEmpty())
        assertEquals(2, recordHeads(history + concurrent).values.single().size)
        val deleted = resolved.copy(id = "deleted", parents = listOf("resolved"), deleted = true)
        val staleFirstSave = duplicate.copy(id = "stale-clone", rootId = "stale-clone")
        assertTrue(activeEntries(history + deleted + staleFirstSave).isEmpty())
        assertEquals(2, recordHeads(history + deleted + staleFirstSave).values.single().size)
    }

    @Test fun schemaOneCanonicalSigningBytesRemainExactlyCompatible() {
        val original = weight().copy(createdAt = 1)
        val legacy = """{"id":"root","rootId":"root","ownerId":"client","subjectId":"client","kind":"WEIGHT","deviceId":"device","createdAt":1,"parents":[],"deleted":false,"plan":null,"session":null,"weight":{"date":"2026-11-02","grams":80000},"signature":"","schemaVersion":1}"""
        assertArrayEquals(legacy.toByteArray(Charsets.UTF_8), original.signingBytes())
        assertEquals(original, TrainPitJson.decodeFromString(Entry.serializer(), TrainPitJson.encodeToString(Entry.serializer(), original)))
        assertThrows(IllegalArgumentException::class.java) {
            validateEntry(original.copy(parents = listOf("old"), slotParents = listOf("other")))
        }
    }
}
