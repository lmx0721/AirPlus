/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.movement.flymodes.rise

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion
import net.airplus.event.EventState
import net.airplus.event.MoveEvent
import net.airplus.event.MotionEvent
import net.airplus.event.PacketEvent
import net.airplus.event.WorldEvent
import net.airplus.features.module.modules.movement.Fly
import net.airplus.features.module.modules.movement.flymodes.FlyMode
import net.airplus.features.module.modules.rise.RiseGrimUtils
import net.airplus.utils.client.PacketUtils.sendPacket
import net.airplus.utils.client.chat
import net.airplus.viaversion.vialoadingbase.ViaLoadingBase
import net.airplus.viaversion.vialoadingbase.model.ComparableProtocolVersion
import net.minecraft.network.Packet
import net.minecraft.network.play.client.C02PacketUseEntity
import net.minecraft.network.play.client.C03PacketPlayer
import net.minecraft.network.play.client.C0FPacketConfirmTransaction
import net.minecraft.network.play.server.S08PacketPlayerPosLook
import net.minecraft.network.play.server.S12PacketEntityVelocity
import java.util.LinkedList

/**
 * Rise Fly（Ported from Rise Grim3Flight / Grim2Flight / Grim191181Flight）。
 *
 * 通过 [Fly.riseMode] 选择对应的 Grim 子模式：
 * - Grim         ：最简版鞘翅飞行——离地后每个 PostMotion tick 持续发送
 *                  ViaVersion 的 START_FALL_FLYING 指令包，借用鞘翅物理悬空；
 * - Grim2        ：Blink 型——取消 S12Velocity 进入 blink 状态，扣住 C0F
 *                  Transaction，在攻击/传送包到达的时机发送鞘翅指令触发飞行，换世界自动关闭；
 * - Grim19-1.18.1：S12 冻结滑翔——取消 S12 并发送地面 C03 冻结 fallDistance，
 *                  随后以极小 motionY 滑翔 + moveFlying 移动（限 1.9~1.18.1）。
 */
object Rise : FlyMode("Rise") {

    //region Grim（Grim3Flight）
    private var airTicks = 0
    //endregion

    //region Grim2（Grim2Flight）
    private val heldPackets = LinkedList<Packet<*>>()
    private var pendingFallFlying = false
    private var blinking = false
    private var pendingTicks = 0
    //endregion

    //region Grim19-1.18.1（Grim191181Flight）
    private var frozen = false
    private var shouldFly = false
    //endregion

    private val isGrim get() = Fly.riseMode == "Grim"
    private val isGrim2 get() = Fly.riseMode == "Grim2"
    private val isGrim19 get() = Fly.riseMode == "Grim19-1.18.1"

    override fun onUpdate() {
        val player = mc.thePlayer ?: return

        if (isGrim) {
            if (!player.onGround) airTicks++ else airTicks = 0
        }
    }

    override fun onMotion(event: MotionEvent) {
        val player = mc.thePlayer ?: return

        when {
            // Grim：PostMotion（即 MotionEvent POST）时持续发送鞘翅指令
            isGrim && event.eventState == EventState.POST -> {
                if (!player.onGround)
                    RiseGrimUtils.sendStartFallFlying()
            }

            // Grim2：扣包队列清空后的下一个 PreMotion 发送鞘翅指令
            isGrim2 && event.eventState == EventState.PRE -> {
                if (pendingFallFlying) {
                    pendingTicks++
                    if (pendingTicks >= 8)
                        RiseGrimUtils.sendStartFallFlying()

                    pendingTicks = 0
                    pendingFallFlying = false
                }
            }
        }
    }

    override fun onMove(event: MoveEvent) {
        val player = mc.thePlayer ?: return

        // Grim19-1.18.1：Rise 的 PostStrafe 逻辑（每个 movement tick 执行一次）
        if (!isGrim19)
            return

        mc.timer.timerSpeed = Fly.riseTimer

        if (!frozen && player.fallDistance > 0f) {
            sendPacket(C03PacketPlayer(true))
            player.fallDistance = 0f
            frozen = true
        }

        if (frozen) {
            player.motionX = 0.0
            player.motionZ = 0.0
            player.motionY = 0.0
        }

        if (shouldFly) {
            RiseGrimUtils.preventDiagonalSpeed()
            RiseGrimUtils.moveFlying(Fly.riseSpeed.toDouble())
            player.motionY = if (Fly.riseGlideLessFlyMore) -1.1E-4 else -2.0E-4
            RiseGrimUtils.preventDiagonalSpeed()
            shouldFly = false
        }

        RiseGrimUtils.preventDiagonalSpeed()
    }

    override fun onPacket(event: PacketEvent) {
        val player = mc.thePlayer ?: return
        val packet = event.packet

        if (isGrim19) {
            // Rise PreMotion 取消的等价实现——冻结期间扣住自身 C03 位置包
            if (packet is C03PacketPlayer && frozen)
                event.cancelEvent()

            // 取消 S12 → 解除冻结并在下一 movement tick 起飞
            if (packet is S12PacketEntityVelocity && packet.entityID == player.entityId) {
                frozen = false
                event.cancelEvent()
                shouldFly = true
            }
        }

        if (isGrim2) {
            if (event.eventType == EventState.SEND) {
                // Blink 状态下扣住 C0F Transaction
                if (packet is C0FPacketConfirmTransaction && blinking) {
                    event.cancelEvent()
                    if (heldPackets.isEmpty())
                        pendingFallFlying = true

                    heldPackets.add(packet)
                }

                // 攻击包到达时回放扣包队列（让 Grim 的攻击豁免吃掉延迟的数据）
                if (packet is C02PacketUseEntity && blinking && heldPackets.isNotEmpty()) {
                    while (heldPackets.isNotEmpty()) {
                        sendPacket(heldPackets.poll(), false)
                    }
                }
            } else {
                // 传送包到达时回放扣包队列
                if (packet is S08PacketPlayerPosLook && blinking && heldPackets.isNotEmpty()) {
                    while (heldPackets.isNotEmpty()) {
                        sendPacket(heldPackets.poll(), false)
                    }
                }

                // 收到指向自己的 S12：取消并进入 blink
                if (packet is S12PacketEntityVelocity && packet.entityID == player.entityId) {
                    if (blinking || heldPackets.isNotEmpty())
                        return

                    pendingTicks = 0
                    blinking = true
                    event.cancelEvent()
                }
            }
        }
    }

    override fun onWorld(event: WorldEvent) {
        // Grim2：换世界自动关闭（Rise 原行为）
        if (isGrim2) {
            Fly.state = false
            pendingTicks = 0
            pendingFallFlying = false
            blinking = false
            heldPackets.clear()
        }
    }

    override fun onEnable() {
        airTicks = 0
        pendingTicks = 0
        pendingFallFlying = false
        blinking = false
        heldPackets.clear()
        frozen = false
        shouldFly = false

        if (isGrim19) {
            // Rise 原版本提示：该模式仅适用于 1.9~1.18.1
            val version: ComparableProtocolVersion? = try {
                ViaLoadingBase.getInstance().targetVersion
            } catch (t: Throwable) {
                null
            }

            if (version == null || version.isEqualTo(ProtocolVersion.v1_8) ||
                version.isNewerThanOrEqualTo(ProtocolVersion.v1_18_2)
            )
                chat("this will only work 1.9-1.18.1")
        }
    }

    override fun onDisable() {
        airTicks = 0
        pendingTicks = 0
        pendingFallFlying = false
        blinking = false
        frozen = false
        shouldFly = false
        mc.timer.timerSpeed = 1f

        while (heldPackets.isNotEmpty()) {
            sendPacket(heldPackets.poll(), false)
        }
    }
}
