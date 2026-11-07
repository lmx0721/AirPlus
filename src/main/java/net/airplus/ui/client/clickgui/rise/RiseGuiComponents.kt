/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Rise 6.9.5 Modern ClickGUI port — value components + module component.
 * Ported from com.alan.clients.ui.click.standard.components.*
 */
package net.airplus.ui.client.clickgui.rise

import net.airplus.config.BlockValue
import net.airplus.config.BoolValue
import net.airplus.config.ColorValue
import net.airplus.config.FloatRangeValue
import net.airplus.config.FloatValue
import net.airplus.config.FontValue
import net.airplus.config.IntRangeValue
import net.airplus.config.IntValue
import net.airplus.config.ListValue
import net.airplus.config.TextValue
import net.airplus.config.Value
import net.airplus.features.module.Module
import net.airplus.features.module.modules.render.ClickGUI
import net.airplus.utils.GlowUtils
import net.minecraft.util.MathHelper
import java.awt.Color
import kotlin.math.roundToInt

// ============================== Screen ==============================

interface RiseScreen {
    fun onRender(mouseX: Int, mouseY: Int, partialTicks: Float)
    fun onKey(typedChar: Char, keyCode: Int)
    fun onClick(mouseX: Int, mouseY: Int, mouseButton: Int)
    fun onMouseRelease()
    fun onBloom()
    fun onSwitch() {}
    /** Rise Screen.pZ() — whether typing letters should auto-switch to search. */
    fun allowsAutoSearch() = true
}

// ============================== ValueComponent ==============================

abstract class RiseValueComponent(val value: Value<*>, protected val gui: RiseClickGui) {
    var height = 14.0
    var position: Pair<Double, Double>? = null
    var opacity = 255

    abstract fun draw(position: Pair<Double, Double>, mouseX: Int, mouseY: Int, partialTicks: Float)

    /** Returns true when the click was consumed. */
    abstract fun click(mouseX: Int, mouseY: Int, mouseButton: Int): Boolean

    open fun onMouseUp() {}

    abstract fun key(typedChar: Char, keyCode: Int)

    fun visible(): Boolean = value.shouldRender()
}

// ---------- Boolean ----------

class RiseBooleanValueComponent(value: Value<*>, gui: RiseClickGui) : RiseValueComponent(value, gui) {
    private val boolValue get() = value as BoolValue
    private val timer = RiseStopWatch()
    private var dotSize = 0.0

    override fun draw(position: Pair<Double, Double>, mouseX: Int, mouseY: Int, partialTicks: Float) {
        this.position = position
        val label = value.name
        RiseGuiFonts.small().drawString(label, position.first.toFloat(), position.second.toFloat(), UIColors.SECONDARY_TEXT.withA(opacity).rgb)
        val dotX = position.first + RiseGuiFonts.small().getStringWidth(label) + 3.0
        if (boolValue.get()) {
            dotSize = Math.min(5.0, dotSize + timer.elapsedTime / 20.0)
        } else {
            dotSize = Math.max(0.0, dotSize - timer.elapsedTime / 20.0)
        }

        RiseRender.rounded(dotX - 2.5 + 5.0, position.second, 5.0, 5.0, 2.5, UIColors.BACKGROUND.withA(opacity))
        if (dotSize != 0.0) {
            val c = Color(accentColor().red, accentColor().green, accentColor().blue, opacity)
            RiseRender.rounded(dotX - dotSize / 2.0 + 5.0, position.second + 2.5 - dotSize / 2.0 + 0.0, dotSize, dotSize, dotSize / 2.0, c)
            if (dotSize > 1.0) {
                GlowUtils.drawGlow(
                    (dotX - dotSize / 2.0 + 5.0).toFloat(), (position.second + 2.5 - dotSize / 2.0).toFloat(),
                    dotSize.toFloat(), dotSize.toFloat(), 6, Color(accentColor().red, accentColor().green, accentColor().blue, (opacity * ClickGUI.nlGlowIntensity).toInt().coerceIn(0, 255))
                )
            }
        }
        timer.reset()
    }

    override fun click(mouseX: Int, mouseY: Int, mouseButton: Int): Boolean {
        val pos = position ?: return false
        if (mouseButton == 0 && RiseRender.hovered(pos.first, pos.second - 3.5, gui.w - 70.0, height, mouseX, mouseY)) {
            boolValue.toggle()
            return true
        }
        return false
    }

    override fun key(typedChar: Char, keyCode: Int) {}
}

// ---------- Number (IntValue / FloatValue / BlockValue) ----------

class RiseNumberValueComponent(value: Value<*>, gui: RiseClickGui) : RiseValueComponent(value, gui) {
    private val timer = RiseStopWatch()
    var dragging = false
    private var progress = 0.0
    private var smoothProgress = 0.0
    private var hoveredSlider = false
    private var hoverAnim = 0f
    val textBox = RiseTextBox(RiseGuiFonts.small(), UIColors.SECONDARY_TEXT, RiseTextAlign.LEFT, "", 20f, "1234567890.")

    private val isInt: Boolean = value is IntValue || value is BlockValue
    private val min: Double
    private val max: Double

    init {
        when (value) {
            is IntValue -> { min = value.minimum.toDouble(); max = value.maximum.toDouble() }
            is FloatValue -> { min = value.minimum.toDouble(); max = value.maximum.toDouble() }
            is BlockValue -> { min = value.minimum.toDouble(); max = value.maximum.toDouble() }
            else -> { min = 0.0; max = 1.0 }
        }
        recalc()
    }

    fun recalc() {
        val current = when (value) {
            is IntValue -> value.get().toDouble()
            is FloatValue -> value.get().toDouble()
            is BlockValue -> value.get().toDouble()
            else -> 0.0
        }
        progress = ((current - min) / (max - min).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
    }

    private fun currentText(): String {
        val s = when (value) {
            is IntValue -> value.get().toString()
            is FloatValue -> formatFloat(value.get())
            is BlockValue -> value.get().toString()
            else -> ""
        }
        return s
    }

    override fun draw(position: Pair<Double, Double>, mouseX: Int, mouseY: Int, partialTicks: Float) {
        this.position = position
        val label = value.name + if (value.suffix != null) " (${value.suffix})" else ""
        val font = RiseGuiFonts.small()
        val offset = font.getStringWidth(label) + 7f
        hoveredSlider = RiseRender.hovered(position.first + offset - 5.0, position.second - 3.5, 110.0, height, mouseX, mouseY)
        hoverAnim = if (hoveredSlider) Math.min(1f, hoverAnim + timer.elapsedTime / 200f) else Math.max(0f, hoverAnim - timer.elapsedTime / 200f)

        font.drawString(label, position.first.toFloat(), position.second.toFloat(), UIColors.SECONDARY_TEXT.withA(opacity).rgb)

        textBox.position = Pair(position.first + offset + 105.0, position.second)
        if (!textBox.selected) textBox.text = currentText()
        textBox.color = UIColors.withAlpha(textBox.color, opacity)
        textBox.draw()

        RiseRender.rounded(position.first + offset, position.second + 1.5, 100.0, 2.0, 1.0, UIColors.BACKGROUND.withA(opacity))
        val sliderX = position.first + offset

        if (gui.scale < 0.8) dragging = false

        if (dragging) {
            progress = ((mouseX - sliderX) / 100.0).coerceIn(0.0, 1.0)
            applyValue()
        }

        var steps = timer.elapsedTime.coerceAtMost(100)
        while (steps-- > 0) smoothProgress = (smoothProgress * 29.0 + progress) / 30.0

        val thumbColor = Color(accentColor().red, accentColor().green, accentColor().blue, opacity)
        RiseRender.rounded(sliderX + smoothProgress * 100.0 - 2.5, position.second, 5.0, 5.0, 2.5, thumbColor)
        if (ClickGUI.nlGlowIntensity > 0f && (dragging || hoveredSlider)) {
            GlowUtils.drawGlow(
                (sliderX + smoothProgress * 100.0 - 2.5).toFloat(), position.second.toFloat(), 5f, 5f, 8,
                Color(accentColor().red, accentColor().green, accentColor().blue, (opacity * ClickGUI.nlGlowIntensity * 0.8).toInt().coerceIn(0, 255))
            )
        }
        timer.reset()
    }

    private fun applyValue() {
        val raw = min + (max - min) * progress
        when (value) {
            is IntValue -> value.set(raw.roundToInt())
            is FloatValue -> value.set(formatFloat(raw.toFloat()).toFloat())
            is BlockValue -> value.set(raw.roundToInt())
            else -> {}
        }
        recalc()
    }

    override fun click(mouseX: Int, mouseY: Int, mouseButton: Int): Boolean {
        val pos = position ?: return false
        val label = value.name
        val offset = RiseGuiFonts.small().getStringWidth(label) + 7f
        if (mouseButton == 0 && RiseRender.hovered(pos.first + offset - 5.0, pos.second - 3.5, 110.0, height, mouseX, mouseY)) {
            dragging = true
            return true
        }
        textBox.click(mouseX, mouseY, mouseButton)
        return false
    }

    override fun onMouseUp() { dragging = false }

    override fun key(typedChar: Char, keyCode: Int) {
        if (keyCode == 28) {
            when (value) {
                is IntValue -> value.set(textBox.text.toDoubleOrNull()?.roundToInt() ?: value.get())
                is FloatValue -> value.set(textBox.text.toFloatOrNull() ?: value.get())
                is BlockValue -> value.set(textBox.text.toIntOrNull() ?: value.get())
                else -> {}
            }
            textBox.selected = false
            recalc()
        } else {
            textBox.key(typedChar, keyCode)
        }
    }
}

// ---------- Bounds number (IntRangeValue / FloatRangeValue) ----------

class RiseBoundsValueComponent(value: Value<*>, gui: RiseClickGui) : RiseValueComponent(value, gui) {
    private val timer = RiseStopWatch()
    var draggingLeft = false
    var draggingRight = false
    private var leftProgress = 0.0
    private var rightProgress = 1.0
    private var smoothLeft = 0.0
    private var smoothRight = 1.0
    private var hoveredSlider = false
    private var hoverAnim = 0f
    val textBox = RiseTextBox(RiseGuiFonts.small(), UIColors.SECONDARY_TEXT, RiseTextAlign.LEFT, "", 100f, "1234567890. ")

    private val isFloat = value is FloatRangeValue
    private val min: Double
    private val max: Double

    init {
        when (value) {
            is IntRangeValue -> { min = value.minimum.toDouble(); max = value.maximum.toDouble() }
            is FloatRangeValue -> { min = value.minimum.toDouble(); max = value.maximum.toDouble() }
            else -> { min = 0.0; max = 1.0 }
        }
        recalc()
    }

    fun recalc() {
        when (value) {
            is IntRangeValue -> {
                val v = value.get()
                leftProgress = ((v.first - min) / (max - min).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
                rightProgress = ((v.last - min) / (max - min).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
            }
            is FloatRangeValue -> {
                val v = value.get()
                leftProgress = ((v.start - min) / (max - min).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
                rightProgress = ((v.endInclusive - min) / (max - min).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
            }
            else -> {}
        }
    }

    private fun currentText(): String {
        val a: String
        val b: String
        when (value) {
            is IntRangeValue -> { val v = value.get(); a = v.first.toString(); b = v.last.toString() }
            is FloatRangeValue -> { val v = value.get(); a = formatFloat(v.start); b = formatFloat(v.endInclusive) }
            else -> { a = ""; b = "" }
        }
        return "$a $b"
    }

    override fun draw(position: Pair<Double, Double>, mouseX: Int, mouseY: Int, partialTicks: Float) {
        this.position = position
        val label = value.name + if (value.suffix != null) " (${value.suffix})" else ""
        val font = RiseGuiFonts.small()
        val offset = font.getStringWidth(label) + 7f
        hoveredSlider = RiseRender.hovered(position.first + offset - 5.0, position.second - 3.5, 110.0, height, mouseX, mouseY)
        hoverAnim = if (hoveredSlider) Math.min(1f, hoverAnim + timer.elapsedTime / 200f) else Math.max(0f, hoverAnim - timer.elapsedTime / 200f)

        font.drawString(label, position.first.toFloat(), position.second.toFloat(), UIColors.SECONDARY_TEXT.withA(opacity).rgb)

        textBox.position = Pair(position.first + offset + 105.0, position.second)
        if (!textBox.selected) textBox.text = currentText()
        textBox.color = UIColors.withAlpha(textBox.color, opacity)
        textBox.draw()

        RiseRender.rounded(position.first + offset, position.second + 1.5, 100.0, 2.0, 1.0, UIColors.BACKGROUND.withA(Math.min(opacity, UIColors.BACKGROUND.alpha)))
        val sliderX = position.first + offset

        if (gui.scale < 0.8) { draggingLeft = false; draggingRight = false }

        if (draggingLeft) {
            leftProgress = ((mouseX - sliderX) / 100.0).coerceIn(0.0, 1.0)
            applyLeft()
            if (leftProgress > rightProgress) {
                rightProgress = leftProgress
                applyRight()
            }
        } else if (draggingRight) {
            rightProgress = ((mouseX - sliderX) / 100.0).coerceIn(0.0, 1.0)
            applyRight()
            if (rightProgress < leftProgress) {
                leftProgress = rightProgress
                applyLeft()
            }
        }

        var steps = timer.elapsedTime.coerceAtMost(100)
        while (steps-- > 0) {
            smoothLeft = (smoothLeft * 29.0 + leftProgress) / 30.0
            smoothRight = (smoothRight * 29.0 + rightProgress) / 30.0
        }

        val t0 = sliderX + smoothLeft * 100.0
        val t1 = sliderX + smoothRight * 100.0
        val thumbColor = Color(accentColor().red, accentColor().green, accentColor().blue, opacity)
        if (leftProgress != rightProgress) {
            RiseRender.rounded(t0, position.second + 1.5, t1 - t0, 2.0, 1.0, UIColors.withAlpha(thumbColor, Math.min(70, opacity)))
        }
        RiseRender.rounded(t0 - 2.5, position.second, 5.0, 5.0, 2.5, thumbColor)
        if (leftProgress != rightProgress) {
            RiseRender.rounded(t1 - 2.5, position.second, 5.0, 5.0, 2.5, thumbColor)
        }
        timer.reset()
    }

    private fun applyLeft() {
        val raw = min + (max - min) * leftProgress
        when (value) {
            is IntRangeValue -> value.setFirst(raw.roundToInt())
            is FloatRangeValue -> value.setFirst(formatFloat(raw.toFloat()).toFloat())
            else -> {}
        }
        recalc()
    }

    private fun applyRight() {
        val raw = min + (max - min) * rightProgress
        when (value) {
            is IntRangeValue -> value.setLast(raw.roundToInt())
            is FloatRangeValue -> value.setLast(formatFloat(raw.toFloat()).toFloat())
            else -> {}
        }
        recalc()
    }

    override fun click(mouseX: Int, mouseY: Int, mouseButton: Int): Boolean {
        val pos = position ?: return false
        val offset = RiseGuiFonts.small().getStringWidth(value.name) + 7f
        if (mouseButton == 0 && RiseRender.hovered(pos.first + offset - 5.0, pos.second - 3.5, 110.0, height, mouseX, mouseY)) {
            val dLeft = Math.abs(mouseX - (pos.first + offset + smoothLeft * 100.0))
            val dRight = Math.abs(mouseX - (pos.first + offset + smoothRight * 100.0))
            if (dLeft < dRight) draggingLeft = true else draggingRight = true
            return true
        }
        textBox.click(mouseX, mouseY, mouseButton)
        return false
    }

    override fun onMouseUp() { draggingLeft = false; draggingRight = false }

    override fun key(typedChar: Char, keyCode: Int) {
        if (keyCode != 28) {
            textBox.key(typedChar, keyCode)
            return
        }
        val parts = textBox.text.split(" ")
        if (!textBox.isEmpty() && parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            when (value) {
                is IntRangeValue -> {
                    value.setFirst(parts[0].toDoubleOrNull()?.roundToInt() ?: value.get().first)
                    value.setLast(parts[1].toDoubleOrNull()?.roundToInt() ?: value.get().last)
                }
                is FloatRangeValue -> {
                    value.setFirst(parts[0].toFloatOrNull() ?: value.get().start)
                    value.setLast(parts[1].toFloatOrNull() ?: value.get().endInclusive)
                }
                else -> {}
            }
        } else {
            when (value) {
                is IntRangeValue -> value.set(value.minimum..value.maximum)
                is FloatRangeValue -> value.set(value.minimum..value.maximum)
                else -> {}
            }
        }
        textBox.selected = false
        recalc()
    }
}

// ---------- List / Mode ----------

class RiseListValueComponent(value: Value<*>, gui: RiseClickGui) : RiseValueComponent(value, gui) {
    private val listValue get() = value as ListValue

    override fun draw(position: Pair<Double, Double>, mouseX: Int, mouseY: Int, partialTicks: Float) {
        this.position = position
        val font = RiseGuiFonts.small()
        val s = value.name + ":"
        font.drawString(s, position.first.toFloat(), position.second.toFloat(), UIColors.SECONDARY_TEXT.withA(opacity).rgb)
        font.drawString(
            listValue.get(),
            (position.first + font.getStringWidth(s) + 2.0).toFloat(), position.second.toFloat(),
            UIColors.SECONDARY_TEXT.withA(opacity).rgb
        )
    }

    override fun click(mouseX: Int, mouseY: Int, mouseButton: Int): Boolean {
        val pos = position ?: return false
        val options = listValue.values.toList()
        val current = listValue.get()
        val index = options.indexOfFirst { it.equals(current, ignoreCase = true) }.coerceAtLeast(0)
        if (RiseRender.hovered(pos.first, pos.second - 3.5, gui.w - 70.0, height, mouseX, mouseY)) {
            val next = when (mouseButton) {
                0 -> if (index + 1 >= options.size) options[0] else options[index + 1]
                1 -> if (index - 1 < 0) options[options.size - 1] else options[index - 1]
                else -> return false
            }
            listValue.set(next)
            return true
        }
        return false
    }

    override fun key(typedChar: Char, keyCode: Int) {}
}

// ---------- Font ----------

class RiseFontValueComponent(value: Value<*>, gui: RiseClickGui) : RiseValueComponent(value, gui) {
    private val fontValue get() = value as FontValue

    override fun draw(position: Pair<Double, Double>, mouseX: Int, mouseY: Int, partialTicks: Float) {
        this.position = position
        val font = RiseGuiFonts.small()
        val s = value.name + ":"
        font.drawString(s, position.first.toFloat(), position.second.toFloat(), UIColors.SECONDARY_TEXT.withA(opacity).rgb)
        font.drawString(
            fontValue.displayName,
            (position.first + font.getStringWidth(s) + 2.0).toFloat(), position.second.toFloat(),
            UIColors.SECONDARY_TEXT.withA(opacity).rgb
        )
    }

    override fun click(mouseX: Int, mouseY: Int, mouseButton: Int): Boolean {
        val pos = position ?: return false
        if (RiseRender.hovered(pos.first, pos.second - 3.5, gui.w - 70.0, height, mouseX, mouseY)) {
            if (mouseButton == 0) fontValue.next() else if (mouseButton == 1) fontValue.previous()
            return true
        }
        return false
    }

    override fun key(typedChar: Char, keyCode: Int) {}
}

// ---------- String / Text ----------

class RiseStringValueComponent(value: Value<*>, gui: RiseClickGui) : RiseValueComponent(value, gui) {
    private val textValue get() = value as TextValue
    val textBox = RiseTextBox(RiseGuiFonts.small(), Color.WHITE, RiseTextAlign.LEFT, "", 230.5f)

    init {
        textBox.text = textValue.get()
        textBox.setCursorPosition(textValue.get().length)
    }

    override fun draw(position: Pair<Double, Double>, mouseX: Int, mouseY: Int, partialTicks: Float) {
        this.position = position
        height = 28.0
        val font = RiseGuiFonts.small()
        font.drawString(value.name, position.first.toFloat(), position.second.toFloat(), UIColors.SECONDARY_TEXT.withA(opacity).rgb)
        textBox.color = UIColors.withAlpha(textBox.color, opacity)
        val pos = Pair(position.first, position.second + 14.0)
        this.position = pos
        textBox.position = pos
        textBox.width = 230.5f
        textBox.draw()
        if (textBox.text != textValue.get()) {
            textValue.set(textBox.text, saveImmediately = false)
        }
    }

    override fun click(mouseX: Int, mouseY: Int, mouseButton: Int): Boolean {
        val pos = position ?: return false
        textBox.click(mouseX, mouseY, mouseButton)
        return textBox.selected
    }

    override fun key(typedChar: Char, keyCode: Int) {
        if (position != null) textBox.key(typedChar, keyCode)
    }
}

// ---------- Color ----------

class RiseColorValueComponent(value: Value<*>, gui: RiseClickGui) : RiseValueComponent(value, gui) {
    private val colorValue get() = value as ColorValue
    private var expanded = false
    private var draggingPicker = false
    private var draggingHue = false

    private val panelW = 105.0  // Rise: aza
    private val panelH = 120.0  // Rise: azb (drawn height = azb - 15)
    private val round = 12.0

    /** Blend the component fade opacity into a color. */
    private fun scaled(c: Color) = Color(c.red, c.green, c.blue, (c.alpha * opacity / 255).toInt().coerceIn(0, 255))

    override fun draw(position: Pair<Double, Double>, mouseX: Int, mouseY: Int, partialTicks: Float) {
        this.position = position
        val w = panelW
        val h = panelH
        val font = RiseGuiFonts.small()
        val offset = font.getStringWidth(value.name) + 4f
        font.drawString(value.name, position.first.toFloat(), position.second.toFloat(), UIColors.SECONDARY_TEXT.withA(opacity).rgb)
        RiseRender.rounded(position.first + offset, position.second, 15.0, 7.0, 2.5, scaled(colorValue.get()))
        height = if (expanded) 110.0 else 15.0

        if (gui.overlay !== this && expanded) gui.overlay = this
        if (!expanded && gui.overlay === this) gui.overlay = null

        if (!expanded) return

        val px = position.first + 0.5 + 10.0 + offset + 8.0
        val py = position.second + 0.5
        val areaH = h * 0.55 // 66 — Rise: azb * 0.55

        GlowUtils.drawGlow(px.toFloat(), py.toFloat(), w.toFloat(), (h - 15f).toFloat(), 12, Color(0, 0, 0, (60 * opacity / 255)))
        RiseRender.rounded(px - 0.5, py, w, h - 15.0, 9.0, scaled(UIColors.SECONDARY))
        RiseRender.rounded(px, py, w - 1.0, h - 1.0 - 15.0, 9.0, scaled(UIColors.BACKGROUND))

        // saturation/brightness area: white->hue horizontal gradient + black vertical overlay
        val hueColor = Color.getHSBColor(colorValue.hueSliderY, 1f, 1f)
        RiseRender.horizontalGradientRounded(
            px.toFloat(), py.toFloat(), (w - 1.0).toFloat(), areaH.toFloat(), 7f,
            Color.WHITE, hueColor
        )
        RiseRender.verticalGradient(
            (px - 0.5).toFloat(), py.toFloat(), w.toFloat(), (areaH + 0.5).toFloat(),
            Color(0, 0, 0, 0), Color.BLACK
        )

        // hue bar (Rise: d5 = d1 + d3 + d2 - 5, bar height = round - 5)
        val barX = px + 8.5
        val barY = py + 8.5 + areaH - 5.0
        val barW = w - 8.5 * 2.0
        RiseRender.rainbowBar(barX.toFloat(), (barY + 2.5).toFloat(), barW.toFloat(), (round - 5f).toFloat(), 2f)

        // live slider updates. NOTE: ColorValue stores hue in hueSliderY (0-1) and
        // (saturation, 1-brightness) in colorPickerPos (0-1) — never pixel offsets.
        if (draggingPicker) {
            colorValue.colorPickerPos.x = RiseRender.clamp(((mouseX - px) / (w - 1.0)).toFloat(), 0f, 1f)
            colorValue.colorPickerPos.y = RiseRender.clamp(((mouseY - py) / areaH).toFloat(), 0f, 1f)
            applyFromSliders()
        } else if (draggingHue) {
            colorValue.hueSliderY = RiseRender.clamp(((mouseX - barX) / barW).toFloat(), 0f, 1f)
            applyFromSliders()
        }

        // hue thumb (Rise: d4 + hue*barW - round/2 + 0.5, d5 + 0.5, round-1, radius round/3+1)
        val hue = colorValue.hueSliderY.coerceIn(0f, 1f)
        val thumbSize = round - 1.0
        val thumbX = barX + hue * barW - round / 2.0 + 0.5
        val thumbY = barY + 0.5
        val thumbColor = Color.getHSBColor(hue, 1f, 1f)
        RiseRender.rounded(thumbX - 1.0, thumbY - 1.0, thumbSize + 2.0, thumbSize + 2.0, round / 3.0 + 1.0, Color.BLACK)
        RiseRender.rounded(thumbX, thumbY, thumbSize, thumbSize, round / 3.0 + 1.0, scaled(thumbColor))

        // saturation/brightness cursor (Rise: azc is in pixels, stored here normalized)
        if (colorValue.colorPickerPos.x != -1f && colorValue.colorPickerPos.y != -1f) {
            val cx = px - 1.0 + colorValue.colorPickerPos.x * (w - 1.0) - 2.5
            val cy = py - 1.0 + colorValue.colorPickerPos.y * areaH - 2.5
            RiseRender.rounded(cx, cy, 7.0, 7.0, 3.5, Color.WHITE)
            RiseRender.rounded(cx + 0.5, cy + 0.5, 6.0, 6.0, 3.0, Color.BLACK)
            RiseRender.rounded(cx + 1.0, cy + 1.0, 5.0, 5.0, 2.5, scaled(colorValue.get()))
        }

        // preview + RGB text + hex (Rise: preview at d1 + d2 + d3 + d3 + round - 11)
        val c = colorValue.get()
        val px2 = px + 8.5
        val py2 = py + areaH + 8.5 + 8.5 + round - 11.0
        RiseRender.rounded(px2, py2, 15.0, 15.5, 3.5, scaled(c))
        val tx = px + 8.5 * 2.0 + 15.0
        RiseGuiFonts.small().drawString(c.red.toString(), (tx + 8.5).toFloat(), py2.toFloat(), UIColors.SECONDARY_TEXT.withA(opacity).rgb)
        RiseGuiFonts.small().drawString(c.green.toString(), (tx + 30.0).toFloat(), py2.toFloat(), UIColors.SECONDARY_TEXT.withA(opacity).rgb)
        RiseGuiFonts.small().drawString(c.blue.toString(), (tx + 8.5 * 6.0).toFloat(), py2.toFloat(), UIColors.SECONDARY_TEXT.withA(opacity).rgb)
        RiseGuiFonts.small().drawString(
            String.format("#%02X%02X%02X", c.red, c.green, c.blue), tx.toFloat(), (py2 + 13.0).toFloat(), scaled(Color(150, 155, 160)).rgb
        )
    }

    private fun applyFromSliders() {
        val sat = colorValue.colorPickerPos.x
        val bright = 1f - colorValue.colorPickerPos.y
        val rgb = Color.HSBtoRGB(colorValue.hueSliderY, sat, bright.coerceIn(0f, 1f))
        val old = colorValue.get()
        colorValue.set(Color(rgb, true).let { Color(it.red, it.green, it.blue, old.alpha) })
    }

    override fun click(mouseX: Int, mouseY: Int, mouseButton: Int): Boolean {
        val pos = position ?: return false
        val offset = RiseGuiFonts.small().getStringWidth(value.name) + 4f
        val px = pos.first + 0.5 + 10.0 + offset + 8.0
        val py = pos.second + 0.5
        val areaH = panelH * 0.55
        val barX = px + 8.5
        val barY = py + 8.5 + areaH - 5.0
        val barW = panelW - 8.5 * 2.0

        draggingPicker = expanded && RiseRender.hovered(px, py, panelW, areaH, mouseX, mouseY)
        draggingHue = expanded && RiseRender.hovered(barX - 2.0, barY, barW + 4.0, 18.0, mouseX, mouseY)

        if (mouseButton == 0) {
            // copy buttons over the RGB / HEX text rows
            val tx = px + 8.5 * 2.0 + 15.0
            val ty = py + areaH + 8.5 + 8.5 + round - 11.0
            val copyX = tx - 2.0
            if (RiseRender.hovered(copyX, ty - 2.0, 60.0, 10.0, mouseX, mouseY)) {
                val c = colorValue.get()
                net.minecraft.client.gui.GuiScreen.setClipboardString("${c.red}, ${c.green}, ${c.blue}")
            } else if (RiseRender.hovered(copyX, ty + 11.0, 60.0, 10.0, mouseX, mouseY)) {
                val c = colorValue.get()
                net.minecraft.client.gui.GuiScreen.setClipboardString(String.format("#%02X%02X%02X", c.red, c.green, c.blue))
            }
        }

        // Rise: keep expanded while clicking inside the picker, toggle via the value row
        expanded = (gui.overlay == null || expanded) && (
            draggingPicker || draggingHue ||
                RiseRender.hovered(px, py + areaH, panelW, 52.0, mouseX, mouseY) ||
                (!expanded && RiseRender.hovered(pos.first, pos.second - 3.5, gui.w - 70.0, height, mouseX, mouseY))
            )
        return false
    }

    override fun onMouseUp() { draggingPicker = false; draggingHue = false }

    override fun key(typedChar: Char, keyCode: Int) {}
}

// ============================== Factory ==============================

fun createValueComponent(value: Value<*>, gui: RiseClickGui): RiseValueComponent? = when (value) {
    is BoolValue -> RiseBooleanValueComponent(value, gui)
    is IntValue -> RiseNumberValueComponent(value, gui)
    is FloatValue -> RiseNumberValueComponent(value, gui)
    is BlockValue -> RiseNumberValueComponent(value, gui)
    is IntRangeValue -> RiseBoundsValueComponent(value, gui)
    is FloatRangeValue -> RiseBoundsValueComponent(value, gui)
    is ListValue -> RiseListValueComponent(value, gui)
    is FontValue -> RiseFontValueComponent(value, gui)
    is TextValue -> RiseStringValueComponent(value, gui)
    is ColorValue -> RiseColorValueComponent(value, gui)
    else -> null
}

// ============================== ModuleComponent ==============================

class RiseModuleComponent(val module: Module, private val gui: RiseClickGui) {
    var scale = Pair(gui.moduleDefaultW, gui.moduleDefaultH)
    var expanded = false
    var valueList = ArrayList<RiseValueComponent>()
    var position: Pair<Double, Double>? = null
    private val hoverTimer = RiseStopWatch()
    private val hoverAnim = RiseAnimation(RiseEasing.LINEAR, 50)
    private val expandAnim = RiseAnimation(RiseEasing.LINEAR, 200)
    private val valueOpacityAnim = RiseAnimation(RiseEasing.LINEAR, 5000)
    private var mouseDown = false

    init {
        expandAnim.setValue(gui.moduleDefaultH.toDouble())
        valueOpacityAnim.setValue(0.0)
        module.values.forEach { value ->
            val component = createValueComponent(value, gui)
            if (component != null) valueList.add(component)
        }
    }

    fun draw(position: Pair<Double, Double>, mouseX: Int, mouseY: Int, partialTicks: Float) {
        this.position = position
        var h = 38.0
        val inView = !(position.second + scale.second < gui.y) && !(position.second > gui.y + gui.h)
        if (inView) {
            RiseRender.rounded(position.first, position.second, scale.first.toDouble(), scale.second.toDouble(), 6.0, UIColors.OVERLAY)
            val textColor = UIColors.TEXT.withA(if (module.state) 255 else 200)
            val hovered = RiseRender.hovered(position.first, position.second, scale.first.toDouble(), scale.second.toDouble(), mouseX, mouseY)
            hoverAnim.animate(if (hovered) (if (mouseDown) 35.0 else 20.0) else 0.0)
            RiseRender.rounded(position.first, position.second, scale.first.toDouble(), scale.second.toDouble(), 6.0, UIColors.withAlpha(Color.BLACK, hoverAnim.value.toInt()))

            val font = RiseGuiFonts.module()
            if (gui.activeScreen is RiseSearchScreen) {
                RiseGuiFonts.small().drawString(
                    "(${module.category.displayName})",
                    (position.first + font.getStringWidth(module.name) + 10.0).toFloat(),
                    (position.second + 10f).toFloat(),
                    UIColors.withAlpha(textColor, 64).rgb
                )
            }

            font.drawString(
                module.name,
                (position.first + 6f).toFloat(),
                (position.second + 8f).toFloat(),
                if (module.state) accentColor().rgb else textColor.rgb
            )
            RiseGuiFonts.small().drawString(
                module.description,
                (position.first + 6f).toFloat(), (position.second + 25f).toFloat(),
                UIColors.withAlpha(textColor, 70).rgb
            )
            scale = Pair(gui.moduleDefaultW, h.toFloat())
        }

        if (!expandAnim.isFinished() || expanded) {
            for (component in valueList) {
                if (!component.visible()) continue
                // Position must be assigned even when the draw below is skipped: opacity
                // depends on component.position, so gating the first draw on opacity would
                // leave position null forever and the settings would never appear.
                val compPos = Pair(position.first + 6.0, position.second + h + 1.0)
                component.position = compPos
                component.opacity = if (component.position!!.second < position.second + expandAnim.value + 15.0) valueOpacityAnim.value.toInt() else 0
                // Alpha < 4 is snapped to fully opaque by the font renderer, so a zero-opacity
                // draw would make every value label flash on screen right after the GUI opens
                // (expandAnim is "unfinished" for the first few frames). Skip drawing instead —
                // heights are still accumulated below so the layout stays identical to Rise.
                if (inView && component.opacity >= 4) {
                    component.draw(compPos, mouseX, mouseY, partialTicks)
                }
                h += component.height
            }
            h--
        }

        expandAnim.setDuration((h * 3).toLong().coerceAtMost(450))
        expandAnim.setEasing(RiseEasing.EASE_OUT_EXPO)
        expandAnim.animate(if (expanded) h else 38.0)
        scale = Pair(scale.first, expandAnim.value.toFloat())
        valueOpacityAnim.setDuration(if (expanded) expandAnim.getDuration() / 2 else expandAnim.getDuration() / 3)
        valueOpacityAnim.animate(if (expanded) 255.0 else 0.0)
    }

    fun key(typedChar: Char, keyCode: Int) {
        val pos = position ?: return
        val inView = !(pos.second + scale.second < gui.y) && !(pos.second > gui.y + gui.h)
        if (inView && expanded) {
            valueList.forEach { it.key(typedChar, keyCode) }
        }
    }

    fun click(mouseX: Int, mouseY: Int, mouseButton: Int) {
        val pos = position ?: return
        val inView = !(pos.second + scale.second < gui.y) && !(pos.second > gui.y + gui.h)
        if (!inView) return
        val left = mouseButton == 0
        val right = mouseButton == 1

        if (RiseRender.hovered(pos.first, pos.second, scale.first.toDouble(), gui.moduleDefaultH - 3.0, mouseX, mouseY) && gui.overlay == null) {
            mouseDown = true
            val totalValueHeight = valueList.sumOf { it.height }
            if (left) {
                module.toggle()
            } else if (right && module.values.isNotEmpty() && totalValueHeight != 0.0) {
                expanded = !expanded
                valueList.forEach {
                    when (it) {
                        is RiseBoundsValueComponent -> { it.draggingLeft = false; it.draggingRight = false }
                        is RiseNumberValueComponent -> it.dragging = false
                    }
                }
            }
        }

        if (expanded) {
            for (component in valueList) {
                if (!component.visible()) continue
                if (component.click(mouseX, mouseY, mouseButton)) break
            }
        }
    }

    fun onMouseUp() {
        mouseDown = false
        if (expanded) valueList.forEach { it.onMouseUp() }
    }

    /** Rise ModuleComponent.ci() — release notifications for value components. */
    fun onRenderRelease() {
        val pos = position ?: return
        val inView = !(pos.second + scale.second < gui.y) && !(pos.second > gui.y + gui.h)
        if (inView && (!expandAnim.isFinished() || expanded)) {
            valueList.forEach { if (it.visible()) it.onMouseUp() }
        }
    }
}

// ============================== Fonts / formatting ==============================

object RiseGuiFonts {
    fun small() = net.airplus.ui.font.Fonts.fontRise35
    fun module() = net.airplus.ui.font.Fonts.fontRise40
    fun title() = net.airplus.ui.font.Fonts.fontRise50
    fun icon() = net.airplus.ui.font.Fonts.fontSessIcon17
}

internal fun formatFloat(v: Float): String {
    return if (Math.abs(v - v.roundToInt()) < 1e-4f) v.roundToInt().toString() else "%.2f".format(v)
}
