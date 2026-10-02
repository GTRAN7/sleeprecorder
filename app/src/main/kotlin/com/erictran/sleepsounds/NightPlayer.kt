package com.erictran.sleepsounds

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException

/** Plays a night's segments back to back as if they were one recording. Main thread only. */
class NightPlayer {
    data class State(val nightId: String? = null, val playing: Boolean = false, val positionMs: Long = 0)

    val state = MutableStateFlow(State())

    private var player: MediaPlayer? = null
    private var night: Night? = null
    private var segmentIndex = 0

    fun toggle(target: Night) {
        val current = player
        if (current == null || night?.id != target.id) {
            play(target, 0)
            return
        }
        if (current.isPlaying) current.pause() else current.start()
        publish()
    }

    fun play(target: Night, positionMs: Long) {
        stop()
        val index = (positionMs / AudioConfig.SEGMENT_MS).toInt().coerceIn(0, target.segments.lastIndex)
        val next = MediaPlayer()
        try {
            next.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            next.setDataSource(target.segments[index].path)
            next.prepare()
        } catch (e: IOException) {
            Log.w(TAG, "Could not play ${target.segments[index]}", e)
            next.release()
            return
        }
        next.seekTo((positionMs - index * AudioConfig.SEGMENT_MS).toInt().coerceIn(0, next.duration))
        next.setOnCompletionListener {
            if (index < target.segments.lastIndex) play(target, (index + 1) * AudioConfig.SEGMENT_MS) else stop()
        }
        next.start()
        player = next
        night = target
        segmentIndex = index
        publish()
    }

    fun stop() {
        player?.release()
        player = null
        night = null
        state.value = State()
    }

    /** Refreshes [state] with the current playback position. */
    fun publish() {
        val current = player ?: return
        state.value = State(
            nightId = night?.id,
            playing = current.isPlaying,
            positionMs = segmentIndex * AudioConfig.SEGMENT_MS + current.currentPosition,
        )
    }

    private companion object {
        const val TAG = "NightPlayer"
    }
}
