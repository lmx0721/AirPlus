/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.movement

import net.airplus.event.*
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.modules.combat.KillAura
import net.airplus.features.module.modules.movement.speedmodes.aac.AACHop3313
import net.airplus.features.module.modules.movement.speedmodes.aac.AACHop350
import net.airplus.features.module.modules.movement.speedmodes.aac.AACHop4
import net.airplus.features.module.modules.movement.speedmodes.aac.AACHop5
import net.airplus.features.module.modules.movement.speedmodes.hypixel.HypixelHop
import net.airplus.features.module.modules.movement.speedmodes.hypixel.HypixelLowHop
import net.airplus.features.module.modules.movement.speedmodes.intave.IntaveHop14
import net.airplus.features.module.modules.movement.speedmodes.matrix.MatrixHop
import net.airplus.features.module.modules.movement.speedmodes.matrix.MatrixSlowHop
import net.airplus.features.module.modules.movement.speedmodes.matrix.OldMatrixHop
import net.airplus.features.module.modules.movement.speedmodes.ncp.*
import net.airplus.features.module.modules.movement.speedmodes.other.*
import net.airplus.features.module.modules.movement.speedmodes.spartan.SpartanYPort
import net.airplus.features.module.modules.movement.speedmodes.spectre.SpectreBHop
import net.airplus.features.module.modules.movement.speedmodes.spectre.SpectreLowHop
import net.airplus.features.module.modules.movement.speedmodes.spectre.SpectreOnGround
import net.airplus.features.module.modules.movement.speedmodes.verus.VerusFHop
import net.airplus.features.module.modules.movement.speedmodes.verus.VerusHop
import net.airplus.features.module.modules.movement.speedmodes.verus.VerusLowHop
import net.airplus.features.module.modules.movement.speedmodes.verus.VerusLowHopNew
import net.airplus.features.module.modules.movement.speedmodes.vulcan.VulcanGround288
import net.airplus.features.module.modules.movement.speedmodes.vulcan.VulcanHop
import net.airplus.features.module.modules.movement.speedmodes.vulcan.VulcanLowHop
import net.airplus.utils.extensions.isMoving
import net.airplus.utils.rotation.RotationUtils

object Speed : Module("Speed", Category.MOVEMENT) {

    private val speedModes = arrayOf(

        // NCP
        NCPBHop,
        NCPFHop,
        SNCPBHop,
        NCPHop,
        NCPYPort,
        UNCPHop,
        UNCPHopNew,

        // AAC
        AACHop3313,
        AACHop350,
        AACHop4,
        AACHop5,

        // Spartan
        SpartanYPort,

        // Spectre
        SpectreLowHop,
        SpectreBHop,
        SpectreOnGround,

        // Verus
        VerusHop,
        VerusFHop,
        VerusLowHop,
        VerusLowHopNew,

        // Vulcan
        VulcanHop,
        VulcanLowHop,
        VulcanGround288,

        // Matrix
        OldMatrixHop,
        MatrixHop,
        MatrixSlowHop,

        // Intave
        IntaveHop14,

        // Server specific
        TeleportCubeCraft,
        HypixelHop,
        HypixelLowHop,
        BlocksMCHop,

        // Other
        Boost,
        Frame,
        MiJump,
        OnGround,
        SlowHop,
        Legit,
        OldGrim,
        CustomSpeed
    )

    /**
     * Old/Deprecated Modes
     */
    private val deprecatedMode = arrayOf(
        TeleportCubeCraft,

        OldMatrixHop,

        VerusLowHop,

        SpectreLowHop, SpectreBHop, SpectreOnGround,

        AACHop3313, AACHop350, AACHop4,

        NCPBHop, NCPFHop, SNCPBHop, NCPHop, NCPYPort,

        MiJump, Frame
    )

    private val showDeprecated by boolean("DeprecatedMode", true).onChanged { value ->
        mode.changeValue(modesList.first { it !in deprecatedMode }.modeName)
        mode.updateValues(modesList.filter { value || it !in deprecatedMode }.map { it.modeName }.toTypedArray())
    }

    private var modesList = speedModes

    val mode = choices("Mode", modesList.map { it.modeName }.toTypedArray(), "NCPBHop")

    // Custom Speed
    val customY by float("CustomY", 0.42f, 0f..4f) { mode.get() == "Custom" }
    val customGroundStrafe by float("CustomGroundStrafe", 1.6f, 0f..2f) { mode.get() == "Custom" }
    val customAirStrafe by float("CustomAirStrafe", 0f, 0f..2f) { mode.get() == "Custom" }
    val customGroundTimer by float("CustomGroundTimer", 1f, 0.1f..2f) { mode.get() == "Custom" }
    val customAirTimerTick by int("CustomAirTimerTick", 5, 1..20) { mode.get() == "Custom" }
    val customAirTimer by float("CustomAirTimer", 1f, 0.1f..2f) { mode.get() == "Custom" }

    // Extra options
    val resetXZ by boolean("ResetXZ", false) { mode.get() == "Custom" }
    val resetY by boolean("ResetY", false) { mode.get() == "Custom" }
    val notOnConsuming by boolean("NotOnConsuming", false) { mode.get() == "Custom" }
    val notOnFalling by boolean("NotOnFalling", false) { mode.get() == "Custom" }
    val notOnVoid by boolean("NotOnVoid", true) { mode.get() == "Custom" }

    // TeleportCubecraft Speed
    val cubecraftPortLength by float("CubeCraft-PortLength", 1f, 0.1f..2f)
    { mode.get() == "TeleportCubeCraft" }

    // IntaveHop14 Speed
    val boost by boolean("Boost", true) { mode.get() == "IntaveHop14" }
    val initialBoostMultiplier by float("InitialBoostMultiplier", 1f, 0.01f..10f)
    { boost && mode.get() == "IntaveHop14" }
    val intaveLowHop by boolean("LowHop", true) { mode.get() == "IntaveHop14" }
    val strafeStrength by float("StrafeStrength", 0.29f, 0.1f..0.29f)
    { mode.get() == "IntaveHop14" }
    val groundTimer by float("GroundTimer", 0.5f, 0.1f..5f) { mode.get() == "IntaveHop14" }
    val airTimer by float("AirTimer", 1.09f, 0.1f..5f) { mode.get() == "IntaveHop14" }

    // UNCPHopNew Speed
    private val pullDown by boolean("PullDown", true) { mode.get() == "UNCPHopNew" }
    val onTick by int("OnTick", 5, 5..9) { pullDown && mode.get() == "UNCPHopNew" }
    val onHurt by boolean("OnHurt", true) { pullDown && mode.get() == "UNCPHopNew" }
    val shouldBoost by boolean("ShouldBoost", true) { mode.get() == "UNCPHopNew" }
    val timerBoost by boolean("TimerBoost", true) { mode.get() == "UNCPHopNew" }
    val damageBoost by boolean("DamageBoost", true) { mode.get() == "UNCPHopNew" }
    val lowHop by boolean("LowHop", true) { mode.get() == "UNCPHopNew" }
    val airStrafe by boolean("AirStrafe", true) { mode.get() == "UNCPHopNew" }

    // MatrixHop Speed
    val matrixLowHop by boolean("LowHop", true)
    { mode.get() == "MatrixHop" || mode.get() == "MatrixSlowHop" }
    val extraGroundBoost by float("ExtraGroundBoost", 0.2f, 0f..0.5f)
    { mode.get() == "MatrixHop" || mode.get() == "MatrixSlowHop" }

    // HypixelLowHop Speed
    val glide by boolean("Glide", true) { mode.get() == "HypixelLowHop" }

    // BlocksMCHop Speed
    val fullStrafe by boolean("FullStrafe", true) { mode.get() == "BlocksMCHop" }
    val bmcLowHop by boolean("LowHop", true) { mode.get() == "BlocksMCHop" }
    val bmcDamageBoost by boolean("DamageBoost", true) { mode.get() == "BlocksMCHop" }
    val damageLowHop by boolean("DamageLowHop", false) { mode.get() == "BlocksMCHop" }
    val safeY by boolean("SafeY", true) { mode.get() == "BlocksMCHop" }

    // OldGrim Speed（触碰实体加速，Ported from SilenceFix）
    val oldGrimSpeed by float("OldGrim-Speed", 5f, 1f..8f) { mode.get() == "OldGrim" }
    val oldGrimTargetStrafe by boolean("OldGrim-TargetStrafe", true) { mode.get() == "OldGrim" }
    val oldGrimOnlyJump by boolean("OldGrim-OnlyJump", true) { oldGrimTargetStrafe && mode.get() == "OldGrim" }
    val oldGrimHurtTimeCheck by boolean("OldGrim-HurtTimeCheck", false) { mode.get() == "OldGrim" }
    val oldGrimBehindTarget by boolean("OldGrim-BehindTarget", false) { mode.get() == "OldGrim" }

    val onUpdate = handler<UpdateEvent> {
        val thePlayer = mc.thePlayer ?: return@handler

        if (thePlayer.isSneaking)
            return@handler

        if (thePlayer.isMoving && !sprintManually)
            thePlayer.isSprinting = true

        modeModule.onUpdate()
    }

    val onMotion = handler<MotionEvent> { event ->
        val thePlayer = mc.thePlayer ?: return@handler

        // OldGrim-TargetStrafe：按住空格时 KillAura 的移动修复表现为 strict（绕过旋转输入重建）
        RotationUtils.forceStrict = mode.get() == "OldGrim" && oldGrimTargetStrafe &&
            KillAura.target != null && mc.gameSettings.keyBindJump.isKeyDown

        if (thePlayer.isSneaking || event.eventState != EventState.PRE)
            return@handler

        if (thePlayer.isMoving && !sprintManually)
            thePlayer.isSprinting = true

        modeModule.onMotion()
    }

    val onMove = handler<MoveEvent> { event ->
        if (mc.thePlayer?.isSneaking == true)
            return@handler

        modeModule.onMove(event)
    }

    val tickHandler = handler<GameTickEvent> {
        if (mc.thePlayer?.isSneaking == true)
            return@handler

        modeModule.onTick()
    }

    val onStrafe = handler<StrafeEvent> {
        if (mc.thePlayer?.isSneaking == true)
            return@handler

        modeModule.onStrafe()
    }

    val onJump = handler<JumpEvent> { event ->
        if (mc.thePlayer?.isSneaking == true)
            return@handler

        modeModule.onJump(event)
    }

    val onPacket = handler<PacketEvent> { event ->
        if (mc.thePlayer?.isSneaking == true)
            return@handler

        modeModule.onPacket(event)
    }

    override fun onEnable() {
        if (mc.thePlayer == null)
            return

        mc.timer.timerSpeed = 1f

        modeModule.onEnable()
    }

    override fun onDisable() {
        RotationUtils.forceStrict = false

        if (mc.thePlayer == null)
            return

        mc.timer.timerSpeed = 1f
        mc.thePlayer.speedInAir = 0.02f

        modeModule.onDisable()
    }

    override val tag
        get() = mode.get()

    private val modeModule
        get() = speedModes.find { it.modeName == mode.get() }!!

    private val sprintManually
        // Maybe there are more but for now there's the Legit mode.get().
        get() = modeModule in arrayOf(Legit)
}
