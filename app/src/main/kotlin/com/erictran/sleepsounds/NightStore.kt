package com.erictran.sleepsounds

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object AudioConfig {
    const val SAMPLE_RATE = 16_000

    /** Length of the full-night segments that version 0.1 stored instead of clips. */
    const val LEGACY_SEGMENT_MS = 10 * 60 * 1000L

    fun samplesToMs(samples: Long) = samples * 1000 / SAMPLE_RATE
}

/** A stretch where the phone delivered no audio. [atMs] is the offset into the recorded audio. */
data class Gap(val atMs: Long, val lengthMs: Long)

enum class SoundType(val label: String) {
    TALKING("Talking"),
    LAUGHING("Laughing"),
    SNORING("Snoring"),

    /** A piece of a full-night recording made by version 0.1. */
    RECORDING("Full recording"),
}

data class SoundEvent(
    val type: SoundType,
    /** Clock time the sound began. */
    val startedAt: Long,
    /** How long the sound went on. For snoring this is the whole merged episode. */
    val durationMs: Long,
    val clip: File,
    /** Length of the saved audio, which for snoring is only a sample of the episode. */
    val clipMs: Long,
    /** Loudest sample in the clip in dBFS, or NaN when unknown. Used to boost quiet clips on playback. */
    val peakDb: Float,
    /** Rough number of snores in a snoring episode, 0 for other types. */
    val count: Int,
)

data class Night(
    val id: String,
    val dir: File,
    val startedAt: Long,
    /** Null when recording was cut off before it could be stopped cleanly. */
    val endedAt: Long?,
    val durationMs: Long,
    val gaps: List<Gap>,
    val events: List<SoundEvent>,
    val sizeBytes: Long,
)

/**
 * One folder per night under the app's private storage: `night.json`, `events.json`, one
 * `clip_NNNN.m4a` per event and `scores.csv` (what the classifier heard, for tuning). A clip
 * being written carries a `.part` suffix until it is finalised, because an unfinalised MP4
 * cannot be played.
 */
object NightStore {
    private const val META = "night.json"
    private const val EVENTS = "events.json"
    private const val PART = ".part"
    private val idFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

    private fun root(context: Context) = File(context.filesDir, "nights")

    fun create(context: Context, startedAt: Long): File {
        val id = Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).format(idFormat)
        return File(root(context), id).apply { mkdirs() }
    }

    private fun startTimeFromId(id: String): Long? = runCatching {
        LocalDateTime.parse(id, idFormat).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()

    fun partFile(dir: File, index: Int) = File(dir, "clip_%04d.m4a$PART".format(index))

    /** Renames a finished `.part` file to its final name and returns it. */
    fun finalise(part: File): File =
        File(part.parentFile, part.name.removeSuffix(PART)).also { part.renameTo(it) }

    fun writeMeta(dir: File, startedAt: Long, endedAt: Long?, samples: Long, gaps: List<Gap>) {
        val json = JSONObject()
            .put("startedAt", startedAt)
            .put("samples", samples)
            .put("gaps", JSONArray(gaps.map { JSONObject().put("atMs", it.atMs).put("lengthMs", it.lengthMs) }))
        if (endedAt != null) json.put("endedAt", endedAt)
        writeAtomically(File(dir, META), json.toString())
    }

    fun writeEvents(dir: File, events: List<SoundEvent>) {
        val json = JSONArray(
            events.map {
                JSONObject()
                    .put("type", it.type.name)
                    .put("startedAt", it.startedAt)
                    .put("durationMs", it.durationMs)
                    .put("file", it.clip.name)
                    .put("clipMs", it.clipMs)
                    .put("count", it.count)
                    .apply { if (!it.peakDb.isNaN()) put("peakDb", it.peakDb.toDouble()) }
            },
        )
        writeAtomically(File(dir, EVENTS), json.toString())
    }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        tmp.renameTo(target)
    }

    /**
     * Loads every stored night, newest first. [activeId] is the night being recorded right now,
     * which is skipped. Leftovers from a recording that was killed are tidied up on the way.
     */
    fun loadAll(context: Context, activeId: String?): List<Night> =
        root(context).listFiles { f -> f.isDirectory && f.name != activeId }
            .orEmpty()
            .mapNotNull(::load)
            .sortedByDescending { it.startedAt }

    private fun load(dir: File): Night? {
        dir.listFiles { f -> f.name.endsWith(PART) }?.forEach { it.delete() }
        var meta = runCatching { JSONObject(File(dir, META).readText()) }.getOrNull()
        if (meta == null) {
            // Only an empty leftover is removed; saved clips are kept even if their details are unreadable.
            if (dir.listFiles { f -> f.name.endsWith(".m4a") }.isNullOrEmpty()) {
                dir.deleteRecursively()
                return null
            }
            meta = JSONObject()
        }
        val startedAt = meta.optLong("startedAt", startTimeFromId(dir.name) ?: dir.lastModified())
        val endedAt = if (meta.has("endedAt")) meta.getLong("endedAt") else null
        val durationMs = AudioConfig.samplesToMs(meta.optLong("samples"))
        val gapsJson = meta.optJSONArray("gaps") ?: JSONArray()
        val eventsFile = File(dir, EVENTS)
        return Night(
            id = dir.name,
            dir = dir,
            startedAt = startedAt,
            endedAt = endedAt,
            durationMs = durationMs,
            gaps = List(gapsJson.length()) {
                val g = gapsJson.getJSONObject(it)
                Gap(g.getLong("atMs"), g.getLong("lengthMs"))
            },
            events = if (eventsFile.exists()) readEvents(dir, eventsFile) else legacyEvents(dir, startedAt, durationMs),
            sizeBytes = dir.listFiles().orEmpty().sumOf { it.length() },
        )
    }

    private fun readEvents(dir: File, file: File): List<SoundEvent> {
        val json = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return emptyList()
        return List(json.length()) { json.getJSONObject(it) }.mapNotNull { e ->
            val clip = File(dir, e.getString("file"))
            val type = runCatching { SoundType.valueOf(e.getString("type")) }.getOrNull()
            if (type == null || !clip.exists()) return@mapNotNull null
            SoundEvent(
                type = type,
                startedAt = e.getLong("startedAt"),
                durationMs = e.getLong("durationMs"),
                clip = clip,
                clipMs = e.getLong("clipMs"),
                peakDb = e.optDouble("peakDb").toFloat(),
                count = e.optInt("count"),
            )
        }
    }

    /** Version 0.1 kept the whole night as 10-minute `seg_NNN.m4a` files; show each as one clip. */
    private fun legacyEvents(dir: File, startedAt: Long, durationMs: Long): List<SoundEvent> {
        val segments = dir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".m4a") }
            .orEmpty().sortedBy { it.name }
        return segments.mapIndexed { i, file ->
            val offset = i * AudioConfig.LEGACY_SEGMENT_MS
            val length = if (i == segments.lastIndex && durationMs > offset) durationMs - offset
            else AudioConfig.LEGACY_SEGMENT_MS
            SoundEvent(SoundType.RECORDING, startedAt + offset, length, file, length, Float.NaN, 0)
        }
    }

    fun delete(night: Night) {
        night.dir.deleteRecursively()
    }

    fun deleteEvent(night: Night, event: SoundEvent) {
        event.clip.delete()
        writeEvents(night.dir, night.events - event)
    }
}
