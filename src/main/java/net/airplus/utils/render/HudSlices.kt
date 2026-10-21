/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.render

import net.airplus.utils.client.MinecraftInstance
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.GL11

/**
 * Samsara HUD 预烘焙贴图的切片绘制（1.8.9 遗留 GL 版）。
 *
 * Samsara 的面板阴影来自预烘焙贴图而非实时模糊：
 * - 3 段横向切片（potion/shadow + glass、session 面板）：左右圆角边缘保持原始比例，
 *   仅中间中性区域随宽度拉伸（ShadowSlices.panelStrip）。
 * - 9-slice（nametags/shadow + capsule）：横竖双向切片（ShadowSlices.nine）。
 *
 * 所有贴图位于 airplus/hud/（从 Samsara assets/samsara/textures/hud 原样拷贝），
 * tint 颜色以 RGBA 分量与贴图相乘（同 Samsara blit tint）。
 */
object HudSlices : MinecraftInstance {

    // ---- 贴图资源（assets/minecraft/airplus/hud/） ----
    val POTION_SHADOW = ResourceLocation("minecraft", "airplus/hud/potion/shadow.png")
    val POTION_GLASS = ResourceLocation("minecraft", "airplus/hud/potion/glass.png")
    val SESSION_PANEL = ResourceLocation("minecraft", "airplus/hud/session.png")
    val SESSION_SKULL = ResourceLocation("minecraft", "airplus/hud/session-skull.png")
    val SESSION_WINS = ResourceLocation("minecraft", "airplus/hud/session-wins.png")
    val NAMETAG_SHADOW = ResourceLocation("minecraft", "airplus/hud/nametags/shadow.png")
    val NAMETAG_CAPSULE = ResourceLocation("minecraft", "airplus/hud/nametags/capsule.png")

    /** 单个带 UV 的染色四边形（1.8.9 POSITION_TEX_COLOR 管线）。 */
    fun texRect(
        texture: ResourceLocation, x: Float, y: Float, w: Float, h: Float,
        u: Float, v: Float, uw: Float, vh: Float, texW: Float, texH: Float, argb: Int
    ) {
        if (w <= 0f || h <= 0f) return
        val a = (argb ushr 24 and 0xFF) / 255f
        val r = (argb ushr 16 and 0xFF) / 255f
        val g = (argb ushr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0)
        GlStateManager.enableTexture2D()
        mc.textureManager.bindTexture(texture)
        val tessellator = Tessellator.getInstance()
        val buffer = tessellator.worldRenderer
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR)
        val u0 = u / texW; val v0 = v / texH
        val u1 = (u + uw) / texW; val v1 = (v + vh) / texH
        buffer.pos(x.toDouble(), y.toDouble(), 0.0).tex(u0.toDouble(), v0.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos(x.toDouble(), (y + h).toDouble(), 0.0).tex(u0.toDouble(), v1.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos((x + w).toDouble(), (y + h).toDouble(), 0.0).tex(u1.toDouble(), v1.toDouble()).color(r, g, b, a).endVertex()
        buffer.pos((x + w).toDouble(), y.toDouble(), 0.0).tex(u1.toDouble(), v0.toDouble()).color(r, g, b, a).endVertex()
        tessellator.draw()
    }

    /**
     * Samsara PotionStatusSurface.panelTexture / OpaiTargetHudSurface.panel 的切片：
     * 贴图四周含 margin px（逻辑坐标）的烘焙阴影，绘制时外扩 margin，9-slice 双向切片——
     * 四角保持原始比例（烘焙圆角不变形），边缘中性区域随宽/高拉伸。
     * 高度等于烘焙基准（potion=20 / session=49）时与原 3 段横向切片逐像素一致。
     *
     * @param margin      阴影外扩（逻辑 px）：potion=5，session/target=8
     * @param texScale    贴图相对逻辑坐标的超采样倍数：potion=8，session/target=4
     * @param contentW    面板内容宽（逻辑 px）
     * @param contentH    面板内容高（逻辑 px）：potion=20，session=49
     * @param radius      面板圆角（逻辑 px）：potion=3.75，session=7.5
     * @param texW,texH   贴图像素尺寸
     */
    fun panelStrip(
        texture: ResourceLocation, x: Float, y: Float, contentW: Float,
        contentH: Float, margin: Float, texScale: Float, radius: Float,
        texW: Float, texH: Float, argb: Int
    ) {
        // 贴图已按 (内容基准 + 2*margin) * texScale 烘焙，换算回逻辑尺寸
        val sourceW = texW / texScale
        val sourceH = texH / texScale
        val destW = contentW + margin * 2f
        val destH = contentH + margin * 2f
        val drawX = x - margin
        val drawY = y - margin
        // 角部逻辑尺寸 = margin + 圆角：双向一致，保证贴图烘焙圆角不随面板尺寸拉伸变形
        val edge = margin + radius
        val ex = edge.coerceAtMost(destW / 2f).coerceAtMost(sourceW / 2f)
        val ey = edge.coerceAtMost(destH / 2f).coerceAtMost(sourceH / 2f)
        val dx = floatArrayOf(0f, ex, destW - ex, destW)
        val dy = floatArrayOf(0f, ey, destH - ey, destH)
        val sx = floatArrayOf(0f, ex, sourceW - ex, sourceW)
        val sy = floatArrayOf(0f, ey, sourceH - ey, sourceH)
        for (row in 0..2) for (col in 0..2) {
            val dw = dx[col + 1] - dx[col]
            val dh = dy[row + 1] - dy[row]
            val suw = (sx[col + 1] - sx[col]) * texScale
            val svh = (sy[row + 1] - sy[row]) * texScale
            if (dw <= 0f || dh <= 0f || suw <= 0f || svh <= 0f) continue
            texRect(
                texture, drawX + dx[col], drawY + dy[row], dw, dh,
                sx[col] * texScale, sy[row] * texScale, suw, svh, texW, texH, argb
            )
        }
    }

    /** Samsara NameTags Geometry.slices 的 9-slice：返回 (dx, dy, dw, dh, su, sv, suw, svh) 三元组列表。 */
    private data class Slice(
        val dx: Float, val dy: Float, val dw: Float, val dh: Float,
        val su: Float, val sv: Float, val suw: Float, val svh: Float
    )

    private fun slices(shadow: Boolean, width: Float, height: Float, radius: Float): List<Slice> {
        val edge = if (shadow) 20f else radius
        val size = if (shadow) 56f else 40f
        val ex = edge.coerceAtMost(width / 2f)
        val ey = edge.coerceAtMost(height / 2f)
        val x = floatArrayOf(0f, ex, width - ex, width)
        val y = floatArrayOf(0f, ey, height - ey, height)
        val src = floatArrayOf(0f, edge, size - edge, size)
        val result = ArrayList<Slice>(9)
        for (row in 0..2) for (col in 0..2) {
            val w = x[col + 1] - x[col]
            val h = y[row + 1] - y[row]
            if (w > 0f && h > 0f) result += Slice(
                x[col], y[row], w, h,
                src[col], src[row], src[col + 1] - src[col], src[row + 1] - src[row]
            )
        }
        return result
    }

    /**
     * Samsara NativeSurface.mask 的 9-slice 绘制（贴图源尺寸 56/40 逻辑 px，×4 超采样）。
     *
     * @param shadow true = nametags/shadow.png（56 源，edge 20），false = capsule.png（40 源，edge=radius）
     */
    fun nametagMask(
        shadowLayer: Boolean, x: Float, y: Float, width: Float, height: Float,
        radius: Float, argb: Int
    ) {
        val texture = if (shadowLayer) NAMETAG_SHADOW else NAMETAG_CAPSULE
        val size = if (shadowLayer) 56f else 40f
        for (s in slices(shadowLayer, width, height, radius)) {
            texRect(
                texture, x + s.dx, y + s.dy, s.dw, s.dh,
                s.su * 4f, s.sv * 4f, s.suw * 4f, s.svh * 4f,
                size * 4f, size * 4f, argb
            )
        }
    }
}
