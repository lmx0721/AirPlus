/*
 * AirClient Hacked Client
 * A free open source mixin-based injection hacked client built on Liquidbounce legacy codebase.
 * https://github.com/lmx0721/AirClient
 */
package net.airplus.ui.client.clickgui.rise

import net.airplus.AirPlus
import net.airplus.config.BoolValue
import net.airplus.config.BlockValue
import net.airplus.config.ColorValue
import net.airplus.config.FloatRangeValue
import net.airplus.config.FloatValue
import net.airplus.config.FontValue
import net.airplus.config.IntRangeValue
import net.airplus.config.IntValue
import net.airplus.config.ListValue
import net.airplus.config.TextValue
import net.airplus.config.Value
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.modules.client.Client
import net.airplus.features.module.modules.render.ClickGUI
import net.airplus.file.FileManager.modulesConfig
import net.airplus.file.FileManager.saveConfig
import net.airplus.file.FileManager.valuesConfig
import net.airplus.ui.font.Fonts
import net.airplus.utils.GlowUtils
import net.airplus.utils.render.HudBlur
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.render.RoundedUtil
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.ScaledResolution
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.GL11
import java.awt.Color
import java.io.IOException
import kotlin.math.abs
import kotlin.math.roundToInt

class RiseClickGui : GuiScreen() {

    private var x = -1f
    private var y = -1f
    private val w = 400f
    private val h = 300f
    private val sidebarW = 100f
    private val moduleW = 283f
    private val moduleBaseH = 38f

    private var dragging = false
    private var dragX = 0f
    private var dragY = 0f
    private var selectedCategory = Category.COMBAT
    private var showSettings = false // When true, content shows settings instead of modules
    private var moduleScroll = 0f
    private var animModuleScroll = 0f
    private var search = ""
    private var searchFocused = false
    private val expandedModules = HashSet<Module>()
    private var focusedText: TextValue? = null
    private var textBuffer = ""
    private var bindingModule: Module? = null
    private var draggingNumber: Value<*>? = null
    private var sidebarDraggingSlider: String? = null // "glow" | "opacity" | "blur"
    private var valuesDirty = false
    private var modulesDirty = false
    private var openProgress = 0f
    private var sidebarSelectorY = 0f
    private val expansionAnimations = HashMap<Module, Float>()

    // Theme color transition animation (smooth color crossfade when switching themes)
    private var displayedAccent: Color = lastDisplayedAccent ?: ClickGUI.nlAccentColor
    private var displayedBg: Color = lastDisplayedBg ?: ClickGUI.nlThemeBgColor

    private val background get() = Color(displayedBg.red, displayedBg.green, displayedBg.blue, (255f * ClickGUI.nlBgOpacity).toInt().coerceIn(0, 255))
    private val sidebar get() = Color(displayedBg.red - 5, displayedBg.green - 6, displayedBg.blue - 8, (255f * ClickGUI.nlBgOpacity).toInt().coerceIn(0, 255))
    private val overlay = Color(0, 0, 0, 50)
    private val overlayHover = Color(255, 255, 255, 20)
    private val text = Color(235, 238, 245)
    private val muted = Color(255, 255, 255, 130)
    private val accent get() = displayedAccent

    // Glow helpers - 使用 sqrt 曲线（与 Neverlose 一致），让低百分比时也能看到明显效果
    private fun glowAlpha(baseAlpha: Int): Int = (baseAlpha * kotlin.math.sqrt(ClickGUI.nlGlowIntensity.toDouble()).toFloat()).roundToInt().coerceIn(0, 255)
    private fun glowRadius(baseRadius: Int): Int = (baseRadius * (0.3f + 0.7f * ClickGUI.nlGlowIntensity)).roundToInt().coerceAtLeast(0)

    // Rise settings (using NL settings as shared config)
    private val riseBgOpacity get() = ClickGUI.nlBgOpacity
    private val riseGlowIntensity get() = ClickGUI.nlGlowIntensity

    private fun lerpColor(a: Color, b: Color, t: Float): Color {
        val r = (a.red + (b.red - a.red) * t).roundToInt().coerceIn(0, 255)
        val g = (a.green + (b.green - a.green) * t).roundToInt().coerceIn(0, 255)
        val bl = (a.blue + (b.blue - a.blue) * t).roundToInt().coerceIn(0, 255)
        return Color(r, g, bl)
    }

    override fun initGui() {
        Keyboard.enableRepeatEvents(true)
        // Restore persisted state
        if (lastX >= 0f && lastY >= 0f && lastX + w <= width && lastY + h <= height) {
            x = lastX; y = lastY
        } else if (x < 0f || y < 0f || x + w > width || y + h > height) {
            x = width / 2f - w / 2f
            y = height / 2f - h / 2f
        }
        lastCategory?.let { selectedCategory = it }
        search = lastSearch
        moduleScroll = lastScroll
        animModuleScroll = lastScroll
        if (search.isNotBlank()) searchFocused = true
        openProgress = 0f
        sidebarSelectorY = selectedCategoryY()
        
        // 恢复展开的模块状态
        expandedModules.clear()
        expansionAnimations.clear()
        if (lastExpandedModules.isNotEmpty()) {
            AirPlus.moduleManager.forEach { module ->
                if (module.name in lastExpandedModules) {
                    expandedModules.add(module)
                }
            }
        }
        
        super.initGui()
    }

    override fun onGuiClosed() {
        Keyboard.enableRepeatEvents(false)
        flushConfigs()
        // Persist state for next session
        lastX = x; lastY = y
        lastCategory = selectedCategory
        lastScroll = moduleScroll
        lastSearch = search
        // 保存展开的模块状态（使用模块名称）
        lastExpandedModules = HashSet(expandedModules.map { it.name })
        // 保存主题颜色用于下次打开时的过渡动画
        lastDisplayedAccent = displayedAccent
        lastDisplayedBg = displayedBg
        super.onGuiClosed()
    }

    override fun doesGuiPauseGame() = false

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        if (dragging) {
            x = mouseX + dragX
            y = mouseY + dragY
        }
        if (!Mouse.isButtonDown(0)) {
            draggingNumber = null
            sidebarDraggingSlider = null
        }

        // Handle sidebar slider dragging
        if (sidebarDraggingSlider != null && Mouse.isButtonDown(0)) {
            handleSidebarSliderDrag(mouseX)
        }

        handleWheel(mouseX, mouseY)
        openProgress = animate(openProgress, 1f, 0.18f)
        val scale = 0.92f + 0.08f * easeOut(openProgress)

        // Theme color transition (smooth crossfade when switching themes)
        val targetAccent = ClickGUI.nlAccentColor
        val targetBg = ClickGUI.nlThemeBgColor
        displayedAccent = lerpColor(displayedAccent, targetAccent, 0.01f)
        displayedBg = lerpColor(displayedBg, targetBg, 0.01f)

        // Blur background - 必须在 GL11 变换之前调用，因为模糊使用屏幕坐标
        // （类型/强度由 Client 模块的全局 Blur 设置统一控制）
        if (ClickGUI.nlBlur) {
            HudBlur.blur(x, y, x + w, y + h, 12f)
        }

        GL11.glPushMatrix()
        GL11.glTranslatef(x + w / 2f, y + h / 2f, 0f)
        GL11.glScalef(scale, scale, 1f)
        GL11.glTranslatef(-(x + w / 2f), -(y + h / 2f), 0f)
        drawShadow()
        RoundedUtil.drawRound(x, y, w, h, 12f, background)
        startScissor(x + 1f, y + 1f, w - 2f, h - 2f)
        drawSidebar(mouseX, mouseY)
        drawContent(mouseX, mouseY)
        endScissor()
        GL11.glPopMatrix()

        super.drawScreen(mouseX, mouseY, partialTicks)
    }

    @Throws(IOException::class)
    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        if (bindingModule != null) {
            bindingModule = null
            return
        }

        if (isHovered(x, y, w, 16f, mouseX, mouseY) && mouseButton == 0 && focusedText == null) {
            dragging = true
            dragX = x - mouseX
            dragY = y - mouseY
            return
        }

        if (!isHovered(x, y, w, h, mouseX, mouseY)) {
            focusedText = null
            searchFocused = false
            return
        }

        clickSidebar(mouseX, mouseY, mouseButton)
        if (showSettings) {
            val contentX = x + sidebarW
            val contentW = w - sidebarW
            clickSettings(contentX, contentW, mouseX, mouseY, mouseButton)
        } else {
            clickSearch(mouseX, mouseY, mouseButton)
            clickModules(mouseX, mouseY, mouseButton)
        }

        super.mouseClicked(mouseX, mouseY, mouseButton)
    }

    override fun mouseReleased(mouseX: Int, mouseY: Int, state: Int) {
        dragging = false
        draggingNumber = null
        flushConfigs()
        super.mouseReleased(mouseX, mouseY, state)
    }

    @Throws(IOException::class)
    override fun keyTyped(typedChar: Char, keyCode: Int) {
        bindingModule?.let { module ->
            module.keyBind = if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_DELETE) Keyboard.KEY_NONE else keyCode
            bindingModule = null
            modulesDirty = true
            saveConfig(modulesConfig)
            return
        }

        focusedText?.let { value ->
            handleTextInput(value, typedChar, keyCode)
            return
        }

        if (searchFocused) {
            when (keyCode) {
                Keyboard.KEY_ESCAPE -> {
                    searchFocused = false
                    return
                }
                Keyboard.KEY_BACK -> if (search.isNotEmpty()) {
                    search = search.dropLast(1)
                    moduleScroll = 0f
                    return
                }
                Keyboard.KEY_DELETE -> {
                    search = ""
                    moduleScroll = 0f
                    return
                }
            }
            if (!Character.isISOControl(typedChar)) {
                search += typedChar
                moduleScroll = 0f
                return
            }
        }

        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null)
            return
        }

        super.keyTyped(typedChar, keyCode)
    }

    private fun drawShadow() {
        for (i in 0..5) {
            RoundedUtil.drawRound(x - i, y - i, w + i * 2f, h + i * 2f, 12f + i, Color(0, 0, 0, 18 - i * 2))
        }
    }

    private fun drawSidebar(mouseX: Int, mouseY: Int) {
        RoundedUtil.drawRound(x, y, sidebarW, h, 12f, sidebar)
        // Blur 开启时不画装饰圆圈，避免光圈效果
        if (!ClickGUI.nlBlur) {
            for (i in 0..7) {
                val radius = i * 42f
                drawCircle(x + sidebarW - radius / 2f, y + h / 2f - radius / 2f, radius, Color(accent.red, accent.green, accent.blue, 10))
            }
        }

        // 标题放大 1.3 倍渲染，腾出空间通过下方 cy 起始位置调整
        GL11.glPushMatrix()
        GL11.glTranslatef(x + 10f, y + 12f, 0f)
        GL11.glScalef(1.3f, 1.3f, 1f)
        Fonts.fontRise50.drawString("Rise", 0f, 0f, text.rgb)
        GL11.glPopMatrix()

        sidebarSelectorY = animate(sidebarSelectorY, selectedCategoryY(), 0.22f)
        val selectedLabel = if (showSettings) "Settings" else selectedCategory.displayName
        val selectedPillW = (Fonts.fontRise35.getStringWidth(selectedLabel) + 28f).coerceAtMost(sidebarW - 12f)
        RoundedUtil.drawRound(x + 6f, sidebarSelectorY - 5f, selectedPillW, 20f, 5f, Color(accent.red, accent.green, accent.blue, 105))

        var cy = y + 58f
        Category.entries.forEach { category ->
            val selected = !showSettings && category == selectedCategory
            val hovered = isHovered(x + 6f, cy - 2f, sidebarW - 12f, 22f, mouseX, mouseY)
            val label = category.displayName
            val icon = category.riseIcon()
            if (hovered && !selected) {
                val pillW = Fonts.fontRise35.getStringWidth(label) + 28f
                RoundedUtil.drawRound(x + 6f, cy - 5f, pillW.coerceAtMost(sidebarW - 12f), 20f, 5f, Color(255, 255, 255, 18))
            }
            Fonts.fontSessIcon48.drawString(icon, x + 10f, cy + 1f, if (selected) Color.WHITE.rgb else Color(255, 255, 255, 200).rgb)
            Fonts.fontRise35.drawString(label, x + 28f, cy + 1f, if (selected) Color.WHITE.rgb else Color(255, 255, 255, 200).rgb)
            cy += 23f
        }

        // Divider
        cy += 4f
        RenderUtils.drawRect(x + 8f, cy, x + sidebarW - 8f, cy + 1f, Color(255, 255, 255, 25).rgb)
        cy += 6f

        // Settings "category" button
        val settingsSelected = showSettings
        val settingsHovered = isHovered(x + 6f, cy - 2f, sidebarW - 12f, 22f, mouseX, mouseY)
        if (settingsHovered && !settingsSelected) {
            RoundedUtil.drawRound(x + 6f, cy - 5f, (Fonts.fontRise35.getStringWidth("Settings") + 28f).coerceAtMost(sidebarW - 12f), 20f, 5f, Color(255, 255, 255, 18))
        }
        Fonts.fontSessIcon48.drawString("K", x + 10f, cy + 1f, if (settingsSelected) Color.WHITE.rgb else Color(255, 255, 255, 200).rgb)
        Fonts.fontRise35.drawString("Settings", x + 28f, cy + 1f, if (settingsSelected) Color.WHITE.rgb else Color(255, 255, 255, 200).rgb)
    }

    private fun drawContent(mouseX: Int, mouseY: Int) {
        val contentX = x + sidebarW
        val contentW = w - sidebarW

        if (showSettings) {
            drawSettingsContent(contentX, contentW, mouseX, mouseY)
            return
        }

        val searchX = contentX + 13f
        val searchY = y + 11f
        RoundedUtil.drawRound(searchX, searchY, contentW - 27f, 22f, 6f, Color(12, 15, 23, 220))
        Fonts.fontRise35.drawString(if (search.isBlank()) "Search" else search, searchX + 8f, searchY + 7f, if (search.isBlank()) muted.rgb else text.rgb)
        if (searchFocused) {
            RenderUtils.drawRect(searchX + 7f, searchY + 20f, searchX + contentW - 35f, searchY + 21f, accent.rgb)
        }

        startScissor(contentX + 7f, y + 42f, contentW - 13f, h - 49f)
        animModuleScroll = animate(animModuleScroll, moduleScroll, 0.18f)
        var my = y + 48f + animModuleScroll
        filteredModules().forEach { module ->
            val cardH = moduleHeight(module)
            drawModule(contentX + 8f, my, module, mouseX, mouseY)
            my += cardH + 7f
        }
        endScissor()

        drawScrollbar(filteredHeight(), contentX + contentW - 5f, y + 42f, h - 50f)
    }

    private fun drawSettingsContent(contentX: Float, contentW: Float, mouseX: Int, mouseY: Int) {
        val sx = contentX + 13f
        var sy = y + 16f

        Fonts.fontRise40.drawString("Client Settings", sx, sy, text.rgb)
        sy += 28f

        val sliderW = contentW - 27f

        // Theme - color dots only (no text labels)
        Fonts.fontRise35.drawString("Theme", sx, sy, muted.rgb)
        sy += 18f
        val themeNames = arrayOf("Ocean", "Sunset", "Forest", "Royal", "Crimson", "Mono")
        val themeColors = arrayOf(Color(74, 144, 217), Color(255, 130, 50), Color(80, 200, 120), Color(130, 100, 220), Color(220, 60, 60), Color(180, 180, 190))
        val dotSize = 14f
        val dotSpacing = 24f
        var dotX = sx
        themeNames.forEachIndexed { i, name ->
            val color = themeColors[i]
            val isSelected = ClickGUI.nlTheme == name
            val dotHovered = isHovered(dotX - 2f, sy + 2f, dotSize + 4f, dotSize + 4f, mouseX, mouseY)
            RoundedUtil.drawRound(dotX, sy + 4f, dotSize, dotSize, 7f, if (isSelected) color else if (dotHovered) Color(color.red, color.green, color.blue, 120) else Color(color.red, color.green, color.blue, 60))
            if (isSelected && riseGlowIntensity > 0f) {
                GlowUtils.drawGlow(dotX - 2f, sy + 2f, dotSize + 4f, dotSize + 4f, glowRadius(16), Color(color.red, color.green, color.blue, glowAlpha(180)))
            }
            dotX += dotSpacing
        }
        sy += 28f

        // Glow Intensity slider (range 0..2, normalized progress)
        Fonts.fontRise35.drawString("Glow Intensity", sx, sy, muted.rgb)
        val glowVal = "%.0f%%".format(riseGlowIntensity * 100)
        Fonts.fontRise35.drawString(glowVal, sx + contentW - 40f, sy, accent.rgb)
        sy += 18f
        val glowProgress = riseGlowIntensity.coerceIn(0f, 1f)
        RoundedUtil.drawRound(sx, sy, sliderW, 4f, 2f, Color(48, 54, 68, 255))
        RoundedUtil.drawRound(sx, sy, sliderW * glowProgress, 4f, 2f, accent)
        val glowThumbX = sx + sliderW * glowProgress - 3f
        RoundedUtil.drawRound(glowThumbX, sy - 3f, 10f, 10f, 5f, Color.WHITE)
        if (riseGlowIntensity > 0f) GlowUtils.drawGlow(glowThumbX - 1f, sy - 4f, 12f, 12f, glowRadius(14), Color(accent.red, accent.green, accent.blue, glowAlpha(160)))
        sy += 22f

        // BG Opacity slider (range 0.1..1.0, normalized progress)
        Fonts.fontRise35.drawString("Background Opacity", sx, sy, muted.rgb)
        val opVal = "%.0f%%".format(riseBgOpacity * 100)
        Fonts.fontRise35.drawString(opVal, sx + contentW - 40f, sy, accent.rgb)
        sy += 18f
        val opProgress = ((riseBgOpacity - 0.1f) / 0.9f).coerceIn(0f, 1f)
        RoundedUtil.drawRound(sx, sy, sliderW, 4f, 2f, Color(48, 54, 68, 255))
        RoundedUtil.drawRound(sx, sy, sliderW * opProgress, 4f, 2f, accent)
        val opThumbX = sx + sliderW * opProgress - 3f
        RoundedUtil.drawRound(opThumbX, sy - 3f, 10f, 10f, 5f, Color.WHITE)
        sy += 22f

        // Blur toggle - boolean value style (small circle)
        Fonts.fontRise35.drawString("Blur", sx, sy, muted.rgb)
        val blurEnabled = ClickGUI.nlBlur
        val toggleX = sx + sliderW - 10f
        val toggleY = sy - 2f
        RoundedUtil.drawRound(toggleX, toggleY, 10f, 10f, 5f, if (blurEnabled) accent else Color(47, 53, 68, 255))
        if (blurEnabled && riseGlowIntensity > 0f) {
            GlowUtils.drawGlow(toggleX, toggleY, 10f, 10f, glowRadius(16), Color(accent.red, accent.green, accent.blue, glowAlpha(200)))
        }
        sy += 22f

        // Blur Strength slider (only if blur enabled; range 1..50, normalized progress)
        if (blurEnabled) {
            Fonts.fontRise35.drawString("Blur Strength", sx, sy, muted.rgb)
            val blurStr = Client.blurStrength
            val blurVal = "%.0f".format(blurStr)
            Fonts.fontRise35.drawString(blurVal, sx + contentW - 40f, sy, accent.rgb)
            sy += 18f
            val blurProgress = ((blurStr - 1f) / 49f).coerceIn(0f, 1f)
            RoundedUtil.drawRound(sx, sy, sliderW, 4f, 2f, Color(48, 54, 68, 255))
            RoundedUtil.drawRound(sx, sy, sliderW * blurProgress, 4f, 2f, accent)
            val blurThumbX = sx + sliderW * blurProgress - 3f
            RoundedUtil.drawRound(blurThumbX, sy - 3f, 10f, 10f, 5f, Color.WHITE)
        }
    }

    private fun drawModule(mx: Float, my: Float, module: Module, mouseX: Int, mouseY: Int) {
        val expanded = module in expandedModules
        val expansion = updateExpansion(module)
        val cardH = moduleHeight(module)
        if (my + cardH < y + 42f || my > y + h - 7f) return

        val hovered = isHovered(mx, my, moduleW, moduleBaseH, mouseX, mouseY)
        RoundedUtil.drawRound(mx, my, moduleW, cardH, 6f, if (hovered) overlayHover else overlay)
        Fonts.fontRise40.drawString(if (bindingModule == module) "Press key..." else module.name, mx + 8f, my + 8f, if (module.state) accent.rgb else text.rgb)
        Fonts.fontRise35.drawString(module.description, mx + 8f, my + 25f, Color(255, 255, 255, 70).rgb)
        if (module.values.any { it.shouldRender() }) {
            val symbol = if (expanded) "-" else "+"
            Fonts.fontRise35.drawString(symbol, mx + moduleW - 15f, my + 13f + (1f - expansion) * 2f, muted.rgb)
        }

        if (expansion <= 0.02f) return

        var vy = my + moduleBaseH + 2f
        module.values.filter { it.shouldRender() }.forEach { value ->
            if (vy + valueHeight(value) <= my + cardH) {
                // Slide-in animation: offset values based on expansion progress
                val offsetY = (1f - expansion) * 8f
                val alpha = (expansion * 255f).toInt().coerceIn(0, 255)
                drawValue(mx + 8f, vy + offsetY, moduleW - 16f, value, mouseX, mouseY, alpha)
            }
            vy += valueHeight(value)
        }
    }

    private fun drawValue(vx: Float, vy: Float, vw: Float, value: Value<*>, mouseX: Int, mouseY: Int, alpha: Int = 255) {
        val labelAlpha = (130 * alpha / 255).coerceIn(0, 255)
        val textAlpha = (235 * alpha / 255).coerceIn(0, 255)
        val labelColor = Color(255, 255, 255, labelAlpha)
        val textColor = Color(235, 238, 245, textAlpha)
        val accentWithAlpha = Color(accent.red, accent.green, accent.blue, alpha)
        val label = value.name
        when (value) {
            is BoolValue -> {
                Fonts.fontRise35.drawString(label, vx, vy + 5f, labelColor.rgb)
                val toggleX = vx + vw - 18f; val toggleY = vy + 5.5f
                RoundedUtil.drawRound(toggleX, toggleY, 10f, 10f, 5f, if (value.get()) accentWithAlpha else Color(47, 53, 68, alpha))
                // Glow for enabled toggle
                if (value.get() && riseGlowIntensity > 0f) {
                    GlowUtils.drawGlow(toggleX, toggleY, 10f, 10f, glowRadius(16), Color(accent.red, accent.green, accent.blue, glowAlpha(200)))
                }
            }
            is IntValue -> drawNumber(vx, vy, vw, label, value.get().toFloat(), value.minimum.toFloat(), value.maximum.toFloat(), alpha)
            is FloatValue -> drawNumber(vx, vy, vw, label, value.get(), value.minimum, value.maximum, alpha)
            is IntRangeValue -> drawRange(vx, vy, vw, label, value.get().first.toFloat(), value.get().last.toFloat(), value.minimum.toFloat(), value.maximum.toFloat(), alpha)
            is FloatRangeValue -> drawRange(vx, vy, vw, label, value.get().start, value.get().endInclusive, value.minimum, value.maximum, alpha)
            is ListValue -> {
                Fonts.fontRise35.drawString(label, vx, vy + 5f, labelColor.rgb)
                val mode = value.get()
                Fonts.fontRise35.drawString(mode, vx + vw - Fonts.fontRise35.getStringWidth(mode) - 7f, vy + 5f, accentWithAlpha.rgb)
            }
            is TextValue -> {
                Fonts.fontRise35.drawString(label, vx, vy + 4f, labelColor.rgb)
                val display = if (focusedText == value) textBuffer + "_" else value.get().ifBlank { "Empty..." }
                RoundedUtil.drawRound(vx, vy + 17f, vw, 15f, 4f, Color(12, 15, 23, (230 * alpha / 255).coerceIn(0, 255)))
                Fonts.fontRise35.drawString(trimToWidth(display, (vw - 12f).toInt()), vx + 6f, vy + 21f, if (focusedText == value) textColor.rgb else Color(170, 178, 190, alpha).rgb)
            }
            is ColorValue -> {
                Fonts.fontRise35.drawString(label, vx, vy + 5f, labelColor.rgb)
                val selColor = value.selectedColor()
                RoundedUtil.drawRound(vx + vw - 21f, vy + 3f, 14f, 12f, 3f, Color(selColor.red, selColor.green, selColor.blue, alpha))
                if (value.rainbow) Fonts.fontRise35.drawString("R", vx + vw - 36f, vy + 5f, accentWithAlpha.rgb)
            }
            is FontValue -> {
                Fonts.fontRise35.drawString(label, vx, vy + 5f, labelColor.rgb)
                val fontName = value.displayName
                Fonts.fontRise35.drawString(fontName, vx + vw - Fonts.fontRise35.getStringWidth(fontName) - 7f, vy + 5f, accentWithAlpha.rgb)
            }
            is BlockValue -> drawNumber(vx, vy, vw, label, value.get().toFloat(), value.minimum.toFloat(), value.maximum.toFloat(), alpha)
            else -> Fonts.fontRise35.drawString("$label: ${value.toText()}", vx, vy + 5f, labelColor.rgb)
        }

        if (draggingNumber == value && Mouse.isButtonDown(0)) {
            updateNumber(value, vx + 2f, vw - 4f, mouseX)
        }
    }

    private fun drawNumber(vx: Float, vy: Float, vw: Float, label: String, current: Float, min: Float, max: Float, alpha: Int = 255) {
        val progress = if (max == min) 0f else ((current - min) / (max - min)).coerceIn(0f, 1f)
        val sliderY = vy + 19f
        val labelAlpha = (130 * alpha / 255).coerceIn(0, 255)
        Fonts.fontRise35.drawString(label, vx, vy + 4f, Color(255, 255, 255, labelAlpha).rgb)
        Fonts.fontRise35.drawString(formatNumber(current), vx + vw - Fonts.fontRise35.getStringWidth(formatNumber(current)) - 5f, vy + 4f, Color(accent.red, accent.green, accent.blue, alpha).rgb)
        RoundedUtil.drawRound(vx + 2f, sliderY, vw - 4f, 3f, 2f, Color(48, 54, 68, alpha))
        RoundedUtil.drawRound(vx + 2f, sliderY, (vw - 4f) * progress, 3f, 2f, Color(accent.red, accent.green, accent.blue, alpha))
        val thumbX = vx + 2f + (vw - 4f) * progress - 2f
        RoundedUtil.drawRound(thumbX, sliderY - 2f, 7f, 7f, 3.5f, Color(255, 255, 255, alpha))
        // Glow for slider thumb
        if (riseGlowIntensity > 0f) {
            GlowUtils.drawGlow(thumbX - 2f, sliderY - 4f, 11f, 11f, glowRadius(18), Color(accent.red, accent.green, accent.blue, glowAlpha(180)))
        }
    }

    private fun drawRange(vx: Float, vy: Float, vw: Float, label: String, startVal: Float, endVal: Float, min: Float, max: Float, alpha: Int = 255) {
        val startP = if (max == min) 0f else ((startVal - min) / (max - min)).coerceIn(0f, 1f)
        val endP = if (max == min) 0f else ((endVal - min) / (max - min)).coerceIn(0f, 1f)
        val sliderY = vy + 19f
        val labelAlpha = (130 * alpha / 255).coerceIn(0, 255)
        Fonts.fontRise35.drawString(label, vx, vy + 4f, Color(255, 255, 255, labelAlpha).rgb)
        val rangeText = "${formatNumber(startVal)} - ${formatNumber(endVal)}"
        Fonts.fontRise35.drawString(rangeText, vx + vw - Fonts.fontRise35.getStringWidth(rangeText) - 5f, vy + 4f, Color(accent.red, accent.green, accent.blue, alpha).rgb)
        // Track
        RoundedUtil.drawRound(vx + 2f, sliderY, vw - 4f, 3f, 2f, Color(48, 54, 68, alpha))
        // Filled range
        val fillX = vx + 2f + (vw - 4f) * startP
        val fillW = (vw - 4f) * (endP - startP)
        RoundedUtil.drawRound(fillX, sliderY, fillW, 3f, 2f, Color(accent.red, accent.green, accent.blue, alpha))
        // Start thumb
        val startThumbX = vx + 2f + (vw - 4f) * startP - 2f
        RoundedUtil.drawRound(startThumbX, sliderY - 2f, 7f, 7f, 3.5f, Color(255, 255, 255, alpha))
        // End thumb
        val endThumbX = vx + 2f + (vw - 4f) * endP - 2f
        RoundedUtil.drawRound(endThumbX, sliderY - 2f, 7f, 7f, 3.5f, Color(255, 255, 255, alpha))
        // Glow for both thumbs
        if (riseGlowIntensity > 0f) {
            GlowUtils.drawGlow(startThumbX - 2f, sliderY - 4f, 11f, 11f, glowRadius(18), Color(accent.red, accent.green, accent.blue, glowAlpha(180)))
            GlowUtils.drawGlow(endThumbX - 2f, sliderY - 4f, 11f, 11f, glowRadius(18), Color(accent.red, accent.green, accent.blue, glowAlpha(180)))
        }
    }

    private fun clickSidebar(mouseX: Int, mouseY: Int, mouseButton: Int) {
        if (mouseButton != 0) return
        var cy = y + 58f
        Category.entries.forEach { category ->
            if (isHovered(x + 6f, cy - 2f, sidebarW - 12f, 22f, mouseX, mouseY)) {
                selectedCategory = category
                showSettings = false
                moduleScroll = 0f
                searchFocused = false
                focusedText = null
                return
            }
            cy += 23f
        }
        // Divider + settings button
        cy += 4f + 6f
        if (isHovered(x + 6f, cy - 2f, sidebarW - 12f, 22f, mouseX, mouseY)) {
            showSettings = true
            searchFocused = false
            focusedText = null
            return
        }
    }

    private fun clickSearch(mouseX: Int, mouseY: Int, mouseButton: Int) {
        if (mouseButton != 0) return
        val searchX = x + sidebarW + 13f
        val searchY = y + 11f
        searchFocused = isHovered(searchX, searchY, w - sidebarW - 27f, 22f, mouseX, mouseY)
        if (searchFocused) focusedText = null
    }

    private fun clickModules(mouseX: Int, mouseY: Int, mouseButton: Int) {
        val contentX = x + sidebarW
        if (!isHovered(contentX + 7f, y + 42f, w - sidebarW - 13f, h - 49f, mouseX, mouseY)) return
        var my = y + 48f + animModuleScroll
        filteredModules().forEach { module ->
            val cardH = moduleHeight(module)
            if (isHovered(contentX + 8f, my, moduleW, moduleBaseH, mouseX, mouseY)) {
                when (mouseButton) {
                    0 -> {
                        module.toggle()
                        modulesDirty = true
                    }
                    1 -> if (module.values.any { it.shouldRender() }) {
                        if (!expandedModules.add(module)) expandedModules.remove(module)
                    }
                    2 -> bindingModule = module
                }
                focusedText = null
                searchFocused = false
                return
            }

            if (module in expandedModules) {
                var vy = my + moduleBaseH + 2f
                module.values.filter { it.shouldRender() }.forEach { value ->
                    if (isHovered(contentX + 16f, vy, moduleW - 16f, valueHeight(value), mouseX, mouseY)) {
                        clickValue(value, contentX + 16f, mouseX, mouseButton)
                        searchFocused = false
                        return
                    }
                    vy += valueHeight(value)
                }
            }
            my += cardH + 7f
        }
    }

    private fun clickSettings(contentX: Float, contentW: Float, mouseX: Int, mouseY: Int, mouseButton: Int) {
        if (mouseButton != 0) return
        val sx = contentX + 13f
        val sliderW = contentW - 27f

        // Theme dots: at y+62f (label) -> sy+4f=y+66f, size 14x14, click area 18x18, spacing 24f
        val dotSize = 14f
        val dotSpacing = 24f
        var sy = y + 62f
        val themeNames = arrayOf("Ocean", "Sunset", "Forest", "Royal", "Crimson", "Mono")
        var dotX = sx
        themeNames.forEach { name ->
            if (isHovered(dotX - 2f, sy + 2f, dotSize + 4f, dotSize + 4f, mouseX, mouseY)) {
                ClickGUI.nlThemeValue.set(name, false)
                valuesDirty = true
                return
            }
            dotX += dotSpacing
        }

        // Glow Intensity slider (y+108f, normalized 0..2 -> 0..1)
        sy = y + 108f
        if (isHovered(sx, sy - 5f, sliderW, 14f, mouseX, mouseY)) {
            sidebarDraggingSlider = "glow"
            handleSidebarSliderDrag(mouseX)
            return
        }

        // Background Opacity slider (y+148f, normalized 0.1..1.0 -> 0..1)
        sy = y + 148f
        if (isHovered(sx, sy - 5f, sliderW, 14f, mouseX, mouseY)) {
            sidebarDraggingSlider = "opacity"
            handleSidebarSliderDrag(mouseX)
            return
        }

        // Blur toggle (y+170f label, toggle at sx+sliderW-10f, sy-2f, size 10x10, click area 14x14)
        sy = y + 170f
        val toggleX = sx + sliderW - 10f
        val toggleY = sy - 2f
        if (isHovered(toggleX - 2f, toggleY - 2f, 14f, 14f, mouseX, mouseY)) {
            ClickGUI.nlBlurValue.set(!ClickGUI.nlBlur, false)
            valuesDirty = true
            return
        }

        // Blur Strength slider (only if blur enabled; y+210f, normalized 1..50 -> 0..1)
        if (ClickGUI.nlBlur) {
            sy = y + 210f
            if (isHovered(sx, sy - 5f, sliderW, 14f, mouseX, mouseY)) {
                sidebarDraggingSlider = "blur"
                handleSidebarSliderDrag(mouseX)
                return
            }
        }
    }

    private fun handleSidebarSliderDrag(mouseX: Int) {
        val contentX = x + sidebarW
        val contentW = w - sidebarW
        val sx = contentX + 13f
        val sliderW = contentW - 27f
        val progress = ((mouseX - sx) / sliderW).coerceIn(0f, 1f)

        when (sidebarDraggingSlider) {
            "glow" -> {
                ClickGUI.nlGlowIntensityValue.set(progress, false)
                valuesDirty = true
            }
            "opacity" -> {
                // Range 0.1f..1.0f
                val value = 0.1f + 0.9f * progress
                ClickGUI.nlBgOpacityValue.set(value, false)
                valuesDirty = true
            }
            "blur" -> {
                // Global Blur strength (int 1..50)
                val value = (1 + 49 * progress).toInt()
                Client.blurStrengthValue.set(value, false)
                valuesDirty = true
            }
        }
    }

    private fun clickValue(value: Value<*>, sliderX: Float, mouseX: Int, mouseButton: Int) {
        when (value) {
            is BoolValue -> if (mouseButton == 0) {
                value.toggle()
                valuesDirty = true
            }
            is IntValue, is FloatValue, is BlockValue -> if (mouseButton == 0) {
                draggingNumber = value
                updateNumber(value, sliderX + 2f, moduleW - 20f, mouseX)
            }
            is IntRangeValue, is FloatRangeValue -> if (mouseButton == 0) {
                draggingNumber = value
                updateNumber(value, sliderX + 2f, moduleW - 20f, mouseX)
            }
            is ListValue -> {
                val values = value.values
                val index = values.indexOf(value.get()).takeIf { it >= 0 } ?: 0
                val next = if (mouseButton == 1) (index - 1 + values.size) % values.size else (index + 1) % values.size
                value.set(values[next])
                valuesDirty = true
            }
            is TextValue -> if (mouseButton == 0) {
                focusedText = value
                textBuffer = value.get()
            }
            is FontValue -> if (mouseButton == 0) {
                value.next()
                valuesDirty = true
            }
            is ColorValue -> {
                if (mouseButton == 1) {
                    value.rainbow = !value.rainbow
                } else {
                    val color = Color.getHSBColor(((System.currentTimeMillis() / 16L) % 360L) / 360f, 0.46f, 1f)
                    value.set(Color(color.red, color.green, color.blue, value.selectedColor().alpha))
                }
                valuesDirty = true
            }
            else -> Unit
        }
    }

    private fun handleTextInput(value: TextValue, typedChar: Char, keyCode: Int) {
        when (keyCode) {
            Keyboard.KEY_ESCAPE, Keyboard.KEY_RETURN -> {
                focusedText = null
                flushConfigs()
                return
            }
            Keyboard.KEY_BACK -> if (textBuffer.isNotEmpty()) {
                textBuffer = textBuffer.dropLast(1)
                value.set(textBuffer)
                valuesDirty = true
                return
            }
            Keyboard.KEY_DELETE -> {
                textBuffer = ""
                value.set("")
                valuesDirty = true
                return
            }
        }

        if (!Character.isISOControl(typedChar) && textBuffer.length < 64) {
            textBuffer += typedChar
            value.set(textBuffer)
            valuesDirty = true
        }
    }

    private fun updateNumber(value: Value<*>, sliderX: Float, sliderW: Float, mouseX: Int) {
        val progress = ((mouseX - sliderX) / sliderW).coerceIn(0f, 1f)
        when (value) {
            is IntValue -> {
                value.set((value.minimum + (value.maximum - value.minimum) * progress).roundToInt())
                valuesDirty = true
            }
            is FloatValue -> {
                val next = value.minimum + (value.maximum - value.minimum) * progress
                value.set((next * 100f).roundToInt() / 100f)
                valuesDirty = true
            }
            is BlockValue -> {
                value.set((value.minimum + (value.maximum - value.minimum) * progress).roundToInt())
                valuesDirty = true
            }
            is IntRangeValue -> {
                val v = (value.minimum + (value.maximum - value.minimum) * progress).roundToInt()
                val range = value.get()
                val distStart = abs(v - range.first)
                val distEnd = abs(v - range.last)
                if (distStart <= distEnd) value.setFirst(v) else value.setLast(v)
                valuesDirty = true
            }
            is FloatRangeValue -> {
                val v = value.minimum + (value.maximum - value.minimum) * progress
                val range = value.get()
                val distStart = abs(v - range.start)
                val distEnd = abs(v - range.endInclusive)
                if (distStart <= distEnd) value.setFirst((v * 100f).roundToInt() / 100f) else value.setLast((v * 100f).roundToInt() / 100f)
                valuesDirty = true
            }
            else -> Unit
        }
    }

    private fun handleWheel(mouseX: Int, mouseY: Int) {
        val wheel = Mouse.getDWheel()
        if (wheel == 0 || !isHovered(x + sidebarW, y, w - sidebarW, h, mouseX, mouseY)) return
        moduleScroll = (moduleScroll + if (wheel > 0) 18f else -18f).coerceIn(minScroll(), 0f)
    }

    private fun filteredModules() = if (search.isBlank()) {
        AirPlus.moduleManager[selectedCategory].sortedBy { it.name.lowercase() }
    } else {
        // When searching, show results from all categories
        Category.entries.flatMap { AirPlus.moduleManager[it] }
            .filter { it.name.contains(search, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
    }

    private fun filteredHeight() = filteredModules().sumOf { (moduleHeight(it) + 7f).toDouble() }.toFloat()

    private fun moduleHeight(module: Module): Float {
        val extraHeight = module.values.filter { it.shouldRender() }.sumOf { valueHeight(it).toDouble() }.toFloat()
        val expansion = expansionAnimations[module] ?: if (module in expandedModules) 1f else 0f
        return moduleBaseH + (extraHeight + 4f) * expansion
    }

    private fun valueHeight(value: Value<*>) = when (value) {
        is IntValue, is FloatValue, is IntRangeValue, is FloatRangeValue, is BlockValue -> 30f
        is TextValue -> 38f
        else -> 21f
    }

    private fun minScroll(): Float {
        val visible = h - 49f
        return (visible - filteredHeight()).coerceAtMost(0f)
    }

    private fun drawScrollbar(total: Float, sx: Float, sy: Float, sh: Float) {
        if (total <= sh) return
        val barH = (sh * (sh / total)).coerceAtLeast(24f)
        val progress = (-animModuleScroll / (total - sh)).coerceIn(0f, 1f)
        RoundedUtil.drawRound(sx, sy, 2f, sh, 1f, Color(255, 255, 255, 28))
        RoundedUtil.drawRound(sx, sy + (sh - barH) * progress, 2f, barH, 1f, accent)
    }

    private fun flushConfigs() {
        if (valuesDirty) {
            saveConfig(valuesConfig)
            valuesDirty = false
        }
        if (modulesDirty) {
            saveConfig(modulesConfig)
            modulesDirty = false
        }
    }

    private fun drawCircle(cx: Float, cy: Float, radius: Float, color: Color) {
        if (radius <= 0f) return
        RenderUtils.drawFilledCircle((cx + radius / 2f).toInt(), (cy + radius / 2f).toInt(), radius / 2f, color)
    }

    private fun selectedCategoryY(): Float {
        if (showSettings) {
            // Settings button sits below all categories + divider (4f + 6f)
            return y + 58f + Category.entries.size * 23f + 10f
        }
        return y + 58f + Category.entries.indexOf(selectedCategory).coerceAtLeast(0) * 23f
    }

    private fun updateExpansion(module: Module): Float {
        val current = expansionAnimations[module] ?: if (module in expandedModules) 1f else 0f
        val target = if (module in expandedModules) 1f else 0f
        val next = animate(current, target, 0.18f)
        if (next <= 0.01f && target == 0f) {
            expansionAnimations.remove(module)
        } else {
            expansionAnimations[module] = next
        }
        return next
    }

    private fun animate(current: Float, target: Float, speed: Float): Float {
        if (abs(target - current) < 0.01f) return target
        return current + (target - current) * speed
    }

    private fun easeOut(value: Float): Float {
        val clamped = value.coerceIn(0f, 1f)
        return 1f - (1f - clamped) * (1f - clamped)
    }

    private fun startScissor(sx: Float, sy: Float, sw: Float, sh: Float) {
        val sr = ScaledResolution(mc)
        val factor = sr.scaleFactor
        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        GL11.glScissor((sx * factor).toInt(), ((sr.scaledHeight - sy - sh) * factor).toInt(), (sw * factor).toInt(), (sh * factor).toInt())
    }

    private fun endScissor() = GL11.glDisable(GL11.GL_SCISSOR_TEST)

    private fun trimToWidth(text: String, maxWidth: Int): String {
        if (Fonts.fontRise35.getStringWidth(text) <= maxWidth) return text
        var trimmed = text
        while (trimmed.isNotEmpty() && Fonts.fontRise35.getStringWidth("...$trimmed") > maxWidth) {
            trimmed = trimmed.drop(1)
        }
        return "...$trimmed"
    }

    private fun formatNumber(value: Float) =
        if (value % 1f == 0f) value.roundToInt().toString() else "%.2f".format(value)

    private fun isHovered(hx: Float, hy: Float, hw: Float, hh: Float, mouseX: Int, mouseY: Int) =
        mouseX >= hx && mouseX <= hx + hw && mouseY >= hy && mouseY <= hy + hh

    // 图标使用 SessIcon 字体（session.ttf）：F剑 C跑步 G单人 A鼠标 L地球 J拼图 K齿轮 H双人
    private fun Category.riseIcon() = when (this) {
        Category.COMBAT -> "F"
        Category.MOVEMENT -> "C"
        Category.PLAYER -> "G"
        Category.RENDER -> "A"
        Category.WORLD -> "L"
        Category.MISC -> "J"
        Category.CLIENT -> "H"
    }

    private companion object {
        // Persisted state across sessions
        var lastX = -1f
        var lastY = -1f
        var lastCategory: Category? = null
        var lastScroll = 0f
        var lastSearch = ""
        var lastExpandedModules = HashSet<String>() // 保存展开的模块名称
        var lastDisplayedAccent: Color? = null // 保存上次关闭时的主题色
        var lastDisplayedBg: Color? = null // 保存上次关闭时的背景色
    }
}
