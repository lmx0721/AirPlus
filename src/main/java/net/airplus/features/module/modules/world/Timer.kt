/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.world

import net.airplus.event.UpdateEvent
import net.airplus.event.WorldEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.extensions.isMoving
import net.airplus.utils.timing.MSTimer

object Timer : Module("Timer", Category.WORLD, gameDetecting = false) {

    private val mode by choices("Mode", arrayOf("OnMove", "NoMove", "Always"), "OnMove")
    private val speed by float("Speed", 2F, 0.1F..10F)

    private val limitAction by choices("LimitAction", arrayOf("Off", "Disable", "Pause"), "Off")
    private val limitTime by float("LimitTime", 10f, 1f..5f) { limitAction != "Off" }
    private val pauseTime by int("PauseTime", 20, 1..400) { limitAction == "Pause" }

    private val boostTimer = MSTimer()
    private var pauseTicksLeft = 0

    override fun onEnable() {
        boostTimer.reset()
        pauseTicksLeft = 0
    }

    override fun onDisable() {
        pauseTicksLeft = 0

        if (mc.thePlayer == null)
            return

        mc.timer.timerSpeed = 1F
    }

    val onUpdate = handler<UpdateEvent> {
        val player = mc.thePlayer ?: return@handler

        // 暂停期：停用自定义加速，倒计时结束后重新开启
        if (pauseTicksLeft > 0) {
            pauseTicksLeft--
            mc.timer.timerSpeed = 1F
            return@handler
        }

        val boosting = mode == "Always" || mode == "OnMove" && player.isMoving || mode == "NoMove" && !player.isMoving

        if (!boosting) {
            // 只统计连续加速时长，中断后重新计时
            boostTimer.reset()
            mc.timer.timerSpeed = 1F
            return@handler
        }

        if (limitAction != "Off" && boostTimer.hasTimePassed(limitTime * 1000f)) {
            boostTimer.reset()

            when (limitAction) {
                "Disable" -> {
                    state = false
                    return@handler
                }
                "Pause" -> {
                    pauseTicksLeft = pauseTime
                    mc.timer.timerSpeed = 1F
                    return@handler
                }
            }
        }

        mc.timer.timerSpeed = speed
    }

    val onWorld = handler<WorldEvent> {
        if (it.worldClient == null)
            state = false
    }
}
