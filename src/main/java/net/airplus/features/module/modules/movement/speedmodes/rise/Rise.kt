/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.movement.speedmodes.rise

import net.airplus.event.EventState
import net.airplus.event.MotionEvent
import net.airplus.features.module.modules.combat.VelocityRise
import net.airplus.features.module.modules.movement.Speed
import net.airplus.features.module.modules.movement.speedmodes.SpeedMode
import net.airplus.utils.skid.rise.RiseGrimUtils.moveFlying
import net.airplus.utils.skid.rise.RiseGrimUtils.sendStartFallFlying
import net.airplus.utils.extensions.tryJump
import net.airplus.utils.rotation.Rotation
import net.airplus.utils.rotation.RotationUtils

/**
 * Rise Speed（Ported from Rise GrimSpeed）。
 *
 * 原理：碰撞箱内靠近实体时获得额外推速（inPlayerSpeed）、常态微加速（moveFlyingIncrease）；
 * Fast Fall 开启时通过 ground-spoof 交替 + ViaVersion 的 START_FALL_FLYING 指令包
 * 实现 Grim 下的快速落地连跳加速，并把状态暴露给 Velocity 的 Grim / Reduce 模式联动。
 */
object Rise : SpeedMode("Rise") {

    private var airTicks = 0
    private var groundSpoof = 0

    override fun onUpdate() {
        val player = mc.thePlayer ?: return

        if (!player.onGround) {
            airTicks++
        } else {
            airTicks = 0
        }

        // 非 Fast Fall 时：空中且未同时按住（前+左/右）时做 45° 侧向旋转修正，绕过 Grim 移动方向检测
        if (!player.isInWeb && !player.onGround && !Speed.riseFastFall
            && (!mc.gameSettings.keyBindForward.isKeyDown || !mc.gameSettings.keyBindRight.isKeyDown)
            && (!mc.gameSettings.keyBindForward.isKeyDown || !mc.gameSettings.keyBindLeft.isKeyDown)
        ) {
            RotationUtils.setTargetRotation(
                Rotation(player.rotationYaw + 45f + Math.random().toFloat(), player.rotationPitch),
                Speed.riseOptions
            )
        }
    }

    override fun onMotion(event: MotionEvent) {
        val player = mc.thePlayer ?: return

        if (event.eventState != EventState.PRE)
            return

        if (Speed.riseFastFall) {
            // ground-spoof 奇偶交替：让 Grim 看到间隔的"地面"状态以绕过地面状态检查
            groundSpoof = if (groundSpoof == 0) {
                if (event.onGround) 1 else 0
            } else {
                0
            }

            event.onGround = groundSpoof != 0

            if (player.onGround)
                player.tryJump()
        }
    }

    override fun onMotionPost() {
        val player = mc.thePlayer ?: return

        if (Speed.riseFastFall && !player.onGround && groundSpoof >= 0) {
            if (airTicks < 1 || airTicks > 5)
                return

            // 1.9+ 鞘翅起飞指令，吃掉 Grim 的模拟位移
            sendStartFallFlying()
            player.isSneaking = false
            if (airTicks == 2)
                player.tryJump()
        }
    }

    override fun onStrafe() {
        val player = mc.thePlayer ?: return

        // 碰撞箱内靠近实体时的额外推速
        mc.theWorld?.loadedEntityList
            ?.asSequence()
            ?.filterIsInstance<net.minecraft.entity.EntityLivingBase>()
            ?.filter {
                it !== player && player.entityBoundingBox
                    .expand(Speed.riseBoundingBoxSize.toDouble(), Speed.riseBoundingBoxSize.toDouble(), Speed.riseBoundingBoxSize.toDouble())
                    .intersectsWith(it.entityBoundingBox)
            }
            ?.forEach { _ -> moveFlying(Speed.riseInPlayerSpeed.toDouble()) }

        moveFlying(Speed.riseMoveFlyingIncrease.toDouble())

        // 与 Velocity Grim 模式联动：未被冻结且击退处理完毕（或空中）时主动跳跃
        if ((!VelocityRise.freezing && (VelocityRise.velocityTicks == 0 || VelocityRise.velocityTicks >= 6) || !player.onGround)
            && player.hurtTime == 0
            && player.onGround
            && mc.gameSettings.keyBindJump.isKeyDown
        ) {
            player.tryJump()
        }
    }

    override fun onEnable() {
        airTicks = 0
        groundSpoof = 0
    }

    override fun onDisable() {
        mc.gameSettings.keyBindSneak.pressed = false
        mc.gameSettings.keyBindJump.pressed = false
        airTicks = 0
        groundSpoof = 0
    }
}
