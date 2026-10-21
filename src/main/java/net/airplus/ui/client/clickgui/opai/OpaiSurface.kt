package net.airplus.ui.client.clickgui.opai

import net.airplus.ui.font.Fonts
import net.airplus.ui.font.GameFontRenderer
import net.airplus.utils.render.RenderUtils
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import org.lwjgl.opengl.GL11
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Minimal drawing surface contract, ported from Samsara's OpaiSurface,
 * implemented on top of the client's legacy GL tooling (RenderUtils + Fonts + GlStateManager).
 * No NanoVG involved.
 */
interface OpaiSurface {

    fun rect(x: Float, y: Float, width: Float, height: Float, color: Int)

    fun rounded(x: Float, y: Float, width: Float, height: Float, radius: Float, color: Int)

    /** Header keeps rounded top corners, body keeps rounded bottom corners (Samsara panel()). */
    fun panel(x: Float, y: Float, width: Float, height: Float, header: Boolean, color: Int)

    /** Draws [text] vertically centered on [centerY], truncated with "..." to [maximum]. */
    fun text(text: String, x: Float, centerY: Float, size: Float, maximum: Float, color: Int)

    fun plainText(text: String, x: Float, centerY: Float, size: Float, maximum: Float, color: Int)

    fun textWidth(text: String, size: Float): Float

    fun plainTextWidth(text: String, size: Float): Float

    fun clip(x: Float, y: Float, width: Float, height: Float, content: Runnable)

    fun opacity(factor: Float, content: Runnable)

    fun check(x: Float, y: Float, size: Float, color: Int)

    fun scale(factor: Float, centerX: Float, centerY: Float, content: Runnable)

    fun ripple(x: Float, y: Float, width: Float, height: Float, corner: Float,
               centerX: Float, centerY: Float, radius: Float, color: Int)

    companion object {
        fun mix(from: Int, to: Int, amount: Float): Int = OpaiStyle.mix(from, to, amount)
    }
}

/**
 * Legacy-GL implementation of [OpaiSurface]. All colors are ARGB ints; the opening/closing
 * fade and nested [opacity] blocks are folded into the alpha channel on every draw call.
 * Raw glColor is never used: fills go through GlStateManager.color, rounded rects through
 * RenderUtils.drawRoundedRect.
 */
class OpaiCanvas(private val screen: OpaiScreen) : OpaiSurface {

    private var globalAlpha = 1f
    private var localOpacity = 1f
    private val clips = ArrayDeque<FloatArray>()

    // Whole-GUI opening/closing transform; GL scissor lives in window space, so clip
    // rectangles are mapped through it before conversion to pixels.
    private var transformScale = 1f
    private var transformCenterX = 0f
    private var transformCenterY = 0f

    fun beginFrame(alpha: Float) {
        globalAlpha = alpha.coerceIn(0f, 1f)
        localOpacity = 1f
        clips.clear()
    }

    fun setTransform(scale: Float, centerX: Float, centerY: Float) {
        transformScale = scale
        transformCenterX = centerX
        transformCenterY = centerY
    }

    private fun shade(color: Int): Int {
        val alpha = (color ushr 24 and 255) * globalAlpha * localOpacity
        val clamped = alpha.roundToInt().coerceIn(0, 255)
        return color and 0xFFFFFF or (clamped shl 24)
    }

    private fun visible(color: Int): Boolean = shade(color) ushr 24 != 0

    override fun rect(x: Float, y: Float, width: Float, height: Float, color: Int) {
        if (width <= 0f || height <= 0f || !visible(color)) return
        val c = shade(color)
        val alpha = (c ushr 24 and 255) / 255f
        val red = (c ushr 16 and 255) / 255f
        val green = (c ushr 8 and 255) / 255f
        val blue = (c and 255) / 255f
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        GlStateManager.color(red, green, blue, alpha)
        val tessellator = Tessellator.getInstance()
        val worldrenderer = tessellator.worldRenderer
        worldrenderer.begin(7, DefaultVertexFormats.POSITION)
        worldrenderer.pos(x.toDouble(), y.toDouble(), 0.0).endVertex()
        worldrenderer.pos(x.toDouble(), (y + height).toDouble(), 0.0).endVertex()
        worldrenderer.pos((x + width).toDouble(), (y + height).toDouble(), 0.0).endVertex()
        worldrenderer.pos((x + width).toDouble(), y.toDouble(), 0.0).endVertex()
        tessellator.draw()
        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
        GlStateManager.color(1f, 1f, 1f, 1f)
    }

    override fun rounded(x: Float, y: Float, width: Float, height: Float, radius: Float, color: Int) {
        if (width <= 0f || height <= 0f || !visible(color)) return
        RenderUtils.drawRoundedRect(x, y, x + width, y + height, shade(color), radius.coerceAtLeast(0f))
    }

    override fun panel(x: Float, y: Float, width: Float, height: Float, header: Boolean, color: Int) {
        if (width <= 0f || height <= 0f || !visible(color)) return
        val corners = if (header) RenderUtils.RoundedCorners.TOP_ONLY else RenderUtils.RoundedCorners.BOTTOM_ONLY
        RenderUtils.drawRoundedRect(x, y, x + width, y + height, shade(color), OpaiLayout.RADIUS, corners)
    }

    override fun text(text: String, x: Float, centerY: Float, size: Float, maximum: Float, color: Int) =
        drawText(text, x, centerY, size, maximum, color, weighted = true)

    override fun plainText(text: String, x: Float, centerY: Float, size: Float, maximum: Float, color: Int) =
        drawText(text, x, centerY, size, maximum, color, weighted = false)

    private fun drawText(text: String, x: Float, centerY: Float, size: Float, maximum: Float, color: Int, weighted: Boolean) {
        if (maximum <= 0f || !visible(color)) return
        var label = text
        if (labelWidth(label, size, weighted) > maximum) {
            if (labelWidth("...", size, weighted) > maximum) return
            while (label.isNotEmpty() && labelWidth("$label...", size, weighted) > maximum) {
                label = label.substring(0, label.length - 1)
            }
            label += "..."
        }
        val c = shade(color)
        val font = if (weighted) headerFont() else plainFont()
        val s = (size / font.fontHeight.coerceAtLeast(1)).coerceIn(0.05f, 4f)
        val drawY = centerY - font.visualLineHeight * s * 0.5f + 2.75f * s
        GlStateManager.pushMatrix()
        GlStateManager.translate(x, drawY, 0f)
        GlStateManager.scale(s, s, 1f)
        font.drawString(label, 0f, 0f, c, shadow = false)
        GlStateManager.popMatrix()
    }

    private fun labelWidth(text: String, size: Float, weighted: Boolean): Float =
        if (weighted) textWidth(text, size) else plainTextWidth(text, size)

    override fun textWidth(text: String, size: Float): Float {
        val font = headerFont()
        return font.getStringWidth(text) * (size / font.fontHeight.coerceAtLeast(1))
    }

    override fun plainTextWidth(text: String, size: Float): Float {
        val font = plainFont()
        return font.getStringWidth(text) * (size / font.fontHeight.coerceAtLeast(1))
    }

    override fun clip(x: Float, y: Float, width: Float, height: Float, content: Runnable) {
        val parent = clips.lastOrNull()
        val left = max(x, parent?.get(0) ?: x)
        val top = max(y, parent?.get(1) ?: y)
        val right = min(x + width, parent?.get(2) ?: (x + width))
        val bottom = min(y + height, parent?.get(3) ?: (y + height))
        clips.addLast(floatArrayOf(left, top, right, bottom))
        applyScissor()
        try {
            content.run()
        } finally {
            clips.removeLast()
            applyScissor()
        }
    }

    private fun applyScissor() {
        val bounds = clips.lastOrNull()
        if (bounds == null) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            return
        }
        val sr = ScaledResolution(net.minecraft.client.Minecraft.getMinecraft())
        val s = sr.scaleFactor
        val guiH = sr.scaledHeight
        val left = transformCenterX + (bounds[0] - transformCenterX) * transformScale
        val top = transformCenterY + (bounds[1] - transformCenterY) * transformScale
        val right = transformCenterX + (bounds[2] - transformCenterX) * transformScale
        val bottom = transformCenterY + (bounds[3] - transformCenterY) * transformScale
        val x = (left * s).roundToInt()
        val y = ((guiH - bottom) * s).roundToInt()
        val w = ((right - left) * s).roundToInt().coerceAtLeast(0)
        val h = ((bottom - top) * s).roundToInt().coerceAtLeast(0)
        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        if (w <= 0 || h <= 0) {
            GL11.glScissor(0, 0, 0, 0)
        } else {
            GL11.glScissor(x, y, w, h)
        }
    }

    override fun opacity(factor: Float, content: Runnable) {
        val previous = localOpacity
        localOpacity *= factor.coerceIn(0f, 1f)
        try {
            content.run()
        } finally {
            localOpacity = previous
        }
    }

    override fun check(x: Float, y: Float, size: Float, color: Int) {
        if (!visible(color)) return
        val c = shade(color)
        val alpha = (c ushr 24 and 255) / 255f
        val red = (c ushr 16 and 255) / 255f
        val green = (c ushr 8 and 255) / 255f
        val blue = (c and 255) / 255f
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        GlStateManager.color(red, green, blue, alpha)
        GL11.glLineWidth(1.25f)
        GL11.glBegin(GL11.GL_LINE_STRIP)
        GL11.glVertex2f(x, y + size * 0.5f)
        GL11.glVertex2f(x + size * 0.38f, y + size * 0.88f)
        GL11.glVertex2f(x + size, y)
        GL11.glEnd()
        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
        GlStateManager.color(1f, 1f, 1f, 1f)
    }

    override fun scale(factor: Float, centerX: Float, centerY: Float, content: Runnable) {
        GlStateManager.pushMatrix()
        GlStateManager.translate(centerX, centerY, 0f)
        GlStateManager.scale(factor, factor, 1f)
        GlStateManager.translate(-centerX, -centerY, 0f)
        try {
            content.run()
        } finally {
            GlStateManager.popMatrix()
        }
    }

    override fun ripple(x: Float, y: Float, width: Float, height: Float, corner: Float,
                        centerX: Float, centerY: Float, radius: Float, color: Int) {
        if (!visible(color) || radius <= 0f) return
        clip(x, y, width, height) {
            rounded(centerX - radius, centerY - radius, radius * 2, radius * 2, radius, color)
        }
    }

    // fontSemibold35 / fontRegular35 都是启动关键路径字体（loadFonts 立即烘焙），GUI 打开时必然已初始化。
    private fun headerFont(): GameFontRenderer = Fonts.fontSemibold35

    private fun plainFont(): GameFontRenderer = Fonts.fontRegular35
}
