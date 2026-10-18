/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.render

import net.airplus.AirPlus.CLIENT_NAME
import net.airplus.AirPlus.hud
import net.airplus.event.*
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.ui.client.hud.designer.GuiHudDesigner
import net.airplus.ui.font.Fonts
import net.airplus.ui.client.hud.element.Element.Companion.MAX_GRADIENT_COLORS
import net.airplus.utils.render.ColorSettingsFloat
import net.airplus.utils.render.ColorSettingsInteger
import net.airplus.utils.render.HudBlur
import net.minecraft.client.gui.GuiChat
import net.minecraft.util.ResourceLocation

object HUD : Module("HUD", Category.RENDER, gameDetecting = false, defaultState = true, defaultHidden = true) {
    val customHotbar by boolean("CustomHotbar", true)

    val smoothHotbarSlot by boolean("SmoothHotbarSlot", true) { customHotbar }

    val roundedHotbarRadius by float("RoundedHotbar-Radius", 3F, 0F..5F) { customHotbar }

    val hotbarMode by choices("Hotbar-Color", arrayOf("Custom", "Rainbow", "Gradient"), "Custom") { customHotbar }
    val hbHighlightColors = ColorSettingsInteger(this, "Hotbar-Highlight-Colors", applyMax = true)
    { customHotbar }.with(a = 0)
    val hbBackgroundColors = ColorSettingsInteger(this, "Hotbar-Background-Colors")
    { customHotbar && hotbarMode == "Custom" }.with(a = 190)
    val gradientHotbarSpeed by float("Hotbar-Gradient-Speed", 1f, 0.5f..10f)
    { customHotbar && hotbarMode == "Gradient" }
    val maxHotbarGradientColors by int("Max-Hotbar-Gradient-Colors", 4, 1..MAX_GRADIENT_COLORS)
    { customHotbar && hotbarMode == "Gradient" }
    val bgGradColors = ColorSettingsFloat.create(this, "Hotbar-Gradient")
    { customHotbar && hotbarMode == "Gradient" && it <= maxHotbarGradientColors }
    val hbHighlightBorder by float("HotbarBorder-Highlight-Width", 2F, 0.5F..5F) { customHotbar }
    val hbHighlightBorderColors = ColorSettingsInteger(this, "HotbarBorder-Highlight-Colors")
    { customHotbar }.with(a = 255, g = 111, b = 255)
    val hbBackgroundBorder by float("HotbarBorder-Background-Width", 0.5F, 0.5F..5F) { customHotbar }
    val hbBackgroundBorderColors = ColorSettingsInteger(this, "HotbarBorder-Background-Colors")
    { customHotbar }.with(a = 0)

    val rainbowX by float("Rainbow-X", -1000F, -2000F..2000F) { customHotbar && hotbarMode == "Rainbow" }
    val rainbowY by float("Rainbow-Y", -1000F, -2000F..2000F) { customHotbar && hotbarMode == "Rainbow" }
    val gradientX by float("Gradient-X", -1000F, -2000F..2000F) { customHotbar && hotbarMode == "Gradient" }
    val gradientY by float("Gradient-Y", -1000F, -2000F..2000F) { customHotbar && hotbarMode == "Gradient" }

    val inventoryParticle by boolean("InventoryParticle", false)
    private val blur by boolean("Blur", false)
    private val fontChat by boolean("FontChat", false)

    // FontChat 自定义字体：开启 FontChat 后聊天使用的字体（可循环/点选）
    val chatFont by font("Chat-Font", Fonts.fontSemibold40) { fontChat }

    // 聊天栏自定义
    val chatCustom by boolean("Chat-Custom", true)
    val chatAnimation by boolean("Chat-Animation", true) { chatCustom }
    val chatAnimationSpeed by float("Chat-Animation-Speed", 1F, 0.1F..3F) { chatCustom && chatAnimation }
    val chatSmoothScroll by boolean("Chat-Smooth-Scroll", true) { chatCustom }
    val chatBackground by boolean("Chat-Background", true) { chatCustom }
    val chatBackgroundColors = ColorSettingsInteger(this, "Chat-Background-Colors")
    { chatCustom && chatBackground }.with(a = 120)
    val chatRoundedRadius by float("Chat-Rounded-Radius", 3F, 0F..5F) { chatCustom && chatBackground }
    val chatCustomWidth by boolean("Chat-Custom-Width", false) { chatCustom }
    val chatWidth by float("Chat-Width", 120F, 40F..1000F) { chatCustom && chatCustomWidth }
    // 聊天栏位置偏移（相对原版聊天原点）与最大高度（背景/文本向上最多延伸 Max px，超出裁剪）
    val chatPosX by float("Chat-Pos-X", 0F, -1000F..1000F) { chatCustom }
    val chatPosY by float("Chat-Pos-Y", 0F, -1000F..1000F) { chatCustom }
    val chatHeightMax by float("Chat-Height-Max", 110F, 9F..400F) { chatCustom }
    val chatBackgroundBlur by boolean("Chat-Background-Blur", true) { chatCustom && chatBackground }
    val chatBlurStrength by float("Chat-Blur-Strength", 8F, 1F..30F) { chatCustom && chatBackground && chatBackgroundBlur }
    val chatBlurMode by choices("Chat-Blur-Mode", HudBlur.MODES, "InternalBlur") { chatCustom && chatBackground && chatBackgroundBlur }

    val onRender2D = handler<Render2DEvent> {
        if (mc.currentScreen is GuiHudDesigner)
            return@handler

        hud.render(false)
    }

    val onUpdate = handler<UpdateEvent> {
        hud.update()
    }

    val onKey = handler<KeyEvent> { event ->
        hud.handleKey('a', event.key)
    }

    val onScreen = handler<ScreenEvent>(always = true) { event ->
        if (mc.theWorld == null || mc.thePlayer == null) return@handler
        if (state && blur && !mc.entityRenderer.isShaderActive && event.guiScreen != null &&
            !(event.guiScreen is GuiChat || event.guiScreen is GuiHudDesigner)
        ) mc.entityRenderer.loadShader(
            ResourceLocation(CLIENT_NAME.lowercase() + "/blur.json")
        ) else if (mc.entityRenderer.shaderGroup != null &&
            "airplus/blur.json" in mc.entityRenderer.shaderGroup.shaderGroupName
        ) mc.entityRenderer.stopUseShader()
    }

    fun shouldModifyChatFont() = handleEvents() && fontChat

    fun shouldRenderCustomChat() = handleEvents() && chatCustom
}