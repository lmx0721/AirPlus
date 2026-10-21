package net.airplus.ui.client.clickgui.opai

import java.util.ArrayDeque
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Click-origin ripple ink and short press feedback, ported 1:1 from Samsara's OpaiFeedback.
 * Independent of the setting's value transition.
 */
class OpaiFeedback {

    private val pulses = ArrayDeque<Pulse>()
    private val hoverMotion = OpaiMotion(0f, 20.0)
    private var pressedAt = Double.NEGATIVE_INFINITY

    fun click(localX: Float, localY: Float, width: Float, height: Float, now: Double) {
        pressedAt = now
        while (pulses.size >= 3) pulses.removeFirst()
        pulses.addLast(Pulse((localX / width).coerceIn(0f, 1f), (localY / height).coerceIn(0f, 1f), now))
    }

    fun hover(hovered: Boolean, now: Double): Float = hoverMotion.approach(if (hovered) 1f else 0f, now)

    fun press(now: Double): Float {
        val age = now - pressedAt
        return if (age < 0 || age >= 220) 0f
        else (sin(Math.PI * age / 220) * exp(-age / 220)).toFloat()
    }

    fun paint(surface: OpaiSurface, x: Float, y: Float, w: Float, h: Float, corner: Float, color: Int, now: Double) {
        pulses.removeIf { pulse -> now - pulse.started >= DURATION }
        for (pulse in pulses) {
            val t = ((now - pulse.started) / DURATION).toFloat().coerceIn(0f, 1f)
            val cx = pulse.x * w
            val cy = pulse.y * h
            val maximum = hypot(maxOf(cx, w - cx).toDouble(), maxOf(cy, h - cy).toDouble()).toFloat()
            val growth = 1 - (1 - minOf(1f, t / .75f)).pow3()
            val alpha = ((color ushr 24 and 255) * .22f * (1 - t) * (1 - t)).roundToInt()
            surface.ripple(x, y, w, h, corner, x + cx, y + cy,
                maxOf(.5f, maximum * growth), color and 0xFFFFFF or (alpha shl 24))
        }
    }

    private fun Float.pow3(): Float = this * this * this

    private class Pulse(val x: Float, val y: Float, val started: Double)

    private companion object {
        const val DURATION = 420.0
    }
}
