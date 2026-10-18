/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.ui.font

import net.airplus.features.module.modules.misc.NameProtect
import net.airplus.utils.client.MinecraftInstance.Companion.mc
import net.airplus.utils.render.ColorUtils.hexColors
import net.airplus.utils.render.ColorUtils.randomMagicText
import net.airplus.utils.render.RenderUtils.drawLine
import net.airplus.utils.render.shader.shaders.GradientFontShader
import net.airplus.utils.render.shader.shaders.RainbowFontShader
import net.minecraft.client.gui.FontRenderer
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.GlStateManager.*
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL20.glUseProgram
import java.awt.Color
import java.awt.Font

fun FontRenderer.drawCenteredString(
    text: String?, x: Float, y: Float, color: Int, shadow: Boolean
) {
    val drawX = x - getStringWidth(text) / 2f
    if (shadow) {
        drawStringWithShadow(text, drawX, y, color)
    } else {
        drawString(text, drawX.toInt(), y.toInt(), color)
    }
}

fun FontRenderer.drawCenteredString(
    text: String?, x: Float, y: Float, color: Int
) {
    val drawX = x - getStringWidth(text) / 2f
    drawString(text, drawX.toInt(), y.toInt(), color)
}

/**
 * Extends Minecraft's [FontRenderer] for potential fallback usage.
 *
 * @author opZywl
 */
class GameFontRenderer(
    font: Font
) : FontRenderer(
    mc.gameSettings, ResourceLocation("textures/font/ascii.png"), mc.textureManager, false
) {

    val defaultFont = AWTFontRenderer(font)

    // 样式变体懒烘焙：绝大多数文字不含 §l/§o 样式码，启动时烘焙 bold/italic/boldItalic
    // 是纯浪费（每个字体 4 次字形贴图烘焙 → 1 次）。
    private val boldFont: AWTFontRenderer by lazy { AWTFontRenderer(font.deriveFont(Font.BOLD)) }
    private val italicFont: AWTFontRenderer by lazy { AWTFontRenderer(font.deriveFont(Font.ITALIC)) }
    private val boldItalicFont: AWTFontRenderer by lazy { AWTFontRenderer(font.deriveFont(Font.BOLD or Font.ITALIC)) }

    val fontHeight: Int
    val size: Int
        get() = defaultFont.font.size

    /**
     * Because AWTFontRenderer might produce a somewhat larger pixel height,
     * we unify it by dividing by 2 to be consistent with vanilla layouts.
     */
    val height: Int
        get() = defaultFont.height / 2

    /** 视觉行高（em 行盒），用于任意盒子内的垂直居中：drawY = top + (boxH - visualLineHeight)/2 + 2.75f */
    val visualLineHeight: Float
        get() = defaultFont.visualLineHeight

    init {
        fontHeight = height
    }

    /**
     * Regular text draw (no shadow).
     */
    fun drawString(
        text: String?, x: Float, y: Float, color: Int
    ): Int = drawString(text, x, y, color, shadow = false)

    /**
     * A simple "double-draw" fade effect:
     * - Black behind at +0.7 offset
     * - Main text in [color]
     */
    fun drawStringFade(
        text: String?, x: Float, y: Float, color: Color
    ) {
        val blackWithAlpha = Color(0, 0, 0, color.alpha).rgb
        drawString(text, x + 0.7f, y + 0.7f, blackWithAlpha, shadow = false)
        drawString(text, x, y, color.rgb, shadow = false)
    }

    /**
     * Overrides vanilla's drawStringWithShadow for compatibility.
     */
    override fun drawStringWithShadow(
        text: String?, x: Float, y: Float, color: Int
    ): Int = drawString(text, x, y, color, shadow = true)

    fun drawCenteredString(
        text: String?, x: Float, y: Float, color: Int, shadow: Boolean
    ) {
        val drawX = x - getStringWidth(text) / 2f
        if (shadow) {
            drawStringWithShadow(text, drawX, y, color)
        } else {
            drawString(text, drawX, y, color, shadow = false)
        }
    }

    fun drawCenteredString(
        text: String?, x: Float, y: Float, color: Int
    ) {
        val drawX = x - getStringWidth(text) / 2f
        drawString(text, drawX, y, color)
    }

    /**
     * Draws text centered, but scaled by [givenScale].
     * Good for larger titles or smaller disclaimers.
     */
    fun drawCenteredTextScaled(
        text: String?, givenX: Int, givenY: Int, color: Int, givenScale: Double
    ) {
        if (text.isNullOrEmpty()) return

        glPushMatrix()
        glTranslated(givenX.toDouble(), givenY.toDouble(), 0.0)
        glScaled(givenScale, givenScale, givenScale)
        drawCenteredString(text, 0f, 0f, color, shadow = true)
        glPopMatrix()
    }

    /**
     * The main text-draw method. If [shadow] is true, we draw black behind
     * the text. Then we draw the real text with optional rainbow/gradient.
     */
    override fun drawString(
        text: String?, x: Float, y: Float, color: Int, shadow: Boolean
    ): Int {
        if (text == null) {
            return 0
        }

        // Basic blend（必须走 GlStateManager：裸调用绕过缓存，
        // 会让后续 enableBlend 被跳过 → 主文本无混合渲染，且状态残留影响后续绘制）
        GlStateManager.enableBlend()
        GlStateManager.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        // NameProtect modifies text (like hiding the player's name)
        val currentText = NameProtect.handleTextMessage(text)

        // Shift text slightly to align
        val baseY = y - 3f

        // If shadow => draw black behind
        if (shadow) {
            glUseProgram(0) // disable any shader
            drawText(
                currentText,
                x + 1f,
                baseY + 1f,
                Color(0, 0, 0, minOf(150, color shr 24 and 0xFF)).rgb,
                ignoreColor = true
            )
        }

        glUseProgram(0)
        GlStateManager.disableBlend()

        // Then real text with optional rainbow or gradient
        val rainbowActive = RainbowFontShader.isInUse
        val gradientActive = GradientFontShader.isInUse
        return drawText(
            currentText, x, baseY, color, ignoreColor = false, rainbow = rainbowActive, gradient = gradientActive
        )
    }

    /**
     * Actually draws the text with color codes, styles, etc.
     * If [ignoreColor], we skip color codes.
     * If [rainbow] or [gradient] is true, we apply the respective shader programs.
     */
    private fun drawText(
        text: String?,
        x: Float,
        y: Float,
        color: Int,
        ignoreColor: Boolean,
        rainbow: Boolean = false,
        gradient: Boolean = false
    ): Int {
        if (text.isNullOrEmpty()) return x.toInt()

        // Potentially enable rainbow or gradient shaders
        if (rainbow) glUseProgram(RainbowFontShader.programId)
        if (gradient) glUseProgram(GradientFontShader.programId)

        // Position & GL states
        glTranslated(x - 1.5, y + 0.5, 0.0)
        enableAlpha()
        enableBlend()
        tryBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO)
        enableTexture2D()

        var drawColor = if ((color and -0x4000000) == 0) (color or -16777216) else color
        val alpha = (drawColor ushr 24) and 0xFF

        // If text has color codes => parse them
        if ('§' in text) {
            val segments = text.split('§')
            var currFont: AWTFontRenderer = defaultFont
            var widthSoFar = 0.0
            var randomCase = false
            var bold = false
            var italic = false
            var strikeThrough = false
            var underline = false

            segments.forEachIndexed { index, segment ->
                if (segment.isEmpty()) return@forEachIndexed
                if (index == 0) {
                    // No color code => normal draw
                    currFont.drawString(segment, widthSoFar, 0.0, drawColor)
                    widthSoFar += currFont.getStringWidth(segment)
                } else {
                    val codeType = segment[0]
                    val remainder = segment.substring(1)
                    // Evaluate color or style
                    when (val colorIndex = getColorIndex(codeType)) {
                        in 0..15 -> {
                            if (!ignoreColor) {
                                drawColor = hexColors[colorIndex] or (alpha shl 24)
                                // If we see a normal color => turn off rainbow/gradient
                                if (rainbow) glUseProgram(0)
                                if (gradient) glUseProgram(0)
                            }
                            randomCase = false; bold = false; italic = false
                            underline = false; strikeThrough = false
                        }

                        16 -> randomCase = true        // §k => random
                        17 -> bold = true              // §l => bold
                        18 -> strikeThrough = true     // §m => strikethrough
                        19 -> underline = true         // §n => underline
                        20 -> italic = true            // §o => italic
                        21 -> {                        // §r => reset
                            drawColor = color
                            if ((drawColor and -67108864) == 0) {
                                drawColor = drawColor or -16777216
                            }
                            if (rainbow) glUseProgram(RainbowFontShader.programId)
                            if (gradient) glUseProgram(GradientFontShader.programId)

                            randomCase = false; bold = false; italic = false
                            underline = false; strikeThrough = false
                        }
                    }

                    // Choose the correct AWT font
                    currFont = when {
                        bold && italic -> boldItalicFont
                        bold -> boldFont
                        italic -> italicFont
                        else -> defaultFont
                    }

                    // Possibly random-case (magic text)
                    val strToDraw = if (randomCase) randomMagicText(remainder) else remainder
                    // Draw
                    currFont.drawString(strToDraw, widthSoFar, 0.0, drawColor)

                    // Strikethrough => draw a line
                    if (strikeThrough) {
                        val lineY = currFont.height / 3.0
                        drawLine(
                            widthSoFar / 2.0 + 1,
                            lineY,
                            (widthSoFar + currFont.getStringWidth(strToDraw)) / 2.0 + 1,
                            lineY,
                            (fontHeight / 16f)
                        )
                    }
                    // Underline => draw line at bottom
                    if (underline) {
                        val lineY = currFont.height / 2.0
                        drawLine(
                            widthSoFar / 2.0 + 1,
                            lineY,
                            (widthSoFar + currFont.getStringWidth(strToDraw)) / 2.0 + 1,
                            lineY,
                            (fontHeight / 16f)
                        )
                    }
                    widthSoFar += currFont.getStringWidth(strToDraw)
                }
            }
        } else {
            // No color codes => just default
            defaultFont.drawString(text, 0.0, 0.0, drawColor)
        }

        // Cleanup
        disableBlend()
        // 无颜色码文本不会走进 §r 分支的 glUseProgram(0)，着色器程序会残留绑定，
        // 导致后续固定管线绘制（如第一人称手持物品）走错管线 → 手持物品异常/半透明
        glUseProgram(0)
        glTranslated(-(x - 1.5), -(y + 0.5), 0.0)
        glColor4f(1f, 1f, 1f, 1f)
        resetColor()
        return (x + getStringWidth(text)).toInt()
    }

    override fun getColorCode(charCode: Char): Int {
        // Convert color code char => color index => color from hexColors
        return hexColors[getColorIndex(charCode)]
    }

    override fun getStringWidth(text: String?): Int {
        if (text == null) {
            return 0
        }

        // NameProtect transformation
        val realText = NameProtect.handleTextMessage(text)
        // If color codes => parse for advanced widths
        return if ('§' in realText) parseColoredWidth(realText) else {
            // Otherwise => just default
            defaultFont.getStringWidth(realText) / 2
        }
    }

    override fun getCharWidth(character: Char): Int = getStringWidth(character.toString())

    /**
     * Parse color codes in [text] to get accurate width.
     */
    private fun parseColoredWidth(text: String): Int {
        val segments = text.split('§')
        var widthPx = 0
        var currentFont = defaultFont
        var bold = false
        var italic = false

        segments.forEachIndexed { idx, seg ->
            if (seg.isEmpty()) return@forEachIndexed
            if (idx == 0) {
                widthPx += currentFont.getStringWidth(seg)
            } else {
                val codeType = seg[0]
                val remainder = seg.substring(1)
                val colorIndex = getColorIndex(codeType)
                when {
                    colorIndex < 16 -> {
                        bold = false
                        italic = false
                    }

                    colorIndex == 17 -> bold = true
                    colorIndex == 20 -> italic = true
                    colorIndex == 21 -> {
                        bold = false
                        italic = false
                    }
                }
                currentFont = when {
                    bold && italic -> boldItalicFont
                    bold -> boldFont
                    italic -> italicFont
                    else -> defaultFont
                }
                widthPx += currentFont.getStringWidth(remainder)
            }
        }
        return widthPx / 2
    }

    companion object {
        /**
         * Map '0'..'9' => 0..9, 'a'..'f' => 10..15, 'k'..'o' => 16..20, 'r' => 21
         */
        fun getColorIndex(type: Char): Int {
            return when (type) {
                in '0'..'9' -> type - '0'
                in 'a'..'f' -> type - 'a' + 10
                in 'k'..'o' -> type - 'k' + 16
                'r' -> 21
                else -> -1
            }
        }
    }
}
