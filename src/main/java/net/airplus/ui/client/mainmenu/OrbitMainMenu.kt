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
import kotlin.math.cos
import kotlin.math.sin

/**
 * 环形轨道主菜单（迁移自 AirClient OrbitMainMenu）。
 *
 * 风格：自然优雅 / 东方留白美学，曲线与圆形呼吸感。
 * 布局：左侧大面积留白显示超大半透明竖向标题；右侧 LOGO 居中，6 个按钮沿右侧半圆弧线分布。
 * 配色：墨绿 #2D4A3E 蒙层，米白 #E8E2D5 文字，强调色 #A8C5A0 淡绿。
 *
 * 注：原 AirClient 使用贴图图标，此处改为 Flux 图标字形绘制，无需外部素材。
 */
class OrbitMainMenu : AbstractScreen() {

    private val timer = MSTimer()

    // 统一使用内置贴图图标，保证所有图标视觉尺寸一致（此前字形图标与 folder 贴图混用时大小不一）
    private data class Entry(
        val label: String,
        val action: () -> Unit,
        val texture: String
    )

    private val entries = listOf(
        Entry("Singleplayer", { mc.displayGuiScreen(GuiSelectWorld(this)) }, "minecraft:airplus/clickgui/world.png"),
        Entry("Multiplayer", { mc.displayGuiScreen(GuiMultiplayer(this)) }, "minecraft:airplus/clickgui/online.png"),
        Entry("Alt Manager", { mc.displayGuiScreen(GuiAltManager(this)) }, "minecraft:airplus/clickgui/player.png"),
        Entry("Options", { mc.displayGuiScreen(GuiOptions(this, mc.gameSettings)) }, "minecraft:airplus/clickgui/setting.png"),
        Entry("Mods", { mc.displayGuiScreen(GuiModsMenu(this)) }, "minecraft:airplus/clickgui/folder.png"),
        Entry("Quit", { mc.shutdown() }, "minecraft:airplus/clickgui/close.png")
    )

    private val hoverProgress = FloatArray(entries.size)

    override fun initGui() {
        timer.reset()
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        val sr = ScaledResolution(mc)
        width = sr.scaledWidth
        height = sr.scaledHeight

        // 统一背景 + 墨绿蒙层
        MainMenuStyles.drawMenuBackground(width, height, Color(28, 48, 40, 155).rgb)

        val accent = Color(168, 197, 160)
        val orbitColor = Color(168, 197, 160, 90)

        val titleFont = Fonts.fontBold180
        val labelFont = Fonts.fontSemibold35
        val smallFont = Fonts.fontRegular35
        val descFont = Fonts.fontRegular30

        // ===== 左侧：超大半透明标题（垂直居中，左对齐） =====
        val leftPad = 60f
        val bigTitle = CLIENT_NAME
        val bigW = titleFont.getStringWidth(bigTitle)
        val leftRegionW = width * 0.42f
        val useBig = bigW <= leftRegionW - 40f
        val usedFont = if (useBig) titleFont else labelFont
        val usedW = usedFont.getStringWidth(bigTitle)
        val bigY = height / 2f - usedFont.height / 2f - 20f
        usedFont.drawString(bigTitle, leftPad, bigY, Color(232, 226, 213, 70).rgb)

        // 标题下方副标题
        descFont.drawString(
            "Minecraft 1.8.9", leftPad, bigY + usedFont.height + 12f,
            Color(180, 196, 176, 200).rgb
        )
        smallFont.drawString(
            clientVersionText, leftPad, bigY + usedFont.height + 12f + descFont.height + 4f,
            Color(140, 156, 140, 200).rgb
        )

        // 左侧装饰：竖向细线 + 小圆点
        val vlineX = leftPad + 2f
        RenderUtils.drawRect(vlineX, bigY - 30f, vlineX + 1f, bigY - 10f, accent.rgb)
        RenderUtils.drawRoundedRect(vlineX - 2f, bigY - 8f, vlineX + 3f, bigY - 3f, accent.rgb, 2.5f)

        // ===== 右侧：环形轨道 + LOGO + 按钮 =====
        val cx = width * 0.72f
        val cy = height / 2f
        val radius = (minOf(width * 0.18f, height * 0.34f)).coerceIn(120f, 200f)

        // 轨道圆环（细线，两道）
        drawCircleOutline(cx, cy, radius, orbitColor.rgb, 1f)
        drawCircleOutline(cx, cy, radius + 14f, Color(168, 197, 160, 35).rgb, 1f)

        // 中心 LOGO 圆
        val logoR = 42f
        RenderUtils.drawRoundedRect(cx - logoR, cy - logoR, cx + logoR, cy + logoR, Color(28, 48, 40, 220).rgb, logoR)
        RenderUtils.drawRoundedRect(cx - logoR + 3f, cy - logoR + 3f, cx + logoR - 3f, cy + logoR - 3f, Color(168, 197, 160, 60).rgb, logoR - 3f)
        val logoChar = "A"
        Fonts.fontExtraBold40.drawString(
            logoChar, cx - Fonts.fontExtraBold40.getStringWidth(logoChar) / 2f,
            cy - Fonts.fontExtraBold40.height / 2f + 1f,
            Color(245, 240, 232).rgb
        )

        // 按钮沿右侧半圆弧线分布（角度从 -70° 到 +70°）
        val angleStart = -70.0
        val angleEnd = 70.0
        val angleStep = (angleEnd - angleStart) / (entries.size - 1)

        for (i in entries.indices) {
            val angle = Math.toRadians(angleStart + angleStep * i)
            val bx = cx + (radius * cos(angle)).toFloat()
            val by = cy + (radius * sin(angle)).toFloat()
            val btnR = 22f
            val hovered = distance(mouseX, mouseY, bx, by) <= btnR + 4f

            val target = if (hovered) 1f else 0f
            hoverProgress[i] += (target - hoverProgress[i]) * 0.22f
            val p = hoverProgress[i]

            // 连接线（轨道到按钮）
            val lineAlpha = (80 + 120 * p).toInt()
            RenderUtils.drawRect(
                minOf(bx, cx), by - 0.5f, maxOf(bx, cx), by + 0.5f,
                Color(168, 197, 160, lineAlpha).rgb
            )

            // 按钮圆
            val scale = 1f + 0.18f * p
            val sR = btnR * scale
            val btnColor = mixColor(Color(28, 48, 40, 220), Color(168, 197, 160, 230), p)
            RenderUtils.drawRoundedRect(bx - sR, by - sR, bx + sR, by + sR, btnColor.rgb, sR)

            // 按钮内图标（统一贴图，居中绘制）
            val texSize = (20f * scale).toInt()
            RenderUtils.drawImage(
                ResourceLocation(entries[i].texture),
                (bx - texSize / 2f).toInt(), (by - texSize / 2f).toInt(),
                texSize, texSize
            )

            // 悬停时标签（按钮外侧）
            if (p > 0.1f) {
                val labelOffset = sR + 12f
                val labelX = bx + labelOffset * cos(angle).toFloat()
                val labelY = by + labelOffset * sin(angle).toFloat() - labelFont.height / 2f
                val labelW = labelFont.getStringWidth(entries[i].label)
                // 标签背景
                RenderUtils.drawRoundedRect(
                    labelX - 6f, labelY - 3f, labelX + labelW + 6f, labelY + labelFont.height + 3f,
                    Color(20, 36, 30, (220 * p).toInt()).rgb, 4f
                )
                labelFont.drawString(
                    entries[i].label, labelX, labelY,
                    Color(245, 240, 232, (255 * p).toInt()).rgb
                )
            }
        }

        // 右下角装饰
        smallFont.drawString(
            "© AirPlus",
            width - 60f - smallFont.getStringWidth("© AirPlus"),
            height - 32f,
            Color(168, 197, 160, 160).rgb
        )

        // 右下角切换按钮
        MainMenuStyles.drawSettingsButton(width, height, mouseX, mouseY)

        super.drawScreen(mouseX, mouseY, partialTicks)
    }

    private fun drawCircleOutline(cx: Float, cy: Float, r: Float, color: Int, thickness: Float) {
        val segments = 64
        val rad = r
        for (i in 0 until segments) {
            val a1 = (2 * Math.PI * i / segments).toFloat()
            val a2 = (2 * Math.PI * (i + 1) / segments).toFloat()
            val x1 = cx + rad * cos(a1.toDouble()).toFloat()
            val y1 = cy + rad * sin(a1.toDouble()).toFloat()
            val x2 = cx + rad * cos(a2.toDouble()).toFloat()
            val y2 = cy + rad * sin(a2.toDouble()).toFloat()
            RenderUtils.drawRect(
                minOf(x1, x2), minOf(y1, y2),
                maxOf(x1, x2) + thickness, maxOf(y1, y2) + thickness,
                color
            )
        }
    }

    private fun distance(x1: Int, y1: Int, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        if (MainMenuStyles.handleSettingsClick(width, height, mouseX, mouseY, mouseButton)) {
            timer.reset()
            return
        }

        if (mouseButton == 0 && timer.hasTimePassed(200)) {
            val cx = width * 0.72f
            val cy = height / 2f
            val radius = (minOf(width * 0.18f, height * 0.34f)).coerceIn(120f, 200f)
            val angleStart = -70.0
            val angleEnd = 70.0
            val angleStep = (angleEnd - angleStart) / (entries.size - 1)
            val btnR = 22f

            for (i in entries.indices) {
                val angle = Math.toRadians(angleStart + angleStep * i)
                val bx = cx + (radius * cos(angle)).toFloat()
                val by = cy + (radius * sin(angle)).toFloat()
                if (distance(mouseX, mouseY, bx, by) <= btnR + 4f) {
                    entries[i].action()
                    timer.reset()
                    return
                }
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton)
    }

    private fun mixColor(a: Color, b: Color, t: Float): Color {
        val tt = t.coerceIn(0f, 1f)
        return Color(
            (a.red + (b.red - a.red) * tt).toInt(),
            (a.green + (b.green - a.green) * tt).toInt(),
            (a.blue + (b.blue - a.blue) * tt).toInt(),
            (a.alpha + (b.alpha - a.alpha) * tt).toInt()
        )
    }
}
