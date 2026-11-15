/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.movement

import net.airplus.event.JumpEvent
import net.airplus.event.MotionEvent
import net.airplus.event.MoveEvent
import net.airplus.event.PacketEvent
import net.airplus.event.UpdateEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.modules.movement.longjumpmodes.aac.AACv1
import net.airplus.features.module.modules.movement.longjumpmodes.aac.AACv2
import net.airplus.features.module.modules.movement.longjumpmodes.aac.AACv3
import net.airplus.features.module.modules.movement.longjumpmodes.ncp.NCP
import net.airplus.features.module.modules.movement.longjumpmodes.other.Buzz
import net.airplus.features.module.modules.movement.longjumpmodes.other.Hycraft
import net.airplus.features.module.modules.movement.longjumpmodes.other.Redesky
import net.airplus.features.module.modules.movement.longjumpmodes.other.VerusDamage
import net.airplus.features.module.modules.movement.longjumpmodes.other.VerusDamage.damaged
import net.airplus.features.module.modules.movement.longjumpmodes.rise.Rise as RiseLongJump
import net.airplus.utils.extensions.isMoving
import net.airplus.utils.extensions.tryJump

object LongJump : Module("LongJump", Category.MOVEMENT) {

    private val longJumpModes = arrayOf(
        // NCP
        NCP,

        // AAC
        AACv1, AACv2, AACv3,

        // Other
        Redesky, Hycraft, Buzz, VerusDamage,

        // Rise
        RiseLongJump
    )

    private val modes = longJumpModes.map { it.modeName }.toTypedArray()

    val mode by choices("Mode", modes, "NCP")
    val ncpBoost by float("NCPBoost", 4.25f, 1f..10f) { mode == "NCP" }

    private val autoJump by boolean("AutoJump", true)

    val autoDisable by boolean("AutoDisable", true) { mode == "VerusDamage" }

    var jumped = false
    var canBoost = false
    var teleported = false

    val onUpdate = handler<UpdateEvent> {
        if (jumped) {
            val mode = mode

            if (mc.thePlayer.onGround || mc.thePlayer.capabilities.isFlying) {
                jumped = false

                if (mode == "NCP") {
                    mc.thePlayer.motionX = 0.0
                    mc.thePlayer.motionZ = 0.0
                }
                return@handler
            }

            modeModule.onUpdate()
        }
        if (autoJump && mc.thePlayer.onGround && mc.thePlayer.isMoving) {
            if (autoDisable && !damaged) {
                return@handler
            }

            jumped = true
            mc.thePlayer.tryJump()
        }
    }

    val onMove = handler<MoveEvent> { event ->
        modeModule.onMove(event)
    }

    val onMotion = handler<MotionEvent> { event ->
        modeModule.onMotion(event)
    }

    val onPacket = handler<PacketEvent> { event ->
        mc.thePlayer ?: return@handler

        modeModule.onPacket(event)
    }

    override fun onEnable() {
        modeModule.onEnable()
    }

    override fun onDisable() {
        modeModule.onDisable()
    }

    val onJump = handler<JumpEvent>(always = true) { event ->
        jumped = true
        canBoost = true
        teleported = false

        if (handleEvents()) {
            modeModule.onJump(event)
        }
    }

    override val tag
        get() = mode

    private val modeModule
        get() = longJumpModes.find { it.modeName == mode }!!
}
