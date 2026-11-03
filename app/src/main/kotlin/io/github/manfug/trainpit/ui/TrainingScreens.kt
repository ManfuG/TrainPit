package io.github.manfug.trainpit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.manfug.trainpit.model.*
import kotlinx.coroutines.delay
import java.time.LocalDate

@Composable
internal fun SubjectSelector(profile: Profile, relationships: List<Relationship>, selected: String?, onSelect: (String) -> Unit, includeEnded: Boolean = false) {
    if (profile.role != Role.TRAINER) return
    val clients = remember(relationships, profile.uid, includeEnded) { relationships.filter { it.trainerId == profile.uid && (it.status == RelationshipStatus.ACTIVE || includeEnded && it.status == RelationshipStatus.ENDED) }.sortedBy { it.clientName.lowercase() } }
    if (clients.isEmpty()) {
        Text("Connect with a client to view their training.", style = MaterialTheme.typography.bodyMedium)
    } else Choice("Client", clients.firstOrNull { it.clientId == selected }?.let { it.clientName + if (it.status == RelationshipStatus.ENDED) " (ended)" else "" } ?: "Choose a client", clients.map { it.clientName + if (it.status == RelationshipStatus.ENDED) " (ended)" else "" }) { onSelect(clients[it].clientId) }
}

@Composable
internal fun DashboardScreen(profile: Profile, state: LocalState, records: List<Entry>, subject: String?, selectSubject: (String) -> Unit, newWeight: () -> Unit, startSession: (Entry, Int) -> Unit) {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            today = LocalDate.now()
        }
    }
    val plans = remember(records, subject, today) {
        val week = monday(today).toString()
        records.filter { entry ->
            val plan = entry.plan
            entry.subjectId == subject && plan?.weekStart == week && plan.days.any { it.dayOfWeek == today.dayOfWeek.value }
        }
    }
    val weights = remember(records, subject) {
        records.mapNotNull { if (it.subjectId == subject) it.weight else null }.sortedBy { it.date }.takeLast(20)
    }
    val weightAllowed = profile.role == Role.CLIENT || state.activeRelationships.any { it.clientId == subject && it.trainerId == profile.uid && it.shareBodyWeight }
    ScrollPage {
        SectionTitle(if (profile.role == Role.CLIENT) "Your training" else "Client overview")
        SubjectSelector(profile, state.relationships, subject, selectSubject)
        if (profile.role == Role.TRAINER && subject == null) {
            Text("No client selected")
        } else {
            if (plans.isEmpty()) Text("No workout today")
            plans.forEach { entry ->
                val plan = requireNotNull(entry.plan)
                val day = plan.days.first { it.dayOfWeek == today.dayOfWeek.value }
                OutlinedCard(
                    onClick = { startSession(entry, day.dayOfWeek) },
                    enabled = profile.role == Role.CLIENT && entry.subjectId == profile.uid,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(plan.title, style = MaterialTheme.typography.titleMedium)
                        Text("${dayName(day.dayOfWeek)} · $today", color = MaterialTheme.colorScheme.primary)
                        day.exercises.forEach { exercise ->
                            HorizontalDivider()
                            Text(exercise.name, style = MaterialTheme.typography.titleSmall)
                            Text("${exercise.sets} × ${exercise.repetitions} reps" + (exercise.loadGrams?.let { " · ${formatKilograms(it)} kg" } ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (weightAllowed) OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Body-weight trend", style = MaterialTheme.typography.titleMedium)
                        if (profile.role == Role.CLIENT) IconButton(onClick = newWeight, modifier = Modifier.semantics { contentDescription = "Add today's body weight" }) {
                            Text("+", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                    if (weights.isEmpty()) Text("No measurements yet") else WeightChart(weights)
                }
            }
        }
    }
}

@Composable
private fun WeightChart(weights: List<BodyWeight>) {
    val color = MaterialTheme.colorScheme.primary
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val low = remember(weights) { weights.minOf { it.grams } }
    val high = remember(weights) { weights.maxOf { it.grams } }
    val description = remember(weights) { weights.joinToString("; ") { "${it.date}: ${formatKilograms(it.grams)} kilograms" } }
    val normalized = remember(weights) {
        val firstDate = LocalDate.parse(weights.first().date).toEpochDay()
        val dateSpan = (LocalDate.parse(weights.last().date).toEpochDay() - firstDate).coerceAtLeast(1)
        weights.map { w -> Offset((LocalDate.parse(w.date).toEpochDay() - firstDate).toFloat() / dateSpan,
            if (high == low) 0.5f else (w.grams - low).toFloat() / (high - low)) }
    }
    Canvas(Modifier.fillMaxWidth().height(144.dp).semantics { contentDescription = description }) {
        val pad = 8.dp.toPx()
        drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), 2f)
        var previous: Offset? = null
        normalized.forEach { point ->
            val position = Offset(pad + (size.width - 2 * pad) * point.x, size.height - pad - (size.height - 2 * pad) * point.y)
            previous?.let { drawLine(color, it, position, 3.dp.toPx()) }
            drawCircle(color, 4.dp.toPx(), position)
            previous = position
        }
    }
    Text("${weights.first().date} → ${weights.last().date}", style = MaterialTheme.typography.labelMedium)
    Text("Range ${formatKilograms(low)}–${formatKilograms(high)} kg · latest ${formatKilograms(weights.last().grams)} kg", style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun PlansScreen(profile: Profile, state: LocalState, records: List<Entry>, subject: String?, selectSubject: (String) -> Unit, edit: (Entry) -> Unit, newPlan: (String) -> Unit, startSession: (Entry, Int) -> Unit) {
    val plans = remember(records, subject) { records.filter { it.plan != null && it.subjectId == subject }.sortedByDescending { it.plan!!.weekStart } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionTitle("Weekly plans") }
        item { SubjectSelector(profile, state.relationships, subject, selectSubject, includeEnded = true) }
        if (profile.role == Role.TRAINER && subject != null && state.activeRelationships.any { it.clientId == subject && it.trainerId == profile.uid }) item { Button(onClick = { newPlan(subject) }) { Text("Create weekly plan") } }
        if (plans.isEmpty()) item { Text("No assigned plans") }
        items(plans, key = { it.id }) { entry -> PlanCard(entry, profile, edit = if (entry.ownerId == profile.uid && state.activeRelationships.any { it.clientId == entry.subjectId && it.trainerId == profile.uid }) ({ edit(entry) }) else null, startSession = startSession) }
    }
}

@Composable
internal fun PlanCard(entry: Entry, profile: Profile, edit: (() -> Unit)? = null, startSession: (Entry, Int) -> Unit) {
    val plan = entry.plan ?: return
    var expanded by remember(entry.id) { mutableStateOf(false) }
    InfoCard(plan.title) {
        Text("Week of ${plan.weekStart}", color = MaterialTheme.colorScheme.primary)
        Text("${plan.days.size} training day(s) · ${plan.days.sumOf { it.exercises.size }} exercises", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide schedule" else "View schedule") }
            if (edit != null) OutlinedButton(onClick = edit) { Text("Update plan") }
        }
        if (expanded) plan.days.sortedBy { it.dayOfWeek }.forEach { day ->
            HorizontalDivider()
            Text("${dayName(day.dayOfWeek)} · ${LocalDate.parse(plan.weekStart).plusDays((day.dayOfWeek - 1).toLong())}", style = MaterialTheme.typography.titleSmall)
            day.exercises.forEach { exercise ->
                Text("${exercise.name}\n${exercise.sets} × ${exercise.repetitions} reps" + (exercise.loadGrams?.let { " · ${formatKilograms(it)} kg" } ?: " · load unspecified"))
            }
            if (profile.role == Role.CLIENT && entry.subjectId == profile.uid) Button(onClick = { startSession(entry, day.dayOfWeek) }) { Text("Start ${dayName(day.dayOfWeek)} session") }
        }
    }
}
