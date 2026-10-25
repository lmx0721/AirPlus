/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.rotation.aim

import kotlin.math.hypot

/**
 * 流式分段器：把录制好的 per-tick 旋转流切分成「一次人手转动」的片段。
 *
 * yaw/pitch 合并处理（人手甩动是合运动），速度按各自 gcd 归一以跨灵敏度复用：
 * - 进入段：speed > ENTER（gcd 单位）
 * - 退出滞回：连续 EXIT_TICKS 个 tick speed < EXIT
 * - 最短段长 MIN_LENGTH（不足丢弃），最长 MAX_LENGTH（截断）
 * - 段间最少隔 GAP_TICKS 个 tick
 */
object AimSegmenter {

    private const val ENTER = 0.8f
    private const val EXIT = 0.4f
    const val EXIT_TICKS = 3
    private const val MIN_LENGTH = 3
    private const val MAX_LENGTH = 40
    const val GAP_TICKS = 2

    /**
     * 搜索污染兜底过滤（v5.2）：段内逐 tick 计算「决策时目标方向」与实际增量的
     * 夹角余弦，按 |delta| 加权平均低于该阈值 → 判定为「找不到目标乱转视角」的
     * 搜索段，不入库。正常瞄准转动（甩头/修正/追踪）余弦恒 ≈ +1。
     */
    private const val DIRECTION_CONSISTENCY_MIN = 0.35f

    /** 决策时剩余差低于该值（度）的 tick 不参与方向统计：已在目标附近时转动方向
     * 无几何意义（亚 gcd 微抖/过冲回拉的方向是纯噪声）。 */
    private const val ON_TARGET_EPS_DEG = 2f

    /** 方向统计的最少有效 tick 数：不足（如整段都在目标身上微抖）跳过过滤。 */
    private const val MIN_DIRECTION_TICKS = 3

    fun segment(ticks: List<TickSample>): List<Segment> {
        val segments = ArrayList<Segment>()

        var inSegment = false
        var gapLeft = 0
        var exitCounter = 0
        var current = ArrayList<TickSample>()
        var prevSpeed = 0f
        var startSpeed = 0f

        fun flush() {
            if (current.size < MIN_LENGTH) {
                current = ArrayList()
                return
            }

            val first = current.first()
            val sumYaw = current.sumOf { it.yawDelta.toDouble() }.toFloat()
            val sumPitch = current.sumOf { it.pitchDelta.toDouble() }.toFloat()

            // v2：段内平均瞄准点漂移（度/tick）。d = needed(j) - needed(j-1) + delta(j)
            // （delta 补偿掉本 tick 自身旋转，剩纯瞄准点移动）。必须与 train.py / 运行时
            // 同一公式同一 shift，且用 raw needed（录制侧保证）。段首 tick 漂移跳过
            // （其 needed(j-1) 在段外，可能是 invalidate 零样本）；零样本 tick 跳过；
            // 超过间断阈值的（换目标/失效混入）跳过（见 AimSegmentClass）。
            // 取逐 tick 幅值的均值（而非带符号和的幅值）：往复漂移（S 型追踪）的
            // 带符号和会相消错误地归零 → 该段被错判成 FINE。
            var driftMagSum = 0f
            var driftCount = 0
            for (k in 1 until current.size) {
                val cur = current[k]
                val prev = current[k - 1]
                val dy = cur.neededYaw - prev.neededYaw + cur.yawDelta
                val dp = cur.neededPitch - prev.neededPitch + cur.pitchDelta
                if (cur.neededYaw == 0f && cur.neededPitch == 0f && cur.yawDelta == 0f && cur.pitchDelta == 0f) continue
                if (prev.neededYaw == 0f && prev.neededPitch == 0f && prev.yawDelta == 0f && prev.pitchDelta == 0f) continue
                val mag = hypot(dy, dp)
                if (mag > AimSegmentClass.DRIFT_DISCONTINUITY_DEG) continue
                driftMagSum += mag
                driftCount++
            }
            val aimDrift = if (driftCount > 0) driftMagSum / driftCount else 0f

            // 场景标签取段内多数派（AimScene 逐 tick 录制真值）。全段无标签（旧数据）
            // 记 0：模板检索时该段回退启发式分类，与旧库行为一致。
            val sceneCount = IntArray(4)
            for (t in current) if (t.scene in 1..3) sceneCount[t.scene]++
            val majority = sceneCount.withIndex().maxByOrNull { it.value }
            val scene = if (majority != null && majority.value > 0) majority.index else 0

            // v5.2 搜索污染兜底过滤：录制端无法可靠区分「正常甩头」与「找不到目标
            // 乱转视角」的搜索数据（两者准星都不在目标上、scene 同为 LOCATE），入库
            // 前按几何一致性最后过滤一次。每 tick 的决策时目标方向：
            // needed(j-1) ≈ needed(j) + delta(j)（目标近似静止时精确成立，移动目标的
            // 偏差在间断阈值量级内可忽略），与实际增量 delta(j) 求带符号余弦 ——
            // 朝目标转 ≈ +1，背离目标 < 0。以 |delta| 加权平均（转动越大的 tick 越
            // 代表段的意图），低于阈值的段整体丢弃：脏段进库会被 AimTemplateEngine
            // 当成真甩头模板重放（方向漂移 + 单侧偏离）。历史脏数据也会在下一次
            // persist 重建 library 时被本过滤剔除。
            var cosWeightedSum = 0f
            var weightSum = 0f
            var dirTicks = 0
            for (t in current) {
                val deltaMag = hypot(t.yawDelta, t.pitchDelta)
                if (deltaMag <= 0f) continue
                val errBeforeYaw = t.neededYaw + t.yawDelta
                val errBeforePitch = t.neededPitch + t.pitchDelta
                val errBeforeMag = hypot(errBeforeYaw, errBeforePitch)
                if (errBeforeMag < ON_TARGET_EPS_DEG) continue
                val cos = (t.yawDelta * errBeforeYaw + t.pitchDelta * errBeforePitch) /
                    (deltaMag * errBeforeMag)
                if (cos.isNaN()) continue
                cosWeightedSum += cos * deltaMag
                weightSum += deltaMag
                dirTicks++
            }
            val meanCos = if (weightSum > 0f) cosWeightedSum / weightSum else 1f
            if (dirTicks >= MIN_DIRECTION_TICKS && meanCos < DIRECTION_CONSISTENCY_MIN) {
                current = ArrayList()
                return
            }

            segments.add(
                Segment(
                    yawDeltas = current.map { it.yawDelta },
                    pitchDeltas = current.map { it.pitchDelta },
                    totalDiff = hypot(sumYaw, sumPitch),
                    startSpeed = startSpeed,
                    duration = current.size,
                    moveSpeed = first.moveSpeed,
                    entityDist = first.entityDist,
                    gcd = first.gcd,
                    aimDrift = aimDrift,
                    scene = scene,
                )
            )
            current = ArrayList()
        }

        for ((i, tick) in ticks.withIndex()) {
            val gcd = tick.gcd.takeIf { it > 0f } ?: continue
            val speed = hypot(tick.yawDelta, tick.pitchDelta) / gcd

            if (inSegment) {
                current.add(tick)

                if (speed < EXIT) {
                    if (++exitCounter >= EXIT_TICKS) {
                        // 去掉尾部的低速 tick
                        repeat(EXIT_TICKS) { if (current.isNotEmpty()) current.removeAt(current.size - 1) }
                        flush()
                        inSegment = false
                        gapLeft = GAP_TICKS
                        exitCounter = 0
                    }
                } else {
                    exitCounter = 0
                }

                if (inSegment && current.size >= MAX_LENGTH) {
                    flush()
                    inSegment = false
                    gapLeft = GAP_TICKS
                    exitCounter = 0
                }
            } else {
                if (gapLeft > 0) {
                    gapLeft--
                } else if (speed > ENTER) {
                    inSegment = true
                    startSpeed = prevSpeed
                    current.add(tick)
                    exitCounter = 0
                }
            }

            prevSpeed = speed
        }

        // 收尾
        if (inSegment) flush()

        return segments
    }
}
