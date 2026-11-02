package io.github.manfug.trainpit

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.manfug.trainpit.data.RoomLocalRepository
import io.github.manfug.trainpit.model.*
import io.github.manfug.trainpit.sync.FirebaseGateway
import java.util.UUID
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountSessionTest {
    @Test fun externalSignOutClearsVisibleClientDataWithoutDeletingOwnedHistory() = runBlocking {
        check(BuildConfig.FIREBASE_EMULATORS) { "Production fixtures are forbidden." }
        val application = ApplicationProvider.getApplicationContext<Application>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val gateway = FirebaseGateway()
        val unique = UUID.randomUUID().toString()
        val profile = gateway.register("Session test", "session-$unique@example.invalid", "Synthetic-$unique", Role.CLIENT)
        val local = RoomLocalRepository.open(application, profile)
        local.saveWeight(BodyWeight("2026-11-02", 75_250))
        val store = ViewModelStore()
        lateinit var controller: TrainPitController
        try {
            instrumentation.runOnMainSync {
                controller = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[TrainPitController::class.java]
            }
            withTimeout(15_000) { controller.authState.filter { it.profile?.uid == profile.uid }.first() }
            withTimeout(15_000) { controller.localState.filter { it.entries.any { e -> e.weight?.grams == 75_250L } }.first() }
            // A second activity/session can sign Firebase out; every existing view must lock down.
            gateway.auth.signOut()
            withTimeout(15_000) { controller.authState.filter { !it.loading && it.profile == null }.first() }
            assertNull(controller.localState.value.profile)
            assertTrue(controller.localState.value.entries.isEmpty())
            assertEquals(75_250L, activeEntries(local.state.value.entries).single().weight!!.grams)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            TrainPitSyncWorker.cancel(application, profile.uid)
            gateway.auth.signOut()
            local.eraseLocal()
            local.close()
        }
    }

    @Test fun settingsErasureSurvivesTheAuthListenerClosingItsStoreReference() = runBlocking {
        check(BuildConfig.FIREBASE_EMULATORS) { "Production fixtures are forbidden." }
        val application = ApplicationProvider.getApplicationContext<Application>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val gateway = FirebaseGateway()
        val unique = UUID.randomUUID().toString()
        val profile = gateway.register("Erasure test", "erase-$unique@example.invalid", "Synthetic-$unique", Role.CLIENT)
        val initial = RoomLocalRepository.open(application, profile)
        initial.saveWeight(BodyWeight("2026-11-02", 73_500))
        initial.close()
        val store = ViewModelStore()
        lateinit var controller: TrainPitController
        try {
            instrumentation.runOnMainSync {
                controller = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[TrainPitController::class.java]
            }
            withTimeout(15_000) { controller.authState.filter { it.profile?.uid == profile.uid }.first() }
            instrumentation.runOnMainSync { controller.eraseLocalData() }
            withTimeout(15_000) { controller.authState.filter { !it.loading && it.profile == null }.first() }
            withTimeout(15_000) { controller.busy.filter { !it }.first() }
            assertNull(gateway.auth.currentUser)
            assertNull(RoomLocalRepository.cachedProfile(application, profile.uid))
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            TrainPitSyncWorker.cancel(application, profile.uid)
            gateway.auth.signOut()
            RoomLocalRepository.cachedProfile(application, profile.uid)?.let { remaining ->
                val cleanup = RoomLocalRepository.open(application, remaining)
                try { cleanup.eraseLocal() } finally { cleanup.close() }
            }
        }
    }
}
