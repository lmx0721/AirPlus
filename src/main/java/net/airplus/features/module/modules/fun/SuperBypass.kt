/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.`fun`

import net.airplus.event.EventState
import net.airplus.event.PacketEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.client.PacketUtils.sendPacket
import net.minecraft.network.play.client.C03PacketPlayer.C06PacketPlayerPosLook
import net.minecraft.network.play.server.S08PacketPlayerPosLook
import net.minecraft.network.play.server.S12PacketEntityVelocity
import net.minecraft.network.play.server.S27PacketExplosion
import kotlin.math.abs

/**
 * SuperBypass —— 无脑吞掉服务器所有会"拉回/击退"玩家的包。
 *
 * S08 处理策略（纯取消会导致服务器冻结移动 / 重进回档，必须带确认）：
 * - 小距离修正（< 100 格，反作弊回传都是这种）：取消 S08（客户端不拉回），
 *   立刻用 S08 的坐标伪造 C06 确认包 —— 服务器认为传送完成、解除移动冻结，
 *   下一个真实移动包与服务器位置差 < 100 会被正常接受，全程无拉回无回档；
 * - 大距离传送（>= 100 格，传送门 / 指令 / 跨世界）：放行，吞了会卡死在原地；
 * - 进服/重生前 40 tick、死亡、骑乘时不吞（登录位置 / 载具传送被吞会直接卡死）。
 *
 * S12PacketEntityVelocity：击退包，仅吞发给自己的；
 * S27PacketExplosion：爆炸击退，仅当带位移时吞（普通爆炸的方块破坏不受影响）。
 *
 * 后果自负：位移与服务器不一致仍会被部分反作弊标记。
 */
object SuperBypass : Module("SuperBypass", Category.FUN, gameDetecting = false) {

    // 进服/重生后的宽限 tick 数，期间不吞 S08
    private const val S08_GRACE_TICKS = 40

    // 超过该距离的 S08 视为合法传送直接放行（100 = vanilla "moved too quickly" 阈值）
    private const val S08_MAX_SPOOF_DISTANCE = 100.0

    val onPacket = handler<PacketEvent> { event ->
        if (event.eventType != EventState.RECEIVE) return@handler
        val player = mc.thePlayer ?: return@handler

        when (event.packet) {
            is S08PacketPlayerPosLook -> {
                val packet = event.packet
                val flags = packet.func_179834_f()

                // 计算绝对目标坐标（S08 可能带相对 flag）
                val absX = if (flags.contains(S08PacketPlayerPosLook.EnumFlags.X)) player.posX + packet.x else packet.x
                val absY = if (flags.contains(S08PacketPlayerPosLook.EnumFlags.Y)) player.posY + packet.y else packet.y
                val absZ = if (flags.contains(S08PacketPlayerPosLook.EnumFlags.Z)) player.posZ + packet.z else packet.z
                val absYaw = if (flags.contains(S08PacketPlayerPosLook.EnumFlags.Y_ROT)) player.rotationYaw + packet.yaw else packet.yaw
                val absPitch = if (flags.contains(S08PacketPlayerPosLook.EnumFlags.X_ROT)) player.rotationPitch + packet.pitch else packet.pitch

                val dx = abs(absX - player.posX)
                val dy = abs(absY - player.posY)
                val dz = abs(absZ - player.posZ)

                val legitTeleport = player.ticksExisted < S08_GRACE_TICKS ||
                    player.isDead ||
                    player.isRiding ||
                    dx > S08_MAX_SPOOF_DISTANCE || dy > S08_MAX_SPOOF_DISTANCE || dz > S08_MAX_SPOOF_DISTANCE

                if (legitTeleport)
                    return@handler

                // 客户端不拉回
                event.cancelEvent()

                // 伪造传送确认：让服务器解除移动冻结，位置差 < 100 的后续移动照常接受
                sendPacket(C06PacketPlayerPosLook(absX, absY, absZ, absYaw, absPitch, true))
            }

            is S12PacketEntityVelocity ->
                if (event.packet.entityID == player.entityId)
                    event.cancelEvent()

            is S27PacketExplosion ->
                // 只有爆炸附带击退位移时才吞（field_149152_f/g/h = motionX/Y/Z）
                if (event.packet.field_149152_f != 0F ||
                    event.packet.field_149153_g != 0F ||
                    event.packet.field_149159_h != 0F
                ) event.cancelEvent()
        }
    }
}
