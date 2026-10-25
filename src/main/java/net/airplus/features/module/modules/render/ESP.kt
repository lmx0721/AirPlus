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
import net.minecraft.client.model.ModelBiped
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.GlStateManager.enableTexture2D
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import org.lwjgl.opengl.GL11.*
import org.lwjgl.util.vector.Vector3f
import java.awt.Color
import java.util.WeakHashMap
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

    val skeleton by boolean("Skeleton", false)
    private val skeletonWidth by float("Skeleton-Width", 1F, 0.5F..4F) { skeleton }
    private val skeletonThruBlocks by boolean("Skeleton-ThruBlocks", true) { skeleton }

    /** 骨骼旋转缓存：由 MixinModelBiped 在 setRotationAngles 末尾写入 */
    val skeletonRotations = WeakHashMap<EntityPlayer, Array<FloatArray>>()

    fun updateSkeleton(player: EntityPlayer, model: ModelBiped) {
        skeletonRotations[player] = arrayOf(
            floatArrayOf(model.bipedHead.rotateAngleX, model.bipedHead.rotateAngleY, model.bipedHead.rotateAngleZ),
            floatArrayOf(model.bipedRightArm.rotateAngleX, model.bipedRightArm.rotateAngleY, model.bipedRightArm.rotateAngleZ),
            floatArrayOf(model.bipedLeftArm.rotateAngleX, model.bipedLeftArm.rotateAngleY, model.bipedLeftArm.rotateAngleZ),
            floatArrayOf(model.bipedRightLeg.rotateAngleX, model.bipedRightLeg.rotateAngleY, model.bipedRightLeg.rotateAngleZ),
            floatArrayOf(model.bipedLeftLeg.rotateAngleX, model.bipedLeftLeg.rotateAngleY, model.bipedLeftLeg.rotateAngleZ)
        )
    }

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

        drawSkeletonPass(it.partialTicks)

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

    // 优先级低于 0（先于 HUD 等默认 0 的 Render2D 处理器执行）：
    // glow 全屏合成 pass 先画，HUD 之后画在其上，避免辉光盖在 HUD 界面上
    val onRender2D = handler<Render2DEvent>(priority = (Byte.MIN_VALUE + 1).toByte()) { event ->
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

    /**
     * 骨骼透视：用缓存的 ModelBiped 旋转角绘制火柴人骨架
     */
    private fun drawSkeletonPass(partialTicks: Float) {
        if (!skeleton) return

        val world = mc.theWorld ?: return
        val cacheIterator = skeletonRotations.keys.iterator()
        while (cacheIterator.hasNext()) {
            val player = cacheIterator.next()
            if (world.playerEntities.none { it === player }) cacheIterator.remove()
        }

        val renderPosX = mc.renderManager.renderPosX
        val renderPosY = mc.renderManager.renderPosY
        val renderPosZ = mc.renderManager.renderPosZ

        glPushAttrib(GL_ENABLE_BIT or GL_LINE_BIT)
        glPushMatrix()
        glEnable(GL_BLEND)
        glDisable(GL_TEXTURE_2D)
        glDisable(GL_LIGHTING)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        if (skeletonThruBlocks) glDisable(GL_DEPTH_TEST) else glEnable(GL_DEPTH_TEST)
        glLineWidth(skeletonWidth)
        glEnable(GL_LINE_SMOOTH)
        glHint(GL_LINE_SMOOTH_HINT, GL_NICEST)

        for (entity in entities) {
            if (entity !is EntityPlayer || entity === mc.thePlayer || entity.isInvisible) continue

            val rotations = skeletonRotations[entity] ?: continue
            val color = getColor(entity)
            GlStateManager.color(color.red / 255f, color.green / 255f, color.blue / 255f, 1f)

            val x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks - renderPosX
            val y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks - renderPosY
            val z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks - renderPosZ

            val sneaking = entity.isSneaking
            val bodyYaw = entity.prevRenderYawOffset +
                    (entity.renderYawOffset - entity.prevRenderYawOffset) * partialTicks
            val headYaw = entity.prevRotationYawHead +
                    (entity.rotationYawHead - entity.prevRotationYawHead) * partialTicks
            val legHeight = if (sneaking) 0.6f else 0.75f

            glPushMatrix()
            glTranslated(x, y, z)
            glRotatef(-bodyYaw, 0f, 1f, 0f)
            glTranslated(0.0, 0.0, if (sneaking) -0.235 else 0.0)

            // 双腿
            drawSkeletonBone(rotations[3], -0.125F, legHeight, -legHeight, 1f)
            drawSkeletonBone(rotations[4], 0.125F, legHeight, -legHeight, 1f)

            glTranslated(0.0, 0.0, if (sneaking) 0.25 else 0.0)

            glPushMatrix()
            if (sneaking) glTranslated(0.0, -0.05, -0.01725)

            // 双臂
            drawSkeletonBone(rotations[1], -0.375F, legHeight + 0.55F, -0.5F, -1f)
            drawSkeletonBone(rotations[2], 0.375F, legHeight + 0.55F, -0.5F, -1f)

            // 头（相对身体朝向）
            glRotatef(bodyYaw - headYaw, 0f, 1f, 0f)
            glPushMatrix()
            glTranslated(0.0, (legHeight + 0.55F).toDouble(), 0.0)
            if (rotations[0][0] != 0f) glRotatef(rotations[0][0] * 57.295776f, 1f, 0f, 0f)
            glBegin(GL_LINES)
            glVertex3d(0.0, 0.0, 0.0)
            glVertex3d(0.0, 0.3, 0.0)
            glEnd()
            glPopMatrix()

            glPopMatrix()

            // 躯干（潜行时前倾）
            if (sneaking) {
                glRotatef(25f, 1f, 0f, 0f)
                glTranslated(0.0, -0.16175, -0.48025)
            }

            // 髋线 / 脊柱 / 肩线
            glPushMatrix()
            glTranslated(0.0, legHeight.toDouble(), 0.0)
            glBegin(GL_LINES)
            glVertex3d(-0.125, 0.0, 0.0)
            glVertex3d(0.125, 0.0, 0.0)
            glVertex3d(0.0, 0.0, 0.0)
            glVertex3d(0.0, 0.55, 0.0)
            glEnd()
            glTranslated(0.0, 0.55, 0.0)
            glBegin(GL_LINES)
            glVertex3d(-0.375, 0.0, 0.0)
            glVertex3d(0.375, 0.0, 0.0)
            glEnd()
            glPopMatrix()

            glPopMatrix()
        }

        glDisable(GL_LINE_SMOOTH)
        glDepthMask(true)
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_TEXTURE_2D)
        glDisable(GL_BLEND)
        glPopMatrix()
        glPopAttrib()
        GlStateManager.color(1f, 1f, 1f, 1f)
    }

    private fun drawSkeletonBone(rotation: FloatArray, x: Float, y: Float, length: Float, zAxisSign: Float) {
        glPushMatrix()
        glTranslated(x.toDouble(), y.toDouble(), 0.0)
        if (rotation[0] != 0f) glRotatef(rotation[0] * 57.295776f, 1f, 0f, 0f)
        if (rotation[1] != 0f) glRotatef(rotation[1] * 57.295776f, 0f, 1f, 0f)
        if (rotation[2] != 0f) glRotatef(rotation[2] * zAxisSign * 57.295776f, 0f, 0f, 1f)
        glBegin(GL_LINES)
        glVertex3d(0.0, 0.0, 0.0)
        glVertex3d(0.0, length.toDouble(), 0.0)
        glEnd()
        glPopMatrix()
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