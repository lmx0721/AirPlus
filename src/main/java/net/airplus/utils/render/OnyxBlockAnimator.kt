/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.render

import net.airplus.utils.extensions.renderPos
import net.airplus.utils.render.RenderUtils.drawFilledBox
import net.airplus.utils.render.RenderUtils.drawSelectionBoundingBox
import net.airplus.utils.render.RenderUtils.enableGlCap
import net.airplus.utils.render.RenderUtils.disableGlCap
import net.airplus.utils.render.RenderUtils.glColor
import net.airplus.utils.render.RenderUtils.resetCaps
import net.minecraft.util.AxisAlignedBB
import net.minecraft.util.BlockPos
import org.lwjgl.opengl.GL11.glBlendFunc
import org.lwjgl.opengl.GL11.glDepthMask
import org.lwjgl.opengl.GL11.glLineWidth
import org.lwjgl.opengl.GL11.GL_BLEND
import org.lwjgl.opengl.GL11.GL_DEPTH_TEST
import org.lwjgl.opengl.GL11.GL_LINE_SMOOTH
import org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA
import org.lwjgl.opengl.GL11.GL_SRC_ALPHA
import org.lwjgl.opengl.GL11.GL_TEXTURE_2D
import java.awt.Color

/**
 * Onyx 风格的方块高亮动画器（动画状态机迁移自 BlockOverlay 的 Onyx 实现，
 * 供 BlockOverlay / Scaffold Mark / ChestAura / Nuker / Fucker 复用）：
 *
 * - 出现：Fade 纯透明淡入；Pop 透明 + 从 62% 缩放弹入；Grow 从中心 0 长到 100%；
 * - 消失：按 Fade 时长反向淡出，期间继续渲染最后一个盒子；
 * - 换目标：相邻方块时 6 个 AABB 边界用 Move 时长平滑滑向新盒，瞬移（> 4 格）直接对齐；
 * - Padding：盒子按占一个方块的百分比外扩。
 *
 * 每帧传入注视目标（含其 SelectedBoundingBox，可为 null 表示离开），返回当前应渲染的
 * [Frame]（世界坐标盒 + 可见度 0..1），返回 null 表示动画播完、无需渲染。
 */
class OnyxBlockAnimator {

    class Settings(
        // 出现方式：Fade / Pop / Grow
        val appearance: String,
        // 出现时长（毫秒）
        val appearTime: Float,
        // 淡出时长（毫秒）
        val fadeTime: Float,
        // 滑向相邻方块时长（毫秒）
        val moveTime: Float,
        // 盒子外扩（占一个方块的百分比）
        val padding: Float,
    )

    class Frame(val box: AxisAlignedBB, val visibility: Float)

    // 正在渲染（含淡出中）的方块；renderBlock == null 表示完全隐藏
    private var renderBlock: BlockPos? = null
    // 是否处于"注视中"（用于区分出现/淡出阶段）
    private var rendering = false
    // 插值盒的基准最小角（滑动时固定，边界偏移向新盒补间）
    private var baseMinX = 0.0
    private var baseMinY = 0.0
    private var baseMinZ = 0.0
    // 6 个 AABB 边界偏移补间（相对 base：minX, minY, minZ, maxX, maxY, maxZ）
    private val edges = Array(6) { Tween(0F, ONYX_MOVE_EASE) }
    // 出现/消失透明系数 0..1
    private val visibility = Tween(0F, ONYX_APPEAR_EASE)
    // Pop/Grow 的尺寸系数（appearance 起点值 → 1）
    private val appearScale = Tween(1F, ONYX_APPEAR_EASE)

    private fun appearanceStart(settings: Settings): Float = when (settings.appearance) {
        "Pop" -> 0.62F
        "Grow" -> 0F
        else -> 1F
    }

    fun update(target: BlockPos?, targetAABB: AxisAlignedBB?, settings: Settings): Frame? {
        if (target == null || targetAABB == null) {
            // 注视离开：按 Fade 时长反向淡出（期间继续渲染最后一个盒子）
            if (rendering) {
                rendering = false
                visibility.tweenTo(0F, settings.fadeTime.toLong(), ONYX_DISAPPEAR_EASE)
                appearScale.tweenTo(appearanceStart(settings), settings.fadeTime.toLong(), ONYX_DISAPPEAR_EASE)
            }
        } else {
            handleTarget(target, targetAABB, settings)
        }

        edges.forEach { it.update() }
        visibility.update()
        appearScale.update()

        val vis = visibility.value
        if (!rendering && vis <= 0.004F) {
            // 淡出完成，停止渲染
            renderBlock = null
            return null
        }

        return Frame(buildInterpolatedBox(settings.padding), vis)
    }

    /**
     * 注视目标处理：首次出现/瞬移（距离 > 4 格）直接对齐；相邻方块则让 6 个边界
     * 用 Move 时长平滑滑向新 AABB（同 Onyx handleBlockPos）。
     */
    private fun handleTarget(pos: BlockPos, aabb: AxisAlignedBB, settings: Settings) {
        val first = renderBlock == null
        val teleport = !first && pos.distanceSq(
            renderBlock!!.x.toDouble(), renderBlock!!.y.toDouble(), renderBlock!!.z.toDouble()
        ) > 16.0

        renderBlock = pos
        rendering = true

        if (!first && !teleport) {
            edges[0].tweenTo((aabb.minX - baseMinX).toFloat(), settings.moveTime.toLong())
            edges[1].tweenTo((aabb.minY - baseMinY).toFloat(), settings.moveTime.toLong())
            edges[2].tweenTo((aabb.minZ - baseMinZ).toFloat(), settings.moveTime.toLong())
            edges[3].tweenTo((aabb.maxX - baseMinX).toFloat(), settings.moveTime.toLong())
            edges[4].tweenTo((aabb.maxY - baseMinY).toFloat(), settings.moveTime.toLong())
            edges[5].tweenTo((aabb.maxZ - baseMinZ).toFloat(), settings.moveTime.toLong())
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
            appearScale.snap(appearanceStart(settings))
        }
        // 出现动画：透明系数与尺寸系数向 1 补间
        visibility.tweenTo(1F, settings.appearTime.toLong())
        appearScale.tweenTo(1F, settings.appearTime.toLong())
    }

    // 由基准角 + 补间边界重建盒子，再按 appearScale 向中心缩放（Grow/Pop）、按 Padding 外扩
    private fun buildInterpolatedBox(padding: Float): AxisAlignedBB {
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

    fun reset() {
        renderBlock = null
        rendering = false
        visibility.snap(0F)
        appearScale.snap(1F)
        edges.forEach { it.snap(0F) }
    }
}

/**
 * 标量补间器（对应 Onyx 的 anim.Cls）：记录起点/终点/起始时间/时长，按给定缓动插值。
 */
class Tween(initial: Float, private var ease: (Double) -> Double) {
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

// ---- Onyx 同款缓动（Util.IFACE2 / IFACE7 / IFACE）：Move 用 (0.2,0,0,1)，出现用 (0.05,0.7,0.1,1)，淡出用 (0.3,0,0.8,0.15) ----
internal val ONYX_MOVE_EASE = bezierEase(0.2, 0.0, 0.0, 1.0)
internal val ONYX_APPEAR_EASE = bezierEase(0.05, 0.7, 0.1, 1.0)
internal val ONYX_DISAPPEAR_EASE = bezierEase(0.3, 0.0, 0.8, 0.15)
internal val ONYX_LINEAR_EASE: (Double) -> Double = { it }

/**
 * 三次贝塞尔缓动工厂（同 Onyx Util.getIfaceForDouble）：进度 0..1 → 缓动值 0..1。
 */
fun bezierEase(x1: Double, y1: Double, x2: Double, y2: Double): (Double) -> Double {
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

/**
 * 绘制一个 Onyx 动画帧：半透明填充 + 描边（盒为世界坐标，按 renderPos 转相对坐标）。
 * [color] 的 alpha 会按 [visibility]（动画可见度）进一步缩放。
 */
fun drawOnyxFrame(box: AxisAlignedBB, visibility: Float, color: Color, lineWidth: Float = 2F) {
    val renderPos = net.airplus.utils.client.MinecraftInstance.mc.renderManager.renderPos
    val rel = box.offset(-renderPos.xCoord, -renderPos.yCoord, -renderPos.zCoord)
    val c = Color(color.red, color.green, color.blue, (color.alpha * visibility).toInt().coerceIn(0, 255))

    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
    enableGlCap(GL_BLEND)
    disableGlCap(GL_TEXTURE_2D, GL_DEPTH_TEST)
    glDepthMask(false)
    glColor(c)
    drawFilledBox(rel)

    glLineWidth(lineWidth)
    enableGlCap(GL_LINE_SMOOTH)
    glColor(c)
    drawSelectionBoundingBox(rel)

    resetCaps()
    glDepthMask(true)
}
