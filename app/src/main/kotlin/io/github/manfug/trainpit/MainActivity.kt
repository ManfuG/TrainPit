package io.github.manfug.trainpit

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.manfug.trainpit.ui.TrainPitApp

class MainActivity : ComponentActivity() {
    private val controller by lazy { ViewModelProvider(this)[TrainPitController::class.java] }
    private var unlockedUid by mutableStateOf<String?>(null)
    private var unlockPrompt by mutableIntStateOf(0)
    private var backgroundAt = 0L
    private var credentialInFlight = false
    private var credentialUid: String? = null
    private val credentialLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        credentialInFlight = false
        val requestedUid = credentialUid
        credentialUid = null
        if (result.resultCode == Activity.RESULT_OK && requestedUid != null && requestedUid == controller.authState.value.profile?.uid) {
            unlockedUid = requestedUid
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            val auth by controller.authState.collectAsStateWithLifecycle()
            val appState = rememberSaveableStateHolder()
            val uid = auth.profile?.uid
            LaunchedEffect(uid, unlockPrompt) {
                if (uid == null) unlockedUid = null
                else if (unlockedUid != uid) requestUnlock()
            }
            val unlocked = uid != null && unlockedUid == uid
            LaunchedEffect(unlocked, uid) {
                controller.setVisible(unlocked && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            }
            when {
                auth.loading -> MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                        }
                    }
                }
                uid == null || unlocked -> appState.SaveableStateProvider(uid ?: "authentication") { TrainPitApp(controller) }
                else -> MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                        ) {
                            Text("TrainPit", style = MaterialTheme.typography.headlineLarge)
                            Text("Unlock your records", style = MaterialTheme.typography.titleLarge)
                            Text("Client information is protected by your phone's PIN, password or pattern. TrainPit locks again after 30 seconds in the background.")
                            val secure = getSystemService(KeyguardManager::class.java).isDeviceSecure
                            Button(onClick = {
                                if (secure) requestUnlock()
                                else startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                            }) { Text(if (secure) "Unlock with screen lock" else "Set a device screen lock") }
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun requestUnlock() {
        if (credentialInFlight) return
        val uid = controller.authState.value.profile?.uid ?: return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) return
        val intent = keyguard.createConfirmDeviceCredentialIntent("Unlock TrainPit", "Access your private training records") ?: return
        credentialUid = uid
        credentialInFlight = true
        credentialLauncher.launch(intent)
    }

    override fun onStart() {
        super.onStart()
        if (backgroundAt != 0L && SystemClock.elapsedRealtime() - backgroundAt >= 30_000) unlockedUid = null
        unlockPrompt++
    }
    override fun onResume() {
        super.onResume()
        val uid = controller.authState.value.profile?.uid
        controller.setVisible(uid != null && unlockedUid == uid)
    }
    override fun onStop() {
        controller.setVisible(false)
        backgroundAt = SystemClock.elapsedRealtime()
        super.onStop()
    }
}
