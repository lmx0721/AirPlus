/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 * skid edmoonlight
 */
package net.airplus.features.module.modules.player.gapple

import net.minecraft.client.entity.EntityPlayerSP
import net.minecraft.network.play.client.C03PacketPlayer
import net.minecraft.potion.Potion

/**
 * moonlight ArmorBreakerServerModel 中 Gapple EdMoonlight 模式（Crit Freeze）实际用到的子集：
 * 维护服务端视角的 onGround / fallDistance / 最近位置，用于预测下一次攻击是否暴击。
 *
 * 相比原版裁剪掉的部分：wireHits / kb 预测 / srvStep 跳跃模拟 / Attr 物品伤害跟踪
 * （那些服务于 moonlight NewArmorBreaker 的攻击流量预测，与 Gapple 无关）。
 */
object GrimServerModel {
    private var serverOnGround = true
    private var serverFallDistance = 0.0f
    private var lastX = 0.0
    private var lastY = Double.NaN
    private var lastZ = 0.0

    /**
     * 对齐 ArmorBreakerServerModel.onWire 的 C03 分支（去掉 srvStep/kb 模拟）。
     */
    fun onWire(packet: C03PacketPlayer) {
        val onGround = packet.onGround

        if (packet.isMoving) {
            val posY = packet.y
            val delta = if (lastY.isNaN()) 0.0 else posY - lastY
            lastY = posY
            lastX = packet.x
            lastZ = packet.z

            if (onGround) {
                serverFallDistance = 0.0f
            } else if (delta < 0.0 && delta > -10.0) {
                serverFallDistance -= delta.toFloat()
            }
        } else if (onGround) {
            serverFallDistance = 0.0f
        }

        serverOnGround = onGround
    }

    /** 对齐 ArmorBreakerServerModel.predictCrit。 */
    fun predictCrit(player: EntityPlayerSP): Boolean {
        return serverFallDistance > 0.0f
            && !serverOnGround
            && !player.isOnLadder
            && !player.isInWater
            && !player.isPotionActive(Potion.blindness)
            && player.ridingEntity == null
    }

    /** 对齐 ArmorBreakerServerModel.predictCritAfter：预测「发送该包之后」的暴击状态。 */
    fun predictCritAfter(player: EntityPlayerSP, packet: C03PacketPlayer): Boolean {
        val onGround = packet.onGround
        var fallDistance = serverFallDistance

        if (onGround) {
            fallDistance = 0.0f
        } else if (packet.isMoving && !lastY.isNaN()) {
            val delta = packet.y - lastY
            if (delta < 0.0 && delta > -10.0) {
                fallDistance -= delta.toFloat()
            }
        }

        return fallDistance > 0.0f
            && !onGround
            && !player.isOnLadder
            && !player.isInWater
            && !player.isPotionActive(Potion.blindness)
            && player.ridingEntity == null
    }

    fun reset() {
        serverOnGround = true
        serverFallDistance = 0.0f
        lastY = Double.NaN
    }
}
