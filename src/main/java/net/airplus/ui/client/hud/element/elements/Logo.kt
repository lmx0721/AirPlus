/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Ported from Hanabi's HUD logo rendering (cn.hanabi.modules.modules.render.HUD).
 * Style "Classic": icon-font glyph tinted with the design color (Hanabi hudMode=Classic)
 * Style "Simple" : pulsing text watermark (Hanabi hudMode=Simple)
 * Style "Icon"   : the AirPlus client icon (airplus/icon_64x64.png)
 */
package net.airplus.ui.client.hud.element.elements

import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.Element
import net.airplus.ui.client.hud.element.ElementInfo
import net.airplus.ui.client.hud.element.Side
import net.airplus.ui.font.Fonts
import net.airplus.utils.render.RenderUtils.drawImage
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.util.ResourceLocation
import java.awt.Color
import kotlin.math.abs
import kotlin.math.max

@ElementInfo(name = "Logo", single = true)
class Logo(
    x: Double = 8.0, y: Double = 8.0, scale: Float = 1F, side: Side = Side.default()
) : Element("Logo", x, y, scale, side) {

    private val style by choices("Style", arrayOf("Classic", "Simple", "Icon"), "Classic")
    private val logoColor by color("Color", Color(33, 170, 47))

    override fun drawElement(): Border = when (style) {
        "Simple" -> drawSimple()
        "Icon" -> drawIcon()
        else -> drawClassic()
    }

    // "Classic"：图标字体字形（SessIcon "F" = 剑），随主题色染色。
    // 默认放大 1.5 倍（原 fontSessIcon48 相对 Icon 样式 64px 偏小）
    private fun drawClassic(): Border {
        val glyph = "F"
        val font = Fonts.fontSessIcon48
        GlStateManager.pushMatrix()
        GlStateManager.scale(TEXT_SCALE, TEXT_SCALE, 1f)
        font.drawStringWithShadow(glyph, 0f, 0f, logoColor.rgb)
        GlStateManager.popMatrix()
        return Border(
            0f, 0f,
            font.getStringWidth(glyph).toFloat() * TEXT_SCALE,
            font.height.toFloat() * TEXT_SCALE
        )
    }

    // Hanabi "Simple": brightness pulsing watermark (PaletteUtil.fade behaviour)，同样放大 1.5 倍
    private fun drawSimple(): Border {
        val hsb = Color.RGBtoHSB(255, 255, 255, null)
        val brightness = abs(((System.currentTimeMillis() % 2000L) / 1000f + 1f / 5f * 2f) % 2f - 1f)
        val faded = Color(Color.HSBtoRGB(hsb[0], hsb[1], 0.5f + 0.5f * brightness))
        val textColor = Color(255, 255, 255, faded.red).rgb

        val titleW = Fonts.fontUsans50.getStringWidth("Hanabi").toFloat()
        val subW = Fonts.fontUsans20.getStringWidth("Build 3.1").toFloat()
        val titleH = Fonts.fontUsans50.height.toFloat()
        val subH = Fonts.fontUsans20.height.toFloat()

        GlStateManager.pushMatrix()
        GlStateManager.scale(TEXT_SCALE, TEXT_SCALE, 1f)
        Fonts.fontUsans50.drawStringWithShadow("Hanabi", 0f, 0f, textColor)
        Fonts.fontUsans20.drawStringWithShadow("Build 3.1", 2f, titleH + 1f, textColor)
        GlStateManager.popMatrix()

        val width = max(titleW, subW + 2f) * TEXT_SCALE
        return Border(0f, 0f, width, (titleH + 1f + subH) * TEXT_SCALE)
    }

    // AirPlus client icon
    private fun drawIcon(): Border {
        drawImage(CLIENT_ICON, 0, 0, 64, 64)
        return Border(0f, 0f, 64f, 64f)
    }

    companion object {
        private val CLIENT_ICON = ResourceLocation("airplus/icon_64x64.png")

        /** Classic / Simple 两个文字样式的默认放大倍数 */
        private const val TEXT_SCALE = 1.5f
    }
}
