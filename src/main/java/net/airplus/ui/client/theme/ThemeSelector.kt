/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.ui.client.theme

import net.airplus.features.module.modules.render.ThemeManager
import net.airplus.ui.font.Fonts
import net.airplus.utils.client.ClientThemesUtils
import net.airplus.utils.client.ClientThemesUtils.ClientTheme
import net.airplus.utils.render.HudBlur
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.render.RenderUtils.drawFilledCircle
import net.airplus.utils.render.RenderUtils.drawRect
import net.airplus.utils.render.RenderUtils.makeScissorBox
import net.airplus.utils.render.RoundedUtil
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.renderer.GlStateManager
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.GL11
import java.awt.Color
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Theme selector gallery - migrated from AirClient and redesigned.
 *
 * The game behind stays sharp; only a light dim keeps focus on the window,
 * whose own body is frosted and transparent. Every theme card previews its own
 * live animated gradient, cards float into view while scrolling, the window
 * drifts gently toward the cursor and the selected theme breathes with an
 * accent halo.
 */
class ThemeSelector : GuiScreen() {

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private val headerH = 46f
    private val footerH = 30f
    private val pad = 18f
    private val columns = 4
    private val gap = 10f
    private val cardH = 64f

    private val windowW get() = min(660, width - 40).toFloat()
    private val windowH get() = min(430, height - 40).toFloat()
    private val winX get() = (width - windowW) / 2f + parallaxX
    private val winY get() = (height - windowH) / 2f + parallaxY
    private val contentX get() = winX + pad
    private val contentY get() = winY + headerH
    private val contentViewH get() = windowH - headerH - footerH
    private val cardW get() = (windowW - pad * 2 - gap * (columns - 1)) / columns

    // ------------------------------------------------------------------
    // Palette - warm charcoal, no neon
    // ------------------------------------------------------------------

    private val overlayColor = Color(8, 8, 12, 140)
    private val windowBg = Color(26, 25, 29, 150)
    private val cardFrame = Color(35, 34, 39, 250)
    private val cardFrameHover = Color(46, 44, 51, 250)
    private val textPrimary = Color(234, 231, 226)
    private val textMuted = Color(255, 255, 255, 108)
    private val lineMuted = Color(255, 255, 255, 26)

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private var search = ""
    private var searchFocused = false
    private var searchFocusAnim = 0f
    private var scroll = 0f
    private var targetScroll = 0f
    private var openProgress = 0f
    private var closing = false
    private var closeProgress = 0f
    private var scrollBarAlpha = 0f

    // Cursor parallax drift
    private var parallaxX = 0f
    private var parallaxY = 0f

    // Window rise offset of the current frame (open/close animation)
    private var riseOffset = 0f

    private var closeHoverAnim = 0f
    private val hoverProgress = HashMap<Int, Float>()

    private val filteredThemes: List<ClientTheme>
        get() {
            val q = search.trim().lowercase()
            if (q.isEmpty()) return ClientThemesUtils.themes
            return ClientThemesUtils.themes.filter {
                it.displayName.lowercase().contains(q) || it.key.contains(q)
            }
        }

    private val closeX get() = winX + windowW - pad - 18f
    private val closeY get() = winY + 14f + riseOffset
    private val searchBoxW = 150f
    private val searchBoxH = 18f
    private val searchBoxX get() = closeX - 12f - searchBoxW
    private val searchBoxY get() = winY + 15f + riseOffset

    private fun maxScroll(): Float {
        val rows = ceil(filteredThemes.size / columns.toFloat())
        val contentHeight = rows * (cardH + gap) - gap
        return max(0f, contentHeight - contentViewH)
    }

    private fun requestClose() {
        closing = true
    }

    // ------------------------------------------------------------------
    // Screen lifecycle
    // ------------------------------------------------------------------

    override fun initGui() {
        Keyboard.enableRepeatEvents(true)
    }

    override fun onGuiClosed() {
        Keyboard.enableRepeatEvents(false)
    }

    override fun doesGuiPauseGame() = false

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    private fun cardAt(mouseX: Int, mouseY: Int): Int {
        if (mouseX < contentX || mouseX > contentX + cardW * columns + gap * (columns - 1)) return -1
        if (mouseY < contentY || mouseY > contentY + contentViewH) return -1

        val relX = mouseX - contentX
        val relY = mouseY - contentY + scroll
        val row = (relY / (cardH + gap)).toInt()
        val column = (relX / (cardW + gap)).toInt()
        if (column < 0 || column >= columns) return -1

        val index = row * columns + column
        if (index < 0 || index >= filteredThemes.size) return -1

        // Ignore clicks in the gap between cards
        if (relY - row * (cardH + gap) > cardH) return -1
        if (relX - column * (cardW + gap) > cardW) return -1
        return index
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        if (mouseButton != 0) return

        // Click outside the window closes it
        if (mouseX < winX || mouseX > winX + windowW || mouseY < winY || mouseY > winY + windowH) {
            requestClose()
            return
        }

        // Close button
        if (mouseX.toFloat() in closeX..closeX + 18f && mouseY.toFloat() in closeY..closeY + 18f) {
            requestClose()
            return
        }

        // Search field
        if (mouseX.toFloat() in searchBoxX..searchBoxX + searchBoxW && mouseY.toFloat() in searchBoxY - 4f..searchBoxY + searchBoxH + 4f) {
            searchFocused = true
            return
        }
        searchFocused = false

        // Theme card
        val index = cardAt(mouseX, mouseY)
        if (index >= 0) {
            ThemeManager.setTheme(filteredThemes[index].displayName)
        }
    }

    @Throws(IOException::class)
    override fun keyTyped(typedChar: Char, keyCode: Int) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (searchFocused) searchFocused = false else requestClose()
            return
        }

        if (searchFocused) {
            when (keyCode) {
                Keyboard.KEY_BACK -> if (search.isNotEmpty()) search = search.dropLast(1)
                Keyboard.KEY_RETURN -> searchFocused = false
                else -> if (!Character.isISOControl(typedChar)) {
                    search += typedChar
                    targetScroll = 0f
                }
            }
            return
        }

        super.keyTyped(typedChar, keyCode)
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        val time = System.currentTimeMillis()

        // Wheel scrolling with inertia
        val wheel = Mouse.getDWheel()
        if (wheel != 0) {
            targetScroll -= wheel * 0.4f
            targetScroll = targetScroll.coerceIn(0f, maxScroll())
            scrollBarAlpha = 1f
        }

        // Open / close progress
        openProgress += (1f - openProgress) * 0.14f
        if (closing) {
            closeProgress += 0.11f
            if (closeProgress >= 1f) {
                mc.displayGuiScreen(null)
                return
            }
        }

        // Frame animations
        scroll += (targetScroll - scroll) * 0.16f
        searchFocusAnim += ((if (searchFocused) 1f else 0f) - searchFocusAnim) * 0.25f
        scrollBarAlpha = (scrollBarAlpha - 0.012f).coerceAtLeast(0f)

        // Cursor parallax - the window drifts gently toward the pointer
        val targetPX = (mouseX - width / 2f) / width * 7f
        val targetPY = (mouseY - height / 2f) / height * 6f
        parallaxX += (targetPX - parallaxX) * 0.045f
        parallaxY += (targetPY - parallaxY) * 0.045f

        // Close button hover fade
        val inClose = mouseX.toFloat() in closeX..closeX + 18f && mouseY.toFloat() in closeY..closeY + 18f
        closeHoverAnim += ((if (inClose) 1f else 0f) - closeHoverAnim) * 0.3f

        val uiFade = openProgress * (1f - closeProgress)
        riseOffset = (1f - openProgress) * 16f + closeProgress * 28f
        val wy = winY + riseOffset

        // Light dim over the sharp game backdrop (no full-screen blur)
        drawRect(0f, 0f, width.toFloat(), height.toFloat(),
            Color(overlayColor.red, overlayColor.green, overlayColor.blue, (overlayColor.alpha * uiFade).toInt()))

        // Frosted glass window: blurred and transparent so the game shines through
        HudBlur.blur(winX, wy, winX + windowW, wy + windowH, 12f)
        val windowColor = Color(windowBg.red, windowBg.green, windowBg.blue, (windowBg.alpha * uiFade).toInt())
        RoundedUtil.drawRound(winX, wy, windowW, windowH, 12f, windowColor)

        // Live accent hairline on the top edge
        val accent = ClientThemesUtils.getColor()
        if (uiFade > 0.05f) {
            RoundedUtil.drawRound(winX + 8f, wy, windowW - 16f, 1.2f, 0.6f,
                Color(accent.red, accent.green, accent.blue, (170 * uiFade).toInt()))
        }

        drawHeader(mouseX, mouseY, wy, time)
        drawGrid(mouseX, mouseY, wy, time)
        drawFooter(wy, time)
    }

    private fun drawHeader(mouseX: Int, mouseY: Int, y: Float, time: Long) {
        val accent = ClientThemesUtils.getColor()

        // Title letters stagger in from the left
        val title = "THEMES"
        var tx = winX + pad
        val ty = y + 14f
        title.forEachIndexed { i, c ->
            val cp = (openProgress * 1.8f - i * 0.08f).coerceIn(0f, 1f)
            val ch = c.toString()
            Fonts.font35.drawString(ch, tx, ty + (1f - cp) * 6f,
                Color(textPrimary.red, textPrimary.green, textPrimary.blue, (255 * cp).toInt()).rgb)
            tx += Fonts.font35.getStringWidth(ch) + 1.4f
        }

        // Live gradient underline grows in with the open animation
        val underlineW = 62f * openProgress
        if (underlineW > 0.5f) {
            val a1 = ClientThemesUtils.getColor(0)
            val a2 = ClientThemesUtils.getColor(90)
            RoundedUtil.applyGradientHorizontal(winX + pad, y + 34f, underlineW, 2.5f, 1.25f, a1, a2, Runnable {})
        }

        // Search field: borderless, underline only
        val inSearch = mouseX.toFloat() in searchBoxX..searchBoxX + searchBoxW && mouseY.toFloat() in searchBoxY - 4f..searchBoxY + searchBoxH + 4f
        drawRect(searchBoxX, searchBoxY + searchBoxH, searchBoxX + searchBoxW, searchBoxY + searchBoxH + 1f,
            if (inSearch) lineMuted.brighter() else lineMuted)

        // Focused underline sweeps out from the center
        if (searchFocusAnim > 0.02f) {
            val lw = searchBoxW * searchFocusAnim
            drawRect(searchBoxX + (searchBoxW - lw) / 2f, searchBoxY + searchBoxH,
                searchBoxX + (searchBoxW + lw) / 2f, searchBoxY + searchBoxH + 1f, accent)
        }

        val textY = searchBoxY + 4f
        if (search.isEmpty()) {
            Fonts.font30.drawString("Search themes", searchBoxX, textY, textMuted.rgb)
        } else {
            Fonts.font30.drawString(search, searchBoxX, textY, textPrimary.rgb)
        }

        // Blinking caret while typing
        if (searchFocused) {
            val caretAlpha = (0.5 + 0.5 * sin(time / 450.0)).toFloat()
            val caretX = searchBoxX + Fonts.font30.getStringWidth(search) + 1f
            drawRect(caretX, textY, caretX + 1f, textY + 10f,
                Color(textPrimary.red, textPrimary.green, textPrimary.blue, (255 * caretAlpha).toInt()))
        }

        // Close (x) button with a soft hover disc
        if (closeHoverAnim > 0.01f) {
            RoundedUtil.drawRound(closeX, closeY, 18f, 18f, 9f, Color(255, 255, 255, (24 * closeHoverAnim).toInt()))
        }
        val iconColor = mixColor(textMuted, textPrimary, closeHoverAnim)
        drawCloseIcon(closeX + 9f, closeY + 9f, 4.5f, iconColor)
    }

    private fun drawGrid(mouseX: Int, mouseY: Int, headerY: Float, time: Long) {
        val themes = filteredThemes
        val gridTop = headerY + headerH
        val gridBottom = gridTop + contentViewH

        makeScissorBox(contentX, gridTop, contentX + windowW - pad * 2, gridBottom)

        val hoveredIndex = cardAt(mouseX, mouseY)
        val currentName = ClientThemesUtils.currentThemeName()

        for (index in themes.indices) {
            val column = index % columns
            val row = index / columns
            val cardX = contentX + column * (cardW + gap)
            val cardY = gridTop + row * (cardH + gap) - scroll

            // Cull cards outside the visible band
            if (cardY + cardH < gridTop - 16f || cardY > gridBottom + 16f) {
                hoverProgress.remove(index)
                continue
            }

            // Staggered cascade on open - higher cards join later, but every
            // card converges to full opacity once the animation settles
            val cascade = ((1f - (1f - openProgress) * (1f + index * 0.015f)) * 1.5f).coerceIn(0f, 1f)

            // Scroll reveal: cards float up as they enter the visible band.
            // Fully visible cards are never dimmed, only the clipped edge fades.
            val reveal = ((gridBottom - cardY) / cardH).coerceIn(0f, 1f)
            val revealRise = (1f - reveal) * 18f
            val alpha = cascade * reveal

            // Hover lift + expand
            val target = if (index == hoveredIndex) 1f else 0f
            val progress = (hoverProgress[index] ?: 0f) + (target - (hoverProgress[index] ?: 0f)) * 0.3f
            hoverProgress[index] = progress
            val lift = progress * 2.5f
            val expand = progress * 1.2f

            val theme = themes[index]
            val accent = ClientThemesUtils.getColor()
            val isSelected = currentName.equals(theme.displayName, true)

            val drawY = cardY - lift - (1f - cascade) * 12f - revealRise
            if (alpha <= 0.02f) continue

            // Selected: breathing accent halo
            if (isSelected) {
                val breath = (0.7f + 0.3f * sin(time / 650.0)).toFloat()
                RoundedUtil.drawRound(cardX - 1.4f - expand, drawY - 1.4f - expand,
                    cardW + 2.8f + expand * 2f, cardH + 2.8f + expand * 2f, 8f + expand,
                    Color(accent.red, accent.green, accent.blue, (255 * breath * alpha).toInt()))
            }

            // Card frame, brightening with hover
            val frame = mixColor(cardFrame, cardFrameHover, progress)
            RoundedUtil.drawRound(cardX - expand, drawY - expand, cardW + expand * 2f, cardH + expand * 2f, 7f + expand,
                Color(frame.red, frame.green, frame.blue, (frame.alpha * alpha).toInt()))

            // Live animated gradient preview - each card shimmers with its own theme
            val p1 = ClientThemesUtils.getColorForMode(theme.displayName, index)
            val p2 = ClientThemesUtils.getColorForMode(theme.displayName, index + 60)
            val prevAlpha = (255 * alpha).toInt()
            RoundedUtil.applyGradientHorizontal(cardX + 6f, drawY + 6f, cardW - 12f, 30f, 5f,
                Color(p1.red, p1.green, p1.blue, prevAlpha),
                Color(p2.red, p2.green, p2.blue, prevAlpha),
                Runnable {})

            // Hover veil
            if (progress > 0.01f) {
                RoundedUtil.drawRound(cardX - expand, drawY - expand, cardW + expand * 2f, cardH + expand * 2f, 7f + expand,
                    Color(255, 255, 255, (26 * progress * alpha).toInt()))
            }

            // Theme name
            val name = fitText(theme.displayName, cardW - 12f)
            Fonts.font30.drawCenteredString(name, cardX + cardW / 2f, drawY + 43f,
                Color(textPrimary.red, textPrimary.green, textPrimary.blue, (255 * alpha).toInt()).rgb)

            // Selected marker dot
            if (isSelected) {
                drawFilledCircle((cardX + cardW - 13f).toInt(), (drawY + 13f).toInt(), 2.5f,
                    Color(accent.red, accent.green, accent.blue, (255 * alpha).toInt()))
            }
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST)

        drawScrollbar(gridTop)
        drawScrollHint(gridBottom, time)
    }

    /** Slim accent scrollbar that fades in while scrolling. */
    private fun drawScrollbar(gridTop: Float) {
        val max = maxScroll()
        if (scrollBarAlpha <= 0.02f || max <= 0.5f) return

        val accent = ClientThemesUtils.getColor()
        val trackX = winX + windowW - pad / 2f - 1f
        val trackH = contentViewH
        val contentHeight = max + contentViewH
        val thumbH = max(26f, trackH * (contentViewH / contentHeight))
        val thumbY = gridTop + (trackH - thumbH) * (scroll / max)

        RoundedUtil.drawRound(trackX, thumbY, 2.5f, thumbH, 1.25f,
            Color(accent.red, accent.green, accent.blue, (200 * scrollBarAlpha).toInt()))
    }

    /** Bouncing chevron hinting the grid can scroll, fading out once used. */
    private fun drawScrollHint(gridBottom: Float, time: Long) {
        if (maxScroll() <= 0.5f || scroll > 1f) return

        val alpha = (1f - scroll).coerceIn(0f, 1f) * openProgress
        if (alpha <= 0.03f) return

        val bounce = sin(time / 320.0).toFloat() * 2.5f
        val cx = winX + windowW / 2f
        val cy = gridBottom - 7f + bounce

        GlStateManager.pushMatrix()
        GlStateManager.disableTexture2D()
        GlStateManager.enableBlend()
        GL11.glLineWidth(1.6f)
        GL11.glColor4f(1f, 1f, 1f, 0.65f * alpha)
        GL11.glBegin(GL11.GL_LINES)
        GL11.glVertex2f(cx - 4f, cy - 2f)
        GL11.glVertex2f(cx, cy + 2f)
        GL11.glVertex2f(cx, cy + 2f)
        GL11.glVertex2f(cx + 4f, cy - 2f)
        GL11.glEnd()
        GlStateManager.disableBlend()
        GlStateManager.enableTexture2D()
        GlStateManager.popMatrix()
    }

    private fun drawFooter(y: Float, time: Long) {
        val footerY = y + windowH - footerH / 2f - 5f
        val accent = ClientThemesUtils.getColor()

        // Current theme: breathing dot + live name + accent underline
        val pulse = (0.6 + 0.4 * sin(time / 900.0)).toFloat()
        drawFilledCircle((winX + pad + 3).toInt(), (footerY + 5).toInt(), 3f,
            Color(accent.red, accent.green, accent.blue, (255 * pulse).toInt()))

        val name = ClientThemesUtils.currentThemeName()
        Fonts.font30.drawString(name, winX + pad + 10f, footerY, accent.rgb)
        val nameW = Fonts.font30.getStringWidth(name)
        RoundedUtil.drawRound(winX + pad + 10f, footerY + 12f, nameW.toFloat(), 1.5f, 0.75f,
            Color(accent.red, accent.green, accent.blue, 90))

        // Count + hint, right aligned
        val hint = "${filteredThemes.size} THEMES  -  CLICK TO APPLY"
        Fonts.font30.drawString(hint, winX + windowW - pad - Fonts.font30.getStringWidth(hint), footerY, textMuted.rgb)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun drawCloseIcon(cx: Float, cy: Float, r: Float, color: Color) {
        GlStateManager.pushMatrix()
        GlStateManager.disableTexture2D()
        GlStateManager.enableBlend()
        GL11.glLineWidth(1.6f)
        GL11.glColor4f(color.red / 255f, color.green / 255f, color.blue / 255f, color.alpha / 255f)
        GL11.glBegin(GL11.GL_LINES)
        GL11.glVertex2f(cx - r, cy - r)
        GL11.glVertex2f(cx + r, cy + r)
        GL11.glVertex2f(cx - r, cy + r)
        GL11.glVertex2f(cx + r, cy - r)
        GL11.glEnd()
        GlStateManager.disableBlend()
        GlStateManager.enableTexture2D()
        GlStateManager.popMatrix()
    }

    private fun mixColor(a: Color, b: Color, t: Float): Color {
        val p = t.coerceIn(0f, 1f)
        val q = 1f - p
        return Color(
            (a.red * q + b.red * p).toInt(),
            (a.green * q + b.green * p).toInt(),
            (a.blue * q + b.blue * p).toInt(),
            (a.alpha * q + b.alpha * p).toInt()
        )
    }

    private fun fitText(text: String, maxWidth: Float): String {
        if (Fonts.font30.getStringWidth(text) <= maxWidth) return text
        var t = text
        while (t.length > 1 && Fonts.font30.getStringWidth("$t...") > maxWidth) {
            t = t.dropLast(1)
        }
        return "$t..."
    }
}
