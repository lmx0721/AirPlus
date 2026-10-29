/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.misc

import net.airplus.event.UpdateEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.client.chat
import net.minecraft.client.gui.GuiGameOver

object Ghost : Module("Ghost", Category.MISC) {

    private var isGhost = false

    val onUpdate = handler<UpdateEvent> {
        if (mc.currentScreen is GuiGameOver) {
            mc.displayGuiScreen(null)
            mc.thePlayer.isDead = false
            mc.thePlayer.health = 20F
            mc.thePlayer.setPositionAndUpdate(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ)
            isGhost = true

            chat("§cYou are now a ghost.")
        }
    }

    override fun onDisable() {
        if (isGhost && mc.thePlayer != null) {
            mc.thePlayer.respawnPlayer()
            isGhost = false
        }
    }

}