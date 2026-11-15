/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.movement

import net.airplus.event.EventState
import net.airplus.event.GameTickEvent
import net.airplus.event.PacketEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.block.BlockUtils.collideBlockIntersects
import net.airplus.utils.client.PacketUtils.sendPacket
import net.minecraft.init.Blocks
import net.minecraft.network.Packet
import net.minecraft.network.play.client.C03PacketPlayer
import net.minecraft.network.play.client.C03PacketPlayer.C06PacketPlayerPosLook
import java.util.ArrayList

/**
 * Phase（Ported from Rise GrimPhase）。
 *
 * 原理：开启后扣住所有 C03 位置包（服务器视角冻结在原地），检测到玩家身体进入
 * 方块时补发 [semiPackets] 个当前位置的 C06 包宣告"入相位"，脱离方块后自动关闭，
 * 关闭时按 ReleaseMode 发送特殊的 C06 包组合，把服务器位置"传送"回相位内，
 * 最后回放扣住的位置包完成相位同步。
 *
 * 事件映射：TickEvent → GameTickEvent；PlayerUtil.vk()（体内方块检测）→
 * collideBlockIntersects(entityBoundingBox)；PreMotionEvent 空监听器省略。
 */
object Phase : Module("Phase", Category.MOVEMENT) {

    private val releaseMode by choices("ReleaseMode", arrayOf("Simple", "Double", "Desync", "None"), "Simple")
    private val semiPackets by int("SemiPackets", 2, 1..15)

    private val heldPackets = ArrayList<Packet<*>>()
    private var phasing = false
    private var selfDisabled = false

    val onPacket = handler<PacketEvent> { event ->
        if (event.eventType != EventState.SEND)
            return@handler

        mc.thePlayer ?: return@handler

        val packet = event.packet
        if (packet is C03PacketPlayer) {
            heldPackets.add(packet)
            event.cancelEvent()
        }
    }

    val onTick = handler<GameTickEvent> {
        val player = mc.thePlayer ?: return@handler
        mc.theWorld ?: return@handler

        // 体内方块检测（PlayerUtil.vk 的近似实现）
        val insideBlock = collideBlockIntersects(player.entityBoundingBox) { it != Blocks.air }

        if (!phasing && insideBlock) {
            // 入相位：补发 N 个当前位置的 C06 包
            val x = player.posX
            val y = player.posY
            val z = player.posZ
            val yaw = player.rotationYaw
            val pitch = player.rotationPitch
            val onGround = player.onGround

            repeat(semiPackets) {
                sendPacket(C06PacketPlayerPosLook(x, y, z, yaw, pitch, onGround), false)
            }

            phasing = true
        } else if (phasing && !insideBlock) {
            // 脱离方块：自动关闭
            selfDisabled = true
            state = false
        }
    }

    override fun onEnable() {
        heldPackets.clear()
        phasing = false
        selfDisabled = false
    }

    override fun onDisable() {
        val player = mc.thePlayer

        if (!selfDisabled && phasing && player != null) {
            if (releaseMode != "None")
                release(releaseMode)
            else
                sendPacket(
                    C06PacketPlayerPosLook(
                        player.posX, player.posY, player.posZ,
                        player.rotationYaw, player.rotationPitch, player.onGround
                    ), false
                )
        }

        // 回放扣住的位置包
        if (player != null && heldPackets.isNotEmpty()) {
            heldPackets.forEach { sendPacket(it, false) }
            heldPackets.clear()
        }

        phasing = false
        selfDisabled = false
    }

    private fun release(mode: String) {
        val player = mc.thePlayer ?: return

        val x = player.posX
        val y = player.posY
        val z = player.posZ
        val yaw = player.rotationYaw
        val pitch = player.rotationPitch
        val onGround = player.onGround

        when (mode.lowercase()) {
            "simple" -> {
                sendPacket(C06PacketPlayerPosLook(x - 5000.0, y, z - 5000.0, yaw, pitch, false), false)
                sendPacket(C06PacketPlayerPosLook(x, y, z, yaw, pitch, onGround), false)
            }

            "double" -> {
                sendPacket(C06PacketPlayerPosLook(x - 5000.0, y, z - 5000.0, yaw, pitch, false), false)
                sendPacket(C06PacketPlayerPosLook(x + 5000.0, y, z + 5000.0, yaw, pitch, false), false)
                sendPacket(C06PacketPlayerPosLook(x, y, z, yaw, pitch, onGround), false)
            }

            "desync" -> {
                sendPacket(C06PacketPlayerPosLook(x, y + 0.0625, z, yaw, pitch, false), false)
                sendPacket(C06PacketPlayerPosLook(x, y, z, yaw, pitch, false), false)
                sendPacket(C06PacketPlayerPosLook(x, y + 0.03125, z, yaw, pitch, true), false)
                sendPacket(C06PacketPlayerPosLook(x, y, z, yaw, pitch, onGround), false)
            }
        }
    }

    override val tag
        get() = "Rise"
}
