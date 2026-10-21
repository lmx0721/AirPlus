/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Ported from Hanabi's HUD potion status rendering (HUD#renderPotionStatus).
 * Replaces the old "Effects" element. Shows a progress bar per active potion
 * effect with the vanilla status icon, effect name and remaining duration.
 * Stacks upward from the element anchor (default: bottom right, above the hotbar).
 */
package net.airplus.ui.client.hud.element.elements

import net.airplus.ui.client.hud.designer.GuiHudDesigner
import net.airplus.ui.font.AWTFontRenderer.Companion.assumeNonVolatile
import net.airplus.ui.font.Fonts
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.Element
import net.airplus.ui.client.hud.element.ElementInfo
import net.airplus.ui.client.hud.element.Side
import net.airplus.utils.client.ClientThemesUtils
import net.airplus.utils.extensions.lerpWith
import net.airplus.utils.render.HudBlur
import net.airplus.utils.render.HudSlices
import net.airplus.utils.render.RenderUtils.deltaTime
import net.airplus.utils.render.RenderUtils.drawRoundedBorder
import net.airplus.utils.render.RenderUtils.drawRect
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.airplus.utils.render.ColorSettingsInteger
import net.minecraft.client.gui.Gui
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.resources.I18n
import net.minecraft.potion.Potion
import net.minecraft.potion.PotionEffect
import net.minecraft.util.ResourceLocation
import java.awt.Color
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max

@ElementInfo(name = "Potions", single = true)
class Potions(
    x: Double = 115.0, y: Double = 60.0, scale: Float = 1F,
    side: Side = Side(Side.Horizontal.RIGHT, Side.Vertical.DOWN)
) : Element("Potions", x, y, scale, side) {

    private val style by choices("Style", arrayOf("Hanabi", "Card", "Compact", "Mini", "Onyx", "Opai"), "Hanabi")
    private val nameColor by color("Color", Color(33, 170, 47))
    private val fontRenderer by font("Font", Fonts.minecraftFont)
    private val blur by boolean("Blur", false)
    private val blurStrength by float("Blur-Strength", 8f, 1f..20f) { blur }
    private val bgColors = ColorSettingsInteger(this, "BackgroundColor") { style != "Hanabi" }.with(Color(20, 22, 28, 150))
    private val bgRadius by float("Round-Radius", 5F, 0F..12F) { style != "Hanabi" }

    // Onyx 样式：排序 / 药水染色 / 隐藏氛围效果（仅 Onyx 可见）
    private val onyxSort by choices("Sort", arrayOf("Time left", "Name", "Level"), "Time left") { style == "Onyx" }
    private val potionColor by boolean("Potion color", true) { style == "Onyx" }
    private val hideAmbient by boolean("Hide ambient", false) { style == "Onyx" }

    // Opai 样式：药水色淡染玻璃卡片（仅 Opai 可见）
    private val opaiTint by boolean("Tint", true) { style == "Opai" }

    // Longest seen duration per potion id, so the bar doesn't reset on re-applied effects
    private val potionMaxDurations = mutableMapOf<Int, Int>()

    // Onyx 行动画状态（按 potionID 跟踪；药水时长刷新会替换 PotionEffect 实例，按 ID 更稳定）
    private class OnyxRowState {
        var alpha = 0F
        var y = Float.NaN // NaN 表示尚未定位，首帧直接吸附到目标槽位（行位为负值，不能用 -1 哨兵）
        var visible = false // 本帧是否仍活跃（每帧重置）
        var potion: Potion? = null // 效果消失后淡出期间沿用最后内容
        var name = ""
        var nameColor = Color(235, 235, 240)
        var time = ""
        var timeColor = Color(255, 255, 255, 204)
    }

    private val onyxRows = HashMap<Int, OnyxRowState>()

    // Opai 行状态（按 potionID 跟踪；内容冻结用于淡出）
    private val opaiRows = HashMap<Int, OpaiRowState>()

    /** Opai 行动画状态：slide（水平滑入/滑出）+ space（垂直槽位延迟塌缩）+ 冻结内容。 */
    private class OpaiRowState(val id: Int, now: Long, firstGroup: Boolean) {
        var potion: Potion? = null
        var title = ""
        var timer = ""
        var color = 0 // 药水液色（ARGB）
        var timerRed = false // 剩余 ≤30s 变红
        var width = 0F
        var present = false
        var justRemoved = false
        var exitY = 0F
        var curX = 0F
        var curY = 0F
        val slide = OpaiSlide(now)
        val space = OpaiSpace(firstGroup, now)
    }

    /** Samsara PotionStatusMotion.Spring：指向 1 的临界阻尼弹簧（rate 18）。 */
    private class OpaiSpring(value: Double, velocity: Double, now: Long) {
        var value = value
        var velocity = velocity
        private var last = now

        fun advance(now: Long) {
            val dt = max(0.0, (now - last).toDouble()) / 1000.0
            val offset = value - 1
            val rate = 18.0
            val decay = exp(-rate * dt)
            val linear = velocity + rate * offset
            value = 1 + (offset + linear * dt) * decay
            velocity = (velocity - rate * linear * dt) * decay
            last = now
        }
    }

    /** Samsara PotionStatusMotion.Slide：三次 Hermite（入场 back 过冲 / 单行快出 / 组退场预备钳制）。 */
    private class OpaiSlide(now: Long) {
        var value = 0.0
        var velocity = 0.0
            private set
        private var start = 0.0
        private var target = 0.0
        private var startVelocity = 0.0
        private var endVelocity = 0.0
        private var duration = 1.0
        private var changedAt = now
        private var moving = false
        private var groupExit = false

        val isMoving: Boolean
            get() = moving

        fun advance(now: Long) {
            if (!moving) return
            val t = ((now - changedAt) / 1000.0 / duration).coerceIn(0.0, 1.0)
            val d = target - start
            val startTangent = startVelocity * duration
            val endTangent = endVelocity * duration
            val raw = start + startTangent * t + (3 * d - 2 * startTangent - endTangent) * t * t +
                (-2 * d + startTangent + endTangent) * t * t * t
            // 组退场预备段：钳制在起点，卡住不动直到曲线回落
            value = if (groupExit) minOf(start, raw) else raw
            velocity = if (groupExit && raw > start) 0.0 else
                (startTangent + 2 * (3 * d - 2 * startTangent - endTangent) * t +
                    3 * (-2 * d + startTangent + endTangent) * t * t) / duration
            if (t >= 1) {
                value = target
                velocity = 0.0
                moving = false
            }
        }

        fun to(target: Double, groupExit: Boolean, distance: Float, now: Long) {
            if (target == this.target) return
            start = value
            this.groupExit = target == 0.0 && groupExit
            duration = when {
                target == 1.0 -> OPAI_ENTRY_S
                this.groupExit -> OPAI_GROUP_EXIT_S
                else -> distance / OPAI_EXIT_SPEED
            }
            val d = target - start
            startVelocity = if (moving) velocity else when {
                target == 1.0 -> (3 + OPAI_BACK) * d / duration
                this.groupExit -> 0.0
                else -> 3 * d / duration
            }
            endVelocity = if (this.groupExit) (3 + OPAI_BACK) * d / duration else 0.0
            this.target = target
            changedAt = now
            moving = true
        }
    }

    /** Samsara PotionStatusMotion.Space：行槽位伸缩，退场延迟 300ms 后 200ms 塌缩重排。 */
    private class OpaiSpace(firstGroup: Boolean, now: Long) {
        private var growth = OpaiSpring(if (firstGroup) 1.0 else 0.0, 0.0, now)
        private var collapseAt = 0L
        private var retiring = false
        private var collapsing = false
        private var collapseFrom = 0.0
        private var velocity = 0.0

        var value = if (firstGroup) 1.0 else 0.0
            private set

        fun advance(now: Long) {
            if (!collapsing) {
                growth.advance(if (retiring) minOf(now, collapseAt) else now)
                value = growth.value
                velocity = growth.velocity
                if (retiring && now >= collapseAt) {
                    collapseFrom = value
                    collapsing = true
                }
            }
            if (collapsing) {
                val t = ((now - collapseAt) / 1000.0 / OPAI_REFLOW_S).coerceIn(0.0, 1.0)
                value = collapseFrom * (1 - t)
                velocity = if (t < 1) -collapseFrom / OPAI_REFLOW_S else 0.0
            }
        }

        fun present(present: Boolean, now: Long) {
            if (present && retiring) {
                growth = OpaiSpring(value, velocity, now)
                retiring = false
                collapsing = false
            } else if (!present && !retiring) {
                retiring = true
                collapseAt = now + OPAI_REFLOW_DELAY_MS
            }
        }
    }

    private class Metrics(val width: Float, val rowHeight: Float, val rowSpacing: Float)

    private fun styleMetrics() = when (style) {
        "Card" -> Metrics(130F, 34F, 40F)
        "Compact" -> Metrics(115F, 20F, 24F)
        "Mini" -> Metrics(80F, 16F, 19F)
        else -> Metrics(BAR_WIDTH, ROW_HEIGHT, ROW_SPACING) // Hanabi
    }

    override fun drawElement(): Border {
        if (style == "Onyx") return drawOnyx()
        if (style == "Opai") return drawOpai()
        return drawLegacy()
    }

    private fun drawLegacy(): Border {
        val player = mc.thePlayer ?: return Border(0f, -ROW_HEIGHT, BAR_WIDTH, 0f)
        val effects = player.activePotionEffects

        // Clean up effects that are gone and track the max seen duration per potion
        potionMaxDurations.keys.removeAll { id ->
            val potion = Potion.potionTypes.getOrNull(id)
            potion == null || player.getActivePotionEffect(potion) == null
        }
        for (effect in effects) {
            val max = potionMaxDurations[effect.potionID]
            if (max == null || max < effect.duration) potionMaxDurations[effect.potionID] = effect.duration
        }

        val metrics = styleMetrics()
        val rowsTop = -metrics.rowHeight - max(0, effects.size - 1) * metrics.rowSpacing

        // Optional blur behind the potion rows (absolute AABB: screen = scale * (render + local))
        if (blur && effects.isNotEmpty()) {
            val s = scale
            val originX = renderX.toFloat()
            val originY = renderY.toFloat()
            HudBlur.blur(
                originX * s, (originY + rowsTop) * s,
                (originX + metrics.width) * s, originY * s,
                blurStrength, "InternalBlur"
            ) {
                drawRect(0f, rowsTop, metrics.width, 0f, -1)
            }
        }

        assumeNonVolatile {
            for ((i, effect) in effects.withIndex()) {
                val potion = Potion.potionTypes[effect.potionID] ?: continue
                // Rows stack upward from the anchor (like Hanabi: x -= 35)
                val rowTop = -metrics.rowHeight - i * metrics.rowSpacing
                val maxDuration = potionMaxDurations[effect.potionID] ?: effect.duration
                val ratio = (effect.duration / maxDuration.toFloat()).coerceIn(0f, 1f)

                when (style) {
                    "Card" -> drawCardRow(potion, effect, rowTop, metrics, ratio)
                    "Compact" -> drawCompactRow(potion, effect, rowTop, metrics, ratio)
                    "Mini" -> drawMiniRow(potion, effect, rowTop, metrics)
                    else -> drawHanabiRow(potion, effect, rowTop, ratio)
                }
            }
        }

        val bottom = if (effects.isEmpty()) 0f else -1f
        val top = if (effects.isEmpty()) -metrics.rowHeight else (-metrics.rowHeight - (effects.size - 1) * metrics.rowSpacing)
        return Border(0f, top, metrics.width, bottom)
    }

    private fun effectName(potion: Potion, effect: PotionEffect): String {
        var name = I18n.format(potion.getName()).replace(Regex("§."), "")
        name += when (effect.amplifier) {
            0 -> " I"
            1 -> " II"
            2 -> " III"
            3 -> " IV"
            else -> " " + (effect.amplifier + 1)
        }
        return name
    }

    private fun durationText(effect: PotionEffect): String =
        Potion.getDurationString(effect).replace(Regex("§."), "")

    private fun drawStatusIcon(potion: Potion, x: Float, y: Float, size: Int, alpha: Float = 1F) {
        if (!potion.hasStatusIcon()) return
        GlStateManager.color(1f, 1f, 1f, alpha)
        mc.textureManager.bindTexture(ResourceLocation("textures/gui/container/inventory.png"))
        val iconIndex = potion.statusIconIndex
        Gui().drawTexturedModalRect(
            x.toInt(), y.toInt(),
            iconIndex % 8 * 18, 198 + iconIndex / 8 * 18, size, size
        )
    }

    /** Hanabi 原版样式：黑色进度条 + 灰色剩余部分 */
    private fun drawHanabiRow(potion: Potion, effect: PotionEffect, rowTop: Float, ratio: Float) {
        val progressX = BAR_WIDTH * ratio
        drawRect(0f, rowTop, progressX, rowTop + ROW_HEIGHT, Color(0, 0, 0, 100).rgb)
        drawRect(progressX, rowTop, BAR_WIDTH, rowTop + ROW_HEIGHT, Color(50, 50, 50, 100).rgb)

        drawStatusIcon(potion, 4f, rowTop + 6f, 18)

        val name = effectName(potion, effect)
        fontRenderer.drawStringWithShadow(name, 30f, rowTop + 5f, nameColor.rgb)

        val duration = durationText(effect)
        fontRenderer.drawStringWithShadow(duration, 30f, rowTop + 17f, Color(255, 255, 255, 204).rgb)
    }

    /** Card 样式：圆角卡片 + 图标 + 名称/持续时间 + 底部进度条 */
    private fun drawCardRow(potion: Potion, effect: PotionEffect, rowTop: Float, metrics: Metrics, ratio: Float) {
        val bg = bgColors.color()
        val pad = 4F
        drawRoundedRect(0f, rowTop, metrics.width, rowTop + metrics.rowHeight, bg.rgb, bgRadius)

        drawStatusIcon(potion, pad + 1f, rowTop + 8f, 18)

        val name = effectName(potion, effect)
        val textX = pad + 22F
        fontRenderer.drawStringWithShadow(name, textX, rowTop + 5f, nameColor.rgb)

        val duration = durationText(effect)
        val durationW = fontRenderer.getStringWidth(duration).toFloat()
        fontRenderer.drawStringWithShadow(
            duration, metrics.width - pad - durationW, rowTop + 5f, Color(255, 255, 255, 204).rgb
        )

        // 底部进度条
        val barY = rowTop + metrics.rowHeight - 7F
        val barW = metrics.width - pad * 2
        drawRoundedRect(pad, barY, pad + barW, barY + 3F, Color(255, 255, 255, 45).rgb, 1.5F)
        if (ratio > 0f) {
            drawRoundedRect(pad, barY, pad + barW * ratio, barY + 3F, nameColor.rgb, 1.5F)
        }
    }

    /** Compact 样式：单行图标 + 名称 + 持续时间 */
    private fun drawCompactRow(potion: Potion, effect: PotionEffect, rowTop: Float, metrics: Metrics, ratio: Float) {
        val bg = bgColors.color()
        drawRoundedRect(0f, rowTop, metrics.width, rowTop + metrics.rowHeight, bg.rgb, bgRadius)
        // 左侧主题色进度指示
        drawRoundedRect(0f, rowTop, metrics.width * ratio, rowTop + metrics.rowHeight, Color(nameColor.red, nameColor.green, nameColor.blue, 60).rgb, bgRadius)

        drawStatusIcon(potion, 3f, rowTop + 2f, 16)

        val name = effectName(potion, effect)
        fontRenderer.drawStringWithShadow(name, 23f, rowTop + metrics.rowHeight / 2f - fontRenderer.FONT_HEIGHT / 2f + 1f, nameColor.rgb)

        val duration = durationText(effect)
        val durationW = fontRenderer.getStringWidth(duration).toFloat()
        fontRenderer.drawStringWithShadow(
            duration, metrics.width - durationW - 4f, rowTop + metrics.rowHeight / 2f - fontRenderer.FONT_HEIGHT / 2f + 1f, Color(255, 255, 255, 204).rgb
        )
    }

    /** Mini 样式：仅名称 + 持续时间，无图标 */
    private fun drawMiniRow(potion: Potion, effect: PotionEffect, rowTop: Float, metrics: Metrics) {
        val bg = bgColors.color()
        drawRoundedRect(0f, rowTop, metrics.width, rowTop + metrics.rowHeight, bg.rgb, bgRadius)

        val name = effectName(potion, effect)
        fontRenderer.drawStringWithShadow(name, 4f, rowTop + metrics.rowHeight / 2f - fontRenderer.FONT_HEIGHT / 2f + 1f, nameColor.rgb)

        val duration = durationText(effect)
        val durationW = fontRenderer.getStringWidth(duration).toFloat()
        fontRenderer.drawStringWithShadow(
            duration, metrics.width - durationW - 4f, rowTop + metrics.rowHeight / 2f - fontRenderer.FONT_HEIGHT / 2f + 1f, Color(255, 255, 255, 204).rgb
        )
    }

    /**
     * Onyx 样式：岛卡片（头部圆点/标题/分隔线，同 Keybinds 观感）+ 药水行
     * （16px 原版效果图标 + 名称罗马数字 + 右侧剩余时间，剩余 <200tick 变红）。
     * 行滑入滑出：150ms 入 / 100ms 出（下方 5px 位移 + 透明度）；行自锚点向上堆叠。
     */
    private fun drawOnyx(): Border {
        val delta = deltaTime.toFloat()
        val designer = mc.currentScreen is GuiHudDesigner

        // 要显示的效果：设计器显示示例行；否则取玩家身上的效果（hideAmbient 过滤氛围效果）
        val effects: List<PotionEffect> = if (designer) {
            listOf(
                PotionEffect(Potion.moveSpeed.id, 1800, 1),
                PotionEffect(Potion.digSpeed.id, 900, 0),
                PotionEffect(Potion.damageBoost.id, 120, 0)
            )
        } else {
            (mc.thePlayer?.activePotionEffects ?: emptyList()).filter { !(hideAmbient && it.isAmbient) }
        }

        // 排序：剩余时长 / 名称 / 等级
        val sorted = when (onyxSort) {
            "Name" -> effects.sortedBy { I18n.format(potionOf(it)?.getName() ?: "").replace(Regex("§."), "") }
            "Level" -> effects.sortedByDescending { it.amplifier }
            else -> effects.sortedByDescending { it.duration }
        }

        // 推进行动画：活跃行淡入并缓存内容，消失的行沿用缓存内容淡出，淡完移除
        for (state in onyxRows.values) state.visible = false
        val drawList = ArrayList<OnyxRowState>(sorted.size)
        for (effect in sorted) {
            val potion = potionOf(effect) ?: continue
            val state = onyxRows.getOrPut(effect.potionID) { OnyxRowState() }
            state.visible = true
            state.potion = potion
            state.name = onyxRowName(potion, effect)
            state.nameColor = if (potionColor) Color(potion.liquidColor) else Color(235, 235, 240)
            state.time = durationText(effect)
            state.timeColor =
                if (!effect.isPotionDurationMax && effect.duration < 200) Color(244, 67, 54) else Color(255, 255, 255, 204)
            state.alpha = (state.alpha + delta / ONYX_IN_MS).coerceAtMost(1F)
            drawList.add(state)
        }
        val iterator = onyxRows.entries.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next().value
            if (state.visible) continue
            state.alpha -= delta / ONYX_OUT_MS
            if (state.alpha <= 0F) {
                iterator.remove()
                continue
            }
            drawList.add(state)
        }
        if (drawList.isEmpty()) return Border(0f, -ONYX_ROW_HEIGHT, 30f, 0f)

        // 尺寸：宽度 = max(头部贡献, 最宽行)；高度 = 37 + n*20 + (n-1)*4（同 Keybinds）
        val titleFont = Fonts.fontSemibold40
        val timeFont = Fonts.fontSemibold35
        var maxRowWidth = 0F
        for (state in drawList) {
            maxRowWidth = max(
                maxRowWidth,
                29F + titleFont.getStringWidth(state.name) + 6F + timeFont.getStringWidth(state.time) + 8F
            )
        }
        val width = max(30F + titleFont.getStringWidth(ONYX_TITLE), maxRowWidth)
        val height = 37F + drawList.size * ONYX_ROW_HEIGHT + max(0, drawList.size - 1) * ONYX_ROW_GAP
        val cardTop = -height

        // 岛卡片（深色底 + 1px 描边），自锚点向上生长
        drawRoundedRect(0F, cardTop, width, 0F, ONYX_BG.rgb, ONYX_RADIUS)
        drawRoundedBorder(0F, cardTop, width, 0F, 1F, ONYX_BORDER.rgb, ONYX_RADIUS)

        // 头部：主题色圆点 + "Potions" 标题 + 分隔线
        drawRoundedRect(12F, cardTop + 11F, 20F, cardTop + 19F, ClientThemesUtils.getColor().rgb, 4F)
        titleFont.drawString(ONYX_TITLE, 24F, cardTop + 15F - titleFont.height / 2F, Color(235, 235, 240).rgb)
        drawRoundedRect(6F, cardTop + 25F, width - 6F, cardTop + 26F, ONYX_SEPARATOR.rgb, 0.5F)

        // 行：行位向目标槽位插值（增删行时滑动），入场行额外下移 5px
        for ((slot, state) in drawList.withIndex()) {
            val targetY = cardTop + 31F + slot * ONYX_ROW_ADVANCE
            state.y = if (state.y.isNaN()) targetY else (state.y..targetY).lerpWith((delta / 100F).coerceIn(0F, 1F))
            drawOnyxRow(state, state.y + (1F - state.alpha) * 5F, width)
        }

        return Border(0f, cardTop, width, 0f)
    }

    /** Onyx 行：16px 原版效果图标 + 名称（可按药水液色染色）+ 右对齐剩余时间（透明度随行动画） */
    private fun drawOnyxRow(state: OnyxRowState, rowY: Float, width: Float) {
        val potion = state.potion ?: return
        val alpha = state.alpha
        val titleFont = Fonts.fontSemibold40
        val timeFont = Fonts.fontSemibold35

        drawStatusIcon(potion, 7F, rowY + 2F, 16, alpha)

        val nc = state.nameColor
        titleFont.drawString(
            state.name, 29F, rowY + 10F - titleFont.height / 2F,
            Color(nc.red, nc.green, nc.blue, (255 * alpha).toInt()).rgb
        )

        val tc = state.timeColor
        timeFont.drawString(
            state.time, width - 8F - timeFont.getStringWidth(state.time), rowY + 10F - timeFont.height / 2F,
            Color(tc.red, tc.green, tc.blue, (255 * alpha).toInt()).rgb
        )
    }

    private fun potionOf(effect: PotionEffect): Potion? = Potion.potionTypes.getOrNull(effect.potionID)

    /**
     * Opai 样式：Samsara PotionStatus 移植（常量按其 1.6 默认缩放预乘：行高 20→32、间距 2.25→3.6、
     * 圆角 3.75→6）。每张小卡为药水色淡染玻璃底 + 原版效果图标 + 名称罗马数字 + 剩余时间
     * （≤30s 变红）。行自左侧带过冲滑入（430ms back-out），单行退场 848px/s，组退场 170ms；
     * 槽位在退场 300ms 后 200ms 塌缩重排；行按 Samsara 固定顺序排列，锚点在整组顶部，
     * 新行向下生长。
     */
    private fun drawOpai(): Border {
        val designer = mc.currentScreen is GuiHudDesigner
        val now = System.currentTimeMillis()
        val s = scale

        // 要显示的效果：设计器显示示例行；否则取玩家身上带原版图标的效果
        val effects: List<PotionEffect> = if (designer) {
            listOf(PotionEffect(Potion.nightVision.id, 9580, 0), PotionEffect(Potion.moveSpeed.id, 1780, 1))
        } else {
            (mc.thePlayer?.activePotionEffects ?: emptyList()).filter { potionOf(it)?.hasStatusIcon() == true }
        }

        val firstGroup = opaiRows.isEmpty()
        val previousCount = opaiRows.values.count { it.present }

        // 更新内容并标记本帧在场
        val presentIds = HashSet<Int>()
        val font = Fonts.fontSemibold35
        for (effect in effects) {
            val potion = potionOf(effect) ?: continue
            val row = opaiRows.getOrPut(effect.potionID) { OpaiRowState(effect.potionID, now, firstGroup) }
            row.potion = potion
            row.title = opaiTitle(potion, effect)
            row.timer = opaiTimer(effect)
            row.color = 0xFF000000.toInt() or (potion.liquidColor and 0xFFFFFF)
            row.timerRed = !effect.isPotionDurationMax && effect.duration <= OPAI_WARN_TICKS
            row.width = OPAI_WIDTH_BASE +
                max(font.getStringWidth(row.title).toFloat(), font.getStringWidth(row.timer).toFloat())
            presentIds.add(effect.potionID)
        }

        // 推进行动画
        for (row in opaiRows.values) {
            row.slide.advance(now)
            row.space.advance(now)
            val next = row.id in presentIds
            row.justRemoved = row.present && !next
            row.slide.to(if (next) 1.0 else 0.0, max(previousCount, presentIds.size) > 1, row.width + OPAI_SLIDE_PAD, now)
            row.space.present(next, now)
            row.present = next
        }
        if (presentIds.isEmpty() && opaiRows.values.none { it.slide.isMoving }) {
            opaiRows.clear()
            return Border(0f, -OPAI_ROW_HEIGHT, 30f, 0f)
        }
        opaiRows.entries.removeAll { !it.value.present && !it.value.slide.isMoving && it.value.space.value <= 0.001 }

        // 排序（Samsara 固定顺序 + id 兜底）并计算行位（锚点在整组顶部，向下生长）
        val sorted = opaiRows.values.sortedWith(compareBy({ opaiOrder(it.id) }, { it.id }))
        var height = 0f
        for (row in sorted) height += (OPAI_ROW_HEIGHT + OPAI_GAP) * row.space.value.toFloat()
        height = max(0f, height - OPAI_GAP)

        val drawList = ArrayList<OpaiRowState>(sorted.size)
        var y = 0f
        for (row in sorted) {
            if (row.justRemoved) row.exitY = y
            if (row.present || row.slide.isMoving) {
                row.curX = -(row.width + OPAI_SLIDE_PAD) * (1f - row.slide.value.toFloat())
                row.curY = if (row.present) y else row.exitY
                drawList.add(row)
            }
            y += (OPAI_ROW_HEIGHT + OPAI_GAP) * row.space.value.toFloat()
        }
        if (drawList.isEmpty()) return Border(0f, -OPAI_ROW_HEIGHT, 30f, 0f)
        val width = drawList.maxOf { it.width }

        // 模糊（逐行，绝对 AABB + 局部圆角遮罩）
        if (blur) {
            val originX = renderX.toFloat()
            val originY = renderY.toFloat()
            for (row in drawList) {
                val rx = row.curX
                val ry = row.curY
                val rw = row.width
                HudBlur.blur(
                    (originX + rx) * s, (originY + ry) * s,
                    (originX + rx + rw) * s, (originY + ry + OPAI_ROW_HEIGHT) * s,
                    blurStrength, "InternalBlur"
                ) {
                    drawRoundedRect(rx, ry, rx + rw, ry + OPAI_ROW_HEIGHT, -1, OPAI_RADIUS)
                }
            }
        }

        for (row in drawList) drawOpaiRow(row)

        return Border(0f, 0f, width, height)
    }

    /** Opai 行：Samsara 预烘焙阴影/玻璃贴图（3 段切片）+ 药水色图标贴图 + 名称 + 剩余时间。 */
    private fun drawOpaiRow(state: OpaiRowState) {
        val potion = state.potion ?: return
        val x = state.curX
        val y = state.curY
        val glassTint = if (opaiTint) 0xFF000000.toInt() or (state.color and 0xFFFFFF) else 0xFFFFFFFF.toInt()

        // Samsara PotionStatusSurface.panel：shadow.png + 药水色染色 glass.png（×1.6 预乘 → texScale 8/1.6）
        HudSlices.panelStrip(
            HudSlices.POTION_SHADOW, x, y, state.width, OPAI_ROW_HEIGHT,
            OPAI_TEX_MARGIN, OPAI_TEX_SCALE, OPAI_RADIUS, 720f, 240f, 0xFFFFFFFF.toInt()
        )
        HudSlices.panelStrip(
            HudSlices.POTION_GLASS, x, y, state.width, OPAI_ROW_HEIGHT,
            OPAI_TEX_MARGIN, OPAI_TEX_SCALE, OPAI_RADIUS, 720f, 240f, glassTint
        )

        drawOpaiIcon(potion, state.id, x, y, glassTint)

        val font = Fonts.fontSemibold35
        font.drawString(state.title, x + OPAI_TEXT_X, y + OPAI_TITLE_Y, state.color)
        font.drawString(state.timer, x + OPAI_TEXT_X, y + OPAI_TIMER_Y, if (state.timerRed) OPAI_TIMER_RED else OPAI_TIMER_GRAY)
    }

    /** Opai 图标：Samsara 自定义药水图标贴图（128×128，药水色染色，占满行高）；无贴图的效果回退原版图标。 */
    private fun drawOpaiIcon(potion: Potion, id: Int, x: Float, y: Float, tint: Int) {
        val key = opaiIconKey(id)
        if (key == null) {
            drawStatusIcon(potion, x + 7f, y + 7f, 18)
            return
        }
        HudSlices.texRect(
            ResourceLocation("minecraft", "airplus/hud/potion/$key.png"),
            x, y, OPAI_ROW_HEIGHT, OPAI_ROW_HEIGHT, 0f, 0f, 128f, 128f, 128f, 128f, tint
        )
    }

    /** 1.8.9 Potion id → Samsara 图标贴图名（17 个内置图标）。 */
    private fun opaiIconKey(id: Int): String? = when (id) {
        Potion.moveSpeed.id -> "speed"
        Potion.moveSlowdown.id -> "slowness"
        Potion.damageBoost.id -> "strength"
        Potion.jump.id -> "jump_boost"
        Potion.regeneration.id -> "regeneration"
        Potion.fireResistance.id -> "fire_resistance"
        Potion.waterBreathing.id -> "water_breathing"
        Potion.invisibility.id -> "invisibility"
        Potion.nightVision.id -> "night_vision"
        Potion.weakness.id -> "weakness"
        Potion.poison.id -> "poison"
        Potion.absorption.id -> "absorption"
        Potion.saturation.id -> "saturation"
        Potion.digSpeed.id -> "haste"
        Potion.digSlowdown.id -> "mining_fatigue"
        Potion.confusion.id -> "nausea"
        Potion.resistance.id -> "resistance"
        else -> null
    }

    /** Opai 剩余时间：M:SS（无限时长显示 ∞，同 Samsara duration）。 */
    private fun opaiTimer(effect: PotionEffect): String {
        if (effect.isPotionDurationMax) return "∞"
        val seconds = max(0, effect.duration) / 20
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    /** Opai 行名：本地化名称 + 等级罗马数字（≤XX，更高用数字，同 Samsara roman）。 */
    private fun opaiTitle(potion: Potion, effect: PotionEffect): String {
        val level = effect.amplifier + 1
        val suffix = if (level > 1) " " + opaiRoman(level) else ""
        return I18n.format(potion.getName()).replace(Regex("§."), "") + suffix
    }

    private fun opaiRoman(value: Int): String {
        if (value > 20) return value.toString()
        val values = intArrayOf(10, 9, 5, 4, 1)
        val numerals = arrayOf("X", "IX", "V", "IV", "I")
        var v = value
        val result = StringBuilder()
        for (i in values.indices) while (v >= values[i]) {
            result.append(numerals[i])
            v -= values[i]
        }
        return result.toString()
    }

    /** Samsara 固定排序：night_vision → speed → … → 默认最后。 */
    private fun opaiOrder(id: Int): Int = when (id) {
        Potion.nightVision.id -> 0
        Potion.moveSpeed.id -> 1
        Potion.moveSlowdown.id -> 2
        Potion.digSpeed.id -> 3
        Potion.digSlowdown.id -> 4
        Potion.damageBoost.id -> 5
        Potion.jump.id -> 8
        Potion.confusion.id -> 9
        Potion.regeneration.id -> 10
        Potion.resistance.id -> 11
        Potion.fireResistance.id -> 12
        Potion.waterBreathing.id -> 13
        Potion.invisibility.id -> 14
        Potion.weakness.id -> 18
        Potion.poison.id -> 19
        Potion.absorption.id -> 22
        Potion.saturation.id -> 23
        else -> 32
    }

    /** Onyx 行名：本地化名称 + 等级罗马数字（2..8 级为 II..VIII，更高用数字） */
    private fun onyxRowName(potion: Potion, effect: PotionEffect): String {
        val level = effect.amplifier + 1
        val suffix = when (level) {
            1 -> ""
            in 2..8 -> " " + ONYX_ROMAN[level - 2]
            else -> " $level"
        }
        return I18n.format(potion.getName()).replace(Regex("§."), "") + suffix
    }

    companion object {
        private const val BAR_WIDTH = 110f
        private const val ROW_HEIGHT = 30f
        private const val ROW_SPACING = 35f

        // Onyx 样式（几何与配色对齐 Keybinds 元素）
        private const val ONYX_TITLE = "Potions"
        private const val ONYX_IN_MS = 150F
        private const val ONYX_OUT_MS = 100F
        private const val ONYX_ROW_HEIGHT = 20F
        private const val ONYX_ROW_GAP = 4F
        private const val ONYX_ROW_ADVANCE = ONYX_ROW_HEIGHT + ONYX_ROW_GAP
        private const val ONYX_RADIUS = 12F
        private val ONYX_BG = Color(20, 20, 24, 220)
        private val ONYX_BORDER = Color(255, 255, 255, 40)
        private val ONYX_SEPARATOR = Color(255, 255, 255, 40)
        private val ONYX_ROMAN = arrayOf("II", "III", "IV", "V", "VI", "VII", "VIII")

        // Opai 样式（Samsara PotionStatusPainter/Motion 常量 ×1.6 预乘）
        private const val OPAI_ROW_HEIGHT = 32F          // 20 ×1.6
        private const val OPAI_GAP = 3.6F                // 2.25 ×1.6
        private const val OPAI_RADIUS = 6F               // 3.75 ×1.6
        private const val OPAI_TEXT_X = 31.6F            // 19.75 ×1.6
        private const val OPAI_TITLE_Y = 7.6F            // 4.75 ×1.6
        private const val OPAI_TIMER_Y = 18.8F           // 11.75 ×1.6
        private const val OPAI_WIDTH_BASE = 38F          // 23.75 ×1.6（图标列 + 右侧内边距）
        private const val OPAI_SLIDE_PAD = 12.8F         // 8 ×1.6（滑入距离附加量）
        private const val OPAI_WARN_TICKS = 600          // 剩余 ≤30s 变红
        private const val OPAI_ENTRY_S = 0.430           // 入场时长（back 过冲）
        private const val OPAI_GROUP_EXIT_S = 0.170      // 组退场时长
        private const val OPAI_EXIT_SPEED = 848.0        // 单行退场速度（530 ×1.6 px/s）
        private const val OPAI_BACK = 1.70158            // 入场过冲系数
        private const val OPAI_REFLOW_DELAY_MS = 300L    // 槽位塌缩延迟
        private const val OPAI_REFLOW_S = 0.200          // 槽位塌缩时长
        private const val OPAI_TEX_MARGIN = 8F           // 贴图阴影外扩 5 ×1.6
        private const val OPAI_TEX_SCALE = 5F            // 贴图超采样 8 ÷ 1.6 预乘
        private val OPAI_TIMER_GRAY = 0xFFAAAAAA.toInt()
        private val OPAI_TIMER_RED = 0xFFFF5555.toInt()
    }
}
