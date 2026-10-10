/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.ui.client.hud.element.elements

import net.airplus.event.Listenable
import net.airplus.event.PacketEvent
import net.airplus.event.handler
import net.airplus.ui.client.hud.designer.GuiHudDesigner
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.Element
import net.airplus.ui.client.hud.element.ElementInfo
import net.airplus.ui.client.hud.element.Side
import net.airplus.utils.client.ClientThemesUtils
import net.airplus.utils.render.ColorSettingsInteger
import net.minecraft.network.play.server.S12PacketEntityVelocity
import org.lwjgl.opengl.GL11.*
import java.awt.Color
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 方向受击指示 HUD 元素
 *
 * 自己收到击退速度包（S12PacketEntityVelocity）时，以元素锚点（默认屏幕中心）为圆心
 * 画一段指向攻击来源的弧形光带；命中瞬间 easeOutBack 弹入，存活期内淡出。
 *
 * 方向推断（参考 AirClient Velocity 的思路）：S12 的水平 motion 就是把你推离攻击者的
 * 冲量向量，atan2(motionX, -motionZ) 即攻击者方位角，减去收包瞬间的 yaw 得到相对方位。
 * 每次受击创建一条记录并在创建时冻结角度，不做实时修正。
 */
@ElementInfo(name = "HitDirection", single = true, disableScale = true)
class HitDirection(
    x: Double = 0.0, y: Double = 0.0, scale: Float = 1F,
    side: Side = Side(Side.Horizontal.MIDDLE, Side.Vertical.MIDDLE),
) : Element("HitDirection", x, y, scale, side), Listenable {

    private val radius by float("Radius", 120F, 30F..300F)
    private val arcSpan by float("ArcSpan", 44F, 16F..120F)
    private val lineWidth by float("LineWidth", 3.5F, 1F..10F)
    private val duration by int("Duration(ms)", 900, 300..3000)

    private val colorMode by choices("ColorMode", arrayOf("Theme", "Custom"), "Custom")
    private val customColors = ColorSettingsInteger(this, "CustomColor") { colorMode == "Custom" }.with(Color(229, 115, 102))
    private val glow by boolean("Glow", true)

    /** 活跃的受击记录（角度创建时冻结） */
    private class Hit(var bearingDeg: Float, val bornAt: Long)

    private val hits = mutableListOf<Hit>()

    init {
        handler<PacketEvent> { event ->
            val packet = event.packet as? S12PacketEntityVelocity ?: return@handler
            val self = mc.thePlayer ?: return@handler
            if (packet.entityID != self.entityId) return@handler

            // 近战击退特征：有垂直分量且水平冲量非零（避免药水/传送等纯垂直速度误触）
            if (packet.motionY <= 0) return@handler
            if (packet.motionX == 0 && packet.motionZ == 0) return@handler

            // 攻击者方位 = 击退向量的反方向：yawToAttacker = atan2(motionX, -motionZ)
            val yawToAttacker = Math.toDegrees(atan2(packet.motionX.toDouble(), -packet.motionZ.toDouble())).toFloat()
            var rel = yawToAttacker - self.rotationYaw
            while (rel > 180F) rel -= 360F
            while (rel < -180F) rel += 360F

            hits += Hit(rel, System.currentTimeMillis())
            if (hits.size > 4) hits.removeAt(0)
        }
    }

    private fun accentColor(): Color =
        if (colorMode == "Custom") customColors.color() else ClientThemesUtils.getColor()

    override fun drawElement(): Border {
        val editing = mc.currentScreen is GuiHudDesigner
        val inWorld = mc.thePlayer != null && mc.theWorld != null
        if (!inWorld && !editing) return Border(0F, 0F, 0F, 0F)

        val now = System.currentTimeMillis()
        if (!inWorld) hits.clear()
        hits.removeAll { now - it.bornAt > duration }

        // 设计器中无真实受击时展示示例弧，方便摆放
        val rendering: List<Hit> = when {
            hits.isNotEmpty() -> hits
            editing -> listOf(Hit(0F, now - (duration / 3)))
            else -> return Border(-9F, -9F, 9F, 9F)
        }

        val accent = accentColor()
        val r = radius
        val spanHalf = arcSpan / 2F

        glPushMatrix()
        glEnable(GL_BLEND)
        glDisable(GL_TEXTURE_2D)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glEnable(GL_LINE_SMOOTH)
        glHint(GL_LINE_SMOOTH_HINT, GL_NICEST)

        for (hit in rendering) {
            val age = (now - hit.bornAt).coerceAtLeast(0L).toFloat()
            val frac = 1F - age / duration // 1 → 0

            // 存活期最后 40% 淡出
            val alpha = if (frac > 0.4F) 1F else frac / 0.4F
            // 前 140ms easeOutBack 弹入
            val pop = easeOutBack((age / 140F).coerceIn(0F, 1F))

            val a = Math.toRadians(hit.bearingDeg.toDouble())
            if (glow) {
                applyColor(accent, alpha * 0.22F)
                drawArc(r, spanHalf * pop, a, lineWidth * 2.6F)
            }
            applyColor(accent, alpha)
            drawArc(r, spanHalf * pop, a, lineWidth)
        }

        glDisable(GL_LINE_SMOOTH)
        glEnable(GL_TEXTURE_2D)
        glDisable(GL_BLEND)
        glColor4f(1F, 1F, 1F, 1F)
        glPopMatrix()

        return Border(-9F, -9F, 9F, 9F)
    }

    /**
     * 以局部原点为圆心画弧，screenAngleRad 为弧中心方位角
     * （0 = 屏幕上方/正前，正值顺时针），radius 为圆半径
     */
    private fun drawArc(radius: Float, spanHalfDeg: Float, screenAngleRad: Double, width: Float) {
        val steps = 24
        glLineWidth(width)
        glBegin(GL_LINE_STRIP)
        for (i in 0..steps) {
            val t = (spanHalfDeg * 2) * i / steps - spanHalfDeg
            val a = screenAngleRad + Math.toRadians(t.toDouble())
            val x = radius * sin(a)
            val y = -radius * cos(a)
            glVertex2d(x, y)
        }
        glEnd()
    }

    private fun applyColor(color: Color, alpha: Float) {
        glColor4f(color.red / 255F, color.green / 255F, color.blue / 255F, alpha.coerceIn(0F, 1F))
    }

    /** easeOutBack：带轻微过冲的弹入曲线 */
    private fun easeOutBack(x: Float): Float {
        val c1 = 1.70158F
        val c3 = c1 + 1F
        val p = x.coerceIn(0F, 1F) - 1F
        return 1F + c3 * p * p * p + c1 * p * p
    }
}
