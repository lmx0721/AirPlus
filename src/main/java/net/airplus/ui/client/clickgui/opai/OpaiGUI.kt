package net.airplus.ui.client.clickgui.opai

import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.ui.client.clickgui.opai.OpaiScreen
import org.lwjgl.input.Keyboard

object OpaiGUI : Module("OpaiGUI", Category.RENDER, Keyboard.KEY_RSHIFT, canBeEnabled = false, gameDetecting = false) {

    val opaiColor by choices("Opai Color", arrayOf("Lavender", "Light Pink"), "Lavender")

    val backgroundBlur by float("BackgroundBlur", 15f, 0f..30f)
    val animSpeed by float("AnimSpeed", 1f, 0.5f..3f)

    fun getAnimTime(baseMs: Long): Long = (baseMs / animSpeed).toLong()

    /** Motion rates are exponential decays, so animSpeed multiplies the rate directly. */
    fun getAnimRate(baseRate: Double): Double = baseRate * animSpeed.toDouble().coerceIn(0.5, 3.0)

    override fun onEnable() {
        if (mc.currentScreen is OpaiScreen) {
            mc.displayGuiScreen(null)
        } else {
            mc.displayGuiScreen(OpaiScreen.INSTANCE)
        }
    }

    /**
     * 供 HUD 药水/Session 等元素共用当前 Opai 调色板（对齐 Samsara 的 currentOpaiPalette）。
     * 未初始化或读取失败时返回 LAVENDER。
     */
    @JvmStatic
    fun currentPalette(): OpaiPalette = runCatching { OpaiStyle.palette(opaiColor) }.getOrDefault(OpaiStyle.LAVENDER)
}
