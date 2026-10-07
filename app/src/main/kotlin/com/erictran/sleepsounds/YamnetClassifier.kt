package com.erictran.sleepsounds

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Runs YAMNet, Google's general audio classifier, on the phone. The model is bundled in the
 * app, so no audio or result ever leaves the device.
 */
class YamnetClassifier(context: Context) : Closeable {
    /** The strongest score, 0 to 1, among the model's classes for each kind of sound. */
    data class Scores(val talk: Float, val laugh: Float, val snore: Float, val cough: Float)

    private val interpreter: Interpreter
    private val input = ByteBuffer.allocateDirect(WINDOW_SAMPLES * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(CLASS_COUNT * 4).order(ByteOrder.nativeOrder())

    init {
        val model = context.assets.openFd(MODEL_ASSET).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use {
                it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    }

    /** [window] is [WINDOW_SAMPLES] samples of 16 kHz mono audio scaled to -1..1. */
    fun classify(window: FloatArray): Scores {
        input.clear()
        input.asFloatBuffer().put(window, 0, WINDOW_SAMPLES)
        output.clear()
        interpreter.run(input, output)
        output.rewind()
        val scores = output.asFloatBuffer()
        return Scores(
            talk = TALK_CLASSES.maxOf { scores[it] },
            laugh = LAUGH_CLASSES.maxOf { scores[it] },
            snore = scores[SNORING_CLASS],
            cough = COUGH_CLASSES.maxOf { scores[it] },
        )
    }

    override fun close() = interpreter.close()

    companion object {
        const val WINDOW_SAMPLES = 15_600
        private const val MODEL_ASSET = "yamnet.tflite"
        private const val CLASS_COUNT = 521

        // Indices into YAMNet's class map (yamnet_class_map.csv).
        // Speech, child speech, conversation, narration, babbling, shout, yell, screaming, whispering.
        private val TALK_CLASSES = intArrayOf(0, 1, 2, 3, 4, 6, 9, 11, 12)

        // Laughter, baby laughter, giggle, snicker, belly laugh, chuckle.
        private val LAUGH_CLASSES = intArrayOf(13, 14, 15, 16, 17, 18)
        private const val SNORING_CLASS = 38

        // Cough, throat clearing.
        private val COUGH_CLASSES = intArrayOf(42, 43)
    }
}
