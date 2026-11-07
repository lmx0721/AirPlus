/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.render

import net.airplus.config.ListValue
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.file.FileManager
import net.airplus.skin.SkinAPI
import net.airplus.utils.client.ClientUtils
import net.minecraft.client.renderer.ImageBufferDownload
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.util.ResourceLocation
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.*
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/**
 * CustomSkin —— 自定义玩家皮肤：
 *
 * - Mode = Local：从本地 skins 文件夹选择皮肤（仿照 Cape：启动时从 jar 内
 *   assets/minecraft/airplus/skin/ 提取预设皮肤到本地，再扫描文件夹生成可选列表）；
 * - Mode = Name：按正版玩家名在线拉取皮肤（SkinAPI 异步加载，见 SkinAPI）；
 * - Target：作用于自己 / 所有玩家。
 *
 * 纹理应用点：MixinAbstractClientPlayer#getLocationSkin HEAD 注入，主线程仅做查表。
 */
object CustomSkin : Module("CustomSkin", Category.RENDER) {

    private val skinFolder: File by lazy {
        File(FileManager.dir, "skins").apply { mkdirs() }
    }

    private val modeValue = choices("Mode", arrayOf("Local", "Name"), "Local")
    private val targetValue = choices("Target", arrayOf("Self", "All Players"), "Self")
    private val playerNameValue = text("PlayerName", "Notch") { modeValue.get() == "Name" }

    /** 按钮式设置：开启时用系统文件管理器打开本地皮肤文件夹，随后自动复位 */
    private val folderValue = boolean("Folder", false)

    private var localSkinValue: ListValue? = null

    // LRU 缓存：限制最大容量，超过时删除最旧纹理
    private val skinMap = object : LinkedHashMap<String, ResourceLocation>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResourceLocation>): Boolean {
            return if (size > MAX_SKIN_CACHE_SIZE) {
                eldest.value?.let { loc ->
                    try {
                        mc.textureManager.deleteTexture(loc)
                    } catch (e: Exception) {
                        ClientUtils.LOGGER.warn("[CustomSkin] Failed to delete texture: ${loc.resourcePath}")
                    }
                }
                true
            } else {
                false
            }
        }
    }

    private val MAX_SKIN_CACHE_SIZE = 16

    private val skinFiles = mutableListOf<File>()
    private var currentLocalSkin: ResourceLocation? = null
    private var initialized = false
    private var lastSkinMode: String = "None"

    val skinMode: String
        get() = localSkinValue?.get() ?: "None"

    init {
        initializeSkins()

        // Folder：打开本地皮肤存放目录（mkdirs 已在 skinFolder 懒加载时保证）
        folderValue.onChanged { opened ->
            if (opened) {
                folderValue.set(false)
                try {
                    java.awt.Desktop.getDesktop().open(skinFolder)
                } catch (e: Throwable) {
                    ClientUtils.LOGGER.warn("[CustomSkin] Failed to open skin folder: ${skinFolder.absolutePath}", e)
                }
            }
        }
    }

    private fun initializeSkins() {
        if (initialized) return

        try {
            ClientUtils.LOGGER.info("[CustomSkin] Starting initialization...")

            extractSkinsFromJar()
            scanSkins()

            val skinNames = mutableListOf("None")
            skinNames.addAll(skinFiles.map { it.nameWithoutExtension })

            localSkinValue = choices("Skin", skinNames.toTypedArray(), skinNames.first()) { modeValue.get() == "Local" }

            localSkinValue?.onChanged { newValue ->
                ClientUtils.LOGGER.info("[CustomSkin] Skin selected: $newValue")
                updateCurrentSkin(newValue)
            }

            lastSkinMode = skinMode
            updateCurrentSkin(skinMode)

            ClientUtils.LOGGER.info("[CustomSkin] Initialization complete. Found ${skinFiles.size} skins, current: $skinMode")
            initialized = true
        } catch (e: Exception) {
            ClientUtils.LOGGER.error("[CustomSkin] Initialization failed: ${e.message}")
            e.printStackTrace()
            localSkinValue = choices("Skin", arrayOf("None"), "None")
        }
    }

    private fun updateCurrentSkin(selectedSkin: String) {
        if (selectedSkin == "None") {
            currentLocalSkin = null
            return
        }

        if (skinMap.containsKey(selectedSkin)) {
            currentLocalSkin = skinMap[selectedSkin]
            return
        }

        val file = skinFiles.find { it.nameWithoutExtension == selectedSkin }
        if (file != null) {
            val texture = loadSkinTexture(file)
            if (texture != null) {
                skinMap[selectedSkin] = texture
                currentLocalSkin = texture
                ClientUtils.LOGGER.info("[CustomSkin] Loaded and cached skin: $selectedSkin -> $texture")
            } else {
                currentLocalSkin = null
                ClientUtils.LOGGER.error("[CustomSkin] Failed to load skin: $selectedSkin")
            }
        } else {
            currentLocalSkin = null
            ClientUtils.LOGGER.error("[CustomSkin] Skin file not found: $selectedSkin")
        }
    }

    private fun extractSkinsFromJar() {
        try {
            skinFolder.mkdirs()

            val jarPath = javaClass.protectionDomain.codeSource.location.toURI().path
            if (!jarPath.endsWith(".jar")) {
                copySkinsFromResources()
                return
            }

            val zipFile = ZipFile(jarPath)
            val entries = zipFile.entries()
            var extractedCount = 0

            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (name.startsWith("assets/minecraft/airplus/skin/") && name.endsWith(".png")) {
                    val fileName = File(name).name
                    val targetFile = File(skinFolder, fileName)

                    if (!targetFile.exists()) {
                        try {
                            zipFile.getInputStream(entry).use { input ->
                                FileOutputStream(targetFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            extractedCount++
                            ClientUtils.LOGGER.info("[CustomSkin] Extracted: $fileName")
                        } catch (e: Exception) {
                            ClientUtils.LOGGER.error("[CustomSkin] Failed to extract $fileName: ${e.message}")
                        }
                    }
                }
            }
            zipFile.close()
            ClientUtils.LOGGER.info("[CustomSkin] Extracted $extractedCount skins from jar")
        } catch (e: Exception) {
            ClientUtils.LOGGER.error("[CustomSkin] Failed to extract skins from jar: ${e.message}")
            e.printStackTrace()
            copySkinsFromResources()
        }
    }

    private fun copySkinsFromResources() {
        try {
            skinFolder.mkdirs()

            // 与 assets/minecraft/airplus/skin 下的实际资源保持一致
            val knownSkins = listOf(
                "sun_zheng_yo.png"
            )

            var copiedCount = 0
            for (skinName in knownSkins) {
                val targetFile = File(skinFolder, skinName)
                if (!targetFile.exists()) {
                    try {
                        val resource = javaClass.classLoader.getResourceAsStream("assets/minecraft/airplus/skin/$skinName")
                        if (resource != null) {
                            FileOutputStream(targetFile).use { output ->
                                resource.copyTo(output)
                            }
                            resource.close()
                            copiedCount++
                            ClientUtils.LOGGER.info("[CustomSkin] Copied from resources: $skinName")
                        }
                    } catch (e: Exception) {
                        ClientUtils.LOGGER.error("[CustomSkin] Failed to copy $skinName: ${e.message}")
                    }
                }
            }
            ClientUtils.LOGGER.info("[CustomSkin] Copied $copiedCount skins from resources")
        } catch (e: Exception) {
            ClientUtils.LOGGER.error("[CustomSkin] Failed to copy skins from resources: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun scanSkins() {
        skinFiles.clear()
        skinMap.clear()

        if (!skinFolder.exists() || !skinFolder.isDirectory) {
            ClientUtils.LOGGER.warn("[CustomSkin] Skin folder does not exist: ${skinFolder.absolutePath}")
            return
        }

        ClientUtils.LOGGER.info("[CustomSkin] Scanning folder: ${skinFolder.absolutePath}")

        skinFolder.walk()
            .filter { file ->
                file.isFile && file.extension.equals("png", ignoreCase = true)
            }
            .sortedBy { it.nameWithoutExtension.lowercase() }
            .forEach { file ->
                skinFiles.add(file)
                ClientUtils.LOGGER.info("[CustomSkin] Found skin file: ${file.name}")
            }

        ClientUtils.LOGGER.info("[CustomSkin] Total skins found: ${skinFiles.size}")
    }

    /**
     * 加载本地皮肤纹理。经 ImageBufferDownload 解析（自动处理 64x32 旧版皮肤 → 64x64 转换）。
     */
    private fun loadSkinTexture(file: File): ResourceLocation? {
        return try {
            val bufferedImage = FileInputStream(file).use { fis ->
                ImageIO.read(fis)
            }
            if (bufferedImage != null) {
                val parsed = ImageBufferDownload().parseUserSkin(bufferedImage) ?: bufferedImage
                val resourceLocation = ResourceLocation("airplus_customskin_local_${file.nameWithoutExtension.lowercase()}")
                mc.textureManager.loadTexture(resourceLocation, DynamicTexture(parsed))
                resourceLocation
            } else {
                ClientUtils.LOGGER.error("[CustomSkin] Failed to read image: ${file.name}")
                null
            }
        } catch (e: Exception) {
            ClientUtils.LOGGER.error("[CustomSkin] Failed to load skin texture ${file.name}: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * 由 MixinAbstractClientPlayer#getLocationSkin 调用：返回该玩家的自定义皮肤，
     * null 表示不接管（走原版逻辑）。
     */
    fun getSkinForPlayer(uuid: UUID): ResourceLocation? {
        if (!handleEvents()) return null

        val currentPlayer = mc.thePlayer ?: return null

        if (targetValue.get() == "Self" && uuid != currentPlayer.uniqueID) return null

        return when (modeValue.get()) {
            "Name" -> SkinAPI.getSkin(playerNameValue.get())
            else -> {
                // 实时切换支持
                val currentMode = skinMode
                if (currentMode != lastSkinMode) {
                    lastSkinMode = currentMode
                    updateCurrentSkin(currentMode)
                }
                currentLocalSkin
            }
        }
    }

    override val tag
        get() = when (modeValue.get()) {
            "Name" -> playerNameValue.get()
            else -> skinMode
        }

    fun refreshSkins() {
        ClientUtils.LOGGER.info("[CustomSkin] Refreshing skins...")
        initialized = false
        skinMap.values.forEach { loc ->
            try {
                mc.textureManager.deleteTexture(loc)
            } catch (e: Exception) {
                ClientUtils.LOGGER.warn("[CustomSkin] Failed to delete texture during refresh: ${loc.resourcePath}")
            }
        }
        skinMap.clear()
        currentLocalSkin = null
        initializeSkins()

        localSkinValue?.let { value ->
            val skinNames = mutableListOf("None")
            skinNames.addAll(skinFiles.map { it.nameWithoutExtension })
            value.updateValues(skinNames.toTypedArray())
            ClientUtils.LOGGER.info("[CustomSkin] Refreshed skin list: ${skinFiles.size} skins")
        }
    }

    override fun onDisable() {
        skinMap.values.forEach { loc ->
            try {
                mc.textureManager.deleteTexture(loc)
            } catch (e: Exception) {
                ClientUtils.LOGGER.warn("[CustomSkin] Failed to delete texture on disable: ${loc.resourcePath}")
            }
        }
        skinMap.clear()
        currentLocalSkin = null
    }
}
