/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.movement.speedmodes

import net.airplus.event.JumpEvent
import net.airplus.event.MoveEvent
import net.airplus.event.MovementInputEvent
import net.airplus.event.PacketEvent
import net.airplus.utils.client.MinecraftInstance

open class SpeedMode(val modeName: String) : MinecraftInstance {
    open fun onMotion() {}
    open fun onMotionPost() {}
    open fun onMovementInput(event: MovementInputEvent) {}
    open fun onUpdate() {}
    open fun onMove(event: MoveEvent) {}
    open fun onTick() {}
    open fun onStrafe() {}
    open fun onJump(event: JumpEvent) {}
    open fun onPacket(event: PacketEvent) {}
    open fun onEnable() {}
    open fun onDisable() {}
}