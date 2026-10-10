/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.ui.client.hud.element.elements

import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.Element
import net.airplus.ui.client.hud.element.ElementInfo
import net.airplus.ui.client.hud.element.Side
import net.airplus.ui.font.AWTFontRenderer.Companion.assumeNonVolatile
import net.airplus.ui.font.Fonts
import net.airplus.ui.font.GameFontRenderer
import net.airplus.utils.client.ClientThemesUtils
import net.airplus.utils.extensions.getPing
import net.airplus.utils.render.ColorSettingsInteger
import net.minecraft.client.renderer.GlStateManager.resetColor
import org.lwjgl.opengl.GL11.*
import java.awt.Color
import kotlin.math.max

/**
 * 延迟曲线 HUD 元素
 *
 * 每秒采样一次自身延迟，绘制迷你折线 + 当前数值
 */
@ElementInfo(name = "Ping")
class Ping(
    x: Double = 140.0, y: Double = 5.0, scale: Float = 1F,
    side: Side = Side(Side.Horizontal.RIGHT, Side.Vertical.UP),
) : Element("Ping", x, y, scale, side) {

    private val width by int("Width", 120, 100..300)
    private val height by int("Height", 40, 30..150)
    private val thickness by float("Thickness", 2F, 1F..3F)
    private val showText by boolean("ShowText", true)
    private val colorMode by choices("ColorMode", arrayOf("Theme", "Custom"), "Theme")
    private val customColors = ColorSettingsInteger(this, "CustomColor") { colorMode == "Custom" }.with(Color(0, 111, 255))

    private val font by font("Font", Fonts.fontRegular35)

    private val samples = mutableListOf<Int>()
    private var lastSampleTick = -1

    private fun graphColor(): Color =
        if (colorMode == "Custom") customColors.color() else ClientThemesUtils.getColor()

    override fun drawElement(): Border {
        val player = mc.thePlayer
        if (player == null || mc.theWorld == null) return Border(0F, 0F, 0F, 0F)

        // 每 tick 采样一次，曲线更新更快
        if (lastSampleTick != player.ticksExisted) {
            lastSampleTick = player.ticksExisted
            samples += player.getPing()
            while (samples.size > 60) samples.removeAt(0)
        }

        val fontRenderer = font
        val fontHeight = ((fontRenderer as? GameFontRenderer)?.height ?: fontRenderer.FONT_HEIGHT).toFloat()
        val textH = if (showText) fontHeight + 3F else 0F
        val currentPing = player.getPing()
        val color = graphColor()

        assumeNonVolatile {
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            glEnable(GL_BLEND)
            glEnable(GL_LINE_SMOOTH)
            glHint(GL_LINE_SMOOTH_HINT, GL_NICEST)
            glLineWidth(thickness)
            glDisable(GL_TEXTURE_2D)
            glDisable(GL_DEPTH_TEST)
            glDepthMask(false)

            glPushMatrix()
            glTranslatef(0F, textH, 0F)

            glBegin(GL_LINES)
            val size = samples.size
            if (size > 1) {
                val maxY = max(200.0, (samples.max() ?: 100) * 1.15)
                glColor4f(color.red / 255f, color.green / 255f, color.blue / 255f, color.alpha / 255f)
                val stepX = width / 59.0
                for (i in 0 until size - 1) {
                    val x0 = i * stepX
                    val y0 = height - (samples[i] / maxY * height).coerceIn(0.0, height.toDouble())
                    val x1 = (i + 1) * stepX
                    val y1 = height - (samples[i + 1] / maxY * height).coerceIn(0.0, height.toDouble())
                    glVertex2d(x0, y0)
                    glVertex2d(x1, y1)
                }
            }
            glEnd()

            glPopMatrix()

            glEnable(GL_TEXTURE_2D)
            glDisable(GL_LINE_SMOOTH)
            glEnable(GL_DEPTH_TEST)
            glDepthMask(true)
            glDisable(GL_BLEND)

            if (showText) {
                fontRenderer.drawString("${currentPing}ms", 0F, 0F, color.rgb, true)
            }
        }

        resetColor()

        return Border(0F, 0F, width.toFloat(), height + textH)
    }
}
