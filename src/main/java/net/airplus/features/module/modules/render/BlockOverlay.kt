/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.render

import net.airplus.event.Render2DEvent
import net.airplus.event.Render3DEvent
import net.airplus.event.WorldEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.ui.font.Fonts
import net.airplus.utils.block.block
import net.airplus.utils.extensions.*
import net.airplus.utils.render.RenderUtils.drawBorderedRect
import net.airplus.utils.render.RenderUtils.drawFilledBox
import net.airplus.utils.render.RenderUtils.drawSelectionBoundingBox
import net.airplus.utils.render.RenderUtils.glColor
import net.minecraft.block.Block
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager.resetColor
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.init.Blocks
import net.minecraft.util.AxisAlignedBB
import net.minecraft.util.BlockPos
import net.minecraft.util.Vec3
import org.lwjgl.opengl.GL11.*
import java.awt.Color
import kotlin.math.min

object BlockOverlay : Module("BlockOverlay", Category.RENDER, gameDetecting = false) {
    private val mode by choices("Mode", arrayOf("Box", "OtherBox", "Outline"), "Box")
    private val depth3D by boolean("Depth3D", false)
    private val thickness by float("Thickness", 2F, 1F..5F)

    val info by boolean("Info", false)

    private val color by color("Color", Color(68, 117, 255, 100))

    // ---- Onyx 动画（迁移自 Onyx BlockOverlayModule）----
    private val animation by boolean("Animation", true)

    // 出现方式：Fade 纯透明；Pop 透明 + 从 62% 缩放弹入；Grow 从中心 0 长到 100%
    private val appearance by choices("Appearance", arrayOf("Fade", "Pop", "Grow"), "Fade") { animation }
    private val appearTime by float("Appear", 140F, 0F..600F, "ms") { animation }
    private val fadeTime by float("Fade", 180F, 0F..600F, "ms") { animation }
    // 滑向相邻方块时长
    private val moveTime by float("Move", 110F, 0F..600F, "ms") { animation }

    // 挖掘进度填充（Onyx 的 "Grow from centre" 进度模式未迁移，只做整面填充）
    private val breakProgress by boolean("BreakProgress", true)
    private val progressDirection by choices("Direction", arrayOf("Top to bottom", "Bottom to top"), "Top to bottom") { breakProgress }
    private val progressOpacity by float("ProgressOpacity", 45F, 1F..100F, "%") { breakProgress }
    // 进度填充渐变第二色：按 y 在 color 与 SecondColor 之间渐变（简化自 Onyx 的 glint 渐变 shader）
    private val secondColor by color("SecondColor", -7055617) { breakProgress }

    // 盒子外扩（占一个方块的百分比）
    private val padding by float("Padding", 0.6F, 0F..5F, "%")
    private val throughWalls by boolean("ThroughWalls", false)

    val currentBlock: BlockPos?
        get() {
            val world = mc.theWorld ?: return null
            val blockPos = mc.objectMouseOver?.blockPos ?: return null

            if (blockPos.block !in arrayOf(
                    Blocks.air,
                    Blocks.water,
                    Blocks.lava
                ) && world.worldBorder.contains(blockPos)
            )
                return blockPos

            return null
        }

    // ---- Onyx 同款缓动（Util.IFACE2 / IFACE7 / IFACE）：Move 用 (0.2,0,0,1)，出现用 (0.05,0.7,0.1,1)，淡出用 (0.3,0,0.8,0.15) ----
    private val MOVE_EASE = bezier(0.2, 0.0, 0.0, 1.0)
    private val APPEAR_EASE = bezier(0.05, 0.7, 0.1, 1.0)
    private val DISAPPEAR_EASE = bezier(0.3, 0.0, 0.8, 0.15)
    private val LINEAR_EASE: (Double) -> Double = { it }

    // ---- 动画状态（世界坐标，对应 Onyx 的 anim.Cls 补间器）----
    // 正在渲染（含淡出中）的方块；renderBlock == null 表示完全隐藏
    private var renderBlock: BlockPos? = null
    // 是否处于"注视中"（用于区分出现/淡出阶段）
    private var rendering = false
    // 插值盒的基准最小角（滑动时固定，边界偏移向新盒补间）
    private var baseMinX = 0.0
    private var baseMinY = 0.0
    private var baseMinZ = 0.0
    // 6 个 AABB 边界偏移补间（相对 base：minX, minY, minZ, maxX, maxY, maxZ）
    private val edges = Array(6) { Tween(0F, MOVE_EASE) }
    // 出现/消失透明系数 0..1
    private val visibility = Tween(0F, APPEAR_EASE)
    // Pop/Grow 的尺寸系数（appearance 起点值 → 1）
    private val appearScale = Tween(1F, APPEAR_EASE)
    // 挖掘进度（朝当前 damage 平滑）
    private val progressTween = Tween(0F, LINEAR_EASE)

    private val appearanceStart: Float
        get() = when (appearance) {
            "Pop" -> 0.62F
            "Grow" -> 0F
            else -> 1F
        }

    private val tessellator = Tessellator.getInstance()
    private val worldRenderer = tessellator.worldRenderer

    val onRender3D = handler<Render3DEvent> {
        val targetPos = currentBlock
        val targetAABB = targetPos?.let { getSelectedAABB(it) }

        if (!animation) {
            // 关闭动画：保持原有即时渲染行为，并每帧重置动画状态（便于重新开启时从头出现）
            resetAnimationState()
            val blockPos = targetPos ?: return@handler
            val block = blockPos.block ?: return@handler
            val thePlayer = mc.thePlayer ?: return@handler

            block.setBlockBoundsBasedOnState(mc.theWorld, blockPos)
            val pos = thePlayer.interpolatedPosition(thePlayer.lastTickPos)
            val axisAlignedBB = block.getSelectedBoundingBox(mc.theWorld, blockPos).expand(0.002, 0.002, 0.002)

            render(axisAlignedBB, pos, 1F, 0F)
            return@handler
        }

        // ---- Onyx 动画路径 ----
        if (targetAABB == null || targetPos == null) {
            // 注视离开：按 Fade 时长反向淡出（期间继续渲染最后一个盒子）
            if (rendering) {
                rendering = false
                visibility.tweenTo(0F, fadeTime.toLong(), DISAPPEAR_EASE)
                appearScale.tweenTo(appearanceStart, fadeTime.toLong(), DISAPPEAR_EASE)
                progressTween.snap(0F)
            }
        } else {
            handleTarget(targetPos, targetAABB)
            // 挖掘进度朝当前 damage 平滑（Onyx 用 70ms 线性）
            progressTween.tweenTo(curBlockDamage(), 70L)
        }

        edges.forEach { it.update() }
        visibility.update()
        appearScale.update()
        progressTween.update()

        val vis = visibility.value
        if (!rendering && vis <= 0.004F) {
            // 淡出完成，停止渲染
            renderBlock = null
            return@handler
        }
        val thePlayer = mc.thePlayer ?: return@handler
        val pos = thePlayer.interpolatedPosition(thePlayer.lastTickPos)

        render(buildInterpolatedBox(), pos, vis, progressTween.value)
    }

    /**
     * 注视目标处理：首次出现/瞬移（距离 > 4 格）直接对齐；相邻方块则让 6 个边界
     * 用 Move 时长平滑滑向新 AABB（同 Onyx handleBlockPos）。
     */
    private fun handleTarget(pos: BlockPos, aabb: AxisAlignedBB) {
        val first = renderBlock == null
        val teleport = !first && pos.distanceSq(
            renderBlock!!.x.toDouble(), renderBlock!!.y.toDouble(), renderBlock!!.z.toDouble()
        ) > 16.0

        if (pos != renderBlock) {
            // 换目标：挖掘进度归零
            progressTween.snap(0F)
        }

        renderBlock = pos
        rendering = true

        if (!first && !teleport) {
            edges[0].tweenTo((aabb.minX - baseMinX).toFloat(), moveTime.toLong())
            edges[1].tweenTo((aabb.minY - baseMinY).toFloat(), moveTime.toLong())
            edges[2].tweenTo((aabb.minZ - baseMinZ).toFloat(), moveTime.toLong())
            edges[3].tweenTo((aabb.maxX - baseMinX).toFloat(), moveTime.toLong())
            edges[4].tweenTo((aabb.maxY - baseMinY).toFloat(), moveTime.toLong())
            edges[5].tweenTo((aabb.maxZ - baseMinZ).toFloat(), moveTime.toLong())
        } else {
            baseMinX = aabb.minX
            baseMinY = aabb.minY
            baseMinZ = aabb.minZ
            edges[0].snap(0F)
            edges[1].snap(0F)
            edges[2].snap(0F)
            edges[3].snap((aabb.maxX - aabb.minX).toFloat())
            edges[4].snap((aabb.maxY - aabb.minY).toFloat())
            edges[5].snap((aabb.maxZ - aabb.minZ).toFloat())
        }

        if (first) {
            visibility.snap(0F)
            appearScale.snap(appearanceStart)
        }
        // 出现动画：透明系数与尺寸系数向 1 补间
        visibility.tweenTo(1F, appearTime.toLong())
        appearScale.tweenTo(1F, appearTime.toLong())
    }

    // 由基准角 + 补间边界重建盒子，再按 appearScale 向中心缩放（Grow/Pop）、按 Padding 外扩（同 Onyx getAxisAlignedBB4）
    private fun buildInterpolatedBox(): AxisAlignedBB {
        val minX = baseMinX + edges[0].value
        val minY = baseMinY + edges[1].value
        val minZ = baseMinZ + edges[2].value
        val maxX = baseMinX + edges[3].value
        val maxY = baseMinY + edges[4].value
        val maxZ = baseMinZ + edges[5].value
        val cx = (minX + maxX) / 2.0
        val cy = (minY + maxY) / 2.0
        val cz = (minZ + maxZ) / 2.0
        val scale = appearScale.value.toDouble()
        val pad = (padding / 100F).toDouble()
        return AxisAlignedBB(
            cx + (minX - cx) * scale - pad,
            cy + (minY - cy) * scale - pad,
            cz + (minZ - cz) * scale - pad,
            cx + (maxX - cx) * scale + pad,
            cy + (maxY - cy) * scale + pad,
            cz + (maxZ - cz) * scale + pad
        )
    }

    private fun getSelectedAABB(blockPos: BlockPos): AxisAlignedBB? {
        val world = mc.theWorld ?: return null
        val block = blockPos.block ?: return null
        block.setBlockBoundsBasedOnState(world, blockPos)
        return block.getSelectedBoundingBox(world, blockPos)
    }

    // 当前挖掘进度（只在挖方块时非 0）
    private fun curBlockDamage(): Float {
        val controller = mc.playerController ?: return 0F
        return if (controller.isHittingBlock) controller.curBlockDamageMP.coerceIn(0F, 1F) else 0F
    }

    private fun render(box: AxisAlignedBB, offset: Vec3, vis: Float, progress: Float) {
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glEnable(GL_LINE_SMOOTH)
        glHint(GL_LINE_SMOOTH_HINT, GL_NICEST)
        val outlineColor = Color(color.red, color.green, color.blue, (color.alpha * vis).toInt().coerceIn(0, 255))
        glColor(outlineColor)
        glLineWidth(thickness)
        glDisable(GL_TEXTURE_2D)
        if (depth3D || throughWalls) glDisable(GL_DEPTH_TEST)
        glDepthMask(false)

        val rel = box.offset(-offset)

        if (mode.lowercase() in arrayOf("box", "otherbox"))
            drawFilledBox(rel)

        // 挖掘进度填充
        if (breakProgress && progress > 0.001F)
            drawProgressFill(rel, vis, progress)

        if (mode.lowercase() in arrayOf("box", "outline")) {
            // 渐变填充会覆盖当前颜色，画描边前重设
            glColor(outlineColor)
            drawSelectionBoundingBox(rel)
        }

        if (depth3D || throughWalls) glEnable(GL_DEPTH_TEST)
        glEnable(GL_TEXTURE_2D)
        glDisable(GL_BLEND)
        glDisable(GL_LINE_SMOOTH)
        glDepthMask(true)
        resetColor()
    }

    /**
     * 挖掘进度填充：随 mc.playerController.curBlockDamage 在盒内画填充面，
     * Top to bottom 从顶往下 / Bottom to top 从底往上（Onyx 的 "Grow from centre" 进度模式不做）。
     * 颜色按 y 在 color（底）与 SecondColor（顶）之间渐变（简化自 Onyx 的 glint 渐变 shader），
     * 填充 alpha = 颜色 alpha × 出现/消失进度 × ProgressOpacity。
     */
    private fun drawProgressFill(box: AxisAlignedBB, vis: Float, progress: Float) {
        val alpha = (color.alpha * vis * (progressOpacity / 100F)).toInt().coerceIn(0, 255)
        if (alpha <= 0) return

        val height = (box.maxY - box.minY) * progress
        val raw = if (progressDirection == "Bottom to top")
            AxisAlignedBB(box.minX, box.minY, box.minZ, box.maxX, box.minY + height, box.maxZ)
        else
            AxisAlignedBB(box.minX, box.maxY - height, box.minZ, box.maxX, box.maxY, box.maxZ)
        // 每个面稍微内缩，避免与外框 z-fighting（Onyx 的 0.0015 间隙）
        val gx = min(0.0015, (raw.maxX - raw.minX) / 2.0)
        val gy = min(0.0015, (raw.maxY - raw.minY) / 2.0)
        val gz = min(0.0015, (raw.maxZ - raw.minZ) / 2.0)
        val fill = AxisAlignedBB(
            raw.minX + gx, raw.minY + gy, raw.minZ + gz,
            raw.maxX - gx, raw.maxY - gy, raw.maxZ - gz
        )

        // 按 y 相对整个插值盒取渐变色（进度增长时渐变保持稳定）
        val heightInv = 1.0 / maxOf(1.0E-6, box.maxY - box.minY)
        fun vertexColor(y: Double): Int {
            val t = ((y - box.minY) * heightInv).coerceIn(0.0, 1.0)
            return Color(
                (color.red + (secondColor.red - color.red) * t).toInt().coerceIn(0, 255),
                (color.green + (secondColor.green - color.green) * t).toInt().coerceIn(0, 255),
                (color.blue + (secondColor.blue - color.blue) * t).toInt().coerceIn(0, 255)
            ).rgb
        }
        fun vertex(x: Double, y: Double, z: Double) {
            val c = vertexColor(y)
            worldRenderer.pos(x, y, z).color((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, alpha).endVertex()
        }

        worldRenderer.begin(7, DefaultVertexFormats.POSITION_COLOR)
        // 顶点顺序与 RenderUtils.drawFilledBox 一致（部分面正反各画一次，兼容背面剔除）
        vertex(fill.minX, fill.minY, fill.minZ)
        vertex(fill.minX, fill.maxY, fill.minZ)
        vertex(fill.maxX, fill.minY, fill.minZ)
        vertex(fill.maxX, fill.maxY, fill.minZ)
        vertex(fill.maxX, fill.minY, fill.maxZ)
        vertex(fill.maxX, fill.maxY, fill.maxZ)
        vertex(fill.minX, fill.minY, fill.maxZ)
        vertex(fill.minX, fill.maxY, fill.maxZ)
        vertex(fill.maxX, fill.maxY, fill.minZ)
        vertex(fill.maxX, fill.minY, fill.minZ)
        vertex(fill.minX, fill.maxY, fill.minZ)
        vertex(fill.minX, fill.minY, fill.minZ)
        vertex(fill.minX, fill.maxY, fill.maxZ)
        vertex(fill.minX, fill.minY, fill.maxZ)
        vertex(fill.maxX, fill.maxY, fill.maxZ)
        vertex(fill.maxX, fill.minY, fill.maxZ)
        vertex(fill.minX, fill.maxY, fill.minZ)
        vertex(fill.maxX, fill.maxY, fill.minZ)
        vertex(fill.maxX, fill.maxY, fill.maxZ)
        vertex(fill.minX, fill.maxY, fill.maxZ)
        vertex(fill.minX, fill.maxY, fill.minZ)
        vertex(fill.minX, fill.maxY, fill.maxZ)
        vertex(fill.maxX, fill.maxY, fill.maxZ)
        vertex(fill.maxX, fill.maxY, fill.minZ)
        vertex(fill.minX, fill.minY, fill.minZ)
        vertex(fill.maxX, fill.minY, fill.minZ)
        vertex(fill.maxX, fill.minY, fill.maxZ)
        vertex(fill.minX, fill.minY, fill.maxZ)
        vertex(fill.minX, fill.minY, fill.minZ)
        vertex(fill.minX, fill.minY, fill.maxZ)
        vertex(fill.maxX, fill.minY, fill.maxZ)
        vertex(fill.maxX, fill.minY, fill.minZ)
        tessellator.draw()
    }

    private fun resetAnimationState() {
        renderBlock = null
        rendering = false
        visibility.snap(0F)
        appearScale.snap(1F)
        progressTween.snap(0F)
        edges.forEach { it.snap(0F) }
    }

    val onRender2D = handler<Render2DEvent> {
        if (!info) return@handler

        val blockPos = currentBlock ?: return@handler
        val block = blockPos.block ?: return@handler

        val info = "${block.localizedName} §7ID: ${Block.getIdFromBlock(block)}"
        val (width, height) = ScaledResolution(mc)

        drawBorderedRect(
            width / 2 - 2F,
            height / 2 + 5F,
            width / 2 + Fonts.fontSemibold40.getStringWidth(info) + 2F,
            height / 2 + 16F,
            3F, Color.BLACK.rgb, Color.BLACK.rgb
        )

        resetColor()
        Fonts.fontSemibold40.drawString(info, width / 2f, height / 2f + 7f, Color.WHITE.rgb, false)
    }

    override fun onDisable() {
        resetAnimationState()
    }

    val onWorld = handler<WorldEvent> {
        resetAnimationState()
    }

    /**
     * 标量补间器（对应 Onyx 的 anim.Cls）：记录起点/终点/起始时间/时长，按给定缓动插值。
     */
    private class Tween(initial: Float, private var ease: (Double) -> Double) {
        var value = initial
            private set
        private var start = initial
        private var target = initial
        private var startTime = 0L
        private var duration = 0L

        // 立即对齐到目标值
        fun snap(v: Float) {
            value = v
            start = v
            target = v
            duration = 0L
        }

        // 目标没变时不重启动画（同 Onyx getCls2 的行为）；可替换缓动（淡出用另一条曲线）
        fun tweenTo(v: Float, ms: Long, newEase: (Double) -> Double = ease) {
            if (target == v) return
            ease = newEase
            start = value
            target = v
            startTime = System.currentTimeMillis()
            duration = ms.coerceAtLeast(0L)
        }

        fun update() {
            value = if (duration > 0L) {
                val progress = ((System.currentTimeMillis() - startTime).toDouble() / duration).coerceIn(0.0, 1.0)
                (start + (target - start) * ease(progress)).toFloat()
            } else {
                target
            }
        }
    }

    /**
     * 三次贝塞尔缓动工厂（同 Onyx Util.getIfaceForDouble）：进度 0..1 → 缓动值 0..1。
     */
    private fun bezier(x1: Double, y1: Double, x2: Double, y2: Double): (Double) -> Double {
        fun sample(t: Double, p1: Double, p2: Double) =
            3.0 * (1 - t) * (1 - t) * t * p1 + 3.0 * (1 - t) * t * t * p2 + t * t * t

        return { progress ->
            val p = progress.coerceIn(0.0, 1.0)
            when {
                p <= 0.0 -> 0.0
                p >= 1.0 -> 1.0
                else -> {
                    // 二分求 Bx(t) = p（CSS 三次贝塞尔保证 x 单调）
                    var low = 0.0
                    var high = 1.0
                    var t = p
                    repeat(20) {
                        val x = sample(t, x1, x2)
                        if (x < p) low = t else high = t
                        t = (low + high) / 2.0
                    }
                    sample(t, y1, y2)
                }
            }
        }
    }
}
