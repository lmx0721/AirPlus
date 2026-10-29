/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.misc

import net.airplus.event.KeyEvent
import net.airplus.event.PacketEvent
import net.airplus.event.ScreenEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.client.PacketUtils.sendPacket
import net.minecraft.client.gui.inventory.GuiContainer
import net.minecraft.client.gui.inventory.GuiInventory
import net.minecraft.network.play.client.C0DPacketCloseWindow
import net.minecraft.network.play.server.S2EPacketCloseWindow
import org.lwjgl.input.Keyboard

object KeepContainer : Module("KeepContainer", Category.MISC) {
    private var container: GuiContainer? = null

    override fun onDisable() {
        if (container != null)
            sendPacket(C0DPacketCloseWindow(container!!.inventorySlots.windowId))

        container = null
    }

    val onGui = handler<ScreenEvent> { event ->
        if (event.guiScreen is GuiContainer && event.guiScreen !is GuiInventory)
            container = event.guiScreen
    }

    val onKey = handler<KeyEvent> { event ->
        if (event.key == Keyboard.KEY_INSERT) {
            if (container == null)
                return@handler

            mc.displayGuiScreen(container)
        }
    }

    val onPacket = handler<PacketEvent> { event ->
        when (event.packet) {
            is C0DPacketCloseWindow -> event.cancelEvent()
            is S2EPacketCloseWindow -> {
                if (event.packet.windowId == container?.inventorySlots?.windowId)
                    container = null
            }
        }
    }
}