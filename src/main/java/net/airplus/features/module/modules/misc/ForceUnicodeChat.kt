/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.misc

import net.airplus.event.PacketEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.minecraft.network.play.client.C01PacketChatMessage

object ForceUnicodeChat :
    Module("ForceUnicodeChat", Category.MISC, subjective = true, gameDetecting = false) {

    val onPacket = handler<PacketEvent> { event ->
        if (event.packet is C01PacketChatMessage) {
            val chatMessage = event.packet
            val message = chatMessage.message

            if (message.startsWith('/')) return@handler

            val stringBuilder = StringBuilder()

            for (c in message.toCharArray())
                if (c.code in 33..128)
                    stringBuilder.append(Character.toChars(c.code + 65248)) else stringBuilder.append(c)

            chatMessage.message = stringBuilder.toString()
        }
    }

}