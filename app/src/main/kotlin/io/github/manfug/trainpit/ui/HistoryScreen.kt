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
    var selected by remember(subject) { mutableStateOf(setOf<String>()) }
    var detailRoot by remember(subject) { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Entry?>(null) }
    var resolving by remember { mutableStateOf<Entry?>(null) }
    val groups = remember(heads, subject, kind, profile, state.relationships) {
        heads.values.filter { branches ->
            branches.isNotEmpty() && branches.all { it.subjectId == subject && canReadEntry(profile, state.relationships, it) } &&
                (kind == null || branches.first().kind == kind) && (branches.size > 1 || branches.any { !it.deleted })
        }.sortedWith(compareByDescending<List<Entry>> { group -> group.maxOf(::recordDate) }.thenBy { it.first().rootId })
    }
    val selectedRoots = remember(selected, heads, subject, profile, state.relationships) {
        heads.values.mapNotNullTo(HashSet()) { branches ->
            branches.singleOrNull()?.takeIf { !it.deleted && it.kind == EntryKind.SESSION && it.subjectId == subject && it.rootId in selected && canReadEntry(profile, state.relationships, it) }?.rootId
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionTitle("History & stats") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (profile.role == Role.TRAINER) SubjectSelector(profile, state.relationships, subject, selectSubject, includeEnded = true, modifier = Modifier.weight(1f))
                Choice("Records", when (kind) { EntryKind.PLAN -> "Plans"; EntryKind.SESSION -> "Sessions"; EntryKind.WEIGHT -> "Body weight"; null -> "All" },
                    listOf("All", "Plans", "Sessions", "Body weight"), modifier = Modifier.weight(1f)) {
                    kind = when (it) { 1 -> EntryKind.PLAN; 2 -> EntryKind.SESSION; 3 -> EntryKind.WEIGHT; else -> null }
                }
            }
        }
        if (selectedRoots.isNotEmpty() && subject != null) item {
            InfoCard("${selectedRoots.size} ${if (selectedRoots.size == 1) "session" else "sessions"} selected") {
                Button(onClick = { exportSelected(subject, selectedRoots.toSet()) }, enabled = !busy) { Text("Export selected sessions") }
                TextButton(onClick = { selected = emptySet() }) { Text("Clear selection") }
            }
        }
        if (groups.isEmpty()) item { InfoCard("No records to show") {} }
        items(groups, key = { it.first().rootId }) { branches ->
            val entry = branches.first()
            RecordCard(entry, recordTitle(entry)) {
                Text(if (entry.kind == EntryKind.PLAN) "Week of ${displayDate(recordDate(entry))}" else displayDate(recordDate(entry)))
                if (branches.size > 1) Text("${branches.size} versions need review. This record is not included in charts until the conflict is resolved.")
                else when (entry.kind) {
                    EntryKind.SESSION -> Text("${entry.session!!.sets.size} completed ${if (entry.session!!.sets.size == 1) "set" else "sets"}")
                    EntryKind.WEIGHT -> Text("${formatKilograms(entry.weight!!.grams)} kg")
                    EntryKind.PLAN -> Text("${entry.plan!!.days.size} training ${if (entry.plan!!.days.size == 1) "day" else "days"}")
                }
                if (entry.ownerId != profile.uid) Text("Read-only", style = MaterialTheme.typography.bodySmall)
                if (branches.size == 1 && !entry.deleted && entry.kind == EntryKind.SESSION) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Include in session export", Modifier.weight(1f))
                    Checkbox(entry.rootId in selectedRoots, { checked -> selected = if (checked) selected + entry.rootId else selected - entry.rootId }, modifier = Modifier.semantics { contentDescription = "Include ${recordTitle(entry)} on ${displayDate(recordDate(entry))} in session export" })
                }
                OutlinedButton(onClick = { detailRoot = entry.rootId }, colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalContentColor.current)) { Text(if (branches.size > 1) "Review conflict" else "View details") }
            }
        }
    }
    detailRoot?.let { root ->
        val branches = remember(heads, root, profile, state.relationships) {
            heads.values.firstOrNull { group -> group.any { it.rootId == root } }
                ?.takeIf { group -> group.all { canReadEntry(profile, state.relationships, it) } }
                .orEmpty().sortedWith(compareBy<Entry> { it.createdAt }.thenBy { it.id })
        }
        val earlier = remember(state.entries, branches, profile, state.relationships) {
            val roots = branches.mapTo(HashSet()) { it.rootId }
            val current = branches.mapTo(HashSet()) { it.id }
            state.entries.filter { it.rootId in roots && it.id !in current && canReadEntry(profile, state.relationships, it) }
                .sortedWith(compareByDescending<Entry> { it.createdAt }.thenBy { it.id })
        }
        val trends = remember(branches, heads, profile, state.relationships) {
            branches.singleOrNull()?.takeUnless { it.deleted }?.let { entry ->
                entry.session?.let { exerciseTrends(entry.subjectId, it, heads, profile, state.relationships) }
            }.orEmpty()
        }
        Dialog(onDismissRequest = { detailRoot = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth().fillMaxHeight(0.95f), shape = MaterialTheme.shapes.large) {
                LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        SectionTitle(if (branches.size > 1) "Choose a version" else "Record details")
                        TextButton(onClick = { detailRoot = null }) { Text("Close") }
                        if (branches.size > 1) Text("Review each version. Keeping one saves your choice without erasing the correction history. Keeping a deletion leaves the record deleted.")
                    }
                    items(branches, key = { "head-${it.id}" }) { entry ->
                        val index = branches.indexOf(entry) + 1
                        RecordCard(entry, if (branches.size > 1) "Version $index · ${recordTitle(entry)}" else recordTitle(entry)) {
                            RecordContent(entry)
                            if (entry.ownerId == profile.uid) {
                                if (branches.size > 1) Button(onClick = { resolving = entry }, enabled = !busy) { Text(if (entry.deleted) "Keep deletion" else "Keep this version") }
                                else if (!entry.deleted) {
                                    if (entry.kind != EntryKind.PLAN || state.activeRelationships.any { it.trainerId == profile.uid && it.clientId == entry.subjectId }) OutlinedButton(onClick = { detailRoot = null; edit(entry) }, enabled = !busy, colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalContentColor.current)) { Text("Edit record") }
                                    TextButton(onClick = { deleting = entry }, enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)) { Text("Delete record") }
                                }
                            } else Text("Read-only")
                        }
                    }
                    if (trends.isNotEmpty()) item {
                        SectionTitle("Exercise trends")
                    }
                    items(trends, key = { it.name }) { trend ->
                        val entry = branches.single()
                        RecordCard(entry, trend.name) {
                            if (trend.separatePrescription) Text("Same-name planned exercises are kept separate.", style = MaterialTheme.typography.bodySmall)
                            Text("Maximum load per day", style = MaterialTheme.typography.titleSmall)
                            TrendChart(trend.maxLoad, "kg")
                            HorizontalDivider()
                            Text("Total repetitions per day", style = MaterialTheme.typography.titleSmall)
                            TrendChart(trend.totalRepetitions, "reps")
                        }
                    }
                    if (earlier.isNotEmpty()) item { Text("Earlier versions (${earlier.size})", style = MaterialTheme.typography.titleMedium) }
                    items(earlier, key = { "revision-${it.id}" }) { entry ->
                        RecordCard(entry, recordTitle(entry)) { RecordContent(entry); CorrectionMetadata(entry) }
                    }
                }
            }
        }
    }
    deleting?.let { entry -> ConfirmDialog("Delete this record?", "This hides the record on your connected devices. Correction history and cloud backups are not securely erased. Previously shared or exported copies cannot be recalled. Other people's records are unchanged.", "Delete record", { deleting = null }, { controller.deleteRecord(entry.rootId); deleting = null; detailRoot = null }) }
    resolving?.let { entry -> ConfirmDialog("Keep this ${if (entry.deleted) "deletion" else "version"}?", "${if (entry.deleted) "The record will remain deleted." else "These values will become the current version."} Other versions remain in the correction history. Your choice resolves the conflict; the latest save time does not choose a winner.", "Resolve conflict", { resolving = null }, { controller.resolveConflict(entry.rootId, entry.id); resolving = null; detailRoot = null }) }
}

@Composable
private fun RecordCard(entry: Entry, title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (container, foreground) = when (entry.kind) {
        EntryKind.PLAN -> colors.primaryContainer to colors.onPrimaryContainer
        EntryKind.SESSION -> colors.secondaryContainer to colors.onSecondaryContainer
        EntryKind.WEIGHT -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container, contentColor = foreground)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(when (entry.kind) { EntryKind.PLAN -> "Plan"; EntryKind.SESSION -> "Session"; EntryKind.WEIGHT -> "Body weight" }, style = MaterialTheme.typography.labelMedium)
            if (entry.kind != EntryKind.WEIGHT || title != "Body weight") Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun CorrectionMetadata(entry: Entry) {
    val timestamp = remember(entry.createdAt) {
        val time = Instant.ofEpochMilli(entry.createdAt).atZone(ZoneId.systemDefault())
        "${displayDate(time.toLocalDate().toString())} at ${time.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }
    val change = when {
        entry.deleted -> "Deleted"
        entry.parents.isEmpty() -> "First saved"
        entry.parents.size > 1 -> "Conflict resolved"
        else -> "Corrected"
    }
    Text("$change · $timestamp", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun RecordContent(entry: Entry) {
    if (entry.deleted) {
        Text("This version deletes the record.")
        return
    }
    entry.plan?.let { plan ->
        Text("Week of ${displayDate(plan.weekStart)}")
        plan.days.sortedBy { it.dayOfWeek }.forEach { day ->
            Text(dayName(day.dayOfWeek), style = MaterialTheme.typography.titleSmall)
            day.exercises.forEach { e -> Text("${e.name}: ${e.sets} × ${e.repetitions} reps" + (e.loadGrams?.let { " · ${formatKilograms(it)} kg" } ?: " · no target load")) }
        }
    }
    entry.session?.let { session ->
        Text(displayDate(session.date))
        session.sets.forEachIndexed { i, set -> Text("${i + 1}. ${set.exerciseName} · ${set.repetitions} reps · ${formatKilograms(set.loadGrams)} kg") }
    }
    entry.weight?.let { Text("${displayDate(it.date)} · ${formatKilograms(it.grams)} kg") }
}
