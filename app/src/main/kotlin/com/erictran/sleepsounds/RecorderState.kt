package com.erictran.sleepsounds

import kotlinx.coroutines.flow.MutableStateFlow

/** What the recording service is doing, shared in-process with the UI. */
object RecorderState {
    data class Status(
        val recording: Boolean = false,
        val nightId: String? = null,
        /** [android.os.SystemClock.elapsedRealtime] at start, for a timer that survives clock changes. */
        val startedAtElapsed: Long = 0,
        val gaps: Int = 0,
        /** Why the last recording stopped on its own, if it did. */
        val error: String? = null,
    )

    val status = MutableStateFlow(Status())

    /** Input level of the latest 100 ms in dBFS, from about -90 (silence) to 0 (clipping). */
    val levelDb = MutableStateFlow(SILENCE_DB)

    const val SILENCE_DB = -90f
}
