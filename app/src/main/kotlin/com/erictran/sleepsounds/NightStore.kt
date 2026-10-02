package com.erictran.sleepsounds

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object AudioConfig {
    const val SAMPLE_RATE = 16_000
    const val SEGMENT_MS = 10 * 60 * 1000L
    const val SEGMENT_SAMPLES = SAMPLE_RATE * SEGMENT_MS / 1000
}

/** A stretch where the phone delivered no audio. [atMs] is the offset into the recorded audio. */
data class Gap(val atMs: Long, val lengthMs: Long)

data class Night(
    val id: String,
    val dir: File,
    val startedAt: Long,
    /** Null when recording was cut off before it could be stopped cleanly. */
    val endedAt: Long?,
    val durationMs: Long,
    val gaps: List<Gap>,
    val segments: List<File>,
    val sizeBytes: Long,
)

/**
 * One folder per night under the app's private storage:
 * `seg_000.m4a`, `seg_001.m4a`, ... plus `night.json`. The segment being written carries a
 * `.part` suffix until it is finalised, because an unfinalised MP4 cannot be played.
 */
object NightStore {
    private const val META = "night.json"
    private const val PART = ".part"
    private val idFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

    private fun root(context: Context) = File(context.filesDir, "nights")

    fun create(context: Context, startedAt: Long): File {
        val id = Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).format(idFormat)
        return File(root(context), id).apply { mkdirs() }
    }

    fun partFile(dir: File, index: Int) = File(dir, "seg_%03d.m4a$PART".format(index))

    fun finalise(part: File): Boolean =
        part.renameTo(File(part.parentFile, part.name.removeSuffix(PART)))

    fun writeMeta(dir: File, startedAt: Long, endedAt: Long?, samples: Long, gaps: List<Gap>) {
        val json = JSONObject()
            .put("startedAt", startedAt)
            .put("samples", samples)
            .put("gaps", JSONArray(gaps.map { JSONObject().put("atMs", it.atMs).put("lengthMs", it.lengthMs) }))
        if (endedAt != null) json.put("endedAt", endedAt)
        val tmp = File(dir, "$META.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(File(dir, META))
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
        val segments = dir.listFiles { f -> f.name.endsWith(".m4a") }.orEmpty().sortedBy { it.name }
        if (segments.isEmpty()) {
            dir.deleteRecursively()
            return null
        }
        val meta = runCatching { JSONObject(File(dir, META).readText()) }.getOrNull() ?: JSONObject()
        val endedAt = if (meta.has("endedAt")) meta.getLong("endedAt") else null
        val gapsJson = meta.optJSONArray("gaps") ?: JSONArray()
        return Night(
            id = dir.name,
            dir = dir,
            startedAt = meta.optLong("startedAt", dir.lastModified()),
            endedAt = endedAt,
            // A cut-off night only keeps its finalised segments, and those are all full length.
            durationMs = if (endedAt != null) meta.optLong("samples") * 1000 / AudioConfig.SAMPLE_RATE
            else segments.size * AudioConfig.SEGMENT_MS,
            gaps = List(gapsJson.length()) {
                val g = gapsJson.getJSONObject(it)
                Gap(g.getLong("atMs"), g.getLong("lengthMs"))
            },
            segments = segments,
            sizeBytes = segments.sumOf { it.length() },
        )
    }

    fun delete(night: Night) {
        night.dir.deleteRecursively()
    }
}
