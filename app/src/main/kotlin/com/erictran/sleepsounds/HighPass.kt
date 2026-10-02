package com.erictran.sleepsounds

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Fourth-order Butterworth high-pass at 150 Hz for 16 kHz audio. It removes rumble from fans,
 * air conditioning and handling noise while leaving voices and snoring intact.
 */
class HighPass {
    private val sections = BUTTERWORTH_Q.map(::Biquad)

    private class Biquad(q: Double) {
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        init {
            val w = 2 * PI * CUTOFF_HZ / AudioConfig.SAMPLE_RATE
            val alpha = sin(w) / (2 * q)
            val a0 = 1 + alpha
            b0 = (1 + cos(w)) / 2 / a0
            b1 = -(1 + cos(w)) / a0
            b2 = b0
            a1 = -2 * cos(w) / a0
            a2 = (1 - alpha) / a0
        }

        fun step(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }
    }

    fun filter(input: ShortArray, output: ShortArray, count: Int) {
        for (i in 0 until count) {
            var sample = input[i].toDouble()
            for (section in sections) sample = section.step(sample)
            output[i] = sample.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private companion object {
        const val CUTOFF_HZ = 150.0

        // Q of each second-order section of a fourth-order Butterworth filter.
        val BUTTERWORTH_Q = listOf(0.54119610, 1.30656296)
    }
}
