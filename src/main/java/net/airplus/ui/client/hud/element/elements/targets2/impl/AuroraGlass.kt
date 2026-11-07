package net.airplus.ui.client.hud.element.elements.targets2.impl

import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.elements.Target2
import net.airplus.ui.client.hud.element.elements.targets2.TargetStyle
import net.airplus.ui.font.Fonts
import net.airplus.utils.render.RoundedUtil
import net.minecraft.entity.player.EntityPlayer
import java.awt.Color
import kotlin.math.max
import kotlin.math.min

/**
 * "Aurora Glass" — 毛玻璃 + 流光渐变血条 (from TargetHUD2-NewStyles.html style 01).
 * Frosted panel (no avatar), white hairline border, and a 7-stop rainbow
 * health bar scrolling continuously (4s loop, 300% background).
 */
class AuroraGlass(inst: Target2) : TargetStyle("Aurora Glass", inst, true) {

    private val padX = 10
    private val padTop = 8
    private val padBottom = 9
    private val minWidth = 190f

    // #fc6042 -> #ff9a5a -> #ffd166 -> #8affc1 -> #39c6ff -> #8a7bff -> (wrap) #fc6042
    private val palette = arrayOf(
        Color(252, 96, 66),
        Color(255, 154, 90),
        Color(255, 209, 102),
        Color(138, 255, 193),
        Color(57, 198, 255),
        Color(138, 123, 255)
    )

    private fun sample(t: Float): Color {
        val p = (((t % 1f) + 1f) % 1f) * palette.size
        val i = p.toInt() % palette.size
        val f = p - p.toInt()
        val a = palette[i]
        val b = palette[(i + 1) % palette.size]
        return Color(
            (a.red + (b.red - a.red) * f).toInt(),
            (a.green + (b.green - a.green) * f).toInt(),
            (a.blue + (b.blue - a.blue) * f).toInt()
        )
    }

    private fun height() = padTop + 11f + 8f + 8f + padBottom

    override fun drawTarget(entity: EntityPlayer) {
        updateAnim(entity.health)
        val alpha = 1F - targetInstance.getFadeProgress()
        if (alpha <= 0F) return

        val name = entity.name
        val maxHealth = entity.maxHealth.coerceAtLeast(1F)
        val healthText = decimalFormat2.format(easingHealth.coerceIn(0F, maxHealth))
        val nameW = Fonts.fontSemibold40.getStringWidth(name)
        val hpW = Fonts.fontSemibold40.getStringWidth(healthText)
        val width = max(minWidth, padX + nameW + 20f + hpW + padX)
        val height = height()

        // 边框（1px 白色 hairline）+ 毛玻璃底 + 白色高光渐变
        RoundedUtil.drawRound(0f, 0f, width, height, 14f, getColor(Color(255, 255, 255, 46)))
        RoundedUtil.drawRound(1f, 1f, width - 2f, height - 2f, 13f, getColor(Color(8, 12, 22, 150)))
        RoundedUtil.applyGradientHorizontal(
            1f, 1f, width - 2f, height - 2f, 13f,
            getColor(Color(255, 255, 255, 31)), getColor(Color(255, 255, 255, 10))
        ) {}

        // 顶部行：名字 + 血量数值
        Fonts.fontSemibold40.drawString(name, padX.toFloat(), padTop + 1f, getColor(Color(232, 234, 240)).rgb)
        Fonts.fontSemibold40.drawString(healthText, width - padX - hpW, padTop + 1f, getColor(Color(232, 234, 240)).rgb)

        // 血条：黑色轨道 + 流光渐变（300% 循环，4s）
        val barY = padTop + 11f + 8f
        val barW = width - padX * 2f
        RoundedUtil.drawRound(padX.toFloat(), barY, barW, 8f, 4f, getColor(Color(0, 0, 0, 85)))
        val ratio = (easingHealth / maxHealth).coerceIn(0f, 1f)
        val fillW = barW * ratio
        if (fillW > 1f) {
            val shift = (System.currentTimeMillis() % 4000L) / 4000f
            var x = 0f
            while (x < fillW) {
                val segW = min(2f, fillW - x)
                val color = sample((x / (width * 3f)) + shift)
                RoundedUtil.drawRound(padX + x, barY, segW, 8f, 4f, getColor(color))
                x += 2f
            }
        }
    }

    override fun getBorder(entity: EntityPlayer?): Border? {
        entity ?: return Border(0f, 0f, minWidth, height())
        val maxHealth = entity.maxHealth.coerceAtLeast(1F)
        val healthText = decimalFormat2.format(entity.health.coerceIn(0F, maxHealth))
        val nameW = Fonts.fontSemibold40.getStringWidth(entity.name)
        val hpW = Fonts.fontSemibold40.getStringWidth(healthText)
        val width = max(minWidth, padX + nameW + 20f + hpW + padX)
        return Border(0f, 0f, width, height())
    }

    override fun handleBlur(entity: EntityPlayer) {
        val border = getBorder(entity) ?: return
        RoundedUtil.drawRound(0f, 0f, border.x2, border.y2, 14f, Color(255, 255, 255, 255))
    }

    override fun handleShadowCut(entity: EntityPlayer) = handleBlur(entity)

    override fun handleShadow(entity: EntityPlayer) {
        val border = getBorder(entity) ?: return
        RoundedUtil.drawRound(0f, 0f, border.x2, border.y2, 14f, shadowOpaque)
    }
}
