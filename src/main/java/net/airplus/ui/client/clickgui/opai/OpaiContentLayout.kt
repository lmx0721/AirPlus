package net.airplus.ui.client.clickgui.opai

import kotlin.math.max
import kotlin.math.min

/**
 * One content coordinate system for rendering, scrolling and input.
 * Ported 1:1 from Samsara's OpaiContentLayout, extended with COLOR / COLOR_PICKER / TEXT kinds
 * for AirPlus setting types that Samsara does not have.
 */
class OpaiContentLayout<T> {

    enum class Kind { MODULE, BOOLEAN, NUMBER, MODE, OPTION, COLOR, COLOR_PICKER, TEXT, FONT, PADDING }

    fun kindHeight(kind: Kind): Float = when (kind) {
        Kind.MODULE -> OpaiLayout.ROW_H
        Kind.BOOLEAN -> OpaiLayout.BOOLEAN_H
        Kind.NUMBER -> OpaiLayout.NUMBER_H
        Kind.MODE -> OpaiLayout.MODE_H
        Kind.OPTION -> OpaiLayout.OPTION_H
        Kind.COLOR -> OpaiLayout.BOOLEAN_H
        Kind.COLOR_PICKER -> OpaiLayout.COLOR_PICKER_H
        Kind.TEXT -> OpaiLayout.TEXT_H
        Kind.FONT -> OpaiLayout.BOOLEAN_H
        Kind.PADDING -> OpaiLayout.PADDING_H
    }

    data class Entry<T>(
        val target: T,
        val kind: Kind,
        val top: Float,
        val height: Float,
        val option: Int,
        val clipTop: Float,
        val clipBottom: Float,
    ) {
        fun visibleTop(): Float = max(top, clipTop)
        fun visibleBottom(): Float = min(top + height, clipBottom)
        fun visible(): Boolean = visibleBottom() > visibleTop()
    }

    data class Rect(val x: Float, val y: Float, val width: Float, val height: Float) {
        fun contains(mouseX: Double, mouseY: Double): Boolean =
            mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height
    }

    data class Scrollbar(val top: Float, val height: Float, val travel: Float)

    private val entries = ArrayList<Entry<T>>()
    private var height = 0f

    fun add(target: T, kind: Kind) {
        append(target, kind, kindHeight(kind), -1)
    }

    fun addOptions(target: T, count: Int) {
        for (i in 0 until count) append(target, Kind.OPTION, OpaiLayout.OPTION_H, i)
    }

    private fun append(target: T, kind: Kind, entryHeight: Float, option: Int) {
        entries.add(Entry(target, kind, height, entryHeight, option, height, height + entryHeight))
        height += entryHeight
    }

    /** Reveal an intact section through one animated clip, rather than squashing each control. */
    fun reveal(section: OpaiContentLayout<T>, progress: Float) {
        val occupied = section.height * progress.coerceIn(0f, 1f)
        if (occupied <= 0) return
        val start = height
        for (item in section.entries) {
            entries.add(Entry(item.target, item.kind, start + item.top, item.height, item.option,
                start + item.clipTop, min(start + item.clipBottom, start + occupied)))
        }
        height += occupied
    }

    fun entries(): List<Entry<T>> = entries

    fun height(): Float = height

    fun bodyHeight(maximum: Float): Float = min(maximum, height + OpaiLayout.FOOTER_H)

    fun maxScroll(bodyHeight: Float): Float = max(0f, height - (bodyHeight - OpaiLayout.FOOTER_H))

    fun hit(localY: Double, scroll: Float, bodyHeight: Float): Entry<T>? {
        if (localY < 0.0 || localY >= bodyHeight - OpaiLayout.FOOTER_H) return null
        val contentY = localY + scroll
        for (entry in entries) {
            if (contentY >= entry.visibleTop() && contentY < entry.visibleBottom()) {
                return if (entry.kind == Kind.PADDING) null else entry
            }
        }
        return null
    }

    companion object {

        fun field(x: Float, y: Float, width: Float): Rect =
            Rect(x + OpaiLayout.TEXT_PAD - 2, y + OpaiLayout.FIELD_Y, width - OpaiLayout.TEXT_PAD * 2 + 4, OpaiLayout.FIELD_H)

        fun sliderHit(x: Float, y: Float, width: Float): Rect =
            Rect(x + OpaiLayout.TEXT_PAD - 3, y + OpaiLayout.SLIDER_Y - 6, width - OpaiLayout.TEXT_PAD * 2 + 6, 12.0f)

        fun sliderFraction(mouseX: Double, columnX: Float, columnWidth: Float): Double =
            ((mouseX - columnX - OpaiLayout.TEXT_PAD) / max(1f, columnWidth - OpaiLayout.TEXT_PAD * 2).toDouble()).coerceIn(0.0, 1.0)

        fun scrollbar(bodyHeight: Float, contentHeight: Float, scroll: Float): Scrollbar {
            val visible = max(0f, bodyHeight - OpaiLayout.FOOTER_H)
            val track = max(0f, visible - 4)
            val thumb = min(track, max(12f, track * visible / max(1f, contentHeight)))
            val travel = track - thumb
            val maximum = max(0f, contentHeight - visible)
            val top = 2 + (if (maximum == 0f) 0f else travel * (scroll / maximum).coerceIn(0f, 1f))
            return Scrollbar(top, thumb, travel)
        }

        fun scrollFromThumb(thumbTop: Float, bodyHeight: Float, contentHeight: Float): Float {
            val bar = scrollbar(bodyHeight, contentHeight, 0f)
            val maximum = max(0f, contentHeight - (bodyHeight - OpaiLayout.FOOTER_H))
            return if (bar.travel == 0f) 0f else ((thumbTop - 2) / bar.travel).coerceIn(0f, 1f) * maximum
        }
    }
}
