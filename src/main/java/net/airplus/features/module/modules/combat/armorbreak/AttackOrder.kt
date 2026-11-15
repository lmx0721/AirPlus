/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 * skid edmoonlight
 * Ported from Moonlight EdMoonlight NewArmorBreaker dependency
 * de.florianmichael.viamcp.fixes.AttackOrder.
 *
 * AirPlus 的 viamcp 包中没有 AttackOrder，这里用等效攻击顺序包序实现：
 * 固定 C02 攻击 -> C0A 挥手 的发包顺序（1.9+ 协议下 Grim 等反作弊要求的顺序）。
 */
package net.airplus.features.module.modules.combat.armorbreak

import net.airplus.utils.client.PacketUtils.sendPacket
import net.minecraft.client.entity.EntityPlayerSP
import net.minecraft.entity.Entity
import net.minecraft.network.play.client.C02PacketUseEntity
import net.minecraft.network.play.client.C0APacketAnimation

object AttackOrder {

    /** 走事件系统的固定攻击顺序（C02 attack + C0A animation）。 */
    fun sendFixedAttack(player: EntityPlayerSP, target: Entity) {
        sendPacket(C02PacketUseEntity(target, C02PacketUseEntity.Action.ATTACK))
        sendPacket(C0APacketAnimation())
    }

    /** 绕过事件系统直发的固定攻击顺序。 */
    fun sendFixedAttackNoPacketEvent(player: EntityPlayerSP, target: Entity) {
        sendPacket(C02PacketUseEntity(target, C02PacketUseEntity.Action.ATTACK), triggerEvent = false)
        sendPacket(C0APacketAnimation(), triggerEvent = false)
    }
}
