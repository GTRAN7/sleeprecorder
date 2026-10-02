package com.erictran.sleepsounds

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color.TRANSPARENT
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(TRANSPARENT), SystemBarStyle.dark(TRANSPARENT))
        setContent {
            MaterialTheme(colorScheme = NightColors) { App() }
        }
    }
}

// Always dark and warm-toned: the app is used in a dark bedroom right before sleep.
private val Amber = Color(0xFFE8B15A)
private val Raised = Color(0xFF1D2433)
private val NightColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF2A1C02),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFD8DCE6),
    surface = Color(0xFF141925),
    onSurface = Color(0xFFD8DCE6),
    secondaryContainer = Raised,
    onSecondaryContainer = Color(0xFFD8DCE6),
    surfaceVariant = Raised,
    onSurfaceVariant = Color(0xFF8A93A6),
    error = Color(0xFFE0766C),
    onError = Color(0xFF2B0704),
)

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val status by RecorderState.status.collectAsStateWithLifecycle()
    val nights by vm.nights.collectAsStateWithLifecycle()
    val playback by vm.player.state.collectAsStateWithLifecycle()
    var micDenied by rememberSaveable { mutableStateOf(false) }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Night?>(null) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        micDenied = result[Manifest.permission.RECORD_AUDIO] != true
        if (!micDenied) {
            // Otherwise playback would end up in the recording.
            vm.player.stop()
            RecordingService.start(context)
        }
    }

    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    var batteryExempt by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        batteryExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose {}
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = insets.calculateTopPadding() + 16.dp,
                bottom = insets.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                RecorderPanel(
                    status = status,
                    micDenied = micDenied,
                    onStart = {
                        val wanted = buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        permissions.launch(wanted.toTypedArray())
                    },
                    onStop = { RecordingService.stop(context) },
                    onOpenAppSettings = { openAppSettings(context) },
                )
            }
            if (!batteryExempt) {
                item { BatteryCard(onAllow = { requestBatteryExemption(context) }) }
            }
            item {
                Text(
                    "Nights",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                )
            }
            if (nights.isEmpty()) {
                item {
                    Text(
                        if (status.recording) "This night will appear here when you stop recording."
                        else "No recordings yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
            items(nights, key = { it.id }) { night ->
                NightCard(
                    night = night,
                    expanded = expandedId == night.id,
                    playback = playback.takeIf { it.nightId == night.id },
                    canPlay = !status.recording,
                    onToggleExpanded = { expandedId = night.id.takeIf { expandedId != night.id } },
                    onTogglePlay = { vm.player.toggle(night) },
                    onSeek = { vm.player.play(night, it) },
                    onDelete = { pendingDelete = night },
                )
            }
        }
    }

    pendingDelete?.let { night ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this night?") },
            text = { Text("The recording from ${formatStart(night.startedAt)} will be removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(night)
                    pendingDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RecorderPanel(
    status: RecorderState.Status,
    micDenied: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (status.recording) {
                val elapsedMs by produceState(0L, status.startedAtElapsed) {
                    while (true) {
                        value = SystemClock.elapsedRealtime() - status.startedAtElapsed
                        delay(1000)
                    }
                }
                Text(
                    formatClock(elapsedMs),
                    style = MaterialTheme.typography.displayMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
                )
                LevelMeter()
                Text(
                    if (status.gaps == 0) "Recording. You can lock the phone."
                    else "Recording. ${countOf(status.gaps, "gap")} so far.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onStop) { Text("Stop", color = MaterialTheme.colorScheme.error) }
            } else {
                Button(
                    onClick = onStart,
                    shape = CircleShape,
                    modifier = Modifier.size(132.dp),
                    contentPadding = PaddingValues(0.dp),
                ) { Text("Start", style = MaterialTheme.typography.headlineSmall) }
                Text(
                    "Leave the phone near your bed, plugged in.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                if (micDenied) {
                    Text(
                        "Microphone access is needed to record.",
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    TextButton(onClick = onOpenAppSettings) { Text("Open app settings") }
                }
            }
        }
    }
}

@Composable
private fun LevelMeter() {
    val db by RecorderState.levelDb.collectAsStateWithLifecycle()
    val fraction by animateFloatAsState(((db - METER_FLOOR_DB) / -METER_FLOOR_DB).coerceIn(0f, 1f), label = "level")
    Box(
        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Raised),
    ) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Amber))
    }
}

@Composable
private fun BatteryCard(onAllow: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Let it run all night", style = MaterialTheme.typography.titleMedium)
            Text(
                "Battery saving can stop the recording after the screen locks. Allow Sleep Sounds to " +
                    "run in the background without restrictions.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAllow, colors = ButtonDefaults.filledTonalButtonColors()) { Text("Allow") }
        }
    }
}

@Composable
private fun NightCard(
    night: Night,
    expanded: Boolean,
    playback: NightPlayer.State?,
    canPlay: Boolean,
    onToggleExpanded: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clip(CardDefaults.shape).clickable(onClick = onToggleExpanded),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(formatStart(night.startedAt), style = MaterialTheme.typography.titleMedium)
            Text(
                "${formatDuration(night.durationMs)} · ${Formatter.formatShortFileSize(context, night.sizeBytes)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                night.endedAt == null -> Text(
                    "Cut off: the phone stopped the recording early.",
                    color = MaterialTheme.colorScheme.error,
                )
                night.gaps.isNotEmpty() -> Text(
                    "${countOf(night.gaps.size, "gap")}, ${formatDuration(night.gaps.sumOf { it.lengthMs })} lost",
                    color = Amber,
                )
            }

            AnimatedVisibility(expanded) {
                Column {
                    var dragMs by remember { mutableStateOf<Float?>(null) }
                    val positionMs = dragMs?.toLong() ?: playback?.positionMs ?: 0L
                    Slider(
                        value = positionMs.toFloat().coerceIn(0f, night.durationMs.toFloat()),
                        onValueChange = { dragMs = it },
                        onValueChangeFinished = {
                            dragMs?.let { onSeek(it.toLong()) }
                            dragMs = null
                        },
                        valueRange = 0f..night.durationMs.toFloat(),
                        enabled = canPlay,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onTogglePlay, enabled = canPlay) {
                            if (playback?.playing == true) {
                                Icon(painterResource(R.drawable.ic_pause), contentDescription = "Pause")
                            } else {
                                Icon(painterResource(R.drawable.ic_play), contentDescription = "Play")
                            }
                        }
                        Text(
                            "${formatClock(positionMs)} · ${formatTimeOfDay(night.wallTimeAt(positionMs))}",
                            style = TextStyle(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onDelete) {
                            Icon(
                                painterResource(R.drawable.ic_delete),
                                contentDescription = "Delete night",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!canPlay) {
                        Text("Playback is off while recording.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    night.gaps.take(MAX_GAPS_SHOWN).forEach { gap ->
                        Text(
                            "Gap at ${formatTimeOfDay(night.wallTimeAt(gap.atMs))}, ${formatDuration(gap.lengthMs)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (night.gaps.size > MAX_GAPS_SHOWN) {
                        Text(
                            "and ${night.gaps.size - MAX_GAPS_SHOWN} more",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private const val METER_FLOOR_DB = -70f
private const val MAX_GAPS_SHOWN = 5

private val startFormat = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")
private val timeOfDayFormat = DateTimeFormatter.ofPattern("h:mm a")

private fun formatStart(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(startFormat)

private fun formatTimeOfDay(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(timeOfDayFormat)

/** Clock time at an offset into the audio, allowing for the gaps before it. */
private fun Night.wallTimeAt(positionMs: Long): Long =
    startedAt + positionMs + gaps.filter { it.atMs < positionMs }.sumOf { it.lengthMs }

private fun formatClock(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds >= 3600 -> "${seconds / 3600} h ${seconds / 60 % 60} min"
        seconds >= 60 -> "${seconds / 60} min"
        else -> "$seconds s"
    }
}

private fun countOf(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

@SuppressLint("BatteryLife") // Sideloaded personal app whose whole job is to run overnight.
private fun requestBatteryExemption(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        )
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
    )
}
