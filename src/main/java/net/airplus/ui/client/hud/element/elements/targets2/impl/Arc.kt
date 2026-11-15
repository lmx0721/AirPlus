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
import org.lwjgl.opengl.GL11
import java.awt.Color
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Arc（移植自 Target.kt renderArcHUD）：
 * 圆形头像 + 环形血条 + 右侧名称/HP 文本。
 * 环底色改用 Target2 的 Background 设置；血环颜色支持 Rainbow/Custom。
 */
class Arc(inst: Target2) : TargetStyle("Arc", inst, true) {

    private val arcRainbow = BoolValue("Arc-Rainbow", true).apply { setSupport { supported() } }
    private val arcColorRed = IntValue("Arc-Red", 255, 0..255).apply { setSupport { supported() && !arcRainbow.get() } }
    private val arcColorGreen = IntValue("Arc-Green", 255, 0..255).apply { setSupport { supported() && !arcRainbow.get() } }
    private val arcColorBlue = IntValue("Arc-Blue", 255, 0..255).apply { setSupport { supported() && !arcRainbow.get() } }
    private val fontMode = ListValue("Font", arrayOf("Minecraft", "HarmonyOS"), "Minecraft").apply { setSupport { supported() } }

    private fun supported() = targetInstance.styleValueName.equals("arc", ignoreCase = true)

    private val fmt = DecimalFormat("0.0", DecimalFormatSymbols(Locale.ENGLISH))

    /** 彩虹色相位（原 Target.kt 全局 hue，每帧推进） */
    private var hue = 0.0f

    /** 每个目标独立的健康值缓动（公式与基类 updateAnim 一致，多目标下各卡片互不干扰） */
    private val easingHealths = HashMap<EntityPlayer, Float>()

    private fun updateEasingHealth(entity: EntityPlayer) {
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

    /** 原样移植的圆弧线段绘制 */
    private fun drawCircleArc(x: Float, y: Float, radius: Float, lineWidth: Float, startAngle: Float, endAngle: Float, color: Color) {
        GL11.glPushMatrix()
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glDisable(GL11.GL_TEXTURE_2D)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glEnable(GL11.GL_LINE_SMOOTH)
        GL11.glLineWidth(lineWidth)

        GL11.glColor4f(color.red / 255F, color.green / 255F, color.blue / 255F, color.alpha / 255F)

        GL11.glBegin(GL11.GL_LINE_STRIP)
        for (i in (startAngle / 360 * 100).toInt()..(endAngle / 360 * 100).toInt()) {
            val angle = (i / 100.0 * 360.0 * (PI / 180)).toFloat()
            GL11.glVertex2f(x + sin(angle) * radius, y + cos(angle) * radius)
        }
        GL11.glEnd()

        GL11.glDisable(GL11.GL_LINE_SMOOTH)
        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glDisable(GL11.GL_BLEND)
        GL11.glPopMatrix()
        GL11.glColor4f(1f, 1f, 1f, 1f)
    }

    /** 原样移植的实心圆绘制 */
    private fun drawCircle(x: Float, y: Float, radius: Float, color: Int) {
        val side = (radius * 2).toInt()
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glDisable(GL11.GL_TEXTURE_2D)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glEnable(GL11.GL_POLYGON_SMOOTH)
        GL11.glBegin(GL11.GL_TRIANGLE_FAN)
        RenderUtils.glColor(Color(color))
        for (i in 0..side) {
            val angle = i * (Math.PI * 2) / side
            GL11.glVertex2d(x + sin(angle) * radius, y + cos(angle) * radius)
        }
        GL11.glEnd()
        GL11.glDisable(GL11.GL_POLYGON_SMOOTH)
        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glDisable(GL11.GL_BLEND)
    }

    override fun drawTarget(entity: EntityPlayer) {
        hue += 0.05f * deltaTime * 0.1f
        if (hue > 1F) hue = 0F

        val size = 50F

        updateEasingHealth(entity)

        RenderUtils.withClipping(
            { drawCircle(size / 2, size / 2, size / 2 - 3, Color.WHITE.rgb) },
            {
                mc.netHandler.getPlayerInfo(entity.uniqueID)?.let {
                    drawHead(it.locationSkin, 3, 3, (size - 6).toInt(), (size - 6).toInt())
                }
            }
        )

        val healthPercent = (easingOf(entity) / entity.maxHealth).coerceIn(0F, 1F)
        val arcColor = if (arcRainbow.get()) Color.getHSBColor(hue, 0.6f, 1f) else Color(arcColorRed.get(), arcColorGreen.get(), arcColorBlue.get())

        // 环底色（原 Color(40,40,40,200*slideIn)）改用 Target2 的 Background 设置
        drawCircleArc(size / 2, size / 2, size / 2 - 1.5F, 3F, 0F, 360F, targetInstance.bgColor)
        if (healthPercent > 0) {
            drawCircleArc(size / 2, size / 2, size / 2 - 1.5F, 3F, -90F, 360F * healthPercent, getColor(arcColor))
        }

        val textX = size + 5
        drawFontString(entity.name, textX, 8F, getColor(Color.WHITE).rgb)
        drawFontString("HP: ${fmt.format(easingOf(entity))}", textX, 24F, getColor(Color(200, 200, 200)).rgb)
    }

    override fun handleBlur(entity: EntityPlayer) {
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        // 遮罩形状：圆形头像面板
        drawCircle(size() / 2, size() / 2, size() / 2, -1)
        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
    }

    override fun handleShadowCut(entity: EntityPlayer) = handleBlur(entity)

    override fun handleShadow(entity: EntityPlayer) {
        drawCircle(size() / 2, size() / 2, size() / 2, shadowOpaque.rgb)
    }

    private fun size() = 50F

    override fun getBorder(entity: EntityPlayer?): Border = Border(0F, 0F, size() + 80, size())

    override fun cleanup(alive: Set<EntityPlayer>) {
        easingHealths.keys.removeAll { it !in alive }
    }
}
