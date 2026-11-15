package net.airplus.ui.client.hud.element.elements.targets2.impl

import net.airplus.config.BoolValue
import net.airplus.config.IntValue
import net.airplus.config.ListValue
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.elements.Target2
import net.airplus.ui.client.hud.element.elements.targets2.TargetStyle
import net.airplus.ui.font.Fonts
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.render.RenderUtils.deltaTime
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.entity.player.EntityPlayer
import java.awt.Color
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Myau（移植自 Target.kt renderMyauHUD）：
 * 1px 边框 + 底色面板 + 头像/名称/❤血量/W-L 胜负与差值文本 + 底部血条。
 * 边框色支持 Rainbow/Custom；底色改用 Target2 的 Background 设置。
 */
class Myau(inst: Target2) : TargetStyle("Myau", inst, true) {

    private val rainbow = BoolValue("Myau-Rainbow", true).apply { setSupport { supported() } }
    private val borderRed = IntValue("Myau-Border-Red", 255, 0..255).apply { setSupport { supported() } }
    private val borderGreen = IntValue("Myau-Border-Green", 255, 0..255).apply { setSupport { supported() } }
    private val borderBlue = IntValue("Myau-Border-Blue", 255, 0..255).apply { setSupport { supported() } }
    private val showAvatar = BoolValue("Myau-Show-Avatar", true).apply { setSupport { supported() } }
    private val fontMode = ListValue("Font", arrayOf("Minecraft", "HarmonyOS"), "Minecraft").apply { setSupport { supported() } }

    private fun supported() = targetInstance.styleValueName.equals("myau", ignoreCase = true)

    private val fmt = DecimalFormat("0.0", DecimalFormatSymbols(Locale.ENGLISH))

    /** 彩虹色相位（原 Target.kt 全局 hue，每帧推进） */
    private var hue = 0.0f

    /** 每个目标独立的血条像素宽度缓动（原 easingHealth 语义） */
    private val barFills = HashMap<EntityPlayer, Float>()

    private fun getRainbowColor(): Color = Color.getHSBColor(hue, 1f, 1f)

    /** 原 Target.kt 的帧率自适应 lerp */
    private fun lerp(start: Float, end: Float, speed: Float): Float =
        start + (end - start) * speed * (deltaTime / (1000F / 60F))

    private fun drawFontString(text: String, x: Float, y: Float, color: Int) {
        when (fontMode.get()) {
            "Minecraft" -> mc.fontRendererObj.drawString(text, x.toInt(), y.toInt(), color)
            "HarmonyOS" -> Fonts.font40.drawString(text, x, y, color)
        }
    }

    private fun getFontWidth(text: String): Int {
        return when (fontMode.get()) {
            "Minecraft" -> mc.fontRendererObj.getStringWidth(text)
            "HarmonyOS" -> Fonts.font40.getStringWidth(text)
            else -> mc.fontRendererObj.getStringWidth(text)
        }
    }

    override fun drawTarget(entity: EntityPlayer) {
        hue += 0.05f * deltaTime * 0.1f
        if (hue > 1F) hue = 0F

        val nameWidth = Fonts.font35.getStringWidth(entity.name)
        val hudWidth = maxOf(80f, nameWidth + 20f)
        val hudHeight = 25f
        val avatarSize = hudHeight

        val borderColor = if (rainbow.get()) getRainbowColor() else Color(borderRed.get(), borderGreen.get(), borderBlue.get())
        val healthBarColor = if (rainbow.get()) getRainbowColor() else Color(
            maxOf(borderRed.get() - 50, 0),
            maxOf(borderGreen.get() - 50, 0),
            maxOf(borderBlue.get() - 50, 0)
        )

        val totalWidth = if (showAvatar.get()) hudWidth + avatarSize else hudWidth

        // 1px 边框（上/下/左/右）
        RenderUtils.drawRect(-1f, -1f, totalWidth + 1f, 0f, getColor(borderColor).rgb)
        RenderUtils.drawRect(-1f, hudHeight, totalWidth + 1f, hudHeight + 1f, getColor(borderColor).rgb)
        RenderUtils.drawRect(-1f, 0f, 0f, hudHeight, getColor(borderColor).rgb)
        RenderUtils.drawRect(totalWidth, 0f, totalWidth + 1f, hudHeight, getColor(borderColor).rgb)
        // 底色（原 Color(0,0,0,100)）改用 Target2 的 Background 设置
        RenderUtils.drawRect(0f, 0f, totalWidth, hudHeight, targetInstance.bgColor.rgb)

        if (showAvatar.get()) {
            mc.netHandler.getPlayerInfo(entity.uniqueID)?.locationSkin?.let {
                drawHead(it, 0, 0, avatarSize.toInt(), avatarSize.toInt())
            }
        }

        val textX = if (showAvatar.get()) avatarSize + 3 else 3f
        drawFontString(entity.name, textX, 1f, getColor(Color.WHITE).rgb)
        val healthText = fmt.format(entity.health)
        drawFontString(healthText, textX, 11f, getColor(healthBarColor).rgb)
        drawFontString("\u2764", textX + getFontWidth(healthText) + 2, 11f, getColor(healthBarColor).rgb)

        val barY = 21f
        val barWidth = hudWidth - 5f
        RenderUtils.drawRect(textX, barY, textX + barWidth, barY + 3, getColor(Color(64, 64, 64)).rgb)
        val targetFill = (entity.health / entity.maxHealth) * barWidth
        val cur = barFills.getOrPut(entity) { targetFill }
        barFills[entity] = lerp(cur, targetFill, 0.1f)
        RenderUtils.drawRect(textX, barY, textX + barFills[entity]!!, barY + 3, getColor(healthBarColor).rgb)

        val playerHealth = mc.thePlayer.health
        val (winLoss, wlColor) = when {
            playerHealth > entity.health -> "W" to Color(0, 255, 0)
            playerHealth < entity.health -> "L" to Color(255, 0, 0)
            else -> "D" to Color(255, 255, 0)
        }
        drawFontString(winLoss, totalWidth - getFontWidth(winLoss) - 1, 1f, getColor(wlColor).rgb)

        val diff = playerHealth - entity.health
        val diffText = if (diff > 0) "+${fmt.format(diff)}" else fmt.format(diff)
        val diffColor = when {
            diff > 0 -> Color(0, 255, 0)
            diff < 0 -> Color(255, 0, 0)
            else -> Color(255, 255, 0)
        }
        drawFontString(diffText, maxOf(totalWidth - getFontWidth(diffText) - 1, textX), 11f, getColor(diffColor).rgb)
    }

    override fun handleBlur(entity: EntityPlayer) {
        val nameWidth = Fonts.font35.getStringWidth(entity.name)
        val hudWidth = maxOf(80f, nameWidth + 20f)
        val totalWidth = if (showAvatar.get()) hudWidth + 25f else hudWidth
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        RenderUtils.quickDrawRect(0f, 0f, totalWidth, 25f)
        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
    }

    override fun handleShadowCut(entity: EntityPlayer) = handleBlur(entity)

    override fun handleShadow(entity: EntityPlayer) {
        val nameWidth = Fonts.font35.getStringWidth(entity.name)
        val hudWidth = maxOf(80f, nameWidth + 20f)
        val totalWidth = if (showAvatar.get()) hudWidth + 25f else hudWidth
        RenderUtils.quickDrawRect(0f, 0f, totalWidth, 25f, shadowOpaque.rgb)
    }

    override fun getBorder(entity: EntityPlayer?): Border {
        entity ?: return Border(0f, 0f, 120f, 25f)
        val nameWidth = Fonts.font35.getStringWidth(entity.name)
        val hudWidth = maxOf(80f, nameWidth + 20f)
        val totalWidth = if (showAvatar.get()) hudWidth + 25f else hudWidth
        return Border(0f, 0f, totalWidth, 25f)
    }

    override fun cleanup(alive: Set<EntityPlayer>) {
        barFills.keys.removeAll { it !in alive }
    }
}
