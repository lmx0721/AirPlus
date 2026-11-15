package net.airplus.ui.client.hud.element.elements.targets2.impl

import net.airplus.config.BoolValue
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
 * Styles（移植自 Target.kt renderStylesHUD）：
 * 底色矩形 + 头像 + 名称/Health/Distance 文本 + 底部两条（血量/护甲）细条。
 * 可选 Styles-Shadow 外发光；底色改用 Target2 的 Background 设置。
 */
class Styles(inst: Target2) : TargetStyle("Styles", inst, true) {

    private val stylesShadow = BoolValue("Styles-Shadow", true).apply { setSupport { supported() } }

    private fun supported() = targetInstance.styleValueName.equals("styles", ignoreCase = true)

    private val fmt = DecimalFormat("0.0", DecimalFormatSymbols(Locale.ENGLISH))

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

    private fun width(entity: EntityPlayer): Float =
        (38 + Fonts.fontRegular35.getStringWidth(entity.name)).coerceAtLeast(118).toFloat()

    override fun drawTarget(entity: EntityPlayer) {
        val width = width(entity)
        val height = 44f

        updateEasingHealth(entity)

        if (stylesShadow.get()) {
            showShadow(0f, 0f, width + 14f, height, 0.3F)
        }

        // 底色（原 Color(0,0,0,120)）改用 Target2 的 Background 设置
        RenderUtils.drawRect(0f, 0f, width + 14f, height, targetInstance.bgColor.rgb)

        mc.netHandler.getPlayerInfo(entity.uniqueID)?.let {
            RenderUtils.drawHead(it.locationSkin, 3, 3, 30, 30, Color.WHITE)
        }

        Fonts.fontRegular35.drawString(entity.name, 34.5f, 4f, getColor(Color.WHITE).rgb)
        Fonts.fontRegular35.drawString("Health: ${fmt.format(easingOf(entity))}", 34.5f, 14f, getColor(Color.WHITE).rgb)
        Fonts.fontRegular35.drawString("Distance: ${fmt.format(mc.thePlayer.getDistanceToEntity(entity))}m", 34.5f, 24f, getColor(Color.WHITE).rgb)

        RenderUtils.drawRect(2.5f, 35.5f, width + 11.5f, 37.5f, getColor(Color(0, 0, 0, 200)).rgb)
        RenderUtils.drawRect(
            3f,
            36f,
            3f + (easingOf(entity) / entity.maxHealth) * (width + 8.5f),
            37f,
            getColor(Color(0, 255, 150)).rgb
        )

        RenderUtils.drawRect(2.5f, 39.5f, width + 11.5f, 41.5f, getColor(Color(0, 0, 0, 200)).rgb)
        RenderUtils.drawRect(
            3f,
            40f,
            3f + (entity.totalArmorValue / 20f) * (width + 8.5f),
            41f,
            getColor(Color(77, 128, 255)).rgb
        )
    }

    override fun handleBlur(entity: EntityPlayer) {
        val width = width(entity)
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        RenderUtils.drawRoundedRect(0f, 0f, width + 14f, 44f, -1, 3f)
        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
    }

    override fun handleShadowCut(entity: EntityPlayer) = handleBlur(entity)

    override fun handleShadow(entity: EntityPlayer) {
        val width = width(entity)
        RenderUtils.originalRoundedRect(0f, 0f, width + 14f, 44f, 3f, shadowOpaque.rgb)
    }

    override fun getBorder(entity: EntityPlayer?): Border {
        entity ?: return Border(0f, 0f, 146f, 44f)
        return Border(0f, 0f, width(entity) + 14f, 44f)
    }

    override fun cleanup(alive: Set<EntityPlayer>) {
        easingHealths.keys.removeAll { it !in alive }
    }
}
