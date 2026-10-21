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
    private const val EXIT_TICKS = 3
    private const val MIN_LENGTH = 3
    private const val MAX_LENGTH = 40
    private const val GAP_TICKS = 2

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
                )
            )
            current = ArrayList()
        }

        for (tick in ticks) {
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
