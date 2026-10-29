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
import net.minecraft.network.play.client.C0DPacketCloseWindow

object MoreCarry : Module("MoreCarry", Category.MISC) {

    val onPacket = handler<PacketEvent> { event ->
        if (event.packet is C0DPacketCloseWindow) {
            event.cancelEvent()
        }
    }

}