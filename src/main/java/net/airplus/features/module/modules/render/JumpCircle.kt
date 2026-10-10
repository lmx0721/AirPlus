/*
 * Air Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 */
package net.airplus.features.module.modules.render

import net.airplus.event.JumpEvent
import net.airplus.event.Render3DEvent
import net.airplus.event.WorldEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.ui.font.Fonts
import net.airplus.utils.client.ClientThemesUtils
import net.airplus.utils.client.ClientUtils.runTimeTicks
import net.airplus.utils.extensions.lerpWith
import net.airplus.utils.render.ColorUtils.shiftHue
import net.airplus.utils.render.ColorUtils.withAlpha
import net.airplus.utils.render.RenderUtils.customRotatedObject2D
import net.airplus.utils.render.RenderUtils.drawHueCircle
import net.airplus.utils.render.RenderUtils.setupDrawCircles
import net.airplus.utils.render.animation.AnimationUtil.easeInOutElasticx
import net.airplus.utils.render.animation.AnimationUtil.easeInOutExpo
import net.airplus.utils.render.animation.AnimationUtil.easeOutBounce
import net.airplus.utils.render.animation.AnimationUtil.easeOutCirc
import net.airplus.utils.render.animation.AnimationUtil.easeOutElasticX
import net.airplus.utils.render.animation.AnimationUtil.easeWave
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.entity.Entity
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.init.Blocks
import net.minecraft.util.BlockPos
import net.minecraft.util.ResourceLocation
import net.minecraft.util.Vec3
import org.lwjgl.opengl.GL11.*
import java.awt.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin

object JumpCircle : Module("JumpCircle", Category.RENDER, gameDetecting = false) {
    private val colorMode by choices("Color", arrayOf("Custom", "Theme"), "Theme")
    private val circleRadius by floatRange("CircleRadius", 0.15F..0.8F, 0F..3F)
    private val innerColor = color("InnerColor", Color(0, 0, 0, 50)) { colorMode == "Custom" }
    private val outerColor = color("OuterColor", Color(0, 111, 255, 255)) { colorMode == "Custom" }
    private val hueOffsetAnim by int("HueOffsetAnim", 63, -360..360)
    private val lifeTime by int("LifeTime", 20, 1..50, "Ticks")
    private val blackHole by boolean("BlackHole", false)
    private val useTexture by boolean("UseTexture", true)
    private val texture by choices("Texture", arrayOf("Supernatural", "Circle"), "Supernatural") { useTexture }
    private val deepestLight by boolean("Deepest Light", true) { useTexture }

    // ---- Onyx 样式（迁移自 Onyx JumpCircles，视觉重实现）----
    private val style by choices("Style", arrayOf("Classic", "Onyx"), "Classic")
    private val ringMode by choices("RingMode", arrayOf("Ring", "Text"), "Ring") { style == "Onyx" }
    private val ringText by text("Text", "ONYX") { style == "Onyx" }
    private val textSize by float("TextSize", 0.22F, 0.05F..0.6F) { style == "Onyx" }
    private val spinSpeed by float("SpinSpeed", 40F, -360F..360F, "°/s") { style == "Onyx" }
    private val onyxRadius by float("Radius", 1.5F, 0.3F..6F) { style == "Onyx" }
    private val onyxOpacity by float("Opacity", 70F, 5F..100F, "%") { style == "Onyx" }
    private val onyxDuration by float("Duration", 2F, 0.25F..8F, "s") { style == "Onyx" }
    private val otherPlayers by boolean("Other players", false) { style == "Onyx" }

    private val staticLoc = ResourceLocation("airplus/textures/jumpcircle/default")

    private val circleIcon = ResourceLocation("$staticLoc/circle1.png")
    private val supernaturalIcon = ResourceLocation("$staticLoc/circle2.png")

    // onUpdate/onRender3D/onWorld 均在主线程触发，无需 CopyOnWriteArrayList
    private val circles: MutableList<JumpData> = mutableListOf()
    // 复用过滤缓冲，避免每帧分配新的 ArrayList
    private val circlesRemaining: MutableList<JumpData> = mutableListOf()
    private var hasJumped = false

    // Onyx：entityId -> 玩家最后一次在地面时的脚底位置
    private val groundPositions = mutableMapOf<Int, Vec3>()
    // Onyx：已生成的圆环（位置 + 创建时间戳），用 System.currentTimeMillis 做生命周期
    private val onyxCircles = mutableListOf<OnyxRing>()

    private val tessellator = Tessellator.getInstance()
    private val worldRenderer = tessellator.worldRenderer
    // Onyx 细环线的分段数
    private const val ONYX_RING_STEPS = 64
    // 缓存上次设置 blur/mipmap 的纹理，避免每帧重复调用 setBlurMipmap
    private var lastBlurTexture: ResourceLocation? = null

    private fun selectJumpTexture(): ResourceLocation = when (texture) {
        "Circle" -> circleIcon
        else -> supernaturalIcon
    }

    private fun createCircleForEntity(entity: Entity) {
        var entityPos = calculateEntityPosition(entity).addVector(0.0, 0.005, 0.0)
        val position = BlockPos(entityPos)
        if (mc.theWorld.getBlockState(position).block == Blocks.snow) {
            entityPos = entityPos.addVector(0.0, 0.125, 0.0)
        }
        circles += JumpData(entityPos, runTimeTicks + if (blackHole) lifeTime else 0)
    }

    val onJump = handler<JumpEvent> {
        hasJumped = true
    }

    val onRender3D = handler<Render3DEvent> {
        // Onyx 样式：起跳检测 + 环线/环形文字渲染，与 Classic 路径互不干扰
        if (style == "Onyx") {
            updateOnyxRings()
            renderOnyxRings()
            return@handler
        }
        if(mc.thePlayer.onGround && hasJumped){
            createCircleForEntity(mc.thePlayer)
            hasJumped = false
        }
        if(circles.isEmpty()) return@handler
        val partialTick = it.partialTicks
        val lightFactor = if (deepestLight) 1f else 0f
        val effectStrength = when {
            lightFactor >= 1f / 255f -> when (texture) {
                "Circle" -> 0.1f
                "Supernatural" -> 0.075f
                else -> 0f
            }
            else -> 0f
        }
        // 光照用 save/restore：无条件开启会让后续绘制的半透明元素（如 Scaffold 的标记）被环境光衰减而变灰。
        // blend 必须无条件关闭：Render3DEvent 触发点在 renderHand 之前，半透明地形渲染遗留的开启状态
        // 若被还原回去，第一人称手部物品会被混合渲染而呈半透明（与 AirClient 原版一致）。
        val lightingEnabled = glIsEnabled(GL_LIGHTING)
        setupDrawCircles {
            // 复用缓冲：先渲染所有圆，再一次性重建列表，避免每帧分配新的 ArrayList
            circlesRemaining.clear()
            var anyExpired = false
            circles.forEach {
                val progress = ((runTimeTicks + partialTick) - it.endTime) / lifeTime
                if (progress >= 1F) {
                    anyExpired = true
                    return@forEach
                }
                val radius = circleRadius.lerpWith(progress)
                if(useTexture){
                    renderTexturedCircle(
                        it.pos,
                        radius.toDouble(),
                        1f - progress,
                        lightFactor,
                        effectStrength
                    )
                } else {
                    renderSimpleCircle(it.pos, radius, progress)
                }
                circlesRemaining.add(it)
            }
            if (anyExpired) {
                circles.clear()
                circles.addAll(circlesRemaining)
            }
        }
        GlStateManager.color(1f, 1f, 1f, 1f)
        GlStateManager.depthMask(true)
        GlStateManager.disableBlend()
        if (lightingEnabled) GlStateManager.enableLighting() else GlStateManager.disableLighting()
    }

    override fun onDisable() {
        circles.clear()
        groundPositions.clear()
        onyxCircles.clear()
        lastBlurTexture = null
    }

    val onWorld = handler<WorldEvent> {
        circles.clear()
        groundPositions.clear()
        onyxCircles.clear()
        lastBlurTexture = null
    }

    private fun renderSimpleCircle(pos: Vec3, radius: Float, timeFraction: Float){
        val (color, color2) = when (colorMode) {
            "Theme" -> {
                val baseColor = ClientThemesUtils.getColor()
                val inner = baseColor.withAlpha((baseColor.alpha * (1 - timeFraction)).toInt().coerceIn(0, 255))
                val outer = baseColor.withAlpha((baseColor.alpha * (1 - timeFraction)).toInt().coerceIn(0, 255))
                Pair(inner, outer)
            }
            else -> {
                val inner = animateColor(innerColor.selectedColor(), 1f - timeFraction)
                val outer = animateColor(outerColor.selectedColor(), 1f - timeFraction)
                Pair(inner,outer)
            }
        }
        drawHueCircle(
            pos,
            radius,
            color,
            color2
        )
        GlStateManager.color(1f, 1f, 1f, 1f)
    }

    private fun renderTexturedCircle(pos: Vec3, maxRadius: Double, timeFraction: Float, shift: Float, intensity: Float) {
        val waveValue = 1f - timeFraction
        val wave = easeWave(waveValue)
        var alphaFraction = easeOutCirc(wave.toDouble()).toFloat()
        if (timeFraction < 0.5f) alphaFraction *= easeInOutExpo(alphaFraction.toDouble()).toFloat()
        val mainFactor = if (timeFraction > 0.5f) {
            easeOutElasticX((wave * wave).toDouble())
        } else {
            easeOutBounce(wave.toDouble())
        }
        val circleRadius = (mainFactor * maxRadius).toFloat()
        val rotation = (easeInOutElasticx(wave.toDouble()) * 90.0 / (1.0 + wave.toDouble()))
        val textureResource = selectJumpTexture()
        val (color, color2) = when (colorMode) {
            "Theme" -> {
                val baseColor = ClientThemesUtils.getColor()
                val inner = baseColor.withAlpha((baseColor.alpha * (1 - timeFraction)).toInt().coerceIn(0, 255))
                val outer = baseColor.withAlpha((baseColor.alpha * (1 - timeFraction)).toInt().coerceIn(0, 255))
                Pair(inner, outer)
            }
            else -> {
                val inner = animateColor(innerColor.selectedColor(), 1f - timeFraction)
                val outer = animateColor(outerColor.selectedColor(), 1f - timeFraction)
                Pair(inner,outer)
            }
        }
        val red = ((color.rgb shr 16) and 0xFF) / 255f
        val green = ((color.rgb shr 8) and 0xFF) / 255f
        val blue = (color.rgb and 0xFF) / 255f
        val alpha = ((color.rgb shr 24) and 0xFF) / 255f
        val red2 = ((color2.rgb shr 16) and 0xFF) / 255f
        val green2 = ((color2.rgb shr 8) and 0xFF) / 255f
        val blue2 = (color2.rgb and 0xFF) / 255f
        val alpha2 = ((color2.rgb shr 24) and 0xFF) / 255f
        mc.textureManager.bindTexture(textureResource)
        // 仅在纹理切换时调用 setBlurMipmap，避免每帧重复 GL 调用
        if (lastBlurTexture != textureResource) {
            mc.textureManager.getTexture(textureResource).setBlurMipmap(true, true)
            lastBlurTexture = textureResource
        }
        GlStateManager.pushMatrix()
        GlStateManager.translate(pos.xCoord - circleRadius / 2.0, pos.yCoord, pos.zCoord - circleRadius / 2.0)
        GlStateManager.rotate(90f, 1f, 0f, 0f)
        customRotatedObject2D(0f, 0f, circleRadius, circleRadius, rotation)
        worldRenderer.begin(GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR)
        worldRenderer.pos(0.0, 0.0, 0.0).tex(0.0, 0.0).color(red, green, blue, alpha).endVertex()
        worldRenderer.pos(0.0, circleRadius.toDouble(), 0.0).tex(0.0, 1.0).color(red, green, blue, alpha).endVertex()
        worldRenderer.pos(circleRadius.toDouble(), circleRadius.toDouble(), 0.0).tex(1.0, 1.0).color(red, green, blue, alpha).endVertex()
        worldRenderer.pos(circleRadius.toDouble(), 0.0, 0.0).tex(1.0, 0.0).color(red, green, blue, alpha).endVertex()
        tessellator.draw()
        GlStateManager.color(1f, 1f, 1f, 1f)
        GlStateManager.popMatrix()
        if (shift >= 1f / 255f) {
            GlStateManager.pushMatrix()
            GlStateManager.translate(pos.xCoord, pos.yCoord, pos.zCoord)
            GlStateManager.rotate(rotation.toFloat(), 0f, 1f, 0f)
            worldRenderer.begin(GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR)
            val polygons = 40
            val maxY = circleRadius / 3.5f
            val maxXZ = circleRadius / 7f
            for (i in 1 until polygons) {
                val fraction = i / polygons.toFloat()
                val fractionValue = fraction - (1.5f / polygons)
                val wave2 = easeWave(fractionValue)
                val circVal = easeOutCirc(wave2.toDouble()).toFloat()
                val alphaCheck = (alphaFraction * intensity * shift).takeIf { it * 255 >= 1 } ?: continue
                val alphaInt = (alphaCheck * 255).toInt()
                val variedRadius = circleRadius + circVal * maxXZ
                worldRenderer.pos((-variedRadius / 2f).toDouble(), (maxY * i / polygons - maxY / polygons).toDouble(), (-variedRadius / 2f).toDouble())
                    .tex(0.0, 0.0).color(red2, green2, blue2, alphaInt / 255f).endVertex()
                worldRenderer.pos((-variedRadius / 2f).toDouble(), (maxY * i / polygons - maxY / polygons).toDouble(), (variedRadius / 2f).toDouble())
                    .tex(0.0, 1.0).color(red2, green2, blue2, alphaInt / 255f).endVertex()
                worldRenderer.pos((variedRadius / 2f).toDouble(), (maxY * i / polygons - maxY / polygons).toDouble(), (variedRadius / 2f).toDouble())
                    .tex(1.0, 1.0).color(red2, green2, blue2, alphaInt / 255f).endVertex()
                worldRenderer.pos((variedRadius / 2f).toDouble(), (maxY * i / polygons - maxY / polygons).toDouble(), (-variedRadius / 2f).toDouble())
                    .tex(1.0, 0.0).color(red2, green2, blue2, alphaInt / 255f).endVertex()
            }
            tessellator.draw()
            GlStateManager.color(1f, 1f, 1f, 1f)
            GlStateManager.popMatrix()
        }
    }
    // ---- Onyx 样式实现 ----

    // Onyx：跟踪玩家起跳。玩家在地面时记录脚底位置；离地且仍在上升（motionY > 0 或高于记录位置）
    // 时在最后落地的位置生成圆环，生命周期用 Duration*1000 毫秒。
    private fun updateOnyxRings() {
        val world = mc.theWorld ?: return
        val now = System.currentTimeMillis()
        val lifeMs = (onyxDuration * 1000).toLong()
        onyxCircles.removeAll { now - it.time >= lifeMs }
        val aliveIds = HashSet<Int>()
        for (player in world.playerEntities) {
            if (!player.isEntityAlive) continue
            if (player !== mc.thePlayer && !otherPlayers) continue
            aliveIds.add(player.entityId)
            if (!player.onGround) {
                // 离地：真正起跳（而非走下台阶）才落环
                val recorded = groundPositions.remove(player.entityId) ?: continue
                if (player.motionY > 0.0 || player.posY > recorded.yCoord + 0.01) {
                    onyxCircles.add(OnyxRing(recorded, now))
                }
            } else {
                groundPositions[player.entityId] = Vec3(player.posX, player.posY, player.posZ)
            }
        }
        // 玩家离开世界时清理对应条目
        groundPositions.keys.retainAll(aliveIds)
    }

    private fun renderOnyxRings() {
        if (onyxCircles.isEmpty()) return
        val rm = mc.renderManager
        val now = System.currentTimeMillis()
        val lifeMs = (onyxDuration * 1000).toLong()

        // GL 状态保存/恢复与 Classic 路径同样小心；blend 结束后必须无条件关闭（原因见上方注释）
        val lightingEnabled = glIsEnabled(GL_LIGHTING)
        val cullEnabled = glIsEnabled(GL_CULL_FACE)
        val lastLineWidth = glGetFloat(GL_LINE_WIDTH)
        glPushMatrix()
        GlStateManager.enableBlend()
        GlStateManager.blendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        GlStateManager.depthMask(false)
        if (lightingEnabled) GlStateManager.disableLighting()
        if (cullEnabled) GlStateManager.disableCull()
        glDisable(GL_TEXTURE_2D)
        glLineWidth(2F)

        // 第一遍：细环线（Ring/Text 模式都画）
        for (ring in onyxCircles) {
            val color = onyxColor(ring, now, lifeMs) ?: continue
            renderOnyxRingLine(ring.pos, rm.renderPosX, rm.renderPosY, rm.renderPosZ, color)
        }
        // 第二遍：环形文字（Text 模式，字体内部会自行开启纹理/混合）
        if (ringMode == "Text") {
            glEnable(GL_TEXTURE_2D)
            for (ring in onyxCircles) {
                val color = onyxColor(ring, now, lifeMs) ?: continue
                renderOnyxRingText(ring, rm.renderPosX, rm.renderPosY, rm.renderPosZ, color, now)
            }
        }

        GlStateManager.color(1f, 1f, 1f, 1f)
        glLineWidth(lastLineWidth)
        GlStateManager.enableTexture2D()
        if (cullEnabled) GlStateManager.enableCull()
        GlStateManager.depthMask(true)
        GlStateManager.disableBlend()
        if (lightingEnabled) GlStateManager.enableLighting() else GlStateManager.disableLighting()
        glPopMatrix()
    }

    // Onyx 圆环颜色：Theme 用主题色、Custom 用 outerColor，alpha 随剩余生命衰减并乘 Opacity
    private fun onyxColor(ring: OnyxRing, now: Long, lifeMs: Long): Color? {
        val remain = 1f - ((now - ring.time).toFloat() / lifeMs).coerceIn(0f, 1f)
        if (remain <= 0f) return null
        val base = if (colorMode == "Theme") ClientThemesUtils.getColor() else outerColor.selectedColor()
        val alpha = (base.alpha * remain * (onyxOpacity / 100f)).toInt().coerceIn(0, 255)
        if (alpha <= 0) return null
        return Color(base.red, base.green, base.blue, alpha)
    }

    // Onyx 细环线：固定 Radius，贴地 y+0.01
    private fun renderOnyxRingLine(pos: Vec3, renderX: Double, renderY: Double, renderZ: Double, color: Color) {
        glColor4f(color.red / 255f, color.green / 255f, color.blue / 255f, color.alpha / 255f)
        worldRenderer.begin(GL_LINE_LOOP, DefaultVertexFormats.POSITION)
        for (i in 0 until ONYX_RING_STEPS) {
            val angle = 2.0 * PI * i / ONYX_RING_STEPS
            worldRenderer.pos(
                pos.xCoord + cos(angle) * onyxRadius - renderX,
                pos.yCoord + 0.01 - renderY,
                pos.zCoord + sin(angle) * onyxRadius - renderZ
            ).endVertex()
        }
        tessellator.draw()
    }

    // Onyx 环形文字：文字平躺地面（绕 Y 转向切线后绕 X 转 90°），整体随时间绕 Y 轴旋转，
    // 沿圆周按周长/字宽重复铺满（drawString 每次一个绘制调用，铺满数量封顶 64 防止短字符串炸帧）。
    private fun renderOnyxRingText(ring: OnyxRing, renderX: Double, renderY: Double, renderZ: Double, color: Color, now: Long) {
        val text = ringText.trim()
        if (text.isEmpty() || onyxRadius <= 0f) return
        val font = Fonts.fontSemibold35
        val scale = (textSize / font.FONT_HEIGHT).toDouble()
        val widthBlocks = font.getStringWidth(text) * scale
        if (widthBlocks <= 0.0) return

        val circumference = 2.0 * PI * onyxRadius
        val copies = max(1, round(circumference / widthBlocks).toInt()).coerceAtMost(64)
        val stepAngle = 360.0 / copies
        val spin = spinSpeed * ((now - ring.time) / 1000.0)
        val fontColor = (color.alpha shl 24) or (color.rgb and 0xFFFFFF)

        GlStateManager.pushMatrix()
        GlStateManager.translate(ring.pos.xCoord - renderX, ring.pos.yCoord + 0.01 - renderY, ring.pos.zCoord - renderZ)
        for (i in 0 until copies) {
            GlStateManager.pushMatrix()
            GlStateManager.rotate((spin + i * stepAngle).toFloat(), 0f, 1f, 0f)
            GlStateManager.translate(onyxRadius.toDouble(), 0.0, 0.0)
            GlStateManager.rotate(90f, 0f, 1f, 0f)
            GlStateManager.rotate(-90f, 1f, 0f, 0f)
            GlStateManager.scale(scale, scale, scale)
            font.drawString(text, -font.getStringWidth(text) / 2f, -font.FONT_HEIGHT / 2f, fontColor)
            GlStateManager.popMatrix()
        }
        GlStateManager.popMatrix()
    }

    private fun animateColor(baseColor: Color, progress: Float): Color {
        val color = baseColor.withAlpha((baseColor.alpha * (1 - progress)).toInt().coerceIn(0, 255))
        if (hueOffsetAnim == 0) {
            return color
        }
        return shiftHue(color, (hueOffsetAnim * progress).toInt())
    }
    private fun calculateEntityPosition(entity: Entity): Vec3 {
        val partialTicks = mc.timer.renderPartialTicks
        val dx = entity.posX - entity.lastTickPosX
        val dy = entity.posY - entity.lastTickPosY
        val dz = entity.posZ - entity.lastTickPosZ
        return Vec3(
            entity.lastTickPosX + dx * partialTicks + dx * 2.0,
            entity.lastTickPosY + dy * partialTicks,
            entity.lastTickPosZ + dz * partialTicks + dz * 2.0
        )
    }
    data class JumpData(val pos: Vec3, val endTime: Int)

    // Onyx 圆环：世界坐标 + 创建时间戳（毫秒）
    data class OnyxRing(val pos: Vec3, val time: Long)
}
