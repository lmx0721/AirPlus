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
 * Flux2（移植自 Target.kt renderFluxHUD）：
 * 140x46 圆角面板 + 圆角头像 + 名称/血量数值/距离 + 横条血条。
 * 血条颜色支持 Custom/Health/Rainbow；面板底色改用 Target2 的 Background 设置。
 */
class Flux2(inst: Target2) : TargetStyle("Flux2", inst, true) {

    private val fluxColorMode = ListValue("Flux-Color", arrayOf("Custom", "Health", "Rainbow"), "Health").apply {
        setSupport { supported() }
    }
    private val fluxColorRed = IntValue("Flux-Red", 0, 0..255).apply { setSupport { supported() && fluxColorMode.get() == "Custom" } }
    private val fluxColorGreen = IntValue("Flux-Green", 120, 0..255).apply { setSupport { supported() && fluxColorMode.get() == "Custom" } }
    private val fluxColorBlue = IntValue("Flux-Blue", 255, 0..255).apply { setSupport { supported() && fluxColorMode.get() == "Custom" } }
    private val barColorMode = ListValue("Bar-Color", arrayOf("Health", "Theme"), "Health").apply { setSupport { supported() } }
    private val fontMode = ListValue("Font", arrayOf("Minecraft", "HarmonyOS"), "Minecraft").apply { setSupport { supported() } }

    private fun supported() = targetInstance.styleValueName.equals("flux2", ignoreCase = true)

    private val fmt = DecimalFormat("0.0", DecimalFormatSymbols(Locale.ENGLISH))

    /** 彩虹色相位（原 Target.kt 全局 hue，每帧推进） */
    private var hue = 0.0f

    /** 每个目标独立的健康值缓动（公式与基类 updateAnim 一致，多目标下各卡片互不干扰） */
    private val easingHealths = HashMap<EntityPlayer, Float>()

    private fun themedHealthColor(health: Float, maxHealth: Float): Color =
        if (barColorMode.get() == "Theme") ClientThemesUtils.getColor() else ColorUtils.getHealthColor(health, maxHealth)

    private fun updateEasingHealth(entity: EntityPlayer) {
        // 清理已死亡且非当前绘制目标的条目，避免单目标淡出期间状态丢失
        easingHealths.keys.removeAll { it !== entity && (it.isDead || !it.isEntityAlive) }
        val target = entity.health
        if (targetInstance.noAnimValue) {
            easingHealths[entity] = target
            return
        }
        val cur = easingHealths.getOrPut(entity) { target }
        easingHealths[entity] = cur + ((target - cur) / 2.0F.pow(10.0F - targetInstance.globalAnimSpeed)) * deltaTime
    }

    private fun easingOf(entity: EntityPlayer): Float = easingHealths.getOrPut(entity) { entity.health }

    private fun drawFontString(text: String, x: Float, y: Float, color: Int) {
        when (fontMode.get()) {
            "Minecraft" -> mc.fontRendererObj.drawString(text, x.toInt(), y.toInt(), color)
            "HarmonyOS" -> Fonts.font40.drawString(text, x, y, color)
        }
    }

    override fun drawTarget(entity: EntityPlayer) {
        hue += 0.05f * deltaTime * 0.1f
        if (hue > 1F) hue = 0F

        val width = 140F
        val height = 46F

        updateEasingHealth(entity)

        RenderUtils.drawRoundedRect(0F, 0F, width, height, targetInstance.bgColor.rgb, 4F)

        mc.netHandler.getPlayerInfo(entity.uniqueID)?.let {
            drawHead(it.locationSkin, 6, 6, 34, 34)
        }

        drawFontString(entity.name, 46F, 8F, getColor(Color.WHITE).rgb)

        val healthPercent = (easingOf(entity) / entity.maxHealth).coerceIn(0F, 1F)
        val healthBarWidth = (width - 52) * healthPercent
        val barColor = when (fluxColorMode.get()) {
            "Custom" -> Color(fluxColorRed.get(), fluxColorGreen.get(), fluxColorBlue.get())
            "Rainbow" -> Color.getHSBColor(hue, 0.7f, 0.9f)
            else -> themedHealthColor(entity.health, entity.maxHealth)
        }

        RenderUtils.drawRect(46F, 22F, width - 6F, 30F, getColor(Color(45, 45, 45)).rgb)
        RenderUtils.drawRect(46F, 22F, 46F + healthBarWidth, 30F, getColor(barColor).rgb)

        drawFontString(fmt.format(easingOf(entity)), 48F, 23F, getColor(Color.WHITE).rgb)

        val distance = mc.thePlayer.getDistanceToEntity(entity)
        drawFontString("${fmt.format(distance)}m", 46F, 33F, getColor(Color(200, 200, 200)).rgb)
    }

    override fun handleBlur(entity: EntityPlayer) {
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        RenderUtils.drawRoundedRect(0F, 0F, 140F, 46F, -1, 3F)
        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
    }

    override fun handleShadowCut(entity: EntityPlayer) = handleBlur(entity)

    override fun handleShadow(entity: EntityPlayer) {
        RenderUtils.originalRoundedRect(0F, 0F, 140F, 46F, 4F, shadowOpaque.rgb)
    }

    override fun getBorder(entity: EntityPlayer?): Border = Border(0F, 0F, 140F, 46F)

    override fun cleanup(alive: Set<EntityPlayer>) {
        easingHealths.keys.removeAll { it !in alive }
    }
}
