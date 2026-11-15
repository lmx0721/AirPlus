package net.airplus.ui.client.hud.element.elements.targets2.impl

import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.elements.Target2
import net.airplus.ui.client.hud.element.elements.targets2.TargetStyle
import net.airplus.ui.font.Fonts
import net.airplus.utils.GlowUtils
import net.airplus.utils.render.RenderUtils
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.entity.player.EntityPlayer
import java.awt.Color
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.pow

/**
 * Naven2（移植自 Target.kt renderNavenHUD）：
 * 130x50 圆角面板 + 头像 + 名称/Health/Distance 文本 + 底部血条，自带黑色外发光。
 * 面板底色改用 Target2 的 Background 设置；血条填充保持原红棕色。
 */
class Naven2(inst: Target2) : TargetStyle("Naven2", inst, true) {

    private val fmt2 = DecimalFormat("0.00", DecimalFormatSymbols(Locale.ENGLISH))

    /** 每个目标独立的健康值缓动（公式与基类 updateAnim 一致，多目标下各卡片互不干扰） */
    private val easingHealths = HashMap<EntityPlayer, Float>()

    /** 原样移植的 Styles/Naven 系列黑色外发光（GlowUtils） */
    private fun showShadow(startX: Float, startY: Float, width: Float, height: Float, shadowStrengh: Float) {
        GlowUtils.drawGlow(
            startX, startY,
            width, height,
            (shadowStrengh * 13F).toInt(),
            Color(0, 0, 0, 100)
        )
    }

    private fun updateEasingHealth(entity: EntityPlayer) {
        easingHealths.keys.removeAll { it !== entity && (it.isDead || !it.isEntityAlive) }
        val target = entity.health
        if (targetInstance.noAnimValue) {
            easingHealths[entity] = target
            return
        }
        val cur = easingHealths.getOrPut(entity) { target }
        easingHealths[entity] = cur + ((target - cur) / 2.0F.pow(10.0F - targetInstance.globalAnimSpeed)) * RenderUtils.deltaTime
    }

    private fun easingOf(entity: EntityPlayer): Float = easingHealths.getOrPut(entity) { entity.health }

    override fun drawTarget(entity: EntityPlayer) {
        val width = 130f
        val height = 50f

        updateEasingHealth(entity)

        showShadow(0F, 0F, width, height, 0.3F)

        // 底色（原 Color(30,30,30,180)）改用 Target2 的 Background 设置
        RenderUtils.drawRoundedRect(0F, 0F, width, height, targetInstance.bgColor.rgb, 5f)

        mc.netHandler.getPlayerInfo(entity.uniqueID)?.locationSkin?.let {
            RenderUtils.drawHead(it, 7, 7, 30, 30, Color.WHITE)
        }

        val barX1 = 5f
        val barY1 = height - 10f
        val barX2 = width - 5f
        val barY2 = barY1 + 3f
        RenderUtils.drawRoundedRect(barX1, barY1, barX2, barY2, getColor(Color(0, 0, 0, 200)).rgb, 2f)

        val healthPercent = easingOf(entity) / entity.maxHealth
        val fillX2 = barX1 + (barX2 - barX1) * healthPercent
        RenderUtils.drawRoundedRect(barX1, barY1, fillX2, barY2, getColor(Color(160, 42, 42)).rgb, 2f)

        Fonts.fontRegular35.drawString(entity.name, 40f, 10f, getColor(Color.WHITE).rgb)
        Fonts.fontRegular35.drawString("Health: ${fmt2.format(easingOf(entity))}", 40f, 22f, getColor(Color.WHITE).rgb)
        Fonts.fontRegular35.drawString("Distance: ${fmt2.format(entity.getDistanceToEntity(mc.thePlayer))}", 40f, 30f, getColor(Color.WHITE).rgb)
    }

    override fun handleBlur(entity: EntityPlayer) {
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        RenderUtils.drawRoundedRect(0F, 0F, 130F, 50F, -1, 5F)
        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
    }

    override fun handleShadowCut(entity: EntityPlayer) = handleBlur(entity)

    override fun handleShadow(entity: EntityPlayer) {
        RenderUtils.originalRoundedRect(0F, 0F, 130F, 50F, 5F, shadowOpaque.rgb)
    }

    override fun getBorder(entity: EntityPlayer?): Border = Border(0F, 0F, 130F, 50F)

    override fun cleanup(alive: Set<EntityPlayer>) {
        easingHealths.keys.removeAll { it !in alive }
    }
}
