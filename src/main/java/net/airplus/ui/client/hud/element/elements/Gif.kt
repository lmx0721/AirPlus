/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.ui.client.hud.element.elements

import net.airplus.file.FileManager
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.Element
import net.airplus.ui.client.hud.element.ElementInfo
import net.airplus.utils.client.ClientUtils.LOGGER
import net.airplus.utils.io.MiscUtils
import net.airplus.utils.render.AnimatedImage
import net.airplus.utils.render.ColorUtils.withAlpha
import net.airplus.utils.render.RenderUtils.drawImage
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import java.awt.Color
import java.io.File

/**
 * CustomHUD gif element
 *
 * 动图（GIF）展示组件。与 MusicPlayer 一致：使用客户端数据目录下的 `gif` 文件夹
 * 作为资源目录，从其中扫描动图文件并在 `Gif` 列表设置中选择；`Folder` 设置启用时
 * 自动用系统资源管理器打开该文件夹（仅 false -> true 转变时打开一次，防止重复弹窗）。
 *
 * 解码与纹理上传全部收敛在 [AnimatedImage]：后台线程一次性解码全部帧，
 * 渲染线程仅在帧序号变化时上传当前帧，因此持续播放时的开销与一张静态贴图近似。
 */
@ElementInfo(name = "Gif")
class Gif : Element("Gif") {

    companion object {

        /** gif 资源目录（客户端数据目录下，与 MusicPlayer 的 Music 目录同级） */
        val gifDir: File by lazy {
            val path = File(FileManager.dir, "gif")
            if (!path.exists()) {
                path.mkdirs()
            }
            path
        }

        private val SUPPORTED_EXTENSIONS = setOf("gif", "png", "jpg", "jpeg")

        /** 内置默认动图：jar 资源路径与复制到 gif 目录后的文件名 */
        private const val DEFAULT_GIF_RESOURCE = "/assets/minecraft/airplus/gif/tomorin.gif"
        private const val DEFAULT_GIF_NAME = "tomorin.gif"

        /** Folder 设置的重复打开防抖间隔（毫秒） */
        private const val FOLDER_OPEN_DEBOUNCE_MS = 3000L

        /** 列表自动重扫间隔（毫秒） */
        private const val RESCAN_INTERVAL_MS = 5000L

        /**
         * Create default element
         */
        fun default(): Gif {
            val gif = Gif()

            gif.x = 1.0
            gif.y = 1.0

            return gif
        }

    }

    private val gifChoices = choices("Gif", arrayOf("无"), "无")
    private val openFolderValue by boolean("Folder", false)
    private val widthValue by float("Width", 160F, 32F..512F)
    private val colorValue by color("Color", Color.WHITE)

    private val image = AnimatedImage()
    private var lastRequested = ""

    // 扫描状态
    private var lastScanTime = 0L

    // Folder 设置状态：只在 false -> true 转变时打开一次，防止连续/循环打开
    private var folderWasEnabled = false
    private var lastOpenTime = 0L

    /**
     * Draw element
     */
    override fun drawElement(): Border {
        // 定期重扫资源目录（保持列表与文件夹内容同步，开销可忽略）
        val now = System.currentTimeMillis()
        if (now - lastScanTime > RESCAN_INTERVAL_MS) {
            refreshGifChoices()
        }

        // Folder 设置：仅在 false -> true 转变时打开一次资源管理器，
        // 配合防抖间隔，杜绝配置加载即触发、连点、循环打开多个窗口
        if (openFolderValue && !folderWasEnabled && now - lastOpenTime > FOLDER_OPEN_DEBOUNCE_MS) {
            lastOpenTime = now
            refreshGifChoices()
            openGifFolder()
        }
        folderWasEnabled = openFolderValue

        ensureImageLoaded()
        image.update()

        val resource = image.resource
        if (resource == null) {
            // 未选择/加载中/加载失败：画一个占位框，方便在编辑器里拖动定位
            drawRoundedRect(0F, 0F, 32F, 18F, Color.BLACK.withAlpha(60).rgb, 3F)
            return Border(0F, 0F, 32F, 18F)
        }

        val width = widthValue.toInt().coerceIn(4, 512)
        val height = if (image.imageHeight > 0) {
            (width.toFloat() * image.imageHeight / image.imageWidth).toInt().coerceAtLeast(4)
        } else {
            width
        }

        drawImage(resource, 0, 0, width, height, colorValue)

        return Border(0F, 0F, width.toFloat(), height.toFloat())
    }

    /**
     * 扫描 gif 资源目录并刷新 `Gif` 列表（列表项 = 文件名含扩展名，保证唯一）
     */
    private fun refreshGifChoices() {
        lastScanTime = System.currentTimeMillis()
        val files = scanGifFolder()
        gifChoices.updateValues((listOf("无") + files.map { it.name }).toTypedArray())
    }

    private fun scanGifFolder(): List<File> {
        if (!gifDir.isDirectory) return emptyList()
        val files = gifDir.listFiles() ?: return emptyList()
        return files.filter { file ->
            file.isFile && file.extension.lowercase() in SUPPORTED_EXTENSIONS
        }.sortedBy { it.name.lowercase() }
    }

    /**
     * 用系统资源管理器打开 gif 资源目录（与 MusicPlayer#openMusicFolder 一致）
     */
    fun openGifFolder() {
        try {
            java.awt.Desktop.getDesktop().open(gifDir)
        } catch (e: Exception) {
            LOGGER.error("[Gif] Failed to open gif folder: {}", e.message)
        }
    }

    /**
     * 按当前列表选择加载对应文件，选择「无」或文件已不存在时卸载纹理
     */
    fun ensureImageLoaded() {
        val name = gifChoices.get()
        if (name.isBlank() || name == "无") {
            unload()
            return
        }

        val file = File(gifDir, name)
        if (!file.isFile) {
            // 文件被删除/改名：卸载并重扫列表
            unload()
            if (System.currentTimeMillis() - lastScanTime > RESCAN_INTERVAL_MS) {
                refreshGifChoices()
            }
            return
        }

        if (file.absolutePath == lastRequested && (image.isReady || image.isLoading || image.errorText != null)) return
        lastRequested = file.absolutePath
        image.loadFile(file)
    }

    private fun unload() {
        if (image.isReady || image.isLoading || image.errorText != null) {
            image.close()
        }
        lastRequested = ""
    }

    /**
     * 编辑器中添加元素：直接创建默认动图组件——把 jar 内置资源 tomorin.gif 复制进
     * gif 资源目录并选中加载，不再弹文件选择器；用户后续可通过 Folder 打开目录
     * 自行放入动图，列表会自动扫描。
     */
    override fun createElement(): Boolean {
        val target = File(gifDir, DEFAULT_GIF_NAME)

        if (!target.isFile) {
            val resource = Gif::class.java.getResourceAsStream(DEFAULT_GIF_RESOURCE)
            if (resource == null) {
                LOGGER.error("[Gif] Bundled gif resource {} not found", DEFAULT_GIF_RESOURCE)
                MiscUtils.showMessageDialog("Error", "Bundled gif resource not found.")
                return false
            }
            try {
                resource.use { input -> target.outputStream().use { input.copyTo(it) } }
            } catch (e: Exception) {
                LOGGER.error("[Gif] Failed to extract bundled gif: {}", e.message)
                MiscUtils.showMessageDialog("Error", "Failed to extract bundled gif.")
                return false
            }
        }

        refreshGifChoices()
        gifChoices.changeValue(target.name)
        ensureImageLoaded()
        return true
    }

    override fun destroyElement() {
        image.close()
    }

}
