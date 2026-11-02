package io.github.manfug.trainpit.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.manfug.trainpit.model.*
import java.time.LocalDate

internal sealed interface Editor {
    data class Plan(val clientId: String, val entry: Entry? = null) : Editor
    data class Session(val entry: Entry? = null, val plan: Entry? = null, val day: Int? = null) : Editor
    data class Weight(val entry: Entry? = null) : Editor
}

@Composable
private fun EditorFrame(title: String, dirty: Boolean, saving: Boolean, onClose: () -> Unit, onSave: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var discard by remember { mutableStateOf(false) }
    val close = { if (dirty) discard = true else onClose() }
    Dialog(onDismissRequest = { if (!saving) close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = close, enabled = !saving) { Text("Cancel") }
                    Button(onClick = onSave, enabled = !saving) { Text(if (saving) "Saving…" else "Save") }
                }
                HorizontalDivider()
                LocalOperationMessage.current?.let { message ->
                    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) { ErrorText(message) }
                }
                CompositionLocalProvider(LocalInputEnabled provides !saving) {
                    ScrollPage {
                        SectionTitle(title, "Dates use YYYY-MM-DD. Weights are in kilograms.")
                        content()
                    }
                }
            }
        }
        if (discard) ConfirmDialog("Discard unsaved changes?", "Your changes have not been saved. Existing records will remain unchanged.", "Discard", { discard = false }, onClose)
    }
}

internal data class ExerciseDraft(val id: String = newId(), val day: Int = 1, val name: String = "", val sets: String = "", val reps: String = "", val kg: String = "")

internal class PlanDraft(editor: Editor.Plan) {
    val originalTitle = editor.entry?.plan?.title.orEmpty()
    val originalWeek = editor.entry?.plan?.weekStart ?: monday().toString()
    val originalRows = editor.entry?.plan?.days?.flatMap { d -> d.exercises.map { ExerciseDraft(it.id, d.dayOfWeek, it.name, it.sets.toString(), it.repetitions.toString(), it.loadGrams?.let(::formatKilograms).orEmpty()) } } ?: listOf(ExerciseDraft())
    val title = mutableStateOf(originalTitle)
    val week = mutableStateOf(originalWeek)
    val rows = mutableStateOf(originalRows)
}

@Composable
internal fun PlanEditor(editor: Editor.Plan, draft: PlanDraft, saving: Boolean, onClose: () -> Unit, onSave: (WorkoutPlan, String?) -> Unit) {
    val plan = editor.entry?.plan
    var title by draft.title
    var week by draft.week
    var rows by draft.rows
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    val dirty = title != draft.originalTitle || week != draft.originalWeek || rows != draft.originalRows
    EditorFrame(if (plan == null) "New weekly plan" else "Update weekly plan", dirty, saving, onClose, onSave = {
        try {
            val days = rows.groupBy { it.day }.toSortedMap().map { (day, exercises) -> WorkoutDay(day, exercises.map {
                ExercisePrescription(it.id, it.name.trim(), it.sets.toIntOrNull() ?: throw IllegalArgumentException("Enter a whole number of sets."),
                    it.reps.toIntOrNull() ?: throw IllegalArgumentException("Enter a whole number of repetitions."),
                    if (it.kg.isBlank()) null else parseKilograms(it.kg, allowZero = true))
            }) }
            val result = WorkoutPlan(editor.clientId, title.trim(), requireDate(week), days)
            validatePlan(result); error = null; onSave(result, editor.entry?.rootId)
        } catch (e: IllegalArgumentException) { error = e.message ?: "Check the plan fields." }
    }) {
        Input(title, { title = it }, "Plan title")
        Input(week, { week = it }, "Week starts (Monday)", help = "Choose the Monday of this plan's week.")
        Text("A saved update creates a signed revision. Existing completed sessions keep their original plan reference.", style = MaterialTheme.typography.bodySmall)
        rows.forEachIndexed { index, row ->
            key(row.id) {
                InfoCard("Exercise ${index + 1}") {
                    fun update(value: ExerciseDraft) { rows = rows.map { if (it.id == row.id) value else it } }
                    Choice("Training day", dayName(row.day), (1..7).map(::dayName)) { update(row.copy(day = it + 1)) }
                    Input(row.name, { update(row.copy(name = it)) }, "Exercise name")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Input(row.sets, { update(row.copy(sets = it)) }, "Sets", Modifier.weight(1f), numeric = true)
                        Input(row.reps, { update(row.copy(reps = it)) }, "Reps per set", Modifier.weight(1f), numeric = true)
                    }
                    Input(row.kg, { update(row.copy(kg = it)) }, "Target load (kg, optional)", numeric = true, help = "Use 0 for no external load; leave blank if unspecified.")
                    TextButton(onClick = { removing = row.id }, enabled = !saving) { Text("Remove exercise") }
                }
            }
        }
        OutlinedButton(onClick = { rows = rows + ExerciseDraft(day = rows.lastOrNull()?.day ?: 1) }, enabled = rows.size < 280 && !saving) { Text("Add exercise") }
        ErrorText(error)
    }
    if (removing != null) ConfirmDialog("Remove exercise?", "This removes the exercise from your unsaved plan.", "Remove", { removing = null }, { rows = rows.filterNot { it.id == removing }; removing = null })
}

internal data class SetDraft(val id: String = newId(), val name: String = "", val reps: String = "", val kg: String = "", val prescriptionId: String? = null, val target: String? = null)

internal class SessionDraft(editor: Editor.Session) {
    private val session = editor.entry?.session
    private val plan = editor.plan?.plan
    val originalRows = session?.sets?.map { SetDraft(it.id, it.exerciseName, it.repetitions.toString(), formatKilograms(it.loadGrams), it.prescribedExerciseId) }
        ?: plan?.days?.firstOrNull { it.dayOfWeek == editor.day }?.exercises?.map { exercise ->
            SetDraft(name = exercise.name, prescriptionId = exercise.id,
                target = "Plan: ${exercise.sets} × ${exercise.repetitions} reps" + (exercise.loadGrams?.let { " · ${formatKilograms(it)} kg" } ?: ""))
        } ?: listOf(SetDraft())
    val initialTitle = session?.title ?: plan?.title.orEmpty()
    val initialDate = session?.date ?: LocalDate.now().toString()
    val title = mutableStateOf(initialTitle)
    val date = mutableStateOf(initialDate)
    val rows = mutableStateOf(originalRows)
}

internal class WeightDraft(editor: Editor.Weight) {
    val originalDate = editor.entry?.weight?.date ?: LocalDate.now().toString()
    val originalKg = editor.entry?.weight?.grams?.let(::formatKilograms).orEmpty()
    val date = mutableStateOf(originalDate)
    val kg = mutableStateOf(originalKg)
}

@Composable
internal fun SessionEditor(editor: Editor.Session, draft: SessionDraft, saving: Boolean, onClose: () -> Unit, onSave: (TrainingSession, String?) -> Unit) {
    val session = editor.entry?.session
    var title by draft.title
    var date by draft.date
    var rows by draft.rows
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    EditorFrame(if (session == null) "Log training session" else "Correct training session", title != draft.initialTitle || date != draft.initialDate || rows != draft.originalRows,
        saving, onClose, onSave = {
            try {
                val result = TrainingSession(requireDate(date), title.trim(), rows.map {
                    SetResult(it.id, it.name.trim(), it.reps.toIntOrNull() ?: throw IllegalArgumentException("Enter actual repetitions for every completed set."),
                        parseKilograms(it.kg, allowZero = true), it.prescriptionId)
                }, session?.planRevisionId ?: editor.plan?.id, session?.dayOfWeek ?: editor.day)
                validateSession(result); error = null; onSave(result, editor.entry?.rootId)
            } catch (e: IllegalArgumentException) { error = e.message ?: "Check the session fields." }
        }) {
        Input(title, { title = it }, "Session title")
        Input(date, { date = it }, "Training date")
        Text("Record only completed sets, up to 400 per session. Planned exercises start with one blank set each: add the sets you completed and remove skipped ones. Actual reps and load are never assumed.", style = MaterialTheme.typography.bodyMedium)
        rows.forEachIndexed { index, row ->
            key(row.id) {
                InfoCard("Completed set ${index + 1}") {
                    fun update(value: SetDraft) { rows = rows.map { if (it.id == row.id) value else it } }
                    row.target?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Input(row.name, { update(row.copy(name = it)) }, "Exercise name")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Input(row.reps, { update(row.copy(reps = it)) }, "Actual reps", Modifier.weight(1f), numeric = true)
                        Input(row.kg, { update(row.copy(kg = it)) }, "Actual kg", Modifier.weight(1f), numeric = true)
                    }
                    OutlinedButton(onClick = { rows = rows + SetDraft(name = row.name, prescriptionId = row.prescriptionId, target = row.target) }, enabled = rows.size < 400 && !saving) { Text("Add another set of this exercise") }
                    TextButton(onClick = { removing = row.id }, enabled = !saving) { Text("Remove set") }
                }
            }
        }
        OutlinedButton(onClick = { rows = rows + SetDraft() }, enabled = rows.size < 400 && !saving) { Text("Add another exercise set") }
        ErrorText(error)
    }
    if (removing != null) ConfirmDialog("Remove set?", "This set will not be included when you save the session.", "Remove", { removing = null }, { rows = rows.filterNot { it.id == removing }; removing = null })
}

@Composable
internal fun WeightEditor(editor: Editor.Weight, draft: WeightDraft, saving: Boolean, onClose: () -> Unit, onSave: (BodyWeight, String?) -> Unit) {
    var date by draft.date
    var kg by draft.kg
    var error by remember { mutableStateOf<String?>(null) }
    EditorFrame(if (editor.entry == null) "Record body weight" else "Correct body weight", date != draft.originalDate || kg != draft.originalKg, saving, onClose, {
        try { val value = BodyWeight(requireDate(date), parseKilograms(kg)); error = null; onSave(value, editor.entry?.rootId) }
        catch (e: IllegalArgumentException) { error = e.message ?: "Check the date and body weight." }
    }) {
        Input(date, { date = it }, "Measurement date")
        Input(kg, { kg = it }, "Body weight (kg)", numeric = true)
        Text("Cloud trainer access requires an active connection and your separate body-weight consent.")
        ErrorText(error)
    }
}
