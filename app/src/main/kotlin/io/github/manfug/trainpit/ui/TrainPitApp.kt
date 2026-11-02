package io.github.manfug.trainpit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.manfug.trainpit.TrainPitController
import io.github.manfug.trainpit.model.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class Destination(val label: String) { OVERVIEW("Overview"), PLANS("Plans"), CONNECTIONS("Connections"), HISTORY("History"), SETTINGS("Settings") }
internal data class PendingSave(val priorIds: Set<String>, val rootId: String?, val plan: WorkoutPlan? = null, val session: TrainingSession? = null, val weight: BodyWeight? = null) {
    fun matches(entry: Entry): Boolean = entry.id !in priorIds && (rootId == null || entry.rootId == rootId) && !entry.deleted &&
        entry.plan == plan && entry.session == session && entry.weight == weight
}
// Deliberately not SavedState: health drafts must never be written to a plaintext state bundle.
internal class UiEditorState : ViewModel() {
    var uid by mutableStateOf<String?>(null)
        private set
    private var current by mutableStateOf<Editor?>(null)
    var pending by mutableStateOf<PendingSave?>(null)
    var error by mutableStateOf<String?>(null)
    var plan: PlanDraft? = null
        private set
    var session: SessionDraft? = null
        private set
    var weight: WeightDraft? = null
        private set
    private var archiveRequest by mutableStateOf<ArchiveRequest?>(null)
    var archiveDraft by mutableStateOf(ArchiveDraft())
        private set
    var archive: ArchiveRequest?
        get() = archiveRequest
        set(value) {
            archiveDraft.close()
            archiveDraft = ArchiveDraft()
            archiveRequest = value
        }
    var editor: Editor?
        get() = current
        set(value) {
            error = null
            plan = (value as? Editor.Plan)?.let(::PlanDraft)
            session = (value as? Editor.Session)?.let(::SessionDraft)
            weight = (value as? Editor.Weight)?.let(::WeightDraft)
            current = value
        }
    fun bind(accountUid: String?) {
        if (uid != accountUid) { editor = null; pending = null; archive = null; uid = accountUid }
    }
    override fun onCleared() { archiveDraft.close() }
}

/** Native entry point; device credential unlocking is handled by the hosting activity. */
@Composable
fun TrainPitApp(controller: TrainPitController) {
    val auth by controller.authState.collectAsState()
    val local by controller.localState.collectAsState()
    val busy by controller.busy.collectAsState()
    val message by controller.message.collectAsState()
    val syncLabel by controller.syncLabel.collectAsState()
    val snackbars = remember { SnackbarHostState() }
    val editing: UiEditorState = viewModel()
    LaunchedEffect(auth.profile?.uid) { editing.bind(auth.profile?.uid) }
    LaunchedEffect(message) {
        message?.let { text ->
            snackbars.showSnackbar(text, actionLabel = "Dismiss", withDismissAction = true, duration = SnackbarDuration.Long)
            if (controller.message.value == text) controller.dismissMessage()
        }
    }
    TrainPitTheme {
        Surface(Modifier.fillMaxSize()) {
            val profile = auth.profile
            if (auth.loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else key(profile?.uid) {
                if (profile == null) Scaffold(snackbarHost = { SnackbarHost(snackbars) }) { padding ->
                    Box(Modifier.padding(padding)) { AuthScreen(controller, busy) }
                } else {
                    val state = if (local.profile?.uid == profile.uid) local else LocalState(profile)
                    if (editing.uid == profile.uid) SignedInApp(controller, profile, state, busy, message, syncLabel, snackbars, editing)
                }
            }
        }
    }
}

@Composable
private fun SignedInApp(controller: TrainPitController, profile: Profile, state: LocalState, busy: Boolean, message: String?, syncLabel: String, snackbars: SnackbarHostState, editing: UiEditorState) {
    var destination by rememberSaveable { mutableStateOf(Destination.OVERVIEW) }
    var selectedClient by rememberSaveable { mutableStateOf<String?>(null) }
    val editor = editing.editor
    var pending by editing::pending
    var archive by editing::archive
    val readable = remember(state.entries, state.relationships, profile) { state.entries.filter { canReadEntry(profile, state.relationships, it) } }
    val heads = remember(readable) { revisionHeads(readable) }
    val records = remember(heads) { heads.values.mapNotNull { it.singleOrNull()?.takeUnless(Entry::deleted) } }
    val subject = if (profile.role == Role.CLIENT) profile.uid else selectedClient
        ?: state.activeRelationships.firstOrNull { it.trainerId == profile.uid }?.clientId
        ?: state.relationships.firstOrNull { it.trainerId == profile.uid && it.status == RelationshipStatus.ENDED }?.clientId
    val conflictCount = remember(heads) { heads.values.count { it.size > 1 } }
    LaunchedEffect(state.entries, pending) {
        val saving = pending
        if (saving != null && state.entries.any { it.ownerId == profile.uid && saving.matches(it) }) { pending = null; editing.editor = null }
    }
    LaunchedEffect(message) {
        if (message != null) { pending = null; if (editor != null) editing.error = message }
    }
    BackHandler(enabled = destination != Destination.OVERVIEW && editor == null && archive == null) { destination = Destination.OVERVIEW }
    val edit: (Entry) -> Unit = { entry ->
        if (entry.ownerId == profile.uid) editing.editor = when (entry.kind) {
            EntryKind.PLAN -> Editor.Plan(entry.subjectId, entry)
            EntryKind.SESSION -> Editor.Session(entry = entry)
            EntryKind.WEIGHT -> Editor.Weight(entry)
        }
    }
    val startSession: (Entry, Int) -> Unit = { entry, day -> if (profile.role == Role.CLIENT) editing.editor = Editor.Session(plan = entry, day = day) }
    val newPlan: (String) -> Unit = { id -> if (profile.role == Role.TRAINER) editing.editor = Editor.Plan(id) }
    fun prepareSave(root: String?): Boolean {
        val original = when (editor) {
            is Editor.Plan -> editor.entry
            is Editor.Session -> editor.entry
            is Editor.Weight -> editor.entry
            null -> null
        }
        if (root != null && heads[root]?.singleOrNull()?.id != original?.id) {
            editing.error = "This record changed while you were editing. Your draft is still here. Cancel and reopen the latest record, or resolve its conflict in History, before editing again."
            return false
        }
        editing.error = null
        controller.dismissMessage()
        return true
    }
    Scaffold(
        topBar = {
            Column(Modifier.statusBarsPadding().fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("TrainPit", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        Text(profile.displayName, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = controller::sync, enabled = !busy) { Text(if (busy) "Working…" else "Sync") }
                }
                Text(syncLabel + " · ${state.pendingCount} pending", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                if (conflictCount > 0) TextButton(onClick = { selectedClient = heads.values.firstOrNull { it.size > 1 }?.firstOrNull()?.subjectId; destination = Destination.HISTORY }) { Text("$conflictCount unresolved record conflict(s) · Review") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()
            }
        },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { item ->
                    NavigationBarItem(selected = destination == item, onClick = { destination = item },
                        icon = { NavigationGlyph(item) }, label = { Text(if (item == Destination.CONNECTIONS) if (profile.role == Role.TRAINER) "Clients" else "Trainers" else item.label, maxLines = 1) })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 1000.dp).fillMaxSize()) {
                when (destination) {
                    Destination.OVERVIEW -> DashboardScreen(profile, state, records, subject, { selectedClient = it }, { editing.editor = Editor.Session() }, { editing.editor = Editor.Weight() }, startSession, newPlan)
                    Destination.PLANS -> PlansScreen(profile, state, records, subject, { selectedClient = it }, edit, newPlan, startSession)
                    Destination.CONNECTIONS -> ConnectionsScreen(profile, state, controller, busy, { selectedClient = it; destination = Destination.OVERVIEW }, newPlan)
                    Destination.HISTORY -> HistoryScreen(profile, state, heads, subject, { selectedClient = it }, controller, busy, edit, { id, roots -> archive = ArchiveRequest.Export(id, roots) })
                    Destination.SETTINGS -> SettingsScreen(profile, state, syncLabel, busy, controller, { archive = ArchiveRequest.Export(it) }, { archive = ArchiveRequest.Import })
                }
            }
        }
    }
    CompositionLocalProvider(LocalOperationMessage provides editing.error) {
        key(editor) {
            when (val open = editor) {
                is Editor.Plan -> PlanEditor(open, requireNotNull(editing.plan), busy || pending != null, { editing.editor = null }, { plan, root ->
                    if (prepareSave(root)) {
                        pending = PendingSave(state.entries.mapTo(HashSet()) { it.id }, root, plan = plan)
                        controller.savePlan(plan, root)
                    }
                })
                is Editor.Session -> SessionEditor(open, requireNotNull(editing.session), busy || pending != null, { editing.editor = null }, { session, root ->
                    if (prepareSave(root)) {
                        pending = PendingSave(state.entries.mapTo(HashSet()) { it.id }, root, session = session)
                        controller.saveSession(session, root)
                    }
                })
                is Editor.Weight -> WeightEditor(open, requireNotNull(editing.weight), busy || pending != null, { editing.editor = null }, { weight, root ->
                    if (prepareSave(root)) {
                        pending = PendingSave(state.entries.mapTo(HashSet()) { it.id }, root, weight = weight)
                        controller.saveWeight(weight, root)
                    }
                })
                null -> Unit
            }
        }
    }
    ArchiveWorkflow(archive, editing.archiveDraft, controller, busy, { archive = null })
}

@Composable
private fun NavigationGlyph(destination: Destination) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val stroke = 1.8.dp.toPx()
        when (destination) {
            Destination.OVERVIEW -> {
                val block = Size(size.width * 0.35f, size.height * 0.35f)
                for (x in 0..1) for (y in 0..1) drawRect(color, Offset(size.width * (0.08f + x * 0.49f), size.height * (0.08f + y * 0.49f)), block, style = Stroke(stroke))
            }
            Destination.PLANS -> {
                drawRect(color, Offset(size.width * 0.15f, size.height * 0.1f), Size(size.width * 0.7f, size.height * 0.8f), style = Stroke(stroke))
                for (row in 0..2) {
                    val y = 0.33f + row * 0.17f
                    drawLine(color, Offset(size.width * 0.3f, size.height * y), Offset(size.width * 0.7f, size.height * y), stroke)
                }
            }
            Destination.CONNECTIONS -> {
                drawCircle(color, size.width * 0.17f, Offset(size.width * 0.5f, size.height * 0.28f), style = Stroke(stroke))
                drawArc(color, 180f, 180f, false, Offset(size.width * 0.15f, size.height * 0.55f), Size(size.width * 0.7f, size.height * 0.6f), style = Stroke(stroke))
            }
            Destination.HISTORY -> {
                drawCircle(color, size.width * 0.4f, center, style = Stroke(stroke))
                drawLine(color, center, Offset(size.width * 0.5f, size.height * 0.24f), stroke)
                drawLine(color, center, Offset(size.width * 0.7f, size.height * 0.6f), stroke)
            }
            Destination.SETTINGS -> {
                for (row in 0..2) {
                    val y = 0.25f + row * 0.25f
                    val x = when (row) { 0 -> 0.35f; 1 -> 0.65f; else -> 0.4f }
                    drawLine(color, Offset(size.width * 0.1f, size.height * y), Offset(size.width * 0.9f, size.height * y), stroke)
                    drawCircle(color, size.width * 0.09f, Offset(size.width * x, size.height * y), style = Stroke(stroke * 1.5f))
                }
            }
        }
    }
}
