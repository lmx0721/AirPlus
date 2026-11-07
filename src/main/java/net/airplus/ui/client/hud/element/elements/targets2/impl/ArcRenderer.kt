package net.airplus.ui.client.hud.element.elements.targets2.impl

import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import org.lwjgl.opengl.GL11
import java.awt.Color
import kotlin.math.ceil

/**
 * Minimal ring/arc renderer used by the ring-based target HUD styles
 * (Halo Pulse / Eclipse Ring). Angle convention follows CSS conic-gradient:
 * -90° = 12 o'clock, increasing clockwise.
 */
internal object ArcRenderer {

    private fun mix(c1: Color, c2: Color, t: Float): Color = Color(
        (c1.red + (c2.red - c1.red) * t).toInt().coerceIn(0, 255),
        (c1.green + (c2.green - c1.green) * t).toInt().coerceIn(0, 255),
        (c1.blue + (c2.blue - c1.blue) * t).toInt().coerceIn(0, 255),
        (c1.alpha + (c2.alpha - c1.alpha) * t).toInt().coerceIn(0, 255)
    )

    fun arc(
        cx: Float, cy: Float,
        radius: Float, thickness: Float,
        startDeg: Float, sweepDeg: Float,
        c1: Color, c2: Color
    ) {
        if (sweepDeg <= 0f || thickness <= 0f || (c1.alpha <= 0 && c2.alpha <= 0)) return

        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0)
        GlStateManager.shadeModel(GL11.GL_SMOOTH)

        val tessellator = Tessellator.getInstance()
        val wr = tessellator.worldRenderer
        wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR)

        val inner = radius - thickness / 2f
        val outer = radius + thickness / 2f
        val steps = ceil(sweepDeg / 3f).toInt().coerceAtLeast(2)
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val ang = Math.toRadians((startDeg + sweepDeg * t).toDouble())
            val sin = Math.sin(ang).toFloat()
            val cos = Math.cos(ang).toFloat()
            val c = mix(c1, c2, t)
            val r = c.red / 255f
            val g = c.green / 255f
            val b = c.blue / 255f
            val a = c.alpha / 255f
            wr.pos((cx + cos * inner).toDouble(), (cy + sin * inner).toDouble(), 0.0).color(r, g, b, a).endVertex()
            wr.pos((cx + cos * outer).toDouble(), (cy + sin * outer).toDouble(), 0.0).color(r, g, b, a).endVertex()
        }
        tessellator.draw()

        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
        GlStateManager.shadeModel(GL11.GL_FLAT)
    }

    fun ring(cx: Float, cy: Float, radius: Float, thickness: Float, color: Color) =
        arc(cx, cy, radius, thickness, 0f, 360f, color, color)
}
