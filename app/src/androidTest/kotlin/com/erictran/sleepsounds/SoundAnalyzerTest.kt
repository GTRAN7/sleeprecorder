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
 * Runs recorded audio through the real detection pipeline on a device.
 *
 * The audio lives in the androidTest assets as 16 kHz mono 16-bit WAV files, which are not in
 * the repository: one is built from recordings that cannot be redistributed and the other is
 * from a private bedroom. Each test is skipped when its file is missing.
 *
 * Pass `-e keep true` to leave the results in the app's storage so they show up in the UI.
 */
@RunWith(AndroidJUnit4::class)
class SoundAnalyzerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val keep = InstrumentationRegistry.getArguments().getString("keep") == "true"

    /**
     * `night.wav` is made up: speech at 20 s, laughter at 60 s, snoring from 90 s to 150 s and
     * again from 210 s to 240 s, speech at 400 s.
     */
    @Test
    fun findsVoiceClipsAndMergesSnoringIntoOneEpisode() {
        val events = analyse("night.wav")

        val snoring = events.filter { it.type == SoundType.SNORING }
        assertEquals("the two snoring bouts are under two minutes apart, so they are one episode", 1, snoring.size)
        assertTrue("episode spans both bouts", snoring[0].durationMs in 130_000..160_000)
        assertTrue("sample is at most 30 s", snoring[0].clipMs in 5_000..30_000)

        val voices = events.filter { it.type != SoundType.SNORING }
        assertEquals("speech, laughter, speech", 3, voices.size)
        assertEquals(SoundType.TALKING, voices.first().type)
        assertEquals(SoundType.TALKING, voices.last().type)
        assertTrue("first clip keeps everything before it, from the start of the night", voices[0].leadInMs in 12_000..17_000)
        assertTrue("second clip reaches back to the end of the first", voices[1].leadInMs in 20_000..30_000)
        assertTrue("last clip keeps the full 30 s before", voices[2].leadInMs in 26_000..28_000)
        voices.forEach { assertTrue("clip holds lead-in plus the sound", it.clipMs == it.leadInMs + it.durationMs) }
        events.forEach { assertTrue("${it.clip} has audio", it.clip.length() > 1000) }
    }

    /**
     * `real.wav` is clips the app saved on a real night, 20 s apart: bedding rustle that was
     * wrongly saved as talking (twice), people talking outside the room, then snoring.
     */
    @Test
    fun ignoresRustlingButKeepsRealTalkingAndSnoring() {
        val events = analyse("real.wav")

        val talking = events.filter { it.type == SoundType.TALKING }
        assertEquals("only the real conversation", 1, talking.size)
        assertTrue("it is the third clip", talking[0].durationMs > 15_000)
        assertEquals(1, events.count { it.type == SoundType.SNORING })
        assertEquals(2, events.size)
    }

    private fun analyse(asset: String): List<SoundEvent> {
        val assets = instrumentation.context.assets
        assumeTrue("$asset is not bundled", assets.list("").orEmpty().contains(asset))

        val bytes = assets.open(asset).use { it.readBytes() }
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
        Log.i(TAG, "$asset: analysed ${durationMs / 1000} s of audio in ${(System.nanoTime() - began) / 1_000_000} ms")
        analyzer.events.forEach {
            Log.i(TAG, "${it.type} at ${(it.startedAt - startedAt) / 1000} s for ${it.durationMs / 1000} s, " +
                "clip ${it.clipMs / 1000} s, peak ${it.peakDb} dB, floor ${it.floorDb} dB, count ${it.count}")
        }
        return analyzer.events.toList()
    }

    private companion object {
        const val TAG = "SoundAnalyzerTest"
        const val WAV_HEADER_BYTES = 44
    }
}
