/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.combat

import net.airplus.features.module.Category
import net.airplus.features.module.Module

object KeepSprint : Module("KeepSprint", Category.COMBAT) {

    val mode by choices("Mode", arrayOf("Legacy", "Rise"), "Legacy")

    // ===== Legacy（原行为：统一运动衰减） =====
    val motionAfterAttackOnGround by float("MotionAfterAttackOnGround", 0.6f, 0.0f..1f) { mode == "Legacy" }
    val motionAfterAttackInAir by float("MotionAfterAttackInAir", 0.6f, 0.0f..1f) { mode == "Legacy" }

    val motionAfterAttack
        get() = if (mc.thePlayer.onGround) motionAfterAttackOnGround else motionAfterAttackInAir

    // ===== Rise（移植自 Rise KeepSprint：区分受击/普通状态 + Buffer Abuse） =====
    private val slowDownVelocity by float("SlowDown-Velocity", 0.6f, 0f..1f) { mode == "Rise" }
    private val slowDownNormal by float("SlowDown-Normal", 0.6f, 0f..1f) { mode == "Rise" }
    private val sprintSlowDownVelocity by boolean("VelocityHitSprint", false) { mode == "Rise" }
    private val sprintSlowDownNormal by boolean("NormalHitSprint", false) { mode == "Rise" }
    private val bufferAbuse by boolean("BufferAbuse", false) { mode == "Rise" }
    private val bufferDecrease by float("BufferDecrease", 1f, 0.1f..10f) { mode == "Rise" && bufferAbuse }
    private val maxBuffer by float("MaxBuffer", 5f, 1f..10f) { mode == "Rise" && bufferAbuse }
    private val onlyInAir by boolean("OnlyInAir", false) { mode == "Rise" }

    private var resetting = false
    private var combo = 0.0

    /** Rise 模式：最近一次攻击的疾跑保持标志；null 表示该次攻击不接管，走原版逻辑 */
    var lastRiseSprint: Boolean? = null
        private set

    val isRise
        get() = mode == "Rise"

    /**
     * 由 MixinEntityPlayer 在 attackTargetEntityWithCurrentItem 的 0.6 常量处调用（仅疾跑时）。
     * 返回该次攻击的运动衰减值；null 表示不接管（原版 0.6）。
     * 同时把疾跑保持标志写入 [lastRiseSprint] 供 setSprinting 注入点读取（该注入点在常量点之后执行）。
     */
    fun beginRiseAttack(playerHurtTime: Int, playerOnGround: Boolean): Double? {
        lastRiseSprint = null

        // Rise 原逻辑：!onGround || !onlyInAir 时才处理
        if (playerOnGround && onlyInAir) return null

        if (bufferAbuse) {
            if (combo < maxBuffer.toInt() && !resetting) {
                combo++
            } else {
                if (combo > 0.0) {
                    combo = maxOf(0.0, combo - bufferDecrease.toDouble())
                    resetting = true
                    // Rise 原代码此处直接 return：本次攻击不修改慢速，走原版行为
                    return null
                }
                resetting = false
            }
        } else {
            combo = 0.0
        }

        val (slowDown, keepSprint) = if (playerHurtTime > 0) {
            slowDownVelocity.toDouble() to sprintSlowDownVelocity
        } else {
            slowDownNormal.toDouble() to sprintSlowDownNormal
        }
        lastRiseSprint = keepSprint
        return slowDown
    }
}
