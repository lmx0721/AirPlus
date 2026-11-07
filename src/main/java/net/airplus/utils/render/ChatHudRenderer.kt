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

    private class ChatLineInfo(val text: String, var alpha: Float, var y: Float)

    private val birthTimes = WeakHashMap<ChatLine, Long>()
    private var entranceOffset = 0F
    private var scrollAnim = 0F
    private var lastFrame = 0L

    // 展开动画：0 = 收起（关闭状态下的可见范围），1 = 完全展开（打开时的完整历史窗口）
    private var expandProgress = 0F

    // 背景弹簧状态（NaN 表示尚未初始化，首帧直接吸附到目标值）
    private var animBlockY1 = Float.NaN
    private var animBlockY2 = Float.NaN
    private var animBlockX2 = Float.NaN
    private var velY1 = 0F
    private var velY2 = 0F
    private var velX2 = 0F

    /** 最近一帧聊天是否有可见内容（每帧 renderChat 入口复位），供 HudBlur 预拍基准快照判断 */
    @JvmField
    internal var chatHadLines = false

    // ===== Rise 风格状态（Chat-Mode = Rise，逻辑移植自 Rise Chat）=====
    private var riseVisibleLines = 0
    private var riseLastLineCount = -1
    private var riseHeight = Float.NaN
    private var riseScrollOffset = 0F
    private var riseDisappearTimer = 0L

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
        expandProgress = 0F
        animBlockY1 = Float.NaN
        animBlockY2 = Float.NaN
        animBlockX2 = Float.NaN
        velY1 = 0F
        velY2 = 0F
        velX2 = 0F
        riseVisibleLines = 0
        riseLastLineCount = -1
        riseHeight = Float.NaN
        riseScrollOffset = 0F
        riseDisappearTimer = 0L
    }

    fun renderChat(
        drawnChatLines: List<ChatLine>,
        scrollPos: Int,
        isScrolled: Boolean,
        updateCounter: Int,
        lineCount: Int,
        chatOpen: Boolean
    ) {
        chatHadLines = false
        if (mc.gameSettings.chatVisibility == EnumChatVisibility.HIDDEN) return
        if (drawnChatLines.isEmpty() || lineCount <= 0) return
        chatHadLines = true

        // Rise 风格：独立渲染路径（Rise Chat 移植）
        if (HUD.chatMode == "Rise") {
            renderChatRise(drawnChatLines, scrollPos, lineCount, chatOpen)
            return
        }

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

        // --- 展开动画：打开/关闭聊天栏时，历史区域在「关闭时可见范围」与「完整窗口」间平滑过渡 ---
        val expandTarget = if (chatOpen) 1F else 0F
        expandProgress += (expandTarget - expandProgress) * factor
        if (abs(expandProgress - expandTarget) < 0.002F) expandProgress = expandTarget
        // 收起过程中仍需渲染历史行（超出关闭可见范围的行交由展开边缘逐行隐藏）
        val revealActive = chatOpen || expandProgress > 0.002F

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
        var youngMinY = Float.MAX_VALUE   // 关闭状态下仍可见（age < 200）的最靠上行的 y

        for (slot in 0 until lineCount) {
            val index = base + slot
            if (index >= drawnChatLines.size) break
            val chatLine = drawnChatLines[index] ?: continue

            // 原版 age 为 tick 计数（50ms 一跳，淡出只有 20Hz），
            // 叠加 partialTicks 插值到帧率级平滑；时间轴方向不变，仅消除台阶感
            val age = (updateCounter - chatLine.getUpdatedCounter()) + mc.timer.renderPartialTicks

            var alpha = 1F
            var yOff = 0F   // 每行的额外纵向偏移（入场 / 退场动画）

            if (!chatOpen && !revealActive && hud.chatAnimation && age >= EXIT_START_TICKS) {
                // 退场：与入场同速的上滑 + 淡出（接管原版 180~200 tick 的淡出段）
                val t = ((age - EXIT_START_TICKS) / EXIT_TICKS).coerceIn(0F, 1F)
                if (t >= 1F) continue
                alpha = 1F - easeInCubic(t)
                yOff -= easeInCubic(t) * lineH
            } else {
                if (age >= 200 && !chatOpen) {
                    if (!revealActive) continue
                    // 收起动画期间：超龄行保持可见，由展开边缘逐行隐藏
                    alpha = 1F
                } else {
                    // 原版的 200 tick 淡出曲线
                    alpha = ((1F - age / 200F) * 10F).coerceIn(0F, 1F)
                    alpha *= alpha
                    if (chatOpen) alpha = 1F
                }
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
            if (age < 200) youngMinY = minOf(youngMinY, y)
            infos.add(ChatLineInfo(text, alpha, y))
            maxTextWidth = max(maxTextWidth, stringWidth(text))
            blockAlpha = max(blockAlpha, alpha)
        }
        if (infos.isEmpty()) return

        // --- 背景块布局（内容自适应，随入场动画/滚动同步移动） ---
        val radius = hud.chatRoundedRadius
        val pad = 3F
        val blockX1 = -pad
        val blockY2 = infos[0].y

        // 展开边缘：在「关闭时可见上缘」与「打开时完整上缘」间插值，随进度推进逐行揭示历史
        val blockY1 = if (revealActive) {
            val openTop = infos[infos.size - 1].y - lineH
            val youngTop = if (youngMinY == Float.MAX_VALUE) blockY2 else youngMinY - lineH
            youngTop + (openTop - youngTop) * easeOutCubic(expandProgress)
        } else {
            infos[infos.size - 1].y - lineH
        }.coerceAtLeast(blockY2 - hud.chatHeightMax)   // 最大高度：向上最多延伸 chatHeightMax px（超出部分被裁剪）

        // 展开边缘处的行淡入并从上缘滑入定位（配合 scissor 裁剪，避免边缘文字被硬切）
        if (revealActive) {
            for (info in infos) {
                val d = info.y - blockY1
                if (d <= 0F) {
                    info.alpha = -1F   // 边缘之上：隐藏（标记后过滤）
                } else if (d < lineH) {
                    val t = d / lineH
                    info.alpha *= t
                    info.y = blockY1 + d * t
                }
            }
            infos.removeAll { it.alpha < 0F }
            if (infos.isEmpty()) return
            maxTextWidth = 0F
            blockAlpha = 0F
            for (info in infos) {
                maxTextWidth = max(maxTextWidth, stringWidth(info.text))
                blockAlpha = max(blockAlpha, info.alpha)
            }
        }

        // 自定义宽度不小于最长消息 + 4px（pad + 1）
        val blockX2 = max(maxTextWidth + pad + 1F, if (hud.chatCustomWidth) hud.chatWidth else 0F)

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
                    HudBlur.blur(absX1, absY1, absX2, absY2, radius) {
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

    /**
     * Rise 风格聊天栏（Chat-Mode = Rise，移植自 Rise Chat#renderChat）：
     * 固定宽度圆角面板，打开/关闭时高度 EASE_OUT_EXPO 缓动；关闭状态按「消失速度」
     * 逐条隐去旧消息（面板高度随 visibleLines 收缩）；新消息推动内容上滑；
     * 打开状态完整显示历史并支持平滑滚动。输入框仍由原版处理，
     * 坐标空间与 air 模式一致（原点 translate + chatScale）。
     */
    private fun renderChatRise(
        drawnChatLines: List<ChatLine>,
        scrollPos: Int,
        lineCount: Int,
        chatOpen: Boolean
    ) {
        val hud = HUD
        val now = System.currentTimeMillis()
        val deltaMs = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(0L, 100L)
        lastFrame = now
        val factor = ((deltaMs / 1000F) * 14F * hud.chatAnimationSpeed.coerceIn(0.1F, 3F)).coerceIn(0F, 1F)

        // --- 字体（与 air 模式一致，复用 HUD 的 FontChat 设置） ---
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

        val totalLines = drawnChatLines.size
        if (totalLines == 0) return

        // --- Rise 可见行数（关闭状态按消失速度逐条隐去） ---
        val closedMaxLines = (hud.chatHeightMax / lineH).toInt().coerceAtLeast(1)
        if (riseLastLineCount == -1) {
            riseLastLineCount = totalLines
            riseVisibleLines = closedMaxLines
        } else if (totalLines > riseLastLineCount) {
            // 新消息到达：内容整体下移等待上滑入场、可见行数扩大（Rise scrollAnimation + visibleLines）
            riseScrollOffset += (totalLines - riseLastLineCount) * lineH
            riseVisibleLines += totalLines - riseLastLineCount
        }
        riseLastLineCount = totalLines

        if (!chatOpen) {
            // Rise messageTimer：每隔「消失速度」毫秒隐去一行最旧消息（面板随之收缩）
            if (riseDisappearTimer == 0L) riseDisappearTimer = now
            while (riseVisibleLines > 0 && now - riseDisappearTimer >= hud.chatRiseDisappearance) {
                riseDisappearTimer += hud.chatRiseDisappearance.toLong()
                riseVisibleLines--
            }
            riseVisibleLines = riseVisibleLines.coerceIn(0, closedMaxLines)
        } else {
            riseDisappearTimer = 0L
        }

        // --- 平滑滚动（打开状态滚历史） ---
        val maxScroll = (totalLines - lineCount).coerceAtLeast(0)
        if (hud.chatSmoothScroll) {
            scrollAnim += (scrollPos - scrollAnim) * factor
            if (abs(scrollAnim - scrollPos) < 0.01F) scrollAnim = scrollPos.toFloat()
        } else {
            scrollAnim = scrollPos.toFloat()
        }
        val anim = scrollAnim.coerceIn(0F, maxScroll.toFloat())
        val base = floor(anim).toInt().coerceAtMost((totalLines - 1).coerceAtLeast(0))
        val frac = anim - base

        // 新消息上滑偏移衰减（Rise scrollAnimation → 0）
        riseScrollOffset -= riseScrollOffset * factor
        if (riseScrollOffset < 0.1F) riseScrollOffset = 0F

        // --- 高度缓动（Rise heightAnimation，EASE_OUT_EXPO） ---
        val contentH = lineH * totalLines + 7F
        val targetH = if (chatOpen) {
            minOf(hud.chatRiseOpenHeight, contentH)
        } else {
            minOf(hud.chatHeightMax, contentH, lineH * riseVisibleLines + 7F)
        }
        if (riseHeight.isNaN()) riseHeight = targetH
        riseHeight += (targetH - riseHeight) * factor
        if (abs(riseHeight - targetH) < 0.05F) riseHeight = targetH

        // --- 面板几何（本地坐标，与 air 模式同一原点；宽度对齐 Rise 的固定宽面板） ---
        val pad = 3F
        val radius = hud.chatRoundedRadius
        val blockX1 = -pad
        val blockY2 = 3F
        val blockY1 = blockY2 - riseHeight

        val visibleCount = (((frac * lineH - blockY1) / lineH).toInt() + 2).coerceIn(0, totalLines - base)
        var maxTextWidth = 0F
        for (i in 0 until visibleCount) {
            maxTextWidth = max(maxTextWidth, stringWidth(drawnChatLines[base + i].getChatComponent().getFormattedText()))
        }
        val blockX2 = max(maxTextWidth + pad + 1F, if (hud.chatCustomWidth) hud.chatWidth else 0F)

        GlStateManager.pushMatrix()
        try {
            val originX = 2F + hud.chatPosX
            val originY = 20F + hud.chatPosY
            GlStateManager.translate(originX, originY, 0F)
            val chatScale = mc.gameSettings.chatScale
            GlStateManager.scale(chatScale, chatScale, 1F)
            val opacityBase = mc.gameSettings.chatOpacity * 0.9F + 0.1F

            if (hud.chatBackground) {
                val bgColor = hud.chatBackgroundColors.color()
                val bgAlpha = ((bgColor.alpha / 255F) * opacityBase).coerceIn(0F, 1F)
                val bgARGB = ((bgAlpha * 255F).toInt() shl 24) or
                        (bgColor.red shl 16) or (bgColor.green shl 8) or bgColor.blue

                if (hud.chatBackgroundBlur && bgAlpha > 0.02F) {
                    val sr = ScaledResolution(mc)
                    val sf = sr.scaleFactor
                    val absOriginY = sr.scaledHeight - 28F + hud.chatPosY
                    HudBlur.blur(
                        originX + blockX1 * chatScale,
                        absOriginY + blockY1 * chatScale,
                        originX + blockX2 * chatScale,
                        absOriginY + blockY2 * chatScale,
                        radius
                    ) {
                        drawRoundedRect(blockX1, blockY1, blockX2, blockY2, -1, radius)
                    }
                }

                drawRoundedRect(blockX1, blockY1, blockX2, blockY2, bgARGB, radius)
            }

            // 文本（裁剪到面板内；打开状态全亮，关闭状态随原版聊天透明度设置）
            val clipText = hud.chatBackground
            if (clipText) {
                val sr = ScaledResolution(mc)
                val sf = sr.scaleFactor
                val absOriginY = sr.scaledHeight - 28F + hud.chatPosY
                GL11.glScissor(
                    ((originX + blockX1 * chatScale) * sf).toInt(),
                    (mc.displayHeight - (absOriginY + blockY2 * chatScale) * sf).toInt(),
                    (((blockX2 - blockX1) * chatScale) * sf).toInt().coerceAtLeast(0),
                    (((blockY2 - blockY1) * chatScale) * sf).toInt().coerceAtLeast(0)
                )
                GL11.glEnable(GL11.GL_SCISSOR_TEST)
            }
            GlStateManager.enableBlend()
            val alphaInt = if (chatOpen) 255 else (opacityBase * 255F).toInt().coerceIn(0, 255)
            for (i in 0 until visibleCount) {
                val idx = base + i
                if (idx >= totalLines) break
                val y = -i * lineH + frac * lineH + riseScrollOffset
                if (y - lineH > blockY2 || y < blockY1) continue
                drawText(
                    drawnChatLines[idx].getChatComponent().getFormattedText(),
                    0F, y - lineH + (lineH - fontHeight) / 2F + 1F,
                    0xFFFFFF or (alphaInt shl 24)
                )
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

    private fun easeOutCubic(t: Float): Float = 1F - (1F - t).pow(3F)

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
