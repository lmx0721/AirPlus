/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.render

import net.airplus.ui.font.Fonts
import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.RenderHelper
import net.minecraft.entity.player.EntityPlayer
import org.lwjgl.opengl.GL11
import java.awt.Color
import kotlin.math.pow

/**
 * Rise 主题热栏（Rise HotBar.java 忠实移植，仅 Rise 主题观感，Traditional 分支不迁移）：
 * 悬浮于屏幕底边的圆角深色条 + 顶部圆角 XP 进度条与居中等级数字 + 选中槽位的深色圆角高亮。
 *
 * 布局（按 Rise Themes.qd() = 4.5 换算，ScaledResolution 单位，对齐原版热栏位置）：
 * - 背景     : (宽/2-91, 高-25) ~ (宽/2+91, 高-3)，圆角 = Rise-Radius（默认 9，Rise Interface 圆角）
 * - XP 条    : 背景上方 0.6px 间隙，高 3，圆角 = min(半径, 2)；轨道与背景同色（半透明黑叠加）
 * - 等级数字 : 居中，底边贴在 XP 条上方（高-26），白色
 * - 选中框   : 22x22 圆角 8，顶部高出背景 1px，横向按 Rise 0.03/ms 插值滑动
 * - 物品     : (宽/2-88+槽*20, 高-23)，保留原版拿起时的 squash 缩放动画
 *
 * 渲染管线对齐 Rise 的 ShaderQueue：
 * - BLUR    → [HudBlur.blur]（x/y 为缩放 GUI 绝对坐标，默认遮罩即指定半径的圆角矩形；
 *             背景/XP 区域各一次，与同帧 HUD 组件共享一次全屏模糊快照）
 * - BLOOM   → HudBlur 内建的 Rise 式辉光累积（受 Client 全局 Glow 设置控制，关闭时自动省略）
 * - REGULAR → 半透明黑叠层（Rise Themes.aDS = Color(0,0,0,110)：背景/选中框/XP 轨道同色，
 *             选中槽位因叠加变得更深）+ 物品 + 白色等级数字
 *
 * 由 MixinGuiInGame 在接管原版热栏后调用（与 LB 自绘热栏同阶段，物品绘制的状态序列一致）。
 */
object RiseHotbar : MinecraftInstance {

    /** 设置由 HUD 模块桥接（对齐 BmDock.Settings 的模式），渲染器不直接依赖模块实现。 */
    interface Settings {
        /** 是否绘制 XP 进度条（原版经验条在接管分支中始终被压制，与 Rise 行为一致） */
        fun showXPBar(): Boolean

        /** 背景圆角半径（0..10，Rise Interface 的 Rounding Radius，默认 9） */
        fun radius(): Float

        /** 背景/选中框/XP 轨道共用的半透明黑 alpha（0..255，Rise 主题默认 110） */
        fun backgroundAlpha(): Int

        /** XP 进度填充色（Rise 的主题 accent 色；ARGB） */
        fun accentColor(): Int
    }

    // ---- Rise HotBar.java 布局常量 ----
    private const val BG_HALF_WIDTH = 91F
    private const val BG_TOP_FROM_BOTTOM = 25F
    private const val BG_BOTTOM_FROM_BOTTOM = 3F
    private const val SELECTOR_SIZE = 22F
    private const val SELECTOR_RADIUS = 8F
    private const val SELECTOR_TOP_FROM_BOTTOM = 26F
    private const val XP_HEIGHT = 3F
    private const val XP_GAP = 0.6F          // XP 条底边与背景顶边的间隙
    private const val XP_RADIUS_CAP = 2F     // Rise: min(interfaceRadius, 2.0)
    private const val ITEM_Y_FROM_BOTTOM = 23F
    private const val LEVEL_BOTTOM_FROM_BOTTOM = 26F

    // ---- 平滑状态 ----
    private var lastFrame = 0L
    private var selectorX = -1F              // -1 = 尚未初始化，首帧直接吸附
    private var xpProgress = 0F

    @JvmStatic
    fun render(resolution: ScaledResolution, player: EntityPlayer, partialTicks: Float, settings: Settings) {
        val sw = resolution.scaledWidth
        val sh = resolution.scaledHeight

        val bgX1 = sw / 2F - BG_HALF_WIDTH
        val bgX2 = bgX1 + BG_HALF_WIDTH * 2F
        val bgY1 = sh - BG_TOP_FROM_BOTTOM
        val bgY2 = sh - BG_BOTTOM_FROM_BOTTOM
        val radius = settings.radius().coerceIn(0F, 10F)

        val slot = player.inventory.currentItem.coerceIn(0, 8)
        val targetExperience = player.experience.coerceIn(0F, 1F)
        val xpVisible = settings.showXPBar() && targetExperience > 0F

        // ---- 帧推进（帧率无关插值）----
        val now = System.currentTimeMillis()
        val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(0L, 100L)
        lastFrame = now

        // Rise: rPosX = lerp(rPosX, 目标, 0.03F * elapsedMs)，选中框横向滑动
        val selectorTarget = sw / 2F - 92F + slot * 20F + 1F
        selectorX = if (selectorX < 0F) selectorTarget
        else lerp(selectorX, selectorTarget, (0.03F * dt).coerceIn(0F, 1F))

        // Rise: xpProgress = lerp(xpProgress, 经验, 0.1F/帧)，按 dt 归一为帧率无关形式
        xpProgress = lerp(
            xpProgress, targetExperience,
            (1F - 0.9F.pow(dt / 16.6667F)).coerceIn(0F, 1F)
        )

        // ---- BLUR（Rise BLUR 队列）+ 辉光（Rise BLOOM 队列的等价实现，随全局 Glow 设置）----
        HudBlur.blur(bgX1, bgY1, bgX2, bgY2, radius)
        if (xpVisible) {
            HudBlur.blur(bgX1, bgY1 - XP_HEIGHT - XP_GAP, bgX2, bgY1 - XP_GAP, minOf(radius, XP_RADIUS_CAP))
        }

        // ---- REGULAR：半透明黑叠层（同色叠加，选中槽位更深）----
        val dark = (settings.backgroundAlpha().coerceIn(0, 255)) shl 24
        val accent = settings.accentColor()

        GL11.glPushMatrix()
        GL11.glDisable(GL11.GL_DEPTH_TEST)

        if (xpVisible) {
            val xpRadius = minOf(radius, XP_RADIUS_CAP)
            val xpY1 = bgY1 - XP_HEIGHT - XP_GAP
            val xpY2 = bgY1 - XP_GAP
            // 轨道（Rise 用主题主色 aDS）+ 进度填充（Rise 用主题 accent 色）
            drawRoundedRect(bgX1, xpY1, bgX2, xpY2, dark, xpRadius)
            if (xpProgress > 0.004F) {
                drawRoundedRect(bgX1, xpY1, bgX1 + BG_HALF_WIDTH * 2F * xpProgress, xpY2, accent, xpRadius)
            }
        }

        // 背景
        drawRoundedRect(bgX1, bgY1, bgX2, bgY2, dark, radius)

        // 选中框（半透明黑叠加在背景之上 → 选中槽位更深的圆角方块）
        drawRoundedRect(
            selectorX, sh - SELECTOR_TOP_FROM_BOTTOM,
            selectorX + SELECTOR_SIZE, sh - SELECTOR_TOP_FROM_BOTTOM + SELECTOR_SIZE,
            dark, SELECTOR_RADIUS
        )

        GL11.glEnable(GL11.GL_DEPTH_TEST)
        GL11.glPopMatrix()

        // ---- 物品（Rise renderHotBarItem：拿起 squash 动画 + 数量/耐久 overlay）----
        GlStateManager.enableRescaleNormal()
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        RenderHelper.enableGUIStandardItemLighting()
        GlStateManager.enableDepth()
        GlStateManager.depthMask(true)

        val renderer = mc.renderItem
        for (j in 0..8) {
            val stack = player.inventory.mainInventory[j] ?: continue
            val ix = sw / 2 - 88 + j * 20
            val iy = sh - ITEM_Y_FROM_BOTTOM.toInt()
            val squash = stack.animationsToGo - partialTicks
            if (squash > 0F) {
                GlStateManager.pushMatrix()
                val f1 = 1F + squash / 5F
                GlStateManager.translate(ix + 8F, iy + 12F, 0F)
                GlStateManager.scale(1F / f1, (f1 + 1F) / 2F, 1F)
                GlStateManager.translate(-(ix + 8F), -(iy + 12F), 0F)
            }
            renderer.renderItemAndEffectIntoGUI(stack, ix, iy)
            if (squash > 0F) GlStateManager.popMatrix()
            renderer.renderItemOverlays(mc.fontRendererObj, stack, ix, iy)
        }

        RenderHelper.disableStandardItemLighting()
        GlStateManager.disableRescaleNormal()
        GlStateManager.disableBlend()

        // ---- 等级数字（Rise: experienceLevel > 0 时居中于 XP 条上方，白色无阴影）----
        if (settings.showXPBar() && player.experienceLevel > 0) {
            val font = Fonts.fontSemibold35
            val text = player.experienceLevel.toString()
            val x = (sw - font.getStringWidth(text)) / 2F
            val y = sh - LEVEL_BOTTOM_FROM_BOTTOM - font.height
            GlStateManager.enableBlend()
            font.drawString(text, x, y, Color.WHITE.rgb)
            GlStateManager.disableBlend()
        }
    }

    private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t
}
