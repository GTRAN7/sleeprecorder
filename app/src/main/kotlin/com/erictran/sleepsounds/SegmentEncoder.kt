package com.erictran.sleepsounds

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

/** Encodes 16-bit mono PCM to AAC and writes it as one .m4a file. Not thread safe. */
class SegmentEncoder(file: File, private val sampleRate: Int) {
    private val codec = MediaCodec.createEncoderByType(MIME)
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var samplesIn = 0L

    init {
        val format = MediaFormat.createAudioFormat(MIME, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun write(pcm: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index)!!
                buffer.clear()
                val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                val n = minOf(shorts.remaining(), count - offset)
                shorts.put(pcm, offset, n)
                codec.queueInputBuffer(index, 0, n * 2, presentationTimeUs(), 0)
                samplesIn += n
                offset += n
            }
            drain(toEnd = false)
        }
    }

    /** Flushes the encoder and finalises the file. The encoder cannot be used afterwards. */
    fun finish() {
        try {
            while (true) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, presentationTimeUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(toEnd = false)
            }
            drain(toEnd = true)
            codec.stop()
            if (muxerStarted) muxer.stop()
        } finally {
            codec.release()
            muxer.release()
        }
    }

    private fun presentationTimeUs() = samplesIn * 1_000_000 / sampleRate

    private fun drain(toEnd: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (toEnd) TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!toEnd) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val out = codec.getOutputBuffer(index)!!
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && muxerStarted) muxer.writeSampleData(track, out, info)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        const val BIT_RATE = 32_000
        const val TIMEOUT_US = 10_000L
    }
}
