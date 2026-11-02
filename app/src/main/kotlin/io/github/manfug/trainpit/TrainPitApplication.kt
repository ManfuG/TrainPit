package io.github.manfug.trainpit

import android.app.Application
import com.google.firebase.auth.FirebaseAuth
import io.github.manfug.trainpit.sync.FirebaseEnvironment

class TrainPitApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseEnvironment.initialize(this, BuildConfig.FIREBASE_EMULATORS)
        FirebaseAuth.getInstance().currentUser?.uid?.let { TrainPitSyncWorker.schedule(this, it) }
    }
}
