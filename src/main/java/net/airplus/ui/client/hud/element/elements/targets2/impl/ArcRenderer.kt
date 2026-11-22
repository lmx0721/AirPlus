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
 *
 * 抗锯齿：核心弧带两侧各画一圈 1px 的 alpha 渐隐 feather（内缘/外缘 alpha 从
 * 真实值线性衰减到 0），消除无 MSAA 环境下三角形边缘的锯齿。
 */
internal object ArcRenderer {

    /** 内外缘渐隐宽度（局部像素） */
    private const val FEATHER = 1f

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

        val inner = radius - thickness / 2f
        val outer = radius + thickness / 2f
        val steps = ceil(sweepDeg / 3f).toInt().coerceAtLeast(2)

        // 核心弧带（inner -> outer，全 alpha）
        arcPass(cx, cy, inner, 1f, outer, 1f, startDeg, sweepDeg, steps, c1, c2)
        // 外缘渐隐（outer -> outer + FEATHER，alpha -> 0）
        arcPass(cx, cy, outer, 1f, outer + FEATHER, 0f, startDeg, sweepDeg, steps, c1, c2)
        // 内缘渐隐（inner - FEATHER -> inner，alpha 0 -> 真实值）
        arcPass(cx, cy, inner - FEATHER, 0f, inner, 1f, startDeg, sweepDeg, steps, c1, c2)

        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
        GlStateManager.shadeModel(GL11.GL_FLAT)
    }

    /**
     * 画一段三角形条带：每个角度步进发射两个顶点（半径 r1 / alpha 因子 a1，
     * 半径 r2 / alpha 因子 a2），颜色沿 sweep 从 c1 渐变到 c2。
     */
    private fun arcPass(
        cx: Float, cy: Float,
        r1: Float, a1f: Float, r2: Float, a2f: Float,
        startDeg: Float, sweepDeg: Float,
        steps: Int, c1: Color, c2: Color
    ) {
        val tessellator = Tessellator.getInstance()
        val wr = tessellator.worldRenderer
        wr.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR)

        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val ang = Math.toRadians((startDeg + sweepDeg * t).toDouble())
            val sin = Math.sin(ang).toFloat()
            val cos = Math.cos(ang).toFloat()
            val c = mix(c1, c2, t)
            val r = c.red / 255f
            val g = c.green / 255f
            val b = c.blue / 255f
            val a1 = (c.alpha / 255f) * a1f
            val a2 = (c.alpha / 255f) * a2f
            wr.pos((cx + cos * r1).toDouble(), (cy + sin * r1).toDouble(), 0.0).color(r, g, b, a1).endVertex()
            wr.pos((cx + cos * r2).toDouble(), (cy + sin * r2).toDouble(), 0.0).color(r, g, b, a2).endVertex()
        }
        tessellator.draw()
    }

    fun ring(cx: Float, cy: Float, radius: Float, thickness: Float, color: Color) =
        arc(cx, cy, radius, thickness, 0f, 360f, color, color)
}
