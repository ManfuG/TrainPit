package io.github.manfug.trainpit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.math.abs

internal data class TrendPoint(val date: String, val value: Double)

private data class ChartPoint(val point: TrendPoint, val day: Long)

/** Keeps both ends and each bucket's extremes; selection still uses every original point. */
private fun overviewIndices(points: List<ChartPoint>): List<Int> {
    if (points.size <= 180) return points.indices.toList()
    val result = sortedSetOf(0, points.lastIndex)
    repeat(89) { bucket ->
        val start = 1 + (points.size - 2) * bucket / 89
        val end = 1 + (points.size - 2) * (bucket + 1) / 89
        val range = start until end
        range.minByOrNull { points[it].point.value }?.let(result::add)
        range.maxByOrNull { points[it].point.value }?.let(result::add)
    }
    return result.toList()
}

@Composable
internal fun TrendChart(points: List<TrendPoint>, unit: String) {
    val series = remember(points) {
        points.filter { it.value.isFinite() }.sortedWith(compareBy<TrendPoint> { it.date }.thenBy { it.value })
            .map { ChartPoint(it, LocalDate.parse(it.date).toEpochDay()) }
    }
    if (series.isEmpty()) {
        Text("No measurements yet.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val rendered = remember(series) { overviewIndices(series) }
    var selected by remember(series) { mutableIntStateOf(series.lastIndex) }
    val low = remember(series) { series.minOf { it.point.value } }
    val high = remember(series) { series.maxOf { it.point.value } }
    val firstDay = series.first().day
    val daySpan = (series.last().day - firstDay).coerceAtLeast(1).toDouble()
    val valueSpan = (high - low).takeIf { it > 0.0 } ?: 1.0
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val current = series[selected].point
    val description = "${displayDate(current.date)}: ${chartValue(current.value)} $unit"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(description, style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text(if (low == high) "${chartValue(low)} $unit" else "${chartValue(low)}–${chartValue(high)} $unit",
            style = MaterialTheme.typography.labelSmall)
        Canvas(Modifier.fillMaxWidth().height(140.dp)
            .semantics {
                contentDescription = "Trend chart. $description"
                customActions = listOf(
                    CustomAccessibilityAction("Previous measurement") { if (selected > 0) { selected--; true } else false },
                    CustomAccessibilityAction("Next measurement") { if (selected < series.lastIndex) { selected++; true } else false },
                )
            }
            .pointerInput(series) {
                detectTapGestures { tap ->
                    val inset = 8.dp.toPx()
                    val width = (size.width - inset * 2).coerceAtLeast(1f)
                    val day = firstDay + ((tap.x - inset) / width).coerceIn(0f, 1f) * daySpan
                    selected = series.indices.minBy { abs(series[it].day - day) }
                }
            }) {
            val inset = 8.dp.toPx()
            val width = (size.width - inset * 2).coerceAtLeast(1f)
            val height = (size.height - inset * 2).coerceAtLeast(1f)
            fun position(index: Int): Offset {
                val value = series[index]
                val x = if (series.first().day == series.last().day) size.width / 2 else
                    inset + ((value.day - firstDay) / daySpan).toFloat() * width
                val y = if (low == high) size.height / 2 else
                    inset + (1.0 - (value.point.value - low) / valueSpan).toFloat() * height
                return Offset(x, y)
            }
            drawLine(grid, Offset(inset, size.height - inset), Offset(size.width - inset, size.height - inset), 1.dp.toPx())
            for (index in 1 until rendered.size) {
                drawLine(color, position(rendered[index - 1]), position(rendered[index]), 2.dp.toPx())
            }
            rendered.forEach { drawCircle(color, 2.dp.toPx(), position(it)) }
            val marker = position(selected)
            drawLine(grid, Offset(marker.x, inset), Offset(marker.x, size.height - inset), 1.dp.toPx())
            drawCircle(color, 5.dp.toPx(), marker)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(displayDate(series.first().point.date), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            if (series.size > 1) Text(displayDate(series.last().point.date), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

private fun chartValue(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
