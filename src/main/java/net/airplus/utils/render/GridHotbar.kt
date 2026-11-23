/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.render

import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.render.RenderUtils.drawRoundedBorder
import net.airplus.utils.render.RenderUtils.drawRoundedRectAA
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.entity.player.EntityPlayer
import org.lwjgl.opengl.GL11
import java.awt.Color

/**
 * Grid 主题热栏：每个槽位是独立的方格（圆角小方块 + 细边框），整体排成一行
 * 看起来是一个整体；底部有一条可自定义颜色的横线在被选槽位下方平滑滑动。
 *
 * 布局（ScaledResolution 单位，比 LB/Rise 的 182x22 更紧凑）：
 * - 槽位  : 16x16，间隙 2，共 9 格（总宽 160），水平居中，底边贴屏幕底
 * - 底衬  : 整行 bounding rect 的低透明度圆角衬底（把独立方格"焊"成一个整体）
 * - 选中线: 高 2，宽与槽位对齐，位于整行最底部，按 Rise 0.03/ms 插值滑动
 * - 物品  : 16px 原始尺寸 + 0.85 中心缩放（在 16px 格内留呼吸位），保留原版拿起 squash 动画
 *
 * 渲染阶段与 RiseHotbar 相同：
 * - BLUR → [HudBlur.blur]（开关控制，遮罩 = 整行圆角矩形，与同帧 HUD 组件共享模糊快照）
 * - REGULAR → 底衬 + 方格 + 选中线 + 物品（走 [HotbarItems] 公共物品绘制）
 */
object GridHotbar : MinecraftInstance {

    /** 设置由 HUD 模块桥接（对齐 RiseHotbar.Settings 的模式），渲染器不直接依赖模块实现。 */
    interface Settings {
        /** 是否对整行区域做背景模糊（HudBlur，受 Client 全局 Blur 模式/强度约束） */
        fun blur(): Boolean

        /** 底部选中线的颜色（ARGB） */
        fun lineColor(): Int

        /** 方格半透明黑底的 alpha（0..255） */
        fun backgroundAlpha(): Int
    }

    // ---- 布局常量 ----
    private const val SLOT = 16F
    private const val GAP = 2F
    private const val SLOTS = 9
    private const val BAR_WIDTH = SLOTS * SLOT + (SLOTS - 1) * GAP   // 160
    private const val SLOT_RADIUS = 3F
    private const val BAR_RADIUS = 5F
    private const val LINE_HEIGHT = 2F
    private const val ITEM_SCALE = 0.85F

    // 方格细边框（低透明度白，只在模糊/深色底上勾出格子轮廓）
    private val slotBorderColor = Color(255, 255, 255, 28).rgb

    // ---- 平滑状态 ----
    private var lastFrame = 0L
    private var lineX = -1F              // -1 = 尚未初始化，首帧直接吸附

    @JvmStatic
    fun render(resolution: ScaledResolution, player: EntityPlayer, partialTicks: Float, settings: Settings) {
        val sw = resolution.scaledWidth
        val sh = resolution.scaledHeight

        val barX1 = sw / 2F - BAR_WIDTH / 2F
        val barX2 = barX1 + BAR_WIDTH
        val lineY2 = sh - 1F                 // 选中线底边（贴屏幕底，对齐 LB 热栏基线）
        val lineY1 = lineY2 - LINE_HEIGHT    // 选中线顶边（高 2）
        val slotY2 = lineY1 - 1F             // 槽位底边（与线留 1px 间隙）
        val slotY1 = slotY2 - SLOT           // 槽位顶边

        val slot = player.inventory.currentItem.coerceIn(0, 8)

        // ---- 帧推进（帧率无关插值）----
        val now = System.currentTimeMillis()
        val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(0L, 100L)
        lastFrame = now

        // 选中线横向滑动（Rise selectorX 同款 0.03F/ms 插值）
        val lineTarget = barX1 + slot * (SLOT + GAP)
        lineX = if (lineX < 0F) lineTarget
        else lerp(lineX, lineTarget, (0.03F * dt).coerceIn(0F, 1F))

        // ---- BLUR（整行 bounding rect，开关控制；与其它 HUD 组件共享同帧模糊快照）----
        if (settings.blur()) {
            HudBlur.blur(barX1, slotY1, barX2, lineY2, BAR_RADIUS) {
                RenderUtils.drawRoundedRect(barX1, slotY1, barX2, lineY2, -1, BAR_RADIUS)
            }
        }

        // ---- REGULAR ----
        GL11.glPushMatrix()
        GL11.glDisable(GL11.GL_DEPTH_TEST)

        // 底衬：把独立方格连成一个整体的低透明度圆角面板
        drawRoundedRectAA(barX1, slotY1, barX2, slotY2, Color(0, 0, 0, 60).rgb, BAR_RADIUS)

        // 每个槽位的独立方格（半透明黑底 + 细边框）
        val slotBg = (settings.backgroundAlpha().coerceIn(0, 255)) shl 24
        for (j in 0 until SLOTS) {
            val sx = barX1 + j * (SLOT + GAP)
            drawRoundedRectAA(sx, slotY1, sx + SLOT, slotY2, slotBg, SLOT_RADIUS)
            drawRoundedBorder(sx, slotY1, sx + SLOT, slotY2, 0.5F, slotBorderColor, SLOT_RADIUS)
        }

        // 选中线（颜色自定义，宽度与槽位对齐，平滑滑动）
        drawRoundedRectAA(lineX, lineY1, lineX + SLOT, lineY2, settings.lineColor(), 1F)

        GL11.glEnable(GL11.GL_DEPTH_TEST)
        GL11.glPopMatrix()

        // ---- 物品（0.85 中心缩放 + squash 动画 + 原版数量/耐久 overlay）----
        HotbarItems.render(player, partialTicks, true, ITEM_SCALE) { j ->
            val sx = barX1 + j * (SLOT + GAP)
            Pair((sx + SLOT / 2F - 8F).toInt(), (slotY1 + SLOT / 2F - 8F).toInt())
        }
    }

    private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t
}
