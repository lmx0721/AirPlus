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
import net.airplus.ui.glass.BmDock
import net.airplus.utils.render.ColorSettingsFloat
import net.airplus.utils.render.ColorSettingsInteger
import net.airplus.utils.render.HudBlur
import net.airplus.utils.render.RiseHotbar
import net.minecraft.client.gui.GuiChat
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.util.ResourceLocation
import net.minecraftforge.client.GuiIngameForge

object HUD : Module("HUD", Category.RENDER, gameDetecting = false, defaultState = true, defaultHidden = true) {
    val customHotbar by boolean("CustomHotbar", true)

    // Hotbar 风格：LB = AirPlus 自绘圆角热栏；BM = BetterMyau 玻璃悬浮 dock 移植；Rise = Rise HotBar 主题移植
    val hotbarStyle by choices("Hotbar-Style", arrayOf("LB", "BM", "Rise"), "LB") { customHotbar }

    val smoothHotbarSlot by boolean("SmoothHotbarSlot", true) { customHotbar && hotbarStyle == "LB" }

    val roundedHotbarRadius by float("RoundedHotbar-Radius", 3F, 0F..5F) { customHotbar && hotbarStyle == "LB" }

    val hotbarMode by choices("Hotbar-Color", arrayOf("Custom", "Rainbow", "Gradient"), "Custom") { customHotbar && hotbarStyle == "LB" }
    val hbHighlightColors = ColorSettingsInteger(this, "Hotbar-Highlight-Colors", applyMax = true)
    { customHotbar && hotbarStyle == "LB" }.with(a = 0)
    val hbBackgroundColors = ColorSettingsInteger(this, "Hotbar-Background-Colors")
    { customHotbar && hotbarStyle == "LB" && hotbarMode == "Custom" }.with(a = 190)
    val gradientHotbarSpeed by float("Hotbar-Gradient-Speed", 1f, 0.5f..10f)
    { customHotbar && hotbarStyle == "LB" && hotbarMode == "Gradient" }
    val maxHotbarGradientColors by int("Max-Hotbar-Gradient-Colors", 4, 1..MAX_GRADIENT_COLORS)
    { customHotbar && hotbarStyle == "LB" && hotbarMode == "Gradient" }
    val bgGradColors = ColorSettingsFloat.create(this, "Hotbar-Gradient")
    { customHotbar && hotbarStyle == "LB" && hotbarMode == "Gradient" && it <= maxHotbarGradientColors }
    val hbHighlightBorder by float("HotbarBorder-Highlight-Width", 2F, 0.5F..5F) { customHotbar && hotbarStyle == "LB" }
    val hbHighlightBorderColors = ColorSettingsInteger(this, "HotbarBorder-Highlight-Colors")
    { customHotbar && hotbarStyle == "LB" }.with(a = 255, g = 111, b = 255)
    val hbBackgroundBorder by float("HotbarBorder-Background-Width", 0.5F, 0.5F..5F) { customHotbar && hotbarStyle == "LB" }
    val hbBackgroundBorderColors = ColorSettingsInteger(this, "HotbarBorder-Background-Colors")
    { customHotbar && hotbarStyle == "LB" }.with(a = 0)

    val rainbowX by float("Rainbow-X", -1000F, -2000F..2000F) { customHotbar && hotbarStyle == "LB" && hotbarMode == "Rainbow" }
    val rainbowY by float("Rainbow-Y", -1000F, -2000F..2000F) { customHotbar && hotbarStyle == "LB" && hotbarMode == "Rainbow" }
    val gradientX by float("Gradient-X", -1000F, -2000F..2000F) { customHotbar && hotbarStyle == "LB" && hotbarMode == "Gradient" }
    val gradientY by float("Gradient-Y", -1000F, -2000F..2000F) { customHotbar && hotbarStyle == "LB" && hotbarMode == "Gradient" }

    // BM 热栏（BetterMyau 玻璃 dock 移植）
    val statusBars by boolean("Status-Bars", true) { customHotbar && hotbarStyle == "BM" }
    val healthNumbers by boolean("Health-Numbers", true) { customHotbar && hotbarStyle == "BM" && statusBars }
    val itemName by boolean("Item-Name", true) { customHotbar && hotbarStyle == "BM" }
    val magnify by boolean("Magnify", true) { customHotbar && hotbarStyle == "BM" }
    val bmScale by int("BM-Scale", 100, 80..130) { customHotbar && hotbarStyle == "BM" }
    val bmOffsetY by int("BM-Offset-Y", 4, 0..20) { customHotbar && hotbarStyle == "BM" }

    // Rise 热栏（Rise HotBar 主题移植）：悬浮圆角深色条 + 顶部 XP 进度条与等级数字
    val riseShowXPBar by boolean("Rise-Show-XP-Bar", true) { customHotbar && hotbarStyle == "Rise" }
    val riseRadius by float("Rise-Radius", 9F, 0F..10F) { customHotbar && hotbarStyle == "Rise" }
    val riseBackgroundAlpha by float("Rise-Background-Alpha", 110F, 0F..255F) { customHotbar && hotbarStyle == "Rise" }
    val riseAccentColors = ColorSettingsInteger(this, "Rise-Accent-Colors", applyMax = true)
    { customHotbar && hotbarStyle == "Rise" }.with(a = 255)

    val inventoryParticle by boolean("InventoryParticle", false)
    private val blur by boolean("Blur", false)
    private val fontChat by boolean("FontChat", false)

    // FontChat 自定义字体：开启 FontChat 后聊天使用的字体（可循环/点选）
    val chatFont by font("Chat-Font", Fonts.fontSemibold40) { fontChat }

    // 聊天栏自定义
    val chatCustom by boolean("Chat-Custom", true)
    // 聊天栏风格：air = AirPlus 弹簧入场/退场动画风格；Rise = Rise 风格（固定宽度圆角面板、
    // 打开/关闭高度缓动、消息按消失速度逐条隐去）
    val chatMode by choices("Chat-Mode", arrayOf("air", "Rise"), "air") { chatCustom }
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
    // 背景模糊的类型/强度由 Client 模块的全局 Blur 设置统一控制（HudBlur）
    val chatBackgroundBlur by boolean("Chat-Background-Blur", true) { chatCustom && chatBackground }
    // Rise 聊天栏：打开聊天时的完整窗口高度（Rise "Open Height"）与关闭状态下
    // 每条消息的存活间隔（Rise "Message disappearance speed"，到时逐条隐去一行）
    val chatRiseOpenHeight by float("Chat-Rise-Open-Height", 130F, 0F..500F) { chatCustom && chatMode == "Rise" }
    val chatRiseDisappearance by int("Chat-Rise-Disappearance-Speed", 5000, 0..5000, "ms")
    { chatCustom && chatMode == "Rise" }

    val onRender2D = handler<Render2DEvent> {
        if (mc.currentScreen is GuiHudDesigner)
            return@handler

        // Rise（Rise HotBar 主题）：原版热栏已在 MixinGuiInGame 中取消，这里在 2D 事件内绘制本体。
        // 此前在 renderTooltip 阶段（Render2DEvent 派发之前）绘制 —— 那是全项目唯一发生在
        // 2D 事件派发外的 blur 调用点：HudBlur 的基准快照在事件派发开头重拍，Rise 热栏先贴回的
        // 模糊会随后被「已含 HUD 内容」的重拍/快照时序吃掉，表现为热栏没有 blur。
        // 移到事件内后与其它 HUD 组件共享同一帧模糊快照（与 BM dock 同阶段）。
        if (riseReplacesHotbar() && mc.renderViewEntity is EntityPlayer) {
            RiseHotbar.render(ScaledResolution(mc), mc.renderViewEntity as EntityPlayer, it.partialTicks, riseSettings)
        }

        hud.render(false)

        // BM（BetterMyau 玻璃 dock）：原版热栏已在 MixinGuiInGame 中取消，这里绘制 dock 本体
        if (bmReplacesHotbar())
            BmDock.render(bmSettings, mc.renderViewEntity as EntityPlayer, it.partialTicks)
    }

    // BM dock：用户手动切槽（数字键/滚轮）时在下一次槽位变化时播放音效。
    // ClientSlotChangeEvent 每次控制器同步都会触发，因此对比真实背包槽位判断是否为本地变更。
    private var lastSupposedSlot = -1
    val onSlotChange = handler<ClientSlotChangeEvent> { event ->
        if (event.supposedSlot != lastSupposedSlot) {
            lastSupposedSlot = event.supposedSlot
            if (bmReplacesHotbar()) BmDock.userSwap()
        }
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

    /**
     * BM dock 是否接管热栏绘制（BetterMyau Hotbar.active() 的等价条件）：
     * HUD 开启 + CustomHotbar + Hotbar-Style 为 BM，且当前渲染实体为非旁观者玩家。
     */
    fun bmReplacesHotbar(): Boolean =
        handleEvents() && customHotbar && hotbarStyle == "BM" &&
            mc.thePlayer != null && mc.playerController != null &&
            !mc.playerController.isSpectator && mc.renderViewEntity is EntityPlayer

    /**
     * Rise 热栏是否接管热栏绘制（Rise HotBar 主题）：HUD 开启 + CustomHotbar + Hotbar-Style 为 Rise，
     * 且当前渲染实体为非旁观者玩家。接管时原版经验条被压制（Rise 自绘 XP 进度条与其重叠），
     * 血量/护甲/饥饿等状态条保持原版。
     */
    fun riseReplacesHotbar(): Boolean =
        handleEvents() && customHotbar && hotbarStyle == "Rise" &&
            mc.thePlayer != null && mc.playerController != null &&
            !mc.playerController.isSpectator && mc.renderViewEntity is EntityPlayer

    // BM dock 压制原版状态条标记（与 DeltaForceStyle 同机制，二者互相独立）
    @Volatile
    private var bmVanillaSuppressed = false

    fun markBmVanillaSuppressed() {
        bmVanillaSuppressed = true
    }

    fun restoreBmVanillaFlags() {
        if (!bmVanillaSuppressed) return
        bmVanillaSuppressed = false
        try {
            GuiIngameForge.renderHealth = true
            GuiIngameForge.renderFood = true
            GuiIngameForge.renderArmor = true
            GuiIngameForge.renderAir = true
            GuiIngameForge.renderExperiance = true
            GuiIngameForge.renderHealthMount = true
        } catch (_: Throwable) {
            // 类未加载等极端情况忽略
        }
    }

    // BM dock 设置桥（HUD 的 BM 属性 -> BmDock.Settings）
    private val bmSettings = object : BmDock.Settings {
        override fun statusBars() = this@HUD.statusBars
        override fun healthNumbers() = this@HUD.healthNumbers
        override fun itemName() = this@HUD.itemName
        override fun magnify() = this@HUD.magnify
        override fun scalePercent() = bmScale
        override fun offsetY() = bmOffsetY
    }

    // Rise 热栏压制原版经验条标记（原版 XP 条与 Rise 自绘 XP 条位置重叠，血量等其余状态条保持原版；
    // 与 BM 的压制标记相互独立，互不干扰恢复）
    @Volatile
    private var riseVanillaSuppressed = false

    fun markRiseVanillaSuppressed() {
        riseVanillaSuppressed = true
    }

    fun restoreRiseVanillaFlags() {
        if (!riseVanillaSuppressed) return
        riseVanillaSuppressed = false
        try {
            GuiIngameForge.renderExperiance = true
        } catch (_: Throwable) {
            // 类未加载等极端情况忽略
        }
    }

    // Rise 热栏设置桥（HUD 的 Rise 属性 -> RiseHotbar.Settings），供 MixinGuiInGame 传入
    val riseSettings = object : RiseHotbar.Settings {
        override fun showXPBar() = this@HUD.riseShowXPBar
        override fun radius() = riseRadius
        override fun backgroundAlpha() = riseBackgroundAlpha.toInt()
        override fun accentColor() = riseAccentColors.color().rgb
    }
}