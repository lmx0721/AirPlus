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

    // ===== 主字体：客户端主要 UI 字体（Client 模块可切换），Semibold/Regular/Bold180 全部跟随该文件 =====
    var mainFontFile: String = "HarmonyOS_Sans_SC_Regular.ttf"

    // ===== 全局字号：Client 模块 FontSize 控制，仅在烘焙核心 UI 文本字体时套用（百分比）=====
    @JvmStatic
    var globalFontSizeScale: Int = 100

    private fun styledSize(size: Int) = (size * globalFontSizeScale / 100).coerceAtLeast(1)

    /** 主字体文本渲染器统一烘焙入口：全局字号在此套用。 */
    private fun mainFontRenderer(size: Int): GameFontRenderer =
        getFontOrDefault(mainFontFile, styledSize(size)).asGameFontRenderer()

    // ===== 启动关键路径字体：HUD / 核心 GUI 需要，loadFonts 时立即烘焙 =====
    lateinit var fontSemibold35: GameFontRenderer
    lateinit var fontSemibold40: GameFontRenderer
    lateinit var fontRegular30: GameFontRenderer
    lateinit var fontRegular35: GameFontRenderer
    lateinit var fontRegular40: GameFontRenderer

    // ===== 其余字体：懒加载，首次访问（或 FontValue 字体循环遍历）时才创建 =====
    // ExtraBold 系列跟随主字体，用可失效缓存以便全局字号/粗细变化后重建
    private var _fontExtraBold35: GameFontRenderer? = null
    private var _fontExtraBold40: GameFontRenderer? = null
    val fontExtraBold35: GameFontRenderer
        get() = _fontExtraBold35 ?: extraBold35().also { _fontExtraBold35 = it }
    val fontExtraBold40: GameFontRenderer
        get() = _fontExtraBold40 ?: extraBold40().also { _fontExtraBold40 = it }

    // 可失效缓存：主字体切换/全局字号粗细变化时置空，下次访问按新设置重建
    private var _fontRegular45: GameFontRenderer? = null
    private var _fontBold180: GameFontRenderer? = null

    val fontRegular45: GameFontRenderer
        get() = _fontRegular45 ?: register(
            FontInfo(name = "HarmonyOS Sans SC Regular", size = 45),
            mainFontRenderer(45)
        ).also { _fontRegular45 = it }

    val fontBold180: GameFontRenderer
        get() = _fontBold180 ?: mainFontRenderer(180).also { _fontBold180 = it }

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

    // ExtraBold / Bold180 属于次级内部 GUI 字体，同样走懒加载委托，
    // 但不注册进 FONT_PROVIDERS（不可被 FontValue 字体循环选中）
    private fun extraBold35(): GameFontRenderer = mainFontRenderer(35)
    private fun extraBold40(): GameFontRenderer = mainFontRenderer(40)

    // session.ttf（SessIcon）：图标字体。不进注册表，按需懒加载。
    val fontSessIcon14: GameFontRenderer by lazy { sessIcon(14) }
    val fontSessIcon20: GameFontRenderer by lazy { sessIcon(20) }
    val fontSessIcon24: GameFontRenderer by lazy { sessIcon(24) }
    val fontSessIcon48: GameFontRenderer by lazy { sessIcon(48) }
    val fontSessIcon60: GameFontRenderer by lazy { sessIcon(60) } // Hanabi 主菜单按钮图标 (sessionInfoIcon30 ×2)

    private fun sessIcon(size: Int): GameFontRenderer =
        getFontOrDefault("SessIcon.ttf", size).asGameFontRenderer(styleable = false)

    // Flux 通知字体（移植自 today.flux FontManager；Icon.ttf 为图标字体，字形 A=info B=warning C=error D=success）。
    // Flux/Yarukon 字体管线渲染尺寸 = 标称 x0.5，与 Hanabi 同理，此处取 x2 标称尺寸还原原始大小。
    // 不进注册表，供 Notifications "Flux" 样式内部使用。
    val fontFluxIcon: GameFontRenderer by lazy { fluxFont("Icon.ttf", 60, styleable = false) }
    val fontFluxTitle: GameFontRenderer by lazy { fluxFont("PoppinsSemiBold.ttf", 40) }
    val fontFluxDesc: GameFontRenderer by lazy { fluxFont("PoppinsRegular.ttf", 32) }

    // Flux 主菜单字体（移植自 today.flux FontManager 主菜单映射：roboto15/robotoL15/L40/L18/sans18_2/icon20/icon30）。
    val fontFluxRoboto by lazy { fluxFont("Roboto.ttf", 30) }        // 信息栏 (15×2)
    val fontFluxRobotoL by lazy { fluxFont("RobotoLight.ttf", 30) }  // Welcome 文字 (15×2)
    val fontFluxRobotoL40 by lazy { fluxFont("RobotoLight.ttf", 80) } // 启动动画大字 (40×2)
    val fontFluxRobotoL18 by lazy { fluxFont("RobotoLight.ttf", 36) } // 启动动画副字 (18×2)
    val fontFluxSans by lazy { fluxFont("sans.ttf", 36) }            // 主按钮文字 (18×2)
    val fontFluxIcon20 by lazy { fluxFont("Icon.ttf", 40, styleable = false) }   // 圆形小按钮图标 (20×2)

    // Flux TabGUI 字体（移植自 today.flux FontManager.normal2 = ArialBold 16，x2 还原）
    val fontFluxTabGui: GameFontRenderer by lazy { fluxFont("ArialBold.ttf", 32) }

    private fun fluxFont(file: String, size: Int, styleable: Boolean = true): GameFontRenderer =
        getFontOrDefault(file, size).asGameFontRenderer(styleable)

    // ===== Setsuna 字体（移植自 SetsunaClient）=====
    // Setsuna 的 Skija 绘制像素 ≈ 本客户端标称尺寸 x 0.25，因此移植代码传入源端像素值，
    // 内部按 x4 标称尺寸加载还原原始大小（例：源端 10px → 标称 40）。
    // pf_normal.ttf：UI 中文字体；music.ttf：音乐符号；icon.ttf / icomoon.ttf：图标。
    private val SETSUNA_FONT_CACHE = java.util.concurrent.ConcurrentHashMap<String, GameFontRenderer>()

    fun fontSetsuna(file: String, size: Int): GameFontRenderer =
        SETSUNA_FONT_CACHE.getOrPut("$file#$size") {
            // pf_normal 为中文文本字体（可套用全局粗细），其余为音乐/图标字体（不套用）
            getFontOrDefault("Setsuna_$file", size * 4).asGameFontRenderer(styleable = file == "pf_normal.ttf")
        }

    // 别名必须走即时 getter：主字体切换后 Regular/Semibold 渲染器会被重建，
    // by lazy 会永久缓存旧对象导致别名不跟随新字体
    @JvmStatic
    val font24: GameFontRenderer get() = fontRegular30
    @JvmStatic
    val font30: GameFontRenderer get() = fontRegular30
    @JvmStatic
    val font35: GameFontRenderer get() = fontRegular35
    @JvmStatic
    val font40: GameFontRenderer get() = fontRegular40
    @JvmStatic
    val font52: GameFontRenderer get() = fontRegular45
    @JvmStatic
    val fontSF35: GameFontRenderer get() = fontSemibold35
    @JvmStatic
    val fontSF40: GameFontRenderer get() = fontSemibold40
    @JvmStatic
    val font72: GameFontRenderer get() = fontBold180
    @JvmStatic
    val font32: GameFontRenderer get() = fontRegular35

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
            bakeCoreFonts()

            // 其余字体：只注册 provider（保持原有注册表顺序，供 FontValue 字体循环遍历），
            // 首次实际使用时才解析 TTF + 烘焙字形
            registerLazy(FontInfo(name = "HarmonyOS Sans SC Regular", size = 45)) {
                mainFontRenderer(45)
            }
            // Setsuna 中文字体（Setsuna_pf_normal）注册进注册表，供主字体选择 / FontValue 循环遍历
            registerLazy(FontInfo(name = "Setsuna", size = 35)) {
                getFontOrDefault("Setsuna_pf_normal.ttf", 35).asGameFontRenderer()
            }
            registerLazy(FontInfo(name = "Setsuna", size = 40)) {
                getFontOrDefault("Setsuna_pf_normal.ttf", 40).asGameFontRenderer()
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

    /**
     * 按文件名加载字体（先 fontsDir 用户目录，再内置资源），失败返回 null。
     * 供 AWTFontRenderer 中文回退字体等外部机制使用。
     */
    internal fun loadFontFile(file: String, size: Int): Font? =
        getFontFromFileOrNull(file, size) ?: getFontFromResource(file, size)

    /** 注册表字体族名 -> 内置 TTF 文件（主字体切换用）。 */
    private val FAMILY_FILES = mapOf(
        "HarmonyOS Sans SC Regular" to "HarmonyOS_Sans_SC_Regular.ttf",
        "Setsuna" to "Setsuna_pf_normal.ttf",
        "Neuton Bold" to "Neuton-Bold.ttf",
        "Neuton ExtraLight" to "Neuton-ExtraLight.ttf",
        "Neuton Italic" to "Neuton-Italic.ttf",
        "Neuton Light" to "Neuton-Light.ttf",
        "Nosifer" to "Nosifer-Regular.ttf",
        "Zen Dots" to "ZenDots-Regular.ttf",
        "SF Apple" to "SFApple.ttf",
        "Rise SF UI Pro" to "SF-UI-Pro.ttf",
        "Usans" to "usans.otf"
    )

    /**
     * 主字体候选列表：自动取自字体注册表全部非图标字体族名
     * （排除 Minecraft 原版字体与 Semibold 别名族，Semibold 跟随主字体无需单选）。
     */
    fun mainFontCandidates(): Array<String> =
        FONT_PROVIDERS.keys.map { it.name }
            .distinct()
            .filter {
                !it.equals("Minecraft Font", true) &&
                        !it.equals("HarmonyOS Sans SC Medium", true)
            }
            .toTypedArray()

    /** 解析字体族名对应的 TTF 文件（内置映射优先，其次用户自定义字体）。 */
    private fun resolveFamilyFile(family: String): String? {
        FAMILY_FILES[family]?.let { return it }
        return customFontInfoList.find { it.name.equals(family, true) }?.fontFile
    }

    /**
     * 切换客户端主字体（Client 模块调用）。
     * Regular/Semibold/ExtraBold/Bold180 全部跟随新字体文件重新烘焙；
     * 若 loadFonts 尚未执行则仅记录文件，启动时自然生效。
     */
    fun applyMainFont(family: String) {
        val file = resolveFamilyFile(family) ?: return
        if (file == mainFontFile && ::fontRegular30.isInitialized) return
        mainFontFile = file

        if (!::fontRegular30.isInitialized) return

        bakeCoreFonts()
    }

    /**
     * 烘焙核心 UI 文本字体（跟随主字体文件 + 全局字号）。
     * Semibold 系列原为 HarmonyOS_Sans_SC_Medium，Medium 已删，跟随主字体文件，
     * 注册名保留 "HarmonyOS Sans SC Medium" 以兼容旧配置中的 FontValue 引用。
     */
    private fun bakeCoreFonts() {
        fontRegular30 = register(
            FontInfo(name = "HarmonyOS Sans SC Regular", size = 30),
            mainFontRenderer(30)
        )
        fontSemibold35 = register(
            FontInfo(name = "HarmonyOS Sans SC Medium", size = 35),
            mainFontRenderer(35)
        )
        fontRegular35 = register(
            FontInfo(name = "HarmonyOS Sans SC Regular", size = 35),
            mainFontRenderer(35)
        )
        fontRegular40 = register(
            FontInfo(name = "HarmonyOS Sans SC Regular", size = 40),
            mainFontRenderer(40)
        )
        fontSemibold40 = register(
            FontInfo(name = "HarmonyOS Sans SC Medium", size = 40),
            mainFontRenderer(40)
        )
        // 可失效懒缓存置空，下次访问按新字体重建
        _fontRegular45 = null
        _fontBold180 = null
        _fontExtraBold35 = null
        _fontExtraBold40 = null
    }

    /** 全局字号/粗细变化后重建核心文本字体（在渲染线程调用，见 AWTFontRenderer.onRender2D）。 */
    fun rebakeStyledFonts() {
        if (!::fontRegular30.isInitialized) return
        bakeCoreFonts()
    }

    private fun Font.asGameFontRenderer(styleable: Boolean = true): GameFontRenderer {
        return GameFontRenderer(this@asGameFontRenderer, styleable)
    }

}
