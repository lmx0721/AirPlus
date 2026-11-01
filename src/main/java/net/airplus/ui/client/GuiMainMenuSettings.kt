/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.ui.client

import net.airplus.file.FileManager
import net.airplus.file.FileManager.valuesConfig
import net.airplus.file.configs.models.ClientConfiguration
import net.airplus.ui.client.mainmenu.MainMenuStyles
import net.airplus.ui.font.Fonts
import net.airplus.utils.render.HudBlur
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.ui.AbstractScreen
import net.minecraft.client.gui.ScaledResolution
import java.awt.Color

/**
 * 主菜单设置界面：
 * - 选择主菜单背景（第一项为 Flux 动态背景带实时预览，含 Xinxin / Hanabi2 内置背景，点击立即应用）
 * - "Custom" 按钮选择本地自定义背景（左键选择文件，右键恢复默认）
 * - 选择主菜单布局风格（点击标记，按 Apply 保存并切换）
 * 背景：全屏模糊 + 透明（不压暗）；面板为毛玻璃设计（模糊 + 半透明白 + 细白描边）。
 */
class GuiMainMenuSettings : AbstractScreen() {

    private data class ThumbButton(val x: Float, val y: Float, val w: Float, val h: Float, val index: Int)
    private data class StyleButton(val x: Float, val y: Float, val w: Float, val h: Float, val style: String, var hover: Float = 0f)

    private var panelX = 0f
    private var panelY = 0f
    private var panelW = 0f
    private var panelH = 0f

    private var thumbs = listOf<ThumbButton>()
    private var styleButtons = listOf<StyleButton>()
    private var applyRect = FloatArray(4)
    private var backRect = FloatArray(4)
    private var customRect = FloatArray(4)

    private var selectedStyle = MainMenuStyles.STYLE_FLUX
    private var applyHover = 0f
    private var backHover = 0f
    private var customHover = 0f

    // 毛玻璃配色
    private val glassFill = Color(255, 255, 255, 85)
    private val glassBorder = Color(255, 255, 255, 120)
    private val textDark = Color(35, 35, 40)
    private val labelDark = Color(35, 35, 40, 190)
    private val accent = Color(30, 100, 180, 230)

    override fun initGui() {
        selectedStyle = MainMenuStyles.normalize(ClientConfiguration.mainMenuStyle)

        val sr = ScaledResolution(mc)
        width = sr.scaledWidth
        height = sr.scaledHeight

        val titleFont = Fonts.fontFluxTitle
        val labelFont = Fonts.fontFluxRoboto

        // 面板尺寸随内容计算
        val thumbW = 74f
        val thumbH = 42f
        val thumbGap = 10f
        val bgCount = MainMenuStyles.BACKGROUND_IMAGE_NAMES.size
        val bgRowW = bgCount * thumbW + (bgCount - 1) * thumbGap

        val styleW = 96f
        val styleH = 20f
        val styleGap = 8f
        val stylesPerRow = 5
        val styleRows = (MainMenuStyles.STYLES.size + stylesPerRow - 1) / stylesPerRow
        val styleRowW = stylesPerRow * styleW + (stylesPerRow - 1) * styleGap

        panelW = maxOf(bgRowW, styleRowW) + 40f
        panelH = 40f + titleFont.height + 12f + labelFont.height + 8f + thumbH + 14f +
                labelFont.height + 8f + styleRows * styleH + (styleRows - 1) * styleGap + 16f + 24f + 16f
        panelX = width / 2f - panelW / 2f
        panelY = height / 2f - panelH / 2f

        // 背景缩略图
        val thumbY = panelY + 40f + titleFont.height + 12f + labelFont.height + 8f
        thumbs = (0 until bgCount).map { i ->
            ThumbButton(panelX + 20f + i * (thumbW + thumbGap), thumbY, thumbW, thumbH, i)
        }

        // 布局风格按钮
        val styleY0 = thumbY + thumbH + 14f + labelFont.height + 8f
        styleButtons = MainMenuStyles.STYLES.mapIndexed { i, style ->
            val r = i / stylesPerRow
            val c = i % stylesPerRow
            StyleButton(
                panelX + 20f + c * (styleW + styleGap),
                styleY0 + r * (styleH + styleGap),
                styleW, styleH, style
            )
        }

        // 底部按钮
        val btnY = panelY + panelH - 36f
        backRect = floatArrayOf(panelX + 20f, btnY, 100f, 22f)
        applyRect = floatArrayOf(panelX + panelW - 120f, btnY, 100f, 22f)

        // Custom 按钮（"Background" 标签行右侧）
        customRect = floatArrayOf(panelX + panelW - 90f, panelY + 24f + titleFont.height, 70f, labelFont.height + 6f)
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        val sr = ScaledResolution(mc)
        width = sr.scaledWidth
        height = sr.scaledHeight

        // 背景：仅全屏模糊（透明，不叠加黑色蒙层），全局 Blur 设置统一控制
        HudBlur.blur(0f, 0f, width.toFloat(), height.toFloat())

        // 毛玻璃面板：半透明白 + 细白描边（模糊已由上方全屏 blur 提供）
        RenderUtils.drawRoundedRect(panelX, panelY, panelX + panelW, panelY + panelH, glassFill.rgb, 6f)
        RenderUtils.drawRoundedBorderRect(
            panelX, panelY, panelX + panelW, panelY + panelH,
            1f, glassBorder.rgb, glassBorder.rgb, 6f
        )

        val titleFont = Fonts.fontFluxTitle
        val labelFont = Fonts.fontFluxRoboto
        val btnFont = Fonts.fontFluxSans

        // 标题
        titleFont.drawString("Menu Settings", panelX + 20f, panelY + 18f, textDark.rgb)

        // Custom 按钮
        customHover += ((if (isHoveringRect(mouseX, mouseY, customRect)) 1f else 0f) - customHover) * 0.2f
        RenderUtils.drawRoundedRect(customRect[0], customRect[1], customRect[0] + customRect[2], customRect[1] + customRect[3], Color(35, 35, 40, 200).rgb, 3f)
        RenderUtils.drawRoundedRect(customRect[0], customRect[1], customRect[0] + customRect[2], customRect[1] + customRect[3], Color(255, 255, 255, (30 * customHover).toInt()).rgb, 3f)
        btnFont.drawCenteredString(
            "Custom",
            customRect[0] + customRect[2] / 2f,
            customRect[1] + customRect[3] / 2f - btnFont.height / 2f,
            0xFFFFFF,
            false
        )

        // 背景选择区
        labelFont.drawString("Background", panelX + 20f, panelY + 24f + titleFont.height, labelDark.rgb)
        val currentBgIndex = MainMenuStyles.backgroundImageIndex(ClientConfiguration.customMenuBackgroundImageIndex)
        for (t in thumbs) {
            val hovered = mouseX >= t.x && mouseX <= t.x + t.w && mouseY >= t.y && mouseY <= t.y + t.h
            val selected = t.index == currentBgIndex

            // Flux 动态背景：实时预览（自定义文件 / 内置 blob 着色器）；其余绘制对应图片
            val resource = MainMenuStyles.backgroundResource(t.index)
            if (resource != null) {
                RenderUtils.drawImage(resource, t.x, t.y, t.w.toInt(), t.h.toInt())
            } else {
                MainMenuStyles.drawFluxBackgroundPreview(width, height, t.x, t.y, t.w, t.h)
            }
            // 边框：选中蓝色高亮，悬停淡白
            val borderColor = when {
                selected -> Color(30, 110, 210, 240).rgb
                hovered -> Color(255, 255, 255, 150).rgb
                else -> Color(255, 255, 255, 70).rgb
            }
            RenderUtils.drawRoundedBorderRect(t.x, t.y, t.x + t.w, t.y + t.h, 1f, borderColor, borderColor, 4f)

            // 名称（Flux 动态背景始终显示名称；图片背景选中或悬停时显示）
            if (selected || hovered || resource == null) {
                val name = MainMenuStyles.backgroundDisplayName(t.index)
                val nameFont = Fonts.fontFluxRoboto
                RenderUtils.drawRect(t.x, t.y + t.h - 14f, t.x + t.w, t.y + t.h, Color(0, 0, 0, 140).rgb)
                nameFont.drawString(name, t.x + 4f, t.y + t.h - 12f + (14f - nameFont.height) / 2f, Color(255, 255, 255).rgb)
            }
        }

        // 布局选择区
        val styleLabelY = thumbs.last().y + thumbs.last().h + 14f
        labelFont.drawString("Layout", panelX + 20f, styleLabelY, labelDark.rgb)
        for (b in styleButtons) {
            val hovered = mouseX >= b.x && mouseX <= b.x + b.w && mouseY >= b.y && mouseY <= b.y + b.h
            b.hover += ((if (hovered) 1f else 0f) - b.hover) * 0.2f
            val selected = b.style == selectedStyle

            val base = if (selected) accent.rgb else Color(255, 255, 255, 110).rgb
            RenderUtils.drawRoundedRect(b.x, b.y, b.x + b.w, b.y + b.h, base, 2f)
            if (b.hover > 0.02f && !selected) {
                RenderUtils.drawRoundedRect(b.x, b.y, b.x + b.w, b.y + b.h, Color(255, 255, 255, (60 * b.hover).toInt()).rgb, 2f)
            }
            btnFont.drawCenteredString(
                MainMenuStyles.displayName(b.style),
                b.x + b.w / 2f,
                b.y + b.h / 2f - btnFont.height / 2f,
                if (selected) 0xFFFFFF else textDark.rgb,
                false
            )
        }

        // 底部按钮
        val btnY = panelY + panelH - 36f

        backHover += ((if (isHoveringRect(mouseX, mouseY, backRect)) 1f else 0f) - backHover) * 0.2f
        RenderUtils.drawRoundedRect(backRect[0], backRect[1], backRect[0] + backRect[2], backRect[1] + backRect[3], Color(35, 35, 40, 200).rgb, 2f)
        RenderUtils.drawRoundedRect(backRect[0], backRect[1], backRect[0] + backRect[2], backRect[1] + backRect[3], Color(255, 255, 255, (30 * backHover).toInt()).rgb, 2f)
        btnFont.drawCenteredString("Back", backRect[0] + backRect[2] / 2f, backRect[1] + backRect[3] / 2f - btnFont.height / 2f, 0xFFFFFF, false)

        applyHover += ((if (isHoveringRect(mouseX, mouseY, applyRect)) 1f else 0f) - applyHover) * 0.2f
        RenderUtils.drawRoundedRect(applyRect[0], applyRect[1], applyRect[0] + applyRect[2], applyRect[1] + applyRect[3], accent.rgb, 2f)
        RenderUtils.drawRoundedRect(applyRect[0], applyRect[1], applyRect[0] + applyRect[2], applyRect[1] + applyRect[3], Color(255, 255, 255, (30 * applyHover).toInt()).rgb, 2f)
        btnFont.drawCenteredString("Apply", applyRect[0] + applyRect[2] / 2f, applyRect[1] + applyRect[3] / 2f - btnFont.height / 2f, 0xFFFFFF, false)

        super.drawScreen(mouseX, mouseY, partialTicks)
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        // Custom 按钮：左键选择本地背景文件，右键恢复默认
        if (isHoveringRect(mouseX, mouseY, customRect)) {
            when (mouseButton) {
                0 -> MainMenuStyles.pickCustomBackground()
                1 -> MainMenuStyles.resetCustomBackground()
            }
            return
        }

        if (mouseButton != 0) {
            super.mouseClicked(mouseX, mouseY, mouseButton)
            return
        }

        // 背景缩略图：点击立即应用并保存
        for (t in thumbs) {
            if (mouseX >= t.x && mouseX <= t.x + t.w && mouseY >= t.y && mouseY <= t.y + t.h) {
                ClientConfiguration.customMenuBackgroundImageIndex = t.index
                FileManager.saveConfig(valuesConfig)
                return
            }
        }

        // 布局按钮：点击仅标记，等待 Apply
        for (b in styleButtons) {
            if (mouseX >= b.x && mouseX <= b.x + b.w && mouseY >= b.y && mouseY <= b.y + b.h) {
                selectedStyle = b.style
                return
            }
        }

        // Back：返回主菜单（不保存布局选择）
        if (isHoveringRect(mouseX, mouseY, backRect)) {
            backToMenu()
            return
        }

        // Apply：保存布局并切换到对应主菜单
        if (isHoveringRect(mouseX, mouseY, applyRect)) {
            ClientConfiguration.mainMenuStyle = selectedStyle
            FileManager.saveConfig(valuesConfig)
            mc.displayGuiScreen(MainMenuStyles.createScreen(selectedStyle) as net.minecraft.client.gui.GuiScreen)
            return
        }

        super.mouseClicked(mouseX, mouseY, mouseButton)
    }

    override fun keyTyped(typedChar: Char, keyCode: Int) {
        // ESC 返回主菜单
        if (keyCode == 1) {
            backToMenu()
            return
        }
        super.keyTyped(typedChar, keyCode)
    }

    private fun backToMenu() {
        mc.displayGuiScreen(MainMenuStyles.createScreen(ClientConfiguration.mainMenuStyle) as net.minecraft.client.gui.GuiScreen)
    }

    private fun isHoveringRect(mouseX: Int, mouseY: Int, rect: FloatArray): Boolean =
        mouseX >= rect[0] && mouseX <= rect[0] + rect[2] && mouseY >= rect[1] && mouseY <= rect[1] + rect[3]
}
