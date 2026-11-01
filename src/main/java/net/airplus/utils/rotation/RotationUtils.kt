/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.rotation

import net.airplus.event.*
import net.airplus.features.module.modules.combat.FastBow
import net.airplus.features.module.modules.misc.NoRotateSet
import net.airplus.features.module.modules.render.Rotations
import net.airplus.utils.block.block
import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.client.chat
import net.airplus.utils.client.rotation
import net.airplus.utils.extensions.*
import net.airplus.utils.inventory.InventoryUtils
import net.airplus.utils.kotlin.RandomUtils.nextDouble
import net.airplus.utils.kotlin.RandomUtils.nextFloat
import net.airplus.utils.rotation.RaycastUtils.raycastEntity
import net.airplus.utils.rotation.aim.AimScene
import net.airplus.utils.rotation.aim.AimSegmentClass
import net.airplus.utils.rotation.aim.AimTemplateEngine
import net.airplus.utils.rotation.aim.Noise3Calculator
import net.airplus.utils.rotation.aim.Noise3Context
import net.airplus.utils.rotation.aim.RotationNet
import net.airplus.utils.timing.WaitTickUtils
import net.minecraft.entity.Entity
import net.minecraft.network.play.client.C03PacketPlayer
import net.minecraft.tileentity.TileEntity
import net.minecraft.util.*
import javax.vecmath.Vector2f
import kotlin.math.*

object RotationUtils : MinecraftInstance, Listenable {

    /**
     * Our final rotation point, which [currentRotation] follows.
     */
    private var targetRotation: Rotation? = null

    /**
     * 视角是否被模块直接接管：ApplyServerSide=false 的可见旋转走 [Rotation.toPlayer]
     * 直接写 rotationYaw/Pitch（不经过 setAngles，也不会设置 currentRotation），
     * 真实鼠标增量与视角真值会被模块转动打断。静默 spoof（currentRotation）不改
     * 第一人称视角，不在此列 —— 录制端（RotationRecorder）据此区分两类接管。
     */
    val isViewOverridden: Boolean
        get() = targetRotation != null && activeSettings?.applyServerSide == false

    /**
     * The current rotation that is responsible for aiming at objects, synchronizing movement, etc.
     */
    var currentRotation: Rotation? = null

    /**
     * The last rotation that the server has received.
     */
    var serverRotation: Rotation
        get() = lastRotations[0]
        set(value) {
            lastRotations = lastRotations.toMutableList().apply { set(0, value) }
        }

    private const val MAX_CAPTURE_TICKS = 3

    var modifiedInput = MovementInput()

    /**
     * 强制移动修复走 strict 分支（绕过旋转输入重建），由 Speed OldGrim-TargetStrafe 在按住跳跃键时置位。
     */
    var forceStrict = false

    /**
     * A list that stores the last rotations captured from 0 up to [MAX_CAPTURE_TICKS] previous ticks.
     */
    var lastRotations = MutableList(MAX_CAPTURE_TICKS) { Rotation.ZERO }
        set(value) {
            val updatedList = MutableList(lastRotations.size) { Rotation.ZERO }

            for (tick in 0 until MAX_CAPTURE_TICKS) {
                updatedList[tick] = if (tick == 0) value[0] else field[tick - 1]
            }

            field = updatedList
        }

    /**
     * The currently in-use rotation settings, which are used to determine how the rotations will move.
     */
    var activeSettings: RotationSettings? = null

    var resetTicks = 0

    /**
     * Face block
     *
     * @param blockPos target block
     */
    fun faceBlock(
        blockPos: BlockPos?,
        throughWalls: Boolean = true,
        targetUpperFace: Boolean = false,
        hRange: ClosedFloatingPointRange<Double> = 0.0..1.0
    ): VecRotation? {
        val world = mc.theWorld ?: return null
        val player = mc.thePlayer ?: return null

        if (blockPos == null) return null

        val block = blockPos.block ?: return null

        val eyesPos = player.eyes
        val startPos = Vec3(blockPos)

        var visibleVec: VecRotation? = null
        var invisibleVec: VecRotation? = null

        val yRange = if (targetUpperFace) 0.0..0.01 else 0.0..1.0

        for (x in hRange) {
            for (y in yRange) {
                for (z in hRange) {
                    val posVec = startPos.add(block.lerpWith(x, y, z))

                    val dist = eyesPos.distanceTo(posVec)

                    val (diffX, diffY, diffZ) = posVec - eyesPos
                    val diffXZ = sqrt(diffX * diffX + diffZ * diffZ)

                    val rotation = Rotation(
                        MathHelper.wrapAngleTo180_float(atan2(diffZ, diffX).toDegreesF() - 90f),
                        MathHelper.wrapAngleTo180_float(-atan2(diffY, diffXZ).toDegreesF())
                    ).fixedSensitivity()

                    val rotationVector = getVectorForRotation(rotation)
                    val vector = eyesPos + (rotationVector * dist)

                    val currentVec = VecRotation(posVec, rotation)
                    val raycast = world.rayTraceBlocks(eyesPos, vector, false, true, false)

                    val currentRotation = currentRotation ?: player.rotation

                    if (raycast != null && raycast.blockPos == blockPos && (!targetUpperFace || raycast.sideHit == EnumFacing.UP)) {
                        if (visibleVec == null || rotationDifference(
                                currentVec.rotation, currentRotation
                            ) < rotationDifference(visibleVec.rotation, currentRotation)
                        ) {
                            visibleVec = currentVec
                        }
                    } else if (throughWalls) {
                        val invisibleRaycast = performRaytrace(blockPos, rotation) ?: continue

                        if (invisibleRaycast.blockPos != blockPos) {
                            continue
                        }

                        if (invisibleVec == null || rotationDifference(
                                currentVec.rotation, currentRotation
                            ) < rotationDifference(invisibleVec.rotation, currentRotation)
                        ) {
                            invisibleVec = currentVec
                        }
                    }
                }
            }
        }

        return visibleVec ?: invisibleVec
    }

    /**
     * Face trajectory of arrow by default, can be used for calculating other trajectories (eggs, snowballs)
     * by specifying `gravity` and `velocity` parameters
     *
     * @param target      your enemy
     * @param predict     predict new enemy position
     * @param predictSize predict size of predict
     * @param gravity     how much gravity does the projectile have, arrow by default
     * @param velocity    with what velocity will the projectile be released, velocity for arrow is calculated when null
     */
    fun faceTrajectory(
        target: Entity,
        predict: Boolean,
        predictSize: Float,
        gravity: Float = 0.05f,
        velocity: Float? = null,
    ): Rotation {
        val player = mc.thePlayer

        val posX =
            target.posX + (if (predict) (target.posX - target.prevPosX) * predictSize else .0) - (player.posX + if (predict) player.posX - player.prevPosX else .0)
        val posY =
            target.entityBoundingBox.minY + (if (predict) (target.entityBoundingBox.minY - target.prevPosY) * predictSize else .0) + target.eyeHeight - 0.15 - (player.entityBoundingBox.minY + (if (predict) player.posY - player.prevPosY else .0)) - player.getEyeHeight()
        val posZ =
            target.posZ + (if (predict) (target.posZ - target.prevPosZ) * predictSize else .0) - (player.posZ + if (predict) player.posZ - player.prevPosZ else .0)
        val posSqrt = sqrt(posX * posX + posZ * posZ)

        var finalVelocity = velocity

        if (finalVelocity == null) {
            finalVelocity = if (FastBow.handleEvents()) 1f else player.itemInUseDuration / 20f
            finalVelocity = ((finalVelocity * finalVelocity + finalVelocity * 2) / 3).coerceAtMost(1f)
        }

        val gravityModifier = 0.12f * gravity

        return Rotation(
            atan2(posZ, posX).toDegreesF() - 90f, -atan(
                (finalVelocity * finalVelocity - sqrt(
                    finalVelocity * finalVelocity * finalVelocity * finalVelocity - gravityModifier * (gravityModifier * posSqrt * posSqrt + 2 * posY * finalVelocity * finalVelocity)
                )) / (gravityModifier * posSqrt)
            ).toDegreesF()
        )
    }

    /**
     * Translate vec to rotation
     *
     * @param vec     target vec
     * @param predict predict new location of your body
     * @return rotation
     */
    fun toRotation(vec: Vec3, predict: Boolean = false, fromEntity: Entity = mc.thePlayer): Rotation {
        val eyesPos = fromEntity.eyes
        if (predict) eyesPos.addVector(fromEntity.motionX, fromEntity.motionY, fromEntity.motionZ)

        val (diffX, diffY, diffZ) = vec - eyesPos
        return Rotation(
            MathHelper.wrapAngleTo180_float(
                atan2(diffZ, diffX).toDegreesF() - 90f
            ), MathHelper.wrapAngleTo180_float(
                -atan2(diffY, sqrt(diffX * diffX + diffZ * diffZ)).toDegreesF()
            )
        )
    }

    /**
     * Search good center
     *
     * @param bb                entity box to search rotation for
     * @param outborder         outborder option
     * @param random            random option
     * @param predict           predict, offsets rotation by player's motion
     * @param lookRange         look range
     * @param attackRange       attack range, rotations in attack range will be prioritized
     * @param throughWallsRange through walls range,
     * @return center
     */
    fun searchCenter(
        bb: AxisAlignedBB, distanceBasedSpot: Boolean = false, outborder: Boolean,
        randomization: RandomizationSettings? = null, predict: Boolean,
        lookRange: Float, attackRange: Float, throughWallsRange: Float = 0f,
        bodyPoints: List<String> = listOf("Head", "Feet"), horizontalSearch: ClosedFloatingPointRange<Float> = 0f..1f,
        aiStableSpot: Boolean = false,
        verticalRange: ClosedFloatingPointRange<Float>? = null,
    ): Rotation? {
        val scanRange = lookRange.coerceAtLeast(attackRange)

        val max = BodyPoint.fromString(bodyPoints[0]).range.endInclusive
        val min = BodyPoint.fromString(bodyPoints[1]).range.start

        // 竖直候选点：传入 verticalRange 时在区间内均匀采样（步长 ≈ 0.1，含两端），
        // 覆盖整个自定义范围；否则沿用 bodyPoints 档位的原有采样节奏（Double 区间
        // 默认步长 1.0，实际上只取区间起点）。
        val yCandidates: List<Double> = verticalRange?.let { range ->
            val lo = range.start.toDouble()
            val hi = range.endInclusive.toDouble()
            val stepCount = ((hi - lo) / 0.1).roundToInt().coerceAtLeast(1)
            (0..stepCount).map { lo + (hi - lo) * it / stepCount }
        } ?: listOf(min)

        if (outborder) {
            val vec3 = bb.lerpWith(nextDouble(0.5, 1.3), nextDouble(0.9, 1.3), nextDouble(0.5, 1.3))

            return toRotation(vec3, predict).fixedSensitivity()
        }

        val eyes = mc.thePlayer.eyes

        val preferredRotation = toRotation(getNearestPointBB(eyes, bb), predict).takeIf {
            distanceBasedSpot
        } ?: currentRotation ?: mc.thePlayer.rotation

        val currRotation = Rotation.ZERO.plus(preferredRotation)

        var attackRotation: Pair<Rotation, Float>? = null
        var lookRotation: Pair<Rotation, Float>? = null

        // 随机化跳点（在敌人身上随机选取瞄准点）。aiStableSpot = AI 瞄准激活时按
        // RandomizationSettings.AiSpotHopInterval 冷却限流 —— AI 引擎逐 tick 平滑
        // 转换剩余差，每 tick 跳点会让剩余差方向高频抖动、污染漂移特征。
        randomization?.takeIf { it.randomizationChosen && it.shouldHop(aiStableSpot) }?.run {
            processNextSpot(bb, currRotation, eyes, scanRange.toDouble())
        }

        val (hMin, hMax) = horizontalSearch.start.toDouble() to min(horizontalSearch.endInclusive + 0.01, 1.0)

        for (x in hMin..hMax) {
            for (y in yCandidates) {
                for (z in hMin..hMax) {
                    val vec = bb.lerpWith(x, y, z)

                    val rotation = toRotation(vec, predict).fixedSensitivity()

                    // Calculate actual hit vec after applying fixed sensitivity to rotation
                    val gcdVec = bb.calculateIntercept(
                        eyes, eyes + getVectorForRotation(rotation) * scanRange.toDouble()
                    )?.hitVec ?: continue

                    val distance = eyes.distanceTo(gcdVec)

                    // Check if vec is in range
                    // Skip if a rotation that is in attack range was already found and the vec is out of attack range
                    if (distance > scanRange || (attackRotation != null && distance > attackRange)) continue

                    // Check if vec is reachable through walls
                    if (!isVisible(gcdVec) && distance > throughWallsRange) continue

                    val rotationWithDiff = rotation to rotationDifference(rotation, currRotation)

                    if (distance <= attackRange) {
                        if (attackRotation == null || rotationWithDiff.second < attackRotation.second) attackRotation =
                            rotationWithDiff
                    } else {
                        if (lookRotation == null || rotationWithDiff.second < lookRotation.second) lookRotation =
                            rotationWithDiff
                    }
                }
            }
        }

        return attackRotation?.first ?: lookRotation?.first ?: run {
            val vec = getNearestPointBB(eyes, bb)
            val dist = eyes.distanceTo(vec)

            if (dist <= scanRange && (dist <= throughWallsRange || isVisible(vec))) toRotation(vec, predict)
            else null
        }
    }

    /**
     * Calculate difference between the client rotation and your entity
     *
     * @param entity your entity
     * @return difference between rotation
     */
    fun rotationDifference(entity: Entity) =
        rotationDifference(toRotation(entity.hitBox.center, true), mc.thePlayer.rotation)

    /**
     * Calculate difference between two rotations
     *
     * @param a rotation
     * @param b rotation
     * @return difference between rotation
     */
    fun rotationDifference(a: Rotation, b: Rotation = serverRotation) =
        hypot(angleDifference(a.yaw, b.yaw), a.pitch - b.pitch)

    private fun limitAngleChange(
        currentRotation: Rotation, targetRotation: Rotation, settings: RotationSettings
    ): Rotation {
        val (hSpeed, vSpeed) = if (settings.instant) {
            180f to 180f
        } else settings.horizontalSpeed to settings.verticalSpeed

        return performAngleChange(
            currentRotation,
            targetRotation,
            hSpeed,
            vSpeed,
            !settings.instant && settings.legitimize,
            settings.minRotationDifference,
            settings.minRotationDifferenceResetTiming,
            settings,
        )
    }

    fun performAngleChange(
        currentRotation: Rotation,
        targetRotation: Rotation,
        hSpeed: Float,
        vSpeed: Float = hSpeed,
        legitimize: Boolean,
        minRotationDiff: Float,
        minRotationDiffResetTiming: String,
        settings: RotationSettings? = null,
    ): Rotation {
        var (yawDiff, pitchDiff) = angleDifferences(targetRotation, currentRotation)

        val rotationDifference = hypot(yawDiff, pitchDiff)

        val isShortStopActive = WaitTickUtils.hasScheduled(this)
        val isNoRotateSetActive = WaitTickUtils.hasScheduled(NoRotateSet)

        if (isNoRotateSetActive) {
            yawDiff = 0F
            pitchDiff = 0F
        } else if (isShortStopActive || activeSettings?.shouldPerformShortStop() == true) {
            if (!isShortStopActive) {
                WaitTickUtils.schedule(activeSettings?.shortStopDuration?.random()?.plus(1) ?: 0, this)
            }

            activeSettings?.resetSimulateShortStopData()

            val yawSlowdown = (0F..0.1F).random()
            val pitchSlowdown = (0F..0.1F).random()

            yawDiff = (yawDiff * yawSlowdown).withGCD()
            pitchDiff = (pitchDiff * pitchSlowdown).withGCD()
        }

        // AI 学习转头：Template（模板库）/ Neural（MLP）生成增量，null 则回退下方 Default 管线。
        // 短停期间跳过 AI：短停已把剩余差缩到近零，AI 引擎按「全速走剩余差」的方式
        // 一 tick 就会走完缩放后的差，完全抵消「几乎不动」的意图，交给 Default 管线处理。
        // Noise3：C40N 路径规划器直接给出本 tick 的绝对旋转（上下文由模块每 tick
        // 组装挂在 settings.noise3Context），完全绕过速度限幅/慢化管线；上下文缺失
        // 时回退 Default 管线。NoRotateSet
        // （blink/免转协议）期间全部跳过：no-rotate 契约下不该产生任何转向增量（审查 S3）。
        val aiDiff = if (settings != null && !settings.instant &&
            !isShortStopActive && !isNoRotateSetActive
        ) {
            when (settings.aimMode) {
                "Noise3" -> settings.noise3Context?.let { noise3GeneratedDiff(it, currentRotation) }
                else -> aiGeneratedDiff(settings, yawDiff, pitchDiff)
            }
        } else null
        if (aiDiff != null) {
            settings?.updateSimulateShortStopData(aiDiff.first)
            return currentRotation.plus(Rotation(aiDiff.first.withGCD(), aiDiff.second.withGCD()))
        }

        var (straightLineYaw, straightLinePitch) = run {
            val baseYawSpeed = abs(yawDiff safeDiv rotationDifference) * hSpeed
            val basePitchSpeed = abs(pitchDiff safeDiv rotationDifference) * vSpeed
            // Apply imperfect correlation
            if (legitimize) {
                baseYawSpeed * (0.9F..1.1F).random()
                basePitchSpeed * (0.9F..1.1F).random()
            }

            baseYawSpeed to basePitchSpeed
        }

        straightLineYaw = yawDiff.coerceIn(-straightLineYaw, straightLineYaw)
        straightLinePitch = pitchDiff.coerceIn(-straightLinePitch, straightLinePitch)

        // Humans usually have some small jitter when moving their mouse from point A to point B.
        // Usually when a rotation axis' difference is prioritized.
        if (rotationDifference > 0F) {
            val yawJitter = (-0.03F..0.03F).random() * straightLineYaw
            val pitchJitter = (-0.02F..0.02F).random() * straightLinePitch

            straightLineYaw += yawJitter
            straightLinePitch += pitchJitter
        }

        val minYaw = nextFloat(min(minRotationDiff, getFixedAngleDelta()), minRotationDiff).withGCD()
        val minPitch = nextFloat(min(minRotationDiff, getFixedAngleDelta()), minRotationDiff).withGCD()

        applySlowDown(straightLineYaw, minYaw, minRotationDiffResetTiming, true, legitimize) {
            straightLineYaw = it
        }

        applySlowDown(straightLinePitch, minPitch, minRotationDiffResetTiming, false, legitimize) {
            straightLinePitch = it
        }

        return currentRotation.plus(Rotation(straightLineYaw, straightLinePitch))
    }

    /**
     * 拟真瞄准点游走：真人不会把准星钉死在 hitbox 同一点上，注视点在目标附近
     * 缓慢漂移并持续产生亚 GCD 级微修正。给剩余角度差叠加一个慢速随机游走偏移
     * （每 8~20 tick 在圆盘内重采样目标点，逐 tick 平滑趋近），量级远小于一次
     * 甩动，只影响收尾与跟踪阶段的微观形态。附近没有生物实体（方块瞄准）时不生效。
     */
    private const val WANDER_RANGE = 0.9f
    private var wanderYaw = 0f
    private var wanderPitch = 0f
    private var wanderTargetYaw = 0f
    private var wanderTargetPitch = 0f
    private var wanderTicksLeft = 0

    private fun updateWander(entityDist: Float): Pair<Float, Float> {
        if (entityDist <= 0f) {
            wanderYaw = 0f
            wanderPitch = 0f
            wanderTicksLeft = 0
            return 0f to 0f
        }

        if (wanderTicksLeft <= 0) {
            wanderTicksLeft = (8..20).random()
            val angle = nextFloat(0f, (2.0 * Math.PI).toFloat())
            val radius = WANDER_RANGE * sqrt(nextFloat(0f, 1f))
            wanderTargetYaw = cos(angle) * radius
            wanderTargetPitch = sin(angle) * radius * 0.6f
        }
        wanderTicksLeft--

        wanderYaw += (wanderTargetYaw - wanderYaw) * 0.15f
        wanderPitch += (wanderTargetPitch - wanderPitch) * 0.15f
        return wanderYaw to wanderPitch
    }

    /** 瞄准点漂移追踪（见 aiGeneratedDiff 注释）：决策态单级历史的 raw needed。 */
    private var prevRawAim = 0f to 0f
    private var aimDriftValid = false

    /** Noise3 引擎（跨 tick 维护 actual/target 状态，resync 自愈）。 */
    private val noise3 = Noise3Calculator()

    /**
     * Noise3 转头：把 C40N 规划器输出的绝对旋转折算成相对 [currentRotation] 的增量。
     * 返回 null 时由调用方回退 Default 管线。
     */
    private fun noise3GeneratedDiff(context: Noise3Context, currentRotation: Rotation): Pair<Float, Float>? {
        val player = mc.thePlayer ?: return null

        val applied = noise3.compute(
            currentRotation, player.hurtTime, context.targetId, context.aim1, context.aim2,
            context.entityYaw, context.entityPitch, context.params
        )

        val diff = angleDifferences(applied, currentRotation)
        return diff.x to diff.y
    }

    /**
     * AI 学习转头：按 [RotationSettings.aimMode]（Template / Neural）把当前剩余角度差交给
     * 模板库或神经网络，生成下一 tick 的 (yawDelta, pitchDelta)。无法生成时返回 null（回退 Default 管线）。
     */
    private fun aiGeneratedDiff(settings: RotationSettings, yawDiff: Float, pitchDiff: Float): Pair<Float, Float>? {
        val player = mc.thePlayer ?: return null

        // 当前角速度 = 上上 tick 到上一 tick 的实际旋转变化
        val vel = angleDifferences(lastRotations[0], lastRotations[1])
        val moveSpeed = hypot(player.motionX, player.motionZ).toFloat()
        val entityDist = nearestEntityDistance(player)

        // 瞄准点漂移（次序关键，按每 tick 内部顺序「发包 → lastRotations 前移 → 决策」）：
        // 决策 tick i 的 yawDiff ≡ 录制契约 needed(i-1)，prevRawAim ≡ needed(i-2)，
        // vel = lastRotations[0] - lastRotations[1] = Δ(i-1)，因此
        //   d = yawDiff - prevRawAim + vel.x ── = needed(i-1) - needed(i-2) + Δ(i-1)
        // 与录制（TickSample）/ train.py 三端同一公式、同一 one-tick shift。
        // 状态推进放在 diff==0 早退之前：瞄准期间（-needed≈0）链条保持连续，
        // 复跑首 tick 的漂移直接等于真实瞄准点移动，而不是靠间断保护碰运气。
        // 保护：首次调用 / 附近无实体（瞄准点未定义）→ 失效，本 tick 记 0；
        // |d| 超过 AimSegmentClass.DRIFT_DISCONTINUITY_DEG（换目标 / 接管恢复后的
        // needed 跳变 / invalidate 零样本混入）同样记 0。必须用 raw（未加 wander）：
        // wander 幅值 ±0.9° 会严重污染这个逐 tick 漂移特征（类别路由只认 scene 真值，
        // 漂移仅作为 NETWORK 输入特征使用）。
        if (entityDist <= 0f) aimDriftValid = false
        val driftYaw: Float
        val driftPitch: Float
        if (!aimDriftValid) {
            driftYaw = 0f
            driftPitch = 0f
        } else {
            val dy = yawDiff - prevRawAim.first + vel.x
            val dp = pitchDiff - prevRawAim.second + vel.y
            if (hypot(dy, dp) > AimSegmentClass.DRIFT_DISCONTINUITY_DEG) {
                driftYaw = 0f; driftPitch = 0f
            } else {
                driftYaw = dy; driftPitch = dp
            }
        }
        prevRawAim = yawDiff to pitchDiff
        aimDriftValid = true

        if (hypot(yawDiff, pitchDiff) <= 0f) return null

        val (wanderYaw, wanderPitch) = updateWander(entityDist)

        // 决策时刻场景（三端共用 AimScene 判据，与录制真值同语义）：
        // 视线（lastRotations[0] = 决策时刻实际生效的旋转，录制契约里本 tick 转动
        // 已应用后的视角）压在可见实体 hitbox 上 → TRACK；附近无实体 → NONE（引擎
        // 返回 null，交回 Default 管线）；其余按剩余差幅值分 定位/调整。
        // scene(i-1) 语义与 pair 级训练标签严格一致 —— 这里 scene 是「上一 tick
        // 转完」的状态，正好对齐训练侧 ctx = ticks[i-1] 的取值时机。
        val scene = AimScene.classify(
            onTarget = AimScene.crosshairOnEntity(lastRotations[0].yaw, lastRotations[0].pitch),
            hasTarget = entityDist > 0f,
            neededDiff = hypot(yawDiff, pitchDiff),
        )

        // v4 传入语义：引擎收 raw needed（特征 / 分类 / 方向锁定 / 缩放全部与训练分布
        // 一致）+ 独立的 wander 偏移（由引擎按尾部衰减叠加到输出增量上，
        // 见 RotationNet/AimTemplateEngine 的拟真游走注释）。
        val generated = when (settings.aimMode) {
            "Template" -> AimTemplateEngine.sample(
                yawDiff, pitchDiff, wanderYaw, wanderPitch,
                driftYaw, driftPitch,
                vel.x, vel.y, moveSpeed, entityDist, getFixedAngleDelta(), scene,
            )
            "Neural" -> RotationNet.predict(
                yawDiff, pitchDiff, wanderYaw, wanderPitch,
                driftYaw, driftPitch,
                vel.x, vel.y, moveSpeed, entityDist, getFixedAngleDelta(), scene,
            )
            else -> null
        } ?: return null

        // AI 生成序列的人手化：模板重放 / MLP 回归对（几乎）相同输入输出确定性
        // 增量，整段转头的 per-tick 步长分布过于一致，反作弊的 stride 一致性
        // （smooth）检测容易命中。这与训练数据量无关 —— 数据再多，确定性输出
        // 序列的「过机械设备感」仍在。叠加与 Default 管线同量级的相对扰动
        // （±3% yaw / ±2% pitch，见 applyAiJitter），在 gcd 量化前完成。
        return applyAiJitter(generated)
    }

    /**
     * 对 AI 生成的增量叠加相对幅值抖动。与 Default 管线的
     * (yawJitter, pitchJitter) = (±3%, ±2%) × straightLine 分量同一量级。
     *
     * 老实现对已 gcd 量化的值乘 (1±3%) 后由 performAngleChange 的 withGCD() 取整，
     * 增量 < 17 gcd 时几乎总是落回同一个 gcd 桶 —— 抖动实际是死代码。改为
     * 「gcd 单位上带抖动 + 概率取整」：小数部分直接作为本 tick 的进位概率，
     * 输出均值严格等于抖动后的幅值（E[输出] = value×(1±rel)），but 每 tick 落在
     * 相邻 gcd 桶上随机游走 —— 步长散步在数据量级上真正生效，打掉确定性输出
     * 序列的 stride 一致性（smooth）特征。
     */
    private fun applyAiJitter(diff: Pair<Float, Float>): Pair<Float, Float> {
        if (diff.first == 0f && diff.second == 0f) return diff

        val gcd = getFixedAngleDelta()
        if (gcd <= 0f) {
            // 无灵敏度取不到 gcd：退回纯相对抖动（下一级管线会再量化）
            return diff.first * (1f + nextFloat(-0.03f, 0.03f)) to
                diff.second * (1f + nextFloat(-0.02f, 0.02f))
        }

        return jitterToGcdBucket(diff.first, 0.03f, gcd) to jitterToGcdBucket(diff.second, 0.02f, gcd)
    }

    private fun jitterToGcdBucket(value: Float, rel: Float, gcd: Float): Float {
        if (value == 0f) return 0f

        val scaled = value / gcd * (1f + nextFloat(-rel, rel))
        val base = floor(scaled)
        // 概率取整：frac 是 scaled 落在下一桶的进位概率；frac == 0 时永不向上取整
        val carry = if (nextFloat(0f, 1f) < scaled - base) 1f else 0f
        return (base + carry) * gcd
    }

    // 与 RotationRecorder / currentAimDiff 保持一致：只统计存活生物实体，避免掉落物/
    // 尸体污染 entityDist 特征，且与 needed 的目标集（currentAimDiff 过滤 isDead）一致
    private fun nearestEntityDistance(player: Entity): Float =
        mc.theWorld?.loadedEntityList?.asSequence()
            ?.filterIsInstance<net.minecraft.entity.EntityLivingBase>()
            ?.filter { it !== player && !it.isDead }
            ?.minOfOrNull { player.getDistanceToEntity(it) }
            ?: 0f

    private fun applySlowDown(
        diff: Float, min: Float, timing: String, yaw: Boolean, applyRealism: Boolean, action: (Float) -> Unit
    ) {
        if (diff == 0f) {
            action(diff)
            return
        }

        val lastTick1 = angleDifferences(serverRotation, lastRotations[1]).let { diffs ->
            if (yaw) diffs.x else diffs.y
        }

        val diffAbs = abs(diff)
        val isSlowingDown = diffAbs <= abs(lastTick1)

        if (diffAbs.withGCD() <= min && (timing == "Always" || timing == "OnSlowDown" && isSlowingDown || timing == "OnStart" && lastTick1 == 0F)) {
            action(0f)
            return
        }

        if (!applyRealism) {
            action(diff)
            return
        }

        val range = when {
            lastTick1 == 0f -> {
                val inc = 0.2f * (diffAbs / 50f).coerceIn(0f, 1f)

                0.1F + inc..0.5F + inc
            }

            else -> 0.3f..0.7f
        }

        val new = (lastTick1..diff).lerpWith(range.random())

        if (abs(new.withGCD()) <= min && isSlowingDown) {
            action(diff)
        } else {
            action(new)
        }
    }

    /**
     * Calculate difference between two angle points
     *
     * @param a angle point
     * @param b angle point
     * @return difference between angle points
     */
    fun angleDifference(a: Float, b: Float) = MathHelper.wrapAngleTo180_float(a - b)

    /**
     * Returns a 2-parameter vector with the calculated angle differences between [target] and [current] rotations
     */
    fun angleDifferences(target: Rotation, current: Rotation) =
        Vector2f(angleDifference(target.yaw, current.yaw), target.pitch - current.pitch)

    /**
     * Calculate rotation to vector
     *
     * @param [yaw] [pitch] your rotation
     * @return target vector
     */
    fun getVectorForRotation(yaw: Float, pitch: Float): Vec3 {
        val yawRad = yaw.toRadians()
        val pitchRad = pitch.toRadians()

        val f = MathHelper.cos(-yawRad - PI.toFloat())
        val f1 = MathHelper.sin(-yawRad - PI.toFloat())
        val f2 = -MathHelper.cos(-pitchRad)
        val f3 = MathHelper.sin(-pitchRad)

        return Vec3((f1 * f2).toDouble(), f3.toDouble(), (f * f2).toDouble())
    }

    fun getVectorForRotation(rotation: Rotation) = getVectorForRotation(rotation.yaw, rotation.pitch)

    /**
     * Returns the inverted yaw angle.
     *
     * @param yaw The original yaw angle in degrees.
     * @return The yaw angle inverted by 180 degrees.
     */
    fun invertYaw(yaw: Float): Float {
        return (yaw + 180) % 360
    }

    /**
     * Allows you to check if your crosshair is over your target entity
     *
     * @param targetEntity       your target entity
     * @param blockReachDistance your reach
     * @return if crosshair is over target
     */
    fun isFaced(targetEntity: Entity, blockReachDistance: Double) =
        raycastEntity(blockReachDistance) { entity: Entity -> targetEntity == entity } != null

    /**
     * Allows you to check if your crosshair is over your target entity
     *
     * @param targetEntity       your target entity
     * @param blockReachDistance your reach
     * @return if crosshair is over target
     */
    fun isRotationFaced(targetEntity: Entity, blockReachDistance: Double, rotation: Rotation) = raycastEntity(
        blockReachDistance, rotation.yaw, rotation.pitch
    ) { entity: Entity -> targetEntity == entity } != null

    /**
     * Allows you to check if your enemy is behind a wall
     */
    fun isVisible(vec3: Vec3) = mc.theWorld.rayTraceBlocks(mc.thePlayer.eyes, vec3) == null

    fun isEntityHeightVisible(entity: Entity) = arrayOf(
        entity.hitBox.center.withY(entity.hitBox.maxY), entity.hitBox.center.withY(entity.hitBox.minY)
    ).any { isVisible(it) }

    fun isEntityHeightVisible(entity: TileEntity) = arrayOf(
        entity.renderBoundingBox.center.withY(entity.renderBoundingBox.maxY),
        entity.renderBoundingBox.center.withY(entity.renderBoundingBox.minY)
    ).any { isVisible(it) }

    /**
     * Set your target rotation
     *
     * @param rotation your target rotation
     */
    fun setTargetRotation(rotation: Rotation, options: RotationSettings, ticks: Int = options.resetTicks) {
        if (rotation.yaw.isNaN() || rotation.pitch.isNaN() || rotation.pitch > 90 || rotation.pitch < -90) {
            return
        }

        if (!options.prioritizeRequest && activeSettings?.prioritizeRequest == true) {
            return
        }

        if (!options.applyServerSide) {
            currentRotation?.let {
                mc.thePlayer.rotationYaw = it.yaw
                mc.thePlayer.rotationPitch = it.pitch
            }

            resetRotation()
        }

        targetRotation = rotation

        resetTicks = if (!options.applyServerSide || !options.resetTicksValue.isSupported()) 1 else ticks

        activeSettings = options

        if (options.immediate) {
            update()
        }
    }

    private fun resetRotation() {
        resetTicks = 0
        currentRotation?.let { (yaw, _) ->
            mc.thePlayer?.let {
                it.rotationYaw = yaw + angleDifference(it.rotationYaw, yaw)
                syncRotations()
            }
        }
        targetRotation = null
        currentRotation = null
        activeSettings = null
    }

    /**
     * Returns the smallest angle difference possible with a specific sensitivity ("gcd")
     */
    fun getFixedAngleDelta(sensitivity: Float = mc.gameSettings.mouseSensitivity) =
        (sensitivity * 0.6f + 0.2f).pow(3) * 1.2f

    /**
     * Returns angle that is legitimately accomplishable with player's current sensitivity
     */
    fun getFixedSensitivityAngle(targetAngle: Float, startAngle: Float = 0f, gcd: Float = getFixedAngleDelta()) =
        startAngle + ((targetAngle - startAngle) / gcd).roundToInt() * gcd

    /**
     * Creates a raytrace even when the target [blockPos] is not visible
     */
    fun performRaytrace(
        blockPos: BlockPos,
        rotation: Rotation,
        reach: Float = mc.playerController.blockReachDistance,
    ): MovingObjectPosition? {
        val world = mc.theWorld ?: return null
        val player = mc.thePlayer ?: return null

        val eyes = player.eyes

        return blockPos.block?.collisionRayTrace(
            world, blockPos, eyes, eyes + (getVectorForRotation(rotation) * reach.toDouble())
        )
    }

    fun performRayTrace(blockPos: BlockPos, vec: Vec3, eyes: Vec3 = mc.thePlayer.eyes) =
        mc.theWorld?.let { blockPos.block?.collisionRayTrace(it, blockPos, eyes, vec) }

    fun syncRotations() {
        val player = mc.thePlayer ?: return

        player.prevRotationYaw = player.rotationYaw
        player.prevRotationPitch = player.rotationPitch
        player.renderArmYaw = player.rotationYaw
        player.renderArmPitch = player.rotationPitch
        player.prevRenderArmYaw = player.rotationYaw
        player.prevRotationPitch = player.rotationPitch
    }

    private fun update() {
        val settings = activeSettings ?: return
        val player = mc.thePlayer ?: return

        val playerRotation = player.rotation

        val shouldUpdate = !InventoryUtils.serverOpenContainer && !InventoryUtils.serverOpenInventory

        if (!shouldUpdate) {
            return
        }

        val serverRotation = currentRotation ?: serverRotation

        if (resetTicks == 0) {
            // 重置视角俯仰锁定（模块注入，如 Telly 低头）：回归目标仅替换 Pitch，Yaw 照常回归真实视角
            val resetTarget = settings.lockedResetPitch?.let { Rotation(playerRotation.yaw, it) } ?: playerRotation

            if (isDifferenceAcceptableForReset(serverRotation, resetTarget, settings)) {
                settings.noise3Context = null
                resetRotation()
                return
            }

            // 回归真实视角路径：目标已丢失，清掉残留的 Noise3 上下文，
            // 避免过期瞄准点在此路径把视角重新拉向死目标（审查 B1）
            settings.noise3Context = null

            currentRotation = limitAngleChange(serverRotation, resetTarget, settings).fixedSensitivity()
            return
        }

        targetRotation?.let {
            limitAngleChange(serverRotation, it, settings).let { rotation ->
                if (!settings.applyServerSide) {
                    rotation.toPlayer(player)
                } else {
                    currentRotation = rotation.fixedSensitivity()
                }
            }
        }

        if (resetTicks > 0) {
            resetTicks--
        }
    }

    private fun isDifferenceAcceptableForReset(
        curr: Rotation, target: Rotation, options: RotationSettings
    ): Boolean {
        if (!options.applyServerSide) return true

        if (rotationDifference(target, curr) > options.angleResetDifference) return false

        // We use the last rotation saved 2 ticks ago because we have not updated the currentRotation yet.
        val diffs = angleDifferences(target, curr).abs
        val lastTickDiffs = angleDifferences(curr, lastRotations[1]).abs

        return diffs.x <= lastTickDiffs.x && diffs.y <= lastTickDiffs.y || !options.legitimize
    }

    /**
     * Any module that modifies the server packets without using the [currentRotation] should use on module disable.
     */
    fun syncSpecialModuleRotations() {
        serverRotation.let { (yaw, _) ->
            mc.thePlayer?.let {
                it.rotationYaw = yaw + angleDifference(it.rotationYaw, yaw)
                syncRotations()
            }
        }
    }

    /**
     * Checks if the rotation difference is not the same as the smallest GCD angle possible.
     */
    fun canUpdateRotation(current: Rotation, target: Rotation, multiplier: Int = 1): Boolean {
        if (current == target) return true

        val smallestAnglePossible = getFixedAngleDelta()

        return rotationDifference(target, current).withGCD() > smallestAnglePossible * multiplier
    }

    /**
     * Handle rotation update
     */
    val onRotationUpdate = handler<RotationUpdateEvent>(priority = -1) {
        activeSettings?.let {
            // Was the rotation update immediate? Allow updates the next tick.
            if (it.immediate) {
                it.immediate = false
                return@handler
            }
        }

        update()
    }

    /**
     * Handle strafing
     */
    val onStrafe = handler<StrafeEvent> { event ->
        val data = activeSettings ?: return@handler

        if (!data.strafe) {
            return@handler
        }

        currentRotation?.let {
            it.applyStrafeToPlayer(event, data.strict || forceStrict)
            event.cancelEvent()
        }
    }

    /**
     * Handle rotation-packet modification
     */
    val onPacket = handler<PacketEvent> { event ->
        val packet = event.packet

        if (packet !is C03PacketPlayer) {
            return@handler
        }

        if (!packet.rotating) {
            activeSettings?.resetSimulateShortStopData()
            return@handler
        }

        currentRotation?.let { packet.rotation = it }

        val diffs = angleDifferences(packet.rotation, serverRotation)

        if (Rotations.debugRotations && currentRotation != null) {
            chat("PREV YAW: ${diffs.x}, PREV PITCH: ${diffs.y}")
        }

        activeSettings?.updateSimulateShortStopData(diffs.x)
    }

    enum class BodyPoint(val rank: Int, val range: ClosedFloatingPointRange<Double>, val displayName: String) {
        HEAD(1, 0.75..0.9, "Head"), BODY(0, 0.5..0.75, "Body"), FEET(-1, 0.1..0.4, "Feet"), UNKNOWN(
            -2, 0.0..0.0, "Unknown"
        );

        companion object {
            fun fromString(point: String): BodyPoint {
                return entries.find { it.name.equals(point, ignoreCase = true) } ?: UNKNOWN
            }
        }
    }

    fun coerceBodyPoint(point: BodyPoint, minPoint: BodyPoint, maxPoint: BodyPoint): BodyPoint {
        return when {
            point.rank < minPoint.rank -> minPoint
            point.rank > maxPoint.rank -> maxPoint
            else -> point
        }
    }
}
