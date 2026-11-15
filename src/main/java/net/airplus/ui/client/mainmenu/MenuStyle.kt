/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.ui.client.mainmenu

import net.minecraft.client.renderer.GlStateManager
import org.lwjgl.opengl.GL11

/**
 * Moonlight MenuStyle 裁剪移植：仅保留主菜单轨道实际用到的 unfold 曲线族与 drawOrbitArc。
 * （moonlight 原版的按钮/滑杆/文本框/屏幕文本那套 OrbitPage UI 不在移植范围内）
 */
object MenuStyle {

    /** 中间项（Alt Manager）圆点的弹出曲线：先冲到 1.7 倍再回弹到 1。 */
    fun unfoldCenterScale(p: Float): Float = when {
        p <= 0f -> 0f
        p < 0.14f -> {
            val t = p / 0.14f
            1.7f * (1f - (1f - t) * (1f - t))
        }
        else -> {
            val t = clamp01((p - 0.14f) / 0.14f)
            1.7f - 0.7f * (t * t * (3f - 2f * t))
        }
    }

    /** 轨道弧展开主曲线（easeInOutQuad，16%~70% 区间展开）。 */
    fun unfoldFront(p: Float): Float {
        val t = clamp01((p - 0.16f) / 0.54f)
        return if (t < 0.5f) 2f * t * t else 1f - Math.pow(-2.0 * t + 2.0, 2.0).toFloat() / 2f
    }

    /** 按角度偏移错开各圆点的展开起点。 */
    fun unfoldReach(front: Float, offset: Float): Float = clamp01((front - offset) / 0.07f)

    /** 圆点 back-out 回弹曲线（c = 1.70158，easeOutBack）。 */
    fun unfoldPop(p: Float): Float {
        val c = 1.70158f
        val t = p - 1f
        return if (p <= 0f) 0f else 1f + (c + 1f) * t * t * t + c * t * t
    }

    /** 标签文字在展开后段（62%~100%）淡入。 */
    fun unfoldLabel(p: Float): Float = clamp01((p - 0.62f) / 0.38f)

    private fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)

    /**
     * 绘制一段带渐隐端点的轨道弧（moonlight 同款 1px 平滑线，alpha 0.07 基准）。
     * [from]/[to] 为整弧角度范围，[revealFrom]/[revealTo] 为当前已展开的可见范围。
     */
    fun drawOrbitArc(
        cx: Float, cy: Float, radius: Float,
        from: Float, to: Float, revealFrom: Float, revealTo: Float, alpha: Float
    ) {
        if (revealTo - revealFrom <= 1.0e-4f || alpha <= 0f) return
        val step = 1.5f / maxOf(1f, radius)
        val span = to - from
        val fade = 6f / maxOf(1f, radius)
        val clipStart = revealFrom > from + 1.0e-4f
        val clipEnd = revealTo < to - 1.0e-4f

        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO)
        GlStateManager.disableAlpha()
        GL11.glDisable(GL11.GL_ALPHA_TEST)
        GlStateManager.disableLighting()
        GL11.glEnable(GL11.GL_LINE_SMOOTH)
        GL11.glLineWidth(1f)
        GL11.glBegin(GL11.GL_LINE_STRIP)
        orbitVertex(cx, cy, radius, revealFrom, from, span, revealFrom, revealTo, fade, clipStart, clipEnd, alpha)
        var angle = Math.ceil(((revealFrom - from) / step).toDouble()).toFloat() * step + from
        while (angle < revealTo) {
            if (angle > revealFrom) {
                orbitVertex(cx, cy, radius, angle, from, span, revealFrom, revealTo, fade, clipStart, clipEnd, alpha)
            }
            angle += step
        }
        orbitVertex(cx, cy, radius, revealTo, from, span, revealFrom, revealTo, fade, clipStart, clipEnd, alpha)
        GL11.glEnd()
        GL11.glDisable(GL11.GL_LINE_SMOOTH)
        GL11.glColor4f(1f, 1f, 1f, 1f)
        GlStateManager.enableTexture2D()
        GlStateManager.enableAlpha()
        GlStateManager.color(1f, 1f, 1f, 1f)
    }

    /** 弧线顶点：端点/中段做 alpha 渐隐。 */
    private fun orbitVertex(
        cx: Float, cy: Float, radius: Float, angle: Float,
        from: Float, span: Float, revealFrom: Float, revealTo: Float,
        fade: Float, clipStart: Boolean, clipEnd: Boolean, alpha: Float
    ) {
        val t = (angle - from) / span
        var edge = minOf(1f, minOf(t, 1f - t) * 6f)
        if (clipStart) edge = minOf(edge, (angle - revealFrom) / fade)
        if (clipEnd) edge = minOf(edge, (revealTo - angle) / fade)
        GL11.glColor4f(1f, 1f, 1f, 0.07f * clamp01(edge) * alpha)
        GL11.glVertex2f(cx + Math.cos(angle.toDouble()).toFloat() * radius, cy + Math.sin(angle.toDouble()).toFloat() * radius)
    }
}
