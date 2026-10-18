/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.ui.client.mainmenu

import net.airplus.AirPlus
import net.airplus.file.FileManager
import net.airplus.file.FileManager.backgroundFileFor
import net.airplus.file.FileManager.existingBackgroundFile
import net.airplus.file.FileManager.valuesConfig
import net.airplus.file.configs.models.ClientConfiguration
import net.airplus.injection.forge.StartupSplash
import net.airplus.ui.client.GuiMainMenu
import net.airplus.ui.client.GuiMainMenuSettings
import net.airplus.ui.font.Fonts
import net.airplus.utils.io.FileFilters
import net.airplus.utils.io.MiscUtils
import net.airplus.utils.io.MiscUtils.showErrorPopup
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.render.shader.FluxBlobShader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.GL11
import java.awt.Color

/**
 * 主菜单风格统一管理（迁移自 AirClient MainMenuStyles）。
 *
 * 循环顺序：Flux -> Hanabi -> Minimal -> Sidebar -> Dock -> Split -> Grid -> Orbit -> Header -> Diagonal -> Flux
 *
 * 背景：索引 0 为 Flux 动态气泡着色器背景（可选择），其余为内置图片背景。
 * 所有主菜单与设置界面统一通过 [drawMenuBackground] 绘制背景。
 */
object MainMenuStyles {

    val STYLE_FLUX = "Flux"
    val STYLE_HANABI = "Hanabi"
    val STYLE_HANABI2 = "Hanabi2"
    val STYLE_XINXIN = "Xinxin"
    val STYLE_MINIMAL = "Minimal"
    val STYLE_SIDEBAR = "Sidebar"
    val STYLE_DOCK = "Dock"
    val STYLE_SPLIT = "Split"
    val STYLE_GRID = "Grid"
    val STYLE_ORBIT = "Orbit"
    val STYLE_HEADER = "Header"
    val STYLE_DIAGONAL = "Diagonal"
    val STYLE_SETSUNA = "Setsuna"

    // 旧版本配置值迁移（Default -> Flux, Custom -> Hanabi）
    private val LEGACY_NAMES = mapOf("Default" to STYLE_FLUX, "Custom" to STYLE_HANABI)

    val STYLES = listOf(
        STYLE_FLUX, STYLE_HANABI, STYLE_HANABI2, STYLE_MINIMAL, STYLE_SIDEBAR, STYLE_DOCK, STYLE_SPLIT,
        STYLE_GRID, STYLE_ORBIT, STYLE_HEADER, STYLE_DIAGONAL, STYLE_XINXIN, STYLE_SETSUNA
    )

    /** 迁移旧配置里的风格名，保证读取到的总是新名字。 */
    @JvmStatic
    fun normalize(style: String): String = LEGACY_NAMES[style] ?: style

    /**
     * 内置图片背景列表（assets/minecraft/airplus/bg 目录）。
     * 索引 0 保留给 Flux 动态背景，因此图片从索引 1 开始。
     * 索引存储在 ClientConfiguration.customMenuBackgroundImageIndex，实际取模避免越界。
     */
    val BACKGROUND_IMAGES = listOf(
        ResourceLocation("airplus/bg/miku.jpeg"),
        ResourceLocation("airplus/bg/miku2.jpeg"),
        ResourceLocation("airplus/bg/nina.jpeg"),
        ResourceLocation("airplus/bg/nigu.jpeg"),
        ResourceLocation("airplus/bg/muzimi.jpeg"),
        ResourceLocation("airplus/mainmenu/xinxin_bg.png"),
        ResourceLocation("airplus/mainmenu/hanabi2_bg.png")
    )

    val BACKGROUND_IMAGE_NAMES = listOf(
        "Flux", "Miku", "Miku 2", "Nina", "Nigu", "Muzimi", "Xinxin", "Hanabi2"
    )

    private const val FLUX_BACKGROUND_INDEX = 0

    // 共享的 Flux 动态气泡背景着色器（懒加载，首次渲染时才有 GL 上下文）
    private val fluxShader: FluxBlobShader by lazy { FluxBlobShader() }

    @JvmStatic
    fun backgroundImageIndex(raw: Int): Int {
        val size = BACKGROUND_IMAGE_NAMES.size
        return ((raw % size) + size) % size
    }

    @JvmStatic
    fun drawMenuBackground(width: Int, height: Int, overlayColor: Int) {
        val idx = backgroundImageIndex(ClientConfiguration.customMenuBackgroundImageIndex)
        if (idx == FLUX_BACKGROUND_INDEX) {
            drawFluxBackground(width, height)
        } else {
            RenderUtils.drawImage(BACKGROUND_IMAGES[idx - 1], 0, 0, width, height)
        }
        if (overlayColor != 0) {
            RenderUtils.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayColor)
        }
    }

    /**
     * Flux 动态背景：用户本地自定义背景（.png/.frag）优先，
     * 否则内置动态气泡着色器；不可用时退回深色纯色。
     */
    @JvmStatic
    fun drawFluxBackground(width: Int, height: Int) {
        // 自定义背景延迟到首次绘制时加载（含 GL 贴图上传，须在渲染线程）
        FileManager.ensureBackgroundLoaded()
        val custom = AirPlus.background
        if (custom != null) {
            custom.drawBackground(width, height)
        } else {
            val shader = fluxShader
            if (shader.isAvailable) {
                shader.renderShader(width, height)
            } else {
                RenderUtils.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Color(18, 18, 22).rgb)
            }
        }
    }

    /**
     * 在缩略图区域内实时预览 Flux 动态/自定义背景（scissor 裁剪全屏绘制）。
     * 用于 Menu Settings 界面的 Flux 背景缩略图。
     */
    @JvmStatic
    fun drawFluxBackgroundPreview(width: Int, height: Int, x: Float, y: Float, w: Float, h: Float) {
        val mc = Minecraft.getMinecraft()
        val factor = ScaledResolution(mc).scaleFactor
        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        GL11.glScissor(
            (x * factor).toInt(),
            (mc.displayHeight - (y + h) * factor).toInt(),
            (w * factor).toInt(),
            (h * factor).toInt()
        )
        drawFluxBackground(width, height)
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
    }

    /** 指定背景对应的图片资源；Flux 动态背景返回 null。 */
    @JvmStatic
    fun backgroundResource(raw: Int): ResourceLocation? {
        val idx = backgroundImageIndex(raw)
        return if (idx == FLUX_BACKGROUND_INDEX) null else BACKGROUND_IMAGES[idx - 1]
    }

    @JvmStatic
    fun backgroundDisplayName(raw: Int): String =
        BACKGROUND_IMAGE_NAMES[backgroundImageIndex(raw)]

    /**
     * 从本地选择自定义背景文件（.png 图片 或 .frag 片段着色器），复制到客户端目录并应用，
     * 同时把背景选择切到 Flux 项（供 Flux 主菜单 Background 按钮与 Menu Settings "Custom" 按钮共用）。
     */
    @JvmStatic
    fun pickCustomBackground() {
        val file = MiscUtils.openFileChooser(FileFilters.IMAGE, FileFilters.SHADER, acceptAll = false) ?: return

        // 替换前释放旧背景的 GL 资源
        AirPlus.background?.dispose()

        AirPlus.background = try {
            // 复制到客户端目录（保留扩展名），下次启动时由 FileManager.loadBackground 恢复
            val target = backgroundFileFor(file.extension)
            if (target.exists()) target.deleteRecursively()
            file.copyTo(target)

            try {
                net.airplus.utils.render.shader.Background.fromFile(target)
            } catch (e: Exception) {
                e.showErrorPopup()
                target.deleteRecursively()
                null
            }
        } catch (e: Exception) {
            e.showErrorPopup()
            null
        }

        // 选了本地背景后切回 Flux 背景项，保证立即生效
        if (AirPlus.background != null) {
            ClientConfiguration.customMenuBackgroundImageIndex = backgroundImageIndex(0)
            FileManager.saveConfig(valuesConfig)
        }
    }

    /** 移除本地自定义背景，恢复默认内置 blob 着色器。 */
    @JvmStatic
    fun resetCustomBackground() {
        AirPlus.background?.dispose()
        AirPlus.background = null
        existingBackgroundFile()?.deleteRecursively()
    }

    @JvmStatic
    fun next(current: String): String {
        val cur = normalize(current)
        val idx = STYLES.indexOf(cur)
        if (idx < 0) return STYLE_FLUX
        return STYLES[(idx + 1) % STYLES.size]
    }

    @JvmStatic
    fun displayName(style: String): String = normalize(style)

    /**
     * 根据风格名创建对应的主菜单屏幕实例。
     */
    @JvmStatic
    fun createScreen(style: String): Any = when (normalize(style)) {
        STYLE_HANABI -> CustomMainMenu()
        STYLE_HANABI2 -> Hanabi2MainMenu()
        STYLE_MINIMAL -> MinimalMainMenu()
        STYLE_SIDEBAR -> SidebarMainMenu()
        STYLE_DOCK -> DockMainMenu()
        STYLE_SPLIT -> SplitMainMenu()
        STYLE_GRID -> GridMainMenu()
        STYLE_ORBIT -> OrbitMainMenu()
        STYLE_HEADER -> HeaderMainMenu()
        STYLE_DIAGONAL -> DiagonalMainMenu()
        STYLE_XINXIN -> XinxinMainMenu()
        STYLE_SETSUNA -> SetsunaMainMenu()
        else -> GuiMainMenu()
    }

    /**
     * 按已保存配置创建主菜单（供 MixinMinecraft 替换原版主菜单时调用）。
     */
    @JvmStatic
    fun createSavedScreen(): net.minecraft.client.gui.GuiScreen =
        createScreen(ClientConfiguration.mainMenuStyle) as net.minecraft.client.gui.GuiScreen

    // ===== 所有风格通用的右下角 Menu Settings 按钮（Flux 主菜单同款样式） =====

    private const val SETTINGS_W = 110f
    private const val SETTINGS_H = 20f
    private var settingsHoverAni = 0f

    // ===== 启动淡入（splash 欢迎画面交还主线程后，主菜单从黑罩淡入，所有风格通用）=====
    // 进度取「时间（2000ms）」与「帧数（30 帧）」的较大者——启动初期渲染首批内容
    // （blob 着色器编译等）单帧可能耗时数百 ms，帧数下限保证淡入逐帧可见不跳变
    private var startupFadeStart = -1L
    private var startupFadeFrames = 0

    /** 绘制启动淡入黑罩（在主菜单 drawScreen 内容全部画完之后调用）；仅 splash 结束后首次进主菜单播一次。 */
    @JvmStatic
    fun drawStartupFade(width: Int, height: Int) {
        if (!StartupSplash.animationPlayed) return
        if (startupFadeStart == 0L) return
        if (startupFadeStart < 0L) startupFadeStart = System.currentTimeMillis()
        val p = maxOf(
            (System.currentTimeMillis() - startupFadeStart) / 2000f,
            startupFadeFrames / 30f
        )
        if (p >= 1f) {
            startupFadeStart = 0L
            return
        }
        startupFadeFrames++
        RenderUtils.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ((1f - p) * 255f).toInt() shl 24)
    }

    private fun settingsRect(width: Int, height: Int): FloatArray =
        floatArrayOf(width - SETTINGS_W - 10f, height - SETTINGS_H - 10f, SETTINGS_W, SETTINGS_H)

    /** 绘制右下角 Menu Settings 按钮（Flux 主菜单同款，点击进入主菜单设置界面统一切换风格与背景）。 */
    @JvmStatic
    fun drawSettingsButton(width: Int, height: Int, mouseX: Int, mouseY: Int) {
        val r = settingsRect(width, height)
        val x = r[0]
        val y = r[1]
        val hovered = mouseX >= x && mouseX < x + r[2] && mouseY >= y && mouseY < y + r[3]

        val add = RenderUtils.deltaTime * 0.2f
        settingsHoverAni = when {
            hovered && settingsHoverAni + add < 30f -> settingsHoverAni + add
            hovered -> 30f
            settingsHoverAni - add > 0f -> settingsHoverAni - add
            else -> 0f
        }

        val font = Fonts.fontFluxSans
        RenderUtils.drawRoundedRect(x, y, x + r[2], y + r[3], Color(0, 0, 0, 200).rgb, 2f)
        if (settingsHoverAni > 1f) {
            RenderUtils.drawRoundedRect(x, y, x + r[2], y + r[3], ((settingsHoverAni / 100f * 255f).toInt() shl 24), 2f)
        }
        font.drawCenteredString(
            "Menu Settings",
            x + r[2] / 2f,
            y + r[3] / 2f - font.height / 2f,
            0xCCFFFFFF.toInt(),
            true
        )

        // 启动淡入黑罩盖在所有内容之上（各非 Flux 风格统一从这里触发）
        drawStartupFade(width, height)
    }

    /** 处理右下角 Menu Settings 按钮点击，命中返回 true。左键打开主菜单设置界面（Flux 同款切换方式）。 */
    @JvmStatic
    fun handleSettingsClick(width: Int, height: Int, mouseX: Int, mouseY: Int, mouseButton: Int): Boolean {
        val r = settingsRect(width, height)
        if (mouseX < r[0] || mouseX > r[0] + r[2] || mouseY < r[1] || mouseY > r[1] + r[3]) {
            return false
        }

        if (mouseButton == 0) {
            Minecraft.getMinecraft().displayGuiScreen(GuiMainMenuSettings() as net.minecraft.client.gui.GuiScreen)
        }
        return true
    }
}
