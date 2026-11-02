package io.github.manfug.trainpit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.manfug.trainpit.model.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private data class WeekSummary(val sessions: List<Entry>, val sets: Int, val repetitions: Long, val volumeGrams: Long, val chart: List<Pair<LocalDate, Int>>, val weights: List<BodyWeight>)
private val ChartDateFormat = DateTimeFormatter.ofPattern("MM-dd")

@Composable
internal fun SubjectSelector(profile: Profile, relationships: List<Relationship>, selected: String?, onSelect: (String) -> Unit, includeEnded: Boolean = false) {
    if (profile.role != Role.TRAINER) return
    val clients = remember(relationships, profile.uid, includeEnded) { relationships.filter { it.trainerId == profile.uid && (it.status == RelationshipStatus.ACTIVE || includeEnded && it.status == RelationshipStatus.ENDED) }.sortedBy { it.clientName.lowercase() } }
    if (clients.isEmpty()) {
        Text("Connect with a client to view their training.", style = MaterialTheme.typography.bodyMedium)
    } else Choice("Client", clients.firstOrNull { it.clientId == selected }?.let { it.clientName + if (it.status == RelationshipStatus.ENDED) " (ended)" else "" } ?: "Choose a client", clients.map { it.clientName + if (it.status == RelationshipStatus.ENDED) " (ended)" else "" }) { onSelect(clients[it].clientId) }
}

@Composable
internal fun DashboardScreen(profile: Profile, state: LocalState, records: List<Entry>, subject: String?, selectSubject: (String) -> Unit, newSession: () -> Unit, newWeight: () -> Unit, startSession: (Entry, Int) -> Unit, newPlan: (String) -> Unit) {
    var week by remember(subject) { mutableStateOf(monday()) }
    val summary = remember(records, subject, week) {
        val sessions = records.filter { it.subjectId == subject && it.session != null }
        val from = week.toString(); val until = week.plusDays(7).toString()
        val current = sessions.filter { val date = requireNotNull(it.session).date; date >= from && date < until }
        val sets = current.flatMap { it.session!!.sets }
        WeekSummary(current, sets.size, sets.sumOf { it.repetitions.toLong() }, sets.sumOf { it.loadGrams * it.repetitions },
            (7 downTo 0).map { offset ->
                val start = week.minusWeeks(offset.toLong()); val end = start.plusWeeks(1).toString()
                val startDate = start.toString()
                start to sessions.count { val date = requireNotNull(it.session).date; date >= startDate && date < end }
            }, records.filter { it.subjectId == subject && it.weight != null }.mapNotNull { it.weight }.sortedBy { it.date }.takeLast(20))
    }
    val plans = remember(records, subject, week) { records.filter { it.subjectId == subject && it.plan?.weekStart == week.toString() } }
    val weightAllowed = profile.role == Role.CLIENT || state.activeRelationships.any { it.clientId == subject && it.trainerId == profile.uid && it.shareBodyWeight }
    ScrollPage {
        SectionTitle(if (profile.role == Role.CLIENT) "Your training" else "Client overview", if (profile.role == Role.TRAINER) "Client results are read-only. Only your own plans can be edited." else null)
        SubjectSelector(profile, state.relationships, subject, selectSubject)
        if (profile.role == Role.TRAINER && subject == null) {
            EmptyState("Choose or connect a client", "Use Connections to invite clients by account UID. After they accept, their permitted records will appear here.")
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { week = week.minusWeeks(1) }) { Text("Previous") }
                TextButton(onClick = { week = monday() }) { Text("This week") }
                OutlinedButton(onClick = { week = week.plusWeeks(1) }) { Text("Next") }
            }
            Text("Week of $week", style = MaterialTheme.typography.titleLarge)
            InfoCard("Recorded this week") {
                Text("Sessions: ${summary.sessions.size} · Sets: ${summary.sets}", style = MaterialTheme.typography.titleMedium)
                Text("Reps: ${summary.repetitions} · Volume: ${formatKilograms(summary.volumeGrams)} kg × reps")
                Text("Volume = external kg × completed reps; body-weight-only sets contribute 0 kg.", style = MaterialTheme.typography.bodySmall)
            }
            if (profile.role == Role.CLIENT) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = newSession) { Text("Log session") }
                    OutlinedButton(onClick = newWeight) { Text("Body weight") }
                }
            } else if (subject != null && state.activeRelationships.any { it.clientId == subject && it.trainerId == profile.uid }) Button(onClick = { newPlan(subject) }) { Text("Create weekly plan") }
            SectionTitle("Weekly schedule")
            if (plans.isEmpty()) EmptyState("No plan for this week", if (profile.role == Role.CLIENT) "Your trainer's assigned plans appear after sync. You can still log a session independently." else "Create a weekly plan for this client. Weeks always start on Monday.")
            plans.forEach { entry -> PlanCard(entry, profile, startSession = startSession) }
            InfoCard("Sessions per week") {
                Text("Eight weeks ending with the selected week. Only saved, non-conflicted sessions are counted.", style = MaterialTheme.typography.bodySmall)
                WeeklyBars(summary.chart)
            }
            if (weightAllowed) InfoCard("Body-weight trend") {
                if (summary.weights.isEmpty()) Text("No body-weight measurements recorded.")
                else WeightChart(summary.weights)
            } else InfoCard("Body-weight privacy") { Text("This client has not granted access to body-weight history.") }
        }
    }
}

@Composable
private fun WeeklyBars(values: List<Pair<LocalDate, Int>>) {
    val color = MaterialTheme.colorScheme.primary
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val max = remember(values) { (values.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1) }
    val description = remember(values) { values.joinToString("; ") { "Week of ${it.first}: ${it.second} sessions" } }
    Canvas(Modifier.fillMaxWidth().height(144.dp).semantics { contentDescription = description }) {
        val cell = size.width / values.size.coerceAtLeast(1)
        drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), 2f)
        values.forEachIndexed { index, pair ->
            val height = (size.height - 12.dp.toPx()) * pair.second / max
            if (height > 0) drawLine(color, Offset(cell * (index + 0.5f), size.height), Offset(cell * (index + 0.5f), size.height - height), cell * 0.52f, StrokeCap.Butt)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        values.forEach { (date, count) -> Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Text(date.format(ChartDateFormat), style = MaterialTheme.typography.labelSmall)
            Text(count.toString(), style = MaterialTheme.typography.labelMedium)
        } }
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
    Text("Most recent ${weights.size} measurements. Points use their actual calendar dates.", style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun PlansScreen(profile: Profile, state: LocalState, records: List<Entry>, subject: String?, selectSubject: (String) -> Unit, edit: (Entry) -> Unit, newPlan: (String) -> Unit, startSession: (Entry, Int) -> Unit) {
    val plans = remember(records, subject) { records.filter { it.plan != null && it.subjectId == subject }.sortedByDescending { it.plan!!.weekStart } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionTitle("Weekly plans", "A plan update is a new signed revision; session references remain tied to the version used.") }
        item { SubjectSelector(profile, state.relationships, subject, selectSubject, includeEnded = true) }
        if (profile.role == Role.TRAINER && subject != null && state.activeRelationships.any { it.clientId == subject && it.trainerId == profile.uid }) item { Button(onClick = { newPlan(subject) }) { Text("Create weekly plan") } }
        if (plans.isEmpty()) item { EmptyState("No assigned plans", if (profile.role == Role.CLIENT) "Connect with a trainer and sync to receive your weekly schedule." else "Choose an active client, then create a weekly plan.") }
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
