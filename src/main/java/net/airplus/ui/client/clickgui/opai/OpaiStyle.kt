package net.airplus.ui.client.clickgui.opai

/**
 * Opai ClickGUI palette, ported 1:1 from Samsara's OpaiStyle.
 * All values are ARGB ints taken verbatim from the source palettes.
 */
data class OpaiPalette(
    val header: Int,
    val body: Int,
    val enabled: Int,
    val enabledHover: Int,
    val accent: Int,
    val enabledText: Int,
    val text: Int,
    val hover: Int,
    val field: Int,
    val fieldLine: Int,
    val track: Int,
    val scrollbar: Int,
    val selected: Int,
    val toggleOutline: Int,
    val tick: Int,
    val hudProgress: Int,
    val hudKnob: Int,
)

object OpaiStyle {

    // Text sizes (NVG pixel sizes in Samsara, mapped onto the client font scale at draw time).
    const val HEADER_TEXT_SIZE = 9.2f
    const val ROW_TEXT_SIZE = 8.3f
    const val SETTING_TEXT_SIZE = 8.5f

    val LAVENDER = OpaiPalette(
        0xFF1C1F29.toInt(), 0xCC151315.toInt(), 0xFFA8B0E7.toInt(), 0xFFB0B8EF.toInt(), 0xFFBBC3FF.toInt(),
        0xFF172778.toInt(), 0xFFE4E1E6.toInt(), 0x0CE4E1E6, 0xFF2D2D38.toInt(), 0xFFC4C0CC.toInt(),
        0xFF484858.toInt(), 0xCC636472.toInt(), 0xFF414253.toInt(), 0xFF938F99.toInt(), 0xFF626786.toInt(), 0xFF9CA5DA.toInt(), 0xFF5B5F7C.toInt())

    val LIGHT_PINK = OpaiPalette(
        0xFF161110.toInt(), 0xCC1A1413.toInt(), 0xFFDF9D95.toInt(), 0xFFE5A59D.toInt(), 0xFFFFB4AA.toInt(),
        0xFF5F150F.toInt(), 0xFFEDE0DE.toInt(), 0x0CEDE0DE, 0xFF2E2625.toInt(), 0xFFCDB7B4.toInt(),
        0xFF56413E.toInt(), 0xCC69534D.toInt(), 0xFF483A37.toInt(), 0xFF9E8D8B.toInt(), 0xFF85645D.toInt(), 0xFFD19997.toInt(), 0xFF7C5752.toInt())

    fun palette(name: String): OpaiPalette =
        if (name.equals("Light Pink", ignoreCase = true)) LIGHT_PINK else LAVENDER

    /** Per-channel lerp, ported from [com.samsara.ui.clickgui.opai.OpaiSurface.mix]. */
    fun mix(from: Int, to: Int, amount: Float): Int {
        var result = 0
        val t = amount.coerceIn(0f, 1f)
        for (shift in 0..24 step 8) {
            val fromChannel = from ushr shift and 255
            val toChannel = to ushr shift and 255
            val blended = (fromChannel + (toChannel - fromChannel) * t).toInt()
            result = result or (blended shl shift)
        }
        return result
    }

    /** Scales the alpha channel, ported from Samsara's ColorUtility.multiplyOpacity. */
    fun multiplyOpacity(color: Int, factor: Float): Int {
        val alpha = ((color ushr 24 and 255) * factor.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return color and 0xFFFFFF or (alpha shl 24)
    }
}
