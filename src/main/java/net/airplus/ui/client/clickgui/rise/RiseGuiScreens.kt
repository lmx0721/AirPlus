/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Rise 6.9.5 Modern ClickGUI port — screens (Category/Search/Theme/Info) + sidebar.
 * Ported from com.alan.clients.ui.click.standard.screen.impl.* and components.category.*
 */
package net.airplus.ui.client.clickgui.rise

import net.airplus.AirPlus
import net.airplus.features.module.Category
import net.airplus.features.module.modules.render.ClickGUI
import net.airplus.features.module.modules.render.ThemeManager
import net.airplus.utils.GlowUtils
import net.airplus.utils.client.ClientThemesUtils
import java.awt.Color

// ============================== CategoryScreen ==============================

class RiseCategoryScreen(private val gui: RiseClickGui, val category: Category) : RiseScreen {
    val scrollUtil = RiseScrollUtil()
    var relevantModules = ArrayList<RiseModuleComponent>()

    fun refresh() {
        relevantModules = ArrayList(gui.moduleList.filter { it.module.category == category })
    }

    override fun onRender(mouseX: Int, mouseY: Int, partialTicks: Float) {
        scrollUtil.update()
        var y = gui.y + 7.0 + scrollUtil.scroll
        var total = 0.0
        for (moduleComponent in relevantModules) {
            moduleComponent.draw(Pair(gui.x + 108.0, y), mouseX, mouseY, partialTicks)
            y += moduleComponent.scale.second + 7.0
            total += moduleComponent.scale.second + 7.0
        }

        scrollUtil.setMax(-total + gui.h - 7.0)
        // scrollbar
        val barX = gui.x + gui.w - 4.0
        val barY = gui.y + 7.0
        drawScrollbar(barX, barY, gui.h - 14.0, total)
    }

    private fun drawScrollbar(x: Double, y: Double, viewHeight: Double, total: Double) {
        val maxScroll = -total + gui.h - 7.0
        if (maxScroll >= 0.0) return
        val ratio = (scrollUtil.scroll / total).coerceIn(0.0, 1.0)
        val thumbH = viewHeight / (total / viewHeight + 1.0).coerceAtLeast(1.0)
        val thumbY = y - ratio * (viewHeight - thumbH)
        RiseRender.rounded(x, thumbY, 1.0, thumbH, 0.5, UIColors.withAlpha(Color.WHITE, 60))
    }

    override fun onKey(typedChar: Char, keyCode: Int) {
        relevantModules.forEach { it.key(typedChar, keyCode) }
    }

    override fun onClick(mouseX: Int, mouseY: Int, mouseButton: Int) {
        relevantModules.forEach { it.click(mouseX, mouseY, mouseButton) }
    }

    override fun onMouseRelease() {
        relevantModules.forEach { it.onMouseUp() }
    }

    override fun onBloom() {
        relevantModules.forEach { it.onRenderRelease() }
    }

    override fun onSwitch() {
        refresh()
        scrollUtil.reset()
    }

    override fun allowsAutoSearch() = true
}

// ============================== SearchScreen ==============================

class RiseSearchScreen(private val gui: RiseClickGui) : RiseScreen {
    val searchBar = RiseTextBox(RiseGuiFonts.module(), Color.WHITE, RiseTextAlign.CENTER, "Search...", 150f)
    private val timer = RiseStopWatch()
    val scrollUtil = RiseScrollUtil()
    var relevantModules = ArrayList<RiseModuleComponent>()
    private var textAlpha = 255.0
    private var typedWhileOpen = false

    fun refresh() {
        relevantModules = ArrayList(getRelevantModules(searchBar.text))
    }

    fun getRelevantModules(query: String): List<RiseModuleComponent> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase().replace(" ", "")
        return gui.moduleList.filter { mc ->
            mc.module.name.lowercase().replace(" ", "").contains(q) ||
                mc.module.description.lowercase().replace(" ", "").contains(q) ||
                mc.module.category.displayName.lowercase().replace(" ", "").contains(q)
        }
    }

    fun setSearchText(text: String) {
        searchBar.text = text
        refresh()
    }

    /** Called when typing a letter on another screen auto-switches here (Rise behavior). */
    fun typedChar(c: Char) {
        searchBar.text = searchBar.text + c
        searchBar.selected = true
        refresh()
    }

    override fun onRender(mouseX: Int, mouseY: Int, partialTicks: Float) {
        textAlpha = if (scrollUtil.getTarget() < 0.0) {
            textAlpha - timer.elapsedTime * 4.0
        } else {
            textAlpha + timer.elapsedTime * 4.0
        }
        textAlpha = textAlpha.coerceIn(0.0, 255.0)
        searchBar.color = UIColors.withAlpha(UIColors.TEXT, textAlpha.toInt())
        searchBar.position = Pair(gui.x + 108.0 + (gui.w - 108.0) / 2.0, gui.y + 17.0 + scrollUtil.scroll)
        searchBar.draw()

        scrollUtil.update()
        var y = gui.y + 35.0 + scrollUtil.scroll
        var total = 0.0
        for (moduleComponent in relevantModules) {
            moduleComponent.draw(Pair(gui.x + 108.0, y), mouseX, mouseY, partialTicks)
            y += moduleComponent.scale.second + 7.0
            total += moduleComponent.scale.second + 7.0
        }

        scrollUtil.setMax(-total + gui.h - 37.0)
        val maxScroll = -total + gui.h - 37.0
        if (maxScroll < 0.0) {
            val ratio = (scrollUtil.scroll / total).coerceIn(0.0, 1.0)
            val viewHeight = gui.h - 37.0 - 14.0
            val thumbH = viewHeight / (total / viewHeight + 1.0).coerceAtLeast(1.0)
            val thumbY = gui.y + 35.0 - ratio * (viewHeight - thumbH)
            RiseRender.rounded(gui.x + gui.w - 4.0, thumbY, 1.0, thumbH, 0.5, UIColors.withAlpha(Color.WHITE, 60))
        }
        timer.reset()
    }

    override fun onKey(typedChar: Char, keyCode: Int) {
        if (keyCode == 208 || keyCode == 200) return
        if (!typedWhileOpen && !anyTextBoxSelected() && typedChar.code != 0 && !Character.isISOControl(typedChar)) {
            typedWhileOpen = true
            setSearchText("")
        }
        if (!anyTextBoxSelected()) {
            searchBar.selected = true
            searchBar.key(typedChar, keyCode)
            scrollUtil.setTarget(0.0)
        }
        refresh()
        relevantModules.forEach { it.key(typedChar, keyCode) }
    }

    private fun anyTextBoxSelected(): Boolean {
        relevantModules.forEach { mc ->
            mc.valueList.forEach { vc ->
                when (vc) {
                    is RiseStringValueComponent -> if (vc.textBox.selected) return true
                    is RiseNumberValueComponent -> if (vc.textBox.selected) return true
                    is RiseBoundsValueComponent -> if (vc.textBox.selected) return true
                }
            }
        }
        return false
    }

    override fun onClick(mouseX: Int, mouseY: Int, mouseButton: Int) {
        relevantModules.forEach { it.click(mouseX, mouseY, mouseButton) }
        searchBar.click(mouseX, mouseY, mouseButton)
    }

    override fun onMouseRelease() {
        relevantModules.forEach { it.onMouseUp() }
    }

    override fun onBloom() {
        relevantModules.forEach { it.onRenderRelease() }
    }

    override fun onSwitch() {
        refresh()
        typedWhileOpen = false
        scrollUtil.reset()
    }

    override fun allowsAutoSearch() = false
}

// ============================== ThemeScreen ==============================

class RiseThemeScreen(private val gui: RiseClickGui) : RiseScreen {
    private val scrollUtil = RiseScrollUtil()
    private val themes get() = ClientThemesUtils.themes
    private var selectedKeyColor: KeyColor? = null

    /** Rise KeyColors — filter row above the theme grid. */
    enum class KeyColor(val color: Color) {
        RED(Color(255, 50, 50)),
        ORANGE(Color(255, 128, 50)),
        YELLOW(Color(255, 255, 50)),
        LIME(Color(128, 255, 50)),
        DARK_GREEN(Color(50, 128, 50)),
        AQUA(Color(50, 200, 255)),
        DARK_BLUE(Color(50, 100, 200)),
        PURPLE(Color(128, 50, 255)),
        PINK(Color(255, 128, 255)),
        GRAY(Color(100, 100, 110))
    }

    private fun classify(c: Color): KeyColor {
        val hsb = Color.RGBtoHSB(c.red, c.green, c.blue, null)
        if (hsb[1] < 0.08f) return KeyColor.GRAY
        val hue = hsb[0] * 360f
        return when {
            hue <= 14f || hue >= 345f -> KeyColor.RED
            hue <= 40f -> KeyColor.ORANGE
            hue <= 65f -> KeyColor.YELLOW
            hue <= 120f -> KeyColor.LIME
            hue <= 165f -> KeyColor.DARK_GREEN
            hue <= 200f -> KeyColor.AQUA
            hue <= 250f -> KeyColor.DARK_BLUE
            hue <= 290f -> KeyColor.PURPLE
            else -> KeyColor.PINK
        }
    }

    private fun keyColorsOf(theme: ClientThemesUtils.ClientTheme): Set<KeyColor> =
        setOf(classify(theme.start), classify(theme.end))

    private fun isActive(theme: ClientThemesUtils.ClientTheme): Boolean =
        ClientThemesUtils.ClientColorMode.lowercase().replace(" ", "") == theme.key.lowercase().replace(" ", "")

    private fun visibleThemes(): List<ClientThemesUtils.ClientTheme> =
        if (selectedKeyColor == null) themes else themes.filter { keyColorsOf(it).contains(selectedKeyColor) }

    override fun onRender(mouseX: Int, mouseY: Int, partialTicks: Float) {
        scrollUtil.update()
        val x = gui.x + 108.0
        val contentW = gui.w - 108.0
        val scroll = scrollUtil.scroll
        RiseGuiFonts.small().drawCenteredString(
            "Themes", (gui.x + gui.w - 20f), gui.y + 20f + scroll.toFloat(), Color(255, 255, 255, 128).rgb
        )

        // key colors filter row (Rise: 5 per row, 17 tall, 24 row spacing)
        val dotW = (contentW - 43.0) / 5.0
        val keyY = gui.y + 44.0 + scroll
        KeyColor.entries.forEachIndexed { i, kc ->
            val cx = x + 7.0 + (7.0 + dotW) * (i % 5)
            val cy = keyY + Math.floor(i / 5.0) * 24.0
            val selected = selectedKeyColor == kc
            RiseRender.rounded(cx, cy, dotW, 17.0, 5.0, if (selected) UIColors.OVERLAY else Color(0, 0, 0, 0))
            RiseRender.rounded(cx + 2.0, cy + 3.5, 10.0, 10.0, 5.0, kc.color)
            if (selected && ClickGUI.nlGlowIntensity > 0f) {
                GlowUtils.drawGlow(
                    (cx + 2.0).toFloat(), (cy + 3.5).toFloat(), 10f, 10f, 8,
                    Color(kc.color.red, kc.color.green, kc.color.blue, (60 * ClickGUI.nlGlowIntensity).toInt().coerceIn(0, 255))
                )
            }
            RiseGuiFonts.small().drawString(
                kc.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                (cx + 15.0).toFloat(), (cy + 4.5).toFloat(),
                if (selected) UIColors.TEXT.rgb else Color(255, 255, 255, 150).rgb
            )
        }

        // theme cards — Rise ThemeComponent design: 3-column grid, rounded 10 card,
        // 30px gradient preview with squared-off bottom, name centered at +37
        val cardW = (contentW - 29.0) / 3.0
        val cardY = keyY + 24.0 * 2.0 + 12.0
        val visible = visibleThemes()
        visible.forEachIndexed { i, theme ->
            val col = i % 3
            val row = Math.floor(i / 3.0)
            val cx = x + 7.0 + (7.0 + cardW) * col
            val cy = cardY + row * 57.0
            val active = isActive(theme)
            val cardColor = if (active) Color(15, 19, 26) else Color(18, 21, 30)
            RiseRender.rounded(cx, cy, cardW, 50.0, 10.0, cardColor)
            RiseRender.horizontalGradientRounded(
                (cx + 3.0).toFloat(), (cy + 3.0).toFloat(), (cardW - 6.0).toFloat(), 30f, 7f,
                theme.start, theme.end
            )
            RiseRender.rounded(cx + 3.0, cy + 26.0, cardW - 6.0, 7.0, 0.0, cardColor)
            if (active && ClickGUI.nlGlowIntensity > 0f) {
                GlowUtils.drawGlow(
                    (cx + 3.0).toFloat(), (cy + 3.0).toFloat(), (cardW - 6.0).toFloat(), 30f, 10,
                    Color(theme.start.red, theme.start.green, theme.start.blue, (70 * ClickGUI.nlGlowIntensity).toInt().coerceIn(0, 255))
                )
            }
            RiseGuiFonts.small().drawCenteredString(
                theme.displayName, (cx + cardW / 2.0).toFloat(), (cy + 37.0).toFloat(),
                (if (active) theme.start else Color.WHITE).rgb
            )
        }

        val rows = Math.ceil(visible.size / 3.0)
        val totalContent = 24.0 * 2.0 + 12.0 + 57.0 * rows
        scrollUtil.setMax(-(totalContent - (gui.h - 108.0 - 12.0)).coerceAtLeast(0.0))
    }

    override fun onKey(typedChar: Char, keyCode: Int) {}

    override fun onClick(mouseX: Int, mouseY: Int, mouseButton: Int) {
        if (mouseButton != 0) return
        val x = gui.x + 108.0
        val contentW = gui.w - 108.0
        val scroll = scrollUtil.scroll

        val dotW = (contentW - 43.0) / 5.0
        val keyY = gui.y + 44.0 + scroll
        KeyColor.entries.forEachIndexed { i, kc ->
            val cx = x + 7.0 + (7.0 + dotW) * (i % 5)
            val cy = keyY + Math.floor(i / 5.0) * 24.0
            if (RiseRender.hovered(cx, cy, dotW, 17.0, mouseX, mouseY)) {
                selectedKeyColor = if (selectedKeyColor == kc) null else kc
                scrollUtil.reset()
                return
            }
        }

        val cardW = (contentW - 29.0) / 3.0
        val cardY = keyY + 24.0 * 2.0 + 12.0
        visibleThemes().forEachIndexed { i, theme ->
            val cx = x + 7.0 + (7.0 + cardW) * (i % 3)
            val cy = cardY + Math.floor(i / 3.0) * 57.0
            if (RiseRender.hovered(cx, cy, cardW, 50.0, mouseX, mouseY)) {
                // drive the client theme system (ThemeManager)
                ThemeManager.setTheme(theme.displayName)
                return
            }
        }
    }

    override fun onMouseRelease() {}
    override fun onBloom() {}
    override fun onSwitch() { scrollUtil.reset() }
    override fun allowsAutoSearch() = false
}

// ============================== InfoScreen ==============================

class RiseInfoScreen(private val gui: RiseClickGui) : RiseScreen {
    private val credits = """
        AirPlus (github.com/lmx0721/AirPlus)

        A free open source mixin-based injection hacked client
        built on the LiquidBounce legacy codebase.

        Rise 6 Modern ClickGUI — ported from Rise 6.9.5.

        UI/UX
          -> Rise client (Alan & Hazsi)

        Port
          -> RiseClickGui / components / screens / values

        Special Thanks
          -> Config and script makers
          -> You, as a user of AirPlus. Thank you!
    """.trimIndent()

    override fun onRender(mouseX: Int, mouseY: Int, partialTicks: Float) {
        val x = gui.x + 108f
        val y = gui.y
        val w = gui.w
        val h = gui.h
        val sidebarW = 108f
        RiseGuiFonts.title().drawString("AirPlus", x + 20f, y + 20f, Color.WHITE.rgb)
        RiseGuiFonts.small().drawString(
            "modern ui", x + 20f + RiseGuiFonts.title().getStringWidth("AirPlus"), y + 18f, Color(255, 255, 255, 100).rgb
        )
        RiseGuiFonts.small().drawString("Rise 6.9.5 port", x + 20f, y + 50f, Color(255, 255, 255, 164).rgb)

        val now = System.currentTimeMillis() / 1000.0 % 45.0 * 11.0
        RiseRender.scissorStart(x.toDouble(), (y + 90f).toDouble(), (w - sidebarW).toDouble(), (h - 175f).toDouble())
        RiseGuiFonts.small().drawString(
            credits, x + 20f, y + 100f - now.toFloat() + (h - 175f),
            Color(164, 164, 164, 64).rgb
        )
        RiseRender.scissorEnd()
    }

    override fun onKey(typedChar: Char, keyCode: Int) {}
    override fun onClick(mouseX: Int, mouseY: Int, mouseButton: Int) {}
    override fun onMouseRelease() {}
    override fun onBloom() {}
    override fun allowsAutoSearch() = false
}

// ============================== Sidebar ==============================

class RiseSidebar(private val gui: RiseClickGui) {
    var width = 100.0
    private var sidebarAlpha = 0.0
    private var collapsed = false
    private val expandAnim = RiseAnimation(RiseEasing.EASE_OUT_EXPO, 300)
    private var lastTime = 0L
    lateinit var entries: List<RiseSidebarEntry>

    fun init() {
        val searchEntry = RiseSidebarEntry("Search", "U", gui.searchScreen)
        val categoryEntries = Category.entries.map { entry ->
            RiseSidebarEntry(entry.displayName, entry.riseIcon(), gui.screenFor(entry), isCategory = true)
        }
        val themeEntry = RiseSidebarEntry("Themes", "U", gui.themeScreen)
        val infoEntry = RiseSidebarEntry("Info", "J", gui.infoScreen)
        entries = listOf(searchEntry) + categoryEntries + listOf(themeEntry, infoEntry)
    }

    fun renderBackground(mouseX: Int, mouseY: Int) {
        val color = UIColors.SECONDARY.withA(sidebarAlpha.toInt())
        expandAnim.setDuration(700)
        expandAnim.animate(0.0)
        // left-edge rounded background (left corners follow the panel radius, right corners squared)
        val bgX = gui.x
        val bgW = width + expandAnim.value
        if (bgW > 0) {
            RiseRender.rounded(bgX - 9.0, bgY(), bgW + 9.0, gui.h.toDouble(), 12.0, color)
        }
    }

    private fun bgY(): Double = gui.y.toDouble()

    fun render(mouseX: Int, mouseY: Int) {
        val now = System.currentTimeMillis()
        if (lastTime == 0L) lastTime = now

        // 左侧模块分类始终显示，不再随屏幕切换/悬停隐藏
        collapsed = false
        sidebarAlpha = Math.min(sidebarAlpha + (now - lastTime) * 2.0, 255.0)

        lastTime = now
        var y = 10.0
        for (entry in entries) {
            y += 19.5
            renderEntry(entry, y, width + expandAnim.value, sidebarAlpha.toInt())
        }

        val fontX = gui.x + 9.0 + expandAnim.value
        val fontY = gui.y + (24.75 - RiseGuiFonts.title().FONT_HEIGHT / 2.0f)
        RiseGuiFonts.title().drawString("AirPlus", (fontX + 5.0).toFloat(), (fontY + 2.0).toFloat(), UIColors.withAlpha(Color.WHITE, sidebarAlpha.toInt()).rgb)
        RiseGuiFonts.small().drawString(
            "6.9.5", (fontX + 5.0 + RiseGuiFonts.title().getStringWidth("AirPlus")).toFloat(), fontY.toFloat(),
            UIColors.withAlpha(accentColor(), Math.min(sidebarAlpha.toInt(), 200)).rgb
        )
    }

    private fun renderEntry(entry: RiseSidebarEntry, y: Double, sidebarW: Double, alpha: Int) {
        if (alpha <= 0) return
        val x = gui.x - (69.0 - sidebarW) - 21.0
        val py = gui.y + y + 16.0
        val font = RiseGuiFonts.small()
        val labelWidth = font.getStringWidth(entry.label)
        val pillW = labelWidth + 8.0 + RiseGuiFonts.icon().getStringWidth(entry.icon) + 8.0
        val selected = gui.activeScreen === entry.screen
        val selAnim = selectionAnim(entry)
        val pillColor = UIColors.withAlpha(accentColor(), (Math.min(selAnim, alpha.toDouble())).toInt()).darker()
        RiseRender.rounded(x, py - 5.5, pillW, 15.0, 5.0, pillColor)
        if (selected && ClickGUI.nlGlowIntensity > 0f) {
            GlowUtils.drawGlow(
                x.toFloat(), (py - 5.5).toFloat(), pillW.toFloat(), 15f, 10,
                Color(accentColor().red, accentColor().green, accentColor().blue, (alpha * 0.35 * ClickGUI.nlGlowIntensity).toInt().coerceIn(0, 255))
            )
        }
        val textAlpha = Math.min(if (selected) 255 else 200, alpha)
        RiseGuiFonts.icon().drawString(
            entry.icon, (x + selAnim / 80.0 + 3.0).toFloat(), py.toFloat(), Color(255, 255, 255, textAlpha).rgb
        )
        font.drawString(
            entry.label,
            (x + selAnim / 80.0 + 3.0 + 4.0 + RiseGuiFonts.icon().getStringWidth(entry.icon)).toFloat(),
            py.toFloat(),
            Color(255, 255, 255, textAlpha).rgb
        )
        entryPositions[entry] = Triple(x, py - 5.5, pillW)
    }

    private val selectionAnims = HashMap<RiseSidebarEntry, RiseAnimation>()
    private val entryPositions = HashMap<RiseSidebarEntry, Triple<Double, Double, Double>>()

    private fun selectionAnim(entry: RiseSidebarEntry): Double {
        var anim = selectionAnims[entry]
        if (anim == null) {
            anim = RiseAnimation(RiseEasing.LINEAR, 200)
            selectionAnims[entry] = anim
        }
        anim.animate(if (gui.activeScreen === entry.screen) 255.0 else 0.0)
        return anim.value
    }

    fun renderBloom(alpha: Double) {
        // Rise pre-renders the category pills for bloom — glow already handled in renderEntry.
    }

    fun click(mouseX: Int, mouseY: Int, mouseButton: Int) {
        if (sidebarAlpha <= 0.0 || mouseButton != 0) return
        for ((entry, pos) in entryPositions) {
            val (x, y, w) = pos
            if (RiseRender.hovered(x - 11.0, y - 5.0, 70.0, 22.0, mouseX, mouseY)) {
                gui.switchScreen(entry.screen)
                return
            }
        }
    }
}

// SessIcon glyph map (session.ttf): F剑 C跑步 G单人 A鼠标 L地球 J拼图 K齿轮 H双人 U放大镜
private fun Category.riseIcon(): String = when (this) {
    Category.COMBAT -> "F"
    Category.MOVEMENT -> "C"
    Category.PLAYER -> "G"
    Category.RENDER -> "A"
    Category.WORLD -> "L"
    Category.MISC -> "J"
    Category.CLIENT -> "H"
    Category.FUN -> "J"
}
