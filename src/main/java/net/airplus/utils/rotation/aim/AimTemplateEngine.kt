/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.rotation.aim

import net.airplus.lang.translation
import net.airplus.utils.client.chat
import net.airplus.utils.kotlin.RandomUtils.nextFloat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 路线 A：模板库引擎。从 library.json 检索与当前上下文最相似的真实旋转段，
 * 把其归一化累计位移曲线按需拉伸后逐 tick 输出增量。
 *
 * 返回 null 表示无法生成（数据缺失 / 剩余角度过小），调用方回退 Default 管线。
 */
object AimTemplateEngine {

    private const val K = 5
    private const val MIN_DIFF_TO_START = 2f
    private const val DIRECTION_RESTART_ANGLE = 30f
    private const val EPS = 0.1f

    @Volatile
    private var templates: List<Segment>? = null

    @Volatile
    private var lastModified = Long.MIN_VALUE

    @Volatile
    private var warnedMissing = false

    fun loadIfChanged(force: Boolean = false) {
        val file = AimDatasetStore.libraryFile
        val mtime = if (file.isFile) file.lastModified() else 0L
        if (force || mtime != lastModified) {
            lastModified = mtime
            templates = AimDatasetStore.readLibrary()
            warnedMissing = false
        }
    }

    /**
     * 进行中的一次旋转：起始方向 + 总角度差 + 时长 + 归一化累计曲线。
     */
    private class Run(
        val neededYaw: Float,
        val neededPitch: Float,
        val totalDiff: Float,
        val duration: Int,
        val curve: FloatArray,
    ) {
        var tick = 0
    }

    private var run: Run? = null

    /**
     * @param neededYaw / neededPitch 当前指向目标所需的剩余 yaw/pitch 角度差（每 tick 重算，保证收敛）
     * @param velYaw / velPitch 上一 tick 的实际旋转增量（当前角速度）
     * @param moveSpeed 玩家水平速度
     * @param entityDist 最近实体距离
     */
    fun sample(
        neededYaw: Float, neededPitch: Float,
        velYaw: Float, velPitch: Float,
        moveSpeed: Float, entityDist: Float,
    ): Pair<Float, Float>? {
        loadIfChanged()
        val libs = templates

        if (libs.isNullOrEmpty()) {
            if (!warnedMissing) {
                warnedMissing = true
                chat(translation("aim.missingData"))
            }
            return null
        }

        val neededDiff = hypot(neededYaw, neededPitch)

        if (neededDiff < MIN_DIFF_TO_START) {
            run = null
            return null
        }

        val active = run?.takeIf {
            // 方向大幅变化（目标切换）时重开一段
            angleBetween(it.neededYaw, it.neededPitch, neededYaw, neededPitch) < DIRECTION_RESTART_ANGLE
        } ?: selectAndStart(libs, neededYaw, neededPitch, neededDiff, hypot(velYaw, velPitch), moveSpeed) ?: return null

        run = active

        val fraction = curveAt(active.curve, active.duration, active.tick + 1) - curveAt(active.curve, active.duration, active.tick)
        var yawDelta = neededYaw / neededDiff * (fraction * active.totalDiff)
        var pitchDelta = neededPitch / neededDiff * (fraction * active.totalDiff)

        // 首 tick 与当前角速度混合，避免拼接点速度跳变
        if (active.tick == 0) {
            yawDelta = yawDelta * 0.5f + velYaw * 0.5f
            pitchDelta = pitchDelta * 0.5f + velPitch * 0.5f
        }

        active.tick++
        if (active.tick >= active.duration) {
            run = null
        }

        return yawDelta to pitchDelta
    }

    /**
     * k-NN 检索：对数尺度距离（角度差量级 + 起始速度量级 + 移动状态），加权随机取一条。
     */
    private fun selectAndStart(
        libs: List<Segment>, neededYaw: Float, neededPitch: Float,
        neededDiff: Float, speed: Float, moveSpeed: Float,
    ): Run? {
        val candidates = libs.asSequence()
            .filter { it.totalDiff > 0f && it.duration >= 3 && it.gcd > 0f }
            .map { template ->
                val d = abs(ln(neededDiff / template.totalDiff)) +
                    0.5f * abs(ln((speed + EPS) / (template.startSpeed + EPS))) +
                    0.3f * abs(moveSpeed - template.moveSpeed)
                d to template
            }
            .sortedBy { it.first }
            .take(K)
            .toList()

        if (candidates.isEmpty()) return null

        // 权重 1/(d+0.1) 的加权随机
        val totalWeight = candidates.sumOf { (1f / (it.first + 0.1f)).toDouble() }
        var roll = nextFloat(0f, totalWeight.toFloat())
        var chosen = candidates.last().second
        for ((d, template) in candidates) {
            roll -= 1f / (d + 0.1f)
            if (roll <= 0f) {
                chosen = template
                break
            }
        }

        val duration = (chosen.duration * neededDiff / chosen.totalDiff).roundToInt().coerceIn(3, 40)

        return Run(
            neededYaw, neededPitch, neededDiff, duration, buildCurve(chosen)
        )
    }

    /**
     * 模板的归一化累计位移曲线 s(t)∈[0,1]，长度 = duration + 1。
     */
    private fun buildCurve(template: Segment): FloatArray {
        val curve = FloatArray(template.duration + 1)
        var cumulative = 0f
        for (i in template.yawDeltas.indices) {
            cumulative += hypot(template.yawDeltas[i], template.pitchDeltas[i])
            curve[i + 1] = if (template.totalDiff > 0f) cumulative / template.totalDiff else 1f
        }
        curve[template.duration] = 1f
        return curve
    }

    /**
     * 归一化时间轴 [0,1] 上的曲线取值（模板点之间线性插值）。
     */
    private fun curveAt(curve: FloatArray, duration: Int, tick: Int): Float {
        val t = (tick.toFloat() / duration).coerceIn(0f, 1f) * (curve.size - 1)
        val index = t.toInt()
        val frac = t - index
        val next = (index + 1).coerceAtMost(curve.size - 1)
        return curve[index] * (1f - frac) + curve[next] * frac
    }

    private fun angleBetween(yaw1: Float, pitch1: Float, yaw2: Float, pitch2: Float): Float {
        val dot = yaw1 * yaw2 + pitch1 * pitch2
        val len1 = hypot(yaw1, pitch1)
        val len2 = hypot(yaw2, pitch2)
        if (len1 <= 0f || len2 <= 0f) return 180f
        val cos = (dot / (len1 * len2)).coerceIn(-1f, 1f)
        // kotlin.math 没有浮点 acos 的度数封装，手动转换
        return Math.toDegrees(kotlin.math.acos(cos.toDouble())).toFloat()
    }
}
