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
import net.airplus.features.module.modules.music.MusicPlayer
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
object Island : Module("Island", Category.RENDER) {
    private val ClientName by text("ClientName", "Air")
    val style by choices("Style", arrayOf("ios", "legacy"), "legacy")
    private val animTension by float("BounceTension", 0.01f, 0.01f..1.0f)
    private val animFriction by float("BounceFriction", 0.12f, 0.01f..1.0f)
    // 每个样式的圆角设置
    private val iosRadius by float("iOS-Radius", 15F, 0F..50F) { style == "ios" }
    private val legacyRadius by float("Legacy-Radius", 14F, 0F..50F) { style == "legacy" }

    // 每个样式的展开动画设置
    private val legacyExpandAnim by choices("Legacy-ExpandAnim", arrayOf("Bounce", "EaseOut", "Linear", "Back"), "Bounce") { style == "legacy" }
    private val iosExpandAnim by choices("iOS-ExpandAnim", arrayOf("Bounce", "EaseOut", "Linear", "Back"), "Bounce") { style == "ios" }

    // iOS样式设置
    private val iosShadowStrength by float("iOS-ShadowStrength", 10F, 0F..30F) { style == "ios" }
    private val iosBlurStrength by float("iOS-BlurStrength", 15F, 0F..50F) { style == "ios" }

    private val logoIcon by choices("LogoIcon", arrayOf("Default", "A", "Diamond", "Radioactive", "Start", "Start2", "Earth"), "A")
    private val pingIcon by choices("PingIcon", arrayOf("Default", "Ping2", "Ping3", "Ping4", "Ping5"), "Ping3") { style == "legacy" }
    private val gappleIcon by choices("GappleIcon", arrayOf("Default", "Heart", "Heart2"), "Default") { style == "legacy" }
    private val userIcon by choices("UserIcon", arrayOf("Default", "User2", "User3", "User4"), "Default") { style == "legacy" }

    private val customip by boolean("customIP", false) { style == "legacy" }
    private val ip by text("IP", "hidden.ip") { customip }
    private val ColorA_ by int("Red", 255, 0..255) { style == "legacy" }
    private val ColorB_ by int("Green", 255, 0..255) { style == "legacy" }
    private val ColorC_ by int("Blue", 255, 0..255) { style == "legacy" }

    private val BackgroundAlpha by int("BackGroundAlpha", 160, 0..255)

    private val ShadowCheck by boolean("Shadow", false) { style == "legacy" }
    private val shadowRadiusValue by float("Shadow-Radius", 15F, 1F..50F) { ShadowCheck }
    private val shadowColor by color("Shadow-Color", Color(0, 0, 0, 120)) { ShadowCheck }

    private val blurCheck by boolean("Blur", true)
    private val blurMode by choices("BlurMode", arrayOf("Gaussian", "Dual", "Better", "Kawase"), "Better") { blurCheck }
    private val blurRadius by float("BlurStrength", 10F, 1F..50F) { blurCheck }
    private val kawaseIterations by int("Kawase-Iterations", 4, 1..10) { blurCheck && blurMode == "Kawase" }
    private val kawaseOffset by int("Kawase-Offset", 3, 1..10) { blurCheck && blurMode == "Kawase" }

    private val notifyDuration by int("NotifyTime(ms)", 1000, 100..10000) { style == "legacy" }
    private val versionNameUp by text("VersionName", "development") { false }
    private val ButtonColor by color("Button-Color", Color(20, 150, 180, 255)) { style == "legacy" }
    private val ModuleNotify by boolean("Notification", true)
    private val isScaffold by boolean("Scaffold", true)
    private val scaffoldStyle by choices("ScaffoldStyle", arrayOf("Classic", "Bar", "Minimal", "Compact", "Standard", "Circle", "Split", "Modern", "Outline"), "Classic") { isScaffold && style == "legacy" }
    private val ScaffoldTheme by color("ScaffoldTheme", Color(65, 130, 225)) { style == "legacy" }
    private val maxBlocks by int("maxBlocks", 576, 64..576)
    private val standardShowBlockCount by boolean("StandardShowBlockCount", true) { isScaffold && scaffoldStyle == "Standard" }

    private val breakProgressCheck by boolean("BreakProgress", true) { style == "legacy" }
    private val breakProgressTheme by color("BreakProgressTheme", Color(225, 150, 65)) { style == "legacy" }
    private val breakProgressOnlyFuckerNuker by boolean("OnlyFuckerAndNuker", true) { breakProgressCheck }

    private val showGappleProgress by boolean("GappleProgress", true)
    private val gappleProgressTheme by color("GappleProgressTheme", Color(255, 215, 0))

    private val pearlCountdown by boolean("PearlCountdown", true)

    private val lowHealthCheck by boolean("LowHealthWarn", true)
    private val lowHealthThreshold by float("LowHealth-HP", 6F, 1F..20F) { lowHealthCheck }
    private val lowHealthColor by color("LowHealth-Color", Color(210, 60, 60, 200)) { lowHealthCheck }

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

    private var pearlEntityId = -1
    private var pearlLandingTicks = -1 // 抛物线预测的落地总 tick
    private var pearlThrowTimeMs = 0L  // 本地投掷时刻
    private var pearlTicksRemaining = -1F // 剩余 tick（由投掷时间 + 预测落地时间推算）

    private var lowAnimAlpha = 0F
    private var lowAnimScale = 0F
    private var velLowAlpha = 0f
    private var velLowScale = 0f
    private var lowLastVisible = false

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

        var targetWidth = 0F
        var targetHeight = 0F
        var targetX = 0F
        var targetY = start_y
        var renderMode = "NONE"

        if (style == "legacy" && showLyricOnIsland && lyricDisplayMode == "Full" && MusicPlayer.currentLyricDisplay.isNotEmpty()) {
            renderMode = "LYRIC_FULL"
            val textFont = getLyricTextFont()
            val currentLyric = MusicPlayer.currentLyricDisplay
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
                "ios" -> {
                    val scaffoldOn = isScaffold && (scaffoldModuleRef?.state == true || scaffold2ModuleRef?.state == true)
                    val musicOn = MusicPlayer.isCurrentlyPlaying && (MusicPlayer.currentLyricDisplay.isNotEmpty() || MusicPlayer.currentMusicName != "None")
                    val toggleActive = ModuleNotify && notifications.isNotEmpty()
                    val gappleOn = showGappleProgress && animatedGappleProgress > 0.01f && gappleModuleRef?.state == true
                    val tabListOn = showTabList && playerList.isNotEmpty()
                    val pearlOn = pearlCountdown && pearlTicksRemaining >= 0f

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
                        scaffoldOn -> {
                            renderMode = "IOS_SCAFFOLD"
                            targetWidth = 210f
                            targetHeight = 34f
                            targetX = (width - targetWidth) / 2
                        }
                        toggleActive -> {
                            renderMode = "IOS_TOGGLE"
                            val borderInfo = calcMaxNotificationWidth()
                            targetWidth = borderInfo.coerceAtLeast(180F)
                            targetHeight = 34f
                            targetX = (width - targetWidth) / 2
                        }
                        musicOn -> {
                            renderMode = "IOS_MUSIC"
                            val musicName = MusicPlayer.currentMusicName
                            val lyricText = MusicPlayer.currentLyricDisplay.take(20)
                            // iOS Dynamic Island music: compact pill with album art + text
                            val coverSize = 36f
                            val pad = 8f
                            val textAreaW = Fonts.fontSemibold35.getStringWidth(musicName).toFloat().coerceAtMost(160f) + pad * 2
                            targetWidth = (pad + coverSize + 6f + textAreaW + pad).coerceIn(200f, 360f)
                            targetHeight = 52f
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

            if (currentAnim == "Bounce") {
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
            val currentRadius = when {
                isIosMode -> if (renderMode == "IOS_CHEST") iosRadius else if (AnimGlobalHeight > 100f) iosRadius.coerceAtMost(AnimGlobalHeight / 2f) else iosRadius.coerceAtMost(AnimGlobalHeight / 2f)
                AnimGlobalHeight > 30F -> if (renderMode == "CHEST" || renderMode == "TABLIST") ChestRounded else legacyRadius.coerceAtMost(AnimGlobalHeight / 2f)
                else -> legacyRadius.coerceAtMost(AnimGlobalHeight / 2f)
            }

            val drawX = AnimGlobalX
            val drawY = AnimGlobalY
            val drawW = AnimGlobalWidth
            val drawH = AnimGlobalHeight
            
            val isBelowMode = showLyricOnIsland && lyricDisplayMode == "Below"
            val islandCorners = if (isBelowMode) RenderUtils.RoundedCorners.TOP_ONLY else RenderUtils.RoundedCorners.ALL

            try {
                EmbeddedStencil.checkSetupFBO(mc.framebuffer)
                EmbeddedStencil.write(false)
                RenderUtils.drawRoundedRect(drawX, drawY, drawX + drawW, drawY + drawH, Color.WHITE.rgb, currentRadius, islandCorners)

                EmbeddedStencil.erase(false)
                if (ShadowCheck) {
                    val maxDist = shadowRadiusValue.toInt()
                    for (i in maxDist downTo 1) {
                        val alpha = (shadowColor.alpha * (1f - i.toFloat() / (maxDist + 1))).toInt().coerceIn(1, shadowColor.alpha)
                        RenderUtils.drawRoundedRect(
                            drawX - i, drawY - i,
                            drawX + drawW + i, drawY + drawH + i,
                            Color(shadowColor.red, shadowColor.green, shadowColor.blue, alpha).rgb,
                            currentRadius + i
                        )
                    }
                }

                EmbeddedStencil.erase(true)
                if (blurCheck) {
                    applyBlur(drawX, drawY, drawW, drawH)
                }

                RenderUtils.drawRoundedRect(
                    drawX, drawY,
                    drawX + drawW, drawY + drawH,
                    Color(0, 0, 0, BackgroundAlpha).rgb,
                    currentRadius,
                    islandCorners
                )

                glEnable(GL_BLEND)
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

                if (!isIosMode) {
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

            } catch (e: Exception) {
                // 异常路径先清除残留的 stencil 状态，避免回退绘制与后续渲染被 GL_EQUAL 遮罩
                EmbeddedStencil.dispose()
                if (ShadowCheck) {
                    val maxDist = shadowRadiusValue.toInt()
                    for (i in maxDist downTo 1) {
                        val alpha = (shadowColor.alpha * (1f - i.toFloat() / (maxDist + 1))).toInt().coerceIn(1, shadowColor.alpha)
                        RenderUtils.drawRoundedRect(
                            drawX - i, drawY - i,
                            drawX + drawW + i, drawY + drawH + i,
                            Color(shadowColor.red, shadowColor.green, shadowColor.blue, alpha).rgb,
                            currentRadius + i
                        )
                    }
                }
                RenderUtils.drawRoundedRect(drawX, drawY, drawX + drawW, drawY + drawH, Color(0,0,0,BackgroundAlpha).rgb, currentRadius, islandCorners)
                glEnable(GL_BLEND)
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            } finally {
                // 无论成功还是异常都必须还原 stencil 状态（dispose 幂等，重复调用安全）
                EmbeddedStencil.dispose()
            }

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
                "IOS_PEARL" -> renderIosPearl(drawX, drawY, drawW, drawH)
                "IOS_WATERMARK" -> renderIosWatermark(drawX, drawY, drawW, drawH)
                "IOS_TOGGLE" -> renderIosToggle(drawX, drawY, drawW, drawH)
                "IOS_SCAFFOLD" -> renderIosScaffold(drawX, drawY, drawW, drawH)
                "IOS_MUSIC" -> renderIosMusic(drawX, drawY, drawW, drawH)
                "IOS_CHEST" -> renderIosChest(drawX, drawY, drawW, drawH, chestSlots)
                "IOS_GAPPLE" -> renderIosGapple(drawX, drawY, drawW, drawH)
                "IOS_TABLIST" -> renderIosTabList(drawX, drawY, drawW, drawH, playerList, headerLines, footerLines)
            }
        }

        if (style == "legacy" && showLyricOnIsland && lyricDisplayMode != "None") {
            renderLyricDisplay()
        }

        renderLowHealthPill()

        glHint(GL_LINE_SMOOTH_HINT, GL_DONT_CARE)
        glDisable(GL_LINE_SMOOTH)
        glEnable(GL_TEXTURE_2D)
        glDisable(GL_BLEND)
        glPopMatrix()
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

        val barW = (x + w - padding) - cx
        val gap = 2F
        val segCount = 10
        val segW = ((barW - gap * (segCount - 1)) / segCount).coerceAtLeast(3F)
        val barH = 8F
        val barY = centerY - barH / 2f
        val filled = ((remaining.toFloat() / total) * segCount + 0.5f).toInt().coerceIn(0, segCount)
        val theme = ClientThemesUtils.getColor()
        val themeColor = Color(theme.red, theme.green, theme.blue, 230)
        for (i in 0 until segCount) {
            val sx = cx + i * (segW + gap)
            val col = if (i < filled) themeColor else Color(60, 60, 60, 200)
            drawRoundedRect(sx, barY, sx + segW, barY + barH, col.rgb, 2F)
        }
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
        val percentage = (hotbarBlockCount.toFloat() / maxBlocks.toFloat()).coerceIn(0f, 1f)
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
        val percentage = (hotbarBlockCount.toFloat() / maxBlocks.toFloat()).coerceIn(0f, 1f)
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
        val percentage = (hotbarBlockCount.toFloat() / maxBlocks.toFloat()).coerceIn(0f, 1f)

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
        val percentage = (hotbarBlockCount.toFloat() / maxBlocks.toFloat()).coerceIn(0f, 1f)
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
        val percentage = (hotbarBlockCount.toFloat() / maxBlocks.toFloat()).coerceIn(0f, 1f)

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
        val percentage = (hotbarBlockCount.toFloat() / maxBlocks.toFloat()).coerceIn(0f, 1f)

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
        val percentage = (hotbarBlockCount.toFloat() / maxBlocks.toFloat()).coerceIn(0f, 1f)
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
        val percentage = (hotbarBlockCount.toFloat() / maxBlocks.toFloat()).coerceIn(0f, 1f)
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
        val percentage = (hotbarBlockCount.toFloat() / maxBlocks.toFloat()).coerceIn(0f, 1f)
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

        val colorRGB = Color(ColorA_, ColorB_, ColorC_, 255)
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
            MusicPlayer.isCurrentlyPlaying && 
            (MusicPlayer.currentLyricDisplay.isNotEmpty() || MusicPlayer.currentMusicName != "None")
        
        if (showInsideLyric) {
            val currentLyric = MusicPlayer.currentLyricDisplay
            val currentMusicName = MusicPlayer.currentMusicName
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
            MusicPlayer.isCurrentlyPlaying && 
            (MusicPlayer.currentLyricDisplay.isNotEmpty() || MusicPlayer.currentMusicName != "None")
        
        if (showInsideLyric) {
            val currentLyric = MusicPlayer.currentLyricDisplay
            val currentMusicName = MusicPlayer.currentMusicName
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
            drawRoundedBorderRect(x, currentY, x+w, currentY+ITEM_NOTIFY_HEIGHT, 0.2F, Color(0,0,0,BackgroundAlpha).rgb, Color(0,0,0,BackgroundAlpha).rgb, 10F)
            notify.draw(x + padding, currentY + 5F)
            currentY += ITEM_NOTIFY_HEIGHT + 2F
        }
    }

    private fun drawNormal() {
        val username = mc.session.username
        val fps = Minecraft.getDebugFPS()
        val pings = getSafePing()
        val colorRGB = Color(ColorA_, ColorB_, ColorC_, 255)
        val text = " | $username | ${fps}fps | ${pings}ms"
        val mainText = ClientName
        val h = 38F
        val wCalc = 20F + 18F + 5F + Fonts.fontSemibold40.getStringWidth(mainText + text) + 10F
        val x = (width - wCalc)/2
        val y = start_y
        val (nX, _) = spring(AnimGlobalX, x, VelGlobalX)
        AnimGlobalX = nX
        ShowShadow(x, y, wCalc, h)
        drawRoundedBorderRect(x, y, x+wCalc, y+h, 0.5F, Color(10,10,10,BackgroundAlpha).rgb, Color(30,30,30,BackgroundAlpha).rgb, h/2)
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
        
        val isPlaying = MusicPlayer.isCurrentlyPlaying
        val currentLyric = MusicPlayer.currentLyricDisplay
        val currentMusicName = MusicPlayer.currentMusicName

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
        val currentLyric = MusicPlayer.currentLyricDisplay
        val previousLyric = MusicPlayer.previousLyricDisplay
        val nextLyric = MusicPlayer.nextLyricDisplay
        val currentMusicName = MusicPlayer.currentMusicName
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
            val progress = MusicPlayer.progress.coerceIn(0F, 1F)
            val timeStr = MusicPlayer.timeDisplayString
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
        val currentLyric = MusicPlayer.currentLyricDisplay
        val previousLyric = MusicPlayer.previousLyricDisplay
        val nextLyric = MusicPlayer.nextLyricDisplay
        val currentMusicName = MusicPlayer.currentMusicName
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
            val progress = MusicPlayer.progress.coerceIn(0F, 1F)
            val timeStr = MusicPlayer.timeDisplayString
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
        val currentLyric = MusicPlayer.currentLyricDisplay ?: ""
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
            val progress = MusicPlayer.progress.coerceIn(0F, 1F)
            val timeStr = MusicPlayer.timeDisplayString ?: "0:00 / 0:00"
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
        val currentLyric = MusicPlayer.currentLyricDisplay ?: ""
        val previousLyric = MusicPlayer.previousLyricDisplay ?: ""
        val nextLyric = MusicPlayer.nextLyricDisplay ?: ""
        val currentMusicName = MusicPlayer.currentMusicName ?: "None"
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
     * Island6 style iOS Scaffold: compact split bar - block count + 10-segment split progress bar
     */
    private fun renderIosScaffold(x: Float, y: Float, w: Float, h: Float) {
        val blockCount = (0..8).sumOf { slotIndex ->
            val stack = mc.thePlayer.inventory.getStackInSlot(slotIndex)
            if (stack != null && stack.item is ItemBlock) stack.stackSize else 0
        }
        val percentage = (blockCount.toFloat() / maxBlocks.toFloat()).coerceIn(0f, 1f)

        val padding = 12F
        val centerY = y + h / 2f
        var cx = x + padding

        // Block count text on the left, vertically centered
        val countStr = "$blockCount"
        val textBaseY = centerY - Fonts.fontSemibold35.FONT_HEIGHT / 2f + 1f
        Fonts.fontSemibold35.drawString(countStr, cx, textBaseY, Color.WHITE.rgb)
        cx += Fonts.fontSemibold35.getStringWidth(countStr).toFloat() + 10F

        // 10-segment split bar on the right, vertically centered
        val barW = (x + w - padding) - cx
        val gap = 2F
        val segCount = 10
        val segW = ((barW - gap * (segCount - 1)) / segCount).coerceAtLeast(3F)
        val barH = 8F
        val barY = centerY - barH / 2f
        val filledSegs = (percentage * segCount).toInt().coerceIn(0, segCount)

        for (i in 0 until segCount) {
            val sx = cx + i * (segW + gap)
            val col = if (i < filledSegs)
                Color(255, 255, 255, 230)
            else
                Color(60, 60, 60, 200)
            drawRoundedRect(sx, barY, sx + segW, barY + barH, col.rgb, 2F)
        }
    }

    /**
     * iOS Dynamic Island Music: small cover + song name + lyric + short white progress bar
     * Mimics the real iOS 16/17 Dynamic Island Now Playing style
     */
    private fun renderIosMusic(x: Float, y: Float, w: Float, h: Float) {
        val pad = 8f
        val musicName = MusicPlayer.currentMusicName
        val lyric = MusicPlayer.currentLyricDisplay
        val displayText = if (musicName != "None" && musicName != "无") musicName else lyric.take(30)
        val progress = MusicPlayer.progress.coerceIn(0f, 1f)

        // --- Cover icon (left side, vertically centered) ---
        val coverSize = 32f  // Larger
        val coverX = x + pad + 2f
        val coverY = y + (h - coverSize) / 2f  // Vertically centered
        val coverRadius = 6f

        // Dark rounded square background for cover
        drawRoundedRect(coverX, coverY, coverX + coverSize, coverY + coverSize,
            Color(30, 30, 30, 220).rgb, coverRadius)

        // Music icon centered in cover
        val iconSz = (coverSize * 0.6f).toInt()
        val iconOffsetX = coverX + (coverSize - iconSz) / 2f
        val iconOffsetY = coverY + (coverSize - iconSz) / 2f
        drawImage(ResourceLocation("airplus/watermark_images/music.png"),
            iconOffsetX.toInt(), iconOffsetY.toInt(), iconSz, iconSz, Color.WHITE)

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

        // 10-segment split bar on the right
        val barW = (x + w - padding) - cx
        val gap = 2F
        val segCount = 10
        val segW = ((barW - gap * (segCount - 1)) / segCount).coerceAtLeast(3F)
        val barH = 8F
        val barY = centerY - barH / 2f
        val filledSegs = (percentage * segCount).toInt().coerceIn(0, segCount)
        val themeColor = Color(gappleProgressTheme.red, gappleProgressTheme.green, gappleProgressTheme.blue, 230)
        for (i in 0 until segCount) {
            val sx = cx + i * (segW + gap)
            val col = if (i < filledSegs) themeColor else Color(60, 60, 60, 200)
            drawRoundedRect(sx, barY, sx + segW, barY + barH, col.rgb, 2F)
        }
    }

    private fun renderIosTabList(x: Float, y: Float, w: Float, h: Float, players: List<NetworkPlayerInfo>, header: List<String>, footer: List<String>) {
        renderTabListContent(x, y, w, h, players, header, footer)
    }

    /**
     * 低血量提示：岛下方红色胶囊 + 心跳脉动，血量低于阈值时出现
     */
    private fun renderLowHealthPill() {
        val player = mc.thePlayer
        val active = lowHealthCheck && player != null && player.isEntityAlive &&
                player.health > 0f && player.health <= lowHealthThreshold

        if (active && !lowLastVisible) {
            lowAnimAlpha = 0F
            lowAnimScale = 0F
            velLowAlpha = 0f
            velLowScale = 0f
        }
        lowLastVisible = active

        val target = if (active) 1f else 0f
        val (nextAlpha, vA) = spring(lowAnimAlpha, target, velLowAlpha)
        lowAnimAlpha = nextAlpha.coerceIn(0f, 1f)
        velLowAlpha = vA
        val (nextScale, vS) = spring(lowAnimScale, target, velLowScale)
        lowAnimScale = nextScale.coerceAtLeast(0f)
        velLowScale = vS

        if (lowAnimAlpha < 0.01f || lowAnimScale < 0.01f) return
        val p = player ?: return

        val text = "Low Health :" + String.format("%.1f", p.health) + " HP"
        val font = Fonts.fontSemibold35
        val pad = 12f
        val pillH = 24f
        val pillW = font.getStringWidth(text) + pad * 2 + 16f
        val cx = AnimGlobalX + AnimGlobalWidth / 2f
        val by = AnimGlobalY + AnimGlobalHeight + 8f
        val nowMs = System.currentTimeMillis()
        val pulse = 1f + 0.05f * sin(nowMs / 180.0).toFloat()
        val s = lowAnimScale * pulse
        val alphaF = lowAnimAlpha

        val x1 = cx - pillW / 2f
        val y1 = by
        val x2 = cx + pillW / 2f
        val y2 = by + pillH
        val pivotX = cx
        val pivotY = by + pillH / 2f

        glPushMatrix()
        glTranslatef(pivotX, pivotY, 0f)
        glScalef(s, s, 1f)
        glTranslatef(-pivotX, -pivotY, 0f)

        val base = lowHealthColor
        drawRoundedRect(x1, y1, x2, y2, Color(base.red, base.green, base.blue, (base.alpha * alphaF).toInt()).rgb, pillH / 2f)

        // 心跳红点
        drawCircle(x1 + pad + 4f, pivotY, 4f + sin(nowMs / 110.0).toFloat() * 1.2f, Color(255, 95, 95, (235 * alphaF).toInt()))

        font.drawString(text, x1 + pad + 14f, pivotY - font.FONT_HEIGHT / 2f + 1f, Color(255, 255, 255, (255 * alphaF).toInt()).rgb)

        glPopMatrix()
    }

}
