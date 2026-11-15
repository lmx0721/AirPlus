package net.airplus.ui.client.hud.element.elements.targets2.impl

import net.airplus.config.IntValue
import net.airplus.config.ListValue
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.elements.Target2
import net.airplus.ui.client.hud.element.elements.targets2.TargetStyle
import net.airplus.ui.font.Fonts
import net.airplus.utils.client.ClientThemesUtils
import net.airplus.utils.render.ColorUtils
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.render.RenderUtils.deltaTime
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.entity.player.EntityPlayer
import java.awt.Color
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.pow

/**
 * RavenB4（移植自 Target.kt renderRavenB4HUD）：
 * 渐变描边圆角面板 + 显示名/W-L/血量文本 + 底部渐变血条。
 * 描边与血条颜色为样式专属设置；底色改用 Target2 的 Background 设置。
 */
class RavenB4(inst: Target2) : TargetStyle("RavenB4", inst, true) {

    private val barColorR = IntValue("RavenB4-BarColorR", 255, 0..255).apply { setSupport { supported() } }
    private val barColorG = IntValue("RavenB4-BarColorG", 255, 0..255).apply { setSupport { supported() } }
    private val barColorB = IntValue("RavenB4-BarColorB", 255, 0..255).apply { setSupport { supported() } }
    private val animSpeedRB4 = IntValue("RavenB4-AnimSpeed", 3, 1..10).apply { setSupport { supported() } }
    private val barColorMode = ListValue("Bar-Color", arrayOf("Health", "Theme"), "Health").apply { setSupport { supported() } }
    private val fontMode = ListValue("Font", arrayOf("Minecraft", "HarmonyOS"), "Minecraft").apply { setSupport { supported() } }

    private fun supported() = targetInstance.styleValueName.equals("ravenb4", ignoreCase = true)

    private val fmt = DecimalFormat("0.0", DecimalFormatSymbols(Locale.ENGLISH))

    /** 每个目标独立的健康值缓动（速度取 RavenB4-AnimSpeed，公式同原 updateRavenB4Anim） */
    private val easingHealths = HashMap<EntityPlayer, Float>()

    private fun themedHealthColor(health: Float, maxHealth: Float): Color =
        if (barColorMode.get() == "Theme") ClientThemesUtils.getColor() else ColorUtils.getHealthColor(health, maxHealth)

    private fun updateEasingHealth(entity: EntityPlayer) {
        easingHealths.keys.removeAll { it !== entity && (it.isDead || !it.isEntityAlive) }
        val target = entity.health
        if (targetInstance.noAnimValue) {
            easingHealths[entity] = target
            return
        }
        val cur = easingHealths.getOrPut(entity) { target }
        easingHealths[entity] = cur + ((target - cur) / 2.0F.pow(10.0F - animSpeedRB4.get())) * deltaTime
    }

    private fun easingOf(entity: EntityPlayer): Float = easingHealths.getOrPut(entity) { entity.health }

    private fun font() = when (fontMode.get()) {
        "Minecraft" -> mc.fontRendererObj
        "HarmonyOS" -> Fonts.font40
        else -> mc.fontRendererObj
    }

    /** 面板尺寸：显示名宽度 + 血量文本宽度 + 23F，高 35F */
    private fun dims(entity: EntityPlayer): Pair<Float, Float> {
        val font = font()
        val hp = fmt.format(entity.health)
        val hplength = font.getStringWidth(hp)
        val length = font.getStringWidth(entity.displayName.formattedText)
        return (length + hplength + 23F) to 35F
    }

    override fun drawTarget(entity: EntityPlayer) {
        val font = font()
        val hp = fmt.format(entity.health)
        val hplength = font.getStringWidth(hp)
        val length = font.getStringWidth(entity.displayName.formattedText)
        val barColor = Color(barColorR.get(), barColorG.get(), barColorB.get())
        val totalWidth = length + hplength + 23F
        val totalHeight = 35F

        updateEasingHealth(entity)
        RenderUtils.drawRoundedGradientOutlineCorner(0F, 0F, totalWidth, totalHeight, 2F, 8F, barColor.rgb, barColor.rgb)
        // 底色（原 Color(0,0,0,100)）改用 Target2 的 Background 设置
        RenderUtils.drawRoundedRect(0F, 0F, totalWidth, totalHeight, targetInstance.bgColor.rgb, 4F)
        GlStateManager.enableBlend()
        font.drawStringWithShadow(entity.displayName.formattedText, 6F, 8F, getColor(Color.WHITE).rgb)

        val winOrLose = if (entity.health < mc.thePlayer.health) "W" else "L"
        val wlColor = if (winOrLose == "W") Color(0, 255, 0) else Color(139, 0, 0)
        font.drawStringWithShadow(winOrLose, length + hplength + 11.6F, 8F, getColor(wlColor).rgb)

        font.drawStringWithShadow(
            hp,
            length + 8F,
            8F,
            getColor(ColorUtils.reAlpha(themedHealthColor(entity.health, entity.maxHealth), 255)).rgb
        )

        GlStateManager.disableAlpha()
        GlStateManager.disableBlend()
        RenderUtils.drawRoundedRect(
            5.0F,
            29.55F,
            length + hplength + 18F,
            25F,
            getColor(Color(0, 0, 0, 110)).rgb,
            2F
        )
        RenderUtils.drawRoundedGradientRectCorner(
            5F,
            25F,
            5F + (easingOf(entity) / entity.maxHealth) * (length + hplength + 13F),
            29.5F,
            4F,
            getColor(barColor).rgb,
            getColor(barColor).rgb
        )
    }

    override fun handleBlur(entity: EntityPlayer) {
        val (w, h) = dims(entity)
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        RenderUtils.drawRoundedRect(0F, 0F, w, h, -1, 4F)
        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
    }

    override fun handleShadowCut(entity: EntityPlayer) = handleBlur(entity)

    override fun handleShadow(entity: EntityPlayer) {
        val (w, h) = dims(entity)
        RenderUtils.originalRoundedRect(0F, 0F, w, h, 4F, shadowOpaque.rgb)
    }

    override fun getBorder(entity: EntityPlayer?): Border {
        entity ?: return Border(0F, 0F, 150F, 35F)
        val (w, h) = dims(entity)
        return Border(0F, 0F, w, h)
    }

    override fun cleanup(alive: Set<EntityPlayer>) {
        easingHealths.keys.removeAll { it !in alive }
    }
}
