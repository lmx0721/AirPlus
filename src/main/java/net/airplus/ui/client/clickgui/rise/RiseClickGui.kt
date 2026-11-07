/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Rise 6.9.5 Modern ClickGUI — full render port.
 * Ported from com.alan.clients.ui.click.standard.RiseClickGUI.
 *
 * Rise-specific subsystems without an AirPlus equivalent were mapped as follows:
 *  - ShaderRenderQueue (REGULAR/BLOOM/AlphaShader) -> direct rendering + GlowUtils glow
 *  - Themes / KeyColors                            -> ClickGUI NL theme presets (ThemeScreen)
 *  - ScriptScreen / LanguageScreen / CurveScreen   -> cut (no script/language/curve subsystem in AirPlus)
 *  - ConfigScreen                                  -> cut (AirPlus manages configs outside the GUI)
 *  - Localization.ce                               -> raw names (AirPlus has no GUI localization layer)
 *  - PinyinIme                                     -> cut (JDK clipboard handling kept in RiseTextBox)
 */
package net.airplus.ui.client.clickgui.rise

import net.airplus.AirPlus
import net.airplus.features.module.Category
import net.airplus.features.module.modules.render.ClickGUI
import net.airplus.file.FileManager.saveConfig
import net.airplus.file.FileManager.valuesConfig
import net.airplus.utils.GlowUtils
import net.airplus.utils.render.HudBlur
import net.minecraft.client.gui.GuiScreen
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11
import java.awt.Color
import java.io.IOException

class RiseClickGui : GuiScreen() {

    // Panel geometry (Rise: position = 400x300, sidebar = 100)
    var x = -1f
    var y = -1f
    val w = 400f
    val h = 300f
    val moduleDefaultW = 283f
    val moduleDefaultH = 38f

    // Rise: axS (scale) / axT (opacity)
    var scale = 0.0
        private set
    var opacity = 0.0
        private set
    var closing = false
        private set

    // Rise: overlayPresent — a value popup (expanded color picker) that blocks panel drag/module toggling
    var overlay: RiseValueComponent? = null

    // Rise: moduleList
    val moduleList = ArrayList<RiseModuleComponent>()

    // Screens
    val searchScreen = RiseSearchScreen(this)
    val themeScreen = RiseThemeScreen(this)
    val infoScreen = RiseInfoScreen(this)
    private val categoryScreens = HashMap<Category, RiseCategoryScreen>()
    private val sidebar = RiseSidebar(this)

    // Rise: axK (current) / axM (previous) / axP (switch timer)
    var activeScreen: RiseScreen = searchScreen
        private set
    private var previousScreen: RiseScreen = searchScreen
    private val switchTimer = RiseStopWatch()
    private var initialized = false

    private val scaleAnimation = RiseAnimation(RiseEasing.EASE_IN_EXPO, 300)
    private val opacityAnimation = RiseAnimation(RiseEasing.EASE_IN_EXPO, 300)

    private var dragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    // Set in initGui; used to swallow the keybind press that opens the GUI (same keyboard event).
    private var openedTime = 0L

    fun screenFor(category: Category): RiseCategoryScreen =
        categoryScreens.getOrPut(category) { RiseCategoryScreen(this, category) }

    private fun build() {
        moduleList.clear()
        val modules = ArrayList<net.airplus.features.module.Module>()
        AirPlus.moduleManager.forEach { modules.add(it) }
        modules.sortWith(compareBy({ it.name.lowercase() }))
        modules.forEach { moduleList.add(RiseModuleComponent(it, this)) }

        sidebar.init()
        categoryScreens.values.forEach { it.refresh() }
        searchScreen.refresh()
    }

    override fun initGui() {
        Keyboard.enableRepeatEvents(true)
        openedTime = System.currentTimeMillis()
        if (!initialized) {
            initialized = true
            build()
            activeScreen = lastScreenId?.let { findScreen(it) } ?: searchScreen
            previousScreen = activeScreen
        }
        activeScreen.onSwitch()

        scaleAnimation.reset()
        scaleAnimation.setValue(0.0)
        opacityAnimation.reset()
        opacityAnimation.setValue(0.0)
        switchTimer.reset()
        closing = false
        dragging = false

        val sr = net.minecraft.client.gui.ScaledResolution(mc)
        if (x < 0f || y < 0f || x + w > sr.scaledWidth || y + h > sr.scaledHeight) {
            x = sr.scaledWidth / 2f - w / 2f
            y = sr.scaledHeight / 2f - h / 2f
        }
        super.initGui()
    }

    private fun findScreen(id: String): RiseScreen? {
        sidebar.entries.forEach { if (it.label == id) return it.screen }
        return null
    }

    private fun screenId(): String =
        (activeScreen as? RiseCategoryScreen)?.category?.displayName
            ?: sidebar.entries.firstOrNull { it.screen === activeScreen }?.label
            ?: "Search"

    fun switchScreen(screen: RiseScreen) {
        if (screen === activeScreen) return
        previousScreen = activeScreen
        activeScreen = screen
        switchTimer.reset()
        screen.onSwitch()
    }

    /** Rise keyTyped: typing letters jumps to the search screen. */
    private fun handleAutoSearch(typedChar: Char) {
        if ("abcdefghijklmnopqrstuvwxyz1234567890 ".contains(typedChar.lowercaseChar()) &&
            activeScreen.allowsAutoSearch() && !anyTextBoxFocused()
        ) {
            if (activeScreen !== searchScreen) switchScreen(searchScreen)
            searchScreen.typedChar(typedChar)
        }
    }

    private fun anyTextBoxFocused(): Boolean {
        if (searchScreen.searchBar.selected) return true
        moduleList.forEach { mc ->
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

    /** Begin the close animation (Rise keeps rendering while scaling out). */
    fun startClose() {
        if (closing) return
        closing = true
        // Unfocus everything so text state flushes
        searchScreen.searchBar.selected = false
        moduleList.forEach { mcComponent ->
            mcComponent.valueList.forEach { vc ->
                when (vc) {
                    is RiseStringValueComponent -> vc.textBox.selected = false
                    is RiseNumberValueComponent -> vc.textBox.selected = false
                    is RiseBoundsValueComponent -> vc.textBox.selected = false
                }
            }
        }
        saveConfig(valuesConfig)
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        if (dragging) {
            x = mouseX + dragOffsetX
            y = mouseY + dragOffsetY
        }

        // Rise: opening -> EASE_OUT_EXPO 300ms, closing -> LINEAR 100ms
        opacityAnimation.setEasing(if (closing) RiseEasing.LINEAR else RiseEasing.EASE_OUT_EXPO)
        opacityAnimation.setDuration(if (closing) 100 else 300)
        opacityAnimation.animate(if (closing) 0.0 else 1.0)
        opacity = opacityAnimation.value
        scaleAnimation.setEasing(if (closing) RiseEasing.LINEAR else RiseEasing.EASE_OUT_EXPO)
        scaleAnimation.setDuration(if (closing) 100 else 300)
        scaleAnimation.animate(if (closing) 0.0 else 1.0)
        scale = if (closing) scaleAnimation.value else scaleAnimation.value.coerceAtLeast(0.01)

        if (closing && scale <= 0.02) {
            mc.displayGuiScreen(null)
            return
        }

        val translateX = (x + w / 2f) * (1.0 - scale)
        val translateY = (y + h / 2f) * (1.0 - scale)

        GL11.glPushMatrix()
        if (scale != 1.0) {
            GL11.glTranslated(translateX, translateY, 0.0)
            GL11.glScaled(scale, scale, 1.0)
        }

        // Background blur in screen space (before the transform it would be offset, so blur region uses
        // transformed bounds)
        if (ClickGUI.nlBlur && !closing && scale > 0.5) {
            val bx = (x * scale + translateX).toFloat()
            val by = (y * scale + translateY).toFloat()
            val bw = (w * scale).toFloat()
            val bh = (h * scale).toFloat()
            HudBlur.blur(bx, by, bx + bw, by + bh, 12f)
        }

        if (scale > 0.993) {
            GlowUtils.drawGlow(x - 6f, y - 6f, w + 12f, h + 12f, 14, Color(0, 0, 0, (90 * opacity).toInt()))
        }

        RiseRender.rounded(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), 12.0, UIColors.BACKGROUND.withA((254 * opacity).toInt()))

        RiseRender.scissorStart((x + 1.0) * scale + translateX, (y + 1.0) * scale + translateY, (w - 2.0) * scale, (h - 2.0) * scale)

        // Screen transition (Rise: 200ms old screen + background flash)
        val elapsed = switchTimer.elapsedTime
        val rendering = if (elapsed < 200) previousScreen else activeScreen
        rendering.onRender(mouseX, mouseY, partialTicks)

        if (elapsed <= 400) {
            val inner = if (elapsed < 200) {
                255.0 - elapsed * (255.0 / 200.0)
            } else {
                (elapsed - 200.0) * (255.0 / 200.0)
            }
            val flash = (255.0 - inner.coerceIn(0.0, 255.0)).toInt()
            if (flash > 0) {
                RiseRender.rounded(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), 12.0, UIColors.BACKGROUND.withA(flash))
            }
        }

        sidebar.renderBackground(mouseX, mouseY)
        sidebar.render(mouseX, mouseY)

        RiseRender.scissorEnd()
        GL11.glPopMatrix()

        super.drawScreen(mouseX, mouseY, partialTicks)
    }

    @Throws(IOException::class)
    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        // Title bar drag (blocked while a value popup is open — Rise: overlayPresent)
        if (RiseRender.hovered(x.toDouble(), y.toDouble(), w.toDouble(), 15.0, mouseX, mouseY) && overlay == null) {
            dragOffsetX = x - mouseX
            dragOffsetY = y - mouseY
            dragging = true
            return
        }

        if (RiseRender.hovered(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), mouseX, mouseY)) {
            if (overlay == null) {
                sidebar.click(mouseX, mouseY, mouseButton)
            }
            activeScreen.onClick(mouseX, mouseY, mouseButton)
        } else {
            // Click outside the panel: unfocus text boxes
            searchScreen.searchBar.selected = false
        }

        super.mouseClicked(mouseX, mouseY, mouseButton)
    }

    override fun mouseReleased(mouseX: Int, mouseY: Int, state: Int) {
        dragging = false
        activeScreen.onMouseRelease()
        activeScreen.onBloom()
        saveConfig(valuesConfig)
        super.mouseReleased(mouseX, mouseY, state)
    }

    override fun keyTyped(typedChar: Char, keyCode: Int) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            startClose()
            return
        }

        // The GUI is opened synchronously inside the KeyEvent dispatch (displayGuiScreen runs in
        // runTick before handleKeyboardInput), so the very same key press that opened the GUI also
        // reaches keyTyped and would instantly close it again ("can't reopen"). Rise's original
        // ClickGUI module debounces this with a 50ms openStopWatch — mirror that here.
        if (keyCode == ClickGUI.keyBind) {
            if (System.currentTimeMillis() - openedTime < 50L) return
            startClose()
            return
        }

        handleAutoSearch(typedChar)

        super.keyTyped(typedChar, keyCode)
        activeScreen.onKey(typedChar, keyCode)
    }

    override fun onGuiClosed() {
        Keyboard.enableRepeatEvents(false)
        dragging = false
        lastScreenId = screenId()
        saveConfig(valuesConfig)
        super.onGuiClosed()
    }

    override fun doesGuiPauseGame() = false

    private companion object {
        var lastScreenId: String? = null
    }
}
