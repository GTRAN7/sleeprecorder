package com.erictran.sleepsounds

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.roundToInt

private val TREND_TYPES = listOf(SoundType.TALKING, SoundType.LAUGHING, SoundType.SNORING, SoundType.COUGHING)
private const val MAX_NIGHTS = 14

/** How often each kind of sound happens, night to night and through the night. */
@Composable
fun TrendsScreen(nights: List<Night>) {
    // Nights from version 0.1 kept full audio and were never analysed, so they say nothing here.
    val analysed = nights.filter { night -> night.events.none { it.type == SoundType.RECORDING } }
    if (analysed.isEmpty()) {
        Text(
            "Trends appear after your first night with detection.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(4.dp),
        )
        return
    }
    var type by rememberSaveable { mutableStateOf(SoundType.TALKING) }
    val recent = analysed.take(MAX_NIGHTS).reversed()
    val perNight = recent.map { it.amountOf(type) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TREND_TYPES.forEach {
                FilterChip(selected = type == it, onClick = { type = it }, label = { Text(it.label) })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = "Nights with ${type.label.lowercase()}",
                value = "${perNight.count { it > 0f }} of ${recent.size}",
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Average per night",
                value = type.formatAmount(perNight.average().toFloat(), short = true),
                modifier = Modifier.weight(1f),
            )
        }

        ChartCard(
            title = "Each night",
            subtitle = "${type.unitLabel()}, last ${countOf(recent.size, "night")}",
        ) {
            BarChart(
                values = perNight,
                labels = recent.map { formatNightDay(it.startedAt) },
                color = type.color(),
                key = type,
                describe = { i -> "${formatNightDate(recent[i].startedAt)}: ${type.formatAmount(perNight[i])}" },
                initiallySelected = perNight.lastIndex,
                formatValue = { type.formatAmount(it, short = true) },
            )
        }

        val hours = hourRange(recent)
        val perHour = hours.map { hour -> recent.sumOf { it.amountInHour(type, hour).toDouble() }.toFloat() }
        ChartCard(
            title = "Time of night",
            subtitle = "When it starts, across the same nights",
        ) {
            BarChart(
                values = perHour,
                labels = hours.map(::formatHour),
                color = type.color(),
                key = type,
                describe = { i ->
                    "${formatHourLong(hours[i])} to ${formatHourLong((hours[i] + 1) % 24)}: " +
                        "${type.formatAmount(perHour[i])} over ${countOf(recent.size, "night")}"
                },
                initiallySelected = perHour.indices.maxBy { perHour[it] },
                formatValue = { type.formatAmount(it, short = true) },
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChartCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/**
 * Vertical bars on one baseline. Tapping a bar shows its exact value underneath.
 */
@Composable
private fun BarChart(
    values: List<Float>,
    labels: List<String>,
    color: Color,
    key: Any,
    describe: (Int) -> String,
    initiallySelected: Int,
    formatValue: (Float) -> String,
) {
    var selected by remember(key, values.size) { mutableIntStateOf(initiallySelected) }
    val max = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
    val baseline = MaterialTheme.colorScheme.outline
    val labelEvery = ceil(values.size / MAX_X_LABELS.toFloat()).toInt().coerceAtLeast(1)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Most: ${formatValue(max)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .pointerInput(values.size) {
                    detectTapGestures { offset ->
                        selected = (offset.x / (size.width.toFloat() / values.size)).toInt().coerceIn(0, values.lastIndex)
                    }
                },
        ) {
            val slot = size.width / values.size
            val gap = 2.dp.toPx()
            val width = (slot * BAR_FILL).coerceAtLeast(2.dp.toPx())
            val radius = CornerRadius(4.dp.toPx())
            values.forEachIndexed { i, value ->
                if (value <= 0f) return@forEachIndexed
                val height = (value / max * size.height).coerceAtLeast(2.dp.toPx())
                val left = i * slot + (slot - width) / 2
                val bar = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = left,
                            top = size.height - height,
                            right = left + width,
                            bottom = size.height - gap / 2,
                            topLeftCornerRadius = radius,
                            topRightCornerRadius = radius,
                        ),
                    )
                }
                drawPath(bar, if (i == selected) color else color.copy(alpha = UNSELECTED_ALPHA))
            }
            drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                Text(
                    if (i % labelEvery == 0 || i == selected) label else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (selected in values.indices) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Text(describe(selected), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private const val BAR_FILL = 0.62f
private const val UNSELECTED_ALPHA = 0.5f
private const val MAX_X_LABELS = 8

/** Snoring is measured in minutes; everything else in events. */
private fun Night.amountOf(type: SoundType): Float =
    events.filter { it.type == type }.let { matching ->
        if (type == SoundType.SNORING) matching.sumOf { it.durationMs } / 60_000f else matching.size.toFloat()
    }

private fun Night.amountInHour(type: SoundType, hour: Int): Float =
    events.filter { it.type == type && hourOf(it.startedAt) == hour }.let { matching ->
        if (type == SoundType.SNORING) matching.sumOf { it.durationMs } / 60_000f else matching.size.toFloat()
    }

private fun hourOf(epochMs: Long) = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).hour

/** Clock hours spanned by the nights, from the earliest start to the latest end, across midnight. */
private fun hourRange(nights: List<Night>): List<Int> {
    // Count hours from 6 PM so that a night runs forwards through midnight.
    fun fromEvening(hour: Int) = (hour - EVENING_HOUR + 24) % 24
    val first = nights.minOf { fromEvening(hourOf(it.startedAt)) }
    val last = nights.maxOf { fromEvening(hourOf(it.endTime())) }.coerceAtLeast(first)
    return (first..last).map { (it + EVENING_HOUR) % 24 }
}

private const val EVENING_HOUR = 18

private fun SoundType.unitLabel() = when (this) {
    SoundType.SNORING -> "Minutes of snoring"
    SoundType.TALKING -> "Talking clips"
    SoundType.LAUGHING -> "Laughing clips"
    SoundType.COUGHING -> "Coughing clips"
    SoundType.RECORDING -> "Recordings"
}

private fun SoundType.formatAmount(amount: Float, short: Boolean = false): String {
    if (this == SoundType.SNORING) return formatDuration((amount * 60_000).roundToInt().toLong())
    val shown = formatScale(amount)
    if (short) return shown
    val noun = when (this) {
        SoundType.TALKING -> "talking clip"
        SoundType.LAUGHING -> "laugh"
        SoundType.COUGHING -> "coughing clip"
        else -> "clip"
    }
    return if (amount == 1f) "1 $noun" else "$shown ${noun}s"
}

private fun formatScale(value: Float): String =
    if (value == value.roundToInt().toFloat()) value.roundToInt().toString() else "%.1f".format(value)
