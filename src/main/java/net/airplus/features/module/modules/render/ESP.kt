/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.render

import net.airplus.event.Render2DEvent
import net.airplus.event.Render3DEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.modules.misc.AntiBot.isBot
import net.airplus.utils.attack.EntityUtils.colorFromDisplayName
import net.airplus.utils.attack.EntityUtils.isLookingOnEntities
import net.airplus.utils.attack.EntityUtils.isSelected
import net.airplus.utils.client.ClientUtils.LOGGER
import net.airplus.utils.client.EntityLookup
import net.airplus.utils.extensions.*
import net.airplus.utils.render.ColorSettingsInteger
import net.airplus.utils.render.RenderUtils.draw2D
import net.airplus.utils.render.RenderUtils.drawEntityBox
import net.airplus.utils.render.RenderUtils.glStateManagerColor
import net.airplus.utils.render.WorldToScreen
import net.airplus.utils.render.shader.shaders.GlowShader
import net.airplus.utils.rotation.RotationUtils.isEntityHeightVisible
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.GlStateManager.enableTexture2D
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import org.lwjgl.opengl.GL11.*
import org.lwjgl.util.vector.Vector3f
import java.awt.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

object ESP : Module("ESP", Category.RENDER) {

    val mode by choices(
        "Mode",
        arrayOf("Box", "OtherBox", "WireFrame", "2D", "Real2D", "Gaussian", "Outline", "Glow", "Onyx"), "Box"
    )

    val outlineWidth by float("Outline-Width", 3f, 0.5f..5f) { mode == "Outline" }

    val wireframeWidth by float("WireFrame-Width", 2f, 0.5f..5f) { mode == "WireFrame" }

    private val glowRenderScale by float("Glow-Renderscale", 1f, 0.5f..2f) { mode == "Glow" }
    private val glowRadius by int("Glow-Radius", 4, 1..5) { mode == "Glow" }
    private val glowFade by int("Glow-Fade", 10, 0..30) { mode == "Glow" }
    private val glowTargetAlpha by float("Glow-Target-Alpha", 0f, 0f..1f) { mode == "Glow" }

    // Onyx Glow（辉光描边 / 纯色填充）
    private val onyxMode by choices("Onyx-Mode", arrayOf("Outline", "Fill"), "Outline") { mode == "Onyx" }
    private val onyxRadius by int("Onyx-Radius", 14, 2..24, "px") { mode == "Onyx" }
    private val onyxIntensity by float("Onyx-Intensity", 40f, 10f..100f, "%") { mode == "Onyx" }
    private val onyxFillOpacity by float("Onyx-Fill-Opacity", 35f, 0f..100f, "%") { mode == "Onyx" && onyxMode == "Fill" }

    /**
     * Intensity → GlowShader 的 fade：glow.frag 以 Σ((radius-dist)/radius)/fade 累加辉光，
     * 累加量近似随 radius² 增长，因此 fade 随 radius² 缩放、随 Intensity 反向缩放，
     * 使 Intensity 表现为外发光透明度（越大越亮）。
     */
    private val onyxFade: Int
        get() = max(1, (onyxRadius * onyxRadius * (110f - onyxIntensity) / 150f).roundToInt())

    private val espColor = ColorSettingsInteger(this, "ESPColor").with(255, 255, 255)

    private val maxRenderDistance by int("MaxRenderDistance", 50, 1..200).onChanged { value ->
        maxRenderDistanceSq = value.toDouble().pow(2)
    }

    private val onLook by boolean("OnLook", false)
    private val maxAngleDifference by float("MaxAngleDifference", 90f, 5.0f..90f) { onLook }

    private val thruBlocks by boolean("ThruBlocks", true)

    private var maxRenderDistanceSq = 0.0
        set(value) {
            field = if (value <= 0.0) maxRenderDistance.toDouble().pow(2.0) else value
        }

    private val colorTeam by boolean("TeamColor", false)
    private val bot by boolean("Bots", true)

    var renderNameTags = true

    private val entities by EntityLookup<EntityLivingBase>().filter { shouldRender(it) }

    val onRender3D = handler<Render3DEvent> {
        if (entities.isEmpty())
            return@handler

        val mvMatrix = WorldToScreen.getMatrix(GL_MODELVIEW_MATRIX)
        val projectionMatrix = WorldToScreen.getMatrix(GL_PROJECTION_MATRIX)
        val real2d = mode == "Real2D"

        if (real2d) {
            glPushAttrib(GL_ENABLE_BIT)
            glEnable(GL_BLEND)
            glDisable(GL_TEXTURE_2D)
            glDisable(GL_DEPTH_TEST)
            glMatrixMode(GL_PROJECTION)
            glPushMatrix()
            glLoadIdentity()
            glOrtho(0.0, mc.displayWidth.toDouble(), mc.displayHeight.toDouble(), 0.0, -1.0, 1.0)
            glMatrixMode(GL_MODELVIEW)
            glPushMatrix()
            glLoadIdentity()
            glDisable(GL_DEPTH_TEST)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            enableTexture2D()
            glDepthMask(true)
            glLineWidth(1f)
        }

        for (entity in entities) {
            val color = getColor(entity)

            when (mode) {
                "Box", "OtherBox" -> drawEntityBox(entity, color, mode != "OtherBox")

                "2D" -> {
                    // Interpolated position computed inline (avoids Vec3 allocations per entity per frame)
                    val tickDelta = mc.timer.renderPartialTicks
                    val ix = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * tickDelta - mc.renderManager.renderPosX
                    val iy = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * tickDelta - mc.renderManager.renderPosY
                    val iz = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * tickDelta - mc.renderManager.renderPosZ
                    draw2D(entity, ix, iy, iz, color.rgb, Color.BLACK.rgb)
                }

                "Real2D" -> {
                    val tickDelta = mc.timer.renderPartialTicks
                    val ix = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * tickDelta - mc.renderManager.renderPosX
                    val iy = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * tickDelta - mc.renderManager.renderPosY
                    val iz = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * tickDelta - mc.renderManager.renderPosZ
                    val bb = entity.hitBox.offset(-entity.currPos.xCoord + ix, -entity.currPos.yCoord + iy, -entity.currPos.zCoord + iz)
                    val boxVertices = arrayOf(
                        doubleArrayOf(bb.minX, bb.minY, bb.minZ),
                        doubleArrayOf(bb.minX, bb.maxY, bb.minZ),
                        doubleArrayOf(bb.maxX, bb.maxY, bb.minZ),
                        doubleArrayOf(bb.maxX, bb.minY, bb.minZ),
                        doubleArrayOf(bb.minX, bb.minY, bb.maxZ),
                        doubleArrayOf(bb.minX, bb.maxY, bb.maxZ),
                        doubleArrayOf(bb.maxX, bb.maxY, bb.maxZ),
                        doubleArrayOf(bb.maxX, bb.minY, bb.maxZ)
                    )
                    var minX = Float.MAX_VALUE
                    var minY = Float.MAX_VALUE
                    var maxX = -1f
                    var maxY = -1f
                    for (boxVertex in boxVertices) {
                        val screenPos = WorldToScreen.worldToScreen(
                            Vector3f(
                                boxVertex[0].toFloat(),
                                boxVertex[1].toFloat(),
                                boxVertex[2].toFloat()
                            ), mvMatrix, projectionMatrix, mc.displayWidth, mc.displayHeight
                        )
                            ?: continue
                        minX = min(screenPos.x, minX)
                        minY = min(screenPos.y, minY)
                        maxX = max(screenPos.x, maxX)
                        maxY = max(screenPos.y, maxY)
                    }
                    if (minX > 0 || minY > 0 || maxX <= mc.displayWidth || maxY <= mc.displayWidth) {
                        glColor4f(color.red / 255f, color.green / 255f, color.blue / 255f, 1f)
                        glBegin(GL_LINE_LOOP)
                        glVertex2f(minX, minY)
                        glVertex2f(minX, maxY)
                        glVertex2f(maxX, maxY)
                        glVertex2f(maxX, minY)
                        glEnd()
                    }
                }
            }
        }

        if (real2d) {
            glColor4f(1f, 1f, 1f, 1f)
            glEnable(GL_DEPTH_TEST)
            glMatrixMode(GL_PROJECTION)
            glPopMatrix()
            glMatrixMode(GL_MODELVIEW)
            glPopMatrix()
            glPopAttrib()
        }
    }

    val onRender2D = handler<Render2DEvent> { event ->
        val onyx = mode == "Onyx"
        if (mc.theWorld == null || (mode != "Glow" && !onyx) || entities.isEmpty())
            return@handler

        renderNameTags = false

        try {
            entities.groupBy(::getColor).forEach { (color, entities) ->
                val fill = onyx && onyxMode == "Fill"

                GlowShader.startDraw(event.partialTicks, if (onyx) 1f else glowRenderScale)

                if (fill) {
                    // Chams 式纯色填充：禁用纹理后实体以 ESP 颜色平涂进离屏帧缓冲，
                    // glow.frag 的内部区域（centerCol.rgb）随之变为纯色，不透明度由 targetAlpha 控制
                    GlStateManager.disableTexture2D()
                    glStateManagerColor(color)
                }

                for (entity in entities) {
                    if (fill) {
                        // renderEntitySimple 每个实体渲染前会重置颜色，填充时改走不重置颜色的 renderEntityWithPosYaw
                        val tickDelta = event.partialTicks
                        val brightness = entity.getBrightnessForRender(tickDelta)
                        OpenGlHelper.setLightmapTextureCoords(
                            OpenGlHelper.lightmapTexUnit,
                            (brightness % 65536).toFloat(),
                            (brightness / 65536).toFloat()
                        )
                        mc.renderManager.renderEntityWithPosYaw(
                            entity,
                            entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * tickDelta - mc.renderManager.renderPosX,
                            entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * tickDelta - mc.renderManager.renderPosY,
                            entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * tickDelta - mc.renderManager.renderPosZ,
                            entity.prevRotationYaw + (entity.rotationYaw - entity.prevRotationYaw) * tickDelta,
                            tickDelta
                        )
                    } else {
                        mc.renderManager.renderEntitySimple(entity, event.partialTicks)
                    }
                }

                if (fill) {
                    enableTexture2D()
                    GlStateManager.color(1f, 1f, 1f, 1f)
                }

                when {
                    fill -> GlowShader.stopDraw(color, onyxRadius, onyxFade, onyxFillOpacity / 100f)
                    onyx -> GlowShader.stopDraw(color, onyxRadius, onyxFade, 0f)
                    else -> GlowShader.stopDraw(color, glowRadius, glowFade, glowTargetAlpha)
                }
            }
        } catch (ex: Exception) {
            LOGGER.error("An error occurred while rendering all entities for shader esp", ex)
        }

        renderNameTags = true
    }

    override val tag
        get() = mode

    fun getColor(entity: Entity? = null): Color {
        if (entity != null && entity is EntityLivingBase) {
            if (entity.hurtTime > 0)
                return Color.RED

            if (entity is EntityPlayer && entity.isClientFriend())
                return Color.BLUE

            if (colorTeam) {
                entity.colorFromDisplayName()?.let {
                    return it
                }
            }
        }

        return espColor.color()
    }

    fun shouldRender(entity: EntityLivingBase): Boolean {
        val player = mc.thePlayer ?: return false

        return (player.getDistanceSqToEntity(entity) <= maxRenderDistanceSq
                && (thruBlocks || isEntityHeightVisible(entity))
                && (!onLook || isLookingOnEntities(entity, maxAngleDifference.toDouble()))
                && isSelected(entity, false)
                && (bot || !isBot(entity)))
    }

}