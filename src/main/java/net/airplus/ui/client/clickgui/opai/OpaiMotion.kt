package net.airplus.ui.client.clickgui.opai

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp

/**
 * Frame-rate independent exponential-decay motion, ported 1:1 from Samsara's OpaiMotion.
 */
class OpaiMotion(initial: Float, private val rate: Double) {

    private var time = Double.NaN
    private var value: Float = initial
    private var destination: Float = initial

    fun approach(target: Float, now: Double): Float {
        if (!time.isNaN()) {
            val dt = (maxOf(0.0, now - time)) / 1000.0
            value = destination + (value - destination) * exp(-rate * dt).toFloat()
            if (abs(value - destination) < 0.0005f) value = destination
        }
        time = now
        destination = target
        return value
    }

    fun value(): Float = value

    fun snap(newValue: Float, now: Double) {
        value = newValue
        destination = newValue
        time = now
    }

    companion object {

        fun now(): Double = System.nanoTime() / 1_000_000.0

        /** Elastic overshoot used by the opening window, ported 1:1 from OpaiMotion.openingScale. */
        fun openingScale(elapsedMs: Double): Float {
            val seconds = maxOf(0.0, elapsedMs) / 1000.0
            return if (seconds >= .32) 1f
            else 1f + (.18 * exp(-17 * seconds) * cos(23 * seconds)).toFloat()
        }
    }
}
