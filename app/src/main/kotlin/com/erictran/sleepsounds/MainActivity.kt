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
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
val Amber = Color(0xFFE8B15A)
val Raised = Color(0xFF1D2433)
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
    surfaceContainer = Color(0xFF10141D),
    surfaceContainerHigh = Color(0xFF1A2030),
    outline = Color(0xFF3A4356),
    error = Color(0xFFE0766C),
    onError = Color(0xFF2B0704),
)

fun SoundType.color(): Color = when (this) {
    // Slots 1-4 of the reference dark categorical palette, in order; checked for colour-blind
    // separation against the card surface. Always shown next to the type's name.
    SoundType.TALKING -> Color(0xFF3987E5)
    SoundType.LAUGHING -> Color(0xFFD95926)
    SoundType.SNORING -> Color(0xFF199E70)
    SoundType.COUGHING -> Color(0xFFC98500)
    SoundType.RECORDING -> Color(0xFF8A93A6)
}

private enum class Tab(val label: String, val icon: Int) {
    RECORD("Record", R.drawable.ic_mic),
    NIGHTS("Nights", R.drawable.ic_moon),
    TRENDS("Trends", R.drawable.ic_chart),
}

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val status by RecorderState.status.collectAsStateWithLifecycle()
    val nights by vm.nights.collectAsStateWithLifecycle()
    val playback by vm.player.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.RECORD) }
    var openNightId by rememberSaveable { mutableStateOf<String?>(null) }
    var micDenied by rememberSaveable { mutableStateOf(false) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        micDenied = result[Manifest.permission.RECORD_AUDIO] != true
        if (!micDenied) {
            // Otherwise playback would end up in the recording.
            vm.player.stop()
            RecordingService.start(context)
        }
    }

    val openNight = nights.firstOrNull { it.id == openNightId }
    if (openNight != null) {
        val close = {
            vm.player.stop()
            openNightId = null
        }
        BackHandler(onBack = close)
        NightScreen(
            night = openNight,
            playback = playback,
            canPlay = !status.recording,
            onBack = close,
            onTogglePlay = vm.player::toggle,
            onDeleteEvent = { vm.delete(openNight, it) },
            onDeleteNight = {
                openNightId = null
                vm.delete(openNight)
            },
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach {
                    NavigationBarItem(
                        selected = tab == it,
                        onClick = { tab = it },
                        icon = { Icon(painterResource(it.icon), contentDescription = null) },
                        label = { Text(it.label) },
                    )
                }
            }
        },
    ) { insets ->
        LazyColumn(
            // Clip below the status bar so scrolled content does not run under its icons.
            modifier = Modifier.fillMaxSize().padding(top = insets.calculateTopPadding()),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = insets.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tab) {
                Tab.RECORD -> {
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
                    item { BatteryCard() }
                    nights.firstOrNull()?.let { latest ->
                        item { SectionTitle("Latest night") }
                        item { NightCard(latest, onClick = { openNightId = latest.id }) }
                    }
                }
                Tab.TRENDS -> item { TrendsScreen(nights) }
                Tab.NIGHTS -> {
                    item { SectionTitle(if (nights.isEmpty()) "Nights" else countOf(nights.size, "night")) }
                    if (nights.isEmpty()) {
                        item {
                            Text(
                                if (status.recording) "This night will appear here when you stop recording."
                                else "No nights yet. Start a recording before you go to sleep.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(4.dp),
                            )
                        }
                    }
                    items(nights, key = { it.id }) { night ->
                        NightCard(night, onClick = { openNightId = night.id })
                    }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
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
                    if (status.gaps == 0) "Listening. You can lock the phone."
                    else "Listening. ${countOf(status.gaps, "gap")} so far.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    LiveCount(SoundType.TALKING, status.talking)
                    LiveCount(SoundType.LAUGHING, status.laughing)
                    LiveCount(SoundType.SNORING, status.snoring)
                    LiveCount(SoundType.COUGHING, status.coughing)
                }
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
private fun LiveCount(type: SoundType, count: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = if (count > 0) type.color() else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(type.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** Shown until the app is exempt from battery optimisation, which otherwise can stop it overnight. */
@Composable
private fun BatteryCard() {
    val context = LocalContext.current
    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    var exempt by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        exempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose {}
    }
    if (exempt) return

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Let it run all night", style = MaterialTheme.typography.titleMedium)
            Text(
                "Battery saving can stop the recording after the screen locks. Allow Sleep Sounds to " +
                    "run in the background without restrictions.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { requestBatteryExemption(context) }, colors = ButtonDefaults.filledTonalButtonColors()) {
                Text("Allow")
            }
        }
    }
}

private const val METER_FLOOR_DB = -70f

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
