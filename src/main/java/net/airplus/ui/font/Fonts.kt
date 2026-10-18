/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.ui.font

import com.google.gson.JsonObject
import net.airplus.file.FileManager.fontsDir
import net.airplus.utils.client.ClientUtils.LOGGER
import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.io.readJson
import net.airplus.utils.io.writeJson
import net.minecraft.client.gui.FontRenderer
import java.awt.Font
import java.io.File
import java.util.IdentityHashMap
import kotlin.system.measureTimeMillis

data class FontInfo(val name: String, val size: Int = -1, val isCustom: Boolean = false)

data class CustomFontInfo(val name: String, val fontFile: String, val fontSize: Int)

/**
 * 字体注册表：key -> provider。
 * 启动时只注册 provider（懒字体不烘焙字形），首次访问或 FontValue 遍历时才真正创建。
 */
private val FONT_PROVIDERS = LinkedHashMap<FontInfo, () -> FontRenderer>()

/** 已实际创建的 FontRenderer 反查表（用于 getFontDetails / customFonts）。 */
private val CREATED = IdentityHashMap<FontRenderer, FontInfo>()

object Fonts : MinecraftInstance {

    private val configFile = File(fontsDir, "fonts.json")
    private var customFontInfoList: List<CustomFontInfo>
        get() = with(configFile) {
            if (exists()) {
                try {
                    readJson().asJsonArray.map {
                        it as JsonObject
                        val fontFile = it["fontFile"].asString
                        val fontSize = it["fontSize"].asInt
                        val name = if (it.has("name")) it["name"].asString else fontFile
                        CustomFontInfo(name, fontFile, fontSize)
                    }
                } catch (e: Exception) {
                    LOGGER.error("Failed to load fonts", e)
                    emptyList()
                }
            } else {
                createNewFile()
                writeText("[]")
                emptyList()
            }
        }
        set(value) = configFile.writeJson(value)

    val minecraftFontInfo = FontInfo(name = "Minecraft Font")
    val minecraftFont: FontRenderer by lazy {
        mc.fontRendererObj
    }

    // ===== 启动关键路径字体：HUD / 核心 GUI 需要，loadFonts 时立即烘焙 =====
    lateinit var fontSemibold35: GameFontRenderer
    lateinit var fontSemibold40: GameFontRenderer
    lateinit var fontRegular30: GameFontRenderer
    lateinit var fontRegular35: GameFontRenderer
    lateinit var fontRegular40: GameFontRenderer

    // ===== 其余字体：懒加载，首次访问（或 FontValue 字体循环遍历）时才创建 =====
    val fontExtraBold35: GameFontRenderer by lazy { extraBold35() }
    val fontExtraBold40: GameFontRenderer by lazy { extraBold40() }
    val fontRegular45: GameFontRenderer by lazy {
        register(FontInfo(name = "HarmonyOS Sans SC Regular", size = 45), getFontOrDefault("HarmonyOS_Sans_SC_Regular.ttf", 45).asGameFontRenderer())
    }
    val fontBold180: GameFontRenderer by lazy {
        // HarmonyOS_Sans_SC_Medium 专用内部字体（主菜单大标题等），不进注册表（不可被 FontValue 选中）
        getFontOrDefault("HarmonyOS_Sans_SC_Medium.ttf", 180).asGameFontRenderer()
    }

    val fontNeutonBold35: GameFontRenderer by lazy {
        register(FontInfo(name = "Neuton Bold", size = 35), getFontOrDefault("Neuton-Bold.ttf", 35).asGameFontRenderer())
    }
    val fontNeutonBold40: GameFontRenderer by lazy {
        register(FontInfo(name = "Neuton Bold", size = 40), getFontOrDefault("Neuton-Bold.ttf", 40).asGameFontRenderer())
    }
    val fontNeutonBold50: GameFontRenderer by lazy {
        register(FontInfo(name = "Neuton Bold", size = 50), getFontOrDefault("Neuton-Bold.ttf", 50).asGameFontRenderer())
    }
    val fontNeutonExtraLight35: GameFontRenderer by lazy {
        register(FontInfo(name = "Neuton ExtraLight", size = 35), getFontOrDefault("Neuton-ExtraLight.ttf", 35).asGameFontRenderer())
    }
    val fontNeutonExtraLight40: GameFontRenderer by lazy {
        register(FontInfo(name = "Neuton ExtraLight", size = 40), getFontOrDefault("Neuton-ExtraLight.ttf", 40).asGameFontRenderer())
    }
    val fontNeutonItalic35: GameFontRenderer by lazy {
        register(FontInfo(name = "Neuton Italic", size = 35), getFontOrDefault("Neuton-Italic.ttf", 35).asGameFontRenderer())
    }
    val fontNeutonItalic40: GameFontRenderer by lazy {
        register(FontInfo(name = "Neuton Italic", size = 40), getFontOrDefault("Neuton-Italic.ttf", 40).asGameFontRenderer())
    }
    val fontNeutonLight35: GameFontRenderer by lazy {
        register(FontInfo(name = "Neuton Light", size = 35), getFontOrDefault("Neuton-Light.ttf", 35).asGameFontRenderer())
    }
    val fontNeutonLight40: GameFontRenderer by lazy {
        register(FontInfo(name = "Neuton Light", size = 40), getFontOrDefault("Neuton-Light.ttf", 40).asGameFontRenderer())
    }
    val fontNosifer35: GameFontRenderer by lazy {
        register(FontInfo(name = "Nosifer", size = 35), getFontOrDefault("Nosifer-Regular.ttf", 35).asGameFontRenderer())
    }
    val fontNosifer40: GameFontRenderer by lazy {
        register(FontInfo(name = "Nosifer", size = 40), getFontOrDefault("Nosifer-Regular.ttf", 40).asGameFontRenderer())
    }
    val fontNosifer50: GameFontRenderer by lazy {
        register(FontInfo(name = "Nosifer", size = 50), getFontOrDefault("Nosifer-Regular.ttf", 50).asGameFontRenderer())
    }
    val fontZenDots35: GameFontRenderer by lazy {
        register(FontInfo(name = "Zen Dots", size = 35), getFontOrDefault("ZenDots-Regular.ttf", 35).asGameFontRenderer())
    }
    val fontZenDots40: GameFontRenderer by lazy {
        register(FontInfo(name = "Zen Dots", size = 40), getFontOrDefault("ZenDots-Regular.ttf", 40).asGameFontRenderer())
    }
    val fontZenDots50: GameFontRenderer by lazy {
        register(FontInfo(name = "Zen Dots", size = 50), getFontOrDefault("ZenDots-Regular.ttf", 50).asGameFontRenderer())
    }
    val fontSFApple35: GameFontRenderer by lazy {
        register(FontInfo(name = "SF Apple", size = 35), getFontOrDefault("SFApple.ttf", 35).asGameFontRenderer())
    }
    val fontSFApple40: GameFontRenderer by lazy {
        register(FontInfo(name = "SF Apple", size = 40), getFontOrDefault("SFApple.ttf", 40).asGameFontRenderer())
    }
    val fontSFApple50: GameFontRenderer by lazy {
        register(FontInfo(name = "SF Apple", size = 50), getFontOrDefault("SFApple.ttf", 50).asGameFontRenderer())
    }
    val fontRise35: GameFontRenderer by lazy {
        register(FontInfo(name = "Rise SF UI Pro", size = 35), getFontOrDefault("SF-UI-Pro.ttf", 35).asGameFontRenderer())
    }
    val fontRise40: GameFontRenderer by lazy {
        register(FontInfo(name = "Rise SF UI Pro", size = 40), getFontOrDefault("SF-UI-Pro.ttf", 40).asGameFontRenderer())
    }
    val fontRise50: GameFontRenderer by lazy {
        register(FontInfo(name = "Rise SF UI Pro", size = 50), getFontOrDefault("SF-UI-Pro.ttf", 50).asGameFontRenderer())
    }

    val fontUsans15: GameFontRenderer by lazy {
        register(FontInfo(name = "Usans", size = 15), getFontOrDefault("usans.otf", 15).asGameFontRenderer())
    }
    val fontUsans16: GameFontRenderer by lazy {
        register(FontInfo(name = "Usans", size = 16), getFontOrDefault("usans.otf", 16).asGameFontRenderer())
    }
    val fontUsans20: GameFontRenderer by lazy {
        register(FontInfo(name = "Usans", size = 20), getFontOrDefault("usans.otf", 20).asGameFontRenderer())
    }
    val fontUsans32: GameFontRenderer by lazy {
        register(FontInfo(name = "Usans", size = 32), getFontOrDefault("usans.otf", 32).asGameFontRenderer())
    }
    val fontUsans40: GameFontRenderer by lazy {
        register(FontInfo(name = "Usans", size = 40), getFontOrDefault("usans.otf", 40).asGameFontRenderer())
    }
    val fontUsans50: GameFontRenderer by lazy {
        register(FontInfo(name = "Usans", size = 50), getFontOrDefault("usans.otf", 50).asGameFontRenderer())
    }

    // ExtraBold / Bold180 属于次级内部 GUI 字体（HarmonyOS_Sans_SC_Medium），同样走懒加载委托，
    // 但不注册进 FONT_PROVIDERS（不可被 FontValue 字体循环选中）
    private fun extraBold35(): GameFontRenderer =
        getFontOrDefault("HarmonyOS_Sans_SC_Medium.ttf", 35).asGameFontRenderer()
    private fun extraBold40(): GameFontRenderer =
        getFontOrDefault("HarmonyOS_Sans_SC_Medium.ttf", 40).asGameFontRenderer()

    // session.ttf（SessIcon）：图标字体。不进注册表，按需懒加载。
    val fontSessIcon14: GameFontRenderer by lazy { sessIcon(14) }
    val fontSessIcon20: GameFontRenderer by lazy { sessIcon(20) }
    val fontSessIcon24: GameFontRenderer by lazy { sessIcon(24) }
    val fontSessIcon48: GameFontRenderer by lazy { sessIcon(48) }
    val fontSessIcon60: GameFontRenderer by lazy { sessIcon(60) } // Hanabi 主菜单按钮图标 (sessionInfoIcon30 ×2)

    private fun sessIcon(size: Int): GameFontRenderer =
        getFontOrDefault("SessIcon.ttf", size).asGameFontRenderer()

    // Flux 通知字体（移植自 today.flux FontManager；Icon.ttf 为图标字体，字形 A=info B=warning C=error D=success）。
    // Flux/Yarukon 字体管线渲染尺寸 = 标称 x0.5，与 Hanabi 同理，此处取 x2 标称尺寸还原原始大小。
    // 不进注册表，供 Notifications "Flux" 样式内部使用。
    val fontFluxIcon: GameFontRenderer by lazy { fluxFont("Icon.ttf", 60) }
    val fontFluxTitle: GameFontRenderer by lazy { fluxFont("PoppinsSemiBold.ttf", 40) }
    val fontFluxDesc: GameFontRenderer by lazy { fluxFont("PoppinsRegular.ttf", 32) }

    // Flux 主菜单字体（移植自 today.flux FontManager 主菜单映射：roboto15/robotoL15/L40/L18/sans18_2/icon20/icon30）。
    val fontFluxRoboto by lazy { fluxFont("Roboto.ttf", 30) }        // 信息栏 (15×2)
    val fontFluxRobotoL by lazy { fluxFont("RobotoLight.ttf", 30) }  // Welcome 文字 (15×2)
    val fontFluxRobotoL40 by lazy { fluxFont("RobotoLight.ttf", 80) } // 启动动画大字 (40×2)
    val fontFluxRobotoL18 by lazy { fluxFont("RobotoLight.ttf", 36) } // 启动动画副字 (18×2)
    val fontFluxSans by lazy { fluxFont("sans.ttf", 36) }            // 主按钮文字 (18×2)
    val fontFluxIcon20 by lazy { fluxFont("Icon.ttf", 40) }          // 圆形小按钮图标 (20×2)

    // Flux TabGUI 字体（移植自 today.flux FontManager.normal2 = ArialBold 16，x2 还原）
    val fontFluxTabGui: GameFontRenderer by lazy { fluxFont("ArialBold.ttf", 32) }

    private fun fluxFont(file: String, size: Int): GameFontRenderer =
        getFontOrDefault(file, size).asGameFontRenderer()

    // ===== Setsuna 字体（移植自 SetsunaClient）=====
    // Setsuna 的 Skija 绘制像素 ≈ 本客户端标称尺寸 x 0.25，因此移植代码传入源端像素值，
    // 内部按 x4 标称尺寸加载还原原始大小（例：源端 10px → 标称 40）。
    // pf_normal.ttf：UI 中文字体；music.ttf：音乐符号；icon.ttf / icomoon.ttf：图标。
    private val SETSUNA_FONT_CACHE = java.util.concurrent.ConcurrentHashMap<String, GameFontRenderer>()

    fun fontSetsuna(file: String, size: Int): GameFontRenderer =
        SETSUNA_FONT_CACHE.getOrPut("$file#$size") {
            getFontOrDefault("Setsuna_$file", size * 4).asGameFontRenderer()
        }

    @JvmStatic
    val font24: GameFontRenderer by lazy { fontRegular30 }
    @JvmStatic
    val font30: GameFontRenderer by lazy { fontRegular30 }
    @JvmStatic
    val font35: GameFontRenderer by lazy { fontRegular35 }
    @JvmStatic
    val font40: GameFontRenderer by lazy { fontRegular40 }
    @JvmStatic
    val font52: GameFontRenderer by lazy { fontRegular45 }
    @JvmStatic
    val fontSF35: GameFontRenderer by lazy { fontSemibold35 }
    @JvmStatic
    val fontSF40: GameFontRenderer by lazy { fontSemibold40 }
    @JvmStatic
    val font72: GameFontRenderer by lazy { fontBold180 }
    @JvmStatic
    val font32: GameFontRenderer by lazy { fontRegular35 }

    private fun <T : FontRenderer> register(fontInfo: FontInfo, fontRenderer: T): T {
        FONT_PROVIDERS[fontInfo] = { fontRenderer }
        CREATED[fontRenderer] = fontInfo
        return fontRenderer
    }

    private fun registerLazy(fontInfo: FontInfo, provider: () -> GameFontRenderer) {
        FONT_PROVIDERS.getOrPut(fontInfo) { provider }
    }

    fun registerCustomAWTFont(customFontInfo: CustomFontInfo, save: Boolean = true): GameFontRenderer? {
        val font = getFontFromFileOrNull(customFontInfo.fontFile, customFontInfo.fontSize)
            ?: getFontFromResource(customFontInfo.fontFile, customFontInfo.fontSize)
            ?: getFallbackFont(customFontInfo.fontSize)
            ?: return null

        val result = register(
            FontInfo(customFontInfo.name, customFontInfo.fontSize, isCustom = true),
            font.asGameFontRenderer()
        )

        if (save) {
            customFontInfoList += customFontInfo
        }

        return result
    }

    fun loadFonts() {
        LOGGER.info("Start to load fonts.")
        val time = measureTimeMillis {
            register(minecraftFontInfo, minecraftFont)

            // 启动关键路径字体（HUD / 主菜单 / 核心 GUI）：立即烘焙
            fontRegular30 = register(
                FontInfo(name = "HarmonyOS Sans SC Regular", size = 30),
                getFontOrDefault("HarmonyOS_Sans_SC_Regular.ttf", 30).asGameFontRenderer()
            )

            fontSemibold35 = register(
                FontInfo(name = "HarmonyOS Sans SC Medium", size = 35),
                getFontOrDefault("HarmonyOS_Sans_SC_Medium.ttf", 35).asGameFontRenderer()
            )

            fontRegular35 = register(
                FontInfo(name = "HarmonyOS Sans SC Regular", size = 35),
                getFontOrDefault("HarmonyOS_Sans_SC_Regular.ttf", 35).asGameFontRenderer()
            )

            fontRegular40 = register(
                FontInfo(name = "HarmonyOS Sans SC Regular", size = 40),
                getFontOrDefault("HarmonyOS_Sans_SC_Regular.ttf", 40).asGameFontRenderer()
            )

            fontSemibold40 = register(
                FontInfo(name = "HarmonyOS Sans SC Medium", size = 40),
                getFontOrDefault("HarmonyOS_Sans_SC_Medium.ttf", 40).asGameFontRenderer()
            )

            // 其余字体：只注册 provider（保持原有注册表顺序，供 FontValue 字体循环遍历），
            // 首次实际使用时才解析 TTF + 烘焙字形
            registerLazy(FontInfo(name = "HarmonyOS Sans SC Regular", size = 45)) {
                getFontOrDefault("HarmonyOS_Sans_SC_Regular.ttf", 45).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Neuton Bold", size = 35)) {
                getFontOrDefault("Neuton-Bold.ttf", 35).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Neuton Bold", size = 40)) {
                getFontOrDefault("Neuton-Bold.ttf", 40).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Neuton Bold", size = 50)) {
                getFontOrDefault("Neuton-Bold.ttf", 50).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Neuton ExtraLight", size = 35)) {
                getFontOrDefault("Neuton-ExtraLight.ttf", 35).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Neuton ExtraLight", size = 40)) {
                getFontOrDefault("Neuton-ExtraLight.ttf", 40).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Neuton Italic", size = 35)) {
                getFontOrDefault("Neuton-Italic.ttf", 35).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Neuton Italic", size = 40)) {
                getFontOrDefault("Neuton-Italic.ttf", 40).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Neuton Light", size = 35)) {
                getFontOrDefault("Neuton-Light.ttf", 35).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Neuton Light", size = 40)) {
                getFontOrDefault("Neuton-Light.ttf", 40).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Nosifer", size = 35)) {
                getFontOrDefault("Nosifer-Regular.ttf", 35).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Nosifer", size = 40)) {
                getFontOrDefault("Nosifer-Regular.ttf", 40).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Nosifer", size = 50)) {
                getFontOrDefault("Nosifer-Regular.ttf", 50).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Zen Dots", size = 35)) {
                getFontOrDefault("ZenDots-Regular.ttf", 35).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Zen Dots", size = 40)) {
                getFontOrDefault("ZenDots-Regular.ttf", 40).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Zen Dots", size = 50)) {
                getFontOrDefault("ZenDots-Regular.ttf", 50).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "SF Apple", size = 35)) {
                getFontOrDefault("SFApple.ttf", 35).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "SF Apple", size = 40)) {
                getFontOrDefault("SFApple.ttf", 40).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "SF Apple", size = 50)) {
                getFontOrDefault("SFApple.ttf", 50).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Rise SF UI Pro", size = 35)) {
                getFontOrDefault("SF-UI-Pro.ttf", 35).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Rise SF UI Pro", size = 40)) {
                getFontOrDefault("SF-UI-Pro.ttf", 40).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Rise SF UI Pro", size = 50)) {
                getFontOrDefault("SF-UI-Pro.ttf", 50).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Usans", size = 15)) {
                getFontOrDefault("usans.otf", 15).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Usans", size = 16)) {
                getFontOrDefault("usans.otf", 16).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Usans", size = 20)) {
                getFontOrDefault("usans.otf", 20).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Usans", size = 32)) {
                getFontOrDefault("usans.otf", 32).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Usans", size = 40)) {
                getFontOrDefault("usans.otf", 40).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Usans", size = 50)) {
                getFontOrDefault("usans.otf", 50).asGameFontRenderer()
            }

            loadCustomFonts()
        }
        LOGGER.info("Loaded ${FONT_PROVIDERS.size} fonts (eager: ${CREATED.size}) in ${time}ms")
    }

    private fun loadCustomFonts() {
        FONT_PROVIDERS.keys.removeIf { it.isCustom }
        CREATED.values.removeIf { it.isCustom }

        customFontInfoList.forEach {
            registerCustomAWTFont(it, save = false)
        }
    }

    fun getFontRenderer(name: String, size: Int): FontRenderer {
        return FONT_PROVIDERS.entries.firstOrNull { (fontInfo, _) ->
            fontInfo.size == size && fontInfo.name.equals(name, true)
        }?.value?.invoke() ?: minecraftFont
    }

    fun getFontDetails(fontRenderer: FontRenderer): FontInfo? {
        return CREATED[fontRenderer]
    }

    val fonts: List<FontRenderer>
        get() = FONT_PROVIDERS.values.map { it() }

    val customFonts: Map<FontInfo, FontRenderer>
        get() = CREATED.entries.filter { it.value.isCustom }.associate { it.value to it.key }

    fun removeCustomFont(fontInfo: FontInfo): CustomFontInfo? {
        if (!fontInfo.isCustom) {
            return null
        }

        FONT_PROVIDERS.remove(fontInfo)
        CREATED.entries.removeIf { it.value == fontInfo }
        return customFontInfoList.firstOrNull {
            it.name == fontInfo.name && it.fontSize == fontInfo.size
        }?.also {
            customFontInfoList -= it
        }
    }

    private fun getFontFromFileOrNull(file: String, size: Int): Font? = try {
        File(fontsDir, file).inputStream().use { inputStream ->
            Font.createFont(Font.TRUETYPE_FONT, inputStream).deriveFont(Font.PLAIN, size.toFloat())
        }
    } catch (e: Exception) {
        LOGGER.warn("Exception during loading font[name=${file}, size=${size}]", e)
        null
    }

    // 内置字体的解析缓存：同一个 TTF 只解析一次，不同字号走 deriveFont 派生
    private val resourceFontCache = HashMap<String, Font?>()

    private fun parsedResourceFont(file: String): Font? = synchronized(resourceFontCache) {
        resourceFontCache.getOrPut(file) { loadResourceFont(file) }
    }

    private fun loadResourceFont(file: String): Font? {
        return try {
            val resourcePath = "/assets/minecraft/airplus/fonts/$file"
            val inputStream = javaClass.getResourceAsStream(resourcePath)
            if (inputStream == null) {
                LOGGER.warn("Font resource not found: $resourcePath")
                return null
            }
            inputStream.use { stream ->
                val font = Font.createFont(Font.TRUETYPE_FONT, stream)
                LOGGER.debug("Loaded font from resource: $file")
                font
            }
        } catch (e: Exception) {
            LOGGER.error("Exception during loading font from resource[name=$file]", e)
            null
        }
    }

    private fun getFontFromResource(file: String, size: Int): Font? =
        parsedResourceFont(file)?.deriveFont(Font.PLAIN, size.toFloat())

    private fun getFallbackFont(size: Int): Font {
        val fallbackFonts = listOf(
            "Microsoft YaHei",
            "SimHei",
            "SimSun",
            "PingFang SC",
            "Noto Sans CJK SC",
            "WenQuanYi Micro Hei",
            "SansSerif"
        )

        for (fallbackName in fallbackFonts) {
            try {
                val fallbackFont = Font(fallbackName, Font.PLAIN, size)
                if (fallbackFont.canDisplay('\u4e2d') || fallbackFont.canDisplay('\u6587')) {
                    LOGGER.info("Using fallback font: $fallbackName (size=$size)")
                    return fallbackFont
                }
            } catch (e: Exception) {
                LOGGER.warn("Failed to create fallback font: $fallbackName", e)
            }
        }

        LOGGER.warn("No suitable fallback font found, using default SansSerif")
        return Font("SansSerif", Font.PLAIN, size)
    }

    private fun getFontOrDefault(file: String, size: Int): Font {
        return getFontFromResource(file, size) ?: getFallbackFont(size)
    }

    private fun Font.asGameFontRenderer(): GameFontRenderer {
        return GameFontRenderer(this@asGameFontRenderer)
    }

}
