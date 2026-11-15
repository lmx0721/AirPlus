/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.movement.longjumpmodes

import net.airplus.event.JumpEvent
import net.airplus.event.MotionEvent
import net.airplus.event.MoveEvent
import net.airplus.event.PacketEvent
import net.airplus.utils.client.MinecraftInstance

open class LongJumpMode(val modeName: String) : MinecraftInstance {
    open fun onUpdate() {}
    open fun onMove(event: MoveEvent) {}
    open fun onJump(event: JumpEvent) {}
    open fun onMotion(event: MotionEvent) {}
    open fun onPacket(event: PacketEvent) {}

    open fun onEnable() {}
    open fun onDisable() {}
}