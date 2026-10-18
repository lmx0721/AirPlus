// skid SetsunaClient
/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.ui.client.mainmenu

import net.airplus.AirPlus.CLIENT_NAME
import net.airplus.AirPlus.clientVersionText
import net.airplus.ui.client.GuiModsMenu
import net.airplus.ui.client.altmanager.GuiAltManager
import net.airplus.ui.font.Fonts
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.ui.AbstractScreen
import net.minecraft.client.gui.GuiMultiplayer
import net.minecraft.client.gui.GuiOptions
import net.minecraft.client.gui.GuiSelectWorld
import net.minecraft.client.gui.ScaledResolution
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Setsuna 风格主菜单（迁移自 SetsunaClient MainMenuScreen，Skija 绘制以 RenderUtils/GL 复刻）。
 *
 * 风格：暗色科技感网格背景，屏幕被一条 8° 斜线分成左右两个场域；
 * 左右区域分别是对齐的 SINGLE PLAYER / MULTI PLAYER 入口（悬停展开高亮面板与下划线）；
 * 中央小圆点悬停展开为工具环：ALT / QUIT / SETTINGS 三分格 + 中心切换键。
 * 配色：青绿强调色 #3ED6B4（左域与 SETTINGS），橙 #F1A45D（右域），近黑底色。
 */
class SetsunaMainMenu : AbstractScreen() {

    companion object {
        // ===== Setsuna 布局/动画常量（原样移植） =====
        private const val DIVIDER_ANGLE = 8f
        private const val IDLE_RADIUS = 11f
        private const val OPEN_RADIUS = 47f
        private const val SWITCH_RADIUS = 6.8f
        private const val BACKGROUND_DURATION = 0.70f
        private const val DOT_GROW_START = 0.58f
        private const val DOT_GROW_DURATION = 0.28f
        private const val DOT_SETTLE_START = 0.86f
        private const val DOT_SETTLE_DURATION = 0.28f
        private const val LINE_START = 1.08f
        private const val LINE_DURATION = 0.55f
        private const val CONTENT_START = 1.34f
        private const val CONTENT_DURATION = 0.40f
        private const val ENTRANCE_COMPLETE = CONTENT_START + CONTENT_DURATION

        private const val UTILITY_SETTINGS = 0
        private const val UTILITY_QUIT = 1
        private const val UTILITY_ALT = 2

        // ===== 配色（Setsuna UiTheme 默认值） =====
        private val ACCENT = Color(62, 214, 180).rgb
        private val ORANGE = Color(0xF1, 0xA4, 0x5D).rgb
        private val WHITE = 0xFFFFFFFF.toInt()

        // 入场蒙层底色（Setsuna 暗色底 #030407）
        private val BACKDROP_OVERLAY = Color(3, 4, 7, 215).rgb
    }

    private data class Layout(
        val centerX: Float,
        val centerY: Float,
        val extent: Float,
        val leftLabelX: Float,
        val rightLabelX: Float
    )

    private data class Entrance(
        val background: Float,
        val dotAlpha: Float,
        val dotScale: Float,
        val lines: Float,
        val content: Float,
        val interactive: Boolean,
        val complete: Boolean
    )

    // 悬停/按压动画状态
    private var singleHover = 0f
    private var multiHover = 0f
    private var centerOpen = 0f
    private var quitHover = 0f
    private var settingsHover = 0f
    private var altHover = 0f
    private var switchHover = 0f
    private var singlePress = 0f
    private var multiPress = 0f
    private var quitPress = 0f
    private var settingsPress = 0f
    private var altPress = 0f
    private var switchPress = 0f

    // 入场与延迟动作
    private var openedAt = System.nanoTime()
    private var lastFrame = openedAt
    private var entrancePlayed = false
    private var pendingAction: (() -> Unit)? = null
    private var pendingActionAt = 0L

    override fun initGui() {
        val now = System.nanoTime()
        openedAt = if (entrancePlayed) {
            now - (ENTRANCE_COMPLETE * 1_000_000_000L).toLong()
        } else {
            now
        }
        lastFrame = now
        pendingAction = null
        resetMotion()
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        val sr = ScaledResolution(mc)
        width = sr.scaledWidth
        height = sr.scaledHeight

        val now = System.nanoTime()
        val delta = frameDelta(now)
        val entrance = entrance(now)
        if (entrance.complete) entrancePlayed = true
        val layout = layout()
        updateMotion(layout, delta, entrance.interactive, mouseX.toFloat(), mouseY.toFloat())

        // 背景：AirPlus 共享背景 + Setsuna 深色蒙层，之上叠加网格/光束/扫描场景
        MainMenuStyles.drawMenuBackground(width, height, BACKDROP_OVERLAY)
        drawBackdropScene(width.toFloat(), height.toFloat(), now / 1_000_000_000.0f,
            mouseX.toFloat(), mouseY.toFloat())
        if (entrance.background < 1f) {
            // 入场时从黑色淡入（复刻 Setsuna 背景层 alpha 淡入）
            RenderUtils.drawRect(
                0f, 0f, width.toFloat(), height.toFloat(),
                argb((255f * (1f - entrance.background)).toInt(), 0, 0, 0)
            )
        }

        drawFields(layout, entrance.content)
        drawDivider(layout, entrance.lines)
        drawDestinations(layout, entrance.content, now / 1_000_000_000.0f)
        drawCenterControl(layout, entrance.dotAlpha, entrance.dotScale, entrance.content)
        drawBrand(entrance.content)

        val action = pendingAction
        if (action != null && now >= pendingActionAt) {
            pendingAction = null
            action()
        }

        // 右下角切换按钮（AirPlus 各风格通用）
        MainMenuStyles.drawSettingsButton(width, height, mouseX, mouseY)

        super.drawScreen(mouseX, mouseY, partialTicks)
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        if (MainMenuStyles.handleSettingsClick(width, height, mouseX, mouseY, mouseButton)) {
            return
        }
        if (mouseButton != 0 || pendingAction != null) {
            super.mouseClicked(mouseX, mouseY, mouseButton)
            return
        }
        if (!entranceReady(System.nanoTime())) return

        val layout = layout()
        val dx = mouseX - layout.centerX
        val dy = mouseY - layout.centerY
        val distance = sqrt(dx * dx + dy * dy)

        if (centerOpen > 0.35f && distance <= OPEN_RADIUS + 10f) {
            if (distance <= SWITCH_RADIUS + 5f) {
                switchPress = 1f
                queue { mc.displayGuiScreen(GuiModsMenu(this)) }
            } else {
                when (utilityAt(dx, dy)) {
                    UTILITY_ALT -> {
                        altPress = 1f
                        queue { mc.displayGuiScreen(GuiAltManager(this)) }
                    }

                    UTILITY_QUIT -> {
                        quitPress = 1f
                        queue { mc.shutdown() }
                    }

                    else -> {
                        settingsPress = 1f
                        queue { mc.displayGuiScreen(GuiOptions(this, mc.gameSettings)) }
                    }
                }
            }
            return
        }

        if (sideAt(layout, mouseX.toFloat(), mouseY.toFloat()) < 0) {
            singlePress = 1f
            queue { mc.displayGuiScreen(GuiSelectWorld(this)) }
        } else {
            multiPress = 1f
            queue { mc.displayGuiScreen(GuiMultiplayer(this)) }
        }
    }

    override fun keyTyped(typedChar: Char, keyCode: Int) {
        // Setsuna 主菜单不允许 Esc 关闭
        if (keyCode == Keyboard.KEY_ESCAPE) return
        super.keyTyped(typedChar, keyCode)
    }

    // ===== 绘制 =====

    /** 左右两个旋转场域的淡色底（悬停时各自加深）。 */
    private fun drawFields(layout: Layout, intro: Float) {
        val leftColor = withAlpha(ACCENT, ((5f + singleHover * 18f) * intro).toInt())
        val rightColor = withAlpha(ORANGE, ((4f + multiHover * 16f) * intro).toInt())

        GL11.glPushMatrix()
        GL11.glTranslatef(layout.centerX, layout.centerY, 0f)
        GL11.glRotatef(DIVIDER_ANGLE, 0f, 0f, 1f)
        val e = layout.extent
        RenderUtils.drawRect(-e, -e, 0f, e, leftColor)
        RenderUtils.drawRect(0f, -e, e, e, rightColor)
        GL11.glPopMatrix()
    }

    /** 中央分隔线（从中心间隙向两端展开）。 */
    private fun drawDivider(layout: Layout, reveal: Float) {
        if (reveal <= 0f) return
        GL11.glPushMatrix()
        GL11.glTranslatef(layout.centerX, layout.centerY, 0f)
        GL11.glRotatef(DIVIDER_ANGLE, 0f, 0f, 1f)

        val centerGap = lerp(IDLE_RADIUS * 0.75f, OPEN_RADIUS - 1.5f, smooth(centerOpen))
        val revealLength = lerp(centerGap, layout.extent, reveal)

        line(0f, -centerGap, 0f, -revealLength, 4f, withAlpha(WHITE, (18f * reveal).toInt()))
        line(0f, centerGap, 0f, revealLength, 4f, withAlpha(WHITE, (18f * reveal).toInt()))
        line(0f, -centerGap, 0f, -revealLength, 1.25f, withAlpha(WHITE, (218f * reveal).toInt()))
        line(0f, centerGap, 0f, revealLength, 1.25f, withAlpha(WHITE, (218f * reveal).toInt()))
        GL11.glPopMatrix()
    }

    private fun drawDestinations(layout: Layout, intro: Float, time: Float) {
        val flash = 0.5f + 0.5f * sin(time * 6f)
        drawDestination(
            "SINGLE PLAYER", layout.leftLabelX, layout.centerY,
            -1f, singleHover, singlePress, ACCENT, intro, flash
        )
        drawDestination(
            "MULTI PLAYER", layout.rightLabelX, layout.centerY,
            1f, multiHover, multiPress, ORANGE, intro, flash
        )
    }

    private fun drawDestination(
        label: String, anchorX: Float, centerY: Float, direction: Float,
        hover: Float, press: Float, accent: Int, intro: Float, flash: Float
    ) {
        val labelFont = Fonts.fontSemibold35
        val labelWidth = labelFont.getStringWidth(label).toFloat()
        val labelX = anchorX + direction * (hover * 5f - press * 2f)
        val textTop = centerY - 5f - labelFont.height / 2f + press * 1.5f
        val textColor = alphaF(mix(0xFFC6CECC.toInt(), WHITE, hover), intro * (0.72f + hover * 0.28f))

        // 悬停高亮面板（呼吸闪烁）
        val panelProgress = smooth(hover) * intro
        val panelWidth = (labelWidth + 46f) * panelProgress
        val panelHeight = 38f
        if (panelWidth > 0.5f) {
            val panelX = anchorX - panelWidth * 0.5f
            val panelY = centerY - panelHeight * 0.5f
            val panelAlpha = ((112f + flash * 34f) * panelProgress).toInt()
            RenderUtils.drawRoundedRect(
                panelX, panelY, panelX + panelWidth, panelY + panelHeight,
                argb(panelAlpha, 0, 0, 0), 2f
            )
            val edgeAlpha = panelProgress * (0.28f + flash * 0.42f)
            RenderUtils.drawRect(panelX, panelY, panelX + panelWidth, panelY + 1f, alphaF(accent, edgeAlpha))
            RenderUtils.drawRect(
                panelX, panelY + panelHeight - 1f, panelX + panelWidth, panelY + panelHeight,
                alphaF(accent, edgeAlpha)
            )
        }

        labelFont.drawCenteredString(label, labelX, textTop, textColor, false)

        // 下方强调下划线（悬停时拉长变亮）
        val lineWidth = 34f + hover * 36f
        val lineX = anchorX - lineWidth * 0.5f
        val lineY = centerY + 12f + press * 1.5f
        RenderUtils.drawRoundedRect(
            lineX, lineY, lineX + lineWidth, lineY + 1.5f,
            alphaF(accent, intro * (0.32f + hover * 0.68f)), 0.75f
        )
    }

    /** 中央圆点/工具环。 */
    private fun drawCenterControl(layout: Layout, dotAlpha: Float, dotScale: Float, content: Float) {
        val progress = smooth(centerOpen) * content
        val radius = lerp(IDLE_RADIUS, OPEN_RADIUS, progress)
        val centerX = layout.centerX
        val centerY = layout.centerY

        val fillRadius = lerp(IDLE_RADIUS * dotScale, OPEN_RADIUS * 0.72f, progress)
        val fillAlpha = (255f * dotAlpha * (1f - progress)).toInt()
        if (fillAlpha > 0) {
            fillCircle(centerX, centerY, fillRadius, argb(fillAlpha, 255, 255, 255))
        }

        if (progress > 0.01f) {
            ringStroke(centerX, centerY, radius, lerp(1f, 2.2f, progress), withAlpha(WHITE, (235f * progress).toInt()))

            val utilityReveal = clampF((progress - 0.38f) / 0.62f, 0f, 1f)
            drawUtilityDividers(centerX, centerY, radius, utilityReveal)
            drawUtility(
                "ALT", centerX, centerY - radius * 0.52f,
                altHover, altPress, Color(0xD3, 0xB3, 0xFF).rgb, utilityReveal
            )
            drawUtility(
                "QUIT", centerX - radius * 0.45f, centerY + radius * 0.28f,
                quitHover, quitPress, Color(0xE2, 0x69, 0x64).rgb, utilityReveal
            )
            drawUtility(
                "SETTINGS", centerX + radius * 0.45f, centerY + radius * 0.28f,
                settingsHover, settingsPress, ACCENT, utilityReveal
            )
            drawSwitchControl(centerX, centerY, utilityReveal)
        }
    }

    /** 工具环三分隔线（-30° / 90° / 210°）。 */
    private fun drawUtilityDividers(centerX: Float, centerY: Float, radius: Float, reveal: Float) {
        val color = alphaF(0xB8FFFFFF.toInt(), reveal)
        for (angle in floatArrayOf(-30f, 90f, 210f)) {
            val radians = Math.toRadians(angle.toDouble())
            val cosV = cos(radians).toFloat()
            val sinV = sin(radians).toFloat()
            val inner = SWITCH_RADIUS + 2f
            line(
                centerX + cosV * inner, centerY + sinV * inner,
                centerX + cosV * (radius - 1.5f), centerY + sinV * (radius - 1.5f),
                0.8f, color
            )
        }
    }

    private fun drawUtility(
        label: String, centerX: Float, centerY: Float,
        hover: Float, press: Float, accent: Int, reveal: Float
    ) {
        val font = Fonts.fontUsans15
        val color = alphaF(mix(0xFFCED5D3.toInt(), WHITE, hover), reveal)
        font.drawCenteredString(label, centerX, centerY + press - font.height / 2f, color, false)

        val indicator = 7f + hover * 10f
        RenderUtils.drawRoundedRect(
            centerX - indicator * 0.5f, centerY + 9f,
            centerX + indicator * 0.5f, centerY + 10.2f,
            alphaF(accent, reveal * (0.35f + hover * 0.65f)), 0.6f
        )
    }

    /** 环心切换键（双向箭头以三角形绘制，替代 Setsuna 的 ⇄ 字形）。 */
    private fun drawSwitchControl(centerX: Float, centerY: Float, reveal: Float) {
        val radius = SWITCH_RADIUS + switchHover * 1.5f - switchPress * 0.7f
        val fill = mix(0xFFF4F6F5.toInt(), ACCENT, switchHover * 0.32f)
        fillCircle(centerX, centerY, radius, alphaF(fill, reveal))

        val glyph = alphaF(0xFF111516.toInt(), reveal)
        // 上：向右箭头
        RenderUtils.drawRect(centerX - 3.4f, centerY - 2f, centerX + 1.6f, centerY - 1.4f, glyph)
        fillTriangle(
            centerX + 1.6f, centerY - 3f,
            centerX + 3.4f, centerY - 1.7f,
            centerX + 1.6f, centerY - 0.4f, glyph
        )
        // 下：向左箭头
        RenderUtils.drawRect(centerX - 1.6f, centerY + 1.4f, centerX + 3.4f, centerY + 2f, glyph)
        fillTriangle(
            centerX - 1.6f, centerY + 0.4f,
            centerX - 3.4f, centerY + 1.7f,
            centerX - 1.6f, centerY + 3f, glyph
        )
    }

    private fun drawBrand(intro: Float) {
        val nameFont = Fonts.fontUsans20
        val versionFont = Fonts.fontUsans15
        val x = 22f
        nameFont.drawString(CLIENT_NAME.uppercase(), x, 24f - nameFont.height / 2f, alphaF(WHITE, intro))
        versionFont.drawString(clientVersionText, x, 35f - versionFont.height / 2f, alphaF(0xFF9EA9A6.toInt(), intro))
    }

    // ===== 背景场景（Setsuna ScreenBackdrop 默认网格背景复刻） =====

    private fun drawBackdropScene(w: Float, h: Float, time: Float, pointerX: Float, pointerY: Float) {
        val parallaxX = clampF((pointerX / w - 0.5f) * 10f, -5f, 5f)
        val parallaxY = clampF((pointerY / h - 0.5f) * 8f, -4f, 4f)
        drawPerspectivePlane(w, h, time, parallaxX, parallaxY)
        drawGrid(w, h, time, parallaxX, parallaxY)
        drawScan(w, h, time)
        drawEdgeShade(w, h)
    }

    private fun drawPerspectivePlane(w: Float, h: Float, time: Float, parallaxX: Float, parallaxY: Float) {
        val pulse = 0.5f + 0.5f * sin(time * 0.55f)
        val peakAlpha = 12 + (pulse * 9f).toInt()

        GL11.glPushMatrix()
        GL11.glTranslatef(w * 0.73f + parallaxX, h * 0.46f + parallaxY, 0f)
        GL11.glRotatef(-13f, 0f, 0f, 1f)
        val planeWidth = maxOf(120f, w * 0.22f)
        val planeHeight = h * 2.1f
        val x0 = -planeWidth * 0.5f
        val x1 = -planeWidth * 0.22f
        val x2 = planeWidth * 0.22f
        val x3 = planeWidth * 0.5f
        val y0 = -planeHeight * 0.5f
        val y1 = planeHeight * 0.5f
        val transparent = withAlpha(ACCENT, 0)
        val peak = withAlpha(ACCENT, peakAlpha)
        val orangeTail = withAlpha(ORANGE, 8)
        gradQuad(x0, y0, x1, y1, transparent, peak, transparent, peak)
        gradQuad(x1, y0, x2, y1, peak, orangeTail, peak, orangeTail)
        gradQuad(x2, y0, x3, y1, orangeTail, transparent, orangeTail, transparent)
        GL11.glPopMatrix()

        // 斜向轨迹线
        val startX = w * 0.82f + parallaxX * 0.7f
        line(startX, h * 0.12f, startX - h * 0.22f, h * 0.88f, 1f, withAlpha(ORANGE, 38))
    }

    private fun drawGrid(w: Float, h: Float, time: Float, parallaxX: Float, parallaxY: Float) {
        val spacing = maxOf(34f, minOf(58f, w / 12f))
        val offsetX = positiveModulo(time * 3.5f + parallaxX, spacing)
        val offsetY = positiveModulo(time * 2f + parallaxY, spacing)
        val gridColor = 0x0EFFFFFF.toInt()

        var x = -spacing + offsetX
        while (x < w + spacing) {
            line(x, 0f, x, h, 1f, gridColor)
            x += spacing
        }
        var y = -spacing + offsetY
        while (y < h + spacing) {
            line(0f, y, w, y, 1f, gridColor)
            y += spacing
        }

        line(w * 0.08f, 0f, w * 0.08f, h, 1f, 0x18FFFFFF.toInt())
        line(w * 0.92f, 0f, w * 0.92f, h, 1f, 0x18FFFFFF.toInt())
    }

    private fun drawScan(w: Float, h: Float, time: Float) {
        val travel = h + 120f
        val y = positiveModulo(time * 23f, travel) - 60f
        val soft = withAlpha(ACCENT, 11)
        gradQuad(0f, y - 38f, w, y, withAlpha(ACCENT, 0), withAlpha(ACCENT, 0), soft, soft)
        gradQuad(0f, y, w, y + 38f, soft, soft, withAlpha(ACCENT, 0), withAlpha(ACCENT, 0))
        line(0f, y, w, y, 1f, withAlpha(ACCENT, 24))
    }

    private fun drawEdgeShade(w: Float, h: Float) {
        val edge = minOf(150f, w * 0.24f)
        gradQuad(
            0f, 0f, edge, h,
            0x8C020306.toInt(), 0x00020306, 0x8C020306.toInt(), 0x00020306
        )
        gradQuad(
            w - edge, 0f, w, h,
            0x00020306, 0x76020306.toInt(), 0x00020306, 0x76020306.toInt()
        )
        val vertical = minOf(100f, h * 0.25f)
        gradQuad(
            0f, h - vertical, w, h,
            0x00020306, 0x00020306, 0x76020306.toInt(), 0x76020306.toInt()
        )
    }

    // ===== GL 绘制辅助 =====

    private fun glColor(color: Int) {
        GL11.glColor4f(
            (color shr 16 and 0xFF) / 255f,
            (color shr 8 and 0xFF) / 255f,
            (color and 0xFF) / 255f,
            (color ushr 24 and 0xFF) / 255f
        )
    }

    private fun line(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, color: Int) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT)
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glEnable(GL11.GL_LINE_SMOOTH)
        GL11.glDisable(GL11.GL_TEXTURE_2D)
        glColor(color)
        RenderUtils.drawLine(x1.toDouble(), y1.toDouble(), x2.toDouble(), y2.toDouble(), thickness)
        GL11.glColor4f(1f, 1f, 1f, 1f)
        GL11.glPopAttrib()
    }

    private fun fillCircle(centerX: Float, centerY: Float, radius: Float, color: Int) {
        if (radius <= 0f) return
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT)
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glDisable(GL11.GL_TEXTURE_2D)
        glColor(color)
        val sections = 60
        GL11.glBegin(GL11.GL_TRIANGLE_FAN)
        GL11.glVertex2f(centerX, centerY)
        for (i in 0..sections) {
            val angle = Math.PI * 2 * i / sections
            GL11.glVertex2f(centerX + cos(angle).toFloat() * radius, centerY + sin(angle).toFloat() * radius)
        }
        GL11.glEnd()
        GL11.glColor4f(1f, 1f, 1f, 1f)
        GL11.glPopAttrib()
    }

    private fun ringStroke(centerX: Float, centerY: Float, radius: Float, thickness: Float, color: Int) {
        if (radius <= 0f || thickness <= 0f) return
        val inner = radius - thickness * 0.5f
        val outer = radius + thickness * 0.5f
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT)
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glDisable(GL11.GL_TEXTURE_2D)
        glColor(color)
        val sections = 72
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP)
        for (i in 0..sections) {
            val angle = Math.PI * 2 * i / sections
            val cosV = cos(angle).toFloat()
            val sinV = sin(angle).toFloat()
            GL11.glVertex2f(centerX + cosV * outer, centerY + sinV * outer)
            GL11.glVertex2f(centerX + cosV * inner, centerY + sinV * inner)
        }
        GL11.glEnd()
        GL11.glColor4f(1f, 1f, 1f, 1f)
        GL11.glPopAttrib()
    }

    private fun fillTriangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, color: Int) {
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT)
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glDisable(GL11.GL_TEXTURE_2D)
        glColor(color)
        GL11.glBegin(GL11.GL_TRIANGLES)
        GL11.glVertex2f(x1, y1)
        GL11.glVertex2f(x2, y2)
        GL11.glVertex2f(x3, y3)
        GL11.glEnd()
        GL11.glColor4f(1f, 1f, 1f, 1f)
        GL11.glPopAttrib()
    }

    /** 四角独立颜色的渐变矩形（复刻 Skija 双色/三停渐变）。 */
    private fun gradQuad(
        x1: Float, y1: Float, x2: Float, y2: Float,
        topLeft: Int, topRight: Int, bottomLeft: Int, bottomRight: Int
    ) {
        if (x2 <= x1 || y2 <= y1) return
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT)
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glDisable(GL11.GL_TEXTURE_2D)
        GL11.glShadeModel(GL11.GL_SMOOTH)
        GL11.glBegin(GL11.GL_QUADS)
        glColor(topLeft); GL11.glVertex2f(x1, y1)
        glColor(topRight); GL11.glVertex2f(x2, y1)
        glColor(bottomRight); GL11.glVertex2f(x2, y2)
        glColor(bottomLeft); GL11.glVertex2f(x1, y2)
        GL11.glEnd()
        GL11.glShadeModel(GL11.GL_FLAT)
        GL11.glColor4f(1f, 1f, 1f, 1f)
        GL11.glPopAttrib()
    }

    // ===== 交互/动画 =====

    private fun updateMotion(layout: Layout, delta: Float, interactive: Boolean, mouseX: Float, mouseY: Float) {
        val dx = mouseX - layout.centerX
        val dy = mouseY - layout.centerY
        val distance = sqrt(dx * dx + dy * dy)
        val centerRange = if (centerOpen > 0.08f) OPEN_RADIUS + 14f else IDLE_RADIUS + 10f
        val centerTarget = interactive && distance <= centerRange
        centerOpen = approach(centerOpen, if (centerTarget) 1f else 0f, delta, 10f)

        val utilityActive = interactive && centerOpen > 0.18f && distance <= OPEN_RADIUS + 14f
        val switchActive = utilityActive && distance <= SWITCH_RADIUS + 5f
        val utility = utilityAt(dx, dy)
        switchHover = approach(switchHover, if (switchActive) 1f else 0f, delta, 14f)
        quitHover = approach(
            quitHover,
            if (utilityActive && !switchActive && utility == UTILITY_QUIT) 1f else 0f, delta, 14f
        )
        settingsHover = approach(
            settingsHover,
            if (utilityActive && !switchActive && utility == UTILITY_SETTINGS) 1f else 0f, delta, 14f
        )
        altHover = approach(
            altHover,
            if (utilityActive && !switchActive && utility == UTILITY_ALT) 1f else 0f, delta, 14f
        )

        val side = sideAt(layout, mouseX, mouseY)
        singleHover = approach(
            singleHover,
            if (interactive && !utilityActive && side < 0) 1f else 0f, delta, 8f
        )
        multiHover = approach(
            multiHover,
            if (interactive && !utilityActive && side >= 0) 1f else 0f, delta, 8f
        )

        singlePress = approach(singlePress, 0f, delta, 20f)
        multiPress = approach(multiPress, 0f, delta, 20f)
        quitPress = approach(quitPress, 0f, delta, 20f)
        settingsPress = approach(settingsPress, 0f, delta, 20f)
        altPress = approach(altPress, 0f, delta, 20f)
        switchPress = approach(switchPress, 0f, delta, 20f)
    }

    /** 以中心为原点的方位角三分格：QUIT（左下）/ ALT（上）/ SETTINGS（右下）。 */
    private fun utilityAt(dx: Float, dy: Float): Int {
        var angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < 0f) angle += 360f
        return when {
            angle >= 90f && angle < 210f -> UTILITY_QUIT
            angle >= 210f && angle < 330f -> UTILITY_ALT
            else -> UTILITY_SETTINGS
        }
    }

    /** 判定指针位于 8° 斜线的哪一侧：<0 左域（单人），>=0 右域（多人）。 */
    private fun sideAt(layout: Layout, x: Float, y: Float): Int {
        val dx = x - layout.centerX
        val dy = y - layout.centerY
        val radians = Math.toRadians(DIVIDER_ANGLE.toDouble())
        val localX = cos(radians).toFloat() * dx + sin(radians).toFloat() * dy
        return if (localX < 0f) -1 else 1
    }

    private fun queue(action: () -> Unit) {
        pendingAction = action
        pendingActionAt = System.nanoTime() + 105_000_000L
    }

    private fun resetMotion() {
        singleHover = 0f; multiHover = 0f; centerOpen = 0f
        quitHover = 0f; settingsHover = 0f; altHover = 0f; switchHover = 0f
        singlePress = 0f; multiPress = 0f; quitPress = 0f
        settingsPress = 0f; altPress = 0f; switchPress = 0f
    }

    // ===== 入场动画 =====

    private fun layout(): Layout {
        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val extent = sqrt((width * width + height * height).toFloat()) + 8f
        val inset = if (width < 520) width * 0.24f else minOf(210f, width * 0.25f)
        return Layout(centerX, centerY, extent, centerX - inset, width - inset)
    }

    private fun frameDelta(now: Long): Float {
        val delta = ((now - lastFrame) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.05f)
        lastFrame = now
        return delta
    }

    private fun entrance(now: Long): Entrance {
        val elapsed = (now - openedAt) / 1_000_000_000.0f
        val background = easeOut(stage(elapsed, 0f, BACKGROUND_DURATION))
        val dotGrow = easeOut(stage(elapsed, DOT_GROW_START, DOT_GROW_DURATION))
        val dotSettle = easeOut(stage(elapsed, DOT_SETTLE_START, DOT_SETTLE_DURATION))
        val dotScale = if (dotGrow < 1f) lerp(0.18f, 1.55f, dotGrow) else lerp(1.55f, 1f, dotSettle)
        val lines = easeOut(stage(elapsed, LINE_START, LINE_DURATION))
        val content = easeOut(stage(elapsed, CONTENT_START, CONTENT_DURATION))
        return Entrance(
            background, dotGrow, dotScale, lines, content,
            elapsed >= LINE_START + LINE_DURATION * 0.92f,
            elapsed >= ENTRANCE_COMPLETE
        )
    }

    private fun entranceReady(now: Long): Boolean =
        entrancePlayed || (now - openedAt) / 1_000_000_000.0f >= LINE_START + LINE_DURATION * 0.92f

    // ===== 数学/颜色工具（Setsuna 同款实现） =====

    private fun stage(elapsed: Float, start: Float, duration: Float): Float =
        clampF((elapsed - start) / duration, 0f, 1f)

    private fun easeOut(value: Float): Float {
        val remaining = 1f - clampF(value, 0f, 1f)
        return 1f - remaining * remaining * remaining
    }

    private fun approach(current: Float, target: Float, delta: Float, speed: Float): Float =
        current + (target - current) * (1f - kotlin.math.exp(-speed * delta))

    private fun smooth(value: Float): Float {
        val clamped = clampF(value, 0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }

    private fun lerp(start: Float, end: Float, amount: Float): Float = start + (end - start) * amount

    private fun mix(first: Int, second: Int, amount: Float): Int {
        val value = clampF(amount, 0f, 1f)
        val alpha = ((first ushr 24 and 0xFF) +
                (((second ushr 24 and 0xFF) - (first ushr 24 and 0xFF)) * value)).toInt()
        val red = ((first shr 16 and 0xFF) +
                (((second shr 16 and 0xFF) - (first shr 16 and 0xFF)) * value)).toInt()
        val green = ((first shr 8 and 0xFF) +
                (((second shr 8 and 0xFF) - (first shr 8 and 0xFF)) * value)).toInt()
        val blue = ((first and 0xFF) +
                (((second and 0xFF) - (first and 0xFF)) * value)).toInt()
        return argb(alpha, red, green, blue)
    }

    private fun alphaF(color: Int, opacity: Float): Int =
        withAlpha(color, ((color ushr 24) * clampF(opacity, 0f, 1f)).toInt())

    private fun withAlpha(color: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or
                (red.coerceIn(0, 255) shl 16) or
                (green.coerceIn(0, 255) shl 8) or
                blue.coerceIn(0, 255)

    private fun clampF(value: Float, min: Float, max: Float): Float =
        maxOf(min, minOf(max, value))

    private fun positiveModulo(value: Float, modulus: Float): Float {
        val result = value % modulus
        return if (result < 0f) result + modulus else result
    }
}
