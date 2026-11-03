package io.github.manfug.trainpit

import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.FirebaseFirestore
import io.github.manfug.trainpit.data.RoomLocalRepository
import io.github.manfug.trainpit.model.*
import io.github.manfug.trainpit.sync.FirebaseGateway
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SlotSynchronizationTest {
    @Test fun crossDeviceDailyConflictSurvivesCloudRecoveryAndEncryptedArchiveReplay() = runBlocking {
        check(BuildConfig.FIREBASE_EMULATORS) { "Production fixtures are forbidden." }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val gateway = FirebaseGateway()
        gateway.auth.signOut()
        val suffix = UUID.randomUUID().toString()
        val profile = gateway.register("Slot test", "slots-$suffix@example.invalid", "Synthetic-$suffix", Role.CLIENT)
        var local = RoomLocalRepository.open(context, profile)
        try {
            local.saveWeight(BodyWeight("2026-11-02", 75_000))
            gateway.synchronize(local)
            val original = activeEntries(local.state.value.entries).single()
            // A second owner-enrolled device represents an offline/legacy first save.
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
            val deviceId = MessageDigest.getInstance("SHA-256").digest(pair.public.encoded).joinToString("") { "%02x".format(it) }
            val device = DeviceKey(profile.uid, deviceId, publicKey)
            Tasks.await(FirebaseFirestore.getInstance().collection("profiles").document(profile.uid).collection("devices").document(deviceId)
                .set(mapOf("ownerId" to profile.uid, "deviceId" to deviceId, "publicKey" to publicKey, "revoked" to false)))
            val id = newId()
            val unsigned = Entry(id = id, rootId = id, ownerId = profile.uid, subjectId = profile.uid,
                kind = EntryKind.WEIGHT, deviceId = deviceId, weight = BodyWeight("2026-11-02", 74_500))
            val signed = unsigned.copy(signature = Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private); update(unsigned.signingBytes()); sign()
            }))
            gateway.synchronize(local)
            local.importArchive(ArchivePreview(ArchiveManifest(exporterId = profile.uid, subjectId = profile.uid,
                selectedSessions = false, entries = listOf(signed), devices = listOf(device))))
            assertTrue(activeEntries(local.state.value.entries).isEmpty())
            local.resolveConflict(original.rootId, signed.id)
            val chosen = activeEntries(local.state.value.entries).single()
            assertEquals(2, chosen.schemaVersion)
            assertEquals(listOf(original.id), chosen.slotParents)
            gateway.synchronize(local)
            assertEquals(0, local.state.value.pendingCount)
            val password = "Synthetic archive $suffix"
            val archive = local.exportArchive(profile.uid, password = password)
            local.eraseLocal()
            local.close()
            local = RoomLocalRepository.open(context, profile)
            gateway.synchronize(local)
            assertEquals(chosen, activeEntries(local.state.value.entries).single())
            assertEquals(setOf(original.id, signed.id, chosen.id), local.state.value.entries.map { it.id }.toSet())
            assertEquals(0, local.importArchive(local.previewArchive(archive, password)).added)
            local.saveWeight(BodyWeight("2026-11-02", 74_250))
            gateway.synchronize(local)
            val corrected = activeEntries(local.state.value.entries).single()
            assertEquals(chosen.rootId, corrected.rootId)
            assertEquals(chosen.slotParents, corrected.slotParents)
            assertEquals(74_250L, corrected.weight!!.grams)
            assertEquals(0, local.state.value.pendingCount)
        } finally {
            gateway.auth.signOut()
            local.eraseLocal()
            local.close()
        }
    }
}
