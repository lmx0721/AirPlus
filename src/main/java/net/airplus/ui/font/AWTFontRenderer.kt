/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.ui.font

import net.airplus.event.Listenable
import net.airplus.event.Render2DEvent
import net.airplus.event.handler
import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.kotlin.LruCache
import net.airplus.utils.render.ColorUtils
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.GlStateManager.bindTexture
import net.minecraft.client.renderer.texture.TextureUtil
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL12.*
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

@SideOnly(Side.CLIENT)
class AWTFontRenderer @JvmOverloads constructor(
    val font: Font,
    startChar: Int = 0,
    stopChar: Int = 65535,
    private val loadingScreen: Boolean = false,
    /** 是否套用全局字体粗细（图标字体传 false，避免图标被描边加粗）。 */
    private val styleable: Boolean = true
) : MinecraftInstance {

    companion object : Listenable {
        var assumeNonVolatile: Boolean = false

        // ===== 全局字体粗细（描边像素，按烘焙尺寸计；由 Client 模块同步写入）=====
        // 主字体字形在全局粗细之外额外套用的描边量由 fallbackTextWeight 表示（中文回退字形专用）
        @JvmStatic
        var textWeight: Float = 0f
        @JvmStatic
        var fallbackTextWeight: Float = 0f

        // 配置线程/初始化线程只置标志，真正的 GL 重烘焙在渲染线程的 Render2D 回调中执行
        @Volatile
        private var styleReloadPending = false
        @Volatile
        private var fallbackInvalidatePending = false

        /** 请求重新烘焙全局字体样式（字号/粗细）；线程安全，延迟到渲染线程执行。 */
        @JvmStatic
        fun requestStyleReload() {
            styleReloadPending = true
        }

        /** 请求清除所有渲染器的中文回退字形缓存（字体文件/字号/粗细变化时）；延迟到渲染线程执行。 */
        @JvmStatic
        fun requestFallbackInvalidate() {
            fallbackInvalidatePending = true
        }

        /** 在渲染线程上清除所有渲染器的中文回退字形缓存。 */
        private fun invalidateFallbackGlyphs() {
            activeFontRenderers.forEach { it.clearFallbackCache() }
        }

        // ===== 中文回退字体设置（由 Client 模块同步写入）=====
        // 主字体缺字形时使用的回退字体文件（相对 assets/minecraft/airplus/fonts/ 或 fontsDir）
        @JvmStatic
        var fallbackFontFile: String = "HarmonyOS_Sans_SC_Regular.ttf"
        // 字号模式："Offset" = 所在渲染器字号 + offset；"Percent" = 所在渲染器字号 × scale%
        @JvmStatic
        var fallbackFontSizeMode: String = "Offset"
        @JvmStatic
        var fallbackFontSizeOffset: Int = 0
        @JvmStatic
        var fallbackFontSizeScale: Int = 100

        private val activeFontRenderers = mutableListOf<AWTFontRenderer>()

        // 回退字体缓存：key = "$file#$targetSize"，避免切换字体/模式后取到陈旧条目
        private const val MAX_FALLBACK_FONTS = 32
        private val chineseFallbackFonts = HashMap<String, Font>()

        private fun resolveFallbackTargetSize(size: Int): Int {
            return if (fallbackFontSizeMode.equals("Percent", true)) {
                (size * fallbackFontSizeScale / 100).coerceAtLeast(1)
            } else {
                (size + fallbackFontSizeOffset).coerceAtLeast(1)
            }
        }

        private fun getChineseFallbackFont(size: Int): Font? {
            val targetSize = resolveFallbackTargetSize(size)
            val file = fallbackFontFile
            val cacheKey = "$file#$targetSize"
            synchronized(chineseFallbackFonts) {
                chineseFallbackFonts[cacheKey]?.let { return it }
            }

            // 优先使用配置的回退字体文件
            val configured = Fonts.loadFontFile(file, targetSize)
            if (configured != null && (configured.canDisplay('\u4e2d') || configured.canDisplay('\u6587'))) {
                synchronized(chineseFallbackFonts) {
                    if (chineseFallbackFonts.size > MAX_FALLBACK_FONTS) chineseFallbackFonts.clear()
                    chineseFallbackFonts[cacheKey] = configured
                }
                return configured
            }

            // 配置字体不可用 → 系统字体回退（原逻辑）
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
                    val fallbackFont = Font(fallbackName, Font.PLAIN, targetSize)
                    if (fallbackFont.canDisplay('\u4e2d') || fallbackFont.canDisplay('\u6587')) {
                        synchronized(chineseFallbackFonts) {
                            if (chineseFallbackFonts.size > MAX_FALLBACK_FONTS) chineseFallbackFonts.clear()
                            chineseFallbackFonts[cacheKey] = fallbackFont
                        }
                        return fallbackFont
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
            return null
        }

        inline fun assumeNonVolatile(block: () -> Unit) {
            assumeNonVolatile = true
            try {
                block()
            } finally {
                assumeNonVolatile = false
            }
        }

        private const val GC_TICKS = 600
        private const val CACHED_FONT_REMOVAL_TIME = 30000L
        private const val MAX_CACHED_STRINGS = 255

        private var gcTicks = 0

        private val onRender2D = handler<Render2DEvent>(priority = Byte.MIN_VALUE) {
            // 全局字号/粗细变化：在渲染线程重烘焙核心字体并刷新回退字形缓存
            if (styleReloadPending) {
                styleReloadPending = false
                fallbackInvalidatePending = false
                Fonts.rebakeStyledFonts()
                invalidateFallbackGlyphs()
            } else if (fallbackInvalidatePending) {
                fallbackInvalidatePending = false
                invalidateFallbackGlyphs()
            }

            if (++gcTicks > GC_TICKS) {
                activeFontRenderers.forEach { it.collectGarbage() }
                gcTicks = 0
            }
        }
    }

    private data class CharLocation(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )

    private data class CachedFont(
        val displayList: Int,
        var lastUsage: Long,
        var deleted: Boolean = false
    )

    private val charLocations = mutableMapOf<Int, CharLocation>()
    private val dynamicCharTextures = mutableMapOf<Int, Int>()

    private val cachedStringFonts = LruCache<String, CachedFont>(MAX_CACHED_STRINGS)
    private val cachedStringWidths = LruCache<String, Int>(MAX_CACHED_STRINGS)

    private var textureID: Int = -1
    private var textureWidth: Int = 0
    private var textureHeight: Int = 0
    private var fontHeight: Int = -1

    val height: Int
        get() = (fontHeight - 8) / 2

    /**
     * 视觉行高（调用方坐标系，即 GameFontRenderer.drawString 使用的坐标）。
     * 管线内部 glScaled(0.25)，AWT fm.height 像素映射为 fm.height × 0.25。
     * 垂直居中公式：drawY = top + (boxHeight - visualLineHeight) / 2 + 2.75f
     * （+2.75 补偿 drawString 的 baseY(y-3) 与内部平移，使 em 行盒在盒子内居中）。
     */
    val visualLineHeight: Float by lazy {
        val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.font = font
        val fm = g.fontMetrics
        g.dispose()
        fm.height * 0.25f
    }

    /** 某字形应套用的描边粗细（烘焙像素）：图标渲染器恒为 0。 */
    private fun weightPx(fallback: Boolean): Float =
        if (!styleable) 0f
        else if (fallback) textWeight + fallbackTextWeight
        else textWeight

    /**
     * 绘制字形到 AWT 画布：粗细为 0 时沿用原生 drawString（保持原有观感）；
     * 大于 0 时用字形轮廓 + 描边加粗，从而实现可自定义的字重。
     */
    private fun drawGlyph(g2d: java.awt.Graphics2D, useFont: Font, fm: FontMetrics, text: String, weight: Float) {
        g2d.font = useFont
        g2d.color = Color.WHITE
        if (weight <= 0f) {
            g2d.drawString(text, 3, 1 + fm.ascent)
            return
        }
        val outline = useFont.createGlyphVector(g2d.fontRenderContext, text)
            .getOutline(3f, (1 + fm.ascent).toFloat())
        g2d.fill(outline)
        g2d.stroke = BasicStroke(weight, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2d.draw(outline)
    }

    /**
     * 清除中文回退字形缓存：回退字体文件/字号/粗细变化后，动态字形贴图、字符尺寸表
     * 以及宽度/显示列表缓存都必须失效，否则设置改动看起来「没有生效」。
     * 含 GL 调用，必须在渲染线程执行。
     */
    private fun clearFallbackCache() {
        dynamicCharTextures.values.forEach { glDeleteTextures(it) }
        dynamicCharTextures.clear()
        charLocations.keys.removeAll { it > 255 }
        cachedStringWidths.clear()
        cachedStringFonts.values.forEach { glDeleteLists(it.displayList, 1) }
        cachedStringFonts.clear()
    }

    init {
        renderBitmap(startChar, minOf(stopChar, 256))
        activeFontRenderers += this
    }

    fun drawString(text: String, x: Double, y: Double, color: Int) {
        glPushMatrix()
        glScaled(0.25, 0.25, 0.25)
        glTranslated(x * 2.0, y * 2.0 - 2.0, 0.0)

        // 使用 GlStateManager 而非裸 GL 调用，避免与原版状态缓存脱钩导致白屏闪烁
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO)
        GlStateManager.enableTexture2D()

        if (loadingScreen) {
            glBindTexture(GL_TEXTURE_2D, textureID)
        } else {
            bindTexture(textureID)
        }

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)

        val (alpha, red, green, blue) = ColorUtils.unpackARGBFloatValue(color)
        glColor4f(red, green, blue, alpha)

        val cached = cachedStringFonts[text]
        if (cached != null) {
            glCallList(cached.displayList)
            cached.lastUsage = System.currentTimeMillis()
            glPopMatrix()
            return
        }

        var listID = -1
        if (assumeNonVolatile) {
            listID = glGenLists(1)
            glNewList(listID, GL_COMPILE_AND_EXECUTE)
        }

        glBegin(GL_QUADS)

        var currX = 0f
        var fallbackWidth = 0f

        for (char in text) {
            val charCode = char.code
            var loc = charLocations[charCode]

            if (loc == null && charCode > 255) {
                glEnd()
                loc = renderDynamicChar(char)
                if (loadingScreen) {
                    glBindTexture(GL_TEXTURE_2D, textureID)
                } else {
                    bindTexture(textureID)
                }
                glBegin(GL_QUADS)
            }

            if (loc == null) {
                glEnd()
                GlStateManager.resetColor()

                glPushMatrix()
                val rev = 4.0f
                glScalef(rev, rev, rev)
                val scale = font.size / 32.0f
                glScalef(scale, scale, 1.0f)

                mc.fontRendererObj.posY = 1.0f
                mc.fontRendererObj.posX = currX / rev

                val fallbackW = mc.fontRendererObj.renderUnicodeChar(char, false).coerceAtLeast(0f)
                fallbackWidth += fallbackW

                if (loadingScreen) {
                    glBindTexture(GL_TEXTURE_2D, textureID)
                } else {
                    bindTexture(textureID)
                }
                glPopMatrix()
                glBegin(GL_QUADS)

                currX += fallbackW * rev
                mc.fontRendererObj.posX = currX / rev
            } else {
                drawChar(loc, currX, 0f, charCode)
                currX += (loc.width - 8f)
            }
        }

        glEnd()

        if (assumeNonVolatile && listID >= 0) {
            cachedStringFonts[text] = CachedFont(listID, System.currentTimeMillis())
            glEndList()
        }

        glPopMatrix()
    }

    /**
     * 仅测量字符尺寸（纯 AWT，无 GL 副作用）。
     * 供 [getStringWidth] 使用，避免在非渲染上下文中创建/绑定纹理，
     * 否则会导致 GlStateManager 纹理绑定缓存脱钩，引发白屏闪烁。
     */
    private fun measureChar(c: Char): CharLocation? {
        var useFont = font
        var isFallback = false
        if (!font.canDisplay(c)) {
            val fallback = getChineseFallbackFont(font.size)
            if (fallback != null && fallback.canDisplay(c)) {
                useFont = fallback
                isFallback = true
            } else {
                return null
            }
        }

        return try {
            val measureImg = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            val measureG = measureImg.createGraphics()
            measureG.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            measureG.font = useFont

            val fm = measureG.fontMetrics
            // 字重描边会向外扩张，宽高预留相应像素，保证排版间距与描边后的实际字宽一致
            val pad = ceil(weightPx(isFallback)).toInt()
            var w = fm.charWidth(c) + 8 + pad
            if (w <= 0) w = 7
            var h = fm.height + 3 + pad
            if (h <= 0) h = useFont.size
            measureG.dispose()

            CharLocation(0, 0, w, h)
        } catch (e: Exception) {
            null
        }
    }

    private fun renderDynamicChar(c: Char): CharLocation? {
        val charCode = c.code

        val loc = measureChar(c) ?: return null
        val w = loc.width
        val h = loc.height

        var useFont = font
        var isFallback = false
        if (!font.canDisplay(c)) {
            val fb = getChineseFallbackFont(font.size)
            if (fb != null) {
                useFont = fb
                isFallback = true
            } else {
                useFont = font
            }
        }

        try {
            val measureImg = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            val measureG = measureImg.createGraphics()
            measureG.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            measureG.font = useFont
            val fm = measureG.fontMetrics
            measureG.dispose()

            val charImg = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g2d = charImg.createGraphics()
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            drawGlyph(g2d, useFont, fm, c.toString(), weightPx(isFallback))
            g2d.dispose()

            val texId = TextureUtil.glGenTextures()
            GlStateManager.bindTexture(texId)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            TextureUtil.uploadTextureImageAllocate(texId, charImg, true, true)
            dynamicCharTextures[charCode] = texId

            charLocations[charCode] = loc

            if (h > fontHeight) {
                fontHeight = h
            }

            return loc
        } catch (e: Exception) {
            return null
        }
    }

    fun getStringWidth(text: String): Int = cachedStringWidths.getOrPut(text) {
        var myWidth = 0
        var fallbackWidth = 0f
        val fallbackScale = font.size / 32f

        for (char in text) {
            val charCode = char.code
            var loc = charLocations[charCode]

            // 只测量尺寸，不创建纹理（无 GL 副作用）
            if (loc == null && charCode > 255) {
                loc = measureChar(char)
            }

            if (loc == null) {
                val w = mc.fontRendererObj.getCharWidth(char)
                fallbackWidth += ((w + 8) * fallbackScale).coerceAtLeast(0f)
            } else {
                myWidth += (loc.width - 8)
            }
        }

        (myWidth / 2) + fallbackWidth.roundToInt()
    }

    fun dispose() {
        if (textureID != -1) {
            glDeleteTextures(textureID)
            textureID = -1
        }
        dynamicCharTextures.values.forEach { glDeleteTextures(it) }
        dynamicCharTextures.clear()
        activeFontRenderers.remove(this)
    }

    protected fun finalize() {
        dispose()
    }

    private fun drawChar(loc: CharLocation, x: Float, y: Float, charCode: Int = -1) {
        val dynamicTexId = if (charCode >= 0) dynamicCharTextures[charCode] else null
        if (dynamicTexId != null) {
            glEnd()
            bindTexture(dynamicTexId)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glBegin(GL_QUADS)
        }

        val w = loc.width.toDouble()
        val h = loc.height.toDouble()
        val xd = x.toDouble()
        val yd = y.toDouble()

        if (dynamicTexId != null) {
            glTexCoord2d(0.0, 0.0)
            glVertex2d(xd, yd)

            glTexCoord2d(0.0, 1.0)
            glVertex2d(xd, yd + h)

            glTexCoord2d(1.0, 1.0)
            glVertex2d(xd + w, yd + h)

            glTexCoord2d(1.0, 0.0)
            glVertex2d(xd + w, yd)
        } else {
            val u = loc.x.toDouble() / textureWidth
            val v = loc.y.toDouble() / textureHeight
            val uw = w / textureWidth
            val vh = h / textureHeight

            glTexCoord2d(u, v)
            glVertex2d(xd, yd)

            glTexCoord2d(u, v + vh)
            glVertex2d(xd, yd + h)

            glTexCoord2d(u + uw, v + vh)
            glVertex2d(xd + w, yd + h)

            glTexCoord2d(u + uw, v)
            glVertex2d(xd + w, yd)
        }

        if (dynamicTexId != null) {
            glEnd()
            if (loadingScreen) {
                glBindTexture(GL_TEXTURE_2D, textureID)
            } else {
                bindTexture(textureID)
            }
            glBegin(GL_QUADS)
        }
    }

    private fun renderBitmap(startChar: Int, stopChar: Int) {
        val fontImages = mutableMapOf<Int, BufferedImage>()

        var rowHeight = 0
        var charX = 0
        var charY = 0

        for (charCode in startChar until stopChar) {
            val charImg = drawCharToImage(charCode.toChar())
            val cw = charImg.width
            val ch = charImg.height

            if (ch > fontHeight) {
                fontHeight = ch
            }

            val loc = CharLocation(charX, charY, cw, ch)
            charLocations[charCode] = loc
            fontImages[charCode] = charImg

            charX += cw
            if (cw > 0 && ch > rowHeight) {
                rowHeight = ch
            }
            if (charX > 2048) {
                if (charX > textureWidth)
                    textureWidth = charX
                charX = 0
                charY += rowHeight
                rowHeight = 0
            }
        }
        textureWidth = max(textureWidth, charX)
        textureHeight = charY + rowHeight

        val bigImage = BufferedImage(textureWidth, textureHeight, BufferedImage.TYPE_INT_ARGB)
        val g = bigImage.createGraphics()
        g.font = font
        g.color = Color(255, 255, 255, 0)
        g.fillRect(0, 0, textureWidth, textureHeight)
        g.color = Color.WHITE
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        for (charCode in startChar until stopChar) {
            val subImg = fontImages[charCode] ?: continue
            val loc = charLocations[charCode] ?: continue
            g.drawImage(subImg, loc.x, loc.y, null)
        }

        textureID = TextureUtil.glGenTextures()
        GlStateManager.bindTexture(textureID)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        TextureUtil.uploadTextureImageAllocate(textureID, bigImage, true, true)
    }

    private fun drawCharToImage(c: Char): BufferedImage {
        val measureImg = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val measureG = measureImg.createGraphics()
        measureG.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        measureG.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        measureG.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        measureG.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        measureG.font = font

        val fm = measureG.fontMetrics
        // 字重描边会向外扩张，宽高预留相应像素
        val pad = ceil(weightPx(false)).toInt()
        var w = fm.charWidth(c) + 8 + pad
        if (w <= 0) w = 7
        var h = fm.height + 3 + pad
        if (h <= 0) h = font.size

        val charImg = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g2d = charImg.createGraphics()
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        drawGlyph(g2d, font, fm, c.toString(), weightPx(false))
        return charImg
    }

    private fun collectGarbage() {
        val now = System.currentTimeMillis()

        with(cachedStringFonts.entries.iterator()) {
            while (hasNext()) {
                val cached = next().value
                if (!cached.deleted && (now - cached.lastUsage) > CACHED_FONT_REMOVAL_TIME) {
                    glDeleteLists(cached.displayList, 1)
                    cached.deleted = true
                    remove()
                }
            }
        }
    }
}
