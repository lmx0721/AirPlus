/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Onyx 风格 Keybinds 元素（移植自 Onyx KeybindsModule 的"灵动岛"视觉，在 AirPlus 渲染
 * 体系内重实现）：圆角岛卡片 + 头部圆点/标题/分隔线 + 20x20 按键徽章 + 模块名。
 * 行滑入滑出动画：150ms 入 / 100ms 出（下方 5px 位移 + 透明度，按 deltaTime 推进）。
 * HUD 编辑态或聊天打开时显示全部绑定模块，否则仅显示启用的。
 */
package net.airplus.ui.client.hud.element.elements

import net.airplus.AirPlus.moduleManager
import net.airplus.features.module.Module
import net.airplus.ui.client.hud.designer.GuiHudDesigner
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.Element
import net.airplus.ui.client.hud.element.ElementInfo
import net.airplus.ui.client.hud.element.Side
import net.airplus.ui.font.Fonts
import net.airplus.utils.client.ClientThemesUtils
import net.airplus.utils.extensions.lerpWith
import net.airplus.utils.render.RenderUtils.drawRoundedBorder
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.airplus.utils.render.RenderUtils.deltaTime
import net.minecraft.client.gui.GuiChat
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11.glPopMatrix
import org.lwjgl.opengl.GL11.glPushMatrix
import org.lwjgl.opengl.GL11.glScalef
import java.awt.Color
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@ElementInfo(name = "Keybinds", single = true)
class Keybinds(
    x: Double = 6.0, y: Double = 100.0, scale: Float = 1F, side: Side = Side.default()
) : Element("Keybinds", x, y, scale, side) {

    // Scale 为独立设置（同 MusicLyric 的 customScale 做法），叠加在元素缩放之上
    private val scaleSetting by float("Scale", 0.85F, 0.5F..2F)

    /**
     * 行动画状态：[alpha] 0..1（150ms 入 / 100ms 出），[y] 行位平滑（增删行时其余行滑动）。
     */
    private class RowState {
        var alpha = 0F
        var y = -1F // -1 表示尚未定位，首帧直接吸附到目标槽位
    }

    private val rows = IdentityHashMap<Module, RowState>()

    override fun drawElement(): Border? {
        val designer = mc.currentScreen is GuiHudDesigner
        // HUD 编辑态或聊天打开时显示全部绑定模块（Onyx 行为），否则仅显示启用的
        val showAll = designer || mc.currentScreen is GuiChat
        val delta = deltaTime.toFloat()

        // 推进既有行动画：不满足显示条件的淡出，淡完移除（完全淡出后从 map 移除）
        val iterator = rows.entries.iterator()
        while (iterator.hasNext()) {
            val (module, state) = iterator.next()
            val shouldShow = module.keyBind != Keyboard.KEY_NONE && module.name !in EXCLUDED &&
                (showAll || module.state)
            state.alpha = if (shouldShow) {
                (state.alpha + delta / IN_MS).coerceAtMost(1F)
            } else {
                state.alpha - delta / OUT_MS
            }
            if (state.alpha <= 0F) iterator.remove()
        }

        // 新显示的模块建立行动画状态
        for (module in moduleManager) {
            if (module.name in EXCLUDED || module.keyBind == Keyboard.KEY_NONE) continue
            if (showAll || module.state) rows.getOrPut(module) { RowState() }
        }

        // 统计可见行并计算岛尺寸（宽度 = max(头部, 最宽行名贡献)，行名按 Onyx 上限 100px 截断）
        val titleFont = Fonts.fontSemibold40
        val badgeFont = Fonts.fontSemibold35
        var rowSlotCount = 0
        var maxNameContribution = 0F
        for ((module, state) in rows) {
            if (state.alpha <= 0F) continue
            rowSlotCount++
            maxNameContribution = max(maxNameContribution, min(titleFont.getStringWidth(module.getName()), 100).toFloat())
        }
        if (rowSlotCount == 0) return null

        val width = max(30F + titleFont.getStringWidth(TITLE), 38F + maxNameContribution)
        val height = 37F + rowSlotCount * ROW_HEIGHT + max(0, rowSlotCount - 1) * ROW_GAP

        glPushMatrix()
        glScalef(scaleSetting, scaleSetting, scaleSetting)

        // 圆角岛卡片（深色底 + 1px 描边）
        drawRoundedRect(0F, 0F, width, height, BG.rgb, RADIUS)
        drawRoundedBorder(0F, 0F, width, height, 1F, BORDER.rgb, RADIUS)

        // 头部：主题色圆点 + "Keybinds" 标题 + 分隔线
        drawRoundedRect(12F, 11F, 20F, 19F, ClientThemesUtils.getColor().rgb, 4F)
        titleFont.drawString(TITLE, 24F, 15F - titleFont.height / 2F, Color(235, 235, 240).rgb)
        drawRoundedRect(6F, 25F, width - 6F, 26F, SEPARATOR.rgb, 0.5F)

        // 行：20x20 圆角徽章（启用=主题色容器，禁用=灰）+ 按键缩写 + 模块名
        var index = 0
        for ((module, state) in rows) {
            if (state.alpha <= 0F) continue

            val targetY = 31F + index * ROW_ADVANCE
            state.y = if (state.y < 0F) targetY else (state.y..targetY).lerpWith((delta / 100F).coerceIn(0F, 1F))
            index++

            val alpha = state.alpha
            val rowY = state.y + (1F - alpha) * 5F // 入场：下方 5px 位移
            val enabled = module.state

            val badgeColor = if (enabled) {
                ClientThemesUtils.getColorWithAlpha(0, (255 * alpha).toInt())
            } else {
                Color(64, 64, 70, (255 * alpha).toInt())
            }
            drawRoundedRect(6F, rowY, 26F, rowY + ROW_HEIGHT, badgeColor.rgb, 4F)

            val abbreviation = abbreviate(Keyboard.getKeyName(module.keyBind))
            val badgeTextColor = if (enabled) {
                Color(250, 250, 252, (255 * alpha).toInt())
            } else {
                Color(210, 210, 215, (255 * alpha).toInt())
            }
            badgeFont.drawString(
                abbreviation,
                16F - badgeFont.getStringWidth(abbreviation) / 2F,
                rowY + 10F - badgeFont.height / 2F,
                badgeTextColor.rgb
            )

            val nameAlpha = if (enabled) (255 * alpha).toInt() else (110 * alpha).toInt()
            titleFont.drawString(
                module.getName(),
                32F,
                rowY + 10F - titleFont.height / 2F,
                Color(255, 255, 255, nameAlpha).rgb
            )
        }

        glPopMatrix()

        // 返回整卡 Border 供 HUD 设计器拖拽（内部做过 scaleSetting 缩放，需换算回元素坐标系）
        return Border(0F, 0F, width * scaleSetting, height * scaleSetting)
    }

    /**
     * 按键缩写（Onyx 同款规则）：多词取各词首字母、最多 3 字符；单词取前 3 字符。
     */
    private fun abbreviate(keyName: String): String {
        val upper = keyName.trim().uppercase(Locale.ROOT)
        if (upper.isEmpty()) return ""

        val words = upper.split(WHITESPACE)
        if (words.size > 1) {
            return buildString {
                for (word in words) {
                    if (length >= 3) break
                    if (word.isNotEmpty()) append(word.first())
                }
            }
        }
        return upper.take(3)
    }

    companion object {
        private const val TITLE = "Keybinds"
        private const val RADIUS = 12F
        private const val ROW_HEIGHT = 20F
        private const val ROW_GAP = 4F
        private const val ROW_ADVANCE = ROW_HEIGHT + ROW_GAP
        private const val IN_MS = 150F
        private const val OUT_MS = 100F

        private val BG = Color(20, 20, 24, 220)
        private val BORDER = Color(255, 255, 255, 40)
        private val SEPARATOR = Color(255, 255, 255, 40)

        private val WHITESPACE = Regex("\\s+")

        // 与 Keybinds 功能重叠的 GUI 类模块不展示（对齐 Onyx 排除自身/ClickGUI 的做法）
        private val EXCLUDED = setOf("ClickGUI", "HUDEdit")
    }
}
