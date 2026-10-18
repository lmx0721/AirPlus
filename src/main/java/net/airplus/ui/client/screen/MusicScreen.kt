// skid SetsunaClient
// 完整移植 SetsunaClient 的音乐工作台界面（库视图 + Now Playing 全屏视图）。
// 渲染层由 Skija 换成本项目 GuiScreen + OpenGL 固定管线；后端为同源 ncm 包。
package net.airplus.ui.client.screen

import net.airplus.features.module.modules.music.NetEaseMusic
import net.airplus.music.ncm.OptionsUtil
import net.airplus.music.ncm.api.CloudMusicApi
import net.airplus.music.ncm.music.AudioPlayer
import net.airplus.music.ncm.music.CloudMusic
import net.airplus.music.ncm.music.NcmLyrics
import net.airplus.music.ncm.music.QRCodeGenerator
import net.airplus.music.ncm.music.dto.Music
import net.airplus.music.ncm.music.dto.PlayList
import net.airplus.music.ncm.util.json.JsonUtils
import net.airplus.ui.font.Fonts
import net.airplus.ui.font.GameFontRenderer
import net.airplus.utils.render.RenderUtils
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.GuiTextField
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.util.ResourceLocation
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.GL11
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 音乐工作台界面（NetEase provider）。
 * 功能：窗口框架 + 侧栏导航 + Home/Playlist/Search/Liked/Daily 页 + 扫码登录
 * + 播放条（进度/音量/喜欢）+ Now Playing 全屏视图（歌词/频谱/队列）。
 */
class MusicScreen : GuiScreen() {

    companion object {
        private const val TITLE_HEIGHT = 36
        private const val PLAYER_HEIGHT = 48
        private const val ROW_HEIGHT = 34

        // ===== 固定 Midnight 配色（Setsuna 默认预设，不提供切换）=====
        private val ACCENT = 0xFF65DED2.toInt()
        private val SECONDARY = 0xFFFF6F61.toInt()
        private val BG = 0xFF080D0E.toInt()
        private val SURFACE = 0xFF0D1314.toInt()
        private val SIDEBAR = 0xFF0B1011.toInt()
        private val CARD = 0xD9182021.toInt()
        private val DANGER = 0xFFE05252.toInt()

        private val TEXT = 0xFFFFFFFF.toInt()
        private val TEXT_MUTED = 0x8FFFFFFF.toInt()
        private const val OUTLINE = 0x1FFFFFFF

        // UiTheme（Setsuna 源端）常量，仅搜索框使用
        private val SEARCH_TEXT = 0xFFF1F6F4.toInt()
        private val TEXT_FAINT = 0xFF677571.toInt()

        /** Setsuna 侧栏 / Now Playing 头部 Logo（迁移自 setsuna:textures/hud/watermark_logo.png）。 */
        private val LOGO_TEXTURE = ResourceLocation("minecraft", "airplus/setsuna_logo.png")

        // ===== 图标字形（music.ttf / icomoon.ttf / icon.ttf 字母字形）=====
        private const val CONTROL_PREVIOUS = "H"
        private const val CONTROL_PAUSE = "A"
        private const val CONTROL_PLAY = "B"
        private const val CONTROL_NEXT = "E"
        private const val MUSIC_HOME = "A"
        private const val MUSIC_SEARCH = "K"
        private const val MUSIC_LIKE = "C"
        private const val MUSIC_DAILY = "D"
        private const val MUSIC_VOLUME_LOW = "I"
        private const val MUSIC_VOLUME_HIGH = "J"
        private const val CLIENT_REFRESH = "D"
        private const val CLIENT_CLOSE = "p"

        private const val BLUR_STRENGTH = 10f
    }

    // ===== 基础数据结构 =====

    /** 矩形盒子（对应 Setsuna 的 UiControls.Box）。 */
    private class Box(val x: Float, val y: Float, val width: Float, val height: Float) {
        fun contains(mx: Float, my: Float): Boolean =
            mx >= x && mx < x + width && my >= y && my < y + height
    }

    /** 布局盒子集合（library 与 now playing 共用一套字段）。 */
    private class Layout(
        val frame: Box, val sidebar: Box, val main: Box, val content: Box,
        val player: Box, val back: Box, val avatar: Box, val navY: Float,
        val search: Box, val reload: Box, val logout: Box,
        val qrCode: Box, val qrLogin: Box,
        val progress: Box, val playerCover: Box,
        val previous: Box, val pause: Box, val next: Box,
        val like: Box, val time: Box, val volume: Box,
        val trackTextWidth: Float, val compact: Boolean
    )

    private enum class Page { HOME, SEARCH, LIKED, DAILY, PLAYLIST }

    private enum class Action {
        BACK, NOW_PLAYING, LIBRARY, PAGE, PLAYLIST, PLAY_SONG,
        PLAY_ALL, SHUFFLE, PREVIOUS, PAUSE, NEXT, LIKE, RELOAD, LOGOUT,
        QR_LOGIN, PROGRESS, VOLUME
    }

    private class ClickRegion(val action: Action, val payload: Any?, val box: Box)
    private class SongClick(val source: List<Music>, val index: Int)

    /** 需要二次确认的危险操作。 */
    private enum class ConfirmKind { RELOAD, LOGOUT }

    /** 封面纹理缓存条目（保存真实尺寸用于 cover-fit 裁剪）。 */
    private class CoverImage(val loc: ResourceLocation, val width: Int, val height: Int)

    // ===== 状态 =====

    private var searchBox: GuiTextField? = null
    private val clickRegions = ArrayList<ClickRegion>()
    private val coverImages = LinkedHashMap<String, CoverImage>(64, 0.75f, true) // accessOrder LRU
    private val loadingCovers = HashSet<String>()
    private val coverRetryAt = HashMap<String, Long>()

    private var page = Page.HOME
    private var selectedPlaylist: PlayList? = null
    private var recommendations: List<PlayList> = emptyList()
    private var homeSongs: List<Music> = emptyList()
    private var visibleSongs: List<Music> = emptyList()
    private var playlistSongs: List<Music> = emptyList()
    private var contentScroll = 0f
    private var contentScrollTarget = 0f
    private var confirmPending: ConfirmKind? = null
    private var confirmYesBox = Box(0f, 0f, 0f, 0f)
    private var confirmNoBox = Box(0f, 0f, 0f, 0f)
    private var searching = false
    private var accountBusy = false
    private var playQueued = false
    private var loadingPage = false
    private var loadingHome = false
    private var draggingProgress = false
    private var draggingVolume = false
    private var pendingSeek = -1f
    private var searchGeneration = 0
    private var pageRequestGeneration = 0
    private var status = "Ready"
    private var statusError = false
    private var disposed = false
    private var qrLoginState: CloudMusic.QrLoginState = CloudMusic.QrLoginState.IDLE
    private var qrLoginThread: Thread? = null
    private var qrTexture: ResourceLocation? = null
    private var qrTextureAddress = ""
    private var qrTextureWidth = 0
    private var qrTextureHeight = 0
    private var nowPlayingView = false
    private var nowPlayingTransition = 0f
    private var lastFrameNanos = System.nanoTime()
    private var preferredVolume = 0.25f
    private var preferredVolumeChanged = false
    private var volumePlayer: AudioPlayer? = null
    private val spectrumBars = FloatArray(56)
    private var panelTransition = 0f
    private var pageTransition = 1.0f
    private var navHighlightY = Float.NaN
    private var frameDelta = 0.05f
    private var closing = false
    private var closeReady = false

    // 鼠标浮点坐标（供命中判定使用）
    private var mouseXF = 0f
    private var mouseYF = 0f

    // ===== 生命周期 =====

    override fun initGui() {
        disposed = false
        val box = GuiTextField(0, fontRendererObj, 0, 0, 1, 1)
        box.maxStringLength = 128
        searchBox = box
        updateInputBounds(libraryLayout())
        initializeAccount()
    }

    override fun doesGuiPauseGame() = false

    override fun onGuiClosed() {
        disposed = true
        cancelQrLogin()
        closeQrImage()
        QRCodeGenerator.clear()
        synchronized(coverImages) {
            for (img in coverImages.values) {
                try {
                    mc.textureManager.deleteTexture(img.loc)
                } catch (ignored: Exception) {
                }
            }
            coverImages.clear()
        }
        loadingCovers.clear()
        super.onGuiClosed()
    }

    // ===== 布局 =====

    private fun windowFrame(): Box {
        val availableWidth = max(1f, width - 16f)
        val availableHeight = max(1f, height - 16f)
        var frameWidth = min(640f, max(320f, width * 0.72f))
        var frameHeight = min(430f, max(220f, height * 0.82f))
        frameWidth = min(frameWidth, availableWidth)
        frameHeight = min(frameHeight, availableHeight)
        return Box((width - frameWidth) * 0.5f, (height - frameHeight) * 0.5f, frameWidth, frameHeight)
    }

    private fun libraryLayout(): Layout {
        val frame = windowFrame()
        val frameWidth = frame.width
        val frameHeight = frame.height
        val x = frame.x
        val y = frame.y
        val compact = frameHeight < 250 || frameWidth < 430
        val sideWidth = (frameWidth * 0.11f).coerceIn(if (compact) 56f else 62f, 76f)
        val sidebar = Box(x, y, sideWidth, frameHeight)
        val main = Box(x + sideWidth, y, frameWidth - sideWidth, frameHeight)
        val player = Box(main.x, y + frameHeight - PLAYER_HEIGHT, main.width, PLAYER_HEIGHT.toFloat())
        val content = Box(main.x, y + TITLE_HEIGHT, main.width,
            max(1f, player.y - (y + TITLE_HEIGHT)))
        val back = Box(x + frameWidth - 27, y + 8, 18f, 18f)

        val avatarSize = if (compact) 20f else 25f
        val avatar = Box(sidebar.x + (sidebar.width - avatarSize) * 0.5f,
            y + frameHeight - PLAYER_HEIGHT - avatarSize - 12, avatarSize, avatarSize)
        val navY = y + TITLE_HEIGHT + 14
        val searchWidth = min(210f, max(72f, min(main.width * 0.48f, main.width - 155)))
        val search = Box(main.x + 13, y + 9, searchWidth, 18f)
        val reload = Box(sidebar.x + 8, y + frameHeight - 27, 20f, 18f)
        val logout = Box(sidebar.x + sidebar.width - 28, y + frameHeight - 27, 20f, 18f)

        val loginWidth = min(286f, max(120f, content.width - 30))
        val loginX = content.x + (content.width - loginWidth) * 0.5f
        val qrSize = (content.height - 80).coerceIn(64f, 112f)
        val loginHeight = qrSize + 65
        val loginY = content.y + max(5f, (content.height - loginHeight) * 0.5f)
        val qrCode = Box(content.x + (content.width - qrSize) * 0.5f, loginY + 22, qrSize, qrSize)
        val qrLogin = Box(loginX, qrCode.y + qrSize + 6, loginWidth, 21f)

        val progress = Box(player.x + 8, player.y + 2, player.width - 16, 2f)
        val playerCover = Box(player.x + 8, player.y + 9, 31f, 31f)
        // 源端布局：暂停键正中在 player 宽度 54% 处，上一首/下一首各偏 30
        val controlsCenter = player.x + player.width * 0.54f
        val previous = Box(controlsCenter - 38f, player.y + 12, 20f, 20f)
        val pause = Box(controlsCenter - 8f, player.y + 12, 20f, 20f)
        val next = Box(controlsCenter + 22f, player.y + 12, 20f, 20f)
        val compactPlayer = player.width < 300
        val rightX = player.x + player.width - (if (compactPlayer) 53f else 69f)
        val like = if (compactPlayer)
            Box(player.x + player.width - 39, player.y + 8, 17f, 17f)
        else
            Box(rightX - 38, player.y + 9, 18f, 18f)
        val time = if (compactPlayer)
            Box(player.x + player.width - 1, player.y + 7, 0f, 12f)
        else
            Box(rightX, player.y + 7, 61f, 12f)
        val volume = Box(rightX, player.y + 28, if (compactPlayer) 45f else 56f, 3f)
        val trackTextWidth = max(24f, previous.x - (playerCover.x + playerCover.width + 12))
        return Layout(frame, sidebar, main, content, player, back, avatar, navY, search, reload, logout,
            qrCode, qrLogin, progress, playerCover, previous, pause, next, like, time, volume,
            trackTextWidth, compact)
    }

    private fun nowPlayingLayout(): Layout {
        val frame = windowFrame()
        val frameWidth = frame.width
        val frameHeight = frame.height
        val compact = frameWidth < 520 || frameHeight < 260
        val queueWidth = if (compact) 0f else (frameWidth * 0.245f).coerceIn(112f, 190f)
        val leftWidth = (frameWidth * (if (compact) 0.32f else 0.29f)).coerceIn(92f, 215f)
        val centerX = frame.x + 18 + min(leftWidth - 18, min(leftWidth - 18,
            max(72f, frameHeight - 110))) + (if (compact) 12f else 22f)
        val centerRight = if (queueWidth > 0) frame.x + frameWidth - queueWidth - 18
        else frame.x + frameWidth - 12
        val centerWidth = max(92f, centerRight - centerX)
        val controlsY = frame.y + frameHeight - 39
        val controlsCenter = centerX + centerWidth * 0.5f
        // 源端布局：暂停键（26×26）正中，上一首/下一首（22×22）各偏 34
        val previous = Box(controlsCenter - 46f, controlsY, 22f, 22f)
        val pause = Box(controlsCenter - 12f, controlsY - 2f, 26f, 26f)
        val next = Box(controlsCenter + 26f, controlsY, 22f, 22f)
        val progress = Box(centerX, controlsY - 10, centerWidth, 2f)
        val volume = Box(centerRight - min(62f, centerWidth * 0.34f),
            frame.y + frameHeight - 10, min(62f, centerWidth * 0.34f), 2f)
        val close = Box(frame.x + frameWidth - 30, frame.y + 7, 20f, 20f)
        val all = frame
        val empty = Box(0f, 0f, 0f, 0f)
        return Layout(frame, empty, all, all, empty, close, empty, 0f, empty, empty, empty,
            empty, empty, progress, empty, previous, pause, next, empty, empty, volume,
            centerWidth, compact)
    }

    private fun layout(): Layout =
        if (nowPlayingView || nowPlayingTransition > 0.5f) nowPlayingLayout() else libraryLayout()

    private fun updateInputBounds(layout: Layout) {
        val box = searchBox ?: return
        box.xPosition = layout.search.x.roundToInt()
        box.yPosition = layout.search.y.roundToInt()
        box.width = max(1, layout.search.width.roundToInt())
        box.height = max(1, layout.search.height.roundToInt())
    }

    // ===== 绘制主流程 =====

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        mouseXF = mouseX.toFloat()
        mouseYF = mouseY.toFloat()
        updateViewTransition()
        // 滚动动画：内容区滚动值向目标值平滑过渡
        contentScroll += (contentScrollTarget - contentScroll) * min(1f, frameDelta * 14f)
        if (Math.abs(contentScrollTarget - contentScroll) < 0.05f) {
            contentScroll = contentScrollTarget
        }
        val libraryLayout = libraryLayout()
        updateInputBounds(libraryLayout)
        clickRegions.clear()

        drawDefaultBackground()
        HudBlurCompat()

        // 全屏暗化底色（Setsuna 的 backdropColor）
        RenderUtils.drawRect(0f, 0f, width.toFloat(), height.toFloat(), setAlpha(BG, 184))

        val panelEase = easeOutCubic(panelTransition)
        val panelScale = 0.955f + panelEase * 0.045f
        GlStateManager.pushMatrix()
        GlStateManager.translate(width * 0.5, height * 0.5, 0.0)
        GlStateManager.scale(panelScale.toDouble(), panelScale.toDouble(), 1.0)
        GlStateManager.translate(-(width * 0.5).toDouble(), -(height * 0.5).toDouble(), 0.0)
        drawLibrary(libraryLayout)
        if (nowPlayingTransition > 0.001f && CloudMusic.currentlyPlaying != null) {
            clickRegions.clear()
            val eased = easeOutCubic(nowPlayingTransition)
            val nowLayout = nowPlayingLayout()
            GlStateManager.pushMatrix()
            GlStateManager.translate(0.0, ((1f - eased) * max(1f, height.toFloat())).toDouble(), 0.0)
            drawNowPlayingShadowed(nowLayout)
            GlStateManager.popMatrix()
            if (!nowPlayingView || nowPlayingTransition < 0.92f) {
                clickRegions.clear()
            }
        }
        GlStateManager.popMatrix()
        if (panelTransition < 0.92f || closing) {
            clickRegions.clear()
        }
        // 确认弹窗最顶层绘制（不参与面板缩放）
        if (confirmPending != null) {
            drawConfirmDialog()
        }
    }

    /** 刷新账号 / 退出登录的确认弹窗。 */
    private fun drawConfirmDialog() {
        val kind = confirmPending ?: return
        val w = 214f
        val h = 94f
        val x = (width - w) * 0.5f
        val y = (height - h) * 0.5f

        RenderUtils.drawRect(0f, 0f, width.toFloat(), height.toFloat(), setAlpha(BG, 130))
        RenderUtils.drawRoundedRect(x, y, x + w, y + h, 0xF8141819.toInt(), 6f)
        RenderUtils.drawRoundedRect(x + 1, y + 1, x + w - 1, y + 1.6f, setAlpha(ACCENT, 110), 0f)

        val title = if (kind == ConfirmKind.RELOAD) "Reload Account?" else "Log Out?"
        val desc = if (kind == ConfirmKind.RELOAD) "Re-sync the current NetEase account."
        else "Your cookie will be cleared on this device."
        drawBoldText(title, x + 14, y + 12, 15f, TEXT, 10f)
        drawTextAt(desc, x + 14, y + 32, 11f, TEXT_MUTED, 7.5f)

        val buttonWidth = (w - 28 - 6) * 0.5f
        confirmYesBox = Box(x + 14, y + h - 33, buttonWidth, 24f)
        confirmNoBox = Box(confirmYesBox.x + buttonWidth + 6, y + h - 33, buttonWidth, 24f)
        val yesHovered = confirmYesBox.contains(mouseXF, mouseYF)
        val noHovered = confirmNoBox.contains(mouseXF, mouseYF)
        RenderUtils.drawRoundedRect(confirmYesBox.x, confirmYesBox.y,
            confirmYesBox.x + confirmYesBox.width, confirmYesBox.y + confirmYesBox.height,
            setAlpha(ACCENT, if (yesHovered) 72 else 48), 4f)
        RenderUtils.drawRoundedRect(confirmNoBox.x, confirmNoBox.y,
            confirmNoBox.x + confirmNoBox.width, confirmNoBox.y + confirmNoBox.height,
            if (noHovered) 0x2AFFFFFF else 0x18FFFFFF, 4f)
        drawCenteredText("Confirm", confirmYesBox, ACCENT, false, 8f)
        drawCenteredText("Cancel", confirmNoBox, TEXT_MUTED, false, 8f)
    }

    /** 兼容包装：模糊当前游戏画面（不支持时静默跳过）。 */
    private fun HudBlurCompat() {
        try {
            net.airplus.utils.render.HudBlur.blur(0f, 0f, width.toFloat(), height.toFloat(),
                BLUR_STRENGTH, "InternalBlur")
        } catch (ignored: Throwable) {
        }
    }

    /** Now Playing 面板：外圈阴影 + 圆角裁剪内绘制。 */
    private fun drawNowPlayingShadowed(layout: Layout) {
        val f = layout.frame
        // 近似 dropShadow：两层外扩半透明黑
        RenderUtils.drawRoundedRect(f.x - 5f, f.y - 5f, f.x + f.width + 5f, f.y + f.height + 5f,
            0x30000000, 10f)
        RenderUtils.drawRoundedRect(f.x - 2f, f.y - 2f, f.x + f.width + 2f, f.y + f.height + 2f,
            0x60000000, 8f)
        RenderUtils.withClipping(
            { RenderUtils.drawRoundedRect(f.x, f.y, f.x + f.width, f.y + f.height, BG, 6f) },
            { drawNowPlaying(layout) })
    }

    private fun drawLibrary(layout: Layout) {
        val f = layout.frame
        // 窗口外圈阴影
        RenderUtils.drawRoundedRect(f.x - 5f, f.y - 5f, f.x + f.width + 5f, f.y + f.height + 5f,
            0x30000000, 10f)
        RenderUtils.drawRoundedRect(f.x - 2f, f.y - 2f, f.x + f.width + 2f, f.y + f.height + 2f,
            0x60000000, 8f)
        RenderUtils.withClipping(
            { RenderUtils.drawRoundedRect(f.x, f.y, f.x + f.width, f.y + f.height, BG, 6f) },
            {
                drawWindow(layout)
                drawSidebar(layout)
                drawTopBar(layout)
                // 内容区用 scissor 裁剪（避免与外层 stencil 裁剪嵌套冲突）
                val c = layout.content
                GlStateManager.pushMatrix()
                GL11.glEnable(GL11.GL_SCISSOR_TEST)
                RenderUtils.makeScissorBox(c.x, c.y, c.x + c.width, c.y + c.height)
                val pageEase = easeOutCubic(pageTransition)
                GlStateManager.pushMatrix()
                GlStateManager.translate(((1f - pageEase) * 12.0).toDouble(), 0.0, 0.0)
                if (!hasAccount()) {
                    drawLogin(layout)
                } else {
                    drawContent(layout)
                }
                GlStateManager.popMatrix()
                GL11.glDisable(GL11.GL_SCISSOR_TEST)
                GlStateManager.popMatrix()
                drawPlayer(layout)
            })
    }

    private fun drawWindow(layout: Layout) {
        val f = layout.frame
        RenderUtils.drawRoundedRect(f.x, f.y, f.x + f.width, f.y + f.height, BG, 6f)
        RenderUtils.drawRect(layout.main.x, layout.main.y,
            layout.main.x + layout.main.width, layout.main.y + layout.main.height, SURFACE)
        RenderUtils.drawRect(layout.sidebar.x + layout.sidebar.width, f.y,
            layout.sidebar.x + layout.sidebar.width + 0.6f, f.y + f.height, OUTLINE)
        RenderUtils.drawGradientRect(f.x.toDouble(), f.y.toDouble(),
            (f.x + f.width).toDouble(), (f.y + 2).toDouble(), ACCENT, SECONDARY, 0f)
    }

    private fun drawTopBar(layout: Layout) {
        RenderUtils.drawRect(layout.main.x, layout.frame.y,
            layout.main.x + layout.main.width, layout.frame.y + TITLE_HEIGHT, BG)
        RenderUtils.drawRect(layout.main.x, layout.frame.y + TITLE_HEIGHT - 0.6f,
            layout.frame.x + layout.frame.width, layout.frame.y + TITLE_HEIGHT, OUTLINE)

        // 搜索框（UiControls.TextInput 样式：聚焦描边 accent / 悬停描边 TEXT_FAINT）
        drawSearchInput(layout)

        drawSmallIconButton(layout.back, CLIENT_CLOSE, true, false, 8f)
        clickRegions.add(ClickRegion(Action.BACK, null, layout.back))
    }

    /** UiControls.TextInput.draw：自绘搜索框（与 Setsuna 源端样式一致）。 */
    private fun drawSearchInput(layout: Layout) {
        val s = layout.search
        val box = searchBox
        val focused = box?.isFocused == true
        val hovered = s.contains(mouseXF, mouseYF)
        val outline = when {
            focused -> ACCENT
            hovered -> TEXT_FAINT
            else -> 0x26FFFFFF
        }
        RenderUtils.drawRoundedRect(s.x, s.y, s.x + s.width, s.y + s.height, outline, 4f)
        // 聚焦（选中）时填充更深的背景以突出输入状态
        RenderUtils.drawRoundedRect(s.x + 1, s.y + 1, s.x + s.width - 1, s.y + s.height - 1,
            if (focused) 0xE60D1415.toInt() else setAlpha(ACCENT, 24), 3f)

        val text = box?.text ?: ""
        if (text.isEmpty() && !focused) {
            drawTextAt("Search...", s.x + 5, s.y, s.height, TEXT_FAINT, 9f)
            return
        }
        val font = uiFont(9f)
        val available = max(0f, s.width - 10f)
        val textWidth = font.getStringWidth(text).toFloat()
        // 文本超宽时窗口向末尾对齐（近似源端 viewStart 滚动窗口）
        val drawX = s.x + 5f - max(0f, textWidth - available)
        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        RenderUtils.makeScissorBox(s.x + 1, s.y + 1, s.x + s.width - 1, s.y + s.height - 1)
        drawTextAt(text, drawX, s.y, s.height, SEARCH_TEXT, 9f)
        if (focused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
            val cursor = (box?.cursorPosition ?: text.length).coerceIn(0, text.length)
            val beforeCursor = text.substring(0, cursor)
            val cursorX = drawX + font.getStringWidth(beforeCursor)
            RenderUtils.drawRect(cursorX, s.y + 4, cursorX + 1, s.y + s.height - 4, SEARCH_TEXT)
        }
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
    }

    private fun drawSidebar(layout: Layout) {
        RenderUtils.drawRect(layout.sidebar.x, layout.frame.y, layout.sidebar.x + layout.sidebar.width,
            layout.frame.y + layout.frame.height, SIDEBAR)

        drawSetsunaLogo(layout.sidebar.x + (layout.sidebar.width - 21) * 0.5f,
            layout.frame.y + 8, 21f)

        val profile = profileName()
        val avatar = layout.avatar
        RenderUtils.drawRoundedRect(avatar.x, avatar.y, avatar.x + avatar.width, avatar.y + avatar.height,
            if (!hasAccount()) 0x22FFFFFF else setAlpha(ACCENT, 66), avatar.width * 0.5f)
        drawCenteredText(firstLetter(profile), avatar, if (!hasAccount()) TEXT_MUTED else TEXT, true, 8f)
        if (!layout.compact) {
            drawCenteredText(ell(profile, layout.sidebar.width - 8, 7f),
                Box(layout.sidebar.x + 4, avatar.y + avatar.height + 3, layout.sidebar.width - 8, 12f),
                TEXT_MUTED, false, 7f)
        }

        val navY = layout.navY
        val activeIndex = when (page) {
            Page.SEARCH -> 1
            Page.LIKED -> 2
            Page.DAILY -> 3
            Page.HOME, Page.PLAYLIST -> 0
        }
        val navTargetY = navY + activeIndex * 24
        if (navHighlightY.isNaN()) {
            navHighlightY = navTargetY
        }
        navHighlightY += (navTargetY - navHighlightY) * min(1f, frameDelta * 10f)
        RenderUtils.drawRoundedRect(layout.sidebar.x + 5, navHighlightY,
            layout.sidebar.x + layout.sidebar.width - 5, navHighlightY + 20,
            setAlpha(ACCENT, 42), 3f)
        drawNav(layout, MUSIC_HOME, "Home", Page.HOME, navY)
        drawNav(layout, MUSIC_SEARCH, "Search", Page.SEARCH, navY + 24)
        drawNav(layout, MUSIC_LIKE, "Like", Page.LIKED, navY + 48)
        drawNav(layout, MUSIC_DAILY, "Daily", Page.DAILY, navY + 72)

        drawSmallIconButton(layout.reload, CLIENT_REFRESH, !accountBusy && !playQueued, false, 9f)
        drawSmallIconButton(layout.logout, CLIENT_CLOSE, hasAccount() && !accountBusy && !playQueued, true, 8f)
        clickRegions.add(ClickRegion(Action.RELOAD, null, layout.reload))
        clickRegions.add(ClickRegion(Action.LOGOUT, null, layout.logout))
    }

    private fun drawNav(layout: Layout, glyph: String, title: String, target: Page, y: Float) {
        val box = Box(layout.sidebar.x + 6, y, layout.sidebar.width - 12, 20f)
        val active = page == target || (target == Page.HOME && page == Page.PLAYLIST)
        val hovered = box.contains(mouseXF, mouseYF)
        val hover = animateInteraction("nav:$title", hovered)
        val fill = if (active) 0x00000000 else withAlpha(0x24FFFFFF, hover)
        RenderUtils.drawRoundedRect(box.x, box.y, box.x + box.width, box.y + box.height, fill, 3f)
        val iconSize = 8f
        val textSize = 7f
        // 源端按行内容整体居中（图标+间距+文字作为一组），文字宽度不同只会让
        // 该行整体微调，图标与文字始终贴合（gap=4）
        val iconWidth = iconFont(iconSize).getStringWidth(glyph).toFloat()
        val textWidth = uiFont(textSize).getStringWidth(title).toFloat()
        val gap = 4f
        val startX = box.x + max(3f, (box.width - iconWidth - gap - textWidth) * 0.5f)
        val color = if (active) ACCENT else if (hovered) TEXT else 0xA8FFFFFF.toInt()
        drawIcon(glyph, startX, box.y, box.height, color, iconSize)
        drawTextAt(title, startX + iconWidth + gap, box.y, box.height, color, textSize)
        clickRegions.add(ClickRegion(Action.PAGE, target, box))
    }

    private fun drawLogin(layout: Layout) {
        syncQrImage()
        val content = layout.content
        val panelWidth = min(286f, max(120f, content.width - 30))
        val x = content.x + (content.width - panelWidth) * 0.5f
        drawBoldText("NetEase Cloud Music QR Login", x, layout.qrCode.y - 23, 18f, TEXT, 12f)

        val qr = layout.qrCode
        RenderUtils.drawRoundedRect(qr.x - 2, qr.y - 2, qr.x + qr.width + 2, qr.y + qr.height + 2,
            0x34FFFFFF, 4f)
        RenderUtils.drawRoundedRect(qr.x, qr.y, qr.x + qr.width, qr.y + qr.height, 0xFFFFFFFF.toInt(), 3f)
        val texture = qrTexture
        if (texture != null) {
            GlStateManager.color(1f, 1f, 1f, 1f)
            GlStateManager.enableTexture2D()
            GlStateManager.enableBlend()
            mc.textureManager.bindTexture(texture)
            val tw = max(1, qrTextureWidth).toFloat()
            val th = max(1, qrTextureHeight).toFloat()
            RenderUtils.drawModalRectWithCustomSizedTexture(qr.x, qr.y, 0f, 0f, qr.width, qr.height, tw, th)
            GlStateManager.color(1f, 1f, 1f, 1f)
        } else {
            drawCenteredText("...", qr, 0xFF101718.toInt(), true, 10f)
        }

        drawMusicButton(layout.qrLogin, if (accountBusy) "Refresh QR code" else "Generate QR code",
            !playQueued, true)
        val loginStatus = qrLoginStatus()
        drawTextAt(ell(loginStatus, panelWidth, 8f), x, layout.qrLogin.y + 25, 12f,
            if (statusError) DANGER else TEXT_MUTED, 8f)
        clickRegions.add(ClickRegion(Action.QR_LOGIN, null, layout.qrLogin))
    }

    private fun drawContent(layout: Layout) {
        when (page) {
            Page.HOME -> drawHome(layout)
            Page.PLAYLIST -> drawPlaylist(layout)
            Page.SEARCH, Page.LIKED, Page.DAILY -> drawSongPage(layout)
        }
    }

    private fun drawHome(layout: Layout) {
        val playlists = homePlaylists()
        if (homeSongs.isEmpty()) {
            primeHomeSongs()
        }

        val content = layout.content
        val padding = 14f
        val heroHeight = (content.height * 0.34f).coerceIn(72f, 126f)
        val cardGap = 8f
        val recCols = max(2, min(5, ((content.width - padding * 2) / 76).toInt()))
        val recSize = max(42f, (content.width - padding * 2 - cardGap * (recCols - 1)) / recCols)
        val songCount = min(homeSongs.size, 18)
        val totalHeight = heroHeight + recSize + 82 + songCount * ROW_HEIGHT
        clampScroll(content.height, totalHeight)
        val y = content.y + contentScroll

        val hero = Box(content.x + padding, y + 10, content.width - padding * 2, heroHeight - 10)
        RenderUtils.drawRoundedRect(hero.x, hero.y, hero.x + hero.width, hero.y + hero.height,
            0xFF121B1C.toInt(), 3f)
        val featured = if (homeSongs.isEmpty()) null else homeSongs[0]
        if (featured != null) {
            val artWidth = min(hero.width * 0.43f, hero.height * 1.55f)
            val art = Box(hero.x + hero.width - artWidth, hero.y, artWidth, hero.height)
            drawCover("music:" + featured.cacheKey(), featured.getCoverUrl(512), art, 3f)
            RenderUtils.drawGradientRect((art.x - 1).toDouble(), art.y.toDouble(),
                (art.x - 1 + art.width * 0.58f).toDouble(), (art.y + art.height).toDouble(),
                0xFF121B1C.toInt(), 0x00121B1C, 0f)
            drawTextAt("SETSUNA SELECTION", hero.x + 14, hero.y + 12, 10f, ACCENT, 6f)
            drawBoldText(ell(featured.name, hero.width * 0.5f, 15f), hero.x + 14, hero.y + 28, 24f, TEXT, 15f)
            drawTextAt(ell(featured.artistsName, hero.width * 0.48f, 7f), hero.x + 14, hero.y + 51, 13f,
                TEXT_MUTED, 7f)
            val heroPlay = Box(hero.x + 14, hero.y + hero.height - 28, 58f, 18f)
            drawMusicButton(heroPlay, "Play now", !playQueued, true)
            clickRegions.add(ClickRegion(Action.PLAY_SONG, SongClick(homeSongs, 0), heroPlay))
        } else {
            drawBoldText(if (loadingHome) "Loading your music" else "Your music, in one place",
                hero.x + 14, hero.y + 25, 26f, TEXT, 15f)
            drawTextAt(ell(displayStatus(), hero.width - 28, 7f), hero.x + 14, hero.y + 55, 14f,
                TEXT_MUTED, 7f)
        }

        val recTitleY = y + heroHeight + 9
        drawBoldText("For you", content.x + padding, recTitleY, 18f, TEXT, 11f)
        drawTextAt("CURATED PLAYLISTS", content.x + padding + 46, recTitleY + 4, 10f, 0x58FFFFFF, 5.5f)
        val recY = recTitleY + 21
        val recLimit = min(playlists.size, recCols)
        for (i in 0 until recLimit) {
            val playlist = playlists[i]
            val cardX = content.x + padding + i * (recSize + cardGap)
            val cover = Box(cardX, recY, recSize, recSize * 0.72f)
            if (!intersects(cover, content)) {
                continue
            }
            drawCover("playlist:" + playlist.cacheKey(), playlist.coverUrl, cover, 3f)
            if (cover.contains(mouseXF, mouseYF)) {
                RenderUtils.drawRect(cover.x, cover.y, cover.x + cover.width, cover.y + cover.height,
                    0x20000000)
                drawPlayDisc(cover.x + cover.width - 11, cover.y + cover.height - 11, 8f, TEXT,
                    0xD9080D0E.toInt())
            }
            drawTextAt(ell(playlist.name, recSize, 7f), cardX, cover.y + cover.height + 3, 12f, TEXT, 7f)
            clickRegions.add(ClickRegion(Action.PLAYLIST, playlist,
                Box(cardX, recY, recSize, cover.height + 15)))
        }

        val songsTitleY = recY + recSize * 0.72f + 24
        drawBoldText("Recently queued", content.x + padding, songsTitleY, 17f, TEXT, 10f)
        drawSongs(content.x + padding, songsTitleY + 20, content.width - padding * 2,
            homeSongs.subList(0, songCount), content)
    }

    private fun drawPlaylist(layout: Layout) {
        val playlist = selectedPlaylist
        if (playlist == null) {
            page = Page.HOME
            drawHome(layout)
            return
        }
        if (loadingPage && playlistSongs.isEmpty() && playlist.musics != null
            && !playlist.musics.isEmpty()) {
            playlistSongs = safeCopy(playlist.musics)
            loadingPage = false
            setStatus(playlistSongs.size.toString() + " songs", false)
        } else if (loadingPage && playlist.musics != null && playlist.musics.isEmpty()
            && (playlist.musicsLoaded || !playlist.musicsQueried)) {
            loadingPage = false
            setStatus(if (playlist.musicsLoaded) "No songs" else "Playlist load failed",
                !playlist.musicsLoaded)
        }
        val content = layout.content
        val totalHeight = 104f + playlistSongs.size * ROW_HEIGHT
        clampScroll(content.height, totalHeight)
        val y = content.y + contentScroll
        val cover = Box(content.x + 12, y + 12, 72f, 72f)
        RenderUtils.drawRoundedRect(cover.x, cover.y, cover.x + cover.width, cover.y + cover.height,
            CARD, 6f)
        drawCover("playlist:" + playlist.cacheKey(), playlist.coverUrl, cover, 6f)
        val textX = cover.x + cover.width + 12
        drawBoldText(ell(playlist.name, content.width - 108, 12f), textX, y + 19, 19f, TEXT, 12f)
        drawTextAt(playlistInfo(playlist), textX, y + 40, 12f, TEXT_MUTED, 8f)
        val playAll = Box(textX, y + 62, 50f, 20f)
        val shuffle = Box(textX + 56, y + 62, 62f, 20f)
        drawMusicButton(playAll, "Play", !playlistSongs.isEmpty() && !playQueued, true)
        drawMusicButton(shuffle, "Shuffle", !playlistSongs.isEmpty() && !playQueued, false)
        clickRegions.add(ClickRegion(Action.PLAY_ALL, null, playAll))
        clickRegions.add(ClickRegion(Action.SHUFFLE, null, shuffle))
        drawSongs(content.x + 10, y + 98, content.width - 20, playlistSongs, content)
    }

    private fun drawSongPage(layout: Layout) {
        val content = layout.content
        val totalHeight = 48f + visibleSongs.size * ROW_HEIGHT
        clampScroll(content.height, totalHeight)
        val y = content.y + contentScroll
        drawBoldText(pageTitle(), content.x + 12, y + 10, 18f, TEXT, 11f)
        val statusColor = if (statusError) DANGER else TEXT_MUTED
        drawTextAt(ell(if (loadingPage) "Loading..." else displayStatus(), content.width - 24, 7f),
            content.x + 12, y + 27, 12f, statusColor, 7f)
        drawSongs(content.x + 10, y + 47, content.width - 20, visibleSongs, content)
    }

    private fun drawSongs(x: Float, y: Float, width: Float, songs: List<Music>, viewport: Box) {
        if (songs.isEmpty()) {
            val emptyTop = max(y, viewport.y + 48)
            drawCenteredText(if (loadingPage) "Loading..." else "No songs",
                Box(x, emptyTop, width, max(24f, viewport.y + viewport.height - emptyTop)),
                TEXT_MUTED, false, 8f)
            return
        }
        for (i in songs.indices) {
            val music = songs[i]
            val rowY = y + i * ROW_HEIGHT
            if (rowY + 30 < viewport.y || rowY > viewport.y + viewport.height) {
                continue
            }
            val row = Box(x, rowY, width, 30f)
            val current = music == CloudMusic.currentlyPlaying
            val fill = if (current) setAlpha(ACCENT, 36)
            else if (row.contains(mouseXF, mouseYF)) 0x14FFFFFF else 0x00000000
            RenderUtils.drawRoundedRect(row.x, row.y, row.x + row.width, row.y + row.height, fill, 3f)
            val cover = Box(row.x + 5, row.y + 4, 22f, 22f)
            drawCover("music:" + music.cacheKey(), music.getCoverUrl(64), cover, 2f)
            drawTextAt(String.format("%02d", i + 1), row.x + 32, row.y + 9, 12f,
                if (current) ACCENT else 0x54FFFFFF, 5.5f)
            val durationWidth = 40f
            val titleWidth = if (row.width > 290) row.width * 0.58f - 55 else row.width - 102
            drawTextAt(ell(music.name, titleWidth, 8f), row.x + 48, row.y + 4, 13f, TEXT, 8f)
            drawTextAt(ell(music.artistsName, titleWidth, 7f), row.x + 48, row.y + 16, 11f, TEXT_MUTED, 7f)
            val durationX = row.x + row.width - durationWidth
            if (row.width > 290) {
                val quality = qualityLabel(music)
                val badgeWidth = uiFont(5.5f).getStringWidth(quality) + 8
                val badgeX = durationX - badgeWidth - 8
                val albumX = row.x + row.width * 0.58f
                val albumWidth = max(0f, badgeX - albumX - 7)
                if (music.album != null && albumWidth > 18) {
                    drawTextAt(ell(music.album.name, albumWidth, 6f), albumX, row.y + 9, 12f,
                        0x62FFFFFF, 6f)
                }
                RenderUtils.drawRoundedRect(badgeX, row.y + 8, badgeX + badgeWidth, row.y + 8 + 13,
                    setAlpha(SECONDARY, 31), 2f)
                drawCenteredText(quality, Box(badgeX, row.y + 8, badgeWidth.toFloat(), 13f),
                    SECONDARY, false, 5.5f)
            }
            drawTextAt(formatDuration(music.duration.toFloat()), durationX, row.y + 9, 12f, TEXT_MUTED, 7f)
            clickRegions.add(ClickRegion(Action.PLAY_SONG, SongClick(songs, i), row))
        }
    }

    // ===== 播放条 =====

    private fun drawPlayer(layout: Layout) {
        val playerBox = layout.player
        RenderUtils.drawRect(playerBox.x, playerBox.y, playerBox.x + playerBox.width,
            playerBox.y + playerBox.height, 0xFA090E0F.toInt())
        RenderUtils.drawRect(playerBox.x, playerBox.y, playerBox.x + playerBox.width,
            playerBox.y + 0.6f, OUTLINE)
        val current = CloudMusic.currentlyPlaying
        val player = if (accountBusy) null else CloudMusic.player
        val progress = playbackProgress(player)
        val progressBox = layout.progress
        val progressHot = progressBox.contains(mouseXF, mouseYF) || draggingProgress
        RenderUtils.drawRoundedRect(progressBox.x, progressBox.y, progressBox.x + progressBox.width,
            progressBox.y + progressBox.height,
            if (progressHot) 0x48FFFFFF else 0x20FFFFFF, 2f)
        if (progress > 0f) {
            RenderUtils.drawRoundedRect(progressBox.x, progressBox.y,
                progressBox.x + progressBox.width * progress, progressBox.y + progressBox.height, ACCENT, 2f)
        }
        clickRegions.add(ClickRegion(Action.PROGRESS, null,
            Box(progressBox.x, progressBox.y - 4, progressBox.width, 11f)))

        val cover = layout.playerCover
        RenderUtils.drawRoundedRect(cover.x, cover.y, cover.x + cover.width, cover.y + cover.height,
            CARD, 2f)
        if (current != null) {
            drawCover("music:" + current.cacheKey(), current.getCoverUrl(96), cover, 2f)
            clickRegions.add(ClickRegion(Action.NOW_PLAYING, null,
                Box(cover.x, cover.y, layout.trackTextWidth + cover.width + 8, cover.height)))
        }
        drawTextAt(ell(current?.name ?: "Not Playing", layout.trackTextWidth, 7f),
            cover.x + cover.width + 6, playerBox.y + 7, 12f, TEXT, 7f)
        drawTextAt(ell(current?.artistsName ?: "None", layout.trackTextWidth, 6f),
            cover.x + cover.width + 6, playerBox.y + 19, 11f, TEXT_MUTED, 6f)

        val playerReady = player != null && !playQueued
        drawPlayerButton(layout.previous, CONTROL_PREVIOUS, playerReady)
        drawPlayerButton(layout.pause,
            if (player != null && !player.isPausing) CONTROL_PAUSE else CONTROL_PLAY, playerReady)
        drawPlayerButton(layout.next, CONTROL_NEXT, playerReady)
        clickRegions.add(ClickRegion(Action.PREVIOUS, null, layout.previous))
        clickRegions.add(ClickRegion(Action.PAUSE, null, layout.pause))
        clickRegions.add(ClickRegion(Action.NEXT, null, layout.next))

        if (current != null) {
            val time = formatDuration(currentTime(player)) + " / " +
                    formatDuration(totalTime(current, player))
            if (layout.time.width > 2) {
                drawTextAt(ell(time, layout.time.width, 6f), layout.time.x, layout.time.y,
                    layout.time.height, 0xCCFFFFFF.toInt(), 6f)
            }
            drawLikeButton(layout.like, isLiked(current))
            clickRegions.add(ClickRegion(Action.LIKE, current, layout.like))
        }

        val volume = currentVolume(player)
        val volumeBox = layout.volume
        RenderUtils.drawRoundedRect(volumeBox.x, volumeBox.y, volumeBox.x + volumeBox.width,
            volumeBox.y + volumeBox.height, 0x38FFFFFF, 2f)
        RenderUtils.drawRoundedRect(volumeBox.x, volumeBox.y, volumeBox.x + volumeBox.width * volume,
            volumeBox.y + volumeBox.height, ACCENT, 2f)
        drawVolumeKnob(volumeBox, volume)
        // 图标框中心与音量条中心（volumeBox.y + height/2）严格对齐
        val volumeIcon = Box(volumeBox.x - 16, volumeBox.y + volumeBox.height * 0.5f - 7f, 13f, 14f)
        drawCenteredIcon(if (volume <= 0.01f) MUSIC_VOLUME_LOW else MUSIC_VOLUME_HIGH, volumeIcon,
            0xB8FFFFFF.toInt(), 9f)
        clickRegions.add(ClickRegion(Action.VOLUME, null,
            Box(volumeIcon.x, volumeBox.y - 7,
                volumeBox.x + volumeBox.width - volumeIcon.x + 3, 17f)))
    }

    // ===== Now Playing 全屏视图 =====

    private fun drawNowPlaying(layout: Layout) {
        val frame = layout.frame
        RenderUtils.drawRect(frame.x, frame.y, frame.x + frame.width, frame.y + frame.height, BG)
        RenderUtils.drawGradientRect(frame.x.toDouble(), frame.y.toDouble(),
            (frame.x + frame.width).toDouble(), (frame.y + frame.height).toDouble(),
            setAlpha(mixColor(SURFACE, ACCENT, 0.12f), 255), setAlpha(BG, 220), 0f)

        val current = CloudMusic.currentlyPlaying ?: run {
            nowPlayingView = false
            return
        }
        val player = if (accountBusy) null else CloudMusic.player
        NcmLyrics.ensureLoaded(current)

        val pad = if (layout.compact) 10f else 18f
        val headerHeight = 34f
        val queueWidth = if (layout.compact) 0f else (frame.width * 0.245f).coerceIn(112f, 190f)
        val queueX = frame.x + frame.width - queueWidth
        val leftWidth = (frame.width * (if (layout.compact) 0.32f else 0.29f)).coerceIn(92f, 215f)
        var coverSize = min(leftWidth - pad * 1.4f, max(72f, frame.height - headerHeight - 76))
        coverSize = min(coverSize, frame.height * 0.57f)
        val coverX = frame.x + pad
        val coverY = frame.y + headerHeight + max(10f,
            (frame.height - headerHeight - 48 - coverSize) * 0.43f)
        val cover = Box(coverX, coverY, coverSize, coverSize)

        drawNowPlayingHeader(layout, current)
        // 封面阴影（外扩近似）
        RenderUtils.drawRoundedRect(cover.x - 3f, cover.y - 3f, cover.x + cover.width + 3f,
            cover.y + cover.height + 3f, 0x98000000.toInt(), 4f)
        drawCover("music-large:" + current.id, current.getCoverUrl(1024), cover, 2f)
        RenderUtils.drawRect(cover.x, cover.y + cover.height - 19, cover.x + cover.width,
            cover.y + cover.height, 0xC4080D0E.toInt())
        drawTextAt("SETSUNA RECORDS", cover.x + 6, cover.y + cover.height - 13, 10f, 0xBFFFFFFF.toInt(), 5f)
        val albumCode = String.format("ST - %02d", max(1, CloudMusic.curIdx + 1))
        val codeFont = uiFont(5f)
        drawTextAt(albumCode, cover.x + cover.width - codeFont.getStringWidth(albumCode) - 6,
            cover.y + cover.height - 13, 10f, 0xBFFFFFFF.toInt(), 5f)
        if (current.album != null) {
            drawTextAt("ALBUM", cover.x, cover.y + cover.height + 9, 9f, ACCENT, 5f)
            drawBoldText(ell(current.album.name, cover.width, 9f), cover.x,
                cover.y + cover.height + 20, 16f, TEXT, 9f)
        }

        val centerX = cover.x + cover.width + (if (layout.compact) 12f else 22f)
        val centerRight = if (queueWidth > 0) queueX - 18 else frame.x + frame.width - pad
        val centerWidth = max(92f, centerRight - centerX)
        drawNowPlayingDetails(layout, current, player, centerX, coverY, centerWidth)

        if (queueWidth > 0) {
            drawQueue(current, Box(queueX, frame.y + headerHeight, queueWidth,
                frame.height - headerHeight))
        }
    }

    private fun drawNowPlayingHeader(layout: Layout, current: Music) {
        val frame = layout.frame
        drawSetsunaLogo(frame.x + 12, frame.y + 7, 20f)
        val now = "NOW PLAYING"
        val nowFont = uiFont(5f)
        drawTextAt(now, frame.x + (frame.width - nowFont.getStringWidth(now)) * 0.5f,
            frame.y + 6, 9f, 0x66FFFFFF, 5f)
        val radio = ell(current.name + " / Radio", frame.width * 0.28f, 6f)
        val radioFont = uiFont(6f)
        drawTextAt(radio, frame.x + (frame.width - radioFont.getStringWidth(radio)) * 0.5f,
            frame.y + 16, 12f, TEXT, 6f)
        val close = layout.back
        val hovered = close.contains(mouseXF, mouseYF)
        RenderUtils.drawRoundedBorder(close.x, close.y, close.x + close.width, close.y + close.height,
            0.7f, if (hovered) ACCENT else 0x32FFFFFF, close.height * 0.5f)
        drawCenteredText("v", close, if (hovered) ACCENT else TEXT_MUTED, true, 9f)
        clickRegions.add(ClickRegion(Action.LIBRARY, null, close))
        RenderUtils.drawRect(frame.x, frame.y + 34 - 0.6f, frame.x + frame.width,
            frame.y + 34, OUTLINE)
    }

    private fun drawNowPlayingDetails(layout: Layout, current: Music, player: AudioPlayer?,
                                      x: Float, y: Float, width: Float) {
        val queueSize = CloudMusic.playList.size
        val count = String.format("%02d / %02d", max(1, CloudMusic.curIdx + 1), max(1, queueSize))
        drawTextAt(count, x, y - 2, 11f, ACCENT, 5.5f)
        val titleSize = if (width < 150) 14f else 18f
        drawBoldText(ell(current.name, width - 24, titleSize), x, y + 12, 27f, TEXT, titleSize)
        drawTextAt(ell(current.artistsName, width - 24, 7f), x, y + 40, 14f, 0xB8FFFFFF.toInt(), 7f)
        val like = Box(x + width - 22, y + 16, 19f, 19f)
        drawLikeButton(like, isLiked(current))
        clickRegions.add(ClickRegion(Action.LIKE, current, like))

        val lyrics = NcmLyrics.getLines()
        val lyricY = y + 74
        if (lyrics.isEmpty()) {
            drawTextAt(if (NcmLyrics.isLoading()) "Loading lyrics..." else "Instrumental / No lyrics",
                x, lyricY + 15, 15f, 0x58FFFFFF, 8f)
        } else {
            val index = max(0, min(lyrics.size - 1, NcmLyrics.currentIndex(currentTime(player))))
            if (index > 0) {
                drawLyricLine(lyrics[index - 1].text(), x, lyricY, width, 0x38FFFFFF, 6.5f)
            }
            drawLyricLine(lyrics[index].text(), x, lyricY + 19, width, TEXT, 10f)
            if (index + 1 < lyrics.size) {
                drawLyricLine(lyrics[index + 1].text(), x, lyricY + 42, width, 0x50FFFFFF, 7f)
            }
        }

        val waveformY = layout.progress.y - 43
        drawWaveform(x, waveformY, width, 25f, player)
        val currentTime = currentTime(player)
        val totalTime = totalTime(current, player)
        val timeY = layout.progress.y - 15
        drawTextAt(formatDuration(currentTime), x, timeY, 10f, TEXT_MUTED, 5.5f)
        val remaining = "-" + formatDuration(max(0f, totalTime - currentTime))
        drawTextAt(remaining, x + width - uiFont(5.5f).getStringWidth(remaining), timeY, 10f,
            TEXT_MUTED, 5.5f)

        val progress = layout.progress
        RenderUtils.drawRoundedRect(progress.x, progress.y, progress.x + progress.width,
            progress.y + progress.height, 0x28FFFFFF, 2f)
        RenderUtils.drawRoundedRect(progress.x, progress.y, progress.x + progress.width * playbackProgress(player),
            progress.y + progress.height, ACCENT, 2f)
        clickRegions.add(ClickRegion(Action.PROGRESS, null,
            Box(progress.x, progress.y - 5, progress.width, 12f)))

        val ready = player != null && !playQueued
        drawPlayerButton(layout.previous, CONTROL_PREVIOUS, ready)
        val playFill = if (ready) ACCENT else 0x2AFFFFFF
        RenderUtils.drawRoundedRect(layout.pause.x, layout.pause.y, layout.pause.x + layout.pause.width,
            layout.pause.y + layout.pause.height, playFill, layout.pause.height * 0.5f)
        drawCenteredIcon(if (player != null && !player.isPausing) CONTROL_PAUSE else CONTROL_PLAY,
            layout.pause, if (ready) 0xFF061011.toInt() else 0x48FFFFFF, 11f, controlsIconFont(11f))
        drawPlayerButton(layout.next, CONTROL_NEXT, ready)
        clickRegions.add(ClickRegion(Action.PREVIOUS, null, layout.previous))
        clickRegions.add(ClickRegion(Action.PAUSE, null, layout.pause))
        clickRegions.add(ClickRegion(Action.NEXT, null, layout.next))

        val volume = layout.volume
        RenderUtils.drawRoundedRect(volume.x, volume.y, volume.x + volume.width,
            volume.y + volume.height, 0x28FFFFFF, 2f)
        RenderUtils.drawRoundedRect(volume.x, volume.y, volume.x + volume.width * currentVolume(player),
            volume.y + volume.height, ACCENT, 2f)
        drawVolumeKnob(volume, currentVolume(player))
        clickRegions.add(ClickRegion(Action.VOLUME, null,
            Box(volume.x - 7, volume.y - 7, volume.width + 14, 17f)))
    }

    private fun drawQueue(current: Music, queue: Box) {
        RenderUtils.drawRect(queue.x, queue.y, queue.x + queue.width, queue.y + queue.height,
            0x80070B0C.toInt())
        RenderUtils.drawRect(queue.x, queue.y, queue.x + 0.6f, queue.y + queue.height, OUTLINE)
        drawTextAt("UP NEXT", queue.x + 13, queue.y + 16, 10f, SECONDARY, 5f)
        drawBoldText("Play queue", queue.x + 13, queue.y + 30, 22f, TEXT, 13f)
        val songs = safeCopy(CloudMusic.playList)
        if (songs.isEmpty()) {
            drawTextAt("Queue is empty", queue.x + 13, queue.y + 60, 13f, TEXT_MUTED, 7f)
            return
        }
        val visibleRows = max(1, ((queue.height - 63) / 37).toInt())
        val start = max(0, min(CloudMusic.curIdx, songs.size - visibleRows))
        val rowY = queue.y + 58
        for (slot in 0 until visibleRows) {
            if (start + slot >= songs.size) break
            val index = start + slot
            val music = songs[index]
            val row = Box(queue.x + 8, rowY + slot * 37, queue.width - 16, 33f)
            val playing = music == current
            if (row.contains(mouseXF, mouseYF) || playing) {
                RenderUtils.drawRoundedRect(row.x, row.y, row.x + row.width, row.y + row.height,
                    if (playing) setAlpha(ACCENT, 31) else 0x0FFFFFFF, 2f)
            }
            val cover = Box(row.x + 21, row.y + 4, 25f, 25f)
            drawCover("music:" + music.cacheKey(), music.getCoverUrl(64), cover, 1f)
            drawTextAt(if (playing) "||" else String.format("%02d", index + 1), row.x + 2, row.y + 10,
                12f, if (playing) ACCENT else 0x48FFFFFF, 5f)
            drawTextAt(ell(music.name, row.width - 79, 6.5f), row.x + 51, row.y + 5, 12f,
                if (playing) ACCENT else TEXT, 6.5f)
            drawTextAt(ell(music.artistsName, row.width - 79, 5.5f), row.x + 51, row.y + 17, 10f,
                TEXT_MUTED, 5.5f)
            val duration = formatDuration(music.duration.toFloat())
            drawTextAt(duration, row.x + row.width - uiFont(5f).getStringWidth(duration) - 3,
                row.y + 11, 10f, 0x62FFFFFF, 5f)
            clickRegions.add(ClickRegion(Action.PLAY_SONG, SongClick(songs, index), row))
        }
    }

    private fun drawLyricLine(raw: String, x: Float, y: Float, width: Float, color: Int, size: Float) {
        drawTextAt(ell(raw, width, size), x, y, 17f, color, size)
    }

    private fun drawWaveform(x: Float, y: Float, width: Float, height: Float, player: AudioPlayer?) {
        val bars = max(18, min(spectrumBars.size, (width / 4.2f).toInt()))
        val step = width / bars
        val bands = AudioPlayer.bandValues
        val active = player != null && !player.isPausing && bands != null && bands.size > 1
        for (i in 0 until bars) {
            var target = if (active) spectrumLevel(bands, i, bars) else 0f
            target = (sqrt(max(0f, target)) * 1.18f).coerceIn(0f, 1f)
            val speed = if (target > spectrumBars[i]) 15f else 7f
            spectrumBars[i] += (target - spectrumBars[i]) * min(1f, frameDelta * speed)
            val amount = 0.08f + spectrumBars[i] * 0.92f
            val barHeight = max(2f, height * amount)
            val alpha = 80 + (spectrumBars[i] * 175f).roundToInt()
            val color = (alpha shl 24) or (ACCENT and 0x00FFFFFF)
            RenderUtils.drawRoundedRect(x + i * step, y + (height - barHeight) * 0.5f,
                x + i * step + max(1f, step * 0.48f), y + (height - barHeight) * 0.5f + barHeight,
                color, 1f)
        }
    }

    private fun spectrumLevel(bands: FloatArray, bar: Int, barCount: Int): Float {
        val fromRatio = (bar.toDouble() / barCount).pow(1.55).toFloat()
        val toRatio = ((bar + 1.0f) / barCount).pow(1.55f).toFloat()
        val from = max(0, min(bands.size - 1, (fromRatio * bands.size).toInt()))
        val to = max(from + 1, min(bands.size, ceil((toRatio * bands.size).toDouble()).toInt()))
        var peak = 0f
        for (index in from until to) {
            val value = bands[index]
            if (!value.isNaN() && !value.isInfinite()) {
                peak = max(peak, value)
            }
        }
        return peak
    }

    // ===== 通用控件 =====

    private fun drawPlayDisc(centerX: Float, centerY: Float, radius: Float, foreground: Int, background: Int) {
        RenderUtils.drawFilledCircle(centerX.toInt(), centerY.toInt(), radius, Color(background))
        drawTextAt(">", centerX - 2.2f, centerY - 4.4f, 9f, foreground, 6f)
    }

    private fun drawVolumeKnob(box: Box, volume: Float) {
        val x = box.x + box.width * volume.coerceIn(0f, 1f)
        val y = box.y + box.height * 0.5f
        RenderUtils.drawFilledCircle(x.toInt(), y.toInt(), 2.4f, Color(0xFFF4FFFF.toInt()))
    }

    /** Setsuna Logo：watermark_logo 贴图直绘（加载失败时退回粗体 "S"，与源端一致）。 */
    private fun drawSetsunaLogo(x: Float, y: Float, size: Float) {
        try {
            GlStateManager.color(1f, 1f, 1f, 1f)
            GlStateManager.enableTexture2D()
            GlStateManager.enableBlend()
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 771)
            mc.textureManager.bindTexture(LOGO_TEXTURE)
            RenderUtils.drawModalRectWithCustomSizedTexture(x, y, 0f, 0f, size, size, size, size)
            GlStateManager.color(1f, 1f, 1f, 1f)
            return
        } catch (ignored: Throwable) {
            // 贴图不可用时保持界面可用（与源端 fallback 行为一致）
        }
        drawBoldText("S", x + 3, y, size, ACCENT, size * 0.65f)
    }

    private fun drawPlayerButton(box: Box, label: String, enabled: Boolean) {
        val hover = animateInteraction("player:" + label + ":" + box.x.roundToInt(),
            enabled && box.contains(mouseXF, mouseYF))
        val fill = if (!enabled) 0x0AFFFFFF else withAlpha(ACCENT, hover * 0.28f)
        RenderUtils.drawRoundedRect(box.x, box.y, box.x + box.width, box.y + box.height,
            fill, box.height * 0.5f)
        drawCenteredIcon(label, box,
            if (enabled) mixColor(TEXT, ACCENT, hover) else 0x48FFFFFF, 10f, controlsIconFont(10f))
    }

    private fun drawLikeButton(box: Box, liked: Boolean) {
        val hovered = box.contains(mouseXF, mouseYF)
        val hover = animateInteraction("like:" + box.x.roundToInt(), hovered)
        val fill = if (liked) mixColor(setAlpha(ACCENT, 54), setAlpha(SECONDARY, 92), hover)
        else mixColor(0x12FFFFFF, setAlpha(ACCENT, 36), hover)
        RenderUtils.drawRoundedRect(box.x, box.y, box.x + box.width, box.y + box.height, fill, 5f)
        drawCenteredIcon(MUSIC_LIKE, box, if (liked) SECONDARY else 0xB8FFFFFF.toInt(), 9f)
    }

    private fun drawMusicButton(box: Box, label: String, enabled: Boolean, primary: Boolean) {
        val hover = animateInteraction("music:" + label + ":" + box.x.roundToInt(),
            enabled && box.contains(mouseXF, mouseYF))
        val border = if (enabled && primary) ACCENT else 0x48FFFFFF
        val fill = when {
            !enabled -> 0x12FFFFFF
            primary -> mixColor(ACCENT, SECONDARY, hover * 0.32f)
            else -> mixColor(0x12FFFFFF, 0x28FFFFFF, hover)
        }
        RenderUtils.drawRoundedRect(box.x, box.y, box.x + box.width, box.y + box.height, border, 5f)
        RenderUtils.drawRoundedRect(box.x + 1, box.y + 1, box.x + box.width - 1, box.y + box.height - 1,
            fill, 4f)
        drawCenteredText(label, box, if (enabled) TEXT else 0x5AFFFFFF, true, 8f)
    }

    private fun drawSmallIconButton(box: Box, glyph: String, enabled: Boolean, danger: Boolean, size: Float) {
        val hover = animateInteraction("small:" + glyph + ":" + box.x.roundToInt(),
            enabled && box.contains(mouseXF, mouseYF))
        val fill = when {
            !enabled -> 0x0EFFFFFF
            danger -> mixColor(0x1CE05252, 0x58E05252, hover)
            else -> mixColor(setAlpha(ACCENT, 28), setAlpha(ACCENT, 56), hover)
        }
        RenderUtils.drawRoundedRect(box.x, box.y, box.x + box.width, box.y + box.height, fill, 5f)
        drawCenteredIcon(glyph, box,
            if (enabled) (if (danger) 0xFFFFA0A6.toInt() else TEXT) else 0x4AFFFFFF, size,
            clientIconFont(size))
    }

    /** SkijaUi.icon：在 y..y+boxHeight 内垂直居中绘制图标字形。 */
    private fun drawIcon(glyph: String, x: Float, y: Float, boxHeight: Float, color: Int, size: Float,
                         font: GameFontRenderer = iconFont(size)) {
        // 等价于源端 baseline = top + (height - (descent-ascent))*0.5 - ascent 的 em 行盒居中；
        // +2.75f 补偿 GameFontRenderer.drawString 的 baseY(y-3) 与内部平移
        font.drawString(glyph, x, y + (boxHeight - font.visualLineHeight) * 0.5f + 2.75f, color)
    }

    private fun drawCenteredIcon(glyph: String, box: Box, color: Int, size: Float,
                                 font: GameFontRenderer = iconFont(size)) {
        val width = font.getStringWidth(glyph).toFloat()
        drawIcon(glyph, box.x + (box.width - width) * 0.5f, box.y, box.height, color, size, font)
    }

    // ===== 文本 / 图标辅助 =====

    /** Setsuna 常规文本（源端 Skija 像素直传，fontSetsuna 内部按 x4 还原）。 */
    private fun uiFont(size: Float): GameFontRenderer =
        Fonts.fontSetsuna("pf_normal.ttf", max(4, size.roundToInt()))

    /** Setsuna 粗体文本（用 pf_normal 双描近似加粗，不引入 pf_middleblack）。 */
    private fun boldUiFont(size: Float): GameFontRenderer =
        Fonts.fontSetsuna("pf_normal.ttf", max(4, size.roundToInt()))

    /** TRITIUM_MUSIC 图标集（源端 music.ttf）：导航 / 喜欢 / 音量图标。 */
    private fun iconFont(size: Float): GameFontRenderer =
        Fonts.fontSetsuna("music.ttf", max(4, size.roundToInt()))

    /** TRITIUM_CONTROLS 图标集（源端 icomoon.ttf）：播放控制按钮。 */
    private fun controlsIconFont(size: Float): GameFontRenderer =
        Fonts.fontSetsuna("icomoon.ttf", max(4, size.roundToInt()))

    /** CLIENT_ICONS 图标集（源端 textures/Font/icon.ttf）：关闭 / 刷新 / 登出小按钮。 */
    private fun clientIconFont(size: Float): GameFontRenderer =
        Fonts.fontSetsuna("icon.ttf", max(4, size.roundToInt()))

    /** SkijaUi.text：在 y..y+boxHeight 内垂直居中绘制。 */
    private fun drawTextAt(text: String, x: Float, y: Float, boxHeight: Float, color: Int, size: Float) {
        val font = uiFont(size)
        font.drawString(text, x, y + (boxHeight - font.visualLineHeight) * 0.5f + 2.75f, color)
    }

    /** SkijaUi.boldText：pf_normal 双描近似加粗。 */
    private fun drawBoldText(text: String, x: Float, y: Float, boxHeight: Float, color: Int, size: Float) {
        val font = boldUiFont(size)
        val drawY = y + (boxHeight - font.visualLineHeight) * 0.5f + 2.75f
        font.drawString(text, x, drawY, color)
        font.drawString(text, x + 0.5f, drawY + 0.3f, color)
    }

    private fun drawCenteredText(text: String, box: Box, color: Int, bold: Boolean, size: Float) {
        val font = uiFont(size)
        val textW = font.getStringWidth(text).toFloat()
        val x = box.x + (box.width - textW) * 0.5f
        if (bold) drawBoldText(text, x, box.y, box.height, color, size)
        else drawTextAt(text, x, box.y, box.height, color, size)
    }

    private fun ell(text: String, maxWidth: Float, size: Float): String {
        val font = uiFont(size)
        if (font.getStringWidth(text) <= maxWidth) return text
        var s = text
        while (s.isNotEmpty() && font.getStringWidth("$s...") > maxWidth) s = s.dropLast(1)
        return if (s.isEmpty()) text else "$s..."
    }

    // ===== 封面系统 =====

    private fun drawCover(key: String, url: String?, box: Box, radius: Float) {
        val img: CoverImage? = synchronized(coverImages) { coverImages[key] }
        if (img == null) {
            RenderUtils.drawRoundedRect(box.x, box.y, box.x + box.width, box.y + box.height,
                0xFF4B303E.toInt(), radius)
            requestCover(key, url)
            return
        }
        GlStateManager.color(1f, 1f, 1f, 1f)
        GlStateManager.enableTexture2D()
        GlStateManager.enableBlend()
        mc.textureManager.bindTexture(img.loc)
        // cover-fit：按视口宽高比裁剪 UV（对应 Setsuna 的 drawImageRect source 裁剪）
        val iw = img.width.toFloat()
        val ih = img.height.toFloat()
        val viewportAspect = box.width / box.height
        var sw = iw
        var sh = ih
        if (viewportAspect > iw / ih) {
            sh = iw / viewportAspect
        } else {
            sw = ih * viewportAspect
        }
        val sx = (iw - sw) * 0.5f
        val sy = (ih - sh) * 0.5f
        if (radius > 0f) {
            withRoundedCoverClip(box, radius) {
                GlStateManager.pushMatrix()
                GlStateManager.translate(box.x.toDouble(), box.y.toDouble(), 0.0)
                GlStateManager.scale((box.width / sw).toDouble(), (box.height / sh).toDouble(), 1.0)
                RenderUtils.drawModalRectWithCustomSizedTexture(0f, 0f, sx, sy, sw, sh, iw, ih)
                GlStateManager.popMatrix()
            }
        } else {
            GlStateManager.pushMatrix()
            GlStateManager.translate(box.x.toDouble(), box.y.toDouble(), 0.0)
            GlStateManager.scale((box.width / sw).toDouble(), (box.height / sh).toDouble(), 1.0)
            RenderUtils.drawModalRectWithCustomSizedTexture(0f, 0f, sx, sy, sw, sh, iw, ih)
            GlStateManager.popMatrix()
        }
        GlStateManager.color(1f, 1f, 1f, 1f)
    }

    /**
     * 圆角裁剪（对应源端 drawCover 的 clipRRect）。
     * 外层框架裁剪（withClipping）占用 stencil bit0 值 1；此处用整字节值区分：
     * 封面矩形写 2 → 圆角形状写 3 → 以 GL_EQUAL,3 绘制纹理 → 恢复矩形区域为 1，
     * 最后还原外层 stencil 状态，保证框架裁剪不受影响。
     */
    private fun withRoundedCoverClip(box: Box, radius: Float, draw: () -> Unit) {
        GL11.glEnable(GL11.GL_STENCIL_TEST)
        GL11.glColorMask(false, false, false, false)
        // 封面矩形区域写入 2（抹掉 bit0，标记为"待定"）
        GL11.glStencilFunc(GL11.GL_ALWAYS, 2, 0xFF)
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE)
        GL11.glStencilMask(0xFF)
        RenderUtils.drawRect(box.x, box.y, box.x + box.width, box.y + box.height, -1)
        // 圆角形状内写入 3（bit0=1 维持外层裁剪，bit1=1 标记形状内）
        GL11.glStencilFunc(GL11.GL_ALWAYS, 3, 0xFF)
        RenderUtils.drawRoundedRect(box.x, box.y, box.x + box.width, box.y + box.height, -1, radius)
        GL11.glColorMask(true, true, true, true)
        // 仅在形状内绘制
        GL11.glStencilFunc(GL11.GL_EQUAL, 3, 0xFF)
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP)
        GL11.glStencilMask(0)
        draw()
        // 恢复封面矩形区域 stencil 为 1（外层裁剪值）
        GL11.glColorMask(false, false, false, false)
        GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF)
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE)
        GL11.glStencilMask(0xFF)
        RenderUtils.drawRect(box.x, box.y, box.x + box.width, box.y + box.height, -1)
        GL11.glColorMask(true, true, true, true)
        // 还原外层 withClipping 的 stencil 状态
        GL11.glStencilFunc(GL11.GL_EQUAL, 1, 1)
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP)
        GL11.glStencilMask(0)
    }

    private fun requestCover(key: String, url: String?) {
        if (url == null || url.isBlank() || loadingCovers.contains(key)
            || (synchronized(coverRetryAt) { coverRetryAt[key] } ?: 0L) > System.currentTimeMillis()) {
            return
        }
        loadingCovers.add(key)
        CompletableFuture.supplyAsync { downloadCover(url) }.whenComplete { bytes, error ->
            mc.addScheduledTask {
                loadingCovers.remove(key)
                if (disposed) {
                    return@addScheduledTask
                }
                if (error != null || bytes == null || bytes.isEmpty()) {
                    synchronized(coverRetryAt) { coverRetryAt[key] = System.currentTimeMillis() + 30_000L }
                    return@addScheduledTask
                }
                try {
                    val image = ImageIO.read(ByteArrayInputStream(bytes))
                    if (image == null) {
                        synchronized(coverRetryAt) { coverRetryAt[key] = System.currentTimeMillis() + 30_000L }
                        return@addScheduledTask
                    }
                    val texture = DynamicTexture(image)
                    val loc = mc.textureManager.getDynamicTextureLocation("cover_$key", texture)
                    synchronized(coverImages) {
                        val old = coverImages.put(key, CoverImage(loc, image.width, image.height))
                        if (old != null) {
                            try {
                                mc.textureManager.deleteTexture(old.loc)
                            } catch (ignored: Exception) {
                            }
                        }
                        trimCoverCache()
                    }
                } catch (ignored: Exception) {
                    synchronized(coverRetryAt) { coverRetryAt[key] = System.currentTimeMillis() + 30_000L }
                }
            }
        }
    }

    /** Java 8 兼容的封面下载（HttpURLConnection + 超时 + UA）。 */
    private fun downloadCover(url: String): ByteArray? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "AirPlus/1.0")
            if (conn.responseCode < 200 || conn.responseCode >= 300) return null
            conn.inputStream.use { it.readBytes() }
        } catch (ignored: Exception) {
            null
        }
    }

    private fun trimCoverCache() {
        while (coverImages.size > 96) {
            val it = coverImages.entries.iterator()
            val entry = it.next()
            try {
                mc.textureManager.deleteTexture(entry.value.loc)
            } catch (ignored: Exception) {
            }
            it.remove()
        }
    }

    // ===== QR 纹理 =====

    private fun syncQrImage() {
        val address = QRCodeGenerator.getLastAddress()
        if (address.isBlank() || address == qrTextureAddress) {
            return
        }
        val image = QRCodeGenerator.getQrImage() ?: return
        try {
            val texture = DynamicTexture(image)
            qrTexture = mc.textureManager.getDynamicTextureLocation("airplus_music_qr", texture)
            qrTextureAddress = address
            qrTextureWidth = image.width
            qrTextureHeight = image.height
        } catch (ignored: Exception) {
        }
    }

    private fun closeQrImage() {
        val texture = qrTexture
        if (texture != null) {
            try {
                mc.textureManager.deleteTexture(texture)
            } catch (ignored: Exception) {
            }
            qrTexture = null
        }
        qrTextureAddress = ""
        qrTextureWidth = 0
        qrTextureHeight = 0
    }

    // ===== 交互 =====

    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        mouseXF = mouseX.toFloat()
        mouseYF = mouseY.toFloat()
        if (mouseButton != 0) return
        if (closing || panelTransition < 0.92f) return
        // 确认弹窗打开时优先消费点击
        if (confirmPending != null) {
            val kind = confirmPending
            val yes = confirmYesBox.contains(mouseXF, mouseYF)
            val no = confirmNoBox.contains(mouseXF, mouseYF)
            confirmPending = null
            if (yes && kind != null) {
                if (kind == ConfirmKind.RELOAD) reloadAccount() else logoutCookie()
            }
            return
        }
        val layout = layout()
        updateInputBounds(layout)
        val box = searchBox
        if (!nowPlayingView && nowPlayingTransition <= 0.001f && box != null) {
            val s = layout.search
            if (mouseX >= s.x && mouseX <= s.x + s.width && mouseY >= s.y && mouseY <= s.y + s.height) {
                box.setFocused(true)
                // 点击定位光标（对应源端 TextInput.click）
                box.mouseClicked(mouseX, mouseY, mouseButton)
                return
            }
            box.setFocused(false)
        }
        for (region in ArrayList(clickRegions)) {
            if (!region.box.contains(mouseXF, mouseYF)) {
                continue
            }
            if (isContentAction(region.action) && !layout.content.contains(mouseXF, mouseYF)) {
                continue
            }
            if (region.action == Action.PROGRESS && CloudMusic.player != null && !accountBusy) {
                draggingProgress = true
                updateSeek(layout.progress, mouseXF)
                return
            }
            if (region.action == Action.VOLUME) {
                draggingVolume = true
                updateVolume(layout.volume, mouseXF)
                return
            }
            handleClick(region)
            return
        }
        super.mouseClicked(mouseX, mouseY, mouseButton)
    }

    override fun mouseClickMove(mouseX: Int, mouseY: Int, clickedMouseButton: Int, timeSinceLastClick: Long) {
        val layout = layout()
        if (draggingProgress) {
            updateSeek(layout.progress, mouseX.toFloat())
            return
        }
        if (draggingVolume) {
            updateVolume(layout.volume, mouseX.toFloat())
            return
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick)
    }

    override fun mouseReleased(mouseX: Int, mouseY: Int, state: Int) {
        if (draggingProgress || draggingVolume) {
            val player = if (accountBusy) null else CloudMusic.player
            if (draggingProgress && player != null && pendingSeek >= 0) {
                try {
                    player.setPlaybackTime(pendingSeek)
                } catch (ignored: RuntimeException) {
                }
            }
            draggingProgress = false
            draggingVolume = false
            pendingSeek = -1f
            return
        }
        super.mouseReleased(mouseX, mouseY, state)
    }

    override fun handleMouseInput() {
        super.handleMouseInput()
        val wheel = Mouse.getEventDWheel()
        if (wheel != 0) {
            val layout = layout()
            if (!layout.content.contains(mouseXF, mouseYF)) {
                return
            }
            contentScrollTarget += wheel / 120f * 24f
            contentScrollTarget = min(0f, contentScrollTarget)
        }
    }

    override fun keyTyped(typedChar: Char, keyCode: Int) {
        if (confirmPending != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                confirmPending = null
            }
            return
        }
        val box = searchBox
        if (!nowPlayingView && box != null && box.isFocused) {
            if (keyCode == Keyboard.KEY_RETURN) {
                startSearch()
                box.setFocused(false)
                return
            }
            if (keyCode == Keyboard.KEY_ESCAPE) {
                box.setFocused(false)
                return
            }
            if (box.textboxKeyTyped(typedChar, keyCode)) {
                return
            }
        }
        if (keyCode == Keyboard.KEY_SPACE && CloudMusic.player != null) {
            togglePause()
            return
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (nowPlayingView || nowPlayingTransition > 0.01f) {
                nowPlayingView = false
            } else {
                onClose()
            }
            return
        }
        super.keyTyped(typedChar, keyCode)
    }

    override fun updateScreen() {
        super.updateScreen()
        searchBox?.updateCursorCounter()
        if (closeReady) {
            closeReady = false
            mc.displayGuiScreen(null)
        }
    }

    private fun onClose() {
        if (closing) {
            return
        }
        closing = true
        nowPlayingView = false
        searchBox?.setFocused(false)
    }

    private fun requestConfirm(kind: ConfirmKind) {
        if (accountBusy || playQueued) return
        if (kind == ConfirmKind.LOGOUT && !hasAccount()) return
        confirmPending = kind
    }

    private fun handleClick(region: ClickRegion) {
        when (region.action) {
            Action.BACK -> onClose()
            Action.NOW_PLAYING -> if (CloudMusic.currentlyPlaying != null) {
                nowPlayingView = true
            }
            Action.LIBRARY -> nowPlayingView = false
            Action.PAGE -> openPage(region.payload as Page)
            Action.PLAYLIST -> openPlaylist(region.payload as PlayList)
            Action.PLAY_SONG -> {
                val song = region.payload as SongClick
                play(song.source, song.index)
            }
            Action.PLAY_ALL -> play(playlistSongs, 0)
            Action.SHUFFLE -> {
                val shuffled = ArrayList(playlistSongs)
                shuffled.shuffle()
                play(shuffled, 0)
            }
            Action.PREVIOUS -> {
                if (CloudMusic.player != null && !playQueued && !accountBusy) {
                    CloudMusic.prev()
                    setStatus("Previous track", false)
                }
            }
            Action.PAUSE -> togglePause()
            Action.NEXT -> {
                if (CloudMusic.player != null && !playQueued && !accountBusy) {
                    CloudMusic.next()
                    setStatus("Next track", false)
                }
            }
            Action.LIKE -> toggleLike(region.payload as Music)
            Action.RELOAD -> requestConfirm(ConfirmKind.RELOAD)
            Action.LOGOUT -> requestConfirm(ConfirmKind.LOGOUT)
            Action.QR_LOGIN -> startQrLogin()
            Action.PROGRESS, Action.VOLUME -> {
            }
        }
    }

    // ===== 页面与数据加载 =====

    private fun openPage(target: Page) {
        pageRequestGeneration++
        page = target
        selectedPlaylist = null
        contentScroll = 0f
        contentScrollTarget = 0f
        pageTransition = 0f
        loadingPage = false
        if (target == Page.LIKED || target == Page.DAILY) {
            visibleSongs = emptyList()
        }
        if (target == Page.SEARCH) {
            searchBox?.setFocused(true)
        } else if (target == Page.LIKED) {
            loadLiked(pageRequestGeneration)
        } else if (target == Page.DAILY) {
            loadDaily(pageRequestGeneration)
        }
    }

    private fun openPlaylist(playlist: PlayList) {
        selectedPlaylist = playlist
        page = Page.PLAYLIST
        playlistSongs = emptyList()
        contentScroll = 0f
        contentScrollTarget = 0f
        pageTransition = 0f
        loadingPage = true
        setStatus("Loading " + playlist.name + "...", false)
        playlist.loadMusicsWithCallback { songs ->
            mc.addScheduledTask {
                if (disposed || selectedPlaylist !== playlist) {
                    return@addScheduledTask
                }
                playlistSongs = safeCopy(songs)
                loadingPage = false
                setStatus(playlistSongs.size.toString() + " songs", false)
            }
        }
    }

    private fun startSearch() {
        val box = searchBox
        val query = box?.text?.trim() ?: ""
        if (query.isEmpty() || searching || accountBusy) {
            if (query.isEmpty()) {
                setStatus("Search text is empty", true)
            }
            return
        }
        searching = true
        loadingPage = true
        searchGeneration++
        pageRequestGeneration++
        page = Page.SEARCH
        selectedPlaylist = null
        contentScroll = 0f
        contentScrollTarget = 0f
        pageTransition = 0f
        setStatus("Searching $query...", false)
        val generation = searchGeneration
        CompletableFuture.supplyAsync { CloudMusic.search(query) }.whenComplete { songs, error ->
            mc.addScheduledTask {
                if (disposed || generation != searchGeneration) {
                    return@addScheduledTask
                }
                searching = false
                loadingPage = false
                if (error != null || songs == null) {
                    visibleSongs = emptyList()
                    setStatus("Search failed: " + errorMessage(error), true)
                    return@addScheduledTask
                }
                visibleSongs = safeCopy(songs)
                setStatus(visibleSongs.size.toString() + " results", false)
            }
        }
    }

    private fun loadLiked(generation: Int) {
        if (CloudMusic.profile == null || CloudMusic.likeList == null || CloudMusic.likeList.isEmpty()) {
            visibleSongs = emptyList()
            loadingPage = false
            setStatus("No liked songs", false)
            return
        }
        visibleSongs = emptyList()
        loadingPage = true
        setStatus("Loading liked songs...", false)
        val likedIds: List<Long> = ArrayList(CloudMusic.likeList)
        CompletableFuture.supplyAsync { loadSongDetails(likedIds) }.whenComplete { songs, error ->
            mc.addScheduledTask {
                if (disposed || page != Page.LIKED || generation != pageRequestGeneration) {
                    return@addScheduledTask
                }
                loadingPage = false
                if (error != null || songs == null) {
                    visibleSongs = emptyList()
                    setStatus("Liked songs failed: " + errorMessage(error), true)
                    return@addScheduledTask
                }
                visibleSongs = songs
                setStatus(visibleSongs.size.toString() + " songs", false)
            }
        }
    }

    private fun loadDaily(generation: Int) {
        visibleSongs = emptyList()
        loadingPage = true
        setStatus("Loading daily songs...", false)
        CompletableFuture.supplyAsync { CloudMusicApi.recommendSongs().toJsonObject() }
            .whenComplete { json, error ->
                mc.addScheduledTask {
                    if (disposed || page != Page.DAILY || generation != pageRequestGeneration) {
                        return@addScheduledTask
                    }
                    loadingPage = false
                    if (error != null || json == null) {
                        visibleSongs = emptyList()
                        setStatus("Daily songs failed: " + errorMessage(error), true)
                        return@addScheduledTask
                    }
                    val data = json.getAsJsonObject("data")
                    visibleSongs = parseSongs(if (data == null) null else data.getAsJsonArray("dailySongs"))
                    setStatus(visibleSongs.size.toString() + " songs", false)
                }
            }
    }

    private fun loadHomeRecommendations() {
        if (loadingHome || !hasAccount()) {
            return
        }
        loadingHome = true
        setStatus("Loading recommendations...", false)
        CompletableFuture.supplyAsync { CloudMusicApi.recommendResource().toJsonObject() }
            .whenComplete { json, error ->
                mc.addScheduledTask {
                    if (disposed) {
                        return@addScheduledTask
                    }
                    loadingHome = false
                    if (error != null || json == null) {
                        recommendations = safePlaylists(CloudMusic.playLists)
                        setStatus("Using saved playlists", false)
                        primeHomeSongs()
                        return@addScheduledTask
                    }
                    val parsed = ArrayList<PlayList>()
                    val array = json.getAsJsonArray("recommend")
                    if (array != null) {
                        for (element in array) {
                            val obj = element.asJsonObject
                            if (obj.has("picUrl")) {
                                obj.addProperty("coverImgUrl", obj.get("picUrl").asString)
                            }
                            if (obj.has("playcount")) {
                                obj.addProperty("playCount", obj.get("playcount").asLong)
                            }
                            parsed.add(JsonUtils.parse(obj, PlayList::class.java))
                        }
                    }
                    recommendations = ArrayList(parsed)
                    setStatus(recommendations.size.toString() + " recommendations", false)
                    primeHomeSongs()
                }
            }
    }

    private fun primeHomeSongs() {
        if (!homeSongs.isEmpty()) {
            return
        }
        if (CloudMusic.playList != null && !CloudMusic.playList.isEmpty()) {
            homeSongs = safeCopy(CloudMusic.playList)
            return
        }
        val source = homePlaylists()
        if (source.isEmpty()) {
            return
        }
        val playlist = source[0]
        playlist.loadMusicsWithCallback { songs ->
            mc.addScheduledTask {
                if (!disposed && homeSongs.isEmpty()) {
                    homeSongs = safeCopy(songs)
                }
            }
        }
    }

    // ===== 账号流程 =====

    private fun reloadAccount() {
        if (playQueued) {
            return
        }
        val value = CloudMusic.loadCookie()
        if (value.isBlank()) {
            startQrLogin()
            return
        }
        if (accountBusy) {
            return
        }
        accountBusy = true
        setStatus("Restoring NetEase account...", false)
        CompletableFuture.runAsync { CloudMusic.loadNCM(value) }.whenComplete { ignored, error ->
            mc.addScheduledTask {
                if (disposed) {
                    return@addScheduledTask
                }
                accountBusy = false
                if (error != null || CloudMusic.profile == null) {
                    setStatus(if (error == null) CloudMusic.status
                    else "Account restore failed: " + errorMessage(error), true)
                    startQrLogin()
                    return@addScheduledTask
                }
                recommendations = emptyList()
                homeSongs = emptyList()
                setStatus("Logged in as " + CloudMusic.profile.name, false)
                loadHomeRecommendations()
            }
        }
    }

    private fun startQrLogin() {
        if (playQueued) {
            return
        }
        cancelQrLogin()
        closeQrImage()
        QRCodeGenerator.clear()

        accountBusy = true
        qrLoginState = CloudMusic.QrLoginState.CREATING
        setStatus("Generating QR code...", false)

        val worker = Thread {
            var error: Throwable? = null
            val cookie = CloudMusic.qrCodeLogin { state ->
                if (qrLoginThread === Thread.currentThread()) qrLoginState = state
            }
            if (!cookie.isBlank() && !Thread.currentThread().isInterrupted) {
                try {
                    CloudMusic.loadNCM(cookie)
                } catch (failure: Throwable) {
                    error = failure
                }
            }
            val resultError = error
            val completedWorker = Thread.currentThread()
            mc.addScheduledTask { finishQrLogin(completedWorker, resultError) }
        }
        worker.name = "AirPlus-NetEase-QR-Login"
        worker.isDaemon = true
        qrLoginThread = worker
        worker.start()
    }

    private fun finishQrLogin(worker: Thread, error: Throwable?) {
        if (disposed || qrLoginThread !== worker) {
            return
        }
        qrLoginThread = null
        accountBusy = false
        if (error != null || !hasAccount()) {
            statusError = qrLoginState == CloudMusic.QrLoginState.FAILED
            setStatus(if (error == null) qrLoginStatus() else "QR login failed: " + errorMessage(error),
                statusError)
            return
        }

        closeQrImage()
        QRCodeGenerator.clear()
        recommendations = emptyList()
        homeSongs = emptyList()
        setStatus("Logged in as " + profileName(), false)
        loadHomeRecommendations()
    }

    private fun logoutCookie() {
        if (accountBusy || playQueued || !hasAccount()) {
            return
        }
        accountBusy = true
        setStatus("Logging out...", false)
        CompletableFuture.runAsync {
            CloudMusic.onStop()
            OptionsUtil.setCookie("")
            CloudMusic.saveCookie("")
            CloudMusic.profile = null
            CloudMusic.playLists = java.util.concurrent.CopyOnWriteArrayList()
            CloudMusic.likeList = java.util.concurrent.CopyOnWriteArrayList()
            CloudMusic.playList = ArrayList()
            CloudMusic.currentlyPlaying = null
            CloudMusic.status = "Not logged in"
        }.whenComplete { ignored, error ->
            mc.addScheduledTask {
                if (disposed) {
                    return@addScheduledTask
                }
                accountBusy = false
                if (error != null) {
                    setStatus("Logout failed: " + errorMessage(error), true)
                    return@addScheduledTask
                }
                recommendations = emptyList()
                homeSongs = emptyList()
                visibleSongs = emptyList()
                playlistSongs = emptyList()
                selectedPlaylist = null
                page = Page.HOME
                setStatus("Logged out", false)
                startQrLogin()
            }
        }
    }

    private fun initializeAccount() {
        if (hasAccount()) {
            if (recommendations.isEmpty()) loadHomeRecommendations()
            primeHomeSongs()
            return
        }
        if (CloudMusic.loadCookie().isBlank()) {
            startQrLogin()
        } else {
            reloadAccount()
        }
    }

    private fun cancelQrLogin() {
        val worker = qrLoginThread
        qrLoginThread = null
        if (worker != null) {
            worker.interrupt()
        }
        accountBusy = false
    }

    private fun qrLoginStatus(): String = when (qrLoginState) {
        CloudMusic.QrLoginState.IDLE -> displayStatus()
        CloudMusic.QrLoginState.CREATING -> "Generating QR code..."
        CloudMusic.QrLoginState.WAITING_SCAN -> "Waiting for scan in NetEase Cloud Music"
        CloudMusic.QrLoginState.WAITING_CONFIRMATION -> "Scanned - confirm login on your phone"
        CloudMusic.QrLoginState.AUTHORIZED -> "Login confirmed, loading your library..."
        CloudMusic.QrLoginState.EXPIRED -> "QR code expired - generate a new one"
        CloudMusic.QrLoginState.FAILED -> "QR login request failed - try again"
    }

    // ===== 播放控制 =====

    private fun play(songs: List<Music>?, index: Int) {
        if (songs == null || songs.isEmpty() || playQueued || accountBusy) {
            return
        }
        val queue = safeCopy(songs)
        val safeIndex = max(0, min(index, queue.size - 1))
        val selected = queue[safeIndex]
        playQueued = true
        setStatus("Queueing " + selected.name + "...", false)
        CompletableFuture.runAsync { CloudMusic.play(queue, safeIndex) }.whenComplete { ignored, error ->
            mc.addScheduledTask {
                if (disposed) {
                    return@addScheduledTask
                }
                playQueued = false
                setStatus(if (error == null) "Playing " + selected.name
                else "Playback failed: " + errorMessage(error), error != null)
            }
        }
    }

    private fun togglePause() {
        val player = CloudMusic.player
        if (player == null || playQueued || accountBusy) {
            return
        }
        try {
            if (player.isPausing) {
                player.unpause()
                setStatus("Playback resumed", false)
            } else {
                player.pause()
                setStatus("Playback paused", false)
            }
        } catch (error: RuntimeException) {
            setStatus("Playback control failed: " + errorMessage(error), true)
        }
    }

    private fun toggleLike(music: Music?) {
        if (music == null || CloudMusic.likeList == null || CloudMusic.profile == null) {
            return
        }
        val liked = isLiked(music)
        if (liked) {
            CloudMusic.likeList.remove(music.id)
        } else if (!CloudMusic.likeList.contains(music.id)) {
            CloudMusic.likeList.add(music.id)
        }
        CompletableFuture.runAsync { music.setLike(!liked) }.whenComplete { ignored, error ->
            if (error == null) {
                return@whenComplete
            }
            if (liked && !CloudMusic.likeList.contains(music.id)) {
                CloudMusic.likeList.add(music.id)
            } else if (!liked) {
                CloudMusic.likeList.remove(music.id)
            }
            mc.addScheduledTask {
                setStatus("Like update failed: " + errorMessage(error), true)
            }
        }
    }

    private fun updateSeek(box: Box, mouseX: Float) {
        val player = if (accountBusy) null else CloudMusic.player
        if (player == null || player.totalTimeMillis <= 0f) {
            return
        }
        val percent = ((mouseX - box.x) / max(1f, box.width)).coerceIn(0f, 1f)
        pendingSeek = player.totalTimeMillis * percent
    }

    private fun updateVolume(box: Box, mouseX: Float) {
        val volume = ((mouseX - box.x) / max(1f, box.width)).coerceIn(0f, 1f)
        preferredVolume = volume
        preferredVolumeChanged = true
        // 回写模块设置（NetEaseMusic.setVolumeFromScreen 会同步到 player 与配置）
        NetEaseMusic.setVolumeFromScreen(volume)
        val player = if (accountBusy) null else CloudMusic.player
        if (player != null) {
            player.setVolume(volume)
            volumePlayer = player
        }
    }

    // ===== 播放状态换算 =====

    private fun playbackProgress(player: AudioPlayer?): Float {
        if (player == null || player.totalTimeMillis <= 0f) {
            return 0f
        }
        val time = if (draggingProgress && pendingSeek >= 0) pendingSeek else player.currentTimeMillis
        return (time / player.totalTimeMillis).coerceIn(0f, 1f)
    }

    private fun currentVolume(player: AudioPlayer?): Float {
        if (player == null) {
            return preferredVolume
        }
        if (player !== volumePlayer) {
            volumePlayer = player
            if (preferredVolumeChanged) {
                player.setVolume(preferredVolume)
            } else {
                preferredVolume = player.volume.coerceIn(0f, 1f)
            }
        }
        return if (preferredVolumeChanged) preferredVolume else player.volume.coerceIn(0f, 1f)
    }

    private fun currentTime(player: AudioPlayer?): Float {
        if (draggingProgress && pendingSeek >= 0) {
            return pendingSeek
        }
        return player?.currentTimeMillis ?: 0f
    }

    private fun totalTime(music: Music, player: AudioPlayer?): Float {
        return if (player == null || player.totalTimeMillis <= 0f) music.duration.toFloat()
        else player.totalTimeMillis
    }

    // ===== 工具 =====

    private fun isContentAction(action: Action): Boolean =
        action == Action.PLAYLIST || action == Action.PLAY_SONG
                || action == Action.PLAY_ALL || action == Action.SHUFFLE

    private fun isLiked(music: Music?): Boolean =
        music != null && CloudMusic.likeList != null && CloudMusic.likeList.contains(music.id)

    private fun clampScroll(viewportHeight: Float, contentHeight: Float) {
        val maxScroll = max(0f, contentHeight - viewportHeight)
        contentScrollTarget = contentScrollTarget.coerceIn(-maxScroll, 0f)
    }

    private fun intersects(first: Box, second: Box): Boolean =
        first.x < second.x + second.width
                && first.x + first.width > second.x
                && first.y < second.y + second.height
                && first.y + first.height > second.y

    private fun homePlaylists(): List<PlayList> =
        if (recommendations.isEmpty()) safePlaylists(CloudMusic.playLists) else recommendations

    private fun displayStatus(): String {
        if (searching || accountBusy || playQueued || loadingPage || loadingHome || statusError) {
            return status
        }
        val cloudStatus = CloudMusic.status
        return if (cloudStatus == null || cloudStatus.isBlank()) status else cloudStatus
    }

    private fun setStatus(newStatus: String, error: Boolean) {
        status = newStatus
        statusError = error
    }

    private fun pageTitle(): String = when (page) {
        Page.SEARCH -> "Search Results"
        Page.LIKED -> "Liked Songs"
        Page.DAILY -> "Daily Songs"
        Page.PLAYLIST -> selectedPlaylist?.name ?: "Playlist"
        Page.HOME -> "Home"
    }

    private fun hasAccount(): Boolean = CloudMusic.profile != null

    private fun profileName(): String = CloudMusic.profile?.name ?: "OFFLINE"

    // ===== 过渡动画 =====

    private fun updateViewTransition() {
        val now = System.nanoTime()
        val delta = min(0.05f, max(0f, (now - lastFrameNanos) / 1_000_000_000.0f))
        lastFrameNanos = now
        frameDelta = delta
        val panelTarget = if (closing) 0f else 1f
        panelTransition += (panelTarget - panelTransition) * min(1f, delta * (if (closing) 12f else 8f))
        pageTransition += (1f - pageTransition) * min(1f, delta * 8f)
        val target = if (nowPlayingView) 1f else 0f
        val speed = if (nowPlayingView) 7.5f else 10.5f
        nowPlayingTransition += (target - nowPlayingTransition) * min(1f, delta * speed)
        if (Math.abs(target - nowPlayingTransition) < 0.001f) {
            nowPlayingTransition = target
        }
        if (closing && panelTransition < 0.01f) {
            closeReady = true
        }
    }

    private fun animateInteraction(key: String, active: Boolean): Float {
        var current = interactionAnim(key)
        val target = if (active) 1f else 0f
        current += (target - current) * min(1f, frameDelta * 12f)
        if (Math.abs(target - current) < 0.002f) {
            current = target
        }
        interactionAnimations[key] = current
        return current
    }

    private val interactionAnimations = HashMap<String, Float>()

    private fun interactionAnim(key: String): Float =
        interactionAnimations[key] ?: 0f

    // ===== 颜色 / 数学 =====

    private fun easeOutCubic(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        val inverse = 1f - t
        return 1f - inverse * inverse * inverse
    }

    private fun withAlpha(color: Int, amount: Float): Int {
        val alpha = (((color ushr 24) and 0xFF) * amount.coerceIn(0f, 1f)).roundToInt()
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }

    private fun mixColor(from: Int, to: Int, amount: Float): Int {
        val value = amount.coerceIn(0f, 1f)
        val alpha = (((from ushr 24) and 0xFF)
                + (((to ushr 24) and 0xFF) - ((from ushr 24) and 0xFF)) * value).roundToInt()
        val red = (((from ushr 16) and 0xFF)
                + (((to ushr 16) and 0xFF) - ((from ushr 16) and 0xFF)) * value).roundToInt()
        val green = (((from ushr 8) and 0xFF)
                + (((to ushr 8) and 0xFF) - ((from ushr 8) and 0xFF)) * value).roundToInt()
        val blue = ((from and 0xFF) + ((to and 0xFF) - (from and 0xFF)) * value).roundToInt()
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun setAlpha(color: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

    // ===== 解析 / 格式化 =====

    private fun parseSongs(array: com.google.gson.JsonArray?): List<Music> {
        val songs = ArrayList<Music>()
        if (array != null) {
            for (element in array) {
                songs.add(JsonUtils.parse(CloudMusic.normalizeSong(element.asJsonObject), Music::class.java))
            }
        }
        return songs
    }

    private fun loadSongDetails(ids: List<Long>): List<Music> {
        if (ids.isEmpty()) {
            return emptyList()
        }
        val songs = ArrayList<Music>()
        var start = 0
        while (start < ids.size) {
            val batch = ids.subList(start, min(ids.size, start + 400))
            val response = CloudMusicApi.songDetail(batch).toJsonObject()
            songs.addAll(parseSongs(response.getAsJsonArray("songs")))
            start += 400
        }
        return songs
    }

    private fun safeCopy(songs: List<Music>?): List<Music> =
        if (songs == null || songs.isEmpty()) emptyList() else ArrayList(songs)

    private fun safePlaylists(playlists: List<PlayList>?): List<PlayList> =
        if (playlists == null || playlists.isEmpty()) emptyList() else ArrayList(playlists)

    private fun playlistInfo(playlist: PlayList): String {
        val count = if (playlist.musicsLoaded && playlist.musics != null) playlist.musics.size
        else playlist.count
        return count.toString() + " songs / " + formatCount(playlist.playCount) + " plays"
    }

    private fun formatCount(count: Long): String {
        if (count >= 100_000_000L) {
            return String.format("%.1fB", count / 100_000_000.0)
        }
        if (count >= 10_000L) {
            return String.format("%.1fW", count / 10_000.0)
        }
        if (count >= 1_000L) {
            return String.format("%.1fK", count / 1_000.0)
        }
        return count.toString()
    }

    private fun formatDuration(millis: Float): String {
        val seconds = max(0, (millis / 1000.0).roundToInt().toLong()).toInt()
        return String.format("%d:%02d", seconds / 60, seconds % 60)
    }

    private fun qualityLabel(music: Music): String {
        if (music.isHiRes) return "HI-RES"
        if (music.isDolbyAtmos) return "DOLBY"
        if (music.isInstrumental) return "INST"
        if (music.isDirty) return "EXPLICIT"
        return "NCM"
    }

    private fun firstLetter(value: String): String =
        if (value.isBlank()) "?" else value.substring(0, 1).toUpperCase()

    private fun errorMessage(error: Throwable?): String {
        if (error == null) {
            return "Unknown error"
        }
        val cause = error.cause ?: error
        val message = cause.message
        return if (message == null || message.isBlank()) cause.javaClass.simpleName else message
    }
}
