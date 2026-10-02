package com.erictran.sleepsounds

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs a short made-up "night" through the real detection pipeline on a device.
 *
 * The audio is `night.wav` in the androidTest assets (16 kHz mono 16-bit), which is not in the
 * repository because the source recordings cannot be redistributed. Its layout: speech at 20 s,
 * laughter at 60 s, snoring from 90 s to 150 s and again from 210 s to 240 s, speech at 400 s.
 * The test is skipped when the file is missing.
 *
 * Pass `-e keep true` to leave the result in the app's storage so it shows up in the UI.
 */
@RunWith(AndroidJUnit4::class)
class SoundAnalyzerTest {
    @Test
    fun findsVoiceClipsAndMergesSnoringIntoOneEpisode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        assumeTrue("night.wav is not bundled", assets.list("").orEmpty().contains("night.wav"))
        val keep = InstrumentationRegistry.getArguments().getString("keep") == "true"

        val bytes = assets.open("night.wav").use { it.readBytes() }
        val pcm = ShortArray((bytes.size - WAV_HEADER_BYTES) / 2)
        ByteBuffer.wrap(bytes, WAV_HEADER_BYTES, pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        val durationMs = AudioConfig.samplesToMs(pcm.size.toLong())

        val startedAt = System.currentTimeMillis() - durationMs
        val dir = if (keep) NightStore.create(context, startedAt) else File(context.cacheDir, "analyzer-test").apply {
            deleteRecursively()
            mkdirs()
        }

        val classifier = YamnetClassifier(context)
        val analyzer = SoundAnalyzer(dir, startedAt, classifier)
        val chunk = ShortArray(AudioConfig.SAMPLE_RATE / 10)
        val began = System.nanoTime()
        var offset = 0
        while (offset < pcm.size) {
            val n = minOf(chunk.size, pcm.size - offset)
            System.arraycopy(pcm, offset, chunk, 0, n)
            analyzer.process(chunk, n)
            offset += n
        }
        analyzer.finish()
        classifier.close()
        NightStore.writeMeta(dir, startedAt, startedAt + durationMs, pcm.size.toLong(), emptyList())
        Log.i(TAG, "Analysed ${durationMs / 1000} s of audio in ${(System.nanoTime() - began) / 1_000_000} ms")

        val events = analyzer.events
        events.forEach {
            Log.i(TAG, "${it.type} at ${(it.startedAt - startedAt) / 1000} s for ${it.durationMs / 1000} s, " +
                "clip ${it.clipMs / 1000} s, peak ${it.peakDb} dB, count ${it.count}, ${it.clip.length()} bytes")
        }

        val snoring = events.filter { it.type == SoundType.SNORING }
        assertEquals("the two snoring bouts are under two minutes apart, so they are one episode", 1, snoring.size)
        assertTrue("episode spans both bouts", snoring[0].durationMs in 130_000..160_000)
        assertTrue("sample is at most 30 s", snoring[0].clipMs in 5_000..30_000)

        val voices = events.filter { it.type != SoundType.SNORING }
        assertEquals("speech, laughter, speech", 3, voices.size)
        assertEquals(SoundType.TALKING, voices.first().type)
        assertEquals(SoundType.TALKING, voices.last().type)
        events.forEach { assertTrue("${it.clip} has audio", it.clip.length() > 1000) }

        if (!keep) dir.deleteRecursively()
    }

    private companion object {
        const val TAG = "SoundAnalyzerTest"
        const val WAV_HEADER_BYTES = 44
    }
}
