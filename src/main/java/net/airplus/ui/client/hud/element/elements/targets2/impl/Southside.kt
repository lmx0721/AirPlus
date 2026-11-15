package net.airplus.ui.client.hud.element.elements.targets2.impl

import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.elements.Target2
import net.airplus.ui.client.hud.element.elements.targets2.TargetStyle
import net.airplus.ui.font.Fonts
import net.airplus.utils.render.ColorUtils
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.render.RenderUtils.deltaTime
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.RenderHelper
import net.minecraft.entity.player.EntityPlayer
import java.awt.Color

/**
 * Southside（移植自 Target.kt renderSouthsideHUD）：
 * 底色矩形 + 缓动血量白条 + 左侧血量色条 + 头像（受击红染）+ 名称/HP 文本 + 右侧手持物放大绘制。
 * 底色改用 Target2 的 Background 设置。
 */
class Southside(inst: Target2) : TargetStyle("Southside", inst, true) {

    /** 每个目标独立的缓动状态（原 southsideEasingHealth + easingHurtTime） */
    private class SouthsideState {
        var easingHealth = 0F
        var easingHurtTime = 0F
    }

    private val states = HashMap<EntityPlayer, SouthsideState>()

    /** 原样移植的帧率自适应 lerp */
    private fun lerp(start: Float, end: Float, speed: Float): Float =
        start + (end - start) * speed * (deltaTime / (1000F / 60F))

    /** 原 updateSouthsideEasingHealth 的非线性缓动算法，原样保留 */
    private fun updateSouthsideEasingHealth(st: SouthsideState, targetHealth: Float, maxHealth: Float) {
        val percent = targetHealth / maxHealth
        var speedMultiplier = 1f
        if (percent > 0.75f) {
            speedMultiplier = 2.25f
        } else if (percent > 0.5f) {
            speedMultiplier = 1.75f
        } else if (percent > 0.25f) {
            speedMultiplier = 1.5f
        }

        st.easingHealth += (targetHealth - st.easingHealth) * 0.1f * speedMultiplier
    }

    private fun updateHurtTime(st: SouthsideState, entity: EntityPlayer) {
        val targetHurtTime = if (entity.isEntityAlive) entity.hurtTime.toFloat() else 0f
        st.easingHurtTime = lerp(st.easingHurtTime, targetHurtTime, 0.5f)
    }

    private fun getHurtColor(st: SouthsideState, baseColor: Color): Color {
        val scale = 1 - st.easingHurtTime / 10f
        return ColorUtils.interpolateColor(Color.RED, baseColor, scale)
    }

    override fun drawTarget(entity: EntityPlayer) {
        val health = entity.health
        val maxHealth = entity.maxHealth
        val healthPercent = (health / maxHealth).coerceIn(0f, 1f)

        val st = states.getOrPut(entity) { SouthsideState().apply { easingHealth = health } }
        states.keys.removeAll { it !== entity && (it.isDead || !it.isEntityAlive) }
        updateSouthsideEasingHealth(st, health, maxHealth)
        val easingHealthPercent = (st.easingHealth / maxHealth).coerceIn(0f, 1f)
        updateHurtTime(st, entity)

        val name = entity.name
        val width = Fonts.fontSemibold40.getStringWidth(name) + 75f
        val presentWidth = easingHealthPercent * width
        val height = 40f

        // 底色（原 Color(0,0,0,100)）改用 Target2 的 Background 设置
        RenderUtils.drawRect(0f, 0f, width, height, targetInstance.bgColor.rgb)
        RenderUtils.drawRect(0f, 0f, presentWidth, height, getColor(Color(230, 230, 230, 100)).rgb)

        val healthColor = when {
            healthPercent > 0.5 -> Color(63, 157, 4, 150)
            healthPercent > 0.25 -> Color(255, 144, 2, 150)
            else -> Color(168, 1, 1, 150)
        }
        RenderUtils.drawRect(0f, 12.5f, 3f, 27.5f, getColor(healthColor).rgb)

        mc.netHandler.getPlayerInfo(entity.uniqueID)?.let {
            RenderUtils.drawHead(it.locationSkin, 7, 7, 26, 26, getHurtColor(st, Color.WHITE))
        } ?: RenderUtils.drawRect(6f, 6f, 34f, 34f, Color.BLACK.rgb)

        Fonts.fontSemibold40.drawString(name, 40f, 7f, getColor(Color(200, 200, 200, 255)).rgb)
        Fonts.fontSemibold40.drawString("${health.toInt()} HP", 40f, 22f, getColor(Color(200, 200, 200, 255)).rgb)

        val itemStack = entity.heldItem
        val itemX = Fonts.fontSemibold40.getStringWidth(name) + 50
        if (itemStack != null) {
            GlStateManager.pushMatrix()
            GlStateManager.translate(itemX.toDouble(), 12.0, 0.0)
            GlStateManager.scale(1.5f, 1.5f, 1.5f)
            RenderHelper.enableGUIStandardItemLighting()
            mc.renderItem.renderItemAndEffectIntoGUI(itemStack, 0, 0)
            RenderHelper.disableStandardItemLighting()
            GlStateManager.popMatrix()
        } else {
            Fonts.fontSemibold40.drawString(
                "?",
                Fonts.fontSemibold40.getStringWidth(name) + 55f,
                11f,
                getColor(Color(200, 200, 200, 255)).rgb
            )
        }
    }

    override fun handleBlur(entity: EntityPlayer) {
        val width = Fonts.fontSemibold40.getStringWidth(entity.name) + 75f
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        RenderUtils.quickDrawRect(0f, 0f, width, 40f)
        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
    }

    override fun handleShadowCut(entity: EntityPlayer) = handleBlur(entity)

    override fun handleShadow(entity: EntityPlayer) {
        val width = Fonts.fontSemibold40.getStringWidth(entity.name) + 75f
        RenderUtils.quickDrawRect(0f, 0f, width, 40f, shadowOpaque.rgb)
    }

    override fun getBorder(entity: EntityPlayer?): Border {
        entity ?: return Border(0f, 0f, 150f, 40f)
        val width = Fonts.fontSemibold40.getStringWidth(entity.name) + 75f
        return Border(0f, 0f, width, 40f)
    }

    override fun cleanup(alive: Set<EntityPlayer>) {
        states.keys.removeAll { it !in alive }
    }
}
