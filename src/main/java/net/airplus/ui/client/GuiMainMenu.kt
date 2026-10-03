/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.ui.client

import net.airplus.AirPlus.CLIENT_NAME
import net.airplus.AirPlus.background
import net.airplus.AirPlus.clientVersionText
import net.airplus.api.ClientUpdate
import net.airplus.api.ClientUpdate.hasUpdate
import net.airplus.file.FileManager
import net.airplus.file.FileManager.backgroundFileFor
import net.airplus.file.FileManager.existingBackgroundFile
import net.airplus.ui.client.altmanager.GuiAltManager
import net.airplus.ui.font.Fonts
import net.airplus.utils.client.JavaVersion
import net.airplus.utils.client.javaVersion
import net.airplus.utils.io.FileFilters
import net.airplus.utils.io.MiscUtils
import net.airplus.utils.io.MiscUtils.showErrorPopup
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.render.shader.Background
import net.airplus.utils.render.shader.FluxBlobShader
import net.airplus.utils.ui.AbstractScreen
import net.minecraft.client.gui.GuiMultiplayer
import net.minecraft.client.gui.GuiOptions
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.GuiSelectWorld
import net.minecraft.client.resources.I18n
import org.lwjgl.input.Mouse
import java.awt.Color
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Flux 风格主菜单（移植自 Flux Client 直接改写的 net.minecraft.client.gui.GuiMainMenu）。
 * blob 着色器动态背景 + 中间竖排大按钮 + 右上角圆形小按钮 + 底部信息栏 + 启动加载动画。
 */
class GuiMainMenu : AbstractScreen() {

    private var popup: PopupScreen? = null
    private var blobShader: FluxBlobShader? = null

    private val buttons = ArrayList<MenuCardButton>()
    private val roundButtons = ArrayList<MenuCircleButton>()

    companion object {
        private var popupOnce = false
        var lastWarningTime: Long? = null
        private val warningInterval = TimeUnit.DAYS.toMillis(7)

        fun shouldShowWarning() = lastWarningTime == null || Instant.now().toEpochMilli() - lastWarningTime!! > warningInterval
    }

    init {
        if (!popupOnce) {
            javaVersion?.let {
                when {
                    it.major == 1 && it.minor == 8 && it.update < 100 -> showOutdatedJava8Warning()
                    it.major > 8 -> showJava11Warning()
                }
            }
            when {
                FileManager.firstStart -> showWelcomePopup()
                hasUpdate() -> showUpdatePopup()
                //shouldShowWarning() -> showDiscontinuedWarning()
            }
            popupOnce = true
        }
    }

    override fun initGui() {
        buttons.clear()
        roundButtons.clear()

        // 中间竖排大按钮（Flux: Single Player "K" / Multi Player "L" / Alt Manager "M"）
        buttons.add(MenuCardButton(GuiSelectWorld(this), I18n.format("menu.singleplayer"), "K"))
        buttons.add(MenuCardButton(GuiMultiplayer(this), I18n.format("menu.multiplayer"), "L"))
        buttons.add(MenuCardButton(GuiAltManager(this), "Alt Manager", "M"))

        // 右上角圆形小按钮（Flux: Options "N" / Quit Game "O"）+ AirPlus: 本地背景选择 "E"
        roundButtons.add(MenuCircleButton(GuiSettingsMenu(this), I18n.format("menu.options"), "N"))
        roundButtons.add(MenuCircleButton(null, I18n.format("menu.quit"), "O"))
        roundButtons.add(
            MenuCircleButton(
                null, "Background", "E",
                leftAction = { openBackgroundPicker() },
                rightAction = { resetBackground() }
            )
        )
    }

    /** 左键 "E"：从本地选择背景文件（.png 图片 或 .frag 片段着色器），复制到客户端目录并应用。 */
    private fun openBackgroundPicker() {
        val file = MiscUtils.openFileChooser(FileFilters.IMAGE, FileFilters.SHADER, acceptAll = false) ?: return

        // 替换前释放旧背景的 GL 资源
        background?.dispose()

        background = try {
            // 复制到客户端目录（保留扩展名），下次启动时由 FileManager.loadBackground 恢复
            val target = backgroundFileFor(file.extension)
            if (target.exists()) target.deleteRecursively()
            file.copyTo(target)

            try {
                Background.fromFile(target)
            } catch (e: Exception) {
                e.showErrorPopup()
                target.deleteRecursively()
                null
            }
        } catch (e: Exception) {
            e.showErrorPopup()
            null
        }
    }

    /** 右键 "E"：移除自定义背景，恢复默认内置 blob 着色器。 */
    private fun resetBackground() {
        background?.dispose()
        background = null
        existingBackgroundFile()?.deleteRecursively()
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        try {
            // Flux：不先画一个透明渐变就会白屏（原注释：不绘制这个他就给我白屏了 Strange）
            drawGradientRect(0, 0, this.width, this.height, 0x00FFFFFF, 0x00FFFFFF)

            // 背景：用户选择的本地 .png/.frag 优先；否则内置 blob 着色器动态气泡背景（编译/渲染失败退回深色纯色）
            val customBackground = background
            if (customBackground != null) {
                customBackground.drawBackground(this.width, this.height)
            } else {
                var shader = blobShader
                if (shader == null) {
                    shader = FluxBlobShader()
                    blobShader = shader
                }
                if (shader.isAvailable) {
                    shader.renderShader(this.width, this.height)
                } else {
                    RenderUtils.drawRect(0F, 0F, width.toFloat(), height.toFloat(), Color(18, 18, 22).rgb)
                }
            }

            // 整体压暗 10%（Flux 同款）
            RenderUtils.drawRect(0, 0, width, height, reAlpha(0x000000, 0.1f))

            // 中间竖排大按钮
            var startY = height / 2f - (buttons.size * 30) / 2f
            for (b in buttons) {
                b.draw(width / 2f - 75f, startY, mouseX, mouseY)
                startY += 30f
            }

            // 右上角圆形小按钮横排
            var startX = width - 8f - (roundButtons.size * 36)
            for (b in roundButtons) {
                b.draw(startX, 12f, mouseX, mouseY)
                startX += 36f
            }

            // 左下信息栏（alpha 0.6 白字，Flux 同款）
            val infoColor = reAlpha(0xFFFFFF, 0.6f)
            Fonts.fontFluxRoboto.drawStringWithShadow("$CLIENT_NAME $clientVersionText", 10f, height - 35f, infoColor)
            Fonts.fontFluxRoboto.drawStringWithShadow("Copyright Mojang AB. Do not distribute!", 10f, height - 25f, infoColor)
            Fonts.fontFluxRoboto.drawStringWithShadow("Minecraft 1.8.9", 10f, height - 15f, infoColor)

            // 右下 Welcome（Flux 显示登录用户名，这里显示当前游戏会话玩家名）
            val welcome = "Welcome, " + (mc.session?.username ?: "Player")
            Fonts.fontFluxRobotoL.drawStringWithShadow(welcome, width - Fonts.fontFluxRobotoL.getStringWidth(welcome) - 11f, height - 35f, infoColor)
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        super.drawScreen(mouseX, mouseY, partialTicks)

        if (popup != null) {
            popup!!.drawScreen(width, height, mouseX, mouseY)
        }
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        if (popup != null) {
            popup!!.mouseClicked(mouseX, mouseY, mouseButton)
            return
        }

        if (mouseButton == 0 || mouseButton == 1) {
            for (b in buttons) {
                b.onClick(mouseButton)
            }
            for (b in roundButtons) {
                b.onClick(mouseButton)
            }
        }

        super.mouseClicked(mouseX, mouseY, mouseButton)
    }

    override fun handleMouseInput() {
        if (popup != null) {
            val eventDWheel = Mouse.getEventDWheel()
            if (eventDWheel != 0) {
                popup!!.handleMouseWheel(eventDWheel)
            }
        }

        super.handleMouseInput()
    }

    /**
     * Flux "Buton"：150×25 黑色半透明卡片按钮，sans 文字居中 + icon 字形，hover 黑罩渐入。
     * screen == null 时点击退出游戏（Flux Quit Game 行为）。
     */
    private inner class MenuCardButton(
        val screen: GuiScreen?,
        val text: String,
        val icon: String
    ) {
        var x = 0f
        var y = 0f
        var isHovered = false
        var hoverAni = 0f

        fun draw(x: Float, y: Float, mouseX: Int, mouseY: Int) {
            this.x = x
            this.y = y

            isHovered = isHovering(mouseX, mouseY, x, y, x + 150f, y + 25f)
            hoverAni = getAnimationState(hoverAni, if (isHovered) 30f else 0f, 200f)
            val finalAni = (hoverAni / 100f).coerceIn(0f, 1f)

            RenderUtils.drawRect(x, y, x + 150f, y + 25f, Color(0, 0, 0, 200).rgb)
            Fonts.fontFluxSans.drawCenteredString(
                text,
                x + 75f,
                y + 12.5f - Fonts.fontFluxSans.height / 2f,
                0xFFFFFF,
                true
            )
            Fonts.fontFluxIcon.drawStringWithShadow(icon, x + 10f, y + 12.5f - Fonts.fontFluxIcon.height / 2f, 0xFFFFFF)

            if (hoverAni > 1f) {
                RenderUtils.drawRoundedRect(x, y, x + 150f, y + 25f, reAlpha(0x000000, finalAni), 2f)
            }
        }

        fun onClick(mouseButton: Int) {
            if (isHovered && mouseButton == 0) {
                if (screen == null) {
                    mc.shutdown()
                } else {
                    mc.displayGuiScreen(screen)
                }
            }
        }
    }

    /**
     * Flux "RoundButton"：28×28 圆形小按钮，icon 字形居中，hover 黑罩渐入。
     * leftAction / rightAction 优先于 screen 跳转（用于行为型按钮，如背景选择）。
     */
    private inner class MenuCircleButton(
        val screen: GuiScreen?,
        val text: String,
        val icon: String,
        val leftAction: (() -> Unit)? = null,
        val rightAction: (() -> Unit)? = null
    ) {
        var x = 0f
        var y = 0f
        var isHovered = false
        var alphaAni = 0f

        fun draw(x: Float, y: Float, mouseX: Int, mouseY: Int) {
            this.x = x
            this.y = y

            isHovered = isHovering(mouseX, mouseY, x, y, x + 28f, y + 28f)
            alphaAni = getAnimationState(alphaAni, if (isHovered) 30f else 0f, 200f)
            val finalAni = (alphaAni / 100f).coerceIn(0f, 1f)

            RenderUtils.drawFilledCircle((x + 14f).toInt(), (y + 14f).toInt(), 14f, Color(0, 0, 0, 200))
            RenderUtils.drawCircle(x + 14f, y + 14f, 14f, 0.5f, 0, 360, Color(0, 0, 0, 200))
            Fonts.fontFluxIcon20.drawStringWithShadow(
                icon,
                x + 14f - Fonts.fontFluxIcon20.getStringWidth(icon) / 2f,
                y + 14f - Fonts.fontFluxIcon20.height / 2f,
                0xFFFFFF
            )

            if (alphaAni > 1f) {
                RenderUtils.drawFilledCircle((x + 14f).toInt(), (y + 14f).toInt(), 14f, Color(0, 0, 0, (finalAni * 255).toInt()))
            }
        }

        fun onClick(mouseButton: Int) {
            if (!isHovered) {
                return
            }
            when {
                mouseButton == 0 && leftAction != null -> leftAction.invoke()
                mouseButton == 1 && rightAction != null -> rightAction.invoke()
                mouseButton == 0 && screen == null -> mc.shutdown()
                mouseButton == 0 -> mc.displayGuiScreen(screen)
            }
        }
    }

    // ---------- Flux 工具函数（内联移植） ----------

    /** Flux AnimationUtils.getAnimationState：delta-time 步进动画（RenderUtils.deltaTime 毫秒）。 */
    private fun getAnimationState(animation: Float, finalState: Float, speed: Float): Float {
        val add = RenderUtils.deltaTime * (speed / 1000f)
        var ani = animation
        if (ani < finalState) {
            ani = if (ani + add < finalState) ani + add else finalState
        } else if (ani - add > finalState) {
            ani -= add
        } else {
            ani = finalState
        }
        return ani
    }

    private fun isHovering(mouseX: Int, mouseY: Int, x: Float, y: Float, x2: Float, y2: Float): Boolean =
        mouseX >= x && mouseX < x2 && mouseY >= y && mouseY < y2

    private fun reAlpha(color: Int, alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (color and 0x00FFFFFF)

    // ---------- popup 系统（AirPlus 保留功能） ----------

    private fun showWelcomePopup() {
        popup = PopupScreen {
            title("§a§l欢迎使用AirPlus!")
            message("""
            感谢下载和使用 §b$CLIENT_NAME§e!
            当前客户端版本: §b$clientVersionText§e

            获取支持或报告问题:加入QQ群聊722573066
            """.trimIndent())
            button("§aOK")
            onClose { popup = null }
        }
    }

    private fun showUpdatePopup() {
        val newestVersion = ClientUpdate.newestVersion ?: return

        val dateFormatter = SimpleDateFormat("EEEE, MMMM dd, yyyy, h a z", Locale.ENGLISH)
        val newestVersionDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH).parse(newestVersion.publishedAt)
        val formattedNewestDate = newestVersionDate?.let { dateFormatter.format(it) } ?: newestVersion.publishedAt
        popup = PopupScreen {
            title("§bNew Update Available!")
            message("""
                §eA new ${if (newestVersion.prerelease) "pre-release" else "version"} of $CLIENT_NAME is available!

                - §aVersion:§r ${newestVersion.tagName}
                - §aDate:§r $formattedNewestDate

                §6Changes:§r
                ${newestVersion.body ?: ""}

                §bUpgrade now to enjoy the latest features and improvements!§r
            """.trimIndent())
            button("§aDownload") { MiscUtils.showURL(newestVersion.htmlUrl) }
            onClose { popup = null }
        }
    }

    private fun showOutdatedJava8Warning() {
        popup = PopupScreen {
            title("§c§lOutdated Java Runtime Environment")
            message("""
                §6§lYou are using an outdated version of Java 8 (${javaVersion!!.raw}).§r

                §fThis might cause unexpected §c§lBUGS§f.
                Please update it to 8u101+, or get a new one from the Internet.
            """.trimIndent())
            button("§aDownload Java") { MiscUtils.showURL(JavaVersion.DOWNLOAD_PAGE) }
            button("§eI realized")
            onClose { popup = null }
        }
    }

    private fun showJava11Warning() {
        popup = PopupScreen {
            title("§c§lInappropriate Java Runtime Environment")
            message("""
                §6§lThis version of $CLIENT_NAME is designed for Java 8 environment.§r

                §fHigher versions of Java might cause bug or crash.
                You can get JRE 8 from the Internet.
            """.trimIndent())
            button("§aDownload Java") { MiscUtils.showURL(JavaVersion.DOWNLOAD_PAGE) }
            button("§eI realized")
            onClose { popup = null }
        }
    }
}
