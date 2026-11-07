package net.airplus.ui.client.hud.element.elements

import net.airplus.config.*
import net.airplus.event.Listenable
import net.airplus.event.Render3DEvent
import net.airplus.event.handler
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
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.entity.player.EntityPlayer
import org.lwjgl.opengl.GL11
import java.awt.Color

@ElementInfo(name = "Target2", retrieveDamage = true)
class Target2 : Element("Target2"), Listenable {

    private val styleList = mutableListOf<TargetStyle>()

    private val styleValue = ListValue("Style", arrayOf("Chill"), "Chill")

    val blurValue by boolean("Blur", false)
    // 模糊类型/强度由 Client 模块的全局 Blur 设置统一控制（HudBlur）

    val shadowValue by boolean("Shadow", false)
    val shadowStrength by float("Shadow-Strength", 1F, 0.01F..40F) { shadowValue }
    val shadowColorMode by choices("Shadow-Color", arrayOf("Background", "Custom", "Bar"), "Background") { shadowValue }

    val shadowColorRedValue by int("Shadow-Red", 0, 0..255) { shadowValue && shadowColorMode.equals("custom", ignoreCase = true) }
    val shadowColorGreenValue by int("Shadow-Green", 111, 0..255) { shadowValue && shadowColorMode.equals("custom", ignoreCase = true) }
    val shadowColorBlueValue by int("Shadow-Blue", 255, 0..255) { shadowValue && shadowColorMode.equals("custom", ignoreCase = true) }

    val noAnimValue by boolean("No-Animation", false)
    val globalAnimSpeed by float("Global-AnimSpeed", 3F, 1F..6.30F) { !noAnimValue }

    val showWithChatOpen by boolean("Show-ChatOpen", true)

    // 跟随模式：把整个 Target2 画在目标玩家的屏幕投影位置上（默认 (0,0) 即锚在玩家身体中央，
    // 面板横竖都相对玩家居中，Follow-OffsetX/Y 只在此基准上叠加）
    val followTargetValue by boolean("FollowTarget", false)
    val followOffsetX by float("Follow-OffsetX", 0F, -200F..200F) { followTargetValue }
    val followOffsetY by float("Follow-OffsetY", 0F, -200F..200F) { followTargetValue }

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

    // FollowTarget：Render3D 阶段用世界矩阵投影出的屏幕位置（projectOn2D 依赖当前 GL 矩阵，
    // 必须在世界渲染阶段采集，2D 阶段绘制时直接使用）
    private var followX = 0F
    private var followY = 0F
    private var followValid = false

    // 淡出中的旧目标（与正在绘制的目标不同）单独采样一份投影：
    // 切换目标瞬间旧目标还在做消失动画，锚点应继续贴在旧目标身上再滑向新目标
    private var fadeFollowX = 0F
    private var fadeFollowY = 0F
    private var fadeFollowValid = false

    // 跟随锚点的平滑状态：在不同玩家之间切换时锚点从 A 身上平滑移动到 B 身上
    private var anchorX = 0F
    private var anchorY = 0F
    private var anchorInitialized = false
    private var anchorTarget: EntityPlayer? = null
    private var anchorLastFrame = 0L

    val onRender3D = handler<Render3DEvent> {
        followValid = false
        fadeFollowValid = false
        if (!followTargetValue) {
            anchorInitialized = false
            anchorTarget = null
            return@handler
        }

        val current = mainTarget
        if (current != null && current !== mc.thePlayer) {
            projectFollow(current)?.let {
                followX = it.first
                followY = it.second
                followValid = true
            }
        }

        val fading = renderTarget
        if (fading != null && fading !== current && fading !== mc.thePlayer) {
            projectFollow(fading)?.let {
                fadeFollowX = it.first
                fadeFollowY = it.second
                fadeFollowValid = true
            }
        }
    }

    /**
     * 把目标玩家身体中心（水平取躯干中线、竖直取身高一半）投影到屏幕坐标，并叠加 Follow-Offset。
     * 投影失败（目标在视野外/背面）返回 null。
     */
    private fun projectFollow(target: EntityPlayer): Pair<Float, Float>? {
        val sr = ScaledResolution(mc)
        val ticks = mc.timer.renderPartialTicks
        val renderManager = mc.renderManager
        val x = (target.lastTickPosX + (target.posX - target.lastTickPosX) * ticks - renderManager.renderPosX).toFloat()
        val y = (target.lastTickPosY + (target.posY - target.lastTickPosY) * ticks - renderManager.renderPosY + target.height * 0.5).toFloat()
        val z = (target.lastTickPosZ + (target.posZ - target.lastTickPosZ) * ticks - renderManager.renderPosZ).toFloat()

        val point = ESPUtil.projectOn2D(x, y, z, sr.scaleFactor) ?: return null
        if (point.z < 0f || point.z > 1f) return null

        return point.x + followOffsetX to point.y + followOffsetY
    }

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
            AuroraGlass(this),
            Chill(this),
            Exhibition(this),
            Flux(this),
            HaloPulse(this),
            Hanabi(this),
            LiquidBounce(this),
            Lnk(this),
            Moon(this),
            Moon4(this),
            Moon5(this),
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
            anchorInitialized = false
            anchorTarget = null
            return Border(0F, 0F, 120F, 48F)
        }

        // None 动画类型无动画，目标消失时直接清除
        if (!hasTarget && animationType == "None") {
            renderTarget = null
            lastHasTarget = false
            anchorInitialized = false
            anchorTarget = null
            return Border(0F, 0F, 120F, 48F)
        }

        val convertTarget = renderTarget!!

        val returnBorder = mainStyle.getBorder(convertTarget) ?: return Border(0F, 0F, 120F, 48F)

        // FollowTarget：绘制锚点从 HUD 固定位置换到目标玩家的屏幕投影位置。
        // (0,0) 默认 = 玩家身体中央（面板相对玩家横竖都居中），Follow-OffsetX/Y 只在此基础上叠加偏移
        val follow = followTargetValue && !isEditing && convertTarget !== mc.thePlayer
        if (follow) {
            // 采样一份本帧投影：正绘制的主目标用自己的采样，淡出中的旧目标用自己的采样
            val samplingMain = convertTarget === mainTarget
            val (sampleX, sampleY, sampleValid) =
                if (samplingMain) Triple(followX, followY, followValid)
                else Triple(fadeFollowX, fadeFollowY, fadeFollowValid)

            if (sampleValid) {
                val now = System.currentTimeMillis()
                if (!anchorInitialized) {
                    // 首次出现：直接吸附到目标身上，避免从旧位置飘过来
                    anchorX = sampleX
                    anchorY = sampleY
                    anchorTarget = convertTarget
                    anchorInitialized = true
                    anchorLastFrame = now
                } else {
                    if (convertTarget !== anchorTarget) {
                        // 目标切换（A→B）：锚点保持在 A 身上的最后位置，
                        // 由下方的平滑动画从 A 身上滑向 B 身上，不做瞬移
                        anchorTarget = convertTarget
                    }

                    val dt = ((now - anchorLastFrame).coerceIn(1L, 100L) / 1000.0).toFloat()
                    anchorLastFrame = now
                    val move = 1F - kotlin.math.exp(-dt * 14F)
                    anchorX += (sampleX - anchorX) * move
                    anchorY += (sampleY - anchorY) * move
                }
            }
            // 采样失败（目标转到背面/视野外）：保持上一次锚点位置原地继续渲染，动画状态不变
        }

        val styleWidth = returnBorder.x2 - returnBorder.x
        val styleHeight = returnBorder.y2 - returnBorder.y
        val anchorCenterX = returnBorder.x + styleWidth / 2F
        val anchorCenterY = returnBorder.y + styleHeight / 2F

        // 面板中心对齐投影锚点，使 Follow-Offset (0,0) 恰好在玩家横竖居中的位置
        val drawX = if (follow) (anchorX - anchorCenterX).toDouble() else renderX
        val drawY = if (follow) (anchorY - anchorCenterY).toDouble() else renderY

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
            anchorInitialized = false
            anchorTarget = null
            return Border(0F, 0F, 120F, 48F)
        }

        GL11.glPushMatrix()

        // 跟随模式下把 HUD 固定锚点平移到目标投影位置（所有局部坐标绘制保持不变）
        GL11.glTranslated(drawX - renderX, drawY - renderY, 0.0)

        try {
            if (shadowValue && mainStyle.shaderSupport) {
                GL11.glTranslated(-drawX, -drawY, 0.0)
                GL11.glPushMatrix()
                ShadowUtils.shadow(shadowStrength, {
                    GL11.glPushMatrix()
                    GL11.glTranslated(drawX, drawY, 0.0)
                    mainStyle.handleShadow(convertTarget)
                    GL11.glPopMatrix()
                }, {
                    GL11.glPushMatrix()
                    GL11.glTranslated(drawX, drawY, 0.0)
                    mainStyle.handleShadowCut(convertTarget)
                    GL11.glPopMatrix()
                })
                GL11.glPopMatrix()
                GL11.glTranslated(drawX, drawY, 0.0)
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

                // 换算屏幕绝对坐标：abs = scale * (local + drawX/drawY)
                val absX1 = (scale * (lx1 + drawX)).toFloat()
                val absY1 = (scale * (ly1 + drawY)).toFloat()
                val absX2 = (scale * (lx2 + drawX)).toFloat()
                val absY2 = (scale * (ly2 + drawY)).toFloat()

                // 重置矩阵为单位阵（抵消 HUD 的 scale 与平移，含跟随位移），遮罩按屏幕绝对坐标绘制
                GL11.glTranslated(-drawX, -drawY, 0.0)
                GL11.glScalef(1F / scale, 1F / scale, 1F)
                HudBlur.blur(
                    absX1, absY1, absX2, absY2,
                    strengthScale = 1F - animProgress
                ) {
                    // M·p = scale*(render + bc) + scale*k*(p - bc)，与 drawTarget 的动画变换一致
                    GL11.glTranslatef(absX1 + (absX2 - absX1) / 2F, absY1 + (absY2 - absY1) / 2F, 0F)
                    GL11.glScalef(scale * k, scale * k, 1F)
                    GL11.glTranslatef(-bcx, -bcy, 0F)
                    mainStyle.handleBlur(convertTarget)
                }
                GL11.glScalef(scale, scale, 1F)
                GL11.glTranslated(drawX, drawY, 0.0)
            }

            if (mainStyle is Chill) {
                val borderWidth = returnBorder.x2 - returnBorder.x
                val borderHeight = returnBorder.y2 - returnBorder.y
                val calcScaleX = animProgress * (4F / (borderWidth / 2F))
                val calcScaleY = animProgress * (4F / (borderHeight / 2F))
                val calcTranslateX = borderWidth / 2F * calcScaleX
                val calcTranslateY = borderHeight / 2F * calcScaleY
                mainStyle.updateData(drawX.toFloat() + calcTranslateX, drawY.toFloat() + calcTranslateY, calcScaleX, calcScaleY)
            }

            GlStateManager.pushMatrix()
            
            when (animationType) {
                "Scale", "Bounce", "Zoom" -> {
                    GlStateManager.translate(anchorCenterX, anchorCenterY, 0f)
                    GlStateManager.scale(animScale, animScale, 1f)
                    GlStateManager.translate(-anchorCenterX, -anchorCenterY, 0f)
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
