/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.render

import net.airplus.features.module.modules.render.HUD
import net.airplus.ui.font.GameFontRenderer
import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.minecraft.client.gui.ChatLine
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.entity.player.EntityPlayer.EnumChatVisibility
import org.lwjgl.opengl.GL11
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/**
 * 自定义聊天渲染：整体圆角背景 + 可选模糊（随 scale/位置/圆角自适应）、
 * 新消息逐行错位滑入+淡入（stagger）、对称的退场上滑+淡出、
 * 背景随行数/内容变化的弹簧缩放、平滑滚动。
 *
 * 由 MixinGuiNewChat 接管 drawChat 后调用，矩阵链与原版一致：
 * GuiIngame translate(0, h-48) → translate(2, 20) → scale(chatScale)。
 */
object ChatHudRenderer : MinecraftInstance {

    private const val LINE_HEIGHT = 9F
    private const val ENTRANCE_DISTANCE = 9F          // 每条新消息推动整体上滑的距离
    private const val ENTRANCE_MAX = 36F              // 刷屏时最大偏移，避免堆叠过深
    private const val FADE_IN_MS = 300F               // 新消息淡入时长
    private const val STAGGER_MS = 40F                // 逐行错位入场的间隔
    private const val EXIT_START_TICKS = 180F         // 退场起点（接管原版 180~200 tick 的淡出段）
    private const val EXIT_TICKS = 6F                 // 退场时长（≈300ms，与入场同速）

    private class ChatLineInfo(val text: String, val alpha: Float, val y: Float)

    private val birthTimes = WeakHashMap<ChatLine, Long>()
    private var entranceOffset = 0F
    private var scrollAnim = 0F
    private var lastFrame = 0L

    // 背景弹簧状态（NaN 表示尚未初始化，首帧直接吸附到目标值）
    private var animBlockY1 = Float.NaN
    private var animBlockY2 = Float.NaN
    private var animBlockX2 = Float.NaN
    private var velY1 = 0F
    private var velY2 = 0F
    private var velX2 = 0F

    /**
     * setChatLine 新增了 [addedCount] 条可见行（新行位于 drawnChatLines 头部）。
     * 逐行错位入场：同批次内越靠下的行越先出现，向上依次延迟。
     */
    fun onChatLineAdded(drawnChatLines: List<ChatLine>, addedCount: Int) {
        if (addedCount <= 0) return
        val now = System.currentTimeMillis()
        val count = addedCount.coerceAtMost(drawnChatLines.size)
        for (i in 0 until count) {
            birthTimes[drawnChatLines[i]] = now + ((count - 1 - i) * STAGGER_MS.toLong())
        }
        entranceOffset = (entranceOffset + ENTRANCE_DISTANCE * addedCount).coerceAtMost(ENTRANCE_MAX)
    }

    fun onClear() {
        birthTimes.clear()
        entranceOffset = 0F
        scrollAnim = 0F
        lastFrame = 0L
        animBlockY1 = Float.NaN
        animBlockY2 = Float.NaN
        animBlockX2 = Float.NaN
        velY1 = 0F
        velY2 = 0F
        velX2 = 0F
    }

    fun renderChat(
        drawnChatLines: List<ChatLine>,
        scrollPos: Int,
        isScrolled: Boolean,
        updateCounter: Int,
        lineCount: Int,
        chatOpen: Boolean
    ) {
        if (mc.gameSettings.chatVisibility == EnumChatVisibility.HIDDEN) return
        if (drawnChatLines.isEmpty() || lineCount <= 0) return

        val hud = HUD
        val now = System.currentTimeMillis()
        val deltaMs = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(0L, 100L)
        lastFrame = now

        // --- 动画状态推进（帧率无关） ---
        val speed = hud.chatAnimationSpeed.coerceIn(0.1F, 3F)
        val factor = ((deltaMs / 1000F) * 14F * speed).coerceIn(0F, 1F)

        if (hud.chatSmoothScroll) {
            scrollAnim += (scrollPos - scrollAnim) * factor
            if (abs(scrollAnim - scrollPos) < 0.01F) scrollAnim = scrollPos.toFloat()
        } else {
            scrollAnim = scrollPos.toFloat()
        }

        if (hud.chatAnimation) {
            entranceOffset -= entranceOffset * factor
            if (entranceOffset < 0.1F) entranceOffset = 0F
        } else {
            entranceOffset = 0F
        }

        // --- 字体（复用 HUD 的 FontChat 设置，字体可在 HUD 设置中选择） ---
        val customFont = hud.shouldModifyChatFont()
        val font = if (customFont) hud.chatFont else null
        val vanillaFont = mc.fontRendererObj
        val fontHeight = when (font) {
            is GameFontRenderer -> font.height
            else -> vanillaFont.FONT_HEIGHT
        }
        val lineH = max(LINE_HEIGHT, fontHeight.toFloat())
        val stringWidth = { s: String ->
            (font?.getStringWidth(s) ?: vanillaFont.getStringWidth(s)).toFloat()
        }
        val drawText = { s: String, x: Float, y: Float, c: Int ->
            if (font != null) font.drawStringWithShadow(s, x, y, c)
            else vanillaFont.drawStringWithShadow(s, x, y, c)
        }

        // --- 滚动插值参数 ---
        val maxScroll = (drawnChatLines.size - lineCount).coerceAtLeast(0)
        val anim = scrollAnim.coerceIn(0F, maxScroll.toFloat())
        val base = floor(anim).toInt().coerceAtMost((drawnChatLines.size - 1).coerceAtLeast(0))
        val frac = anim - base

        // --- 收集可见行 ---
        val opacityBase = mc.gameSettings.chatOpacity * 0.9F + 0.1F
        val infos = ArrayList<ChatLineInfo>(lineCount)
        var maxTextWidth = 0F
        var blockAlpha = 0F

        for (slot in 0 until lineCount) {
            val index = base + slot
            if (index >= drawnChatLines.size) break
            val chatLine = drawnChatLines[index] ?: continue

            // 原版 age 为 tick 计数（50ms 一跳，淡出只有 20Hz），
            // 叠加 partialTicks 插值到帧率级平滑；时间轴方向不变，仅消除台阶感
            val age = (updateCounter - chatLine.getUpdatedCounter()) + mc.timer.renderPartialTicks

            var alpha = 1F
            var yOff = 0F   // 每行的额外纵向偏移（入场 / 退场动画）

            if (!chatOpen && hud.chatAnimation && age >= EXIT_START_TICKS) {
                // 退场：与入场同速的上滑 + 淡出（接管原版 180~200 tick 的淡出段）
                val t = ((age - EXIT_START_TICKS) / EXIT_TICKS).coerceIn(0F, 1F)
                if (t >= 1F) continue
                alpha = 1F - easeInCubic(t)
                yOff -= easeInCubic(t) * lineH
            } else {
                if (age >= 200 && !chatOpen) continue
                // 原版的 200 tick 淡出曲线
                alpha = ((1F - age / 200F) * 10F).coerceIn(0F, 1F)
                alpha *= alpha
                if (chatOpen) alpha = 1F
            }
            alpha *= opacityBase

            // 新消息逐行错位滑入 + 淡入
            if (hud.chatAnimation) {
                val birth = birthTimes[chatLine]
                if (birth != null) {
                    if (now < birth) continue
                    val progress = ((now - birth) / FADE_IN_MS).coerceIn(0F, 1F)
                    val e = easeOutExpo(progress)
                    alpha *= e
                    yOff += (1F - e) * lineH
                }
            }

            if (alpha <= 0.012F) continue

            val text = chatLine.getChatComponent().getFormattedText()
            val y = -(slot * lineH) + frac * lineH + entranceOffset + yOff
            infos.add(ChatLineInfo(text, alpha, y))
            maxTextWidth = max(maxTextWidth, stringWidth(text))
            blockAlpha = max(blockAlpha, alpha)
        }
        if (infos.isEmpty()) return

        // --- 背景块布局（内容自适应，随入场动画/滚动同步移动） ---
        val radius = hud.chatRoundedRadius
        val pad = 3F
        val blockX1 = -pad
        // 自定义宽度不小于最长消息 + 4px（pad + 1）
        val blockX2 = max(maxTextWidth + pad + 1F, if (hud.chatCustomWidth) hud.chatWidth else 0F)
        val blockY1 = infos[infos.size - 1].y - lineH
        var blockY2 = infos[0].y

        // 最大高度：背景块向上最多延伸 chatHeightMax px（历史过多时顶部被裁剪）
        blockY2 = blockY2.coerceAtLeast(blockY1 - hud.chatHeightMax)

        // --- 背景弹簧缩放（历史行数 / 内容宽度变化时弹性放大或缩小） ---
        val dt = deltaMs / 1000F
        if (hud.chatAnimation) {
            if (animBlockY1.isNaN()) {
                animBlockY1 = blockY1
                animBlockY2 = blockY2
                animBlockX2 = blockX2
            }
            val (y1, vy1) = integrateSpring(animBlockY1, velY1, blockY1, dt)
            animBlockY1 = y1
            velY1 = vy1
            val (y2, vy2) = integrateSpring(animBlockY2, velY2, blockY2, dt)
            animBlockY2 = y2
            velY2 = vy2
            val (x2, vx2) = integrateSpring(animBlockX2, velX2, blockX2, dt)
            animBlockX2 = x2
            velX2 = vx2
        } else {
            animBlockY1 = blockY1
            animBlockY2 = blockY2
            animBlockX2 = blockX2
            velY1 = 0F
            velY2 = 0F
            velX2 = 0F
        }

        GlStateManager.pushMatrix()
        try {
            // 位置偏移：在原版聊天原点 (2, h-28) 基础上叠加自定义 X/Y 偏移
            val originX = 2F + hud.chatPosX
            val originY = 20F + hud.chatPosY
            GlStateManager.translate(originX, originY, 0F)
            val chatScale = mc.gameSettings.chatScale
            GlStateManager.scale(chatScale, chatScale, 1F)

            if (hud.chatBackground) {
                val bgColor = hud.chatBackgroundColors.color()
                val bgAlpha = ((bgColor.alpha / 255F) * blockAlpha).coerceIn(0F, 1F)
                val bgARGB = ((bgAlpha * 255F).toInt() shl 24) or
                        (bgColor.red shl 16) or (bgColor.green shl 8) or bgColor.blue

                // 模糊 AABB 换算为屏幕绝对坐标：origin = (2+posX, h-28+posY)，跟随弹簧后的实际背景
                // blur 状态随内容透明度管理：背景几乎完全淡出时跳过模糊，避免模糊区域残留
                if (hud.chatBackgroundBlur && bgAlpha > 0.02F) {
                    val sr = ScaledResolution(mc)
                    val absOriginY = sr.scaledHeight - 28F + hud.chatPosY
                    val absX1 = originX + blockX1 * chatScale
                    val absY1 = absOriginY + animBlockY1 * chatScale
                    val absX2 = originX + animBlockX2 * chatScale
                    val absY2 = absOriginY + animBlockY2 * chatScale

                    // 遮罩在当前矩阵下按局部坐标绘制，自动跟随 scale 与位置
                    HudBlur.blur(absX1, absY1, absX2, absY2, hud.chatBlurStrength, hud.chatBlurMode, radius) {
                        drawRoundedRect(blockX1, animBlockY1, animBlockX2, animBlockY2, -1, radius)
                    }
                }

                drawRoundedRect(blockX1, animBlockY1, animBlockX2, animBlockY2, bgARGB, radius)
            }

            // 文本（裁剪到背景范围内，防止入场动画/弹簧缩放期间文字超出背景范围）
            val clipText = hud.chatBackground
            if (clipText) {
                val sr = ScaledResolution(mc)
                val factor = sr.scaleFactor
                val absOriginY = sr.scaledHeight - 28F + hud.chatPosY
                val clipTop = absOriginY + animBlockY1 * chatScale
                val clipBottom = absOriginY + animBlockY2 * chatScale
                GL11.glScissor(
                    ((originX + blockX1 * chatScale) * factor).toInt(),
                    (mc.displayHeight - clipBottom * factor).toInt(),
                    (((animBlockX2 - blockX1) * chatScale) * factor).toInt().coerceAtLeast(0),
                    ((clipBottom - clipTop) * factor).toInt().coerceAtLeast(0)
                )
                GL11.glEnable(GL11.GL_SCISSOR_TEST)
            }
            GlStateManager.enableBlend()
            for (info in infos) {
                val a = (info.alpha * 255F).toInt().coerceIn(0, 255)
                if (a <= 3) continue
                drawText(info.text, 0F, info.y - lineH + (lineH - fontHeight) / 2F + 1F, 0xFFFFFF or (a shl 24))
            }
            GlStateManager.disableAlpha()
            GlStateManager.disableBlend()
            if (clipText) GL11.glDisable(GL11.GL_SCISSOR_TEST)
        } finally {
            GlStateManager.popMatrix()
        }
    }

    private fun easeOutExpo(t: Float): Float = if (t >= 1F) 1F else 1F - 2F.pow(-10F * t)

    private fun easeInCubic(t: Float): Float = t * t * t

    /**
     * 半隐式欧拉弹簧积分，按固定子步长积分保证帧率无关。
     * [pos]/[vel] 为当前位置与速度，[target] 为目标值，[dt] 单位为秒。
     */
    private fun integrateSpring(pos: Float, vel: Float, target: Float, dt: Float): Pair<Float, Float> {
        if (dt <= 0F) return pos to vel
        val stiffness = 190F
        val damping = 24F
        val h = 1F / 120F
        var p = pos
        var v = vel
        var remaining = dt
        while (remaining > 0F) {
            val step = minOf(h, remaining)
            v += ((target - p) * stiffness - v * damping) * step
            p += v * step
            remaining -= step
        }
        return p to v
    }
}
