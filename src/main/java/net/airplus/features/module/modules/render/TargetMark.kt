/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.render

import net.airplus.event.AttackEvent
import net.airplus.event.Render2DEvent
import net.airplus.event.Render3DEvent
import net.airplus.event.UpdateEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.modules.combat.KillAura
import net.airplus.utils.render.ColorUtils
import net.airplus.utils.render.ESPUtil
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.render.drawWithTessellatorWorldRenderer
import net.airplus.utils.timing.TimerUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.RenderGlobal
import net.minecraft.client.renderer.WorldRenderer
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.entity.EntityAgeable
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.util.AxisAlignedBB
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.GL11.*
import java.awt.Color
import kotlin.math.*

/** 本项目 WorldRenderer 没有 color(int) 重载，按 ARGB 拆包。 */
private fun WorldRenderer.color(argb: Int): WorldRenderer =
    color(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF, argb shr 24 and 0xFF)

object TargetMark : Module("TargetMark", Category.RENDER) {

    // ==================== Moonlight TargetESP 移植：状态 ====================
    private val glowCircleTexture = ResourceLocation("airplus/moonlight/texture/targetesp/glow_circle.png")
    private val rectangleTexture = ResourceLocation("airplus/moonlight/texture/targetesp/rectangle.png")
    private val quadStappleTexture = ResourceLocation("airplus/moonlight/texture/targetesp/quadstapple.png")
    private val triangleStappleTexture = ResourceLocation("airplus/moonlight/texture/targetesp/trianglestapple.png")
    private val triangleStippleTexture = ResourceLocation("airplus/moonlight/texture/targetesp/trianglestipple.png")

    // Moonlight 追加模式（原有 Circle 为默认样式贴图名，Moonlight 的 Circle 模式记为 Circle3D）
    private val moonlightModes = arrayOf("Points", "Ghost", "Image", "Exhi", "Circle3D")

    private var espTarget: EntityLivingBase? = null
    private val attackTimer = TimerUtils()
    private val alphaAnim = DecelerateAnimation(400, 1.0)
    private val lastTime = System.currentTimeMillis()
    private var prevCircleStep = 0.0
    private var circleStep = 0.0
    private var espTarget2DPos: Pair<Float, Float>? = null

    // ==================== 原有设置（保持不变） ====================
    private val sizeValue by float("Size", 0.05f, 0.05f..0.1f)
    private val rotationSpeed by float("RotationSpeed", 180f, 0f..360f)
    private val color by color("Color", Color(255, 255, 255, 150))
    private val choiceImage by choices(
        "Image",
        arrayOf("Outline", "Outline2", "Circle", "CCBlueX", "Cry", "Creeper", "Points", "Ghost", "Image", "Exhi", "Circle3D"),
        "Outline"
    )

    // ==================== Moonlight TargetESP 移植：设置 ====================
    private val choiceTargetESPImage by choices(
        "ImageMode", arrayOf("Rectangle", "QuadStapple", "TriangleStapple", "TriangleStipple"), "Rectangle"
    ) { choiceImage == "Image" }
    private val circleSpeed by float("CircleSpeed", 2f, 1f..5f) { choiceImage == "Circle3D" }
    private val onlyPlayer by boolean("OnlyPlayer", true)

    private var rotation = 0f

    // ==================== Moonlight TargetESP 移植：事件 ====================
    private val onAttack = handler<AttackEvent> { event ->
        val attacked = event.targetEntity as? EntityLivingBase ?: return@handler
        if (onlyPlayer && attacked !is EntityPlayer) return@handler
        espTarget = attacked
        alphaAnim.setDirection(DecelerateAnimation.Direction.FORWARDS)
        attackTimer.reset()
    }

    private val onUpdate = handler<UpdateEvent> {
        if (espTarget == null) return@handler
        if (attackTimer.hasTimeElapsed(100L)) {
            alphaAnim.setDirection(DecelerateAnimation.Direction.BACKWARDS)
            if (alphaAnim.isDone) {
                espTarget = null
            }
        }
    }

    val onRender3D = handler<Render3DEvent> { event ->
        // ===== Moonlight TargetESP 移植部分 =====
        val current = espTarget
        if (current != null) {
            when (choiceImage) {
                "Points" -> drawPoints(current)
                "Ghost" -> drawGhost(current)
                "Exhi" -> drawExhi(current)
                "Circle3D" -> drawCircle3D(current)
            }
            if (choiceImage == "Image") {
                espTarget2DPos = targetESP2DPos(current)
            }
        } else if (choiceImage == "Image") {
            espTarget2DPos = null
        }

        // ===== 原有默认样式（绘制路径保持不变） =====
        if (choiceImage !in moonlightModes) {
            KillAura.target?.let { entity ->
                val x = (entity.prevPosX + (entity.posX - entity.prevPosX) * event.partialTicks) - mc.renderManager.viewerPosX
                val y = (entity.prevPosY + (entity.posY - entity.prevPosY) * event.partialTicks) + entity.height * 0.6 - mc.renderManager.viewerPosY
                val z = (entity.prevPosZ + (entity.posZ - entity.prevPosZ) * event.partialTicks) - mc.renderManager.viewerPosZ
                glPushMatrix()
                glTranslated(x, y, z)
                glRotatef(-mc.renderManager.playerViewY, 0f, 1f, 0f)
                glRotatef(mc.renderManager.playerViewX * if (mc.gameSettings.thirdPersonView == 2) -1 else 1, 1f, 0f, 0f)
                if (choiceImage != "Outline") {
                    rotation = 180f
                } else {
                    rotation += rotationSpeed * (event.partialTicks / 20f)
                }
                glRotatef(rotation % 360, 0f, 0f, 1f)
                val finalSize = sizeValue * 0.8f
                glScalef(finalSize, finalSize, finalSize)

                drawTargetMark()

                glDisable(GL_BLEND)
                glEnable(GL_DEPTH_TEST)
                glPopMatrix()
            }
        }
    }

    private val onRender2D = handler<Render2DEvent> {
        if (choiceImage != "Image") return@handler
        val current = espTarget ?: return@handler
        val pos = espTarget2DPos ?: return@handler
        val player = mc.thePlayer ?: return@handler
        val alphaScale = 1f - (abs(player.getDistanceToEntity(current) - 6f) / 60f).coerceIn(0f, 0.75f)
        drawTargetESP2D(pos.first, pos.second, alphaScale)
    }

    private fun drawTargetMark() {
        val texture: ResourceLocation = when (choiceImage) {
            "Outline" -> ResourceLocation("airplus/targetimage/target.png")
            "Outline2" -> ResourceLocation("airplus/targetimage/target2.png")
            "Circle" -> ResourceLocation("airplus/targetimage/glow_circle.png")
            "CCBlueX" -> ResourceLocation("airplus/targetimage/ccbluex.png")
            "Cry" -> ResourceLocation("airplus/targetimage/cry.png")
            "Creeper" -> ResourceLocation("airplus/targetimage/creeper.png")
            else -> ResourceLocation("airplus/targetimage/target.png")
        }
        RenderUtils.drawImage(texture, -16, -16, 32, 32, color)
    }

    // ==================== Moonlight TargetESP 移植：绘制 ====================

    private fun drawPoints(espTarget: EntityLivingBase) {
        val pt = mc.timer.renderPartialTicks
        val px = espTarget.lastTickPosX + (espTarget.posX - espTarget.lastTickPosX) * pt
        val py = espTarget.lastTickPosY + (espTarget.posY - espTarget.lastTickPosY) * pt + espTarget.height / 1.6F
        val pz = espTarget.lastTickPosZ + (espTarget.posZ - espTarget.lastTickPosZ) * pt
        val elapsed = (System.currentTimeMillis() - lastTime) / 1500.0
        var phase = (elapsed + sin(elapsed) / 10.0).toFloat()
        var yOffset = 0f
        var expanded = false
        val base = Color(color.rgb)
        val opacity = alphaAnim.output.toFloat()
        val outerColor = applyOpacity(base, opacity).rgb
        val innerColor = applyOpacity(ColorUtils.shiftHue(base, 90), opacity).rgb
        val blendWasEnabled = glIsEnabled(GL_BLEND)
        mc.textureManager.bindTexture(glowCircleTexture)

        repeat(3) {
            val start = phase * 360f
            var angle = start
            while (angle < start + 90f) {
                val progress = (angle - (start - 45f)) / 135f
                val radius = 0.6f
                val rad = Math.toRadians(angle.toDouble())
                val y = yOffset + sin(rad * 1.2) * 0.1
                GlStateManager.pushMatrix()
                RenderUtils.setupOrientationMatrix(px, py, pz)
                GlStateManager.rotate(-mc.renderManager.playerViewY, 0f, 1f, 0f)
                GlStateManager.rotate(mc.renderManager.playerViewX, 1f, 0f, 0f)
                GlStateManager.depthMask(false)
                val baseScale = if (expanded) 0.15f else 0.25f
                val factor = if (expanded) max(0.25f, progress) else max(0.15f, (1f + (0.4f - progress)) / 2f)
                val pointSize = baseScale * (factor + 0.45f) * 2f
                drawImageBlendedWorld(
                    cos(rad) * radius - pointSize / 2.0,
                    y - 0.7,
                    sin(rad) * radius - pointSize / 2.0,
                    pointSize.toDouble(), pointSize.toDouble(),
                    outerColor, innerColor, innerColor, outerColor
                )
                glEnable(GL_DEPTH_TEST)
                GlStateManager.depthMask(true)
                GlStateManager.popMatrix()
                angle += 2f
            }
            phase *= -1.025f
            expanded = !expanded
            yOffset += 0.45f
        }

        if (!blendWasEnabled) {
            GlStateManager.disableBlend()
        }
    }

    private fun drawGhost(espTarget: EntityLivingBase) {
        val pt = mc.timer.renderPartialTicks
        GlStateManager.pushMatrix()
        try {
            GlStateManager.disableLighting()
            GlStateManager.depthMask(false)
            GlStateManager.enableBlend()
            glShadeModel(GL_SMOOTH)
            GlStateManager.disableCull()
            GlStateManager.disableAlpha()
            GlStateManager.tryBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE, GL_ZERO, GL_ONE)
            val gap = 0.67
            val size = 0.4f
            val spacing = 19.0
            val count = 20
            val ix = espTarget.lastTickPosX + (espTarget.posX - espTarget.lastTickPosX) * pt
            val iy = espTarget.lastTickPosY + (espTarget.posY - espTarget.lastTickPosY) * pt + 0.75
            val iz = espTarget.lastTickPosZ + (espTarget.posZ - espTarget.lastTickPosZ) * pt
            RenderUtils.setupOrientationMatrix(ix, iy + 0.5, iz)
            GlStateManager.rotate(-mc.renderManager.playerViewY, 0f, 1f, 0f)
            GlStateManager.rotate(mc.renderManager.playerViewX, 1f, 0f, 0f)
            val glowColor = applyOpacity(Color(color.rgb), alphaAnim.output.toFloat()).rgb
            repeat(3) { ring ->
                for (i in 0 until count) {
                    val t = 0.15 * (System.currentTimeMillis() - lastTime - i * spacing) / 45.0
                    val sx = sin(t) * gap
                    val sy = cos(t) * gap
                    val offset = when (ring) {
                        0 -> doubleArrayOf(sx, sy, -sy)
                        1 -> doubleArrayOf(-sx, sy, -sy)
                        else -> doubleArrayOf(-sx, -sx, sy)
                    }
                    GlStateManager.translate(offset[0], offset[1], offset[2])
                    drawImageWorld(glowCircleTexture, 0f, 0f, -size, -size, glowColor)
                    GlStateManager.translate(-offset[0], -offset[1], -offset[2])
                }
            }
        } finally {
            GlStateManager.tryBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO)
            GlStateManager.disableBlend()
            GlStateManager.enableCull()
            GlStateManager.enableAlpha()
            GlStateManager.depthMask(true)
            GlStateManager.popMatrix()
        }
    }

    private fun drawExhi(espTarget: EntityLivingBase) {
        val boxColor = when {
            espTarget.hurtTime > 3 -> Color(200, 255, 100, 75)
            espTarget.hurtTime < 3 -> Color(235, 40, 40, 75)
            else -> Color(255, 255, 255, 75)
        }
        val player = mc.thePlayer ?: return
        val pt = mc.timer.renderPartialTicks
        GlStateManager.pushMatrix()
        try {
            glShadeModel(GL_SMOOTH)
            glHint(GL_PERSPECTIVE_CORRECTION_HINT, GL_NICEST)
            mc.entityRenderer.setupCameraTransform(pt, 0)
            val dx = espTarget.posX - espTarget.prevPosX
            val dy = espTarget.posY - espTarget.prevPosY
            val dz = espTarget.posZ - espTarget.prevPosZ
            val ix = espTarget.prevPosX + dx * pt - mc.renderManager.renderPosX
            val iy = espTarget.prevPosY + dy * pt - mc.renderManager.renderPosY
            val iz = espTarget.prevPosZ + dz * pt - mc.renderManager.renderPosZ
            GlStateManager.translate(
                ix + (dx + player.motionX + 0.005),
                iy + (dy + player.motionY - 0.002),
                iz + (dz + player.motionZ + 0.005)
            )
            val bb = espTarget.entityBoundingBox
            drawTargetBox(
                AxisAlignedBB(
                    bb.minX - 0.1 - espTarget.posX,
                    bb.minY - 0.1 - espTarget.posY,
                    bb.minZ - 0.1 - espTarget.posZ,
                    bb.maxX + 0.1 - espTarget.posX,
                    bb.maxY + 0.2 - espTarget.posY,
                    bb.maxZ + 0.1 - espTarget.posZ
                ),
                boxColor
            )
        } finally {
            GlStateManager.popMatrix()
        }
    }

    private fun drawTargetBox(bb: AxisAlignedBB, color: Color) {
        GlStateManager.enableBlend()
        GlStateManager.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glLineWidth(2f)
        GlStateManager.disableTexture2D()
        glDisable(GL_DEPTH_TEST)
        GlStateManager.depthMask(false)
        glEnable(GL_LINE_SMOOTH)
        RenderGlobal.drawOutlinedBoundingBox(bb, color.red, color.green, color.blue, color.alpha)
        glDisable(GL_LINE_SMOOTH)
        RenderUtils.glColor(color)
        RenderUtils.drawFilledBox(bb)
        GlStateManager.depthMask(true)
        glEnable(GL_DEPTH_TEST)
        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
    }

    private fun drawCircle3D(espTarget: EntityLivingBase) {
        prevCircleStep = circleStep
        val fps = Minecraft.getDebugFPS()
        circleStep += circleSpeed * (if (fps > 0) 1.0 / fps else 1.0)
        val pt = mc.timer.renderPartialTicks
        var eyeHeight = espTarget.getEyeHeight().toDouble()
        if (espTarget.isSneaking) {
            eyeHeight -= 0.2
        }
        val step = prevCircleStep + (circleStep - prevCircleStep) * pt
        val waveBottom = abs(1.0 + sin(step - 0.5)) / 2.0
        val waveTop = abs(1.0 + sin(step)) / 2.0
        val ix = espTarget.lastTickPosX + (espTarget.posX - espTarget.lastTickPosX) * pt - mc.renderManager.renderPosX
        val iz = espTarget.lastTickPosZ + (espTarget.posZ - espTarget.lastTickPosZ) * pt - mc.renderManager.renderPosZ
        val iyBottom = espTarget.lastTickPosY + (espTarget.posY - espTarget.lastTickPosY) * pt - mc.renderManager.renderPosY + waveBottom * eyeHeight
        val iyTop = espTarget.lastTickPosY + (espTarget.posY - espTarget.lastTickPosY) * pt - mc.renderManager.renderPosY + waveTop * eyeHeight
        val radius = espTarget.width * 0.8
        val c = color
        GlStateManager.pushMatrix()
        try {
            glDisable(GL_CULL_FACE)
            glDisable(GL_TEXTURE_2D)
            glEnable(GL_BLEND)
            glDisable(GL_DEPTH_TEST)
            glDisable(GL_ALPHA_TEST)
            glShadeModel(GL_SMOOTH)
            glBegin(GL_QUAD_STRIP)
            for (angle in 0..360) {
                val rad = Math.toRadians(angle.toDouble())
                val cosX = cos(rad) * radius
                val sinZ = sin(rad) * radius
                glColor4f(c.red / 255f, c.green / 255f, c.blue / 255f, 0.6f)
                glVertex3d(ix + cosX, iyTop, iz + sinZ)
                glColor4f(c.red / 255f, c.green / 255f, c.blue / 255f, 0.01f)
                glVertex3d(ix + cosX, iyBottom, iz + sinZ)
            }
            glEnd()
            glEnable(GL_LINE_SMOOTH)
            glBegin(GL_LINE_LOOP)
            for (angle in 0..360) {
                val rad = Math.toRadians(angle.toDouble())
                glColor4f(c.red / 255f, c.green / 255f, c.blue / 255f, 0.8f)
                glVertex3d(ix + cos(rad) * radius, iyTop, iz + sin(rad) * radius)
            }
            glEnd()
            glDisable(GL_LINE_SMOOTH)
        } finally {
            glEnable(GL_TEXTURE_2D)
            glEnable(GL_ALPHA_TEST)
            glEnable(GL_DEPTH_TEST)
            glShadeModel(GL_SMOOTH)
            glDisable(GL_BLEND)
            glEnable(GL_CULL_FACE)
            GlStateManager.popMatrix()
            GlStateManager.resetColor()
        }
    }

    /** Moonlight Image 模式的 2D 贴图绘制（Render3D 采集投影 → Render2D 绘制）。 */
    private fun drawTargetESP2D(x: Float, y: Float, alphaScale: Float) {
        val time = System.currentTimeMillis() + 3 * 400L
        val pulse = ((sin(time / 150.0) + 1.0) / 2.0 * 30.0).coerceIn(0.0, 30.0)
        val sizeScale = ((sin(time / 500.0) + 1.0) / 2.0).coerceIn(0.8, 1.0)
        var angle = ((sin(time / 1000.0) + 1.0) / 2.0 * 360.0).coerceIn(0.0, 360.0)
        angle += (if (choiceTargetESPImage == "QuadStapple") 45.0 else 0.0) - (pulse - 15.0)
        angle = 45.0 - (pulse - 15.0) + angle
        val opacity = alphaAnim.output.toFloat()
        val base = Color(color.rgb)
        val c0 = applyOpacity(base, opacity).rgb
        val c90 = applyOpacity(ColorUtils.shiftHue(base, 90), opacity).rgb
        val c180 = applyOpacity(ColorUtils.shiftHue(base, 180), opacity).rgb
        val c270 = applyOpacity(ColorUtils.shiftHue(base, 270), opacity).rgb
        val size = 128.0f * alphaScale * sizeScale.toFloat()
        val x1 = x - size / 2f
        val x2 = x1 + size
        val y1 = y - size / 2f
        val y2 = y1 + size
        val texture = when (choiceTargetESPImage) {
            "Rectangle" -> rectangleTexture
            "QuadStapple" -> quadStappleTexture
            "TriangleStapple" -> triangleStappleTexture
            else -> triangleStippleTexture
        }
        GlStateManager.pushMatrix()
        RenderUtils.customRotatedObject2D(x1, y1, size, size, angle)
        glDisable(GL_ALPHA_TEST)
        GlStateManager.depthMask(false)
        GlStateManager.enableBlend()
        glShadeModel(GL_SMOOTH)
        GlStateManager.tryBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE, GL_ONE, GL_ZERO)
        drawImageQuad(texture, x1, y1, x2, y2, c0, c90, c180, c270)
        GlStateManager.tryBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO)
        GlStateManager.resetColor()
        glShadeModel(GL_SMOOTH)
        GlStateManager.depthMask(true)
        glEnable(GL_ALPHA_TEST)
        GlStateManager.popMatrix()
    }

    /** Moonlight targetESPSPos 的等价实现：Render3D 阶段用 ESPUtil.projectOn2D 采集目标包围盒 2D 投影。 */
    private fun targetESP2DPos(entity: EntityLivingBase): Pair<Float, Float>? {
        val pt = mc.timer.renderPartialTicks
        val x = entity.prevPosX + (entity.posX - entity.prevPosX) * pt
        val y = entity.prevPosY + (entity.posY - entity.prevPosY) * pt
        val z = entity.prevPosZ + (entity.posZ - entity.prevPosZ) * pt
        val halfHeight = entity.height / (if (entity is EntityAgeable && entity.isChild) 1.75f else 1.0f) / 2.0f
        val bb = AxisAlignedBB(x, y, z, x, y + halfHeight, z)
        val sr = ScaledResolution(mc)
        val corners = listOf(
            doubleArrayOf(bb.minX, bb.minY, bb.minZ),
            doubleArrayOf(bb.minX, bb.maxY, bb.minZ),
            doubleArrayOf(bb.minX, bb.maxY, bb.maxZ),
            doubleArrayOf(bb.minX, bb.minY, bb.maxZ),
            doubleArrayOf(bb.maxX, bb.minY, bb.minZ),
            doubleArrayOf(bb.maxX, bb.maxY, bb.minZ),
            doubleArrayOf(bb.maxX, bb.maxY, bb.maxZ),
            doubleArrayOf(bb.maxX, bb.minY, bb.maxZ)
        )
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var valid = false
        for (corner in corners) {
            val projected = ESPUtil.projectOn2D(
                (corner[0] - mc.renderManager.viewerPosX).toFloat(),
                (corner[1] - mc.renderManager.viewerPosY).toFloat(),
                (corner[2] - mc.renderManager.viewerPosZ).toFloat(),
                sr.scaleFactor
            ) ?: continue
            if (projected.z < 0f || projected.z >= 1f) continue
            valid = true
            minX = minOf(minX, projected.x)
            minY = minOf(minY, projected.y)
        }
        return if (valid) minX to minY else null
    }

    private fun drawImageWorld(texture: ResourceLocation, x: Float, y: Float, width: Float, height: Float, colorRgb: Int) {
        mc.textureManager.bindTexture(texture)
        glShadeModel(GL_SMOOTH)
        glDepthMask(false)
        drawWithTessellatorWorldRenderer {
            begin(7, DefaultVertexFormats.POSITION_TEX_COLOR)
            pos(x.toDouble(), (y + height).toDouble(), 0.0).tex(0.0, 1.0).color(colorRgb).endVertex()
            pos((x + width).toDouble(), (y + height).toDouble(), 0.0).tex(1.0, 1.0).color(colorRgb).endVertex()
            pos((x + width).toDouble(), y.toDouble(), 0.0).tex(1.0, 0.0).color(colorRgb).endVertex()
            pos(x.toDouble(), y.toDouble(), 0.0).tex(0.0, 0.0).color(colorRgb).endVertex()
        }
        glDepthMask(true)
    }

    private fun drawImageBlendedWorld(
        x: Double, y: Double, z: Double, width: Double, height: Double,
        c1: Int, c2: Int, c3: Int, c4: Int
    ) {
        GlStateManager.enableBlend()
        GlStateManager.blendFunc(GL_SRC_ALPHA, GL_ONE)
        glShadeModel(GL_SMOOTH)
        glAlphaFunc(GL_GREATER, 0f)
        GlStateManager.color(1f, 1f, 1f, 1f)
        drawWithTessellatorWorldRenderer {
            begin(7, DefaultVertexFormats.POSITION_TEX_COLOR)
            pos(x, y + height, z).tex(0.0, 0.99).color(c1).endVertex()
            pos(x + width, y + height, z).tex(1.0, 0.99).color(c2).endVertex()
            pos(x + width, y, z).tex(1.0, 0.0).color(c3).endVertex()
            pos(x, y, z).tex(0.0, 0.0).color(c4).endVertex()
        }
        GlStateManager.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun drawImageQuad(
        texture: ResourceLocation, x1: Float, y1: Float, x2: Float, y2: Float,
        c1: Int, c2: Int, c3: Int, c4: Int
    ) {
        mc.textureManager.bindTexture(texture)
        drawWithTessellatorWorldRenderer {
            begin(7, DefaultVertexFormats.POSITION_TEX_COLOR)
            pos(x1.toDouble(), y2.toDouble(), 0.0).tex(0.0, 1.0).color(c1).endVertex()
            pos(x2.toDouble(), y2.toDouble(), 0.0).tex(1.0, 1.0).color(c2).endVertex()
            pos(x2.toDouble(), y1.toDouble(), 0.0).tex(1.0, 0.0).color(c3).endVertex()
            pos(x1.toDouble(), y1.toDouble(), 0.0).tex(0.0, 0.0).color(c4).endVertex()
        }
    }

    private fun applyOpacity(color: Color, opacity: Float): Color {
        val clamped = opacity.coerceIn(0f, 1f)
        return Color(color.red, color.green, color.blue, (color.alpha * clamped).toInt())
    }

    /**
     * Moonlight DecelerateAnimation 的等价实现：时间差驱动 + 减速曲线 1 - (x - 1)²，
     * 方向切换时按 Moonlight Animation.setDirection 保留镜像进度。
     */
    private class DecelerateAnimation(private val duration: Int, private val endPoint: Double = 1.0) {
        enum class Direction { FORWARDS, BACKWARDS }

        private val timer = TimerUtils()
        private var direction = Direction.FORWARDS

        val isDone: Boolean
            get() = timer.hasTimeElapsed(duration.toLong())

        fun setDirection(direction: Direction) {
            if (this.direction != direction) {
                this.direction = direction
                timer.setTime(System.currentTimeMillis() - (duration - timer.getTime().coerceAtMost(duration.toLong())))
            }
        }

        val output: Double
            get() = when {
                direction == Direction.FORWARDS ->
                    if (isDone) endPoint else equation(timer.getTime() / duration.toDouble()) * endPoint
                isDone -> 0.0
                else -> (1.0 - equation(timer.getTime() / duration.toDouble())) * endPoint
            }

        private fun equation(x: Double): Double = 1.0 - (x - 1.0) * (x - 1.0)
    }
}
