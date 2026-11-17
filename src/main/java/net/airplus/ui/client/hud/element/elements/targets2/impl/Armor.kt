package net.airplus.ui.client.hud.element.elements.targets2.impl

import net.airplus.config.BoolValue
import net.airplus.config.FloatValue
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.elements.Target2
import net.airplus.ui.client.hud.element.elements.targets2.TargetStyle
import net.airplus.ui.font.Fonts
import net.airplus.utils.render.ColorUtils
import net.airplus.utils.render.RoundedUtil
import net.minecraft.entity.player.EntityPlayer
import java.awt.Color
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Armor（移植自 moonlight TargetCards.body）：
 * 左侧 armor 部件耐久图 + 右侧血量/距离/最差护甲/格挡列的紧凑卡片。
 */
class Armor(inst: Target2) : TargetStyle("Armor", inst, true) {

    private val showDistanceValue = BoolValue("Distance", true).apply { setSupport { supported() } }
    private val showBlockRateValue = BoolValue("BlockRate", true).apply { setSupport { supported() } }
    private val damageGhostValue = BoolValue("DamageGhost", true).apply { setSupport { supported() } }
    private val lowHealthValue = FloatValue("LowHealth", 30F, 10F..60F).apply { setSupport { supported() } }
    private val cardAlphaValue = FloatValue("CardAlpha", 165F, 40F..255F).apply { setSupport { supported() } }
    private val armorWarnValue = FloatValue("ArmorWarn", 30F, 5F..80F).apply { setSupport { supported() } }

    private fun supported() = targetInstance.styleValueName.equals("armor", ignoreCase = true)

    private class Smooth {
        var shown = -1F
        var ghost = 0F
        private var last = 0L
        private var holdUntil = 0L

        fun update(target: Float) {
            val now = System.currentTimeMillis()
            if (shown < 0F) {
                shown = target
                ghost = target
                last = now
                return
            }
            val dt = min(0.1F, (now - last) / 1000F)
            last = now
            if (dt <= 0F) return
            if (target < shown - 0.001F && ghost <= shown + 0.002F) holdUntil = now + 350L
            shown += (target - shown) * (1F - exp(-18F * dt))
            if (ghost < shown) {
                ghost = shown
            } else if (now >= holdUntil) {
                ghost += (shown - ghost) * (1F - exp(-6F * dt))
            }
        }
    }

    private val states = HashMap<EntityPlayer, Smooth>()

    private fun off(size: Float): Float {
        val a = -3.01F + 0.131F * size
        val b = -3.02F + 0.507F * size
        return -(b - a) / 2F - a
    }

    private fun lowHp(ratio: Float) = ratio <= lowHealthValue.get() / 100F

    private fun armor(entity: EntityPlayer): FloatArray {
        // 对齐 moonlight TargetCards.armor：无装备 = -1F（微弱占位）；
        // 装备存在但不可损耗 = 0F（按 0% 耐久显示，weakest 也会选中它）；
        // 可损耗装备 = 1 - 损耗/最大耐久
        val arr = FloatArray(4) { -1F }
        for (i in 0..3) {
            val stack = entity.getCurrentArmor(3 - i) ?: continue
            arr[i] = if (stack.isItemStackDamageable && stack.maxDamage > 0) {
                1F - stack.itemDamage.toFloat() / stack.maxDamage.toFloat()
            } else {
                0F
            }
        }
        return arr
    }

    private fun weakest(armor: FloatArray): Int {
        var idx = -1
        var minV = Float.MAX_VALUE
        for (i in 0..3) {
            if (armor[i] < 0F) continue
            if (armor[i] < minV) {
                minV = armor[i]
                idx = i
            }
        }
        return idx
    }

    private fun duraColor(dura: Float): Color = when {
        dura <= armorWarnValue.get() / 100F -> RED
        dura <= 0.5F -> ORANGE
        else -> GREEN
    }

    private fun armorColWidth(): Float {
        val f14 = Fonts.fontMoonlight(14)
        val f10 = Fonts.fontMoonlight(10)
        return max(f14.getStringWidth("100%").toFloat(), f10.getStringWidth("CHESTPLATE").toFloat())
    }

    private fun blockColWidth(): Float {
        val f14 = Fonts.fontMoonlight(14)
        val f10 = Fonts.fontMoonlight(10)
        return max(f14.getStringWidth("100%").toFloat(), f10.getStringWidth("BLOCK").toFloat())
    }

    private fun bodyWidth(entity: EntityPlayer): Float {
        val f13 = Fonts.fontMoonlight(13)
        val f12 = Fonts.fontMoonlight(12)
        var top = f13.getStringWidth(entity.name).toFloat()
        if (showDistanceValue.get()) top += 10F + f12.getStringWidth("00.0m")
        val f26 = Fonts.fontMoonlight(26)
        var bottom = f26.getStringWidth("00.0") + 2F + f12.getStringWidth("HP") + 12F + armorColWidth()
        if (showBlockRateValue.get()) bottom += 10F + blockColWidth()
        return max(150F, 36.4F + max(top, bottom) + 8F)
    }

    private fun text(font: net.airplus.ui.font.GameFontRenderer, str: String, x: Float, y: Float, color: Color, p: Float) {
        font.drawString(str, x, y, applyOpacity(color, p).rgb)
    }

    /** 圆角块（Tessellator 路径，与 HUD 其他元素一致的稳定渲染；radius 自动钳制到尺寸内） */
    private fun round(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Color) {
        if (w <= 0F || h <= 0F || color.alpha <= 0) return
        RoundedUtil.drawRound(x, y, w, h, radius.coerceIn(0F, min(w, h) / 2F), color)
    }

    /** 圆角块 + 水平 c1→c2 渐变，宽度按 [w] 截断（血条填充） */
    private fun roundGradient(x: Float, y: Float, w: Float, h: Float, radius: Float, c1: Color, c2: Color) {
        if (w <= 0F || h <= 0F || (c1.alpha <= 0 && c2.alpha <= 0)) return
        RoundedUtil.applyGradientHorizontal(x, y, w, h, radius.coerceIn(0F, min(w, h) / 2F), c1, c2) {}
    }

    /**
     * armor 部件小块（头/胸/腿/靴，dura < 0 表示未装备）：
     * 对齐 moonlight TargetCards.part —— 圆角 0.9F、未装备白 30*p、装备 duraColor * 0.92*p。
     */
    private fun part(x: Float, y: Float, w: Float, h: Float, dura: Float, p: Float) {
        val c = if (dura < 0F) Color(255, 255, 255, (30F * p).toInt())
        else applyOpacity(duraColor(dura), 0.92F * p)
        round(x, y, w, h, 0.9F, c)
    }

    override fun drawTarget(entity: EntityPlayer) {
        val self = mc.thePlayer ?: return
        updateBlockRate(entity)
        val p = fadeProgress()
        val h = 48F
        val w = bodyWidth(entity)

        // 卡片背景（moonlight card：圆角 10、alpha = cardAlpha * 进度）
        round(0F, 0F, w, h, RADIUS, cardColor(p))

        val smooth = states.getOrPut(entity) { Smooth() }
        val ratio = clamp01(entity.health / max(1F, entity.maxHealth))
        smooth.update(ratio)
        val armorArr = armor(entity)

        // 左侧 armor 部件人形图（对齐 moonlight TargetCards.body 的 part 布局）
        val u = 2.3F
        val figH = u * 15.1F + 3F
        val figX = 9F
        val figY = (h - figH) / 2F
        part(figX + u * 2F, figY, u * 4F, u * 4F, armorArr[0], p)
        val chestY = figY + u * 4F + 1F
        part(figX + u * 2F, chestY, u * 4F, u * 5.5F, armorArr[1], p)
        part(figX, chestY, u * 1.7F, u * 5.5F, armorArr[1], p)
        part(figX + u * 6.3F, chestY, u * 1.7F, u * 5.5F, armorArr[1], p)
        val legsY = chestY + u * 5.5F + 1F
        part(figX + u * 2F, legsY, u * 1.9F, u * 4F, armorArr[2], p)
        part(figX + u * 4.1F, legsY, u * 1.9F, u * 4F, armorArr[2], p)
        val bootsY = legsY + u * 4F + 1F
        part(figX + u * 2F, bootsY, u * 1.9F, u * 1.6F, armorArr[3], p)
        part(figX + u * 4.1F, bootsY, u * 1.9F, u * 1.6F, armorArr[3], p)

        // 右侧血条 + 伤害 Ghost
        val barX = 8F + u * 8F + 10F
        val rightX = w - 8F
        val barW = rightX - barX
        val barY = h - 8F - 2F
        round(barX, barY, barW, 2F, 1F, Color(255, 255, 255, (30F * p).toInt()))
        if (damageGhostValue.get() && smooth.ghost > smooth.shown + 0.002F) {
            round(barX, barY, barW * smooth.ghost, 2F, 1F, Color(255, 255, 255, (85F * p).toInt()))
        }
        if (smooth.shown > 0.002F) {
            val low = lowHp(ratio)
            val c1 = if (low) applyOpacity(Color(255, 120, 110), p) else targetInstance.barColor
            val c2 = if (low) applyOpacity(Color(255, 80, 120), p) else ColorUtils.shiftHue(targetInstance.barColor, 90)
            roundGradient(barX, barY, barW * smooth.shown, 2F, 1F, c1, c2)
        }

        if (p > 0.12F) {
            val f13 = Fonts.fontMoonlight(13)
            val f12 = Fonts.fontMoonlight(12)
            val f26 = Fonts.fontMoonlight(26)
            val f14 = Fonts.fontMoonlight(14)
            val f10 = Fonts.fontMoonlight(10)

            text(f13, entity.name, barX, 11F + off(13F), Color(255, 255, 255, 175), p)
            if (showDistanceValue.get()) {
                val dist = decimalFormat2.format(self.getDistanceToEntity(entity)) + "m"
                text(f12, dist, rightX - f12.getStringWidth(dist), 11F + off(12F), Color(255, 255, 255, 110), p)
            }

            val hp = decimalFormat2.format(entity.health)
            val hpY = 24.5F + off(26F)
            text(f26, hp, barX, hpY, if (lowHp(ratio)) RED else Color.WHITE, p)
            text(f12, "HP", barX + f26.getStringWidth(hp) + 2F, hpY + 0.507F * (26F - 12F), Color(255, 255, 255, 120), p)

            val weakIdx = weakest(armorArr)
            val pct = if (weakIdx >= 0) Math.round(armorArr[weakIdx] * 100F).toString() + "%" else "-"
            val slot = if (weakIdx >= 0) SLOT_NAMES[weakIdx] else "NO ARMOR"
            val duraC = if (weakIdx >= 0) duraColor(armorArr[weakIdx]) else Color(255, 255, 255, 120)
            text(f14, pct, rightX - f14.getStringWidth(pct), 22F + off(14F), duraC, p)
            text(f10, slot, rightX - f10.getStringWidth(slot), 29F + off(10F), Color(255, 255, 255, 100), p)

            if (showBlockRateValue.get()) {
                val colX = rightX - armorColWidth() - 10F
                text(f14, blockRateText(entity), colX - f14.getStringWidth(blockRateText(entity)), 22F + off(14F), blockColor(entity), p)
                text(f10, "BLOCK", colX - f10.getStringWidth("BLOCK"), 29F + off(10F), Color(255, 255, 255, 100), p)
            }
        }
    }

    private fun cardColor(p: Float): Color {
        val bg = targetInstance.bgColorValue
        return Color(bg.red, bg.green, bg.blue, (cardAlphaValue.get() * p).toInt().coerceIn(0, 255))
    }

    override fun cleanup(alive: Set<EntityPlayer>) {
        cleanupBlockRate(alive)
        states.keys.removeAll { it !in alive }
    }

    override fun handleBlur(entity: EntityPlayer) {
        RoundedUtil.drawRound(0F, 0F, bodyWidth(entity), 48F, RADIUS, Color.WHITE)
    }

    override fun handleShadowCut(entity: EntityPlayer) {
        handleBlur(entity)
    }

    override fun handleShadow(entity: EntityPlayer) {
        RoundedUtil.drawRound(0F, 0F, bodyWidth(entity), 48F, RADIUS, shadowOpaque)
    }

    override fun getBorder(entity: EntityPlayer?): Border? {
        val e = entity ?: return null
        return Border(0F, 0F, bodyWidth(e), 48F)
    }

    companion object {
        private const val RADIUS = 10F

        private val GREEN = Color(52, 211, 120)
        private val ORANGE = Color(255, 170, 60)
        private val RED = Color(255, 99, 99)

        private val SLOT_NAMES = arrayOf("HEAD", "CHEST", "LEGS", "BOOTS")
    }
}
