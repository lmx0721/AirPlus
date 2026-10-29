/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.rotation.aim

import net.airplus.utils.rotation.Rotation
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Noise3 转头算法（按反编译 Java C40N/C48V/C47TL 对齐，JS 数值仿真 harness 验证接线）。
 *
 * 组成：
 * - [C40N]：路径规划器（抛物线刹车曲线 + 半隐式 Euler 积分 + 二分调参）。
 * - [Noise3Calculator]：主入口，跨 tick 维护 actual/target 状态并输出本 tick 的绝对旋转。
 *
 * 与反编译的接线（harness 对照验证后的最终方案）：
 * - planMulti（对齐 m44，C40N.java L1062-1109）：每 sub 迭代剩余量 = 总量/0.15 − 已消费
 *   「速度」累积（L1074-1078：object2 += m42 结果 .f42 后以 object2.f44/f47 回扣）；
 *   输出 = 速度累积，实际角位移 dy = vel × 0.15。旧接线的「位置累积」簿记会让
 *   sub-plan 退化（rem→0 → 一 tick 走满 / 恒零 / 死代码 resync），是转头不对齐的结构病根。
 * - 每 tick 二分上界 f45（C40N.java L602/L658 槽位：AxisState 的 maxVelocity/acceleration/
 *   accelFactor 三参全 = f45）：重锚 tick → speedAtInitialRot×20；dist > largeRotThreshold
 *   → speedAtLargeRot×20；否则（含退化重同步的下一 tick）→ aimSpeed×20。
 * - simulate 分支：正向（与当前速度同号）accelLimit = maxVelocity（不乘 accelFactor）；
 *   反向/刹车 accelLimit = accelFactor(=f45) × (|v| > highspeedThreshold/DT/3 ?
 *   decelPostHighspeed : decelPost)；vmax 抛物线系数 = decelPre。
 * - needResync（补点前判定）= 1 ≤ 原生路径步数 < remainSteps（空路径 → 零点回退，
 *   不算 need，避免 dPitch==0 时的 resync 风暴）；planMulti 汇总 anyResync →
 *   calculator 全量重置（对应 m44 内 f40C.run() + f47 = -1，C40N.java L1099-1100）。
 * - aimSpeed 注入：废弃输出端 speedFactor（aimSpeed/100*8+2）与 ±speedAt*×DT 钳制，
 *   改为 aimSpeed×20 作二分上界；输出端仅 vel × 0.15。
 * - 速度种子：只用外部种子 = 目标实体自转 Δyaw/0.45（wrap）、Δpitch/0.45（静止=0），
 *   内部跟踪 prev、重锚时清零。f40x 链内回填（C40N.java L851 sel.f42）经 harness 实测
 *   引入 ±0.5° 2-cycle 振荡，弃用。
 *
 * 反编译无法 100% 还原、标注待真机标定的项：
 * 1. m42 选点下标 var23_14：本实现取 path[remainSteps-1]（终点速度）。harness 对照
 *    path[0]（首 tick 速度）两选法，终点选法稳态残差更低（0.40° vs 0.49° @aimSpeed=15），
 *    据此选用；语义待真机标定。aimSpeed 极端值（=100）下存在 ~2.6° 结构固有稳态残差。
 * 2. ×20 量级（bound = 设置值 × 20）：由 m42 槽位与设置值的关系推断，待真机标定。
 * 3. 噪声层 C47TL：依赖 dump 缺失类（C47T8）与未解析 indy 站点，noiseLayers 从未被
 *    填充；默认 removeNoiseChance=0 时噪声恒清零，省略后默认行为等价。
 * 4. m42 第 4 参（baseVelocity 语义）在调用点的取值无法从反编译确定，本实现恒 0。
 * 5. resyncDelayMs / resyncRateMin / resyncRateMax 保留为参数但主流程未引用（对应
 *    C48V L1656-1660 每 tick 重掷的双默认 15/20、30/40，客户端无对应结构）。
 */

/** Noise3 的用户可调参数（全部由 KillAura 设置面板注入）。 */
class Noise3Params(
    val pointSelectionMode: String,        // {"直接选点", "预判平滑"}
    val aimSpeed: Float,                   // [1..100] ×20 后作常规档二分上界 f45（量级待真机标定）
    val decelPre: Float,                   // [0.5..2.0] 抛物线刹车曲线系数（vmax = base + sqrt(2·a·decelPre·|d|)）
    val decelPost: Float,                  // [0.5..8.0] 反向/刹车分支限幅系数（常规速度档）
    val decelPostHighspeed: Float,         // [0.5..8.0] 反向/刹车分支限幅系数（高速速度档）
    val highspeedThreshold: Float,         // [5..20] 高/低速档分界（度/tick，内部 /DT/3 换算）
    val speedAtLargeRot: Float,            // [1..100] ×20 后作大角度档二分上界
    val speedAtInitialRot: Float,          // [1..100] ×20 后作重锚档二分上界
    val largeRotThreshold: Float,          // [20..180] 大角度档触发阈值（度）
    val resyncChance: Float,               // [0..100]
    val resyncUpdateDelay: Float,          // [1..5]
    val removeNoiseChance: Float,          // [0..100]
    val noNoiseRecalcDelay: Float,         // [1..8]
    val resyncDelayMs: Float,              // [0..1000]（预留，主流程未引用，待真机标定）
    val resyncRateMin: Float,              // [0..1]（预留，主流程未引用，待真机标定）
    val resyncRateMax: Float,              // [0..1]（预留，主流程未引用，待真机标定）
)

/** 一次 Noise3 决策所需的上下文（由模块每 tick 组装后挂在 RotationSettings 上）。 */
class Noise3Context(
    val aim1: Rotation,
    val aim2: Rotation,
    val targetId: Int,
    val entityYaw: Float,
    val entityPitch: Float,
    val params: Noise3Params,
)

/** 双精度二维向量（仅算法内部使用）。 */
internal data class N3Vec2(var x: Double, var y: Double)

/** 单轴物理状态（yaw / pitch 各一份）。 */
internal class AxisState(
    val accelFactor: Double,
    val totalRemaining: Double,
    var acceleration: Double,
    var baseVelocity: Double,
    val timeStep: Double,
    var currentVelocity: Double = 0.0,
) {
    var maxVelocity: Double = 0.0

    fun copy() = AxisState(accelFactor, totalRemaining, acceleration, baseVelocity, timeStep, currentVelocity).apply {
        maxVelocity = this@AxisState.maxVelocity
    }
}

internal class PathPoint(val velocity: Double, val position: Double, val acceleration: Double)

internal class StepResult(val targetPos: N3Vec2, val velocity: N3Vec2, val needResync: Boolean)

/**
 * 路径规划器：对 yaw / pitch 两轴分别用「抛物线刹车曲线 + 半隐式 Euler」
 * 模拟从 0 走到目标位移的过程，并用二分法把路径步数调到指定 tick 数。
 */
internal class C40N {
    /** 每 tick 二分上界 f45（重锚/大角度/常规三档，×20 量级待真机标定）。 */
    var bisectBound = 60.0

    /** 抛物线刹车曲线系数（f40i）。 */
    var decelPre = 1.0

    /** 反向/刹车分支限幅系数：常规档（f48）/ 高速档（f42decHS）。 */
    var decelPost = 1.0
    var decelPostHighspeed = 2.0

    /** 高/低速档分界（度/tick，f40N，内部 /DT/3 换算）。 */
    var highspeedThreshold = 10.0

    /** 外部速度种子（目标实体自转 Δ/0.45，重锚时清零；f40x 链内回填已弃用）。 */
    var seedYaw = 0.0
    var seedPitch = 0.0

    fun simulate(s: AxisState): MutableList<PathPoint> {
        val out = mutableListOf<PathPoint>()
        var position = 0.0
        var remaining = s.totalRemaining
        val total = s.totalRemaining

        for (i in 0 until MAX_SIM_STEPS) {
            if (remaining == 0.0) break

            // 抛物线刹车曲线：vmax = base + sqrt(2 * a * decelPre * |d|)（f40i = decelPre）
            val vmax = s.baseVelocity + sqrt(2.0 * s.acceleration * decelPre * abs(remaining))
            val targetV = vmax * remaining.n3sign() - s.currentVelocity

            // 加速度上限（对齐反编译 m42 分支，harness 已验证）：
            // - 正向（与当前速度同号）：= maxVelocity（二分后的 mid），不乘 accelFactor
            // - 反向/刹车（与当前速度反号）：= accelFactor(=f45) × 按速度档位的 decel 系数
            var accelLimit: Double
            if (remaining.n3sign() != s.currentVelocity.n3sign()) {
                val highspeed = abs(s.currentVelocity) > highspeedThreshold / DT / 3.0
                accelLimit = s.accelFactor * (if (highspeed) decelPostHighspeed else decelPost)
            } else {
                accelLimit = s.maxVelocity
            }
            if (targetV.n3sign() != remaining.n3sign()) accelLimit = Double.POSITIVE_INFINITY

            val delta = targetV.coerceIn(-accelLimit, accelLimit)

            // 半隐式 Euler
            s.currentVelocity += delta * s.timeStep
            remaining -= s.currentVelocity * s.timeStep
            position += s.currentVelocity * s.timeStep

            // 反向截断
            if (remaining.n3sign() != total.n3sign()) {
                s.currentVelocity = s.baseVelocity * total.n3sign()
                remaining = 0.0
                position = total
            }
            out.add(PathPoint(s.currentVelocity, position, delta))

            // 过冲回折
            if (abs(remaining) > OVERSHOOT_CLAMP) {
                remaining = (abs(remaining) - OVERSHOOT_CLAMP) * -remaining.n3sign()
            }
        }
        return out
    }

    /** 二分调整 maxVelocity/acceleration，使路径步数逼近 [targetSteps]。 */
    fun tuneSteps(base: AxisState, targetSteps: Int): AxisState {
        val s = base.copy()
        var low = 0.001
        var high = s.maxVelocity
        for (i in 0 until MAX_BISECT) {
            val mid = 0.5 * (low + high)
            s.maxVelocity = mid
            s.acceleration = mid
            val count = simulate(s).size
            if (count < targetSteps) high = mid
            else if (count > targetSteps) low = mid
            else return s
            if (abs(high - low) < EPS) break
        }
        s.maxVelocity = 0.5 * (low + high)
        s.acceleration = s.maxVelocity
        return s
    }

    /**
     * 单轴规划（对齐 m42，C40N.java L491）。
     *
     * AxisState 三参（maxVelocity/acceleration/accelFactor）全 = f45 = [bisectBound]；
     * currentVelocity 从外部速度种子起步（目标实体自转 Δ/0.45，静止 = 0）。
     * 注意（审查 Q2）：种子只在二分首轮 simulate 生效 —— tuneSteps 的反复 simulate
     * 会把轴状态推进到终态（落地截断把速度回置 baseVelocity·sign = 0），最终选点用的
     * 那次 simulate 几乎总是从 0 起步，种子对选点的贡献是间接的（仅影响二分计数）。
     * 这与 harness 已验证的接线一致，但「种子驱动跟随」的实际效果弱于字面语义，待真机标定。
     * m42 第 4 参（baseVelocity 语义）在调用点的取值无法从反编译确定，恒 0（待真机标定）。
     */
    fun planSingle(dYaw: Double, dPitch: Double, remainSteps: Int): StepResult {
        val yaw = AxisState(bisectBound, dYaw, bisectBound, 0.0, DT).apply {
            currentVelocity = seedYaw
            maxVelocity = bisectBound
        }
        val pitch = AxisState(bisectBound, dPitch, bisectBound, 0.0, DT).apply {
            currentVelocity = seedPitch
            maxVelocity = bisectBound
        }

        val ty = tuneSteps(yaw, remainSteps)
        val tp = tuneSteps(pitch, remainSteps)

        val py = simulate(ty)
        val pp = simulate(tp)

        val rawYawSize = py.size
        val rawPitchSize = pp.size

        // 空路径回退：java 用 new 47UQ(0,0,0) 作零点，不算退化
        if (py.isEmpty()) py.add(PathPoint(0.0, 0.0, 0.0))
        if (pp.isEmpty()) pp.add(PathPoint(0.0, 0.0, 0.0))

        while (py.size < remainSteps) py.add(py.last())
        while (pp.size < remainSteps) pp.add(pp.last())

        // 选点下标 var23_14 语义待真机标定：取终点（harness 对照 path[0]，终点残差更低）
        val selYaw = py[remainSteps - 1]
        val selPitch = pp[remainSteps - 1]

        // 补点前判定（对应 m42 的 f46）：原生路径能自然走出 1..remainSteps-1 步
        // 即为退化计划（剩余量太小铺不满）→ 上层全量重置重新规划。
        // 注意（审查 S5）：二分非精确收敛时 rawSize 可能落在 remainSteps-1，
        // 小余量 + 大 decelPost(8) + 大 bound 的极端参数组合下存在每 tick 重同步
        // 风暴的理论条件 —— 默认参数下 harness 未触发，参数敏感，待真机标定。
        val needResync = rawYawSize in 1 until remainSteps || rawPitchSize in 1 until remainSteps

        return StepResult(
            N3Vec2(selYaw.position, selPitch.position),
            N3Vec2(selYaw.velocity, selPitch.velocity),
            needResync,
        )
    }

    fun planMulti(dYaw: Double, dPitch: Double, totalSteps: Int): StepResult {
        val accumPos = N3Vec2(0.0, 0.0)
        val accumVel = N3Vec2(0.0, 0.0)
        var anyResync = false
        for (i in 0 until totalSteps) {
            // 对齐 m44（C40N.java L1074-1078）：剩余量减去的是已消费「速度」累积
            // （object2 += m42 结果 .f42），非位置累积 —— pos 累积是旧接线的结构病根
            val remainYaw = dYaw / DT - accumVel.x
            val remainPitch = dPitch / DT - accumVel.y
            val step = planSingle(remainYaw, remainPitch, totalSteps - i)
            accumPos.x += step.targetPos.x
            accumPos.y += step.targetPos.y
            accumVel.x += step.velocity.x
            accumVel.y += step.velocity.y
            anyResync = anyResync || step.needResync
        }
        return StepResult(accumPos, accumVel, anyResync)
    }

    companion object {
        private const val EPS = 1.0e-9
        private const val MAX_BISECT = 100
        private const val MAX_SIM_STEPS = 1000
        private const val DT = 0.15
        private const val OVERSHOOT_CLAMP = 1200.0
    }
}

private fun Double.n3sign(): Double = when {
    this > 0.0 -> 1.0
    this < 0.0 -> -1.0
    else -> 0.0
}

/**
 * Noise3 主入口：跨 tick 维护 actual/target 旋转状态，每 tick 输出下一个
 * 应生效的绝对旋转。resync 判定（视角被外部改变 / 换目标 / hurtTime 变化）
 * 时硬重置全部状态。
 */
class Noise3Calculator {
    private val planner = C40N()

    private var actualYaw = 0.0
    private var actualPitch = 0.0
    private var targetYaw = 0.0
    private var targetPitch = 0.0
    private var firstTime = true
    private var resyncCounter = 0
    private var noNoiseCounter = 0
    private var enablePrediction = true
    private var removeNoise = false
    private var lastTargetId = Int.MIN_VALUE
    private var lastHurtTime = -1

    /** 上一 tick 的目标实体旋转（外部速度种子 = Δ/0.45 的内部跟踪）。 */
    private var prevEntityYaw = 0.0
    private var prevEntityPitch = 0.0

    /** 上一 tick 触发了退化重同步 → 本 tick 二分上界强制用 aimSpeed×20（大角度档也不例外）。 */
    private var degenerateFollowUp = false

    fun compute(
        current: Rotation,
        hurtTime: Int,
        targetId: Int,
        aim1: Rotation,
        aim2: Rotation,
        entityYaw: Float,
        entityPitch: Float,
        params: Noise3Params,
    ): Rotation {
        val curYaw = wrapAngle(current.yaw.toDouble())
        val curPitch = current.pitch.toDouble()

        // NaN 防御（审查 Q6）：任一输入为 NaN 时整条规划链会被污染（NaN%360=NaN、
        // coerceIn 不拦截 NaN），直接透传当前旋转
        if (!current.yaw.isFinite() || !current.pitch.isFinite() ||
            !entityYaw.isFinite() || !entityPitch.isFinite() ||
            !aim1.yaw.isFinite() || !aim1.pitch.isFinite() ||
            !aim2.yaw.isFinite() || !aim2.pitch.isFinite()
        ) {
            return current
        }

        if (firstTime) {
            actualYaw = curYaw
            actualPitch = curPitch
            targetYaw = curYaw
            targetPitch = curPitch
            prevEntityYaw = entityYaw.toDouble()
            prevEntityPitch = entityPitch.toDouble()
            firstTime = false
            return current
        }

        // resync：视角被外部接管 / 换目标 / hurtTime 变化 → 硬重置（不能只重置一半）。
        // yaw 差必须再 wrap 一次：准星穿越正北（±180 边界）时 raw 差会≈359.9°，
        // 不 wrap 会误触发硬重锚（审查 S1）。hurtTime 受击窗口内逐 tick 递减会导致
        // 窗口内每 tick 重锚 —— 属既有设计（受击期间持续重锚），待真机标定（审查 S2）。
        val needResync = abs(wrapAngle(curYaw - targetYaw)) > 1.0 ||
            abs(actualPitch - curPitch) > 1.0 ||
            lastTargetId != targetId ||
            lastHurtTime != hurtTime

        val reanchor: Boolean
        if (needResync) {
            actualYaw = curYaw
            actualPitch = curPitch
            targetYaw = curYaw
            targetPitch = curPitch
            reanchor = true
            degenerateFollowUp = false
        } else {
            reanchor = false
        }
        lastTargetId = targetId
        lastHurtTime = hurtTime

        if (resyncCounter == 0) {
            enablePrediction = (Math.random() - params.resyncChance / 100.0) > 0
            resyncCounter = maxOf(1, params.resyncUpdateDelay.toInt())
        }
        if (resyncCounter > 0) resyncCounter--

        if (noNoiseCounter == 0) {
            removeNoise = (Math.random() - params.removeNoiseChance / 100.0) > 0
            noNoiseCounter = maxOf(1, params.noNoiseRecalcDelay.toInt())
        }
        if (noNoiseCounter > 0) noNoiseCounter--

        val targetRot = if (params.pointSelectionMode.equals("预判平滑", true)) {
            Rotation(
                ((aim1.yaw + aim2.yaw) / 2f),
                ((aim1.pitch + aim2.pitch) / 2f),
            )
        } else aim1

        val dYaw = wrapAngle(targetRot.yaw.toDouble() - targetYaw)
        val dPitch = targetRot.pitch.toDouble() - targetPitch
        val dist = hypot(dYaw, dPitch)

        // 每 tick 参数注入（decel* 系数 + 高速阈值）
        planner.decelPre = params.decelPre.toDouble()
        planner.decelPost = params.decelPost.toDouble()
        planner.decelPostHighspeed = params.decelPostHighspeed.toDouble()
        planner.highspeedThreshold = params.highspeedThreshold.toDouble()

        // 外部速度种子：目标实体自转 Δ/0.45（yaw 走 wrap；静止 = 0）。
        // 重锚 tick 清零并把 prev 钉到当前实体旋转，避免换目标/受击瞬间产生 Δ 尖峰。
        if (reanchor) {
            planner.seedYaw = 0.0
            planner.seedPitch = 0.0
            prevEntityYaw = entityYaw.toDouble()
            prevEntityPitch = entityPitch.toDouble()
        } else {
            planner.seedYaw = wrapAngle(entityYaw.toDouble() - prevEntityYaw) / 0.45
            planner.seedPitch = (entityPitch.toDouble() - prevEntityPitch) / 0.45
            prevEntityYaw = entityYaw.toDouble()
            prevEntityPitch = entityPitch.toDouble()
        }

        // 每 tick 二分上界 f45（×20 量级待真机标定）：
        // 重锚 tick → speedAtInitialRot×20；退化重同步的下一 tick → aimSpeed×20；
        // dist > 大角度阈值 → speedAtLargeRot×20；常规 → aimSpeed×20
        planner.bisectBound = when {
            reanchor -> params.speedAtInitialRot * 20.0
            degenerateFollowUp -> params.aimSpeed * 20.0
            dist > params.largeRotThreshold -> params.speedAtLargeRot * 20.0
            else -> params.aimSpeed * 20.0
        }
        degenerateFollowUp = false

        val step = planner.planMulti(dYaw, dPitch, PLAN_STEPS)

        if (step.needResync) {
            // 退化重同步：全量重置（对应 m44 内 f40C.run() + f47 = -1，C40N.java L1099-1100），
            // 已规划的输出向量仍照常生效，下一 tick 二分上界回落到 aimSpeed×20
            actualYaw = curYaw
            actualPitch = curPitch
            targetYaw = curYaw
            targetPitch = curPitch
            planner.seedYaw = 0.0
            planner.seedPitch = 0.0
            prevEntityYaw = entityYaw.toDouble()
            prevEntityPitch = entityPitch.toDouble()
            degenerateFollowUp = true
        }

        // 对齐反编译输出段（C48V L1662-1668）：输出 = 速度累积 × DT，
        // 无 speedFactor、无 ±speedAt*×DT 钳制
        val dy = step.velocity.x * DT
        val dp = step.velocity.y * DT

        actualYaw = wrapAngle(actualYaw + dy)
        actualPitch = (actualPitch + dp).coerceIn(-90.0, 90.0)
        targetYaw = wrapAngle(targetYaw + dy)
        targetPitch = (targetPitch + dp).coerceIn(-90.0, 90.0)

        return Rotation(targetYaw.toFloat(), targetPitch.toFloat())
    }

    companion object {
        private const val DT = 0.15

        // planMulti 的 3 步是硬编码（C48V.m45 末尾直接 m44(3)），不可配置
        private const val PLAN_STEPS = 3
    }
}

internal fun wrapAngle(a: Double): Double {
    var a = a % 360.0
    if (a >= 180.0) a -= 360.0
    if (a < -180.0) a += 360.0
    return a
}
