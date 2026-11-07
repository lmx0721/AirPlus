/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Rise 6.9.5 Modern ClickGUI port — core utilities.
 * Ported from com.alan.clients.ui.click.standard (Animation/Easing/StopWatch/ScrollUtil/TextBox/UIColors).
 */
package net.airplus.ui.client.clickgui.rise

import net.airplus.features.module.modules.render.ClickGUI
import net.airplus.ui.font.GameFontRenderer
import net.minecraft.util.ChatAllowedCharacters
import net.minecraft.util.MathHelper
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.GL11
import java.awt.Color
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

// ============================== UIColors ==============================

object UIColors {
    val BACKGROUND: Color get() = ClickGUI.nlThemeBgColor
    val SECONDARY: Color get() = Color(BACKGROUND.red - 5, BACKGROUND.green - 6, BACKGROUND.blue - 8)
    val TEXT = Color(255, 255, 255)
    val SECONDARY_TEXT = Color(255, 255, 255, 220)
    val TRINARY_TEXT = Color(255, 255, 255, 130)
    val OVERLAY = Color(0, 0, 0, 50)

    fun withAlpha(color: Color, alpha: Int): Color = Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))
}

fun accentColor(): Color = ClickGUI.nlAccentColor

/** Rise UIColors.Y(alpha) — same color with overridden alpha (top-level for global use). */
fun Color.withA(alpha: Int): Color = Color(red, green, blue, alpha.coerceIn(0, 255))

// ============================== StopWatch ==============================

class RiseStopWatch {
    private var lastTime = System.currentTimeMillis()

    fun reset() { lastTime = System.currentTimeMillis() }

    /** Rise StopWatch.T(ms): true if [ms] elapsed, auto-reset. */
    fun delay(ms: Long): Boolean =
        if (System.currentTimeMillis() - lastTime >= ms) { reset(); true } else false

    val elapsedTime: Long get() = System.currentTimeMillis() - lastTime
}

// ============================== Animation / Easing ==============================

enum class RiseEasing(val function: (Double) -> Double) {
    LINEAR({ it }),
    EASE_IN_EXPO({ d -> if (d == 0.0) 0.0 else Math.pow(2.0, 10.0 * d - 10.0) }),
    EASE_OUT_EXPO({ d -> if (d == 1.0) 1.0 else 1.0 - Math.pow(2.0, -10.0 * d) });
}

class RiseAnimation(private var easing: RiseEasing, private var duration: Long) {
    private var startTime = System.currentTimeMillis()
    private var startValue = 0.0
    private var destinationValue = 0.0
    private var finished = false

    var value = 0.0
        private set

    /** Rise Animation.Q(value) — tick towards the target. Call once per frame. */
    fun animate(target: Double) {
        val millis = System.currentTimeMillis()
        if (destinationValue != target) {
            destinationValue = target
            reset()
        } else {
            finished = millis - duration > startTime
            if (finished) {
                value = target
                return
            }
        }

        val progress = easing.function.invoke(elapsed().coerceIn(0.0, 1.0))
        value = if (value > target) {
            startValue - (startValue - target) * progress
        } else {
            startValue + (target - startValue) * progress
        }
    }

    private fun elapsed(): Double = (System.currentTimeMillis() - startTime).toDouble() / duration.toDouble()

    fun reset() {
        startTime = System.currentTimeMillis()
        startValue = value
        finished = false
    }

    fun isFinished() = finished
    fun setEasing(easing: RiseEasing) { this.easing = easing }
    fun setDuration(duration: Long) { this.duration = duration }
    fun getDuration(): Long = duration
    fun setValue(v: Double) { value = v }
    fun getDestinationValue() = destinationValue
}

// ============================== ScrollUtil ==============================

class RiseScrollUtil {
    private var target = 0.0
    var scroll = 0.0
        private set
    private var maxScroll = 25.0
    private val smoothTimer = RiseStopWatch()
    private val wheelTimer = RiseStopWatch()
    private var scrolled = false

    /** Rise ScrollUtil.qx() — read wheel + smooth. Call once per frame before use. */
    fun update() {
        if (wheelTimer.delay(50)) {
            val wheel = Mouse.getDWheel().toDouble()
            scrolled = wheel != 0.0
            // Rise: while the wheel is idle the target is clamped to the regular scroll range
            // [maxScroll, 0]; while scrolling it may overshoot by up to 30px in both directions.
            target = if (scrolled) {
                (target + wheel / 2.0).coerceIn(maxScroll - 30.0, 30.0)
            } else {
                target.coerceIn(maxScroll.coerceAtMost(0.0), 0.0)
            }
        }

        var steps = smoothTimer.elapsedTime
        smoothTimer.reset()
        steps = steps.coerceAtMost(100)
        while (steps-- > 0) {
            scroll += (target - scroll) * 0.01
        }
    }

    /** Rise ScrollUtil.V(max) — set max scroll extent. */
    fun setMax(max: Double) { maxScroll = max }

    /** Rise ScrollUtil.U(target) — jump target. */
    fun setTarget(t: Double) { target = t.coerceIn(0.0, maxScroll.coerceAtLeast(0.0)) }

    fun reset() {
        scroll = 0.0
        target = 0.0
    }

    fun isScrolling() = scrolled
    fun getTarget() = target
}

// ============================== TextBox ==============================

enum class RiseTextAlign { LEFT, CENTER }

class RiseTextBox(
    var font: GameFontRenderer,
    var color: Color,
    var textAlign: RiseTextAlign,
    var placeholder: String,
    var width: Float,
    val allowedChars: String? = null,
) {
    var text = ""
    var selected = false
    var position = Pair(200.0, 200.0)
    private var cursor = 0
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorSmooth = 0.0
    private var smoothTimer = 0.0

    fun isEmpty() = text.trim().isEmpty()

    fun setCursorPosition(i: Int) { cursor = i }

    fun draw() {
        Keyboard.enableRepeatEvents(true)
        cursor = cursor.coerceIn(0, text.length)
        cursorY = position.second.toFloat()
        val empty = isEmpty()
        val display = if (empty) placeholder else text
        val startOffset = if (empty) 0 else visibleStart(display)
        var visible = if (empty) display else display.substring(startOffset)
        while (font.getStringWidth(visible) > width && visible.isNotEmpty()) {
            visible = visible.substring(0, visible.length - 1)
        }

        val textW = font.getStringWidth(visible).toFloat()
        val now = System.currentTimeMillis()
        val delta = Math.min(Math.abs(now - smoothTimer), 500.0)
        smoothTimer = now.toDouble()

        val posX: Double = when (textAlign) {
            RiseTextAlign.CENTER -> {
                var p = position.first - textW / 2.0
                // smooth the centered position like Rise
                var steps = delta.toInt().coerceAtMost(100)
                while (steps-- > 0) p = (p * 19.0 + (position.first - textW / 2.0)) / 20.0
                p
            }
            RiseTextAlign.LEFT -> position.first
        }

        if (empty) {
            font.drawString(
                visible, posX.toFloat(), position.second.toFloat(),
                Color(color.red, color.green, color.blue, (color.alpha * (if (selected) 0.3f else 0.2f)).toInt()).rgb
            )
        } else {
            font.drawString(visible, posX.toFloat(), position.second.toFloat(), color.rgb)
        }

        if (selected) {
            val marked = substringBetween(display, startOffset, cursor)
            val markedW = font.getStringWidth(marked).toFloat()
            cursorX = posX.toFloat() + markedW
            var steps = delta.toInt().coerceAtMost(100)
            while (steps-- > 0) cursorSmooth = (cursorSmooth * 19.0 + (markedW - 2.0)) / 20.0

            val blink = ((Math.sin(now / 150.0) + 1.0) / 2.0 * 255.0).toInt()
            font.drawString(
                "|", (posX + cursorSmooth + 1.0).toFloat(), position.second.toFloat() - 1f,
                Color(color.red, color.green, color.blue, if (color.alpha == 0) 0 else blink).rgb
            )
        }
    }

    fun getTextX() = cursorX
    fun getTextY() = cursorY

    private fun visibleStart(display: String): Int {
        if (font.getStringWidth(display) <= width) return 0
        var i = cursor
        while (i > 0 && font.getStringWidth(display.substring(i - 1, cursor)) < width) i--
        return i
    }

    private fun substringBetween(s: String, from: Int, to: Int): String {
        val i = from.coerceIn(0, s.length)
        val j = to.coerceIn(i, s.length)
        return s.substring(i, j)
    }

    fun click(mouseX: Int, mouseY: Int, button: Int) {
        val x = if (textAlign == RiseTextAlign.CENTER) position.first - width / 2.0 else position.first
        val hovered = mouseX >= x && mouseX <= x + width && mouseY >= position.second && mouseY <= position.second + font.FONT_HEIGHT
        selected = button == 0 && hovered
        if (selected) cursor = text.length
    }

    fun key(typedChar: Char, keyCode: Int) {
        if (!selected || keyCode == 28) return
        cursor = cursor.coerceIn(0, text.length)
        val ctrl = Keyboard.isKeyDown(29) || Keyboard.isKeyDown(157) || Keyboard.isKeyDown(219) || Keyboard.isKeyDown(220)
        when {
            keyCode == 208 || keyCode == 200 -> return
            ctrl && keyCode == 47 -> {
                try {
                    val clip = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor) as String
                    val before = text.length
                    addText(clip, cursor)
                    cursor += text.length - before
                } catch (_: Exception) { }
            }
            ctrl && keyCode == 46 -> {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(StringSelection(text), null)
            }
            keyCode == 211 && text.isNotEmpty() -> deleteCharAt(cursor + 1)
            keyCode == 14 && text.isNotEmpty() -> {
                deleteCharAt(cursor)
                cursor--
                if (ctrl) while (cursor > 0) { deleteCharAt(cursor); cursor-- }
            }
            keyCode == 205 -> {
                cursor++
                if (ctrl) cursor = text.length
            }
            keyCode == 203 -> {
                cursor--
                if (ctrl) cursor = 0
            }
            else -> {
                if (canType(typedChar)) {
                    val before = text.length
                    addText(typedChar.toString(), cursor)
                    if (text.length > before) cursor++
                }
                cursor = cursor.coerceIn(0, text.length)
            }
        }
    }

    private fun canType(c: Char): Boolean {
        if (c.toInt() == 0 || c == '\n' || c == '\r' || c == '\t') return false
        if (allowedChars != null && allowedChars.isNotEmpty()) {
            return allowedChars.indexOf(c) >= 0 || allowedChars.indexOf(c.lowercaseChar()) >= 0
        }
        return ChatAllowedCharacters.isAllowedCharacter(c) || c == ' '
    }

    private fun addText(add: String, at: Int) {
        if (add.isEmpty()) return
        val sb = StringBuilder()
        for (c in add) if (canType(c)) sb.append(c)
        if (sb.isNotEmpty()) {
            val i = at.coerceIn(0, text.length)
            text = StringBuilder(text).insert(i, sb).toString()
        }
    }

    private fun deleteCharAt(at: Int) {
        if (at in 1..text.length) {
            text = StringBuilder(text).deleteCharAt(at - 1).toString()
        }
    }
}

// ============================== Render helpers ==============================

object RiseRender {
    fun hovered(x: Float, y: Float, width: Float, height: Float, mouseX: Int, mouseY: Int): Boolean =
        mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height

    fun hovered(x: Double, y: Double, width: Double, height: Double, mouseX: Int, mouseY: Int): Boolean =
        mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height

    fun rounded(x: Double, y: Double, width: Double, height: Double, radius: Double, color: Color) {
        if (width <= 0 || height <= 0) return
        net.airplus.utils.render.RoundedUtil.drawRound(
            x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat(), radius.toFloat(), color
        )
    }

    /** Rise RenderUtil.dropShadow — dark glow outside the rect. */
    fun dropShadow(blur: Int, x: Float, y: Float, width: Float, height: Float, color: Color) {
        net.airplus.utils.GlowUtils.drawGlow(x, y, width, height, blur, color)
    }

    /** Vertical (top->bottom) linear gradient quad. */
    fun verticalGradient(x: Float, y: Float, width: Float, height: Float, top: Color, bottom: Color) {
        GL11.glPushMatrix()
        net.minecraft.client.renderer.GlStateManager.enableBlend()
        net.minecraft.client.renderer.GlStateManager.disableTexture2D()
        net.minecraft.client.renderer.GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        GL11.glShadeModel(GL11.GL_SMOOTH)
        val tessellator = net.minecraft.client.renderer.Tessellator.getInstance()
        val wr = tessellator.worldRenderer
        wr.begin(GL11.GL_QUADS, net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_COLOR)
        wr.pos(x.toDouble(), y.toDouble(), 0.0).color(top.red / 255f, top.green / 255f, top.blue / 255f, top.alpha / 255f).endVertex()
        wr.pos(x.toDouble(), (y + height).toDouble(), 0.0).color(bottom.red / 255f, bottom.green / 255f, bottom.blue / 255f, bottom.alpha / 255f).endVertex()
        wr.pos((x + width).toDouble(), (y + height).toDouble(), 0.0).color(bottom.red / 255f, bottom.green / 255f, bottom.blue / 255f, bottom.alpha / 255f).endVertex()
        wr.pos((x + width).toDouble(), y.toDouble(), 0.0).color(top.red / 255f, top.green / 255f, top.blue / 255f, top.alpha / 255f).endVertex()
        tessellator.draw()
        GL11.glShadeModel(GL11.GL_FLAT)
        net.minecraft.client.renderer.GlStateManager.enableTexture2D()
        net.minecraft.client.renderer.GlStateManager.disableBlend()
        GL11.glPopMatrix()
    }

    /** Rounded rect with a horizontal white->color gradient (color picker saturation area, left half only). */
    fun horizontalGradientRounded(x: Float, y: Float, width: Float, height: Float, radius: Float, left: Color, right: Color) {
        net.minecraft.client.renderer.GlStateManager.enableBlend()
        net.minecraft.client.renderer.GlStateManager.disableTexture2D()
        net.minecraft.client.renderer.GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        GL11.glShadeModel(GL11.GL_SMOOTH)
        val tessellator = net.minecraft.client.renderer.Tessellator.getInstance()
        val wr = tessellator.worldRenderer
        wr.begin(GL11.GL_POLYGON, net.minecraft.client.renderer.vertex.DefaultVertexFormats.POSITION_COLOR)
        val step = 5
        // TL -> BL -> BR -> TR order to keep a smooth polygon like RoundedUtil
        var deg = 180
        while (deg <= 270) {
            val rad = Math.toRadians(deg.toDouble())
            wr.pos(x + radius + Math.sin(rad) * radius, y + radius + Math.cos(rad) * radius, 0.0)
                .color(left.red / 255f, left.green / 255f, left.blue / 255f, left.alpha / 255f).endVertex()
            deg += step
        }
        var deg2 = 90
        while (deg2 <= 180) {
            val rad = Math.toRadians(deg2.toDouble())
            wr.pos(x + radius + Math.sin(rad) * radius, y + height - radius + Math.cos(rad) * radius, 0.0)
                .color(left.red / 255f, left.green / 255f, left.blue / 255f, left.alpha / 255f).endVertex()
            deg2 += step
        }
        var deg3 = 270
        while (deg3 <= 360) {
            val rad = Math.toRadians(deg3.toDouble())
            wr.pos(x + width - radius + Math.sin(rad) * radius, y + height - radius + Math.cos(rad) * radius, 0.0)
                .color(right.red / 255f, right.green / 255f, right.blue / 255f, right.alpha / 255f).endVertex()
            deg3 += step
        }
        var deg4 = 0
        while (deg4 <= 90) {
            val rad = Math.toRadians(deg4.toDouble())
            wr.pos(x + width - radius + Math.sin(rad) * radius, y + radius + Math.cos(rad) * radius, 0.0)
                .color(right.red / 255f, right.green / 255f, right.blue / 255f, right.alpha / 255f).endVertex()
            deg4 += step
        }
        tessellator.draw()
        GL11.glShadeModel(GL11.GL_FLAT)
        net.minecraft.client.renderer.GlStateManager.enableTexture2D()
        net.minecraft.client.renderer.GlStateManager.disableBlend()
    }

    /** Rise RenderUtil.rainbowRectangle — hue spectrum bar. */
    fun rainbowBar(x: Float, y: Float, width: Float, height: Float, radius: Float) {
        val segments = 30
        val segW = width / segments
        for (i in 0 until segments) {
            val hue = i.toFloat() / segments
            val c = Color.getHSBColor(hue, 1f, 1f)
            rounded((x + i * segW).toDouble(), y.toDouble(), (segW + 0.5f).toDouble(), height.toDouble(), if (i == 0 || i == segments - 1) radius.toDouble() / 2.0 else 0.0, c)
        }
    }

    fun scissorStart(x: Double, y: Double, width: Double, height: Double) {
        val sr = net.minecraft.client.gui.ScaledResolution(net.minecraft.client.Minecraft.getMinecraft())
        val factor = sr.scaleFactor
        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        GL11.glScissor(
            (x * factor).toInt(),
            ((sr.scaledHeight - y - height) * factor).toInt(),
            (width * factor).toInt(),
            (height * factor).toInt()
        )
    }

    fun scissorEnd() = GL11.glDisable(GL11.GL_SCISSOR_TEST)

    fun clamp(v: Float, min: Float, max: Float): Float = MathHelper.clamp_float(v, min, max)
}

// ============================== Sidebar entries ==============================

/** One entry in the Rise sidebar (pseudo categories included). */
class RiseSidebarEntry(
    val label: String,
    val icon: String,
    val screen: RiseScreen,
    val isCategory: Boolean = false,
)
