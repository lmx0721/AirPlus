/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.render

import net.airplus.event.AttackEvent
import net.airplus.event.EventState
import net.airplus.event.JumpEvent
import net.airplus.event.MoveEvent
import net.airplus.event.PacketEvent
import net.airplus.event.StrafeEvent
import net.airplus.event.UpdateEvent
import net.airplus.event.WorldEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.client.asResourceLocation
import net.airplus.utils.client.playSound
import net.minecraft.block.Block
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.init.Blocks
import net.minecraft.network.play.client.C02PacketUseEntity
import net.minecraft.network.play.server.S19PacketEntityStatus
import net.minecraft.util.EnumParticleTypes
import net.minecraft.util.MathHelper
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 处决（Execution）
 *
 * 击杀玩家/生物后播放第一人称处决动画：右手把一把钻石剑刺入目标胸口，
 * 停顿片刻后拔出回位。动画期间可选择把镜头对准目标胸口、锁定移动/攻击/视角，
 * 并冻结目标的死亡倒地 —— 处决结束后再由原版继续播放死亡动画。
 *
 * 击杀确认逻辑与 KillEffect / SessionInfo 一致：
 * - 生物：客户端 health <= 0 / isDead 判定；
 * - 玩家：服务端 S19 实体状态包 opCode 3（死亡）判定，因为其他玩家的血量不同步。
 */
object Execution : Module("Execution", Category.RENDER) {

    // 击杀归属判定窗口（同 SessionInfo / KillEffect）
    private const val TRACK_WINDOW_MS = 8_000L

    // 动画阶段（0..1 进度）
    private const val PHASE_WINDUP = 0.18f
    private const val PHASE_STAB = 0.36f
    private const val PHASE_HOLD = 0.62f
    private const val PHASE_PULL = 0.74f
    private const val PHASE_HURT = 0.76f
    private const val PHASE_SMOKE = 0.92f

    // 镜头对准的淡入/淡出区间
    private const val AIM_FADE_IN = 0.12f
    private const val AIM_FADE_OUT = 0.85f

    // 剑身前伸量（矩阵空间，剑已 0.4 缩放）：刺击阶段剑沿剑身方向（本地 +Y）从
    // 握持点探出，手臂不叠加该偏移 —— 修正剑柄陷进手臂的观感
    private const val BLADE_OUT_MAX = 0.62f

    // 手部姿态：x, y, z, pitch(绕X), roll(绕Z)
    // BASE 与原版无挥动时的手部变换一致，保证动画开始/结束不跳变
    private val BASE_POSE = floatArrayOf(0.56f, -0.52f, -0.72f, 0f, 0f)
    private val WINDUP_POSE = floatArrayOf(0.70f, -0.30f, -0.80f, 20f, 16f)
    private val STAB_POSE = floatArrayOf(0.10f, -0.32f, -0.60f, -74f, -10f)
    private val PULL_POSE = floatArrayOf(0.46f, -0.44f, -0.74f, -26f, 8f)

    // Settings
    private val targets by choices("Targets", arrayOf("Players", "All"), "Players")
    private val cameraAim by boolean("CameraAim", true)
    private val duration by float("Duration", 1.1f, 0.6f..2.0f)
    private val range by float("Range", 6f, 0f..32f)
    private val soundEnabled by boolean("Sound", true)

    // Kill tracking
    private data class TrackedTarget(val entity: EntityLivingBase, val attackedAt: Long)

    private val trackedTargets = LinkedHashMap<UUID, TrackedTarget>()

    // Animation state
    private var playing = false
    private var startNanos = 0L
    private var chestX = 0.0
    private var chestY = 0.0
    private var chestZ = 0.0
    private var stabEffectPlayed = false
    private var pullEffectPlayed = false

    // 被处决者：拔剑后播放受击闪红，随后化作烟雾消失
    private var victim: EntityLivingBase? = null
    private var hurtPlayed = false
    private var smokeStarted = false
    private var hiddenVictim: EntityLivingBase? = null

    // Camera state
    private var rotationSaved = false
    private var savedYaw = 0f
    private var savedPitch = 0f
    private var savedPrevYaw = 0f
    private var savedPrevPitch = 0f

    /** 供 mixin 查询：处决动画是否正在播放 */
    fun isPlaying() = playing

    /** 供 MixinRendererLivingEntity 查询：该实体是否为处决动画中的被处决者 */
    fun isCurrentVictim(entity: EntityLivingBase): Boolean = playing && victim === entity

    val onAttack = handler<AttackEvent> { event ->
        val player = mc.thePlayer ?: return@handler
        val target = event.targetEntity as? EntityLivingBase ?: return@handler
        if (target === player || !target.isEntityAlive) return@handler
        if (targets == "Players" && target !is EntityPlayer) return@handler
        if (range > 0f && player.getDistanceToEntity(target) > range) return@handler

        trackedTargets.remove(target.uniqueID)
        trackedTargets[target.uniqueID] = TrackedTarget(target, System.currentTimeMillis())
    }

    val onUpdate = handler<UpdateEvent> {
        val player = mc.thePlayer
        val world = mc.theWorld
        if (player == null || world == null) {
            reset()
            return@handler
        }

        val now = System.currentTimeMillis()

        // 生物击杀：客户端血量归零 / isDead
        val iterator = trackedTargets.values.iterator()
        while (iterator.hasNext()) {
            val tracked = iterator.next()
            val victim = tracked.entity
            if (victim.worldObj !== world || now - tracked.attackedAt > TRACK_WINDOW_MS) {
                iterator.remove()
                continue
            }
            if (victim.isDead || victim.health <= 0f) {
                iterator.remove()
                startExecution(victim)
            }
        }

        if (!playing) return@handler

        // 冻结死亡计时：阻止生物在处决动画中提前倒地/被移除，等烟雾阶段由模块自行隐藏
        victim?.let {
            if (it.worldObj === world) it.deathTime = 0
        }

        val t = progress()
        if (!stabEffectPlayed && t >= PHASE_STAB) {
            stabEffectPlayed = true
            onStab()
        }
        if (!pullEffectPlayed && t >= PHASE_HOLD) {
            pullEffectPlayed = true
            onPullOut()
        }
        // 拔剑完成 → 目标受击闪红
        if (!hurtPlayed && t >= PHASE_HURT) {
            hurtPlayed = true
            playVictimHurt()
        }
        // 受击后 → 化作烟雾消失
        if (!smokeStarted && t >= PHASE_SMOKE) {
            smokeStarted = true
            startVictimSmoke()
        } else if (smokeStarted) {
            spawnSmokeBurst(0.25)
        }
        if (t >= 1f) {
            stopExecution()
        }
    }

    val onWorld = handler<WorldEvent> { reset() }

    val onStrafe = handler<StrafeEvent> { event ->
        if (playing) event.cancelEvent()
    }

    val onMove = handler<MoveEvent> { event ->
        if (playing) event.zeroXZ()
    }

    val onJump = handler<JumpEvent> { event ->
        if (playing) event.cancelEvent()
    }

    val onPacket = handler<PacketEvent> { event ->
        val packet = event.packet
        when {
            // 玩家死亡包（其他玩家血量不同步，只能靠 S19 opCode 3 确认）
            event.eventType == EventState.RECEIVE && packet is S19PacketEntityStatus && packet.opCode == 3.toByte() -> {
                mc.addScheduledTask { handleDeathPacket(packet) }
            }
            // 动画期间锁定攻击（手动点击与 KillAura 等的攻击包统一拦下）
            event.eventType == EventState.SEND && playing && packet is C02PacketUseEntity &&
                packet.action == C02PacketUseEntity.Action.ATTACK -> event.cancelEvent()
        }
    }

    private fun handleDeathPacket(packet: S19PacketEntityStatus) {
        val world = mc.theWorld ?: return
        val victim = packet.getEntity(world) as? EntityLivingBase ?: return
        if (victim === mc.thePlayer) return
        if (targets == "Players" && victim !is EntityPlayer) return

        val tracked = trackedTargets.remove(victim.uniqueID) ?: return
        if (System.currentTimeMillis() - tracked.attackedAt > TRACK_WINDOW_MS) return
        startExecution(victim)
    }

    private fun startExecution(victim: EntityLivingBase) {
        if (playing) return

        val player = mc.thePlayer ?: return
        if (mc.theWorld == null || victim.worldObj !== mc.theWorld) return
        // 只在第一人称、无界面打开时播放
        if (mc.gameSettings.thirdPersonView != 0 || mc.currentScreen != null) return
        if (range > 0f && player.getDistanceToEntity(victim) > range) return

        playing = true
        startNanos = System.nanoTime()
        chestX = victim.posX
        chestY = victim.posY + victim.height * 0.72
        chestZ = victim.posZ
        stabEffectPlayed = false
        pullEffectPlayed = false
        hurtPlayed = false
        smokeStarted = false
        this.victim = victim
    }

    private fun stopExecution() {
        playing = false
        stabEffectPlayed = false
        pullEffectPlayed = false
        hurtPlayed = false
        smokeStarted = false
        // 手部动画结束；被处决者的隐藏状态由 hiddenVictim 继续维持
        victim = null
    }

    /** 动画进度 0..1（基于真实时间，与渲染帧率无关） */
    private fun progress(): Float {
        if (!playing) return 0f
        val durationNanos = duration.toDouble() * 1_000_000_000.0
        if (durationNanos <= 0.0) return 1f
        return ((System.nanoTime() - startNanos) / durationNanos).toFloat().coerceIn(0f, 1f)
    }

    /**
     * 供 MixinItemRenderer 调用：把当前矩阵变换到手部姿态。
     * 之后由 mixin 调用 renderItem 渲染钻石剑。
     */
    fun applyHandTransform() {
        val t = progress()
        val pose = poseAt(t)

        var trembleX = 0f
        var trembleY = 0f
        if (t >= PHASE_STAB && t < PHASE_HOLD) {
            trembleX = sin(t.toDouble() * 40.0).toFloat() * 0.004f
            trembleY = cos(t.toDouble() * 37.0).toFloat() * 0.003f
        }

        GlStateManager.translate(pose[0] + trembleX, pose[1] + trembleY, pose[2])
        GlStateManager.rotate(45f, 0f, 1f, 0f)
        GlStateManager.rotate(pose[3], 1f, 0f, 0f)
        GlStateManager.rotate(pose[4], 0f, 0f, 1f)
        GlStateManager.scale(0.4f, 0.4f, 0.4f)
    }

    /**
     * 供 MixinItemRenderer 调用：抵消剑的手部基准变换
     * （translate(0.56,-0.52,-0.72) + 45° yaw + 0.4 缩放）的逆矩阵，
     * 使 renderPlayerArm 自带的手臂基准与钻石剑共用同一套动画增量，两者始终贴合。
     */
    fun applyArmTransform() {
        GlStateManager.scale(2.5f, 2.5f, 2.5f)
        GlStateManager.rotate(-45f, 0f, 1f, 0f)
        GlStateManager.translate(-0.56f, 0.52f, 0.72f)
    }

    /**
     * 供 MixinItemRenderer 调用（仅施加给剑，手臂不叠加）：沿剑身方向（本地 +Y，
     * 此时矩阵已带姿态旋转与 0.4 缩放）把剑从握持点前伸 —— 刺入时剑明显探出
     * 手臂，而不是整把埋在手里。
     */
    fun applySwordOffset() {
        GlStateManager.translate(0f, BLADE_OUT_MAX * bladeOutAt(progress()), 0f)
    }

    /** 剑身前伸进度：蓄力阶段收拢，刺入阶段伸出到最大，拔剑回位时收回。 */
    private fun bladeOutAt(t: Float): Float = when {
        t < PHASE_WINDUP -> 0f
        t < PHASE_STAB -> easeOutCubic((t - PHASE_WINDUP) / (PHASE_STAB - PHASE_WINDUP))
        t < PHASE_PULL -> 1f
        else -> 1f - smoothStep((t - PHASE_PULL) / (1f - PHASE_PULL))
    }

    private fun poseAt(t: Float): FloatArray = when {
        t < PHASE_WINDUP -> blendPose(BASE_POSE, WINDUP_POSE, easeOutCubic(t / PHASE_WINDUP))
        t < PHASE_STAB -> blendPose(
            WINDUP_POSE, STAB_POSE,
            easeOutCubic((t - PHASE_WINDUP) / (PHASE_STAB - PHASE_WINDUP))
        )
        t < PHASE_HOLD -> STAB_POSE
        t < PHASE_PULL -> blendPose(
            STAB_POSE, PULL_POSE,
            smoothStep((t - PHASE_HOLD) / (PHASE_PULL - PHASE_HOLD))
        )
        else -> blendPose(PULL_POSE, BASE_POSE, easeOutCubic((t - PHASE_PULL) / (1f - PHASE_PULL)))
    }

    private fun blendPose(from: FloatArray, to: FloatArray, t: Float): FloatArray {
        val result = FloatArray(from.size)
        for (i in from.indices) {
            result[i] = from[i] + (to[i] - from[i]) * t
        }
        return result
    }

    /**
     * 供 MixinEntityRenderer 在 updateCameraAndRender HEAD 调用：
     * 把相机视角（玩家渲染旋转）平滑对准目标胸口。
     * 起始视角只在动画开始时保存一次：动画期间鼠标增量被下面的覆写丢弃，
     * 结束时恢复的始终是处决开始前的原始视角（期间移动鼠标不影响回位位置）。
     */
    fun applyCameraAim(partialTicks: Float) {
        if (!playing || !cameraAim) return
        val player = mc.thePlayer ?: return
        if (mc.gameSettings.thirdPersonView != 0) return

        if (!rotationSaved) {
            savedYaw = player.rotationYaw
            savedPitch = player.rotationPitch
            savedPrevYaw = player.prevRotationYaw
            savedPrevPitch = player.prevRotationPitch
            rotationSaved = true
        }

        val eye = player.getPositionEyes(partialTicks)
        val dx = chestX - eye.xCoord
        val dy = chestY - eye.yCoord
        val dz = chestZ - eye.zCoord
        val horizontal = sqrt(dx * dx + dz * dz)
        if (horizontal < 1.0E-4) return

        val targetYaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
        val targetPitch = MathHelper.clamp_float(
            (-Math.toDegrees(atan2(dy, horizontal))).toFloat(), -89f, 89f
        )

        val t = progress()
        val blend = when {
            t <= AIM_FADE_IN -> smoothStep(t / AIM_FADE_IN)
            t >= AIM_FADE_OUT -> smoothStep((1f - t) / (1f - AIM_FADE_OUT))
            else -> 1f
        }

        val yaw = savedYaw + MathHelper.wrapAngleTo180_float(targetYaw - savedYaw) * blend
        val pitch = savedPitch + (targetPitch - savedPitch) * blend

        player.rotationYaw = yaw
        player.prevRotationYaw = yaw
        player.rotationPitch = pitch
        player.prevRotationPitch = pitch
    }

    /** 供 MixinEntityRenderer 在 updateCameraAndRender TAIL 调用：恢复真实视角 */
    fun restoreCamera() {
        if (!rotationSaved) return
        rotationSaved = false

        val player = mc.thePlayer ?: return
        player.rotationYaw = savedYaw
        player.prevRotationYaw = savedPrevYaw
        player.rotationPitch = savedPitch
        player.prevRotationPitch = savedPrevPitch
    }

    private fun onStab() {
        // 胸口血粒子
        val baseX = chestX
        val baseY = chestY
        val baseZ = chestZ
        repeat(14) {
            mc.effectRenderer.spawnEffectParticle(
                EnumParticleTypes.BLOCK_CRACK.particleID,
                baseX + (Math.random() - 0.5) * 0.35,
                baseY + (Math.random() - 0.5) * 0.35,
                baseZ + (Math.random() - 0.5) * 0.35,
                (Math.random() - 0.5) * 0.35,
                (Math.random() - 0.5) * 0.35,
                (Math.random() - 0.5) * 0.35,
                Block.getStateId(Blocks.redstone_block.defaultState)
            )
        }

        if (soundEnabled) {
            mc.playSound("random.successful_hit".asResourceLocation(), 0.6f)
        }
    }

    private fun onPullOut() {
        if (soundEnabled) {
            mc.playSound("random.bowhit".asResourceLocation(), 1.35f)
        }
    }

    /** 剑拔出后的受击反馈：原版红闪由 hurtTime 驱动，玩家/生物分别播放对应受击音效 */
    private fun playVictimHurt() {
        val target = victim ?: return
        if (target.worldObj !== mc.theWorld) return

        target.hurtTime = 10

        if (soundEnabled) {
            val sound = if (target is EntityPlayer) "game.player.hurt" else "game.neutral.hurt"
            mc.playSound(sound.asResourceLocation(), 1f)
        }
    }

    /** 受击结束：目标化作死亡烟雾，并从渲染中隐藏 */
    private fun startVictimSmoke() {
        val target = victim ?: return
        if (target.worldObj !== mc.theWorld) return

        hiddenVictim = target
        spawnSmokeBurst(1.0)

        if (soundEnabled) {
            mc.playSound("random.fizz".asResourceLocation(), 0.6f)
        }
    }

    private fun spawnSmokeBurst(strength: Double) {
        val target = hiddenVictim ?: return
        val centerY = target.posY + target.height * 0.5
        val count = (22 * strength).toInt().coerceAtLeast(1)

        repeat(count) {
            mc.effectRenderer.spawnEffectParticle(
                EnumParticleTypes.SMOKE_LARGE.particleID,
                target.posX + (Math.random() - 0.5) * target.width * 1.6,
                centerY + (Math.random() - 0.5) * target.height,
                target.posZ + (Math.random() - 0.5) * target.width * 1.6,
                (Math.random() - 0.5) * 0.08 * strength,
                0.06 * strength,
                (Math.random() - 0.5) * 0.08 * strength
            )
        }
    }

    /** 供 MixinRenderManager 查询：该实体是否应被隐藏（烟雾消散后到实体消失前） */
    fun shouldHideRender(entity: EntityLivingBase): Boolean {
        val hidden = hiddenVictim ?: return false
        if (hidden !== entity) return false

        val world = mc.theWorld
        if (world == null || hidden.worldObj !== world || hidden.isEntityAlive ||
            !world.loadedEntityList.contains(hidden)
        ) {
            hiddenVictim = null
            return false
        }
        return true
    }

    private fun reset() {
        playing = false
        startNanos = 0L
        stabEffectPlayed = false
        pullEffectPlayed = false
        hurtPlayed = false
        smokeStarted = false
        victim = null
        hiddenVictim = null
        trackedTargets.clear()
        restoreCamera()
    }

    override fun onEnable() {
        reset()
    }

    override fun onDisable() {
        reset()
    }

    private fun easeOutCubic(t: Float): Float {
        val x = 1f - t.coerceIn(0f, 1f)
        return 1f - x * x * x
    }

    private fun smoothStep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}
