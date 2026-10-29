/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.human

import net.airplus.utils.extensions.random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 堆叠拟人算法（HumanStack）：把「真实人类行为」拆成多层相互独立的噪声/行为层，
 * 每层维护自己的跨 tick 状态，最终把各层输出**堆叠**（乘性/加性叠加）成单一输出。
 *
 * 与单层随机（原 randomClickDelay / Legacy 限幅）的区别：单层随机在统计特征上
 * 是白噪声 —— 间隔直方图均匀、无自相关、无慢趋势；真实人类的点击序列
 * 同时具备 慢趋势、爆发自相关、疲劳曲线、偶发事件 等多层结构，任何一层被
 * 反作弊统计出来都会露馅。堆叠模型逐层复现这些结构，各层参数可独立调整。
 */

/**
 * 堆叠点击模型的用户参数（由 AutoClicker 每次点击时按设置面板组装）。
 */
class StackedClickParams(
    /** 慢漂移层幅度：CPS 整体上下漂移的最大比例 [0..0.4]。 */
    val driftAmplitude: Float,
    /** 连点惯性：相邻间隔的自相关强度 [0..0.9]，越高越容易连成一串快/慢点击。 */
    val burstInertia: Float,
    /** 爆发概率：每次点击有概率把本串节奏放大（连点爆发）[0..100]。 */
    val burstChance: Float,
    /** 疲劳幅度：持续点击后变慢的最大比例 [0..0.3]。 */
    val fatigueAmplitude: Float,
    /** 单次疲劳：每次点击积累的疲劳量 [0..0.02]。 */
    val fatiguePerClick: Float,
    /** 疲劳恢复：停止点击后疲劳减半所需毫秒数 [50..2000]。 */
    val fatigueRecoveryMs: Float,
    /** 双击概率：手指打滑连续触发两次的概率 [0..100]。 */
    val doubleClickChance: Float,
    /** 卡顿概率：注意力分散出现一次明显长间隔的概率 [0..100]。 */
    val hesitationChance: Float,
)

/**
 * 堆叠点击生成器：跨点击维护 漂移/惯性/疲劳 三层状态，输出下一次点击的延迟。
 * 左右键各自持有独立实例（两根手指的节奏本来就不同步）。
 */
class StackedClickGenerator {

    /** 慢漂移层：AR(1) 随机游走值 [-1..1]。 */
    private var driftWalk = 0f

    /** 连点惯性层：上一间隔相对基准的偏差（自相关记忆）。 */
    private var burstDev = 0f

    /** 疲劳层：累积疲劳 [0..1]。 */
    private var fatigue = 0f

    private var lastClickTime = 0L

    fun reset() {
        driftWalk = 0f
        burstDev = 0f
        fatigue = 0f
        lastClickTime = 0L
    }

    /**
     * 生成下一次点击延迟（毫秒）。[minCps] / [maxCps] 为基准频率区间。
     */
    fun nextDelay(minCps: Int, maxCps: Int, p: StackedClickParams): Int {
        val now = System.currentTimeMillis()

        // —— 基准频率层：CPS 区间换算成延迟区间，两次均匀采样取均值（三角分布），
        // 中心聚集、边缘稀少，比纯均匀分布更接近真人测得的 CPS 直方图。
        val lo = 1000.0 / maxCps.coerceIn(1, 50).toDouble()
        val hi = 1000.0 / minCps.coerceIn(1, 50).toDouble()
        val base = (lo + Random.nextDouble() * (hi - lo) + lo + Random.nextDouble() * (hi - lo)) / 2.0

        // —— 慢漂移层：AR(1) 随机游走（秒级周期的分形噪声），让 CPS 整体起伏
        // 而不是每一下都独立抖 —— 真人的手感和状态是连续变化的。
        driftWalk = (driftWalk * 0.985f + gaussian() * 0.06f).coerceIn(-1f, 1f)

        // —— 连点惯性层：相邻间隔自相关（连成一串快点/慢点）。
        var dev = burstDev * p.burstInertia + gaussian() * 0.12f * (1f - p.burstInertia * 0.5f)
        if (Random.nextFloat() * 100f < p.burstChance) dev *= 1.8f
        burstDev = dev.coerceIn(-0.6f, 0.6f)

        // —— 疲劳层：点击积累疲劳、停手按指数恢复（半衰期 [fatigueRecoveryMs]）。
        if (lastClickTime > 0L) {
            val elapsed = (now - lastClickTime).toFloat().coerceAtLeast(0f)
            fatigue *= exp(-elapsed / p.fatigueRecoveryMs.coerceAtLeast(50f))
        }
        fatigue = (fatigue + p.fatiguePerClick).coerceIn(0f, 1f)
        lastClickTime = now

        // —— 事件层：双击（手指打滑）/ 卡顿（走神停顿），把极端间隔重新注回序列。
        var delay = base * (1f + driftWalk * p.driftAmplitude) * (1f + burstDev) * (1f + fatigue * p.fatigueAmplitude)

        when {
            Random.nextFloat() * 100f < p.doubleClickChance -> delay *= 0.4f
            Random.nextFloat() * 100f < p.hesitationChance -> delay *= (1.6f..2.8f).random()
        }

        return delay.roundToInt().coerceIn(20, 2000)
    }

    /** Box-Muller 标准正态采样。 */
    private fun gaussian(): Float {
        val u1 = Random.nextDouble().coerceAtLeast(1e-9)
        val u2 = Random.nextDouble()
        return (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)).toFloat()
    }
}
