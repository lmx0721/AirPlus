/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 * skid edmoonlight，gold bounce,liquid bounce+
 */
package net.airplus.ui.client.hud.element.elements

import net.airplus.config.*
import net.airplus.event.AttackEvent
import net.airplus.event.Listenable
import net.airplus.event.Render3DEvent
import net.airplus.event.handler
import net.airplus.features.module.modules.combat.KillAura
import net.airplus.features.module.modules.misc.AntiBot
import net.airplus.features.module.modules.render.getMixedColor
import net.airplus.utils.client.ClientThemesUtils
import net.airplus.ui.client.hud.designer.GuiHudDesigner
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.Element
import net.airplus.ui.client.hud.element.ElementInfo
import net.airplus.ui.client.hud.element.elements.targets2.TargetStyle
import net.airplus.ui.client.hud.element.elements.targets2.impl.*
import net.airplus.utils.render.*
import net.airplus.utils.render.animation.AnimationUtil
import net.minecraft.client.gui.GuiChat
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.entity.player.EntityPlayer
import org.lwjgl.opengl.GL11
import java.awt.Color
import kotlin.math.exp

@ElementInfo(name = "Target2", retrieveDamage = true)
class Target2 : Element("Target2"), Listenable {

    private val styleList = mutableListOf<TargetStyle>()

    private val styleValue = ListValue("Style", arrayOf("Chill"), "Chill")

    // 多目标模式（全局设置，对所有样式生效）：维护目标动画表，网格/跟随两种布局
    val multiTargetValue by boolean("MultiTarget", true)

    // 仅 KillAura 目标：关闭时普通攻击的目标（最近 3 秒内被攻击过且仍存活）也纳入显示
    val onlyKillAuraValue by boolean("OnlyKillAura", true)

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

    // 多目标卡片出入场动画时长（ms），对齐 moonlight Anim Speed 语义
    val multiAnimSpeed by float("Multi-AnimSpeed", 300F, 50F..800F) { multiTargetValue }

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

    /** 攻击记录：目标 → 最近一次攻击时间戳（OnlyKillAura 关闭时纳入目标集，保留 3 秒） */
    private val attackHistory = LinkedHashMap<EntityPlayer, Long>()

    private val attackEventHandler = handler<AttackEvent> { event ->
        if (onlyKillAuraValue) return@handler
        val entity = event.targetEntity
        if (entity is EntityPlayer && entity !== mc.thePlayer && !AntiBot.isBot(entity)) {
            attackHistory[entity] = System.currentTimeMillis()
        }
    }

    /** 最近 3 秒内被攻击过且仍存活的最新攻击目标（单目标模式 OnlyKillAura 关闭时使用） */
    private fun latestAttackTarget(): EntityPlayer? {
        val now = System.currentTimeMillis()
        attackHistory.entries.removeAll { now - it.value > ATTACK_KEEP_MS }
        return attackHistory.entries
            .asSequence()
            .filter { (ent, time) -> now - time <= ATTACK_KEEP_MS && !ent.isDead && ent.isEntityAlive }
            .maxByOrNull { it.value }?.key
    }

    // ===== 多目标（MultiTarget）共用状态 =====
    // 目标 → 卡片动画状态（淡入/淡出进度 + 绘制位置），AnimationUtil.base 按帧推进，时长取 multiAnimSpeed
    private class MultiCard {
        var progress = 0F
        // 绘制位置：网格模式下为相对组件原点的槽位坐标；跟随模式下为屏幕投影锚点坐标
        var posX = 0F
        var posY = 0F
        var posInitialized = false
        // FollowTarget：Render3D 阶段采样的该目标屏幕投影
        var sampleX = 0F
        var sampleY = 0F
        var sampleValid = false
    }

    /** 一张卡片的绘制布局：局部平移与卡片局部原点的屏幕绝对坐标 */
    private class MultiLayout(
        val localX: Float,
        val localY: Float,
        val absX: Float,
        val absY: Float,
        val border: Border
    )

    private val multiAnims = LinkedHashMap<EntityPlayer, MultiCard>()
    private var multiLastFrame = 0L
    private var lastFollowMode = false

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

        if (multiTargetValue) {
            // 多目标跟随：为动画表中的每个目标独立采样一份屏幕投影
            for ((target, card) in multiAnims) {
                if (target === mc.thePlayer) continue
                projectFollow(target)?.let {
                    card.sampleX = it.first
                    card.sampleY = it.second
                    card.sampleValid = true
                } ?: run { card.sampleValid = false }
            }
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
            Arc(this),
            Armor(this),
            Astolfo(this),
            Astolfo2(this),
            AsuidBounce(this),
            AuroraGlass(this),
            Chill(this),
            Exhibition(this),
            Flux(this),
            Flux2(this),
            Hanabi(this),
            HaloPulse(this),
            LiquidBounce(this),
            Lnk(this),
            Moon(this),
            Moon4(this),
            Moon5(this),
            Myau(this),
            Naven2(this),
            Novoline(this),
            Novoline2(this),
            Novoline3(this),
            Onyx(this),
            Opai(this),
            Raven(this),
            RavenB4(this),
            Remix(this),
            Rice(this),
            RiseModern(this),
            Slowly(this),
            Southside(this),
            Styles(this),
            Tifality(this)
        )
        styles.forEach { styleList.add(it) }
        styleValue.values = styleList.map { it.name }.toTypedArray()

        styleList.forEach { addValues(it.values) }
    }

    override fun drawElement(): Border? {
        val isEditing = mc.currentScreen is GuiHudDesigner

        if (isEditing) {
            mainTarget = mc.thePlayer
        } else if (multiTargetValue) {
            // 多目标路径不使用单目标状态
            mainTarget = null
        } else {
            val kaTarget = KillAura.target
            val attackT = if (!onlyKillAuraValue) latestAttackTarget() else null
            mainTarget = when {
                kaTarget != null && kaTarget is EntityPlayer -> kaTarget as EntityPlayer
                attackT != null -> attackT
                mc.currentScreen is GuiChat && showWithChatOpen -> mc.thePlayer
                else -> null
            }
        }

        val currentStyleName = styleValue.get()
        val mainStyle = styleList.find { it.name.equals(currentStyleName, ignoreCase = true) } ?: return Border(0F, 0F, 120F, 48F)

        // 多目标模式（全局设置，对所有样式生效）走独立渲染路径
        if (multiTargetValue) {
            return drawMultiTarget(mainStyle, isEditing)
        }

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
                    val move = 1F - exp(-dt * 14F)
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

    /**
     * 多目标渲染路径（MultiTarget 全局设置，对所有样式生效）：
     * 维护「目标 → 动画状态」表，目标出现淡入、消失/死亡淡出，淡出完成后移除。
     * 布局：
     *  - FollowTarget 关闭：网格排列，一行最多 2 张卡片，从左到右、从上到下，位于组件自身位置；
     *    淡出中的卡片保留槽位，卡片被移除后其后的卡片槽位前移、绘制位置缓动补齐。
     *  - FollowTarget 开启：每张卡片跟随各自目标的屏幕投影，保持相对锚点居中；
     *    投影失败（视野外/背面）时不消失，绘制位置缓动回落到组件自身位置继续渲染。
     */
    private fun drawMultiTarget(mainStyle: TargetStyle, isEditing: Boolean): Border {
        // ===== 目标收集 =====
        val alive = HashSet<EntityPlayer>()
        if (isEditing) {
            mc.thePlayer?.let { alive.add(it) }
        } else {
            if (KillAura.handleEvents()) {
                for (t in KillAura.targets) {
                    if (t is EntityPlayer && t !== mc.thePlayer && !t.isDead && t.isEntityAlive && !AntiBot.isBot(t)) alive.add(t)
                }
            }
            if (!onlyKillAuraValue) {
                // 最近 3 秒内被普通攻击过且仍存活的玩家也纳入目标集
                val now = System.currentTimeMillis()
                attackHistory.entries.removeAll { now - it.value > ATTACK_KEEP_MS }
                for ((ent, time) in attackHistory) {
                    if (ent !== mc.thePlayer && now - time <= ATTACK_KEEP_MS && !ent.isDead && ent.isEntityAlive && !AntiBot.isBot(ent)) alive.add(ent)
                }
            }
        }

        val now = System.currentTimeMillis()
        val dtMs = if (multiLastFrame == 0L) 50L else (now - multiLastFrame).coerceIn(1L, 100L)
        multiLastFrame = now
        val dtSec = dtMs / 1000F
        val duration = mainStyle.animDurationMs().coerceAtLeast(50F)
        val speed = (dtMs / duration * 3.0).coerceIn(0.01, 1.0)
        val move = 1F - exp(-dtSec * 14F)

        // 推进动画：可攻击目标淡入；死亡/失效目标淡出，淡出完成后移除（对齐 moonlight TargetHUD.onTick）
        val animIter = multiAnims.entries.iterator()
        while (animIter.hasNext()) {
            val entry = animIter.next()
            val forwards = entry.key in alive
            val card = entry.value
            card.progress = AnimationUtil.base(card.progress.toDouble(), if (forwards) 1.0 else 0.0, speed).toFloat()
            if (!forwards && card.progress <= 0.01F) animIter.remove()
        }
        alive.forEach { multiAnims.putIfAbsent(it, MultiCard()) }

        // 网格/跟随两套坐标系不同，模式切换时重置绘制位置避免互相缓动
        if (lastFollowMode != followTargetValue) {
            multiAnims.values.forEach { it.posInitialized = false }
            lastFollowMode = followTargetValue
        }

        // 样式内部状态清理（对齐 moonlight 各 TargetXxx.cleanup）
        mainStyle.cleanup(alive)

        // 收集本帧要绘制的卡片（最多 10 张）
        val cards = mutableListOf<Pair<EntityPlayer, MultiCard>>()
        for (entry in multiAnims) {
            if (entry.value.progress <= 0.01F) continue
            cards.add(entry.key to entry.value)
            if (cards.size >= 10) break
        }

        if (cards.isEmpty()) {
            animProgress = 1F
            return Border(0F, 0F, 120F, 48F)
        }

        val follow = followTargetValue && !isEditing
        val gap = 4F
        val layouts = ArrayList<MultiLayout>(cards.size)
        var gridRight = 0F
        var gridBottom = 0F

        if (!follow) {
            // 网格排列：一行最多 2 张卡片，从左到右、从上到下
            var slotX = 0F
            var slotY = 0F
            var rowMaxH = 0F
            var countInRow = 0
            for ((target, card) in cards) {
                val border = mainStyle.getBorder(target) ?: Border(0F, 0F, 120F, 48F)
                val w = border.x2 - border.x
                val h = border.y2 - border.y

                if (!card.posInitialized) {
                    card.posX = slotX
                    card.posY = slotY
                    card.posInitialized = true
                } else {
                    // 绘制位置指数平滑向槽位补齐
                    card.posX += (slotX - card.posX) * move
                    card.posY += (slotY - card.posY) * move
                }

                // 卡片内容按各样式 border 原点绘制（如 Astolfo 内容位于 (-23,41)..(148,107)），
                // 平移量补偿 border.x/y，使内容 AABB 恰好落在槽位 [slotX, slotX + w] 内
                layouts.add(
                    MultiLayout(
                        card.posX - border.x, card.posY - border.y,
                        (renderX + card.posX - border.x).toFloat(), (renderY + card.posY - border.y).toFloat(),
                        border
                    )
                )
                gridRight = maxOf(gridRight, slotX + w)
                gridBottom = maxOf(gridBottom, slotY + h)

                rowMaxH = maxOf(rowMaxH, h)
                countInRow++
                slotX += w + gap
                if (countInRow >= 2) {
                    slotX = 0F
                    slotY += rowMaxH + gap
                    countInRow = 0
                    rowMaxH = 0F
                }
            }
        } else {
            // 屏幕外/投影失败卡片的备用槽位游标：一行最多 2 张（与网格模式同布局），
            // 多张卡片同时投影失败时各归各槽缓动回落，不再全部叠在组件原点互相遮挡；
            // 某张卡片恢复可见后，其后的回落卡片槽位前移、绘制位置缓动补齐
            var fbSlotX = renderX.toFloat()
            var fbSlotY = renderY.toFloat()
            var fbRowMaxH = 0F
            var fbCountInRow = 0

            for ((target, card) in cards) {
                val border = mainStyle.getBorder(target) ?: Border(0F, 0F, 120F, 48F)
                val w = border.x2 - border.x
                val h = border.y2 - border.y
                val centerX = border.x + w / 2F
                val centerY = border.y + h / 2F

                if (card.sampleValid && target !== mc.thePlayer) {
                    // 投影成功：卡片锚点缓动贴向目标投影
                    if (!card.posInitialized) {
                        card.posX = card.sampleX
                        card.posY = card.sampleY
                        card.posInitialized = true
                    } else {
                        card.posX += (card.sampleX - card.posX) * move
                        card.posY += (card.sampleY - card.posY) * move
                    }
                } else {
                    // 投影失败/编辑器/自己：缓动回落到组件位置的备用槽位继续渲染
                    // （锚点 = 槽位左上角 + 面板中心，使卡片内容 AABB 恰好落在槽位内）
                    val fallbackX = fbSlotX + centerX
                    val fallbackY = fbSlotY + centerY
                    if (!card.posInitialized) {
                        card.posX = fallbackX
                        card.posY = fallbackY
                        card.posInitialized = true
                    } else {
                        card.posX += (fallbackX - card.posX) * move
                        card.posY += (fallbackY - card.posY) * move
                    }

                    fbRowMaxH = maxOf(fbRowMaxH, h)
                    fbCountInRow++
                    fbSlotX += w + gap
                    if (fbCountInRow >= 2) {
                        fbSlotX = renderX.toFloat()
                        fbSlotY += fbRowMaxH + gap
                        fbCountInRow = 0
                        fbRowMaxH = 0F
                    }
                }

                // 卡片局部原点屏幕坐标 = 锚点 - 面板中心（每张卡相对自身锚点居中）
                val absX = card.posX - centerX
                val absY = card.posY - centerY
                layouts.add(MultiLayout(absX - renderX.toFloat(), absY - renderY.toFloat(), absX, absY, border))
            }
        }

        GL11.glPushMatrix()
        try {
            for ((index, pair) in cards.withIndex()) {
                val (target, card) = pair
                val layout = layouts[index]
                val border = layout.border

                // 出入场动画系数（AnimationType）：Scale/Zoom 按进度缩放，Bounce 带回弹过冲，None 不缩放
                val animK = when (animationType) {
                    "None" -> 1F
                    "Bounce" -> easeOutBack(card.progress.coerceIn(0F, 1F))
                    else -> card.progress
                }
                val bcx = border.x + (border.x2 - border.x) / 2F
                val bcy = border.y + (border.y2 - border.y) / 2F

                // 每张卡片按自身进度重算主题色/背景色与淡出透明度（getColor 依赖 animProgress）
                val preBarColor = when (colorModeValue) {
                    "Rainbow" -> Color(ColorUtils.getRainbowOpaque(waveSecondValue, saturationValue, brightnessValue, 0))
                    "Custom" -> Color(redValue, greenValue, blueValue)
                    "Sky" -> ColorUtils.skyRainbow(0, saturationValue, brightnessValue, 1f)
                    "Fade" -> ColorUtils.fade(Color(redValue, greenValue, blueValue), 0, 100)
                    "Health" -> BlendUtils.getHealthColor(target.health, target.maxHealth)
                    "Theme" -> ClientThemesUtils.getColor()
                    "Mixer" -> getMixedColor(0, waveSecondValue)
                    else -> ColorUtils.LiquidSlowly(System.nanoTime(), 0, saturationValue, brightnessValue)
                }
                animProgress = 1F - card.progress
                barColor = ColorUtils.reAlpha(preBarColor, preBarColor.alpha / 255F * card.progress)
                bgColor = Color(bgColorValue.red, bgColorValue.green, bgColorValue.blue, (bgColorValue.alpha * card.progress).toInt())

                GL11.glPushMatrix()
                GL11.glTranslatef(layout.localX, layout.localY, 0F)

                if (shadowValue && mainStyle.shaderSupport) {
                    // 先回退卡片平移再保留 HUD 的 scale（对齐单目标路径）：
                    // 形状绘制矩阵 = S(scale) * T(render + local)，与卡片内容完全一致
                    GL11.glPushMatrix()
                    GL11.glTranslated(-renderX - layout.localX, -renderY - layout.localY, 0.0)
                    ShadowUtils.shadow(shadowStrength, {
                        GL11.glPushMatrix()
                        GL11.glTranslated(renderX + layout.localX, renderY + layout.localY, 0.0)
                        mainStyle.handleShadow(target)
                        GL11.glPopMatrix()
                    }, {
                        GL11.glPushMatrix()
                        GL11.glTranslated(renderX + layout.localX, renderY + layout.localY, 0.0)
                        mainStyle.handleShadowCut(target)
                        GL11.glPopMatrix()
                    })
                    GL11.glPopMatrix()
                }

                if (blurValue && mainStyle.shaderSupport) {
                    // 动画后的卡片局部 AABB（绕内容中心缩放，与下方 drawTarget 的动画变换一致）
                    val lx1 = bcx + (border.x - bcx) * animK
                    val ly1 = bcy + (border.y - bcy) * animK
                    val lx2 = bcx + (border.x2 - bcx) * animK
                    val ly2 = bcy + (border.y2 - bcy) * animK

                    // 完全重置矩阵为单位阵（HUD scale + 平移 + 卡片平移全部抵消）后按
                    // 屏幕绝对坐标绘制遮罩。此前残留的卡片平移会把遮罩推到错误位置，
                    // 而 HudBlur 强制打开 colorMask 时白色遮罩形状会直接画进画面。
                    GL11.glPushMatrix()
                    GL11.glTranslated(-renderX - layout.localX, -renderY - layout.localY, 0.0)
                    GL11.glScalef(1F / scale, 1F / scale, 1F)
                    HudBlur.blur(
                        (scale * (layout.absX + lx1)).toFloat(), (scale * (layout.absY + ly1)).toFloat(),
                        (scale * (layout.absX + lx2)).toFloat(), (scale * (layout.absY + ly2)).toFloat(),
                        strengthScale = card.progress
                    ) {
                        // 遮罩与 drawTarget 的动画变换一致：绕内容中心按 animK 缩放
                        GL11.glTranslatef((scale * (layout.absX + bcx)).toFloat(), (scale * (layout.absY + bcy)).toFloat(), 0F)
                        GL11.glScalef(scale * animK, scale * animK, 1F)
                        GL11.glTranslatef(-bcx, -bcy, 0F)
                        mainStyle.handleBlur(target)
                    }
                    GL11.glPopMatrix()
                }

                // 出入场动画（AnimationType）：新卡片出现/消失时绕内容中心缩放
                if (animK < 0.999F) {
                    GL11.glTranslatef(bcx, bcy, 0F)
                    GL11.glScalef(animK, animK, 1F)
                    GL11.glTranslatef(-bcx, -bcy, 0F)
                }

                mainStyle.drawTarget(target)
                GL11.glPopMatrix()

                GlStateManager.resetColor()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            GlStateManager.resetColor()
            GL11.glPopMatrix()
        }

        return if (follow) {
            // 跟随模式：卡片画在各目标投影处，组件占位用固定 Border
            Border(0F, 0F, 120F, 48F)
        } else {
            // 网格模式：返回整个网格的 AABB
            Border(0F, 0F, gridRight.coerceAtLeast(1F), gridBottom.coerceAtLeast(1F))
        }
    }

    override fun handleDamage(ent: EntityPlayer) {
        val currentStyleName = styleValue.get()
        val mainStyle = styleList.find { it.name.equals(currentStyleName, ignoreCase = true) }
        if (multiTargetValue) {
            // 多目标模式：受击目标在动画表中即转发（伤害反馈按目标各自维护）
            if (multiAnims.containsKey(ent)) mainStyle?.handleDamage(ent)
            return
        }
        if (mainTarget != null && ent == mainTarget) {
            mainStyle?.handleDamage(ent)
        }
    }

    /** easeOutBack：带过冲回弹的出场曲线（AnimationType = Bounce 时使用） */
    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158F
        val c3 = c1 + 1F
        val x = t - 1F
        return 1F + c3 * x * x * x + c1 * x * x
    }

    companion object {
        /** 普通攻击目标的保留窗口（毫秒） */
        const val ATTACK_KEEP_MS = 3000L
    }

    fun getFadeProgress() = animProgress
}
