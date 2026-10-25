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
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL15
import java.awt.Color
import kotlin.math.*

/**
 * Particles：玩家周围漂浮的环境粒子。
 *
 * 全部使用自绘几何（不依赖任何 Minecraft 贴图资源）：
 * - Dot：三角形拼出的圆点
 * - Snow：三根交叉细长四边形组成的六角雪花
 * - Flower：交替半径的十边形近似花瓣
 *
 * 性能：
 * - 顶点数组批量绘制：CPU 侧一次性填入复用的直接缓冲，整帧仅一次 glDrawArrays
 *   （替代 immediate mode 的数千次 glVertex JNI 调用）
 * - 背面剔除：位于相机平面后方的粒子直接跳过几何计算
 * - 定向 GL 状态捕获/恢复（替代昂贵的 glPushAttrib(GL_ALL_ATTRIB_BITS)），
 *   所有提前 return 均发生在任何状态修改之前，避免状态泄露
 * - 粒子数量硬上限，超出范围或寿命耗尽即回收复用
 */
object Particles : Module("Particles", Category.RENDER) {

    private val type by choices("Type", arrayOf("Dot", "Snow", "Flower"), "Dot")
    val colors = ColorSettingsInteger(this, "Color").with(Color(255, 255, 255, 190))

    /** 动画样式：Fall = 从天而降铺满视野，Rise = 自下而上，Float = 空中悬浮漂移 */
    private val animMode by choices("Animation", arrayOf("Fall", "Rise", "Float"), "Rise")

    private val size by float("Size", 0.12F, 0.02F..0.6F)
    private val amount by int("Amount", 60, 1..300)
    private val radius by float("Radius", 6F, 1F..24F)
    private val speed by float("Speed", 0.35F, 0F..3F)
    private val sway by float("Sway", 0.25F, 0F..1F)
    private val lifeTime by float("LifeTime", 6F, 1F..30F, "s")
    private val thruBlocks by boolean("ThruBlocks", false)

    private val random = java.util.Random()
    private val particles = ArrayList<Particle>()
    private var lastUpdate = 0L

    // 复用的直接缓冲：位置(xyz) + 颜色(rgba)，避免每帧分配
    // LWJGL 2 此映射仅提供 ByteBuffer 重载，用 FloatBuffer 视图写入原始字节
    private var posBuffer = BufferUtils.createByteBuffer(0)
    private var colBuffer = BufferUtils.createByteBuffer(0)
    private var posFloats = posBuffer.asFloatBuffer()
    private var colFloats = colBuffer.asFloatBuffer()

    private val onRender3D = handler<Render3DEvent> {
        val player = mc.thePlayer ?: return@handler
        if (mc.theWorld == null) return@handler

        val now = System.currentTimeMillis()
        val dt = if (lastUpdate == 0L) 0.016F else ((now - lastUpdate) / 1000F).coerceIn(0F, 0.1F)
        lastUpdate = now

        // 数量同步
        while (particles.size < amount) particles += Particle().also { reset(it, player) }
        while (particles.size > amount) particles.removeAt(particles.lastIndex)

        // 回收半径：大于最大生成范围（水平 1.4r + 垂直 1.5r ≈ 2.1r），留余量避免生成即回收
        val cullDist = radius * 2.5F
        val radiusSq = (cullDist * cullDist).toDouble()

        for (particle in particles) {
            particle.age += dt
            if (particle.age >= particle.life ||
                player.getDistanceSq(particle.x, particle.y, particle.z) > radiusSq
            ) {
                reset(particle, player)
                continue
            }

            particle.x += particle.vx * dt
            particle.y += particle.vy * dt
            particle.z += particle.vz * dt
            particle.rot += particle.spin * dt

            // 越过垂直边界即回收到对侧，形成持续不断的覆盖效果
            when (animMode) {
                "Fall" -> if (particle.y < player.posY - radius * 0.5) reset(particle, player)
                "Rise" -> if (particle.y > player.posY + radius * 1.5) reset(particle, player)
            }
        }

        renderParticles()
    }

    private fun reset(particle: Particle, player: net.minecraft.entity.player.EntityPlayer) {
        val angle = random.nextDouble() * Math.PI * 2.0
        // 水平铺开 1.4 倍半径，营造铺天盖地的范围感
        val distance = sqrt(random.nextDouble()) * radius * 1.4

        particle.x = player.posX + cos(angle) * distance
        particle.z = player.posZ + sin(angle) * distance

        when (animMode) {
            "Fall" -> {
                // 从头顶高空大范围生成，向下飘落（如雪幕）
                particle.y = player.posY + radius * (0.5 + random.nextDouble())
                particle.vx = (random.nextDouble() - 0.5) * speed * 0.2
                particle.vy = -(0.5 + random.nextDouble() * 0.7) * speed
                particle.vz = (random.nextDouble() - 0.5) * speed * 0.2
            }
            "Rise" -> {
                particle.y = player.posY - radius * 0.5 + random.nextDouble() * radius * 0.8
                particle.vx = (random.nextDouble() - 0.5) * speed * 0.3
                particle.vy = (random.nextDouble() * 0.6 + 0.2) * speed
                particle.vz = (random.nextDouble() - 0.5) * speed * 0.3
            }
            else -> {
                // Float：以玩家为中心的立体球状分布，极慢漂移
                particle.y = player.posY + (random.nextDouble() - 0.5) * radius * 1.2
                particle.vx = (random.nextDouble() - 0.5) * speed * 0.25
                particle.vy = (random.nextDouble() - 0.5) * speed * 0.12
                particle.vz = (random.nextDouble() - 0.5) * speed * 0.25
            }
        }

        particle.age = 0F
        particle.life = lifeTime * (0.6F + random.nextFloat() * 0.8F)
        particle.rot = random.nextDouble() * Math.PI * 2.0
        particle.spin = (random.nextDouble() - 0.5) * 1.2
        particle.sizeScale = 0.7F + random.nextFloat() * 0.6F
        particle.seed = random.nextDouble() * Math.PI * 2.0
    }

    private fun renderParticles() {
        if (particles.isEmpty()) return

        val player = mc.thePlayer ?: return
        val color = colors.color()
        val baseAlpha = color.alpha / 255F
        val cr = color.red / 255F
        val cg = color.green / 255F
        val cb = color.blue / 255F

        // 相机基向量（billboard）
        val yaw = Math.toRadians(player.rotationYaw.toDouble())
        val pitch = Math.toRadians(player.rotationPitch.toDouble())
        val cosYaw = cos(yaw)
        val sinYaw = sin(yaw)
        val cosPitch = cos(pitch)
        val sinPitch = sin(pitch)

        val forwardX = -sinYaw * cosPitch
        val forwardY = -sinPitch
        val forwardZ = cosYaw * cosPitch

        val rightX = cosYaw
        val rightZ = sinYaw

        val upX = forwardY * rightZ
        val upY = forwardZ * rightX - forwardX * rightZ
        val upZ = -forwardY * rightX

        val camX = mc.renderManager.viewerPosX
        val camY = mc.renderManager.viewerPosY
        val camZ = mc.renderManager.viewerPosZ

        val shape = type

        // 缓冲容量：Dot/Flower 每粒子 30 顶点，Snow 12 顶点
        ensureBuffers(particles.size * 30)
        posFloats.clear()
        colFloats.clear()

        for (particle in particles) {
            // 背面剔除：粒子在相机平面后方（留 1 格余量）直接跳过全部几何计算
            val dx = particle.x - camX
            val dy = particle.y - camY
            val dz = particle.z - camZ
            if (dx * forwardX + dy * forwardY + dz * forwardZ < -1.0) continue

            val alpha = particle.alpha(baseAlpha)
            if (alpha <= 0.004F) continue

            val cx = dx + sin(particle.age * 1.7 + particle.seed) * sway * 0.25
            val cy = dy
            val cz = dz

            when (shape) {
                "Snow" -> emitSnow(particle, cx, cy, cz, rX = rightX, rZ = rightZ,
                    uX = upX, uY = upY, uZ = upZ, cr, cg, cb, alpha)
                "Flower" -> emitFlower(particle, cx, cy, cz, rX = rightX, rZ = rightZ,
                    uX = upX, uY = upY, uZ = upZ, cr, cg, cb, alpha)
                else -> emitDot(particle, cx, cy, cz, rX = rightX, rZ = rightZ,
                    uX = upX, uY = upY, uZ = upZ, cr, cg, cb, alpha)
            }
        }

        val vertexCount = posFloats.position() / 3
        if (vertexCount == 0) return

        // 定向状态捕获（在任何状态修改之前）
        val texState = glIsEnabled(GL_TEXTURE_2D)
        val lightState = glIsEnabled(GL_LIGHTING)
        val cullState = glIsEnabled(GL_CULL_FACE)
        val alphaState = glIsEnabled(GL_ALPHA_TEST)
        val blendState = glIsEnabled(GL_BLEND)
        val depthState = glIsEnabled(GL_DEPTH_TEST)
        val depthMaskState = glGetBoolean(GL_DEPTH_WRITEMASK)

        glDisable(GL_TEXTURE_2D)
        glDisable(GL_LIGHTING)
        glDisable(GL_CULL_FACE)
        glDisable(GL_ALPHA_TEST)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        if (thruBlocks) glDisable(GL_DEPTH_TEST) else glEnable(GL_DEPTH_TEST)

        mc.entityRenderer.disableLightmap()

        // 顶点数组批量绘制：整帧一次 draw call
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
        glEnableClientState(GL_VERTEX_ARRAY)
        glEnableClientState(GL_COLOR_ARRAY)
        glVertexPointer(3, GL_FLOAT, 12, posBuffer)
        glColorPointer(4, GL_FLOAT, 16, colBuffer)
        glDrawArrays(if (shape == "Snow") GL_QUADS else GL_TRIANGLES, 0, vertexCount)
        glDisableClientState(GL_COLOR_ARRAY)
        glDisableClientState(GL_VERTEX_ARRAY)

        // 状态恢复
        glDepthMask(depthMaskState)
        if (depthState) glEnable(GL_DEPTH_TEST) else glDisable(GL_DEPTH_TEST)
        if (blendState) glEnable(GL_BLEND) else glDisable(GL_BLEND)
        if (alphaState) glEnable(GL_ALPHA_TEST) else glDisable(GL_ALPHA_TEST)
        if (cullState) glEnable(GL_CULL_FACE) else glDisable(GL_CULL_FACE)
        if (lightState) glEnable(GL_LIGHTING) else glDisable(GL_LIGHTING)
        if (texState) glEnable(GL_TEXTURE_2D) else glDisable(GL_TEXTURE_2D)
        glColor(Color.WHITE)
    }

    private fun ensureBuffers(maxVertices: Int) {
        if (posBuffer.capacity() >= maxVertices * 12 && colBuffer.capacity() >= maxVertices * 16) return
        posBuffer = BufferUtils.createByteBuffer(maxVertices * 12)
        colBuffer = BufferUtils.createByteBuffer(maxVertices * 16)
        posFloats = posBuffer.asFloatBuffer()
        colFloats = colBuffer.asFloatBuffer()
    }

    private fun vertex(
        x: Double, y: Double, z: Double, r: Float, g: Float, b: Float, a: Float,
    ) {
        posFloats.put(x.toFloat()).put(y.toFloat()).put(z.toFloat())
        colFloats.put(r).put(g).put(b).put(a)
    }

    private fun Particle.alpha(baseAlpha: Float): Float {
        val fraction = (age / life).coerceIn(0F, 1F)
        // 前 15% 淡入，之后线性淡出
        val fadeIn = (fraction / 0.15F).coerceAtMost(1F)
        return baseAlpha * (1F - fraction) * fadeIn
    }

    private fun emitDot(
        p: Particle, cx: Double, cy: Double, cz: Double,
        rX: Double, rZ: Double, uX: Double, uY: Double, uZ: Double,
        cr: Float, cg: Float, cb: Float, alpha: Float,
    ) {
        val s = (size * p.sizeScale).toDouble()

        val segments = 10
        var prevX = s
        var prevY = 0.0
        for (i in 1..segments) {
            val angle = i * Math.PI * 2.0 / segments
            val vx = cos(angle) * s
            val vy = sin(angle) * s

            vertex(cx, cy, cz, cr, cg, cb, alpha)
            vertex(cx + rX * prevX + uX * prevY, cy + uY * prevY, cz + rZ * prevX + uZ * prevY, cr, cg, cb, alpha)
            vertex(cx + rX * vx + uX * vy, cy + uY * vy, cz + rZ * vx + uZ * vy, cr, cg, cb, alpha)

            prevX = vx
            prevY = vy
        }
    }

    private fun emitFlower(
        p: Particle, cx: Double, cy: Double, cz: Double,
        rX: Double, rZ: Double, uX: Double, uY: Double, uZ: Double,
        cr: Float, cg: Float, cb: Float, alpha: Float,
    ) {
        val s = (size * p.sizeScale).toDouble()

        val points = 10
        val step = Math.PI * 2.0 / points
        var prevX = cos(p.rot) * s
        var prevY = sin(p.rot) * s

        for (i in 1..points) {
            val angle = p.rot + i * step
            val petal = if (i % 2 == 0) s else s * 0.45
            val vx = cos(angle) * petal
            val vy = sin(angle) * petal

            vertex(cx, cy, cz, cr, cg, cb, alpha)
            vertex(cx + rX * prevX + uX * prevY, cy + uY * prevY, cz + rZ * prevX + uZ * prevY, cr, cg, cb, alpha)
            vertex(cx + rX * vx + uX * vy, cy + uY * vy, cz + rZ * vx + uZ * vy, cr, cg, cb, alpha)

            prevX = vx
            prevY = vy
        }
    }

    private fun emitSnow(
        p: Particle, cx: Double, cy: Double, cz: Double,
        rX: Double, rZ: Double, uX: Double, uY: Double, uZ: Double,
        cr: Float, cg: Float, cb: Float, alpha: Float,
    ) {
        val s = (size * p.sizeScale).toDouble()
        val thickness = s * 0.13

        for (arm in 0..2) {
            val angle = p.rot + arm * Math.PI / 3.0
            val dirX = cos(angle)
            val dirY = sin(angle)
            val perpX = -sin(angle)
            val perpY = cos(angle)

            // 一根雪花臂：沿 dir 的细长四边形
            val ax1 = -dirX * s - perpX * thickness
            val ay1 = -dirY * s - perpY * thickness
            val ax2 = -dirX * s + perpX * thickness
            val ay2 = -dirY * s + perpY * thickness
            val bx1 = dirX * s + perpX * thickness
            val by1 = dirY * s + perpY * thickness
            val bx2 = dirX * s - perpX * thickness
            val by2 = dirY * s - perpY * thickness

            vertex(cx + rX * ax1 + uX * ay1, cy + uY * ay1, cz + rZ * ax1 + uZ * ay1, cr, cg, cb, alpha)
            vertex(cx + rX * ax2 + uX * ay2, cy + uY * ay2, cz + rZ * ax2 + uZ * ay2, cr, cg, cb, alpha)
            vertex(cx + rX * bx1 + uX * by1, cy + uY * by1, cz + rZ * bx1 + uZ * by1, cr, cg, cb, alpha)
            vertex(cx + rX * bx2 + uX * by2, cy + uY * by2, cz + rZ * bx2 + uZ * by2, cr, cg, cb, alpha)
        }
    }

    private val onWorld = handler<WorldEvent> {
        particles.clear()
        lastUpdate = 0L
    }

    override fun onDisable() {
        particles.clear()
        lastUpdate = 0L
    }
}

private class Particle {
    var x = 0.0
    var y = 0.0
    var z = 0.0
    var vx = 0.0
    var vy = 0.0
    var vz = 0.0
    var age = 0F
    var life = 1F
    var rot = 0.0
    var spin = 0.0
    var sizeScale = 1F
    var seed = 0.0
}
