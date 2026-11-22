/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 * 移植自 edmoonlight AnimatedImageTexture（解码层），适配 1.8.9 DynamicTexture
 */
package net.airplus.utils.render

import net.airplus.utils.client.ClientUtils.LOGGER
import net.airplus.utils.client.MinecraftInstance
import net.minecraft.client.renderer.texture.DynamicTexture
import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.metadata.IIOMetadata
import javax.imageio.stream.ImageInputStream
import org.w3c.dom.NamedNodeMap
import org.w3c.dom.Node

/**
 * 动图（GIF 等）纹理：后台线程一次性解码全部帧（含帧位移/恢复合成的正确拼帧），
 * 每帧只保存一份 ARGB int[]；渲染线程按时间线取当前帧，仅在帧序号变化时把该帧
 * 数据写入 DynamicTexture 并上传 —— 每帧最多一次纹理上传（W*H int 写入 + glTexSubImage），
 * 无逐帧解码、无逐帧纹理创建，性能开销与静态图片近似。
 *
 * 防滥用上限（与 edmoonlight 一致）：帧数 ≤ 500，画布 ≤ 16M 像素，总像素 ≤ 64M，
 * 超限直接报错，避免一张大 GIF 打爆显存/内存。
 */
class AnimatedImage : MinecraftInstance {

    class Frame internal constructor(val argb: IntArray, val delayMillis: Long)

    private class FrameMeta(
        val left: Int,
        val top: Int,
        val delayMillis: Long,
        val restoreBackground: Boolean,
        val restorePrevious: Boolean
    )

    private class Decoded(
        val width: Int,
        val height: Int,
        val frames: List<Frame>,
        val totalDuration: Long
    )

    private val resourceLocation = net.minecraft.util.ResourceLocation("airplus", "animated_${System.identityHashCode(this)}")

    private var texture: DynamicTexture? = null
    private var sourceKey: String? = null
    private var error: String? = null
    private var loadGeneration = 0
    var imageWidth = 0
        private set
    var imageHeight = 0
        private set
    private var totalDuration = 1L
    private var startedAt = 0L
    private var loading = false
    private var frames: List<Frame> = emptyList()
    private var currentFrame = -1

    val isReady: Boolean
        get() = texture != null && frames.isNotEmpty()

    val isLoading: Boolean
        get() = loading

    val errorText: String?
        get() = error

    /** 当前纹理的 ResourceLocation（未加载时为 null，调用方跳过绘制） */
    val resource: net.minecraft.util.ResourceLocation?
        get() = texture?.let { resourceLocation }

    fun loadFile(file: File) {
        val normalized = file.absoluteFile.normalize().path
        request("file:$normalized") { file.inputStream() }
    }

    fun update() {
        val tex = texture ?: return
        if (frames.isEmpty()) return
        val elapsed = Math.floorMod(System.currentTimeMillis() - startedAt, totalDuration)
        var frameEnd = 0L
        var frameIndex = frames.size - 1
        for (i in frames.indices) {
            frameEnd += frames[i].delayMillis
            if (elapsed >= frameEnd) continue
            frameIndex = i
            break
        }
        if (frameIndex != currentFrame) uploadFrame(frameIndex)
    }

    fun close() {
        loadGeneration++
        loading = false
        sourceKey = null
        error = null
        frames = emptyList()
        imageWidth = 0
        imageHeight = 0
        currentFrame = -1
        totalDuration = 1L
        destroyTexture()
    }

    private fun request(key: String, supplier: () -> InputStream) {
        if (key == sourceKey && (loading || isReady || error != null)) return
        val generation = loadGeneration + 1
        loadGeneration = generation
        sourceKey = key
        loading = true
        error = null
        frames = emptyList()
        imageWidth = 0
        imageHeight = 0
        currentFrame = -1
        destroyTexture()
        CompletableFuture.supplyAsync {
            supplier().use { input -> decode(input) }
        }.whenComplete { decoded, throwable ->
            mc.addScheduledTask {
                if (generation != loadGeneration) return@addScheduledTask
                loading = false
                if (throwable != null) {
                    error = rootMessage(throwable)
                    LOGGER.error("[AnimatedImage] Failed to load animated image {}: {}", key, error)
                    return@addScheduledTask
                }
                try {
                    install(decoded)
                } catch (e: Exception) {
                    error = e.message
                    LOGGER.error("[AnimatedImage] Failed to upload animated image {}", key, e)
                }
            }
        }
    }

    private fun install(decoded: Decoded) {
        destroyTexture()
        imageWidth = decoded.width
        imageHeight = decoded.height
        frames = decoded.frames
        totalDuration = decoded.totalDuration
        currentFrame = -1
        startedAt = System.currentTimeMillis()
        val tex = DynamicTexture(imageWidth, imageHeight)
        // 初始帧立即填充，避免首帧黑块
        System.arraycopy(frames[0].argb, 0, tex.textureData, 0, imageWidth * imageHeight)
        tex.updateDynamicTexture()
        texture = tex
        mc.textureManager.loadTexture(resourceLocation, tex)
        currentFrame = 0
    }

    private fun uploadFrame(index: Int) {
        val tex = texture ?: return
        if (index < 0 || index >= frames.size) return
        System.arraycopy(frames[index].argb, 0, tex.textureData, 0, imageWidth * imageHeight)
        tex.updateDynamicTexture()
        currentFrame = index
    }

    private fun destroyTexture() {
        texture?.let { mc.textureManager.deleteTexture(resourceLocation) }
        texture = null
    }

    companion object {
        private const val MAX_FRAMES = 500
        private const val MAX_CANVAS_PIXELS = 16L * 1024L * 1024L
        private const val MAX_TOTAL_PIXELS = 64L * 1024L * 1024L

        /** ImageIO 解码 + 帧位移/恢复正确合成（后台线程调用，纯 AWT 操作） */
        private fun decode(input: InputStream): Decoded {
            val imageInput: ImageInputStream = ImageIO.createImageInputStream(input)
                ?: throw IOException("Unsupported or unreadable image")
            try {
                val readers = ImageIO.getImageReaders(imageInput)
                if (!readers.hasNext()) throw IOException("Unsupported image format")
                val reader = readers.next()
                try {
                    reader.setInput(imageInput, false, false)
                    val frameCount = reader.getNumImages(true)
                    if (frameCount < 1 || frameCount > MAX_FRAMES) {
                        throw IOException("Image frame count must be between 1 and $MAX_FRAMES")
                    }
                    val (width, height) = canvasSize(reader)
                    val canvasPixels = width.toLong() * height.toLong()
                    if (width < 1 || height < 1 || canvasPixels > MAX_CANVAS_PIXELS ||
                        canvasPixels * frameCount > MAX_TOTAL_PIXELS
                    ) {
                        throw IOException("Image is too large to render safely")
                    }
                    var canvas = newCanvas(width, height)
                    val frames = ArrayList<Frame>(frameCount)
                    var totalDuration = 0L
                    for (i in 0 until frameCount) {
                        val raw = reader.read(i)
                        val meta = frameMeta(reader.getImageMetadata(i))
                        val previous = if (meta.restorePrevious) copy(canvas) else null
                        val drawX = if (raw.width == width) 0 else meta.left
                        val drawY = if (raw.height == height) 0 else meta.top
                        withGraphics(canvas) { g ->
                            g.composite = AlphaComposite.SrcOver
                            g.drawImage(raw, drawX, drawY, null)
                        }
                        val delay = if (frameCount == 1) 1000L else meta.delayMillis
                        frames.add(Frame(canvas.getRGB(0, 0, width, height, null, 0, width), delay))
                        totalDuration += delay
                        when {
                            meta.restoreBackground -> withGraphics(canvas) { clear ->
                                clear.composite = AlphaComposite.Clear
                                clear.fillRect(meta.left, meta.top, raw.width, raw.height)
                            }
                            previous != null -> canvas = previous
                        }
                    }
                    return Decoded(width, height, frames, totalDuration.coerceAtLeast(1L))
                } finally {
                    reader.dispose()
                }
            } finally {
                imageInput.close()
            }
        }

        private fun newCanvas(width: Int, height: Int) = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

        private fun copy(source: BufferedImage): BufferedImage {
            val copy = newCanvas(source.width, source.height)
            withGraphics(copy) { g ->
                g.composite = AlphaComposite.Src
                g.drawImage(source, 0, 0, null)
            }
            return copy
        }

        /** java.awt.Graphics 未实现 AutoCloseable，手动管理 dispose */
        private inline fun <T> withGraphics(canvas: BufferedImage, block: (Graphics2D) -> T): T {
            val g = canvas.createGraphics()
            try {
                return block(g)
            } finally {
                g.dispose()
            }
        }

        private fun canvasSize(reader: ImageReader): Pair<Int, Int> {
            var width = reader.getWidth(0)
            var height = reader.getHeight(0)
            val metadata: IIOMetadata = reader.streamMetadata ?: return width to height
            val format = metadata.nativeMetadataFormatName ?: return width to height
            val descriptor = findNode(metadata.getAsTree(format), "LogicalScreenDescriptor") ?: return width to height
            width = intAttr(descriptor, "logicalScreenWidth", width)
            height = intAttr(descriptor, "logicalScreenHeight", height)
            return width to height
        }

        private fun frameMeta(metadata: IIOMetadata?): FrameMeta {
            var left = 0
            var top = 0
            var delay = 100L
            var disposal = "none"
            if (metadata != null) {
                val format = metadata.nativeMetadataFormatName
                if (format != null) {
                    val root = metadata.getAsTree(format)
                    val descriptor = findNode(root, "ImageDescriptor")
                    if (descriptor != null) {
                        left = intAttr(descriptor, "imageLeftPosition", 0)
                        top = intAttr(descriptor, "imageTopPosition", 0)
                    }
                    val control = findNode(root, "GraphicControlExtension")
                    if (control != null) {
                        delay = (intAttr(control, "delayTime", 10).toLong() * 10L).coerceIn(20L, 10000L)
                        disposal = attr(control, "disposalMethod", "none")
                    }
                }
            }
            return FrameMeta(left, top, delay, disposal == "restoreToBackgroundColor", disposal == "restoreToPrevious")
        }

        private fun findNode(node: Node?, name: String): Node? {
            if (node == null) return null
            if (name == node.nodeName) return node
            var child = node.firstChild
            while (child != null) {
                val found = findNode(child, name)
                if (found != null) return found
                child = child.nextSibling
            }
            return null
        }

        private fun intAttr(node: Node, name: String, fallback: Int): Int = try {
            attr(node, name, fallback.toString()).toInt()
        } catch (e: NumberFormatException) {
            fallback
        }

        private fun attr(node: Node, name: String, fallback: String): String {
            val attributes: NamedNodeMap = node.attributes ?: return fallback
            val item = attributes.getNamedItem(name) ?: return fallback
            return item.nodeValue ?: fallback
        }

        private fun rootMessage(throwable: Throwable?): String {
            var current: Throwable? = throwable
            while (current?.cause != null) current = current.cause
            current ?: return "Unknown error"
            val message = current.message
            return if (message.isNullOrBlank()) current.javaClass.simpleName else message
        }
    }
}
