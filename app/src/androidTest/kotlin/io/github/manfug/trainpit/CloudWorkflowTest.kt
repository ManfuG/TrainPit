package io.github.manfug.trainpit

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.manfug.trainpit.data.RoomLocalRepository
import io.github.manfug.trainpit.model.*
import io.github.manfug.trainpit.sync.FirebaseGateway
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Keystore/Room + Firebase Auth/Firestore emulators, never production fixtures. */
@RunWith(AndroidJUnit4::class)
class CloudWorkflowTest {
    @Test fun offlineRecordsConsentVersionedPlansArchivesAndRevocation() = runBlocking {
        check(BuildConfig.FIREBASE_EMULATORS) { "Build with -PfirebaseEmulators=true; production tests are forbidden." }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val gateway = FirebaseGateway()
        val suffix = UUID.randomUUID().toString()
        val password = "Synthetic-only-$suffix"
        val clientEmail = "client-$suffix@example.invalid"
        val trainerEmail = "trainer-$suffix@example.invalid"
        val today = LocalDate.now().toString()
        val week = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
        gateway.auth.signOut()
        val clientProfile = gateway.register("Synthetic Client", clientEmail, password, Role.CLIENT)
        var client = RoomLocalRepository.open(context, clientProfile)
        var trainer: RoomLocalRepository? = null
        try {
            // Local writes need no cloud relationship or network request.
            client.saveSession(TrainingSession(today, "Independent session", listOf(SetResult(exerciseName = "Squat", repetitions = 8, loadGrams = 50_000))))
            client.saveWeight(BodyWeight(today, 81_250))
            val independentId = activeEntries(client.state.value.entries).single { it.kind == EntryKind.SESSION }.rootId
            client.close()
            client = RoomLocalRepository.open(context, clientProfile)
            assertEquals(50_000L, activeEntries(client.state.value.entries).single { it.kind == EntryKind.SESSION }.session!!.sets.single().loadGrams)
            assertEquals(2, client.state.value.pendingCount)
            gateway.synchronize(client)
            assertEquals(0, client.state.value.pendingCount)

            gateway.auth.signOut()
            val trainerProfile = gateway.register("Synthetic Trainer", trainerEmail, password, Role.TRAINER)
            val trainingStore = RoomLocalRepository.open(context, trainerProfile)
            trainer = trainingStore
            gateway.synchronize(trainingStore)
            gateway.requestClient(clientProfile.uid)
            gateway.synchronize(trainingStore)
            val relationId = relationshipId(trainerProfile.uid, clientProfile.uid)
            assertEquals(RelationshipStatus.PENDING, trainingStore.state.value.relationships.single().status)
            assertTrue(trainingStore.state.value.entries.isEmpty())

            gateway.auth.signOut()
            gateway.signIn(clientEmail, password)
            gateway.synchronize(client)
            gateway.respondRelationship(relationId, accept = true)
            gateway.synchronize(client)
            assertFalse(client.state.value.relationships.single().shareBodyWeight)

            gateway.auth.signOut()
            gateway.signIn(trainerEmail, password)
            gateway.synchronize(trainingStore)
            assertEquals(independentId, activeEntries(trainingStore.state.value.entries).single { it.kind == EntryKind.SESSION }.rootId)
            assertFalse(trainingStore.state.value.entries.any { it.kind == EntryKind.WEIGHT })
            val exercise = ExercisePrescription(name = "Squat", sets = 2, repetitions = 8, loadGrams = 60_000)
            val prescription = WorkoutPlan(clientProfile.uid, "Weekly strength", week, listOf(WorkoutDay(1, listOf(exercise))))
            trainingStore.savePlan(prescription.copy(weekStart = LocalDate.parse(week).minusWeeks(3).toString()))
            gateway.synchronize(trainingStore)
            assertEquals(0, trainingStore.state.value.pendingCount) // The current-week copy uploads in the same pass.
            val original = activeEntries(trainingStore.state.value.entries).single { it.plan?.weekStart == week }
            assertEquals(exercise.id, original.plan!!.days.single().exercises.single().id)
            assertEquals(2, activeEntries(trainingStore.state.value.entries).count { it.kind == EntryKind.PLAN })

            gateway.auth.signOut()
            gateway.signIn(clientEmail, password)
            gateway.synchronize(client)
            assertEquals(original.id, activeEntries(client.state.value.entries).single { it.plan?.weekStart == week }.id)
            client.saveSession(TrainingSession(today, "Weekly strength · Monday", listOf(
                SetResult(exerciseName = "Squat", repetitions = 8, loadGrams = 57_500, prescribedExerciseId = exercise.id),
                SetResult(exerciseName = "Squat", repetitions = 7, loadGrams = 57_500, prescribedExerciseId = exercise.id),
            ), planRevisionId = original.id, dayOfWeek = 1))
            val plannedSession = activeEntries(client.state.value.entries).single { it.session?.planRevisionId == original.id }
            gateway.synchronize(client)
            gateway.setWeightSharing(relationId, enabled = true)
            gateway.synchronize(client)

            gateway.auth.signOut()
            gateway.signIn(trainerEmail, password)
            gateway.synchronize(trainingStore)
            assertEquals(81_250L, activeEntries(trainingStore.state.value.entries).single { it.kind == EntryKind.WEIGHT }.weight!!.grams)
            val received = activeEntries(trainingStore.state.value.entries).single { it.rootId == plannedSession.rootId }
            val recordedSets = requireNotNull(received.session).sets
            assertEquals(listOf(8, 7), recordedSets.map { it.repetitions })
            assertEquals(listOf(57_500L, 57_500L), recordedSets.map { it.loadGrams })
            trainingStore.savePlan(prescription.copy(title = "Updated strength"), original.rootId)
            gateway.synchronize(trainingStore)

            gateway.auth.signOut()
            gateway.signIn(clientEmail, password)
            gateway.synchronize(client)
            assertEquals("Updated strength", activeEntries(client.state.value.entries).single { it.plan?.weekStart == week }.plan!!.title)
            assertEquals(original.id, activeEntries(client.state.value.entries).single { it.rootId == plannedSession.rootId }.session!!.planRevisionId)
            client.saveWeight(BodyWeight(LocalDate.now().plusDays(1).toString(), 80_750))
            val archivePassword = "Synthetic archive password $suffix"
            val fullArchive = client.exportArchive(clientProfile.uid, password = archivePassword)
            val selected = client.previewArchive(client.exportArchive(clientProfile.uid, setOf(plannedSession.rootId), archivePassword), archivePassword)
            assertTrue(selected.manifest.entries.none { it.kind == EntryKind.WEIGHT || it.rootId == independentId })
            assertTrue(selected.manifest.entries.any { it.id == original.id })

            gateway.auth.signOut()
            gateway.signIn(trainerEmail, password)
            val preview = trainingStore.previewArchive(fullArchive, archivePassword)
            assertEquals(1, trainingStore.importArchive(preview).added)
            assertEquals(0, trainingStore.importArchive(preview).added)
            assertEquals(0, trainingStore.state.value.pendingCount) // Foreign import never becomes a trainer-authored upload.

            gateway.auth.signOut()
            gateway.signIn(clientEmail, password)
            gateway.endRelationship(relationId)
            gateway.synchronize(client)
            assertFalse(client.state.value.entries.any { it.kind == EntryKind.PLAN })
            assertEquals(original.id, activeEntries(client.state.value.entries).single { it.rootId == plannedSession.rootId }.session!!.planRevisionId)
            gateway.auth.signOut()
            gateway.signIn(trainerEmail, password)
            gateway.synchronize(trainingStore)
            assertFalse(trainingStore.state.value.entries.any { it.ownerId == clientProfile.uid })
            try {
                trainingStore.previewArchive(fullArchive, archivePassword)
                fail("An ended connection must not authorize a stale history archive.")
            } catch (_: IllegalArgumentException) { /* Expected consent boundary. */ }
        } finally {
            gateway.auth.signOut()
            client.eraseLocal()
            client.close()
            trainer?.eraseLocal()
            trainer?.close()
        }
    }
}
