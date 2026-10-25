/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.render

import net.airplus.event.Render3DEvent
import net.airplus.event.WorldEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.render.ColorSettingsInteger
import net.airplus.utils.render.RenderUtils.glColor
import org.lwjgl.opengl.GL11.*
import java.awt.Color

/**
 * Trail：玩家身后拖出的面向相机的带状拖尾。
 *
 * 采样点按移动距离记录（非固定 tick），因此低帧率下也不会断层；
 * 每个线段根据相邻点方向与相机位置计算侧向量，形成 billboard ribbon。
 */
object Trail : Module("Trail", Category.RENDER) {

    val colors = ColorSettingsInteger(this, "Color").with(120, 180, 255)

    private val width by float("Width", 0.12F, 0.02F..0.6F)
    private val lifeTime by float("LifeTime", 1.2F, 0.2F..10F, "s")
    private val fade by boolean("Fade", true)
    private val minDistance by float("MinDistance", 0.15F, 0.05F..1F)
    private val maxPoints by int("MaxPoints", 240, 20..1000)
    private val thruBlocks by boolean("ThruBlocks", true)

    private val points = ArrayDeque<TrailPoint>()

    val onRender3D = handler<Render3DEvent> { event ->
        val player = mc.thePlayer ?: return@handler

        val now = System.currentTimeMillis()
        val maxAge = (lifeTime * 1000F).toLong()

        val partialTicks = event.partialTicks
        val x = player.prevPosX + (player.posX - player.prevPosX) * partialTicks
        val y = player.prevPosY + (player.posY - player.prevPosY) * partialTicks + 0.1
        val z = player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks

        val last = points.lastOrNull()
        if (last == null) {
            points.addLast(TrailPoint(x, y, z, now))
        } else {
            val dx = x - last.x
            val dy = y - last.y
            val dz = z - last.z
            if (dx * dx + dy * dy + dz * dz >= (minDistance * minDistance).toDouble()) {
                points.addLast(TrailPoint(x, y, z, now))
                while (points.size > maxPoints) points.removeFirst()
            }
        }

        while (points.isNotEmpty() && now - points.first().time > maxAge) points.removeFirst()
        if (points.size < 2) return@handler

        val camX = mc.renderManager.viewerPosX
        val camY = mc.renderManager.viewerPosY
        val camZ = mc.renderManager.viewerPosZ

        val color = colors.color()
        val baseAlpha = color.alpha / 255F
        val halfWidth = width / 2.0

        glPushAttrib(GL_ALL_ATTRIB_BITS)
        glPushMatrix()

        glDisable(GL_TEXTURE_2D)
        glDisable(GL_CULL_FACE)
        glDisable(GL_ALPHA_TEST)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        if (thruBlocks) glDisable(GL_DEPTH_TEST) else glEnable(GL_DEPTH_TEST)

        mc.entityRenderer.disableLightmap()

        glBegin(GL_QUADS)

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]

            // 相机相对坐标
            val x1 = p1.x - camX
            val y1 = p1.y - camY
            val z1 = p1.z - camZ
            val x2 = p2.x - camX
            val y2 = p2.y - camY
            val z2 = p2.z - camZ

            // 线段方向
            val dirX = x2 - x1
            val dirY = y2 - y1
            val dirZ = z2 - z1

            // 线段中点到相机的方向（相机在原点）
            val toCamX = -(x1 + x2) / 2.0
            val toCamY = -(y1 + y2) / 2.0
            val toCamZ = -(z1 + z2) / 2.0

            // side = normalize(dir × toCam) * halfWidth
            var sx = dirY * toCamZ - dirZ * toCamY
            var sy = dirZ * toCamX - dirX * toCamZ
            var sz = dirX * toCamY - dirY * toCamX
            val len = Math.sqrt(sx * sx + sy * sy + sz * sz)

            if (len < 1.0E-6) {
                // 退化：线段正对相机，改用垂直向上作为侧向量
                sx = 0.0
                sy = halfWidth
                sz = 0.0
            } else {
                val scale = halfWidth / len
                sx *= scale
                sy *= scale
                sz *= scale
            }

            val alpha1 = vertexAlpha(p1, now, maxAge, baseAlpha)
            val alpha2 = vertexAlpha(p2, now, maxAge, baseAlpha)

            glColor4f(color.red / 255F, color.green / 255F, color.blue / 255F, alpha1)
            glVertex3d(x1 - sx, y1 - sy, z1 - sz)
            glVertex3d(x1 + sx, y1 + sy, z1 + sz)
            glColor4f(color.red / 255F, color.green / 255F, color.blue / 255F, alpha2)
            glVertex3d(x2 + sx, y2 + sy, z2 + sz)
            glVertex3d(x2 - sx, y2 - sy, z2 - sz)
        }

        glEnd()

        glDepthMask(true)
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_CULL_FACE)
        glEnable(GL_TEXTURE_2D)
        glDisable(GL_BLEND)
        glColor(Color.WHITE)
        glPopMatrix()
        glPopAttrib()
    }

    private fun vertexAlpha(point: TrailPoint, now: Long, maxAge: Long, baseAlpha: Float): Float {
        if (!fade) return baseAlpha
        val ageFraction = ((now - point.time).toFloat() / maxAge).coerceIn(0F, 1F)
        return baseAlpha * (1F - ageFraction)
    }

    val onWorld = handler<WorldEvent> {
        points.clear()
    }

    override fun onDisable() {
        points.clear()
    }
}

private class TrailPoint(val x: Double, val y: Double, val z: Double, val time: Long)
