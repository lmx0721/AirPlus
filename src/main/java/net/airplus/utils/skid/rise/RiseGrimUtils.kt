/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.skid.rise

import com.viaversion.viaversion.api.Via
import com.viaversion.viaversion.api.minecraft.Position
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion
import com.viaversion.viaversion.api.type.Type
import com.viaversion.viaversion.protocols.protocol1_19to1_18_2.Protocol1_19To1_18_2
import com.viaversion.viaversion.protocols.protocol1_19to1_18_2.ServerboundPackets1_19
import com.viaversion.viaversion.protocols.protocol1_9to1_8.Protocol1_9To1_8
import com.viaversion.viaversion.protocols.protocol1_9to1_8.ServerboundPackets1_9
import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.client.PacketUtils
import net.airplus.utils.client.chat
import net.airplus.utils.extensions.isMoving
import net.airplus.utils.movement.MovementUtils
import net.airplus.viaversion.vialoadingbase.ViaLoadingBase
import net.minecraft.network.Packet
import net.minecraft.network.play.client.C09PacketHeldItemChange
import net.minecraft.network.play.server.S08PacketPlayerPosLook
import net.minecraft.network.play.server.S12PacketEntityVelocity
import net.minecraft.network.play.server.S14PacketEntity
import net.minecraft.network.play.server.S27PacketExplosion
import net.minecraft.network.play.server.S32PacketConfirmTransaction
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rise Grim 模块礼包（Ported from Rise）公共工具。
 *
 * Rise 原实现分散在 MoveUtil / PacketUtil / RotationComponent 等组件中，
 * 这里集中提供移植后的等价实现，供 Speed/Fly/Velocity/NoSlow/NoFall/
 * LongJump/Phase/Disabler 的 Rise 模式共用。
 */
object RiseGrimUtils : MinecraftInstance {

    private var viaErrorLogged = false

    /** Rise MoveUtil.moveFlying：在移动输入方向上叠加速度（累加而非重设）。 */
    fun moveFlying(amount: Double) {
        val player = mc.thePlayer ?: return
        if (!player.isMoving)
            return

        val yaw = MovementUtils.direction
        player.motionX += -sin(yaw) * amount
        player.motionZ += cos(yaw) * amount
    }

    /** Rise MoveUtil.strafe（以指定速度重新定向移动）。 */
    fun strafe(speed: Double) {
        MovementUtils.strafe(speed.toFloat())
    }

    /**
     * Rise MoveUtil.preventDiagonalSpeed：消除斜向 45° 移动的 √2 增益，
     * 把水平速度钳制到单轴最大分量。
     */
    fun preventDiagonalSpeed() {
        val player = mc.thePlayer ?: return

        val x = abs(player.motionX)
        val z = abs(player.motionZ)
        if (x == 0.0 || z == 0.0)
            return

        val keep = maxOf(x, z)
        val speed = sqrt(x * x + z * z)
        if (speed > keep) {
            val scale = keep / speed
            player.motionX *= scale
            player.motionZ *= scale
        }
    }

    /** Grim 预测跳跃运动：按 Grim 的物理公式 (motionY - 0.08) * 0.98 迭代 [ticks] 次。 */
    fun predictedMotion(motion: Double, ticks: Int): Double {
        var result = motion
        repeat(ticks) {
            result = (result - 0.08) * 0.98
        }
        return result
    }

    /** Grim2Velocity/GrimTestVelocity 的"全角度旋转"判定：yaw/pitch 恰为 90° 的倍数。 */
    fun isFullRotation(angle: Float): Boolean {
        val mod = abs(angle % 90f)
        return mod < 0.01f || mod > 89.99f
    }

    /**
     * Rise PacketUtil.receive：在本地直接处理一个"收到"的包（用于扣包后的原序回放）。
     */
    fun receivePacket(packet: Packet<*>) {
        PacketUtils.handlePackets(packet)
    }

    /**
     * 通过 ViaVersion 直接向服务器注入 1.9+ 的 ENTITY_ACTION(START_FALL_FLYING, action=8)
     * 鞘翅起飞指令包（1.8 客户端无此包，必须走 Via 管线）。
     * 仅当 Via 目标版本 ≥ 1.9 时生效。
     */
    fun sendStartFallFlying() {
        val player = mc.thePlayer ?: return

        try {
            val version = ViaLoadingBase.getInstance().targetVersion
            if (version.isNewerThanOrEqualTo(ProtocolVersion.v1_9)) {
                val connection = Via.getManager().connectionManager.connections.iterator().next()
                val wrapper = PacketWrapper.create(ServerboundPackets1_9.ENTITY_ACTION, connection)
                wrapper.write(Type.VAR_INT, player.entityId)
                wrapper.write(Type.VAR_INT, 8) // START_FALL_FLYING
                wrapper.write(Type.VAR_INT, 0)
                wrapper.sendToServer(Protocol1_9To1_8::class.java)
            }
        } catch (t: Throwable) {
            logViaError(t)
        }
    }

    /**
     * 通过 ViaVersion 直接注入 1.9+ 的 USE_ITEM 包，绕过 Grim 对"使用物品"的模拟检测。
     * Grim19NoSlow（任意版本）/ GrimVelocity（1.19+ 分支）共用。
     *
     * Rise 原实现里 1.19+ 走 ServerboundPackets1_19.USE_ITEM + Protocol1_19To1_18_2；
     * 1.9~1.18.2 走 ServerboundPackets1_9.USE_ITEM + Protocol1_9To1_8。
     */
    fun sendViaUseItem(sequence: Int = 0) {
        val player = mc.thePlayer ?: return

        try {
            val version = ViaLoadingBase.getInstance().targetVersion
            when {
                version.isNewerThanOrEqualTo(ProtocolVersion.v1_19) -> {
                    val connection = Via.getManager().connectionManager.connections.iterator().next()
                    val wrapper = PacketWrapper.create(ServerboundPackets1_19.USE_ITEM, connection)
                    wrapper.write(Type.VAR_INT, 1) // hand: main hand
                    wrapper.write(Type.VAR_INT, sequence)
                    wrapper.sendToServer(Protocol1_19To1_18_2::class.java)
                }

                version.isNewerThanOrEqualTo(ProtocolVersion.v1_9) -> {
                    val connection = Via.getManager().connectionManager.connections.iterator().next()
                    val wrapper = PacketWrapper.create(ServerboundPackets1_9.USE_ITEM, connection)
                    wrapper.write(Type.VAR_INT, 1) // hand: main hand
                    wrapper.sendToServer(Protocol1_9To1_8::class.java)
                }

                else -> if (player.ticksExisted % 5 == 0)
                    chat("use 1.9+ for this bypass")
            }
        } catch (t: Throwable) {
            logViaError(t)
        }
    }

    /**
     * GrimVelocity 1.19+ 分支：通过 ViaVersion 注入 USE_ITEM_ON（PLAYER_BLOCK_PLACEMENT）
     * 对脚下方的方块发起"使用"，触发 Grim 的使用物品豁免。
     */
    fun sendViaUseItemOn(
        blockX: Int, blockY: Int, blockZ: Int,
        face: Int, offsetX: Float, offsetY: Float, offsetZ: Float, sequence: Int,
    ) {
        try {
            val version = ViaLoadingBase.getInstance().targetVersion
            if (version.isNewerThanOrEqualTo(ProtocolVersion.v1_19)) {
                val connection = Via.getManager().connectionManager.connections.iterator().next()
                val wrapper = PacketWrapper.create(ServerboundPackets1_19.PLAYER_BLOCK_PLACEMENT, connection)
                wrapper.write(Type.VAR_INT, 0) // hand: main hand
                wrapper.write(Type.POSITION1_14, Position(blockX, blockY, blockZ))
                wrapper.write(Type.VAR_INT, face)
                wrapper.write(Type.FLOAT, offsetX)
                wrapper.write(Type.FLOAT, offsetY)
                wrapper.write(Type.FLOAT, offsetZ)
                wrapper.write(Type.BOOLEAN, false)
                wrapper.write(Type.VAR_INT, sequence)
                wrapper.sendToServer(Protocol1_19To1_18_2::class.java)
            }
        } catch (t: Throwable) {
            logViaError(t)
        }
    }

    /** GrimNoSlow 的"换手重置"：快速发两个 C09 槽位包（8+1 槽循环）骗过物品使用减速检测。 */
    fun sendSlotSwap(currentSlot: Int) {
        PacketUtils.sendPacket(C09PacketHeldItemChange(currentSlot % 8 + 1))
        PacketUtils.sendPacket(C09PacketHeldItemChange(currentSlot))
    }

    /** 判断 C03 玩家包（或其子类）是否是位置/旋转更新包。 */
    fun isMovingPacket(packet: Packet<*>): Boolean = packet is net.minecraft.network.play.client.C03PacketPlayer

    /**
     * Rise 击退包族判定：S12（击退）、S32（事务）、S14（实体运动）、S27（爆炸）、
     * S08（lagback 传送）。GrimVelocity/GrimReduceVelocity 扣包时按此集合处理。
     */
    fun isKnockbackRelated(packet: Packet<*>): Boolean = packet is S12PacketEntityVelocity ||
        packet is S32PacketConfirmTransaction ||
        packet is S14PacketEntity ||
        packet is S27PacketExplosion ||
        packet is S08PacketPlayerPosLook

    private fun logViaError(t: Throwable) {
        if (viaErrorLogged)
            return

        viaErrorLogged = true
        chat("§c[Rise] ViaVersion call failed: ${t.message}")
        t.printStackTrace()
    }
}
