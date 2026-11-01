/*
 * Air Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 */
package net.airplus.features.module.modules.render

import net.airplus.AirPlus.clickGui
import net.airplus.event.PacketEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.ui.client.clickgui.ClickGui
import net.airplus.ui.client.clickgui.astolfo.AstolfoClickGui
import net.airplus.ui.client.clickgui.augustus.AugustusClickGui
import net.airplus.ui.client.clickgui.flat.FlatClickGui
import net.airplus.ui.client.clickgui.moonlight.MoonLightClickGui
import net.airplus.ui.client.clickgui.neverlose.NeverloseScreen
import net.airplus.ui.client.clickgui.opai.OpaiScreen
import net.airplus.ui.client.clickgui.rise.RiseClickGui
import net.airplus.ui.client.clickgui.style.styles.LiquidBounceStyle
import net.airplus.ui.client.clickgui.style.styles.SlowlyStyle
import net.airplus.utils.client.ClientThemesUtils
import net.minecraft.network.play.server.S2EPacketCloseWindow
import org.lwjgl.input.Keyboard
import java.awt.Color

object ClickGUI : Module("ClickGUI", Category.CLIENT, Keyboard.KEY_RSHIFT, canBeEnabled = false) {
    private val style by choices(
        "Style",
        arrayOf("LiquidBounce", "Slowly", "Neverlose", "Augustus", "Opai", "Astolfo", "MoonLight", "Rise", "Flat"),
        "LiquidBounce"
    ).onChanged {
        updateStyle()
    }
    var scale by float("Scale", 0.8f, 0.5f..1.5f)
    val maxElements by int("MaxElements", 15, 1..30)
    val fadeSpeed by float("FadeSpeed", 1f, 0.5f..4f)
    val scrolls by boolean("Scrolls", true)
    val spacedModules by boolean("SpacedModules", false)
    val panelsForcedInBoundaries by boolean("PanelsForcedInBoundaries", false)

    private val color by color("Color", Color(0, 160, 255)) { !useTheme && style !in arrayOf("Slowly", "Neverlose", "Augustus", "Opai", "Astolfo", "MoonLight", "Rise", "Flat") }

    private val useTheme by boolean("UseTheme", false) { style !in arrayOf("Slowly", "Neverlose", "Augustus", "Opai", "Astolfo", "MoonLight", "Rise", "Flat") }

    val guiColor
        get() = if (useTheme) ClientThemesUtils.getColor(0).rgb else color.rgb

    // Neverlose style settings - expose Value objects for ClickGUI interaction
    val nlThemeValue = choices("NLTheme", arrayOf("Ocean", "Sunset", "Forest", "Royal", "Crimson", "Mono", "Theme"), "Ocean") { style == "Neverlose" || style == "Rise" }
    val nlBgOpacityValue = float("NLBgOpacity", 0.6f, 0.1f..1.0f) { style == "Neverlose" || style == "Rise" }
    val nlBlurValue = boolean("NLBlur", true) { style == "Neverlose" || style == "Rise" }
    // 模糊类型/强度由 Client 模块的全局 Blur 设置统一控制（HudBlur）
    val nlAnimationSpeedValue = float("NLAnimationSpeed", 1.0f, 0.1f..4.0f) { style == "Neverlose" || style == "Rise" }
    val nlGlowIntensityValue = float("NLGlowIntensity", 1.0f, 0f..1f) { style == "Neverlose" || style == "Rise" }

    val nlTheme by nlThemeValue
    val nlBgOpacity by nlBgOpacityValue
    val nlBlur by nlBlurValue
    val nlAnimationSpeed by nlAnimationSpeedValue
    val nlGlowIntensity by nlGlowIntensityValue

    // Augustus style settings - 背景模糊开关（类型/强度由 Client 全局 Blur 设置控制）
    val augBlurValue = boolean("AugBlur", true) { style == "Augustus" }

    val augBlur by augBlurValue

    // Theme presets: each theme has accent color and background color.
    // "Theme" follows the global theme engine (ThemeManager / ClientThemesUtils).
    val nlAccentColor: Color
        get() = when (nlTheme) {
            "Ocean" -> Color(74, 144, 217)
            "Sunset" -> Color(255, 130, 50)
            "Forest" -> Color(80, 200, 120)
            "Royal" -> Color(160, 100, 255)
            "Crimson" -> Color(255, 80, 100)
            "Mono" -> Color(220, 220, 230)
            "Theme" -> ClientThemesUtils.getColor(0)
            else -> Color(74, 144, 217)
        }

    val nlThemeBgColor: Color
        get() = when (nlTheme) {
            "Ocean" -> Color(0x0D, 0x1B, 0x2A)
            "Sunset" -> Color(0x1A, 0x0E, 0x14)
            "Forest" -> Color(0x0D, 0x1A, 0x0F)
            "Royal" -> Color(0x14, 0x0D, 0x1A)
            "Crimson" -> Color(0x1A, 0x0D, 0x10)
            "Mono" -> Color(0x12, 0x12, 0x1C)
            "Theme" -> ClientThemesUtils.getBackgroundColor(0)
            else -> Color(0x12, 0x12, 0x1C)
        }

    // Theme accent colors for the color dots display
    val nlThemeAccents: Array<Pair<String, Color>>
        get() = arrayOf(
            "Ocean" to Color(74, 144, 217),
            "Sunset" to Color(255, 130, 50),
            "Forest" to Color(80, 200, 120),
            "Royal" to Color(160, 100, 255),
            "Crimson" to Color(255, 80, 100),
            "Mono" to Color(220, 220, 230),
            "Theme" to ClientThemesUtils.getColor(0)
        )

    override fun onEnable() {
        openSelectedStyle()
        Keyboard.enableRepeatEvents(true)
    }

    private fun openSelectedStyle() {
        when (style) {
            "Neverlose" -> mc.displayGuiScreen(NeverloseScreen())
            "Augustus" -> mc.displayGuiScreen(AugustusClickGui())
            "Opai" -> mc.displayGuiScreen(OpaiScreen.INSTANCE)
            "Astolfo" -> mc.displayGuiScreen(AstolfoClickGui())
            "MoonLight" -> mc.displayGuiScreen(MoonLightClickGui())
            "Rise" -> mc.displayGuiScreen(RiseClickGui())
            "Flat" -> mc.displayGuiScreen(FlatClickGui())
            else -> {
                updateStyle()
                mc.displayGuiScreen(clickGui)
            }
        }
    }

    private fun updateStyle() {
        clickGui.style = when (style) {
            "LiquidBounce" -> LiquidBounceStyle
            "Slowly" -> SlowlyStyle
            else -> return
        }
    }

    val onPacket = handler<PacketEvent>(always = true) { event ->
        if (event.packet is S2EPacketCloseWindow && mc.currentScreen is ClickGui) {
            event.cancelEvent()
        }
    }
}
