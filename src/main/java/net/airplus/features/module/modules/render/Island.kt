/*
 * NekoBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/RouQingNeko1024/NekoBounce
 * Code By GoldBounce,Lizz,NightSky,FDP
 * https://github.com/SkidderMC/FDPClient
 * https://github.com/qm123pz/NightSky-Client
 * https://github.com/bzym2/GoldBounce/
 */
// skid neko bounce 
// https://github.com/RouQingNeko1024/NekoBounce
package net.airplus.features.module.modules.render

import net.airplus.AirPlus.clientVersionText
import net.airplus.features.module.ModuleManager
import net.airplus.event.Render2DEvent
import net.airplus.event.ScreenEvent
import net.airplus.event.UpdateEvent
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.event.handler
import net.airplus.features.module.modules.music.MusicHub
import net.airplus.features.module.modules.player.Blink
import net.airplus.ui.client.hud.designer.GuiHudDesigner
import net.airplus.ui.font.Fonts
import net.airplus.utils.GlowUtils
import net.airplus.utils.client.ServerUtils
import net.airplus.utils.client.ClientThemesUtils
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.render.RenderUtils.drawImage
import net.airplus.utils.render.RenderUtils.drawRoundedBorderRect
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.airplus.utils.render.BlurEffects
import net.airplus.utils.render.InternalBlurShader
import net.airplus.utils.render.shader.shaders.GradientFontShader
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting
import net.minecraft.client.renderer.RenderHelper.enableGUIStandardItemLighting
import net.minecraft.util.ResourceLocation
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.inventory.GuiChest
import net.minecraft.inventory.Slot
import net.minecraft.item.ItemBlock
import net.minecraft.item.ItemStack
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.shader.Framebuffer
import net.minecraft.client.network.NetworkPlayerInfo
import net.minecraft.client.gui.Gui
import net.minecraft.client.gui.GuiPlayerTabOverlay
import net.minecraft.entity.item.EntityEnderPearl
import net.minecraft.block.material.Material
import net.minecraft.init.Items
import net.minecraft.util.AxisAlignedBB
import net.minecraft.util.BlockPos
import net.minecraft.util.IChatComponent
import net.minecraft.util.Vec3
import net.minecraft.world.WorldSettings
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.EXTFramebufferObject
import java.net.URL
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import org.lwjgl.opengl.EXTPackedDepthStencil
import java.awt.Color
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
object Island : Module("Island", Category.RENDER) {
    private val ClientName by text("ClientName", "Air")
    val style by choices("Style", arrayOf("ios", "legacy", "prism", "opai"), "legacy")
    private val animTension by float("BounceTension", 0.01f, 0.01f..1.0f)
    private val animFriction by float("BounceFriction", 0.12f, 0.01f..1.0f)
    // 每个样式的圆角设置
    private val iosRadius by float("iOS-Radius", 15F, 0F..50F) { style == "ios" }
    private val legacyRadius by float("Legacy-Radius", 14F, 0F..50F) { style == "legacy" }

    // 每个样式的展开动画设置
    private val legacyExpandAnim by choices("Legacy-ExpandAnim", arrayOf("Bounce", "EaseOut", "Linear", "Back"), "Bounce") { style == "legacy" }
    private val iosExpandAnim by choices("iOS-ExpandAnim", arrayOf("Bounce", "EaseOut", "Linear", "Back"), "Bounce") { style == "ios" }

    // iOS样式设置
    private val iosBlurStrength by float("iOS-BlurStrength", 15F, 0F..50F) { style == "ios" }

    private val logoIcon by choices("LogoIcon", arrayOf("Default", "A", "Diamond", "Radioactive", "Start", "Start2", "Earth"), "A")
    private val pingIcon by choices("PingIcon", arrayOf("Default", "Ping2", "Ping3", "Ping4", "Ping5"), "Ping3") { style == "legacy" }
    private val gappleIcon by choices("GappleIcon", arrayOf("Default", "Heart", "Heart2"), "Default") { style == "legacy" }
    private val userIcon by choices("UserIcon", arrayOf("Default", "User2", "User3", "User4"), "Default") { style == "legacy" }

    private val customip by boolean("customIP", false) { style == "legacy" }
    private val ip by text("IP", "hidden.ip") { customip }

    // legacy 样式背景：颜色与透明度统一由 color 类型设置控制（alpha 通道即透明度）
    private val legacyBackground by color("Background", Color(0, 0, 0, 160)) { style == "legacy" }
    // legacy 样式品牌色（图标染色 / 客户端名文字等）
    private val legacyAccent by color("Accent", Color(255, 255, 255)) { style == "legacy" }

    // iOS 样式背景自定义颜色（含 alpha 通道，透明度直接由颜色 alpha 控制）
    private val iosBackgroundColor by color("iOS-Background", Color(0, 0, 0)) { style == "ios" }

    private val ShadowCheck by boolean("Shadow", false) { style == "legacy" }
    private val shadowRadiusValue by float("Shadow-Radius", 15F, 1F..50F) { ShadowCheck }
    private val shadowColor by color("Shadow-Color", Color(0, 0, 0, 120)) { ShadowCheck }

    // iOS 样式阴影
    private val iosShadowCheck by boolean("iOS-Shadow", false) { style == "ios" }
    private val iosShadowRadius by float("iOS-Shadow-Radius", 14F, 1F..50F) { style == "ios" && iosShadowCheck }
    private val iosShadowColor by color("iOS-Shadow-Color", Color(0, 0, 0, 110)) { style == "ios" && iosShadowCheck }

    // ===== Prism 新样式设置 =====
    // 背景透明度直接由颜色 alpha 控制；blur 使用全局 Blur 设置
    private val prismBackground by color("Prism-Background", Color(0, 0, 0, 235)) { style == "prism" }
    private val prismAccent by color("Prism-Accent", Color(108, 166, 255)) { style == "prism" }
    private val newRadius by float("New-Radius", 16F, 0F..50F) { style == "prism" }
    private val newShadowCheck by boolean("New-Shadow", true) { style == "prism" }
    private val newShadowRadius by float("New-Shadow-Radius", 14F, 1F..50F) { newShadowCheck && style == "prism" }
    private val newShadowColor by color("New-Shadow-Color", Color(0, 0, 0, 110)) { newShadowCheck && style == "prism" }

    // ===== Opai 样式设置（视觉常量对照 Samsara DynamicIsland）=====
    // 背景透明度/强调色等样式属性统一由 color 类型设置的 alpha/RGB 控制
    private val opaiBackground by color("Opai-Background", Color(0, 0, 0, 152)) { style == "opai" }
    private val opaiAccent by color("Opai-Accent", Color(187, 195, 255)) { style == "opai" }
    private val opaiRadius by float("Opai-Radius", 8.5F, 0F..24F) { style == "opai" }
    private val opaiSpeed by float("Opai-AnimSpeed", 1F, 0.25F..3F) { style == "opai" }
    private val opaiStatusBar by boolean("Opai-StatusBar", true) { style == "opai" }
    private val opaiProgress by boolean("Opai-Progress", true) { style == "opai" }
    private val opaiTabList by boolean("Opai-TabList", true) { style == "opai" }
    private val opaiTabListRows by int("Opai-TabList-Rows", 10, 5..20) { opaiTabList && style == "opai" }
    private val opaiChest by boolean("Opai-Chest", true) { style == "opai" }


    // Blink 面板：Blink 启用时显示当前 blink 时间，Pulse 启用时显示进度条
    private val blinkPanelCheck by boolean("Blink-Panel", true)

    private val blurCheck by boolean("Blur", true)
    private val blurMode by choices("BlurMode", arrayOf("Gaussian", "Dual", "Better", "Kawase"), "Better") { blurCheck }
    private val blurRadius by float("BlurStrength", 10F, 1F..50F) { blurCheck }
    private val kawaseIterations by int("Kawase-Iterations", 4, 1..10) { blurCheck && blurMode == "Kawase" }
    private val kawaseOffset by int("Kawase-Offset", 3, 1..10) { blurCheck && blurMode == "Kawase" }

    private val notifyDuration by int("NotifyTime(ms)", 1000, 100..10000) { style == "legacy" || style == "prism" || style == "opai" }
    private val versionNameUp by text("VersionName", "development") { false }
    private val ButtonColor by color("Button-Color", Color(20, 150, 180, 255)) { style == "legacy" }
    private val ModuleNotify by boolean("Notification", true)
    private val isScaffold by boolean("Scaffold", true)
    private val scaffoldStyle by choices("ScaffoldStyle", arrayOf("Classic", "Bar", "Minimal", "Compact", "Standard", "Circle", "Split", "Modern", "Outline"), "Classic") { isScaffold && style == "legacy" }
    private val ScaffoldTheme by color("ScaffoldTheme", Color(65, 130, 225)) { style == "legacy" }
    private val standardShowBlockCount by boolean("StandardShowBlockCount", true) { isScaffold && scaffoldStyle == "Standard" }

    private val breakProgressCheck by boolean("BreakProgress", true) { style == "legacy" }
    private val breakProgressTheme by color("BreakProgressTheme", Color(225, 150, 65)) { style == "legacy" }
    private val breakProgressOnlyFuckerNuker by boolean("OnlyFuckerAndNuker", true) { breakProgressCheck }

    private val showGappleProgress by boolean("GappleProgress", true)
    private val gappleProgressTheme by color("GappleProgressTheme", Color(255, 215, 0))

    private val pearlCountdown by boolean("PearlCountdown", true)

    private val ChestTheme by boolean("Chest", true)
    private val ChestRounded by float("ChestRoundRadius", 4F, 0.0F..8.0F)

    private val tabListCheck by boolean("TabList", true)
    private val tabListMaxRows by int("TabList-MaxRows", 20, 5..100)

    private val bpsUpdateInterval by int("BPS-Update-Interval(ms)", 100, 50..500) { style == "legacy" }
    private val versionNameDown = clientVersionText

    private val showLyricOnIsland by boolean("ShowLyric", true) { style == "legacy" }
    private val lyricDisplayMode by choices("LyricMode", arrayOf("None", "Below", "Inside", "Float", "Full"), "None") { showLyricOnIsland }
    private val lyricHeight by int("LyricHeight", 40, 20..80) { showLyricOnIsland && lyricDisplayMode != "None" }
    private val lyricFloatOffsetY by int("LyricFloatOffsetY", 20, 0..200) { showLyricOnIsland && (lyricDisplayMode == "Float" || lyricDisplayMode == "Full") }
    private val lyricShowMusicName by boolean("LyricShowMusicName", true) { showLyricOnIsland && lyricDisplayMode != "None" && lyricDisplayMode != "Full" }
    private val lyricShowPrevious by boolean("LyricShowPrevious", true) { showLyricOnIsland && lyricDisplayMode == "Below" }
    private val lyricShowNext by boolean("LyricShowNext", true) { showLyricOnIsland && lyricDisplayMode == "Below" }
    private val lyricMusicNameFont by choices("LyricMusicNameFont", arrayOf("ExtraBold35", "ExtraBold40", "Semibold35", "Semibold40", "Regular30", "Regular35", "Regular40", "Regular45", "Bold180"), "Semibold35") { showLyricOnIsland && lyricDisplayMode != "None" }
    private val lyricTextFont by choices("LyricTextFont", arrayOf("ExtraBold35", "ExtraBold40", "Semibold35", "Semibold40", "Regular30", "Regular35", "Regular40", "Regular45", "Bold180"), "Regular35") { showLyricOnIsland && lyricDisplayMode != "None" }
    private val lyricColorMode by choices("LyricColorMode", arrayOf("Custom", "Theme"), "Theme") { showLyricOnIsland && lyricDisplayMode != "None" }
    private val lyricGradientMode by choices("LyricGradientMode", arrayOf("Sync", "LeftToRight", "RightToLeft"), "Sync") { showLyricOnIsland && lyricDisplayMode != "None" && lyricColorMode == "Theme" }
    private val lyricCustomColor by color("LyricCustomColor", Color(255, 255, 255)) { showLyricOnIsland && lyricDisplayMode != "None" && lyricColorMode == "Custom" }
    private val lyricBackgroundAlpha by int("LyricBackgroundAlpha", 160, 0..255) { showLyricOnIsland && (lyricDisplayMode == "Below" || lyricDisplayMode == "Float" || lyricDisplayMode == "Full") }
    private val lyricTextAlpha by int("LyricTextAlpha", 0, 0..255) { showLyricOnIsland && lyricDisplayMode != "None" }
    private val lyricBlur by boolean("LyricBlur", true) { showLyricOnIsland && (lyricDisplayMode == "Below" || lyricDisplayMode == "Float" || lyricDisplayMode == "Full") }
    private val lyricBounce by boolean("LyricBounce", true) { showLyricOnIsland && lyricDisplayMode != "None" }
    private val lyricScrollAnimation by boolean("LyricScrollAnimation", true) { showLyricOnIsland && (lyricDisplayMode == "Below" || lyricDisplayMode == "Float") }
    private val lyricScrollAnimTime by int("LyricScrollAnimTime", 300, 100..1000) { showLyricOnIsland && lyricScrollAnimation && (lyricDisplayMode == "Below" || lyricDisplayMode == "Float") }
    private val lyricShowProgress by boolean("LyricShowProgress", true) { showLyricOnIsland && (lyricDisplayMode == "Below" || lyricDisplayMode == "Float" || lyricDisplayMode == "Full") }
    private val lyricFullWidth by int("LyricFullWidth", 300, 100..500) { showLyricOnIsland && lyricDisplayMode == "Full" }
    private val lyricFullHeight by int("LyricFullHeight", 50, 30..150) { showLyricOnIsland && lyricDisplayMode == "Full" }
    private val lyricFullAnimation by choices("LyricFullAnimation", arrayOf("None", "Fade", "SlideLeft", "SlideRight", "SlideUp", "SlideDown", "Scale", "Typewriter"), "Fade") { showLyricOnIsland && lyricDisplayMode == "Full" }
    private val lyricFullAnimTime by int("LyricFullAnimTime", 300, 100..1000) { showLyricOnIsland && lyricDisplayMode == "Full" && lyricFullAnimation != "None" }

    private var breakProgressTarget = 0F
    private var animatedBreakProgress = 0F
    private var lastBreakProgressUpdateTime: Long = 0L

    private var gappleProgressTarget = 0F
    private var animatedGappleProgress = 0F
    private var lastGappleProgressUpdateTime: Long = 0L

    // iOS / Opai 样式 Scaffold 进度条的平滑动画值（scaffoldProgress 原始值直接跳变）
    private var animatedScaffoldProgress = 0F

    private var pearlEntityId = -1
    private var pearlLandingTicks = -1 // 抛物线预测的落地总 tick
    private var pearlThrowTimeMs = 0L  // 本地投掷时刻
    private var pearlTicksRemaining = -1F // 剩余 tick（由投掷时间 + 预测落地时间推算）

    // Scaffold 进度条基准：启用 Scaffold 时快照物品栏内可用方块总数作为最大值
    private var scaffoldMaxSnapshot = 0
    private var scaffoldWasActive = false

    // 反射缓存：避免每帧重新查找 Method/Field，减少渲染热路径开销
    private var gappleProgressMethod: java.lang.reflect.Method? = null
    private var gappleIsEatingField: java.lang.reflect.Field? = null
    private var gappleTicksField: java.lang.reflect.Field? = null
    private var gappleCField: java.lang.reflect.Field? = null
    private var gappleReflectInited = false

    private var AnimGlobalX = 0F
    private var AnimGlobalY = 0F
    private var AnimGlobalWidth = 100F
    private var AnimGlobalHeight = 28F

    private var VelGlobalX = 0f
    private var VelGlobalY = 0f
    private var VelGlobalWidth = 0f
    private var VelGlobalHeight = 0f

    // Non-bounce animation state
    private var animStartTime = 0L
    private var animStartX = 0F
    private var animStartY = 0F
    private var animStartW = 0F
    private var animStartH = 0F
    private var animTargetX = 0F
    private var animTargetY = 0F
    private var animTargetW = 0F
    private var animTargetH = 0F
    private var lastExpandAnim = "Bounce"
    private val ANIM_DURATION = 350L

    private var animBubbleY = 0F
    private var animBubbleAlpha = 0F
    private var animBubbleWidth = 0F
    private var animBubbleHeight = 0F
    private var velBubbleY = 0f
    private var velBubbleAlpha = 0f
    private var velBubbleWidth = 0f
    private var velBubbleHeight = 0f

    private var animInsideWidth = 0F
    private var velInsideWidth = 0f

    private var animScrollOffset = 0F
    private var velScrollOffset = 0f
    private var lastLyricChangeTime = 0L
    private var lastLyricText = ""
    private var scrollAnimProgress = 0F
    private var velScrollAnim = 0F

    private data class SlotRipple(val x: Float, val y: Float, val startTime: Long)
    private val slotRipples = CopyOnWriteArrayList<SlotRipple>()
    private val prevSlotItems = HashMap<Int, ItemStack?>()
    private var lastChestContainerHash: Int = 0

    private const val ITEM_NOTIFY_HEIGHT = 38F
    private const val NORMAL_WATERMARK_HEIGHT = 28F

    private var prevX: Double = 0.0
    private var prevZ: Double = 0.0
    private var AnimatedBps = 0.0
    private var lastBPSUpdateTime: Long = 0L
    private var displayedBPS: Double = 0.0
    private var ProgressBarAnimationWidth = 0F

    private var prevModuleStates = HashMap<Module, Boolean>()
    private val notifications = CopyOnWriteArrayList<ToggleNotification>()
    private var scaledScreen = ScaledResolution(mc)
    private var width = scaledScreen.scaledWidth
    private var height = scaledScreen.scaledHeight
    private var start_y = (height / 20).toFloat()

    // 缓存常用模块引用，避免每帧调用 ModuleManager.getModule(name) 的 O(n) 线性扫描
    // 模块在客户端启动时注册，使用 lazy 安全
    private val scaffoldModuleRef by lazy { ModuleManager.getModule("Scaffold") }
    private val scaffold2ModuleRef by lazy { ModuleManager.getModule("Scaffold2") }
    private val gappleModuleRef by lazy { ModuleManager.getModule("Gapple") }
    private val blinkModuleRef by lazy { ModuleManager.getModule("Blink") as? Blink }
    private val fuckerModuleRef by lazy { ModuleManager.getModule("Fucker") }
    private val nukerModuleRef by lazy { ModuleManager.getModule("Nuker") }

    private var headerFooterCacheTime = 0L
    private var cachedHeader: List<String>? = null
    private var cachedFooter: List<String>? = null

    private fun getSafePing(): Int {
        val player = mc.thePlayer ?: return 0
        return mc.netHandler?.getPlayerInfo(player.uniqueID)?.responseTime ?: 0
    }

    private fun getLogoResource(): ResourceLocation {
        return when (logoIcon) {
            "Default" -> ResourceLocation("airplus/watermark_images/logo_icon.png")
            "A" -> ResourceLocation("airplus/watermark_images/A.png")
            "Diamond" -> ResourceLocation("airplus/watermark_images/Diamond.png")
            "Radioactive" -> ResourceLocation("airplus/watermark_images/Radioactive.png")
            "Start" -> ResourceLocation("airplus/watermark_images/start.png")
            "Start2" -> ResourceLocation("airplus/watermark_images/start2.png")
            "Earth" -> ResourceLocation("airplus/watermark_images/earth.png")
            else -> ResourceLocation("airplus/watermark_images/logo_icon.png")
        }
    }

    private fun getPingResource(): ResourceLocation {
        return when (pingIcon) {
            "Default" -> ResourceLocation("airplus/watermark_images/ms.png")
            "Ping2" -> ResourceLocation("airplus/watermark_images/ping2.png")
            "Ping3" -> ResourceLocation("airplus/watermark_images/ping3.png")
            "Ping4" -> ResourceLocation("airplus/watermark_images/ping4.png")
            "Ping5" -> ResourceLocation("airplus/watermark_images/ping5.png")
            else -> ResourceLocation("airplus/watermark_images/ms.png")
        }
    }

    private fun getGappleResource(): ResourceLocation {
        return when (gappleIcon) {
            "Default" -> ResourceLocation("airplus/watermark_images/apple.png")
            "Heart" -> ResourceLocation("airplus/watermark_images/heart.png")
            "Heart2" -> ResourceLocation("airplus/watermark_images/heart2.png")
            else -> ResourceLocation("airplus/watermark_images/apple.png")
        }
    }

    private fun getUserResource(): ResourceLocation {
        return when (userIcon) {
            "Default" -> ResourceLocation("airplus/watermark_images/user.png")
            "User2" -> ResourceLocation("airplus/watermark_images/user2.png")
            "User3" -> ResourceLocation("airplus/watermark_images/user3.png")
            "User4" -> ResourceLocation("airplus/watermark_images/user4.png")
            else -> ResourceLocation("airplus/watermark_images/user.png")
        }
    }

    private fun applyBlur(x: Float, y: Float, w: Float, h: Float) {
        // 保存将被修改的 GL 状态，结束时精确恢复（而不是无条件覆盖）
        val prevBlend = glIsEnabled(GL_BLEND)
        val prevBlendSrc = glGetInteger(GL_BLEND_SRC)
        val prevBlendDst = glGetInteger(GL_BLEND_DST)
        val prevAlpha = glIsEnabled(GL_ALPHA_TEST)
        val prevTexture = glIsEnabled(GL_TEXTURE_2D)

        when (blurMode) {
            "Gaussian" -> BlurEffects.blurArea(x, y, w, h, blurRadius, BlurEffects.BlurMode.GAUSSIAN)
            "Dual" -> BlurEffects.blurArea(x, y, w, h, blurRadius, BlurEffects.BlurMode.DUAL)
            "Better" -> BlurEffects.blurArea(x, y, w, h, blurRadius, BlurEffects.BlurMode.BETTER)
            "Kawase" -> {
                // 使用区域版本的 Kawase：scissor 限制模糊范围，
                // 圆角形状由外层 EmbeddedStencil（erase(true) 后 stencil==1）保证
                val sr = ScaledResolution(mc)
                val factor = sr.scaleFactor
                val px = (x * factor).toInt()
                val py = mc.displayHeight - ((y + h) * factor).toInt()
                val pw = (w * factor).toInt()
                val ph = (h * factor).toInt()
                net.airplus.utils.render.shader.KawaseBlur.renderBlurScissor(kawaseIterations, kawaseOffset, px, py, pw, ph)
            }
        }

        // 精确恢复进入前的 GL 状态，避免 blend func / alpha test 泄漏到后续渲染
        if (prevBlend) glEnable(GL_BLEND) else glDisable(GL_BLEND)
        glBlendFunc(prevBlendSrc, prevBlendDst)
        if (prevAlpha) glEnable(GL_ALPHA_TEST) else glDisable(GL_ALPHA_TEST)
        if (prevTexture) glEnable(GL_TEXTURE_2D) else glDisable(GL_TEXTURE_2D)
    }

    private fun spring(current: Float, target: Float, velocity: Float): Pair<Float, Float> {
        val displacement = target - current
        val force = displacement * animTension
        val drag = velocity * animFriction
        val acceleration = force - drag
        val newVelocity = velocity + acceleration
        val newPosition = current + newVelocity
        return newPosition to newVelocity
    }

    private fun easeOutCubic(t: Float): Float = 1f - (1f - t).pow(3)
    private fun linear(t: Float): Float = t
    private fun backOut(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        return 1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
    }

    private fun applyExpandAnim(current: Float, start: Float, target: Float, elapsed: Float): Float {
        val t = (elapsed / ANIM_DURATION.toFloat()).coerceIn(0f, 1f)
        val anim = getCurrentExpandAnim()
        val eased = when (anim) {
            "EaseOut" -> easeOutCubic(t)
            "Linear" -> linear(t)
            "Back" -> backOut(t)
            else -> return current
        }
        return start + (target - start) * eased
    }

    private fun getCurrentExpandAnim(): String {
        return when (style) {
            "ios" -> iosExpandAnim
            "legacy" -> legacyExpandAnim
            else -> legacyExpandAnim
        }
    }

    private fun getTabListHeaderFooter(): Pair<IChatComponent?, IChatComponent?> {
        try {
            val tabOverlay = mc.ingameGUI.tabList
            val cls = GuiPlayerTabOverlay::class.java

            var headerField = try { cls.getDeclaredField("header") } catch (e: Exception) { cls.getDeclaredField("field_175256_a") }
            headerField.isAccessible = true
            val header = headerField.get(tabOverlay) as? IChatComponent

            var footerField = try { cls.getDeclaredField("footer") } catch (e: Exception) { cls.getDeclaredField("field_175255_b") }
            footerField.isAccessible = true
            val footer = footerField.get(tabOverlay) as? IChatComponent

            return Pair(header, footer)
        } catch (e: Exception) {
            return Pair(null, null)
        }
    }

    /**
     * Shared tab list size calculation for all styles.
     * Returns (width, height) based on player count, header/footer lines, and maxRows setting.
     */
    private fun calcTabListSize(players: List<NetworkPlayerInfo>, header: List<String>, footer: List<String>): Pair<Float, Float> {
        val playerCount = players.size
        val maxRows = tabListMaxRows
        val columns = ceil(playerCount.toDouble() / maxRows.toDouble()).toInt()
        val headSize = 10F
        val outerPadding = 8F
        val padding = 6F
        val spacing = 4F
        var maxNameWidth = 50F

        players.forEach {
            val fullName = mc.ingameGUI.tabList.getPlayerName(it)
            val w = Fonts.fontRegular35.getStringWidth(fullName).toFloat()
            if (w > maxNameWidth) maxNameWidth = w
        }
        val columnWidth = padding + headSize + spacing + maxNameWidth + spacing + 25F + padding
        val playersWidth = columns * columnWidth

        var maxHeaderW = 0f
        header.forEach { maxHeaderW = max(maxHeaderW, Fonts.fontRegular35.getStringWidth(it).toFloat()) }
        var maxFooterW = 0f
        footer.forEach { maxFooterW = max(maxFooterW, Fonts.fontRegular35.getStringWidth(it).toFloat()) }

        val targetWidth = max(playersWidth, max(maxHeaderW, maxFooterW) + padding * 2)

        val lineH = Fonts.fontRegular35.FONT_HEIGHT + 2
        val headerHeight = if (header.isNotEmpty()) header.size * lineH + 2 else 0
        val footerHeight = if (footer.isNotEmpty()) footer.size * lineH + 2 else 0

        val actualRows = if (columns == 1) playerCount else maxRows
        val playersBlockHeight = actualRows * (headSize + spacing) - spacing

        val targetHeight = outerPadding +
                headerHeight.toFloat() +
                (if (headerHeight > 0) 2F else 0F) +
                playersBlockHeight +
                (if (footerHeight > 0) 2F else 0F) +
                footerHeight.toFloat() +
                outerPadding

        return Pair(targetWidth, targetHeight)
    }

    private fun shouldShowBreakProgress(): Boolean {
        if (!breakProgressOnlyFuckerNuker) return true
        val fucker = fuckerModuleRef ?: return false
        val nuker = nukerModuleRef ?: return false
        return fucker.state || nuker.state
    }

    val onUpdate = handler<UpdateEvent> {
        if (mc.thePlayer == null || mc.theWorld == null) return@handler

        if (tabListCheck) {
            mc.gameSettings.keyBindPlayerList.pressed = false
        }

        val distanceX = mc.thePlayer.posX - prevX
        val distanceZ = mc.thePlayer.posZ - prevZ
        val currentCalculatedBPS = sqrt(distanceX.pow(2) + distanceZ.pow(2)) * 20.0
        if (System.currentTimeMillis() - lastBPSUpdateTime >= bpsUpdateInterval) {
            displayedBPS = currentCalculatedBPS
            lastBPSUpdateTime = System.currentTimeMillis()
        }
        prevX = mc.thePlayer.posX
        prevZ = mc.thePlayer.posZ

        if (mc.playerController != null && mc.thePlayer != null) {
            val currentBreakProgress = mc.playerController.curBlockDamageMP
            if (System.currentTimeMillis() - lastBreakProgressUpdateTime >= 50) {
                breakProgressTarget = currentBreakProgress
                lastBreakProgressUpdateTime = System.currentTimeMillis()
            }
        } else {
            breakProgressTarget = 0F
        }

        val gappleModule = gappleModuleRef
        if (gappleModule != null && gappleModule.state) {
            val currentProgress = getGappleEatingProgress()
            if (System.currentTimeMillis() - lastGappleProgressUpdateTime >= 50) {
                gappleProgressTarget = currentProgress
                lastGappleProgressUpdateTime = System.currentTimeMillis()
            }
        } else {
            gappleProgressTarget = 0F
        }

        if (pearlCountdown) {
            updatePearlTracking()
        } else {
            resetPearlTracking()
        }

        if (ModuleNotify) {
            for (module in ModuleManager) {
                if (!prevModuleStates.containsKey(module)) {
                    prevModuleStates[module] = module.state
                    continue
                }
                val prevState = prevModuleStates[module]!!
                val currentState = module.state
                if (prevState != currentState) {
                    prevModuleStates[module] = currentState

                    val titleText = "Module Toggled"
                    val modName = "${module.name}"
                    val stateText = if (currentState) "§l§aEnabled" else "§l§cDisabled"
                    val message = "§l$modName§r §fhas been $stateText§r §f!"

                    showToggleNotification(titleText, message, currentState, module.name)
                }
            }
        }
    }

    private fun getGappleEatingProgress(): Float {
        val gappleModule = gappleModuleRef ?: return 0f
        if (!gappleModule.state) return 0f

        // 初始化反射缓存（仅执行一次），避免每帧 getDeclaredMethod/getDeclaredField
        if (!gappleReflectInited) {
            gappleReflectInited = true
            try {
                gappleProgressMethod = gappleModule::class.java.getDeclaredMethod("getEatingProgress")
                gappleProgressMethod?.isAccessible = true
            } catch (_: Exception) {
                try {
                    gappleIsEatingField = gappleModule::class.java.getDeclaredField("isEating")
                    gappleIsEatingField?.isAccessible = true
                    gappleTicksField = gappleModule::class.java.getDeclaredField("ticks")
                    gappleTicksField?.isAccessible = true
                    gappleCField = gappleModule::class.java.getDeclaredField("c")
                    gappleCField?.isAccessible = true
                } catch (_: Exception) {}
            }
        }

        return try {
            // 优先使用缓存的方法
            gappleProgressMethod?.let {
                (it.invoke(gappleModule) as? Float) ?: 0f
            } ?: run {
                // 回退到字段方式
                val isEating = gappleIsEatingField?.getBoolean(gappleModule) ?: return 0f
                if (!isEating) return 0f
                val ticks = gappleTicksField?.getInt(gappleModule) ?: return 0f
                val c = gappleCField?.get(gappleModule) as? Int ?: return 0f
                (ticks.toFloat() / c.toFloat()).coerceIn(0f, 1f)
            }
        } catch (_: Exception) {
            0f
        }
    }

    /**
     * 珍珠追踪：找到玩家自己投掷的末影珍珠，记录本地投掷时间，
     * 用抛物线预测落地时间，倒计时到珍珠落地为止
     */
    private fun updatePearlTracking() {
        val player = mc.thePlayer
        val world = mc.theWorld
        if (player == null || world == null) {
            resetPearlTracking()
            return
        }

        val pearls = world.loadedEntityList.filterIsInstance<EntityEnderPearl>()
        val pearl = pearls.firstOrNull { it.entityId == pearlEntityId }
            ?: pearls.firstOrNull {
                // 1.8.9 客户端不会同步珍珠的 thrower 字段（handleSpawnObject 不设置），
                // 改用启发式判定是自己的珍珠：刚出生(<=1 tick)、出生点在玩家眼睛 2 格内、
                // 初速方向与玩家视线同向
                it.ticksExisted <= 1 &&
                    Vec3(it.posX - player.posX, it.posY - (player.posY + player.getEyeHeight()), it.posZ - player.posZ)
                        .lengthVector() < 2.0 &&
                    it.motionX * player.lookVec.xCoord + it.motionY * player.lookVec.yCoord +
                        it.motionZ * player.lookVec.zCoord > 0
            }
        if (pearl == null) {
            resetPearlTracking()
            return
        }

        if (pearl.entityId != pearlEntityId) {
            // 新珍珠：记录本地投掷时刻，用初始运动做抛物线预测得到落地所需 tick
            pearlEntityId = pearl.entityId
            pearlThrowTimeMs = System.currentTimeMillis()
            pearlLandingTicks = predictPearlLandingTicks(pearl.posX, pearl.posY, pearl.posZ,
                pearl.motionX, pearl.motionY, pearl.motionZ)
        }

        // 落地倒计时 = 预测落地 tick - 已飞行 tick
        val elapsedTicks = (System.currentTimeMillis() - pearlThrowTimeMs) / 50.0
        pearlTicksRemaining = (pearlLandingTicks - elapsedTicks).toFloat()
    }

    private fun resetPearlTracking() {
        pearlEntityId = -1
        pearlLandingTicks = -1
        pearlThrowTimeMs = 0L
        pearlTicksRemaining = -1F
    }

    /**
     * 抛物线预测：模拟珍珠运动直到碰撞方块/实体，返回落地所需 tick 数
     * （重力 0.03，空气中摩擦 0.99，水中 0.8，实体碰撞判定参考 Projectiles 的预测算法）
     */
    private fun predictPearlLandingTicks(px0: Double, py0: Double, pz0: Double,
                                         mx0: Double, my0: Double, mz0: Double): Int {
        val world = mc.theWorld ?: return -1
        val player = mc.thePlayer ?: return -1
        val size = 0.25

        var px = px0
        var py = py0
        var pz = pz0
        var mx = mx0
        var my = my0
        var mz = mz0

        for (tick in 1..200) {
            val before = Vec3(px, py, pz)
            val after = Vec3(px + mx, py + my, pz + mz)
            if (world.rayTraceBlocks(before, after, false, true, false) != null)
                return tick

            // 实体碰撞检测
            val hitBox = AxisAlignedBB(px - size, py - size, pz - size, px + size, py + size, pz + size)
                .addCoord(mx, my, mz)
            for (entity in world.getEntitiesWithinAABBExcludingEntity(player, hitBox)) {
                if (entity.canBeCollidedWith()) return tick
            }

            px += mx
            py += my
            pz += mz

            val inWater = world.getBlockState(BlockPos(px, py, pz)).block.material === Material.water
            val drag = if (inWater) 0.8 else 0.99
            mx *= drag
            mz *= drag
            my = my * drag - 0.03

            if (py < -10.0) return tick
        }
        return 200
    }

    val onScreen = handler<ScreenEvent>(always = true) { event ->
        if (mc.theWorld == null || mc.thePlayer == null) return@handler
    }

    val onRender2D = handler<Render2DEvent> {
        glPushMatrix()
        glEnable(GL_BLEND)
        glDisable(GL_TEXTURE_2D)
        GlStateManager.tryBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO)
        glEnable(GL_LINE_SMOOTH)
        glHint(GL_LINE_SMOOTH_HINT, GL_NICEST)

        updateNotifications()
        scaledScreen = ScaledResolution(mc)
        width = scaledScreen.scaledWidth
        height = scaledScreen.scaledHeight
        start_y = (height / 20).toFloat()

        val scaffoldModule = scaffoldModuleRef
        val scaffoldModule2 = scaffold2ModuleRef

        val isChestOpen = mc.currentScreen is GuiChest && ChestTheme
        val chestSlots = if (isChestOpen) {
            (mc.currentScreen as GuiChest).inventorySlots?.inventorySlots?.filter { it.inventory != mc.thePlayer?.inventory } ?: emptyList()
        } else emptyList()

        if (!isChestOpen) {
            prevSlotItems.clear()
            slotRipples.clear()
            lastChestContainerHash = 0
        } else {
            val currentContainerId = (mc.currentScreen as GuiChest).inventorySlots.windowId
            if (currentContainerId != lastChestContainerHash) {
                prevSlotItems.clear()
                slotRipples.clear()
                lastChestContainerHash = currentContainerId
            }
        }

        val tabKey = mc.gameSettings.keyBindPlayerList.keyCode
        val isTabKeyDown = if (tabKey > 0) Keyboard.isKeyDown(tabKey) else false
        val showTabList = tabListCheck && isTabKeyDown && mc.netHandler != null

        val playerList = if (showTabList) {
            mc.netHandler.playerInfoMap
                .filter { it.gameProfile.name != null }
                .sortedWith(compareBy({ it.gameProfile.name }))
        } else emptyList()

        // Shared header/footer cache for tab list (used by all styles)
        var headerLines: List<String> = emptyList()
        var footerLines: List<String> = emptyList()
        if (showTabList && playerList.isNotEmpty()) {
            if (System.currentTimeMillis() - headerFooterCacheTime > 500) {
                val (h, f) = getTabListHeaderFooter()
                cachedHeader = h?.formattedText?.split("\n")
                cachedFooter = f?.formattedText?.split("\n")
                headerFooterCacheTime = System.currentTimeMillis()
            }
            headerLines = cachedHeader ?: emptyList()
            footerLines = cachedFooter ?: emptyList()
        }

        val lerpSpeed = 0.2f * (Minecraft.getDebugFPS() / 60f).coerceIn(0.5f, 2f)
        animatedBreakProgress += (breakProgressTarget - animatedBreakProgress) * lerpSpeed
        animatedBreakProgress = animatedBreakProgress.coerceIn(0F, 1F)

        animatedGappleProgress += (gappleProgressTarget - animatedGappleProgress) * lerpSpeed
        animatedGappleProgress = animatedGappleProgress.coerceIn(0F, 1F)

        // Scaffold 进度（iOS / Opai 面板条）平滑动画
        animatedScaffoldProgress += (scaffoldProgress() - animatedScaffoldProgress) * lerpSpeed
        animatedScaffoldProgress = animatedScaffoldProgress.coerceIn(0F, 1F)

        // Scaffold 进度条基准：启用 Scaffold 瞬间快照物品栏可用方块总数作为最大值，
        // 之后不再跟随当前数量变化（补充方块也不会上调最大值，超出时进度钳制在 100%）
        val scaffoldNowActive = isScaffold && (scaffoldModuleRef?.state == true || scaffold2ModuleRef?.state == true)
        val inventoryBlocks = inventoryBlockCount()
        if (scaffoldNowActive && !scaffoldWasActive) {
            scaffoldMaxSnapshot = inventoryBlocks
        }
        scaffoldWasActive = scaffoldNowActive

        // Blink 面板状态（所有样式共用）
        val blinkOn = blinkPanelCheck && blinkModuleRef?.state == true
        val blinkPulse = blinkModuleRef?.pulseEnabled() == true

        var targetWidth = 0F
        var targetHeight = 0F
        var targetX = 0F
        var targetY = start_y
        var renderMode = "NONE"

        if (style == "legacy" && showLyricOnIsland && lyricDisplayMode == "Full" && MusicHub.currentLyricDisplay.isNotEmpty()) {
            renderMode = "LYRIC_FULL"
            val textFont = getLyricTextFont()
            val currentLyric = MusicHub.currentLyricDisplay
            val textWidth = textFont.getStringWidth(currentLyric).toFloat()
            targetWidth = (textWidth + 40F).coerceIn(100F, lyricFullWidth.toFloat())
            targetHeight = if (lyricShowProgress) 50F else 35F
            targetX = (width - targetWidth) / 2
            targetY = lyricFloatOffsetY.toFloat()
        } else if (style == "legacy" && chestSlots.isNotEmpty()) {
            renderMode = "CHEST"
            val columns = 9
            val rows = (chestSlots.size + 8) / 9
            val padding = 8F
            val slotSize = 16F
            targetWidth = columns * slotSize + padding * 2
            targetHeight = rows * slotSize + padding * 2
            targetX = (width - targetWidth) / 2
            targetY = start_y.coerceIn(5f, height - targetHeight - 5f)

        } else if (style == "legacy" && showTabList && playerList.isNotEmpty()) {
            renderMode = "TABLIST"
            val (tw, th) = calcTabListSize(playerList, headerLines, footerLines)
            targetWidth = tw
            targetHeight = th
            targetX = (width - targetWidth) / 2
            targetY = start_y

        } else if (style == "legacy" && pearlCountdown && pearlTicksRemaining >= 0f) {
            renderMode = "PEARL"
            targetWidth = 190F
            targetHeight = 58F
            targetX = (width - targetWidth) / 2
            targetY = start_y

        } else if (style == "legacy" && (scaffoldModule?.state == true ||  scaffoldModule2?.state == true && isScaffold)) {
            renderMode = "SCAFFOLD"
            when (scaffoldStyle) {
                "Classic" -> { targetWidth = 190F; targetHeight = 58F }
                "Bar" -> { targetWidth = 280F; targetHeight = 28F }
                "Minimal" -> { targetWidth = 120F; targetHeight = 24F }
                "Compact" -> { targetWidth = 160F; targetHeight = 40F }
                "Standard" -> { targetWidth = 280F; targetHeight = 28F }
                "Circle" -> { targetWidth = 80F; targetHeight = 80F }
                "Split" -> { targetWidth = 200F; targetHeight = 50F }
                "Modern" -> { targetWidth = 220F; targetHeight = 65F }
                "Outline" -> { targetWidth = 180F; targetHeight = 35F }
                else -> { targetWidth = 190F; targetHeight = 58F }
            }
            targetX = (width - targetWidth) / 2
        } else if (style == "legacy" && showGappleProgress && animatedGappleProgress > 0.01f && gappleModuleRef?.state == true) {
            renderMode = "GAPPLE_PROGRESS"
            targetWidth = 190F
            targetHeight = 58F
            targetX = (width - targetWidth) / 2
            targetY = start_y
        } else if (style == "legacy" && blinkOn) {
            renderMode = "BLINK"
            if (blinkPulse) {
                targetWidth = 190F
                targetHeight = 58F
            } else {
                targetWidth = 16F + Fonts.fontSemibold40.getStringWidth("Blink").toFloat() + 8F +
                        Fonts.fontRegular40.getStringWidth(blinkTimeString()).toFloat() + 16F
                targetHeight = 48F
            }
            targetX = (width - targetWidth) / 2
            targetY = start_y
        } else if (style == "legacy" && notifications.isNotEmpty() && ModuleNotify) {
            renderMode = "NOTIFY_STACK"
            val borderInfo = calcMaxNotificationWidth()
            targetWidth = borderInfo.coerceAtLeast(180F)
            targetHeight = (notifications.size * ITEM_NOTIFY_HEIGHT).toFloat()
            targetX = (width - targetWidth) / 2
        } else if (style == "legacy" && breakProgressCheck && animatedBreakProgress > 0.01f && shouldShowBreakProgress()) {
            renderMode = "BREAK_PROGRESS"
            targetWidth = 190F
            targetHeight = 58F
            targetX = (width - targetWidth) / 2
            targetY = start_y
        } else {
            when (style) {
                "prism" -> {
                    val scaffoldOn = isScaffold && (scaffoldModuleRef?.state == true || scaffold2ModuleRef?.state == true)
                    val musicOn = MusicHub.isCurrentlyPlaying && (MusicHub.currentLyricDisplay.isNotEmpty() || MusicHub.currentMusicName != "None")
                    val toggleActive = ModuleNotify && notifications.isNotEmpty()
                    val gappleOn = showGappleProgress && animatedGappleProgress > 0.01f && gappleModuleRef?.state == true
                    val tabListOn = showTabList && playerList.isNotEmpty()
                    val pearlOn = pearlCountdown && pearlTicksRemaining >= 0f
                    val styleBlinkOn = blinkOn

                    when {
                        isChestOpen && chestSlots.isNotEmpty() -> {
                            renderMode = "NEW_CHEST"
                            val columns = 9
                            val rows = (chestSlots.size + 8) / 9
                            val slotSize = 18f
                            val padding = 10f
                            targetWidth = columns * slotSize + padding * 2
                            targetHeight = rows * slotSize + padding * 2
                            targetX = (width - targetWidth) / 2
                            targetY = start_y.coerceIn(5f, height - targetHeight - 5f)
                        }
                        tabListOn -> {
                            renderMode = "NEW_TABLIST"
                            val (tw, th2) = calcTabListSize(playerList, headerLines, footerLines)
                            targetWidth = tw
                            targetHeight = th2
                            targetX = (width - targetWidth) / 2
                            targetY = start_y.coerceIn(5f, height - targetHeight - 5f)
                        }
                        scaffoldOn -> {
                            renderMode = "NEW_SCAFFOLD"
                            val (tw, th2) = calcNewScaffoldSize()
                            targetWidth = tw
                            targetHeight = th2
                            targetX = (width - targetWidth) / 2
                        }
                        pearlOn -> {
                            renderMode = "NEW_PEARL"
                            val (tw, th2) = calcNewPearlSize()
                            targetWidth = tw
                            targetHeight = th2
                            targetX = (width - targetWidth) / 2
                        }
                        gappleOn -> {
                            renderMode = "NEW_GAPPLE"
                            targetWidth = 210f
                            targetHeight = 34f
                            targetX = (width - targetWidth) / 2
                        }
                        styleBlinkOn -> {
                            renderMode = "NEW_BLINK"
                            targetWidth = if (blinkPulse) 210f else calcNewBlinkWidth()
                            targetHeight = 34f
                            targetX = (width - targetWidth) / 2
                        }
                        musicOn -> {
                            renderMode = "NEW_MUSIC"
                            val musicName = MusicHub.currentMusicName
                            val coverSize = 38f
                            val pad = 10f
                            val textAreaW = (Fonts.fontSemibold35.getStringWidth(musicName).toFloat() + 40f).coerceAtMost(200f)
                            targetWidth = (pad + coverSize + 10f + textAreaW + pad).coerceIn(210f, 360f)
                            targetHeight = 56f
                            targetX = (width - targetWidth) / 2
                        }
                        toggleActive -> {
                            renderMode = "NEW_NOTIFY"
                            val (tw, th2) = calcNewNotifySize()
                            targetWidth = tw
                            targetHeight = th2
                            targetX = (width - targetWidth) / 2
                        }
                        else -> {
                            renderMode = "NEW_WATERMARK"
                            targetWidth = calcNewWatermarkWidth()
                            targetHeight = 30f
                            targetX = (width - targetWidth) / 2
                        }
                    }
                }
                "ios" -> {
                    val scaffoldOn = isScaffold && (scaffoldModuleRef?.state == true || scaffold2ModuleRef?.state == true)
                    val musicOn = MusicHub.isCurrentlyPlaying && (MusicHub.currentLyricDisplay.isNotEmpty() || MusicHub.currentMusicName != "None")
                    val toggleActive = ModuleNotify && notifications.isNotEmpty()
                    val gappleOn = showGappleProgress && animatedGappleProgress > 0.01f && gappleModuleRef?.state == true
                    val tabListOn = showTabList && playerList.isNotEmpty()
                    val pearlOn = pearlCountdown && pearlTicksRemaining >= 0f
                    val styleBlinkOn = blinkOn

                    when {
                        isChestOpen && chestSlots.isNotEmpty() -> {
                            renderMode = "IOS_CHEST"
                            val columns = 9
                            val rows = (chestSlots.size + 8) / 9
                            val slotSize = 18f
                            val padding = 10f
                            targetWidth = columns * slotSize + padding * 2
                            targetHeight = rows * slotSize + padding * 2
                            targetX = (width - targetWidth) / 2
                            targetY = start_y.coerceIn(5f, height - targetHeight - 5f)
                        }
                        tabListOn -> {
                            renderMode = "IOS_TABLIST"
                            val (tw, th) = calcTabListSize(playerList, headerLines, footerLines)
                            targetWidth = tw
                            targetHeight = th
                            targetX = (width - targetWidth) / 2
                            targetY = start_y.coerceIn(5f, height - targetHeight - 5f)
                        }
                        pearlOn -> {
                            renderMode = "IOS_PEARL"
                            targetWidth = 170f
                            targetHeight = 34f
                            targetX = (width - targetWidth) / 2
                        }
                        gappleOn -> {
                            renderMode = "IOS_GAPPLE"
                            targetWidth = 210f
                            targetHeight = 34f
                            targetX = (width - targetWidth) / 2
                        }
                        styleBlinkOn -> {
                            renderMode = "IOS_BLINK"
                            targetWidth = if (blinkPulse) 210f else calcIosBlinkWidth()
                            targetHeight = 34f
                            targetX = (width - targetWidth) / 2
                        }
                        scaffoldOn -> {
                            renderMode = "IOS_SCAFFOLD"
                            targetWidth = 210f
                            targetHeight = 34f
                            targetX = (width - targetWidth) / 2
                        }
                        musicOn -> {
                            renderMode = "IOS_MUSIC"
                            val musicName = MusicHub.currentMusicName
                            val lyricText = MusicHub.currentLyricDisplay.take(20)
                            // iOS Dynamic Island music: compact pill with album art + text
                            val coverSize = 36f
                            val pad = 8f
                            val textAreaW = Fonts.fontSemibold35.getStringWidth(musicName).toFloat().coerceAtMost(160f) + pad * 2
                            targetWidth = (pad + coverSize + 6f + textAreaW + pad).coerceIn(200f, 360f)
                            targetHeight = 52f
                            targetX = (width - targetWidth) / 2
                        }
                        toggleActive -> {
                            renderMode = "IOS_TOGGLE"
                            val borderInfo = calcMaxNotificationWidth()
                            targetWidth = borderInfo.coerceAtLeast(180F)
                            targetHeight = 34f
                            targetX = (width - targetWidth) / 2
                        }
                        else -> {
                            renderMode = "IOS_WATERMARK"
                            targetWidth = calcIosWatermarkWidth()
                            targetHeight = 30f
                            targetX = (width - targetWidth) / 2
                        }
                    }
                }
                "opai" -> {
                    val scaffoldOn = isScaffold && (scaffoldModuleRef?.state == true || scaffold2ModuleRef?.state == true)
                    val musicOn = MusicHub.isCurrentlyPlaying && (MusicHub.currentLyricDisplay.isNotEmpty() || MusicHub.currentMusicName != "None")
                    val toggleActive = ModuleNotify && notifications.isNotEmpty()
                    val gappleOn = showGappleProgress && animatedGappleProgress > 0.01f && gappleModuleRef?.state == true
                    val tabListOn = opaiTabList && showTabList && playerList.isNotEmpty()
                    val pearlOn = pearlCountdown && pearlTicksRemaining >= 0f
                    val styleBlinkOn = blinkOn
                    // Opai 可用视口宽度（对照 Samsara available = viewportWidth - 16）
                    val opaiAvailable = (width - 16f).coerceAtLeast(1f)

                    when {
                        opaiChest && isChestOpen && chestSlots.isNotEmpty() -> {
                            renderMode = "OPAI_CHEST"
                            val rows = (chestSlots.size + 8) / 9
                            targetWidth = OPAI_PANEL_W
                            targetHeight = rows * 20f + 8f
                            targetX = (width - targetWidth) / 2
                            targetY = OPAI_IDLE_TOP
                        }
                        tabListOn -> {
                            renderMode = "OPAI_TABLIST"
                            val rows = playerList.size.coerceAtMost(opaiTabListRows)
                            targetWidth = OPAI_PANEL_W
                            targetHeight = rows * OPAI_TABLIST_ROW + 4f
                            targetX = (width - targetWidth) / 2
                            targetY = OPAI_IDLE_TOP
                        }
                        scaffoldOn -> {
                            renderMode = "OPAI_SCAFFOLD"
                            targetWidth = calcOpaiPanelWidth("Scaffold Toggled", opaiScaffoldDetail())
                            targetHeight = OPAI_PANEL_HEIGHT
                            targetX = (width - targetWidth) / 2
                            targetY = OPAI_TOP
                        }
                        pearlOn -> {
                            renderMode = "OPAI_PEARL"
                            targetWidth = calcOpaiPanelWidth("Pearl Landing", opaiPearlDetail())
                            targetHeight = OPAI_PANEL_HEIGHT
                            targetX = (width - targetWidth) / 2
                            targetY = OPAI_TOP
                        }
                        gappleOn -> {
                            renderMode = "OPAI_GAPPLE"
                            targetWidth = calcOpaiPanelWidth("Eating Gapple", opaiGappleDetail())
                            targetHeight = OPAI_PANEL_HEIGHT
                            targetX = (width - targetWidth) / 2
                            targetY = OPAI_TOP
                        }
                        styleBlinkOn -> {
                            renderMode = "OPAI_BLINK"
                            targetWidth = calcOpaiPanelWidth("Blink", blinkTimeString())
                            targetHeight = OPAI_PANEL_HEIGHT
                            targetX = (width - targetWidth) / 2
                            targetY = OPAI_TOP
                        }
                        musicOn -> {
                            renderMode = "OPAI_MUSIC"
                            targetWidth = calcOpaiPanelWidth(opaiMusicTitle(), opaiMusicDetail())
                            targetHeight = OPAI_PANEL_HEIGHT
                            targetX = (width - targetWidth) / 2
                            targetY = OPAI_TOP
                        }
                        toggleActive -> {
                            renderMode = "OPAI_NOTIFY"
                            targetWidth = (OPAI_TEXT_X + opaiNotifyContentWidth() + OPAI_NOTICE_RIGHT)
                                .coerceAtLeast(60f).coerceAtMost(opaiAvailable)
                            targetHeight = OPAI_ROW_HEIGHT * notifications.size.coerceAtMost(OPAI_MAX_NOTICES)
                            targetX = (width - targetWidth) / 2
                            targetY = OPAI_IDLE_TOP
                        }
                        else -> {
                            renderMode = "OPAI_WATERMARK"
                            targetWidth = calcOpaiIdleWidth().coerceIn(1f, opaiAvailable)
                            targetHeight = OPAI_IDLE_HEIGHT
                            targetX = (width - targetWidth) / 2
                            targetY = OPAI_IDLE_TOP
                        }
                    }
                }
                "legacy" -> {
                    renderMode = "NORMAL_OPAI"
                    val info = calcNormal3Info()
                    targetWidth = info.width
                    targetHeight = NORMAL_WATERMARK_HEIGHT
                    targetX = (width - targetWidth) / 2
                }
            }
        }

        if (renderMode != "NONE") {
            val currentAnim = getCurrentExpandAnim()
            val isOpaiMode = renderMode.startsWith("OPAI_")

            if (isOpaiMode) {
                // Samsara DynamicIsland 解析阻尼弹簧（宽 / 高 / 顶三个独立 Morph，任意帧率一致、打断保留速度）
                // x 不单独弹簧：由动画宽度推导保持水平居中（对照 Samsara paintShell）
                val nowMs = System.currentTimeMillis()
                val opaiIdle = renderMode == "OPAI_WATERMARK"
                val opaiPanel = renderMode == "OPAI_TABLIST" || renderMode == "OPAI_CHEST"
                // 冲量：进入待机 5.5，离开待机 / 面板切换 7.25（Samsara 常量）
                val opaiImpulse = if (opaiIdle != opaiWasCompact || opaiPanel != opaiWasPanel) {
                    if (opaiIdle) 5.5 else 7.25
                } else 0.0
                opaiWasCompact = opaiIdle
                opaiWasPanel = opaiPanel
                AnimGlobalWidth = opaiWidthMorph.value(targetWidth, nowMs, opaiImpulse, opaiSpeed)
                    .coerceIn(1f, (width - 16f).coerceAtLeast(1f))
                AnimGlobalHeight = opaiHeightMorph.value(targetHeight, nowMs, opaiImpulse, opaiSpeed).coerceAtLeast(1f)
                AnimGlobalY = opaiTopMorph.value(targetY, nowMs, opaiImpulse, opaiSpeed)
                AnimGlobalX = (width - AnimGlobalWidth) / 2f
            } else if (currentAnim == "Bounce") {
                // Original spring-based bounce animation
                val (nextW, vW) = spring(AnimGlobalWidth, targetWidth, VelGlobalWidth)
                AnimGlobalWidth = nextW.coerceAtLeast(0F)
                VelGlobalWidth = vW

                val (nextH, vH) = spring(AnimGlobalHeight, targetHeight, VelGlobalHeight)
                AnimGlobalHeight = nextH.coerceAtLeast(0F)
                VelGlobalHeight = vH

                val (nextX, vX) = spring(AnimGlobalX, targetX, VelGlobalX)
                AnimGlobalX = nextX
                VelGlobalX = vX

                val (nextY, vY) = spring(AnimGlobalY, targetY, VelGlobalY)
                AnimGlobalY = nextY
                VelGlobalY = vY
            } else {
                // Non-bounce animations (EaseOut, Linear, Elastic, Back)
                val targetChanged = targetX != animTargetX || targetY != animTargetY ||
                                    targetWidth != animTargetW || targetHeight != animTargetH ||
                                    currentAnim != lastExpandAnim
                if (targetChanged) {
                    animStartX = AnimGlobalX
                    animStartY = AnimGlobalY
                    animStartW = AnimGlobalWidth
                    animStartH = AnimGlobalHeight
                    animTargetX = targetX
                    animTargetY = targetY
                    animTargetW = targetWidth
                    animTargetH = targetHeight
                    animStartTime = System.currentTimeMillis()
                    lastExpandAnim = currentAnim
                }

                val elapsed = (System.currentTimeMillis() - animStartTime).toFloat()
                AnimGlobalWidth = applyExpandAnim(AnimGlobalWidth, animStartW, animTargetW, elapsed).coerceAtLeast(0F)
                AnimGlobalHeight = applyExpandAnim(AnimGlobalHeight, animStartH, animTargetH, elapsed).coerceAtLeast(0F)
                AnimGlobalX = applyExpandAnim(AnimGlobalX, animStartX, animTargetX, elapsed)
                AnimGlobalY = applyExpandAnim(AnimGlobalY, animStartY, animTargetY, elapsed)
            }

            val isIosMode = renderMode.startsWith("IOS_")
            val isNewMode = renderMode.startsWith("NEW_")
            val currentRadius = when {
                // Opai：待机收缩为胶囊（半径跟随动画高度），展开面板用固定圆角（Samsara RADIUS 逻辑）
                isOpaiMode -> if (renderMode == "OPAI_WATERMARK") AnimGlobalHeight / 2f else opaiRadius.coerceAtMost(AnimGlobalHeight / 2f)
                isIosMode -> if (renderMode == "IOS_CHEST") iosRadius else if (AnimGlobalHeight > 100f) iosRadius.coerceAtMost(AnimGlobalHeight / 2f) else iosRadius.coerceAtMost(AnimGlobalHeight / 2f)
                isNewMode -> newRadius.coerceAtMost(AnimGlobalHeight / 2f)
                AnimGlobalHeight > 30F -> if (renderMode == "CHEST" || renderMode == "TABLIST") ChestRounded else legacyRadius.coerceAtMost(AnimGlobalHeight / 2f)
                else -> legacyRadius.coerceAtMost(AnimGlobalHeight / 2f)
            }
            // 各样式的阴影开关 / 强度 / 颜色相互独立（iOS / 新样式 / legacy）；
            // opai 对齐 Samsara：黑色指数衰减环形阴影常开、无设置项
            val useShadow = when {
                isOpaiMode -> true
                isIosMode -> iosShadowCheck
                isNewMode -> newShadowCheck
                else -> ShadowCheck
            }
            val shadowMaxDist = (when {
                isIosMode -> iosShadowRadius
                isNewMode -> newShadowRadius
                else -> shadowRadiusValue
            }).toInt()
            val shadowCol = when {
                isIosMode -> iosShadowColor
                isNewMode -> newShadowColor
                else -> shadowColor
            }

            val drawX = AnimGlobalX
            val drawY = AnimGlobalY
            val drawW = AnimGlobalWidth
            val drawH = AnimGlobalHeight
            
            val isBelowMode = style == "legacy" && showLyricOnIsland && lyricDisplayMode == "Below"
            val islandCorners = if (isBelowMode) RenderUtils.RoundedCorners.TOP_ONLY else RenderUtils.RoundedCorners.ALL

            try {
                EmbeddedStencil.checkSetupFBO(mc.framebuffer)
                EmbeddedStencil.write(false)
                RenderUtils.drawRoundedRect(drawX, drawY, drawX + drawW, drawY + drawH, Color.WHITE.rgb, currentRadius, islandCorners)

                EmbeddedStencil.erase(false)
                if (useShadow) {
                    if (isOpaiMode) {
                        drawOpaiIslandShadow(drawX, drawY, drawW, drawH, currentRadius)
                    } else {
                        // 统一采用 Prism 思路：沿岛体圆角轮廓逐层向外扩展的半透明阴影
                        for (i in shadowMaxDist downTo 1) {
                            val alpha = (shadowCol.alpha * (1f - i.toFloat() / (shadowMaxDist + 1))).toInt().coerceIn(1, shadowCol.alpha)
                            RenderUtils.drawRoundedRect(
                                drawX - i, drawY - i,
                                drawX + drawW + i, drawY + drawH + i,
                                Color(shadowCol.red, shadowCol.green, shadowCol.blue, alpha).rgb,
                                currentRadius + i
                            )
                        }
                    }
                }

                EmbeddedStencil.erase(true)
                if (blurCheck) {
                    applyBlur(drawX, drawY, drawW, drawH)
                }

                // 样式背景透明度直接取颜色自带的 alpha 通道（Opai-Background / iOS-Background 等颜色设置）
                val bgColor = when {
                    isOpaiMode -> opaiBackground
                    isIosMode -> iosBackgroundColor
                    isNewMode -> newBackgroundColor(renderMode)
                    else -> legacyBackground
                }
                RenderUtils.drawRoundedRect(
                    drawX, drawY,
                    drawX + drawW, drawY + drawH,
                    bgColor.rgb,
                    currentRadius,
                    islandCorners
                )
                glEnable(GL_BLEND)
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

                // Samsara 岛体只有阴影 + 玻璃背景，无描边；白色描边仅 legacy / ios 使用
                if (!isIosMode && !isNewMode && !isOpaiMode) {
                    glDisable(GL_TEXTURE_2D)
                    glEnable(GL_LINE_SMOOTH)
                    glHint(GL_LINE_SMOOTH_HINT, GL_NICEST)
                    glLineWidth(1.5f)
                    RenderUtils.drawRoundedBorderRect(
                        drawX - 0.5f, drawY - 0.5f,
                        drawX + drawW + 0.5f, drawY + drawH + 0.5f,
                        0.5f,
                        Color(0, 0, 0, 0).rgb,
                        Color(255, 255, 255, 35).rgb,
                        currentRadius + 0.5f
                    )
                    glLineWidth(1.0f)
                    glDisable(GL_LINE_SMOOTH)
                    glEnable(GL_TEXTURE_2D)
                }

                if (isNewMode) {
                    glDisable(GL_TEXTURE_2D)
                    glEnable(GL_LINE_SMOOTH)
                    glHint(GL_LINE_SMOOTH_HINT, GL_NICEST)
                    glLineWidth(1f)
                    RenderUtils.drawRoundedBorderRect(
                        drawX - 0.5f, drawY - 0.5f,
                        drawX + drawW + 0.5f, drawY + drawH + 0.5f,
                        1f,
                        Color(0, 0, 0, 0).rgb,
                        newTheme().border.rgb,
                        currentRadius + 0.5f
                    )
                    glLineWidth(1.0f)
                    glDisable(GL_LINE_SMOOTH)
                    glEnable(GL_TEXTURE_2D)
                }

            } catch (e: Exception) {
                // 异常路径先清除残留的 stencil 状态，避免回退绘制与后续渲染被 GL_EQUAL 遮罩
                EmbeddedStencil.dispose()
                if (useShadow) {
                    if (isOpaiMode) {
                        drawOpaiIslandShadow(drawX, drawY, drawW, drawH, currentRadius)
                    } else {
                        // 统一采用 Prism 思路：沿岛体圆角轮廓逐层向外扩展的半透明阴影
                        for (i in shadowMaxDist downTo 1) {
                            val alpha = (shadowCol.alpha * (1f - i.toFloat() / (shadowMaxDist + 1))).toInt().coerceIn(1, shadowCol.alpha)
                            RenderUtils.drawRoundedRect(
                                drawX - i, drawY - i,
                                drawX + drawW + i, drawY + drawH + i,
                                Color(shadowCol.red, shadowCol.green, shadowCol.blue, alpha).rgb,
                                currentRadius + i
                            )
                        }
                    }
                }
                RenderUtils.drawRoundedRect(drawX, drawY, drawX + drawW, drawY + drawH, (when {
                    isOpaiMode -> opaiBackground
                    isIosMode -> iosBackgroundColor
                    isNewMode -> newBackgroundColor(renderMode)
                    else -> legacyBackground
                }).rgb, currentRadius, islandCorners) // 异常回退保持原色
                glEnable(GL_BLEND)
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            } finally {
                // 无论成功还是异常都必须还原 stencil 状态（dispose 幂等，重复调用安全）
                EmbeddedStencil.dispose()
            }

            // 内容裁剪：用动画中的圆角岛形作为模板，把内容渲染限制在岛内，
            // 避免背景未抵达目标尺寸时 tablist 等元素溢出到岛外
            try {
                EmbeddedStencil.checkSetupFBO(mc.framebuffer)
                EmbeddedStencil.write(false)
                RenderUtils.drawRoundedRect(drawX, drawY, drawX + drawW, drawY + drawH, Color.WHITE.rgb, currentRadius, islandCorners)
                EmbeddedStencil.erase(true)
                renderIslandContent(renderMode, drawX, drawY, drawW, drawH, playerList, headerLines, footerLines, chestSlots)
            } catch (e: Exception) {
                // 裁剪失败时按原样（无裁剪）兜底渲染，避免整块内容消失
                EmbeddedStencil.dispose()
                renderIslandContent(renderMode, drawX, drawY, drawW, drawH, playerList, headerLines, footerLines, chestSlots)
            } finally {
                EmbeddedStencil.dispose()
            }
        }

        if (style == "legacy" && showLyricOnIsland && lyricDisplayMode != "None") {
            renderLyricDisplay()
        }

        glHint(GL_LINE_SMOOTH_HINT, GL_DONT_CARE)
        glDisable(GL_LINE_SMOOTH)
        glEnable(GL_TEXTURE_2D)
        glDisable(GL_BLEND)
        glPopMatrix()
    }

    /** 按渲染模式分发岛内容（外层由圆角模板裁剪） */
    private fun renderIslandContent(
        renderMode: String,
        drawX: Float, drawY: Float, drawW: Float, drawH: Float,
        playerList: List<NetworkPlayerInfo>,
        headerLines: List<String>,
        footerLines: List<String>,
        chestSlots: List<Slot>
    ) {
        when (renderMode) {
            "LYRIC_FULL" -> renderLyricFullContent(drawX, drawY, drawW, drawH)
            "SCAFFOLD" -> renderScaffoldContent(drawX, drawY, drawW, drawH)
            "NOTIFY_STACK" -> renderNotificationStack(drawX, drawY, drawW, drawH)
            "NORMAL_OPAI" -> renderNormal3Content(drawX, drawY, drawW, drawH)
            "CHEST" -> renderChestContent(drawX, drawY, drawW, drawH, chestSlots)
            "TABLIST" -> renderTabListContent(drawX, drawY, drawW, drawH, playerList, headerLines, footerLines)
            "BREAK_PROGRESS" -> renderBreakProgressContent(drawX, drawY, drawW, drawH)
            "GAPPLE_PROGRESS" -> renderGappleProgressContent(drawX, drawY, drawW, drawH)
            "PEARL" -> renderPearlContent(drawX, drawY, drawW, drawH)
            "BLINK" -> renderBlinkContent(drawX, drawY, drawW, drawH)
            "IOS_PEARL" -> renderIosPearl(drawX, drawY, drawW, drawH)
            "IOS_WATERMARK" -> renderIosWatermark(drawX, drawY, drawW, drawH)
            "IOS_TOGGLE" -> renderIosToggle(drawX, drawY, drawW, drawH)
            "IOS_SCAFFOLD" -> renderIosScaffold(drawX, drawY, drawW, drawH)
            "IOS_MUSIC" -> renderIosMusic(drawX, drawY, drawW, drawH)
            "IOS_CHEST" -> renderIosChest(drawX, drawY, drawW, drawH, chestSlots)
            "IOS_GAPPLE" -> renderIosGapple(drawX, drawY, drawW, drawH)
            "IOS_BLINK" -> renderIosBlink(drawX, drawY, drawW, drawH)
            "IOS_TABLIST" -> renderIosTabList(drawX, drawY, drawW, drawH, playerList, headerLines, footerLines)
            "NEW_WATERMARK" -> renderNewWatermark(drawX, drawY, drawW, drawH)
            "NEW_NOTIFY" -> renderNewNotify(drawX, drawY, drawW, drawH)
            "NEW_TABLIST" -> renderNewTabList(drawX, drawY, drawW, drawH, playerList, headerLines, footerLines)
            "NEW_MUSIC" -> renderNewMusic(drawX, drawY, drawW, drawH)
            "NEW_SCAFFOLD" -> renderNewScaffold(drawX, drawY, drawW, drawH)
            "NEW_PEARL" -> renderNewPearl(drawX, drawY, drawW, drawH)
            "NEW_GAPPLE" -> renderNewGapple(drawX, drawY, drawW, drawH)
            "NEW_BLINK" -> renderNewBlink(drawX, drawY, drawW, drawH)
            "NEW_CHEST" -> renderIosChest(drawX, drawY, drawW, drawH, chestSlots)
            "OPAI_WATERMARK" -> renderOpaiIdle(drawX, drawY, drawW, drawH)
            "OPAI_NOTIFY" -> renderOpaiNotices(drawX, drawY, drawW, drawH)
            "OPAI_MUSIC" -> renderOpaiMusicPanel(drawX, drawY, drawW, drawH)
            "OPAI_SCAFFOLD" -> renderOpaiScaffoldPanel(drawX, drawY, drawW, drawH)
            "OPAI_PEARL" -> renderOpaiPearlPanel(drawX, drawY, drawW, drawH)
            "OPAI_GAPPLE" -> renderOpaiGapplePanel(drawX, drawY, drawW, drawH)
            "OPAI_BLINK" -> renderOpaiBlinkPanel(drawX, drawY, drawW, drawH)
            "OPAI_TABLIST" -> renderOpaiTabList(drawX, drawY, drawW, drawH, playerList)
            "OPAI_CHEST" -> renderOpaiChest(drawX, drawY, drawW, drawH, chestSlots)
        }
    }

    private fun renderGappleProgressContent(x: Float, y: Float, w: Float, h: Float) {
        val percentage = animatedGappleProgress.coerceIn(0f, 1f)
        val padding = 8F
        val cornerRadius = 6F
        val iconSize = 32F
        val iconBgX = x + padding
        val iconBgY = y + padding
        val themeColor = Color(gappleProgressTheme.red, gappleProgressTheme.green, gappleProgressTheme.blue, 200)
        drawRoundedRect(iconBgX, iconBgY, iconBgX + iconSize, iconBgY + iconSize, themeColor.rgb, cornerRadius - 1)
        
        try {
            val appleImgSize = 24
            drawImage(getGappleResource(), 
                    (iconBgX + (iconSize - appleImgSize) / 2).toInt(), 
                    (iconBgY + (iconSize - appleImgSize) / 2 + 1).toInt(), 
                    appleImgSize, appleImgSize, Color.WHITE)
        } catch (e: Exception) {
            Fonts.fontSemibold40.drawCenteredString("EAT", iconBgX + iconSize / 2, iconBgY + iconSize / 2 - 8, Color.WHITE.rgb)
        }

        val textX = iconBgX + iconSize + 8F
        val titleY = y + padding + 2F
        Fonts.fontSemibold40.drawString("Eating Gapple", textX, titleY, Color.WHITE.rgb)
        val percentText = String.format("%.1f", percentage * 100) + "%"
        Fonts.fontRegular40.drawString(percentText, textX, titleY + Fonts.fontSemibold40.FONT_HEIGHT + 2F, Color(200, 200, 200).rgb)

        val barHeight = 8F
        val barY = y + h - barHeight - padding
        val maxBarWidth = w - (padding * 2)
        val currentBarWidth = maxBarWidth * percentage
        drawRoundedRect(x + padding, barY, x + padding + maxBarWidth, barY + barHeight, Color(60, 60, 70, 180).rgb, 3F)
        val lighter = Color(gappleProgressTheme.red, gappleProgressTheme.green, gappleProgressTheme.blue, 255)
        drawRoundedRect(x + padding, barY, x + padding + currentBarWidth, barY + barHeight, lighter.rgb, 3F)
    }

    private fun renderPearlContent(x: Float, y: Float, w: Float, h: Float) {
        val total = pearlLandingTicks.coerceAtLeast(1)
        val remaining = pearlTicksRemaining.coerceIn(0f, total.toFloat())
        val padding = 8F
        val iconSize = 32F
        val iconBgX = x + padding
        val iconBgY = y + padding
        val theme = ClientThemesUtils.getColor()
        drawRoundedRect(iconBgX, iconBgY, iconBgX + iconSize, iconBgY + iconSize, Color(theme.red, theme.green, theme.blue, 200).rgb, 5F)

        glPushMatrix()
        enableGUIStandardItemLighting()
        try {
            mc.renderItem.renderItemAndEffectIntoGUI(ItemStack(Items.ender_pearl), (iconBgX + 8f).toInt(), (iconBgY + 8f).toInt())
        } catch (_: Exception) {
        }
        disableStandardItemLighting()
        GlStateManager.enableAlpha()
        GlStateManager.disableBlend()
        GlStateManager.disableLighting()
        glPopMatrix()

        val textX = iconBgX + iconSize + 8F
        val titleY = y + padding + 2F
        Fonts.fontSemibold40.drawString("Pearl Landing", textX, titleY, Color.WHITE.rgb)
        val remainText = String.format("%.1fs", remaining * 50f / 1000f)
        Fonts.fontRegular40.drawString(remainText, textX, titleY + Fonts.fontSemibold40.FONT_HEIGHT + 2F, Color(200, 200, 200).rgb)

        val barHeight = 8F
        val barY = y + h - barHeight - padding
        val maxBarWidth = w - (padding * 2)
        val progress = (1f - remaining / total).coerceIn(0f, 1f)
        drawRoundedRect(x + padding, barY, x + padding + maxBarWidth, barY + barHeight, Color(60, 60, 70, 180).rgb, 3F)
        drawRoundedRect(x + padding, barY, x + padding + maxBarWidth * progress, barY + barHeight, Color(theme.red, theme.green, theme.blue, 255).rgb, 3F)
    }

    // ---- Blink 面板（所有样式：Blink 启用时显示当前 blink 时间，Pulse 启用时显示进度条） ----

    private fun blinkTimeString(): String {
        val ms = blinkModuleRef?.blinkTime() ?: 0L
        return String.format("%.1fs", ms / 1000.0)
    }

    private fun renderBlinkContent(x: Float, y: Float, w: Float, h: Float) {
        val padding = 8F
        val iconSize = 32F
        val iconBgX = x + padding
        val iconBgY = y + padding
        val theme = ClientThemesUtils.getColor()
        drawRoundedRect(iconBgX, iconBgY, iconBgX + iconSize, iconBgY + iconSize, Color(theme.red, theme.green, theme.blue, 200).rgb, 5F)
        Fonts.fontSemibold40.drawCenteredString("B", iconBgX + iconSize / 2,
            iconBgY + iconSize / 2 - Fonts.fontSemibold40.FONT_HEIGHT / 2f, Color.WHITE.rgb)

        val textX = iconBgX + iconSize + 8F
        val titleY = y + padding + 2F
        Fonts.fontSemibold40.drawString("Blink", textX, titleY, Color.WHITE.rgb)
        Fonts.fontRegular40.drawString(blinkTimeString(), textX, titleY + Fonts.fontSemibold40.FONT_HEIGHT + 2F, Color(200, 200, 200).rgb)

        if (blinkModuleRef?.pulseEnabled() == true) {
            val progress = (blinkModuleRef?.pulseProgress() ?: 0f).coerceIn(0f, 1f)
            val barHeight = 8F
            val barY = y + h - barHeight - padding
            val maxBarWidth = w - padding * 2
            drawRoundedRect(x + padding, barY, x + padding + maxBarWidth, barY + barHeight, Color(60, 60, 70, 180).rgb, 3F)
            drawRoundedRect(x + padding, barY, x + padding + maxBarWidth * progress, barY + barHeight, Color(theme.red, theme.green, theme.blue, 255).rgb, 3F)
        }
    }

    private fun renderIosBlink(x: Float, y: Float, w: Float, h: Float) {
        val padding = 12F
        val centerY = y + h / 2f
        val timeStr = blinkTimeString()
        val textBaseY = centerY - Fonts.fontSemibold35.FONT_HEIGHT / 2f + 1f

        if (blinkModuleRef?.pulseEnabled() == true) {
            var cx = x + padding
            Fonts.fontSemibold35.drawString(timeStr, cx, textBaseY, Color.WHITE.rgb)
            cx += Fonts.fontSemibold35.getStringWidth(timeStr).toFloat() + 10F
            // Apple 风格连续胶囊进度条（Pulse 周期进度）
            val barW = (x + w - padding) - cx
            val barH = 6F
            val theme = ClientThemesUtils.getColor()
            drawAppleBar(cx, centerY - barH / 2f, barW, barH, blinkModuleRef?.pulseProgress() ?: 0f,
                Color(theme.red, theme.green, theme.blue, 230))
        } else {
            Fonts.fontSemibold35.drawString("Blink $timeStr", x + padding, textBaseY, Color.WHITE.rgb)
        }
    }

    private fun calcIosBlinkWidth(): Float =
        12F + Fonts.fontSemibold35.getStringWidth("Blink " + blinkTimeString()).toFloat() + 12F

    private fun renderNewBlink(x: Float, y: Float, w: Float, h: Float) {
        val th = newTheme()
        val pad = 12F
        val centerY = y + h / 2f
        var cx = x + pad

        val labelBaseY = centerY - Fonts.fontRegular30.FONT_HEIGHT / 2f + 1f
        val valBaseY = centerY - Fonts.fontSemibold35.FONT_HEIGHT / 2f + 1f
        Fonts.fontRegular30.drawString("Blink", cx, labelBaseY, th.sub.rgb)
        cx += Fonts.fontRegular30.getStringWidth("Blink").toFloat() + 6F
        val timeStr = blinkTimeString()
        Fonts.fontSemibold35.drawString(timeStr, cx, valBaseY, th.accent.rgb)
        cx += Fonts.fontSemibold35.getStringWidth(timeStr).toFloat() + 12F

        if (blinkModuleRef?.pulseEnabled() == true) {
            // 10 段分段条，随 Pulse 周期逐渐填满
            val barW = (x + w - pad) - cx
            val gap = 2F
            val segCount = 10
            val segW = ((barW - gap * (segCount - 1)) / segCount).coerceAtLeast(3F)
            val barH = 8F
            val barY = centerY - barH / 2f
            val filledSegs = ((blinkModuleRef?.pulseProgress() ?: 0f) * segCount).toInt().coerceIn(0, segCount)
            for (i in 0 until segCount) {
                val sx = cx + i * (segW + gap)
                val col = if (i < filledSegs) th.accent else th.track
                drawRoundedRect(sx, barY, sx + segW, barY + barH, col.rgb, 2F)
            }
        }
    }

    private fun calcNewBlinkWidth(): Float =
        12F + Fonts.fontRegular30.getStringWidth("Blink").toFloat() + 6F +
                Fonts.fontSemibold35.getStringWidth(blinkTimeString()).toFloat() + 12F

    private fun renderOpaiBlinkPanel(x: Float, y: Float, w: Float, h: Float) {
        val pulseOn = blinkModuleRef?.pulseEnabled() == true
        val progress = if (pulseOn) blinkModuleRef?.pulseProgress() ?: 0f else 0f
        renderOpaiProgressPanel(x, y, w, opaiAccent, "Blink", blinkTimeString(), progress, glyph = "B")
    }

    private fun renderIosPearl(x: Float, y: Float, w: Float, h: Float) {
        val total = pearlLandingTicks.coerceAtLeast(1)
        val remaining = pearlTicksRemaining.coerceIn(0f, total.toFloat())
        val padding = 12F
        val centerY = y + h / 2f
        var cx = x + padding

        val secs = String.format("%.1fs", remaining * 50f / 1000f)
        val textBaseY = centerY - Fonts.fontSemibold35.FONT_HEIGHT / 2f + 1f
        Fonts.fontSemibold35.drawString(secs, cx, textBaseY, Color.WHITE.rgb)
        cx += Fonts.fontSemibold35.getStringWidth(secs).toFloat() + 10F

        // Apple 风格连续胶囊进度条（剩余时间比例）
        val barW = (x + w - padding) - cx
        val barH = 6F
        val theme = ClientThemesUtils.getColor()
        val fillColor = Color(theme.red, theme.green, theme.blue, 230)
        drawAppleBar(cx, centerY - barH / 2f, barW, barH, remaining / total, fillColor)
    }

    private fun renderBreakProgressContent(x: Float, y: Float, w: Float, h: Float) {
        val percentage = animatedBreakProgress.coerceIn(0f, 1f)
        val padding = 8F
        val cornerRadius = 6F
        val iconSize = 32F
        val iconBgX = x + padding
        val iconBgY = y + padding
        val themeColor = Color(breakProgressTheme.red, breakProgressTheme.green, breakProgressTheme.blue, 200)
        drawRoundedRect(iconBgX, iconBgY, iconBgX + iconSize, iconBgY + iconSize, themeColor.rgb, cornerRadius - 1)
        val bedImgSize = 24
        drawImage(ResourceLocation("airplus/watermark_images/bed.png"), (iconBgX + (iconSize - bedImgSize) / 2).toInt(), (iconBgY + (iconSize - bedImgSize) / 2 + 1).toInt(), bedImgSize, bedImgSize, Color.WHITE)

        val textX = iconBgX + iconSize + 8F
        val titleY = y + padding + 2F
        Fonts.fontSemibold40.drawString("Break Progress", textX, titleY, Color.WHITE.rgb)
        val percentText = String.format("%.1f", percentage * 100) + "%"
        Fonts.fontRegular40.drawString(percentText, textX, titleY + Fonts.fontSemibold40.FONT_HEIGHT + 2F, Color(200, 200, 200).rgb)

        val barHeight = 8F
        val barY = y + h - barHeight - padding
        val maxBarWidth = w - (padding * 2)
        val currentBarWidth = maxBarWidth * percentage
        drawRoundedRect(x + padding, barY, x + padding + maxBarWidth, barY + barHeight, Color(60, 60, 70, 180).rgb, 3F)
        val lighter = Color(breakProgressTheme.red, breakProgressTheme.green, breakProgressTheme.blue, 255)
        drawRoundedRect(x + padding, barY, x + padding + currentBarWidth, barY + barHeight, lighter.rgb, 3F)
    }

    private fun drawCircle(x: Float, y: Float, radius: Float, color: Color) {
        GlStateManager.pushMatrix()
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO)
        glEnable(GL_LINE_SMOOTH)
        glHint(GL_LINE_SMOOTH_HINT, GL_NICEST)
        glColor4f(color.red / 255f, color.green / 255f, color.blue / 255f, color.alpha / 255f)

        glBegin(GL_POLYGON)
        for (i in 0..360 step 6) {
            val theta = i * Math.PI / 180
            glVertex2d(x + radius * cos(theta), y + radius * sin(theta))
        }
        glEnd()

        glDisable(GL_LINE_SMOOTH)
        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
        GlStateManager.popMatrix()
    }

    private fun renderChestContent(x: Float, y: Float, w: Float, h: Float, slots: List<Slot>) {
        val padding = 8F
        val slotSize = 16

        val rippleDuration = 600L
        val maxRadius = 18F

        enableGUIStandardItemLighting()
        try {
            slots.forEachIndexed { index, slot ->
                val stack = slot.stack
                val col = index % 9
                val row = index / 9
                val itemX = (x + padding + col * slotSize).toInt()
                val itemY = (y + padding + row * slotSize).toInt()

                val prevStack = prevSlotItems[index]

                if (prevSlotItems.containsKey(index)) {
                    val isChanged = when {
                        stack == null && prevStack == null -> false
                        stack == null || prevStack == null -> true
                        else -> !ItemStack.areItemStacksEqual(stack, prevStack) || stack.stackSize != prevStack.stackSize
                    }

                    if (isChanged) {
                        slotRipples.add(SlotRipple((itemX + 8).toFloat(), (itemY + 8).toFloat(), System.currentTimeMillis()))
                    }
                }

                prevSlotItems[index] = stack?.copy()

                if (stack != null) {
                    if (mc.currentScreen is GuiHudDesigner) glDisable(GL_DEPTH_TEST)
                    mc.renderItem.renderItemAndEffectIntoGUI(stack, itemX, itemY)
                    mc.renderItem.renderItemOverlays(mc.fontRendererObj, stack, itemX, itemY)
                    if (mc.currentScreen is GuiHudDesigner) glEnable(GL_DEPTH_TEST)
                }
            }

            disableStandardItemLighting()
            GlStateManager.disableDepth()

            val currentTime = System.currentTimeMillis()
            val iterator = slotRipples.iterator()

            while (iterator.hasNext()) {
                val ripple = iterator.next()
                val timeAlive = currentTime - ripple.startTime

                if (timeAlive > rippleDuration) {
                    slotRipples.remove(ripple)
                } else {
                    val progress = timeAlive.toFloat() / rippleDuration.toFloat()
                    val ease = 1f - (1f - progress).pow(3)
                    val radius = maxRadius * ease
                    val alpha = (180 * (1f - progress)).toInt().coerceIn(0, 255)

                    if (alpha > 0) {
                        drawCircle(ripple.x, ripple.y, radius, Color(255, 255, 255, alpha))
                    }
                }
            }
            GlStateManager.enableDepth()

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            disableStandardItemLighting()
            GlStateManager.enableAlpha()
            GlStateManager.disableBlend()
            GlStateManager.disableLighting()
        }
    }

    private fun renderTabListContent(x: Float, y: Float, w: Float, h: Float, players: List<NetworkPlayerInfo>, header: List<String>, footer: List<String>) {
        val maxRows = tabListMaxRows
        val outerPadding = 8F

        var currentY = y + outerPadding
        val centerX = x + w / 2F

        if (header.isNotEmpty()) {
            for (line in header) {
                Fonts.fontRegular35.drawCenteredString(line, centerX, currentY, Color.WHITE.rgb)
                currentY += Fonts.fontRegular35.FONT_HEIGHT + 2
            }
            currentY += 2
        }

        if (players.isNotEmpty()) {
            var maxNameWidth = 50F
            players.forEach { it ->
                val fullName = mc.ingameGUI.tabList.getPlayerName(it)
                val wName = Fonts.fontRegular35.getStringWidth(fullName).toFloat()
                if (wName > maxNameWidth) maxNameWidth = wName
            }
            val headSize = 10F
            val padding = 6F
            val spacing = 4F
            val itemHeight = headSize + spacing
            val colWidth = padding + headSize + spacing + maxNameWidth + spacing + 25F + padding
            val totalCols = ceil(players.size.toDouble() / maxRows.toDouble()).toInt()

            val playersTotalWidth = totalCols * colWidth
            var startX = centerX - playersTotalWidth / 2F

            val columnY = currentY

            for (i in players.indices) {
                val player = players[i]

                if (i > 0 && i % maxRows == 0) {
                    startX += colWidth
                    currentY = columnY
                }

                val rowX = startX + padding

                mc.textureManager.bindTexture(player.locationSkin)
                glColor4f(1f, 1f, 1f, 1f)
                Gui.drawScaledCustomSizeModalRect(rowX.toInt(), currentY.toInt(), 8f, 8f, 8, 8, headSize.toInt(), headSize.toInt(), 64f, 64f)

                val fullName = mc.ingameGUI.tabList.getPlayerName(player)
                Fonts.fontRegular35.drawString(fullName, rowX + headSize + spacing, currentY + 1.5F, Color.WHITE.rgb)

                val ping = player.responseTime
                val pingColor = when {
                    ping < 0 -> Color(50, 50, 50)
                    ping < 100 -> Color(100, 255, 100)
                    ping < 200 -> Color(255, 200, 50)
                    else -> Color(255, 80, 80)
                }
                val pingText = "${ping}ms"
                val pingW = Fonts.fontRegular35.getStringWidth(pingText).toFloat()
                Fonts.fontRegular35.drawString(pingText, startX + colWidth - padding - pingW, currentY + 1.5F, pingColor.rgb)

                currentY += itemHeight
            }

            val actualRows = if (totalCols == 1) players.size else maxRows
            currentY = columnY + actualRows * itemHeight + 2
        }

        if (footer.isNotEmpty()) {
            for (line in footer) {
                Fonts.fontRegular35.drawCenteredString(line, centerX, currentY, Color.WHITE.rgb)
                currentY += Fonts.fontRegular35.FONT_HEIGHT + 2
            }
        }
    }

    private fun renderScaffoldContent(x: Float, y: Float, w: Float, h: Float) {
        when (scaffoldStyle) {
            "Classic" -> renderScaffoldClassic(x, y, w, h)
            "Bar" -> renderScaffoldBar(x, y, w, h)
            "Minimal" -> renderScaffoldMinimal(x, y, w, h)
            "Compact" -> renderScaffoldCompact(x, y, w, h)
            "Standard" -> renderScaffoldStandard(x, y, w, h)
            "Circle" -> renderScaffoldCircle(x, y, w, h)
            "Split" -> renderScaffoldSplit(x, y, w, h)
            "Modern" -> renderScaffoldModern(x, y, w, h)
            "Outline" -> renderScaffoldOutline(x, y, w, h)
            else -> renderScaffoldClassic(x, y, w, h)
        }
    }

    private fun renderScaffoldClassic(x: Float, y: Float, w: Float, h: Float) {
        val hotbarBlockCount = (0..8).sumOf { slotIndex ->
            val stack = mc.thePlayer.inventory.getStackInSlot(slotIndex)
            if (stack != null && stack.item is ItemBlock) stack.stackSize else 0
        }
        val percentage = scaffoldProgress()
        val targetBPS = displayedBPS
        AnimatedBps += (targetBPS - AnimatedBps) * 0.15 * (Minecraft.getDebugFPS() / 20.0).coerceIn(0.1, 2.0)

        val padding = 8F
        val cornerRadius = 6F
        val iconSize = 32F
        val iconBgX = x + padding
        val iconBgY = y + padding
        val themeColor = Color(ScaffoldTheme.red, ScaffoldTheme.green, ScaffoldTheme.blue, 200)
        drawRoundedRect(iconBgX, iconBgY, iconBgX + iconSize, iconBgY + iconSize, themeColor.rgb, cornerRadius - 1)
        val blockImgSize = 24
        drawImage(ResourceLocation("airplus/watermark_images/block.png"), (iconBgX + (iconSize - blockImgSize) / 2).toInt(), (iconBgY + (iconSize - blockImgSize) / 2 + 1).toInt(), blockImgSize, blockImgSize, Color.WHITE)

        val textX = iconBgX + iconSize + 8F
        val titleY = y + padding + 2F
        Fonts.fontSemibold40.drawString("Scaffold Toggled", textX, titleY, Color.WHITE.rgb)
        val bpsText = String.format("%.2f", if(AnimatedBps < 0.01) 0.0 else AnimatedBps)
        Fonts.fontRegular40.drawString("$hotbarBlockCount blocks - $bpsText block/s", textX, titleY + Fonts.fontSemibold40.FONT_HEIGHT + 2F, Color(200, 200, 200).rgb)

        val barHeight = 8F
        val barY = y + h - barHeight - padding
        val maxBarWidth = w - (padding * 2)
        val targetBarWidth = maxBarWidth * percentage
        val lerpSpeed = 0.15f * (Minecraft.getDebugFPS() / 60f).coerceIn(0.5f, 2f)
        ProgressBarAnimationWidth += (targetBarWidth - ProgressBarAnimationWidth) * lerpSpeed
        ProgressBarAnimationWidth = ProgressBarAnimationWidth.coerceIn(0F, maxBarWidth)
        drawRoundedRect(x + padding, barY, x + padding + maxBarWidth, barY + barHeight, Color(60, 60, 70, 180).rgb, 3F)
        val lighter = Color((ScaffoldTheme.red + 50).coerceAtMost(255), (ScaffoldTheme.green + 50).coerceAtMost(255), (ScaffoldTheme.blue + 50).coerceAtMost(255), 255)
        drawRoundedRect(x + padding, barY, x + padding + ProgressBarAnimationWidth, barY + barHeight, lighter.rgb, 3F)
    }

    private fun renderScaffoldBar(x: Float, y: Float, w: Float, h: Float) {
        val hotbarBlockCount = (0..8).sumOf { slotIndex ->
            val stack = mc.thePlayer.inventory.getStackInSlot(slotIndex)
            if (stack != null && stack.item is ItemBlock) stack.stackSize else 0
        }
        val percentage = scaffoldProgress()
        val targetBPS = displayedBPS
        AnimatedBps += (targetBPS - AnimatedBps) * 0.15 * (Minecraft.getDebugFPS() / 20.0).coerceIn(0.1, 2.0)

        val padding = 6F
        val barHeight = h - padding * 2
        val maxBarWidth = w - padding * 2
        val targetBarWidth = maxBarWidth * percentage
        val lerpSpeed = 0.15f * (Minecraft.getDebugFPS() / 60f).coerceIn(0.5f, 2f)
        ProgressBarAnimationWidth += (targetBarWidth - ProgressBarAnimationWidth) * lerpSpeed
        ProgressBarAnimationWidth = ProgressBarAnimationWidth.coerceIn(0F, maxBarWidth)

        drawRoundedRect(x + padding, y + padding, x + padding + maxBarWidth, y + padding + barHeight, Color(60, 60, 70, 180).rgb, barHeight / 2)
        val lighter = Color((ScaffoldTheme.red + 50).coerceAtMost(255), (ScaffoldTheme.green + 50).coerceAtMost(255), (ScaffoldTheme.blue + 50).coerceAtMost(255), 255)
        if (ProgressBarAnimationWidth > 1F) {
            drawRoundedRect(x + padding, y + padding, x + padding + ProgressBarAnimationWidth, y + padding + barHeight, lighter.rgb, barHeight / 2)
        }

        val bpsText = String.format("%.1f", if(AnimatedBps < 0.01) 0.0 else AnimatedBps)
        val blockText = "$hotbarBlockCount"
        val bpsFullText = "$bpsText b/s"
        val textWidth = Fonts.fontSemibold35.getStringWidth("$blockText  $bpsFullText")
        val centerX = x + w / 2

        if (ProgressBarAnimationWidth > textWidth + 10F) {
            Fonts.fontSemibold35.drawString(blockText, centerX - textWidth / 2, y + (h - Fonts.fontSemibold35.FONT_HEIGHT) / 2, Color.WHITE.rgb)
            Fonts.fontRegular30.drawString(bpsFullText, centerX - textWidth / 2 + Fonts.fontSemibold35.getStringWidth(blockText) + 6F, y + (h - Fonts.fontRegular30.FONT_HEIGHT) / 2 + 1F, Color(220, 220, 220, 200).rgb)
        } else {
            Fonts.fontSemibold35.drawCenteredString(blockText, centerX, y + (h - Fonts.fontSemibold35.FONT_HEIGHT) / 2, Color.WHITE.rgb)
        }
    }

    private fun renderScaffoldMinimal(x: Float, y: Float, w: Float, h: Float) {
        val hotbarBlockCount = (0..8).sumOf { slotIndex ->
            val stack = mc.thePlayer.inventory.getStackInSlot(slotIndex)
            if (stack != null && stack.item is ItemBlock) stack.stackSize else 0
        }
        val percentage = scaffoldProgress()

        val padding = 4F
        val barHeight = h - padding * 2
        val maxBarWidth = w - padding * 2
        val targetBarWidth = maxBarWidth * percentage
        val lerpSpeed = 0.15f * (Minecraft.getDebugFPS() / 60f).coerceIn(0.5f, 2f)
        ProgressBarAnimationWidth += (targetBarWidth - ProgressBarAnimationWidth) * lerpSpeed
        ProgressBarAnimationWidth = ProgressBarAnimationWidth.coerceIn(0F, maxBarWidth)

        drawRoundedRect(x + padding, y + padding, x + padding + maxBarWidth, y + padding + barHeight, Color(60, 60, 70, 180).rgb, barHeight / 2)
        val lighter = Color((ScaffoldTheme.red + 50).coerceAtMost(255), (ScaffoldTheme.green + 50).coerceAtMost(255), (ScaffoldTheme.blue + 50).coerceAtMost(255), 255)
        if (ProgressBarAnimationWidth > 1F) {
            drawRoundedRect(x + padding, y + padding, x + padding + ProgressBarAnimationWidth, y + padding + barHeight, lighter.rgb, barHeight / 2)
        }

        val blockText = "$hotbarBlockCount"
        val centerX = x + w / 2
        Fonts.fontSemibold35.drawCenteredString(blockText, centerX, y + (h - Fonts.fontSemibold35.FONT_HEIGHT) / 2, Color.WHITE.rgb)
    }

    private fun renderScaffoldCompact(x: Float, y: Float, w: Float, h: Float) {
        val hotbarBlockCount = (0..8).sumOf { slotIndex ->
            val stack = mc.thePlayer.inventory.getStackInSlot(slotIndex)
            if (stack != null && stack.item is ItemBlock) stack.stackSize else 0
        }
        val percentage = scaffoldProgress()
        val targetBPS = displayedBPS
        AnimatedBps += (targetBPS - AnimatedBps) * 0.15 * (Minecraft.getDebugFPS() / 20.0).coerceIn(0.1, 2.0)

        val padding = 6F
        val iconSize = 20F
        val iconBgX = x + padding
        val iconBgY = y + 6F
        val themeColor = Color(ScaffoldTheme.red, ScaffoldTheme.green, ScaffoldTheme.blue, 200)
        drawRoundedRect(iconBgX, iconBgY, iconBgX + iconSize, iconBgY + iconSize, themeColor.rgb, 4F)
        val blockImgSize = 14
        drawImage(ResourceLocation("airplus/watermark_images/block.png"), (iconBgX + (iconSize - blockImgSize) / 2).toInt(), (iconBgY + (iconSize - blockImgSize) / 2 + 1).toInt(), blockImgSize, blockImgSize, Color.WHITE)

        val textX = iconBgX + iconSize + 6F
        val bpsText = String.format("%.1f", if(AnimatedBps < 0.01) 0.0 else AnimatedBps)
        Fonts.fontSemibold35.drawString("$hotbarBlockCount blocks", textX, iconBgY + 2F, Color.WHITE.rgb)
        Fonts.fontRegular30.drawString("$bpsText b/s", textX + Fonts.fontSemibold35.getStringWidth("$hotbarBlockCount blocks") + 8F, iconBgY + 3F, Color(200, 200, 200, 200).rgb)

        val barHeight = 4F
        val barY = y + h - barHeight - padding + 4F
        val maxBarWidth = w - padding * 2
        val targetBarWidth = maxBarWidth * percentage
        val lerpSpeed = 0.15f * (Minecraft.getDebugFPS() / 60f).coerceIn(0.5f, 2f)
        ProgressBarAnimationWidth += (targetBarWidth - ProgressBarAnimationWidth) * lerpSpeed
        ProgressBarAnimationWidth = ProgressBarAnimationWidth.coerceIn(0F, maxBarWidth)
        drawRoundedRect(x + padding, barY, x + padding + maxBarWidth, barY + barHeight, Color(60, 60, 70, 180).rgb, 2F)
        val lighter = Color((ScaffoldTheme.red + 50).coerceAtMost(255), (ScaffoldTheme.green + 50).coerceAtMost(255), (ScaffoldTheme.blue + 50).coerceAtMost(255), 255)
        drawRoundedRect(x + padding, barY, x + padding + ProgressBarAnimationWidth, barY + barHeight, lighter.rgb, 2F)
    }

    private fun renderScaffoldStandard(x: Float, y: Float, w: Float, h: Float) {
        val hotbarBlockCount = (0..8).sumOf { slotIndex ->
            val stack = mc.thePlayer.inventory.getStackInSlot(slotIndex)
            if (stack != null && stack.item is ItemBlock) stack.stackSize else 0
        }
        val percentage = scaffoldProgress()

        val padding = 6F
        val barHeight = h - padding * 2
        val maxBarWidth = w - padding * 2
        val targetBarWidth = maxBarWidth * percentage
        val lerpSpeed = 0.15f * (Minecraft.getDebugFPS() / 60f).coerceIn(0.5f, 2f)
        ProgressBarAnimationWidth += (targetBarWidth - ProgressBarAnimationWidth) * lerpSpeed
        ProgressBarAnimationWidth = ProgressBarAnimationWidth.coerceIn(0F, maxBarWidth)

        drawRoundedRect(x + padding, y + padding, x + padding + maxBarWidth, y + padding + barHeight, Color(60, 60, 70, 180).rgb, 0F)
        val themeColor = Color(ScaffoldTheme.red, ScaffoldTheme.green, ScaffoldTheme.blue, 255)
        if (ProgressBarAnimationWidth > 1F) {
            drawRoundedRect(x + padding, y + padding, x + padding + ProgressBarAnimationWidth, y + padding + barHeight, themeColor.rgb, 0F)
        }

        if (standardShowBlockCount) {
            val blockText = "$hotbarBlockCount blocks"
            val blockWidth = Fonts.fontSemibold35.getStringWidth(blockText) + 12F
            val textY = y + (h - Fonts.fontSemibold35.FONT_HEIGHT) / 2

            if (ProgressBarAnimationWidth > blockWidth + 10F) {
                Fonts.fontSemibold35.drawString(blockText, x + padding + 8F, textY, Color.WHITE.rgb)
            } else {
                Fonts.fontSemibold35.drawCenteredString(blockText, x + w / 2, textY, Color.WHITE.rgb)
            }
        }
    }

    private fun renderScaffoldCircle(x: Float, y: Float, w: Float, h: Float) {
        val hotbarBlockCount = (0..8).sumOf { slotIndex ->
            val stack = mc.thePlayer.inventory.getStackInSlot(slotIndex)
            if (stack != null && stack.item is ItemBlock) stack.stackSize else 0
        }
        val percentage = scaffoldProgress()

        val centerX = x + w / 2
        val centerY = y + h / 2
        val radius = min(w, h) / 2 - 12F
        val ringWidth = 5F

        GlStateManager.pushMatrix()
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO)
        glEnable(GL_LINE_SMOOTH)
        glHint(GL_LINE_SMOOTH_HINT, GL_NICEST)

        glColor4f(0.24f, 0.24f, 0.27f, 0.8f)
        glLineWidth(ringWidth)
        glBegin(GL_LINE_LOOP)
        for (i in 0..360) {
            val theta = i * Math.PI / 180
            glVertex2d(centerX + radius * cos(theta), centerY + radius * sin(theta))
        }
        glEnd()

        val themeColor = Color(ScaffoldTheme.red, ScaffoldTheme.green, ScaffoldTheme.blue, 255)
        glColor4f(themeColor.red / 255f, themeColor.green / 255f, themeColor.blue / 255f, 1f)
        glBegin(GL_LINE_STRIP)
        val segments = 360
        val drawSegments = (segments * percentage).toInt()
        for (i in 0..drawSegments) {
            val theta = (i - 90) * Math.PI / 180
            glVertex2d(centerX + radius * cos(theta), centerY + radius * sin(theta))
        }
        glEnd()

        glDisable(GL_LINE_SMOOTH)
        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
        GlStateManager.popMatrix()

        val blockText = "$hotbarBlockCount"
        Fonts.fontSemibold40.drawCenteredString(blockText, centerX, centerY - Fonts.fontSemibold40.FONT_HEIGHT / 2, Color.WHITE.rgb)
    }

    private fun renderScaffoldSplit(x: Float, y: Float, w: Float, h: Float) {
        val hotbarBlockCount = (0..8).sumOf { slotIndex ->
            val stack = mc.thePlayer.inventory.getStackInSlot(slotIndex)
            if (stack != null && stack.item is ItemBlock) stack.stackSize else 0
        }
        val percentage = scaffoldProgress()
        val targetBPS = displayedBPS
        AnimatedBps += (targetBPS - AnimatedBps) * 0.15 * (Minecraft.getDebugFPS() / 20.0).coerceIn(0.1, 2.0)

        val padding = 6F
        val barHeight = 4F
        val barY = y + h - barHeight - padding
        val contentHeight = barY - y - padding - 4F

        val dividerX = x + w / 2
        drawRoundedRect(dividerX - 1F, y + padding, dividerX + 1F, barY - 4F, Color(80, 80, 90, 150).rgb, 1F)

        val leftCenterX = x + w / 4
        val rightCenterX = x + w * 3 / 4

        Fonts.fontSemibold40.drawCenteredString("$hotbarBlockCount", leftCenterX, y + contentHeight / 2 - 2F, Color.WHITE.rgb)
        Fonts.fontRegular30.drawCenteredString("blocks", leftCenterX, y + contentHeight / 2 + Fonts.fontSemibold40.FONT_HEIGHT, Color(180, 180, 180).rgb)

        val bpsText = String.format("%.1f", if(AnimatedBps < 0.01) 0.0 else AnimatedBps)
        Fonts.fontSemibold40.drawCenteredString(bpsText, rightCenterX, y + contentHeight / 2 - 2F, Color.WHITE.rgb)
        Fonts.fontRegular30.drawCenteredString("b/s", rightCenterX, y + contentHeight / 2 + Fonts.fontSemibold40.FONT_HEIGHT, Color(180, 180, 180).rgb)

        val maxBarWidth = w - padding * 2
        val targetBarWidth = maxBarWidth * percentage
        val lerpSpeed = 0.15f * (Minecraft.getDebugFPS() / 60f).coerceIn(0.5f, 2f)
        ProgressBarAnimationWidth += (targetBarWidth - ProgressBarAnimationWidth) * lerpSpeed
        ProgressBarAnimationWidth = ProgressBarAnimationWidth.coerceIn(0F, maxBarWidth)
        drawRoundedRect(x + padding, barY, x + padding + maxBarWidth, barY + barHeight, Color(60, 60, 70, 180).rgb, 2F)
        val lighter = Color((ScaffoldTheme.red + 50).coerceAtMost(255), (ScaffoldTheme.green + 50).coerceAtMost(255), (ScaffoldTheme.blue + 50).coerceAtMost(255), 255)
        drawRoundedRect(x + padding, barY, x + padding + ProgressBarAnimationWidth, barY + barHeight, lighter.rgb, 2F)
    }

    private fun renderScaffoldModern(x: Float, y: Float, w: Float, h: Float) {
        val hotbarBlockCount = (0..8).sumOf { slotIndex ->
            val stack = mc.thePlayer.inventory.getStackInSlot(slotIndex)
            if (stack != null && stack.item is ItemBlock) stack.stackSize else 0
        }
        val percentage = scaffoldProgress()
        val targetBPS = displayedBPS
        AnimatedBps += (targetBPS - AnimatedBps) * 0.15 * (Minecraft.getDebugFPS() / 20.0).coerceIn(0.1, 2.0)

        val padding = 10F
        val themeColor = Color(ScaffoldTheme.red, ScaffoldTheme.green, ScaffoldTheme.blue, 40)
        drawRoundedRect(x + padding, y + padding, x + w - padding, y + h - padding, themeColor.rgb, 8F)

        val blockText = "$hotbarBlockCount"
        val centerX = x + w / 2
        val numberY = y + h / 2 - Fonts.font52.FONT_HEIGHT / 2 - 12F

        Fonts.font52.drawCenteredString(blockText, centerX, numberY, Color.WHITE.rgb)

        val labelY = numberY + Fonts.font52.FONT_HEIGHT + 2F
        Fonts.fontSemibold35.drawCenteredString("BLOCKS", centerX, labelY, Color(180, 180, 180).rgb)

        val bpsText = String.format("%.1f b/s", if(AnimatedBps < 0.01) 0.0 else AnimatedBps)
        Fonts.fontRegular30.drawCenteredString(bpsText, centerX, labelY + Fonts.fontSemibold35.FONT_HEIGHT + 2F, Color(150, 150, 150).rgb)

        val barHeight = 5F
        val barY = y + h - barHeight - padding - 2F
        val maxBarWidth = w - padding * 2
        val targetBarWidth = maxBarWidth * percentage
        val lerpSpeed = 0.15f * (Minecraft.getDebugFPS() / 60f).coerceIn(0.5f, 2f)
        ProgressBarAnimationWidth += (targetBarWidth - ProgressBarAnimationWidth) * lerpSpeed
        ProgressBarAnimationWidth = ProgressBarAnimationWidth.coerceIn(0F, maxBarWidth)
        drawRoundedRect(x + padding, barY, x + padding + maxBarWidth, barY + barHeight, Color(40, 40, 50, 200).rgb, 3F)
        val lighter = Color(ScaffoldTheme.red, ScaffoldTheme.green, ScaffoldTheme.blue, 255)
        drawRoundedRect(x + padding, barY, x + padding + ProgressBarAnimationWidth, barY + barHeight, lighter.rgb, 3F)
    }

    private fun renderScaffoldOutline(x: Float, y: Float, w: Float, h: Float) {
        val hotbarBlockCount = (0..8).sumOf { slotIndex ->
            val stack = mc.thePlayer.inventory.getStackInSlot(slotIndex)
            if (stack != null && stack.item is ItemBlock) stack.stackSize else 0
        }
        val percentage = scaffoldProgress()
        val targetBPS = displayedBPS
        AnimatedBps += (targetBPS - AnimatedBps) * 0.15 * (Minecraft.getDebugFPS() / 20.0).coerceIn(0.1, 2.0)

        val padding = 6F
        val innerPadding = 4F
        val themeColor = Color(ScaffoldTheme.red, ScaffoldTheme.green, ScaffoldTheme.blue, 255)

        RenderUtils.drawRoundedBorderRect(x + padding, y + padding, x + w - padding, y + h - padding, 
            2F, Color(0, 0, 0, 0).rgb, themeColor.rgb, 6F)

        val centerX = x + w / 2
        val textY = y + (h - Fonts.fontSemibold35.FONT_HEIGHT) / 2

        val blockText = "$hotbarBlockCount blocks"
        val bpsText = String.format("%.1f b/s", if(AnimatedBps < 0.01) 0.0 else AnimatedBps)
        val separator = "  |  "
        val fullText = "$blockText$separator$bpsText"

        Fonts.fontSemibold35.drawCenteredString(fullText, centerX, textY, Color.WHITE.rgb)

        val barHeight = 3F
        val barY = y + h - barHeight - padding - innerPadding
        val maxBarWidth = w - padding * 2 - innerPadding * 2
        val targetBarWidth = maxBarWidth * percentage
        val lerpSpeed = 0.15f * (Minecraft.getDebugFPS() / 60f).coerceIn(0.5f, 2f)
        ProgressBarAnimationWidth += (targetBarWidth - ProgressBarAnimationWidth) * lerpSpeed
        ProgressBarAnimationWidth = ProgressBarAnimationWidth.coerceIn(0F, maxBarWidth)

        drawRoundedRect(x + padding + innerPadding, barY, x + padding + innerPadding + maxBarWidth, barY + barHeight, Color(60, 60, 70, 150).rgb, 2F)
        if (ProgressBarAnimationWidth > 1F) {
            drawRoundedRect(x + padding + innerPadding, barY, x + padding + innerPadding + ProgressBarAnimationWidth, barY + barHeight, themeColor.rgb, 2F)
        }
    }

    private fun renderNotificationStack(x: Float, y: Float, w: Float, h: Float) {
        var currentYOffset = 0F
        val centerXOffset = 10F
        for (notify in notifications) {
            val rowY = y + currentYOffset
            notify.draw(x + centerXOffset, rowY)
            currentYOffset += ITEM_NOTIFY_HEIGHT
        }
    }

    private fun renderNormal3Content(x: Float, y: Float, w: Float, h: Float) {
        val info = calcNormal3Info()
        val textBaseY = y + (h - Fonts.fontSemibold40.FONT_HEIGHT) / 2 + Fonts.fontSemibold40.FONT_HEIGHT - 8
        val iconSize = 15F
        val iconY = y + (h - iconSize) / 2
        var cx = x + info.padding

        val colorRGB = legacyAccent
        drawImage(getLogoResource(), cx, iconY, 15, 15, colorRGB)
        cx += 15F + info.elementSpacing
        Fonts.fontSemibold40.drawString(ClientName, cx, textBaseY, colorRGB.rgb)
        cx += info.clientNameWidth + info.dotSpacing
        drawCenteredDot(cx, textBaseY)
        cx += 4F
        drawImage(getUserResource(), cx, iconY, 15, 15, Color.WHITE)
        cx += 15F + info.elementSpacing
        Fonts.fontSemibold40.drawString(info.username, cx - 1F, textBaseY, Color.WHITE.rgb)
        cx += info.usernameWidth + info.dotSpacing
        
        val showInsideLyric = showLyricOnIsland && lyricDisplayMode == "Inside" && 
            MusicHub.isCurrentlyPlaying && 
            (MusicHub.currentLyricDisplay.isNotEmpty() || MusicHub.currentMusicName != "None")
        
        if (showInsideLyric) {
            val currentLyric = MusicHub.currentLyricDisplay
            val currentMusicName = MusicHub.currentMusicName
            val displayText = if (lyricShowMusicName && currentMusicName != "None") {
                currentMusicName
            } else if (currentLyric.isNotEmpty()) {
                currentLyric.take(15)
            } else {
                ""
            }
            
            if (displayText.isNotEmpty()) {
                val font = if (lyricShowMusicName && currentMusicName != "None") getMusicNameFont() else getLyricTextFont()
                val lyricWidth = font.getStringWidth(displayText)
                
                if (lyricBounce) {
                    val (nextInside, vI) = spring(animInsideWidth, lyricWidth + 20F, velInsideWidth)
                    animInsideWidth = nextInside.coerceIn(0F, 300F)
                    velInsideWidth = vI
                    val (nextAlpha, vA) = spring(animBubbleAlpha, 1F, velBubbleAlpha)
                    animBubbleAlpha = nextAlpha.coerceIn(0F, 1F)
                    velBubbleAlpha = vA
                } else {
                    animInsideWidth = lyricWidth + 20F
                    animBubbleAlpha = 1F
                }
                
                if (animInsideWidth > 5F && animBubbleAlpha > 0.01F) {
                    val themeColor = ClientThemesUtils.getColor()
                    val lyricStartX = cx
                    val lyricEndX = cx + animInsideWidth
                    
                    drawRoundedRect(lyricStartX, y + 4F, lyricEndX, y + h - 4F, 
                        Color(30, 30, 35, (lyricBackgroundAlpha * animBubbleAlpha).toInt()).rgb, 5F)
                    
                    val useGradient = lyricColorMode == "Theme" && lyricGradientMode != "Sync"
                    val (gradientX, gradientY) = when {
                        !useGradient -> 0f to 0f
                        lyricGradientMode == "LeftToRight" -> 0.002f to 0f
                        else -> -0.002f to 0f
                    }
                    val gradientOffset = System.currentTimeMillis() % 10000 / 10000F
                    val gradientSpeed = ClientThemesUtils.ThemeFadeSpeed / 5f
                    val gradientColors = if (useGradient) {
                        val startColor = ClientThemesUtils.setColor("start", 255)
                        val endColor = ClientThemesUtils.setColor("end", 255)
                        if (lyricGradientMode == "LeftToRight") {
                            listOf(
                                floatArrayOf(startColor.red / 255f, startColor.green / 255f, startColor.blue / 255f, 1f),
                                floatArrayOf(endColor.red / 255f, endColor.green / 255f, endColor.blue / 255f, 1f)
                            )
                        } else {
                            listOf(
                                floatArrayOf(endColor.red / 255f, endColor.green / 255f, endColor.blue / 255f, 1f),
                                floatArrayOf(startColor.red / 255f, startColor.green / 255f, startColor.blue / 255f, 1f)
                            )
                        }
                    } else null
                    
                    // Draw music icon centered
                    val iconBgSize = 10F
                    val iconBgX = lyricStartX + 4F
                    val iconBgY = y + (h - iconBgSize) / 2
                    drawRoundedRect(iconBgX, iconBgY, iconBgX + iconBgSize, iconBgY + iconBgSize, 
                        Color(40, 40, 45, (200 * animBubbleAlpha).toInt()).rgb, 3F)
                    drawImage(ResourceLocation("airplus/watermark_images/music.png"), 
                        (iconBgX + 1).toInt(), (iconBgY + 1).toInt(), 8, 8, 
                        Color(255, 255, 255, (255 * animBubbleAlpha).toInt()))
                    
                    val textX = iconBgX + iconBgSize + 3F
                    val textColor = when {
                        lyricColorMode == "Custom" -> Color(lyricCustomColor.red, lyricCustomColor.green, lyricCustomColor.blue, (lyricTextAlpha * animBubbleAlpha).toInt()).rgb
                        else -> Color(themeColor.red, themeColor.green, themeColor.blue, (lyricTextAlpha * animBubbleAlpha).toInt()).rgb
                    }
                    
                    GradientFontShader.begin(useGradient, gradientX, gradientY, gradientColors ?: emptyList(), gradientSpeed, gradientOffset).use {
                        font.drawString(displayText, textX, textBaseY, if (useGradient) 0 else textColor)
                    }
                    
                    cx = lyricEndX + info.dotSpacing
                }
            }
        } else {
            if (lyricBounce) {
                val (nextInside, vI) = spring(animInsideWidth, 0F, velInsideWidth)
                animInsideWidth = nextInside.coerceIn(0F, 300F)
                velInsideWidth = vI
                val (nextAlpha, vA) = spring(animBubbleAlpha, 0F, velBubbleAlpha)
                animBubbleAlpha = nextAlpha.coerceIn(0F, 1F)
                velBubbleAlpha = vA
            } else {
                animInsideWidth = 0F
                animBubbleAlpha = 0F
            }
        }
        
        drawCenteredDot(cx, textBaseY)
        cx += 4F
        drawImage(getPingResource(), cx, iconY, 15, 15, Color.GREEN)
        cx += 15F + info.elementSpacing
        Fonts.fontSemibold40.drawString(info.pingStr, cx, textBaseY, Color.GREEN.rgb)
        cx += info.pingTextWidth
        Fonts.fontSemibold40.drawString("  to  ", cx, textBaseY, Color.WHITE.rgb)
        cx += info.toTextWidth
        Fonts.fontSemibold40.drawString(info.ipStr, cx, textBaseY, Color.WHITE.rgb)
        cx += info.serverIpWidth + info.dotSpacing
        drawCenteredDot(cx - 1, textBaseY)
        cx += 3F
        drawImage(ResourceLocation("airplus/watermark_images/fps.png"), cx, iconY, 15, 15, Color.WHITE)
        cx += 15F + info.elementSpacing
        Fonts.fontSemibold40.drawString(info.fpsStr, cx, textBaseY, Color.WHITE.rgb)
    }

    data class Normal3Info(
        val width: Float,
        val padding: Float = 13F,
        val elementSpacing: Float = 8F,
        val dotSpacing: Float = 13F,
        val username: String,
        val clientNameWidth: Float,
        val usernameWidth: Float,
        val pingStr: String,
        val pingTextWidth: Float,
        val toTextWidth: Float,
        val ipStr: String,
        val serverIpWidth: Float,
        val fpsStr: String,
        val fpsTextWidth: Float
    )

    private fun calcNormal3Info(): Normal3Info {
        val username = mc.session?.username ?: "Unknown"
        val fps = Minecraft.getDebugFPS()
        val pings = getSafePing()
        val ipStr = if (customip) ip else ServerUtils.remoteIp ?: "SinglePlayer"
        val clientNameWidth = Fonts.fontSemibold40.getStringWidth(ClientName).toFloat()
        val usernameWidth = Fonts.fontSemibold40.getStringWidth(username).toFloat() - 1f
        val pingStr = "${pings}ms"
        val pingTextWidth = Fonts.fontSemibold40.getStringWidth(pingStr).toFloat()
        val toTextWidth = Fonts.fontSemibold40.getStringWidth("  to  ").toFloat()
        val serverIpWidth = Fonts.fontSemibold40.getStringWidth(ipStr).toFloat()
        val fpsStr = "${fps}fps"
        val fpsTextWidth = Fonts.fontSemibold40.getStringWidth(fpsStr).toFloat()
        val padding = 13F
        val icon = 15F
        val space = 8F
        val dot = 13F
        val dotW = 4F
        
        var lyricWidthExtra = 0F
        val showInsideLyric = showLyricOnIsland && lyricDisplayMode == "Inside" && 
            MusicHub.isCurrentlyPlaying && 
            (MusicHub.currentLyricDisplay.isNotEmpty() || MusicHub.currentMusicName != "None")
        
        if (showInsideLyric) {
            val currentLyric = MusicHub.currentLyricDisplay
            val currentMusicName = MusicHub.currentMusicName
            val displayText = if (lyricShowMusicName && currentMusicName != "None") {
                currentMusicName
            } else if (currentLyric.isNotEmpty()) {
                currentLyric.take(15)
            } else {
                ""
            }
            if (displayText.isNotEmpty()) {
                val font = if (lyricShowMusicName && currentMusicName != "None") getMusicNameFont() else getLyricTextFont()
                lyricWidthExtra = font.getStringWidth(displayText) + 20F + dot + 14F // +14F for music icon
            }
        }
        
        val w = padding + icon + space + clientNameWidth + dot + dotW +
                icon + space + usernameWidth + dot + dotW +
                lyricWidthExtra +
                icon + space + pingTextWidth + toTextWidth + serverIpWidth + dot + 3F +
                icon + space + fpsTextWidth + padding
        return Normal3Info(w, username=username, clientNameWidth=clientNameWidth, usernameWidth=usernameWidth,
            pingStr=pingStr, pingTextWidth=pingTextWidth, toTextWidth=toTextWidth, ipStr=ipStr, serverIpWidth=serverIpWidth,
            fpsStr=fpsStr, fpsTextWidth=fpsTextWidth)
    }

    private fun calcMaxNotificationWidth(): Float {
        if (notifications.isEmpty()) return 0F
        var maxWidth = 0f
        val fixedElementWidth = 30F + 15F + 30F
        for (notif in notifications) {
            val titleWidth = Fonts.fontSemibold40.getStringWidth(notif.title).toFloat()
            val descWidth = Fonts.fontRegular35.getStringWidth(notif.message).toFloat()
            val textWidth = max(titleWidth, descWidth)
            val totalWidth = fixedElementWidth + textWidth
            maxWidth = max(maxWidth, totalWidth)
        }
        return maxWidth
    }

    private fun drawOldStyleNotifications(startYOffset: Float = 0F) {
        var currentY = start_y + startYOffset
        val padding = 3F
        for(notify in notifications) {
            val tW = Fonts.fontSemibold40.getStringWidth(notify.title)
            val dW = Fonts.fontRegular35.getStringWidth(notify.message)
            val w = 35F + max(tW, dW) + 20F
            val x = (width - w)/2
            drawRoundedBorderRect(x, currentY, x+w, currentY+ITEM_NOTIFY_HEIGHT, 0.2F, legacyBackground.rgb, legacyBackground.rgb, 10F)
            notify.draw(x + padding, currentY + 5F)
            currentY += ITEM_NOTIFY_HEIGHT + 2F
        }
    }

    private fun drawNormal() {
        val username = mc.session.username
        val fps = Minecraft.getDebugFPS()
        val pings = getSafePing()
        val colorRGB = legacyAccent
        val text = " | $username | ${fps}fps | ${pings}ms"
        val mainText = ClientName
        val h = 38F
        val wCalc = 20F + 18F + 5F + Fonts.fontSemibold40.getStringWidth(mainText + text) + 10F
        val x = (width - wCalc)/2
        val y = start_y
        val (nX, _) = spring(AnimGlobalX, x, VelGlobalX)
        AnimGlobalX = nX
        ShowShadow(x, y, wCalc, h)
        drawRoundedBorderRect(x, y, x+wCalc, y+h, 0.5F, legacyBackground.rgb, legacyBackground.rgb, h/2)
        drawImage(ResourceLocation("airplus/logo_icon.png"), (x+5).toInt(), (y + (h-18)/2).toInt(), 18, 18, colorRGB)
        Fonts.fontSemibold40.drawString(mainText, x + 28, y + (h-9)/2+1, colorRGB.rgb)
        Fonts.fontSemibold40.drawString(text, x + 28 + Fonts.fontSemibold40.getStringWidth(mainText), y + (h-9)/2+1, -1)
    }

    private fun drawNormal2() {}
    
    private fun drawCenteredDot(x: Float, textBaseY: Float) {
        val dotY = textBaseY - Fonts.fontSemibold40.FONT_HEIGHT / 2 + 3F
        Fonts.fontSemibold40.drawString("·", x - 3f, dotY, Color(180, 180, 180, 255).rgb)
    }

    private fun ShowShadow(x: Float, y: Float, w: Float, h: Float) {
        if (ShadowCheck) GlowUtils.drawGlow(x, y, w, h, shadowRadiusValue.toInt(), shadowColor)
    }

    /** Samsara DynamicIslandPainter.SHADOW_EXTENT / SHADOW_STEP */
    private val OPAI_SHADOW_EXTENT = 8f
    private val OPAI_SHADOW_STEP = 0.25f

    /** Samsara shadowCoverage：归一化距离的指数衰减覆盖度。 */
    private fun opaiShadowCoverage(distance: Float): Float {
        val normalized = distance.coerceAtLeast(0f) / 2.8f
        return 0.5f * exp(-0.78f * normalized - 0.5f * normalized * normalized)
    }

    /**
     * Samsara DynamicIslandNanoSurface.shadow 的环形阴影（1.8.9 版）：
     * 外扩 8px、步长 0.25 逐层向外绘制黑色圆角矩形（大→小），每层 alpha =
     * shadowLayer(spread) = 255·(coverage(s)−coverage(s+step))/(1−coverage(s+step))。
     * 外层→内层叠画在可见的环带区域与 Samsara 的「外矩形挖洞」逐环绘制结果一致
     * （环带内每层只画一次；岛体内部最终被背景色覆盖）。
     */
    private fun drawOpaiIslandShadow(x: Float, y: Float, w: Float, h: Float, radius: Float) {
        var spread = OPAI_SHADOW_EXTENT
        while (spread > 0f) {
            val desired = opaiShadowCoverage(spread)
            val previous = opaiShadowCoverage(spread + OPAI_SHADOW_STEP)
            val alpha = (255f * (desired - previous) / (1f - previous)).roundToInt()
            if (alpha > 0) {
                drawRoundedRect(x - spread, y - spread, x + w + spread, y + h + spread, alpha shl 24, radius + spread)
            }
            spread -= OPAI_SHADOW_STEP
        }
    }

    fun drawToggleButton(StartX: Float, StartY: Float, ContainerH: Float, ModuleState: Boolean, animationState: SwitchAnimationState) {
        val btnH = 19F
        val btnW = 30F
        val margin = 3F
        val radius = btnH / 2
        val btnStartY = StartY + (ITEM_NOTIFY_HEIGHT - btnH) / 2
        animationState.updateState(ModuleState)
        val anim = animationState.getOutput()
        val trackColor = if (ModuleState) Color(ButtonColor.red, ButtonColor.green, ButtonColor.blue, 255) else Color(45, 45, 45, 255)
        drawRoundedBorderRect(StartX, btnStartY, StartX + btnW, btnStartY + btnH, 0.1f, trackColor.rgb, trackColor.rgb, radius)
        val knobSize = btnH - margin * 2
        val knobX = StartX + margin + (btnW - margin*2 - knobSize) * anim.toFloat()
        val knobColor = if (ModuleState) Color.WHITE.rgb else Color(100, 100, 100, 255).rgb
        drawRoundedBorderRect(knobX, btnStartY + margin, knobX + knobSize, btnStartY + margin + knobSize, 0.1f, knobColor, knobColor, knobSize / 2)
    }

    fun drawToggleText(StartX: Float, StartY: Float, TextBar: Pair<String, String>, ContainerH: Float) {
        val titleH = 9F
        val textStartX = StartX + 30F + 8F
        val center = StartY + ITEM_NOTIFY_HEIGHT / 2
        Fonts.fontSemibold40.drawString(TextBar.first, textStartX, center - titleH + 1F, Color.WHITE.rgb)
        Fonts.fontRegular35.drawString(TextBar.second, textStartX, center + 3F, Color.WHITE.rgb)
    }

    enum class Direction { FORWARDS, BACKWARDS }
    class EaseOutExpo(private val duration: Long, private val end: Double) {
        private var start = 0.0
        private var startTime = System.currentTimeMillis()
        private var direction = Direction.FORWARDS
        fun setDirection(dir: Direction) { if (this.direction != dir) { this.direction = dir; startTime = System.currentTimeMillis(); start = getOutput() } }
        fun getOutput(): Double {
            val progress = (System.currentTimeMillis() - startTime).toDouble() / duration
            val result = when (direction) {
                Direction.FORWARDS -> if (progress >= 1.0) end else (-2.0.pow(-10 * progress) + 1) * end
                Direction.BACKWARDS -> if (progress >= 1.0) 0.0 else (2.0.pow(-10 * progress) * end)
            }
            return result.coerceIn(0.0, end)
        }
    }
    class SwitchAnimationState {
        private val animation = EaseOutExpo(300, 1.0)
        fun updateState(state: Boolean) = animation.setDirection(if (state) Direction.FORWARDS else Direction.BACKWARDS)
        fun getOutput() = animation.getOutput()
    }

    private abstract class Notification(val id: String = UUID.randomUUID().toString(), var title: String, var message: String, var createTime: Long = System.currentTimeMillis(), var duration: Long = 3000L) {
        // 滑入动画锚点：仅在创建时设置。重复开关同模块时只刷新 createTime（用于计时过期），
        // 不重置 slideTime，避免正在显示的通知重播滑入动画
        val slideTime: Long = System.currentTimeMillis()
        var isMarkedForDelete = false
        abstract fun draw(x: Float, y: Float)
        open fun updateState(newMsg: String, newEnable: Boolean, newDuration: Long) { this.message = newMsg; this.createTime = System.currentTimeMillis(); this.duration = newDuration }
        fun getHeight() = ITEM_NOTIFY_HEIGHT
        fun update() { if (System.currentTimeMillis() > createTime + duration) isMarkedForDelete = true }
    }
    private class ToggleNotification(t: String, m: String, d: Long, var enabled: Boolean, val moduleName: String) : Notification(title=t, message=m, duration=d) {
        val anim = SwitchAnimationState()
        init { anim.updateState(enabled) }
        override fun updateState(newMsg: String, newEnable: Boolean, newDuration: Long) { super.updateState(newMsg, newEnable, newDuration); this.enabled = newEnable; anim.updateState(newEnable) }
        override fun draw(x: Float, y: Float) { drawToggleButton(x, y, 0F, enabled, anim); drawToggleText(x, y, Pair(title, message), 0F) }
    }

    fun showToggleNotification(title: String, message: String, enabled: Boolean, moduleName: String) {
        val existing = notifications.find { it.moduleName == moduleName }
        val duration = notifyDuration.toLong()
        if (existing != null) existing.updateState(message, enabled, duration)
        else notifications.add(ToggleNotification(title, message, duration, enabled, moduleName))
    }
    private fun updateNotifications() { notifications.forEach { it.update() }; notifications.removeAll { it.isMarkedForDelete } }

    object EmbeddedStencil {
        fun checkSetupFBO(framebuffer: Framebuffer?) {
            if (framebuffer != null && framebuffer.depthBuffer > -1) {
                setupFBO(framebuffer)
                framebuffer.depthBuffer = -1
            }
        }
        fun setupFBO(framebuffer: Framebuffer) {
            EXTFramebufferObject.glDeleteRenderbuffersEXT(framebuffer.depthBuffer)
            val stencilDepthBufferID = EXTFramebufferObject.glGenRenderbuffersEXT()
            EXTFramebufferObject.glBindRenderbufferEXT(EXTFramebufferObject.GL_RENDERBUFFER_EXT, stencilDepthBufferID)
            EXTFramebufferObject.glRenderbufferStorageEXT(EXTFramebufferObject.GL_RENDERBUFFER_EXT, EXTPackedDepthStencil.GL_DEPTH_STENCIL_EXT, Minecraft.getMinecraft().displayWidth, Minecraft.getMinecraft().displayHeight)
            EXTFramebufferObject.glFramebufferRenderbufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, EXTFramebufferObject.GL_DEPTH_ATTACHMENT_EXT, EXTFramebufferObject.GL_RENDERBUFFER_EXT, stencilDepthBufferID)
            EXTFramebufferObject.glFramebufferRenderbufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, EXTFramebufferObject.GL_STENCIL_ATTACHMENT_EXT, EXTFramebufferObject.GL_RENDERBUFFER_EXT, stencilDepthBufferID)
        }
        fun write(invert: Boolean) {
            checkSetupFBO(Minecraft.getMinecraft().framebuffer)
            glClearStencil(0)
            glClear(GL_STENCIL_BUFFER_BIT)
            glEnable(GL_STENCIL_TEST)
            glStencilFunc(GL_ALWAYS, 1, 65535)
            glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE)
            if (!invert) {
                glColorMask(false, false, false, false)
                glDepthMask(false)
                glStencilFunc(GL_ALWAYS, 1, 65535)
                glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE)
            }
        }
        fun erase(invert: Boolean) {
            glStencilFunc(if (invert) GL_EQUAL else GL_NOTEQUAL, 1, 65535)
            glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE)
            if (invert) {
                glColorMask(true, true, true, true)
                glDepthMask(true)
                glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE)
            } else {
                glColorMask(true, true, true, true)
                glDepthMask(true)
                glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP)
            }
        }
        fun dispose() {
            glDisable(GL_STENCIL_TEST)
        }
    }

    private fun getMusicNameFont() = when (lyricMusicNameFont) {
        "ExtraBold35" -> Fonts.fontExtraBold35
        "ExtraBold40" -> Fonts.fontExtraBold40
        "Semibold35" -> Fonts.fontSemibold35
        "Semibold40" -> Fonts.fontSemibold40
        "Regular30" -> Fonts.fontRegular30
        "Regular35" -> Fonts.fontRegular35
        "Regular40" -> Fonts.fontRegular40
        "Regular45" -> Fonts.fontRegular45
        "Bold180" -> Fonts.fontBold180
        else -> Fonts.fontSemibold35
    }

    private fun getLyricTextFont() = when (lyricTextFont) {
        "ExtraBold35" -> Fonts.fontExtraBold35
        "ExtraBold40" -> Fonts.fontExtraBold40
        "Semibold35" -> Fonts.fontSemibold35
        "Semibold40" -> Fonts.fontSemibold40
        "Regular30" -> Fonts.fontRegular30
        "Regular35" -> Fonts.fontRegular35
        "Regular40" -> Fonts.fontRegular40
        "Regular45" -> Fonts.fontRegular45
        "Bold180" -> Fonts.fontBold180
        else -> Fonts.fontRegular35
    }

    private fun renderLyricDisplay() {
        if (lyricDisplayMode == "Full") return
        
        val isPlaying = MusicHub.isCurrentlyPlaying
        val currentLyric = MusicHub.currentLyricDisplay
        val currentMusicName = MusicHub.currentMusicName

        if (!isPlaying || (currentLyric.isEmpty() && currentMusicName == "None")) {
            if (lyricBounce) {
                val (nextAlpha, vA) = spring(animBubbleAlpha, 0F, velBubbleAlpha)
                animBubbleAlpha = nextAlpha.coerceIn(0F, 1F)
                velBubbleAlpha = vA
                val (nextWidth, vW) = spring(animBubbleWidth, 0F, velBubbleWidth)
                animBubbleWidth = nextWidth.coerceIn(0F, 500F)
                velBubbleWidth = vW
                val (nextHeight, vH) = spring(animBubbleHeight, 0F, velBubbleHeight)
                animBubbleHeight = nextHeight.coerceIn(0F, 200F)
                velBubbleHeight = vH
            } else {
                animBubbleAlpha = 0F
                animBubbleWidth = 0F
                animBubbleHeight = 0F
            }
            return
        }

        when (lyricDisplayMode) {
            "Below" -> renderLyricBelow()
            "Float" -> renderLyricFloat()
            "Full" -> renderLyricFull()
        }
    }

    private fun renderLyricBelow() {
        val currentLyric = MusicHub.currentLyricDisplay
        val previousLyric = MusicHub.previousLyricDisplay
        val nextLyric = MusicHub.nextLyricDisplay
        val currentMusicName = MusicHub.currentMusicName
        val musicFont = getMusicNameFont()
        val textFont = getLyricTextFont()
        
        val displayLines = mutableListOf<Pair<String, Boolean>>()
        if (lyricShowMusicName && currentMusicName != "None") {
            displayLines.add(currentMusicName to true)
        }
        if (lyricShowPrevious && previousLyric.isNotEmpty()) {
            displayLines.add(previousLyric to false)
        }
        if (currentLyric.isNotEmpty()) {
            displayLines.add(currentLyric to false)
        }
        if (lyricShowNext && nextLyric.isNotEmpty()) {
            displayLines.add(nextLyric to false)
        }
        
        if (displayLines.isEmpty()) return

        val maxWidth = displayLines.maxOf { (line, isMusic) -> 
            (if (isMusic) musicFont else textFont).getStringWidth(line) + 40F 
        }.coerceIn(150F, 350F)
        val targetHeight = displayLines.size * 18F + 16F + if (lyricShowProgress) 10F else 0F

        if (lyricBounce) {
            val (nextW, vW) = spring(animBubbleWidth, maxWidth, velBubbleWidth)
            animBubbleWidth = nextW.coerceIn(0F, 500F)
            velBubbleWidth = vW
            val (nextH, vH) = spring(animBubbleHeight, targetHeight, velBubbleHeight)
            animBubbleHeight = nextH.coerceIn(0F, 200F)
            velBubbleHeight = vH
            val (nextAlpha, vA) = spring(animBubbleAlpha, 1F, velBubbleAlpha)
            animBubbleAlpha = nextAlpha.coerceIn(0F, 1F)
            velBubbleAlpha = vA
        } else {
            animBubbleWidth = maxWidth
            animBubbleHeight = targetHeight
            animBubbleAlpha = 1F
        }

        if (animBubbleAlpha < 0.01f || animBubbleWidth < 10f || animBubbleHeight < 10f) return

        val islandBottom = AnimGlobalY + AnimGlobalHeight
        val bubbleX = AnimGlobalX
        val bubbleY = islandBottom
        val alpha = (lyricBackgroundAlpha * animBubbleAlpha).toInt()

        glPushMatrix()
        if (lyricBlur && blurCheck) {
            try {
                EmbeddedStencil.checkSetupFBO(mc.framebuffer)
                EmbeddedStencil.write(false)
                RenderUtils.drawRoundedRect(bubbleX, bubbleY, bubbleX + AnimGlobalWidth, bubbleY + animBubbleHeight, Color.WHITE.rgb, 8F, RenderUtils.RoundedCorners.BOTTOM_ONLY)
                EmbeddedStencil.erase(true)
                GlStateManager.pushMatrix()
                applyBlur(bubbleX, bubbleY, AnimGlobalWidth, animBubbleHeight)
                GlStateManager.popMatrix()
            } catch (e: Exception) {
            } finally {
                // 异常时也必须还原 stencil 状态，避免空 catch 泄漏 GL_EQUAL
                EmbeddedStencil.dispose()
            }
        }

        RenderUtils.drawRoundedRect(bubbleX, bubbleY, bubbleX + AnimGlobalWidth, bubbleY + animBubbleHeight,
                       Color(0, 0, 0, alpha).rgb, 8F, RenderUtils.RoundedCorners.BOTTOM_ONLY)
        
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        val themeColor = ClientThemesUtils.getColor()
        val useGradient = lyricColorMode == "Theme" && lyricGradientMode != "Sync"
        val (gradientX, gradientY) = when {
            !useGradient -> 0f to 0f
            lyricGradientMode == "LeftToRight" -> 0.002f to 0f
            else -> -0.002f to 0f
        }
        val gradientOffset = System.currentTimeMillis() % 10000 / 10000F
        val gradientSpeed = ClientThemesUtils.ThemeFadeSpeed / 5f
        val gradientColors = if (useGradient) {
            val startColor = ClientThemesUtils.setColor("start", 255)
            val endColor = ClientThemesUtils.setColor("end", 255)
            if (lyricGradientMode == "LeftToRight") {
                listOf(
                    floatArrayOf(startColor.red / 255f, startColor.green / 255f, startColor.blue / 255f, 1f),
                    floatArrayOf(endColor.red / 255f, endColor.green / 255f, endColor.blue / 255f, 1f)
                )
            } else {
                listOf(
                    floatArrayOf(endColor.red / 255f, endColor.green / 255f, endColor.blue / 255f, 1f),
                    floatArrayOf(startColor.red / 255f, startColor.green / 255f, startColor.blue / 255f, 1f)
                )
            }
        } else null
        
        if (lyricScrollAnimation) {
            val currentFullText = displayLines.joinToString("|") { it.first }
            if (currentFullText != lastLyricText) {
                lastLyricText = currentFullText
                scrollAnimProgress = 18F
                velScrollAnim = 0F
            }
            if (scrollAnimProgress > 0.01F) {
                val animSpeed = 300F / lyricScrollAnimTime
                val (nextScroll, vS) = spring(scrollAnimProgress, 0F, velScrollAnim * animSpeed)
                scrollAnimProgress = nextScroll.coerceIn(0F, 50F)
                velScrollAnim = vS
            }
        }
        
        GradientFontShader.begin(useGradient, gradientX, gradientY, gradientColors ?: emptyList(), gradientSpeed, gradientOffset).use {
            var textY = bubbleY + 8F
            displayLines.forEachIndexed { index, (line, isMusic) ->
                val font = if (isMusic) musicFont else textFont
                val textWidth = font.getStringWidth(line)
                val textX = bubbleX + (AnimGlobalWidth - textWidth) / 2
                val currentLyricIndex = displayLines.indexOfFirst { it.first == currentLyric }
                val lineAlpha = if (index == currentLyricIndex || (currentLyricIndex == -1 && index == displayLines.size - 1)) {
                    (lyricTextAlpha * animBubbleAlpha).toInt()
                } else {
                    (lyricTextAlpha * 0.6 * animBubbleAlpha).toInt()
                }
                val colorToUse = when {
                    useGradient -> 0
                    lyricColorMode == "Theme" -> Color(themeColor.red, themeColor.green, themeColor.blue, lineAlpha).rgb
                    else -> Color(lyricCustomColor.red, lyricCustomColor.green, lyricCustomColor.blue, lineAlpha).rgb
                }
                val actualY = if (isMusic) textY else textY - scrollAnimProgress
                font.drawString(line, textX, actualY, colorToUse)
                textY += 18F
            }
        }
        
        if (lyricShowProgress) {
            val progress = MusicHub.progress.coerceIn(0F, 1F)
            val timeStr = MusicHub.timeDisplayString
            val barHeight = 3F
            val barY = bubbleY + animBubbleHeight - barHeight - 6F
            val barPadding = 8F
            val timeWidth = Fonts.fontRegular30.getStringWidth(timeStr)
            val timeX = bubbleX + barPadding
            val barStartX = timeX + timeWidth + 6F
            val maxBarWidth = AnimGlobalWidth - barPadding * 2 - timeWidth - 6F
            
            Fonts.fontRegular30.drawString(timeStr, timeX, barY - 1F, 
                Color(180, 180, 190, (lyricTextAlpha * animBubbleAlpha).toInt()).rgb)
            
            drawRoundedRect(barStartX, barY, barStartX + maxBarWidth, barY + barHeight, 
                Color(60, 60, 70, (180 * animBubbleAlpha).toInt()).rgb, 2F)
            
            val progressColor = when {
                lyricColorMode == "Custom" -> Color(lyricCustomColor.red, lyricCustomColor.green, lyricCustomColor.blue, (lyricTextAlpha * animBubbleAlpha).toInt())
                else -> Color(themeColor.red, themeColor.green, themeColor.blue, (lyricTextAlpha * animBubbleAlpha).toInt())
            }
            if (maxBarWidth * progress > 1f) {
                drawRoundedRect(barStartX, barY, barStartX + maxBarWidth * progress, barY + barHeight, 
                    progressColor.rgb, 2F)
            }
        }
        glPopMatrix()
    }

    private fun renderLyricFloat() {
        val currentLyric = MusicHub.currentLyricDisplay
        val previousLyric = MusicHub.previousLyricDisplay
        val nextLyric = MusicHub.nextLyricDisplay
        val currentMusicName = MusicHub.currentMusicName
        val musicFont = getMusicNameFont()
        val textFont = getLyricTextFont()
        
        val displayLines = mutableListOf<Pair<String, Boolean>>()
        if (lyricShowMusicName && currentMusicName != "None") {
            displayLines.add(currentMusicName to true)
        }
        if (lyricShowPrevious && previousLyric.isNotEmpty()) {
            displayLines.add(previousLyric to false)
        }
        if (currentLyric.isNotEmpty()) {
            displayLines.add(currentLyric to false)
        }
        if (lyricShowNext && nextLyric.isNotEmpty()) {
            displayLines.add(nextLyric to false)
        }
        
        if (displayLines.isEmpty()) return

        val maxWidth = displayLines.maxOf { (line, isMusic) -> 
            (if (isMusic) musicFont else textFont).getStringWidth(line) + 40F 
        }.coerceIn(150F, 350F)
        val targetHeight = displayLines.size * 18F + 16F + if (lyricShowProgress) 10F else 0F

        if (lyricBounce) {
            val (nextW, vW) = spring(animBubbleWidth, maxWidth, velBubbleWidth)
            animBubbleWidth = nextW.coerceIn(0F, 500F)
            velBubbleWidth = vW
            val (nextH, vH) = spring(animBubbleHeight, targetHeight, velBubbleHeight)
            animBubbleHeight = nextH.coerceIn(0F, 200F)
            velBubbleHeight = vH
            val (nextY, vY) = spring(animBubbleY, lyricFloatOffsetY.toFloat(), velBubbleY)
            animBubbleY = nextY
            velBubbleY = vY
            val (nextAlpha, vA) = spring(animBubbleAlpha, 1F, velBubbleAlpha)
            animBubbleAlpha = nextAlpha.coerceIn(0F, 1F)
            velBubbleAlpha = vA
        } else {
            animBubbleWidth = maxWidth
            animBubbleHeight = targetHeight
            animBubbleY = lyricFloatOffsetY.toFloat()
            animBubbleAlpha = 1F
        }

        if (animBubbleAlpha < 0.01f || animBubbleWidth < 10f || animBubbleHeight < 10f) return

        val islandBottom = AnimGlobalY + AnimGlobalHeight
        val bubbleX = (width - animBubbleWidth) / 2
        val bubbleY = islandBottom + animBubbleY
        val alpha = (lyricBackgroundAlpha * animBubbleAlpha).toInt()

        glPushMatrix()
        if (lyricBlur && blurCheck) {
            try {
                EmbeddedStencil.checkSetupFBO(mc.framebuffer)
                EmbeddedStencil.write(false)
                drawRoundedRect(bubbleX, bubbleY, bubbleX + animBubbleWidth, bubbleY + animBubbleHeight, Color.WHITE.rgb, 8F)
                EmbeddedStencil.erase(true)
                GlStateManager.pushMatrix()
                applyBlur(bubbleX, bubbleY, animBubbleWidth, animBubbleHeight)
                GlStateManager.popMatrix()
            } catch (e: Exception) {
            } finally {
                // 异常时也必须还原 stencil 状态，避免空 catch 泄漏 GL_EQUAL
                EmbeddedStencil.dispose()
            }
        }

        drawRoundedRect(bubbleX, bubbleY, bubbleX + animBubbleWidth, bubbleY + animBubbleHeight, 
                       Color(0, 0, 0, alpha).rgb, 8F)
        
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        val themeColor = ClientThemesUtils.getColor()
        val useGradient = lyricColorMode == "Theme" && lyricGradientMode != "Sync"
        val (gradientX, gradientY) = when {
            !useGradient -> 0f to 0f
            lyricGradientMode == "LeftToRight" -> 0.002f to 0f
            else -> -0.002f to 0f
        }
        val gradientOffset = System.currentTimeMillis() % 10000 / 10000F
        val gradientSpeed = ClientThemesUtils.ThemeFadeSpeed / 5f
        val gradientColors = if (useGradient) {
            val startColor = ClientThemesUtils.setColor("start", 255)
            val endColor = ClientThemesUtils.setColor("end", 255)
            if (lyricGradientMode == "LeftToRight") {
                listOf(
                    floatArrayOf(startColor.red / 255f, startColor.green / 255f, startColor.blue / 255f, 1f),
                    floatArrayOf(endColor.red / 255f, endColor.green / 255f, endColor.blue / 255f, 1f)
                )
            } else {
                listOf(
                    floatArrayOf(endColor.red / 255f, endColor.green / 255f, endColor.blue / 255f, 1f),
                    floatArrayOf(startColor.red / 255f, startColor.green / 255f, startColor.blue / 255f, 1f)
                )
            }
        } else null
        
        if (lyricScrollAnimation) {
            val currentFullText = displayLines.joinToString("|") { it.first }
            if (currentFullText != lastLyricText) {
                lastLyricText = currentFullText
                scrollAnimProgress = 18F
                velScrollAnim = 0F
            }
            if (scrollAnimProgress > 0.01F) {
                val animSpeed = 300F / lyricScrollAnimTime
                val (nextScroll, vS) = spring(scrollAnimProgress, 0F, velScrollAnim * animSpeed)
                scrollAnimProgress = nextScroll.coerceIn(0F, 50F)
                velScrollAnim = vS
            }
        }
        
        GradientFontShader.begin(useGradient, gradientX, gradientY, gradientColors ?: emptyList(), gradientSpeed, gradientOffset).use {
            var textY = bubbleY + 8F
            displayLines.forEachIndexed { index, (line, isMusic) ->
                val font = if (isMusic) musicFont else textFont
                val textWidth = font.getStringWidth(line)
                val textX = bubbleX + (animBubbleWidth - textWidth) / 2
                val currentLyricIndex = displayLines.indexOfFirst { it.first == currentLyric }
                val lineAlpha = if (index == currentLyricIndex || (currentLyricIndex == -1 && index == displayLines.size - 1)) {
                    (lyricTextAlpha * animBubbleAlpha).toInt()
                } else {
                    (lyricTextAlpha * 0.6 * animBubbleAlpha).toInt()
                }
                val colorToUse = when {
                    useGradient -> 0
                    lyricColorMode == "Theme" -> Color(themeColor.red, themeColor.green, themeColor.blue, lineAlpha).rgb
                    else -> Color(lyricCustomColor.red, lyricCustomColor.green, lyricCustomColor.blue, lineAlpha).rgb
                }
                val actualY = if (isMusic) textY else textY - scrollAnimProgress
                font.drawString(line, textX, actualY, colorToUse)
                textY += 18F
            }
        }
        
        if (lyricShowProgress) {
            val progress = MusicHub.progress.coerceIn(0F, 1F)
            val timeStr = MusicHub.timeDisplayString
            val barHeight = 3F
            val barY = bubbleY + animBubbleHeight - barHeight - 6F
            val barPadding = 8F
            val timeWidth = Fonts.fontRegular30.getStringWidth(timeStr)
            val timeX = bubbleX + barPadding
            val barStartX = timeX + timeWidth + 6F
            val maxBarWidth = animBubbleWidth - barPadding * 2 - timeWidth - 6F
            
            Fonts.fontRegular30.drawString(timeStr, timeX, barY - 1F, 
                Color(180, 180, 190, (lyricTextAlpha * animBubbleAlpha).toInt()).rgb)
            
            drawRoundedRect(barStartX, barY, barStartX + maxBarWidth, barY + barHeight, 
                Color(60, 60, 70, (180 * animBubbleAlpha).toInt()).rgb, 2F)
            
            val progressColor = when {
                lyricColorMode == "Custom" -> Color(lyricCustomColor.red, lyricCustomColor.green, lyricCustomColor.blue, (lyricTextAlpha * animBubbleAlpha).toInt())
                else -> Color(themeColor.red, themeColor.green, themeColor.blue, (lyricTextAlpha * animBubbleAlpha).toInt())
            }
            if (maxBarWidth * progress > 1f) {
                drawRoundedRect(barStartX, barY, barStartX + maxBarWidth * progress, barY + barHeight, 
                    progressColor.rgb, 2F)
            }
        }
        glPopMatrix()
    }

    private var lastFullLyricText = ""
    private var fullLyricAnimStartTime = 0L
    private var fullLyricAnimProgress = 0F

    private fun renderLyricFullContent(x: Float, y: Float, w: Float, h: Float) {
        val currentLyric = MusicHub.currentLyricDisplay ?: ""
        val textFont = getLyricTextFont() ?: return
        val themeColor = ClientThemesUtils.getColor() ?: return
        
        if (currentLyric.isEmpty()) return
        
        if (currentLyric != lastFullLyricText) {
            lastFullLyricText = currentLyric
            fullLyricAnimStartTime = System.currentTimeMillis()
            fullLyricAnimProgress = 0F
        }
        
        val animDuration = lyricFullAnimTime.toFloat()
        val elapsed = (System.currentTimeMillis() - fullLyricAnimStartTime).toFloat()
        fullLyricAnimProgress = (elapsed / animDuration).coerceIn(0F, 1F)
        
        val textWidth = textFont.getStringWidth(currentLyric)
        val textX = x + (w - textWidth) / 2
        val textY = y + (h - textFont.height) / 2 - if (lyricShowProgress) 8F else 0F
        
        val colorToUse = when (lyricColorMode) {
            "Theme" -> Color(themeColor.red, themeColor.green, themeColor.blue, 255)
            else -> Color(lyricCustomColor.red, lyricCustomColor.green, lyricCustomColor.blue, 255)
        }
        
        glPushMatrix()
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        
        when (lyricFullAnimation) {
            "None" -> {
                textFont.drawString(currentLyric, textX, textY, colorToUse.rgb)
            }
            "Fade" -> {
                val alpha = (fullLyricAnimProgress * 255).toInt()
                val fadeColor = Color(colorToUse.red, colorToUse.green, colorToUse.blue, alpha)
                textFont.drawString(currentLyric, textX, textY, fadeColor.rgb)
            }
            "SlideLeft" -> {
                val offsetX = (1F - fullLyricAnimProgress) * w * 0.3F
                textFont.drawString(currentLyric, textX - offsetX, textY, colorToUse.rgb)
            }
            "SlideRight" -> {
                val offsetX = (1F - fullLyricAnimProgress) * w * 0.3F
                textFont.drawString(currentLyric, textX + offsetX, textY, colorToUse.rgb)
            }
            "SlideUp" -> {
                val offsetY = (1F - fullLyricAnimProgress) * h * 0.5F
                textFont.drawString(currentLyric, textX, textY + offsetY, colorToUse.rgb)
            }
            "SlideDown" -> {
                val offsetY = (1F - fullLyricAnimProgress) * h * 0.5F
                textFont.drawString(currentLyric, textX, textY - offsetY, colorToUse.rgb)
            }
            "Scale" -> {
                val scale = 0.5F + fullLyricAnimProgress * 0.5F
                glTranslatef(textX + textWidth / 2, textY + textFont.height / 2, 0F)
                glScalef(scale, scale, 1F)
                glTranslatef(-(textX + textWidth / 2), -(textY + textFont.height / 2), 0F)
                val alpha = (fullLyricAnimProgress * 255).toInt()
                val scaleColor = Color(colorToUse.red, colorToUse.green, colorToUse.blue, alpha)
                textFont.drawString(currentLyric, textX, textY, scaleColor.rgb)
            }
            "Typewriter" -> {
                val visibleChars = (fullLyricAnimProgress * currentLyric.length).toInt().coerceIn(0, currentLyric.length)
                val displayText = currentLyric.substring(0, visibleChars)
                textFont.drawString(displayText, textX, textY, colorToUse.rgb)
            }
            else -> {
                textFont.drawString(currentLyric, textX, textY, colorToUse.rgb)
            }
        }
        
        glPopMatrix()
        
        if (lyricShowProgress) {
            val progress = MusicHub.progress.coerceIn(0F, 1F)
            val timeStr = MusicHub.timeDisplayString ?: "0:00 / 0:00"
            val barHeight = 3F
            val barY = y + h - barHeight - 8F
            val barPadding = 8F
            val fontRegular30 = Fonts.fontRegular30 ?: return
            val timeWidth = fontRegular30.getStringWidth(timeStr)
            val timeX = x + barPadding
            val barStartX = timeX + timeWidth + 6F
            val maxBarWidth = w - barPadding * 2 - timeWidth - 6F
            
            fontRegular30.drawString(timeStr, timeX, barY - 1F, 
                Color(180, 180, 190, 200).rgb)
            
            drawRoundedRect(barStartX, barY, barStartX + maxBarWidth, barY + barHeight, 
                Color(60, 60, 70, 180).rgb, 2F)
            
            val progressColor = when (lyricColorMode) {
                "Custom" -> Color(lyricCustomColor.red, lyricCustomColor.green, lyricCustomColor.blue, 200)
                else -> Color(themeColor.red, themeColor.green, themeColor.blue, 200)
            }
            if (maxBarWidth * progress > 1f) {
                drawRoundedRect(barStartX, barY, barStartX + maxBarWidth * progress, barY + barHeight, 
                    progressColor.rgb, 2F)
            }
        }
    }

    private fun renderLyricFull() {
        val currentLyric = MusicHub.currentLyricDisplay ?: ""
        val previousLyric = MusicHub.previousLyricDisplay ?: ""
        val nextLyric = MusicHub.nextLyricDisplay ?: ""
        val currentMusicName = MusicHub.currentMusicName ?: "None"
        val musicFont = getMusicNameFont() ?: return
        val textFont = getLyricTextFont() ?: return
        
        val displayLines = mutableListOf<Pair<String, Boolean>>()
        if (lyricShowMusicName && currentMusicName != "None") {
            displayLines.add(currentMusicName to true)
        }
        if (lyricShowPrevious && previousLyric.isNotEmpty()) {
            displayLines.add(previousLyric to false)
        }
        if (currentLyric.isNotEmpty()) {
            displayLines.add(currentLyric to false)
        }
        if (lyricShowNext && nextLyric.isNotEmpty()) {
            displayLines.add(nextLyric to false)
        }
        
        if (displayLines.isEmpty()) return

        val maxWidth = displayLines.maxOf { (line, isMusic) ->
            (if (isMusic) musicFont else textFont).getStringWidth(line) + 40F
        }.coerceIn(100F, lyricFullWidth.toFloat())

        val lineCount = displayLines.size
        val targetHeight = (lineCount * 18F + 16F + if (lyricShowProgress) 10F else 0F).coerceIn(30F, lyricFullHeight.toFloat())

        if (lyricBounce) {
            val (nextW, vW) = spring(animBubbleWidth, maxWidth, velBubbleWidth)
            animBubbleWidth = nextW.coerceIn(0F, 500F)
            velBubbleWidth = vW
            val (nextH, vH) = spring(animBubbleHeight, targetHeight, velBubbleHeight)
            animBubbleHeight = nextH.coerceIn(0F, 200F)
            velBubbleHeight = vH
            val (nextY, vY) = spring(animBubbleY, lyricFloatOffsetY.toFloat(), velBubbleY)
            animBubbleY = nextY
            velBubbleY = vY
            val (nextAlpha, vA) = spring(animBubbleAlpha, 1F, velBubbleAlpha)
            animBubbleAlpha = nextAlpha.coerceIn(0F, 1F)
            velBubbleAlpha = vA
        } else {
            animBubbleWidth = maxWidth
            animBubbleHeight = targetHeight
            animBubbleY = lyricFloatOffsetY.toFloat()
            animBubbleAlpha = 1F
        }
    }

    private fun calcIosWatermarkWidth(): Float {
        val fps = Minecraft.getDebugFPS()
        val fpsStr = "${fps}fps"
        val iconSize = 16F
        val padding = 10F
        val spacing = 8F
        val dotW = 6F
        val clientNameWidth = Fonts.fontSemibold35.getStringWidth(ClientName).toFloat()
        val fpsWidth = Fonts.fontSemibold35.getStringWidth(fpsStr).toFloat()
        return padding + iconSize + spacing + clientNameWidth + dotW + fpsWidth + padding
    }

    /**
     * Island6 style iOS Watermark: (icon) ClientName • 120fps
     * Minimal, pill-shaped, pure black
     */
    private fun renderIosWatermark(x: Float, y: Float, w: Float, h: Float) {
        val iconSize = 14F
        val spacing = 6F
        val sepStr = " \u2022 "
        val fpsStr = "${Minecraft.getDebugFPS()}fps"

        // Calculate total content width to center everything
        val iconW = iconSize
        val nameW = Fonts.fontSemibold35.getStringWidth(ClientName).toFloat()
        val sepW = Fonts.fontSemibold35.getStringWidth(sepStr).toFloat()
        val fpsW = Fonts.fontSemibold35.getStringWidth(fpsStr).toFloat()
        val totalContentW = iconW + spacing + nameW + sepW + fpsW

        // Center horizontally within the pill
        var cx = x + (w - totalContentW) / 2
        val centerY = y + h / 2f
        val textBaseY = centerY - Fonts.fontSemibold35.FONT_HEIGHT / 2f + 1f
        val iconY = centerY - iconSize / 2f

        // Logo icon (white on black)
        drawImage(getLogoResource(), cx.toInt(), iconY.toInt(), iconSize.toInt(), iconSize.toInt(), Color.WHITE)
        cx += iconSize + spacing

        // Client name
        Fonts.fontSemibold35.drawString(ClientName, cx, textBaseY, Color.WHITE.rgb)
        cx += nameW

        // Separator " • "
        Fonts.fontSemibold35.drawString(sepStr, cx, textBaseY, Color(140, 140, 140).rgb)
        cx += sepW

        // FPS
        Fonts.fontSemibold35.drawString(fpsStr, cx, textBaseY, Color.WHITE.rgb)
    }

    /**
     * Island6 style iOS Toggle notification: left green/red dot + module name + right [ON]/[OFF], with fade
     */
    private fun renderIosToggle(x: Float, y: Float, w: Float, h: Float) {
        // Only show the most recent toggle notification (override previous)
        val notify = notifications.lastOrNull() ?: return
        val notifyEntry = notify as? ToggleNotification ?: return

        // Calculate fade alpha based on remaining time
        val elapsed = System.currentTimeMillis() - notify.createTime
        val remaining = notify.duration - elapsed
        val fadeAlpha = if (remaining < 300) {
            (remaining.toFloat() / 300f).coerceIn(0f, 1f)
        } else 1f

        val padding = 14F
        val centerY = y + h / 2f
        val textBaseY = centerY - Fonts.fontSemibold35.FONT_HEIGHT / 2f + 1f
        var cx = x + padding

        // Left dot: use drawRoundedRect for reliable rendering
        val dotSize = 7F
        val dotX = cx
        val dotY = centerY - dotSize / 2f
        val dotColor = if (notifyEntry.enabled) {
            Color(76, 217, 100, (255 * fadeAlpha).toInt())
        } else {
            Color(255, 99, 71, (255 * fadeAlpha).toInt())
        }
        drawRoundedRect(dotX, dotY, dotX + dotSize, dotY + dotSize, dotColor.rgb, dotSize / 2f)
        cx += dotSize + 8F

        // Module name in white
        Fonts.fontSemibold35.drawString(notifyEntry.moduleName, cx, textBaseY,
            Color(255, 255, 255, (255 * fadeAlpha).toInt()).rgb)

        // Right side [ON] in green / [OFF] in red, right-aligned
        val stateText = if (notifyEntry.enabled) "[ON]" else "[OFF]"
        val stateColor = if (notifyEntry.enabled) {
            Color(76, 217, 100, (255 * fadeAlpha).toInt())
        } else {
            Color(255, 99, 71, (255 * fadeAlpha).toInt())
        }
        val stateWidth = Fonts.fontSemibold35.getStringWidth(stateText)
        Fonts.fontSemibold35.drawString(stateText, x + w - padding - stateWidth, textBaseY, stateColor.rgb)
    }

    /**
     * Apple 风格连续胶囊进度条：半透明轨道 + 圆角填充
     */
    private fun drawAppleBar(x: Float, y: Float, w: Float, h: Float, progress: Float, fillColor: Color) {
        drawRoundedRect(x, y, x + w, y + h, Color(255, 255, 255, 28).rgb, h / 2f)
        if (w * progress > 0.5f) {
            drawRoundedRect(x, y, x + w * progress, y + h, fillColor.rgb, h / 2f)
        }
    }

    /**
     * Island6 style iOS Scaffold: 剩余方块数量 + Apple 风格连续胶囊进度条
     */
    private fun renderIosScaffold(x: Float, y: Float, w: Float, h: Float) {
        val blockCount = hotbarBlockCount()
        val percentage = animatedScaffoldProgress.coerceIn(0f, 1f)

        val padding = 12F
        val centerY = y + h / 2f
        var cx = x + padding

        // 左侧：剩余方块数量，垂直居中
        val countStr = "$blockCount"
        val textBaseY = centerY - Fonts.fontSemibold35.FONT_HEIGHT / 2f + 1f
        Fonts.fontSemibold35.drawString(countStr, cx, textBaseY, Color.WHITE.rgb)
        cx += Fonts.fontSemibold35.getStringWidth(countStr).toFloat() + 10F

        // 右侧：连续胶囊进度条
        val barW = (x + w - padding) - cx
        val barH = 6F
        drawAppleBar(cx, centerY - barH / 2f, barW, barH, percentage, Color(255, 255, 255, 230))
    }

    /**
     * iOS Dynamic Island Music: small cover + song name + lyric + short white progress bar
     * Mimics the real iOS 16/17 Dynamic Island Now Playing style
     */
    private fun renderIosMusic(x: Float, y: Float, w: Float, h: Float) {
        val pad = 8f
        val musicName = MusicHub.currentMusicName
        val lyric = MusicHub.currentLyricDisplay
        val displayText = if (musicName != "None" && musicName != "无") musicName else lyric.take(30)
        val progress = MusicHub.progress.coerceIn(0f, 1f)

        // --- Cover icon (left side, vertically centered) ---
        val coverSize = 32f  // Larger
        val coverX = x + pad + 2f
        val coverY = y + (h - coverSize) / 2f  // Vertically centered
        val coverRadius = 6f

        // Dark rounded square background for cover
        drawRoundedRect(coverX, coverY, coverX + coverSize, coverY + coverSize,
            Color(30, 30, 30, 220).rgb, coverRadius)

        // 有音乐封面则铺满封面区块（圆角），否则回退音符图标
        val coverTexture = MusicCoverCache.get(MusicHub.currentCoverUrl)
        if (coverTexture != null) {
            GlStateManager.color(1f, 1f, 1f, 1f)
            drawImage(coverTexture, coverX, coverY, coverSize.toInt(), coverSize.toInt(),
                Color.WHITE, coverRadius)
        } else {
            // Music icon centered in cover
            val iconSz = (coverSize * 0.6f).toInt()
            val iconOffsetX = coverX + (coverSize - iconSz) / 2f
            val iconOffsetY = coverY + (coverSize - iconSz) / 2f
            drawImage(ResourceLocation("airplus/watermark_images/music.png"),
                iconOffsetX.toInt(), iconOffsetY.toInt(), iconSz, iconSz, Color.WHITE)
        }

        // --- Text area (right of cover) ---
        val textX = coverX + coverSize + 8f
        val textRightEdge = x + w - pad
        val maxTextW = textRightEdge - textX

        // Song name (top line, white, semibold)
        val displayName = truncateText(displayText, Fonts.fontSemibold35, maxTextW)
        val nameY = y + pad + 1f
        Fonts.fontSemibold35.drawString(displayName, textX, nameY, Color.WHITE.rgb)

        // Lyric / secondary info (below song name, lighter)
        val lyricLine = lyric.take(25)
        if (lyricLine.isNotEmpty()) {
            val lyricDisplay = truncateText(lyricLine, Fonts.fontRegular30, maxTextW)
            val lyricY = nameY + Fonts.fontSemibold35.FONT_HEIGHT + 1f
            Fonts.fontRegular30.drawString(lyricDisplay, textX, lyricY,
                Color(180, 180, 190, 200).rgb)
        }

        // --- Short white progress bar at the bottom, only under text area ---
        val barH = 3f  // Thicker
        val barY = y + h - barH - pad
        val barX = textX
        val barW = textRightEdge - textX

        // Background track
        drawRoundedRect(barX, barY, barX + barW, barY + barH,
            Color(255, 255, 255, 30).rgb, barH / 2)

        // Filled progress in white
        if (barW * progress > 0.5f) {
            drawRoundedRect(barX, barY, barX + barW * progress, barY + barH,
                Color(255, 255, 255, 180).rgb, barH / 2)
        }
    }

    /**
     * iOS style Chest: rounded pill with grid, subtle slot backgrounds, ripple effects
     * Matches the iOS pill aesthetic with smooth rounded slots
     */
    private fun renderIosChest(x: Float, y: Float, w: Float, h: Float, slots: List<Slot>) {
        val padding = 10f
        val slotSize = 18
        val slotPad = 1f
        val slotRadius = 4f

        // Draw subtle slot backgrounds
        for (i in slots.indices) {
            val col = i % 9
            val row = i / 9
            val slotX = x + padding + col * slotSize
            val slotY = y + padding + row * slotSize
            drawRoundedRect(slotX + slotPad, slotY + slotPad,
                slotX + slotSize - slotPad, slotY + slotSize - slotPad,
                Color(255, 255, 255, 20).rgb, slotRadius)
        }

        enableGUIStandardItemLighting()
        try {
            slots.forEachIndexed { index, slot ->
                val stack = slot.stack
                val col = index % 9
                val row = index / 9
                val itemX = (x + padding + col * slotSize).toInt()
                val itemY = (y + padding + row * slotSize).toInt()

                val prevStack = prevSlotItems[index]

                if (prevSlotItems.containsKey(index)) {
                    val isChanged = when {
                        stack == null && prevStack == null -> false
                        stack == null || prevStack == null -> true
                        else -> !ItemStack.areItemStacksEqual(stack, prevStack) || stack.stackSize != prevStack.stackSize
                    }
                    if (isChanged) {
                        slotRipples.add(SlotRipple((itemX + slotSize / 2).toFloat(), (itemY + slotSize / 2).toFloat(), System.currentTimeMillis()))
                    }
                }
                prevSlotItems[index] = stack?.copy()

                if (stack != null) {
                    mc.renderItem.renderItemAndEffectIntoGUI(stack, itemX + 1, itemY + 1)
                    mc.renderItem.renderItemOverlays(mc.fontRendererObj, stack, itemX + 1, itemY + 1)
                }
            }

            disableStandardItemLighting()
            GlStateManager.disableDepth()

            // Ripple effects
            val currentTime = System.currentTimeMillis()
            val iterator = slotRipples.iterator()
            while (iterator.hasNext()) {
                val ripple = iterator.next()
                val timeAlive = currentTime - ripple.startTime
                if (timeAlive > 600L) {
                    slotRipples.remove(ripple)
                } else {
                    val progress = timeAlive.toFloat() / 600f
                    val ease = 1f - (1f - progress).pow(3)
                    val radius = 18f * ease
                    val alpha = (150 * (1f - progress)).toInt().coerceIn(0, 255)
                    if (alpha > 0) drawCircle(ripple.x, ripple.y, radius, Color(255, 255, 255, alpha))
                }
            }
            GlStateManager.enableDepth()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            disableStandardItemLighting()
            GlStateManager.enableAlpha()
            GlStateManager.disableBlend()
            GlStateManager.disableLighting()
        }
    }

    private fun truncateText(text: String, font: net.airplus.ui.font.GameFontRenderer, maxWidth: Float): String {
        if (maxWidth <= 0f) return ""
        if (font.getStringWidth(text).toFloat() <= maxWidth) return text
        var truncated = text
        while (font.getStringWidth("$truncated\u2026").toFloat() > maxWidth && truncated.isNotEmpty()) {
            truncated = truncated.dropLast(1)
        }
        return if (truncated.isNotEmpty()) "$truncated\u2026" else ""
    }

    // ============================================================
    // iOS style Gapple + TabList
    // ============================================================

    private fun renderIosGapple(x: Float, y: Float, w: Float, h: Float) {
        val percentage = animatedGappleProgress.coerceIn(0f, 1f)
        val padding = 12F
        val centerY = y + h / 2f
        var cx = x + padding

        // Percentage text on the left
        val pctStr = String.format("%.0f%%", percentage * 100f)
        val textBaseY = centerY - Fonts.fontSemibold35.FONT_HEIGHT / 2f + 1f
        Fonts.fontSemibold35.drawString(pctStr, cx, textBaseY, Color.WHITE.rgb)
        cx += Fonts.fontSemibold35.getStringWidth(pctStr).toFloat() + 10F

        // Apple 风格连续胶囊进度条
        val barW = (x + w - padding) - cx
        val barH = 6F
        val fillColor = Color(gappleProgressTheme.red, gappleProgressTheme.green, gappleProgressTheme.blue, 230)
        drawAppleBar(cx, centerY - barH / 2f, barW, barH, percentage, fillColor)
    }

    private fun renderIosTabList(x: Float, y: Float, w: Float, h: Float, players: List<NetworkPlayerInfo>, header: List<String>, footer: List<String>) {
        renderTabListContent(x, y, w, h, players, header, footer)
    }

    // ============================================================
    // Prism 新样式
    // ============================================================

    val isNewStyle: Boolean
        get() = style == "prism"

    /** 新样式背景色（含 alpha 透明度） */
    private fun newBackgroundColor(mode: String): Color = prismBackground

    private data class NewTheme(
        val text: Color,
        val sub: Color,
        val accent: Color,
        val track: Color,
        val border: Color,
        val light: Boolean
    )

    private fun newTheme(): NewTheme = NewTheme(
        text = Color(255, 255, 255), sub = Color(139, 150, 163),
        accent = prismAccent, track = Color(255, 255, 255, 24),
        border = Color(255, 255, 255, 16), light = false
    )

    private fun newGreen(th: NewTheme, alpha: Int = 255) =
        if (th.light) Color(63, 158, 108, alpha) else Color(105, 201, 138, alpha)

    private fun newRed(alpha: Int = 255) = Color(235, 95, 95, alpha)

    private fun hotbarBlockCount(): Int = (0..8).sumOf { slotIndex ->
        val stack = mc.thePlayer?.inventory?.getStackInSlot(slotIndex)
        if (stack != null && stack.item is ItemBlock) stack.stackSize else 0
    }

    /** 物品栏（快捷栏 + 背包）内可用方块总数 */
    private fun inventoryBlockCount(): Int = (0..35).sumOf { slotIndex ->
        val stack = mc.thePlayer?.inventory?.getStackInSlot(slotIndex)
        if (stack != null && stack.item is ItemBlock) stack.stackSize else 0
    }

    /** Scaffold 进度：当前物品栏可用方块数 / 启用 Scaffold 时的快照最大值 */
    private fun scaffoldProgress(): Float {
        val count = inventoryBlockCount()
        if (scaffoldMaxSnapshot <= 0) return if (count > 0) 1f else 0f
        return (count.toFloat() / scaffoldMaxSnapshot.toFloat()).coerceIn(0f, 1f)
    }

    private const val NEW_NOTIFY_ROW = 34F

    // ---------- 水印 ----------

    private fun calcNewWatermarkWidth(): Float {
        val pingStr = "${getSafePing()}ms"
        val fpsStr = "${Minecraft.getDebugFPS()}fps"
        val ipStr = if (customip) ip else ServerUtils.remoteIp ?: "SinglePlayer"
        val nameW = Fonts.fontSemibold35.getStringWidth(ClientName).toFloat()
        val pingW = Fonts.fontRegular30.getStringWidth(pingStr).toFloat()
        val fpsW = Fonts.fontRegular30.getStringWidth(fpsStr).toFloat()
        val ipW = Fonts.fontRegular30.getStringWidth(ipStr).toFloat()
        // 8 + 图标芯片20 + 7 + 名称 + 8 + 竖线1 + 8 + 状态点5 + 4 + 延迟 + 8 + FPS + 8 + IP + 10
        return 8F + 20F + 7F + nameW + 8F + 1F + 8F + 5F + 4F + pingW + 8F + fpsW + 8F + ipW + 10F
    }

    private fun renderNewWatermark(x: Float, y: Float, w: Float, h: Float) {
        val th = newTheme()
        val centerY = y + h / 2f
        var cx = x + 8F

        // 左侧 App 图标风格芯片：accent 低透明度圆角方块 + 居中 logo
        val chip = 20F
        drawRoundedRect(cx, centerY - chip / 2f, cx + chip, centerY + chip / 2f,
            Color(th.accent.red, th.accent.green, th.accent.blue, 36).rgb, 6F)
        val logoSize = 13F
        drawImage(getLogoResource(), (cx + (chip - logoSize) / 2f).toInt(), (centerY - logoSize / 2f).toInt(),
            logoSize.toInt(), logoSize.toInt(), Color.WHITE)
        cx += chip + 7F

        val nameBaseY = centerY - Fonts.fontSemibold35.FONT_HEIGHT / 2f + 1f
        Fonts.fontSemibold35.drawString(ClientName, cx, nameBaseY, th.text.rgb)
        cx += Fonts.fontSemibold35.getStringWidth(ClientName).toFloat() + 8F

        // 细竖线分隔
        drawRoundedRect(cx, centerY - 6f, cx + 1f, centerY + 6f, Color(255, 255, 255, 42).rgb, 0.5F)
        cx += 1F + 8F

        // 延迟：状态圆点（按延迟分级变色）+ 数值
        val ping = getSafePing()
        val pingColor = when {
            ping < 100 -> newGreen(th)
            ping < 200 -> Color(245, 185, 66)
            else -> newRed()
        }
        drawCircle(cx + 2.5f, centerY, 2.5f, pingColor)
        cx += 5F + 4F

        val subBaseY = centerY - Fonts.fontRegular30.FONT_HEIGHT / 2f + 1f
        val pingStr = "${ping}ms"
        Fonts.fontRegular30.drawString(pingStr, cx, subBaseY, th.text.rgb)
        cx += Fonts.fontRegular30.getStringWidth(pingStr).toFloat() + 8F

        val fpsStr = "${Minecraft.getDebugFPS()}fps"
        Fonts.fontRegular30.drawString(fpsStr, cx, subBaseY, th.text.rgb)
        cx += Fonts.fontRegular30.getStringWidth(fpsStr).toFloat() + 8F

        val ipStr = if (customip) ip else ServerUtils.remoteIp ?: "SinglePlayer"
        Fonts.fontRegular30.drawString(ipStr, cx, subBaseY, th.sub.rgb)
    }

    // ---------- 模块开关通知（滑动入场 + 多条堆叠） ----------

    private fun calcNewNotifySize(): Pair<Float, Float> {
        var maxTextW = 80F
        for (n in notifications) {
            val t = n as? ToggleNotification ?: continue
            val nameW = Fonts.fontSemibold35.getStringWidth(t.moduleName).toFloat()
            val stateW = Fonts.fontRegular30.getStringWidth(if (t.enabled) "已启用" else "已禁用").toFloat()
            maxTextW = max(maxTextW, max(nameW, stateW))
        }
        val w = (12F + 30F + 10F + maxTextW + 12F).coerceAtLeast(170F)
        return Pair(w, notifications.size * NEW_NOTIFY_ROW + 8F)
    }

    private fun renderNewNotify(x: Float, y: Float, w: Float, h: Float) {
        val th = newTheme()
        val now = System.currentTimeMillis()
        var rowY = y + 4F
        for (n in notifications) {
            val t = n as? ToggleNotification ?: continue
            // 入场：从上方滑落 + 淡入（仅首次创建时播放，重复开关同模块不重播）
            val slide = ((now - n.slideTime) / 350f).coerceIn(0f, 1f)
            val e = easeOutCubic(slide)
            val centerY = rowY + NEW_NOTIFY_ROW / 2f - (1f - e) * NEW_NOTIFY_ROW * 0.6f
            val alpha = (255 * e).toInt()

            drawNewToggleButton(x + 12F, centerY, t.enabled, t.anim, th, alpha)

            val textX = x + 12F + 30F + 10F
            val fhName = Fonts.fontSemibold35.FONT_HEIGHT.toFloat()
            val fhState = Fonts.fontRegular30.FONT_HEIGHT.toFloat()
            val totalH = fhName + 2F + fhState
            val nameY = centerY - totalH / 2f
            Fonts.fontSemibold35.drawString(t.moduleName, textX, nameY,
                Color(th.text.red, th.text.green, th.text.blue, alpha).rgb)
            val stateStr = if (t.enabled) "已启用" else "已禁用"
            Fonts.fontRegular30.drawString(stateStr, textX, nameY + fhName + 2F,
                (if (t.enabled) newGreen(th, alpha) else newRed(alpha)).rgb)
            rowY += NEW_NOTIFY_ROW
        }
    }

    private fun drawNewToggleButton(x: Float, centerY: Float, enabled: Boolean, anim: SwitchAnimationState, th: NewTheme, alpha: Int) {
        val btnH = 19F
        val btnW = 30F
        val margin = 3F
        val btnY = centerY - btnH / 2f
        anim.updateState(enabled)
        val a = anim.getOutput().toFloat()
        val track = if (enabled) newGreen(th, alpha)
            else if (th.light) Color(150, 160, 172, alpha) else Color(58, 65, 75, alpha)
        drawRoundedRect(x, btnY, x + btnW, btnY + btnH, track.rgb, btnH / 2f)
        val knobSize = btnH - margin * 2
        val knobX = x + margin + (btnW - margin * 2 - knobSize) * a
        drawRoundedRect(knobX, btnY + margin, knobX + knobSize, btnY + margin + knobSize,
            Color(255, 255, 255, alpha).rgb, knobSize / 2f)
    }

    // ---------- TabList ----------

    private fun renderNewTabList(x: Float, y: Float, w: Float, h: Float, players: List<NetworkPlayerInfo>, header: List<String>, footer: List<String>) {
        val th = newTheme()
        val maxRows = tabListMaxRows
        val outerPadding = 8F

        var currentY = y + outerPadding
        val centerX = x + w / 2F

        if (header.isNotEmpty()) {
            for (line in header) {
                Fonts.fontRegular35.drawCenteredString(line, centerX, currentY, th.text.rgb)
                currentY += Fonts.fontRegular35.FONT_HEIGHT + 2
            }
            currentY += 2
        }

        if (players.isNotEmpty()) {
            var maxNameWidth = 50F
            players.forEach {
                val fullName = mc.ingameGUI.tabList.getPlayerName(it)
                val wName = Fonts.fontRegular35.getStringWidth(fullName).toFloat()
                if (wName > maxNameWidth) maxNameWidth = wName
            }
            val headSize = 10F
            val padding = 6F
            val spacing = 4F
            val itemHeight = headSize + spacing
            val colWidth = padding + headSize + spacing + maxNameWidth + spacing + 25F + padding
            val totalCols = ceil(players.size.toDouble() / maxRows.toDouble()).toInt()

            val playersTotalWidth = totalCols * colWidth
            var startX = centerX - playersTotalWidth / 2F
            val columnY = currentY

            for (i in players.indices) {
                val player = players[i]
                if (i > 0 && i % maxRows == 0) {
                    startX += colWidth
                    currentY = columnY
                }
                val rowX = startX + padding

                mc.textureManager.bindTexture(player.locationSkin)
                GlStateManager.color(1f, 1f, 1f, 1f)
                Gui.drawScaledCustomSizeModalRect(rowX.toInt(), currentY.toInt(), 8f, 8f, 8, 8, headSize.toInt(), headSize.toInt(), 64f, 64f)

                val fullName = mc.ingameGUI.tabList.getPlayerName(player)
                Fonts.fontRegular35.drawString(fullName, rowX + headSize + spacing, currentY + 1.5F, th.text.rgb)

                val ping = player.responseTime
                val pingColor = when {
                    ping < 0 -> Color(50, 50, 50)
                    ping < 100 -> Color(100, 255, 100)
                    ping < 200 -> Color(255, 200, 50)
                    else -> Color(255, 80, 80)
                }
                val pingText = "${ping}ms"
                val pingW = Fonts.fontRegular35.getStringWidth(pingText).toFloat()
                Fonts.fontRegular35.drawString(pingText, startX + colWidth - padding - pingW, currentY + 1.5F, pingColor.rgb)

                currentY += itemHeight
            }

            val actualRows = if (totalCols == 1) players.size else maxRows
            currentY = columnY + actualRows * itemHeight + 2
        }

        if (footer.isNotEmpty()) {
            for (line in footer) {
                Fonts.fontRegular35.drawCenteredString(line, centerX, currentY, th.text.rgb)
                currentY += Fonts.fontRegular35.FONT_HEIGHT + 2
            }
        }
    }

    // ---------- 音乐 ----------

    private fun renderNewMusic(x: Float, y: Float, w: Float, h: Float) {
        val th = newTheme()
        val pad = 10f
        val musicName = MusicHub.currentMusicName
        val lyric = MusicHub.currentLyricDisplay
        val displayText = if (musicName != "None" && musicName != "无") musicName else lyric.take(30)
        val progress = MusicHub.progress.coerceIn(0f, 1f)

        // 封面：大圆角方形，垂直居中；无封面时 accent 底色 + 音符图标
        val coverSize = 38f
        val coverX = x + pad
        val coverY = y + (h - coverSize) / 2f
        val coverRadius = 10f

        drawRoundedRect(coverX, coverY, coverX + coverSize, coverY + coverSize,
            Color(th.accent.red, th.accent.green, th.accent.blue, 32).rgb, coverRadius)

        val coverTexture = MusicCoverCache.get(MusicHub.currentCoverUrl)
        if (coverTexture != null) {
            GlStateManager.color(1f, 1f, 1f, 1f)
            drawImage(coverTexture, coverX, coverY, coverSize.toInt(), coverSize.toInt(), Color.WHITE, coverRadius)
        } else {
            val iconSz = (coverSize * 0.55f).toInt()
            drawImage(ResourceLocation("airplus/watermark_images/music.png"),
                (coverX + (coverSize - iconSz) / 2f).toInt(), (coverY + (coverSize - iconSz) / 2f).toInt(), iconSz, iconSz, th.accent)
        }

        val textX = coverX + coverSize + 10f
        val textRightEdge = x + w - pad
        val maxTextW = textRightEdge - textX

        // 歌名 + 右侧进度百分比（Apple Music 迷你播放器布局）
        val nameY = y + 9f
        val pctStr = String.format("%.0f%%", progress * 100f)
        val pctW = Fonts.fontRegular30.getStringWidth(pctStr).toFloat()
        val nameMaxW = (maxTextW - pctW - 8f).coerceAtLeast(40f)
        val displayName = truncateText(displayText, Fonts.fontSemibold35, nameMaxW)
        Fonts.fontSemibold35.drawString(displayName, textX, nameY, th.text.rgb)
        Fonts.fontRegular30.drawString(pctStr, textRightEdge - pctW, nameY + 1f, th.accent.rgb)

        // 歌词行
        val lyricLine = lyric.take(25)
        if (lyricLine.isNotEmpty()) {
            val lyricDisplay = truncateText(lyricLine, Fonts.fontRegular30, maxTextW)
            Fonts.fontRegular30.drawString(lyricDisplay, textX, nameY + Fonts.fontSemibold35.FONT_HEIGHT + 2f, th.sub.rgb)
        }

        // 进度条：细圆角条贯穿文本区，accent 填充
        val barH = 3f
        val barY = y + h - barH - 9f
        val barW = textRightEdge - textX
        drawRoundedRect(textX, barY, textX + barW, barY + barH, th.track.rgb, barH / 2)
        if (barW * progress > 0.5f) {
            drawRoundedRect(textX, barY, textX + barW * progress, barY + barH, th.accent.rgb, barH / 2)
        }
    }

    // ---------- 珍珠倒计时 ----------

    private fun calcNewPearlSize(): Pair<Float, Float> {
        val labelW = Fonts.fontRegular30.getStringWidth("珍珠").toFloat()
        val valStr = String.format("%.1fs", pearlTicksRemaining.coerceAtLeast(0f) * 50f / 1000f)
        val valW = Fonts.fontSemibold35.getStringWidth(valStr).toFloat()
        return Pair((12F + labelW + 6F + valW + 12F + 110F + 12F).coerceAtLeast(190F), 34F)
    }

    private fun renderNewPearl(x: Float, y: Float, w: Float, h: Float) {
        val th = newTheme()
        val total = pearlLandingTicks.coerceAtLeast(1)
        val remaining = pearlTicksRemaining.coerceIn(0f, total.toFloat())
        val pad = 12F
        val centerY = y + h / 2f
        var cx = x + pad

        val labelBaseY = centerY - Fonts.fontRegular30.FONT_HEIGHT / 2f + 1f
        val valBaseY = centerY - Fonts.fontSemibold35.FONT_HEIGHT / 2f + 1f
        Fonts.fontRegular30.drawString("珍珠", cx, labelBaseY, th.sub.rgb)
        cx += Fonts.fontRegular30.getStringWidth("珍珠").toFloat() + 6F
        val valStr = String.format("%.1fs", remaining * 50f / 1000f)
        Fonts.fontSemibold35.drawString(valStr, cx, valBaseY, th.accent.rgb)
        cx += Fonts.fontSemibold35.getStringWidth(valStr).toFloat() + 12F

        // 10 段分段条，随落地倒计时逐渐消耗
        val barW = (x + w - pad) - cx
        val gap = 2F
        val segCount = 10
        val segW = ((barW - gap * (segCount - 1)) / segCount).coerceAtLeast(3F)
        val barH = 8F
        val barY = centerY - barH / 2f
        val filled = ((remaining / total) * segCount + 0.5f).toInt().coerceIn(0, segCount)
        for (i in 0 until segCount) {
            val sx = cx + i * (segW + gap)
            val col = if (i < filled) th.accent else th.track
            drawRoundedRect(sx, barY, sx + segW, barY + barH, col.rgb, 2F)
        }
    }

    // ---------- Gapple ----------

    private fun renderNewGapple(x: Float, y: Float, w: Float, h: Float) {
        val th = newTheme()
        val percentage = animatedGappleProgress.coerceIn(0f, 1f)
        val pad = 12F
        val centerY = y + h / 2f
        var cx = x + pad

        val pctStr = String.format("%.0f%%", percentage * 100f)
        Fonts.fontSemibold35.drawString(pctStr, cx, centerY - Fonts.fontSemibold35.FONT_HEIGHT / 2f + 1f, th.text.rgb)
        cx += Fonts.fontSemibold35.getStringWidth(pctStr).toFloat() + 10F

        val barW = (x + w - pad) - cx
        val gap = 2F
        val segCount = 10
        val segW = ((barW - gap * (segCount - 1)) / segCount).coerceAtLeast(3F)
        val barH = 8F
        val barY = centerY - barH / 2f
        val filledSegs = (percentage * segCount).toInt().coerceIn(0, segCount)
        val fill = Color(gappleProgressTheme.red, gappleProgressTheme.green, gappleProgressTheme.blue, 230)
        for (i in 0 until segCount) {
            val sx = cx + i * (segW + gap)
            val col = if (i < filledSegs) fill else th.track
            drawRoundedRect(sx, barY, sx + segW, barY + barH, col.rgb, 2F)
        }
    }

    // ---------- Scaffold（圆环包裹使用的方块） ----------

    private fun calcNewScaffoldSize(): Pair<Float, Float> {
        // Prism：圆环包裹使用的方块图标，图标下方为剩余数量，圆环显示百分比
        return Pair(68F, 64F)
    }

    private fun renderNewScaffold(x: Float, y: Float, w: Float, h: Float) {
        val th = newTheme()
        val count = inventoryBlockCount()
        val percentage = scaffoldProgress()

        val ringR = 20F
        val ringCX = x + w / 2f
        val ringCY = y + h / 2f

        GlStateManager.pushMatrix()
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO)
        glEnable(GL_LINE_SMOOTH)
        glHint(GL_LINE_SMOOTH_HINT, GL_NICEST)

        // 轨道
        glColor4f(1f, 1f, 1f, 0.12f)
        glLineWidth(4f)
        glBegin(GL_LINE_LOOP)
        for (i in 0..360) {
            val theta = i * Math.PI / 180
            glVertex2d(ringCX + ringR * cos(theta), ringCY + ringR * sin(theta))
        }
        glEnd()

        // 进度弧：从顶部顺时针展开，弧长即百分比
        glColor4f(th.accent.red / 255f, th.accent.green / 255f, th.accent.blue / 255f, 1f)
        glBegin(GL_LINE_STRIP)
        val drawSegments = (360 * percentage).toInt()
        for (i in 0..drawSegments) {
            val theta = (i - 90) * Math.PI / 180
            glVertex2d(ringCX + ringR * cos(theta), ringCY + ringR * sin(theta))
        }
        glEnd()

        glDisable(GL_LINE_SMOOTH)
        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
        GlStateManager.popMatrix()

        // 环中上方：当前使用的方块图标（快捷栏内第一个方块）
        var blockStack: ItemStack? = null
        for (i in 0..8) {
            val s = mc.thePlayer?.inventory?.getStackInSlot(i)
            if (s != null && s.item is ItemBlock) {
                blockStack = s
                break
            }
        }
        if (blockStack != null) {
            glPushMatrix()
            enableGUIStandardItemLighting()
            try {
                mc.renderItem.renderItemAndEffectIntoGUI(blockStack, (ringCX - 8f).toInt(), (ringCY - 14f).toInt())
            } catch (_: Exception) {
            }
            disableStandardItemLighting()
            GlStateManager.enableAlpha()
            GlStateManager.disableBlend()
            GlStateManager.disableLighting()
            glPopMatrix()
        }

        // 图标下方：剩余方块数量
        Fonts.fontRegular30.drawCenteredString("$count", ringCX, ringCY + 5F, th.text.rgb)
    }

    // ============================================================
    // Opai 样式（移植自 Samsara DynamicIsland，几何 / 弹簧 / 配色常量一比一对照）
    // ============================================================

    // ---- DynamicIslandState 几何常量 ----
    private const val OPAI_TOP = 15f               // 展开面板顶部（scaffold / lowHealth 等进度面板）
    private const val OPAI_IDLE_TOP = 21f          // 待机胶囊顶部（通知行 / tablist / chest 面板）
    private const val OPAI_IDLE_HEIGHT = 23f
    private const val OPAI_ROW_HEIGHT = 36f        // 通知行高
    private const val OPAI_PANEL_HEIGHT = 50f      // 进度面板高（SCAFFOLD_HEIGHT）
    private const val OPAI_TEXT_X = 36f            // NOTICE_TEXT_X / SCAFFOLD_TEXT_X
    private const val OPAI_NOTICE_RIGHT = 5.5f     // NOTICE_RIGHT_PADDING
    private const val OPAI_PANEL_RIGHT = 8f        // SCAFFOLD_RIGHT_PADDING
    private const val OPAI_MAX_NOTICES = 3
    private const val OPAI_TABLIST_ROW = 20f
    private const val OPAI_PANEL_W = 190f          // chest / tablist 面板宽度
    private const val OPAI_ICON_SIZE = 11f         // 待机行图标（Samsara ICON_SIZE 10.5 的整数化）
    private const val OPAI_ICON_GAP = 3.5f
    // ---- DynamicIslandState.Morph 弹簧参数 ----
    private const val OPAI_MORPH_DECAY = 8.7
    private const val OPAI_MORPH_FREQUENCY = 8.0

    // ---- 配色（OpaiStyle.LAVENDER / DynamicIslandStatus / DynamicIslandPainter）----
    private val opaiForeground = Color(228, 225, 230)      // 0xFFE4E1E6
    private val opaiEnabled = Color(85, 255, 85)           // ENABLED
    private val opaiDisabled = Color(255, 85, 85)          // DISABLED
    private val opaiTileBg = Color(20, 22, 22, 200)        // 进度面板瓦片 0xC8141616
    private val opaiBarTrack = Color(52, 54, 54)           // 进度条轨道 0xFF343636
    private val opaiProgressCol = Color(156, 165, 218)     // hudProgress 0xFF9CA5DA
    private val opaiPingOnline = Color(128, 200, 16)       // 0xFF80C810
    private val opaiPingOrange = Color(200, 125, 40)       // 0xFFC87D28
    private val opaiPingRed = Color(202, 62, 46)           // 0xFFCA3E2E
    private val opaiToggleOutline = Color(147, 143, 153)   // toggleOutline
    private val opaiToggleTrack = Color(54, 52, 59)        // 开关内轨 0xFF36343B
    private val opaiToggleKnob = Color(133, 132, 136)      // 旋钮 0xFF858488
    private val opaiKnobAccent = Color(91, 95, 124)        // hudKnob 0xFF5B5F7C

    /** Samsara Morph 解析阻尼弹簧：任意帧率一致、打断保留速度（时间差在 Double 域计算后才转 Float） */
    private class OpaiMorph {
        private var current = 0.0
        private var target = 0.0
        private var velocity = 0.0
        private var sampled = -1L

        fun value(destination: Float, now: Long, impulse: Double, speed: Float): Float {
            if (sampled < 0L) {
                current = destination.toDouble()
                target = current
                velocity = 0.0
            } else {
                val seconds = (now - sampled).coerceAtLeast(0L) / 1000.0 * speed
                val displacement = current - target
                val sineCoefficient = (velocity + OPAI_MORPH_DECAY * displacement) / OPAI_MORPH_FREQUENCY
                val cosine = cos(OPAI_MORPH_FREQUENCY * seconds)
                val sine = sin(OPAI_MORPH_FREQUENCY * seconds)
                val decay = exp(-OPAI_MORPH_DECAY * seconds)
                val offset = displacement * cosine + sineCoefficient * sine
                current = target + decay * offset
                velocity = decay * (-OPAI_MORPH_DECAY * offset +
                        OPAI_MORPH_FREQUENCY * (-displacement * sine + sineCoefficient * cosine))
                if (destination.toDouble() != target) {
                    target = destination.toDouble()
                    // 静止起步立即响应；反向打断保留弹簧真实速度
                    if (abs(velocity) < max(0.1, abs(target - current) * 0.1)) {
                        velocity = (target - current) * impulse
                    }
                }
                if (abs(current - target) < 0.01 && abs(velocity) < 0.1) {
                    current = target
                    velocity = 0.0
                }
            }
            sampled = now
            return current.toFloat()
        }
    }

    private val opaiWidthMorph = OpaiMorph()
    private val opaiHeightMorph = OpaiMorph()
    private val opaiTopMorph = OpaiMorph()
    private var opaiWasCompact = true     // 上一帧是否待机（冲量判定）
    private var opaiWasPanel = false      // 上一帧是否面板态（tablist / chest）
    private var opaiNotifyTextW = 0f      // 通知行文本宽度记忆：morph 过程中不重新截断（对照 notice.contentWidth）

    private fun opaiMix(from: Color, to: Color, amount: Float): Color = Color(
        (from.red + (to.red - from.red) * amount).toInt().coerceIn(0, 255),
        (from.green + (to.green - from.green) * amount).toInt().coerceIn(0, 255),
        (from.blue + (to.blue - from.blue) * amount).toInt().coerceIn(0, 255)
    )

    private fun opaiFade(c: Color, opacity: Float): Color =
        Color(c.red, c.green, c.blue, (c.alpha * opacity).toInt().coerceIn(0, 255))

    private fun opaiSep(measure: net.airplus.ui.font.GameFontRenderer): Float =
        measure.getStringWidth("  ·  ") + 2f

    private fun opaiPingText(ping: Int): String = if (ping < 0) "...ms" else "${ping}ms"

    private fun opaiPingColor(ping: Int): Color = when {
        ping < 0 -> opaiForeground
        ping < 100 -> opaiPingOnline
        ping <= 400 -> opaiPingOrange
        else -> opaiPingRed
    }

    /** 信号格数：<0 无信号、<100 满 4 格、<=250 3 格、<=400 2 格、其余 1 格 */
    private fun opaiPingTier(ping: Int): Int = when {
        ping < 0 -> 0
        ping < 100 -> 4
        ping <= 250 -> 3
        ping <= 400 -> 2
        else -> 1
    }

    private fun opaiServerLabel(singleplayer: Boolean): String =
        if (singleplayer) "singleplayer" else (mc.currentServerData?.serverIP ?: "multiplayer")

    /** 进度面板宽度（对照 Samsara：SCAFFOLD_TEXT_X + max(标题, 详情) + SCAFFOLD_RIGHT_PADDING） */
    private fun calcOpaiPanelWidth(title: String, detail: String): Float {
        val textW = max(
            Fonts.fontSemibold35.getStringWidth(title).toFloat(),
            Fonts.fontRegular30.getStringWidth(detail).toFloat())
        return (OPAI_TEXT_X + textW + OPAI_PANEL_RIGHT).coerceAtLeast(80f)
    }

    // ---- 待机胶囊（对照 DynamicIslandStatus.layout：logo · 用户 · 网络 · fps）----

    private fun calcOpaiIdleWidth(): Float {
        val measure = Fonts.fontRegular30
        var x = 9f                                    // INSET
        x += OPAI_ICON_SIZE + OPAI_ICON_GAP           // 客户端 logo
        x += measure.getStringWidth(ClientName)
        if (opaiStatusBar) {
            x += opaiSep(measure)
            x += OPAI_ICON_SIZE + OPAI_ICON_GAP       // 玩家
            x += measure.getStringWidth(mc.session.username)
            x += opaiSep(measure)
            x += OPAI_ICON_SIZE + OPAI_ICON_GAP       // 网络
            val singleplayer = mc.isSingleplayer
            if (!singleplayer) {
                x += measure.getStringWidth(opaiPingText(getSafePing()))
                x += measure.getStringWidth(" to ")
            }
            x += measure.getStringWidth(opaiServerLabel(singleplayer))
        }
        x += opaiSep(measure)
        x += OPAI_ICON_SIZE + OPAI_ICON_GAP           // fps
        x += measure.getStringWidth("${Minecraft.getDebugFPS()} fps")
        return x + 12.5f                              // RIGHT_INSET
    }

    private fun renderOpaiIdle(x: Float, y: Float, w: Float, h: Float) {
        val measure = Fonts.fontRegular30
        val centerY = y + OPAI_IDLE_HEIGHT / 2f
        val textBaseY = centerY - measure.FONT_HEIGHT / 2f
        val iconY = centerY - OPAI_ICON_SIZE / 2f
        var cx = x + 9f

        fun icon(loc: ResourceLocation, color: Color) {
            drawImage(loc, cx.toInt(), iconY.toInt(), OPAI_ICON_SIZE.toInt(), OPAI_ICON_SIZE.toInt(), color)
            cx += OPAI_ICON_SIZE + OPAI_ICON_GAP
        }
        fun label(text: String, color: Color) {
            measure.drawString(text, cx, textBaseY, color.rgb)
            cx += measure.getStringWidth(text)
        }
        fun separator() { cx += opaiSep(measure) }

        icon(getLogoResource(), opaiAccent)
        label(ClientName, opaiAccent)
        if (opaiStatusBar) {
            separator()
            icon(getUserResource(), opaiForeground)
            label(mc.session.username, opaiForeground)
            separator()
            val ping = getSafePing()
            val singleplayer = mc.isSingleplayer
            icon(getPingResource(), opaiPingColor(ping))
            if (!singleplayer) {
                label(opaiPingText(ping), opaiPingColor(ping))
                label(" to ", opaiForeground)
            }
            label(opaiServerLabel(singleplayer), opaiForeground)
        }
        separator()
        icon(ResourceLocation("airplus/watermark_images/fps.png"), opaiForeground)
        label("${Minecraft.getDebugFPS()} fps", opaiForeground)
    }

    // ---- 通知行（对照 drawRow + drawIcon TOGGLE，最多 3 行，行高 36）----

    private fun opaiNotifyContentWidth(): Float {
        if (notifications.isEmpty()) return 0f
        val bangW = Fonts.fontRegular30.getStringWidth("!").toFloat()
        var maxW = 0f
        for (n in notifications.takeLast(OPAI_MAX_NOTICES)) {
            val titleW = Fonts.fontSemibold35.getStringWidth(n.title).toFloat()
            val detailW = Fonts.fontRegular30.getStringWidth("${n.moduleName} has been").toFloat() +
                    Fonts.fontRegular30.getStringWidth(if (n.enabled) "Enabled" else "Disabled").toFloat() + bangW
            maxW = max(maxW, max(titleW, detailW))
        }
        return maxW
    }

    private fun renderOpaiNotices(x: Float, y: Float, w: Float, h: Float) {
        val rows = notifications.takeLast(OPAI_MAX_NOTICES)
        if (rows.isEmpty()) {
            opaiNotifyTextW = 0f
            return
        }
        opaiNotifyTextW = max(opaiNotifyTextW, opaiNotifyContentWidth())
        val contentW = max(w, opaiNotifyTextW + OPAI_TEXT_X + OPAI_NOTICE_RIGHT)
        val now = System.currentTimeMillis()
        var rowY = y
        for (n in rows) {
            n.anim.updateState(n.enabled)
            val t = n.anim.getOutput().toFloat()
            // 淡出透明度（对照 Samsara FADE_MS = 200）
            val remaining = n.duration - (now - n.createTime)
            val opacity = (remaining / 200f).coerceIn(0f, 1f)
            renderOpaiToggleRow(x, rowY, contentW, n, t, opacity)
            rowY += OPAI_ROW_HEIGHT
        }
    }

    private fun renderOpaiToggleRow(x: Float, y: Float, rowW: Float, n: ToggleNotification, t: Float, opacity: Float) {
        val accent = opaiAccent
        // 开关图标：轨道 26x16@(5,10) / 内轨 24x14@(6,11) / 旋钮 8+3.5t（Samsara drawIcon TOGGLE）
        val outline = opaiMix(opaiToggleOutline, accent, t)
        val track = opaiMix(opaiToggleTrack, accent, t)
        val knob = opaiMix(opaiToggleKnob, opaiKnobAccent, t)
        drawRoundedRect(x + 5f, y + 10f, x + 31f, y + 26f, opaiFade(outline, opacity).rgb, 8f)
        drawRoundedRect(x + 6f, y + 11f, x + 30f, y + 25f, opaiFade(track, opacity).rgb, 7f)
        val knobSize = 8f + 3.5f * t
        val knobX = x + 8.5f + 8.5f * t
        val knobY = y + 13.5f - 1.25f * t
        drawRoundedRect(knobX, knobY, knobX + knobSize, knobY + knobSize, opaiFade(knob, opacity).rgb, knobSize / 2f)

        // 文本：标题居中于 y+13.5 / 详情+状态+"!" 居中于 y+24.75（Samsara drawRow 固定行坐标）
        // 文本不截断：岛宽由 targetWidth 伸展到完整内容宽度（morph 过程由圆角 stencil 裁剪）
        val textX = x + OPAI_TEXT_X
        Fonts.fontSemibold35.drawString(n.title, textX,
            y + 13.5f - Fonts.fontSemibold35.FONT_HEIGHT / 2f, opaiFade(opaiForeground, opacity).rgb)
        val statusText = if (n.enabled) "Enabled" else "Disabled"
        val bangW = Fonts.fontRegular30.getStringWidth("!").toFloat()
        val prefix = "${n.moduleName} has been"
        val statusOffset = Fonts.fontRegular30.getStringWidth(prefix).toFloat()
        val detailBaseY = y + 24.75f - Fonts.fontRegular30.FONT_HEIGHT / 2f
        Fonts.fontRegular30.drawString(prefix, textX, detailBaseY, opaiFade(opaiForeground, opacity).rgb)
        Fonts.fontRegular30.drawString(statusText, textX + statusOffset, detailBaseY,
            opaiFade(if (n.enabled) opaiEnabled else opaiDisabled, opacity).rgb)
        Fonts.fontRegular30.drawString("!", textX + statusOffset + Fonts.fontRegular30.getStringWidth(statusText),
            detailBaseY, opaiFade(opaiForeground, opacity).rgb)
    }

    // ---- 进度面板（对照 drawProgressPanel：瓦片 28x28@(4,5) / 标题 y+13 / 详情 y+25 / 进度条 y+37 h8）----

    private fun renderOpaiProgressPanel(
        x: Float, y: Float, w: Float, accent: Color,
        title: String, detail: String, progress: Float,
        itemStack: ItemStack? = null, iconRes: ResourceLocation? = null, glyph: String? = null,
        coverUrl: String? = null
    ) {
        drawRoundedRect(x + 4f, y + 5f, x + 32f, y + 33f, opaiTileBg.rgb, 7f)
        when {
            itemStack != null -> {
                glPushMatrix()
                enableGUIStandardItemLighting()
                try {
                    mc.renderItem.renderItemAndEffectIntoGUI(itemStack, (x + 10f).toInt(), (y + 11f).toInt())
                } catch (_: Exception) {
                }
                disableStandardItemLighting()
                GlStateManager.enableAlpha()
                GlStateManager.disableBlend()
                GlStateManager.disableLighting()
                glPopMatrix()
            }
            // 网易云专辑封面（对齐 iOS 实现：有封面铺满瓦片区并圆角裁剪，无封面回退音符图标）
            coverUrl != null -> {
                val cover = MusicCoverCache.get(coverUrl)
                if (cover != null) {
                    GlStateManager.color(1f, 1f, 1f, 1f)
                    drawImage(cover, x + 4f, y + 5f, 28, 28, Color.WHITE, 7f)
                } else {
                    drawImage(ResourceLocation("airplus/watermark_images/music.png"),
                        (x + 10f).toInt(), (y + 11f).toInt(), 16, 16, accent)
                }
            }
            iconRes != null -> drawImage(iconRes, (x + 10f).toInt(), (y + 11f).toInt(), 16, 16, Color.WHITE)
            glyph != null -> Fonts.fontSemibold40.drawCenteredString(
                glyph, x + 18f, y + 19f - Fonts.fontSemibold40.FONT_HEIGHT / 2f, accent.rgb)
            else -> opaiCubeWireframe(x + 18f, y + 19f, 14f, accent)
        }

        // 文本不截断：岛宽由 calcOpaiPanelWidth 伸展到完整内容宽度（morph 过程由圆角 stencil 裁剪）
        Fonts.fontSemibold35.drawString(title, x + OPAI_TEXT_X,
            y + 13f - Fonts.fontSemibold35.FONT_HEIGHT / 2f, accent.rgb)
        Fonts.fontRegular30.drawString(detail, x + OPAI_TEXT_X,
            y + 25f - Fonts.fontRegular30.FONT_HEIGHT / 2f, Color.WHITE.rgb)

        if (opaiProgress) {
            val barW = (w - 8f).coerceAtLeast(0f)
            val barY = y + 37f
            val barH = 8f
            drawRoundedRect(x + 4f, barY, x + 4f + barW, barY + barH, opaiBarTrack.rgb, barH / 2f)
            val filled = barW * progress.coerceIn(0f, 1f)
            if (filled > 0f) {
                drawRoundedRect(x + 4f, barY, x + 4f + filled, barY + barH, opaiProgressCol.rgb,
                    min(barH / 2f, filled / 2f))
            }
        }
    }

    /** Samsara drawCube：方块线框（scaffold 瓦片图标） */
    private fun opaiCubeWireframe(cx: Float, cy: Float, size: Float, color: Color) {
        val half = size * 0.5f
        val top = cy - half
        val bottom = cy + half
        val left = cx - half * 0.9f
        val right = cx + half * 0.9f
        val shoulder = cy - half * 0.5f
        val lower = cy + half * 0.5f
        glPushMatrix()
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO)
        glEnable(GL_LINE_SMOOTH)
        glHint(GL_LINE_SMOOTH_HINT, GL_NICEST)
        glLineWidth(1f)
        GlStateManager.color(color.red / 255f, color.green / 255f, color.blue / 255f, color.alpha / 255f)
        glBegin(GL_LINES)
        // 顶面菱形
        glVertex2f(cx, top); glVertex2f(right, shoulder)
        glVertex2f(right, shoulder); glVertex2f(cx, cy)
        glVertex2f(cx, cy); glVertex2f(left, shoulder)
        glVertex2f(left, shoulder); glVertex2f(cx, top)
        // 侧棱 + 中棱
        glVertex2f(left, shoulder); glVertex2f(left, lower)
        glVertex2f(left, lower); glVertex2f(cx, bottom)
        glVertex2f(cx, bottom); glVertex2f(right, lower)
        glVertex2f(right, lower); glVertex2f(right, shoulder)
        glVertex2f(cx, cy); glVertex2f(cx, bottom)
        glEnd()
        glDisable(GL_LINE_SMOOTH)
        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
        glPopMatrix()
    }

    private fun opaiScaffoldDetail(): String =
        "${hotbarBlockCount()} blocks left - " + String.format("%.1f", displayedBPS) + " block/s"

    private fun renderOpaiScaffoldPanel(x: Float, y: Float, w: Float, h: Float) {
        val progress = animatedScaffoldProgress.coerceIn(0f, 1f)
        renderOpaiProgressPanel(x, y, w, opaiAccent, "Scaffold Toggled", opaiScaffoldDetail(), progress)
    }

    private fun opaiPearlDetail(): String {
        val total = pearlLandingTicks.coerceAtLeast(1)
        val remaining = pearlTicksRemaining.coerceIn(0f, total.toFloat())
        return "Landing in " + String.format("%.1fs", remaining * 50f / 1000f)
    }

    private fun renderOpaiPearlPanel(x: Float, y: Float, w: Float, h: Float) {
        val total = pearlLandingTicks.coerceAtLeast(1)
        val remaining = pearlTicksRemaining.coerceIn(0f, total.toFloat())
        val progress = (1f - remaining / total).coerceIn(0f, 1f)
        renderOpaiProgressPanel(x, y, w, opaiAccent, "Pearl Landing", opaiPearlDetail(), progress,
            itemStack = ItemStack(Items.ender_pearl))
    }

    private fun opaiGappleDetail(): String =
        "Eaten " + String.format("%.0f", animatedGappleProgress.coerceIn(0f, 1f) * 100) + "%"

    private fun renderOpaiGapplePanel(x: Float, y: Float, w: Float, h: Float) {
        val progress = animatedGappleProgress.coerceIn(0f, 1f)
        renderOpaiProgressPanel(x, y, w, opaiAccent, "Eating Gapple", opaiGappleDetail(), progress,
            iconRes = getGappleResource())
    }

    private fun opaiMusicTitle(): String {
        val name = MusicHub.currentMusicName
        return if (name != "None" && name != "无") name else MusicHub.currentLyricDisplay.take(30).ifEmpty { "Now Playing" }
    }

    private fun opaiMusicDetail(): String {
        val name = MusicHub.currentMusicName
        return if (name != "None" && name != "无") MusicHub.currentLyricDisplay.take(28) else ""
    }

    private fun renderOpaiMusicPanel(x: Float, y: Float, w: Float, h: Float) {
        renderOpaiProgressPanel(x, y, w, opaiAccent, opaiMusicTitle(), opaiMusicDetail(),
            MusicHub.progress.coerceIn(0f, 1f),
            coverUrl = MusicHub.currentCoverUrl)
    }

    // ---- iOS 风格 TabList（Samsara 无对应实现，按 Opai 视觉常量自行设计：头像 + 名字 + 4 段信号条）----

    private fun renderOpaiTabList(x: Float, y: Float, w: Float, h: Float, players: List<NetworkPlayerInfo>) {
        val rows = players.take(opaiTabListRows)
        var rowY = y + 2f
        for (p in rows) {
            // 皮肤头像（64x64 皮肤图的 8,8 脸部区域 → 10x10）
            mc.textureManager.bindTexture(p.locationSkin)
            GlStateManager.color(1f, 1f, 1f, 1f)
            Gui.drawScaledCustomSizeModalRect((x + 8f).toInt(), (rowY + 5f).toInt(), 8f, 8f, 8, 8, 10, 10, 64f, 64f)

            val nameX = x + 8f + 10f + 6.5f
            val nameRight = x + w - 12.5f - 16.5f   // 右侧信号条区 + 内边距
            val name = mc.ingameGUI.tabList.getPlayerName(p)
            Fonts.fontRegular35.drawString(
                truncateText(name, Fonts.fontRegular35, (nameRight - nameX).coerceAtLeast(0f)),
                nameX, rowY + 10f - Fonts.fontRegular35.FONT_HEIGHT / 2f, Color.WHITE.rgb)

            // 4 段信号条：宽 3 / 间隙 1.5 / 高 4,6,8,10，底对齐 rowY+15，未亮格半透明白
            val ping = p.responseTime
            val tier = opaiPingTier(ping)
            val litCol = opaiPingColor(ping)
            var barX = x + w - 12.5f - (4f * 3f + 3f * 1.5f)
            for (i in 0..3) {
                val barH = 4f + i * 2f
                val barY = rowY + 15f - barH
                val col = if (i < tier) litCol else Color(255, 255, 255, 40)
                drawRoundedRect(barX, barY, barX + 3f, barY + barH, col.rgb, 1f)
                barX += 3f + 1.5f
            }
            rowY += OPAI_TABLIST_ROW
        }
    }

    // ---- iOS 风格 Chest（对照 ChestStealer：面板 190 宽 / slot 7+col*20 / y+6+row*20，GL 流程照抄 renderIosChest）----

    private fun renderOpaiChest(x: Float, y: Float, w: Float, h: Float, slots: List<Slot>) {
        val contentLeft = x + (w - OPAI_PANEL_W) / 2f
        enableGUIStandardItemLighting()
        try {
            slots.forEachIndexed { index, slot ->
                val stack = slot.stack
                val col = index % 9
                val row = index / 9
                val itemX = (contentLeft + 7f + col * 20f).toInt()
                val itemY = (y + 6f + row * 20f).toInt()

                val prevStack = prevSlotItems[index]
                if (prevSlotItems.containsKey(index)) {
                    val isChanged = when {
                        stack == null && prevStack == null -> false
                        stack == null || prevStack == null -> true
                        else -> !ItemStack.areItemStacksEqual(stack, prevStack) || stack.stackSize != prevStack.stackSize
                    }
                    if (isChanged) {
                        slotRipples.add(SlotRipple(itemX + 10f, itemY + 10f, System.currentTimeMillis()))
                    }
                }
                prevSlotItems[index] = stack?.copy()

                if (stack != null) {
                    mc.renderItem.renderItemAndEffectIntoGUI(stack, itemX, itemY)
                    mc.renderItem.renderItemOverlays(mc.fontRendererObj, stack, itemX, itemY)
                }
            }

            disableStandardItemLighting()
            GlStateManager.disableDepth()

            // 物品变动涟漪（复用现有 slotRipples 管线）
            val currentTime = System.currentTimeMillis()
            val iterator = slotRipples.iterator()
            while (iterator.hasNext()) {
                val ripple = iterator.next()
                val timeAlive = currentTime - ripple.startTime
                if (timeAlive > 600L) {
                    slotRipples.remove(ripple)
                } else {
                    val progress = timeAlive.toFloat() / 600f
                    val ease = 1f - (1f - progress).pow(3)
                    val radius = 18f * ease
                    val alpha = (150 * (1f - progress)).toInt().coerceIn(0, 255)
                    if (alpha > 0) drawCircle(ripple.x, ripple.y, radius, Color(255, 255, 255, alpha))
                }
            }
            GlStateManager.enableDepth()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            disableStandardItemLighting()
            GlStateManager.enableAlpha()
            GlStateManager.disableBlend()
            GlStateManager.disableLighting()
        }
    }

    /**
     * 网易云封面异步下载缓存：url -> ResourceLocation（LRU 上限 32）。
     * 下载失败 60 秒内不重试；超出容量时释放最旧纹理。
     */
    private object MusicCoverCache {
        private const val MAX_CACHE = 32
        private val cache = LinkedHashMap<String, ResourceLocation>(16, 0.75f, true)
        private val loading = HashSet<String>()
        private val retryAt = HashMap<String, Long>()

        fun get(url: String?): ResourceLocation? {
            if (url.isNullOrEmpty()) return null
            cache[url]?.let { return it }
            if (url in loading) return null
            val now = System.currentTimeMillis()
            retryAt[url]?.let { if (now < it) return null }
            loading.add(url)
            CompletableFuture.supplyAsync { ImageIO.read(URL(url)) }
                .whenComplete { image, error ->
                    Minecraft.getMinecraft().addScheduledTask {
                        loading.remove(url)
                        if (error != null || image == null) {
                            retryAt[url] = System.currentTimeMillis() + 60_000L
                            return@addScheduledTask
                        }
                        val texture = DynamicTexture(image)
                        val loc = Minecraft.getMinecraft().textureManager
                            .getDynamicTextureLocation("airplus-music-cover", texture)
                        cache[url] = loc
                        while (cache.size > MAX_CACHE) {
                            val eldest = cache.entries.iterator()
                            val entry = eldest.next()
                            Minecraft.getMinecraft().textureManager.deleteTexture(entry.value)
                            eldest.remove()
                        }
                    }
                }
            return null
        }
    }

}
