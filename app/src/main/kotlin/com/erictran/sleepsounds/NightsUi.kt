package com.erictran.sleepsounds

import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

@Composable
fun NightCard(night: Night, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clip(CardDefaults.shape).clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(formatStart(night.startedAt), style = MaterialTheme.typography.titleMedium)
            Text(
                "${formatDuration(night.durationMs)} · until ${formatTimeOfDay(night.endTime())}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(night.summary())
            NightWarnings(night)
        }
    }
}

@Composable
private fun NightWarnings(night: Night) {
    if (night.endedAt == null) {
        Text("Cut off: the phone stopped the recording early.", color = MaterialTheme.colorScheme.error)
    }
    if (night.gaps.isNotEmpty()) {
        Text(
            "${countOf(night.gaps.size, "gap")}, ${formatDuration(night.gaps.sumOf { it.lengthMs })} not recorded",
            color = Amber,
        )
    }
}

/** Everything caught during one night, in order, with a strip showing when it happened. */
@Composable
fun NightScreen(
    night: Night,
    playback: ClipPlayer.State,
    canPlay: Boolean,
    onBack: () -> Unit,
    onTogglePlay: (SoundEvent, Boolean) -> Unit,
    onDeleteEvent: (SoundEvent) -> Unit,
    onDeleteNight: () -> Unit,
) {
    val context = LocalContext.current
    var filter by rememberSaveable { mutableStateOf<SoundType?>(null) }
    var deletingNight by remember { mutableStateOf(false) }
    var deletingEvent by remember { mutableStateOf<SoundEvent?>(null) }
    val events = night.events.sortedBy { it.startedAt }
    val types = SoundType.entries.filter { type -> events.any { it.type == type } }
    val shown = events.filter { filter == null || it.type == filter }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back") }
                Text(
                    formatStart(night.startedAt),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                IconButton(onClick = { deletingNight = true }) {
                    Icon(
                        painterResource(R.drawable.ic_delete),
                        contentDescription = "Delete night",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = insets.calculateTopPadding() + 4.dp,
                bottom = insets.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${formatDuration(night.durationMs)} · until ${formatTimeOfDay(night.endTime())} · " +
                            Formatter.formatShortFileSize(context, night.sizeBytes),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NightWarnings(night)
                    if (!canPlay) {
                        Text("Playback is off while recording.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (events.any { it.type != SoundType.RECORDING }) {
                item { Timeline(night) }
            }
            if (types.size > 1) {
                item {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All ${events.size}") })
                        types.forEach { type ->
                            FilterChip(
                                selected = filter == type,
                                onClick = { filter = type },
                                label = { Text("${type.label} ${events.count { it.type == type }}") },
                            )
                        }
                    }
                }
            }
            if (events.isEmpty()) {
                item {
                    Text(
                        "No talking, laughing or snoring was detected this night.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
            items(shown, key = { it.clip.name }) { event ->
                EventRow(
                    event = event,
                    playback = playback.takeIf { it.clip == event.clip },
                    canPlay = canPlay,
                    onTogglePlay = { onTogglePlay(event, false) },
                    onPlayFromStart = { onTogglePlay(event, true) },
                    onDelete = { deletingEvent = event },
                )
            }
        }
    }

    if (deletingNight) {
        ConfirmDelete(
            title = "Delete this night?",
            text = "All ${countOf(night.events.size, "clip")} from ${formatStart(night.startedAt)} will be removed from this phone.",
            onConfirm = onDeleteNight,
            onDismiss = { deletingNight = false },
        )
    }
    deletingEvent?.let { event ->
        ConfirmDelete(
            title = "Delete this clip?",
            text = "${event.type.label} at ${formatTimeOfDay(event.startedAt)} will be removed from this phone.",
            onConfirm = { onDeleteEvent(event) },
            onDismiss = { deletingEvent = null },
        )
    }
}

@Composable
private fun ConfirmDelete(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The night from start to end, one lane per kind of sound, with a mark wherever one was heard. */
@Composable
private fun Timeline(night: Night) {
    val lanes = listOf(SoundType.TALKING, SoundType.LAUGHING, SoundType.SNORING, SoundType.COUGHING)
    val spanMs = (night.endTime() - night.startedAt).coerceAtLeast(1)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(Modifier.fillMaxWidth().height(54.dp)) {
                val gap = 5.dp.toPx()
                val laneHeight = (size.height - gap * (lanes.size - 1)) / lanes.size
                val radius = CornerRadius(laneHeight / 2)
                lanes.forEachIndexed { i, type ->
                    val top = i * (laneHeight + gap)
                    drawRoundRect(Raised, Offset(0f, top), Size(size.width, laneHeight), radius)
                    night.events.filter { it.type == type }.forEach { event ->
                        val x = (event.startedAt - night.startedAt).toFloat() / spanMs * size.width
                        val width = (event.durationMs.toFloat() / spanMs * size.width).coerceAtLeast(MIN_MARK_DP.dp.toPx())
                        drawRoundRect(
                            type.color(),
                            Offset(x.coerceIn(0f, size.width - width), top),
                            Size(width, laneHeight),
                            radius,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    formatTimeOfDay(night.startedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatTimeOfDay(night.endTime()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lanes.forEach { type ->
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(type.color()))
                        Text(
                            type.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(
    event: SoundEvent,
    playback: ClipPlayer.State?,
    canPlay: Boolean,
    onTogglePlay: () -> Unit,
    onPlayFromStart: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onTogglePlay, enabled = canPlay) {
                    if (playback?.playing == true) {
                        Icon(painterResource(R.drawable.ic_pause), contentDescription = "Pause")
                    } else {
                        Icon(painterResource(R.drawable.ic_play), contentDescription = "Play")
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(event.type.color()))
                        Text(event.type.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            formatPreciseTime(event.startedAt),
                            style = TextStyle(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(event.detail(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (event.leadInMs >= MIN_LEAD_IN_MS) {
                        Text(
                            "Play from ${formatDuration(event.leadInMs)} before",
                            color = if (canPlay) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(enabled = canPlay, onClick = onPlayFromStart)
                                .padding(vertical = 6.dp),
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        painterResource(R.drawable.ic_delete),
                        contentDescription = "Delete clip",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (playback != null) {
                LinearProgressIndicator(
                    progress = { (playback.positionMs.toFloat() / event.clipMs.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = event.type.color(),
                    trackColor = Raised,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

private fun SoundEvent.detail(): String = when (type) {
    SoundType.COUGHING -> "${formatDuration(durationMs)}, about ${countOf(count, "cough")}"
    SoundType.SNORING ->
        "${formatDuration(durationMs)}, about ${countOf(count, "snore")} · ${formatDuration(clipMs)} sample"
    else -> formatDuration(durationMs)
}

private const val MIN_MARK_DP = 3
private const val MIN_LEAD_IN_MS = 5_000L
