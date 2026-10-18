/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.ui.client.mainmenu

import net.airplus.AirPlus.CLIENT_NAME
import net.airplus.AirPlus.clientVersionText
import net.airplus.ui.client.GuiModsMenu
import net.airplus.ui.client.altmanager.GuiAltManager
import net.airplus.ui.font.Fonts
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.timing.MSTimer
import net.airplus.utils.ui.AbstractScreen
import net.minecraft.client.gui.GuiMultiplayer
import net.minecraft.client.gui.GuiOptions
import net.minecraft.client.gui.GuiSelectWorld
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.util.ResourceLocation
import java.awt.Color

/**
 * 底部 Dock 主菜单（迁移自 AirClient DockMainMenu）。
 *
 * 风格：macOS 风格 Dock，简洁现代，呼吸感强。
 * 布局：上方大面积留白显示左对齐超大标题与版本副标题；底部居中胶囊 Dock 水平排列圆形图标按钮，悬停放大并显示文字提示。
 * 配色：深石板渐变背景蒙层，Dock 浅米色半透明 #E8E2D5，图标文字深炭 #2A2A2A，强调色 #6E8B8B（青灰）。
 *
 * 注：原 AirClient 使用贴图图标（watermark_images/clickgui 素材），此处改为 Flux 图标字形绘制，无需外部素材。
 */
class DockMainMenu : AbstractScreen() {

    private val timer = MSTimer()

    // 统一使用内置贴图图标，保证所有图标视觉尺寸一致（此前字形图标与 folder 贴图混用时大小不一）
    private data class DockItem(
        val label: String,
        val action: () -> Unit,
        val texture: String
    )

    private val items = listOf(
        DockItem("Singleplayer", { mc.displayGuiScreen(GuiSelectWorld(this)) }, "minecraft:airplus/clickgui/world.png"),
        DockItem("Multiplayer", { mc.displayGuiScreen(GuiMultiplayer(this)) }, "minecraft:airplus/clickgui/online.png"),
        DockItem("Alt Manager", { mc.displayGuiScreen(GuiAltManager(this)) }, "minecraft:airplus/clickgui/player.png"),
        DockItem("Options", { mc.displayGuiScreen(GuiOptions(this, mc.gameSettings)) }, "minecraft:airplus/clickgui/setting.png"),
        DockItem("Mods", { mc.displayGuiScreen(GuiModsMenu(this)) }, "minecraft:airplus/clickgui/folder.png"),
        DockItem("Quit", { mc.shutdown() }, "minecraft:airplus/clickgui/close.png")
    )

    private val hoverProgress = FloatArray(items.size)
    private var hoveredIndex = -1

    override fun initGui() {
        timer.reset()
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        val sr = ScaledResolution(mc)
        width = sr.scaledWidth
        height = sr.scaledHeight

        // 统一背景 + 深色渐变蒙层
        MainMenuStyles.drawMenuBackground(width, height, 0)
        RenderUtils.drawGradientRect(
            0, 0, width, height,
            Color(20, 22, 26, 120).rgb,
            Color(20, 22, 26, 180).rgb,
            0f
        )

        val titleFont = Fonts.fontBold180
        val subFont = Fonts.fontSemibold40
        val smallFont = Fonts.fontRegular35
        val tipFont = Fonts.fontSemibold35

        // ===== 上方大标题区（左对齐偏上） =====
        val padX = 50f
        val titleY = height * 0.16f
        titleFont.drawString(CLIENT_NAME, padX, titleY, Color(245, 240, 232).rgb)
        // 标题下方强调短横线
        val lineY = titleY + titleFont.height + 14f
        RenderUtils.drawRect(padX, lineY, padX + 54f, lineY + 2f, Color(110, 139, 139).rgb)
        // 副标题
        subFont.drawString(
            "Minecraft 1.8.9", padX, lineY + 12f,
            Color(190, 186, 178).rgb
        )
        smallFont.drawString(
            clientVersionText, padX, lineY + 12f + subFont.height + 4f,
            Color(140, 136, 128).rgb
        )

        // ===== 底部 Dock =====
        val dockItemSize = 56f
        val dockGap = 16f
        val dockPadX = 26f
        val dockPadY = 16f
        val tipSpace = 28f

        val dockContentW = items.size * dockItemSize + (items.size - 1) * dockGap
        val dockW = dockContentW + dockPadX * 2
        val dockH = dockItemSize + dockPadY * 2 + tipSpace
        val dockX = width / 2f - dockW / 2f
        val dockY = height - dockH - 28f

        // 计算悬停索引
        hoveredIndex = -1
        var ix = dockX + dockPadX
        val iy = dockY + dockPadY + tipSpace
        for (i in items.indices) {
            if (mouseX >= ix && mouseX <= ix + dockItemSize &&
                mouseY >= iy && mouseY <= iy + dockItemSize) {
                hoveredIndex = i
            }
            ix += dockItemSize + dockGap
        }

        // 绘制 dock 项
        ix = dockX + dockPadX
        for (i in items.indices) {
            val it = items[i]
            val hovered = i == hoveredIndex

            val target = if (hovered) 1f else 0f
            hoverProgress[i] += (target - hoverProgress[i]) * 0.22f
            val p = hoverProgress[i]

            val scale = 1f + 0.22f * p
            val size = dockItemSize * scale
            val cx = ix + dockItemSize / 2f
            val cy = iy + dockItemSize / 2f
            val halfS = size / 2f

            // 提示文字（在 dock 项上方）
            if (p > 0.1f) {
                val tipW = tipFont.getStringWidth(it.label)
                val tipX = cx - tipW / 2f
                val tipY = iy - tipFont.height - 8f
                RenderUtils.drawRoundedRect(
                    tipX - 8f, tipY - 3f, tipX + tipW + 8f, tipY + tipFont.height + 3f,
                    Color(40, 38, 34, (220 * p).toInt()).rgb, 5f
                )
                tipFont.drawString(
                    it.label, tipX, tipY,
                    Color(245, 240, 232, (255 * p).toInt()).rgb
                )
            }

            // 图标方块
            val iconColor = if (hovered) Color(110, 139, 139) else Color(70, 72, 76)
            RenderUtils.drawRoundedRect(
                cx - halfS, cy - halfS, cx + halfS, cy + halfS,
                iconColor.rgb, 12f * scale
            )
            // 图标（统一贴图，居中绘制）
            val texSize = 22f * scale
            RenderUtils.drawImage(
                ResourceLocation(it.texture),
                (cx - texSize / 2f).toInt(), (cy - texSize / 2f).toInt(),
                texSize.toInt(), texSize.toInt()
            )

            // 悬停时底部小圆点指示
            if (p > 0.1f) {
                val dotR = 2.2f
                RenderUtils.drawRoundedRect(
                    cx - dotR, iy + dockItemSize + 8f,
                    cx + dotR, iy + dockItemSize + 8f + dotR * 2f,
                    Color(110, 139, 139, (220 * p).toInt()).rgb, dotR
                )
            }

            ix += dockItemSize + dockGap
        }

        // 右下角切换按钮
        MainMenuStyles.drawSettingsButton(width, height, mouseX, mouseY)

        super.drawScreen(mouseX, mouseY, partialTicks)
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        if (MainMenuStyles.handleSettingsClick(width, height, mouseX, mouseY, mouseButton)) {
            timer.reset()
            return
        }

        if (mouseButton == 0 && timer.hasTimePassed(200)) {
            val dockItemSize = 56f
            val dockGap = 16f
            val dockPadX = 26f
            val dockPadY = 16f
            val tipSpace = 28f
            val dockContentW = items.size * dockItemSize + (items.size - 1) * dockGap
            val dockW = dockContentW + dockPadX * 2
            val dockH = dockItemSize + dockPadY * 2 + tipSpace
            val dockX = width / 2f - dockW / 2f
            val dockY = height - dockH - 28f
            val iy = dockY + dockPadY + tipSpace

            var ix = dockX + dockPadX
            for (i in items.indices) {
                val hitPad = 6f
                if (mouseX >= ix - hitPad && mouseX <= ix + dockItemSize + hitPad &&
                    mouseY >= iy - hitPad && mouseY <= iy + dockItemSize + hitPad) {
                    items[i].action()
                    timer.reset()
                    return
                }
                ix += dockItemSize + dockGap
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton)
    }

}
