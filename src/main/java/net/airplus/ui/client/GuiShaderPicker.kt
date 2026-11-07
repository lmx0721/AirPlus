/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Shader 背景选择器：网格展示全部内置主菜单 shader 背景的名称（无实时预览，避免多着色器
 * 同帧渲染拖低帧率），点击立即应用并返回设置界面。
 * 背景：全屏模糊 + 毛玻璃面板（与 Menu Settings 一致）。
 */
package net.airplus.ui.client

import net.airplus.file.FileManager
import net.airplus.file.FileManager.valuesConfig
import net.airplus.file.configs.models.ClientConfiguration
import net.airplus.ui.client.mainmenu.MainMenuStyles
import net.airplus.ui.font.Fonts
import net.airplus.utils.render.HudBlur
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.render.shader.MenuShaderBackground
import net.airplus.utils.ui.AbstractScreen
import net.minecraft.client.gui.ScaledResolution
import org.lwjgl.input.Mouse
import java.awt.Color
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

class GuiShaderPicker : AbstractScreen() {

    private data class ShaderCell(val x: Float, val y: Float, val w: Float, val h: Float, val position: Int, val name: String)

    private var panelX = 0f
    private var panelY = 0f
    private var panelW = 0f
    private var panelH = 0f
    private var contentY0 = 0f
    private var contentY1 = 0f

    private var cells = listOf<ShaderCell>()
    private var backRect = FloatArray(4)

    private var scroll = 0f          // 当前滚动（px，平滑后）
    private var scrollTarget = 0f    // 目标滚动
    private var maxScroll = 0f
    private var backHover = 0f

    // 毛玻璃配色（与 Menu Settings 一致）
    private val glassFill = Color(255, 255, 255, 85)
    private val glassBorder = Color(255, 255, 255, 120)
    private val textDark = Color(35, 35, 40)
    private val labelDark = Color(35, 35, 40, 190)
    private val accent = Color(30, 110, 210, 240)

    private val cellW = 128f
    private val cellH = 18f
    private val gapX = 8f
    private val gapY = 6f
    private val cols = 4

    override fun initGui() {
        val sr = ScaledResolution(mc)
        width = sr.scaledWidth
        height = sr.scaledHeight

        val titleFont = Fonts.fontFluxTitle
        val rows = ceil(MainMenuStyles.MENU_SHADERS.size / cols.toDouble()).toInt()

        panelW = cols * cellW + (cols - 1) * gapX + 40f
        panelH = min(height - 30f, 470f)
        panelX = width / 2f - panelW / 2f
        panelY = height / 2f - panelH / 2f

        contentY0 = panelY + 32f + titleFont.height
        contentY1 = panelY + panelH - 44f
        val contentH = max(contentY1 - contentY0, cellH)
        maxScroll = max(rows * cellH + (rows - 1) * gapY - contentH, 0f)

        // 单元格坐标按无滚动状态排布，绘制时统一上移 scroll
        val gridX0 = panelX + 20f
        cells = MainMenuStyles.MENU_SHADERS.mapIndexed { pos, name ->
            val r = pos / cols
            val c = pos % cols
            ShaderCell(
                gridX0 + c * (cellW + gapX),
                contentY0 + r * (cellH + gapY),
                cellW, cellH, pos, name
            )
        }

        val btnY = panelY + panelH - 34f
        backRect = floatArrayOf(panelX + 20f, btnY, 100f, 22f)
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        val sr = ScaledResolution(mc)
        width = sr.scaledWidth
        height = sr.scaledHeight

        // 背景：实时绘制当前主菜单背景后整屏模糊 —— shader 选中后下一帧立即生效
        HudBlur.advanceFrame()
        MainMenuStyles.drawMenuBackground(width, height, 0)
        HudBlur.blur(0f, 0f, width.toFloat(), height.toFloat())

        // 毛玻璃面板
        RenderUtils.drawRoundedRect(panelX, panelY, panelX + panelW, panelY + panelH, glassFill.rgb, 6f)
        RenderUtils.drawRoundedBorderRect(
            panelX, panelY, panelX + panelW, panelY + panelH,
            1f, glassBorder.rgb, glassBorder.rgb, 6f
        )

        val titleFont = Fonts.fontFluxTitle
        val labelFont = Fonts.fontFluxRoboto
        val btnFont = Fonts.fontFluxSans

        titleFont.drawString("Shader Background", panelX + 20f, panelY + 16f, textDark.rgb)
        val countText = "${MainMenuStyles.MENU_SHADERS.size} shaders"
        labelFont.drawString(
            countText,
            panelX + panelW - 20f - labelFont.getStringWidth(countText),
            panelY + 20f + titleFont.height - labelFont.height,
            labelDark.rgb
        )

        // 滚动平滑动画 + 收敛
        scroll += (scrollTarget - scroll) * min(RenderUtils.deltaTime * 0.35f, 1f)
        scrollTarget = scrollTarget.coerceIn(0f, maxScroll)
        scroll = scroll.coerceIn(0f, maxScroll)

        val currentBgIndex = MainMenuStyles.backgroundImageIndex(ClientConfiguration.customMenuBackgroundImageIndex)
        val selectedPosition = if (MainMenuStyles.isShaderBackground(currentBgIndex)) {
            currentBgIndex - MainMenuStyles.BACKGROUND_IMAGE_NAMES.size
        } else -1

        // 只绘制可见行（无 scissor：绘制前做行级可见性过滤即可）
        val rowH = cellH + gapY
        val firstRow = floor(scroll / rowH).toInt().coerceAtLeast(0)
        val lastRow = ceil((scroll + (contentY1 - contentY0)) / rowH).toInt()
        for (cell in cells) {
            val row = cell.position / cols
            if (row < firstRow || row > lastRow) continue
            val x = cell.x
            val y = cell.y - scroll
            // 整格在内容区外时跳过（避免越界绘制到面板外）
            if (y + cell.h <= contentY0 || y >= contentY1) continue

            val hovered = mouseX >= x && mouseX <= x + cell.w && mouseY >= y && mouseY <= y + cell.h
            val selected = cell.position == selectedPosition

            val base = if (selected) accent.rgb else Color(255, 255, 255, 110).rgb
            RenderUtils.drawRoundedRect(x, y, x + cell.w, y + cell.h, base, 3f)
            if (hovered && !selected) {
                RenderUtils.drawRoundedRect(x, y, x + cell.w, y + cell.h, Color(255, 255, 255, 60).rgb, 3f)
            }

            var label = MenuShaderBackground.displayName(cell.name)
            while (label.isNotEmpty() && btnFont.getStringWidth(label) > cellW - 12f) {
                label = label.dropLast(1)
            }
            btnFont.drawString(
                label,
                x + 6f,
                y + cell.h / 2f - btnFont.height / 2f,
                if (selected) Color(255, 255, 255).rgb else textDark.rgb
            )
        }

        // 底部提示
        val hint = "Click to apply  ·  Scroll to browse  ·  Esc to back"
        btnFont.drawCenteredString(hint, panelX + panelW / 2f, contentY1 + 14f, textDark.rgb, false)

        // Back 按钮
        backHover += ((if (isHoveringRect(mouseX, mouseY, backRect)) 1f else 0f) - backHover) * 0.2f
        RenderUtils.drawRoundedRect(backRect[0], backRect[1], backRect[0] + backRect[2], backRect[1] + backRect[3], Color(35, 35, 40, 200).rgb, 2f)
        RenderUtils.drawRoundedRect(backRect[0], backRect[1], backRect[0] + backRect[2], backRect[1] + backRect[3], Color(255, 255, 255, (30 * backHover).toInt()).rgb, 2f)
        btnFont.drawCenteredString("Back", backRect[0] + backRect[2] / 2f, backRect[1] + backRect[3] / 2f - btnFont.height / 2f, 0xFFFFFF, false)

        super.drawScreen(mouseX, mouseY, partialTicks)
    }

    override fun handleMouseInput() {
        super.handleMouseInput()
        val dWheel = Mouse.getEventDWheel()
        if (dWheel != 0 && maxScroll > 0f) {
            scrollTarget = (scrollTarget - dWheel * 0.3f).coerceIn(0f, maxScroll)
        }
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        if (mouseButton != 0) {
            super.mouseClicked(mouseX, mouseY, mouseButton)
            return
        }

        // Back：返回 Menu Settings
        if (isHoveringRect(mouseX, mouseY, backRect)) {
            backToSettings()
            return
        }

        // 命中内容区域内的单元格：应用该 shader 并返回设置界面
        if (mouseX >= panelX && mouseX <= panelX + panelW && mouseY >= contentY0 && mouseY <= contentY1) {
            for (cell in cells) {
                val y = cell.y - scroll
                if (mouseX >= cell.x && mouseX <= cell.x + cell.w && mouseY >= y && mouseY <= y + cell.h) {
                    ClientConfiguration.customMenuBackgroundImageIndex =
                        MainMenuStyles.shaderBackgroundConfigIndex(cell.position)
                    FileManager.saveConfig(valuesConfig)
                    backToSettings()
                    return
                }
            }
        }

        super.mouseClicked(mouseX, mouseY, mouseButton)
    }

    override fun keyTyped(typedChar: Char, keyCode: Int) {
        // ESC 返回 Menu Settings
        if (keyCode == 1) {
            backToSettings()
            return
        }
        super.keyTyped(typedChar, keyCode)
    }

    private fun backToSettings() {
        mc.displayGuiScreen(GuiMainMenuSettings() as net.minecraft.client.gui.GuiScreen)
    }

    private fun isHoveringRect(mouseX: Int, mouseY: Int, rect: FloatArray): Boolean =
        mouseX >= rect[0] && mouseX <= rect[0] + rect[2] && mouseY >= rect[1] && mouseY <= rect[1] + rect[3]
}
