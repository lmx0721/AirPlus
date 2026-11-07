/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.movement.speedmodes.other

import net.airplus.event.JumpEvent
import net.airplus.event.MovementInputEvent
import net.airplus.event.PacketEvent
import net.airplus.features.module.modules.movement.Speed
import net.airplus.features.module.modules.movement.Speed.grim2HighPingMode
import net.airplus.features.module.modules.movement.Speed.grim2Speed
import net.airplus.features.module.modules.movement.speedmodes.SpeedMode
import net.airplus.utils.client.PacketUtils
import net.airplus.utils.client.chat
import net.airplus.utils.extensions.isMoving
import net.airplus.utils.movement.MovementUtils
import net.minecraft.network.play.client.C03PacketPlayer
import net.minecraft.network.play.server.S08PacketPlayerPosLook
import net.minecraft.network.play.server.S12PacketEntityVelocity
import kotlin.math.cos
import kotlin.math.sin

/**
 * Grim2 Speed（Ported from Rise Grim2Speed）。
 *
 * 原理：偶数 tick 在 PostMotion 额外补发一对 C03 玩家包（普通 ping 下 true+false，
 * 高 ping 模式 false+false），配合奇偶交替的 strafe 加速实现位移；收到 S08
 * （lagback 传送）时把奇偶对齐回偶数 tick 并把 timer 归位，收到指向自己的
 * S12 velocity 时标记下一 tick 主动跳（服务器期望的跳跃验证）。
 */
object Grim2 : SpeedMode("Grim2") {

    private var shouldJump = false
    private var strafeTicks = 0
    private var groundTicks = 0

    override fun onMotion() {
        shouldJump = false
    }

    override fun onMotionPost() {
        if (strafeTicks % 2 == 0) {
            if (grim2HighPingMode) {
                PacketUtils.sendPacket(C03PacketPlayer(false))
                PacketUtils.sendPacket(C03PacketPlayer(false))
            } else {
                PacketUtils.sendPacket(C03PacketPlayer(true))
                PacketUtils.sendPacket(C03PacketPlayer(false))
            }
        }
    }

    override fun onMovementInput(event: MovementInputEvent) {
        if (shouldJump)
            event.originalInput.jump = true
    }

    override fun onStrafe() {
        val player = mc.thePlayer ?: return

        groundTicks = if (player.onGround) ++groundTicks else 0

        var distance = 0.03
        if (strafeTicks % 2 == 0) {
            distance = if (player.onGround) 0.085 else 0.03
        }
        moveFlying(distance * grim2Speed)

        ++strafeTicks
    }

    override fun onJump(event: JumpEvent) {
        val player = mc.thePlayer ?: return

        // 只放行由真实输入（shouldJump）触发的跳跃，其余（被动的 jump() 调用）取消
        if (!player.isJumping)
            event.cancelEvent()
    }

    override fun onPacket(event: PacketEvent) {
        when (val packet = event.packet) {
            is S08PacketPlayerPosLook -> {
                // lagback 传送发生在奇数 tick 时补成偶数，保证下一轮 PostMotion 双包对齐
                if (strafeTicks % 2 == 1)
                    ++strafeTicks
                mc.timer.timerSpeed = 1f
            }

            is S12PacketEntityVelocity -> {
                val player = mc.thePlayer ?: return
                if (packet.entityID == player.entityId)
                    shouldJump = true
            }
        }
    }

    override fun onEnable() {
        if (grim2HighPingMode) {
            PacketUtils.sendPacket(C03PacketPlayer(false))
            PacketUtils.sendPacket(C03PacketPlayer(false))
        } else {
            PacketUtils.sendPacket(C03PacketPlayer(true))
            PacketUtils.sendPacket(C03PacketPlayer(false))
        }

        chat("ping needs to be below 150ms for this to work consistantly")

        strafeTicks = 0
        groundTicks = 0
        mc.timer.timerSpeed = 1f
    }

    /** Rise MoveUtil.moveFlying：在移动输入方向上叠加速度（累加而非重设）。 */
    private fun moveFlying(amount: Double) {
        val player = mc.thePlayer ?: return
        if (!player.isMoving)
            return

        val yaw = MovementUtils.direction
        player.motionX += -sin(yaw) * amount
        player.motionZ += cos(yaw) * amount
    }
}
