/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.player.nofallmodes.rise

import net.airplus.event.EventState
import net.airplus.event.MotionEvent
import net.airplus.event.MovementInputEvent
import net.airplus.event.PacketEvent
import net.airplus.features.module.modules.player.NoFall
import net.airplus.features.module.modules.player.nofallmodes.NoFallMode
import net.airplus.features.module.modules.rise.RiseGrimUtils.predictedMotion
import net.airplus.utils.client.PacketUtils.sendPacket
import net.minecraft.network.Packet
import net.minecraft.network.play.client.C03PacketPlayer
import net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition
import net.minecraft.network.play.server.S12PacketEntityVelocity
import net.minecraft.network.play.server.S27PacketExplosion
import net.minecraft.network.play.server.S32PacketConfirmTransaction
import java.util.ArrayList

/**
 * Rise NoFall（Ported from Rise GrimServer19NoFall）。
 *
 * 原理：fallDistance > 3 落地时扣下当 tick 的 C03 位置包，改发"地面状态"的
 * C03（或 Newest Grim 选项下的 C04 +0.01，可能触发反作弊标记），骗过 Grim 的
 * 服务器端落地校验；S12/S27 在扣包期间不额外处理（原实现的 heldPackets 实为空壳）。
 *
 * 事件映射：Rise 的 PreMotion 取消 → airplus 的 MotionEvent(PRE) 标记 +
 * PacketEvent(SEND) 扣包换发；PreUpdateEvent → UpdateEvent；
 * FallDistanceComponent.cY → player.fallDistance。
 */
object Rise : NoFallMode("Rise") {

    private var shouldNoFall = false
    private var shouldJump = false

    /** PreMotion 取消的等价实现：标记扣下本 tick 的 C03。 */
    private var cancelNextC03 = false

    /** Rise 的 holdingPackets / releasing 静态标记。 */
    private var holdingPackets = false
    private var releasing = false

    private val heldPackets = ArrayList<Packet<*>>()

    override fun onUpdate() {
        val player = mc.thePlayer ?: return

        // PreUpdateEvent：落地后释放扣包（原实现的扣包列表实为空壳，保留形态）
        if (holdingPackets && player.onGround) {
            releasing = true
            holdingPackets = false
            heldPackets.forEach { sendPacket(it, false) }
            heldPackets.clear()
            releasing = false
        }
    }

    override fun onMotion(event: MotionEvent) {
        if (event.eventState != EventState.PRE)
            return

        val player = mc.thePlayer ?: return
        if (player.isCollidedHorizontally)
            return

        var fallDistance = player.fallDistance

        if (player.motionY > 0.1)
            shouldNoFall = false

        if (fallDistance > 3.0f)
            shouldNoFall = true

        if (shouldNoFall && player.onGround) {
            // 等价 PreMotion 取消：标记扣下本 tick 的 C03，在 onPacket 里换发地面包
            cancelNextC03 = true
            mc.gameSettings.keyBindJump.pressed = false
            fallDistance = 0.0f
        }

        if (shouldNoFall)
            mc.gameSettings.keyBindJump.pressed = false

        // 混淆字段近似：PlayerUtil.vi() → fallDistance、tR/Zl 判定按原形态保留
        //（ticksExisted == 9 && hurtTime > 200 基本不可达，仅为忠实移植）
        if (fallDistance > 5.0 && player.ticksExisted == 9 && player.hurtTime > 200 && NoFall.riseNewestGrim) {
            player.motionY = predictedMotion(player.motionY, 10)
        }

        player.fallDistance = fallDistance
    }

    override fun onPacket(event: PacketEvent) {
        val player = mc.thePlayer ?: return
        val packet = event.packet

        // PreMotion 取消的等价实现：扣下当 tick 的 C03，换发地面状态包
        if (event.eventType == EventState.SEND) {
            if (packet is C03PacketPlayer && cancelNextC03) {
                cancelNextC03 = false
                event.cancelEvent()

                if (NoFall.riseNewestGrim)
                    sendPacket(C04PacketPlayerPosition(player.posX, player.posY + 0.01, player.posZ, true))

                sendPacket(C03PacketPlayer(true))
            }
            return
        }

        if (releasing || player.ticksExisted < 10 || player.isCollidedHorizontally)
            return

        when (packet) {
            is S12PacketEntityVelocity ->
                if (packet.entityID == player.entityId && !event.isCancelled) {
                    if (packet.motionY > 0 || player.hurtTime <= 14 || player.hurtResistantTime <= 1)
                        shouldJump = true

                    if (!player.onGround && shouldNoFall)
                        holdingPackets = true
                }

            // 原实现在 holdingPackets 时对 S32 / S27（混淆包 a）直接 return（空操作），保留注释
            is S32PacketConfirmTransaction -> {}
            is S27PacketExplosion -> {}
        }
    }

    override fun onMovementInput(event: MovementInputEvent) {
        val player = mc.thePlayer ?: return
        if (player.isCollidedHorizontally)
            return

        if (shouldJump && shouldNoFall) {
            event.originalInput.jump = true
            shouldJump = false
        }
    }

    override fun onEnable() {
        shouldNoFall = false
        shouldJump = false
        cancelNextC03 = false
        holdingPackets = false
        releasing = false
        heldPackets.clear()
    }

    override fun onDisable() {
        shouldNoFall = false
        shouldJump = false
        cancelNextC03 = false
        holdingPackets = false
        releasing = false
        heldPackets.clear()
    }
}
