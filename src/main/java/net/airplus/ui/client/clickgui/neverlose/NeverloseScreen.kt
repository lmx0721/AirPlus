/*
 * AirClient Hacked Client
 * A free open source mixin-based injection hacked client built on Liquidbounce legacy codebase.
 * https://github.com/lmx0721/AirClient
 */
package net.airplus.ui.client.clickgui.neverlose

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
import net.airplus.features.module.modules.render.ClickGUI
import net.airplus.file.FileManager.modulesConfig
import net.airplus.file.FileManager.saveConfig
import net.airplus.file.FileManager.valuesConfig
import net.airplus.ui.font.Fonts
import net.airplus.utils.render.HudBlur
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.render.RoundedUtil
import net.airplus.utils.GlowUtils
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.util.ResourceLocation
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import java.awt.Color
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt

class NeverloseScreen : GuiScreen() {

    private val panels = Category.entries.associateWith { category ->
        AirPlus.moduleManager[category].sortedBy { it.name.lowercase() }
    }

    private var selectedCategory = Category.COMBAT
    private var search = ""
    private var searchFocused = false
    private var searchExpanded = false
    private var searchExpandAnim = 0f

    private var dragging = false
    private var dragX = 0
    private var dragY = 0
    private var posX = 40
    private var posY = 40

    private var contentScroll = 0
    private var targetContentScroll = 0

    private var rightPanelOpen = false

    // Toggle knob animation: key = module/value object, value = animation progress 0..1
    private val toggleAnimations = HashMap<Any, Float>()

    // Slider thumb animation: key = value object, value = displayed progress 0..1
    private val sliderDisplayProgress = HashMap<Value<*>, Float>()
    // Range slider thumb animation: key = value object, pair = (startProgress, endProgress)
    private val rangeStartDisplayProgress = HashMap<Value<*>, Float>()
    private val rangeEndDisplayProgress = HashMap<Value<*>, Float>()

    // Gear popup state: which module's gear is open
    private var gearPopupModule: Module? = null
    private var gearPopupX = 0
    private var gearPopupY = 0

    // Keybind listening
    private var keybindListeningModule: Module? = null

    // Text value editing
    private var focusedTextValue: TextValue? = null
    private var focusedTextBuffer = ""
    private var focusedTextSelected = false

    // Color value expanded picker - track position
    private var expandedColorValue: ColorValue? = null
    private var colorPickerX = 0
    private var colorPickerY = 0

    // Open dropdown list values
    private var openListValue: ListValue? = null

    // ListValue expand animation progress (0 = collapsed, 1 = expanded)
    private var listExpandAnim = 0f

    // Dragging slider state
    private var draggingSlider: Value<*>? = null
    private var rightPanelDraggingId: String? = null
    // Which thumb is being dragged in a range slider: "start" or "end"
    private var draggingRangeThumb: String? = null

    private var modulesConfigDirty = false
    private var valuesConfigDirty = false

    // Smooth animations
    private var rightPanelAnim = 0f
    private var gearPopupAnim = 0f
    private var categorySwitchAnim = 1f

    // Theme color transition animation (smooth color crossfade when switching themes)
    private var displayedAccent: Color = Color(74, 144, 217)
    private var displayedBg: Color = Color(0x0D, 0x1B, 0x2A)
    private var themeTransitionAnim = 1f

    private val guiWidth = 480
    private val guiHeight = 320
    private val sidebarWidth = 110
    private val rightPanelWidth = 200
    private val headerHeight = 36
    private val columnGap = 16
    private val sectionGap = 14
    private val rowHeight = 22
    private val rowGap = 8

    private val sdf = SimpleDateFormat("yyyy.MM.dd HH:mm")

    // Glow alpha multiplier (0 = no glow, 1 = full glow) - sqrt curve for more visible per-% change
    private fun glowAlpha(baseAlpha: Int): Int = (baseAlpha * kotlin.math.sqrt(ClickGUI.nlGlowIntensity.toDouble()).toFloat()).roundToInt().coerceIn(0, 255)
    // Glow blur radius multiplier (scales radius by intensity for size change)
    private fun glowRadius(baseRadius: Int): Int = (baseRadius * (0.3f + 0.7f * ClickGUI.nlGlowIntensity)).roundToInt().coerceAtLeast(0)

    override fun initGui() {
        Keyboard.enableRepeatEvents(true)
        // Restore last position and category if available, otherwise center
        posX = lastPosX ?: ((width - guiWidth) / 2).coerceAtLeast(20)
        posY = lastPosY ?: ((height - guiHeight) / 2).coerceAtLeast(20)
        lastCategory?.let { selectedCategory = it }
        // Restore search and scroll state
        search = lastSearch
        if (search.isNotBlank()) {
            searchExpanded = true
            searchExpandAnim = 1f
            searchFocused = true
        }
        contentScroll = lastScroll
        targetContentScroll = lastScroll
    }

    override fun onGuiClosed() {
        Keyboard.enableRepeatEvents(false)
        commitFocusedTextValue()
        saveDirtyConfigs()
        // Save current position and category for next session
        lastPosX = posX
        lastPosY = posY
        lastCategory = selectedCategory
        lastScroll = contentScroll
        lastSearch = search
    }

    override fun doesGuiPauseGame() = false

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        if (dragging) {
            posX = (mouseX + dragX).coerceIn(0, width - guiWidth)
            posY = (mouseY + dragY).coerceIn(0, height - guiHeight)
        }

        // Smooth scroll animation
        contentScroll = lerp(contentScroll.toFloat(), targetContentScroll.toFloat(), animSpeed() * 0.25f).roundToInt()

        // Smooth panel animations
        val targetRight = if (rightPanelOpen) 1f else 0f
        rightPanelAnim = lerp(rightPanelAnim, targetRight, animSpeed() * 0.25f)
        val targetGear = if (gearPopupModule != null) 1f else 0f
        gearPopupAnim = lerp(gearPopupAnim, targetGear, animSpeed() * 0.3f)
        val targetSearch = if (searchExpanded) 1f else 0f
        searchExpandAnim = lerp(searchExpandAnim, targetSearch, animSpeed() * 0.35f)
        val targetListExpand = if (openListValue != null) 1f else 0f
        listExpandAnim = lerp(listExpandAnim, targetListExpand, animSpeed() * 0.3f)
        categorySwitchAnim = lerp(categorySwitchAnim, 1f, animSpeed() * 0.2f)

        // Theme color transition (smooth crossfade when switching themes)
        val targetAccent = ClickGUI.nlAccentColor
        val targetBg = ClickGUI.nlThemeBgColor
        displayedAccent = lerpColor(displayedAccent, targetAccent, animSpeed() * 0.15f)
        displayedBg = lerpColor(displayedBg, targetBg, animSpeed() * 0.15f)

        drawShell(mouseX, mouseY)
        drawSidebar(mouseX, mouseY)
        drawMainContent(mouseX, mouseY)
        if (rightPanelAnim > 0.01f) {
            drawRightPanel(mouseX, mouseY)
        }
        if (gearPopupAnim > 0.01f && gearPopupModule != null) {
            drawGearPopup(mouseX, mouseY)
        }
        if (expandedColorValue != null) {
            drawColorPicker(mouseX, mouseY)
        }
        if (openListValue != null) {
            drawOpenList(mouseX, mouseY)
        }

        handleWheel(mouseX, mouseY)
    }

    // ============ Shell & Background ============

    private fun drawShell(mouseX: Int, mouseY: Int) {
        val opacity = ClickGUI.nlBgOpacity
        val bgColor = displayedBg // Use animated theme bg color
        // Unified background color (single draw to avoid color stacking from overlapping draws)
        val bgColorUniform = colorWithAlpha(bgColor, opacity)

        // Background blur (HudBlur InternalBlur：与 HUD 编辑器同一条已验证可用的模糊路径)
        if (ClickGUI.nlBlur) {
            HudBlur.blur(
                posX.toFloat(), posY.toFloat(),
                (posX + guiWidth).toFloat(), (posY + guiHeight).toFloat(),
                ClickGUI.nlBlurStrength, "InternalBlur", 12f
            )
        }

        // Single unified background draw (no double-drawing sidebar + main panel)
        RoundedUtil.drawRound(posX.toFloat(), posY.toFloat(), guiWidth.toFloat(), guiHeight.toFloat(), 12f, bgColorUniform)

        // Divider line between sidebar and main panel
        RenderUtils.drawRect(
            (posX + sidebarWidth - 1).toFloat(), posY.toFloat(),
            (posX + sidebarWidth).toFloat(), (posY + guiHeight).toFloat(), DIVIDER
        )
    }

    // ============ Sidebar ============

    private fun drawSidebar(mouseX: Int, mouseY: Int) {
        // Logo (larger font)
        Fonts.font52.drawString("AirClient", (posX + 10).toFloat(), (posY + 18).toFloat(), TEXT.rgb)

        // Categories list (no group labels)
        var y = posY + 50
        Category.entries.filter { it.shouldShow() }.forEach { category ->
            val hovered = isHovered(posX + 8, y - 4, sidebarWidth - 16, 20, mouseX, mouseY)
            val selected = category == selectedCategory

            if (selected) {
                RoundedUtil.drawRound((posX + 8).toFloat(), (y - 4).toFloat(), (sidebarWidth - 16).toFloat(), 20f, 5f, colorWithAlpha(displayedBg, ClickGUI.nlBgOpacity))
                // Accent left border
                RenderUtils.drawRect((posX + 8).toFloat(), (y - 4).toFloat(), (posX + 10).toFloat(), (y + 16).toFloat(), accentColor())
            } else if (hovered) {
                RoundedUtil.drawRound((posX + 8).toFloat(), (y - 4).toFloat(), (sidebarWidth - 16).toFloat(), 20f, 5f, colorWithAlpha(displayedBg, ClickGUI.nlBgOpacity))
            }

            // Category icon (image) - aligned with text baseline
            val iconColor = if (selected) accentColor() else if (hovered) Color(200, 200, 210) else MUTED
            drawCategoryIcon(category, (posX + 12).toFloat(), (y - 2).toFloat(), iconColor)
            // Glow for selected category icon
            if (selected && ClickGUI.nlGlowIntensity > 0f) {
                GlowUtils.drawGlow((posX + 10).toFloat(), (y - 4).toFloat(), 18f, 18f, glowRadius(20), Color(accentColor().red, accentColor().green, accentColor().blue, glowAlpha(180)))
            }
            // Category name
            val textColor = if (selected) TEXT else if (hovered) Color(220, 220, 230) else Color(150, 150, 160)
            Fonts.font35.drawString(category.displayName, (posX + 32).toFloat(), (y + 2).toFloat(), textColor.rgb)
            y += 26
        }

        // User profile at bottom
        drawUserProfile(mouseX, mouseY)
    }

    private fun drawUserProfile(mouseX: Int, mouseY: Int) {
        val avatarSize = 24
        val profileY = posY + guiHeight - 8 - avatarSize
        // Divider line above profile
        val dividerY = profileY - 8
        val dividerLeft = posX + 8
        RenderUtils.drawRect(
            dividerLeft.toFloat(), dividerY.toFloat(),
            (posX + sidebarWidth - 8).toFloat(), (dividerY + 1).toFloat(), DIVIDER
        )

        val playerName = mc.thePlayer?.name ?: "Offline"
        // Avatar left edge aligned with divider left edge
        val avatarX = dividerLeft

        // Draw player head avatar
        val playerInfo = mc.netHandler.getPlayerInfo(mc.thePlayer?.uniqueID)
        val skin = playerInfo?.locationSkin
        if (skin != null) {
            RenderUtils.drawHead(skin, avatarX, profileY, 8f, 8f, 8, 8, avatarSize, avatarSize, 64f, 64f, Color.WHITE)
        } else {
            RoundedUtil.drawRound(avatarX.toFloat(), profileY.toFloat(), avatarSize.toFloat(), avatarSize.toFloat(), 6f, accentColor())
            val initial = playerName.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
            Fonts.font35.drawString(initial, (avatarX + avatarSize / 2 - Fonts.font35.getStringWidth(initial) / 2).toFloat(), (profileY + avatarSize / 2 - 4).toFloat(), TEXT.rgb)
        }

        // Player name and time (right of avatar, shifted down slightly)
        val nameX = avatarX + avatarSize + 4
        Fonts.font35.drawString(playerName, nameX.toFloat(), (profileY + 4).toFloat(), TEXT.rgb)
        // Current date/time
        val timeStr = sdf.format(Date())
        Fonts.font30.drawString(timeStr, nameX.toFloat(), (profileY + 16).toFloat(), accentColor().rgb)
    }

    // ============ Main Content ============

    private fun drawMainContent(mouseX: Int, mouseY: Int) {
        drawTopBar(mouseX, mouseY)
        drawContentGrid(mouseX, mouseY)
    }

    private fun drawTopBar(mouseX: Int, mouseY: Int) {
        val topY = posY + 8
        val topX = posX + sidebarWidth + 14

        // Right side: search icon (fixed position), search input expands left from it
        val searchIconSize = 14
        val searchIconX = posX + guiWidth - 20
        val searchIconY = topY + 4
        val searchHovered = isHovered(searchIconX, searchIconY, searchIconSize, searchIconSize, mouseX, mouseY)

        // Expanded search input width
        val searchExpandedW = 120
        val searchCollapsedW = 0
        val searchW = (searchCollapsedW + (searchExpandedW - searchCollapsedW) * searchExpandAnim).toInt()
        // Search box starts at searchIconX - searchW - 6 (gap between box and icon)
        val searchBoxX = searchIconX - searchW - 6
        val searchBoxRight = searchIconX - 6

        if (searchW > 2) {
            val searchBg = colorWithAlpha(displayedBg, ClickGUI.nlBgOpacity)
            RoundedUtil.drawRound(searchBoxX.toFloat(), topY.toFloat(), searchW.toFloat(), 20f, 4f, searchBg)
            if (searchW > 20) {
                val searchTextY = topY + (20 - 8) / 2 + 1
                if (search.isBlank()) {
                    Fonts.font30.drawString("Search...", (searchBoxX + 8).toFloat(), searchTextY.toFloat(), MUTED.rgb)
                } else {
                    Fonts.font30.drawString(search, (searchBoxX + 8).toFloat(), searchTextY.toFloat(), TEXT.rgb)
                }
                if (searchFocused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
                    val caretX = searchBoxX + 8 + Fonts.font30.getStringWidth(search)
                    RenderUtils.drawRect(caretX.toFloat(), (topY + 4).toFloat(), (caretX + 1).toFloat(), (topY + 16).toFloat(), accentColor())
                }
            }
        }

        // Search icon (clickable, toggles expand)
        val searchIconColor = if (searchHovered || searchFocused || searchExpanded) accentColor() else MUTED
        drawSearchIcon(searchIconX.toFloat(), searchIconY.toFloat(), searchIconColor)

        // Settings gear (to the left of search area)
        val gearSize = 14
        val gearX = searchBoxX - gearSize - 6
        val gearY = topY + 4
        val gearHovered = isHovered(gearX, gearY, gearSize, gearSize, mouseX, mouseY)
        drawGearIcon(gearX.toFloat(), gearY.toFloat(), if (gearHovered || rightPanelOpen) accentColor() else MUTED)

        // Category title (left side of top bar) - shows "Search Results" when searching across all categories
        val title = if (search.isNotBlank()) "搜索结果" else selectedCategory.displayName
        Fonts.font40.drawString(title, topX.toFloat(), (topY + 5).toFloat(), TEXT.rgb)

        // Divider line below top bar
        RenderUtils.drawRect(
            (posX + sidebarWidth).toFloat(), (posY + headerHeight).toFloat(),
            (posX + guiWidth).toFloat(), (posY + headerHeight + 1).toFloat(), DIVIDER
        )
    }

    private fun drawContentGrid(mouseX: Int, mouseY: Int) {
        val contentX = posX + sidebarWidth + 14
        val contentY = posY + headerHeight + 10
        val contentW = guiWidth - sidebarWidth - 28
        val contentH = guiHeight - headerHeight - 20
        val colW = (contentW - columnGap) / 2

        // Clip to content area for scrolling
        val clipBottom = contentY + contentH

        // Enable scissor test for scrolling (only clip vertically, allow horizontal overflow for glow)
        enableScissor(0, contentY, width, clipBottom)

        // Apply category switch animation (slide-up + fade-in)
        GlStateManager.pushMatrix()
        val yOffset = (1f - categorySwitchAnim) * 15f
        GlStateManager.translate(0f, yOffset, 0f)
        GlStateManager.color(1f, 1f, 1f, categorySwitchAnim)

        val modules = filteredModules()
        if (modules.isEmpty()) {
            GlStateManager.popMatrix()
            GlStateManager.color(1f, 1f, 1f, 1f)
            disableScissor()
            Fonts.font35.drawCenteredString("No modules", (contentX + contentW / 2).toFloat(), (contentY + 60).toFloat(), MUTED.rgb)
            return
        }

        // Distribute modules into 2 columns (balance by height)
        val columns = arrayOf(mutableListOf<Module>(), mutableListOf<Module>())
        val colHeights = floatArrayOf(0f, 0f)
        modules.forEach { module ->
            val targetCol = if (colHeights[0] <= colHeights[1]) 0 else 1
            columns[targetCol].add(module)
            colHeights[targetCol] = colHeights[targetCol] + moduleSectionHeight(module).toFloat()
        }

        var maxBottom = contentY
        for (colIndex in 0..1) {
            var y = contentY + contentScroll
            val x = if (colIndex == 0) contentX else contentX + colW + columnGap
            columns[colIndex].forEach { module ->
                if (y + moduleSectionHeight(module) > contentY && y < clipBottom) {
                    drawModuleSection(module, x, y, colW, mouseX, mouseY)
                }
                y += moduleSectionHeight(module)
            }
            if (y > maxBottom) maxBottom = y
        }

        GlStateManager.popMatrix()
        GlStateManager.color(1f, 1f, 1f, 1f)

        disableScissor()

        // Update max scroll
        val totalContentHeight = maxBottom - contentY - contentScroll
        val maxScroll = (totalContentHeight - contentH + 20).coerceAtLeast(0).toInt()
        targetContentScroll = targetContentScroll.coerceIn(-maxScroll, 0)
    }

    private fun moduleSectionHeight(module: Module): Int {
        val values = module.values.filter { it.shouldRender() && !it.subjective }
        var h = 24 // section title
        h += rowHeight + rowGap // Enabled row
        values.forEach { value ->
            h += valueDisplayHeight(value) + rowGap
        }
        h += sectionGap
        return h
    }

    private fun valueDisplayHeight(value: Value<*>): Int {
        return when (value) {
            is IntValue, is FloatValue -> rowHeight + 8 // slider needs extra for track
            is IntRangeValue, is FloatRangeValue -> rowHeight + 8
            is ColorValue -> if (expandedColorValue === value) 140 else rowHeight
            // ListValue: use stable (non-animated) height to prevent layout shifts above.
            // Animation is purely visual within the allocated space.
            is ListValue -> if (openListValue === value) rowHeight + value.values.size * 20 + 8 else rowHeight
            else -> rowHeight
        }
    }

    private fun drawModuleSection(module: Module, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        // Section title (module name) - brighter than MUTED for visibility
        Fonts.font40.drawString(module.name, x.toFloat(), (y + 4).toFloat(), MODULE_TITLE.rgb)

        var rowY = y + 22

        // Enabled row with gear + toggle
        drawEnabledRow(module, x, rowY, w, mouseX, mouseY)
        rowY += rowHeight + rowGap

        // Module values - filter out subjective values (Hide, etc.) since they're in the gear popup
        val values = module.values.filter { it.shouldRender() && !it.subjective }
        values.forEach { value ->
            val vh = valueDisplayHeight(value)
            if (rowY + vh > posY + headerHeight && rowY < posY + guiHeight) {
                drawValue(value, x, rowY, w, mouseX, mouseY)
            }
            rowY += vh + rowGap
        }
    }

    private fun drawEnabledRow(module: Module, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        val rowHovered = isHovered(x, y, w, rowHeight, mouseX, mouseY)
        if (rowHovered) {
            RoundedUtil.drawRound(x.toFloat(), y.toFloat(), w.toFloat(), rowHeight.toFloat(), 4f, ROW_HOVER)
        }

        Fonts.font30.drawString("Enabled", (x + 4).toFloat(), (y + 7).toFloat(), TEXT.rgb)

        // Gear icon (right side, before toggle)
        val gearSize = 14
        val gearX = x + w - 52
        val gearY = y + 4
        val gearHovered = isHovered(gearX, gearY, gearSize, gearSize, mouseX, mouseY)
        drawGearIcon(gearX.toFloat(), gearY.toFloat(), if (gearHovered) Color(170, 170, 180) else Color(100, 100, 110))

        // Toggle switch with slide animation - knob size matches track height for visual consistency
        val toggleW = 28
        val toggleH = 14
        val knobSize = 10
        val knobPadding = 2 // symmetric padding on both sides
        val toggleX = x + w - 32
        val toggleY = y + (rowHeight - toggleH) / 2
        val enabled = module.state
        val toggleBg = if (enabled) accentColor() else TOGGLE_OFF
        // Glow effect on toggle when enabled
        if (enabled && ClickGUI.nlGlowIntensity > 0f) {
            GlowUtils.drawGlow(toggleX.toFloat(), toggleY.toFloat(), toggleW.toFloat(), toggleH.toFloat(), glowRadius(30), Color(accentColor().red, accentColor().green, accentColor().blue, glowAlpha(255)))
        }
        RoundedUtil.drawRound(toggleX.toFloat(), toggleY.toFloat(), toggleW.toFloat(), toggleH.toFloat(), 7f, toggleBg)
        // Animated knob position (symmetric travel: padding .. toggleW - knobSize - padding)
        val target = if (enabled) 1f else 0f
        val anim = toggleAnimations.getOrPut(module) { target }
        val newAnim = lerp(anim, target, (animSpeed() * 0.25f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        toggleAnimations[module] = newAnim
        val knobTravel = toggleW - knobSize - 2 * knobPadding
        val knobX = toggleX + knobPadding + knobTravel * newAnim
        val knobY = toggleY + (toggleH - knobSize) / 2
        RoundedUtil.drawRound(knobX.toFloat(), knobY.toFloat(), knobSize.toFloat(), knobSize.toFloat(), 5f, Color.WHITE)
    }

    // ============ Value Rendering ============

    private fun drawValue(value: Value<*>, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        val rowHovered = isHovered(x, y, w, rowHeight, mouseX, mouseY)
        if (rowHovered) {
            RoundedUtil.drawRound(x.toFloat(), y.toFloat(), w.toFloat(), rowHeight.toFloat(), 4f, ROW_HOVER)
        }

        // Draw setting name with two-line wrapping for long names
        val nameMaxW = w - 120
        val fullName = value.name
        if (Fonts.font30.getStringWidth(fullName) <= nameMaxW) {
            val textY = y + (rowHeight - 8) / 2 + 1
            Fonts.font30.drawString(fullName, (x + 4).toFloat(), textY.toFloat(), TEXT_RGB)
        } else {
            // Split into two lines that fit within nameMaxW
            var splitIdx = fullName.lastIndexOf(' ', fullName.length / 2)
            if (splitIdx <= 0) splitIdx = fullName.length / 2
            var line1 = fullName.substring(0, splitIdx).trimEnd()
            var line2 = fullName.substring(splitIdx).trimStart()
            if (Fonts.font30.getStringWidth(line1) > nameMaxW) line1 = fitText(line1, nameMaxW)
            if (Fonts.font30.getStringWidth(line2) > nameMaxW) line2 = fitText(line2, nameMaxW)
            Fonts.font30.drawString(line1, (x + 4).toFloat(), (y + 3).toFloat(), TEXT_RGB)
            Fonts.font30.drawString(line2, (x + 4).toFloat(), (y + 11).toFloat(), TEXT_RGB)
        }

        when (value) {
            is BoolValue -> drawBoolValue(value, x, y, w)
            is IntValue -> drawNumberValue(value.get().toFloat(), value.minimum.toFloat(), value.maximum.toFloat(), value.get().toString(), x, y, w, mouseX, mouseY, value)
            is FloatValue -> drawNumberValue(value.get(), value.minimum, value.maximum, "%.2f".format(value.get()), x, y, w, mouseX, mouseY, value)
            is IntRangeValue -> drawRangeValue(value.get().first.toFloat(), value.get().last.toFloat(), value.minimum.toFloat(), value.maximum.toFloat(), "${value.get().first}..${value.get().last}", x, y, w, mouseX, mouseY, value)
            is FloatRangeValue -> drawRangeValue(value.get().start, value.get().endInclusive, value.minimum, value.maximum, "%.1f..%.1f".format(value.get().start, value.get().endInclusive), x, y, w, mouseX, mouseY, value)
            is ListValue -> drawListValue(value, x, y, w, mouseX, mouseY)
            is TextValue -> drawTextValue(value, x, y, w)
            is FontValue -> drawFontValue(value, x, y, w, mouseX, mouseY)
            is BlockValue -> drawNumberValue(value.get().toFloat(), value.minimum.toFloat(), value.maximum.toFloat(), value.get().toString(), x, y, w, mouseX, mouseY, value)
            is ColorValue -> drawColorValue(value, x, y, w, mouseX, mouseY)
            else -> {
                val text = fitText(value.toText(), 100)
                Fonts.font30.drawString(text, (x + w - Fonts.font30.getStringWidth(text) - 4).toFloat(), (y + 9).toFloat(), MUTED.rgb)
            }
        }
    }

    private fun drawBoolValue(value: BoolValue, x: Int, y: Int, w: Int) {
        val enabled = value.get()
        val toggleW = 28
        val toggleH = 14
        val knobSize = 10
        val knobPadding = 2
        val toggleX = x + w - 32
        val toggleY = y + (rowHeight - toggleH) / 2
        val toggleBg = if (enabled) accentColor() else TOGGLE_OFF
        // Glow effect on toggle when enabled
        if (enabled && ClickGUI.nlGlowIntensity > 0f) {
            GlowUtils.drawGlow(toggleX.toFloat(), toggleY.toFloat(), toggleW.toFloat(), toggleH.toFloat(), glowRadius(30), Color(accentColor().red, accentColor().green, accentColor().blue, glowAlpha(255)))
        }
        RoundedUtil.drawRound(toggleX.toFloat(), toggleY.toFloat(), toggleW.toFloat(), toggleH.toFloat(), 7f, toggleBg)
        // Animated knob position (symmetric travel)
        val target = if (enabled) 1f else 0f
        val anim = toggleAnimations.getOrPut(value) { target }
        val newAnim = lerp(anim, target, (animSpeed() * 0.25f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        toggleAnimations[value] = newAnim
        val knobTravel = toggleW - knobSize - 2 * knobPadding
        val knobX = toggleX + knobPadding + knobTravel * newAnim
        val knobY = toggleY + (toggleH - knobSize) / 2
        RoundedUtil.drawRound(knobX.toFloat(), knobY.toFloat(), knobSize.toFloat(), knobSize.toFloat(), 5f, Color.WHITE)
    }

    private fun drawNumberValue(
        current: Float, min: Float, max: Float, text: String,
        x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int, value: Value<*>
    ) {
        val sliderX = x + w - 110
        val sliderY = y + (rowHeight - 3) / 2 // center 3px track in row
        val sliderW = 80
        val textW = Fonts.font30.getStringWidth(text)
        // Center text vertically with row
        val textY = y + (rowHeight - 8) / 2 + 1
        Fonts.font30.drawString(text, (x + w - textW - 4).toFloat(), textY.toFloat(), MUTED.rgb)

        val targetProgress = if (max == min) 0f else ((current - min) / (max - min)).coerceIn(0f, 1f)
        // Animate slider thumb (skip animation during active drag for responsiveness)
        val displayedProgress = if (draggingSlider === value) {
            targetProgress
        } else {
            val cur = sliderDisplayProgress.getOrPut(value) { targetProgress }
            val newProgress = lerp(cur, targetProgress, animSpeed() * 0.25f)
            sliderDisplayProgress[value] = newProgress
            newProgress
        }

        RoundedUtil.drawRound(sliderX.toFloat(), sliderY.toFloat(), sliderW.toFloat(), 3f, 2f, SLIDER_TRACK)
        RoundedUtil.drawRound(sliderX.toFloat(), sliderY.toFloat(), (sliderW * displayedProgress), 3f, 2f, accentColor())
        // Glow effect on slider thumb
        val thumbCX = (sliderX + sliderW * displayedProgress).toFloat()
        val thumbCY = sliderY.toFloat()
        if (ClickGUI.nlGlowIntensity > 0f) GlowUtils.drawGlow(thumbCX - 5, thumbCY - 5, 10f, 10f, glowRadius(24), Color(accentColor().red, accentColor().green, accentColor().blue, glowAlpha(240)))
        RoundedUtil.drawRound((sliderX + sliderW * displayedProgress - 5).toFloat(), (sliderY - 4).toFloat(), 10f, 10f, 5f, accentColor())

        // Highlight thumb on hover
        if (isHovered(sliderX - 4, sliderY - 6, sliderW + 8, 14, mouseX, mouseY) || draggingSlider === value) {
            RoundedUtil.drawRound((sliderX + sliderW * displayedProgress - 6).toFloat(), (sliderY - 5).toFloat(), 12f, 12f, 6f, Color(accentColor().red, accentColor().green, accentColor().blue, 60))
        }
    }

    private fun drawRangeValue(
        start: Float, end: Float, min: Float, max: Float, text: String,
        x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int, value: Value<*>
    ) {
        val sliderX = x + w - 110
        val sliderY = y + (rowHeight - 3) / 2
        val sliderW = 80
        val textW = Fonts.font30.getStringWidth(text)
        val textY = y + (rowHeight - 8) / 2 + 1
        Fonts.font30.drawString(text, (x + w - textW - 4).toFloat(), textY.toFloat(), MUTED.rgb)

        val targetStartProg = if (max == min) 0f else ((start - min) / (max - min)).coerceIn(0f, 1f)
        val targetEndProg = if (max == min) 0f else ((end - min) / (max - min)).coerceIn(0f, 1f)
        // Animate range thumbs (skip animation during active drag)
        val isDragging = draggingSlider === value
        val displayedStart = if (isDragging) targetStartProg else {
            val cur = rangeStartDisplayProgress.getOrPut(value) { targetStartProg }
            val newProg = lerp(cur, targetStartProg, animSpeed() * 0.25f)
            rangeStartDisplayProgress[value] = newProg
            newProg
        }
        val displayedEnd = if (isDragging) targetEndProg else {
            val cur = rangeEndDisplayProgress.getOrPut(value) { targetEndProg }
            val newProg = lerp(cur, targetEndProg, animSpeed() * 0.25f)
            rangeEndDisplayProgress[value] = newProg
            newProg
        }

        RoundedUtil.drawRound(sliderX.toFloat(), sliderY.toFloat(), sliderW.toFloat(), 3f, 2f, SLIDER_TRACK)
        RoundedUtil.drawRound((sliderX + sliderW * displayedStart).toFloat(), sliderY.toFloat(), (sliderW * (displayedEnd - displayedStart)), 3f, 2f, accentColor())
        // Glow effect on range slider thumbs
        val startThumbX = (sliderX + sliderW * displayedStart).toFloat()
        val endThumbX = (sliderX + sliderW * displayedEnd).toFloat()
        if (ClickGUI.nlGlowIntensity > 0f) {
            GlowUtils.drawGlow(startThumbX - 5, (sliderY - 5).toFloat(), 10f, 10f, glowRadius(24), Color(accentColor().red, accentColor().green, accentColor().blue, glowAlpha(240)))
            GlowUtils.drawGlow(endThumbX - 5, (sliderY - 5).toFloat(), 10f, 10f, glowRadius(24), Color(accentColor().red, accentColor().green, accentColor().blue, glowAlpha(240)))
        }
        RoundedUtil.drawRound((sliderX + sliderW * displayedStart - 5).toFloat(), (sliderY - 4).toFloat(), 10f, 10f, 5f, accentColor())
        RoundedUtil.drawRound((sliderX + sliderW * displayedEnd - 5).toFloat(), (sliderY - 4).toFloat(), 10f, 10f, 5f, accentColor())
    }

    private fun drawListValue(value: ListValue, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        val text = fitText(value.get(), 100)
        val ddX = x + w - 110
        val ddW = 106
        val ddH = 18
        val ddY = y + (rowHeight - ddH) / 2
        val ddHovered = isHovered(ddX, ddY, ddW, ddH, mouseX, mouseY)
        val open = openListValue === value

        RoundedUtil.drawRound(ddX.toFloat(), ddY.toFloat(), ddW.toFloat(), ddH.toFloat(), 4f, if (open || ddHovered) DD_BG_HOVER else DD_BG)
        // Center text vertically within the dropdown
        val textY = ddY + (ddH - 8) / 2 + 1
        Fonts.font30.drawString(text, (ddX + 8).toFloat(), textY.toFloat(), TEXT_RGB)
        // Arrow
        val arrow = if (open) "▲" else "▼"
        Fonts.font30.drawString(arrow, (ddX + ddW - 14).toFloat(), textY.toFloat(), MUTED.rgb)

        if (open && listExpandAnim > 0.01f) {
            val totalOptionsHeight = value.values.size * 20 + 4
            val animOptionsHeight = (totalOptionsHeight * listExpandAnim).roundToInt()
            if (animOptionsHeight > 0) {
                val optionY = ddY + ddH + 2
                RoundedUtil.drawRound(ddX.toFloat(), optionY.toFloat(), ddW.toFloat(), animOptionsHeight.toFloat(), 4f, colorWithAlpha(displayedBg, ClickGUI.nlBgOpacity))
                // Clip options within animated height
                enableScissor(ddX, optionY, ddX + ddW, optionY + animOptionsHeight)
                value.values.forEachIndexed { index, option ->
                    val optY = optionY + 2 + index * 20
                    if (optY + 20 > optionY && optY < optionY + animOptionsHeight) {
                        val optHovered = isHovered(ddX, optY, ddW, 20, mouseX, mouseY)
                        if (optHovered) {
                            RoundedUtil.drawRound((ddX + 2).toFloat(), optY.toFloat(), (ddW - 4).toFloat(), 20f, 3f, DD_OPT_HOVER)
                        }
                        val isSelected = option.equals(value.get(), ignoreCase = true)
                        Fonts.font30.drawString(fitText(option, ddW - 12), (ddX + 8).toFloat(), (optY + 6).toFloat(), if (isSelected) accentColor().rgb else TEXT_RGB)
                    }
                }
                disableScissor()
            }
        }
    }

    private fun drawTextValue(value: TextValue, x: Int, y: Int, w: Int) {
        val editing = focusedTextValue === value
        val rawText = if (editing) focusedTextBuffer else value.get()
        val displayText = if (rawText.isEmpty() && !editing) "..." else rawText
        val text = fitText(displayText, 100)
        val inputX = x + w - 110
        val inputW = 106
        val inputH = 18
        val inputY = y + (rowHeight - inputH) / 2

        RoundedUtil.drawRound(inputX.toFloat(), inputY.toFloat(), inputW.toFloat(), inputH.toFloat(), 4f, if (editing) colorWithAlpha(displayedBg, ClickGUI.nlBgOpacity) else DD_BG)
        val textW = Fonts.font30.getStringWidth(text)
        val textDrawX = inputX + inputW - textW - 6
        val textY = inputY + (inputH - 8) / 2 + 1
        Fonts.font30.drawString(text, textDrawX.toFloat(), textY.toFloat(), if (editing) TEXT_RGB else MUTED.rgb)

        if (editing && !focusedTextSelected && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            // Cursor should be right after the last character
            val caretX = (textDrawX + textW + 1).coerceIn(inputX + 4, inputX + inputW - 4)
            RenderUtils.drawRect(caretX.toFloat(), (inputY + 3).toFloat(), (caretX + 1).toFloat(), (inputY + inputH - 3).toFloat(), accentColor())
        }
    }

    private fun drawFontValue(value: FontValue, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        val text = fitText(value.displayName, 100)
        val ddX = x + w - 110
        val ddW = 106
        val ddH = 18
        val ddY = y + (rowHeight - ddH) / 2
        val ddHovered = isHovered(ddX, ddY, ddW, ddH, mouseX, mouseY)
        RoundedUtil.drawRound(ddX.toFloat(), ddY.toFloat(), ddW.toFloat(), ddH.toFloat(), 4f, if (ddHovered) DD_BG_HOVER else DD_BG)
        val textY = ddY + (ddH - 8) / 2 + 1
        Fonts.font30.drawString(text, (ddX + 8).toFloat(), textY.toFloat(), TEXT_RGB)
        Fonts.font30.drawString("◀▶", (ddX + ddW - 18).toFloat(), textY.toFloat(), MUTED.rgb)
    }

    private fun drawColorValue(value: ColorValue, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int) {
        val color = value.selectedColor()
        val previewSize = 14
        val previewX = x + w - 18
        val previewY = y + (rowHeight - previewSize) / 2

        // Track the position for the color picker popup
        if (expandedColorValue === value) {
            colorPickerX = x
            colorPickerY = y + rowHeight
        }

        RoundedUtil.drawRound(previewX.toFloat(), previewY.toFloat(), previewSize.toFloat(), previewSize.toFloat(), 4f, color)
        val hex = if (value.rainbow) "Rainbow" else "#%02X%02X%02X".format(color.red, color.green, color.blue)
        val text = fitText(hex, 80)
        val textY = y + (rowHeight - 8) / 2 + 1
        Fonts.font30.drawString(text, (previewX - Fonts.font30.getStringWidth(text) - 6).toFloat(), textY.toFloat(), if (value.rainbow) accentColor().rgb else MUTED.rgb)

        // Small hue bar indicator
        if (expandedColorValue !== value) {
            val hueBarX = x + w - 130
            val hueBarY = y + rowHeight - 2
            val hueBarW = 100
            for (i in 0 until hueBarW) {
                val hueColor = Color(Color.HSBtoRGB(i / hueBarW.toFloat(), 1f, 1f))
                RenderUtils.drawRect((hueBarX + i).toFloat(), hueBarY.toFloat(), (hueBarX + i + 1).toFloat(), (hueBarY + 2).toFloat(), hueColor.rgb)
            }
        }
    }

    // ============ Color Picker (expanded) ============

    private fun drawColorPicker(mouseX: Int, mouseY: Int) {
        val value = expandedColorValue ?: return
        // Position below the color value row
        val pickerX = colorPickerX
        val pickerY = colorPickerY
        val pickerW = 200
        val pickerH = 140

        // Ensure picker stays within GUI bounds
        val px = pickerX.coerceIn(posX + sidebarWidth + 10, posX + guiWidth - pickerW - 10)
        val py = pickerY.coerceIn(posY + headerHeight, posY + guiHeight - pickerH - 10)

        RoundedUtil.drawRound(px.toFloat(), py.toFloat(), pickerW.toFloat(), pickerH.toFloat(), 6f, colorWithAlpha(displayedBg, ClickGUI.nlBgOpacity))
        Fonts.font30.drawString(value.name, (px + 8).toFloat(), (py + 6).toFloat(), TEXT_RGB)

        // Hue bar
        val hueBarX = px + 8
        val hueBarY = py + 30
        val hueBarW = pickerW - 16
        val hueBarH = 8
        for (i in 0 until hueBarW) {
            val hueColor = Color(Color.HSBtoRGB(i / hueBarW.toFloat(), 1f, 1f))
            RenderUtils.drawRect((hueBarX + i).toFloat(), hueBarY.toFloat(), (hueBarX + i + 1).toFloat(), (hueBarY + hueBarH).toFloat(), hueColor.rgb)
        }
        val hueMarkerX = hueBarX + (value.hueSliderY.coerceIn(0f, 1f) * hueBarW).roundToInt()
        RenderUtils.drawRect((hueMarkerX - 1).toFloat(), (hueBarY - 2).toFloat(), (hueMarkerX + 1).toFloat(), (hueBarY + hueBarH + 2).toFloat(), TEXT.rgb)

        // Saturation/Brightness box
        val sbX = px + 8
        val sbY = py + 48
        val sbW = pickerW - 16
        val sbH = 60
        for (i in 0 until sbW) {
            for (j in 0 until sbH) {
                val sat = i / sbW.toFloat()
                val bri = 1f - j / sbH.toFloat()
                val c = Color(Color.HSBtoRGB(value.hueSliderY, sat, bri))
                RenderUtils.drawRect((sbX + i).toFloat(), (sbY + j).toFloat(), (sbX + i + 1).toFloat(), (sbY + j + 1).toFloat(), c.rgb)
            }
        }
        val markerX = sbX + (value.colorPickerPos.x * sbW).roundToInt()
        val markerY = sbY + (value.colorPickerPos.y * sbH).roundToInt()
        RenderUtils.drawRect((markerX - 2).toFloat(), (markerY - 2).toFloat(), (markerX + 2).toFloat(), (markerY + 2).toFloat(), TEXT.rgb)

        // Rainbow toggle
        Fonts.font30.drawString("Rainbow: ${if (value.rainbow) "ON" else "OFF"}", (px + 8).toFloat(), (py + pickerH - 16).toFloat(), if (value.rainbow) accentColor().rgb else MUTED.rgb)
    }

    private fun drawOpenList(mouseX: Int, mouseY: Int) {
        // List dropdowns are drawn inline in drawListValue, this is a placeholder
    }

    // ============ Gear Popup (Bind/Hide/Reset) ============

    private fun drawGearPopup(mouseX: Int, mouseY: Int) {
        val module = gearPopupModule ?: return
        val popupW = 180
        val popupH = 120
        val px = gearPopupX
        val py = gearPopupY

        // Animation scale
        val scale = 0.9f + 0.1f * gearPopupAnim
        GlStateManager.pushMatrix()
        GlStateManager.translate((px + popupW / 2).toFloat(), (py + popupH / 2).toFloat(), 0f)
        GlStateManager.scale(scale, scale, scale)
        GlStateManager.translate(-(px + popupW / 2).toFloat(), -(py + popupH / 2).toFloat(), 0f)

        // Shadow
        for (i in 8 downTo 1) {
            val alpha = (15 * (1f - i / 8f)).toInt()
            RenderUtils.drawRect(
                (px - i).toFloat(), (py - i).toFloat(),
                (px + popupW + i).toFloat(), (py + popupH + i).toFloat(),
                Color(0, 0, 0, alpha).rgb
            )
        }

        RoundedUtil.drawRound(px.toFloat(), py.toFloat(), popupW.toFloat(), popupH.toFloat(), 6f, colorWithAlpha(displayedBg, ClickGUI.nlBgOpacity))

        // Header
        Fonts.font30.drawString(module.name, (px + 10).toFloat(), (py + 8).toFloat(), TEXT_RGB)

        // Bind row
        val bindY = py + 30
        val bindHovered = isHovered(px + 6, bindY, popupW - 12, 22, mouseX, mouseY)
        if (bindHovered) RoundedUtil.drawRound((px + 6).toFloat(), bindY.toFloat(), (popupW - 12).toFloat(), 22f, 4f, DD_OPT_HOVER)
        Fonts.font30.drawString("Bind", (px + 12).toFloat(), (bindY + 7).toFloat(), TEXT_RGB)
        val listening = keybindListeningModule === module
        val keyName = if (listening) "Listening..." else if (module.keyBind == 0) "None" else Keyboard.getKeyName(module.keyBind)
        Fonts.font30.drawString(keyName, (px + popupW - Fonts.font30.getStringWidth(keyName) - 12).toFloat(), (bindY + 7).toFloat(), if (listening) accentColor().rgb else MUTED.rgb)

        // Hide row
        val hideY = py + 56
        val hideHovered = isHovered(px + 6, hideY, popupW - 12, 22, mouseX, mouseY)
        if (hideHovered) RoundedUtil.drawRound((px + 6).toFloat(), hideY.toFloat(), (popupW - 12).toFloat(), 22f, 4f, DD_OPT_HOVER)
        Fonts.font30.drawString("Hide", (px + 12).toFloat(), (hideY + 7).toFloat(), TEXT_RGB)
        val hideText = if (module.isHidden) "ON" else "OFF"
        Fonts.font30.drawString(hideText, (px + popupW - Fonts.font30.getStringWidth(hideText) - 12).toFloat(), (hideY + 7).toFloat(), if (module.isHidden) accentColor().rgb else MUTED.rgb)

        // Reset row
        val resetY = py + 82
        val resetHovered = isHovered(px + 6, resetY, popupW - 12, 22, mouseX, mouseY)
        if (resetHovered) RoundedUtil.drawRound((px + 6).toFloat(), resetY.toFloat(), (popupW - 12).toFloat(), 22f, 4f, DD_OPT_HOVER)
        Fonts.font30.drawString("Reset", (px + 12).toFloat(), (resetY + 7).toFloat(), if (resetHovered) Color(255, 100, 100).rgb else TEXT_RGB)

        GlStateManager.popMatrix()
    }

    // ============ Right Panel ============

    private fun drawRightPanel(mouseX: Int, mouseY: Int) {
        val panelX = posX + guiWidth + 8
        val panelY = posY
        val panelH = guiHeight

        GlStateManager.pushMatrix()
        val offset = (1f - rightPanelAnim) * 20f
        GlStateManager.translate(offset, 0f, 0f)
        GlStateManager.color(1f, 1f, 1f, rightPanelAnim)

        // Blur (HudBlur InternalBlur)
        if (ClickGUI.nlBlur) {
            GlStateManager.pushMatrix()
            HudBlur.blur(
                panelX.toFloat(), panelY.toFloat(),
                (panelX + rightPanelWidth).toFloat(), (panelY + panelH).toFloat(),
                ClickGUI.nlBlurStrength, "InternalBlur", 12f
            )
            GlStateManager.popMatrix()
        }

        RoundedUtil.drawRound(panelX.toFloat(), panelY.toFloat(), rightPanelWidth.toFloat(), panelH.toFloat(), 12f, colorWithAlpha(displayedBg, ClickGUI.nlBgOpacity))

        // Header
        Fonts.font35.drawString("About AirClient", (panelX + 16).toFloat(), (panelY + 18).toFloat(), MUTED.rgb)
        // Close button
        val closeX = panelX + rightPanelWidth - 24
        val closeY = panelY + 14
        val closeHovered = isHovered(closeX, closeY, 16, 16, mouseX, mouseY)
        Fonts.font35.drawString("✕", closeX.toFloat(), (closeY + 2).toFloat(), if (closeHovered) TEXT.rgb else MUTED.rgb)

        // Logo (larger font)
        Fonts.font52.drawString("AIRCLIENT", (panelX + 12).toFloat(), (panelY + 44).toFloat(), TEXT.rgb)

        // Info list
        val infoY = panelY + 80
        drawInfoRow("Username:", mc.thePlayer?.name ?: "Offline", panelX + 16, infoY, rightPanelWidth - 32)
        drawInfoRow("Version:", AirPlus.clientVersionText, panelX + 16, infoY + 22, rightPanelWidth - 32)
        drawInfoRow("Time:", sdf.format(Date()), panelX + 16, infoY + 44, rightPanelWidth - 32)

        // Copyright
        Fonts.font30.drawString("AirClient © 2026", (panelX + 16).toFloat(), (panelY + panelH - 180).toFloat(), MUTED.rgb)

        // Divider
        RenderUtils.drawRect((panelX + 16).toFloat(), (panelY + panelH - 165).toFloat(), (panelX + rightPanelWidth - 16).toFloat(), (panelY + panelH - 164).toFloat(), DIVIDER)

        // Settings list
        var setY = panelY + panelH - 155
        // Theme dots (replaces Style row and Accent Color row)
        Fonts.font30.drawString("Theme", (panelX + 16).toFloat(), (setY + 7).toFloat(), TEXT_RGB)
        val themes = ClickGUI.nlThemeAccents
        val dotSize = 12
        val dotGap = 4
        val dotsTotalW = themes.size * dotSize + (themes.size - 1) * dotGap
        var dotX = panelX + rightPanelWidth - 16 - dotsTotalW
        val dotY = setY + 4
        themes.forEach { (name, color) ->
            val isSelected = name == ClickGUI.nlTheme
            if (isSelected) {
                // Glow ring around selected theme
                if (ClickGUI.nlGlowIntensity > 0f) GlowUtils.drawGlow(dotX.toFloat(), dotY.toFloat(), dotSize.toFloat(), dotSize.toFloat(), glowRadius(26), Color(color.red, color.green, color.blue, glowAlpha(255)))
                RoundedUtil.drawRound((dotX - 3).toFloat(), (dotY - 3).toFloat(), (dotSize + 6).toFloat(), (dotSize + 6).toFloat(), 8f, Color(color.red, color.green, color.blue, 80))
            }
            RoundedUtil.drawRound(dotX.toFloat(), dotY.toFloat(), dotSize.toFloat(), dotSize.toFloat(), 6f, color)
            dotX += dotSize + dotGap
        }
        setY += 28

        // Glow intensity slider
        drawRightPanelSlider("Glow", ClickGUI.nlGlowIntensity, 0f, 1f, panelX + 16, setY, rightPanelWidth - 32, "%.0f%%".format(ClickGUI.nlGlowIntensity * 100), mouseX, mouseY, "glowIntensity")
        setY += 34

        // Animation Speed slider
        drawRightPanelSlider("Animation Speed", ClickGUI.nlAnimationSpeed, 0.1f, 4.0f, panelX + 16, setY, rightPanelWidth - 32, "%.1f".format(ClickGUI.nlAnimationSpeed), mouseX, mouseY, "animSpeed")
        setY += 34

        // Background Opacity slider
        drawRightPanelSlider("BG Opacity", ClickGUI.nlBgOpacity, 0.1f, 1.0f, panelX + 16, setY, rightPanelWidth - 32, "%.0f%%".format(ClickGUI.nlBgOpacity * 100), mouseX, mouseY, "bgOpacity")
        setY += 34

        // Background Blur toggle
        Fonts.font30.drawString("Background Blur", (panelX + 16).toFloat(), (setY + 7).toFloat(), TEXT_RGB)
        val blurOn = ClickGUI.nlBlur
        val bToggleX = panelX + rightPanelWidth - 34
        val bToggleY = setY + 4
        RoundedUtil.drawRound(bToggleX.toFloat(), bToggleY.toFloat(), 30f, 16f, 8f, if (blurOn) accentColor() else TOGGLE_OFF)
        RoundedUtil.drawRound((bToggleX + if (blurOn) 16 else 2).toFloat(), (bToggleY + 2).toFloat(), 12f, 12f, 6f, Color.WHITE)

        GlStateManager.popMatrix()
        GlStateManager.color(1f, 1f, 1f, 1f)
    }

    private fun drawRightPanelSlider(
        label: String, current: Float, min: Float, max: Float,
        x: Int, y: Int, w: Int, text: String, mouseX: Int, mouseY: Int, id: String
    ) {
        Fonts.font30.drawString(label, x.toFloat(), (y + 7).toFloat(), TEXT_RGB)
        Fonts.font30.drawString(text, (x + w - Fonts.font30.getStringWidth(text)).toFloat(), (y + 7).toFloat(), MUTED.rgb)

        val sliderX = x
        val sliderY = y + 22
        val sliderW = w
        val progress = if (max == min) 0f else ((current - min) / (max - min)).coerceIn(0f, 1f)
        RoundedUtil.drawRound(sliderX.toFloat(), sliderY.toFloat(), sliderW.toFloat(), 3f, 2f, SLIDER_TRACK)
        RoundedUtil.drawRound(sliderX.toFloat(), sliderY.toFloat(), (sliderW * progress), 3f, 2f, accentColor())
        RoundedUtil.drawRound((sliderX + sliderW * progress - 5).toFloat(), (sliderY - 4).toFloat(), 10f, 10f, 5f, accentColor())
    }

    private fun drawInfoRow(label: String, value: String, x: Int, y: Int, w: Int) {
        Fonts.font30.drawString(label, x.toFloat(), y.toFloat(), MUTED.rgb)
        val valueText = fitText(value, w - Fonts.font30.getStringWidth(label) - 10)
        Fonts.font30.drawString(valueText, (x + w - Fonts.font30.getStringWidth(valueText)).toFloat(), y.toFloat(), accentColor().rgb)
    }

    // ============ Input Handling ============

    @Throws(IOException::class)
    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        // If a text value was focused, commit it on outside click
        if (focusedTextValue != null && !isHoveredTextValue(mouseX, mouseY)) {
            commitFocusedTextValue()
        }

        // Click outside list dropdown closes it
        if (openListValue != null && !isHoveredListValue(mouseX, mouseY)) {
            openListValue = null
        }

        // Click outside color picker closes it
        if (expandedColorValue != null && !isHoveredColorValue(mouseX, mouseY)) {
            expandedColorValue = null
        }

        // Gear popup clicks
        if (gearPopupModule != null && handleGearPopupClick(mouseX, mouseY, mouseButton)) {
            return
        }

        // Click outside gear popup closes it
        if (gearPopupModule != null && !isHoveredGearPopup(mouseX, mouseY)) {
            gearPopupModule = null
            keybindListeningModule = null
        }

        // Top bar clicks
        if (handleTopBarClick(mouseX, mouseY, mouseButton)) return

        // Sidebar clicks
        if (handleSidebarClick(mouseX, mouseY, mouseButton)) return

        // Right panel clicks
        if (rightPanelOpen && handleRightPanelClick(mouseX, mouseY, mouseButton)) return

        // Content clicks
        handleContentClick(mouseX, mouseY, mouseButton)

        super.mouseClicked(mouseX, mouseY, mouseButton)
    }

    private fun handleTopBarClick(mouseX: Int, mouseY: Int, mouseButton: Int): Boolean {
        if (mouseButton != 0) return false
        val topY = posY + 8

        // Search icon position (matches drawTopBar)
        val searchIconSize = 14
        val searchIconX = posX + guiWidth - 20
        val searchIconY = topY + 4
        val searchExpandedW = 120
        val searchW = (searchExpandedW * searchExpandAnim).toInt()
        val searchBoxX = searchIconX - searchW - 6

        // Search icon click: toggle expand
        if (isHovered(searchIconX, searchIconY, searchIconSize, searchIconSize, mouseX, mouseY)) {
            searchExpanded = !searchExpanded
            if (searchExpanded) searchFocused = true
            else { searchFocused = false; search = "" }
            return true
        }

        // Search box click: focus for typing
        if (searchW > 2 && isHovered(searchBoxX, topY, searchW, 20, mouseX, mouseY)) {
            searchFocused = true
            return true
        }
        searchFocused = false

        // Settings gear (left of search area)
        val gearSize = 14
        val gearX = searchBoxX - gearSize - 6
        val gearY = topY + 4
        if (isHovered(gearX, gearY, gearSize, gearSize, mouseX, mouseY)) {
            rightPanelOpen = !rightPanelOpen
            return true
        }

        // Drag header
        if (isHovered(posX + sidebarWidth, posY, guiWidth - sidebarWidth, headerHeight, mouseX, mouseY)) {
            dragging = true
            dragX = posX - mouseX
            dragY = posY - mouseY
            return true
        }
        return false
    }

    private fun handleSidebarClick(mouseX: Int, mouseY: Int, mouseButton: Int): Boolean {
        if (mouseButton != 0) return false
        // Drag from sidebar logo area
        if (isHovered(posX, posY, sidebarWidth, 46, mouseX, mouseY)) {
            dragging = true
            dragX = posX - mouseX
            dragY = posY - mouseY
            return true
        }

        var y = posY + 50
        Category.entries.filter { it.shouldShow() }.forEach { category ->
            if (isHovered(posX + 8, y - 4, sidebarWidth - 16, 20, mouseX, mouseY)) {
                if (selectedCategory != category) {
                    selectedCategory = category
                    categorySwitchAnim = 0f
                    targetContentScroll = 0
                }
                return true
            }
            y += 26
        }
        return false
    }

    private fun handleRightPanelClick(mouseX: Int, mouseY: Int, mouseButton: Int): Boolean {
        if (mouseButton != 0) return false
        val panelX = posX + guiWidth + 8
        val panelY = posY
        if (!isHovered(panelX, panelY, rightPanelWidth, guiHeight, mouseX, mouseY)) return false

        // Close button
        val closeX = panelX + rightPanelWidth - 24
        val closeY = panelY + 14
        if (isHovered(closeX, closeY, 16, 16, mouseX, mouseY)) {
            rightPanelOpen = false
            return true
        }

        val sliderX = panelX + 16
        val sliderW = rightPanelWidth - 32

        // Settings in right panel - positions must match drawRightPanel
        var setY = panelY + guiHeight - 155

        // Theme dots - click to switch theme
        val themes = ClickGUI.nlThemeAccents
        val dotSize = 12
        val dotGap = 4
        val dotsTotalW = themes.size * dotSize + (themes.size - 1) * dotGap
        var dotX = panelX + rightPanelWidth - 16 - dotsTotalW
        val dotY = setY + 4
        themes.forEach { (name, _) ->
            if (isHovered(dotX - 3, dotY - 3, dotSize + 6, dotSize + 6, mouseX, mouseY)) {
                ClickGUI.nlThemeValue.set(name, false)
                valuesConfigDirty = true
                return true
            }
            dotX += dotSize + dotGap
        }
        setY += 28

        // Glow intensity slider
        if (isHovered(sliderX, setY + 18, sliderW, 16, mouseX, mouseY)) {
            rightPanelDraggingId = "glowIntensity"
            setRightPanelSliderByMouse(mouseX, sliderX, sliderW, "glowIntensity")
            return true
        }
        setY += 34

        // Animation Speed slider (slider track at setY + 22)
        if (isHovered(sliderX, setY + 18, sliderW, 16, mouseX, mouseY)) {
            rightPanelDraggingId = "animSpeed"
            setRightPanelSliderByMouse(mouseX, sliderX, sliderW, "animSpeed")
            return true
        }
        setY += 34

        // BG Opacity slider
        if (isHovered(sliderX, setY + 18, sliderW, 16, mouseX, mouseY)) {
            rightPanelDraggingId = "bgOpacity"
            setRightPanelSliderByMouse(mouseX, sliderX, sliderW, "bgOpacity")
            return true
        }
        setY += 34

        // Background Blur toggle
        val bToggleX = panelX + rightPanelWidth - 34
        val bToggleY = setY + 4
        if (isHovered(bToggleX, bToggleY, 30, 16, mouseX, mouseY)) {
            ClickGUI.nlBlurValue.set(!ClickGUI.nlBlur, false)
            valuesConfigDirty = true
            return true
        }

        return true
    }

    private fun setRightPanelSliderByMouse(mouseX: Int, sliderX: Int, sliderW: Int, id: String) {
        val progress = ((mouseX - sliderX).toFloat() / sliderW).coerceIn(0f, 1f)
        when (id) {
            "glowIntensity" -> {
                val v = progress // 0..1
                ClickGUI.nlGlowIntensityValue.set(v, false)
                valuesConfigDirty = true
            }
            "animSpeed" -> {
                val v = 0.1f + (4.0f - 0.1f) * progress
                ClickGUI.nlAnimationSpeedValue.set(v, false)
                valuesConfigDirty = true
            }
            "bgOpacity" -> {
                val v = 0.1f + (1.0f - 0.1f) * progress
                ClickGUI.nlBgOpacityValue.set(v, false)
                valuesConfigDirty = true
            }
        }
    }

    private fun handleContentClick(mouseX: Int, mouseY: Int, mouseButton: Int) {
        // Ignore clicks during category switch transition to avoid mismatch with rendering offset
        if (categorySwitchAnim < 0.9f) return

        val contentX = posX + sidebarWidth + 14
        val contentY = posY + headerHeight + 10
        val contentW = guiWidth - sidebarWidth - 28
        val colW = (contentW - columnGap) / 2

        val modules = filteredModules()
        if (modules.isEmpty()) return

        val columns = arrayOf(mutableListOf<Module>(), mutableListOf<Module>())
        val colHeights = floatArrayOf(0f, 0f)
        modules.forEach { module ->
            val targetCol = if (colHeights[0] <= colHeights[1]) 0 else 1
            columns[targetCol].add(module)
            colHeights[targetCol] = colHeights[targetCol] + moduleSectionHeight(module).toFloat()
        }

        for (colIndex in 0..1) {
            var y = contentY + contentScroll
            val x = if (colIndex == 0) contentX else contentX + colW + columnGap
            val visibleBottom = posY + guiHeight - 10
            columns[colIndex].forEach { module ->
                if (y + moduleSectionHeight(module) > contentY && y < visibleBottom) {
                    if (handleModuleClick(module, x, y, colW, mouseX, mouseY, mouseButton)) return
                }
                y += moduleSectionHeight(module)
            }
        }
    }

    private fun handleModuleClick(module: Module, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int, mouseButton: Int): Boolean {
        var rowY = y + 22

        // Enabled row
        if (mouseButton == 0 && isHovered(x, rowY, w, rowHeight, mouseX, mouseY)) {
            // Gear click (matches drawEnabledRow: gearX = x + w - 52, gearY = rowY + 4)
            val gearX = x + w - 52
            if (isHovered(gearX, rowY + 4, 14, 14, mouseX, mouseY)) {
                gearPopupModule = module
                gearPopupX = mouseX - 90
                gearPopupY = mouseY + 10
                keybindListeningModule = null
                return true
            }
            // Toggle click (matches drawEnabledRow: toggleX = x + w - 32, toggleY = rowY + (rowHeight-14)/2)
            val toggleX = x + w - 32
            val toggleY = rowY + (rowHeight - 14) / 2
            if (isHovered(toggleX, toggleY, 28, 14, mouseX, mouseY)) {
                module.toggle()
                modulesConfigDirty = true
                return true
            }
            // Click on row label also toggles
            module.toggle()
            modulesConfigDirty = true
            return true
        }
        rowY += rowHeight + rowGap

        // Value rows - filter out subjective values (Hide, etc.) since they're in the gear popup
        val values = module.values.filter { it.shouldRender() && !it.subjective }
        values.forEach { value ->
            val vh = valueDisplayHeight(value)
            // For ListValue, extend click area to include dropdown options
            val clickH = if (value is ListValue && openListValue === value) vh else rowHeight
            if (isHovered(x, rowY, w, clickH, mouseX, mouseY)) {
                handleValueClick(value, x, rowY, w, mouseX, mouseY, mouseButton)
                return true
            }
            rowY += vh + rowGap
        }
        return false
    }

    private fun handleValueClick(value: Value<*>, x: Int, y: Int, w: Int, mouseX: Int, mouseY: Int, mouseButton: Int) {
        when (value) {
            is BoolValue -> {
                if (mouseButton == 0) {
                    value.set(!value.get(), false)
                    valuesConfigDirty = true
                }
            }
            is IntValue -> {
                if (mouseButton == 0) {
                    val sliderX = x + w - 110
                    val sliderW = 80
                    val progress = ((mouseX - sliderX).toFloat() / sliderW).coerceIn(0f, 1f)
                    value.set((value.minimum + (value.maximum - value.minimum) * progress).roundToInt(), false)
                    draggingSlider = value
                    valuesConfigDirty = true
                }
            }
            is FloatValue -> {
                if (mouseButton == 0) {
                    val sliderX = x + w - 110
                    val sliderW = 80
                    val progress = ((mouseX - sliderX).toFloat() / sliderW).coerceIn(0f, 1f)
                    value.set(value.minimum + (value.maximum - value.minimum) * progress, false)
                    draggingSlider = value
                    valuesConfigDirty = true
                }
            }
            is IntRangeValue -> {
                if (mouseButton == 0) {
                    val sliderX = x + w - 110
                    val sliderW = 80
                    val progress = ((mouseX - sliderX).toFloat() / sliderW).coerceIn(0f, 1f)
                    val v = (value.minimum + (value.maximum - value.minimum) * progress).roundToInt()
                    val current = value.get()
                    // Determine which thumb is closer; tie-break: prefer the one that allows movement
                    val distStart = kotlin.math.abs(v - current.first)
                    val distEnd = kotlin.math.abs(v - current.last)
                    draggingRangeThumb = if (distStart <= distEnd) "start" else "end"
                    updateIntRangeValue(value, v)
                    draggingSlider = value
                    valuesConfigDirty = true
                }
            }
            is FloatRangeValue -> {
                if (mouseButton == 0) {
                    val sliderX = x + w - 110
                    val sliderW = 80
                    val progress = ((mouseX - sliderX).toFloat() / sliderW).coerceIn(0f, 1f)
                    val v = value.minimum + (value.maximum - value.minimum) * progress
                    val current = value.get()
                    val distStart = kotlin.math.abs(v - current.start)
                    val distEnd = kotlin.math.abs(v - current.endInclusive)
                    draggingRangeThumb = if (distStart <= distEnd) "start" else "end"
                    updateFloatRangeValue(value, v)
                    draggingSlider = value
                    valuesConfigDirty = true
                }
            }
            is ListValue -> {
                if (mouseButton == 0) {
                    val ddX = x + w - 110
                    val ddW = 106
                    val ddH = 18
                    val ddY = y + (rowHeight - ddH) / 2
                    if (openListValue === value) {
                        // Check option click
                        val optionY = ddY + ddH + 2
                        value.values.forEachIndexed { index, option ->
                            val optY = optionY + 2 + index * 20
                            if (isHovered(ddX, optY, ddW, 20, mouseX, mouseY)) {
                                value.set(option, false)
                                valuesConfigDirty = true
                                openListValue = null
                                return
                            }
                        }
                        openListValue = null
                    } else {
                        openListValue = value
                    }
                } else if (mouseButton == 1) {
                    // Right click cycles backwards
                    val index = value.values.indexOfFirst { it.equals(value.get(), ignoreCase = true) }
                    value.set(value.values[(index - 1 + value.values.size).floorMod(value.values.size)], false)
                    valuesConfigDirty = true
                }
            }
            is TextValue -> {
                if (mouseButton == 0) {
                    focusTextValue(value)
                } else if (mouseButton == 1) {
                    focusTextValue(value)
                    focusedTextBuffer = ""
                    focusedTextSelected = false
                    valuesConfigDirty = true
                }
            }
            is FontValue -> {
                if (mouseButton == 0) {
                    value.next()
                    valuesConfigDirty = true
                } else if (mouseButton == 1) {
                    value.previous()
                    valuesConfigDirty = true
                }
            }
            is BlockValue -> {
                if (mouseButton == 0) {
                    val sliderX = x + w - 110
                    val sliderW = 80
                    val progress = ((mouseX - sliderX).toFloat() / sliderW).coerceIn(0f, 1f)
                    value.set((value.minimum + (value.maximum - value.minimum) * progress).roundToInt(), false)
                    draggingSlider = value
                    valuesConfigDirty = true
                }
            }
            is ColorValue -> {
                if (mouseButton == 0) {
                    if (expandedColorValue === value) {
                        // Handle picker clicks
                        handleColorPickerClick(value, mouseX, mouseY)
                    } else {
                        expandedColorValue = value
                    }
                } else if (mouseButton == 1) {
                    value.rainbow = !value.rainbow
                    value.set(value.selectedColor(), false)
                    valuesConfigDirty = true
                }
            }
            else -> {}
        }
    }

    private fun handleColorPickerClick(value: ColorValue, mouseX: Int, mouseY: Int) {
        val pickerW = 200
        val pickerH = 140
        val px = colorPickerX.coerceIn(posX + sidebarWidth + 10, posX + guiWidth - pickerW - 10)
        val py = colorPickerY.coerceIn(posY + headerHeight, posY + guiHeight - pickerH - 10)

        // Hue bar
        val hueBarX = px + 8
        val hueBarY = py + 30
        val hueBarW = pickerW - 16
        val hueBarH = 8
        if (isHovered(hueBarX, hueBarY, hueBarW, hueBarH, mouseX, mouseY)) {
            val hue = ((mouseX - hueBarX).toFloat() / hueBarW).coerceIn(0f, 1f)
            value.hueSliderY = hue
            updateColorFromSliders(value)
            valuesConfigDirty = true
            return
        }

        // Saturation/Brightness box
        val sbX = px + 8
        val sbY = py + 48
        val sbW = pickerW - 16
        val sbH = 60
        if (isHovered(sbX, sbY, sbW, sbH, mouseX, mouseY)) {
            val sat = ((mouseX - sbX).toFloat() / sbW).coerceIn(0f, 1f)
            val bri = 1f - ((mouseY - sbY).toFloat() / sbH).coerceIn(0f, 1f)
            value.colorPickerPos.set(sat, 1f - bri)
            updateColorFromSliders(value)
            valuesConfigDirty = true
            return
        }

        // Rainbow toggle area
        if (isHovered(px + 8, py + pickerH - 20, pickerW - 16, 18, mouseX, mouseY)) {
            value.rainbow = !value.rainbow
            value.set(value.selectedColor(), false)
            valuesConfigDirty = true
            return
        }

        // Click outside picker closes it
        expandedColorValue = null
    }

    private fun updateColorFromSliders(value: ColorValue) {
        val hue = value.hueSliderY
        val sat = value.colorPickerPos.x
        val bri = 1f - value.colorPickerPos.y
        val alpha = (value.opacitySliderY * 255).roundToInt().coerceIn(0, 255)
        value.rainbow = false
        value.set(Color(Color.HSBtoRGB(hue, sat, bri), true).let { Color(it.red, it.green, it.blue, alpha) }, false)
    }

    private fun handleGearPopupClick(mouseX: Int, mouseY: Int, mouseButton: Int): Boolean {
        val module = gearPopupModule ?: return false
        if (!isHoveredGearPopup(mouseX, mouseY)) return false
        if (mouseButton != 0) return true

        val px = gearPopupX
        val py = gearPopupY

        // Bind row
        val bindY = py + 30
        if (isHovered(px + 6, bindY, 168, 22, mouseX, mouseY)) {
            keybindListeningModule = if (keybindListeningModule === module) null else module
            return true
        }

        // Hide row
        val hideY = py + 56
        if (isHovered(px + 6, hideY, 168, 22, mouseX, mouseY)) {
            module.isHidden = !module.isHidden
            modulesConfigDirty = true
            return true
        }

        // Reset row
        val resetY = py + 82
        if (isHovered(px + 6, resetY, 168, 22, mouseX, mouseY)) {
            // Reset all module values
            module.values.forEach { it.resetValue() }
            valuesConfigDirty = true
            return true
        }

        return true
    }

    @Throws(IOException::class)
    override fun keyTyped(typedChar: Char, keyCode: Int) {
        // Keybind listening takes priority
        if (keybindListeningModule != null) {
            val module = keybindListeningModule!!
            if (keyCode == Keyboard.KEY_ESCAPE) {
                // ESC cancels binding - set to none (0)
                module.keyBind = 0
                keybindListeningModule = null
            } else {
                module.keyBind = keyCode
                keybindListeningModule = null
            }
            modulesConfigDirty = true
            return
        }

        // Text value editing
        if (focusedTextValue != null) {
            handleFocusedTextInput(typedChar, keyCode)
            return
        }

        // ESC closes GUI (unless color picker or list is open)
        if (keyCode == Keyboard.KEY_ESCAPE) {
            when {
                expandedColorValue != null -> expandedColorValue = null
                openListValue != null -> openListValue = null
                gearPopupModule != null -> {
                    gearPopupModule = null
                    keybindListeningModule = null
                }
                rightPanelOpen -> rightPanelOpen = false
                searchFocused -> searchFocused = false
                else -> mc.displayGuiScreen(null)
            }
            return
        }

        // Search input
        if (searchFocused || !Character.isISOControl(typedChar)) {
            when (keyCode) {
                Keyboard.KEY_BACK -> if (search.isNotEmpty()) search = search.dropLast(1)
                Keyboard.KEY_RETURN -> searchFocused = false
                else -> if (!Character.isISOControl(typedChar) && searchFocused) {
                    search += typedChar
                }
            }
            return
        }

        super.keyTyped(typedChar, keyCode)
    }

    override fun mouseClickMove(mouseX: Int, mouseY: Int, clickedMouseButton: Int, timeSinceLastClick: Long) {
        // Handle slider dragging
        if (clickedMouseButton == 0 && draggingSlider != null) {
            handleSliderDrag(draggingSlider!!, mouseX)
        }
        // Handle right panel slider dragging
        if (clickedMouseButton == 0 && rightPanelDraggingId != null) {
            val panelX = posX + guiWidth + 8
            val sliderX = panelX + 16
            val sliderW = rightPanelWidth - 32
            setRightPanelSliderByMouse(mouseX, sliderX, sliderW, rightPanelDraggingId!!)
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick)
    }

    private fun handleSliderDrag(value: Value<*>, mouseX: Int) {
        // Find the value's current position - this is tricky without storing it
        // For simplicity, we re-find the value in the visible modules
        val contentX = posX + sidebarWidth + 14
        val contentW = guiWidth - sidebarWidth - 28
        val colW = (contentW - columnGap) / 2
        val sliderW = 80

        // Search in both columns
        val modules = filteredModules()
        modules.forEach { module ->
            val values = module.values.filter { it.shouldRender() }
            if (value in values) {
                // Determine which column this module is in
                val columns = arrayOf(mutableListOf<Module>(), mutableListOf<Module>())
                val colHeights = floatArrayOf(0f, 0f)
                modules.forEach { m ->
                    val targetCol = if (colHeights[0] <= colHeights[1]) 0 else 1
                    columns[targetCol].add(m)
                    colHeights[targetCol] = colHeights[targetCol] + moduleSectionHeight(m).toFloat()
                }
                for (colIndex in 0..1) {
                    if (module in columns[colIndex]) {
                        val x = if (colIndex == 0) contentX else contentX + colW + columnGap
                        val sliderX = x + colW - 110
                        val progress = ((mouseX - sliderX).toFloat() / sliderW).coerceIn(0f, 1f)
                        when (value) {
                            is IntValue -> value.set((value.minimum + (value.maximum - value.minimum) * progress).roundToInt(), false)
                            is FloatValue -> value.set(value.minimum + (value.maximum - value.minimum) * progress, false)
                            is BlockValue -> value.set((value.minimum + (value.maximum - value.minimum) * progress).roundToInt(), false)
                            is IntRangeValue -> {
                                val v = (value.minimum + (value.maximum - value.minimum) * progress).roundToInt()
                                updateIntRangeValue(value, v)
                            }
                            is FloatRangeValue -> {
                                val v = value.minimum + (value.maximum - value.minimum) * progress
                                updateFloatRangeValue(value, v)
                            }
                            else -> {}
                        }
                        valuesConfigDirty = true
                        return
                    }
                }
            }
        }
    }

    // Update IntRangeValue with linkage: dragging start pushes end forward, dragging end pushes start backward
    private fun updateIntRangeValue(value: IntRangeValue, v: Int) {
        val current = value.get()
        when (draggingRangeThumb) {
            "start" -> {
                val newStart = v.coerceIn(value.minimum, value.maximum)
                if (newStart > current.last) {
                    // Start pushed past end: move end forward too (linkage)
                    value.set(newStart..newStart, false)
                } else {
                    value.setFirst(newStart, false)
                }
            }
            "end" -> {
                val newEnd = v.coerceIn(value.minimum, value.maximum)
                if (newEnd < current.first) {
                    // End pushed past start: move start backward too (linkage)
                    value.set(newEnd..newEnd, false)
                } else {
                    value.setLast(newEnd, false)
                }
            }
            else -> {
                // Fallback: determine by proximity
                val distStart = kotlin.math.abs(v - current.first)
                val distEnd = kotlin.math.abs(v - current.last)
                if (distStart <= distEnd) {
                    if (v > current.last) value.set(v..v, false)
                    else value.setFirst(v, false)
                } else {
                    if (v < current.first) value.set(v..v, false)
                    else value.setLast(v, false)
                }
            }
        }
    }

    // Update FloatRangeValue with linkage: dragging start pushes end forward, dragging end pushes start backward
    private fun updateFloatRangeValue(value: FloatRangeValue, v: Float) {
        val current = value.get()
        when (draggingRangeThumb) {
            "start" -> {
                val newStart = v.coerceIn(value.minimum, value.maximum)
                if (newStart > current.endInclusive) {
                    value.set(newStart..newStart, false)
                } else {
                    value.setFirst(newStart, false)
                }
            }
            "end" -> {
                val newEnd = v.coerceIn(value.minimum, value.maximum)
                if (newEnd < current.start) {
                    value.set(newEnd..newEnd, false)
                } else {
                    value.setLast(newEnd, false)
                }
            }
            else -> {
                val distStart = kotlin.math.abs(v - current.start)
                val distEnd = kotlin.math.abs(v - current.endInclusive)
                if (distStart <= distEnd) {
                    if (v > current.endInclusive) value.set(v..v, false)
                    else value.setFirst(v, false)
                } else {
                    if (v < current.start) value.set(v..v, false)
                    else value.setLast(v, false)
                }
            }
        }
    }

    override fun mouseReleased(mouseX: Int, mouseY: Int, state: Int) {
        if (state == 0) {
            dragging = false
            draggingSlider = null
            rightPanelDraggingId = null
            draggingRangeThumb = null
        }
        saveDirtyConfigs()
        super.mouseReleased(mouseX, mouseY, state)
    }

    private fun handleWheel(mouseX: Int, mouseY: Int) {
        val wheel = Mouse.getDWheel()
        if (wheel == 0) return

        val contentX = posX + sidebarWidth + 14
        val contentY = posY + headerHeight + 10
        val contentW = guiWidth - sidebarWidth - 28
        val contentH = guiHeight - headerHeight - 20

        if (isHovered(contentX, contentY, contentW, contentH, mouseX, mouseY)) {
            targetContentScroll = (targetContentScroll + if (wheel > 0) 30 else -30)
        }
    }

    // ============ Text Input Helpers ============

    private fun focusTextValue(value: TextValue) {
        if (focusedTextValue !== value) {
            commitFocusedTextValue()
            focusedTextValue = value
            focusedTextBuffer = value.get()
        }
        focusedTextSelected = false
        searchFocused = false
    }

    private fun commitFocusedTextValue() {
        val value = focusedTextValue ?: return
        if (value.set(focusedTextBuffer, false)) {
            valuesConfigDirty = true
        }
        focusedTextValue = null
        focusedTextBuffer = ""
        focusedTextSelected = false
    }

    private fun handleFocusedTextInput(typedChar: Char, keyCode: Int) {
        when (keyCode) {
            Keyboard.KEY_ESCAPE, Keyboard.KEY_RETURN -> {
                commitFocusedTextValue()
                saveDirtyConfigs()
                return
            }
            Keyboard.KEY_BACK -> {
                if (focusedTextSelected) {
                    focusedTextBuffer = ""
                    focusedTextSelected = false
                } else if (focusedTextBuffer.isNotEmpty()) {
                    focusedTextBuffer = focusedTextBuffer.dropLast(1)
                }
                valuesConfigDirty = true
                return
            }
            Keyboard.KEY_DELETE -> {
                if (focusedTextSelected || focusedTextBuffer.isNotEmpty()) {
                    focusedTextBuffer = ""
                    focusedTextSelected = false
                    valuesConfigDirty = true
                }
                return
            }
        }

        if (isCtrlKeyDown()) {
            when (keyCode) {
                Keyboard.KEY_A -> focusedTextSelected = focusedTextBuffer.isNotEmpty()
                Keyboard.KEY_V -> {
                    val clipboard = getClipboardString() ?: ""
                    if (clipboard.isNotEmpty()) {
                        focusedTextBuffer = if (focusedTextSelected) clipboard else focusedTextBuffer + clipboard
                        focusedTextSelected = false
                        valuesConfigDirty = true
                    }
                }
                Keyboard.KEY_C -> if (focusedTextSelected) setClipboardString(focusedTextBuffer)
            }
            return
        }

        if (!Character.isISOControl(typedChar)) {
            focusedTextBuffer = if (focusedTextSelected) typedChar.toString() else focusedTextBuffer + typedChar
            focusedTextSelected = false
            valuesConfigDirty = true
        }
    }

    // ============ Utility ============

    private fun filteredModules(): List<Module> {
        if (search.isBlank()) {
            return panels[selectedCategory].orEmpty()
        }
        // When searching, show results across ALL categories
        return Category.entries
            .filter { it.shouldShow() }
            .flatMap { category -> panels[category].orEmpty() }
            .filter { module ->
                module.name.contains(search, ignoreCase = true) ||
                module.spacedName.contains(search, ignoreCase = true)
            }
            .sortedBy { it.name.lowercase() }
    }

    private fun saveDirtyConfigs() {
        if (modulesConfigDirty) {
            saveConfig(modulesConfig)
            modulesConfigDirty = false
        }
        if (valuesConfigDirty) {
            saveConfig(valuesConfig)
            valuesConfigDirty = false
        }
    }

    private fun fitText(text: String, maxWidth: Int): String {
        if (Fonts.font35.getStringWidth(text) <= maxWidth) return text
        var clipped = text
        while (clipped.isNotEmpty() && Fonts.font35.getStringWidth("$clipped...") > maxWidth) {
            clipped = clipped.dropLast(1)
        }
        return if (clipped.isEmpty()) "..." else "$clipped..."
    }

    private fun isHovered(x: Int, y: Int, w: Int, h: Int, mouseX: Int, mouseY: Int): Boolean {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h
    }

    private fun isHoveredTextValue(mouseX: Int, mouseY: Int): Boolean {
        // Check if mouse is within the text input area of the focused text value
        // We don't track exact position, so check if click is in the content area
        return focusedTextValue != null
    }

    private fun isHoveredListValue(mouseX: Int, mouseY: Int): Boolean {
        // Check if mouse is within the currently open list dropdown area
        val value = openListValue ?: return false
        // The dropdown is rendered at x + w - 110, y + 4, width 106
        // We need to check the full area including expanded options
        // Since we don't track exact position here, use a generous check
        // The list dropdown is always in the right portion of the content area
        val contentX = posX + sidebarWidth + 16
        val contentRight = posX + guiWidth - 20
        return mouseX >= contentX && mouseX <= contentRight && mouseY >= posY + headerHeight && mouseY <= posY + guiHeight
    }

    private fun isHoveredColorValue(mouseX: Int, mouseY: Int): Boolean {
        val value = expandedColorValue ?: return false
        // Check if mouse is within the color picker area
        val pickerW = 200
        val pickerH = 140
        val px = colorPickerX.coerceIn(posX + sidebarWidth + 10, posX + guiWidth - pickerW - 10)
        val py = colorPickerY.coerceIn(posY + headerHeight, posY + guiHeight - pickerH - 10)
        return isHovered(px, py, pickerW, pickerH, mouseX, mouseY)
    }

    private fun isHoveredGearPopup(mouseX: Int, mouseY: Int): Boolean {
        return isHovered(gearPopupX, gearPopupY, 180, 120, mouseX, mouseY)
    }

    private fun accentColor(): Color = displayedAccent

    private fun animSpeed(): Float = ClickGUI.nlAnimationSpeed

    private fun colorWithAlpha(color: Color, alpha: Float): Color {
        return Color(color.red, color.green, color.blue, (255 * alpha).toInt().coerceIn(0, 255))
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun lerpColor(a: Color, b: Color, t: Float): Color {
        val r = (a.red + (b.red - a.red) * t).roundToInt().coerceIn(0, 255)
        val g = (a.green + (b.green - a.green) * t).roundToInt().coerceIn(0, 255)
        val bl = (a.blue + (b.blue - a.blue) * t).roundToInt().coerceIn(0, 255)
        val al = (a.alpha + (b.alpha - a.alpha) * t).roundToInt().coerceIn(0, 255)
        return Color(r, g, bl, al)
    }

    private fun Int.floorMod(mod: Int): Int = ((this % mod) + mod) % mod

    private fun enableScissor(x1: Int, y1: Int, x2: Int, y2: Int) {
        val factor = mc.gameSettings.guiScale
        val left = (x1 * factor).coerceAtLeast(0)
        val top = (mc.displayHeight - y2 * factor).coerceAtLeast(0)
        val right = (x2 * factor).coerceAtMost(mc.displayWidth)
        val bottom = (mc.displayHeight - y1 * factor).coerceAtMost(mc.displayHeight)
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST)
        org.lwjgl.opengl.GL11.glScissor(left, top, right - left, bottom - top)
    }

    private fun disableScissor() {
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST)
    }

    private fun drawGearIcon(x: Float, y: Float, color: Color) {
        RenderUtils.drawImage(SETTING_ICON, x.toInt(), y.toInt(), 14, 14, color)
    }

    private fun drawSearchIcon(x: Float, y: Float, color: Color) {
        RenderUtils.drawImage(SEARCH_ICON, x.toInt(), y.toInt(), 14, 14, color)
    }

    private fun drawCategoryIcon(category: Category, x: Float, y: Float, color: Color) {
        val res = when (category) {
            Category.COMBAT -> COMBAT_ICON
            Category.PLAYER -> PLAYER_ICON
            Category.MOVEMENT -> MOVEMENT_ICON
            Category.RENDER -> RENDER_ICON
            Category.WORLD -> WORLD_ICON
            Category.MISC -> MISC_ICON
            Category.CLIENT -> SETTING_ICON
        }
        RenderUtils.drawImage(res, x.toInt(), y.toInt(), 14, 14, color)
    }

    private companion object {
        // Persisted GUI state across sessions (position + selected category)
        var lastPosX: Int? = null
        var lastPosY: Int? = null
        var lastCategory: Category? = null
        var lastScroll: Int = 0
        var lastSearch: String = ""

        val DIVIDER = Color(0x1E1E2A)
        val ROW_HOVER = Color(255, 255, 255, 15)
        val DD_BG = Color(255, 255, 255, 10)
        val DD_BG_HOVER = Color(255, 255, 255, 18)
        val DD_OPT_HOVER = Color(255, 255, 255, 20)
        val SLIDER_TRACK = Color(0x2A2A36)
        val TOGGLE_OFF = Color(0x2A2A36)
        val TEXT = Color(0xF0F0F5)
        val TEXT_RGB: Int = Color(0xF0F0F5).rgb
        val MUTED = Color(0x6A6A78)
        val MODULE_TITLE = Color(0xE8E8F0)

        val SETTING_ICON = ResourceLocation("airplus/clickgui/setting.png")
        val SEARCH_ICON = ResourceLocation("airplus/clickgui/search2.png")
        val COMBAT_ICON = ResourceLocation("airplus/clickgui/combat.png")
        val PLAYER_ICON = ResourceLocation("airplus/clickgui/player.png")
        val MOVEMENT_ICON = ResourceLocation("airplus/clickgui/movement.png")
        val RENDER_ICON = ResourceLocation("airplus/clickgui/render.png")
        val WORLD_ICON = ResourceLocation("airplus/clickgui/world.png")
        val MISC_ICON = ResourceLocation("airplus/clickgui/misc.png")
    }
}
