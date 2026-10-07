package com.erictran.sleepsounds

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Listens to the night's audio as it arrives and saves the interesting parts as clips.
 *
 * Low rumble is filtered out first: it carries nothing the classifier uses and is most of the
 * background noise in a bedroom. Twice a second the last second of audio is classified.
 * Talking and laughing become one clip per outburst, with a few seconds of tail and up to 30
 * seconds of what came before, because the start of an outburst often scores too low to be
 * caught. Snoring is merged: snores less than
 * two minutes apart belong to one episode, which is stored as a single event with a 30-second
 * sample taken from its loudest stretch.
 *
 * Not thread safe; call everything from the recording thread.
 */
class SoundAnalyzer(
    private val dir: File,
    private val nightStartedAt: Long,
    private val classifier: YamnetClassifier,
    private val onEvent: (SoundEvent) -> Unit = {},
) {
    /** Total length of the gaps so far, so that event times stay on the wall clock. */
    var lostMs = 0L

    val events = mutableListOf<SoundEvent>()

    private val ring = ShortArray(RING_SAMPLES)
    private val window = FloatArray(YamnetClassifier.WINDOW_SAMPLES)
    private val scratch = ShortArray(RING_SAMPLES)
    private val highPass = HighPass()
    private var filtered = ShortArray(0)
    private val scoreLog = File(dir, "scores.csv").bufferedWriter()
    private var total = 0L
    private var lastWindowAt = 0L
    private var clipIndex = 0
    private var voice: VoiceClip? = null
    private var lastVoiceEnd = 0L
    private var previousWasCough = false
    private var snore: SnoreEpisode? = null
    private var previousWasSnore = false

    /** FAINT_SNORE is too weak to count on its own but is taken as snoring next to clear snores. */
    private enum class Kind { NONE, TALK, LAUGH, COUGH, SNORE, FAINT_SNORE }

    /** [clipStart] is where the saved audio begins; [startSample] is where the voice was first heard. */
    private class VoiceClip(val encoder: ClipEncoder, val part: File, val clipStart: Long, val startSample: Long) {
        var talkWindows = 0
        var laughWindows = 0
        var coughWindows = 0
        var coughs = 0
        var lastHeardAt = 0L
        var peak = 0
        var floorDb = 0.0
    }

    private class SnoreEpisode(val startSample: Long) {
        var lastHeardAt = 0L
        var windows = 0
        var clearWindows = 0
        var bursts = 0
        var floorDb = 0.0

        // The episode is cut into 30-second blocks and only the loudest one so far is kept.
        var block = ShortArray(SNORE_SAMPLE_SAMPLES)
        var blockLength = 0
        var blockEnergy = 0.0
        var best = ShortArray(SNORE_SAMPLE_SAMPLES)
        var bestLength = 0
        var bestEnergy = 0.0

        fun keepBlockIfLouder() {
            if (blockEnergy > bestEnergy) {
                val previous = best
                best = block
                bestLength = blockLength
                bestEnergy = blockEnergy
                block = previous
            }
            blockLength = 0
            blockEnergy = 0.0
        }
    }

    init {
        scoreLog.write("ms,level_db,talk,laugh,snore,cough\n")
    }

    fun process(raw: ShortArray, count: Int) {
        if (filtered.size < count) filtered = ShortArray(count)
        val pcm = filtered
        highPass.filter(raw, pcm, count)
        for (i in 0 until count) ring[((total + i) % RING_SAMPLES).toInt()] = pcm[i]
        total += count

        voice?.let {
            it.encoder.write(pcm, count)
            it.peak = max(it.peak, peakOf(pcm, 0, count))
        }
        snore?.let { capture(it, pcm, count) }

        if (total >= YamnetClassifier.WINDOW_SAMPLES && total - lastWindowAt >= HOP_SAMPLES) {
            lastWindowAt = total
            analyseWindow()
        }
    }

    /** Closes whatever is still open. Call once when recording stops. */
    fun finish() {
        closeVoice()
        closeSnore()
        scoreLog.close()
    }

    private fun analyseWindow() {
        val length = YamnetClassifier.WINDOW_SAMPLES
        copyRecent(length, scratch)
        var sumSquares = 0.0
        for (i in 0 until length) sumSquares += scratch[i].toDouble() * scratch[i]
        val meanSquare = sumSquares / length / (FULL_SCALE * FULL_SCALE)
        val rms = sqrt(meanSquare)

        // Sounds across a bedroom arrive far quieter than the model was trained on, so bring
        // each window up to a normal level first. The cap stops pure silence being amplified
        // into something that sounds like an event.
        val gain = if (rms > 0) min(TARGET_RMS / rms, MAX_GAIN) else 1.0
        val scale = (gain / FULL_SCALE).toFloat()
        for (i in 0 until length) window[i] = (scratch[i] * scale).coerceIn(-1f, 1f)

        val scores = classifier.classify(window)
        val kind = when {
            scores.snore >= SNORE_THRESHOLD -> Kind.SNORE
            scores.cough >= COUGH_THRESHOLD -> Kind.COUGH
            scores.laugh >= LAUGH_THRESHOLD -> Kind.LAUGH
            scores.talk >= TALK_THRESHOLD -> Kind.TALK
            scores.snore >= FAINT_SNORE_THRESHOLD -> Kind.FAINT_SNORE
            else -> Kind.NONE
        }
        val levelDb = if (meanSquare > 0) 10 * log10(meanSquare) else -100.0
        scoreLog.write(
            String.format(
                Locale.US, "%d,%.0f,%.2f,%.2f,%.2f,%.2f\n",
                AudioConfig.samplesToMs(total), levelDb, scores.talk, scores.laugh, scores.snore, scores.cough,
            ),
        )

        trackVoice(kind, levelDb)
        trackSnore(kind, meanSquare, levelDb)
    }

    private fun trackVoice(kind: Kind, levelDb: Double) {
        if (kind == Kind.TALK || kind == Kind.LAUGH || kind == Kind.COUGH) {
            val clip = voice ?: openVoice()
            when (kind) {
                Kind.LAUGH -> clip.laughWindows++
                Kind.COUGH -> {
                    if (!previousWasCough) clip.coughs++
                    clip.coughWindows++
                }
                else -> clip.talkWindows++
            }
            clip.lastHeardAt = total
        }
        previousWasCough = kind == Kind.COUGH
        val clip = voice ?: return
        clip.floorDb = min(clip.floorDb, levelDb)
        if (total - clip.lastHeardAt >= VOICE_TAIL_SAMPLES || total - clip.startSample >= VOICE_MAX_SAMPLES) closeVoice()
    }

    private fun openVoice(): VoiceClip {
        val heardFrom = max(0L, total - YamnetClassifier.WINDOW_SAMPLES - VOICE_LEAD_IN_SAMPLES)
        // Reach back up to 30 s, but not into the previous voice clip.
        val clipStart = maxOf(0L, lastVoiceEnd, total - YamnetClassifier.WINDOW_SAMPLES - VOICE_BEFORE_SAMPLES)
        val length = (total - clipStart).toInt()
        val part = NightStore.partFile(dir, clipIndex++)
        val clip = VoiceClip(ClipEncoder(part, AudioConfig.SAMPLE_RATE), part, clipStart, max(heardFrom, clipStart))
        copyRecent(length, scratch)
        clip.encoder.write(scratch, length)
        clip.peak = peakOf(scratch, 0, length)
        voice = clip
        return clip
    }

    private fun closeVoice() {
        val clip = voice ?: return
        voice = null
        lastVoiceEnd = total
        clip.encoder.finish()
        // Laughing and coughing win over talking because the model also scores both as speech:
        // a coughing fit flips between "cough" and "speech" from one half second to the next.
        // Real speech almost never scores as a cough, so cough hits at half the speech hits
        // mean coughing, while a long sleep-talk with one cough in it stays talking.
        val type = when {
            clip.laughWindows >= MIN_LAUGH_WINDOWS -> SoundType.LAUGHING
            clip.coughWindows > 0 && clip.coughWindows * 2 >= clip.talkWindows -> SoundType.COUGHING
            else -> SoundType.TALKING
        }
        // A hit or two of "speech" on their own are usually bedding rustle. A cough is short, but
        // the model rarely calls anything else a cough, so one clear hit is enough.
        val enough = if (type == SoundType.COUGHING) clip.coughWindows >= MIN_COUGH_WINDOWS
        else clip.talkWindows + clip.laughWindows + clip.coughWindows >= MIN_VOICE_WINDOWS
        if (!enough) {
            clip.part.delete()
            return
        }
        addEvent(
            SoundEvent(
                type = type,
                startedAt = wallTimeAt(clip.startSample),
                durationMs = AudioConfig.samplesToMs(total - clip.startSample),
                clip = NightStore.finalise(clip.part),
                clipMs = AudioConfig.samplesToMs(total - clip.clipStart),
                leadInMs = AudioConfig.samplesToMs(clip.startSample - clip.clipStart),
                peakDb = peakDb(clip.peak),
                floorDb = clip.floorDb.toFloat(),
                count = if (type == SoundType.COUGHING) clip.coughs else 0,
            ),
        )
    }

    private fun trackSnore(kind: Kind, meanSquare: Double, levelDb: Double) {
        val heard = kind == Kind.SNORE || kind == Kind.FAINT_SNORE
        if (heard) {
            val episode = snore ?: openSnore()
            episode.windows++
            if (kind == Kind.SNORE) episode.clearWindows++
            if (!previousWasSnore) episode.bursts++
            episode.lastHeardAt = total
            episode.blockEnergy += meanSquare
        }
        previousWasSnore = heard
        val episode = snore ?: return
        episode.floorDb = min(episode.floorDb, levelDb)
        if (total - episode.lastHeardAt >= SNORE_MERGE_SAMPLES) closeSnore()
    }

    private fun openSnore(): SnoreEpisode {
        val length = YamnetClassifier.WINDOW_SAMPLES
        val episode = SnoreEpisode(total - length)
        copyRecent(length, episode.block)
        episode.blockLength = length
        snore = episode
        return episode
    }

    private fun capture(episode: SnoreEpisode, pcm: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val n = min(count - offset, SNORE_SAMPLE_SAMPLES - episode.blockLength)
            System.arraycopy(pcm, offset, episode.block, episode.blockLength, n)
            episode.blockLength += n
            offset += n
            if (episode.blockLength == SNORE_SAMPLE_SAMPLES) episode.keepBlockIfLouder()
        }
    }

    private fun closeSnore() {
        val episode = snore ?: return
        snore = null
        episode.keepBlockIfLouder()
        // A couple of hits, or only faint ones, are more likely a snort or heavy breathing than snoring.
        if (episode.windows < MIN_SNORE_WINDOWS || episode.clearWindows < MIN_CLEAR_SNORE_WINDOWS) return
        if (episode.bestLength == 0) return

        val part = NightStore.partFile(dir, clipIndex++)
        ClipEncoder(part, AudioConfig.SAMPLE_RATE).apply {
            write(episode.best, episode.bestLength)
            finish()
        }
        addEvent(
            SoundEvent(
                type = SoundType.SNORING,
                startedAt = wallTimeAt(episode.startSample),
                durationMs = AudioConfig.samplesToMs(episode.lastHeardAt - episode.startSample),
                clip = NightStore.finalise(part),
                clipMs = AudioConfig.samplesToMs(episode.bestLength.toLong()),
                peakDb = peakDb(peakOf(episode.best, 0, episode.bestLength)),
                floorDb = episode.floorDb.toFloat(),
                count = episode.bursts,
            ),
        )
    }

    private fun addEvent(event: SoundEvent) {
        events += event
        NightStore.writeEvents(dir, events)
        onEvent(event)
    }

    /** Copies the most recent [length] samples, oldest first, into the start of [into]. */
    private fun copyRecent(length: Int, into: ShortArray) {
        val start = total - length
        for (i in 0 until length) into[i] = ring[((start + i) % RING_SAMPLES).toInt()]
    }

    private fun wallTimeAt(sample: Long) = nightStartedAt + AudioConfig.samplesToMs(sample) + lostMs

    private fun peakOf(pcm: ShortArray, from: Int, count: Int): Int {
        var peak = 0
        for (i in from until from + count) peak = max(peak, abs(pcm[i].toInt()))
        return peak
    }

    private fun peakDb(peak: Int): Float = if (peak > 0) (20 * log10(peak / FULL_SCALE)).toFloat() else -100f

    private companion object {
        const val FULL_SCALE = 32768.0
        const val HOP_SAMPLES = AudioConfig.SAMPLE_RATE / 2
        const val RING_SAMPLES = AudioConfig.SAMPLE_RATE * 40

        // Tuned on one real night so far (2 October 2026).
        const val TALK_THRESHOLD = 0.5f
        const val LAUGH_THRESHOLD = 0.1f
        const val SNORE_THRESHOLD = 0.3f
        const val FAINT_SNORE_THRESHOLD = 0.1f

        // Chosen from sample recordings only; at bedroom distance quiet coughs often score as speech.
        const val COUGH_THRESHOLD = 0.5f

        // Bring each window to about -25 dBFS, boosting by at most 30 dB.
        const val TARGET_RMS = 0.056
        const val MAX_GAIN = 31.6

        const val VOICE_LEAD_IN_SAMPLES = AudioConfig.SAMPLE_RATE * 3
        const val VOICE_BEFORE_SAMPLES = AudioConfig.SAMPLE_RATE * 30
        const val VOICE_TAIL_SAMPLES = AudioConfig.SAMPLE_RATE * 4
        const val VOICE_MAX_SAMPLES = AudioConfig.SAMPLE_RATE * 120
        const val MIN_VOICE_WINDOWS = 3
        const val MIN_LAUGH_WINDOWS = 2
        const val MIN_COUGH_WINDOWS = 1

        const val SNORE_MERGE_SAMPLES = AudioConfig.SAMPLE_RATE * 120
        const val SNORE_SAMPLE_SAMPLES = AudioConfig.SAMPLE_RATE * 30
        const val MIN_SNORE_WINDOWS = 4
        const val MIN_CLEAR_SNORE_WINDOWS = 2
    }
}
