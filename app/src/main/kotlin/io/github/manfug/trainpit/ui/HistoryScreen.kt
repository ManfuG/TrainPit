package io.github.manfug.trainpit.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.manfug.trainpit.TrainPitController
import io.github.manfug.trainpit.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun HistoryScreen(profile: Profile, state: LocalState, heads: Map<String, List<Entry>>, subject: String?, selectSubject: (String) -> Unit, controller: TrainPitController, busy: Boolean, edit: (Entry) -> Unit, exportSelected: (String, Set<String>) -> Unit) {
    var kind by remember { mutableStateOf<EntryKind?>(null) }
    var showDeleted by remember { mutableStateOf(false) }
    var selected by remember(subject) { mutableStateOf(setOf<String>()) }
    var detailRoot by remember(subject) { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Entry?>(null) }
    var resolving by remember { mutableStateOf<Entry?>(null) }
    val groups = remember(heads, subject, kind, showDeleted) {
        heads.values.filter { branches -> branches.any { it.subjectId == subject && (kind == null || it.kind == kind) } && (showDeleted || branches.size > 1 || branches.any { !it.deleted }) }
            .sortedWith(compareByDescending<List<Entry>> { group -> group.maxOf(::recordDate) }.thenBy { it.first().rootId })
    }
    val selectedRoots = remember(selected, heads, subject) { selected.filterTo(HashSet()) { root -> heads[root]?.singleOrNull()?.let { !it.deleted && it.kind == EntryKind.SESSION && it.subjectId == subject } == true } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionTitle("History & conflicts", "Only record owners can edit, delete or resolve conflicting revisions. Trainer monitoring of client sessions is read-only.") }
        item { SubjectSelector(profile, state.relationships, subject, selectSubject, includeEnded = true) }
        item {
            Choice("Records", kind?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "All", listOf("All", "Plans", "Sessions", "Body weight")) { kind = when (it) { 1 -> EntryKind.PLAN; 2 -> EntryKind.SESSION; 3 -> EntryKind.WEIGHT; else -> null } }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Show deleted records", Modifier.weight(1f))
                Switch(showDeleted, { showDeleted = it }, modifier = Modifier.semantics { contentDescription = "Show deleted records" })
            }
        }
        if (selectedRoots.isNotEmpty() && subject != null) item {
            InfoCard("${selectedRoots.size} session(s) selected") {
                Button(onClick = { exportSelected(subject, selectedRoots.toSet()) }, enabled = !busy) { Text("Export selected sessions") }
                TextButton(onClick = { selected = emptySet() }) { Text("Clear selection") }
            }
        }
        if (groups.isEmpty()) item { EmptyState("No records to show", "Choose a client or record type, sync, or record your first session. Conflicted records appear here for explicit review.") }
        items(groups, key = { it.first().rootId }) { branches ->
            val entry = branches.first()
            InfoCard(if (branches.size > 1) "Conflict · ${recordTitle(entry)}" else recordTitle(entry)) {
                Text("${recordDate(entry)} · ${entry.kind.name.lowercase()}", color = MaterialTheme.colorScheme.primary)
                if (branches.size > 1) Text("${branches.size} competing signed revisions. No branch is included in statistics until the owner resolves this conflict.", color = MaterialTheme.colorScheme.error)
                else if (entry.deleted) Text("Deleted · retained as signed deletion evidence")
                else when (entry.kind) {
                    EntryKind.SESSION -> Text("${entry.session!!.sets.size} completed sets")
                    EntryKind.WEIGHT -> Text("${formatKilograms(entry.weight!!.grams)} kg")
                    EntryKind.PLAN -> Text("Week of ${entry.plan!!.weekStart}")
                }
                Text(if (entry.ownerId == profile.uid) "Owned by you" else "Read-only · owned by ${entry.ownerId}", style = MaterialTheme.typography.bodySmall)
                if (branches.size == 1 && !entry.deleted && entry.kind == EntryKind.SESSION) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Include in session export", Modifier.weight(1f))
                    Checkbox(entry.rootId in selectedRoots, { checked -> selected = if (checked) selected + entry.rootId else selected - entry.rootId }, modifier = Modifier.semantics { contentDescription = "Include ${recordTitle(entry)} on ${recordDate(entry)} in session export" })
                }
                OutlinedButton(onClick = { detailRoot = entry.rootId }) { Text(if (branches.size > 1) "Review conflict" else "View details & revisions") }
            }
        }
    }
    detailRoot?.let { root ->
        val branches = heads[root].orEmpty()
        val revisions = remember(state.entries, root) { state.entries.filter { it.rootId == root }.sortedByDescending { it.createdAt } }
        Dialog(onDismissRequest = { detailRoot = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth().fillMaxHeight(0.95f), shape = MaterialTheme.shapes.large) {
                LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        SectionTitle(if (branches.size > 1) "Resolve competing revisions" else "Record details")
                        TextButton(onClick = { detailRoot = null }) { Text("Close") }
                        Text("Record ID: $root", style = MaterialTheme.typography.bodySmall)
                        if (branches.size > 1) Text("Review every branch. Choosing one creates a new signed revision descending from every competing head. A deleted branch keeps the record deleted.")
                    }
                    items(branches, key = { "head-${it.id}" }) { entry ->
                        InfoCard(if (branches.size > 1) "Competing revision ${entry.id.take(8)}" else recordTitle(entry)) {
                            RecordContent(entry)
                            RevisionMetadata(entry)
                            if (entry.ownerId == profile.uid) {
                                if (branches.size > 1) Button(onClick = { resolving = entry }, enabled = !busy) { Text(if (entry.deleted) "Keep deletion" else "Keep this version") }
                                else if (!entry.deleted) {
                                    if (entry.kind != EntryKind.PLAN || state.activeRelationships.any { it.trainerId == profile.uid && it.clientId == entry.subjectId }) OutlinedButton(onClick = { detailRoot = null; edit(entry) }, enabled = !busy) { Text("Edit record") }
                                    TextButton(onClick = { deleting = entry }, enabled = !busy) { Text("Delete record") }
                                }
                            } else Text("Read-only. Only the owner can change this record or resolve its conflicts.")
                        }
                    }
                    item { Text("Signed revision history (${revisions.size})", style = MaterialTheme.typography.titleMedium) }
                    items(revisions, key = { "revision-${it.id}" }) { entry ->
                        InfoCard(if (entry.deleted) "Deletion revision" else recordTitle(entry)) { RecordContent(entry); RevisionMetadata(entry) }
                    }
                }
            }
        }
    }
    deleting?.let { entry -> ConfirmDialog("Delete this record?", "This hides the record using a signed deletion marker that synchronizes to authorized devices. Historical revisions and cloud backups are not securely erased. Previously shared or exported copies cannot be recalled. Records owned by other people are unchanged.", "Delete record", { deleting = null }, { controller.deleteRecord(entry.rootId); deleting = null; detailRoot = null }) }
    resolving?.let { entry -> ConfirmDialog("Keep this ${if (entry.deleted) "deletion" else "version"}?", "Other competing heads will be merged as parents of a new revision. ${if (entry.deleted) "The record will remain deleted." else "The displayed values will become the active version."} This is an explicit owner decision, not a timestamp-based winner.", "Resolve conflict", { resolving = null }, { controller.resolveConflict(entry.rootId, entry.id); resolving = null; detailRoot = null }) }
}

@Composable
private fun RevisionMetadata(entry: Entry) {
    val timestamp = remember(entry.createdAt) { Instant.ofEpochMilli(entry.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }
    Text("Revision: ${entry.id}\nOwner: ${entry.ownerId}\nDevice: ${entry.deviceId}\nCreated locally: $timestamp\nParent revisions: ${entry.parents.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RecordContent(entry: Entry) {
    Text(recordTitle(entry), style = MaterialTheme.typography.titleMedium)
    if (entry.deleted) Text("This revision marks the record deleted.", color = MaterialTheme.colorScheme.error)
    entry.plan?.let { plan ->
        Text("Week of ${plan.weekStart} · client ${plan.clientId}")
        plan.days.sortedBy { it.dayOfWeek }.forEach { day ->
            Text(dayName(day.dayOfWeek), style = MaterialTheme.typography.titleSmall)
            day.exercises.forEach { e -> Text("${e.name}: ${e.sets} × ${e.repetitions} reps" + (e.loadGrams?.let { " · ${formatKilograms(it)} kg" } ?: " · unspecified load")) }
        }
    }
    entry.session?.let { session ->
        Text("Training date: ${session.date}")
        session.sets.forEachIndexed { i, set -> Text("${i + 1}. ${set.exerciseName} · ${set.repetitions} reps · ${formatKilograms(set.loadGrams)} kg") }
        session.planRevisionId?.let { Text("Original plan revision: $it", style = MaterialTheme.typography.bodySmall) }
    }
    entry.weight?.let { Text("${it.date} · ${formatKilograms(it.grams)} kg") }
}
