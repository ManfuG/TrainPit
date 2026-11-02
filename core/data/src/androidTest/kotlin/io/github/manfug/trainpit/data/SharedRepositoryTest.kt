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
