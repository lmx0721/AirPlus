/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.combat

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion
import net.airplus.event.EventState
import net.airplus.event.GameTickEvent
import net.airplus.event.JumpEvent
import net.airplus.event.Listenable
import net.airplus.event.MoveEvent
import net.airplus.event.MotionEvent
import net.airplus.event.MovementInputEvent
import net.airplus.event.PacketEvent
import net.airplus.event.StrafeEvent
import net.airplus.event.UpdateEvent
import net.airplus.event.handler
import net.airplus.features.module.modules.movement.Speed
import net.airplus.utils.skid.rise.RiseGrimUtils
import net.airplus.utils.skid.rise.RiseGrimUtils.receivePacket
import net.airplus.features.module.modules.world.scaffolds.Scaffold
import net.airplus.utils.attack.EntityUtils.isSelected
import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.client.PacketUtils.sendPacket
import net.airplus.utils.client.chat
import net.airplus.utils.extensions.tryJump
import net.airplus.utils.rotation.Rotation
import net.airplus.utils.rotation.RotationUtils
import net.airplus.utils.rotation.RotationUtils.toRotation
import net.airplus.viaversion.vialoadingbase.ViaLoadingBase
import net.minecraft.block.BlockWeb
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.network.Packet
import net.minecraft.network.play.client.C02PacketUseEntity
import net.minecraft.network.play.client.C03PacketPlayer
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement
import net.minecraft.network.play.client.C0APacketAnimation
import net.minecraft.network.play.client.C0BPacketEntityAction
import net.minecraft.network.play.server.S06PacketUpdateHealth
import net.minecraft.network.play.server.S07PacketRespawn
import net.minecraft.network.play.server.S08PacketPlayerPosLook
import net.minecraft.network.play.server.S0CPacketSpawnPlayer
import net.minecraft.network.play.server.S12PacketEntityVelocity
import net.minecraft.network.play.server.S14PacketEntity
import net.minecraft.network.play.server.S19PacketEntityStatus
import net.minecraft.network.play.server.S23PacketBlockChange
import net.minecraft.network.play.server.S27PacketExplosion
import net.minecraft.network.play.server.S29PacketSoundEffect
import net.minecraft.network.play.server.S2APacketParticles
import net.minecraft.network.play.server.S32PacketConfirmTransaction
import net.minecraft.network.play.server.S3EPacketTeams
import net.minecraft.util.BlockPos
import net.minecraft.util.EnumFacing
import net.minecraft.util.Vec3
import java.util.ArrayList
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Rise Velocity（Ported from Rise GrimVelocity / Grim2Velocity / GrimReduceVelocity / GrimTestVelocity）。
 *
 * Velocity 模块是无模式对象的 monolithic 模块，其 "Rise" 模式实现集中在本独立
 * [Listenable] 对象中；所有 handler 顶部都通过 [active] 判定
 * （`Velocity.handleEvents() && Velocity.mode == "Rise"`），按 [Velocity.riseMode] 分派：
 * - Grim   ：S12/S32/S14/S27/S08 扣包冻结 + movementFrozen + S23 回执解除 + Tick 发
 *            C08/Via USE_ITEM_ON + Strafe 保存恢复 motion + Jump 时回放扣包；
 * - Grim2  ：C03 全角度旋转修正（yaw/pitch 噪声改写）+ Fake S08 setPosition + 取消 S12；
 * - Reduce ：S12/S32/S14/S27 扣包 + 延迟回放 + jumpReset + 额外攻击（按版本顺序）+ 旋转朝目标；
 * - Test   ：Reduce/Jump Reset/Grim Full 三种子模式的实验性实现。
 *
 * 与 Speed（Rise / GrimSpeed）联动：暴露 [freezing]（Rise 的 dj）、[velocityTicks]、
 * [flushing] 供 `Speed` 的 Rise 模式读取。
 */
object VelocityRise : Listenable, MinecraftInstance {

    //region 对 Speed.Rise（GrimSpeed）暴露的共享状态
    /** Rise 的 dj：空中收到 S12 且已扣包时为 true。 */
    var freezing = false

    /** Rise 的 velocityTicks：原实现从未自增（恒为 0），保留以兼容 Speed 的联动判定。 */
    var velocityTicks = 0

    /** Rise 的 flushing：正在回放扣包时为 true。 */
    var flushing = false
    //endregion

    //region Grim（GrimVelocity）
    private val heldPackets = ArrayList<Packet<*>>()
    private val placedBlocks = HashSet<BlockPos>()
    private var movementFrozen = false
    private var motionSaved = false
    private var savedMotionX = 0.0
    private var savedMotionY = 0.0
    private var savedMotionZ = 0.0
    private var rotating = false
    //endregion

    //region Reduce（GrimReduceVelocity）
    private val delayedPackets = ArrayList<Packet<*>>()
    private var shouldReduce = false
    private var pendingJumpReset = false
    private var flushingPackets = false
    //endregion

    //region Test（GrimTestVelocity）
    private val testDelayedPackets = ConcurrentLinkedQueue<Packet<*>>()
    private var testDelaying = false
    private var pendingVelocity: Vec3? = null
    private var velocityApplied = false
    private var skipNextDelay = false
    private var delayStart = 0L
    private var testTarget: Entity? = null
    private var velocityReceived = false
    private var jumpResetCountdown = 0
    private var knockbackSpeed = 0.0
    private var pendingAttacks = 0
    private var hurtReceived = false
    private var enableTime = 0L
    private var wasNotSprinting = false
    private val random = Random()
    //endregion

    private val active
        get() = Velocity.handleEvents() && Velocity.mode == "Rise"

    private val riseMode
        get() = Velocity.riseMode

    /** Rise 原实现在每个事件里重复读取的 Speed 联动 flag：Speed(Rise/GrimSpeed) 启用且 FastFall 开启。 */
    private val speedFastFall
        get() = Speed.handleEvents() && Speed.mode.get() == "Rise" && Speed.riseFastFall

    //region 生命周期（由 Velocity.onEnable / onDisable 调用）

    fun onEnabled() {
        // GrimTestVelocity.onEnable
        enableTime = System.currentTimeMillis()
        resetGrim()
        resetReduce()
        resetTest()
    }

    fun onDisabled() {
        resetGrim()
        resetReduce()
        resetTest()
    }

    private fun resetGrim() {
        rotating = false
        movementFrozen = false
        motionSaved = false
        placedBlocks.clear()
        flushHeld()
        freezing = false
    }

    private fun resetReduce() {
        shouldReduce = false
        pendingJumpReset = false
        flushDelayed()
        delayedPackets.clear()
    }

    private fun resetTest() {
        velocityReceived = false
        jumpResetCountdown = 0
        testTarget = null
        knockbackSpeed = 0.0
        pendingAttacks = 0
        hurtReceived = false
        wasNotSprinting = false
        endDelay(true)
    }
    //endregion

    //region 事件监听

    private val onPacket = handler<PacketEvent> { event ->
        if (!active)
            return@handler

        val player = mc.thePlayer ?: return@handler
        val packet = event.packet

        when (riseMode) {
            "Grim" -> handleGrimPacket(event, player, packet)
            "Grim2" -> handleGrim2Packet(event, player, packet)
            "Reduce" -> handleReducePacket(event, player, packet)
            "Test" -> handleTestPacket(event, player, packet)
        }
    }

    private val onMove = handler<MoveEvent> { event ->
        if (!active || riseMode != "Grim")
            return@handler

        val player = mc.thePlayer ?: return@handler

        if ((player.hurtTime >= 7 && !player.isInWeb && !speedFastFall) || !player.onGround) {
            if (movementFrozen)
                event.cancelEvent()

            // 超过 25 tick 仍未处理：放弃冻结，回放扣包
            if (player.ticksExisted > 25 && freezing) {
                freezing = false
                flushHeld()
            }
        } else if (heldPackets.isNotEmpty() || movementFrozen || freezing) {
            // 已落地且无事可做：立即回放扣包并复位
            flushing = true
            movementFrozen = false
            placedBlocks.clear()
            freezing = false
            motionSaved = false
            flushHeld()
            flushing = false
        }
    }

    private val onStrafe = handler<StrafeEvent> {
        if (!active || riseMode != "Grim")
            return@handler

        val player = mc.thePlayer ?: return@handler

        if (player.hurtTime >= 7 && !player.isInWeb) {
            // 击退抗性期间主动跳跃，抵消冻结造成的位置漂移
            if (player.hurtResistantTime > 3 && player.onGround && freezing)
                player.tryJump()

            // 冻结期间保存 motion，解冻后恢复（回放扣包前后速度不被服务端数据冲掉）
            if (movementFrozen) {
                if (!motionSaved) {
                    savedMotionX = player.motionX
                    savedMotionY = player.motionY
                    savedMotionZ = player.motionZ
                    motionSaved = true
                }
            } else if (motionSaved) {
                player.motionX = savedMotionX
                player.motionY = savedMotionY
                player.motionZ = savedMotionZ
                motionSaved = false
            }
        }
    }

    private val onJump = handler<JumpEvent> {
        if (!active || riseMode != "Grim")
            return@handler

        val player = mc.thePlayer ?: return@handler

        if (player.hurtTime >= 7 && !player.isInWeb && !speedFastFall && player.onGround && freezing) {
            movementFrozen = true
            freezing = false

            // BlinkComponent.dispatch()：airplus 无对应组件，跳过
            // 保存起跳 motion，回放扣包后再恢复
            val motionX = player.motionX
            val motionY = player.motionY
            val motionZ = player.motionZ
            flushHeld()
            player.motionX = motionX
            player.motionY = motionY
            player.motionZ = motionZ
        }
    }

    private val onTick = handler<GameTickEvent> {
        if (!active || riseMode != "Grim")
            return@handler

        val player = mc.thePlayer ?: return@handler

        if (movementFrozen && !speedFastFall) {
            val blockPos = BlockPos(player)

            if (Velocity.riseRotate) {
                rotating = true
                // Smart Combat (for High Ping)：不旋转，只靠使用物品豁免
                if (!Velocity.riseSmartCombatForHighPing) {
                    RotationUtils.setTargetRotation(
                        Rotation(player.rotationYaw, (90.0 - Math.random() * 0.1).toFloat()),
                        Velocity.riseOptions
                    )
                }
            } else {
                rotating = false
            }

            // 对脚下方的方块发起"使用"，触发 Grim 的使用物品豁免（1.19+ 走 Via 管线）
            val facing = EnumFacing.UP
            val offsetX = (player.posX - blockPos.x).toFloat()
            val offsetY = 1.0f
            val offsetZ = (player.posZ - blockPos.z).toFloat()
            val below = blockPos.down()

            val version = ViaLoadingBase.getInstance().targetVersion
            if (version.isNewerThanOrEqualTo(ProtocolVersion.v1_19)) {
                // RiseGrimUtils 内部同样判 1.19+，这里直接复用
                RiseGrimUtils.sendViaUseItemOn(below.x, below.y, below.z, 1, offsetX, offsetY, offsetZ, 0)
            } else {
                sendPacket(
                    C08PacketPlayerBlockPlacement(below, facing.index, player.heldItem, offsetX, offsetY, offsetZ)
                )
            }

            // S23BlockChange 回执到达后解除冻结（见 handleGrimPacket）
            placedBlocks.add(below)
        } else {
            rotating = false
        }
    }

    private val onUpdate = handler<UpdateEvent> {
        if (!active)
            return@handler

        when (riseMode) {
            "Reduce" -> handleReduceUpdate()
            "Test" -> handleTestUpdate()
        }
    }

    private val onMotion = handler<MotionEvent> { event ->
        if (!active || riseMode != "Reduce")
            return@handler

        // Rise 的 PreMotion：每 tick 重置 jumpReset 待定标记
        if (event.eventState == EventState.PRE)
            pendingJumpReset = false
    }

    private val onMovementInput = handler<MovementInputEvent> { event ->
        if (!active)
            return@handler

        val player = mc.thePlayer ?: return@handler

        when (riseMode) {
            "Reduce" -> {
                if (pendingJumpReset && Velocity.riseJumpReset)
                    event.originalInput.jump = true

                // 旋转激活时强制朝目标前进
                if (player.hurtTime < 7 && Velocity.riseReduceRotate && !Scaffold.handleEvents()) {
                    event.originalInput.moveForward = 1.0f
                    event.originalInput.moveStrafe = 0.0f
                }
            }

            "Test" -> {
                if (Velocity.riseTestType == "Jump Reset" && player.onGround && jumpResetCountdown == 1) {
                    event.originalInput.jump = true
                    jumpResetCountdown = 0
                    event.originalInput.moveForward = 1.0f
                }
            }
        }
    }
    //endregion

    //region Grim（GrimVelocity）

    private fun handleGrimPacket(event: PacketEvent, player: net.minecraft.client.entity.EntityPlayerSP, packet: Packet<*>) {
        if (flushing)
            return

        if (player.hurtTime < 7 || player.isInWeb || speedFastFall)
            return

        // S12：地面 → 冻结移动；空中 → 扣包等待
        if (packet is S12PacketEntityVelocity && packet.entityID == player.entityId) {
            if (player.onGround) {
                movementFrozen = true
            } else {
                heldPackets.add(packet)
                freezing = true
            }
            event.cancelEvent()
            return
        }

        if (freezing) {
            if (packet is S32PacketConfirmTransaction ||
                packet is S27PacketExplosion || // Rise 混淆包 z / ad（击退族）→ 以 S27 近似
                packet is S14PacketEntity ||
                packet is S08PacketPlayerPosLook
            ) {
                heldPackets.add(packet)
                event.cancelEvent()
            }
        }

        // 使用方块回执：解除冻结
        if (packet is S23PacketBlockChange) {
            if (placedBlocks.remove(packet.blockPosition) && placedBlocks.isEmpty()) {
                movementFrozen = false
            }
        }
    }

    private fun flushHeld() {
        flushing = true
        val packets = ArrayList(heldPackets)
        heldPackets.clear()
        packets.forEach { receivePacket(it) }
        flushing = false
    }
    //endregion

    //region Grim2（Grim2Velocity）

    private fun handleGrim2Packet(event: PacketEvent, player: net.minecraft.client.entity.EntityPlayerSP, packet: Packet<*>) {
        if (event.eventType == EventState.SEND) {
            // Full Rotation Fix：yaw/pitch 恰为 90° 倍数时加微小噪声
            // Rise 原实现按 moving 标志替换为 C05/C06，这里直接在原包上改写（效果等价）
            if (Velocity.riseFullRotationFix && packet is C03PacketPlayer && packet.rotating) {
                if (RiseGrimUtils.isFullRotation(packet.yaw) || RiseGrimUtils.isFullRotation(packet.pitch)) {
                    val noise = Velocity.riseRotationNoise
                    packet.yaw += (if (random.nextBoolean()) 1 else -1) * noise
                    packet.pitch += (if (random.nextBoolean()) 1 else -1) * noise
                    debugLog("Full rotation bypass")
                }
            }
        } else {
            // Fake S08：本地直接应用传送位置并置空速度，吞掉服务端的位置同步
            if (Velocity.riseFakeS08 && packet is S08PacketPlayerPosLook) {
                player.setPosition(packet.x, packet.y, packet.z)
                player.motionX = 0.0
                player.motionY = 0.0
                player.motionZ = 0.0
                event.cancelEvent()
                debugLog("Fake S08")
            }

            // Cancel Velocity：直接吞掉击退包
            if (Velocity.riseCancelVelocity && packet is S12PacketEntityVelocity && packet.entityID == player.entityId) {
                event.cancelEvent()
                debugLog("Velocity cancelled")
            }
        }
    }

    private fun debugLog(message: String) {
        if (Velocity.riseDebugLog)
            chat("§8[§cGrimVelocity2§8] §7$message")
    }
    //endregion

    //region Reduce（GrimReduceVelocity）

    private fun handleReducePacket(event: PacketEvent, player: net.minecraft.client.entity.EntityPlayerSP, packet: Packet<*>) {
        if (flushingPackets || player.hurtTime < 3 || player.isInWeb ||
            (!Velocity.riseDelayTillGround && !Velocity.riseDelayPlus) || speedFastFall
        )
            return

        if (packet is S12PacketEntityVelocity && packet.entityID == player.entityId) {
            pendingJumpReset = true
            delayedPackets.add(packet)
            freezing = true // Rise 的 dj
            event.cancelEvent()
        }

        if (freezing) {
            if (packet is S32PacketConfirmTransaction ||
                packet is S14PacketEntity ||
                packet is S27PacketExplosion // Rise 混淆包 ad / z（击退族）→ 以 S27 近似
            ) {
                delayedPackets.add(packet)
                event.cancelEvent()
            }
        }
    }

    private fun handleReduceUpdate() {
        val player = mc.thePlayer ?: return

        // —— PreUpdate（低优先级部分）：瞄准 / shouldReduce / 额外攻击 ——
        shouldReduce = false

        val killAuraTarget = if (KillAura.handleEvents()) KillAura.target else null
        var closest = getClosest(Velocity.riseRange.toFloat())

        if (player.hurtTime < 7 && Velocity.riseReduceRotate && !Scaffold.handleEvents())
            lookAt(killAuraTarget ?: closest)

        // Rise 原实现的 onSwing 检查依赖 Velocity 自己的 onSwing 选项（airplus 无对应选项），跳过

        if (player.ticksExisted <= 20)
            return

        if (speedFastFall)
            return

        val reduceTarget = killAuraTarget ?: closest

        if (player.hurtTime <= Velocity.riseReduceTicks && !Scaffold.handleEvents() &&
            player.hurtTime > Velocity.riseTeleportDisableTicks
        ) {
            // BadPacketsComponent 无对应实现，跳过该检查
            shouldReduce = true
        }

        closest = getClosest(Velocity.riseRange.toFloat())

        if (killAuraTarget != null) {
            // KillAura 目标超出攻击范围时低头旋转（视线扫向脚下规避 Grim 的角度检查）
            val target = killAuraTarget
            val mouseOver = mc.objectMouseOver
            if (player.getDistanceToEntity(target) > 3.0 &&
                mouseOver?.entityHit !== target && Velocity.riseRange > 3
            ) {
                RotationUtils.setTargetRotation(
                    Rotation(player.rotationYaw, (90.0 - Math.random() * 0.1).toFloat()),
                    Velocity.riseOptions
                )
            }
        } else if (closest != null) {
            // 对最近目标做视线遮挡检测：视线打不到目标时低头旋转
            val bb = closest.entityBoundingBox.expand(0.1, 0.1, 0.1)
            val eyes = player.getPositionEyes(1.0f)
            val center = Vec3((bb.minX + bb.maxX) * 0.5, (bb.minY + bb.maxY) * 0.5, (bb.minZ + bb.maxZ) * 0.5)
            val direction = center.subtract(eyes).normalize()
            val endPoint = eyes.addVector(direction.xCoord * 3.0, direction.yCoord * 3.0, direction.zCoord * 3.0)
            val hit = bb.calculateIntercept(eyes, endPoint)
            val distance = hit?.let { eyes.distanceTo(it.hitVec) } ?: eyes.distanceTo(closestPointOnBox(eyes, bb))

            if (distance > 3.0 && player.hurtTime <= Velocity.riseReduceTicks) {
                RotationUtils.setTargetRotation(
                    Rotation(player.rotationYaw, (90.0 - Math.random() * 0.2).toFloat()),
                    Velocity.riseOptions
                )
            }
        }

        if (reduceTarget == null)
            return
        if (player.hurtTime > Velocity.riseReduceTicks + 1)
            return
        if (Scaffold.handleEvents())
            return
        if (player.hurtTime <= Velocity.riseTeleportDisableTicks)
            return

        if (!Velocity.riseExtraHit)
            return

        // Extra Hit：追加一次攻击（1.9+ 先攻击后挥手，1.8 先挥手后攻击）
        val version = ViaLoadingBase.getInstance().targetVersion
        if (version.isNewerThan(ProtocolVersion.v1_8)) {
            mc.playerController?.attackEntity(player, reduceTarget)
            sendPacket(C0APacketAnimation())
        } else {
            sendPacket(C0APacketAnimation())
            mc.playerController?.attackEntity(player, reduceTarget)
        }

        // —— PreUpdate（高优先级部分）：延迟扣包回放 ——
        val flushCondition =
            (player.onGround && freezing && !Velocity.riseDelayPlus) ||
                (player.hurtTime < 3 && freezing) ||
                (Velocity.riseDelayPlus && (player.onGround || !Velocity.riseDelayTillGround) && freezing &&
                    (killAuraTarget == null ||
                        player.getDistanceToEntity(killAuraTarget) < 2.7 ||
                        player.hurtTime == 1)) // 混淆字段 aY 的近似

        if (flushCondition) {
            freezing = false
            flushDelayed()
        }

        if (player.ticksExisted > 25 && freezing) {
            freezing = false
            flushDelayed()
        }
    }

    private fun flushDelayed() {
        flushingPackets = true
        // BlinkComponent.dispatch()：airplus 无对应组件，跳过
        val packets = ArrayList(delayedPackets)
        delayedPackets.clear()
        packets.forEach { receivePacket(it) }
        flushingPackets = false
    }

    private fun getClosest(range: Float): EntityLivingBase? {
        val player = mc.thePlayer ?: return null
        val world = mc.theWorld ?: return null

        return world.loadedEntityList
            .filterIsInstance<EntityLivingBase>()
            .filter { it !== player && isSelected(it, true) && player.getDistanceToEntity(it) <= range }
            .minByOrNull { player.getDistanceToEntity(it) }
    }

    private fun lookAt(entity: Entity?) {
        if (entity == null)
            return

        val player = mc.thePlayer ?: return
        val bb = entity.entityBoundingBox
        val center = Vec3((bb.minX + bb.maxX) / 2.0, (bb.minY + bb.maxY) / 2.0, (bb.minZ + bb.maxZ) / 2.0)

        // Rise 的 RotationUtil.y(entity)：只取朝向目标的 yaw，pitch 保持不变
        val rotation = toRotation(center)
        RotationUtils.setTargetRotation(Rotation(rotation.yaw, player.rotationPitch), Velocity.riseOptions)
    }

    private fun closestPointOnBox(vec: Vec3, bb: net.minecraft.util.AxisAlignedBB): Vec3 =
        Vec3(
            vec.xCoord.coerceIn(bb.minX, bb.maxX),
            vec.yCoord.coerceIn(bb.minY, bb.maxY),
            vec.zCoord.coerceIn(bb.minZ, bb.maxZ)
        )
    //endregion

    //region Test（GrimTestVelocity）

    private fun handleTestPacket(event: PacketEvent, player: net.minecraft.client.entity.EntityPlayerSP, packet: Packet<*>) {
        when (Velocity.riseTestType) {
            // Grim Full：全量击退取消 + Fake S08 + C03 全角度噪声
            "Grim Full" -> {
                if (packet is S12PacketEntityVelocity && packet.entityID == player.entityId) {
                    event.cancelEvent()
                    return
                }

                if (packet is S08PacketPlayerPosLook) {
                    player.setPosition(packet.x, packet.y, packet.z)
                    player.setVelocity(0.0, 0.0, 0.0)
                    event.cancelEvent()
                    return
                }
            }

            // Reduce：检测到 Flag 时跳过下一次延迟
            "Reduce" -> {
                if (Velocity.riseDelayRelease && packet is S08PacketPlayerPosLook && !testDelaying) {
                    skipNextDelay = true
                    logDelay("Flag detected, skipping next delay.")
                }
            }
        }

        // S1B 实体状态（opcode 2 = 受伤）：标记 hurtReceived
        if (packet is S19PacketEntityStatus && packet.getEntity(mc.theWorld) === player && packet.getOpCode().toInt() == 2)
            hurtReceived = true

        // S12：记录击退速度并按子模式处理
        if (packet is S12PacketEntityVelocity) {
            if (packet.entityID != player.entityId)
                return

            velocityReceived = true
            testTarget = if (KillAura.handleEvents()) KillAura.target else null

            val motionX = packet.getMotionX() / 8000.0
            val motionY = packet.getMotionY() / 8000.0
            val motionZ = packet.getMotionZ() / 8000.0
            val kbSpeed = kotlin.math.sqrt(motionX * motionX + motionZ * motionZ)
            knockbackSpeed = kbSpeed

            when (Velocity.riseTestType) {
                "Reduce" -> {
                    if (!hurtReceived)
                        return

                    hurtReceived = false

                    if (isInLiquidOrWeb())
                        return

                    if (System.currentTimeMillis() - enableTime < 1000L)
                        return

                    if (kbSpeed < 0.1)
                        return

                    if (!player.isSprinting)
                        return

                    if (Velocity.riseDelayRelease && !skipNextDelay) {
                        if (kbSpeed >= Velocity.riseDelaySpeedThreshold) {
                            event.cancelEvent()
                            testDelaying = true
                            pendingVelocity = Vec3(motionX, motionY, motionZ)
                            delayStart = System.currentTimeMillis()
                            logDelay("Strong KB (speed: ${"%.3f".format(kbSpeed)}), delay started.")
                            return
                        }

                        logDelay("Weak KB (speed: ${"%.3f".format(kbSpeed)}), normal reduce.")
                    }

                    if (skipNextDelay) {
                        skipNextDelay = false
                        logDelay("Flagged, using normal reduce this time.")
                    }

                    pendingAttacks = getRandomAttackCount()
                    wasNotSprinting = !player.isSprinting
                }

                "Jump Reset" ->
                    jumpResetCountdown = Velocity.riseJumpResetTick
            }
        }

        // 延迟期间扣包（像 PingSpoof 一样延迟回放）
        if (testDelaying) {
            // 无关紧要的世界/声音/粒子包直接放行（Rise 混淆包 c → 以 S2A 粒子包近似）
            if (packet is S23PacketBlockChange || packet is S29PacketSoundEffect || packet is S2APacketParticles)
                return

            if (packet is S3EPacketTeams || packet is S27PacketExplosion) // Rise 混淆包 a → 以 S27 近似
                return

            if (packet is S06PacketUpdateHealth) {
                if (packet.health <= 0.0f) {
                    logDelay("Player died, releasing all packets.")
                    endDelay(true)
                }
                return
            }

            // 关键包立即回放（Rise 混淆包 bt → 以 S05 出生玩家包近似）
            if (packet is S0CPacketSpawnPlayer || packet is S07PacketRespawn || packet is S08PacketPlayerPosLook) {
                logDelay("Critical packet received, releasing all packets.")
                endDelay(true)
                return
            }

            testDelayedPackets.add(packet)
            event.cancelEvent()
        }
    }

    private fun handleTestUpdate() {
        val player = mc.thePlayer ?: return

        if (player.hurtTime == 0) {
            velocityReceived = false
            knockbackSpeed = 0.0
        }

        if (jumpResetCountdown > 0)
            jumpResetCountdown--

        // 延迟中的强击退：瞄准到目标后追加攻击衰减 KB，超时则全额应用
        if (testDelaying && pendingVelocity != null) {
            val pending = pendingVelocity!!
            val mouseOver = mc.objectMouseOver
            val mouseEntity = mouseOver?.entityHit

            if (mouseEntity is net.minecraft.entity.player.EntityPlayer) {
                val attacks = getRandomAttackCount()
                var reduce = 1.0

                repeat(attacks) {
                    if (player.isSprinting)
                        player.isSprinting = false

                    mc.playerController?.attackEntity(player, mouseEntity)
                    player.swingItem()
                    reduce *= 0.6
                }

                player.setVelocity(pending.xCoord * reduce, pending.yCoord, pending.zCoord * reduce)
                logDelay("Target acquired, attacks: $attacks, KB reduced to ${"%.1f".format(reduce * 100.0)}%")
                velocityApplied = true
            }

            if (!velocityApplied) {
                val elapsed = System.currentTimeMillis() - delayStart
                if (elapsed >= Velocity.riseDelayTimeoutMs) {
                    player.setVelocity(pending.xCoord, pending.yCoord, pending.zCoord)
                    logDelay("Timeout (${elapsed}ms), applying full KB.")
                    velocityApplied = true
                }
            }

            if (velocityApplied)
                endDelay(true)
        }

        if (skipNextDelay && player.hurtTime == 0)
            skipNextDelay = false

        // Reduce 子模式的假攻击衰减（0.6 递减）
        if (Velocity.riseTestType == "Reduce" && testTarget != null && pendingAttacks > 0) {
            if (wasNotSprinting)
                sendPacket(C0BPacketEntityAction(player, C0BPacketEntityAction.Action.START_SPRINTING))

            while (pendingAttacks >= 1) {
                sendPacket(C02PacketUseEntity(testTarget, C02PacketUseEntity.Action.ATTACK))
                player.swingItem()
                player.setVelocity(player.motionX * 0.6, player.motionY, player.motionZ * 0.6)
                player.onGround = false
                pendingAttacks--
            }

            if (wasNotSprinting) {
                sendPacket(C0BPacketEntityAction(player, C0BPacketEntityAction.Action.STOP_SPRINTING))
                wasNotSprinting = false
            }
        }
    }

    private fun endDelay(release: Boolean) {
        testDelaying = false

        if (release) {
            // BlinkComponent.dispatch()：airplus 无对应组件，跳过
            while (!testDelayedPackets.isEmpty()) {
                val packet = testDelayedPackets.poll() ?: break
                receivePacket(packet)
            }
        } else {
            testDelayedPackets.clear()
        }

        pendingVelocity = null
        velocityApplied = false
        delayStart = 0L
    }

    private fun logDelay(message: String) {
        if (Velocity.riseLogging && Velocity.riseDelayLogging)
            chat("[Delay] $message")
    }

    private fun getRandomAttackCount(): Int {
        var min = Velocity.riseMinAttacks
        var max = Velocity.riseMaxAttacks

        if (min < 1) min = 1
        if (max < 1) max = 1
        if (min > max) {
            val tmp = min
            min = max
            max = tmp
        }

        return if (min == max) min else java.util.concurrent.ThreadLocalRandom.current().nextInt(min, max + 1)
    }

    private fun isInLiquidOrWeb(): Boolean {
        val player = mc.thePlayer ?: return false
        val world = mc.theWorld ?: return false

        if (player.isInWater || player.isInLava || player.isOnLadder)
            return true

        return world.getBlockState(BlockPos(player)).block is BlockWeb
    }
    //endregion
}
