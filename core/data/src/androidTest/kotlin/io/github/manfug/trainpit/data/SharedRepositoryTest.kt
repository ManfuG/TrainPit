package io.github.manfug.trainpit.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.manfug.trainpit.model.*
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedRepositoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun profile() = Profile("storage-test-${UUID.randomUUID()}", "Storage test", Role.CLIENT)

    @Test fun concurrentOpensShareWritesAndOneCloseDoesNotCloseTheOther() = runBlocking {
        val profile = profile()
        val repositories = coroutineScope {
            listOf(async(Dispatchers.IO) { RoomLocalRepository.open(context, profile) },
                async(Dispatchers.IO) { RoomLocalRepository.open(context, profile) }).awaitAll()
        }
        val ui = repositories[0]
        val worker = repositories[1]
        var workerClosed = false
        var uiClosed = false
        try {
            val remote = remoteWeight(profile.uid)
            worker.cacheDevices(listOf(remote.first))
            assertEquals(1, worker.applyRemote(listOf(remote.second)))
            assertEquals(remote.second, ui.state.value.entries.single())
            ui.saveSession(TrainingSession("2026-01-02", "Logged offline",
                listOf(SetResult("set", "Squat", 5, 50000))))
            assertEquals(50_000L, worker.state.value.entries.single { it.kind == EntryKind.SESSION }.session!!.sets.single().loadGrams)

            worker.close()
            workerClosed = true
            ui.saveWeight(BodyWeight("2026-01-03", 76000))
            assertEquals(76_000L, ui.state.value.entries.single { it.ownerId == profile.uid && it.weight?.date == "2026-01-03" }.weight!!.grams)
            assertEquals(2, ui.state.value.pendingCount)
            ui.close()
            uiClosed = true

            val reopened = RoomLocalRepository.open(context, profile)
            try {
                assertEquals(76_000L, reopened.state.value.entries.single { it.weight?.date == "2026-01-03" }.weight!!.grams)
                assertEquals(2, reopened.pending(100, null).size)
                reopened.eraseLocal()
            } finally { reopened.close() }
        } finally {
            if (!workerClosed) worker.close()
            if (!uiClosed) ui.close()
        }
    }

    @Test fun explicitErasureInvalidatesAllReferencesAndDestroysDeviceData() = runBlocking {
        val profile = profile()
        val ui = RoomLocalRepository.open(context, profile)
        val worker = RoomLocalRepository.open(context, profile)
        val device = ui.device
        val database = context.getDatabasePath("trainpit-${fingerprint(profile.uid.toByteArray(Charsets.UTF_8))}.db")
        try {
            worker.saveWeight(BodyWeight("2026-01-02", 75000))
            val lateRemote = remoteWeight(profile.uid)
            worker.cacheDevices(listOf(lateRemote.first))
            ui.eraseLocal()
            assertNull(ui.state.value.profile)
            assertTrue(worker.state.value.entries.isEmpty())
            assertFalse(database.exists())
            assertFalse(java.io.File(database.path + "-wal").exists())
            assertFalse(java.io.File(database.path + "-shm").exists())
            assertNull(RoomLocalRepository.cachedProfile(context, profile.uid))
            try {
                worker.applyRemote(listOf(lateRemote.second))
                fail("A stale worker repopulated an erased account")
            } catch (_: IllegalStateException) { }
            try {
                KeystoreCrypto(profile.uid, create = false)
                fail("The erased account's Keystore keys are still accessible")
            } catch (_: IllegalStateException) { }

            // A subsequent explicit authenticated open enrolls a new device, never the erased key.
            val newlyAuthenticated = RoomLocalRepository.open(context, profile)
            try {
                assertNotEquals(device.deviceId, newlyAuthenticated.device.deviceId)
                assertTrue(newlyAuthenticated.state.value.entries.isEmpty())
                newlyAuthenticated.eraseLocal()
            } finally { newlyAuthenticated.close() }
        } finally { worker.close(); ui.close() }
    }

    @Test fun repeatedDailySaveCorrectsOneRootAndOccupiedDateMovesFailWithoutWrites() = runBlocking {
        val local = RoomLocalRepository.open(context, profile())
        try {
            local.saveWeight(BodyWeight("2026-01-02", 75_000))
            val original = local.state.value.entries.single()
            local.saveWeight(BodyWeight("2026-01-02", 74_500))
            val corrected = activeEntries(local.state.value.entries).single()
            assertEquals(original.rootId, corrected.rootId)
            assertEquals(listOf(original.id), corrected.parents)
            assertEquals(original, local.state.value.entries.single { it.id == original.id })
            local.saveWeight(BodyWeight("2026-01-03", 74_000))
            val before = local.state.value.entries
            expectRejected { local.saveWeight(BodyWeight("2026-01-03", 73_500), original.rootId) }
            assertEquals(before, local.state.value.entries)
            local.saveWeight(BodyWeight("2026-01-04", 74_250), original.rootId)
            assertEquals(setOf("2026-01-03", "2026-01-04"), activeEntries(local.state.value.entries).map { it.weight!!.date }.toSet())
            assertEquals(0, local.carryForwardPlans("2026-11-02"))
            local.deleteRecord(original.rootId)
            val deleted = revisionHeads(local.state.value.entries).getValue(original.rootId).single()
            expectRejected { local.saveWeight(BodyWeight("2026-01-04", 73_000), original.rootId) }
            local.saveWeight(BodyWeight("2026-01-04", 73_500))
            val restored = activeEntries(local.state.value.entries).single { it.weight?.date == "2026-01-04" }
            assertEquals(original.rootId, restored.rootId)
            assertEquals(listOf(deleted.id), restored.parents)
        } finally { local.eraseLocal(); local.close() }
    }

    @Test fun independentDeviceRootsImportAsConflictAndOwnerResolutionRetainsEverySignedFact() = runBlocking {
        val profile = profile()
        val local = RoomLocalRepository.open(context, profile)
        try {
            local.saveWeight(BodyWeight("2026-01-02", 74_500))
            val original = local.state.value.entries.single()
            val remote = remoteWeight(profile.uid)
            local.cacheDevices(listOf(remote.first))
            val manifest = ArchiveManifest(exporterId = profile.uid, subjectId = profile.uid, selectedSessions = false,
                entries = listOf(remote.second), devices = listOf(remote.first))
            val imported = local.importArchive(ArchivePreview(manifest))
            assertEquals(1, imported.added)
            assertEquals(1, imported.conflicts)
            assertTrue(activeEntries(local.state.value.entries).isEmpty())
            assertEquals(2, recordHeads(local.state.value.entries).values.single().size)
            val before = local.state.value.entries
            expectRejected { local.saveWeight(BodyWeight("2026-01-02", 73_000)) }
            assertEquals(before, local.state.value.entries)
            local.resolveConflict(original.rootId, remote.second.id)
            val surviving = activeEntries(local.state.value.entries).single()
            assertEquals(remote.second.rootId, surviving.rootId)
            assertEquals(75_000L, surviving.weight!!.grams)
            assertEquals(original, local.state.value.entries.single { it.id == original.id })
            assertEquals(remote.second, local.state.value.entries.single { it.id == remote.second.id })
            assertEquals(2, surviving.schemaVersion)
            assertEquals(listOf(original.id), surviving.slotParents)
            local.saveWeight(BodyWeight("2026-01-02", 74_750), surviving.rootId)
            assertEquals(74_750L, activeEntries(local.state.value.entries).single().weight!!.grams)
            local.applyRemote(listOf(remote.second))
            assertEquals(74_750L, activeEntries(local.state.value.entries).single().weight!!.grams)
            local.saveWeight(BodyWeight("2026-01-03", 74_750), surviving.rootId)
            val moved = activeEntries(local.state.value.entries).single()
            assertEquals("2026-01-03", moved.weight!!.date)
            assertEquals(surviving.slotParents, moved.slotParents)
            local.saveWeight(BodyWeight("2026-01-02", 74_750), surviving.rootId)
            val corrected = activeEntries(local.state.value.entries).single()
            assertEquals(surviving.slotParents, corrected.slotParents)
            val crypto = KeystoreCrypto(profile.uid, create = false)
            val lateEdit = crypto.sign(original.copy(id = "late-edit", parents = listOf(original.id),
                weight = BodyWeight("2026-01-02", 72_000), signature = ""))
            local.applyRemote(listOf(lateEdit))
            assertTrue(activeEntries(local.state.value.entries).isEmpty())
            local.resolveConflict(corrected.rootId, corrected.id)
            local.deleteRecord(corrected.rootId)
            val staleFirstSave = crypto.sign(original.copy(id = "stale-first", rootId = "stale-first", signature = ""))
            local.applyRemote(listOf(staleFirstSave))
            assertTrue(activeEntries(local.state.value.entries).isEmpty())
            assertEquals(2, recordHeads(local.state.value.entries).values.single().size)
            local.deleteRecord(corrected.rootId)
            assertTrue(activeEntries(local.state.value.entries).isEmpty())
        } finally { local.eraseLocal(); local.close() }
    }

    @Test fun weeklySaveReusesRootRejectsOccupiedMoveAndCarryForwardSkipsMissedWeeks() = runBlocking {
        val profile = Profile("trainer-test-${UUID.randomUUID()}", "Trainer", Role.TRAINER)
        val local = RoomLocalRepository.open(context, profile)
        try {
            val relationship = Relationship(relationshipId(profile.uid, "client"), profile.uid, "client", "Trainer", "Client", RelationshipStatus.ACTIVE)
            local.replaceRelationships(listOf(relationship))
            val exercise = ExercisePrescription("stable-exercise", "Squat", 3, 8, 60_000)
            val plan = WorkoutPlan("client", "Strength", "2026-10-19", listOf(WorkoutDay(1, listOf(exercise))))
            local.savePlan(plan)
            val original = local.state.value.entries.single()
            local.savePlan(plan.copy(title = "Updated strength"))
            val revised = activeEntries(local.state.value.entries).single()
            assertEquals(original.rootId, revised.rootId)
            val copies = coroutineScope {
                listOf(async { local.carryForwardPlans("2026-11-03") }, async { local.carryForwardPlans("2026-11-03") }).awaitAll()
            }
            assertEquals(1, copies.sum())
            val current = activeEntries(local.state.value.entries).single { it.plan!!.weekStart == "2026-11-02" }
            assertEquals("Updated strength", current.plan!!.title)
            assertEquals(exercise, current.plan!!.days.single().exercises.single())
            assertNotEquals(original.rootId, current.rootId)
            assertFalse(local.state.value.entries.any { it.plan!!.weekStart == "2026-10-26" })
            assertEquals(original, local.state.value.entries.single { it.id == original.id })
            val before = local.state.value.entries
            expectRejected { local.savePlan(plan.copy(weekStart = "2026-11-02"), original.rootId) }
            assertEquals(before, local.state.value.entries)
            local.deleteRecord(current.rootId)
            assertEquals(0, local.carryForwardPlans("2026-11-08"))
            assertEquals(0, local.carryForwardPlans("2026-11-09"))
            assertTrue(revisionHeads(local.state.value.entries).getValue(current.rootId).single().deleted)
            val staleClone = KeystoreCrypto(profile.uid, create = false).sign(current.copy(
                id = "stale-plan-clone", rootId = "stale-plan-clone", parents = emptyList(), signature = ""))
            local.applyRemote(listOf(staleClone))
            assertEquals(0, local.carryForwardPlans("2026-11-02"))
            assertFalse(activeEntries(local.state.value.entries).any { it.plan!!.weekStart == "2026-11-02" })
            assertEquals(2, recordHeads(local.state.value.entries).values.single { heads -> heads.any { it.rootId == current.rootId } }.size)
            local.resolveConflict(current.rootId, revisionHeads(local.state.value.entries).getValue(current.rootId).single().id)
            val deleted = recordHeads(local.state.value.entries).values.flatten().single { it.plan!!.weekStart == "2026-11-02" }
            expectRejected { local.savePlan(plan.copy(weekStart = "2026-11-02"), deleted.rootId) }
            local.savePlan(plan.copy(weekStart = "2026-11-02"))
            val restored = activeEntries(local.state.value.entries).single { it.plan!!.weekStart == "2026-11-02" }
            assertEquals(deleted.rootId, restored.rootId)
            assertEquals(listOf(deleted.id), restored.parents)
        } finally { local.eraseLocal(); local.close() }
    }

    @Test fun endedRelationshipsAndConflictedLatestPlansNeverGenerateNewPlans() = runBlocking {
        val profile = Profile("trainer-test-${UUID.randomUUID()}", "Trainer", Role.TRAINER)
        val local = RoomLocalRepository.open(context, profile)
        try {
            val relationship = Relationship(relationshipId(profile.uid, "client"), profile.uid, "client", "Trainer", "Client", RelationshipStatus.ACTIVE)
            local.replaceRelationships(listOf(relationship))
            val plan = WorkoutPlan("client", "Strength", "2026-10-19",
                listOf(WorkoutDay(1, listOf(ExercisePrescription("squat", "Squat", 3, 8)))))
            local.savePlan(plan)
            val original = local.state.value.entries.single()
            local.replaceRelationships(listOf(relationship.copy(status = RelationshipStatus.ENDED)))
            assertEquals(0, local.carryForwardPlans("2026-11-02"))
            expectRejected { local.savePlan(plan.copy(weekStart = "2026-11-02")) }
            local.replaceRelationships(listOf(relationship))
            val crypto = KeystoreCrypto(profile.uid, create = false)
            val duplicate = crypto.sign(original.copy(id = "legacy-plan", rootId = "legacy-plan", parents = emptyList(), signature = ""))
            local.applyRemote(listOf(duplicate))
            assertEquals(0, local.carryForwardPlans("2026-11-02"))
            assertTrue(activeEntries(local.state.value.entries).isEmpty())
            local.resolveConflict(original.rootId, original.id)
            assertEquals(1, local.carryForwardPlans("2026-11-02"))
            assertEquals(original, local.state.value.entries.single { it.id == original.id })
            local.deleteRecord(activeEntries(local.state.value.entries).single { it.plan!!.weekStart == "2026-11-02" }.rootId)
            assertEquals(0, local.carryForwardPlans("2026-11-02"))
        } finally { local.eraseLocal(); local.close() }
    }

    @Test fun planCorrectionsCarryForwardAndConsentPurgeNeverRewritePerformedReferences() = runBlocking {
        val clientProfile = profile()
        val trainerProfile = Profile("trainer-test-${UUID.randomUUID()}", "Trainer", Role.TRAINER)
        val trainer = RoomLocalRepository.open(context, trainerProfile)
        val client = RoomLocalRepository.open(context, clientProfile)
        try {
            val relationship = Relationship(relationshipId(trainerProfile.uid, clientProfile.uid),
                trainerProfile.uid, clientProfile.uid, "Trainer", "Client", RelationshipStatus.ACTIVE)
            trainer.replaceRelationships(listOf(relationship))
            client.replaceRelationships(listOf(relationship))
            client.cacheDevices(listOf(trainer.device))
            val exercise = ExercisePrescription("exercise", "Squat", 3, 8, 60_000)
            val plan = WorkoutPlan(clientProfile.uid, "Strength", "2026-10-19", listOf(WorkoutDay(1, listOf(exercise))))
            trainer.savePlan(plan)
            val original = trainer.state.value.entries.single()
            client.applyRemote(trainer.state.value.entries)
            client.saveSession(TrainingSession("2026-10-19", "Performed",
                listOf(SetResult("result", "Squat", 7, 57_500, exercise.id)), original.id, 1))
            val performed = client.state.value.entries.single { it.kind == EntryKind.SESSION }
            trainer.savePlan(plan.copy(title = "Revised"), original.rootId)
            val revised = activeEntries(trainer.state.value.entries).single()
            val duplicate = KeystoreCrypto(trainerProfile.uid, create = false).sign(revised.copy(
                id = "duplicate-plan", rootId = "duplicate-plan", parents = emptyList(), signature = ""))
            trainer.applyRemote(listOf(duplicate))
            trainer.resolveConflict(original.rootId, revised.id)
            val chosen = activeEntries(trainer.state.value.entries).single()
            assertEquals(2, chosen.schemaVersion)
            assertEquals(listOf(duplicate.id), chosen.slotParents)
            assertEquals(1, trainer.carryForwardPlans("2026-11-02"))
            client.applyRemote(trainer.state.value.entries)
            assertEquals(0, client.carryForwardPlans("2026-11-09"))
            assertEquals(performed, client.state.value.entries.single { it.kind == EntryKind.SESSION })
            assertEquals(original.id, activeEntries(client.state.value.entries).single { it.kind == EntryKind.SESSION }.session!!.planRevisionId)
            val dependencySession = TrainingSession("2026-10-20", "After resolution",
                listOf(SetResult("dependency-result", "Squat", 6, 55_000, exercise.id)), chosen.id, 1)
            client.saveSession(dependencySession)
            val dependencyRoot = activeEntries(client.state.value.entries).single { it.session?.planRevisionId == chosen.id }.rootId
            val password = "Synthetic archive passphrase"
            val selected = client.previewArchive(client.exportArchive(clientProfile.uid, setOf(dependencyRoot), password), password)
            assertTrue(selected.manifest.entries.any { it.id == duplicate.id })
            assertTrue(selected.manifest.entries.any { it.id == chosen.id && it.slotParents == listOf(duplicate.id) })
            client.replaceRelationships(listOf(relationship.copy(status = RelationshipStatus.ENDED)))
            assertFalse(client.state.value.entries.any { it.kind == EntryKind.PLAN })
            assertEquals(performed, client.state.value.entries.single { it.id == performed.id })
            assertEquals(2, client.state.value.entries.size)
        } finally {
            client.eraseLocal(); client.close()
            trainer.eraseLocal(); trainer.close()
        }
    }

    private suspend fun expectRejected(operation: suspend () -> Unit) {
        try { operation(); fail("The occupied or unauthorized slot was accepted") }
        catch (_: IllegalArgumentException) { }
    }

    private fun remoteWeight(uid: String): Pair<DeviceKey, Entry> {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val device = DeviceKey(uid, fingerprint(pair.public.encoded), Base64.getEncoder().encodeToString(pair.public.encoded))
        val entry = Entry(id = "remote-weight", rootId = "remote-weight", ownerId = uid, subjectId = uid,
            kind = EntryKind.WEIGHT, deviceId = device.deviceId, createdAt = 1, weight = BodyWeight("2026-01-02", 75000))
        val signed = entry.copy(signature = Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(entry.signingBytes()); sign()
        }))
        return device to signed
    }
}
