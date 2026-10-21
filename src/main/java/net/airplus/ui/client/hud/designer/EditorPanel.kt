/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.ui.client.hud.designer

import net.airplus.config.*
import net.airplus.file.FileManager.hudConfig
import net.airplus.file.FileManager.saveConfig
import net.airplus.file.FileManager.valuesConfig
import net.airplus.file.HudPresets
import net.airplus.ui.client.clickgui.ClickGui
import net.airplus.ui.client.hud.HUD
import net.airplus.ui.client.hud.HUD.ELEMENTS
import net.airplus.ui.client.hud.element.Element
import net.airplus.ui.client.hud.element.Side
import net.airplus.ui.font.Fonts.fontSemibold35
import net.airplus.utils.client.ClientThemesUtils
import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.extensions.lerpWith
import net.airplus.utils.render.ColorUtils
import net.airplus.utils.render.ColorUtils.blendColors
import net.airplus.utils.render.ColorUtils.withAlpha
import net.airplus.utils.render.HudBlur
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.render.RenderUtils.drawBorderedRect
import net.airplus.utils.render.RenderUtils.drawRect
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.airplus.utils.render.RenderUtils.drawTexture
import net.airplus.utils.render.RenderUtils.makeScissorBox
import net.airplus.utils.render.RenderUtils.updateTextureCache
import net.airplus.utils.render.animation.AnimationUtil
import net.airplus.utils.timing.WaitTickUtils
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.util.MathHelper
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.GL11.*
import java.awt.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * HUD 编辑器：顶部浮动工具条（添加 / 重置 / 保存）+ 元素设置弹出卡片。
 *
 * - 点击 HUD 元素后在其旁边弹出设置卡片（弹出缩放动画 + blur 背景）
 * - 卡片内容超出屏幕高度时平滑滚动
 * - 工具条可拖动
 */
class EditorPanel(private val hudDesigner: GuiHudDesigner, var x: Int, var y: Int) : MinecraftInstance {

    companion object {
        private const val TOOLBAR_HEIGHT = 22F
        private const val CARD_RADIUS = 8F
        private const val TOOLBAR_RADIUS = 8F
        private const val HEADER_HEIGHT = 18F
        private const val ROW_HEIGHT = 14F
        private const val BLUR_STRENGTH = 8F
        private const val MAX_CARD_H = 200F
    }

    // ---------------- 布局状态（供命中测试） ----------------
    private var toolbarW = 0F
    private var cardX = 0F
    private var cardY = 0F
    private var cardW = 120F
    private var cardH = 0F
    private var addX = 0F
    private var addY = 0F
    private var addW = 0F
    private var addH = 0F
    private var confirmX = 0F
    private var confirmY = 0F
    private var confirmW = 0F
    private var confirmH = 0F

    // ---------------- 动画状态 ----------------
    private var toolbarAnim = 0F
    private var positioned = false

    private var selected: Element? = null
    private var cardAnim = 0F
    private var contentH = 140F

    private var scroll = 0F
    private var scrollTarget = 0F

    private var addAnim = 0F
    private var addScroll = 0F
    private var addScrollTarget = 0F
    private var addContentH = 0F

    private var confirmAnim = 0F
    private var showConfirmation = false

    private var saveToast = 0F
    private var toastText = "Saved!"

    // ---------------- Save / Load 预设对话框 ----------------
    private var showSaveDialog = false
    private var saveName = ""
    private var showLoadDialog = false
    private var loadScroll = 0F
    private var loadScrollTarget = 0F
    private var dlgAnim = 0F
    private var dlgX = 0F
    private var dlgY = 0F
    private var dlgW = 0F
    private var dlgH = 0F

    // ---------------- 交互状态 ----------------
    private var drag = false
    private var dragX = 0
    private var dragY = 0

    private var mouseDown = false
    private var rightMouseDown = false

    /** “添加”列表是否展开（保留旧 API 名，GuiHudDesigner 的 ESC / 点击空白会关闭它） */
    var create = false

    // ------------------------------------------------------------------
    // 命中测试：GuiHudDesigner 用来判断点击是否落在编辑器 UI 上
    // ------------------------------------------------------------------
    fun hitTest(mouseX: Int, mouseY: Int): Boolean {
        if (mouseX >= x && mouseX <= x + toolbarW && mouseY >= y && mouseY <= y + TOOLBAR_HEIGHT) return true
        if (create && addAnim > 0.05F && mouseX >= addX && mouseX <= addX + addW && mouseY >= addY && mouseY <= addY + addH) return true
        if (selected != null && cardAnim > 0.05F && mouseX >= cardX && mouseX <= cardX + cardW && mouseY >= cardY && mouseY <= cardY + cardH) return true
        if (showConfirmation && mouseX >= confirmX && mouseX <= confirmX + confirmW && mouseY >= confirmY && mouseY <= confirmY + confirmH) return true
        if ((showSaveDialog || showLoadDialog) && dlgAnim > 0.05F && mouseX >= dlgX && mouseX <= dlgX + dlgW && mouseY >= dlgY && mouseY <= dlgY + dlgH) return true
        return false
    }

    fun drawPanel(mouseX: Int, mouseY: Int, wheel: Int) {
        val element = hudDesigner.selectedElement
        if (element != selected) {
            selected = element
            cardAnim = 0F
            scroll = 0F
            scrollTarget = 0F
            contentH = 140F
        }
        // 注意：不要在 element == null 时强制关闭 add 列表，
        // 否则未选中元素时点击 Add 会被立即重置（点空白 / ESC 已负责关闭）
        drawToolbar(mouseX, mouseY)

        if (create) drawAddList(mouseX, mouseY, wheel)
        else addAnim = 0F

        if (selected != null) drawCard(mouseX, mouseY, wheel)
        else cardAnim = 0F

        if (showConfirmation) drawConfirm(mouseX, mouseY)
        else confirmAnim = 0F

        if (showSaveDialog) drawSaveDialog(mouseX, mouseY)
        if (showLoadDialog) drawLoadDialog(mouseX, mouseY, wheel)
        if (showSaveDialog || showLoadDialog) dlgAnim = AnimationUtil.base(dlgAnim.toDouble(), 1.0, 0.4).toFloat()
        else dlgAnim = 0F

        drawSaveToast()

        // 动画推进（与代码库其余部分一致，按帧插值）
        toolbarAnim = AnimationUtil.base(toolbarAnim.toDouble(), 1.0, 0.4).toFloat()
        if (selected != null) cardAnim = AnimationUtil.base(cardAnim.toDouble(), 1.0, 0.4).toFloat()
        if (create) addAnim = AnimationUtil.base(addAnim.toDouble(), 1.0, 0.4).toFloat()
        if (showConfirmation) confirmAnim = AnimationUtil.base(confirmAnim.toDouble(), 1.0, 0.4).toFloat()
        if (saveToast > 0F) saveToast = (saveToast - RenderUtils.deltaTime / 500F).coerceAtLeast(0F)

        // 保存鼠标状态，供下一帧边沿检测
        mouseDown = Mouse.isButtonDown(0)
        rightMouseDown = Mouse.isButtonDown(1)
    }

    // ==================================================================
    // 顶部工具条
    // ==================================================================
    private fun drawToolbar(mouseX: Int, mouseY: Int) {
        val buttons = listOf("Add", "Reset", "Save", "Load")
        val pad = 10F
        val gap = 6F
        var w = pad * 2
        val buttonWidths = buttons.map { fontSemibold35.getStringWidth(it) + 12F }
        buttonWidths.forEach { w += it + gap }
        w -= gap
        toolbarW = w

        // 首帧按实际宽度居中到屏幕顶部（避免用硬编码宽度导致偏移）
        if (!positioned) {
            val sr0 = ScaledResolution(mc)
            x = (sr0.scaledWidth - w.roundToInt()) / 2
            y = 6
            positioned = true
        }

        // 计算按钮区域（用于拖动时排除按钮）
        val buttonRects = ArrayList<Pair<Float, Float>>(buttons.size) // x1..x2
        var bx0 = x + pad
        buttonWidths.forEach { bw ->
            buttonRects += bx0 to (bx0 + bw)
            bx0 += bw + gap
        }
        val onButton = buttonRects.any { (b1, b2) -> mouseX >= b1 && mouseX <= b2 && mouseY >= y && mouseY <= y + TOOLBAR_HEIGHT }

        // 拖动工具条（按住按钮时除外）
        if (Mouse.isButtonDown(0) && !mouseDown && !onButton && mouseY >= y && mouseY <= y + TOOLBAR_HEIGHT && mouseX >= x && mouseX <= x + toolbarW) {
            drag = true
            dragX = mouseX - x
            dragY = mouseY - y
        }
        if (Mouse.isButtonDown(0) && drag) {
            x = mouseX - dragX
            y = mouseY - dragY
        } else drag = false

        val sr = ScaledResolution(mc)
        val fx = x.coerceIn(2, sr.scaledWidth - toolbarW.toInt() - 2)
        val fy = y.coerceIn(2, sr.scaledHeight - TOOLBAR_HEIGHT.toInt() - 2)
        if (fx != x || fy != y) {
            x = fx
            y = fy
        }

        val slide = (1F - toolbarAnim) * -14F
        if (toolbarAnim > 0.01F) {
            // blur 背景（在弹出变换外绘制，避免遮罩错位）
            HudBlur.blur(x.toFloat(), y.toFloat(), x + toolbarW, y + TOOLBAR_HEIGHT, BLUR_STRENGTH * toolbarAnim, "InternalBlur") {
                drawRoundedRect(x.toFloat(), y.toFloat(), x + toolbarW, y + TOOLBAR_HEIGHT, -1, TOOLBAR_RADIUS)
            }

            glPushMatrix()
            glTranslatef(0F, slide, 0F)

            drawRoundedRect(
                x.toFloat(), y.toFloat(), x + toolbarW, y + TOOLBAR_HEIGHT,
                Color(15, 15, 20, (185 * toolbarAnim).toInt()).rgb, TOOLBAR_RADIUS
            )

            var bx = x + pad
            buttonWidths.forEachIndexed { index, bw ->
                val by = y + (TOOLBAR_HEIGHT - 14F) / 2F
                val hovered = mouseX >= bx && mouseX <= bx + bw && mouseY >= y && mouseY <= y + TOOLBAR_HEIGHT
                val accent = ClientThemesUtils.getColor()

                drawRoundedRect(
                    bx, by, bx + bw, by + 14F,
                    when {
                        hovered -> Color(accent.red, accent.green, accent.blue, 70).rgb
                        else -> Color(255, 255, 255, 22).rgb
                    }, 5F
                )
                fontSemibold35.drawString(buttons[index], bx + 6F, by + 4F, Color.WHITE.rgb, true)

                if (hovered && Mouse.isButtonDown(0) && !mouseDown) {
                    when (index) {
                        0 -> create = !create
                        1 -> showConfirmation = true
                        2 -> {
                            // Save：弹出自定义命名对话框
                            showSaveDialog = true
                            showLoadDialog = false
                            saveName = ""
                        }
                        3 -> {
                            // Load：弹出预设列表
                            showLoadDialog = true
                            showSaveDialog = false
                            loadScrollTarget = 0F
                        }
                    }
                    ClickGui.style.clickSound()
                }

                bx += bw + gap
            }
            glPopMatrix()
        }
    }

    // ==================================================================
    // “添加元素”列表
    // ==================================================================
    private fun drawAddList(mouseX: Int, mouseY: Int, wheel: Int) {
        val sr = ScaledResolution(mc)

        // 计算行高（Add 列表按显示名 A-Z 排序；不改变 ELEMENTS 注册顺序，存档/事件顺序不受影响）
        val rows = ELEMENTS.entries
            .filter { !(it.value.single && HUD.elements.any { e -> e.javaClass == it.key }) }
            .sortedBy { it.value.name.lowercase() }
        val rowH = 13F
        addContentH = rows.size * rowH + 20F
        val viewH = min(addContentH, sr.scaledHeight - addY - 16F)

        addW = 100F
        rows.forEach { addW = max(addW, fontSemibold35.getStringWidth(it.value.name) + 18F) }
        addX = x.toFloat()
        addY = y + TOOLBAR_HEIGHT + 4F
        addH = min(viewH, 200F)

        // 滚动
        if (wheel != 0 && mouseX >= addX && mouseX <= addX + addW && mouseY >= addY && mouseY <= addY + addH) {
            addScrollTarget += if (wheel > 0) 24F else -24F
        }
        addScrollTarget = addScrollTarget.coerceIn(-(addContentH - addH).coerceAtLeast(0F), 0F)
        addScroll = AnimationUtil.base(addScroll.toDouble(), addScrollTarget.toDouble(), 0.4).toFloat()

        val pop = easeOutBack(addAnim.coerceIn(0F, 1F))
        if (addAnim > 0.01F) {
            // blur 背景（不参与弹出变换，避免遮罩错位）
            HudBlur.blur(addX, addY, addX + addW, addY + addH, BLUR_STRENGTH * addAnim, "InternalBlur") {
                drawRoundedRect(addX, addY, addX + addW, addY + addH, -1, CARD_RADIUS)
            }

            glPushMatrix()
            val cx = addX + addW / 2F
            val cy = addY + addH / 2F + (1F - pop) * 8F
            glTranslatef(cx, cy, 0F)
            glScalef(0.92F + 0.08F * pop, 0.92F + 0.08F * pop, 1F)
            glTranslatef(-cx, -(addY + addH / 2F), 0F)

            drawRoundedRect(addX, addY, addX + addW, addY + addH, Color(15, 15, 20, (200 * addAnim).toInt()).rgb, CARD_RADIUS)

            // 标题
            fontSemibold35.drawString("§lAdd Element", addX + 6F, addY + 5F, Color.WHITE.rgb, true)
            val contentTop = addY + HEADER_HEIGHT

            // 内容区裁剪 + 滚动
            glEnable(GL_SCISSOR_TEST)
            makeScissorBox(addX, contentTop, addX + addW, addY + addH)

            val my = if (mouseY >= contentTop && mouseY <= addY + addH) mouseY else -999
            var rowY = contentTop + 2F + addScroll

            rows.forEach { (clazz, info) ->
                val hovered = mouseX >= addX && mouseX <= addX + addW && my >= rowY && my <= rowY + rowH
                if (hovered) {
                    val accent = ClientThemesUtils.getColor()
                    drawRoundedRect(addX + 3F, rowY, addX + addW - 3F, rowY + rowH - 1F, Color(accent.red, accent.green, accent.blue, 60).rgb, 4F)
                }
                fontSemibold35.drawString(
                    info.name, addX + 7F, rowY + 3F,
                    if (hovered) Color.WHITE.rgb else Color(200, 200, 200).rgb,
                    true
                )

                if (hovered && Mouse.isButtonDown(0) && !mouseDown) {
                    try {
                        val newElement = clazz.newInstance()
                        if (newElement.createElement()) HUD.addElement(newElement)
                    } catch (e: InstantiationException) {
                        e.printStackTrace()
                    } catch (e: IllegalAccessException) {
                        e.printStackTrace()
                    }
                    create = false
                }

                rowY += rowH
            }

            glDisable(GL_SCISSOR_TEST)
            glPopMatrix()
        }
    }

    // ==================================================================
    // 元素设置卡片
    // ==================================================================
    private fun drawCard(mouseX: Int, mouseY: Int, wheel: Int) {
        val element = selected ?: return
        val sr = ScaledResolution(mc)

        // 依据元素边框将卡片放置在其旁边
        val border = element.border
        var anchorLeft = element.renderX.toFloat()
        var anchorRight = element.renderX.toFloat()
        var anchorTop = element.renderY.toFloat()
        if (border != null) {
            anchorLeft = (element.scale * (minOf(border.x, border.x2) + element.renderX)).toFloat()
            anchorRight = (element.scale * (maxOf(border.x, border.x2) + element.renderX)).toFloat()
            anchorTop = (element.scale * (minOf(border.y, border.y2) + element.renderY)).toFloat()
        }

        cardW = 128F
        cardX = anchorRight + 8F
        if (cardX + cardW > sr.scaledWidth - 4F) {
            cardX = (anchorLeft - 8F - cardW).coerceAtLeast(4F)
        }

        // 先按内容算出期望高度（有上限），再据此垂直定位：
        // 元素在屏幕下方时把卡片整体上移，避免被屏幕底边裁掉
        val desiredH = min(contentH, MAX_CARD_H)
        cardY = anchorTop.coerceIn(4F, (sr.scaledHeight - desiredH - 6F).coerceAtLeast(4F))
        val maxViewH = sr.scaledHeight - cardY - 6F
        cardH = min(desiredH, maxViewH)

        // 滚动
        val contentTop = cardY + HEADER_HEIGHT + 2F
        if (wheel != 0 && mouseX >= cardX && mouseX <= cardX + cardW && mouseY >= cardY && mouseY <= cardY + cardH) {
            scrollTarget += if (wheel > 0) 32F else -32F
        }
        scrollTarget = scrollTarget.coerceIn(-max(contentH - cardH, 0F), 0F)
        scroll = AnimationUtil.base(scroll.toDouble(), scrollTarget.toDouble(), 0.4).toFloat()

        val pop = easeOutBack(cardAnim.coerceIn(0F, 1F))
        if (cardAnim <= 0.01F) return

        // blur 背景（不参与弹出变换）
        HudBlur.blur(cardX, cardY, cardX + cardW, cardY + cardH, BLUR_STRENGTH * cardAnim, "InternalBlur") {
            drawRoundedRect(cardX, cardY, cardX + cardW, cardY + cardH, -1, CARD_RADIUS)
        }

        glPushMatrix()
        val cx = cardX + cardW / 2F
        val cy = cardY + cardH / 2F + (1F - pop) * 10F
        glTranslatef(cx, cy, 0F)
        glScalef(0.94F + 0.06F * pop, 0.94F + 0.06F * pop, 1F)
        glTranslatef(-cx, -(cardY + cardH / 2F), 0F)

        drawRoundedRect(cardX, cardY, cardX + cardW, cardY + cardH, Color(15, 15, 20, (205 * cardAnim).toInt()).rgb, CARD_RADIUS)

        // 顶栏
        val accent = ClientThemesUtils.getColor()
        drawRoundedRect(cardX, cardY, cardX + cardW, cardY + HEADER_HEIGHT, Color(accent.red, accent.green, accent.blue, 60).rgb, CARD_RADIUS, RenderUtils.RoundedCorners.TOP_ONLY)
        drawRect(cardX, cardY + HEADER_HEIGHT - 1F, cardX + cardW, cardY + HEADER_HEIGHT, Color(accent.red, accent.green, accent.blue, 120).rgb)
        fontSemibold35.drawString("§l${element.name}", cardX + 6F, cardY + 5F, Color.WHITE.rgb, true)

        // 删除按钮
        if (!element.info.force) {
            val deleteText = "§lDelete"
            val deleteW = fontSemibold35.getStringWidth(deleteText)
            val deleteX = cardX + cardW - deleteW - 6F
            fontSemibold35.drawString(deleteText, deleteX, cardY + 5F, Color(255, 90, 90).rgb, true)
            if (Mouse.isButtonDown(0) && !mouseDown && mouseX >= deleteX && mouseX <= deleteX + deleteW && mouseY >= cardY && mouseY <= cardY + HEADER_HEIGHT) {
                HUD.removeElement(hudDesigner, element)
            }
        }

        // 内容区（裁剪 + 平滑滚动）
        glEnable(GL_SCISSOR_TEST)
        makeScissorBox(cardX, cardY + HEADER_HEIGHT, cardX + cardW, cardY + cardH - 1F)

        // 光标在视口外时禁用行内交互
        val interactive = mouseY >= cardY + HEADER_HEIGHT && mouseY <= cardY + cardH - 1F
        drawCardContent(element, mouseX, if (interactive) mouseY else -999)

        glDisable(GL_SCISSOR_TEST)
        glPopMatrix()
    }

    // ==================================================================
    // 卡片内容：信息 + 各类型 Value 控件
    // ==================================================================
    private fun drawCardContent(element: Element, mouseX: Int, mouseY: Int) {
        var cy = cardY + HEADER_HEIGHT + 4F + scroll
        var w = cardW
        val accent = ClientThemesUtils.getColor()
        val leftClick = Mouse.isButtonDown(0) && !mouseDown
        val rightClick = Mouse.isButtonDown(1) && !rightMouseDown

        fun row(h: Float = ROW_HEIGHT) {
            cy += h
        }

        // ---- 坐标 / 缩放信息 ----
        run {
            val text = "X ${"%.1f".format(element.renderX)}  Y ${"%.1f".format(element.renderY)}"
            fontSemibold35.drawString(text, cardX + 7F, cy + 2F, Color(160, 160, 160).rgb, true)
            w = max(w, fontSemibold35.getStringWidth(text) + 16F)
            row()
        }
        run {
            val text = "Scale ${"%.2f".format(element.scale)}"
            fontSemibold35.drawString(text, cardX + 7F, cy + 2F, Color(160, 160, 160).rgb, true)
            row()
        }

        // ---- 对齐边（点击切换） ----
        run {
            val text = "H ${element.side.horizontal.sideName}"
            fontSemibold35.drawString(text, cardX + 7F, cy + 2F, Color.WHITE.rgb, true)
            fontSemibold35.drawString("click", cardX + cardW - 30F, cy + 2F, Color(110, 110, 110).rgb)
            if (leftClick && mouseY >= cy && mouseY <= cy + ROW_HEIGHT && mouseX >= cardX && mouseX <= cardX + cardW) {
                val values = Side.Horizontal.entries.toTypedArray()
                val currIndex = values.indexOf(element.side.horizontal)
                val rx = element.renderX
                element.side.horizontal = values[(currIndex + 1) % values.size]
                element.x = when (element.side.horizontal) {
                    Side.Horizontal.LEFT -> rx
                    Side.Horizontal.MIDDLE -> (ScaledResolution(mc).scaledWidth / 2) - rx
                    Side.Horizontal.RIGHT -> ScaledResolution(mc).scaledWidth - rx
                }
                ClickGui.style.clickSound()
            }
            row()
        }
        run {
            val text = "V ${element.side.vertical.sideName}"
            fontSemibold35.drawString(text, cardX + 7F, cy + 2F, Color.WHITE.rgb, true)
            fontSemibold35.drawString("click", cardX + cardW - 30F, cy + 2F, Color(110, 110, 110).rgb)
            if (leftClick && mouseY >= cy && mouseY <= cy + ROW_HEIGHT && mouseX >= cardX && mouseX <= cardX + cardW) {
                val values = Side.Vertical.entries.toTypedArray()
                val currIndex = values.indexOf(element.side.vertical)
                val ry = element.renderY
                element.side.vertical = values[(currIndex + 1) % values.size]
                element.y = when (element.side.vertical) {
                    Side.Vertical.UP -> ry
                    Side.Vertical.MIDDLE -> (ScaledResolution(mc).scaledHeight / 2) - ry
                    Side.Vertical.DOWN -> ScaledResolution(mc).scaledHeight - ry
                }
                ClickGui.style.clickSound()
            }
            row()
        }

        drawRect(cardX + 6F, cy + 2F, cardX + cardW - 6F, cy + 3F, Color(255, 255, 255, 24).rgb)
        row(6F)

        // ---- Values ----
        for (value in element.values) {
            if (!value.isSupported()) continue

            when (value) {
                is BoolValue -> {
                    val stringWidth = fontSemibold35.getStringWidth(value.name)
                    w = max(w, stringWidth + 40F)
                    fontSemibold35.drawString(
                        value.name, cardX + 7F, cy + 3F,
                        if (value.get()) Color.WHITE.rgb else Color(130, 130, 130).rgb, true
                    )

                    // 开关
                    val toggleW = 18F
                    val toggleH = 9F
                    val toggleX = cardX + cardW - toggleW - 8F
                    val toggleY = cy + (ROW_HEIGHT - toggleH) / 2F
                    drawRoundedRect(
                        toggleX, toggleY, toggleX + toggleW, toggleY + toggleH,
                        if (value.get()) Color(accent.red, accent.green, accent.blue, 220).rgb else Color(60, 60, 65).rgb,
                        toggleH / 2F
                    )
                    val knobX = if (value.get()) toggleX + toggleW - toggleH else toggleX
                    drawRoundedRect(knobX, toggleY, knobX + toggleH, toggleY + toggleH, Color.WHITE.rgb, toggleH / 2F)

                    if (leftClick && mouseY >= cy && mouseY <= cy + ROW_HEIGHT && mouseX >= cardX && mouseX <= cardX + cardW) {
                        value.toggle()
                        element.updateElement()
                        ClickGui.style.clickSound()
                    }
                    row()
                }

                is FloatValue -> {
                    val current = value.get()
                    val minV = value.minimum
                    val maxV = value.maximum
                    val text = value.name
                    fontSemibold35.drawString(text, cardX + 7F, cy + 3F, Color.WHITE.rgb, true)
                    val valueText = "%.2f".format(current)
                    fontSemibold35.drawString(valueText, cardX + cardW - fontSemibold35.getStringWidth(valueText) - 8F, cy + 3F, Color(accent.red, accent.green, accent.blue).rgb, true)
                    w = max(w, fontSemibold35.getStringWidth(text) + 34F)

                    drawSliderTrack(cy + ROW_HEIGHT, current, minV, maxV, accent, mouseX, mouseY)

                    if (Mouse.isButtonDown(0) && mouseX >= cardX + 8 && mouseX <= cardX + cardW - 8 && mouseY >= cy + ROW_HEIGHT - 4 && mouseY <= cy + ROW_HEIGHT + 10) {
                        val curr = MathHelper.clamp_float((mouseX - cardX - 10F) / (cardW - 20F), 0F, 1F)
                        value.set(minV + (maxV - minV) * curr)
                        element.updateElement()
                    }
                    row(ROW_HEIGHT + 12F)
                }

                is IntValue -> {
                    val current = value.get()
                    val minV = value.minimum
                    val maxV = value.maximum
                    val text = value.name
                    fontSemibold35.drawString(text, cardX + 7F, cy + 3F, Color.WHITE.rgb, true)
                    val valueText = "$current"
                    fontSemibold35.drawString(valueText, cardX + cardW - fontSemibold35.getStringWidth(valueText) - 8F, cy + 3F, Color(accent.red, accent.green, accent.blue).rgb, true)
                    w = max(w, fontSemibold35.getStringWidth(text) + 34F)

                    drawSliderTrack(cy + ROW_HEIGHT, current.toFloat(), minV.toFloat(), maxV.toFloat(), accent, mouseX, mouseY)

                    if (Mouse.isButtonDown(0) && mouseX >= cardX + 8 && mouseX <= cardX + cardW - 8 && mouseY >= cy + ROW_HEIGHT - 4 && mouseY <= cy + ROW_HEIGHT + 10) {
                        val curr = MathHelper.clamp_float((mouseX - cardX - 10F) / (cardW - 20F), 0F, 1F)
                        value.set((minV + (maxV - minV) * curr).toInt())
                        element.updateElement()
                    }
                    row(ROW_HEIGHT + 12F)
                }

                is ListValue -> {
                    val stringWidth = fontSemibold35.getStringWidth(value.name)
                    w = max(w, stringWidth + 16F)
                    fontSemibold35.drawString(value.name, cardX + 7F, cy + 3F, Color(160, 160, 160).rgb, true)
                    row()

                    for (s in value.values) {
                        val isSelected = s == value.get()
                        val optionText = s
                        val optionW = fontSemibold35.getStringWidth(optionText)
                        w = max(w, optionW + 28F)

                        val hovered = mouseX >= cardX && mouseX <= cardX + cardW && mouseY >= cy && mouseY <= cy + ROW_HEIGHT
                        if (isSelected || hovered) {
                            drawRoundedRect(
                                cardX + 5F, cy + 1F, cardX + cardW - 5F, cy + ROW_HEIGHT - 1F,
                                if (isSelected) Color(accent.red, accent.green, accent.blue, 70).rgb else Color(255, 255, 255, 16).rgb,
                                4F
                            )
                        }
                        fontSemibold35.drawString(
                            optionText, cardX + 13F, cy + 3F,
                            if (isSelected) Color.WHITE.rgb else Color(150, 150, 150).rgb, true
                        )

                        if (hovered && leftClick) {
                            value.set(s)
                            element.updateElement()
                            ClickGui.style.clickSound()
                        }
                        row()
                    }
                }

                is FontValue -> {
                    val displayString = value.displayName
                    val stringWidth = fontSemibold35.getStringWidth(displayString)
                    w = max(w, stringWidth + 16F)
                    fontSemibold35.drawString(displayString, cardX + 7F, cy + 3F, Color.WHITE.rgb, true)

                    if ((leftClick || rightClick) && mouseX >= cardX && mouseX <= cardX + cardW && mouseY >= cy && mouseY <= cy + ROW_HEIGHT) {
                        if (Mouse.isButtonDown(0)) value.next() else value.previous()
                        element.updateElement()
                        ClickGui.style.clickSound()
                    }
                    row()
                }

                is ColorValue -> {
                    cy = drawColorValue(element, value, mouseX, mouseY, cy, { w2 -> w = max(w, w2) }, accent, leftClick, rightClick)
                }

                else -> {}
            }
        }

        // 记录内容总高度，供下一帧布局 / 滚动使用；宽度不允许超过 128（超出部分裁剪）
        contentH = (cy - scroll) - (cardY + HEADER_HEIGHT + 4F) + 6F
        cardW = w.coerceAtMost(128F)
    }

    /** 滑条轨道 + 主题色填充 + 圆形把手 */
    private fun drawSliderTrack(trackY: Float, current: Float, minV: Float, maxV: Float, accent: Color, mouseX: Int, mouseY: Int) {
        val trackX1 = cardX + 10F
        val trackX2 = cardX + cardW - 10F
        val trackH = 3.5F
        val trackY2 = trackY + 4F

        drawRoundedRect(trackX1, trackY2 - trackH, trackX2, trackY2, Color(255, 255, 255, 30).rgb, trackH / 2F)

        val progress = if (maxV > minV) ((current - minV) / (maxV - minV)).coerceIn(0F, 1F) else 0F
        val fillX = trackX1 + (trackX2 - trackX1) * progress
        drawRoundedRect(trackX1, trackY2 - trackH, fillX, trackY2, accent.rgb, trackH / 2F)

        val hovered = mouseX >= trackX1 && mouseX <= trackX2 && mouseY >= trackY2 - 8 && mouseY <= trackY2 + 8
        val knobR = if (hovered) 4F else 3F
        drawRoundedRect(fillX - knobR, trackY2 - knobR, fillX + knobR, trackY2 + knobR, Color.WHITE.rgb, knobR)
    }

    // ==================================================================
    // 颜色选择器（沿用原实现，坐标基于卡片）
    // ==================================================================
    private fun drawColorValue(
        element: Element,
        value: ColorValue,
        mouseX: Int,
        mouseY: Int,
        startCy: Float,
        updateWidth: (Float) -> Unit,
        accent: Color,
        leftClick: Boolean,
        rightClick: Boolean
    ): Float {
        var cy = startCy
        val currentColor = value.selectedColor()

        updateWidth(fontSemibold35.getStringWidth(value.name) + 30F)

        val spacing = 14

        val startX = cardX.roundToInt()
        val startY = cy.roundToInt()

        // 颜色预览
        val colorPreviewSize = 9
        val colorPreviewX2 = (cardX + cardW - 8F).roundToInt()
        val colorPreviewX1 = colorPreviewX2 - colorPreviewSize
        val colorPreviewY1 = startY + 1
        val colorPreviewY2 = colorPreviewY1 + colorPreviewSize

        val rainbowPreviewX2 = colorPreviewX1 - (colorPreviewSize / 1.5F).roundToInt()
        val rainbowPreviewX1 = rainbowPreviewX2 - colorPreviewSize

        val textX = startX + 2F
        val textY = startY + 3F

        // 滑条尺寸
        val hueSliderWidth = 7
        val hueSliderHeight = 50
        val colorPickerWidth = 75
        val colorPickerHeight = 50

        val spacingBetweenSliders = 5

        val colorPickerStartX = textX.roundToInt()
        val colorPickerEndX = colorPickerStartX + colorPickerWidth
        val colorPickerStartY = colorPreviewY2 + spacing / 3
        val colorPickerEndY = colorPickerStartY + colorPickerHeight

        val hueSliderStartY = colorPickerStartY
        val hueSliderEndY = colorPickerStartY + hueSliderHeight

        val hueSliderX = colorPickerEndX + spacingBetweenSliders

        val opacityStartX = hueSliderX + hueSliderWidth + spacingBetweenSliders
        val opacityEndX = opacityStartX + hueSliderWidth

        val rainbow = value.rainbow

        if (leftClick || rightClick) {
            val isColorPreview =
                mouseX >= colorPreviewX1 && mouseX <= colorPreviewX2 && mouseY >= colorPreviewY1 && mouseY <= colorPreviewY2
            val isRainbowPreview =
                mouseX >= rainbowPreviewX1 && mouseX <= rainbowPreviewX2 && mouseY >= colorPreviewY1 && mouseY <= colorPreviewY2

            when {
                isColorPreview -> {
                    if (leftClick && rainbow) value.rainbow = false
                    if (rightClick) value.showPicker = !value.showPicker
                    ClickGui.style.clickSound()
                    element.updateElement()
                }

                isRainbowPreview -> {
                    if (leftClick) value.rainbow = true
                    if (rightClick) value.showPicker = !value.showPicker
                    ClickGui.style.clickSound()
                    element.updateElement()
                }
            }
        }

        fontSemibold35.drawString(value.name, textX, textY, Color.WHITE.rgb)

        val normalBorderColor = if (rainbow) 0 else Color.BLUE.rgb
        val rainbowBorderColor = if (rainbow) Color.BLUE.rgb else 0

        val hue = if (rainbow) {
            Color.RGBtoHSB(currentColor.red, currentColor.green, currentColor.blue, null)[0]
        } else {
            value.hueSliderY
        }

        if (value.showPicker) {
            // 颜色选择器
            value.updateTextureCache(
                id = 0,
                hue = hue,
                width = colorPickerWidth,
                height = colorPickerHeight,
                generateImage = { image, _ ->
                    for (px in 0 until colorPickerWidth) {
                        for (py in 0 until colorPickerHeight) {
                            val localS = px / colorPickerWidth.toFloat()
                            val localB = 1.0f - (py / colorPickerHeight.toFloat())
                            val rgb = Color.HSBtoRGB(hue, localS, localB)
                            image.setRGB(px, py, rgb)
                        }
                    }
                },
                drawAt = { id ->
                    drawTexture(
                        id, colorPickerStartX, colorPickerStartY, colorPickerWidth, colorPickerHeight
                    )
                })

            val markerX = (colorPickerStartX..colorPickerEndX).lerpWith(value.colorPickerPos.x)
            val markerY = (colorPickerStartY..colorPickerEndY).lerpWith(value.colorPickerPos.y)

            if (!rainbow) {
                RenderUtils.drawBorder(
                    markerX - 2f, markerY - 2f, markerX + 3f, markerY + 3f, 1.5f, Color.WHITE.rgb
                )
            }

            // 色相滑条
            value.updateTextureCache(
                id = 1,
                hue = hue,
                width = hueSliderWidth,
                height = hueSliderHeight,
                generateImage = { image, _ ->
                    for (y in 0 until hueSliderHeight) {
                        for (x in 0 until hueSliderWidth) {
                            val localHue = y / hueSliderHeight.toFloat()
                            val rgb = Color.HSBtoRGB(localHue, 1.0f, 1.0f)
                            image.setRGB(x, y, rgb)
                        }
                    }
                },
                drawAt = { id ->
                    drawTexture(
                        id, hueSliderX, colorPickerStartY, hueSliderWidth, hueSliderHeight
                    )
                })

            // 不透明度滑条
            value.updateTextureCache(
                id = 2,
                hue = currentColor.rgb.toFloat(),
                width = hueSliderWidth,
                height = hueSliderHeight,
                generateImage = { image, _ ->
                    val gridSize = 1

                    for (y in 0 until hueSliderHeight) {
                        for (x in 0 until hueSliderWidth) {
                            val gridX = x / gridSize
                            val gridY = y / gridSize

                            val checkerboardColor = if ((gridY + gridX) % 2 == 0) {
                                Color.WHITE.rgb
                            } else {
                                Color.BLACK.rgb
                            }

                            val alpha = ((1 - y.toFloat() / hueSliderHeight.toFloat()) * 255).roundToInt()

                            val finalColor = blendColors(
                                Color(checkerboardColor), currentColor.withAlpha(alpha)
                            )

                            image.setRGB(x, y, finalColor.rgb)
                        }
                    }
                },
                drawAt = { id ->
                    drawTexture(
                        id, opacityStartX, colorPickerStartY, hueSliderWidth, hueSliderHeight
                    )
                })

            val opacityMarkerY = (hueSliderStartY..hueSliderEndY).lerpWith(1 - value.opacitySliderY)
            val hueMarkerY = (hueSliderStartY..hueSliderEndY).lerpWith(hue)

            RenderUtils.drawBorder(
                hueSliderX.toFloat() - 1,
                hueMarkerY - 1f,
                hueSliderX + hueSliderWidth + 1f,
                hueMarkerY + 1f,
                1.5f,
                Color.WHITE.rgb,
            )

            RenderUtils.drawBorder(
                opacityStartX.toFloat() - 1,
                opacityMarkerY - 1f,
                opacityEndX + 1f,
                opacityMarkerY + 1f,
                1.5f,
                Color.WHITE.rgb,
            )

            val inColorPicker =
                mouseX >= colorPickerStartX && mouseX < colorPickerEndX && mouseY >= colorPickerStartY && mouseY < colorPickerEndY && !rainbow
            val inHueSlider =
                mouseX >= hueSliderX - 1 && mouseX <= hueSliderX + hueSliderWidth + 1 && mouseY >= hueSliderStartY && mouseY < hueSliderEndY && !rainbow
            val inOpacitySlider =
                mouseX >= opacityStartX - 1 && mouseX <= opacityEndX + 1 && mouseY >= hueSliderStartY && mouseY < hueSliderEndY

            // 必须放在下面的 if 外面，确保鼠标按键状态按时更新
            val sliderType = value.lastChosenSlider

            if (leftClick && (inColorPicker || inHueSlider || inOpacitySlider) || value.lastChosenSlider != null) {
                if (inColorPicker && sliderType == null || sliderType == ColorValue.SliderType.COLOR) {
                    val newS = ((mouseX - colorPickerStartX) / colorPickerWidth.toFloat()).coerceIn(0f, 1f)
                    val newB = (1.0f - (mouseY - colorPickerStartY) / colorPickerHeight.toFloat()).coerceIn(0f, 1f)
                    value.colorPickerPos.x = newS
                    value.colorPickerPos.y = 1 - newB
                }

                var finalColor = Color(
                    Color.HSBtoRGB(
                        value.hueSliderY, value.colorPickerPos.x, 1 - value.colorPickerPos.y
                    )
                )

                if (inHueSlider && sliderType == null || sliderType == ColorValue.SliderType.HUE) {
                    value.hueSliderY = ((mouseY - hueSliderStartY) / hueSliderHeight.toFloat()).coerceIn(0f, 1f)

                    finalColor = Color(
                        Color.HSBtoRGB(
                            value.hueSliderY, value.colorPickerPos.x, 1 - value.colorPickerPos.y
                        )
                    )
                }

                if (inOpacitySlider && sliderType == null || sliderType == ColorValue.SliderType.OPACITY) {
                    value.opacitySliderY =
                        1 - ((mouseY - hueSliderStartY) / hueSliderHeight.toFloat()).coerceIn(0f, 1f)
                }

                finalColor = finalColor.withAlpha((value.opacitySliderY * 255).roundToInt())

                value.changeValue(finalColor)

                if (!WaitTickUtils.hasScheduled(this)) {
                    WaitTickUtils.conditionalSchedule(this, 10) {
                        (value.lastChosenSlider == null).also { if (it) saveConfig(valuesConfig) }
                    }
                }

                if (leftClick) {
                    value.lastChosenSlider = when {
                        inColorPicker && !rainbow -> ColorValue.SliderType.COLOR
                        inHueSlider && !rainbow -> ColorValue.SliderType.HUE
                        inOpacitySlider -> ColorValue.SliderType.OPACITY
                        else -> null
                    }
                }
            }

            val inc = colorPickerHeight + colorPreviewSize - 6

            cy += inc
        }

        drawBorderedRect(
            colorPreviewX1,
            colorPreviewY1,
            colorPreviewX2,
            colorPreviewY2,
            1.5f,
            normalBorderColor,
            value.get().rgb
        )

        drawBorderedRect(
            rainbowPreviewX1,
            colorPreviewY1,
            rainbowPreviewX2,
            colorPreviewY2,
            1.5f,
            rainbowBorderColor,
            ColorUtils.rainbow(alpha = value.opacitySliderY).rgb
        )

        cy += spacing
        return cy
    }

    // ==================================================================
    // Save 预设对话框：输入自定义名称
    // ==================================================================
    private fun drawSaveDialog(mouseX: Int, mouseY: Int) {
        val sr = ScaledResolution(mc)
        dlgW = 150F
        dlgH = 64F
        dlgX = sr.scaledWidth / 2F - dlgW / 2F
        dlgY = sr.scaledHeight / 2F - dlgH / 2F

        val pop = easeOutBack(dlgAnim.coerceIn(0F, 1F))
        if (dlgAnim <= 0.01F) return

        val clicked = Mouse.isButtonDown(0) && !mouseDown

        HudBlur.blur(dlgX, dlgY, dlgX + dlgW, dlgY + dlgH, BLUR_STRENGTH * dlgAnim, "InternalBlur") {
            drawRoundedRect(dlgX, dlgY, dlgX + dlgW, dlgY + dlgH, -1, CARD_RADIUS)
        }

        glPushMatrix()
        val cx = dlgX + dlgW / 2F
        val cy = dlgY + dlgH / 2F + (1F - pop) * 8F
        glTranslatef(cx, cy, 0F)
        glScalef(0.92F + 0.08F * pop, 0.92F + 0.08F * pop, 1F)
        glTranslatef(-cx, -(dlgY + dlgH / 2F), 0F)

        drawRoundedRect(dlgX, dlgY, dlgX + dlgW, dlgY + dlgH, Color(15, 15, 20, 215).rgb, CARD_RADIUS)
        fontSemibold35.drawString("§lSave Preset", dlgX + 8F, dlgY + 6F, Color.WHITE.rgb, true)

        // 名称输入框
        val fieldY = dlgY + 20F
        val fieldH = 14F
        drawRoundedRect(dlgX + 8F, fieldY, dlgX + dlgW - 8F, fieldY + fieldH, Color(255, 255, 255, 18).rgb, 4F)
        val cursor = if (System.currentTimeMillis() % 1000 < 500) "_" else ""
        fontSemibold35.drawString(
            if (saveName.isEmpty()) cursor.ifEmpty { "Name..." } else saveName + cursor,
            dlgX + 12F, fieldY + 4F,
            if (saveName.isEmpty()) Color(110, 110, 110).rgb else Color.WHITE.rgb, true
        )

        val buttonY = dlgY + dlgH - 20F
        val buttonH = 14F
        val buttonW = (dlgW - 20F) / 2F
        val accent = ClientThemesUtils.getColor()

        val okX = dlgX + 8F
        val okHovered = mouseX >= okX && mouseX <= okX + buttonW && mouseY >= buttonY && mouseY <= buttonY + buttonH
        drawRoundedRect(okX, buttonY, okX + buttonW, buttonY + buttonH, if (okHovered) accent.rgb else Color(accent.red, accent.green, accent.blue, 90).rgb, 5F)
        fontSemibold35.drawCenteredString("Save", okX + buttonW / 2F, buttonY + 4F, Color.WHITE.rgb, true)

        val cancelX = dlgX + dlgW - 8F - buttonW
        val cancelHovered = mouseX >= cancelX && mouseX <= cancelX + buttonW && mouseY >= buttonY && mouseY <= buttonY + buttonH
        drawRoundedRect(cancelX, buttonY, cancelX + buttonW, buttonY + buttonH, if (cancelHovered) Color(120, 120, 130).rgb else Color(120, 120, 130, 90).rgb, 5F)
        fontSemibold35.drawCenteredString("Cancel", cancelX + buttonW / 2F, buttonY + 4F, Color.WHITE.rgb, true)

        if (clicked) {
            when {
                okHovered -> confirmSave()
                cancelHovered -> showSaveDialog = false
                !(mouseX >= dlgX && mouseX <= dlgX + dlgW && mouseY >= dlgY && mouseY <= dlgY + dlgH) -> showSaveDialog = false
            }
        }
        glPopMatrix()
    }

    private fun confirmSave() {
        val name = saveName.trim()
        if (name.isEmpty()) return
        saveConfig(hudConfig)
        HudPresets.save(name)
        toastText = "Saved!"
        saveToast = 1F
        showSaveDialog = false
    }

    // ==================================================================
    // Load 预设对话框：列出已保存的 HUD 预设
    // ==================================================================
    private fun drawLoadDialog(mouseX: Int, mouseY: Int, wheel: Int) {
        val sr = ScaledResolution(mc)
        val presets = HudPresets.list()

        val rowH = 14F
        val headerH = 20F
        dlgW = 150F
        val listH = min(presets.size * rowH + 6F, 140F)
        dlgH = headerH + listH + 6F
        dlgX = sr.scaledWidth / 2F - dlgW / 2F
        dlgY = sr.scaledHeight / 2F - dlgH / 2F

        // 滚动
        val contentH = presets.size * rowH
        if (wheel != 0 && mouseX >= dlgX && mouseX <= dlgX + dlgW && mouseY >= dlgY && mouseY <= dlgY + dlgH) {
            loadScrollTarget += if (wheel > 0) 24F else -24F
        }
        loadScrollTarget = loadScrollTarget.coerceIn(-(contentH - listH).coerceAtLeast(0F), 0F)
        loadScroll = AnimationUtil.base(loadScroll.toDouble(), loadScrollTarget.toDouble(), 0.4).toFloat()

        val pop = easeOutBack(dlgAnim.coerceIn(0F, 1F))
        if (dlgAnim <= 0.01F) return

        val clicked = Mouse.isButtonDown(0) && !mouseDown
        val accent = ClientThemesUtils.getColor()

        HudBlur.blur(dlgX, dlgY, dlgX + dlgW, dlgY + dlgH, BLUR_STRENGTH * dlgAnim, "InternalBlur") {
            drawRoundedRect(dlgX, dlgY, dlgX + dlgW, dlgY + dlgH, -1, CARD_RADIUS)
        }

        glPushMatrix()
        val cx = dlgX + dlgW / 2F
        val cy = dlgY + dlgH / 2F + (1F - pop) * 8F
        glTranslatef(cx, cy, 0F)
        glScalef(0.92F + 0.08F * pop, 0.92F + 0.08F * pop, 1F)
        glTranslatef(-cx, -(dlgY + dlgH / 2F), 0F)

        drawRoundedRect(dlgX, dlgY, dlgX + dlgW, dlgY + dlgH, Color(15, 15, 20, 215).rgb, CARD_RADIUS)
        fontSemibold35.drawString("§lLoad Preset", dlgX + 8F, dlgY + 6F, Color.WHITE.rgb, true)

        if (presets.isEmpty()) {
            fontSemibold35.drawString("No presets saved", dlgX + 8F, dlgY + headerH + 4F, Color(130, 130, 130).rgb, true)
        } else {
            val listTop = dlgY + headerH
            glEnable(GL_SCISSOR_TEST)
            makeScissorBox(dlgX + 4F, listTop, dlgX + dlgW - 4F, dlgY + dlgH - 2F)

            var rowY = listTop + 2F + loadScroll
            for (name in presets) {
                val hovered = mouseX >= dlgX + 4F && mouseX <= dlgX + dlgW - 4F && mouseY >= rowY && mouseY <= rowY + rowH
                if (hovered) {
                    drawRoundedRect(dlgX + 4F, rowY, dlgX + dlgW - 4F, rowY + rowH - 1F, Color(accent.red, accent.green, accent.blue, 60).rgb, 4F)
                }
                fontSemibold35.drawString(name, dlgX + 9F, rowY + 3F, if (hovered) Color.WHITE.rgb else Color(200, 200, 200).rgb, true)

                // 行尾删除按钮（hover 行时显示）
                val delText = "x"
                val delW = fontSemibold35.getStringWidth(delText) + 8F
                val delX = dlgX + dlgW - delW - 6F
                val delHovered = hovered &&
                    mouseX >= delX && mouseX <= delX + delW && mouseY >= rowY && mouseY <= rowY + rowH - 1F
                if (hovered) {
                    if (delHovered) {
                        drawRoundedRect(delX, rowY + 1F, delX + delW, rowY + rowH - 2F, Color(255, 70, 70, 70).rgb, 3F)
                        fontSemibold35.drawCenteredString(delText, delX + delW / 2F, rowY + 3.5F, Color(255, 110, 110).rgb, true)
                    } else {
                        fontSemibold35.drawCenteredString(delText, delX + delW / 2F, rowY + 3.5F, Color(150, 150, 150).rgb, true)
                    }
                }

                if (hovered && clicked) {
                    if (delHovered) {
                        // 删除预设
                        if (HudPresets.delete(name)) {
                            toastText = "Deleted!"
                        } else {
                            toastText = "Delete failed"
                        }
                        saveToast = 1F
                    } else if (HudPresets.load(name)) {
                        toastText = "Loaded!"
                        saveToast = 1F
                        showLoadDialog = false
                        hudDesigner.selectedElement = null
                    } else {
                        toastText = "Load failed"
                        saveToast = 1F
                    }
                    ClickGui.style.clickSound()
                }
                rowY += rowH
            }

            glDisable(GL_SCISSOR_TEST)
        }

        if (clicked && !(mouseX >= dlgX && mouseX <= dlgX + dlgW && mouseY >= dlgY && mouseY <= dlgY + dlgH)) {
            showLoadDialog = false
        }
        glPopMatrix()
    }

    /**
     * 对话框打开时处理键盘输入，返回 true 表示已消费（GuiHudDesigner 不再处理）。
     */
    fun handleDialogKey(typedChar: Char, keyCode: Int): Boolean {
        if (showSaveDialog) {
            when (keyCode) {
                Keyboard.KEY_ESCAPE -> showSaveDialog = false
                Keyboard.KEY_RETURN -> confirmSave()
                Keyboard.KEY_BACK -> if (saveName.isNotEmpty()) saveName = saveName.dropLast(1)
                else -> if (typedChar >= ' ' && saveName.length < 24) saveName += typedChar
            }
            return true
        }
        if (showLoadDialog) {
            if (keyCode == Keyboard.KEY_ESCAPE) showLoadDialog = false
            return true
        }
        return false
    }

    // ==================================================================
    // 重置确认对话框
    // ==================================================================
    private fun drawConfirm(mouseX: Int, mouseY: Int) {
        val sr = ScaledResolution(mc)
        confirmW = 130F
        confirmH = 44F
        confirmX = sr.scaledWidth / 2F - confirmW / 2F
        confirmY = sr.scaledHeight / 2F - confirmH / 2F

        val pop = easeOutBack(confirmAnim.coerceIn(0F, 1F))
        if (confirmAnim <= 0.01F) return

        HudBlur.blur(confirmX, confirmY, confirmX + confirmW, confirmY + confirmH, BLUR_STRENGTH * confirmAnim, "InternalBlur") {
            drawRoundedRect(confirmX, confirmY, confirmX + confirmW, confirmY + confirmH, -1, CARD_RADIUS)
        }

        glPushMatrix()
        val cx = confirmX + confirmW / 2F
        val cy = confirmY + confirmH / 2F + (1F - pop) * 8F
        glTranslatef(cx, cy, 0F)
        glScalef(0.92F + 0.08F * pop, 0.92F + 0.08F * pop, 1F)
        glTranslatef(-cx, -(confirmY + confirmH / 2F), 0F)

        drawRoundedRect(confirmX, confirmY, confirmX + confirmW, confirmY + confirmH, Color(15, 15, 20, 210).rgb, CARD_RADIUS)

        val message = "Reset HUD to default?"
        fontSemibold35.drawCenteredString(message, cx, confirmY + 8F, Color.WHITE.rgb, true)

        val buttonY = confirmY + 24F
        val buttonH = 14F
        val buttonW = (confirmW - 18F) / 2F
        val accent = ClientThemesUtils.getColor()

        // Yes
        val yesX = confirmX + 6F
        val yesHovered = mouseX >= yesX && mouseX <= yesX + buttonW && mouseY >= buttonY && mouseY <= buttonY + buttonH
        drawRoundedRect(yesX, buttonY, yesX + buttonW, buttonY + buttonH, if (yesHovered) accent.rgb else Color(accent.red, accent.green, accent.blue, 90).rgb, 5F)
        fontSemibold35.drawCenteredString("Yes", yesX + buttonW / 2F, buttonY + 4F, Color.WHITE.rgb, true)

        // No
        val noX = confirmX + confirmW - 6F - buttonW
        val noHovered = mouseX >= noX && mouseX <= noX + buttonW && mouseY >= buttonY && mouseY <= buttonY + buttonH
        drawRoundedRect(noX, buttonY, noX + buttonW, buttonY + buttonH, if (noHovered) Color(200, 60, 60).rgb else Color(200, 60, 60, 90).rgb, 5F)
        fontSemibold35.drawCenteredString("No", noX + buttonW / 2F, buttonY + 4F, Color.WHITE.rgb, true)

        if (Mouse.isButtonDown(0) && !mouseDown) {
            if (yesHovered) {
                HUD.setDefault()
                showConfirmation = false
                create = false
            } else if (noHovered) {
                showConfirmation = false
            }
        }
        glPopMatrix()
    }

    // ==================================================================
    // 保存提示
    // ==================================================================
    private fun drawSaveToast() {
        if (saveToast <= 0F) return

        val alpha = (saveToast.coerceIn(0F, 1F) * 255).toInt()
        val text = toastText
        val textW = fontSemibold35.getStringWidth(text)
        val tx = x + toolbarW / 2F - textW / 2F
        val ty = y + TOOLBAR_HEIGHT + 4F + (1F - saveToast) * -6F

        fontSemibold35.drawString(text, tx, ty, Color(120, 255, 150, alpha).rgb, true)
    }

    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158F
        val c3 = c1 + 1F
        return 1F + c3 * (t - 1F) * (t - 1F) * (t - 1F) + c1 * (t - 1F) * (t - 1F)
    }
}
