package io.github.manfug.trainpit.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.manfug.trainpit.model.*
import java.time.LocalDate

@Composable
internal fun SubjectSelector(profile: Profile, relationships: List<Relationship>, selected: String?, onSelect: (String) -> Unit, includeEnded: Boolean = false, modifier: Modifier = Modifier) {
    if (profile.role != Role.TRAINER) return
    val clients = remember(relationships, profile.uid, includeEnded) { relationships.filter { it.trainerId == profile.uid && (it.status == RelationshipStatus.ACTIVE || includeEnded && it.status == RelationshipStatus.ENDED) }.sortedBy { it.clientName.lowercase() } }
    if (clients.isEmpty()) {
        Text("No clients yet", modifier, style = MaterialTheme.typography.bodyMedium)
    } else Choice("Client", clients.firstOrNull { it.clientId == selected }?.let { it.clientName + if (it.status == RelationshipStatus.ENDED) " (ended)" else "" } ?: "Choose a client", clients.map { it.clientName + if (it.status == RelationshipStatus.ENDED) " (ended)" else "" }, modifier) { onSelect(clients[it].clientId) }
}

internal fun currentWeekPlans(profile: Profile, state: LocalState, subject: String?, today: LocalDate): List<Entry> {
    val week = monday(today).toString()
    val candidates = recordHeads(state.entries).values.filter { group ->
        group.any { entry -> entry.subjectId == subject && entry.plan?.weekStart?.let { it <= week } == true &&
            state.relationships.any { it.status == RelationshipStatus.ACTIVE && it.clientId == subject && it.trainerId == entry.ownerId && it.includes(profile.uid) } }
    }
    return candidates.groupBy { it.first().ownerId }.values.mapNotNull { weeks ->
        val latest = weeks.maxOf { group -> group.maxOf { requireNotNull(it.plan).weekStart } }
        weeks.singleOrNull { group -> group.any { it.plan?.weekStart == latest } }?.singleOrNull()?.takeUnless { it.deleted }
    }
}

@Composable
internal fun DashboardScreen(profile: Profile, state: LocalState, records: List<Entry>, subject: String?, selectSubject: (String) -> Unit, newWeight: () -> Unit, startSession: (Entry, Int) -> Unit) {
    val today = currentDate()
    val plans = remember(state.entries, state.relationships, profile, subject, today) {
        currentWeekPlans(profile, state, subject, today).filter { entry ->
            requireNotNull(entry.plan).days.any { it.dayOfWeek == today.dayOfWeek.value }
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
                        Text(displayDate(today.toString()), color = MaterialTheme.colorScheme.primary)
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
                    if (weights.isEmpty()) Text("No measurements yet") else {
                        val points = remember(weights) { weights.map { TrendPoint(it.date, it.grams / 1000.0) } }
                        TrendChart(points, "kg")
                    }
                }
            }
        }
    }
}


@Composable
internal fun PlansScreen(profile: Profile, state: LocalState, subject: String?, selectSubject: (String) -> Unit, edit: (Entry) -> Unit, newPlan: (String) -> Unit, startSession: (Entry, Int) -> Unit) {
    val today = currentDate()
    val plans = remember(state.entries, state.relationships, profile, subject, today) { currentWeekPlans(profile, state, subject, today) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionTitle("Weekly plans") }
        item { SubjectSelector(profile, state.relationships, subject, selectSubject) }
        if (plans.none { it.ownerId == profile.uid } && profile.role == Role.TRAINER && subject != null && state.activeRelationships.any { it.clientId == subject && it.trainerId == profile.uid }) item { Button(onClick = { newPlan(subject) }) { Text("Create weekly plan") } }
        if (plans.isEmpty()) item { Text("No assigned plans") }
        items(plans, key = { it.id }) { entry -> PlanCard(entry, profile, edit = if (entry.ownerId == profile.uid && state.activeRelationships.any { it.clientId == entry.subjectId && it.trainerId == profile.uid }) ({ if (entry.plan?.weekStart == monday(today).toString()) edit(entry) else newPlan(entry.subjectId) }) else null, startSession = startSession) }
    }
}

@Composable
internal fun PlanCard(entry: Entry, profile: Profile, edit: (() -> Unit)? = null, startSession: (Entry, Int) -> Unit) {
    val plan = entry.plan ?: return
    InfoCard(plan.title) {
        if (edit != null) TextButton(onClick = edit) { Text("Edit plan") }
        plan.days.sortedBy { it.dayOfWeek }.forEach { day ->
            HorizontalDivider()
            Text(displayDate(monday().plusDays((day.dayOfWeek - 1).toLong()).toString()), style = MaterialTheme.typography.titleSmall)
            day.exercises.forEach { exercise ->
                Text("${exercise.name}\n${exercise.sets} × ${exercise.repetitions} reps" + (exercise.loadGrams?.let { " · ${formatKilograms(it)} kg" } ?: " · load unspecified"))
            }
            if (profile.role == Role.CLIENT && entry.subjectId == profile.uid) Button(onClick = { startSession(entry, day.dayOfWeek) }) { Text("Start ${dayName(day.dayOfWeek)} session") }
        }
    }
}
