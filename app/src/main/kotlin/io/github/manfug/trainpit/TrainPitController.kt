package io.github.manfug.trainpit

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import io.github.manfug.trainpit.data.RoomLocalRepository
import io.github.manfug.trainpit.model.*
import io.github.manfug.trainpit.sync.FirebaseGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AuthState(val loading: Boolean = true, val profile: Profile? = null)

class TrainPitController(application: Application) : AndroidViewModel(application) {
    private val gateway = FirebaseGateway()
    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()
    private val _localState = MutableStateFlow(LocalState())
    val localState: StateFlow<LocalState> = _localState.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _syncLabel = MutableStateFlow("Not synchronized")
    val syncLabel: StateFlow<String> = _syncLabel.asStateFlow()
    private val syncMutex = Mutex()
    private var repository: LocalRepository? = null
    private var observer: Job? = null
    private var foreground: Job? = null
    private var syncJob: Job? = null
    private var pendingActions = 0
    private var visible = false
    private val authListener = FirebaseAuth.AuthStateListener { auth ->
        val profile = _authState.value.profile
        if (profile != null && auth.currentUser?.uid != profile.uid) clearLocalSession()
    }

    init {
        gateway.auth.addAuthStateListener(authListener)
        viewModelScope.launch {
            try {
                val uid = gateway.auth.currentUser?.uid
                if (uid != null) {
                    val cached = withContext(Dispatchers.IO) { RoomLocalRepository.cachedProfile(getApplication(), uid) }
                    attach(cached ?: gateway.profile(uid))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _message.value = readableError(error)
            } finally {
                _authState.value = _authState.value.copy(loading = false)
            }
        }
    }

    private suspend fun attach(profile: Profile) {
        require(gateway.auth.currentUser?.uid == profile.uid) { "The active account changed. Sign in again." }
        observer?.cancel()
        repository?.close()
        repository = null
        val local = withContext(Dispatchers.IO) { RoomLocalRepository.open(getApplication(), profile) }
        if (gateway.auth.currentUser?.uid != profile.uid) {
            local.close()
            throw IllegalStateException("The active account changed. Sign in again.")
        }
        repository = local
        local.carryForwardPlans()
        _localState.value = local.state.value
        _authState.value = AuthState(loading = false, profile = profile)
        observer = viewModelScope.launch { local.state.collect { if (repository === local) _localState.value = it } }
        TrainPitSyncWorker.schedule(getApplication(), profile.uid)
    }

    fun signIn(email: String, password: String) = action {
        attach(gateway.signIn(email, password))
    }
    fun register(name: String, email: String, password: String, role: Role) = action {
        attach(gateway.register(name, email, password, role))
    }
    fun resetPassword(email: String) = action {
        gateway.resetPassword(email)
        _message.value = "If this address is registered, password-reset instructions have been sent."
    }

    fun signOut() {
        clearLocalSession()
        gateway.auth.signOut()
    }

    private fun clearLocalSession() {
        foreground?.cancel()
        syncJob?.cancel()
        observer?.cancel()
        visible = false
        repository?.let { local ->
            TrainPitSyncWorker.cancel(getApplication(), local.profile.uid)
            local.close()
        }
        repository = null
        _localState.value = LocalState()
        _authState.value = AuthState(loading = false)
        _syncLabel.value = "Not synchronized"
        _message.value = null
    }

    fun setVisible(isVisible: Boolean) {
        visible = isVisible
        foreground?.cancel()
        if (isVisible && repository != null) {
            foreground = viewModelScope.launch {
                while (visible && repository != null) {
                    synchronize(manual = false)
                    delay(60_000)
                }
            }
        }
    }

    fun sync() = enqueueSync(manual = true)

    private fun enqueueSync(manual: Boolean) {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch { synchronize(manual) }
    }

    private suspend fun synchronize(manual: Boolean) = syncMutex.withLock {
        val local = repository ?: return@withLock
        try {
            // Calendar rollover is local work and must also run without connectivity.
            local.carryForwardPlans()
            if (!hasNetwork()) {
                _syncLabel.value = "Offline · changes stay on this device"
                if (manual) _message.value = "No connection. Your local records are available and changes will sync later."
                return@withLock
            }
            _syncLabel.value = "Synchronizing…"
            val report = gateway.synchronize(local)
            if (repository !== local) return@withLock
            _syncLabel.value = if (report.blocked > 0) "${report.blocked} plan updates kept locally · connection ended" else "Up to date · checked just now"
            if (manual) _message.value = "Uploaded ${report.uploaded}, received ${report.downloaded}${if (report.blocked > 0) "; ${report.blocked} blocked by ended connections" else ""}."
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            if (repository === local) {
                _syncLabel.value = "Sync unavailable · local changes kept"
                if (manual) _message.value = readableError(error)
            }
        }
    }

    fun requestClient(clientId: String) = cloudAction("Request sent. The client must accept before sharing begins.") { gateway.requestClient(clientId.trim()) }
    fun respondRelationship(id: String, accept: Boolean) = cloudAction(if (accept) "Connection accepted. Body weight stays private unless you enable sharing." else "Request declined.") { gateway.respondRelationship(id, accept) }
    fun endRelationship(id: String) = cloudAction("Connection ended. Previously shared copies cannot be recalled.") { gateway.endRelationship(id) }
    fun setWeightSharing(id: String, enabled: Boolean) = cloudAction(if (enabled) "Body-weight sharing enabled for this trainer." else "New body-weight sharing disabled. Previously shared copies cannot be recalled.") { gateway.setWeightSharing(id, enabled) }

    private fun cloudAction(success: String, operation: suspend () -> Unit) = action {
        require(hasNetwork()) { "Connection changes require internet access. Local training records remain available offline." }
        operation()
        synchronize(manual = false)
        _message.value = success
    }

    fun savePlan(plan: WorkoutPlan, rootId: String? = null) = localAction { savePlan(plan, rootId) }
    fun saveSession(session: TrainingSession, rootId: String? = null) = localAction { saveSession(session, rootId) }
    fun saveWeight(weight: BodyWeight, rootId: String? = null) = localAction { saveWeight(weight, rootId) }
    fun deleteRecord(rootId: String) = localAction("Deletion marker saved on this device.") { deleteRecord(rootId) }
    fun resolveConflict(rootId: String, keepRevisionId: String) = localAction("Conflict resolved with a new revision.") { resolveConflict(rootId, keepRevisionId) }
    fun deleteAllOwnedRecords() = localAction("Deletion markers saved. Historical revisions and previously shared copies are not securely erased.") { deleteAllOwnedRecords() }

    private fun localAction(success: String = "Saved on this device.", operation: suspend LocalRepository.() -> Unit) = action {
        val local = currentRepository()
        withContext(Dispatchers.IO) { local.operation() }
        if (repository === local) {
            _message.value = success
            enqueueSync(manual = false)
        }
    }

    suspend fun exportArchive(subjectId: String, sessionRootIds: Set<String>? = null, password: String): ByteArray {
        val local = currentRepository()
        return withContext(Dispatchers.IO) { local.exportArchive(subjectId, sessionRootIds, password) }
    }
    suspend fun previewArchive(bytes: ByteArray, password: String): ArchivePreview {
        val local = currentRepository()
        return withContext(Dispatchers.IO) { local.previewArchive(bytes, password) }
    }
    fun importArchive(preview: ArchivePreview) = action {
        val local = currentRepository()
        val result = withContext(Dispatchers.IO) { local.importArchive(preview) }
        _message.value = "Imported ${result.added}; ${result.duplicates} already present; ${result.conflicts} unresolved conflicts."
        enqueueSync(manual = false)
    }
    fun eraseLocalData() = action {
        val profile = currentRepository().profile
        // Retain the store while the Auth listener releases this controller's reference.
        val local = withContext(Dispatchers.IO) { RoomLocalRepository.open(getApplication(), profile) }
        try {
            syncJob?.cancel()
            foreground?.cancel()
            TrainPitSyncWorker.cancel(getApplication(), profile.uid)
            gateway.auth.signOut()
            syncMutex.withLock { withContext(Dispatchers.IO) { local.eraseLocal() } }
        } finally {
            signOut()
            local.close()
        }
    }
    fun dismissMessage() { _message.value = null }

    private fun currentRepository(): LocalRepository = requireNotNull(repository) { "Sign in to access your local records." }
    private fun hasNetwork(): Boolean {
        val manager = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            pendingActions++
            _busy.value = true
            try { block() }
            catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                _message.value = readableError(error)
            } finally {
                pendingActions--
                _busy.value = pendingActions > 0
            }
        }
    }
    private fun readableError(error: Exception): String = when (error) {
        is TimeoutCancellationException -> "The service did not respond. Local changes are kept; check your connection and try again."
        is FirebaseAuthException -> when (error.errorCode) {
            "ERROR_INVALID_EMAIL" -> "Enter a valid email address."
            "ERROR_WEAK_PASSWORD" -> "Use a stronger password with at least 10 characters."
            "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Wait before trying again."
            "ERROR_NETWORK_REQUEST_FAILED" -> "No connection. Account setup and sign-in need internet access."
            else -> "Sign-in failed. Check your email and password, or use password reset."
        }
        is java.time.DateTimeException -> "Enter a valid calendar date in YYYY-MM-DD format."
        is IllegalArgumentException, is IllegalStateException -> error.message?.take(240) ?: "The supplied information is invalid."
        else -> "The operation could not finish. Check your connection and access permissions. Local records have not been discarded."
    }

    override fun onCleared() {
        gateway.auth.removeAuthStateListener(authListener)
        observer?.cancel()
        foreground?.cancel()
        syncJob?.cancel()
        repository?.close()
    }
}
