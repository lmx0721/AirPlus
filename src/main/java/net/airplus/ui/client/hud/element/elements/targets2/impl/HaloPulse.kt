package net.airplus.ui.client.hud.element.elements.targets2.impl

import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.elements.Target2
import net.airplus.ui.client.hud.element.elements.targets2.TargetStyle
import net.airplus.ui.font.Fonts
import net.airplus.utils.render.RoundedUtil
import net.airplus.utils.render.Stencil
import net.minecraft.entity.player.EntityPlayer
import java.awt.Color
import kotlin.math.max

/**
 * "Halo Pulse" — 双轨道光环 + 外圈血量弧 (from TargetHUD2-NewStyles.html style 17).
 * Ring zone: 50px container, inner dual rings r≈18 (cyan sweep 2.6s cw + coral
 * dashed ring 6s ccw), outer health arc r≈23.5 starting at 12 o'clock.
 */
class HaloPulse(inst: Target2) : TargetStyle("Halo Pulse", inst, true) {

    companion object {
        fun healthColor(health: Float, maxHealth: Float): Color {
            val ratio = if (maxHealth <= 0f) 1f else health / maxHealth
            return when {
                ratio > 0.55f -> Color(74, 222, 128)   // #4ade80
                ratio > 0.30f -> Color(251, 191, 36)   // #fbbf24
                else -> Color(248, 113, 113)           // #f87171
            }
        }
    }

    private val ringZone = 50
    private val padLeft = 5
    private val padRight = 11
    private val gap = 10

    private fun infoWidth(name: String, healthText: String): Float =
        max(
            max(Fonts.fontSemibold40.getStringWidth(name), Fonts.fontSemibold40.getStringWidth(healthText)).toFloat(),
            108f
        )

    private fun width(name: String, healthText: String) =
        (padLeft + ringZone + gap + infoWidth(name, healthText) + padRight)

    private val height = ringZone + 8f

    override fun drawTarget(entity: EntityPlayer) {
        updateAnim(entity.health)
        val alpha = 1F - targetInstance.getFadeProgress()
        if (alpha <= 0F) return

        val name = entity.name
        val maxHealth = entity.maxHealth.coerceAtLeast(1F)
        val healthText = decimalFormat2.format(easingHealth.coerceIn(0F, maxHealth))
        val width = width(name, healthText)
        val cx = padLeft + ringZone / 2f
        val cy = height / 2f
        val time = System.currentTimeMillis()

        // 面板
        RoundedUtil.drawRound(0f, 0f, width, height, 16f, getColor(Color(11, 15, 25, 201)))

        // 内环 1：青色扫光弧（2.6s 正转），透明->#39c6ff->#8affc1->透明
        val sweep = (time / 2600.0 * 360.0) % 360.0
        val s = sweep.toFloat()
        val cyan = Color(57, 198, 255)
        val mint = Color(138, 255, 193)
        ArcRenderer.arc(cx, cy, 18f, 1.2f, s + 223.2f, 64.8f, Color(57, 198, 255, 0), cyan)
        ArcRenderer.arc(cx, cy, 18f, 1.2f, s + 288f, 36f, cyan, mint)
        ArcRenderer.arc(cx, cy, 18f, 1.2f, s + 324f, 21.6f, mint, Color(138, 255, 193, 0))

        // 内环 2：珊瑚色刻度虚线环（6s 反转，10 段，每段 12°）
        val dash = (-(time / 6000.0 * 360.0) % 360.0).toFloat()
        val coral = Color(252, 96, 66, 165)
        for (k in 0..9) {
            ArcRenderer.arc(cx, cy, 18f, 1.2f, dash + k * 36f, 12f, coral, coral)
        }

        // 外圈血量弧（12 点钟起顺时针）+ 辉光
        val ratio = (easingHealth / maxHealth).coerceIn(0f, 1f)
        val hpSweep = ratio * 360f
        val hpColor = getColor(healthColor(easingHealth.coerceIn(0f, maxHealth), maxHealth))
        if (hpSweep > 0f) {
            ArcRenderer.arc(cx, cy, 23.5f, 4f, -90f, hpSweep, Color(hpColor.red, hpColor.green, hpColor.blue, 45), Color(hpColor.red, hpColor.green, hpColor.blue, 45))
            ArcRenderer.arc(cx, cy, 23.5f, 7f, -90f, hpSweep, Color(hpColor.red, hpColor.green, hpColor.blue, 18), Color(hpColor.red, hpColor.green, hpColor.blue, 18))
            ArcRenderer.arc(cx, cy, 23.5f, 1.5f, -90f, hpSweep, hpColor, hpColor)
        }

        // 圆形头像 + 白色描边环（受击时缩小并泛红，与 Rice 一致）
        val playerInfo = mc.netHandler.getPlayerInfo(entity.uniqueID)
        if (playerInfo != null) {
            val scaleHT = (entity.hurtTime.toFloat() / entity.maxHurtTime.coerceAtLeast(1).toFloat()).coerceIn(0F, 1F)
            val headScale = 1f - scaleHT * 0.2f
            val headOff = 15f * scaleHT * 0.2f
            Stencil.write(false)
            RoundedUtil.drawRound(cx - 15f + headOff, cy - 15f + headOff, 30f * headScale, 30f * headScale, 15f * headScale, Color.WHITE)
            Stencil.erase(true)
            drawHead(
                playerInfo.locationSkin,
                cx - 15f + headOff, cy - 15f + headOff,
                headScale, 30, 30,
                1f, 0.4f + (1f - scaleHT) * 0.6f, 0.4f + (1f - scaleHT) * 0.6f,
                alpha
            )
            Stencil.dispose()
        }
        ArcRenderer.ring(cx, cy, 15f, 1.2f, Color(255, 255, 255, 46))

        // 信息区
        val infoX = (padLeft + ringZone + gap).toFloat()
        val infoRight = width - padRight
        Fonts.fontSemibold40.drawString(name, infoX, cy - 13f, getColor(Color(232, 234, 240)).rgb)
        val hpW = Fonts.fontSemibold40.getStringWidth(healthText)
        Fonts.fontSemibold40.drawString(healthText, infoRight - hpW, cy - 13f, getColor(Color(138, 255, 193)).rgb)

        val barY = cy + 3f
        val barW = infoRight - infoX
        RoundedUtil.drawRound(infoX, barY, barW, 5f, 2.5f, getColor(Color(255, 255, 255, 20)))
        val fillW = barW * ratio
        if (fillW > 0f) {
            RoundedUtil.applyGradientHorizontal(
                infoX, barY, fillW, 5f, 2.5f,
                getColor(Color(57, 198, 255)), getColor(Color(138, 255, 193))
            ) {}
        }
    }

    override fun getBorder(entity: EntityPlayer?): Border? {
        entity ?: return Border(0f, 0f, 184f, 58f)
        val maxHealth = entity.maxHealth.coerceAtLeast(1F)
        val healthText = decimalFormat2.format(entity.health.coerceIn(0F, maxHealth))
        return Border(0f, 0f, width(entity.name, healthText), height)
    }

    override fun handleBlur(entity: EntityPlayer) {
        val border = getBorder(entity) ?: return
        RoundedUtil.drawRound(0f, 0f, border.x2, border.y2, 16f, Color(255, 255, 255, 255))
    }

    override fun handleShadowCut(entity: EntityPlayer) = handleBlur(entity)

    override fun handleShadow(entity: EntityPlayer) {
        val border = getBorder(entity) ?: return
        RoundedUtil.drawRound(0f, 0f, border.x2, border.y2, 16f, shadowOpaque)
    }
}
