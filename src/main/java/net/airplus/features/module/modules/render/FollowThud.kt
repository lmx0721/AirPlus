/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Ported from LiquidBounce ModuleFollowThud / TargetPanelStyle (Chill / Flux / Rice).
 * Renders a small panel with the target's head, name and an animated health bar,
 * following the target on screen.
 */
package net.airplus.features.module.modules.render

import net.airplus.event.Render2DEvent
import net.airplus.event.Render3DEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.modules.combat.KillAura
import net.airplus.ui.font.Fonts
import net.airplus.utils.render.RenderUtils.drawRoundedBorder
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.airplus.utils.render.RenderUtils.drawRect
import net.airplus.utils.render.WorldToScreen
import net.minecraft.client.entity.AbstractClientPlayer
import net.minecraft.client.gui.Gui
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.entity.EntityLivingBase
import org.lwjgl.opengl.GL11.*
import org.lwjgl.util.vector.Vector3f
import java.awt.Color
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

object FollowThud : Module("FollowThud", Category.RENDER) {

    private val mode by choices("Style", arrayOf("Chill", "Flux", "Rice"), "Chill")
    private val height by float("Height", 2.1f, 0f..4f)
    private val scale by float("Scale", 1f, 0.5f..2f)
    private val barColor by color("BarColor", Color(66, 134, 245))

    // Chill 专属
    private val chillFontSpeed by float("FontSpeed", 0.5f, 0.01f..1f) { mode == "Chill" }
    private val chillRoundedBar by boolean("RoundedBar", true) { mode == "Chill" }

    // Rice 专属
    private val gradient by boolean("Gradient", true) { mode == "Rice" }
    private val gradientLoop by int("GradientLoop", 4, 1..40) { mode == "Rice" }
    private val gradientDistance by int("GradientDistance", 50, 1..200) { mode == "Rice" }
    private val gradientRoundedBar by boolean("GradientRoundedBar", true) { mode == "Rice" }
    private val riceParticle by boolean("Particles", true) { mode == "Rice" }
    private val riceParticleSpin by boolean("ParticleSpin", true) { mode == "Rice" }
    private val riceParticleCircle by boolean("CircleParticles", true) { mode == "Rice" }
    private val riceParticleRect by boolean("RectParticles", true) { mode == "Rice" }
    private val riceParticleTriangle by boolean("TriangleParticles", true) { mode == "Rice" }
    private val generateAmount by int("GenerateAmount", 10, 1..40) { mode == "Rice" }
    private val riceParticleSpeed by float("ParticleSpeed", 0.05f, 0.01f..0.2f) { mode == "Rice" }
    private val riceParticleFade by boolean("ParticleFade", true) { mode == "Rice" }
    private val riceParticleFadingSpeed by float("ParticleFadingSpeed", 0.05f, 0.01f..0.2f) { mode == "Rice" }
    private val particleRange by float("ParticleRange", 50f, 0f..50f) { mode == "Rice" }
    private val minParticleSize by float("MinParticleSize", 0.5f, 0f..5f) { mode == "Rice" }
    private val maxParticleSize by float("MaxParticleSize", 2.5f, 0f..5f) { mode == "Rice" }

    override val tag: String?
        get() = mode

    // Render3D 阶段捕获的世界矩阵，供 Render2D 投影使用
    private var mvMatrix: org.lwjgl.util.vector.Matrix4f? = null
    private var projectionMatrix: org.lwjgl.util.vector.Matrix4f? = null

    // 面板动画状态
    private var lastEntityId = -1
    private var lastFrameMs = 0L
    private var deltaTimeMs = 0f
    private var easedHealth = 0f

    // Chill 数字弹跳
    private var lastShownHealth = -1f
    private var numberBump = 0f

    // Rice 粒子
    private val particleList = mutableListOf<TargetParticle>()
    private var lastHurtTime = 0

    val onRender3D = handler<Render3DEvent> {
        if (mc.theWorld == null || mc.thePlayer == null) return@handler
        mvMatrix = WorldToScreen.getMatrix(GL_MODELVIEW_MATRIX)
        projectionMatrix = WorldToScreen.getMatrix(GL_PROJECTION_MATRIX)
    }

    val onRender2D = handler<Render2DEvent> { event ->
        val target = KillAura.target ?: return@handler
        val mv = mvMatrix ?: return@handler
        val proj = projectionMatrix ?: return@handler

        // 目标插值位置 + 高度偏移，投影到屏幕后换算成 GUI 缩放坐标
        val tickDelta = event.partialTicks
        val wx = target.lastTickPosX + (target.posX - target.lastTickPosX) * tickDelta
        val wy = target.lastTickPosY + (target.posY - target.lastTickPosY) * tickDelta + height
        val wz = target.lastTickPosZ + (target.posZ - target.lastTickPosZ) * tickDelta
        val screenPos = WorldToScreen.worldToScreen(
            Vector3f(wx.toFloat(), wy.toFloat(), wz.toFloat()), mv, proj, mc.displayWidth, mc.displayHeight
        ) ?: return@handler

        val sr = ScaledResolution(mc)
        val guiScale = mc.displayWidth.toFloat() / sr.scaledWidth.toFloat()
        val guiX = screenPos.x / guiScale
        val guiY = screenPos.y / guiScale

        updateAnimation(target)

        glPushMatrix()
        glTranslatef(guiX, guiY, 0f)
        glScalef(scale, scale, scale)
        glDisable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        when (mode) {
            "Flux" -> drawFlux(target)
            "Rice" -> drawRice(target)
            else -> drawChill(target)
        }

        glDisable(GL_BLEND)
        glEnable(GL_DEPTH_TEST)
        glColor4f(1f, 1f, 1f, 1f)
        glPopMatrix()
    }

    private fun updateAnimation(living: EntityLivingBase) {
        val now = System.currentTimeMillis()
        deltaTimeMs = if (lastFrameMs == 0L) 0f else (now - lastFrameMs).coerceAtMost(250L).toFloat()
        lastFrameMs = now

        val delta = deltaTimeMs / 1000f
        if (living.entityId != lastEntityId) {
            lastEntityId = living.entityId
            easedHealth = living.health
        } else {
            easedHealth += (living.health - easedHealth) * (delta * 10f).coerceIn(0f, 1f)
        }
    }

    // ========== Chill ==========

    private fun drawChill(living: EntityLivingBase) {
        val nameFont = Fonts.fontSemibold40
        val hpFont = Fonts.font52
        val name = living.name ?: ""
        val hpText = HEALTH_FORMAT.format(easedHealth.toDouble())
        val width = (45f + maxOf(
            nameFont.getStringWidth(name).toFloat(),
            hpFont.getStringWidth(hpText) * 1.5f
        )).coerceAtLeast(120f)
        val panelHeight = 48f

        glTranslatef(-width / 2f, -panelHeight, 0f)

        drawRoundedRect(0f, 0f, width, panelHeight, PANEL_BG, 5f)
        drawHead(living, 4f, 4f, 30f)
        drawRoundedBorder(4f, 4f, 34f, 34f, 1f, Color(255, 255, 255, 100).rgb, 7f)

        nameFont.drawString(name, 38f, 6f, Color.WHITE.rgb)

        // 血量数字在掉血/回血时弹跳（FontSpeed 控制回落速度）
        if (lastShownHealth < 0f || abs(easedHealth - lastShownHealth) > 0.05f) {
            numberBump = 1f
        }
        lastShownHealth = easedHealth
        numberBump = (numberBump - deltaTimeMs * 0.001f * chillFontSpeed * 3f).coerceAtLeast(0f)
        val numberScale = 1f + 0.35f * numberBump

        glPushMatrix()
        glTranslatef(38f, 17f - 3f * numberBump, 0f)
        glScalef(numberScale, numberScale, 1f)
        hpFont.drawString(hpText, 0f, 0f, Color.WHITE.rgb)
        glPopMatrix()

        val fraction = (easedHealth / living.maxHealth.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val barWidth = fraction * (width - 8f)
        if (chillRoundedBar) {
            drawRoundedRect(4f, 38f, width - 4f, 44f, barColor.darker().rgb, 3f)
            drawRoundedRect(4f, 38f, 4f + barWidth, 44f, barColor.rgb, 3f)
        } else {
            drawRect(4f, 38f, width - 4f, 44f, barColor.darker().rgb)
            drawRect(4f, 38f, 4f + barWidth, 44f, barColor.rgb)
        }
    }

    // ========== Flux ==========

    private fun drawFlux(living: EntityLivingBase) {
        val font = Fonts.fontSemibold40
        val name = living.name ?: ""
        val hpText = HEALTH_FORMAT.format(easedHealth.toDouble())
        val additionalWidth = font.getStringWidth("$name  $hpText hp").toFloat().coerceAtLeast(75f)
        val width = 45f + additionalWidth + 6f
        val panelHeight = 34f

        glTranslatef(-width / 2f, -panelHeight, 0f)

        drawRoundedRect(0f, 0f, width, panelHeight, PANEL_BG, 5f)
        drawHead(living, 5f, 3f, 28f)
        drawRoundedBorder(5f, 3f, 34f, 32f, 1f, Color(255, 255, 255, 100).rgb, 0f)

        font.drawString(name, 40f, 4f, Color.WHITE.rgb)
        font.drawString("$hpText hp", 40f, 15f, Color.WHITE.rgb)

        val fraction = (easedHealth / living.maxHealth.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val barWidth = fraction * (additionalWidth + 6f)
        drawRoundedRect(40f, 26f, 45f + additionalWidth + 1f, 31f, Color(30, 30, 30).rgb, 2f)
        drawRoundedRect(40f, 26f, 40f + barWidth, 31f, barColor.rgb, 2f)
    }

    // ========== Rice ==========

    private fun drawRice(living: EntityLivingBase) {
        val font = Fonts.fontSemibold35
        val name = living.name ?: ""
        val player = mc.thePlayer ?: return
        val distance = player.getDistanceToEntity(living).roundToInt()
        val length = maxOf(
            font.getStringWidth("Name: $name").toFloat(),
            font.getStringWidth("Distance: $distance").toFloat()
        ).coerceAtLeast(125f) + 40f
        val maxHealthLength = font.getStringWidth(HEALTH_FORMAT.format(living.maxHealth.toDouble()))
        val width = 10f + length
        val panelHeight = 55f

        glTranslatef(-width / 2f, -panelHeight, 0f)

        drawRoundedRect(0f, 0f, width, panelHeight, PANEL_BG, 8f)

        // 受击动画：头部缩小并淡出
        val scaleHT = (living.hurtTime / 10f).coerceIn(0f, 1f)
        val headOffset = 5f + 15f * (scaleHT * 0.2f)
        drawHead(
            living, headOffset, headOffset, 30f * (1f - scaleHT * 0.2f),
            alpha = ((0.4f + (1f - scaleHT) * 0.6f) * 255f).toInt()
        )

        if (riceParticle) {
            updateParticles(living)
        } else {
            lastHurtTime = living.hurtTime
        }

        font.drawString("Name: $name", 39f, 11f, Color.WHITE.rgb)
        font.drawString("Distance: $distance", 39f, 23f, Color.WHITE.rgb)

        val fraction = (easedHealth / living.maxHealth.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val gradientLength = length - 5f - maxHealthLength
        val barWidth = gradientLength * fraction.pow(2)

        if (gradient) {
            drawGradientBar(gradientLength, barWidth)
        } else if (barWidth > 0f) {
            drawRiceBarShape(5f, 5f + barWidth, barColor)
        }

        font.drawString(HEALTH_FORMAT.format(easedHealth.toDouble()), 10f + barWidth, 41f, Color.WHITE.rgb)
    }

    private fun drawRiceBarShape(start: Float, end: Float, color: Color) {
        if (gradientRoundedBar) {
            drawRoundedRect(start, 42f, end, 48f, color.rgb, 3f)
        } else {
            drawRect(start, 42f, end, 48f, color.rgb)
        }
    }

    /** 色相偏移渐变血条段，裁剪到动画宽度 [barWidth]（AirClient 同款行为）。 */
    private fun drawGradientBar(gradientLength: Float, barWidth: Float) {
        if (barWidth <= 0f) {
            return
        }

        val end = 5f + barWidth
        val loop = gradientLoop
        if (gradientRoundedBar && barWidth < 12f) {
            // 过短无法画两端圆头：整条用首段颜色画圆角
            drawRoundedRect(5f, 42f, end, 48f, segmentColor(0).rgb, 3f)
            return
        }

        val bodyStart = if (gradientRoundedBar) 8f else 5f
        val bodyEnd = if (gradientRoundedBar) end - 3f else end
        for (i in 0 until loop) {
            val start = (5f + gradientLength * i / loop).coerceAtLeast(bodyStart)
            val stop = (5f + gradientLength * (i + 1) / loop).coerceAtMost(bodyEnd)
            if (stop > start) {
                drawRect(start, 42f, stop, 48f, segmentColor(i).rgb)
            }
        }
        if (gradientRoundedBar) {
            // 两端圆头压在分段之上，避免分段切断圆角
            drawRoundedRect(5f, 42f, 11f, 48f, segmentColor(0).rgb, 3f)
            drawRoundedRect(
                end - 6f, 42f, end, 48f,
                segmentColor(((end - 5f) / gradientLength * loop).toInt().coerceIn(0, loop - 1)).rgb, 3f
            )
        }
    }

    private fun segmentColor(i: Int) = shiftHue(barColor, i * gradientDistance)

    private fun shiftHue(color: Color, shift: Int): Color {
        val hsb = Color.RGBtoHSB(color.red, color.green, color.blue, null)
        return Color(Color.HSBtoRGB((hsb[0] + shift / 360f) % 1f, hsb[1], hsb[2]))
    }

    // ========== Rice 粒子 ==========

    private fun updateParticles(living: EntityLivingBase) {
        if (living.hurtTime > 0 && lastHurtTime == 0) {
            spawnParticles()
        }
        lastHurtTime = living.hurtTime

        val deleteQueue = mutableListOf<TargetParticle>()
        for (particle in particleList) {
            if (particle.alpha > 0f) {
                particle.render(
                    20f, 20f,
                    riceParticleFade,
                    riceParticleSpeed,
                    riceParticleFadingSpeed,
                    riceParticleSpin,
                    deltaTimeMs
                )
            } else {
                deleteQueue.add(particle)
            }
        }
        particleList.removeAll(deleteQueue)
    }

    private fun spawnParticles() {
        val shapes = mutableListOf<TargetParticleShape>()
        if (riceParticleCircle) shapes.add(TargetParticleShape.SOLID_CIRCLE)
        if (riceParticleRect) shapes.add(TargetParticleShape.SOLID_RECT)
        if (riceParticleTriangle) shapes.add(TargetParticleShape.SOLID_TRIANGLE)
        if (shapes.isEmpty()) {
            return
        }
        val random = ThreadLocalRandom.current()
        repeat(generateAmount) {
            val blend = if (random.nextBoolean()) random.nextFloat() * 0.5f + 0.5f else 0f
            particleList.add(
                TargetParticle(
                    interpolateColor(Color.WHITE, barColor, blend),
                    (random.nextFloat() * 2f - 1f) * particleRange,
                    (random.nextFloat() * 2f - 1f) * particleRange,
                    minParticleSize + random.nextFloat() * (maxParticleSize - minParticleSize),
                    shapes.random()
                )
            )
        }
    }

    private fun interpolateColor(from: Color, to: Color, t: Float): Color = Color(
        (from.red + (to.red - from.red) * t).roundToInt().coerceIn(0, 255),
        (from.green + (to.green - from.green) * t).roundToInt().coerceIn(0, 255),
        (from.blue + (to.blue - from.blue) * t).roundToInt().coerceIn(0, 255)
    )

    private class TargetParticle(
        val color: Color,
        val distX: Float,
        val distY: Float,
        val radius: Float,
        val shape: TargetParticleShape,
    ) {
        var alpha = 1f
        private var progress = 0.0
        private var rotate = 0f

        fun render(
            x: Float,
            y: Float,
            fade: Boolean,
            speed: Float,
            fadeSpeed: Float,
            canRotate: Boolean,
            deltaTime: Float,
        ) {
            if (progress >= 1.0) {
                progress = 1.0
                if (fade) {
                    alpha -= fadeSpeed * 0.02f * deltaTime
                    if (alpha < 0f) {
                        alpha = 0f
                    }
                }
            } else {
                progress += (speed * 0.025f * deltaTime).toDouble()
            }

            if (alpha <= 0f) {
                return
            }

            val easedColor = Color(
                color.red, color.green, color.blue,
                (alpha * 255f).toInt().coerceIn(0, 255)
            )
            val easeOut = (1.0 - (1.0 - progress).pow(4)).toFloat()
            val rotation = if (canRotate && shape.rotatable) {
                rotate += 10f * (1f - easeOut)
                rotate
            } else {
                0f
            }

            shape.draw(this, x + distX * easeOut, y + distY * easeOut, radius, easedColor, rotation)
        }
    }

    private enum class TargetParticleShape(val rotatable: Boolean) {
        SOLID_CIRCLE(false) {
            override fun draw(particle: TargetParticle, x: Float, y: Float, radius: Float, color: Color, rotation: Float) {
                drawRoundedRect(x - radius, y - radius, x + radius, y + radius, color.rgb, radius)
            }
        },
        SOLID_RECT(true) {
            override fun draw(particle: TargetParticle, x: Float, y: Float, radius: Float, color: Color, rotation: Float) {
                val half = radius / 2f
                glPushMatrix()
                glTranslatef(x, y, 0f)
                if (rotation != 0f) glRotatef(rotation, 0f, 0f, 1f)
                drawRect(-half, -half, half, half, color.rgb)
                glPopMatrix()
            }
        },
        SOLID_TRIANGLE(true) {
            override fun draw(particle: TargetParticle, x: Float, y: Float, radius: Float, color: Color, rotation: Float) {
                val points = FloatArray(6)
                for (i in 0..2) {
                    val angle = Math.toRadians((rotation + 90f + i * 120f).toDouble())
                    points[i * 2] = x + radius * cos(angle).toFloat()
                    points[i * 2 + 1] = y + radius * sin(angle).toFloat()
                }
                glPushMatrix()
                glDisable(GL_TEXTURE_2D)
                glColor4f(color.red / 255f, color.green / 255f, color.blue / 255f, color.alpha / 255f)
                glBegin(GL_TRIANGLES)
                glVertex2f(points[0], points[1])
                glVertex2f(points[2], points[3])
                glVertex2f(points[4], points[5])
                glEnd()
                glEnable(GL_TEXTURE_2D)
                glColor4f(1f, 1f, 1f, 1f)
                glPopMatrix()
            }
        };

        abstract fun draw(particle: TargetParticle, x: Float, y: Float, radius: Float, color: Color, rotation: Float)
    }

    // ========== 通用 ==========

    /** 绘制目标头部（皮肤 64x64 的脸部区域 8,8 起 8x8）。 */
    private fun drawHead(entity: EntityLivingBase, x: Float, y: Float, size: Float, alpha: Int = 255) {
        val skin = (entity as? AbstractClientPlayer)?.locationSkin
            ?: DefaultPlayerSkin.getDefaultSkin(entity.uniqueID)
        val alphaF = alpha / 255f

        glDepthMask(false)
        glColor4f(1f, 1f, 1f, alphaF)
        mc.textureManager.bindTexture(skin)
        Gui.drawScaledCustomSizeModalRect(
            x.toInt(), y.toInt(), 8f, 8f, 8, 8, size.toInt(), size.toInt(), 64f, 64f
        )
        glDepthMask(true)
        glColor4f(1f, 1f, 1f, 1f)
    }

    private val PANEL_BG = Color(0, 0, 0, 130).rgb

    private val HEALTH_FORMAT = DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.US))
}
