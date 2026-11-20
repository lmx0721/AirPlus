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
import net.airplus.utils.extensions.*
import net.airplus.utils.render.ColorSettingsInteger
import net.airplus.utils.render.ColorUtils.withAlpha
import net.airplus.utils.render.RenderUtils.glColor
import org.lwjgl.opengl.GL11.*
import java.awt.Color

object Breadcrumbs : Module("Breadcrumbs", Category.RENDER) {
    val colors = ColorSettingsInteger(this, "Color").with(132, 102, 255)
    private val lineHeight by float("LineHeight", 0.25F, 0.25F..2F)
    private val temporary by boolean("Temporary", true)
    private val fade by boolean("Fade", true) { temporary }
    private val lifeTime by float("LifeTime", 1F, 0F..10F) { temporary }

    private val positions = ArrayDeque<PositionData>()

    val onRender3D = handler<Render3DEvent> {
        val player = mc.thePlayer ?: return@handler

        if (positions.isEmpty() && !player.isMoving) {
            return@handler
        }

        val now = System.currentTimeMillis()
        val fadeSeconds = lifeTime * 1000L

        glPushAttrib(GL_ENABLE_BIT or GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT or GL_CURRENT_BIT or GL_LINE_BIT or GL_LIGHTING_BIT or GL_TEXTURE_BIT)
        glPushMatrix()

        glDisable(GL_TEXTURE_2D)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glEnable(GL_LINE_SMOOTH)
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_CULL_FACE)
        glEnable(GL_ALPHA_TEST)
        glAlphaFunc(GL_GREATER, 0.0f)

        player.interpolatedPosition(player.prevPos).let { pos ->
            val data = PositionData(pos.toDoubleArray(), now)

            val lastData = positions.lastOrNull()?.array

            if (lastData == null || !lastData.contentEquals(data.array)) positions += data
        }

        mc.entityRenderer.disableLightmap()

        // 过期段全部位于队首（按时间淘汰），线性弹出替代原先 removeEach + indexOf 的 O(n²)
        if (temporary) {
            while (positions.isNotEmpty()) {
                val head = positions.first()
                if (now - head.time <= fadeSeconds) break
                positions.removeFirst()
            }
        }

        val baseColor = colors.color()
        if (!fade) glColor(baseColor.withAlpha(150))

        glBegin(GL_QUADS)

        val renderPosX = mc.renderManager.viewerPosX
        val renderPosY = mc.renderManager.viewerPosY
        val renderPosZ = mc.renderManager.viewerPosZ

        var prev: DoubleArray? = null
        for (data in positions) {
            if (fade) {
                val timestamp = now - data.time
                val transparency = (0f..150f).lerpWith(1 - (timestamp / fadeSeconds).coerceAtMost(1.0F))
                glColor(baseColor.withAlpha(transparency.toInt()))
            }

            val startPos = prev
            if (startPos != null) {
                val endPos = data.array

                glVertex3d(startPos[0] - renderPosX, startPos[1] - renderPosY, startPos[2] - renderPosZ)
                glVertex3d(startPos[0] - renderPosX, startPos[1] - renderPosY + lineHeight, startPos[2] - renderPosZ)
                glVertex3d(endPos[0] - renderPosX, endPos[1] - renderPosY + lineHeight, endPos[2] - renderPosZ)
                glVertex3d(endPos[0] - renderPosX, endPos[1] - renderPosY, endPos[2] - renderPosZ)
            }
            prev = data.array
        }

        glEnd()

        glColor(Color.WHITE)
        glEnable(GL_CULL_FACE)
        glEnable(GL_DEPTH_TEST)
        glDisable(GL_ALPHA_TEST)
        glDisable(GL_LINE_SMOOTH)
        glDisable(GL_BLEND)
        glEnable(GL_TEXTURE_2D)
        glPopMatrix()
        glPopAttrib()
    }

    val onWorld = handler<WorldEvent> {
        positions.clear()
    }

    override fun onDisable() {
        positions.clear()
    }
}

private class PositionData(val array: DoubleArray, val time: Long)