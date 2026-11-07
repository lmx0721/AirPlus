/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.client

import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.file.FileManager
import net.airplus.utils.GlowUtils
import net.airplus.utils.client.ClientUtils
import net.airplus.utils.client.MinecraftInstance
import net.minecraft.client.gui.Gui
import net.minecraft.client.gui.GuiIngameMenu
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.gui.inventory.GuiChest
import net.minecraft.client.gui.inventory.GuiInventory
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.GL11
import java.awt.Color
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/**
 * GuiImage —— 在指定 GUI 界面上叠加一张自定义图片（默认右下角）。
 *
 * 仅支持：ClickGUI / 箱子界面 / ESC 界面 / 背包界面（用户明确要求的白名单）。
 *
 * - Image：本地图片选择（仿照 Cape：启动时从 jar 内 assets/minecraft/airplus/image/
 *   提取预设图片到本地 images 文件夹，再扫描生成可选列表；支持 png / jpg）；
 * - GUI 开关：ClickGUI / Chest / Esc / Inventory 各自独立开关；
 * - Size：图片最长边像素（1..5000，可超小可超大），等比缩放；
 * - X / Y：相对「右下角贴合」的偏移（X 正=右移、Y 正=下移，可移出屏幕）；
 * - Rotation：-180°..180°，绕图片中心旋转（glow 同步跟随旋转）；
 * - Glow：发光 + GlowColor + GlowRadius。
 *
 * 渲染点：各目标 GUI 的 drawScreen TAIL 注入（MixinGuiContainer / MixinGuiIngameMenu /
 * MixinClickGuiScreens）——直接画在 GUI 之上，层级确定，与 HUD 事件时序无关。
 *
 * 纹理加载：LRU 缓存（仿照 Cape），选中时才读盘解码（BufferedImage），主线程一次性
 * 开销仅发生在切换图片时；渲染期只做查表 + GL 绘制。
 */
object GuiImage : Module("GuiImage", Category.CLIENT), MinecraftInstance {

    private val imageFolder: File by lazy {
        File(FileManager.dir, "images").apply { mkdirs() }
    }

    private val MAX_IMAGE_CACHE_SIZE = 8

    private var imageModeValue: net.airplus.config.ListValue? = null

    private val clickGuiValue by boolean("ClickGUI", true)
    private val chestValue by boolean("Chest", true)
    private val escValue by boolean("Esc", true)
    private val inventoryValue by boolean("Inventory", true)

    private val sizeValue by int("Size", 128, 1..5000, "px")
    private val xValue by int("X", 0, -3000..3000, "px")
    private val yValue by int("Y", 0, -3000..3000, "px")
    private val rotationValue by int("Rotation", 0, -180..180, "°")

    private val glowValue by boolean("Glow", false)
    private val glowColorValue by color("GlowColor", Color(255, 255, 255, 120)) { glowValue }
    private val glowRadiusValue by int("GlowRadius", 8, 1..50) { glowValue }

    private val tintColorValue by color("Color", Color(255, 255, 255, 255))

    /** 按钮式设置：开启时用系统文件管理器打开本地图片文件夹，随后自动复位 */
    private val folderValue = boolean("Folder", false)

    /** LRU 纹理缓存：name -> (resourceLocation, 宽高比 = w / max(w, h)) */
    private val imageMap = object : LinkedHashMap<String, Pair<ResourceLocation, Float>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<ResourceLocation, Float>>): Boolean {
            return if (size > MAX_IMAGE_CACHE_SIZE) {
                eldest.value?.first?.let { loc ->
                    try {
                        mc.textureManager.deleteTexture(loc)
                    } catch (e: Exception) {
                        ClientUtils.LOGGER.warn("[GuiImage] Failed to delete texture: ${loc.resourcePath}")
                    }
                }
                true
            } else {
                false
            }
        }
    }

    private val imageFiles = mutableListOf<File>()
    private var initialized = false

    val imageMode: String
        get() = imageModeValue?.get() ?: "None"

    init {
        initializeImages()

        // Folder：打开本地图片存放目录（mkdirs 已在 imageFolder 懒加载时保证）
        folderValue.onChanged { opened ->
            if (opened) {
                folderValue.set(false)
                try {
                    java.awt.Desktop.getDesktop().open(imageFolder)
                } catch (e: Throwable) {
                    ClientUtils.LOGGER.warn("[GuiImage] Failed to open image folder: ${imageFolder.absolutePath}", e)
                }
            }
        }
    }

    private fun initializeImages() {
        if (initialized) return

        try {
            ClientUtils.LOGGER.info("[GuiImage] Starting initialization...")

            extractImagesFromJar()
            scanImages()

            val imageNames = mutableListOf("None")
            imageNames.addAll(imageFiles.map { it.nameWithoutExtension })

            imageModeValue = choices("Image", imageNames.toTypedArray(), imageNames.first())

            imageModeValue?.onChanged { newValue ->
                ClientUtils.LOGGER.info("[GuiImage] Image selected: $newValue")
            }

            ClientUtils.LOGGER.info("[GuiImage] Initialization complete. Found ${imageFiles.size} images, current: $imageMode")
            initialized = true
        } catch (e: Exception) {
            ClientUtils.LOGGER.error("[GuiImage] Initialization failed: ${e.message}")
            e.printStackTrace()
            imageModeValue = choices("Image", arrayOf("None"), "None")
        }
    }

    private fun extractImagesFromJar() {
        try {
            imageFolder.mkdirs()

            val jarPath = javaClass.protectionDomain.codeSource.location.toURI().path
            if (!jarPath.endsWith(".jar")) {
                copyImagesFromResources()
                return
            }

            val zipFile = ZipFile(jarPath)
            val entries = zipFile.entries()
            var extractedCount = 0

            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (name.startsWith("assets/minecraft/airplus/image/") && isImageFile(name)) {
                    val fileName = File(name).name
                    val targetFile = File(imageFolder, fileName)

                    if (!targetFile.exists()) {
                        try {
                            zipFile.getInputStream(entry).use { input ->
                                FileOutputStream(targetFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            extractedCount++
                            ClientUtils.LOGGER.info("[GuiImage] Extracted: $fileName")
                        } catch (e: Exception) {
                            ClientUtils.LOGGER.error("[GuiImage] Failed to extract $fileName: ${e.message}")
                        }
                    }
                }
            }
            zipFile.close()
            ClientUtils.LOGGER.info("[GuiImage] Extracted $extractedCount images from jar")
        } catch (e: Exception) {
            ClientUtils.LOGGER.error("[GuiImage] Failed to extract images from jar: ${e.message}")
            e.printStackTrace()
            copyImagesFromResources()
        }
    }

    private fun copyImagesFromResources() {
        try {
            imageFolder.mkdirs()

            // 与 assets/minecraft/airplus/image 下的实际资源保持一致
            val knownImages = listOf(
                "deobf.jpg",
                "mygo.png"
            )

            var copiedCount = 0
            for (imageName in knownImages) {
                val targetFile = File(imageFolder, imageName)
                if (!targetFile.exists()) {
                    try {
                        val resource = javaClass.classLoader.getResourceAsStream("assets/minecraft/airplus/image/$imageName")
                        if (resource != null) {
                            FileOutputStream(targetFile).use { output ->
                                resource.copyTo(output)
                            }
                            resource.close()
                            copiedCount++
                            ClientUtils.LOGGER.info("[GuiImage] Copied from resources: $imageName")
                        }
                    } catch (e: Exception) {
                        ClientUtils.LOGGER.error("[GuiImage] Failed to copy $imageName: ${e.message}")
                    }
                }
            }
            ClientUtils.LOGGER.info("[GuiImage] Copied $copiedCount images from resources")
        } catch (e: Exception) {
            ClientUtils.LOGGER.error("[GuiImage] Failed to copy images from resources: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
    }

    private fun scanImages() {
        imageFiles.clear()

        if (!imageFolder.exists() || !imageFolder.isDirectory) {
            ClientUtils.LOGGER.warn("[GuiImage] Image folder does not exist: ${imageFolder.absolutePath}")
            return
        }

        ClientUtils.LOGGER.info("[GuiImage] Scanning folder: ${imageFolder.absolutePath}")

        imageFolder.walk()
            .filter { it.isFile && isImageFile(it.name) }
            .sortedBy { it.nameWithoutExtension.lowercase() }
            .forEach { file ->
                imageFiles.add(file)
                ClientUtils.LOGGER.info("[GuiImage] Found image file: ${file.name}")
            }

        ClientUtils.LOGGER.info("[GuiImage] Total images found: ${imageFiles.size}")
    }

    /** 加载本地图片纹理，返回 (resourceLocation, 宽高比) */
    private fun loadImageTexture(file: File): Pair<ResourceLocation, Float>? {
        return try {
            val bufferedImage = FileInputStream(file).use { fis ->
                ImageIO.read(fis)
            }
            if (bufferedImage != null) {
                val resourceLocation = ResourceLocation("airplus_guiimage_${file.nameWithoutExtension.lowercase()}")
                mc.textureManager.loadTexture(resourceLocation, DynamicTexture(bufferedImage))
                val aspect = bufferedImage.width.toFloat() / maxOf(bufferedImage.width, bufferedImage.height).toFloat()
                resourceLocation to aspect
            } else {
                ClientUtils.LOGGER.error("[GuiImage] Failed to read image: ${file.name}")
                null
            }
        } catch (e: Exception) {
            ClientUtils.LOGGER.error("[GuiImage] Failed to load image texture ${file.name}: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * 由各 GUI mixin 的 drawScreen TAIL 调用：当前界面在白名单内且开启对应开关时绘制图片。
     */
    fun onGuiRender(screen: net.minecraft.client.gui.GuiScreen?) {
        if (!handleEvents()) return
        val currentScreen = screen ?: return
        val (image, aspect) = currentImage() ?: return

        val show = when (currentScreen) {
            is GuiChest -> chestValue
            is GuiInventory -> inventoryValue
            is GuiIngameMenu -> escValue
            else -> clickGuiValue && isClickGuiScreen(currentScreen)
        }
        if (!show) return

        drawImage(currentScreen, image, aspect)
    }

    private fun isClickGuiScreen(screen: net.minecraft.client.gui.GuiScreen): Boolean {
        // 所有 ClickGUI 样式（ClickGui / RiseClickGui / FlatClickGui / AstolfoClickGui /
        // NeverloseScreen / MoonLightClickGui / AugustusClickGui / OpaiScreen）均位于
        // net.airplus.ui.client.clickgui 包下
        return screen.javaClass.name.startsWith("net.airplus.ui.client.clickgui.")
    }

    /** 当前选中图片的缓存条目；"None" 或加载失败返回 null */
    private fun currentImage(): Pair<ResourceLocation, Float>? {
        val selected = imageMode ?: return null
        if (selected == "None") return null

        imageMap[selected]?.let { return it }

        val file = imageFiles.find { it.nameWithoutExtension == selected } ?: return null
        val loaded = loadImageTexture(file) ?: return null
        imageMap[selected] = loaded
        return loaded
    }

    private fun drawImage(screen: net.minecraft.client.gui.GuiScreen, image: ResourceLocation, aspect: Float) {
        val sr = ScaledResolution(mc)
        val maxSize = sizeValue.toFloat()

        // 等比缩放：最长边 = Size
        val aspectSafe = aspect.coerceIn(0.01F, 100F)
        val width: Float
        val height: Float
        if (aspectSafe >= 1F) {
            width = maxSize
            height = maxSize / aspectSafe
        } else {
            width = maxSize * aspectSafe
            height = maxSize
        }

        // 默认右下角贴合，X 正 = 右移，Y 正 = 下移
        val centerX = sr.scaledWidth - width / 2F + xValue
        val centerY = sr.scaledHeight - height / 2F + yValue

        GL11.glPushMatrix()
        try {
            // 平移到中心 → 旋转 → 以中心为原点绘制
            GlStateManager.translate(centerX, centerY, 0F)
            if (rotationValue != 0) {
                GL11.glRotatef(rotationValue.toFloat(), 0F, 0F, 1F)
            }

            val halfW = width / 2F
            val halfH = height / 2F

            if (glowValue) {
                GlowUtils.drawGlow(-halfW, -halfH, width, height, glowRadiusValue, glowColorValue)
            }

            GlStateManager.enableBlend()
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO)
            GlStateManager.disableDepth()
            GL11.glColor4f(
                tintColorValue.red / 255F,
                tintColorValue.green / 255F,
                tintColorValue.blue / 255F,
                tintColorValue.alpha / 255F
            )

            mc.textureManager.bindTexture(image)
            // uWidth/vHeight = tileWidth/tileHeight = 1 → 整张纹理铺满目标矩形
            Gui.drawScaledCustomSizeModalRect(
                (-halfW).toInt(),
                (-halfH).toInt(),
                0F, 0F, 1, 1,
                width.toInt().coerceAtLeast(1),
                height.toInt().coerceAtLeast(1),
                1F, 1F
            )

            GL11.glColor4f(1F, 1F, 1F, 1F)
            GlStateManager.enableDepth()
            GlStateManager.disableBlend()
        } finally {
            GL11.glPopMatrix()
        }
    }

    override val tag
        get() = imageMode

    fun refreshImages() {
        ClientUtils.LOGGER.info("[GuiImage] Refreshing images...")
        initialized = false
        imageMap.values.forEach { (loc, _) ->
            try {
                mc.textureManager.deleteTexture(loc)
            } catch (e: Exception) {
                ClientUtils.LOGGER.warn("[GuiImage] Failed to delete texture during refresh: ${loc.resourcePath}")
            }
        }
        imageMap.clear()
        initializeImages()

        imageModeValue?.let { value ->
            val imageNames = mutableListOf("None")
            imageNames.addAll(imageFiles.map { it.nameWithoutExtension })
            value.updateValues(imageNames.toTypedArray())
            ClientUtils.LOGGER.info("[GuiImage] Refreshed image list: ${imageFiles.size} images")
        }
    }

    override fun onDisable() {
        imageMap.values.forEach { (loc, _) ->
            try {
                mc.textureManager.deleteTexture(loc)
            } catch (e: Exception) {
                ClientUtils.LOGGER.warn("[GuiImage] Failed to delete texture on disable: ${loc.resourcePath}")
            }
        }
        imageMap.clear()
    }
}
