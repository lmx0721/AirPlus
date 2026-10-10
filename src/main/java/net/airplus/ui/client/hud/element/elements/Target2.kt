package net.airplus.ui.client.hud.element.elements

import net.airplus.config.*
import net.airplus.features.module.modules.combat.KillAura
import net.airplus.features.module.modules.render.getMixedColor
import net.airplus.utils.client.ClientThemesUtils
import net.airplus.ui.client.hud.designer.GuiHudDesigner
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.Element
import net.airplus.ui.client.hud.element.ElementInfo
import net.airplus.ui.client.hud.element.elements.targets2.TargetStyle
import net.airplus.ui.client.hud.element.elements.targets2.impl.*
import net.airplus.utils.render.*
import net.minecraft.client.gui.GuiChat
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.entity.player.EntityPlayer
import org.lwjgl.opengl.GL11
import java.awt.Color

@ElementInfo(name = "Target2", retrieveDamage = true)
class Target2 : Element("Target2") {

    private val styleList = mutableListOf<TargetStyle>()

    private val styleValue = ListValue("Style", arrayOf("Chill"), "Chill")

    val blurValue by boolean("Blur", false)
    val blurStrength by float("Blur-Strength", 1F, 0.01F..40F) { blurValue }
    val blurModeValue by choices("Blur-Mode", HudBlur.MODES, "InternalBlur") { blurValue }

    val shadowValue by boolean("Shadow", false)
    val shadowStrength by float("Shadow-Strength", 1F, 0.01F..40F) { shadowValue }
    val shadowColorMode by choices("Shadow-Color", arrayOf("Background", "Custom", "Bar"), "Background") { shadowValue }

    val shadowColorRedValue by int("Shadow-Red", 0, 0..255) { shadowValue && shadowColorMode.equals("custom", ignoreCase = true) }
    val shadowColorGreenValue by int("Shadow-Green", 111, 0..255) { shadowValue && shadowColorMode.equals("custom", ignoreCase = true) }
    val shadowColorBlueValue by int("Shadow-Blue", 255, 0..255) { shadowValue && shadowColorMode.equals("custom", ignoreCase = true) }

    val noAnimValue by boolean("No-Animation", false)
    val globalAnimSpeed by float("Global-AnimSpeed", 3F, 1F..6.30F) { !noAnimValue }

    val showWithChatOpen by boolean("Show-ChatOpen", true)

    val animationType by choices("AnimationType", arrayOf("Scale", "Bounce", "Zoom", "None"), "Scale")
    val animSpeed by float("AnimationSpeed", 0.1F, 0.01F..0.5F)
    val bounceTension by float("BounceTension", 0.08f, 0.01f..0.5f) { animationType == "Bounce" }
    val bounceFriction by float("BounceFriction", 0.2f, 0.01f..0.5f) { animationType == "Bounce" }

    val colorModeValue by choices("Color", arrayOf("Custom", "Rainbow", "Sky", "Slowly", "Fade", "Mixer", "Health", "Theme"), "Custom")
    val redValue by int("Red", 252, 0..255)
    val greenValue by int("Green", 96, 0..255)
    val blueValue by int("Blue", 66, 0..255)
    val saturationValue by float("Saturation", 1F, 0F..1F)
    val brightnessValue by float("Brightness", 1F, 0F..1F)
    val waveSecondValue by int("Seconds", 2, 1..10)
    val bgColorValue by color("Background", Color(0, 0, 0, 160))

    val bordercolor: Color
        get() = Color(redValue, greenValue, blueValue)

    val styleValueName: String
        get() = styleValue.get()

    var barColor = Color(-1)
    var bgColor = Color(-1)
    var animProgress = 0F

    private var animAlpha = 0F
    private var animScale = 0F

    private var velAlpha = 0f
    private var velScale = 0f
    
    private var lastHasTarget = false

    val counter1 = intArrayOf(50)
    val counter2 = intArrayOf(80)

    private var mainTarget: EntityPlayer? = null
    private var renderTarget: EntityPlayer? = null

    private fun spring(current: Float, target: Float, velocity: Float, tension: Float = bounceTension, friction: Float = bounceFriction): Pair<Float, Float> {
        val displacement = target - current
        val force = displacement * tension
        val drag = velocity * friction
        val acceleration = force - drag
        val newVelocity = velocity + acceleration
        val newPosition = current + newVelocity
        return newPosition to newVelocity
    }

    init {
        // 将 Style 选择移动到设置列表最顶部
        val existingValues = ArrayList(values)
        get().clear()
        addValue(styleValue)
        existingValues.forEach { addValue(it) }

        val styles = arrayOf(
            Astolfo(this),
            Astolfo2(this),
            AsuidBounce(this),
            Chill(this),
            Exhibition(this),
            Flux(this),
            Hanabi(this),
            LiquidBounce(this),
            Lnk(this),
            Moon(this),
            Moon4(this),
            Novoline(this),
            Novoline2(this),
            Novoline3(this),
            Onyx(this),
            Raven(this),
            RavenB4(this),
            Remix(this),
            Rice(this),
            RiseModern(this),
            Slowly(this),
            Tifality(this)
        )
        styles.forEach { styleList.add(it) }
        styleValue.values = styleList.map { it.name }.toTypedArray()

        styleList.forEach { addValues(it.values) }
    }

    override fun drawElement(): Border? {
        val isEditing = mc.currentScreen is GuiHudDesigner
        
        val kaTarget = KillAura.target

        if (isEditing) {
            mainTarget = mc.thePlayer
        } else if (kaTarget != null && kaTarget is EntityPlayer) {
            mainTarget = kaTarget as EntityPlayer
        } else if (mc.currentScreen is GuiChat && showWithChatOpen) {
            mainTarget = mc.thePlayer
        } else {
            mainTarget = null
        }

        val currentStyleName = styleValue.get()
        val mainStyle = styleList.find { it.name.equals(currentStyleName, ignoreCase = true) } ?: return Border(0F, 0F, 120F, 48F)

        val hasTarget = mainTarget != null

        // 保留目标用于消失动画
        if (hasTarget) {
            renderTarget = mainTarget
        }

        // 无渲染目标时直接返回
        if (renderTarget == null) {
            lastHasTarget = false
            return Border(0F, 0F, 120F, 48F)
        }

        // None 动画类型无动画，目标消失时直接清除
        if (!hasTarget && animationType == "None") {
            renderTarget = null
            lastHasTarget = false
            return Border(0F, 0F, 120F, 48F)
        }

        val convertTarget = renderTarget!!

        val preBarColor = when (colorModeValue) {
            "Rainbow" -> Color(ColorUtils.getRainbowOpaque(waveSecondValue, saturationValue, brightnessValue, 0))
            "Custom" -> Color(redValue, greenValue, blueValue)
            "Sky" -> ColorUtils.skyRainbow(0, saturationValue, brightnessValue, 1f)
            "Fade" -> ColorUtils.fade(Color(redValue, greenValue, blueValue), 0, 100)
            "Health" -> BlendUtils.getHealthColor(convertTarget.health, convertTarget.maxHealth)
            "Theme" -> ClientThemesUtils.getColor()
            "Mixer" -> getMixedColor(0, waveSecondValue)
            else -> ColorUtils.LiquidSlowly(System.nanoTime(), 0, saturationValue, brightnessValue)
        }

        // 更新 animProgress：有目标时不淡出，无目标时随动画淡出
        animProgress = if (hasTarget) 0F else 1F - animAlpha

        barColor = ColorUtils.reAlpha(preBarColor, preBarColor.alpha / 255F * (1F - animProgress))
        bgColor = Color(bgColorValue.red, bgColorValue.green, bgColorValue.blue, (bgColorValue.alpha * (1F - animProgress)).toInt())

        val returnBorder = mainStyle.getBorder(convertTarget) ?: return Border(0F, 0F, 120F, 48F)

        val targetScale = if (hasTarget) 1F else 0F
        val targetAlpha = if (hasTarget) 1F else 0F
        
        if (hasTarget && !lastHasTarget) {
            animScale = 0F
            animAlpha = 0F
            velAlpha = 0f
            velScale = 0f
        }
        lastHasTarget = hasTarget

        when (animationType) {
            "Scale" -> {
                animScale = net.airplus.utils.render.animation.AnimationUtil.base(animScale.toDouble(), targetScale.toDouble(), animSpeed.toDouble()).toFloat()
                animAlpha = if (hasTarget) 1F else animScale
            }
            "Bounce" -> {
                val (nextAlpha, vA) = spring(animAlpha, targetAlpha, velAlpha)
                animAlpha = nextAlpha.coerceIn(0F, 1F)
                velAlpha = vA

                val (nextScale, vS) = spring(animScale, targetScale, velScale)
                animScale = nextScale.coerceIn(0F, 1.5F)
                velScale = vS
            }
            "Zoom" -> {
                val zoomTarget = if (hasTarget) 1F else 0F
                animScale = net.airplus.utils.render.animation.AnimationUtil.base(animScale.toDouble(), zoomTarget.toDouble(), animSpeed.toDouble()).toFloat()
                animAlpha = animScale
            }
            "None" -> {
                animScale = 1F
                animAlpha = 1F
            }
        }

        // 消失动画完成检查
        if (!hasTarget && animAlpha < 0.01f) {
            renderTarget = null
            lastHasTarget = false
            return Border(0F, 0F, 120F, 48F)
        }

        GL11.glPushMatrix()
        
        try {
            if (shadowValue && mainStyle.shaderSupport) {
                GL11.glTranslated(-renderX, -renderY, 0.0)
                GL11.glPushMatrix()
                ShadowUtils.shadow(shadowStrength, {
                    GL11.glPushMatrix()
                    GL11.glTranslated(renderX, renderY, 0.0)
                    mainStyle.handleShadow(convertTarget)
                    GL11.glPopMatrix()
                }, {
                    GL11.glPushMatrix()
                    GL11.glTranslated(renderX, renderY, 0.0)
                    mainStyle.handleShadowCut(convertTarget)
                    GL11.glPopMatrix()
                })
                GL11.glPopMatrix()
                GL11.glTranslated(renderX, renderY, 0.0)
            }

            if (blurValue && mainStyle.shaderSupport) {
                val k = animScale
                val borderWidth = returnBorder.x2 - returnBorder.x
                val borderHeight = returnBorder.y2 - returnBorder.y
                val bcx = returnBorder.x + borderWidth / 2F
                val bcy = returnBorder.y + borderHeight / 2F

                // 动画后的局部 AABB（绕中心缩放，与 drawTarget 的动画变换一致）
                val lx1 = bcx + (returnBorder.x - bcx) * k
                val ly1 = bcy + (returnBorder.y - bcy) * k
                val lx2 = bcx + (returnBorder.x2 - bcx) * k
                val ly2 = bcy + (returnBorder.y2 - bcy) * k

                // 换算屏幕绝对坐标：abs = scale * (local + render)
                val absX1 = (scale * (lx1 + renderX)).toFloat()
                val absY1 = (scale * (ly1 + renderY)).toFloat()
                val absX2 = (scale * (lx2 + renderX)).toFloat()
                val absY2 = (scale * (ly2 + renderY)).toFloat()

                // 重置矩阵为单位阵（抵消 HUD 的 scale 与平移），遮罩按屏幕绝对坐标绘制
                GL11.glTranslated(-renderX, -renderY, 0.0)
                GL11.glScalef(1F / scale, 1F / scale, 1F)
                HudBlur.blur(
                    absX1, absY1, absX2, absY2,
                    blurStrength * (1F - animProgress),
                    blurModeValue
                ) {
                    // M·p = scale*(render + bc) + scale*k*(p - bc)，与 drawTarget 的动画变换一致
                    GL11.glTranslatef(absX1 + (absX2 - absX1) / 2F, absY1 + (absY2 - absY1) / 2F, 0F)
                    GL11.glScalef(scale * k, scale * k, 1F)
                    GL11.glTranslatef(-bcx, -bcy, 0F)
                    mainStyle.handleBlur(convertTarget)
                }
                GL11.glScalef(scale, scale, 1F)
                GL11.glTranslated(renderX, renderY, 0.0)
            }

            if (mainStyle is Chill) {
                val borderWidth = returnBorder.x2 - returnBorder.x
                val borderHeight = returnBorder.y2 - returnBorder.y
                val calcScaleX = animProgress * (4F / (borderWidth / 2F))
                val calcScaleY = animProgress * (4F / (borderHeight / 2F))
                val calcTranslateX = borderWidth / 2F * calcScaleX
                val calcTranslateY = borderHeight / 2F * calcScaleY
                mainStyle.updateData(renderX.toFloat() + calcTranslateX, renderY.toFloat() + calcTranslateY, calcScaleX, calcScaleY)
            }

            val styleWidth = returnBorder.x2 - returnBorder.x
            val styleHeight = returnBorder.y2 - returnBorder.y
            val centerX = returnBorder.x + styleWidth / 2F
            val centerY = returnBorder.y + styleHeight / 2F

            GlStateManager.pushMatrix()
            
            when (animationType) {
                "Scale", "Bounce", "Zoom" -> {
                    GlStateManager.translate(centerX, centerY, 0f)
                    GlStateManager.scale(animScale, animScale, 1f)
                    GlStateManager.translate(-centerX, -centerY, 0f)
                }
            }

            mainStyle.drawTarget(convertTarget)
            
            GlStateManager.popMatrix()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            GlStateManager.resetColor()
            GL11.glPopMatrix()
        }

        return returnBorder
    }

    override fun handleDamage(ent: EntityPlayer) {
        if (mainTarget != null && ent == mainTarget) {
            val currentStyleName = styleValue.get()
            val mainStyle = styleList.find { it.name.equals(currentStyleName, ignoreCase = true) }
            mainStyle?.handleDamage(ent)
        }
    }

    fun getFadeProgress() = animProgress
}
