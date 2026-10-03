package com.erictran.sleepsounds

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.IOException

/** Plays one clip at a time. Main thread only. */
class ClipPlayer {
    data class State(val clip: File? = null, val playing: Boolean = false, val positionMs: Long = 0)

    val state = MutableStateFlow(State())

    private var player: MediaPlayer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var clip: File? = null

    /**
     * Starts [event]'s clip where the sound was detected, or pauses and resumes it if it is the
     * one already loaded. [fromStart] plays the audio saved from before the detection too.
     */
    fun toggle(event: SoundEvent, fromStart: Boolean = false) {
        val current = player
        if (current != null && clip == event.clip && !fromStart) {
            if (current.isPlaying) current.pause() else current.start()
            publish()
            return
        }
        stop()
        val next = MediaPlayer()
        try {
            next.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            next.setDataSource(event.clip.path)
            next.prepare()
        } catch (e: IOException) {
            Log.w(TAG, "Could not play ${event.clip}", e)
            next.release()
            return
        }
        boost(next, event)
        if (!fromStart) next.seekTo(event.leadInMs.coerceIn(0, next.duration.toLong()).toInt())
        next.setOnCompletionListener { stop() }
        next.start()
        player = next
        clip = event.clip
        publish()
    }

    /**
     * Sounds from across a bedroom are recorded very quietly. Raise the clip towards full
     * volume without touching the saved file, but stop before the room's background noise
     * turns into loud hiss.
     */
    private fun boost(target: MediaPlayer, event: SoundEvent) {
        if (event.peakDb.isNaN()) return
        val noiseLimitDb = if (event.floorDb.isNaN()) UNKNOWN_FLOOR_BOOST_DB else MAX_FLOOR_DB - event.floorDb
        val gainDb = minOf(TARGET_PEAK_DB - event.peakDb, noiseLimitDb).coerceIn(0f, MAX_BOOST_DB)
        if (gainDb < 1f) return
        enhancer = runCatching {
            LoudnessEnhancer(target.audioSessionId).apply {
                setTargetGain((gainDb * 100).toInt())
                enabled = true
            }
        }.onFailure { Log.w(TAG, "No loudness boost on this phone", it) }.getOrNull()
    }

    fun stop() {
        enhancer?.release()
        enhancer = null
        player?.release()
        player = null
        clip = null
        state.value = State()
    }

    /** Refreshes [state] with the current playback position. */
    fun publish() {
        val current = player ?: return
        state.value = State(clip, current.isPlaying, current.currentPosition.toLong())
    }

    private companion object {
        const val TAG = "ClipPlayer"
        const val TARGET_PEAK_DB = -3f
        const val MAX_BOOST_DB = 30f

        /** Background noise is never raised above this level. */
        const val MAX_FLOOR_DB = -45f

        /** Boost for clips saved before the noise level was recorded. */
        const val UNKNOWN_FLOOR_BOOST_DB = 15f
    }
}
