package net.airplus.ui.client.hud.element.elements.targets2.impl

import net.airplus.config.BoolValue
import net.airplus.config.FloatValue
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.elements.Target2
import net.airplus.ui.client.hud.element.elements.targets2.TargetStyle
import net.airplus.ui.font.Fonts
import net.airplus.utils.render.ColorUtils
import net.airplus.utils.render.RoundedUtil
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.entity.player.EntityPlayer
import org.lwjgl.opengl.GL11
import java.awt.Color
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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

    /** 当前 HUD 的像素尺寸（moonlight fillRound 的柔边参数：1/scaleFactor/hudScale，hudScale 默认 1） */
    private fun pixelSize(): Float = 1F / max(1, ScaledResolution(mc).scaleFactor)

    /**
     * moonlight ProgressBarWidget.fillRound 的 1:1 移植（自包含绘制，不依赖 RoundedUtil 的 GL 状态）：
     * GL_TRIANGLE_FAN 内芯 + GL_TRIANGLE_STRIP 柔边（feather = pixel/2）。
     * [maxWidth] 为水平渐变截断线：右侧顶点钳到该 x 并把法线改为朝右（Float.MAX_VALUE 表示不截断）。
     * 渐变颜色固定沿 [x, x+w] 全宽插值，与 moonlight 血条「全宽绘制 + 截断」语义一致。
     */
    private fun fillRound(
        x: Float, y: Float, w: Float, h: Float, radius: Float, pixel: Float,
        c1: Color, c2: Color?, maxWidth: Float
    ) {
        if (w <= 0F || h <= 0F) return
        if (c1.alpha <= 0 && (c2 == null || c2.alpha <= 0)) return

        val r = radius.coerceIn(0F, min(w, h) / 2F)
        val feather = pixel / 2F
        val steps = ceil(r * 1.5F).toInt().coerceIn(4, 16)
        val count = steps * 4 + 4
        val px = FloatArray(count)
        val py = FloatArray(count)
        val nx = FloatArray(count)
        val ny = FloatArray(count)
        val corners = arrayOf(
            floatArrayOf(x + r, y + r, 180F),
            floatArrayOf(x + w - r, y + r, 270F),
            floatArrayOf(x + w - r, y + h - r, 0F),
            floatArrayOf(x + r, y + h - r, 90F)
        )
        var idx = 0
        for (corner in corners) {
            for (i in 0..steps) {
                val ang = Math.toRadians(corner[2] + 90.0 * i / steps)
                val cosV = cos(ang).toFloat()
                val sinV = sin(ang).toFloat()
                nx[idx] = cosV
                ny[idx] = sinV
                if (r - feather < 0F) {
                    px[idx] = corner[0] - Math.signum(cosV) * feather
                    py[idx] = corner[1] - Math.signum(sinV) * feather
                } else {
                    px[idx] = corner[0] + cosV * (r - feather)
                    py[idx] = corner[1] + sinV * (r - feather)
                }
                idx++
            }
        }

        // 渐变截断：超出 maxWidth 的顶点钳到截断线，法线改朝右（让柔边垂直于截断线）
        val limit = maxWidth - feather
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        for (i in 0 until count) {
            if (px[i] > limit) {
                px[i] = limit
                nx[i] = 1F
                ny[i] = 0F
            }
            if (px[i] < minX) minX = px[i]
            if (px[i] > maxX) maxX = px[i]
        }
        if (maxX - minX < 0.05F) return

        val cBase = c2 ?: c1
        val gradW = max(0.001F, w)

        // GL 状态一律用裸 GL11 强制写入，不经过 GlStateManager 缓存守卫：
        // HUD 里其他元素大量裸调 GL11，会把缓存与真实状态打失步，缓存守卫式
        // enable/disable 会被静默跳过，导致 fan/strip 在错误状态（纹理/模板/混合）
        // 下绘制成碎片多边形。逐顶点色的 immediate mode 对这类污染最敏感。
        val stencilWasEnabled = GL11.glGetBoolean(GL11.GL_STENCIL_TEST)
        GL11.glPushMatrix()
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glDisable(GL11.GL_TEXTURE_2D)
        GL11.glDisable(GL11.GL_CULL_FACE)
        GL11.glDisable(GL11.GL_ALPHA_TEST)
        GL11.glDisable(GL11.GL_STENCIL_TEST)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glShadeModel(GL11.GL_SMOOTH)

        // 内芯：三角扇（alpha = 1，颜色沿全宽渐变插值）
        GL11.glBegin(GL11.GL_TRIANGLE_FAN)
        val centerX = (minX + maxX) / 2F
        lerpColor(c1, cBase, (centerX - x) / gradW, 1F)
        GL11.glVertex2f(centerX, y + h / 2F)
        for (i in 0 until count) {
            val j = i % count
            lerpColor(c1, cBase, (px[j] - x) / gradW, 1F)
            GL11.glVertex2f(px[j], py[j])
        }
        GL11.glEnd()

        // 柔边：三角带，从内圈（alpha=1）沿法线外推 pixel 渐隐到 alpha=0
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP)
        for (i in 0 until count) {
            val j = i % count
            val t = (px[j] - x) / gradW
            lerpColor(c1, cBase, t, 1F)
            GL11.glVertex2f(px[j], py[j])
            lerpColor(c1, cBase, t, 0F)
            GL11.glVertex2f(px[j] + nx[j] * pixel, py[j] + ny[j] * pixel)
        }
        GL11.glEnd()

        GL11.glShadeModel(GL11.GL_FLAT)
        GL11.glEnable(GL11.GL_ALPHA_TEST)
        GL11.glEnable(GL11.GL_CULL_FACE)
        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glColor4f(1F, 1F, 1F, 1F)
        if (stencilWasEnabled) GL11.glEnable(GL11.GL_STENCIL_TEST)
        GL11.glPopMatrix()
    }

    /** moonlight ProgressBarWidget.color 的移植：c1→c2 按 t 插值，alpha 再乘 [alphaMul] */
    private fun lerpColor(c1: Color, c2: Color, t: Float, alphaMul: Float) {
        val k = t.coerceIn(0F, 1F)
        GL11.glColor4f(
            (c1.red + (c2.red - c1.red) * k) / 255F,
            (c1.green + (c2.green - c1.green) * k) / 255F,
            (c1.blue + (c2.blue - c1.blue) * k) / 255F,
            (c1.alpha + (c2.alpha - c1.alpha) * k) / 255F * alphaMul
        )
    }

    /**
     * armor 部件小块（头/胸/腿/靴，dura < 0 表示未装备）：
     * 对齐 moonlight TargetCards.part —— 圆角 0.9F、未装备白 30*p、装备 duraColor * 0.92*p。
     */
    private fun part(x: Float, y: Float, w: Float, h: Float, dura: Float, pixel: Float, p: Float) {
        if (w <= 0F || h <= 0F) return
        val c = if (dura < 0F) Color(255, 255, 255, (30F * p).toInt())
        else applyOpacity(duraColor(dura), 0.92F * p)
        fillRound(x, y, w, h, 0.9F, pixel, c, null, Float.MAX_VALUE)
    }

    override fun drawTarget(entity: EntityPlayer) {
        val self = mc.thePlayer ?: return
        updateBlockRate(entity)
        val p = fadeProgress()
        val h = 48F
        val w = bodyWidth(entity)
        val pixel = pixelSize()

        // 卡片背景（moonlight card：圆角 10、alpha = cardAlpha * 进度）
        fillRound(0F, 0F, w, h, RADIUS, pixel, cardColor(p), null, Float.MAX_VALUE)

        val smooth = states.getOrPut(entity) { Smooth() }
        val ratio = clamp01(entity.health / max(1F, entity.maxHealth))
        smooth.update(ratio)
        val armorArr = armor(entity)

        // 左侧 armor 部件人形图（对齐 moonlight TargetCards.body 的 part 布局）
        val u = 2.3F
        val figH = u * 15.1F + 3F
        val figX = 9F
        val figY = (h - figH) / 2F
        part(figX + u * 2F, figY, u * 4F, u * 4F, armorArr[0], pixel, p)
        val chestY = figY + u * 4F + 1F
        part(figX + u * 2F, chestY, u * 4F, u * 5.5F, armorArr[1], pixel, p)
        part(figX, chestY, u * 1.7F, u * 5.5F, armorArr[1], pixel, p)
        part(figX + u * 6.3F, chestY, u * 1.7F, u * 5.5F, armorArr[1], pixel, p)
        val legsY = chestY + u * 5.5F + 1F
        part(figX + u * 2F, legsY, u * 1.9F, u * 4F, armorArr[2], pixel, p)
        part(figX + u * 4.1F, legsY, u * 1.9F, u * 4F, armorArr[2], pixel, p)
        val bootsY = legsY + u * 4F + 1F
        part(figX + u * 2F, bootsY, u * 1.9F, u * 1.6F, armorArr[3], pixel, p)
        part(figX + u * 4.1F, bootsY, u * 1.9F, u * 1.6F, armorArr[3], pixel, p)

        // 右侧血条 + 伤害 Ghost：全宽绘制、按 maxWidth 截断（对齐 moonlight，渐变固定全宽）
        val barX = 8F + u * 8F + 10F
        val rightX = w - 8F
        val barW = rightX - barX
        val barY = h - 8F - 2F
        fillRound(barX, barY, barW, 2F, 1F, pixel, Color(255, 255, 255, (30F * p).toInt()), null, Float.MAX_VALUE)
        if (damageGhostValue.get() && smooth.ghost > smooth.shown + 0.002F) {
            fillRound(barX, barY, barW, 2F, 1F, pixel, Color(255, 255, 255, (85F * p).toInt()), null, barX + barW * smooth.ghost)
        }
        if (smooth.shown > 0.002F) {
            val low = lowHp(ratio)
            val c1 = if (low) applyOpacity(Color(255, 120, 110), p) else targetInstance.barColor
            val c2 = if (low) applyOpacity(Color(255, 80, 120), p) else ColorUtils.shiftHue(targetInstance.barColor, 90)
            fillRound(barX, barY, barW, 2F, 1F, pixel, c1, c2, barX + barW * smooth.shown)
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
