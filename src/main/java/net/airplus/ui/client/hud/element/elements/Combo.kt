/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.ui.client.hud.element.elements

import net.airplus.event.AttackEvent
import net.airplus.event.Listenable
import net.airplus.event.handler
import net.airplus.ui.client.hud.designer.GuiHudDesigner
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.Element
import net.airplus.ui.client.hud.element.ElementInfo
import net.airplus.ui.client.hud.element.Side
import net.airplus.ui.font.AWTFontRenderer.Companion.assumeNonVolatile
import net.airplus.ui.font.Fonts
import net.airplus.ui.font.GameFontRenderer
import net.airplus.utils.client.ClientThemesUtils
import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.render.ColorSettingsInteger
import net.airplus.utils.render.HudBlur
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.minecraft.client.renderer.GlStateManager.*
import net.minecraft.entity.Entity
import org.lwjgl.opengl.GL11.*
import java.awt.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * 连击追踪单例：监听 AttackEvent 记录攻击目标，
 * 在目标真正受到伤害（S19 实体受伤状态）时统计连续命中
 */
object ComboTracker : Listenable, MinecraftInstance {
    private val hitTimes = mutableListOf<Long>()
    private var lastAttackTarget: Entity? = null
    private var lastAttackTime = 0L
    private var lastHurtTarget: Entity? = null
    private var lastHurtTime = 0L

    var combo = 0
        private set
    var lastHitTime = 0L
        private set

    init {
        handler<AttackEvent> {
            lastAttackTarget = it.targetEntity
            lastAttackTime = System.currentTimeMillis()
        }
    }

    /**
     * 目标受伤回调：仅当受伤的玩家是最近攻击的目标时才计入连击
     */
    fun onTargetHurt(target: Entity) {
        if (target === mc.thePlayer) return
        val now = System.currentTimeMillis()
        // 同一目标 60ms 内的重复受伤回调去重（防止重复包/重复元素实例导致一次命中 +2）
        if (target === lastHurtTarget && now - lastHurtTime < 60L) return
        lastHurtTarget = target
        lastHurtTime = now
        if (target === lastAttackTarget && now - lastAttackTime <= 1000L) {
            hitTimes += now
            hitTimes.removeAll { now - it > 2000L }
            combo++
            lastHitTime = now
        }
    }

    fun reset() {
        combo = 0
        hitTimes.clear()
    }

    /** 最近 1 秒内的命中次数（有效命中 CPS） */
    fun hitsPerSecond(): Float {
        val now = System.currentTimeMillis()
        return hitTimes.count { now - it <= 1000L }.toFloat()
    }
}

/**
 * 连击 HUD 元素
 *
 * 岛系胶囊风格：外圈衰减环 + 连击数（命中弹跳）+ 攻击 CPS（平滑动画）
 * 出现/消失采用与 Text 元素同款的 bounce 弹簧动画
 */
@ElementInfo(name = "Combo", single = true, retrieveDamage = true)
class Combo(
    x: Double = 10.0, y: Double = 150.0, scale: Float = 1F, side: Side = Side.default(),
) : Element("Combo", x, y, scale, side) {

    private val idleRender by boolean("IdleRender", false)
    private val showCps by boolean("ShowCPS", true)
    private val timeout by int("ComboTimeout(ms)", 2000, 500..5000)

    private val enableBounce by boolean("Bounce", true)
    private val animTension by float("BounceTension", 0.01f, 0.01f..1.0f) { enableBounce }
    private val animFriction by float("BounceFriction", 0.12f, 0.01f..1.0f) { enableBounce }

    private val colorMode by choices("ColorMode", arrayOf("Theme", "Custom"), "Theme")
    private val customColors = ColorSettingsInteger(this, "CustomColor") { colorMode == "Custom" }.with(Color(90, 200, 120))
    private val bgColors = ColorSettingsInteger(this, "BackgroundColor").with(Color(20, 22, 28, 160))

    private val bgRadius by float("Round-Radius", 10F, 0F..18F)
    private val bgBlur by boolean("Background-Blur", false)
    private val bgBlurStrength by float("Background-Blur-Strength", 8F, 1F..30F) { bgBlur }
    private val bgBlurMode by choices("Background-Blur-Mode", HudBlur.MODES, "InternalBlur") { bgBlur }

    private val font by font("Font", Fonts.fontSemibold40)
    private val smallFont by font("SmallFont", Fonts.fontRegular35)

    private var animAlpha = 1F
    private var animScale = 1F
    private var velAlpha = 0F
    private var velScale = 0F
    private var lastVisible = true

    private var popScale = 1F
    private var lastCombo = 0
    private var smoothCps = 0F

    private fun accentColor(): Color =
        if (colorMode == "Custom") customColors.color() else ClientThemesUtils.getColor()

    private fun spring(current: Float, target: Float, velocity: Float): Pair<Float, Float> {
        val displacement = target - current
        val force = displacement * animTension
        val drag = velocity * animFriction
        val acceleration = force - drag
        val newVelocity = velocity + acceleration
        return (current + newVelocity) to newVelocity
    }

    override fun drawElement(): Border {
        val editing = mc.currentScreen is GuiHudDesigner
        val inWorld = mc.thePlayer != null && mc.theWorld != null
        if (!inWorld && !editing) return Border(0F, 0F, 0F, 0F)

        val now = System.currentTimeMillis()
        if (ComboTracker.combo > 0 && now - ComboTracker.lastHitTime > timeout)
            ComboTracker.reset()

        // 设计器中无连击时展示示例数据，方便摆放位置
        val combo = when {
            ComboTracker.combo > 0 -> ComboTracker.combo
            editing -> 5
            else -> 0
        }
        val targetCps = when {
            ComboTracker.combo > 0 -> ComboTracker.hitsPerSecond()
            editing -> 3F
            else -> 0F
        }
        smoothCps += (targetCps - smoothCps) * 0.12F
        if (kotlin.math.abs(targetCps - smoothCps) < 0.01F) smoothCps = targetCps

        if (combo > lastCombo) popScale = 1.35F
        lastCombo = combo
        popScale += (1F - popScale) * 0.18F

        val visible = combo > 0 || idleRender || editing

        val accent = accentColor()
        val fontRenderer = font
        val fontHeight = ((fontRenderer as? GameFontRenderer)?.height ?: fontRenderer.FONT_HEIGHT)
        val smallHeight = ((smallFont as? GameFontRenderer)?.height ?: smallFont.FONT_HEIGHT)

        val comboText = "x$combo"
        val comboTextW = fontRenderer.getStringWidth(comboText).toFloat()
        val labelW = smallFont.getStringWidth("Combo").toFloat()
        val cpsText = String.format("%.1f", smoothCps)
        val cpsW = if (showCps) smallFont.getStringWidth("CPS $cpsText").toFloat() else 0F

        val ringSize = 14F
        val paddingX = 10F
        val gap = 7F
        val contentH = maxOf(ringSize, fontHeight.toFloat(), smallHeight.toFloat())
        val height = contentH + 12F
        var width = paddingX * 2 + ringSize + gap + comboTextW + 5F + labelW
        if (showCps) width += gap + 1F + gap + cpsW

        if (enableBounce) {
            if (visible && !lastVisible) {
                animAlpha = 0F
                animScale = 0F
                velAlpha = 0F
                velScale = 0F
            }

            val targetValue = if (visible) 1F else 0F

            val (nextAlpha, vA) = spring(animAlpha, targetValue, velAlpha)
            animAlpha = nextAlpha.coerceIn(0F, 1F)
            velAlpha = vA

            val (nextScale, vS) = spring(animScale, targetValue, velScale)
            animScale = nextScale.coerceAtLeast(0F)
            velScale = vS

            lastVisible = visible

            if (animAlpha < 0.01F || animScale < 0.01F)
                return Border(0F, 0F, 0F, 0F)
        } else {
            animAlpha = 1F
            animScale = 1F
            lastVisible = visible
        }

        assumeNonVolatile {
            if (visible || animAlpha > 0.01F) {
                glPushMatrix()
                val pivotX = width / 2F
                val pivotY = height / 2F
                glTranslatef(pivotX, pivotY, 0F)
                glScalef(animScale, animScale, 1F)
                glTranslatef(-pivotX, -pivotY, 0F)
                val alphaF = if (enableBounce) animAlpha else 1F

                if (bgBlur) {
                    val lx1 = pivotX + (0F - pivotX) * animScale
                    val ly1 = pivotY + (0F - pivotY) * animScale
                    val lx2 = pivotX + (width - pivotX) * animScale
                    val ly2 = pivotY + (height - pivotY) * animScale
                    val absX1 = (scale * (lx1 + renderX)).toFloat()
                    val absY1 = (scale * (ly1 + renderY)).toFloat()
                    val absX2 = (scale * (lx2 + renderX)).toFloat()
                    val absY2 = (scale * (ly2 + renderY)).toFloat()
                    HudBlur.blur(absX1, absY1, absX2, absY2, bgBlurStrength, bgBlurMode) {
                        drawRoundedRect(0F, 0F, width, height, -1, bgRadius)
                    }
                }

                val bg = bgColors.color()
                drawRoundedRect(0F, 0F, width, height, Color(bg.red, bg.green, bg.blue, (bg.alpha * alphaF).toInt()).rgb, bgRadius)

                // 外圈：轨道 + 剩余时间弧
                val ringX = paddingX + ringSize / 2F
                val ringY = height / 2F
                drawRing(
                    ringX, ringY, ringSize / 2F - 1F,
                    Color(255, 255, 255, (60 * alphaF).toInt()), 2.2F, 0F, 360F
                )
                if (combo > 0) {
                    val frac = (1F - (now - ComboTracker.lastHitTime).coerceAtLeast(0L) / timeout.toFloat()).coerceIn(0F, 1F)
                    drawRing(
                        ringX, ringY, ringSize / 2F - 1F,
                        Color(accent.red, accent.green, accent.blue, (255 * alphaF).toInt()), 2.2F, -90F, -90F + 360F * frac
                    )
                }

                var tx = paddingX + ringSize + gap

                // 连击数字（命中时弹跳）
                val numPivotX = tx + comboTextW / 2F
                val numPivotY = height / 2F
                glPushMatrix()
                glTranslatef(numPivotX, numPivotY, 0F)
                glScalef(popScale, popScale, 1F)
                glTranslatef(-numPivotX, -numPivotY, 0F)
                val numColor = if (combo > 0) accent else Color(150, 150, 155)
                fontRenderer.drawString(
                    comboText, tx, height / 2F - fontHeight / 2F,
                    Color(numColor.red, numColor.green, numColor.blue, (255 * alphaF).toInt()).rgb, true
                )
                glPopMatrix()
                tx += comboTextW + 5F

                smallFont.drawString(
                    "Combo", tx, height / 2F - smallHeight / 2F,
                    Color(200, 203, 210, (255 * alphaF).toInt()).rgb, true
                )
                tx += labelW

                if (showCps) {
                    tx += gap + 1F
                    drawRoundedRect(tx, height / 2F - 6F, tx + 1F, height / 2F + 6F, Color(255, 255, 255, (45 * alphaF).toInt()).rgb, 0.5F)
                    tx += gap
                    smallFont.drawString(
                        "CPS $cpsText", tx, height / 2F - smallHeight / 2F,
                        Color(accent.red, accent.green, accent.blue, (235 * alphaF).toInt()).rgb, true
                    )
                }

                glPopMatrix()
            }
        }

        return Border(0F, 0F, width, height)
    }

    private fun drawRing(cx: Float, cy: Float, radius: Float, color: Color, lineWidth: Float, startDeg: Float, endDeg: Float) {
        glEnable(GL_BLEND)
        glDisable(GL_TEXTURE_2D)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glEnable(GL_LINE_SMOOTH)
        glHint(GL_LINE_SMOOTH_HINT, GL_NICEST)
        glLineWidth(lineWidth)
        glColor4f(color.red / 255f, color.green / 255f, color.blue / 255f, color.alpha / 255f)

        glBegin(GL_LINE_STRIP)
        val steps = 48
        for (i in 0..steps) {
            val a = Math.toRadians((startDeg + (endDeg - startDeg) * i / steps).toDouble())
            glVertex2d((cx + radius * cos(a)).toDouble(), (cy + radius * sin(a)).toDouble())
        }
        glEnd()

        glDisable(GL_LINE_SMOOTH)
        glEnable(GL_TEXTURE_2D)
        glDisable(GL_BLEND)
        glColor4f(1f, 1f, 1f, 1f)
    }

    override fun handleDamage(player: net.minecraft.entity.player.EntityPlayer) {
        // 攻击目标受伤时才计入连击（挥空不计）。
        // 自己受击不再清空连击：实战中会被对手反打立刻归零，看起来像组件"瞬间消失"
        if (player !== mc.thePlayer) {
            ComboTracker.onTargetHurt(player)
        }
    }
}
