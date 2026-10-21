package net.airplus.ui.client.clickgui.opai

import kotlin.math.max
import kotlin.math.min

/**
 * Shared geometry, ported 1:1 from Samsara's OpaiLayout + OpaiContentLayout constants.
 */
object OpaiLayout {

    const val COL_W = 152.0f
    const val COL_GAP = 8.0f
    const val HEADER_H = 21.0f
    const val ROW_H = 19.6f
    const val FOOTER_H = 7.0f
    const val RADIUS = 7.0f
    const val TEXT_PAD = 10.0f

    // OpaiContentLayout entry heights.
    const val BOOLEAN_H = 19.6f
    const val NUMBER_H = 20.0f
    const val MODE_H = 40.0f
    const val OPTION_H = 20.0f
    const val PADDING_H = 0.0f
    const val FIELD_Y = 16.0f
    const val FIELD_H = 21.0f
    const val SLIDER_Y = 15.6f

    // AirPlus extension rows (color picker / text input), sized in the same rhythm.
    const val COLOR_PICKER_H = 101.0f
    const val TEXT_H = 42.0f

    fun viewport(width: Int, height: Int, columns: Int): Viewport {
        val margin = min(30.0f, width * 0.0382f)
        val colWidth = min(COL_W, width - margin * 2)
        val top = max(8.0f, height * 0.112f)
        val maxBody = max(ROW_H, height - top - HEADER_H - max(8.0f, height * 0.10f))
        return Viewport(colWidth, margin, top, min(16.0f * ROW_H + FOOTER_H, maxBody))
    }

    fun horizontalOverflow(width: Int, columns: Int, layout: Viewport): Float =
        max(0f, layout.left * 2 + columns * layout.columnWidth + (columns - 1) * COL_GAP - width)

    /** "KillAura" -> "Kill Aura", ported from OpaiLayout.displayName. */
    fun displayName(name: String): String = name
        .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")

    data class Viewport(val columnWidth: Float, val left: Float, val top: Float, val maxBodyHeight: Float)
}
