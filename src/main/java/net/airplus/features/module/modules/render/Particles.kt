/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.render

import net.airplus.event.Render3DEvent
import net.airplus.event.UpdateEvent
import net.airplus.event.WorldEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.render.ColorSettingsInteger
import net.airplus.utils.render.RenderUtils.glColor
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.util.BlockPos
import net.minecraft.util.MathHelper
import net.minecraft.util.ResourceLocation
import net.minecraft.util.Vec3
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.world.World
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
 * - Bloom/Stars/Hearts/Dollars/SnowFlake：Moonlight Fireflies 移植的贴图粒子
 *   （airplus/moonlight/texture/fireflies/ 下贴图，走独立的物理/渲染路径，
 *   但设置与几何粒子统一共用 AirPlus 原版的 Color/Animation/Size/Amount/Radius/Speed/Sway/LifeTime/ThruBlocks）
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

    private val type by choices("Type", arrayOf("Dot", "Snow", "Flower", "Bloom", "Stars", "Hearts", "Dollars", "SnowFlake"), "Dot")
    val colors = ColorSettingsInteger(this, "Color").with(Color(255, 255, 255, 190))

    /**
     * 动画样式（几何粒子与贴图粒子统一）：
     * Fall = 从天而降铺满视野，Rise = 自下而上，Float = 空中悬浮漂移，
     * Drop = moonlight Drop 物理（直落 + 重力），Fly = moonlight Fly 物理（自由漂移）。
     * 几何粒子无重力概念：Drop 同 Fall、Fly 同 Float；贴图粒子 Drop/Fly 走原 moonlight 物理。
     */
    private val animMode by choices("Animation", arrayOf("Fall", "Rise", "Float", "Drop", "Fly"), "Rise")

    private val size by float("Size", 0.12F, 0.02F..0.6F)
    private val amount by int("Amount", 60, 1..300)
    private val radius by float("Radius", 6F, 1F..24F)
    private val speed by float("Speed", 0.35F, 0F..3F)
    private val sway by float("Sway", 0.25F, 0F..1F)
    private val lifeTime by float("LifeTime", 6F, 1F..30F, "s")
    private val thruBlocks by boolean("ThruBlocks", false)

    // ===== Moonlight Particles（Fireflies 贴图粒子）独有外观开关：仅当 Type 为贴图模式时生效 =====
    // 其余设置统一走 AirPlus 原版的 Color/Animation/Size/Amount/Radius/Speed/Sway/LifeTime/ThruBlocks
    private val fireflyLime by boolean("Lime", false) { isFireflies() }
    private val fireflyLighting by boolean("Lighting", false) { isFireflies() }

    /** 贴图粒子速度随 Speed 设置缩放（0.35 为原 moonlight 默认手感基准） */
    private val fireflySpeedScale: Double
        get() = (speed / 0.35F).toDouble()

    /** 贴图粒子物理：Fall/Drop = Drop（受重力下落），Rise/Float/Fly = Fly（漂移） */
    private val fireflyPhysicsMode: String
        get() = if (animMode == "Fall" || animMode == "Drop") "Drop" else "Fly"

    private fun isFireflies(): Boolean =
        type == "Bloom" || type == "Stars" || type == "Hearts" || type == "Dollars" || type == "SnowFlake"

    private fun randBetween(min: Double, max: Double): Double = Math.random() * (max - min) + min

    private val fireflies = ArrayList<FirePart>()

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

        // Moonlight Fireflies（贴图粒子）：独立系统，与原有几何粒子互不影响
        if (isFireflies()) {
            renderFireflies(it.partialTicks)
            return@handler
        }

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
                "Fall", "Drop" -> if (particle.y < player.posY - radius * 0.5) reset(particle, player)
                "Rise" -> if (particle.y > player.posY + radius * 1.5) reset(particle, player)
            }
        }

        renderParticles()
    }

    // Moonlight onUpdate：Fireflies 粒子的出生 / 更新 / 回收（出生/寿命/范围统一走 AirPlus 设置）
    private val onFireflyUpdate = handler<UpdateEvent> {
        if (!isFireflies()) {
            fireflies.clear()
            return@handler
        }
        val player = mc.thePlayer ?: return@handler
        val world = mc.theWorld ?: return@handler

        if (player.ticksExisted == 1) {
            fireflies.forEach { it.setToRemove() }
        }
        fireflies.forEach { it.updatePart(player, world, fireflyPhysicsMode) }
        fireflies.removeAll { it.isToRemove() }

        while (fireflies.size < amount) {
            val part = FirePart(
                spawnFireflyPos(player),
                (lifeTime * 20.0 * randBetween(0.6, 1.4)).toInt().coerceAtLeast(40)
            )
            when (animMode) {
                "Fall", "Drop" -> {
                    // 从头顶生成，向下飘落
                    part.motionX = 0F
                    part.motionZ = 0F
                    part.motionY = (randBetween(-0.08, -0.03) * fireflySpeedScale).toFloat()
                }
                "Rise" -> {
                    // 自下而上飘起
                    part.motionX = (randBetween(-0.2, 0.2) * fireflySpeedScale).toFloat()
                    part.motionZ = (randBetween(-0.2, 0.2) * fireflySpeedScale).toFloat()
                    part.motionY = (randBetween(0.03, 0.08) * fireflySpeedScale).toFloat()
                }
                else -> {
                    // Float/Fly：立体球状分布，慢速漂移
                    part.motionX = (randBetween(-0.4, 0.4) * fireflySpeedScale).toFloat()
                    part.motionZ = (randBetween(-0.4, 0.4) * fireflySpeedScale).toFloat()
                    part.motionY = (randBetween(-0.1, 0.1) * fireflySpeedScale).toFloat()
                }
            }
            fireflies += part
        }
    }

    /** 贴图粒子出生位置：水平按 Radius 铺开，垂直按 Animation 模式分布（同几何粒子 reset 的分布逻辑） */
    private fun spawnFireflyPos(player: EntityPlayer): Vec3 {
        val angle = random.nextDouble() * Math.PI * 2.0
        val distance = sqrt(random.nextDouble()) * radius

        val y = when (animMode) {
            "Fall", "Drop" -> player.posY + radius * (0.5 + random.nextDouble())
            "Rise" -> player.posY - radius * 0.5 + random.nextDouble() * radius * 0.8
            else -> player.posY + (random.nextDouble() - 0.5) * radius * 1.2
        }

        return Vec3(player.posX + cos(angle) * distance, y, player.posZ + sin(angle) * distance)
    }

    private fun reset(particle: Particle, player: net.minecraft.entity.player.EntityPlayer) {
        val angle = random.nextDouble() * Math.PI * 2.0
        // 水平铺开 1.4 倍半径，营造铺天盖地的范围感
        val distance = sqrt(random.nextDouble()) * radius * 1.4

        particle.x = player.posX + cos(angle) * distance
        particle.z = player.posZ + sin(angle) * distance

        when (animMode) {
            "Fall", "Drop" -> {
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
                // Float/Fly：以玩家为中心的立体球状分布，极慢漂移
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
        fireflies.clear()
    }

    override fun onDisable() {
        particles.clear()
        lastUpdate = 0L
        fireflies.clear()
    }

    // ==================== Moonlight Fireflies（wtf.moonlight Particles 移植） ====================

    private fun renderFireflies(partialTicks: Float) {
        if (fireflies.isEmpty()) return
        setupFireflyDraw {
            mc.textureManager.bindTexture(fireflyTexture())
            fireflies.forEach { drawFirePart(it, partialTicks) }
        }
    }

    private fun fireflyTexture(): ResourceLocation = when (type) {
        "Bloom" -> ResourceLocation("airplus/moonlight/texture/fireflies/firefly.png")
        "Stars" -> ResourceLocation("airplus/moonlight/texture/fireflies/star.png")
        "Hearts" -> ResourceLocation("airplus/moonlight/texture/fireflies/heart.png")
        "Dollars" -> ResourceLocation("airplus/moonlight/texture/fireflies/dollar.png")
        else -> ResourceLocation("airplus/moonlight/texture/fireflies/snowflake.png")
    }

    /** 粒子颜色 = Color 设置 + 出生淡入透明度（moonlight getPartColor 的等价实现，颜色源统一为 AirPlus 设置） */
    private fun partColor(part: FirePart): Color {
        val c = colors.color()
        return Color(c.red, c.green, c.blue, (c.alpha * part.animOutput).toInt().coerceIn(0, 255))
    }

    /** 贴图公告牌 + 光晕（moonlight drawPart：billboard + drawBindedTexture） */
    private fun drawFirePart(part: FirePart, partialTicks: Float) {
        val color = partColor(part)
        if (fireflyLime) {
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
            drawFireSparkParts(color, part, partialTicks)
            drawFireTrailParts(color, part)
            GlStateManager.tryBlendFuncSeparate(770, 1, 1, 0)
        }

        val renderPos = part.getRenderPosVec(partialTicks)
        GlStateManager.pushMatrix()
        GlStateManager.translate(renderPos.xCoord, renderPos.yCoord, renderPos.zCoord)
        glNormal3d(1.0, 1.0, 1.0)
        GlStateManager.rotate(-mc.renderManager.playerViewY, 0F, 1F, 0F)
        GlStateManager.rotate(mc.renderManager.playerViewX, if (mc.gameSettings.thirdPersonView == 2) -1F else 1F, 0F, 0F)
        GlStateManager.scale(-0.1F, -0.1F, 0.1F)

        // Size 设置为世界坐标下的粒子直径（公告牌 scale 0.1，故四边形半宽 = size * 10）
        val s = size * 20F
        drawBindedFireflyTexture(-s / 2F, -s / 2F, s / 2F, s / 2F, color.rgb)
        if (fireflyLighting) {
            val big = s * 3F
            val glow = applyOpacity(darkerColor(color, 0.2F), (part.animOutput / 7.0).toFloat())
            drawBindedFireflyTexture(-big / 2F, -big / 2F, big / 2F, big / 2F, glow.rgb)
        }
        GlStateManager.popMatrix()
    }

    /** 与玩家的距离（moonlight getSmoothDistanceToCoord 的等价实现） */
    private fun fireflyDistTo(part: FirePart): Float {
        val player = mc.thePlayer ?: return 0F
        val pos = part.getPosVec()
        return MathHelper.sqrt_double(player.getDistanceSq(pos.xCoord, pos.yCoord + 1.6, pos.zCoord)).toFloat()
    }

    /** 火花拖尾点（moonlight drawSparkPartsList） */
    private fun drawFireSparkParts(color: Color, part: FirePart, partialTicks: Float) {
        if (part.sparkParts.size < 2) return

        glDisable(GL_TEXTURE_2D)
        glEnable(GL_BLEND)
        glDisable(GL_ALPHA_TEST)
        glEnable(GL_POINT_SMOOTH)
        glPointSize(1.5F + 6F * MathHelper.clamp_float(1F - (fireflyDistTo(part) - 3F) / 10F, 0F, 1F))
        glBegin(GL_POINTS)

        for (spark in part.sparkParts) {
            glColorARGB(color.rgb)
            val x = spark.prevPosX + (spark.posX - spark.prevPosX) * partialTicks
            val y = spark.prevPosY + (spark.posY - spark.prevPosY) * partialTicks
            val z = spark.prevPosZ + (spark.posZ - spark.prevPosZ) * partialTicks
            glVertex3d(x, y, z)
        }

        glEnd()
        GlStateManager.resetColor()
        glEnable(GL_ALPHA_TEST)
        glEnable(GL_TEXTURE_2D)
    }

    /** 粒子拖尾 GL_LINE_STRIP（moonlight drawTrailPartsList） */
    private fun drawFireTrailParts(color: Color, part: FirePart) {
        if (part.trailParts.size < 2) return

        glDisable(GL_TEXTURE_2D)
        glLineWidth(1.0E-5F + 8F * MathHelper.clamp_float(1F - (fireflyDistTo(part) - 3F) / 20F, 0F, 1F))
        glEnable(GL_BLEND)
        glDisable(GL_ALPHA_TEST)
        glEnable(GL_LINE_SMOOTH)
        glHint(GL_LINE_SMOOTH_HINT, GL_NICEST)
        glBegin(GL_LINE_STRIP)

        for (trail in part.trailParts) {
            glColorARGB(color.rgb)
            glVertex3d(trail.x, trail.y, trail.z)
        }

        glEnd()
        GlStateManager.resetColor()
        glEnable(GL_ALPHA_TEST)
        glDisable(GL_LINE_SMOOTH)
        glHint(GL_LINE_SMOOTH_HINT, GL_DONT_CARE)
        glLineWidth(1F)
        glEnable(GL_TEXTURE_2D)
    }

    private fun drawBindedFireflyTexture(x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        val tessellator = Tessellator.getInstance()
        val buffer = tessellator.worldRenderer
        buffer.begin(GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR)
        buffer.pos(x1.toDouble(), y1.toDouble(), 0.0).tex(0.0, 0.0)
            .color(color shr 16 and 0xFF, color shr 8 and 0xFF, color and 0xFF, color ushr 24 and 0xFF).endVertex()
        buffer.pos(x1.toDouble(), y2.toDouble(), 0.0).tex(0.0, 1.0)
            .color(color shr 16 and 0xFF, color shr 8 and 0xFF, color and 0xFF, color ushr 24 and 0xFF).endVertex()
        buffer.pos(x2.toDouble(), y2.toDouble(), 0.0).tex(1.0, 1.0)
            .color(color shr 16 and 0xFF, color shr 8 and 0xFF, color and 0xFF, color ushr 24 and 0xFF).endVertex()
        buffer.pos(x2.toDouble(), y1.toDouble(), 0.0).tex(1.0, 0.0)
            .color(color shr 16 and 0xFF, color shr 8 and 0xFF, color and 0xFF, color ushr 24 and 0xFF).endVertex()
        tessellator.draw()
    }

    /** moonlight setupGLDrawsFireParts：定向 GL 状态切换 + 相机平移，finally 中恢复（深度测试跟随 ThruBlocks 设置） */
    private fun setupFireflyDraw(body: () -> Unit) {
        val viewerX = mc.renderManager.viewerPosX
        val viewerY = mc.renderManager.viewerPosY
        val viewerZ = mc.renderManager.viewerPosZ
        val depthState = glIsEnabled(GL_DEPTH_TEST)

        GlStateManager.pushMatrix()
        GlStateManager.tryBlendFuncSeparate(770, 1, 1, 0)
        mc.entityRenderer.disableLightmap()
        glEnable(GL_BLEND)
        glLineWidth(1F)
        glEnable(GL_TEXTURE_2D)
        glDisable(GL_LIGHTING)
        glShadeModel(GL_SMOOTH)
        glDisable(GL_ALPHA_TEST)
        glDisable(GL_CULL_FACE)
        glDepthMask(false)
        if (thruBlocks) glDisable(GL_DEPTH_TEST) else glEnable(GL_DEPTH_TEST)
        try {
            GlStateManager.translate(-viewerX, -viewerY, -viewerZ)
            try {
                body()
            } catch (t: Throwable) {
                Tessellator.getInstance().worldRenderer.reset()
            }
            GlStateManager.translate(viewerX, viewerY, viewerZ)
        } finally {
            glDepthMask(true)
            if (depthState) glEnable(GL_DEPTH_TEST) else glDisable(GL_DEPTH_TEST)
            glEnable(GL_CULL_FACE)
            glEnable(GL_ALPHA_TEST)
            glLineWidth(1F)
            glShadeModel(GL_FLAT)
            glEnable(GL_TEXTURE_2D)
            GlStateManager.resetColor()
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
            GlStateManager.popMatrix()
        }
    }

    private fun darkerColor(color: Color, factor: Float): Color = Color(
        (color.red * factor).toInt().coerceIn(0, 255),
        (color.green * factor).toInt().coerceIn(0, 255),
        (color.blue * factor).toInt().coerceIn(0, 255),
        color.alpha
    )

    private fun applyOpacity(color: Color, opacity: Float): Color {
        val o = opacity.coerceIn(0F, 1F)
        return Color(color.red, color.green, color.blue, (color.alpha * o).toInt())
    }

    private fun glColorARGB(color: Int) {
        GlStateManager.color(
            (color shr 16 and 0xFF) / 255F,
            (color shr 8 and 0xFF) / 255F,
            (color and 0xFF) / 255F,
            (color ushr 24 and 0xFF) / 255F
        )
    }

    /** Fireflies 贴图粒子（Moonlight FirePart 移植，object 内不允许 inner class，依赖全部走参数） */
    private class FirePart(pos: Vec3, ageIn: Int) {
        val trailParts = ArrayList<TrailPart>()
        val sparkParts = ArrayList<SparkPart>()
        var prevPos: Vec3 = pos
        var pos: Vec3 = pos
        private val bornAt = System.currentTimeMillis()
        var motionX = 0F
        var motionY = 0F
        var motionZ = 0F
        private var age = ageIn
        private var toRemove = false

        /** 出生后 400ms 内 0→1 平滑淡入（moonlight SmoothStepAnimation(400, 1.0) 等价） */
        val animOutput: Float
            get() {
                val t = ((System.currentTimeMillis() - bornAt) / 400.0).coerceIn(0.0, 1.0)
                return (-2.0 * t * t * t + 3.0 * t * t).toFloat()
            }

        fun getPosVec(): Vec3 = pos

        fun getRenderPosVec(partialTicks: Float): Vec3 = pos.addVector(
            -(prevPos.xCoord - pos.xCoord) * partialTicks,
            -(prevPos.yCoord - pos.yCoord) * partialTicks,
            -(prevPos.zCoord - pos.zCoord) * partialTicks
        )

        fun updatePart(player: EntityPlayer, world: World, physics: String) {
            // 回收距离跟随 Radius 设置（同几何粒子的 cullDist = radius * 2.5）
            val cullDistSq = (radius * 2.5).toDouble().let { it * it }
            val distanceSq = player.getDistanceSq(pos.xCoord, pos.yCoord, pos.zCoord)
            if (distanceSq > cullDistSq) {
                age -= 8
            } else if (!world.isAirBlock(BlockPos(pos.xCoord, pos.yCoord, pos.zCoord))) {
                age -= 8
            } else {
                age--
            }

            prevPos = pos
            pos = pos.addVector(motionX.toDouble(), motionY.toDouble(), motionZ.toDouble())

            if (physics == "Drop") {
                motionX *= 0.98F
                motionZ *= 0.98F
                motionY -= 0.005F
                if (pos.yCoord < player.posY + 5.0) {
                    motionY *= 0.95F
                }
            } else {
                motionX *= 0.99F
                motionZ *= 0.99F
                motionY *= 0.99F
                if (world.rand.nextInt(20) == 0) {
                    // 漂移抖动幅度跟随 Sway 设置（0.25 为原 moonlight 默认手感基准）
                    val wander = (sway / 0.25F).toFloat()
                    motionX += (world.rand.nextFloat() - 0.5F) * 0.02F * wander
                    motionZ += (world.rand.nextFloat() - 0.5F) * 0.02F * wander
                    motionY += (world.rand.nextFloat() - 0.5F) * 0.01F * wander
                }
            }

            if (age <= 0) {
                setToRemove()
            }

            if (trailParts.isNotEmpty()) {
                trailParts.forEach { it.age-- }
                trailParts.removeAll { it.age <= 0 }
            }
            if (sparkParts.isNotEmpty()) {
                sparkParts.removeAll { it.age <= 0 }
            }

            trailParts += TrailPart(this)
            repeat(2) { sparkParts += SparkPart(this) }
            sparkParts.forEach { it.motionSparkProcess() }
        }

        fun setToRemove() {
            toRemove = true
        }

        fun isToRemove(): Boolean = toRemove
    }

    /** FirePart 每帧生成的火花点（Moonlight SparkPart 移植） */
    private class SparkPart(part: FirePart) {
        var posX = part.getPosVec().xCoord
        var posY = part.getPosVec().yCoord
        var posZ = part.getPosVec().zCoord
        var prevPosX = posX
        var prevPosY = posY
        var prevPosZ = posZ
        var speed = Math.random() / 30.0
        var radianYaw = Math.random() * 360.0
        var radianPitch = -90.0 + Math.random() * 180.0
        var age = 15

        fun motionSparkProcess() {
            prevPosX = posX
            prevPosY = posY
            prevPosZ = posZ
            val yaw = Math.toRadians(radianYaw)
            posX += Math.sin(yaw) * speed
            posY += Math.cos(Math.toRadians(radianPitch - 90.0)) * speed
            posZ += Math.cos(yaw) * speed
            age--
        }
    }

    /** FirePart 拖尾采样点（Moonlight TrailPart 移植） */
    private class TrailPart(part: FirePart) {
        val x = part.getPosVec().xCoord
        val y = part.getPosVec().yCoord
        val z = part.getPosVec().zCoord
        var age = 30
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
