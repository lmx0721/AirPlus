/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.movement.longjumpmodes.rise

import net.airplus.event.EventState
import net.airplus.event.MotionEvent
import net.airplus.event.MoveEvent
import net.airplus.event.PacketEvent
import net.airplus.features.module.modules.movement.LongJump
import net.airplus.features.module.modules.movement.longjumpmodes.LongJumpMode
import net.airplus.utils.client.PacketUtils.sendPacket
import net.airplus.utils.extensions.tryJump
import net.minecraft.network.play.client.C03PacketPlayer
import net.minecraft.network.play.server.S08PacketPlayerPosLook
import net.minecraft.network.play.server.S12PacketEntityVelocity

/**
 * Rise LongJump（Ported from Rise GrimLongJump）。
 *
 * 原理：起跳后以 timer=2f 加速，连发 C03(false) 空包填充 Grim 的预测状态，
 * 配合扣包（cancelNextPacket）在落地时规避跌落校验；S12 负 Y 击退自动关闭，
 * S08 传送进入 stage=1 的恢复流程。
 *
 * 事件映射：PostStrafeEvent → MoveEvent；PreMotionEvent → MotionEvent(PRE)
 * （pitch 噪声直接改写 player.rotationPitch，airplus 的 MotionEvent 不携带 pitch）。
 */
object Rise : LongJumpMode("Rise") {

    private var strafeTicks = 0
    private var cancelNextPacket = 0
    private var timeoutTicks = 0
    private var started = 0
    private var stage = 0

    override fun onMotion(event: MotionEvent) {
        if (event.eventState != EventState.PRE)
            return

        val player = mc.thePlayer ?: return

        mc.timer.timerSpeed = 2f

        // Rise 的 preMotionEvent.setPitch(pitch + rand * 0.1)：pitch 旋转噪声
        player.rotationPitch += Math.random().toFloat() * 0.1f

        if (started == 0)
            return

        player.motionY = 0.42

        when (stage) {
            1 -> {
                stage = 2
                timeoutTicks = 0
            }

            2 -> {
                // 连发 2 个 C03(false) 空包后扣下一个包并回到初始 stage
                repeat(2) {
                    sendPacket(C03PacketPlayer(false), false)
                }
                cancelNextPacket = 1
                stage = 0
            }

            else -> {
                player.motionY = 0.0
                player.motionX = 0.0
                player.motionZ = 0.0
                cancelNextPacket = 1
                timeoutTicks++

                if (timeoutTicks > 20)
                    LongJump.state = false
            }
        }
    }

    override fun onMove(event: MoveEvent) {
        // Rise 的 PostStrafeEvent 逻辑（每个 movement tick 执行一次）
        val player = mc.thePlayer ?: return

        if (strafeTicks == 0)
            player.tryJump()

        if (strafeTicks == 1) {
            started = 1
            repeat(20) {
                sendPacket(C03PacketPlayer(false), false)
            }
        }

        strafeTicks++
    }

    override fun onPacket(event: PacketEvent) {
        val player = mc.thePlayer ?: return
        val packet = event.packet

        if (event.eventType == EventState.SEND) {
            // 扣掉标记的 C03 包（Rise 的 cancelNextPacket）
            if (packet is C03PacketPlayer && cancelNextPacket == 1) {
                cancelNextPacket = 0
                event.cancelEvent()
            }
        } else {
            // 负 Y 击退：自动关闭
            if (packet is S12PacketEntityVelocity && packet.entityID == player.entityId &&
                packet.motionY / 8000.0 < 0.0
            ) {
                LongJump.state = false
            }

            // 传送包：进入恢复 stage
            if (packet is S08PacketPlayerPosLook)
                stage = 1
        }
    }

    override fun onEnable() {
        strafeTicks = 0
        started = 0
        stage = 0
        cancelNextPacket = 0
        timeoutTicks = 0
    }

    override fun onDisable() {
        strafeTicks = 0
        started = 0
        stage = 0
        cancelNextPacket = 0
        timeoutTicks = 0
        mc.timer.timerSpeed = 1f
    }
}
