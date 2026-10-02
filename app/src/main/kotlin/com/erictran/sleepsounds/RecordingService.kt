package com.erictran.sleepsounds

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.log10

/**
 * Records the microphone for the whole night. Runs as a foreground service holding a partial
 * wake lock, so it keeps going with the screen locked.
 */
class RecordingService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> running = false
        }
        if (worker == null) stopSelf()
        // Not sticky: Android does not let a restarted background service reopen the microphone.
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (worker != null) return
        val startedAt = System.currentTimeMillis()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(startedAt), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Not allowed to start recording", e)
            RecorderState.status.value = RecorderState.Status(error = "Android did not allow recording to start.")
            return
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepSounds:recording")
            .apply { acquire(WAKE_LOCK_LIMIT_MS) }

        running = true
        worker = Thread({ record(startedAt) }, "recorder").apply { start() }
    }

    private fun record(startedAt: Long) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val dir = NightStore.create(this, startedAt)
        val gaps = mutableListOf<Gap>()
        var samples = 0L
        var error: String? = null
        var audio: AudioRecord? = null
        var encoder: SegmentEncoder? = null
        var part: File? = null

        fun closeSegment() {
            val open = encoder ?: return
            encoder = null
            open.finish()
            part?.let(NightStore::finalise)
        }

        try {
            NightStore.writeMeta(dir, startedAt, null, 0, gaps)
            val startedAtElapsed = SystemClock.elapsedRealtime()
            RecorderState.status.value =
                RecorderState.Status(recording = true, nightId = dir.name, startedAtElapsed = startedAtElapsed)

            val buffer = ShortArray(AudioConfig.SAMPLE_RATE / 10)
            var segmentIndex = 0
            var segmentSamples = 0L
            // How far wall time is ahead of recorded audio. It creeps slowly as the two clocks
            // drift, so follow it and treat only a sudden jump as lost audio.
            var lagMs = 0.0

            while (running) {
                val input = audio ?: openMicrophone().also { audio = it }
                val read = input?.read(buffer, 0, buffer.size) ?: 0
                if (read <= 0) {
                    Log.w(TAG, "Microphone unavailable (read=$read), retrying")
                    input?.release()
                    audio = null
                    SystemClock.sleep(MIC_RETRY_MS)
                    continue
                }

                var segment = encoder
                if (segment == null) {
                    if (dir.usableSpace < MIN_FREE_BYTES) {
                        error = "Stopped because the phone is almost out of storage."
                        break
                    }
                    val file = NightStore.partFile(dir, segmentIndex)
                    segment = SegmentEncoder(file, AudioConfig.SAMPLE_RATE)
                    part = file
                    encoder = segment
                }
                segment.write(buffer, read)
                val firstRead = samples == 0L
                samples += read
                segmentSamples += read
                RecorderState.levelDb.value = levelDb(buffer, read)

                val audioMs = samples * 1000 / AudioConfig.SAMPLE_RATE
                val lagNow = (SystemClock.elapsedRealtime() - startedAtElapsed - audioMs).toDouble()
                if (firstRead) {
                    // The microphone takes a moment to open; that is not lost audio.
                    lagMs = lagNow
                } else if (lagNow - lagMs > GAP_THRESHOLD_MS) {
                    gaps += Gap(audioMs, (lagNow - lagMs).toLong())
                    lagMs = lagNow
                    Log.w(TAG, "Gap of ${gaps.last().lengthMs} ms at $audioMs ms")
                    RecorderState.status.value = RecorderState.status.value.copy(gaps = gaps.size)
                } else {
                    lagMs += (lagNow - lagMs) * LAG_SMOOTHING
                }

                if (segmentSamples >= AudioConfig.SEGMENT_SAMPLES) {
                    closeSegment()
                    segmentIndex++
                    segmentSamples = 0
                    NightStore.writeMeta(dir, startedAt, null, samples, gaps)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording failed", e)
            error = "Recording stopped unexpectedly: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            audio?.release()
            runCatching {
                closeSegment()
                NightStore.writeMeta(dir, startedAt, System.currentTimeMillis(), samples, gaps)
            }.onFailure { Log.e(TAG, "Could not finalise the night", it) }
            RecorderState.levelDb.value = RecorderState.SILENCE_DB
            RecorderState.status.value = RecorderState.Status(error = error)
            mainHandler.post(::shutDown)
        }
    }

    // The service only starts after the UI has been granted the microphone permission.
    @SuppressLint("MissingPermission")
    private fun openMicrophone(): AudioRecord? {
        val minBytes = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        // Two seconds of headroom so a slow segment switch does not drop samples.
        val bufferBytes = maxOf(minBytes, AudioConfig.SAMPLE_RATE * 2 * 2)
        // Unprocessed input keeps quiet sounds that the default source's noise suppression removes.
        val unprocessed = getSystemService(AudioManager::class.java)
            .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val record = try {
            AudioRecord(source, AudioConfig.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not open the microphone", e)
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        record.startRecording()
        return record
    }

    private fun levelDb(pcm: ShortArray, count: Int): Float {
        var sumSquares = 0.0
        for (i in 0 until count) sumSquares += pcm[i].toDouble() * pcm[i]
        val meanSquare = sumSquares / count / (32768.0 * 32768.0)
        if (meanSquare <= 0.0) return RecorderState.SILENCE_DB
        return (10 * log10(meanSquare)).toFloat().coerceAtLeast(RecorderState.SILENCE_DB)
    }

    private fun shutDown() {
        worker = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        running = false
        worker?.join(STOP_TIMEOUT_MS)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun buildNotification(startedAt: Long): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, RecordingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Recording sleep sounds")
            .setContentText("Audio stays on this phone.")
            .setWhen(startedAt)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val ACTION_START = "com.erictran.sleepsounds.START"
        private const val ACTION_STOP = "com.erictran.sleepsounds.STOP"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_LIMIT_MS = 18 * 60 * 60 * 1000L
        private const val MIN_FREE_BYTES = 200L * 1024 * 1024
        private const val MIC_RETRY_MS = 1000L
        private const val STOP_TIMEOUT_MS = 5000L
        private const val GAP_THRESHOLD_MS = 2000
        private const val LAG_SMOOTHING = 0.01

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, RecordingService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
