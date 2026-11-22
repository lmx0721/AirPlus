/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.ui.client.hud.element.elements

import net.airplus.features.module.modules.world.scaffolds.Scaffold
import net.airplus.ui.glass.Anim
import net.airplus.ui.client.hud.designer.GuiHudDesigner
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.Element
import net.airplus.ui.client.hud.element.ElementInfo
import net.airplus.ui.client.hud.element.Side
import net.airplus.ui.client.hud.element.Side.Horizontal
import net.airplus.ui.client.hud.element.Side.Vertical
import net.airplus.ui.font.AWTFontRenderer.Companion.assumeNonVolatile
import net.airplus.ui.font.Fonts
import net.airplus.ui.font.GameFontRenderer
import net.airplus.utils.client.ClientThemesUtils
import net.airplus.utils.inventory.InventoryUtils
import net.airplus.utils.render.ColorSettingsInteger
import net.airplus.utils.render.ColorUtils.reAlpha
import net.airplus.utils.render.HudBlur
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.render.RenderUtils.deltaTime
import net.airplus.utils.render.RenderUtils.drawRect
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.RenderHelper
import net.minecraft.client.gui.FontRenderer
import net.minecraft.item.ItemBlock
import net.minecraft.item.ItemStack
import org.lwjgl.opengl.GL11
import java.awt.Color
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * CustomHUD BlockCount element
 *
 * 显示可用于搭路的方块数量（来源：物品栏中的 ItemBlock，且过滤不可放置方块）。
 *
 * 样式：
 * - Rise   —— 迁移自 Rise 计数 HUD（圆角卡片 + 主题强调色 + blur/glow）
 * - Flux   —— 迁移自 edmoonlight FluxHud#drawBlockCount（胶囊卡片 + 三角箭头 + 滑入动画）
 * - Hanabi —— 迁移自 Hanabi HUD#renderBlockCount（胶囊卡片 + 上方箭头指针）
 * - Leader —— 迁移自 Leader Scaffold#renderBlockCounter（图标徽章卡片 + 状态色 + 进度条）
 * - LB     —— 依照 Text 元素 showBlock + %blockcounter% 的组合逻辑实现（物品图标 + 数量文本）
 *
 * 所有样式的背景均支持全局 Blur。
 */
@ElementInfo(name = "BlockCount")
class BlockCount(
    x: Double = 60.0, y: Double = 60.0, scale: Float = 1F,
    side: Side = Side(Horizontal.LEFT, Vertical.UP),
) : Element("BlockCount", x, y, scale, side) {

    private val style by choices("Style", arrayOf("Rise", "Flux", "Hanabi", "Leader", "LB"), "Flux")
    private val visibility by choices("Visibility", arrayOf("Always", "Scaffold"), "Scaffold")
    private val blur by boolean("Background-Blur", true)
    private val shadow by boolean("Shadow", true) { style == "Rise" || style == "LB" }
    private val accentColors = ColorSettingsInteger(this, "AccentColor").with(ClientThemesUtils.getColor())
    private val bgColors = ColorSettingsInteger(this, "BgColor").with(Color(25, 25, 25, 150))
    private val fontValue = font("Font", Fonts.fontSemibold40) { style == "LB" || style == "Rise" }

    // 数字变化动画：计数平滑滚动，数量变更瞬间脉冲放大
    private val countAnim by boolean("Count-Animation", true)
    // 出入场弹性：卡片以过冲缩放登场/退场（关闭则保持原淡入/滑入）
    private val elasticAnim by boolean("Elastic", true)
    // 低数量警告：≤32 时数字颜色随数量减少逐渐变红，0 时全红
    private val lowWarn by boolean("Low-Warning", true)

    // 可见性淡入淡出（Rise / Leader / LB 样式）
    private var animAlpha = 0F

    // Flux / Hanabi 的滑入动画（y 偏移）
    private var slideY = 20F

    // Leader 样式的进度条动画与最大值追踪
    private var animPercent = 0F
    private var counterMax = 0

    // 数字平滑滚动状态（Count-Animation）
    private var displayCount = -1F
    private var lastShown = -1
    private val countPulse = Anim.spring(0F, 0.18F, 0.42F)

    // 出入场弹性缩放（Elastic）：0..1，过冲到 ~1.08
    private val showSpring = Anim.spring(0F, 0.26F, 0.6F)

    // 本帧 drawElement 施加的矩阵缩放（blurRect 需把局部坐标同步缩放后再换算屏幕坐标）
    private var frameScale = 1F

    private val inDesigner: Boolean
        get() = mc.currentScreen is GuiHudDesigner

    private val visibleTarget: Boolean
        get() = mc.thePlayer != null &&
            (visibility == "Always" || Scaffold.handleEvents() || inDesigner)

    /**
     * 方块数量来源：物品栏中可用于搭路的方块（ItemBlock 且过滤黑名单/不可放置方块）
     */
    private fun blockCount(): Int = InventoryUtils.blocksAmount()

    /**
     * 当前展示的方块物品：手持方块优先，其次热键栏中的第一个方块
     */
    private fun displayStack(): ItemStack? {
        val player = mc.thePlayer ?: return null
        player.heldItem?.takeIf { it.item is ItemBlock }?.let { return it }
        for (i in 0..8) {
            player.inventory.getStackInSlot(i)?.takeIf { it.item is ItemBlock }?.let { return it }
        }
        return null
    }

    private fun FontRenderer.heightF(): Float =
        (this as? GameFontRenderer)?.height?.toFloat() ?: FONT_HEIGHT.toFloat()

    /** edmoonlight FluxHud#approach */
    private fun approach(animation: Float, target: Float, speed: Float): Float {
        val add = deltaTime * (speed / 1000F)
        return if (animation < target) max(target, animation.coerceAtMost(target - add) * 0F + animation + add)
            .coerceAtMost(target)
        else min(target, (animation - add).coerceAtLeast(target))
    }

    private fun approachY(value: Float, target: Float): Float =
        approach(value, target, max(10F, abs(value - target) * 50F * 0.5F))

    /** 将 alpha 因子乘入颜色 */
    private fun withA(color: Int, factor: Float): Int {
        val a = ((color shr 24) and 255)
        val na = (a * factor).toInt().coerceIn(0, 255)
        return (na shl 24) or (color and 0xFFFFFF)
    }

    /**
     * 数字变化动画：内部计数平滑滚动逼近真实数量，数量变更瞬间给缩放弹簧一个 impulse。
     * 返回本帧展示的计数。
     */
    private fun shownCount(count: Int): Int {
        if (!countAnim) {
            displayCount = count.toFloat()
            lastShown = count
            countPulse.snap(0F)
            return count
        }
        val dt = deltaTime / 1000F
        if (displayCount < 0) displayCount = count.toFloat()
        if (count != lastShown) {
            countPulse.kick(10F)
            lastShown = count
        }
        displayCount += (count - displayCount) * (1F - exp(-14F * dt))
        if (abs(count - displayCount) < 0.06F) displayCount = count.toFloat()
        countPulse.update(0F, dt)
        return displayCount.roundToInt()
    }

    /** 数字脉冲缩放因子：变更瞬间 1 -> ~1.15 -> 1（关闭数字动画时恒为 1） */
    private fun pulseScale(): Float =
        if (countAnim) (1F + countPulse.value).coerceIn(0.85F, 1.3F) else 1F

    /** 出入场弹性缩放因子：0.5 -> 1 过冲登场，退场反向（关闭时恒为 1） */
    private fun elasticScale(visible: Boolean): Float {
        val target = if (visible || inDesigner) 1F else 0F
        if (!elasticAnim) {
            showSpring.snap(target)
            return target
        }
        return 0.5F + 0.5F * showSpring.update(target, deltaTime / 1000F)
    }

    /** 低数量警告：count ≤ 32 时将 RGB 向警告红 (255,84,84) 线性渐变，alpha 不变 */
    private fun lowWarnMix(color: Int, count: Int): Int {
        if (!lowWarn || count >= 32) return color
        val t = ((32 - count) / 32F).coerceIn(0F, 1F)
        val r = (((color shr 16) and 255) + (255 - ((color shr 16) and 255)) * t).toInt().coerceIn(0, 255)
        val g = (((color shr 8) and 255) + (84 - ((color shr 8) and 255)) * t).toInt().coerceIn(0, 255)
        val b = ((color and 255) + (84 - (color and 255)) * t).toInt().coerceIn(0, 255)
        val a = (color shr 24) and 255
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 填充三角形（Flux/Hanabi 样式的箭头指针） */
    private fun drawFilledTriangle(
        x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, color: Int
    ) {
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        GlStateManager.color(
            ((color shr 16) and 255) / 255F,
            ((color shr 8) and 255) / 255F,
            (color and 255) / 255F,
            ((color shr 24) and 255) / 255F
        )
        GL11.glBegin(GL11.GL_TRIANGLES)
        GL11.glVertex2f(x1, y1)
        GL11.glVertex2f(x2, y2)
        GL11.glVertex2f(x3, y3)
        GL11.glEnd()
        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
    }

    /** 绘制方块物品图标（Leader / LB 样式） */
    private fun drawItemIcon(stack: ItemStack, x: Float, y: Float) {
        RenderHelper.enableGUIStandardItemLighting()
        GlStateManager.enableDepth()
        GlStateManager.disableBlend()
        mc.renderItem.zLevel = 200F
        mc.renderItem.renderItemAndEffectIntoGUI(stack, x.toInt(), y.toInt())
        mc.renderItem.zLevel = 0F
        RenderHelper.disableStandardItemLighting()
        GlStateManager.enableBlend()
    }

    /** 局部矩形 -> 屏幕绝对坐标模糊（AABB + 圆角遮罩）；局部坐标按本帧矩阵缩放同步 */
    private fun blurRect(
        x1: Float, y1: Float, x2: Float, y2: Float,
        radius: Float = 0F, mask: (() -> Unit)? = null
    ) {
        if (!blur) return
        val fs = frameScale
        HudBlur.blur(
            (scale * (x1 * fs + renderX)).toFloat(), (scale * (y1 * fs + renderY)).toFloat(),
            (scale * (x2 * fs + renderX)).toFloat(), (scale * (y2 * fs + renderY)).toFloat(),
            radius
        ) {
            mask?.invoke() ?: drawRoundedRect(x1, y1, x2, y2, -1, radius)
        }
    }

    override fun drawElement(): Border? {
        val target = visibleTarget
        val count = shownCount(blockCount())
        // 出入场弹性 × 数字变更脉冲，围绕元素局部原点整体缩放
        val s = elasticScale(target) * pulseScale()
        frameScale = s
        var result: Border? = null

        assumeNonVolatile {
            val scaled = abs(s - 1F) > 0.002F
            if (scaled) {
                GlStateManager.pushMatrix()
                GlStateManager.scale(s, s, 1F)
            }
            result = when (style) {
                "Flux" -> drawFlux(count, target)
                "Hanabi" -> drawHanabi(count, target)
                "Rise" -> drawRise(count, target)
                "Leader" -> drawLeader(count, target)
                else -> drawLB(count, target)
            }
            if (scaled) GlStateManager.popMatrix()
        }
        // Border 同步缩放，保证 HUD 设计器的悬停/拖拽判定正确
        return result?.let { Border(it.x * s, it.y * s, it.x2 * s, it.y2 * s) }
    }

    // ===== Flux 样式（迁移自 edmoonlight FluxHud#drawBlockCount：胶囊卡片 + 下方三角箭头 + 上滑淡入） =====

    private fun drawFlux(count: Int, state: Boolean): Border? {
        animAlpha = approach(animAlpha, if (state) 0.8F else 0F, 10F)
        slideY = approachY(slideY, if (state) 0F else 20F)
        if (animAlpha <= 0.2F && !inDesigner) return null

        val numFont = Fonts.fontSemibold40
        val labelFont = Fonts.fontSemibold35

        val num = count.toString()
        val label = "  block" + (if (count > 1) "s " else "  ")
        val length = numFont.getStringWidth(num) + labelFont.getStringWidth(label)
        val black = reAlpha(Color.BLACK, (animAlpha * 255).toInt()).rgb
        val numColor = lowWarnMix(reAlpha(Color.WHITE, ((animAlpha + 0.25F).coerceAtMost(1F) * 255).toInt()).rgb, count)
        val labelColor = reAlpha(Color.WHITE, ((animAlpha - 0.1F).coerceAtLeast(0F) * 255).toInt()).rgb

        val boxLeft = -length / 2F
        val boxTop = slideY - 15F
        val boxRight = length / 2F
        val boxBottom = slideY

        blurRect(boxLeft, boxTop, boxRight, boxBottom, 2F) {
            drawRoundedRect(boxLeft, boxTop, boxRight, boxBottom, -1, 2F)
            drawFilledTriangleMask(boxLeft + length / 2F - 5F, boxBottom, boxLeft + length / 2F + 5F, boxBottom + 5F)
        }
        drawRoundedRect(boxLeft, boxTop, boxRight, boxBottom, black, 2F)
        drawFilledTriangle(boxLeft + length / 2F - 5F, boxBottom, boxLeft + length / 2F + 5F, boxBottom + 5F, boxLeft + length / 2F, boxBottom + 5F, black)

        numFont.drawString(num, boxLeft + 1F, boxTop + 2F, numColor, false)
        labelFont.drawString(label, boxLeft + 1F + numFont.getStringWidth(num), boxTop + 3.5F, labelColor, false)

        return Border(boxLeft, boxTop, boxRight, boxBottom + 5F)
    }

    private fun drawFilledTriangleMask(left: Float, top: Float, right: Float, bottom: Float) {
        val mid = (left + right) / 2F
        GL11.glBegin(GL11.GL_TRIANGLES)
        GL11.glVertex2f(left, top)
        GL11.glVertex2f(right, top)
        GL11.glVertex2f(mid, bottom)
        GL11.glEnd()
    }

    // ===== Hanabi 样式（迁移自 Hanabi HUD#renderBlockCount：胶囊卡片 + 上方箭头指针 + 下滑淡入） =====

    private fun drawHanabi(count: Int, state: Boolean): Border? {
        animAlpha = approach(animAlpha, if (state) 0.7F else 0F, 10F)
        slideY = approachY(slideY, if (state) 0F else 10F)
        if (animAlpha <= 0.15F && !inDesigner) return null

        val numFont = Fonts.fontSemibold40
        val labelFont = Fonts.fontSemibold35

        val num = count.toString()
        val label = "block" + (if (count > 1) "s" else "")
        val length = (numFont.getStringWidth("$num  ") + labelFont.getStringWidth(label) + 1).toFloat()
        val black = reAlpha(Color.BLACK, (animAlpha * 255).toInt()).rgb
        val numColor = lowWarnMix(reAlpha(Color.WHITE, (animAlpha * 255).toInt()).rgb, count)
        val labelColor = reAlpha(Color.WHITE, ((animAlpha - 0.1F).coerceAtLeast(0F) * 255).toInt()).rgb

        val boxLeft = -length / 2F
        val boxTop = slideY
        val boxRight = length / 2F
        val boxBottom = slideY + 15F

        blurRect(boxLeft, boxTop - 5F, boxRight, boxBottom, 2F) {
            drawRoundedRect(boxLeft, boxTop, boxRight, boxBottom, -1, 2F)
            drawFilledTriangleMask(boxLeft + length / 2F - 5F, boxTop - 5F, boxLeft + length / 2F + 5F, boxTop)
        }
        drawRoundedRect(boxLeft, boxTop, boxRight, boxBottom, black, 2F)
        drawFilledTriangle(boxLeft + length / 2F - 5F, boxTop - 5F, boxLeft + length / 2F + 5F, boxTop - 5F, boxLeft + length / 2F, boxTop, black)

        numFont.drawString(num, boxLeft + 2F, boxTop + 3F, numColor, false)
        labelFont.drawString(label, boxLeft + 2.5F + numFont.getStringWidth("$num  "), boxTop + 3.5F, labelColor, false)

        return Border(boxLeft, boxTop - 5F, boxRight, boxBottom)
    }

    // ===== Rise 样式（迁移自 Rise 计数 HUD：圆角卡片 + 主题强调色 + blur/glow） =====

    private fun drawRise(count: Int, state: Boolean): Border? {
        animAlpha = approach(animAlpha, if (state) 1F else 0F, 12F)
        if (animAlpha <= 0.03F && !inDesigner) return null

        val fontRenderer = fontValue.get()
        val num = count.toString()
        val label = " Blocks"
        val numWidth = fontRenderer.getStringWidth(num).toFloat()
        val length = numWidth + fontRenderer.getStringWidth(label)
        val height = fontRenderer.heightF() + 8F

        val boxLeft = -length / 2F - 6F
        val boxTop = -height / 2F
        val boxRight = length / 2F + 6F
        val boxBottom = height / 2F

        val accent = withA(accentColors.color().rgb, animAlpha)
        val white = withA(Color.WHITE.rgb, animAlpha)
        val bg = withA(bgColors.color().rgb, animAlpha)

        blurRect(boxLeft, boxTop, boxRight, boxBottom, 3F)
        drawRoundedRect(boxLeft, boxTop, boxRight, boxBottom, bg, 3F)

        val textY = boxTop + (height - fontRenderer.heightF()) / 2F + 1F
        fontRenderer.drawString(num, -length / 2F, textY, lowWarnMix(accent, count), shadow)
        fontRenderer.drawString(label, -length / 2F + numWidth, textY, white, shadow)

        return Border(boxLeft, boxTop, boxRight, boxBottom)
    }

    // ===== Leader 样式（迁移自 Leader Scaffold#renderBlockCounter：图标徽章卡片 + 状态色 + 进度条） =====

    private fun drawLeader(count: Int, state: Boolean): Border? {
        animAlpha = approach(animAlpha, if (state) 1F else 0F, 12F)
        if (animAlpha <= 0.03F && !inDesigner) return null

        // 计数归零时重置最大值（与 Leader 原实现一致）
        if (count <= 0) counterMax = 0
        counterMax = max(counterMax, count)
        animPercent = approach(animPercent, if (counterMax > 0) count.toFloat() / counterMax else 0F, 30F)

        val pad = 6F
        val icon = 16F
        val radius = 6F

        val countFont = Fonts.fontSemibold40
        val labelFont = Fonts.fontSemibold35

        val countText = count.toString()
        val labelText = if (count == 1) "block" else "blocks"

        // 状态色：低方块红 -> 中等橙 -> 其余主题色（Low-Warning 开启时统一改为 32 起的渐变红）
        val stateColor = if (lowWarn) lowWarnMix(accentColors.color().rgb, count) else when {
            count <= 16 -> Color(255, 84, 84).rgb
            count <= 48 -> Color(255, 176, 64).rgb
            else -> accentColors.color().rgb
        }

        val countW = countFont.getStringWidth(countText).toFloat()
        val labelW = labelFont.getStringWidth(labelText).toFloat()
        val width = max(84F, pad + icon + 6F + countW + 3F + labelW + pad)
        val height = pad + max(icon, countFont.heightF() + 4F) + 4F + 2F + pad

        val bg = withA(bgColors.color().rgb, animAlpha)
        val countColor = lowWarnMix(withA(Color(244, 247, 252).rgb, animAlpha), count)
        val labelColor = withA(Color(160, 166, 180).rgb, animAlpha)

        blurRect(0F, 0F, width, height, radius)
        drawRoundedRect(0F, 0F, width, height, bg, radius)

        // 图标徽章
        drawRoundedRect(pad, pad, pad + icon, pad + icon, withA(stateColor, animAlpha * 36F / 255F), 4F)
        displayStack()?.let { drawItemIcon(it, pad, pad) }

        // 数量 + 单位
        val textY = pad + (height - pad * 2F - 6F - countFont.heightF()) / 2F + 1F
        countFont.drawString(countText, pad + icon + 6F, textY, countColor, false)
        labelFont.drawString(labelText, pad + icon + 6F + countW + 3F, textY + 2F, labelColor, false)

        // 进度条
        val trackY = height - pad / 2F - 2F
        drawRoundedRect(pad, trackY, width - pad, trackY + 2F, withA(Color.WHITE.rgb, animAlpha * 28F / 255F), 1F)
        if (animPercent > 0.01F) {
            val fillW = (width - pad * 2F) * animPercent.coerceIn(0F, 1F)
            drawRoundedRect(pad, trackY, pad + fillW, trackY + 2F, withA(stateColor, animAlpha * 240F / 255F), 1F)
        }

        return Border(0F, 0F, width, height)
    }

    // ===== LB 样式（按 Text 元素 showBlock + %blockcounter% 的逻辑拼凑：物品图标 + 数量文本） =====

    private fun drawLB(count: Int, state: Boolean): Border? {
        animAlpha = approach(animAlpha, if (state) 1F else 0F, 12F)
        if (animAlpha <= 0.03F && !inDesigner) return null

        val fontRenderer = fontValue.get()
        val stack = displayStack()
        val shouldRender = stack != null
        val text = count.toString()

        val textWidth = fontRenderer.getStringWidth(text)
        val fontHeight = fontRenderer.heightF()

        val rectLeft = if (shouldRender) -20F else -4F
        val rectTop = -3F
        val rectRight = if (shouldRender) textWidth + 6F else textWidth + 8F
        val rectBottom = fontHeight + 4F

        val bg = withA(bgColors.color().rgb, animAlpha)
        val textColor = lowWarnMix(withA(Color.WHITE.rgb, animAlpha), count)

        blurRect(rectLeft, rectTop, rectRight, rectBottom, 3F)
        drawRoundedRect(rectLeft, rectTop, rectRight, rectBottom, bg, 3F)

        if (shouldRender) {
            drawItemIcon(stack, -18F, -4F)
        }

        fontRenderer.drawString(text, 0F, 0F, textColor, shadow)

        return Border(rectLeft, rectTop, rectRight, rectBottom)
    }
}
