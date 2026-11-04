/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.world.scaffolds

import net.airplus.event.*
import net.airplus.event.async.loopSequence
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.modules.player.Blink
import net.airplus.features.module.modules.render.NoSwing
import net.airplus.utils.attack.CPSCounter
import net.airplus.utils.block.*
import net.airplus.utils.client.BlinkUtils
import net.airplus.utils.client.PacketUtils.sendPacket
import net.airplus.utils.extensions.*
import net.airplus.utils.inventory.InventoryUtils
import net.airplus.utils.inventory.InventoryUtils.blocksAmount
import net.airplus.utils.inventory.SilentHotbar
import net.airplus.utils.inventory.hotBarSlot
import net.airplus.utils.kotlin.RandomUtils
import net.airplus.utils.movement.MovementUtils
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.render.drawWithTessellatorWorldRenderer
import net.airplus.utils.rotation.PlaceRotation
import net.airplus.utils.rotation.Rotation
import net.airplus.utils.rotation.RotationSettingsWithRotationModes
import net.airplus.utils.rotation.RotationUtils
import net.airplus.utils.rotation.RotationUtils.canUpdateRotation
import net.airplus.utils.rotation.RotationUtils.getFixedAngleDelta
import net.airplus.utils.rotation.RotationUtils.getVectorForRotation
import net.airplus.utils.rotation.RotationUtils.rotationDifference
import net.airplus.utils.rotation.RotationUtils.setTargetRotation
import net.airplus.utils.rotation.RotationUtils.toRotation
import net.airplus.utils.simulation.SimulatedPlayer
import net.airplus.utils.timing.*
import net.minecraft.block.BlockBush
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.client.settings.GameSettings
import net.minecraft.init.Blocks.air
import net.minecraft.item.ItemBlock
import net.minecraft.item.ItemStack
import net.minecraft.network.play.client.C0BPacketEntityAction
import net.minecraft.util.*
import net.minecraft.world.WorldSettings
import net.minecraftforge.event.ForgeEventFactory
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11
import java.awt.Color
import kotlin.math.*

object Scaffold : Module("Scaffold", Category.WORLD, Keyboard.KEY_I) {

    /**
     * TOWER MODES & SETTINGS
     */

    // -->

    private val towerMode by Tower.towerModeValues

    init {
        addValues(Tower.values)
    }

    // <--

    /**
     * SCAFFOLD MODES & SETTINGS
     */

    // -->

    val scaffoldMode by choices(
        "ScaffoldMode", arrayOf("Normal", "Rewinside", "Expand", "Telly", "GodBridge"), "Normal"
    )

    // Expand
    private val omniDirectionalExpand by boolean("OmniDirectionalExpand", false) { scaffoldMode == "Expand" }
    private val expandLength by int("ExpandLength", 1, 1..6) { scaffoldMode == "Expand" }

    // Placeable delay
    private val placeDelayValue = boolean("PlaceDelay", true) { scaffoldMode != "GodBridge" }
    private val delay by intRange("Delay", 0..0, 0..1000) { placeDelayValue.isActive() }

    // Extra clicks
    private val extraClicks by boolean("DoExtraClicks", false)
    private val simulateDoubleClicking by boolean("SimulateDoubleClicking", false) { extraClicks }
    private val extraClickCPS by intRange("ExtraClickCPS", 3..7, 0..50) { extraClicks }
    private val placementAttempt by choices(
        "PlacementAttempt", arrayOf("Fail", "Independent"), "Fail"
    ) { extraClicks }

    // Autoblock
    private val autoBlock by choices("AutoBlock", arrayOf("Off", "Pick", "Spoof", "Switch"), "Spoof")
    private val sortByHighestAmount by boolean("SortByHighestAmount", false) { autoBlock != "Off" }
    private val earlySwitch by boolean("EarlySwitch", false) { autoBlock != "Off" && !sortByHighestAmount }
    private val amountBeforeSwitch by int(
        "SlotAmountBeforeSwitch", 3, 1..10
    ) { earlySwitch && !sortByHighestAmount }

    // Settings
    private val autoF5 by boolean("AutoF5", false).subjective()

    // Basic stuff
    val sprint by boolean("Sprint", false)
    private val swing by boolean("Swing", true).subjective()
    private val down by boolean("Down", true) { !sameY && scaffoldMode !in arrayOf("GodBridge", "Telly") }

    private val ticksUntilRotation by intRange("TicksUntilRotation", 3..3, 1..8) {
        scaffoldMode == "Telly"
    }

    // GodBridge mode sub-values
    private val waitForRots by boolean("WaitForRotations", false) { isGodBridgeEnabled }
    private val useOptimizedPitch by boolean("UseOptimizedPitch", false) { isGodBridgeEnabled }
    private val customGodPitch by float(
        "GodBridgePitch", 73.5f, 0f..90f
    ) { isGodBridgeEnabled && !useOptimizedPitch }

    val jumpAutomatically by boolean("JumpAutomatically", true) { scaffoldMode == "GodBridge" }
    private val blocksToJumpRange by intRange("BlocksToJumpRange", 4..4, 1..8) {  scaffoldMode == "GodBridge" && !jumpAutomatically }

    // Telly mode sub-values
    private val startHorizontally by boolean("StartHorizontally", true) { scaffoldMode == "Telly" }
    private val horizontalPlacementsRange by intRange("HorizontalPlacementsRange", 1..1, 1..10) { scaffoldMode == "Telly" }
    private val verticalPlacementsRange by intRange("VerticalPlacementsRange", 1..1, 1..10) { scaffoldMode == "Telly" }

    private val jumpTicksRange by intRange("JumpTicksRange", 0..0, 0..10) { scaffoldMode == "Telly" }

    // KeepHeadDown：Telly 重置角度（回归视角）时 Pitch 锁定垂直低头，Yaw 照常回归；寻找方块的旋转逻辑不变
    private val tellyKeepHeadDown by boolean("TellyKeepHeadDown", false) { scaffoldMode == "Telly" }

    // Telly 起跳门控：服务器视角 Yaw 与玩家瞄准 Yaw 相差不超过该角度即允许起跳（不要求 Pitch 收敛）
    private val tellyJumpYawDifference by float("TellyJumpYawDifference", 30f, 5f..180f) { scaffoldMode == "Telly" }

    private val allowClutching by boolean("AllowClutching", true) { scaffoldMode !in arrayOf("Telly", "Expand") }
    private val horizontalClutchBlocks by int("HorizontalClutchBlocks", 3, 1..5) {
        allowClutching && scaffoldMode !in arrayOf("Telly", "Expand")
    }
    private val verticalClutchBlocks by int("VerticalClutchBlocks", 2, 1..3) {
        allowClutching && scaffoldMode !in arrayOf("Telly", "Expand")
    }
    private val blockSafe by boolean("BlockSafe", false) { !isGodBridgeEnabled }

    // Eagle
    private val eagleValue =
        choices("Eagle", arrayOf("Normal", "Silent", "Off"), "Normal") { scaffoldMode != "GodBridge" }
    val eagle by eagleValue
    private val eagleMode by choices("EagleMode", arrayOf("Both", "OnGround", "InAir"), "Both")
    { eagle != "Off" && scaffoldMode != "GodBridge" }
    private val adjustedSneakSpeed by boolean("AdjustedSneakSpeed", true)
    { eagle == "Silent" && scaffoldMode != "GodBridge" }
    private val eagleSpeed by float("EagleSpeed", 0.3f, 0.3f..1.0f) { eagle != "Off" && scaffoldMode != "GodBridge" }
    val eagleSprint by boolean("EagleSprint", false) { eagle == "Normal" && scaffoldMode != "GodBridge" }
    private val blocksToEagle by intRange("BlocksToEagle", 0..0, 0..10) { eagle != "Off" && scaffoldMode != "GodBridge" }
    private val edgeDistance by float("EagleEdgeDistance", 0f, 0f..0.5f)
    { eagle != "Off" && scaffoldMode != "GodBridge" }
    private val useMaxSneakTime by boolean("UseMaxSneakTime", true) { eagle != "Off" && scaffoldMode != "GodBridge" }
    private val maxSneakTicks by intRange("MaxSneakTicks", 3..3, 0..10) { useMaxSneakTime }
    private val blockSneakingAgainUntilOnGround by boolean("BlockSneakingAgainUntilOnGround", true)
    { useMaxSneakTime && eagleMode != "OnGround" }

    // Rotation Options
    private val modeList =
        choices("Rotations", arrayOf("Off", "Normal", "Stabilized", "ReverseYaw", "GodBridge"), "Normal")

    private val options = RotationSettingsWithRotationModes(this, modeList).apply {
        strictValue.excludeWithState()
        resetTicksValue.setSupport { it }
    }

    // ReverseYaw 提前转头：启用模块后立刻开始转头，方向取自移动输入（无输入则按当前视角朝向为正方向）
    private val reverseYawAhead by boolean("ReverseYawAhead", true) { options.rotationMode == "ReverseYaw" }

    // Search options
    val searchMode by choices("SearchMode", arrayOf("Area", "Center"), "Area") { scaffoldMode != "GodBridge" }
    private val minDist by float("MinDist", 0f, 0f..0.2f) { scaffoldMode !in arrayOf("GodBridge", "Telly") }

    // Zitter
    private val zitterMode by choices("Zitter", arrayOf("Off", "Teleport", "Smooth"), "Off")
    private val zitterSpeed by float("ZitterSpeed", 0.13f, 0.1f..0.3f) { zitterMode == "Teleport" }
    private val zitterStrength by float("ZitterStrength", 0.05f, 0f..0.2f) { zitterMode == "Teleport" }
    private val zitterTicks by intRange("ZitterTicks", 2..3, 0..6) { zitterMode == "Smooth" }

    private val useSneakMidAir by boolean("UseSneakMidAir", false) { zitterMode == "Smooth" }

    // Game
    val timer by float("Timer", 1f, 0.1f..10f)
    private val speedModifier by float("SpeedModifier", 1f, 0f..2f)
    private val speedLimiter by boolean("SpeedLimiter", false) { !slow }
    private val speedLimit by float("SpeedLimit", 0.11f, 0.01f..0.12f) { !slow && speedLimiter }
    private val slow by boolean("Slow", false)
    private val slowGround by boolean("SlowOnlyGround", false) { slow }
    private val slowSpeed by float("SlowSpeed", 0.6f, 0.2f..0.8f) { slow }

    // Jump Strafe
    private val jumpStrafe by boolean("JumpStrafe", false)
    private val jumpStraightStrafe by floatRange("JumpStraightStrafe", 0.4f..0.45f, 0.1f..1f) { jumpStrafe }
    private val jumpDiagonalStrafe by floatRange("JumpDiagonalStrafe", 0.4f..0.45f, 0.1f..1f) { jumpStrafe }

    // Safety
    private val sameY by boolean("SameY", false) { scaffoldMode != "GodBridge" }
    private val jumpOnUserInput by boolean("JumpOnUserInput", true) { sameY && scaffoldMode != "GodBridge" }

    private val safeWalkValue = boolean("SafeWalk", true) { scaffoldMode != "GodBridge" }
    private val airSafe by boolean("AirSafe", false) { safeWalkValue.isActive() }

    // Clutch（坠落保命：触发后冻结悬停等 Scaffold 补方块，迁移自 Leader-Lite）
    private val clutch by boolean("Clutch", true)
    private val clutchOnlyVoid by boolean("OnlyVoid", false) { clutch }
    private val clutchTicks by int("ClutchTicks", 10, 5..20) { clutch }

    // Visuals
    private val mark by boolean("Mark", false).subjective()
    private val markStyle by choices("MarkStyle", arrayOf("Box", "CornerFrame", "RingPulse"), "Box") { mark }
    private val markColor = color("MarkColor", Color(68, 117, 255, 100)) { mark }
    private val markAnimDuration by int("MarkAnimDuration", 250, 0..1000) { mark }
    private val trackCPS by boolean("TrackCPS", false).subjective()

    // Target placement
    var placeRotation: PlaceRotation? = null

    // Launch position
    private var launchY = -999

    // Clutch 状态
    private var clutchActive = false
    private var clutchFrozen = false
    private var clutchFrozenTicks = 0
    private var clutchSavedMotionX = 0.0
    private var clutchSavedMotionY = 0.0
    private var clutchSavedMotionZ = 0.0
    private var clutchSafeY = -999.0

    val shouldJumpOnInput
        get() = !jumpOnUserInput || !mc.gameSettings.keyBindJump.isKeyDown && mc.thePlayer.posY >= launchY && !mc.thePlayer.onGround

    private val shouldKeepLaunchPosition
        get() = sameY && shouldJumpOnInput && scaffoldMode != "GodBridge"

    // Zitter
    private var zitterDirection = false

    // Delay
    private val delayTimer = object : DelayTimer(delay.first, delay.last, MSTimer()) {
        override fun hasTimePassed() = !placeDelayValue.isActive() || super.hasTimePassed()
    }

    private val zitterTickTimer = TickDelayTimer(zitterTicks.first, zitterTicks.last)

    // Eagle
    private var placedBlocksWithoutEagle = 0

    var eagleSneaking = false

    private var requestedStopSneak = false

    private val isEagleEnabled
        get() = eagle != "Off" && !shouldGoDown && scaffoldMode != "GodBridge"

    // Downwards
    val shouldGoDown
        get() = down && !sameY && GameSettings.isKeyDown(mc.gameSettings.keyBindSneak) && scaffoldMode !in arrayOf(
            "GodBridge", "Telly"
        ) && blocksAmount() > 1

    // Current rotation
    private val currRotation
        get() = RotationUtils.currentRotation ?: mc.thePlayer.rotation

    // Extra clicks
    private var extraClick = ExtraClickInfo(TimeUtils.randomClickDelay(extraClickCPS.first, extraClickCPS.last), 0L, 0)

    // GodBridge
    private var blocksPlacedUntilJump = 0

    private val isManualJumpOptionActive
        get() = scaffoldMode == "GodBridge" && !jumpAutomatically

    private var blocksToJump = blocksToJumpRange.random()

    private val isGodBridgeEnabled
        get() = scaffoldMode == "GodBridge" || scaffoldMode == "Normal" && options.rotationMode == "GodBridge"

    private var godBridgeTargetRotation: Rotation? = null

    private val isLookingDiagonally: Boolean
        get() {
            val player = mc.thePlayer ?: return false

            val directionDegree = MovementUtils.direction.toDegreesF()

            // Round the direction rotation to the nearest multiple of 45 degrees so that way we check if the player faces diagonally
            val yaw = round(abs(MathHelper.wrapAngleTo180_float(directionDegree)) / 45f) * 45f

            val isYawDiagonal = yaw % 90 != 0f
            val isMovingDiagonal = player.movementInput.moveForward != 0f && player.movementInput.moveStrafe == 0f
            val isStrafing = mc.gameSettings.keyBindRight.isKeyDown || mc.gameSettings.keyBindLeft.isKeyDown

            return isYawDiagonal && (isMovingDiagonal || isStrafing)
        }

    // Telly
    private var ticksUntilJump = 0
    private var blocksUntilAxisChange = 0
    private var jumpTicks = jumpTicksRange.random()
    private var horizontalPlacements = horizontalPlacementsRange.random()
    private var verticalPlacements = verticalPlacementsRange.random()
    private val shouldPlaceHorizontally
        get() = scaffoldMode == "Telly" && mc.thePlayer.isMoving && (startHorizontally && blocksUntilAxisChange <= horizontalPlacements || !startHorizontally && blocksUntilAxisChange > verticalPlacements)

    // <--

    // Enabling module
    override fun onEnable() {
        val player = mc.thePlayer ?: return

        launchY = player.posY.roundToInt()
        blocksUntilAxisChange = 0

        // ReverseYaw 提前转头：启用后立刻转头，低头固定 90°（垂直向下），朝向按屏幕视角取整（直行 90°、斜向 45°）
        if (reverseYawAhead && options.rotationMode == "ReverseYaw") {
            val step = if (isLookingDiagonally) 45f else 90f
            val target = MathHelper.wrapAngleTo180_float(round(player.rotationYaw / step) * step)

            setTargetRotation(Rotation(target, 90f).fixedSensitivity(), options)
        }
    }

    // Events
    val onUpdate = loopSequence {
        val player = mc.thePlayer ?: return@loopSequence

        if (mc.playerController.currentGameType == WorldSettings.GameType.SPECTATOR) return@loopSequence

        // Clutch
        updateClutch()

        if (clutchFrozen) {
            player.motionX = 0.0
            player.motionY = 0.0
            player.motionZ = 0.0
        }

        mc.timer.timerSpeed = timer

        // Telly
        if (player.onGround) ticksUntilJump++

        if (shouldGoDown) {
            mc.gameSettings.keyBindSneak.pressed = false
        }

        if (slow) {
            if (!slowGround || slowGround && player.onGround) {
                player.motionX *= slowSpeed
                player.motionZ *= slowSpeed
            }
        }

        // Eagle
        if (isEagleEnabled) {
            var dif = 0.5
            val blockPos = BlockPos(player).down()

            for (side in EnumFacing.entries) {
                if (side.axis == EnumFacing.Axis.Y) {
                    continue
                }

                val neighbor = blockPos.offset(side)

                if (neighbor.isReplaceable) {
                    val calcDif = (if (side.axis == EnumFacing.Axis.Z) {
                        abs(neighbor.z + 0.5 - player.posZ)
                    } else {
                        abs(neighbor.x + 0.5 - player.posX)
                    }) - 0.5

                    if (calcDif < dif) {
                        dif = calcDif
                    }
                }
            }

            val blockSneaking = WaitTickUtils.hasScheduled("block")
            val alreadySneaking = WaitTickUtils.hasScheduled("sneak")

            val options = mc.gameSettings

            run {
                if (placedBlocksWithoutEagle < blocksToEagle.random() && !alreadySneaking && !blockSneaking && !eagleSneaking && !requestedStopSneak) {
                    return@run
                }

                val eagleCondition = when (eagleMode) {
                    "OnGround" -> player.onGround
                    "InAir" -> !player.onGround
                    else -> true
                }

                // For better sneak support we could move this to MovementInputEvent
                val pressedOnKeyboard = Keyboard.isKeyDown(options.keyBindSneak.keyCode)

                var shouldEagle =
                    eagleCondition && (blockPos.isReplaceable || dif < edgeDistance) || pressedOnKeyboard

                val shouldSchedule = !requestedStopSneak

                if (requestedStopSneak) {
                    requestedStopSneak = false

                    if (!player.onGround) {
                        shouldEagle = pressedOnKeyboard
                    }
                } else if (blockSneaking || alreadySneaking) {
                    return@run
                }

                if (eagle == "Silent") {
                    if (eagleSneaking != shouldEagle) {
                        sendPacket(
                            C0BPacketEntityAction(
                                player, if (shouldEagle) {
                                    C0BPacketEntityAction.Action.START_SNEAKING
                                } else {
                                    C0BPacketEntityAction.Action.STOP_SNEAKING
                                }
                            )
                        )

                        // Adjust speed when silent sneaking
                        if (adjustedSneakSpeed && shouldEagle) {
                            player.motionX *= eagleSpeed
                            player.motionZ *= eagleSpeed
                        }
                    }

                    eagleSneaking = shouldEagle
                } else {
                    options.keyBindSneak.pressed = shouldEagle
                    eagleSneaking = shouldEagle
                }

                if (eagleSneaking && shouldSchedule) {
                    if (useMaxSneakTime) {
                        WaitTickUtils.conditionalSchedule("sneak") { elapsed ->
                            (elapsed >= maxSneakTicks.random() + 1).also { requestedStopSneak = it }
                        }
                    }

                    if (blockSneakingAgainUntilOnGround && !player.onGround) {
                        WaitTickUtils.conditionalSchedule("block") {
                            mc.thePlayer?.onGround.also { if (it != false) requestedStopSneak = true } ?: true
                        }
                    }
                }

                placedBlocksWithoutEagle = 0
            }
        }

        if (player.onGround) {
            // Still a thing?
            if (scaffoldMode == "Rewinside") {
                MovementUtils.strafe(0.2F)
                player.motionY = 0.0
            }
        }
    }

    val onStrafe = handler<StrafeEvent> {
        val player = mc.thePlayer ?: return@handler

        // Jumping needs to be done here, so it doesn't get detected by movement-sensitive anti-cheats.
        if (scaffoldMode == "Telly" && player.onGround && player.isMoving && ticksUntilJump >= jumpTicks && isTellyRotationResetDone()) {
            player.tryJump()

            ticksUntilJump = 0
            jumpTicks = jumpTicksRange.random()
        }
    }

    /**
     * Telly 起跳的视角回归判定（yaw-only）：
     * currentRotation 已释放时恒成立；未释放时只要服务器视角 Yaw 与玩家瞄准 Yaw
     * 相差不超过 [tellyJumpYawDifference] 即允许起跳，不再要求 Pitch 收敛与 gcd 严格容差 ——
     * MinRotationDifference / 转头速度停滞会让严格判定永远等不到收敛，起跳迟迟不触发，
     * 走出边缘直接掉落。回正速度由 ResetTicks（角度重置速度）控制。
     */
    private fun isTellyRotationResetDone(): Boolean {
        val player = mc.thePlayer ?: return false
        val current = RotationUtils.currentRotation ?: return true
        return abs(RotationUtils.angleDifference(current.yaw, player.rotationYaw)) <= tellyJumpYawDifference
    }

    val onRotationUpdate = handler<RotationUpdateEvent> {
        val player = mc.thePlayer ?: return@handler

        if (player.ticksExisted == 1) {
            launchY = player.posY.roundToInt()
        }

        // KeepHeadDown：Telly 重置角度阶段锁定低头（本客户端正 Pitch 即向下，90° = 垂直向下，即用户所述 -90° 低头）
        options.lockedResetPitch = if (tellyKeepHeadDown && scaffoldMode == "Telly") 90f else null

        val rotation = RotationUtils.currentRotation

        update()

        val ticks = if (options.keepRotation) {
            options.resetTicks
        } else {
            if (isGodBridgeEnabled) options.resetTicks else RotationUtils.resetTicks
        }

        if (!Tower.isTowering && isGodBridgeEnabled && options.rotationsActive) {
            generateGodBridgeRotations(ticks)

            return@handler
        }

        if (options.rotationsActive && rotation != null) {
            val placeRotation = this.placeRotation?.rotation ?: rotation

            if (RotationUtils.resetTicks != 0 || options.keepRotation) {
                setRotation(placeRotation, ticks)
            }
        }
    }

    val onTick = handler<GameTickEvent> {
        val target = placeRotation?.placeInfo

        val raycastProperly = !(scaffoldMode == "Expand" && expandLength > 1 || shouldGoDown) && options.rotationsActive

        /**
         * Calculate block raytracing process once to simulate proper vanilla ray-cast update logic.
         *
         * @see net.minecraft.client.Minecraft.runTick Line 1345
         */
        val raycast = performBlockRaytrace(currRotation, mc.playerController.blockReachDistance)

        var alreadyPlaced = false

        if (extraClicks) {
            val doubleClick = if (simulateDoubleClicking) RandomUtils.nextInt(-1, 1) else 0

            val clicks = extraClick.clicks + doubleClick

            repeat(clicks) {
                extraClick.clicks--

                doPlaceAttempt(raycast, it + 1 == clicks) { alreadyPlaced = true }
            }
        }

        if (target == null) {
            if (placeDelayValue.isActive()) {
                delayTimer.reset()
            }
            return@handler
        }

        // Change/Schedule slot once per tick according to vanilla-logic
        if (alreadyPlaced || SilentHotbar.modifiedThisTick) {
            return@handler
        }

        raycast.let {
            if (!options.rotationsActive || it != null && it.blockPos == target.blockPos && (!raycastProperly || it.sideHit == target.enumFacing)) {
                val result = if (raycastProperly && it != null) {
                    PlaceInfo(it.blockPos, it.sideHit, it.hitVec)
                } else {
                    target
                }

                place(result)
            }
        }
    }

    val onSneakSlowDown = handler<SneakSlowDownEvent> { event ->
        if (!isEagleEnabled || eagle != "Normal") {
            return@handler
        }

        event.forward *= eagleSpeed / 0.3f
        event.strafe *= eagleSpeed / 0.3f
    }

    val onMovementInput = handler<MovementInputEvent> { event ->
        val player = mc.thePlayer ?: return@handler

        // Clutch 冻结期间清空所有移动输入
        if (clutchFrozen) {
            event.originalInput.moveForward = 0f
            event.originalInput.moveStrafe = 0f
            event.originalInput.jump = false
            event.originalInput.sneak = false
            return@handler
        }

        if (!isGodBridgeEnabled || !player.onGround) return@handler

        if (waitForRots) {
            godBridgeTargetRotation?.run {
                event.originalInput.sneak =
                    event.originalInput.sneak || rotationDifference(this, currRotation) > getFixedAngleDelta()
            }
        }

        val simPlayer = SimulatedPlayer.fromClientPlayer(RotationUtils.modifiedInput)

        simPlayer.rotationYaw = currRotation.yaw

        simPlayer.tick()

        if (!simPlayer.onGround && !isManualJumpOptionActive || blocksPlacedUntilJump > blocksToJump) {
            event.originalInput.jump = true

            blocksPlacedUntilJump = 0

            blocksToJump = blocksToJumpRange.random()
        }
    }

    fun update() {
        val player = mc.thePlayer ?: return
        val holdingItem = player.heldItem?.item is ItemBlock

        if (!holdingItem && (autoBlock == "Off" || InventoryUtils.findBlockInHotbar() == null)) {
            return
        }

        findBlock(scaffoldMode == "Expand" && expandLength > 1, searchMode == "Area")
    }

    /**
     * Clutch 坠落保命（迁移自 Leader-Lite）：
     * 搭路时坠落刚起步（fallDistance > 1 或掉出最后站立平面 1.5 格）且还来得及救时，
     * 进入脉冲式冻结 —— 每次冻结 ClutchTicks tick（扣住发包 + 零运动 + 清输入）后短暂释放一 tick，
     * 循环往复直到落地，期间 Scaffold 持续在脚下补方块。
     */
    private fun updateClutch() {
        val player = mc.thePlayer ?: return

        // 记录最后一次落地的 Y，作为搭路平面参考（launchY 多数配置下会跟随玩家下移，不能用）
        if (player.onGround) clutchSafeY = player.posY

        if (!clutch || player.isDead || player.onGround || clutchBlocksBelow()) {
            resetClutch()
            return
        }

        // 提前触发：坠落刚起步（fallDistance > 1）或已掉出最后站立平面 1.5 格即出手，
        // 不再等 fallDistance > 2（那时速度已 ~-0.7/tick，经常来不及）
        val shouldClutch = (player.fallDistance > 1 || player.posY < clutchSafeY - 1.5)
                && !clutchAirAbove()
                && (!player.isCollidedHorizontally || isFallingIntoVoid())
                && (!clutchOnlyVoid || isFallingIntoVoid())

        if (shouldClutch && !clutchActive && !Blink.state) {
            clutchActive = true
            clutchFrozen = false
            clutchFrozenTicks = 0
        }

        if (!clutchActive) return

        if (clutchFrozen) {
            clutchFrozenTicks++
            if (clutchFrozenTicks >= clutchTicks) releaseClutchFreeze()
        } else {
            startClutchFreeze()
        }
    }

    /** 进入冻结：保存当前 motion，之后由 PacketEvent 扣包、各事件清运动。 */
    private fun startClutchFreeze() {
        val player = mc.thePlayer ?: run {
            resetClutch()
            return
        }

        clutchFrozen = true
        clutchFrozenTicks = 0
        clutchSavedMotionX = player.motionX
        clutchSavedMotionY = player.motionY
        clutchSavedMotionZ = player.motionZ
    }

    /** 解除冻结：放出期间扣住的包并恢复进入冻结时的 motion（释放 tick 玩家会下坠一格）。 */
    private fun releaseClutchFreeze() {
        clutchFrozen = false
        clutchFrozenTicks = 0

        BlinkUtils.unblink()

        val player = mc.thePlayer ?: return
        player.motionX = clutchSavedMotionX
        player.motionY = clutchSavedMotionY
        player.motionZ = clutchSavedMotionZ
    }

    private fun resetClutch() {
        if (clutchFrozen) {
            BlinkUtils.unblink()

            mc.thePlayer?.let {
                it.motionX = clutchSavedMotionX
                it.motionY = clutchSavedMotionY
                it.motionZ = clutchSavedMotionZ
            }
        }

        clutchActive = false
        clutchFrozen = false
        clutchFrozenTicks = 0
    }

    /** 脚下 1~2 格存在实体方块（即将落地，没必要也没时间 clutch）。 */
    private fun clutchBlocksBelow(): Boolean {
        val player = mc.thePlayer ?: return true
        val world = mc.theWorld ?: return true

        val playerY = MathHelper.floor_double(player.posY)

        for (i in 1..2) {
            val pos = BlockPos(MathHelper.floor_double(player.posX), playerY - i, MathHelper.floor_double(player.posZ))
            if (world.getBlockState(pos).block.material.isSolid) {
                // 高速下坠时 2 格外的方块可能来不及救（横向漂出其上方），不再视为即将落地
                if (i == 2 && player.motionY < -0.5) continue
                return true
            }
        }

        return false
    }

    /** 头顶上方一个身位处是否存在碰撞方块。 */
    private fun clutchAirAbove(): Boolean {
        val player = mc.thePlayer ?: return false
        val world = mc.theWorld ?: return false

        return world.getCollidingBoundingBoxes(player, player.entityBoundingBox.offset(0.0, 1.0, 0.0)).isNotEmpty()
    }

    /** 下方 128 格内是否完全没有实体方块（真·掉虚空）。 */
    private fun isFallingIntoVoid(): Boolean {
        val player = mc.thePlayer ?: return false
        val world = mc.theWorld ?: return false

        for (i in 0..128) {
            val pos = BlockPos(
                MathHelper.floor_double(player.posX),
                MathHelper.floor_double(player.posY) - i,
                MathHelper.floor_double(player.posZ)
            )
            if (world.getBlockState(pos).block.material.isSolid) return false
        }

        return true
    }

    private fun setRotation(rotation: Rotation, ticks: Int) {
        val player = mc.thePlayer ?: return

        if (scaffoldMode == "Telly" && player.isMoving) {
            if (player.airTicks < ticksUntilRotation.random() && ticksUntilJump >= jumpTicks) {
                return
            }
        }

        setTargetRotation(rotation, options, ticks)
    }

    // Search for new target block
    private fun findBlock(expand: Boolean, area: Boolean) {
        val player = mc.thePlayer ?: return

        if (!shouldKeepLaunchPosition) launchY = player.posY.roundToInt()

        val blockPosition = if (shouldGoDown) {
            if (player.posY == player.posY.roundToInt() + 0.5) {
                BlockPos(player.posX, player.posY - 0.6, player.posZ)
            } else {
                BlockPos(player.posX, player.posY - 0.6, player.posZ).down()
            }
        } else if (shouldKeepLaunchPosition && launchY <= player.posY) {
            BlockPos(player.posX, launchY - 1.0, player.posZ)
        } else if (player.posY == player.posY.roundToInt() + 0.5) {
            BlockPos(player)
        } else {
            BlockPos(player).down()
        }

        if (!expand && (!blockPosition.isReplaceable || search(
                blockPosition, !shouldGoDown, area, shouldPlaceHorizontally
            ))
        ) {
            return
        }

        if (expand) {
            val yaw = player.rotationYaw.toRadiansD()
            val x = if (omniDirectionalExpand) -sin(yaw).roundToInt() else player.horizontalFacing.directionVec.x
            val z = if (omniDirectionalExpand) cos(yaw).roundToInt() else player.horizontalFacing.directionVec.z

            repeat(expandLength) {
                if (search(blockPosition.add(x * it, 0, z * it), false, area)) return
            }
            return
        }

        val (horizontal, vertical) = if (scaffoldMode == "Telly") {
            5 to 3
        } else if (allowClutching) {
            horizontalClutchBlocks to verticalClutchBlocks
        } else {
            1 to 1
        }

        BlockPos.getAllInBox(
            blockPosition.add(-horizontal, 0, -horizontal), blockPosition.add(horizontal, -vertical, horizontal)
        ).sortedBy {
            BlockUtils.getCenterDistance(it)
        }.forEach {
            if (it.canBeClicked() || search(it, !shouldGoDown, area, shouldPlaceHorizontally)) {
                return
            }
        }
    }

    private fun place(placeInfo: PlaceInfo) {
        val player = mc.thePlayer ?: return
        val world = mc.theWorld ?: return

        if (!delayTimer.hasTimePassed() || shouldKeepLaunchPosition && launchY - 1 != placeInfo.vec3.yCoord.toInt() && scaffoldMode != "Expand") return

        val currentSlot = SilentHotbar.currentSlot

        var stack = player.hotBarSlot(currentSlot).stack

        //TODO: blacklist more blocks than only bushes
        if (stack == null || stack.item !is ItemBlock || (stack.item as ItemBlock).block is BlockBush || stack.stackSize <= 0 || sortByHighestAmount || earlySwitch) {
            val blockSlot = if (sortByHighestAmount) {
                InventoryUtils.findLargestBlockStackInHotbar() ?: return
            } else if (earlySwitch) {
                InventoryUtils.findBlockStackInHotbarGreaterThan(amountBeforeSwitch)
                    ?: InventoryUtils.findBlockInHotbar() ?: return
            } else {
                InventoryUtils.findBlockInHotbar() ?: return
            }

            stack = player.hotBarSlot(blockSlot).stack

            // Check if block is placeable on target side before switching slots
            if ((stack.item as? ItemBlock)?.canPlaceBlockOnSide(
                    world, placeInfo.blockPos, placeInfo.enumFacing, player, stack
                ) == false
            ) {
                return
            }

            if (autoBlock != "Off") {
                SilentHotbar.selectSlotSilently(this, blockSlot, render = autoBlock == "Pick", resetManually = true)
            }
        }

        tryToPlaceBlock(stack, placeInfo.blockPos, placeInfo.enumFacing, placeInfo.vec3)

        if (autoBlock == "Switch") SilentHotbar.resetSlot(this, true)

        // Since we violate vanilla slot switch logic if we send the packets now, we arrange them for the next tick
        findBlockToSwitchNextTick(stack)

        if (trackCPS) {
            CPSCounter.registerClick(CPSCounter.MouseButton.RIGHT)
        }
    }

    private fun doPlaceAttempt(raytrace: MovingObjectPosition?, lastClick: Boolean, onSuccess: () -> Unit = { }) {
        val player = mc.thePlayer ?: return
        val world = mc.theWorld ?: return

        val stack = player.hotBarSlot(SilentHotbar.currentSlot).stack ?: return

        if (stack.item !is ItemBlock || InventoryUtils.BLOCK_BLACKLIST.contains((stack.item as ItemBlock).block)) {
            return
        }

        raytrace ?: return

        val block = stack.item as ItemBlock

        val canPlaceOnUpperFace = block.canPlaceBlockOnSide(
            world, raytrace.blockPos, EnumFacing.UP, player, stack
        )

        val shouldPlace = if (placementAttempt == "Fail") {
            !block.canPlaceBlockOnSide(world, raytrace.blockPos, raytrace.sideHit, player, stack)
        } else {
            if (shouldKeepLaunchPosition) {
                raytrace.blockPos.y == launchY - 1 && !canPlaceOnUpperFace
            } else if (shouldPlaceHorizontally) {
                !canPlaceOnUpperFace
            } else {
                raytrace.blockPos.y <= player.posY.toInt() - 1 && !(raytrace.blockPos.y == player.posY.toInt() - 1 && canPlaceOnUpperFace && raytrace.sideHit == EnumFacing.UP)
            }
        }

        if (!raytrace.typeOfHit.isBlock || !shouldPlace) {
            return
        }

        tryToPlaceBlock(stack, raytrace.blockPos, raytrace.sideHit, raytrace.hitVec, attempt = true) { onSuccess() }

        // Since we violate vanilla slot switch logic if we send the packets now, we arrange them for the next tick
        if (lastClick) {
            findBlockToSwitchNextTick(stack)
        }

        if (trackCPS) {
            CPSCounter.registerClick(CPSCounter.MouseButton.RIGHT)
        }
    }

    // Disabling module
    override fun onDisable() {
        val player = mc.thePlayer ?: return

        if (!GameSettings.isKeyDown(mc.gameSettings.keyBindSneak)) {
            mc.gameSettings.keyBindSneak.pressed = false
            if (eagleSneaking && player.isSneaking) {
                //sendPacket(C0BPacketEntityAction(player, C0BPacketEntityAction.Action.STOP_SNEAKING))

                /**
                 * Should prevent false flag by some AntiCheat (Ex: Verus)
                 */
                player.isSneaking = false
            }
        }

        if (!GameSettings.isKeyDown(mc.gameSettings.keyBindRight)) {
            mc.gameSettings.keyBindRight.pressed = false
        }
        if (!GameSettings.isKeyDown(mc.gameSettings.keyBindLeft)) {
            mc.gameSettings.keyBindLeft.pressed = false
        }

        if (autoF5) {
            mc.gameSettings.thirdPersonView = 0
        }

        placeRotation = null
        mc.timer.timerSpeed = 1f

        resetClutch()

        lastMarkPos = null
        fadingMarks.clear()

        SilentHotbar.resetSlot(this)

        options.instant = false
        options.lockedResetPitch = null
    }

    // Entity movement event
    val onMove = handler<MoveEvent> { event ->
        val player = mc.thePlayer ?: return@handler

        // Clutch 冻结期间直接取消位移，把玩家定在原地
        if (clutchFrozen) {
            event.cancelEvent()
            return@handler
        }

        if (!safeWalkValue.isActive() || shouldGoDown) {
            return@handler
        }

        if (airSafe || player.onGround) {
            event.isSafeWalk = true
        }
    }

    // Clutch 冻结期间扣住所有发包，解除时统一放出（服务器视角只是卡顿）
    val onClutchPacket = handler<PacketEvent> { event ->
        if (!clutchFrozen) return@handler

        BlinkUtils.blink(event.packet, event, sent = true, receive = false)
    }

    // 切换世界时丢弃冻结期间积压的旧世界包并复位 Clutch 状态
    val onClutchWorld = handler<WorldEvent> {
        if (clutchFrozen) BlinkUtils.clear()

        clutchActive = false
        clutchFrozen = false
        clutchFrozenTicks = 0
    }

    val jumpHandler = handler<JumpEvent> { event ->
        if (!jumpStrafe) return@handler

        if (event.eventState == EventState.POST) {
            MovementUtils.strafe(
                (if (!isLookingDiagonally) jumpStraightStrafe else jumpDiagonalStrafe).random()
            )
        }
    }

    // Visuals
    val onRender3D = handler<Render3DEvent> {
        val player = mc.thePlayer ?: return@handler

        val shouldBother =
            !(shouldGoDown || scaffoldMode == "Expand" && expandLength > 1) && extraClicks && (player.isMoving || MovementUtils.speed > 0.03)

        if (shouldBother) {
            currRotation.let {
                performBlockRaytrace(it, mc.playerController.blockReachDistance)?.let { raytrace ->
                    val timePassed = System.currentTimeMillis() - extraClick.lastClick >= extraClick.delay

                    if (raytrace.typeOfHit.isBlock && timePassed) {
                        extraClick = ExtraClickInfo(
                            TimeUtils.randomClickDelay(extraClickCPS.first, extraClickCPS.last),
                            System.currentTimeMillis(),
                            extraClick.clicks + 1
                        )
                    }
                }
            }
        }

        if (!mark) {
            return@handler
        }

        var markPos: BlockPos? = null

        repeat(if (scaffoldMode == "Expand") expandLength + 1 else 2) {
            if (markPos != null) return@repeat

            val yaw = player.rotationYaw.toRadiansD()
            val x = if (omniDirectionalExpand) -sin(yaw).roundToInt() else player.horizontalFacing.directionVec.x
            val z = if (omniDirectionalExpand) cos(yaw).roundToInt() else player.horizontalFacing.directionVec.z
            val blockPos = BlockPos(
                player.posX + x * it,
                if (shouldKeepLaunchPosition && launchY <= player.posY) launchY - 1.0 else player.posY - (if (player.posY == player.posY + 0.5) 0.0 else 1.0) - if (shouldGoDown) 1.0 else 0.0,
                player.posZ + z * it
            )
            val placeInfo = PlaceInfo.get(blockPos)

            if (blockPos.isReplaceable && placeInfo != null) {
                markPos = blockPos
            }
        }

        updateMark(markPos)
    }

    /** RingPulse 扩散环动画周期（毫秒） */
    private const val MARK_PULSE_DURATION = 1000L

    /** 正在淡出的旧标记（支持多个同时淡出，上一个动画没播完不会被打断消失） */
    private class FadingMark(val pos: BlockPos, val start: Long)

    // Mark 动画状态：标记的方块变化时新标记淡入、旧位置的标记进入淡出队列
    private var lastMarkPos: BlockPos? = null
    private var markAnimStart = 0L
    private val fadingMarks = mutableListOf<FadingMark>()

    /** 当前动画时长（用户可调，毫秒）。 */
    private val markDuration
        get() = markAnimDuration.toFloat()

    /** 标记方块颜色：取自 MarkColor 设置（含透明度）。 */
    private fun markBoxColor(): Color {
        val c = markColor.selectedColor()
        // drawBlockBox 在 alpha=255 时会回退到内部默认透明度，钳制一下让用户透明度完全生效
        return Color(c.red, c.green, c.blue, c.alpha.coerceAtMost(254))
    }

    /**
     * 更新并绘制 Scaffold 的放置标记（Box / CornerFrame / RingPulse 样式）。
     * 标记的方块变化时播放「从小到大 + 淡入」（easeOutCubic），
     * 旧位置的标记进入淡出队列，各自播放「从大到小 + 淡出」（easeInCubic）动画，
     * 动画时长由 MarkAnimDuration 设置控制（0 为无动画）。
     */
    private fun updateMark(blockPos: BlockPos?) {
        val now = System.currentTimeMillis()
        val duration = markDuration

        if (blockPos != null) {
            if (lastMarkPos != blockPos) {
                lastMarkPos?.let { old ->
                    // 新目标如果正在淡出，先取消它的淡出
                    fadingMarks.removeAll { it.pos == blockPos }
                    if (fadingMarks.none { it.pos == old }) {
                        fadingMarks.add(FadingMark(old, now))
                    }
                }
                lastMarkPos = blockPos
                markAnimStart = now
            }

            // 淡入：从小到大 + 透明度上升
            val progress = if (duration <= 0f) 1f else ((now - markAnimStart) / duration).coerceIn(0f, 1f)
            drawMark(blockPos, 1f - (1f - progress).pow(3))
        } else if (lastMarkPos != null) {
            // 目标消失时旧标记进入淡出队列
            val old = lastMarkPos!!
            if (fadingMarks.none { it.pos == old }) {
                fadingMarks.add(FadingMark(old, now))
            }
            lastMarkPos = null
        }

        // 淡出：从大到小 + 透明度下降，多个旧标记可同时淡出互不干扰
        fadingMarks.removeAll { fading ->
            val progress = if (duration <= 0f) 1f else (now - fading.start) / duration
            if (progress >= 1f) {
                true
            } else {
                drawMark(fading.pos, (1f - progress).pow(3))
                false
            }
        }
    }

    /** 按给定的动画进度（0=刚出现/即将消失，1=完全显示）绘制标记。 */
    private fun drawMark(blockPos: BlockPos, eased: Float) {
        val base = markBoxColor()
        val color = Color(base.red, base.green, base.blue, (base.alpha * eased).toInt().coerceIn(0, 255))
        val scale = 0.5f + 0.5f * eased

        when (markStyle) {
            "CornerFrame" -> drawMarkCornerFrame(blockPos, color, scale)
            "RingPulse" -> drawMarkRingPulse(blockPos, color)
            else -> drawMarkBox(blockPos, color, scale)
        }
    }

    /** 围绕方块中心按 scale 缩放后的 AABB。 */
    private fun scaledMarkBB(blockPos: BlockPos, scale: Float): AxisAlignedBB {
        val renderManager = mc.renderManager
        val (x, y, z) = blockPos.toVec() - renderManager.renderPos
        val half = 0.5 * scale

        return AxisAlignedBB.fromBounds(
            x + 0.5 - half, y + 0.5 - half, z + 0.5 - half,
            x + 0.5 + half, y + 0.5 + half, z + 0.5 + half
        )
    }

    /** Box 样式：半透明填充 + 边框（同 drawBlockBox 的 GL 状态处理）。 */
    private fun drawMarkBox(blockPos: BlockPos, color: Color, scale: Float) {
        val bb = scaledMarkBB(blockPos, scale)

        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        RenderUtils.enableGlCap(GL11.GL_BLEND)
        RenderUtils.disableGlCap(GL11.GL_TEXTURE_2D, GL11.GL_DEPTH_TEST)
        GL11.glDepthMask(false)
        RenderUtils.glColor(color)
        RenderUtils.drawFilledBox(bb)

        GL11.glLineWidth(1f)
        RenderUtils.enableGlCap(GL11.GL_LINE_SMOOTH)
        RenderUtils.glColor(color)
        RenderUtils.drawSelectionBoundingBox(bb)

        RenderUtils.resetCaps()
        GL11.glDepthMask(true)
    }

    /** CornerFrame 样式：方块八个角的 L 形短边框，轻量不遮挡视野。 */
    private fun drawMarkCornerFrame(blockPos: BlockPos, color: Color, scale: Float) {
        val bb = scaledMarkBB(blockPos, scale)
        val len = 0.25 * (bb.maxX - bb.minX)

        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        RenderUtils.enableGlCap(GL11.GL_BLEND)
        RenderUtils.disableGlCap(GL11.GL_TEXTURE_2D, GL11.GL_DEPTH_TEST)
        GL11.glDepthMask(false)
        GL11.glLineWidth(2f)
        RenderUtils.enableGlCap(GL11.GL_LINE_SMOOTH)
        RenderUtils.glColor(color)

        drawWithTessellatorWorldRenderer {
            begin(GL11.GL_LINES, DefaultVertexFormats.POSITION)
            for (px in booleanArrayOf(false, true)) {
                for (py in booleanArrayOf(false, true)) {
                    for (pz in booleanArrayOf(false, true)) {
                        val cx = if (px) bb.maxX else bb.minX
                        val cy = if (py) bb.maxY else bb.minY
                        val cz = if (pz) bb.maxZ else bb.minZ
                        val sx = if (px) -len else len
                        val sy = if (py) -len else len
                        val sz = if (pz) -len else len

                        pos(cx, cy, cz).endVertex(); pos(cx + sx, cy, cz).endVertex()
                        pos(cx, cy, cz).endVertex(); pos(cx, cy + sy, cz).endVertex()
                        pos(cx, cy, cz).endVertex(); pos(cx, cy, cz + sz).endVertex()
                    }
                }
            }
        }

        RenderUtils.resetCaps()
        GL11.glDepthMask(true)
    }

    /** RingPulse 样式：底面轮廓环不断向外扩散并淡出。 */
    private fun drawMarkRingPulse(blockPos: BlockPos, color: Color) {
        val bb = scaledMarkBB(blockPos, 1f)
        val now = System.currentTimeMillis()

        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        RenderUtils.enableGlCap(GL11.GL_BLEND)
        RenderUtils.disableGlCap(GL11.GL_TEXTURE_2D, GL11.GL_DEPTH_TEST)
        GL11.glDepthMask(false)
        GL11.glLineWidth(2f)
        RenderUtils.enableGlCap(GL11.GL_LINE_SMOOTH)

        // 两个相位相差半个周期的环，保证动画连续
        for (i in 0..1) {
            val phase = (now % MARK_PULSE_DURATION) / MARK_PULSE_DURATION.toFloat()
            val p = (phase + i * 0.5f) % 1f
            val expand = p * 0.35
            val alpha = ((1f - p) * color.alpha).toInt().coerceIn(0, 255)

            if (alpha <= 0) continue

            RenderUtils.glColor(Color(color.red, color.green, color.blue, alpha))

            val minX = bb.minX - expand
            val minZ = bb.minZ - expand
            val maxX = bb.maxX + expand
            val maxZ = bb.maxZ + expand
            val y = bb.minY

            drawWithTessellatorWorldRenderer {
                begin(GL11.GL_LINES, DefaultVertexFormats.POSITION)
                pos(minX, y, minZ).endVertex(); pos(maxX, y, minZ).endVertex()
                pos(maxX, y, minZ).endVertex(); pos(maxX, y, maxZ).endVertex()
                pos(maxX, y, maxZ).endVertex(); pos(minX, y, maxZ).endVertex()
                pos(minX, y, maxZ).endVertex(); pos(minX, y, minZ).endVertex()
            }
        }

        RenderUtils.resetCaps()
        GL11.glDepthMask(true)
    }

    /**
     * Search for placeable block
     *
     * @param blockPosition pos
     * @param raycast visible
     * @param area spot
     * @return
     */

    fun search(
        blockPosition: BlockPos,
        raycast: Boolean,
        area: Boolean,
        horizontalOnly: Boolean = false,
    ): Boolean {
        val player = mc.thePlayer ?: return false

        options.instant = false

        if (!blockPosition.isReplaceable) {
            if (autoF5) mc.gameSettings.thirdPersonView = 0
            return false
        } else {
            if (autoF5 && mc.gameSettings.thirdPersonView != 1) mc.gameSettings.thirdPersonView = 1
        }

        val maxReach = mc.playerController.blockReachDistance

        val eyes = player.eyes
        var placeRotation: PlaceRotation? = null

        var currPlaceRotation: PlaceRotation?

        for (side in EnumFacing.entries) {
            if (horizontalOnly && side.axis == EnumFacing.Axis.Y) {
                continue
            }

            val neighbor = blockPosition.offset(side)

            if (!neighbor.canBeClicked()) {
                continue
            }

            if (!area || isGodBridgeEnabled) {
                currPlaceRotation =
                    findTargetPlace(blockPosition, neighbor, Vec3(0.5, 0.5, 0.5), side, eyes, maxReach, raycast)
                        ?: continue

                placeRotation = compareDifferences(currPlaceRotation, placeRotation)
            } else {
                for (x in 0.1..0.9) {
                    for (y in 0.1..0.9) {
                        for (z in 0.1..0.9) {
                            currPlaceRotation =
                                findTargetPlace(blockPosition, neighbor, Vec3(x, y, z), side, eyes, maxReach, raycast)
                                    ?: continue

                            placeRotation = compareDifferences(currPlaceRotation, placeRotation)
                        }
                    }
                }
            }
        }

        placeRotation ?: return false

        if (options.rotationsActive && !isGodBridgeEnabled) {
            val rotationDifference = rotationDifference(placeRotation.rotation, currRotation)
            val rotationDifference2 = rotationDifference(placeRotation.rotation / 90F, currRotation / 90F)

            val simPlayer = SimulatedPlayer.fromClientPlayer(player.movementInput)
            simPlayer.tick()

            // We don't want to use block safe all the time, so we check if it's not needed.
            options.instant =
                blockSafe && simPlayer.fallDistance > player.fallDistance + 0.05 && rotationDifference > rotationDifference2 / 2

            setRotation(placeRotation.rotation, options.resetTicks)
        }

        this.placeRotation = placeRotation
        return true
    }

    /**
     * For expand scaffold, fixes vector values that should match according to direction vector
     */
    private fun modifyVec(original: Vec3, direction: EnumFacing, pos: Vec3, shouldModify: Boolean): Vec3 {
        if (!shouldModify) {
            return original
        }

        val x = original.xCoord
        val y = original.yCoord
        val z = original.zCoord

        val side = direction.opposite

        return when (side.axis ?: return original) {
            EnumFacing.Axis.Y -> Vec3(x, pos.yCoord + side.directionVec.y.coerceAtLeast(0), z)
            EnumFacing.Axis.X -> Vec3(pos.xCoord + side.directionVec.x.coerceAtLeast(0), y, z)
            EnumFacing.Axis.Z -> Vec3(x, y, pos.zCoord + side.directionVec.z.coerceAtLeast(0))
        }

    }

    private fun findTargetPlace(
        pos: BlockPos, offsetPos: BlockPos, vec3: Vec3, side: EnumFacing, eyes: Vec3, maxReach: Float, raycast: Boolean,
    ): PlaceRotation? {
        val world = mc.theWorld ?: return null

        val vec = (Vec3(pos) + vec3).addVector(
            side.directionVec.x * vec3.xCoord, side.directionVec.y * vec3.yCoord, side.directionVec.z * vec3.zCoord
        )

        val distance = eyes.distanceTo(vec)

        if (raycast && (distance > maxReach || world.rayTraceBlocks(eyes, vec, false, true, false) != null)) {
            return null
        }

        val diff = vec - eyes

        if (side.axis != EnumFacing.Axis.Y) {
            val dist = abs(if (side.axis == EnumFacing.Axis.Z) diff.zCoord else diff.xCoord)

            if (dist < minDist && scaffoldMode != "Telly") {
                return null
            }
        }

        var rotation = toRotation(vec, false)

        val roundYaw90 = round(rotation.yaw / 90f) * 90f
        val roundYaw45 = round(rotation.yaw / 45f) * 45f

        rotation = when (options.rotationMode) {
            "Stabilized" -> Rotation(roundYaw45, rotation.pitch)
            "ReverseYaw" -> Rotation(if (!isLookingDiagonally) roundYaw90 else roundYaw45, rotation.pitch)
            else -> rotation
        }.fixedSensitivity()

        // If the current rotation already looks at the target block and side, then return right here
        performBlockRaytrace(currRotation, maxReach)?.let { raytrace ->
            if (raytrace.blockPos == offsetPos && (!raycast || raytrace.sideHit == side.opposite)) {
                return PlaceRotation(
                    PlaceInfo(
                        raytrace.blockPos, side.opposite, modifyVec(raytrace.hitVec, side, Vec3(offsetPos), !raycast)
                    ), currRotation
                )
            }
        }

        val raytrace = performBlockRaytrace(rotation, maxReach) ?: return null

        val multiplier = if (options.legitimize) 3 else 1

        if (raytrace.blockPos == offsetPos && (!raycast || raytrace.sideHit == side.opposite) && canUpdateRotation(
                currRotation, rotation, multiplier
            )
        ) {
            return PlaceRotation(
                PlaceInfo(
                    raytrace.blockPos, side.opposite, modifyVec(raytrace.hitVec, side, Vec3(offsetPos), !raycast)
                ), rotation
            )
        }

        return null
    }

    private fun performBlockRaytrace(rotation: Rotation, maxReach: Float): MovingObjectPosition? {
        val player = mc.thePlayer ?: return null
        val world = mc.theWorld ?: return null

        val eyes = player.eyes
        val rotationVec = getVectorForRotation(rotation)

        val reach = eyes + (rotationVec * maxReach.toDouble())

        return world.rayTraceBlocks(eyes, reach, false, false, true)
    }

    private fun compareDifferences(
        new: PlaceRotation, old: PlaceRotation?, rotation: Rotation = currRotation,
    ): PlaceRotation {
        if (old == null || rotationDifference(new.rotation, rotation) < rotationDifference(old.rotation, rotation)) {
            return new
        }

        return old
    }

    private fun findBlockToSwitchNextTick(stack: ItemStack) {
        if (autoBlock in arrayOf("Off", "Switch")) return

        val switchAmount = if (earlySwitch) amountBeforeSwitch else 0

        if (stack.stackSize > switchAmount) return

        val switchSlot = if (earlySwitch) {
            InventoryUtils.findBlockStackInHotbarGreaterThan(amountBeforeSwitch) ?: InventoryUtils.findBlockInHotbar()
            ?: return
        } else {
            InventoryUtils.findBlockInHotbar()
        } ?: return

        SilentHotbar.selectSlotSilently(this, switchSlot, render = autoBlock == "Pick", resetManually = true)
    }

    private fun updatePlacedBlocksForTelly() {
        if (blocksUntilAxisChange > horizontalPlacements + verticalPlacements) {
            blocksUntilAxisChange = 0

            horizontalPlacements = horizontalPlacementsRange.random()
            verticalPlacements = verticalPlacementsRange.random()
            return
        }

        blocksUntilAxisChange++
    }

    private fun tryToPlaceBlock(
        stack: ItemStack, clickPos: BlockPos, side: EnumFacing, hitVec: Vec3, attempt: Boolean = false,
        onSuccess: () -> Unit = { }
    ): Boolean {
        val thePlayer = mc.thePlayer ?: return false

        val prevSize = stack.stackSize

        // Defer the placement (C08) so it is sent right before the rotation packet of this tick. On 1.9+ servers
        // Grim validates a placement against the rotation of the tick it arrived in, but only when the place
        // packet is received shortly before the flying packet - otherwise it falls back to the previous tick's
        // rotation and the placement gets swallowed (see PlayerExtension.flushDeferredPlacements).
        val clickedSuccessfully = thePlayer.onPlayerRightClick(clickPos, side, hitVec, stack, defer = true)

        if (clickedSuccessfully) {
            if (!attempt) {
                delayTimer.reset()

                if (thePlayer.onGround) {
                    thePlayer.motionX *= speedModifier
                    thePlayer.motionZ *= speedModifier
                }
            }

            if (swing) thePlayer.swingItem()
            else NoSwing.swingWithoutAnimation()

            if (isManualJumpOptionActive) blocksPlacedUntilJump++

            updatePlacedBlocksForTelly()

            if (stack.stackSize <= 0) {
                thePlayer.inventory.mainInventory[SilentHotbar.currentSlot] = null
                ForgeEventFactory.onPlayerDestroyItem(thePlayer, stack)
            } else if (stack.stackSize != prevSize || mc.playerController.isInCreativeMode) {
                // Swing 关闭时不重置 equippedProgress，避免出现物品下沉再抬起的切换动画
                if (swing) mc.entityRenderer.itemRenderer.resetEquippedProgress()
            }

            placeRotation = null

            placedBlocksWithoutEagle++

            onSuccess()
        } else {
            if (thePlayer.sendUseItem(stack, defer = true)) {
                if (swing) mc.entityRenderer.itemRenderer.resetEquippedProgress2()
            }
        }

        return clickedSuccessfully
    }

    fun handleMovementOptions(input: MovementInput) {
        val player = mc.thePlayer ?: return

        if (!state) {
            return
        }

        if (!slow && speedLimiter && MovementUtils.speed > speedLimit) {
            input.moveStrafe = 0f
            input.moveForward = 0f
            return
        }

        when (zitterMode.lowercase()) {
            "off" -> {
                return
            }

            "smooth" -> {
                val notOnGround = !player.onGround || !player.isCollidedVertically

                if (player.onGround) {
                    input.sneak = eagleSneaking || GameSettings.isKeyDown(mc.gameSettings.keyBindSneak)
                }

                if (input.jump || mc.gameSettings.keyBindJump.isKeyDown || notOnGround) {
                    zitterTickTimer.reset()

                    if (useSneakMidAir) {
                        input.sneak = true
                    }

                    if (!notOnGround && !input.jump) {
                        // Attempt to move against the direction
                        input.moveStrafe = if (zitterDirection) 1f else -1f
                    } else {
                        input.moveStrafe = 0f
                    }

                    zitterDirection = !zitterDirection

                    // Recreate input in case the user was indeed pressing inputs
                    if (mc.gameSettings.keyBindLeft.isKeyDown) {
                        input.moveStrafe++
                    }

                    if (mc.gameSettings.keyBindRight.isKeyDown) {
                        input.moveStrafe--
                    }
                    return
                }

                if (zitterTickTimer.hasTimePassed()) {
                    zitterDirection = !zitterDirection
                    zitterTickTimer.reset()
                } else {
                    zitterTickTimer.update()
                }

                if (zitterDirection) {
                    input.moveStrafe = -1f
                } else {
                    input.moveStrafe = 1f
                }
            }

            "teleport" -> {
                MovementUtils.strafe(zitterSpeed)
                val yaw = (player.rotationYaw + if (zitterDirection) 90.0 else -90.0).toRadians()
                player.motionX -= sin(yaw) * zitterStrength
                player.motionZ += cos(yaw) * zitterStrength
                zitterDirection = !zitterDirection
            }
        }
    }

    private var isOnRightSide = false

    /**
     * God-bridge rotation generation method from Nextgen
     *
     * Credits to @Ell1ott
     */
    private fun generateGodBridgeRotations(ticks: Int) {
        val player = mc.thePlayer ?: return

        val direction = if (options.applyServerSide) {
            MovementUtils.direction.toDegreesF() + 180f
        } else MathHelper.wrapAngleTo180_float(player.rotationYaw)

        val movingYaw = round(direction / 45) * 45

        val steps45 = arrayListOf(-135f, -45f, 45f, 135f)

        val isMovingStraight = if (options.applyServerSide) {
            movingYaw % 90 == 0f
        } else movingYaw in steps45 && player.movementInput.isSideways

        if (!player.isNearEdge(2.5f)) return

        if (!player.isMoving) {
            placeRotation?.run {
                val axisMovement = floor(this.rotation.yaw / 90) * 90

                val yaw = axisMovement + 45f
                val pitch = 75f

                setRotation(Rotation(yaw, pitch), ticks)
                return
            }

            if (!options.keepRotation) return
        }

        val rotation = if (isMovingStraight) {
            if (player.onGround) {
                isOnRightSide = floor(player.posX + cos(movingYaw.toRadians()) * 0.5) != floor(player.posX) || floor(
                    player.posZ + sin(movingYaw.toRadians()) * 0.5
                ) != floor(player.posZ)

                val posInDirection =
                    BlockPos(player.positionVector.offset(EnumFacing.fromAngle(movingYaw.toDouble()), 0.6))

                val isLeaningOffBlock = player.position.down().block == air
                val nextBlockIsAir = posInDirection.down().block == air

                if (isLeaningOffBlock && nextBlockIsAir) {
                    isOnRightSide = !isOnRightSide
                }
            }

            val side = if (options.applyServerSide) {
                if (isOnRightSide) 45f else -45f
            } else 0f

            Rotation(movingYaw + side, if (useOptimizedPitch) 73.5f else customGodPitch)
        } else {
            Rotation(movingYaw, 75.6f)
        }.fixedSensitivity()

        godBridgeTargetRotation = rotation

        setRotation(rotation, ticks)
    }

    override val tag
        get() = if (towerMode != "None") ("$scaffoldMode | $towerMode") else scaffoldMode

    data class ExtraClickInfo(val delay: Int, val lastClick: Long, var clicks: Int)
}
