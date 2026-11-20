/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 * skid edmoonlight
 */
package net.airplus.ui.client.hud.element.elements

import net.airplus.AirPlus.moduleManager
import net.airplus.config.Configurable
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.modules.misc.GameDetector
import net.airplus.ui.client.hud.designer.GuiHudDesigner
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.Element
import net.airplus.ui.client.hud.element.ElementInfo
import net.airplus.ui.client.hud.element.Side
import net.airplus.ui.client.hud.element.Side.Horizontal
import net.airplus.ui.client.hud.element.Side.Vertical
import net.airplus.ui.font.AWTFontRenderer.Companion.assumeNonVolatile
import net.airplus.ui.font.Fonts
import net.airplus.ui.glass.Anim
import net.airplus.ui.glass.Draw
import net.airplus.ui.glass.GlState
import net.airplus.ui.glass.SmoothFont
import net.airplus.utils.client.ClientThemesUtils
import net.airplus.utils.extensions.safeDiv
import net.airplus.utils.render.*
import net.airplus.utils.render.ColorUtils.fade
import net.airplus.utils.render.ColorUtils.withAlpha
import net.airplus.utils.render.RenderUtils.deltaTime
import net.airplus.utils.render.RenderUtils.drawImage
import net.airplus.utils.render.RenderUtils.drawRect
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.airplus.utils.render.RenderUtils.drawRoundedRectAA
import net.airplus.utils.render.shader.shaders.GradientFontShader
import net.airplus.utils.render.shader.shaders.GradientShader
import net.airplus.utils.render.shader.shaders.RainbowFontShader
import net.airplus.utils.render.shader.shaders.RainbowShader
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.GlStateManager.resetColor
import java.awt.Color
import kotlin.math.exp
import kotlin.math.pow

/**
 * CustomHUD Arraylist element
 *
 * Shows a list of enabled modules
 */
@ElementInfo(name = "Arraylist", single = true)
class Arraylist(
    x: Double = 0.0, y: Double = 0.0, scale: Float = 1F,
    side: Side = Side(Horizontal.RIGHT, Vertical.UP),
) : Element("Arraylist", x, y, scale, side) {

    private val style by choices("Style", arrayOf("LB", "Grouped", "Flow", "HotKey", "BM"), "LB")

    // Grouped / Flow / HotKey 卡片样式（移植自 MoonLight）的专属设置
    private val cardAlpha by float("Background-Alpha", 0.6F, 0F..1F) { style != "LB" }
    private val cardRadius by float("Corner-Radius", 6F, 0F..8F) { style != "LB" }
    private val cardBlur by boolean("Card-Blur", false) { style != "LB" }
    private val hotkeyHeader by boolean("HotKeys-Header", true) { style == "HotKey" }

    // BM 样式（移植自 BetterMyau 模块列表）的专属设置：行染色浓度（对应 BetterMyau "background"，默认 25%）
    private val bmBackground by float("BM-Background", 25F, 0F..100F) { style == "BM" }

    private val textColorMode by choices(
        "Text-Mode", arrayOf("Custom", "Fade", "Random", "Rainbow", "Gradient", "Theme"), "theme"
    ) { style == "LB" }
    private val textColors = ColorSettingsInteger(this, "TextColor") { style == "LB" && textColorMode == "Custom" }.with(blueRibbon)
    private val textFadeColors = ColorSettingsInteger(this, "Text-Fade") { style == "LB" && textColorMode == "Fade" }.with(0, 111, 255)

    private val textFadeDistance by int("Text-Fade-Distance", 50, 0..100) { style == "LB" && textColorMode == "Fade" }

    private val gradientTextSpeed by float("Text-Gradient-Speed", 1f, 0.5f..10f) { style == "LB" && textColorMode == "Gradient" }

    private val maxTextGradientColors by int(
        "Max-Text-Gradient-Colors", 4, 1..MAX_GRADIENT_COLORS
    ) { style == "LB" && textColorMode == "Gradient" }
    private val textGradColors =
        ColorSettingsFloat.create(this, "Text-Gradient") { style == "LB" && textColorMode == "Gradient" && it <= maxTextGradientColors }

    private val rectMode by choices("Rect-Mode", arrayOf("None", "Left", "Right", "Outline"), "Right") { style == "LB" }
    private val roundedRectRadius by float("RoundedRect-Radius", 0F, 0F..2F) { style == "LB" && rectMode !in setOf("None", "Outline") }
    private val rectColorMode by choices(
        "Rect-ColorMode", arrayOf("Custom", "Fade", "Random", "Rainbow", "Gradient", "Theme"), "theme"
    ) { style == "LB" && rectMode != "None" }
    private val rectColors =
        ColorSettingsInteger(this, "RectColor", applyMax = true) { style == "LB" && isCustomRectSupported }.with(blueRibbon)
    private val rectFadeColors = ColorSettingsInteger(this, "Rect-Fade", applyMax = true) { style == "LB" && rectColorMode == "Fade" }

    private val rectFadeDistance by int("Rect-Fade-Distance", 50, 0..100) { style == "LB" && rectColorMode == "Fade" }

    private val gradientRectSpeed by float("Rect-Gradient-Speed", 1f, 0.5f..10f) { style == "LB" && isCustomRectGradientSupported }

    private val maxRectGradientColors by int(
        "Max-Rect-Gradient-Colors", 4, 1..MAX_GRADIENT_COLORS
    ) { style == "LB" && isCustomRectGradientSupported }
    private val rectGradColors = ColorSettingsFloat.create(
        this, "Rect-Gradient"
    ) { style == "LB" && isCustomRectGradientSupported && it <= maxRectGradientColors }

    private val roundedBackgroundRadius by float("RoundedBackGround-Radius", 1F, 0F..5F) { style == "LB" && bgColors.color().alpha > 0 }

    private val backgroundMode by choices(
        "Background-Mode", arrayOf("Custom", "Fade", "Random", "Rainbow", "Gradient", "Theme"), "Custom"
    ) { style == "LB" }
    private val bgColors =
        ColorSettingsInteger(this, "BackgroundColor") { style == "LB" && backgroundMode == "Custom" }.with(Color.BLACK.withAlpha(150))
    private val bgFadeColors = ColorSettingsInteger(this, "Background-Fade") { style == "LB" && backgroundMode == "Fade" }

    private val bgFadeDistance by int("Background-Fade-Distance", 50, 0..100) { style == "LB" && backgroundMode == "Fade" }

    private val gradientBackgroundSpeed by float(
        "Background-Gradient-Speed", 1f, 0.5f..10f
    ) { style == "LB" && backgroundMode == "Gradient" }

    private val maxBackgroundGradientColors by int(
        "Max-Background-Gradient-Colors", 4, 1..MAX_GRADIENT_COLORS
    ) { style == "LB" && backgroundMode == "Gradient" }
    private val bgGradColors = ColorSettingsFloat.create(
        this, "Background-Gradient"
    ) { style == "LB" && backgroundMode == "Gradient" && it <= maxBackgroundGradientColors }

    // Icons
    private val displayIcons by boolean("DisplayIcons", true) { style == "LB" }
    private val iconShadows by boolean("IconShadows", true) { style == "LB" && displayIcons }
    private val xDistance by float("ShadowXDistance", 0F, -2F..2F) { style == "LB" && iconShadows }
    private val yDistance by float("ShadowYDistance", 0F, -2F..2F) { style == "LB" && iconShadows }
    private val shadowColor by color("ShadowColor", Color.BLACK.withAlpha(128), rainbow = true) { style == "LB" && iconShadows }

    private val iconColorMode by choices(
        "IconColorMode", arrayOf("Custom", "Fade"), "Custom"
    ) { style == "LB" && displayIcons }
    private val iconColor by color("IconColor", Color.WHITE) { style == "LB" && iconColorMode == "Custom" && displayIcons }
    private val iconFadeColor by color("IconFadeColor", Color.WHITE) { style == "LB" && iconColorMode == "Fade" && displayIcons }
    private val iconFadeDistance by int("IconFadeDistance", 50, 0..100) { style == "LB" && iconColorMode == "Fade" && displayIcons }

    private fun isColorModeUsed(value: String) =
        value in listOf(textColorMode, rectMode, backgroundMode, iconColorMode) && style == "LB"

    private val saturation by float("Random-Saturation", 0.9f, 0f..1f) { isColorModeUsed("Random") }
    private val brightness by float("Random-Brightness", 1f, 0f..1f) { isColorModeUsed("Random") }
    private val rainbowX by float("Rainbow-X", -1000F, -2000F..2000F) { isColorModeUsed("Rainbow") }
    private val rainbowY by float("Rainbow-Y", -1000F, -2000F..2000F) { isColorModeUsed("Rainbow") }
    private val gradientX by float("Gradient-X", -1000F, -2000F..2000F) { isColorModeUsed("Gradient") }
    private val gradientY by float("Gradient-Y", -1000F, -2000F..2000F) { isColorModeUsed("Gradient") }

    private val tags by boolean("Tags", true)
    private val tagsStyle by choices("TagsStyle", arrayOf("[]", "()", "<>", "-", "|", "Space"), "Space") {
        tags
    }.onChanged { updateTagDetails() }
    private val tagsCase by choices("TagsCase", arrayOf("Normal", "Uppercase", "Lowercase"), "Normal") { tags }
    private val tagsArrayColor by boolean("TagsArrayColor", false) {
        tags
    }.onChanged { updateTagDetails() }

    private val font by font("Font", Fonts.fontSemibold35)
    private val textShadow by boolean("ShadowText", true)
    private val moduleCase by choices("ModuleCase", arrayOf("Normal", "Uppercase", "Lowercase"), "Normal")
    private val space by float("Space", 1F, 0F..5F)
    private val textHeight by float("TextHeight", 11F, 1F..20F)
    private val textY by float("TextY", 3.25F, 0F..20F)

    private val hideRender by boolean("HideRender", true)

    private val animation by choices(
        "Animation", arrayOf("Slide", "Back"), "Slide"
    ) { style == "LB" && tags }
    private val animationSpeed by float("AnimationSpeed", 0.2F, 0.01F..1F) { style == "LB" && animation == "Back" }

    companion object : Configurable("StandaloneArraylist") {
        val spacedModulesValue = boolean("SpacedModules", false)
    }

    private val spacedModules: Boolean by +spacedModulesValue

    private val inactiveStyle by choices(
        "InactiveModulesStyle", arrayOf("Normal", "Color", "Hide"), "Color"
    ) { GameDetector.state }

    private var x2 = 0
    private var y2 = 0F

    private lateinit var tagPrefix: String

    private lateinit var tagSuffix: String

    private var modules = emptyList<Module>()

    private val inactiveColor = Color(255, 255, 255, 100).rgb

    private val isCustomRectSupported
        get() = rectMode != "None" && rectColorMode == "Custom"

    private val isCustomRectGradientSupported
        get() = rectMode != "None" && rectColorMode == "Gradient"

    init {
        updateTagDetails()

        // 将 Style 选择移动到设置列表最顶部（参考 Target2 的做法）
        val styleValue = values.first { it.name.equals("Style", ignoreCase = true) }
        val existingValues = ArrayList(values)
        get().clear()
        addValue(styleValue)
        existingValues.forEach { if (it !== styleValue) addValue(it) }
    }

    fun updateTagDetails() {
        val pair: Pair<String, String> = when (tagsStyle) {
            "[]", "()", "<>" -> tagsStyle[0].toString() to tagsStyle[1].toString()
            "-", "|" -> tagsStyle[0] + " " to ""
            else -> "" to ""
        }

        tagPrefix = (if (tagsArrayColor) " " else " §7") + pair.first
        tagSuffix = pair.second
    }

    private fun getDisplayString(module: Module): String {
        val moduleName = when (moduleCase) {
            "Uppercase" -> module.getName().uppercase()
            "Lowercase" -> module.getName().lowercase()
            else -> module.getName()
        }

        var tag = module.tag ?: ""

        tag = when (tagsCase) {
            "Uppercase" -> tag.uppercase()
            "Lowercase" -> tag.lowercase()
            else -> tag
        }

        val moduleTag = if (tags && !module.tag.isNullOrEmpty()) tagPrefix + tag + tagSuffix else ""

        return moduleName + moduleTag
    }

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158F
        val c3 = c1 + 1F
        return 1F + c3 * (t - 1F).pow(3) + c1 * (t - 1F).pow(2)
    }

    override fun drawElement(): Border? = when (style) {
        "Grouped" -> drawGrouped()
        "Flow" -> drawFlow()
        "HotKey" -> drawHotKey()
        "BM" -> drawBM()
        else -> drawLB()
    }

    // ===== LB 样式（原有 LiquidBounce 风格 Arraylist，逻辑零改动） =====

    private fun drawLB(): Border? {
        assumeNonVolatile {
            // Slide animation - update every render
            val delta = deltaTime

            val padding = if (displayIcons) 15 else 0

            for (module in moduleManager) {
                // HideRender: 开启后将 RENDER 分类的模块从列表中完全隐藏（对齐 AirClient 行为）
                val shouldShow =
                    (!module.isHidden && module.state && (inactiveStyle != "Hide" || module.isActive) && !(hideRender && module.category == Category.RENDER))

                if (!shouldShow && module.slide <= 0f) continue

                val displayString = getDisplayString(module)

                val width = font.getStringWidth(displayString) + padding

                when (animation) {
                    "Slide" -> {
                        // If modules become inactive because they only work when in game, animate them as if they got disabled
                        module.slideStep += if (shouldShow) delta / 4F else -delta / 4F
                        if (shouldShow) {
                            if (module.slide < width) {
                                module.slide = AnimationUtils.easeOut(module.slideStep, width.toFloat()) * width
                            }
                        } else {
                            module.slide = AnimationUtils.easeOut(module.slideStep, width.toFloat()) * width
                        }

                        module.slide = module.slide.coerceIn(0F, width.toFloat())
                        module.slideStep = module.slideStep.coerceIn(0F, width.toFloat())
                    }

                    "Back" -> {
                        module.slideStep += if (shouldShow) delta / 4F else -delta / 4F
                        val t = (module.slideStep / width).coerceIn(0F, 1F)
                        val eased = easeOutBack(t)
                        // Back 会有超出目标宽度的回弹，不向上截断
                        module.slide = (eased * width).coerceAtLeast(0F)
                    }
                }
            }
            // Draw arraylist
            val textCustomColor = textColors.color().rgb
            val rectCustomColor = rectColors.color().rgb
            val backgroundCustomColor = bgColors.color().rgb
            val textSpacer = textHeight + space

            val rainbowOffset = System.currentTimeMillis() % 10000 / 10000F
            val rainbowX = 1f safeDiv rainbowX
            val rainbowY = 1f safeDiv rainbowY

            val gradientOffset = System.currentTimeMillis() % 10000 / 10000F
            val gradientX = 1f safeDiv gradientX
            val gradientY = 1f safeDiv gradientY

            // ===== 逐行背景（每行的背景长度随自身文本宽度自适应）=====
            // 模糊仍按所有行并集的 AABB 拍一次，但遮罩逐行画圆角矩形，真正绘制时也逐行
            // 画各自的背景矩形：短模块的背景不再被最长行拉宽。局部坐标经 renderX/renderY
            // 与元素 scale 换算成屏幕绝对坐标后交给 HudBlur，遮罩自动跟随列表动画。
            val panelCorners = when (side.horizontal) {
                Horizontal.LEFT -> RenderUtils.RoundedCorners.RIGHT_ONLY
                Horizontal.MIDDLE -> RenderUtils.RoundedCorners.ALL
                else -> RenderUtils.RoundedCorners.LEFT_ONLY
            }
            if (modules.isNotEmpty()) {
                // 每行的背景矩形（局部坐标 x1/y1/x2/y2），几何与原整块逻辑一致但按行独立
                val rowRects = modules.mapIndexed { index, module ->
                    val yPos = (if (side.vertical == Vertical.DOWN) -textSpacer else textSpacer) *
                        (if (side.vertical == Vertical.DOWN) index + 1 else index)
                    val rect = FloatArray(4)
                    // 行矩形取精确的 [yPos, yPos + textSpacer]：相邻行恰好共边不重叠。
                    // 若外扩 ±1px 会造成相邻行背景在 2px 交叠带内被半透明颜色双重混合，
                    // 开启 blur 后背景变平滑，交叠带会显出一条更深的黑线。
                    rect[1] = yPos
                    rect[3] = yPos + textSpacer
                    when (side.horizontal) {
                        Horizontal.LEFT -> {
                            val width = font.getStringWidth(getDisplayString(module))
                            val xPos = -(width - module.slide) + if (rectMode == "Left") 6 else 3
                            rect[0] = (if (rectMode == "Left") 1f else 0f) - 3f
                            rect[2] = xPos + width + (if (rectMode == "Right") 4f else 1f) + 3f
                        }
                        else -> {
                            val xPos = -module.slide - if (displayIcons) 2 else 3
                            rect[0] = xPos - ((if (rectMode == "Right") 5f else 2f) + 3f)
                            rect[2] = (if (rectMode == "Right") -3f else -1f) + 3f
                        }
                    }
                    rect
                }

                var minX = Float.MAX_VALUE
                var minY = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE
                var maxY = -Float.MAX_VALUE
                rowRects.forEach { rect ->
                    minX = minOf(minX, rect[0]); minY = minOf(minY, rect[1])
                    maxX = maxOf(maxX, rect[2]); maxY = maxOf(maxY, rect[3])
                }

                HudBlur.blur(
                    (scale * (minX + renderX)).toFloat(), (scale * (minY + renderY)).toFloat(),
                    (scale * (maxX + renderX)).toFloat(), (scale * (maxY + renderY)).toFloat(),
                    roundedBackgroundRadius, panelCorners
                ) {
                    rowRects.forEach {
                        drawRoundedRect(it[0], it[1], it[2], it[3], -1, roundedBackgroundRadius, RenderUtils.RoundedCorners.ALL)
                    }
                }

                GradientShader.begin(
                    backgroundMode == "Gradient",
                    gradientX,
                    gradientY,
                    bgGradColors.toColorArray(maxBackgroundGradientColors),
                    gradientBackgroundSpeed,
                    gradientOffset
                ).use {
                    RainbowShader.begin(backgroundMode == "Rainbow", rainbowX, rainbowY, rainbowOffset).use {
                        modules.forEachIndexed { index, module ->
                            val rect = rowRects[index]
                            drawRoundedRect(
                                rect[0], rect[1], rect[2], rect[3],
                                when (backgroundMode) {
                                    "Gradient" -> 0
                                    "Rainbow" -> 0
                                    "Random" -> Color.getHSBColor(module.hue, saturation, brightness).rgb
                                    "Fade" -> fade(bgFadeColors, index * bgFadeDistance, 100).rgb
                                    "Theme" -> ClientThemesUtils.getColorWithAlpha(index, bgColors.color().alpha).rgb
                                    else -> backgroundCustomColor
                                },
                                roundedBackgroundRadius,
                                RenderUtils.RoundedCorners.ALL
                            )
                        }
                    }
                }
            }

            modules.forEachIndexed { index, module ->
                val yPos =
                    (if (side.vertical == Vertical.DOWN) -textSpacer else textSpacer) * if (side.vertical == Vertical.DOWN) index + 1 else index
                val moduleColor = Color.getHSBColor(module.hue, saturation, brightness).rgb

                val textFadeColor = fade(textFadeColors, index * textFadeDistance, 100).rgb
                val rectFadeColor = fade(rectFadeColors, index * rectFadeDistance, 100).rgb
                val iconFadeColor = fade(iconFadeColor, index * iconFadeDistance, 100).rgb

                val markAsInactive =
                    inactiveStyle == "Color" && !module.isActive

                val displayString = getDisplayString(module)
                val displayStringWidth = font.getStringWidth(displayString)

                val previousDisplayString = getDisplayString(modules[(if (index > 0) index else 1) - 1])
                val previousDisplayStringWidth = font.getStringWidth(previousDisplayString)

                when (side.horizontal) {
                    Horizontal.RIGHT, Horizontal.MIDDLE -> {
                        val xPos = -module.slide - if (displayIcons) 2 else 3

                        GradientFontShader.begin(
                            !markAsInactive && textColorMode == "Gradient",
                            gradientX,
                            gradientY,
                            textGradColors.toColorArray(maxTextGradientColors),
                            gradientTextSpeed,
                            gradientOffset
                        ).use {
                            RainbowFontShader.begin(
                                !markAsInactive && textColorMode == "Rainbow", rainbowX, rainbowY, rainbowOffset
                            ).use {
                                font.drawString(
                                    displayString,
                                    xPos + 1 - if (rectMode == "Right") 3 else 0,
                                    yPos + textY,
                                    if (markAsInactive) inactiveColor
                                    else when (textColorMode) {
                                        "Gradient" -> 0
                                        "Rainbow" -> 0
                                        "Random" -> moduleColor
                                        "Fade" -> textFadeColor
                                        "Theme" -> ClientThemesUtils.getColor(index).rgb
                                        else -> textCustomColor
                                    },
                                    textShadow,
                                )
                            }
                        }

                        GradientShader.begin(
                            !markAsInactive && isCustomRectGradientSupported,
                            gradientX,
                            gradientY,
                            rectGradColors.toColorArray(maxRectGradientColors),
                            gradientRectSpeed,
                            gradientOffset
                        ).use {
                            if (rectMode != "None") {
                                RainbowShader.begin(
                                    !markAsInactive && rectColorMode == "Rainbow", rainbowX, rainbowY, rainbowOffset
                                ).use {
                                    val rectColor = if (markAsInactive) inactiveColor
                                    else when (rectColorMode) {
                                        "Gradient" -> 0
                                        "Rainbow" -> 0
                                        "Random" -> moduleColor
                                        "Fade" -> rectFadeColor
                                        "Theme" -> ClientThemesUtils.getColor(index).rgb
                                        else -> rectCustomColor
                                    }

                                    when (rectMode) {
                                        "Left" -> drawRoundedRect(
                                            xPos - 5,
                                            yPos,
                                            xPos - 2,
                                            yPos + textSpacer,
                                            rectColor,
                                            roundedRectRadius,
                                            RenderUtils.RoundedCorners.LEFT_ONLY
                                        )

                                        "Right" -> drawRoundedRect(
                                            -3F,
                                            yPos,
                                            0F,
                                            yPos + textSpacer,
                                            rectColor,
                                            roundedRectRadius,
                                            if (modules.lastIndex == 0) {
                                                RenderUtils.RoundedCorners.RIGHT_ONLY
                                            } else when (module) {
                                                modules.first() -> RenderUtils.RoundedCorners.TOP_RIGHT_ONLY
                                                modules.last() -> RenderUtils.RoundedCorners.BOTTOM_RIGHT_ONLY
                                                else -> RenderUtils.RoundedCorners.NONE
                                            }
                                        )

                                        "Outline" -> {
                                            drawRect(-1F, yPos - 1F, 0F, yPos + textSpacer, rectColor)
                                            drawRect(xPos - 3, yPos, xPos - 2, yPos + textSpacer, rectColor)

                                            if (module == modules.first()) {
                                                drawRect(xPos - 3, yPos - 1F, 0F, yPos, rectColor)
                                            }

                                            drawRect(
                                                xPos - 3 - (previousDisplayStringWidth - displayStringWidth),
                                                yPos,
                                                xPos - 2,
                                                yPos + 1,
                                                rectColor
                                            )

                                            if (module == modules.last()) {
                                                drawRect(
                                                    xPos - 3, yPos + textSpacer, 0F, yPos + textSpacer + 1, rectColor
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Horizontal.LEFT -> {
                        val width = font.getStringWidth(displayString)
                        val xPos = -(width - module.slide) + if (rectMode == "Left") 6 else 3

                        GradientFontShader.begin(
                            !markAsInactive && textColorMode == "Gradient",
                            gradientX,
                            gradientY,
                            textGradColors.toColorArray(maxTextGradientColors),
                            gradientTextSpeed,
                            gradientOffset
                        ).use {
                            RainbowFontShader.begin(
                                !markAsInactive && textColorMode == "Rainbow", rainbowX, rainbowY, rainbowOffset
                            ).use {
                                font.drawString(
                                    displayString, xPos - 1, yPos + textY, if (markAsInactive) inactiveColor
                                    else when (textColorMode) {
                                        "Gradient" -> 0
                                        "Rainbow" -> 0
                                        "Random" -> moduleColor
                                        "Fade" -> textFadeColor
                                        "Theme" -> ClientThemesUtils.getColor(index).rgb
                                        else -> textCustomColor
                                    }, textShadow
                                )
                            }
                        }

                        GradientShader.begin(
                            !markAsInactive && isCustomRectGradientSupported,
                            gradientX,
                            gradientY,
                            rectGradColors.toColorArray(maxRectGradientColors),
                            gradientRectSpeed,
                            gradientOffset
                        ).use {
                            RainbowShader.begin(
                                !markAsInactive && rectColorMode == "Rainbow", rainbowX, rainbowY, rainbowOffset
                            ).use {
                                if (rectMode != "None") {
                                    val rectColor = if (markAsInactive) inactiveColor
                                    else when (rectColorMode) {
                                        "Gradient" -> 0
                                        "Rainbow" -> 0
                                        "Random" -> moduleColor
                                        "Fade" -> rectFadeColor
                                        "Theme" -> ClientThemesUtils.getColor(index).rgb
                                        else -> rectCustomColor
                                    }

                                    when (rectMode) {
                                        "Left" -> drawRoundedRect(
                                            0F,
                                            yPos,
                                            3F,
                                            yPos + textSpacer,
                                            rectColor,
                                            roundedRectRadius,
                                            if (modules.lastIndex == 0) {
                                                RenderUtils.RoundedCorners.LEFT_ONLY
                                            } else when (module) {
                                                modules.first() -> RenderUtils.RoundedCorners.TOP_LEFT_ONLY
                                                modules.last() -> RenderUtils.RoundedCorners.BOTTOM_LEFT_ONLY
                                                else -> RenderUtils.RoundedCorners.NONE
                                            }
                                        )

                                        "Right" -> drawRoundedRect(
                                            xPos + width + 2,
                                            yPos,
                                            xPos + width + 2 + 3,
                                            yPos + textSpacer,
                                            rectColor,
                                            roundedRectRadius,
                                            RenderUtils.RoundedCorners.RIGHT_ONLY
                                        )

                                        "Outline" -> {
                                            drawRect(-1F, yPos - 1F, 0F, yPos + textSpacer, rectColor)
                                            drawRect(
                                                xPos + width + 1,
                                                yPos - 1F,
                                                xPos + width + 2,
                                                yPos + textSpacer,
                                                rectColor
                                            )

                                            if (module == modules.first()) {
                                                drawRect(xPos + width + 2, yPos - 1, xPos + width + 2, yPos, rectColor)
                                                drawRect(-1F, yPos - 1, xPos + width + 2, yPos, rectColor)
                                            }

                                            drawRect(
                                                xPos + width + 1,
                                                yPos - 1,
                                                xPos + width + 2 + (previousDisplayStringWidth - displayStringWidth),
                                                yPos,
                                                rectColor
                                            )

                                            if (module == modules.last()) {
                                                drawRect(
                                                    xPos + width + 1,
                                                    yPos + textSpacer,
                                                    xPos + width + 2,
                                                    yPos + textSpacer + 1,
                                                    rectColor
                                                )
                                                drawRect(
                                                    -1F,
                                                    yPos + textSpacer,
                                                    xPos + width + 2,
                                                    yPos + textSpacer + 1,
                                                    rectColor
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (displayIcons) {
                    val width = font.getStringWidth(displayString)

                    val side = if (side.horizontal == Side.Horizontal.LEFT) {
                        (-width + module.slide) / 6 + if (rectMode == "Left") 3 else 0
                    } else {
                        -module.slide - 2 + width + if (rectMode == "Right") 0 else 2
                    }

                    val resource = module.category.iconResourceLocation

                    if (iconShadows) {
                        drawImage(resource, side + xDistance, yPos + yDistance, 12, 12, shadowColor)
                    }

                    val iconColor = if (markAsInactive) {
                        inactiveColor
                    } else when (iconColorMode) {
                        "Gradient" -> 0
                        "Rainbow" -> 0
                        "Fade" -> iconFadeColor
                        else -> this.iconColor.rgb
                    }

                    drawImage(resource, side, yPos, 12, 12, Color(iconColor, true))
                }
            }

            // Draw border
            if (mc.currentScreen is GuiHudDesigner) {
                x2 = Int.MIN_VALUE

                if (modules.isEmpty()) {
                    return if (side.horizontal == Horizontal.LEFT) Border(0F, -1F, 20F, 20F)
                    else Border(0F, -1F, -20F, 20F)
                }

                for (module in modules) {
                    when (side.horizontal) {
                        Horizontal.RIGHT, Horizontal.MIDDLE -> {
                            val xPos = -module.slide.toInt() - 2
                            if (x2 == Int.MIN_VALUE || xPos < x2) x2 = xPos
                        }

                        Horizontal.LEFT -> {
                            val xPos = module.slide.toInt() + 16
                            if (x2 == Int.MIN_VALUE || xPos > x2) x2 = xPos
                        }
                    }
                }

                y2 = (if (side.vertical == Vertical.DOWN) -textSpacer else textSpacer) * modules.size

                return Border(0F, 0F, x2 - 7F, y2 - if (side.vertical == Vertical.DOWN) 1F else 0F)
            }
        }

        resetColor()
        return null
    }

    // ===== Grouped / Flow / HotKey 卡片样式（移植自 MoonLight ArrayListCards / HotKeyWidget） =====

    private class CardItem {
        lateinit var module: Module
        var p = 0F
        var on = false
        var tag = ""
        var nameW = 0F
        var w = 0F

        // Flow chip 摆放状态（虚拟右锚定坐标）
        var x = 0F
        var y = 0F
        var cw = 0F
        var placed = false
    }

    private val cardItems = HashMap<Module, CardItem>()
    private val groupWidths = HashMap<Category, Float>()
    private var cardsLastFrame = 0L

    private val isLeftSide get() = side.horizontal == Horizontal.LEFT
    private val isDownSide get() = side.vertical == Vertical.DOWN

    // 虚拟坐标帧：x=0 为锚定边，内容向负 x 延伸（同 MoonLight 的右锚定布局），
    // y 向下增长；LEFT / DOWN 侧在绘制原语处镜像。
    private fun vmx(v: Float) = if (isLeftSide) -v else v

    private fun vy(v: Float) = if (isDownSide) -v else v

    private fun vtx(origin: Float, width: Float) = if (isLeftSide) -(origin + width) else origin

    private fun cardDisplayName(module: Module): String = when (moduleCase) {
        "Uppercase" -> module.getName().uppercase()
        "Lowercase" -> module.getName().lowercase()
        else -> module.getName()
    }

    private fun stripFormatting(text: String): String {
        if (!text.contains('§')) return text
        return buildString {
            var i = 0
            while (i < text.length) {
                if (text[i] == '§' && i + 1 < text.length) {
                    i += 2
                } else {
                    append(text[i])
                    i++
                }
            }
        }
    }

    private fun cardTagText(module: Module): String {
        if (!tags) return ""
        return module.tag?.let(::stripFormatting)?.trim() ?: ""
    }

    private fun measureCardItem(item: CardItem, module: Module) {
        val tag = cardTagText(module)
        item.tag = tag
        item.nameW = font.getStringWidth(cardDisplayName(module)).toFloat()
        item.w = item.nameW + (if (tag.isEmpty()) 0F else 3.5F + font.getStringWidth(tag))
    }

    private fun isCardVisible(module: Module): Boolean =
        module.state && !module.isHidden &&
            (inactiveStyle != "Hide" || module.isActive) &&
            !(hideRender && module.category == Category.RENDER)

    /**
     * 推进卡片淡入淡出动画（0.24s 淡入 / 0.18s 淡出，指数平滑），
     * 返回本帧的插值系数，供位置 approach 使用。
     */
    private fun advanceCards(): Float {
        val now = System.nanoTime()
        val first = cardsLastFrame == 0L
        val dt = if (first) 0F else ((now - cardsLastFrame) / 1.0E9F).coerceAtMost(0.1F)
        cardsLastFrame = now

        for (module in moduleManager) {
            val visible = isCardVisible(module)
            var item = cardItems[module]
            if (item == null) {
                if (!visible) continue
                item = CardItem()
                item.module = module
                item.p = if (first) 1F else 0F
                cardItems[module] = item
            }
            item.on = visible
            item.p = if (visible) {
                (item.p + dt / 0.24F).coerceAtMost(1F)
            } else {
                (item.p - dt / 0.18F).coerceAtLeast(0F)
            }
            measureCardItem(item, module)
        }
        cardItems.values.removeAll { !it.on && it.p <= 0F }

        return 1F - exp(-14F * dt)
    }

    private fun smooth(value: Float): Float {
        val v = value.coerceIn(0F, 1F)
        return v * v * (3F - 2F * v)
    }

    private fun approach(current: Float, target: Float, factor: Float): Float {
        val next = current + (target - current) * factor
        return if (kotlin.math.abs(target - next) < 0.05F) target else next
    }

    private fun cardTextY(centerY: Float): Float =
        vy(centerY) - font.FONT_HEIGHT / 2F + 1.75F

    /** 半透明墨色玻璃卡底（INK 色，透明度 = 设置值 × 淡入进度）。 */
    private fun glass(x: Float, y: Float, w: Float, h: Float, radius: Float, alpha: Float) {
        if (alpha < 0.03F || w <= 0.5F || h <= 0.5F) return
        val a = (cardAlpha * 255F * alpha).toInt().coerceIn(0, 255)
        drawRoundedRectAA(
            vmx(x), vy(y), vmx(x + w), vy(y + h),
            Color(13, 15, 30, a).rgb,
            radius,
            RenderUtils.RoundedCorners.ALL
        )
    }

    /**
     * 卡片模糊（Card-Blur 可开关）：虚拟帧坐标换算成屏幕绝对坐标后交给 HudBlur，
     * 遮罩沿用调用方矩阵（HUD scale + 平移）镜像绘制，自动跟随卡片动画。
     */
    private fun glassBlur(
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        radius: Float,
        alpha: Float,
        mask: (() -> Unit)? = null
    ) {
        if (!cardBlur || alpha < 0.03F || w <= 0.5F || h <= 0.5F) return
        val x1 = minOf(vmx(x), vmx(x + w))
        val x2 = maxOf(vmx(x), vmx(x + w))
        val y1 = minOf(vy(y), vy(y + h))
        val y2 = maxOf(vy(y), vy(y + h))
        HudBlur.blur(
            (scale * (x1 + renderX)).toFloat(), (scale * (y1 + renderY)).toFloat(),
            (scale * (x2 + renderX)).toFloat(), (scale * (y2 + renderY)).toFloat(),
            radius = radius
        ) {
            // 自定义遮罩（如 HotKey 行的文本框 + 图标徽章两块，中间空隙不模糊），
            // 缺省遮罩为整块圆角矩形
            if (mask != null) mask()
            else drawRoundedRect(x1, y1, x2, y2, -1, radius, RenderUtils.RoundedCorners.ALL)
        }
    }

    /** 主题色圆点（用小圆角矩形拟合圆盘）。 */
    private fun drawCardDisc(cx: Float, cy: Float, radius: Float, color: Color, alpha: Float) {
        if (alpha <= 0.05F) return
        drawRoundedRect(
            vmx(cx - radius), vy(cy - radius), vmx(cx + radius), vy(cy + radius),
            Color(color.red, color.green, color.blue, (color.alpha * alpha).toInt().coerceIn(0, 255)).rgb,
            radius,
            RenderUtils.RoundedCorners.ALL
        )
    }

    /** 模块名（白）+ 后缀（暗淡），按侧向对齐；origin 为虚拟帧内文本块起点。 */
    private fun drawCardItem(item: CardItem, origin: Float, centerY: Float, alpha: Float, dimInactive: Boolean) {
        if (alpha <= 0.08F) return
        val module = item.module
        val textY = cardTextY(centerY)
        val markAsInactive = dimInactive && inactiveStyle == "Color" && !module.isActive

        val nameColor = if (markAsInactive) inactiveColor
        else Color(255, 255, 255, (255F * alpha).toInt().coerceIn(0, 255)).rgb
        font.drawString(cardDisplayName(module), vtx(origin, item.nameW), textY, nameColor, textShadow)

        if (item.tag.isNotEmpty()) {
            val tagW = font.getStringWidth(item.tag).toFloat()
            val tagColor = if (markAsInactive) inactiveColor
            else Color(255, 255, 255, (120F * alpha).toInt().coerceIn(0, 255)).rgb
            font.drawString(item.tag, vtx(origin + item.nameW + 3.5F, tagW), textY, tagColor, textShadow)
        }
    }

    /** 字距加宽的大写标题（移植 MoonLight drawCaps / capsWidth）。 */
    private fun capsWidth(text: String): Float {
        var w = 0F
        for (c in text) {
            w += font.getStringWidth(c.toString()) + 1.1F
        }
        return (w - 1.1F).coerceAtLeast(0F)
    }

    private fun drawCaps(text: String, x: Float, y: Float, color: Int) {
        var cx = x
        for (c in text) {
            font.drawString(c.toString(), cx, y, color, textShadow)
            cx += font.getStringWidth(c.toString()) + 1.1F
        }
    }

    /** 卡片样式的 Border（供 HUD 编辑器框选），随锚定侧向镜像。 */
    private fun cardBorder(spanW: Float, spanH: Float): Border {
        val x1 = if (isLeftSide) 0F else -spanW
        val x2 = if (isLeftSide) spanW else 0F
        val y1 = if (isDownSide) -spanH else 0F
        val y2 = if (isDownSide) 0F else spanH
        return Border(x1, y1, x2, y2)
    }

    private fun emptyCardBorder(): Border =
        if (isLeftSide) Border(0F, -1F, 20F, 20F) else Border(0F, -1F, -20F, 20F)

    /** Grouped：按模块 Category 分块成玻璃卡，带分组标题 / 计数 / 圆点。 */
    private fun drawGrouped(): Border? {
        var result: Border? = null
        assumeNonVolatile {
            val factor = advanceCards()

            val pad = 6F
            val headerTop = 11F
            val headerBottom = 3F
            val rowH = textHeight + space
            val groupGap = 3F

            var maxSpanW = 0F
            var yCursor = 0F

            for (category in Category.entries) {
                val items = cardItems.values.filter { it.module.category == category && it.p > 0F }.toMutableList()
                if (items.isEmpty()) {
                    groupWidths.remove(category)
                    continue
                }

                items.sortByDescending { it.w }

                var sum = 0F
                var contentW = 0F
                var onCount = 0
                for (item in items) {
                    sum += smooth(item.p)
                    contentW = maxOf(contentW, item.w)
                    if (item.on) onCount++
                }

                val categoryName = category.displayName.uppercase()
                val countText = onCount.toString()
                val headerW = capsWidth(categoryName) + 4F + font.getStringWidth(countText) + 6.7F
                val targetW = maxOf(headerW, contentW) + pad * 2F

                val prevW = groupWidths[category]
                val groupW = if (prevW == null) targetW else approach(prevW, targetW, factor)
                groupWidths[category] = groupW
                maxSpanW = maxOf(maxSpanW, groupW)

                val groupAlpha = sum.coerceAtMost(1F)
                val cardH = (headerTop + headerBottom) * groupAlpha + rowH * sum

                glassBlur(-groupW, yCursor, groupW, cardH, minOf(cardRadius, cardH / 2F), groupAlpha)
                glass(-groupW, yCursor, groupW, cardH, minOf(cardRadius, cardH / 2F), groupAlpha)

                // 分组标题：计数 + 大写标题 + 主题色圆点（淡入过半后出现）
                val headerAlpha = (groupAlpha - 0.5F) * 2F
                if (headerAlpha > 0F) {
                    val th = font.FONT_HEIGHT.toFloat()
                    val dotColor = ClientThemesUtils.getColor(category.ordinal)
                    val headerCy = yCursor + headerTop / 2F
                    val headerTextY = cardTextY(headerCy)

                    var rightV = -pad
                    val countW = font.getStringWidth(countText).toFloat()
                    if (countW > 0F) {
                        font.drawString(
                            countText,
                            vtx(rightV - countW, countW),
                            headerTextY,
                            Color(255, 255, 255, (110F * headerAlpha).toInt().coerceIn(0, 255)).rgb,
                            textShadow
                        )
                        rightV -= countW + 4F
                    }

                    val titleW = capsWidth(categoryName)
                    if (titleW > 0F) {
                        val origin = rightV - titleW
                        drawCaps(
                            categoryName,
                            vtx(origin, titleW),
                            headerTextY,
                            Color(255, 255, 255, (125F * headerAlpha).toInt().coerceIn(0, 255)).rgb
                        )
                        drawCardDisc(origin - 5.1F, headerCy, 1.6F, dotColor, headerAlpha)
                    }
                }

                // 组内条目：按宽度降序，右对齐排布，行高随淡入进度折叠
                var itemY = yCursor + headerTop * groupAlpha
                for (item in items) {
                    val ap = smooth(item.p)
                    val itemH = rowH * ap
                    if (ap > 0.08F) {
                        val centerY = itemY + itemH / 2F
                        drawCardItem(item, -6F - item.w, centerY, ap, dimInactive = true)
                    }
                    itemY += itemH
                }

                yCursor += cardH + groupGap * groupAlpha
            }

            resetColor()

            result = when {
                yCursor > 0F -> cardBorder(maxSpanW, yCursor)
                mc.currentScreen is GuiHudDesigner -> emptyCardBorder()
                else -> null
            }
        }
        return result
    }

    /** Flow：按类别排序后的 chip 横向排布，超出 Flow 宽度自动换行。 */
    private fun drawFlow(): Border? {
        var result: Border? = null
        assumeNonVolatile {
            val factor = advanceCards()

            val chipPad = 5F
            val chipGap = 3F
            val flowWidth = 150F
            val rowH = textHeight + space
            val dotW = 6.7F

            val items = cardItems.values.filter { it.on && it.p > 0F }.toMutableList()
            items.sortWith(compareBy({ it.module.category.ordinal }, { it.module.getName().lowercase() }))

            var cursorX = 0F
            var cursorY = 0F
            var maxSpanW = 0F

            for (item in items) {
                val chipW = chipPad * 2F + dotW + item.w
                if (cursorX < 0F && -cursorX + chipW > flowWidth) {
                    cursorX = 0F
                    cursorY += rowH + chipGap
                }

                cursorX -= chipW
                item.cw = chipW
                if (!item.placed) {
                    item.x = cursorX
                    item.y = cursorY
                    item.placed = true
                } else {
                    item.x = approach(item.x, cursorX, factor)
                    item.y = approach(item.y, cursorY, factor)
                }

                maxSpanW = maxOf(maxSpanW, -item.x)
                cursorX -= chipGap
            }

            for (item in cardItems.values) {
                if (!item.placed || item.p <= 0F) continue
                val ap = smooth(item.p)
                glassBlur(item.x, item.y, item.cw, rowH, minOf(cardRadius, rowH / 2F), ap)
                glass(item.x, item.y, item.cw, rowH, minOf(cardRadius, rowH / 2F), ap)
                if (ap > 0.1F) {
                    val centerY = item.y + rowH / 2F
                    val contentOrigin = item.x + chipPad
                    drawCardDisc(contentOrigin + 1.6F, centerY, 1.6F, ClientThemesUtils.getColor(item.module.category.ordinal), ap)
                    drawCardItem(item, contentOrigin + dotW, centerY, ap, dimInactive = true)
                }
            }

            val spanH = if (items.isEmpty()) 0F else cursorY + rowH

            resetColor()

            result = when {
                spanH > 0F -> cardBorder(maxSpanW, spanH)
                mc.currentScreen is GuiHudDesigner -> emptyCardBorder()
                else -> null
            }
        }
        return result
    }

    /**
     * HotKey：1:1 对齐 MoonLight HotKeyWidget 的模式——
     * 可选 "HotKeys" 标题栏 + 全部开启模块的圆角行（13px 行高，模块名 + 后缀 + 13px
     * 分类图标徽章贴锚定边，行围绕内侧锚点缩放入场），按宽度降序排列。
     */
    private fun drawHotKey(): Border? {
        var result: Border? = null
        assumeNonVolatile {
            advanceCards()

            val rowH = 13F
            val badgeW = 13F
            val badgeGap = 4F
            val textPad = 3F

            val items = cardItems.values.filter { it.on && it.p > 0F }
                .sortedByDescending { it.w }

            var yCursor = 0F
            var spanH = 0F
            var maxSpanW = 0F

            // HotKeys 标题栏（moonlight hotkeysDisplay）
            if (hotkeyHeader && items.isNotEmpty()) {
                val headerW = 88F
                val headerY = -(rowH + 6F)
                spanH += rowH + 6F
                maxSpanW = maxOf(maxSpanW, headerW)

                glassBlur(-headerW, headerY, headerW, rowH, minOf(cardRadius, rowH / 2F), 1F)
                glass(-headerW, headerY, headerW, rowH, minOf(cardRadius, rowH / 2F), 1F)

                val th = font.FONT_HEIGHT.toFloat()
                val headerTextW = font.getStringWidth("HotKeys").toFloat()
                drawCardDisc(-headerW + 7F, headerY + rowH / 2F, 1.6F, ClientThemesUtils.getColor(0), 1F)
                font.drawString(
                    "HotKeys",
                    vtx(-headerW + 13F, headerTextW),
                    vy(headerY + (rowH - th) / 2F + 0.5F),
                    Color.WHITE.rgb,
                    textShadow
                )
            }

            for (item in items) {
                val ap = smooth(item.p)
                if (ap <= 0.05F) continue

                val boxW = item.w + textPad * 2F
                val rowW = badgeW + badgeGap + boxW
                maxSpanW = maxOf(maxSpanW, rowW)

                val rowY = yCursor
                yCursor += rowH * ap
                spanH = rowY + rowH * ap

                val centerY = rowY + rowH / 2F
                val textY = cardTextY(centerY)
                val radius = minOf(cardRadius, rowH / 2F)

                // 行模糊（未缩放矩阵下按屏幕绝对坐标取景）：遮罩只覆盖文本框与图标
                // 徽章两块底色，中间空隙不参与模糊（空隙处没有底色，不该有 blur）
                glassBlur(-rowW, rowY, rowW, rowH, radius, ap) {
                    drawRoundedRect(
                        vmx(-rowW), vy(rowY), vmx(-badgeW - badgeGap), vy(rowY + rowH),
                        -1, radius, RenderUtils.RoundedCorners.ALL
                    )
                    drawRoundedRect(
                        vmx(-badgeW), vy(rowY), vmx(0F), vy(rowY + rowH),
                        -1, radius, RenderUtils.RoundedCorners.ALL
                    )
                }

                // 行淡入时围绕内侧锚点（文本框靠徽章一侧）缩放（对齐 MoonLight）
                GlStateManager.pushMatrix()
                if (ap < 1F) {
                    val cx = vmx(-badgeW - badgeGap)
                    val cy = vy(centerY)
                    GlStateManager.translate(cx, cy, 0F)
                    GlStateManager.scale(ap, ap, 1F)
                    GlStateManager.translate(-cx, -cy, 0F)
                }

                // 行底 + 图标徽章（moonlight Dark 底色）
                val rowColor = Color(21, 21, 21, (cardAlpha * 255F * ap).toInt().coerceIn(0, 255)).rgb
                drawRoundedRectAA(
                    vmx(-rowW), vy(rowY), vmx(-badgeW - badgeGap), vy(rowY + rowH),
                    rowColor, radius, RenderUtils.RoundedCorners.ALL
                )
                drawRoundedRectAA(
                    vmx(-badgeW), vy(rowY), vmx(0F), vy(rowY + rowH),
                    rowColor, radius, RenderUtils.RoundedCorners.ALL
                )

                // 分类图标徽章（取镜像后的最小角，保证 LEFT/DOWN 侧不越界）
                drawImage(
                    item.module.category.iconResourceLocation,
                    minOf(vmx(-badgeW + 1F), vmx(-1F)),
                    minOf(vy(rowY + 1F), vy(rowY + rowH - 1F)),
                    11, 11,
                    Color(255, 255, 255, (255F * ap).toInt().coerceIn(0, 255))
                )

                // 模块名 + 后缀（主题色，右对齐于文本框）
                val markAsInactive = inactiveStyle == "Color" && !item.module.isActive
                val textColor = if (markAsInactive) inactiveColor
                else ClientThemesUtils.getColorWithAlpha(0, (255F * ap).toInt().coerceIn(0, 255)).rgb
                val nameOrigin = -(badgeW + badgeGap + textPad) - item.w
                font.drawString(cardDisplayName(item.module), vtx(nameOrigin, item.nameW), textY, textColor, textShadow)
                if (item.tag.isNotEmpty()) {
                    val tagW = font.getStringWidth(item.tag).toFloat()
                    val tagColor = if (markAsInactive) inactiveColor
                    else Color(255, 255, 255, (120F * ap).toInt().coerceIn(0, 255)).rgb
                    font.drawString(item.tag, vtx(nameOrigin + item.nameW + 3.5F, tagW), textY, tagColor, textShadow)
                }

                GlStateManager.popMatrix()
            }

            resetColor()

            result = when {
                spanH > 0F -> cardBorder(maxSpanW, spanH)
                mc.currentScreen is GuiHudDesigner -> emptyCardBorder()
                else -> null
            }
        }
        return result
    }

    // ===== BM 样式（移植自 BetterMyau 模块列表渲染） =====

    /** 每行滑入/滑出动画（BetterMyau Anim.Smooth(0, 14)：模块开启行展开，关闭行收起后移除） */
    private class BmRow(val module: Module) {
        val show = Anim.Smooth(0F, 14F)
        var name = ""
        var tag = ""
        var width = 0F
    }

    private val bmRows = LinkedHashMap<Module, BmRow>()

    /**
     * BetterMyau 模块列表：逐行圆角玻璃行 —— Draw.blurred 行背景模糊 + 0x0a0c10 染色 +
     * 贴锚定边的 2px accent 发光条（BetterMyau showBar）+ SmoothFont.regular(9) 文本
     * （模块名白 / 后缀 0xffaab0bc）。行按宽度降序排序（长行贴锚定边），外伸侧圆角
     * 首行 5、其余随与次行的宽度差收窄（step ≤ 5）；行高 12、内边距 4，全部照搬源实现。
     */
    private fun drawBM(): Border? {
        var result: Border? = null
        assumeNonVolatile {
            // BetterMyau HUD 同款玻璃 2D 状态；Draw 单位刻度 = guiScale × 元素缩放（像素/单位），
            // 与元素自身的 scale/平移矩阵对齐 —— 抗锯齿宽度与 SmoothFont 光栅化精度随之正确
            GlState.hudDefaults()
            Draw.setScale(Draw.guiScaleFactor() * scale)

            val bmFont = SmoothFont.regular(9f)
            val listDt = Anim.dt()

            // 每行动画推进：可见 → 展开，隐藏 → 收起（收起完成后移除，对齐 BetterMyau listShow）
            for (module in moduleManager) {
                if (isCardVisible(module) && !bmRows.containsKey(module)) {
                    bmRows[module] = BmRow(module)
                }
            }
            val shown = ArrayList<BmRow>(bmRows.size)
            val rowIterator = bmRows.entries.iterator()
            while (rowIterator.hasNext()) {
                val row = rowIterator.next().value
                val visible = isCardVisible(row.module)
                row.show.update(visible, listDt)
                if (!visible && row.show.value < 0.01F) {
                    rowIterator.remove()
                    continue
                }
                // 名称 / 后缀 / 行宽每帧算一次，排序与绘制共用（BetterMyau rowNames/rowWidths）
                row.name = cardDisplayName(row.module)
                row.tag = cardTagText(row.module)
                var width = bmFont.width(row.name)
                if (row.tag.isNotEmpty()) width += 3F + bmFont.width(row.tag)
                row.width = width
                shown.add(row)
            }
            // BetterMyau：行宽降序
            shown.sortByDescending { it.width }

            val rowH = 12F
            val pad = 4F
            val right = side.horizontal != Horizontal.LEFT
            val bottom = side.vertical == Vertical.DOWN
            val gap = 4F // BetterMyau showBar（accent 条）的锚定边让位
            val tint = Draw.alpha(0x0A0C10, bmBackground / 100F)

            var cursor = 0F
            var spanH = 0F
            var maxSpanW = 0F

            for ((index, row) in shown.withIndex()) {
                val show = row.show.value
                val rowW = row.width + pad * 2F
                val rowH2 = rowH * show
                if (rowH2 <= 0F) continue

                // 行从锚定边外滑入（BetterMyau slide = ±(1 - show) * 0.35 * rowW）
                val slide = (if (right) 1F else -1F) * (1F - show) * 0.35F * rowW
                val rowX = (if (right) -gap - rowW else gap) + slide
                val rowY = if (bottom) cursor - rowH2 else cursor
                cursor += if (bottom) -rowH2 else rowH2
                spanH = if (bottom) -cursor else cursor
                maxSpanW = maxOf(maxSpanW, rowW + gap)

                // 行圆角：贴锚定边两角为 0，外伸侧首行 5、其余随与次行的宽度差收窄（BetterMyau 原样）
                val nextW = if (index + 1 < shown.size) shown[index + 1].width + pad * 2F else 0F
                val step = minOf(5F, maxOf(0F, rowW - nextW))
                val first = if (index == 0) 5F else 0F
                var tl = 0F
                var tr = 0F
                var br = 0F
                var bl = 0F
                if (right) {
                    if (bottom) {
                        tl = step; bl = first
                    } else {
                        bl = step; tl = first
                    }
                } else {
                    if (bottom) {
                        tr = step; br = first
                    } else {
                        br = step; tr = first
                    }
                }

                Draw.pushAlpha(show)
                try {
                    // 行背景：圆角玻璃（模糊 + 0x0a0c10 染色），直接走 Draw.blurred，
                    // 不依赖 Client.blurMode 全局设置（模糊不可用时 Draw 自行跳过）
                    Draw.blurred(rowX, rowY, rowW, rowH2, tl, tr, br, bl, 1F)
                    if (bmBackground > 0F) Draw.rect(rowX, rowY, rowW, rowH2, tl, tr, br, bl, tint)

                    // accent 边条：贴锚定边 2px 发光条（BetterMyau showBar），颜色取主题逐行轮换
                    val barX = if (right) -gap + 1F else gap - 3F
                    val accent = ClientThemesUtils.getColor(index).rgb
                    Draw.glow(barX, rowY, 2F, rowH2, 0F, 4F, Draw.alpha(accent, 0.45F))
                    Draw.rect(barX, rowY, 2F, rowH2, 0F, accent)

                    // 文本：模块名（白，复用 ModuleCase / InactiveModulesStyle / ShadowText）+
                    // 后缀（复用 Tags 开关，色 0xffaab0bc）
                    val textX = rowX + pad
                    val textY = bmFont.centerY(rowY, rowH2) - 0.5F
                    val markAsInactive = inactiveStyle == "Color" && !row.module.isActive
                    val nameColor = if (markAsInactive) inactiveColor else Color.WHITE.rgb
                    if (textShadow) bmFont.drawShadowed(row.name, textX, textY, nameColor)
                    else bmFont.draw(row.name, textX, textY, nameColor)
                    if (row.tag.isNotEmpty()) {
                        bmFont.draw(row.tag, textX + bmFont.width(row.name).toFloat() + 3F, textY, 0xffaab0bc.toInt())
                    }
                } finally {
                    Draw.popAlpha()
                }
            }

            Draw.flush()
            Draw.setScale(Draw.guiScaleFactor())
            resetColor()

            result = when {
                spanH > 0F -> cardBorder(maxSpanW, spanH)
                mc.currentScreen is GuiHudDesigner -> emptyCardBorder()
                else -> null
            }
        }
        return result
    }

    override fun updateElement() {
        modules = when (style) {
            "LB" -> moduleManager.filter {
                it.slide > 0 && !it.isHidden && !(hideRender && it.category == Category.RENDER)
            }.sortedBy { -font.getStringWidth(getDisplayString(it)) }

            else -> moduleManager.filter { isCardVisible(it) }
        }
    }
}
