package com.erictran.sleepsounds

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val startFormat = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")
private val timeOfDayFormat = DateTimeFormatter.ofPattern("h:mm a")
private val preciseTimeFormat = DateTimeFormatter.ofPattern("h:mm:ss a")

private fun format(epochMs: Long, formatter: DateTimeFormatter): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(formatter)

fun formatStart(epochMs: Long) = format(epochMs, startFormat)

fun formatTimeOfDay(epochMs: Long) = format(epochMs, timeOfDayFormat)

fun formatPreciseTime(epochMs: Long) = format(epochMs, preciseTimeFormat)

fun formatClock(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}

fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds >= 3600 -> "${seconds / 3600} h ${seconds / 60 % 60} min"
        seconds >= 60 && seconds % 60 == 0L -> "${seconds / 60} min"
        seconds >= 60 -> "${seconds / 60} min ${seconds % 60} s"
        else -> "$seconds s"
    }
}

fun countOf(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

/** Clock time at which the night's recording ended, counting the gaps. */
fun Night.endTime(): Long = endedAt ?: (startedAt + durationMs + gaps.sumOf { it.lengthMs })

/** One line saying what was caught, such as "Talking 3 · Snoring 2 (1 h 4 min)". */
fun Night.summary(): String {
    val parts = SoundType.entries.mapNotNull { type ->
        val ofType = events.filter { it.type == type }
        when {
            ofType.isEmpty() -> null
            type == SoundType.SNORING -> "Snoring ${ofType.size} (${formatDuration(ofType.sumOf { it.durationMs })})"
            else -> "${type.label} ${ofType.size}"
        }
    }
    return if (parts.isEmpty()) "Nothing detected" else parts.joinToString(" · ")
}
