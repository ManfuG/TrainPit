package io.github.manfug.trainpit.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.manfug.trainpit.model.*
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.util.Locale

internal fun monday(date: LocalDate = LocalDate.now()): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())
internal fun dayName(day: Int): String = java.time.DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.ENGLISH)
private val DisplayDate = DateTimeFormatter.ofPattern("EEEE, d MMM uuuu", Locale.ENGLISH)
internal fun displayDate(value: String): String = LocalDate.parse(value).format(DisplayDate)

@Composable
internal fun currentDate(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            today = LocalDate.now()
            delay(60_000)
        }
    }
    return today
}
internal fun recordTitle(entry: Entry): String = entry.plan?.title ?: entry.session?.title ?: "Body weight"
internal fun recordDate(entry: Entry): String = entry.plan?.weekStart ?: entry.session?.date ?: entry.weight?.date.orEmpty()
internal fun requireDate(value: String): String {
    try { require(value.length == 10 && LocalDate.parse(value).year in 1..9999) { "Use a date in YYYY-MM-DD format, with a year from 0001 to 9999." } }
    catch (_: DateTimeParseException) { throw IllegalArgumentException("Use a real date in YYYY-MM-DD format.") }
    return value
}

@Composable
internal fun ScrollPage(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

@Composable
internal fun SectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun EmptyState(title: String, explanation: String) {
    InfoCard(title) { Text(explanation, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
internal fun ErrorText(error: String?) {
    if (error != null) Text(error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

internal val LocalInputEnabled = compositionLocalOf { true }
internal val LocalOperationMessage = compositionLocalOf<String?> { null }

@Composable
internal fun Input(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, numeric: Boolean = false, help: String? = null, enabled: Boolean = true) {
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        enabled = enabled && LocalInputEnabled.current, keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
        supportingText = if (help != null) ({ Text(help) }) else null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateInput(value: String, onChange: (String) -> Unit, label: String) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = LocalInputEnabled.current) { Text("$label: ${displayDate(value)}") }
    if (open) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = 1..9999,
        )
        DatePickerDialog(onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = {
                picker.selectedDateMillis?.let { onChange(java.time.Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toString()) }
                open = false
            }, enabled = picker.selectedDateMillis != null) { Text("Done") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(picker, showModeToggle = false) }
    }
}

@Composable
internal fun ConfirmDialog(title: String, description: String, confirm: String = "Confirm", onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(description) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
internal fun Choice(label: String, selected: String, values: List<String>, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = LocalInputEnabled.current, modifier = Modifier.fillMaxWidth()) { Text("$label: $selected", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            values.forEachIndexed { index, value -> DropdownMenuItem(text = { Text(value) }, onClick = { open = false; onSelect(index) }) }
        }
    }
}
