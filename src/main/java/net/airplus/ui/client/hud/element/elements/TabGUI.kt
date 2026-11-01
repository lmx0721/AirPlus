/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * TabGUI ported from Flux Client (today.flux.gui.hud.TabGuiRenderer, YouM02/Flux-Client-Eclipse-Ready).
 */
package net.airplus.ui.client.hud.element.elements

import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.ModuleManager
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.Element
import net.airplus.ui.client.hud.element.ElementInfo
import net.airplus.ui.font.Fonts
import net.airplus.ui.font.GameFontRenderer
import net.airplus.utils.client.MinecraftInstance.Companion.mc
import net.airplus.utils.render.HudBlur
import net.airplus.utils.render.RenderUtils.deltaTime
import net.airplus.utils.render.RenderUtils.drawBorderedRect
import net.airplus.utils.render.RenderUtils.drawRect
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.airplus.utils.render.RenderUtils.makeScissorBox
import net.airplus.utils.render.shader.ShaderSupport
import net.minecraft.client.gui.ScaledResolution
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11.GL_SCISSOR_TEST
import org.lwjgl.opengl.GL11.glDisable
import org.lwjgl.opengl.GL11.glEnable
import java.awt.Color
import kotlin.math.abs
import kotlin.math.sign

/**
 * Flux 风格 TabGUI。
 *
 * 布局：左侧分类列（左缘 1.5F 高亮条 + 选中行白字滑入），右键展开模块列（AnimationTimer 式宽度展开）。
 * 主题：Normal（暗/亮两套配色 + 可选背景模糊）、Nostalgia（老式洋红高亮 + 原版字体）。
 * 按键：↑↓ 移动（长按 500ms 后每 410ms 重复），→ 进入模块列 / toggle，← 返回，回车 toggle。
 */
@ElementInfo(name = "TabGUI")
class TabGUI(x: Double = 16.0, y: Double = 43.0) : Element("TabGUI", x = x, y = y) {

    // Settings
    private val theme by choices("Theme", arrayOf("Normal", "Nostalgia"), "Normal")
    private val lightMode by boolean("LightMode", false)
    private val blur by boolean("Blur", true)

    // Colors (Flux originals)
    private val accent get() = if (isLight) ACCENT_LIGHT else ACCENT_DARK
    private val bgColor get() = if (isLight) BG_LIGHT else BG_DARK
    private val textGray get() = if (isLight) TEXT_GRAY_LIGHT else TEXT_GRAY_DARK

    private val isNostalgia get() = theme == "Nostalgia"
    private val isLight get() = lightMode && !isNostalgia

    private val fluxFont: GameFontRenderer get() = Fonts.fontFluxTabGui

    // State (Flux: categoryIndex/moduleIndex + render indexes for smooth highlight)
    private var section = Section.Category
    private var categoryIndex = 0
    private var moduleIndex = 0
    private var renderCategoryIndex = 0F
    private var renderModuleIndex = 0F
    private var tabAnimation = 0F
    private var moduleScroll = 0F

    // Long-press repeat (Flux DelayTimer: 500ms hold, then repeat every 410ms)
    private var downPressStart = 0L
    private var upPressStart = 0L
    private var downLastRepeat = 0L
    private var upLastRepeat = 0L
    private var lastAnimStep = 0L

    private enum class Section { Category, Module }

    // Layout
    private val categories get() = Category.entries
    private val currentCategory get() = categories[categoryIndex.coerceIn(0, categories.lastIndex)]

    private val rowHeight
        get() = if (isNostalgia) mc.fontRendererObj.FONT_HEIGHT + 2F else fluxFont.height + 2F

    private val catWidth get() = maxCategoryWidth() + EXPAND_BOX
    private val catHeight get() = categories.size * rowHeight + 2F

    private fun textWidth(text: String): Float =
        if (isNostalgia) mc.fontRendererObj.getStringWidth(text).toFloat() else fluxFont.getStringWidth(text).toFloat()

    private fun maxCategoryWidth(): Float = categories.maxOf { textWidth(it.displayName) }

    private fun maxModuleWidth(category: Category): Float =
        ModuleManager[category].maxOfOrNull { textWidth(it.getName()) } ?: 0F

    override fun drawElement(): Border {
        updateSmooth()

        val width = catWidth
        val height = catHeight

        drawBackground(width, height)
        drawCategories(width)

        drawModulePanel(width)

        // Border covers category column + expanded module column
        val moduleExtra = if (tabAnimation > 0.02F) MODULE_GAP + maxModuleWidth(currentCategory) * tabAnimation else 0F
        return Border(0F, 0F, width + moduleExtra, height)
    }

    private fun drawBackground(width: Float, height: Float) {
        if (isNostalgia) {
            drawBorderedRect(0F, 0F, width, height, 1F, NOST_BORDER.rgb, NOST_BG.rgb)
            return
        }

        if (blur && ShaderSupport.isSupported) {
            // 模糊类型/强度由 Client 模块的全局 Blur 设置统一控制（HudBlur）
            // 模糊 AABB 必须换算成屏幕绝对坐标（HUD 的 scale/translate 变换），
            // 遮罩在当前矩阵下按局部坐标绘制，并带上与背景一致的圆角
            val absX1 = (scale * (0F + renderX)).toFloat()
            val absY1 = (scale * (0F + renderY)).toFloat()
            val absX2 = (scale * (width + renderX)).toFloat()
            val absY2 = (scale * (height + renderY)).toFloat()
            HudBlur.blur(absX1, absY1, absX2, absY2) {
                drawRoundedRect(0F, 0F, width, height, -1, CORNER_RADIUS)
            }
        }
        drawRoundedRect(0F, 0F, width, height, bgColor.rgb, CORNER_RADIUS)
    }

    private fun drawCategories(width: Float) {
        val startX = 1F
        val startY = 1F
        val textX = startX + 3F

        // Selected highlight (Flux: 1.5F accent bar on the left edge)
        if (isNostalgia) {
            val rowY = startY + rowHeight * renderCategoryIndex
            drawRect(0F, rowY, width, rowY + rowHeight, Color.MAGENTA.rgb)
        } else {
            val barY = startY + rowHeight * renderCategoryIndex + 1F
            drawRect(startX, barY, startX + 1.5F, barY + rowHeight - 1F, accent.rgb)
        }

        // All rows in gray
        var textY = startY + 1F
        for (category in categories) {
            drawText(category.displayName, textX, textY, textGray.rgb)
            textY += rowHeight
        }

        // Selected row in white (crop a band following the smooth highlight, redraw all rows)
        if (!isNostalgia) {
            glEnable(GL_SCISSOR_TEST)
            makeScissorBox(
                startX,
                startY + rowHeight * renderCategoryIndex,
                width,
                startY + rowHeight * (renderCategoryIndex + 1F) + 1F
            )
            textY = startY + 1F
            for (category in categories) {
                drawText(category.displayName, textX, textY, accent.rgb)
                textY += rowHeight
            }
            glDisable(GL_SCISSOR_TEST)
        }
    }

    /**
     * Expanding module panel, anchored to the selected category row.
     * Includes background + modules, all clipped by scissor while expanding (Flux tabAnimation).
     *
     * 滚动处理：面板超出屏幕底部时整体上移贴底；仍放不下（内容比屏幕还高）时
     * 固定面板高度为可见区域，内容随选中项平滑滚动，保证选中模块始终可见。
     */
    private fun drawModulePanel(catWidth: Float) {
        val modules = ModuleManager[currentCategory]
        if (modules.isEmpty() || tabAnimation <= 0.02F) return

        val panelWidth = maxModuleWidth(currentCategory) + EXPAND_BOX
        val contentHeight = modules.size * rowHeight + 2F
        val panelX = catWidth + MODULE_GAP
        val unclampedY = rowHeight * categoryIndex

        // 可用视口：元素顶边到屏幕底部（缩放 GUI 坐标），至少留 3 行
        val viewport = (ScaledResolution(mc).scaledHeight - renderY.toFloat() - 1F)
            .coerceAtLeast(rowHeight * 3F)

        var panelY = unclampedY
        var visibleHeight = contentHeight

        if (unclampedY + contentHeight > viewport) {
            if (contentHeight <= viewport) {
                // 整体上移，贴住屏幕底部
                panelY = viewport - contentHeight
                moduleScroll = 0F
            } else {
                // 内容比视口还高：面板高度固定为视口，内容滚动
                panelY = 0F
                visibleHeight = viewport

                val maxScroll = contentHeight - viewport
                val selectedTop = renderModuleIndex * rowHeight
                val selectedBottom = selectedTop + rowHeight

                val target = when {
                    selectedTop - rowHeight * 0.5F < moduleScroll ->
                        (selectedTop - rowHeight * 0.5F).coerceAtLeast(0F)
                    selectedBottom + rowHeight * 0.5F > moduleScroll + viewport ->
                        (selectedBottom + rowHeight * 0.5F - viewport).coerceAtMost(maxScroll)
                    else -> moduleScroll
                }.coerceIn(0F, maxScroll)

                moduleScroll += (target - moduleScroll) * 0.1F * deltaTime
                if (abs(target - moduleScroll) < 0.5F) moduleScroll = target
            }
        } else {
            moduleScroll = 0F
        }

        val clipWidth = panelWidth * tabAnimation
        val scroll = moduleScroll

        glEnable(GL_SCISSOR_TEST)
        makeScissorBox(panelX, panelY, panelX + clipWidth, panelY + visibleHeight)

        if (!isNostalgia && blur && ShaderSupport.isSupported) {
            // 模块列模糊：同款将 AABB 换算为屏幕绝对坐标，遮罩在当前矩阵按局部坐标绘制
            val absX1 = (scale * (panelX + renderX)).toFloat()
            val absY1 = (scale * (panelY + renderY)).toFloat()
            val absX2 = (scale * (panelX + panelWidth + renderX)).toFloat()
            val absY2 = (scale * (panelY + visibleHeight + renderY)).toFloat()
            HudBlur.blur(absX1, absY1, absX2, absY2) {
                drawRoundedRect(panelX, panelY, panelX + panelWidth, panelY + visibleHeight, -1, CORNER_RADIUS)
            }
        }

        if (isNostalgia) {
            drawBorderedRect(panelX, panelY, panelX + panelWidth, panelY + visibleHeight, 1F, NOST_BORDER.rgb, NOST_BG.rgb)
        } else {
            drawRoundedRect(panelX, panelY, panelX + panelWidth, panelY + visibleHeight, bgColor.rgb, CORNER_RADIUS)
        }

        // Selected highlight
        if (isNostalgia) {
            val rowY = panelY + 1F + rowHeight * renderModuleIndex - scroll
            drawRect(panelX, rowY, panelX + panelWidth, rowY + rowHeight, Color.MAGENTA.rgb)
        } else {
            val barY = panelY + 1F + rowHeight * renderModuleIndex - scroll
            drawRect(panelX + 1F, barY, panelX + 1F + 1.5F, barY + rowHeight - 1F, accent.rgb)

        }

        // Module names: enabled = white, disabled = gray (offset by scroll, only draw visible rows)
        val textX = panelX + 4F
        var textY = panelY + 1F - scroll
        for (module in modules) {
            if (textY + rowHeight >= panelY && textY <= panelY + visibleHeight) {
                drawText(module.getName(), textX, textY, if (module.state) Color.WHITE.rgb else textGray.rgb)
            }
            textY += rowHeight
        }

        glDisable(GL_SCISSOR_TEST)
    }

    private fun drawText(text: String, x: Float, y: Float, color: Int) {
        if (isNostalgia) {
            mc.fontRendererObj.drawStringWithShadow(text, x, y, color)
        } else {
            fluxFont.drawString(text, x, y, color, shadow = false)
        }
    }

    /**
     * Flux: highlight index moves <= 0.15F every 10ms; tabAnimation approaches target with AnimationTimer(8).
     */
    private fun updateSmooth() {
        val now = System.currentTimeMillis()

        if (now - lastAnimStep >= 10) {
            lastAnimStep = now
            renderCategoryIndex = approach(renderCategoryIndex, categoryIndex.toFloat())
            renderModuleIndex = approach(renderModuleIndex, moduleIndex.toFloat())
        }

        val target = if (section == Section.Module) 1F else 0F
        tabAnimation += (target - tabAnimation) * 0.1F * deltaTime
        if (abs(target - tabAnimation) < 0.005F) tabAnimation = target
        tabAnimation = tabAnimation.coerceIn(0F, 1F)
    }

    private fun approach(current: Float, target: Float): Float {
        val diff = target - current
        return if (abs(diff) <= 0.15F) target else current + 0.15F * sign(diff)
    }

    override fun handleKey(c: Char, keyCode: Int) {
        when (keyCode) {
            Keyboard.KEY_UP -> keyPress(Keyboard.KEY_UP)
            Keyboard.KEY_DOWN -> keyPress(Keyboard.KEY_DOWN)
            Keyboard.KEY_LEFT -> keyPress(Keyboard.KEY_LEFT)
            Keyboard.KEY_RIGHT -> keyPress(Keyboard.KEY_RIGHT)
            Keyboard.KEY_RETURN -> if (section == Section.Module) toggleCurrentModule()
        }
    }

    /**
     * Flux onTick: while ↑/↓ held, repeat after 500ms, then every 410ms.
     */
    override fun updateElement() {
        // 别的界面（聊天等）打开时不轮询，避免输入串扰
        if (mc.currentScreen != null) return

        val now = System.currentTimeMillis()

        if (Keyboard.isKeyDown(Keyboard.KEY_DOWN)) {
            if (downPressStart == 0L) {
                downPressStart = now
            } else if (now - downPressStart >= 500 && now - downLastRepeat >= 410) {
                keyPress(Keyboard.KEY_DOWN)
                downLastRepeat = now
            }
        } else {
            downPressStart = 0L
            downLastRepeat = 0L
        }

        if (Keyboard.isKeyDown(Keyboard.KEY_UP)) {
            if (upPressStart == 0L) {
                upPressStart = now
            } else if (now - upPressStart >= 500 && now - upLastRepeat >= 410) {
                keyPress(Keyboard.KEY_UP)
                upLastRepeat = now
            }
        } else {
            upPressStart = 0L
            upLastRepeat = 0L
        }
    }

    private fun keyPress(key: Int) {
        if (section == Section.Category) {
            if (key == Keyboard.KEY_DOWN && categoryIndex < categories.lastIndex) {
                categoryIndex++
            }

            if (key == Keyboard.KEY_UP && categoryIndex > 0) {
                categoryIndex--
            }

            if (key == Keyboard.KEY_RIGHT) {
                section = Section.Module
                moduleIndex = 0
                renderModuleIndex = 0F
            }
            return
        }

        // Section.Module
        val size = ModuleManager[currentCategory].size

        if (key == Keyboard.KEY_DOWN && moduleIndex < size - 1) {
            moduleIndex++
        }

        if (key == Keyboard.KEY_UP && moduleIndex > 0) {
            moduleIndex--
        }

        if (key == Keyboard.KEY_LEFT) {
            section = Section.Category
            moduleIndex = 0
            return
        }

        if (key == Keyboard.KEY_RIGHT && size > 0) {
            toggleCurrentModule()
        }
    }

    private fun toggleCurrentModule() {
        val modules = ModuleManager[currentCategory]
        modules.getOrNull(moduleIndex)?.toggle()
    }

    private companion object {
        private const val EXPAND_BOX = 15F
        private const val MODULE_GAP = 2F

        private val ACCENT_DARK = Color(206, 89, 255)
        private val ACCENT_LIGHT = Color(0x1D, 0xA0, 0xFF)
        private val BG_DARK = Color(0, 0, 0, 150)
        private val BG_LIGHT = Color(255, 255, 255, 200)
        private val TEXT_GRAY_DARK = Color(185, 185, 185)
        private val TEXT_GRAY_LIGHT = Color(0x72, 0x73, 0x71)
        private val NOST_BORDER = Color(30, 30, 30)
        private val NOST_BG = Color(0, 0, 0)

        /** Normal 主题背景与模糊遮罩的统一圆角（Nostalgia 保持直角老式风格） */
        private val CORNER_RADIUS = 6F
    }
}
