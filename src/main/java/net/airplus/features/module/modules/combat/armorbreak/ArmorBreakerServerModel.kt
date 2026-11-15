/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 * skid edmoonlight
 * Ported from Moonlight EdMoonlight ArmorBreakerServerModel (NewArmorBreaker dependency).
 *
 * 服务端状态镜像：跟踪已发出的 C03/C02/C08/C09 包，推导服务端视角的
 * 位移 / 下落距离 / 暴击状态 / 手持武器伤害 / 受击击退间隔，用于暴击预测与伤害规划。
 *
 * 移植说明：moonlight 里 onWire 只在 AutoGapple blink 队列期间被喂包；
 * AirPlus 没有对应机制，改为由 EdMoonlightMode 的 PacketEvent(SEND) 观察者
 * 对所有发出包喂数据（语义一致且覆盖更完整）。
 */
package net.airplus.features.module.modules.combat.armorbreak

import net.airplus.features.module.modules.movement.NoJumpDelay
import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.client.PacketUtils.sendPacket
import net.minecraft.client.entity.EntityPlayerSP
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.EnumCreatureAttribute
import net.minecraft.item.ItemStack
import net.minecraft.network.Packet
import net.minecraft.network.play.client.C02PacketUseEntity
import net.minecraft.network.play.client.C03PacketPlayer
import net.minecraft.network.play.client.C03PacketPlayer.C04PacketPlayerPosition
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement
import net.minecraft.network.play.client.C09PacketHeldItemChange
import net.minecraft.potion.Potion
import net.minecraft.potion.PotionEffect
import net.minecraft.util.AxisAlignedBB
import java.util.concurrent.ConcurrentLinkedQueue

/** 用于预测时的 Y 轴碰撞裁剪回调。 */
internal typealias ClipY = (y: Double, motionY: Double) -> Double

internal class SrvCtx {
    var hist: DoubleArray = DoubleArray(0)
    var pending: Boolean = false
    var lastY: Double = 0.0
    var ground: Boolean = false
    var back: Int = 2
}

internal object ArmorBreakerServerModel : MinecraftInstance {

    @Volatile
    private var wireItemAttr = Float.NaN

    @Volatile
    private var serverOnGround = true

    @Volatile
    private var serverFallDistance = 0.0f

    @Volatile
    private var lastY = Double.NaN

    @Volatile
    private var lastX = 0.0

    @Volatile
    private var lastZ = 0.0

    @Volatile
    private var wireSlot = -1

    val wireHits = ConcurrentLinkedQueue<FloatArray>()

    @Volatile
    var nextOwn = false

    @Volatile
    var wireTick = 0

    @Volatile
    var blockOwed = false

    @Volatile
    private var srvMotionY = -0.0784

    @Volatile
    private var srvGroundTop = Double.NaN

    @Volatile
    private var srvJumpV = 0.42

    private val srvHist = DoubleArray(8)
    private var srvHistN = 0

    @Volatile
    var kbBack = 2

    private var landedTick = -1000
    private var lastOnGround = true
    private var bhop = false
    private var kbTick = -1000
    private var kbInterval = 0
    private var kbVy = 0.0

    @Volatile
    private var kbHurtNanos = 0L
    private var kbPending = false
    private var kbPreTick = -1000

    var dbgKb = 0
    var dbgKbOnTime = 0

    internal fun wireHeld(player: EntityPlayerSP): ItemStack? {
        val slot = wireSlot
        return if (slot >= 0 && slot < 9) player.inventory.getStackInSlot(slot) else player.heldItem
    }

    private fun onWireAttack(packet: C02PacketUseEntity) {
        val player = mc.thePlayer ?: return
        if (packet.action == C02PacketUseEntity.Action.ATTACK && wireHits.size <= 64) {
            val target = if (player.worldObj == null) null
            else packet.getEntityFromWorld(player.worldObj) as? EntityLivingBase
            if (target != null) {
                val attr = serverAttr(player)
                val crit = predictCrit(player)
                val amount = predictAmount(attr, crit, wireHeld(player), target)
                wireHits.add(floatArrayOf(target.entityId.toFloat(), amount, if (nextOwn) 1.0f else 0.0f, wireTick.toFloat()))
                nextOwn = false
                ArmorBreakerTrace.onWireAttack(
                    packet,
                    target.entityId,
                    attr,
                    crit,
                    predictAmount(0.0f, false, wireHeld(player), target),
                    amount
                )
            }
        }
    }

    /** 喂入一个已发出的客户端包（C03/C02/C08/C09），更新服务端镜像。 */
    fun onWire(packet: Packet<*>) {
        if (ArmorBreakerTrace.enabled && packet !is C02PacketUseEntity) {
            val player = mc.thePlayer ?: return
            ArmorBreakerTrace.onWire(packet, wireItemAttr, ArmorBreakerLadder.itemAttr(wireHeld(player)))
        }

        when (packet) {
            is C02PacketUseEntity -> onWireAttack(packet)

            is C08PacketPlayerBlockPlacement -> {
                if (packet.placedBlockDirection == 255) {
                    blockOwed = false
                }
            }

            is C09PacketHeldItemChange -> wireSlot = packet.slotId

            is C03PacketPlayer -> {
                val player = mc.thePlayer ?: return
                wireItemAttr = ArmorBreakerLadder.itemAttr(wireHeld(player))
                val onGround = packet.isOnGround
                val lastYValue = lastY
                val baseY = if (lastYValue.isNaN()) (if (packet.isMoving) packet.positionY else player.posY) else lastYValue
                val deltaY = if (packet.isMoving && !lastYValue.isNaN()) packet.positionY - lastYValue else 0.0
                val stepped = srvStep(srvMotionY, baseY, srvGroundTop, serverOnGround, onGround, deltaY, srvJumpV)
                srvMotionY = stepped
                synchronized(srvHist) {
                    srvHist[srvHistN % srvHist.size] = stepped
                    srvHistN++
                }

                if (packet.isMoving) {
                    val deltaYMoving = if (lastY.isNaN()) 0.0 else packet.positionY - lastY
                    lastY = packet.positionY
                    lastX = packet.positionX
                    lastZ = packet.positionZ
                    if (onGround) {
                        serverFallDistance = 0.0f
                    } else if (deltaYMoving < 0.0 && deltaYMoving > -10.0) {
                        serverFallDistance = (serverFallDistance - deltaYMoving).toFloat()
                    }
                } else if (onGround) {
                    serverFallDistance = 0.0f
                }

                serverOnGround = onGround
            }
        }
    }

    fun wireAttr(): Float = wireItemAttr

    internal fun srvStep(
        motionY: Double,
        y: Double,
        groundTop: Double,
        wasOnGround: Boolean,
        onGround: Boolean,
        deltaY: Double,
        jumpV: Double,
    ): Double {
        var motion = motionY
        if (Math.abs(motion) < 0.005) {
            motion = 0.0
        }

        if (motion < 0.0 && !groundTop.isNaN() && y + motion <= groundTop + 1.0E-7) {
            motion = 0.0
        }

        motion = (motion - 0.08) * 0.98
        if (wasOnGround && !onGround && deltaY > 0.0) {
            motion = jumpV
        }

        return motion
    }

    internal fun kbVyFrom(motionY: Double): Double = Math.min(0.4, motionY / 2.0 + 0.4)

    internal fun wireFallDistance(): Float = serverFallDistance

    fun lastGround(): Boolean = serverOnGround

    fun refreshPacket(): C03PacketPlayer {
        return if (lastY.isNaN()) C03PacketPlayer(serverOnGround)
        else C04PacketPlayerPosition(lastX, lastY, lastZ, serverOnGround)
    }

    /** 按服务端镜像状态伪造/修正暴击状态包（跳一下置 fallDistance，或落地清零）。 */
    fun setCritState(crit: Boolean): Boolean {
        if (lastY.isNaN()) {
            return false
        }

        val x = lastX
        val y = lastY
        val z = lastZ
        if (crit) {
            sendPacket(C04PacketPlayerPosition(x, y + 0.0625, z, false), triggerEvent = false)
            sendPacket(C04PacketPlayerPosition(x, y, z, false), triggerEvent = false)
        } else {
            sendPacket(C04PacketPlayerPosition(x, y, z, true), triggerEvent = false)
        }

        return true
    }

    fun predictCritAfter(player: EntityPlayerSP, packet: C03PacketPlayer): Boolean {
        val onGround = packet.isOnGround
        var fallDistance = serverFallDistance
        if (onGround) {
            fallDistance = 0.0f
        } else if (packet.isMoving && !lastY.isNaN()) {
            val deltaY = packet.positionY - lastY
            if (deltaY < 0.0 && deltaY > -10.0) {
                fallDistance = (fallDistance - deltaY).toFloat()
            }
        }

        return fallDistance > 0.0f && !onGround && !player.isOnLadder && !player.isInWater
            && !player.isPotionActive(Potion.blindness) && player.ridingEntity == null
    }

    internal fun wireCritState(): Boolean = serverFallDistance > 0.0f && !serverOnGround

    fun reset() {
        wireItemAttr = Float.NaN
        wireSlot = -1
        serverOnGround = true
        serverFallDistance = 0.0f
        lastY = Double.NaN
        wireHits.clear()
    }

    fun predictCrit(player: EntityPlayerSP): Boolean {
        return serverFallDistance > 0.0f
            && !serverOnGround
            && !player.isOnLadder
            && !player.isInWater
            && !player.isPotionActive(Potion.blindness)
            && player.ridingEntity == null
    }

    internal fun trackJump(player: EntityPlayerSP) {
        val onGround = player.onGround
        if (onGround && !lastOnGround) {
            landedTick = player.ticksExisted
        } else if (!onGround && lastOnGround) {
            bhop = player.ticksExisted - landedTick <= 2
        } else if (onGround && player.ticksExisted - landedTick > 2) {
            bhop = false
        }

        lastOnGround = onGround
        kbPreTick = player.ticksExisted
        val boundingBox = player.entityBoundingBox
        var groundTop = Double.NaN

        for (box in player.worldObj.getCollidingBoundingBoxes(player, boundingBox.addCoord(0.0, -4.0, 0.0))) {
            if (box.maxY <= boundingBox.minY + 1.0E-7 && (groundTop.isNaN() || box.maxY > groundTop)) {
                groundTop = box.maxY
            }
        }

        srvGroundTop = groundTop
        val jumpEffect = player.getActivePotionEffect(Potion.jump)
        srvJumpV = 0.42 + (if (jumpEffect == null) 0.0 else (jumpEffect.amplifier + 1) * 0.1)
    }

    /** 收到自身的受击状态包（S19 opcode 2）时记录时间戳。 */
    fun onSelfHurtPacket() {
        kbHurtNanos = System.nanoTime()
    }

    private fun hurtSignal(): Boolean {
        val nanos = kbHurtNanos
        return nanos != 0L && System.nanoTime() - nanos < 150000000L
    }

    /** 收到自身 Velocity 包时触发（moonlight 用 VelocityEvent，AirPlus 用 S12 包事件替代）。 */
    fun onSelfVelocity() {
        if (hurtSignal()) {
            kbHurtNanos = 0L
            kbPending = true
        }
    }

    internal fun trackKnockback(player: EntityPlayerSP) {
        if (kbPending) {
            kbPending = false
            val tick = player.ticksExisted
            dbgKb++
            if (kbInterval > 0 && kbTick + 10 == tick) {
                dbgKbOnTime++
            }

            val gap = tick - kbTick
            kbInterval = if (gap >= 10 && gap <= 16) gap else 0
            kbTick = tick
            kbVy = player.motionY
        }
    }

    internal fun nextKnockback(tick: Int): Int {
        if (kbInterval == 0) {
            return -1
        }

        var next = kbTick + 10
        if (next <= tick) {
            if (tick - next >= 2) {
                return -1
            }

            next = tick + 1
        }

        return next
    }

    internal fun resetKnockback() {
        kbTick = -1000
        kbInterval = 0
        kbPending = false
        kbHurtNanos = 0L
    }

    internal fun forecastCrit(
        player: EntityPlayerSP,
        pending: Boolean,
        critNow: Boolean,
        plan: BooleanArray,
        horizon: Int,
        knockbackForecast: Boolean,
        stage: Int,
    ): Int {
        plan[0] = critNow
        if (player.isOnLadder || player.isInWater || player.isPotionActive(Potion.blindness) || player.ridingEntity != null || player.capabilities.isFlying) {
            for (i in 1 until horizon) {
                plan[i] = false
            }
            return horizon
        }

        val boundingBox = player.entityBoundingBox
        val boxes = player.worldObj.getCollidingBoundingBoxes(player, boundingBox.addCoord(0.0, 3.0, 0.0).addCoord(0.0, -4.0, 0.0))
        val posY = player.posY
        val clip: ClipY = { y, motionY ->
            var result = motionY
            val movedBox = boundingBox.offset(0.0, y - posY, 0.0)
            for (i in boxes.indices) {
                result = boxes[i].calculateYOffset(movedBox, result)
            }
            result
        }

        val jumpDown = player.movementInput != null && player.movementInput.jump
        val noJumpDelay = NoJumpDelay.state
        val jumpEffect = player.getActivePotionEffect(Potion.jump)
        val jumpV = 0.42 + (if (jumpEffect == null) 0.0 else (jumpEffect.amplifier + 1) * 0.1)
        val tick = player.ticksExisted
        val kbSameTick = tick == kbPreTick && !pending && stage == 0
        if (!pending && stage == 0 && !kbSameTick) {
            trackKnockback(player)
        }

        val nextKb = if (knockbackForecast) nextKnockback(tick) else -1
        var kbIn = if (nextKb < 0) -1 else nextKb - tick + 1 - stage
        if (knockbackForecast && kbSameTick && (kbPending || hurtSignal())) {
            kbIn = 1
        }

        val history: DoubleArray
        synchronized(srvHist) {
            val count = Math.min(srvHistN, srvHist.size)
            history = DoubleArray(count)
            for (i in 0 until count) {
                history[i] = srvHist[(srvHistN - count + i) % srvHist.size]
            }
        }

        val ctx = SrvCtx().apply {
            hist = history
            this.pending = pending || stage == 1
            lastY = if (lastY.isNaN()) posY else this@ArmorBreakerServerModel.lastY
            ground = serverOnGround
            back = kbBack
        }

        forecast(
            posY,
            player.motionY,
            if (player.onGround) 0.0f else player.fallDistance,
            player.onGround,
            player.jumpTicks,
            jumpDown,
            noJumpDelay,
            bhop,
            jumpV,
            pending,
            clip,
            plan,
            horizon,
            kbIn,
            if (kbIn > 0) 10 else 0,
            kbVy,
            ctx
        )
        return horizon
    }

    internal fun forecast(
        y: Double,
        motionY: Double,
        fallDistance: Float,
        onGround: Boolean,
        jumpTicks: Int,
        jumpDown: Boolean,
        noJumpDelay: Boolean,
        bhop: Boolean,
        jumpV: Double,
        pending: Boolean,
        clip: ClipY,
        plan: BooleanArray,
        horizon: Int,
        kbIn: Int,
        kbEvery: Int,
        kbVy: Double,
        ctx: SrvCtx?,
    ) {
        var motion = motionY
        var mirrorHist: DoubleArray? = null
        var histLen = 0
        var lastMotion = -0.0784
        var curY = y
        var curGround = onGround
        var jumpCooldown = if (noJumpDelay) 0 else jumpTicks
        var fall = fallDistance

        if (ctx != null) {
            val hist = DoubleArray(ctx.hist.size + horizon + 2)
            for (value in ctx.hist) {
                hist[histLen++] = value
            }

            lastMotion = if (histLen > 0) hist[histLen - 1] else -0.0784
            curY = ctx.lastY
            curGround = ctx.ground
            if (ctx.pending) {
                lastMotion = srvStepClip(lastMotion, curY, clip, curGround, onGround, y - curY, jumpV)
                curGround = onGround
                curY = y
                hist[histLen++] = lastMotion
            }
            mirrorHist = hist
        }

        for (i in 1 until horizon) {
            if (i == 1 && pending) {
                plan[i] = !onGround && fallDistance > 0.0f
            } else {
                if (kbIn > 0 && i >= kbIn && (if (kbEvery <= 0) i == kbIn else (i - kbIn) % kbEvery == 0)) {
                    motion = if (mirrorHist != null && histLen > 0) {
                        kbVyFrom(mirrorHist[Math.max(0, histLen - 1 - ctx!!.back)])
                    } else {
                        kbVy
                    }
                }

                if (jumpCooldown > 0) {
                    jumpCooldown--
                }

                if (Math.abs(motion) < 0.005) {
                    motion = 0.0
                }

                if (jumpDown) {
                    if (curGround && jumpCooldown == 0) {
                        motion = jumpV
                        jumpCooldown = if (noJumpDelay) 0 else 10
                    }
                } else {
                    jumpCooldown = 0
                    if (curGround && bhop) {
                        motion = jumpV
                    }
                }

                val clipped = clip(curY, motion)
                val hitGround = clipped != motion
                val landed = hitGround && motion < 0.0
                val prevY = curY
                curGround = landed
                curY += clipped
                if (landed) {
                    fall = 0.0f
                } else if (clipped < 0.0) {
                    fall = (fall - clipped).toFloat()
                }

                if (hitGround) {
                    motion = 0.0
                }

                motion = (motion - 0.08) * 0.98
                plan[i] = !landed && fall > 0.0f
                if (mirrorHist != null) {
                    lastMotion = srvStepClip(lastMotion, prevY, clip, curGround, landed, clipped, jumpV)
                    mirrorHist[histLen++] = lastMotion
                }
            }
        }
    }

    private fun srvStepClip(
        motionY: Double,
        y: Double,
        clip: ClipY,
        wasOnGround: Boolean,
        onGround: Boolean,
        deltaY: Double,
        jumpV: Double,
    ): Double {
        var motion = motionY
        if (Math.abs(motion) < 0.005) {
            motion = 0.0
        }

        if (motion < 0.0 && clip(y, motion) != motion) {
            motion = 0.0
        }

        motion = (motion - 0.08) * 0.98
        if (wasOnGround && !onGround && deltaY > 0.0) {
            motion = jumpV
        }

        return motion
    }

    fun predictNextCrit(player: EntityPlayerSP, afterMove: Boolean): Boolean {
        if (player.isOnLadder || player.isInWater || player.isPotionActive(Potion.blindness) || player.ridingEntity != null) {
            return false
        } else if (!afterMove) {
            val motionY = player.motionY
            if (motionY < 0.0 && player.worldObj.getCollidingBoundingBoxes(player, player.entityBoundingBox.addCoord(0.0, motionY, 0.0)).isNotEmpty()) {
                return false
            }

            val fallDistance = if (player.onGround) 0.0f else player.fallDistance
            return (if (motionY < 0.0) fallDistance - motionY else fallDistance.toDouble()) > 0.0
        } else {
            return player.fallDistance > 0.0f && !player.onGround
        }
    }

    /** 服务端视角的当前武器总伤害（含药水修正）。 */
    fun serverAttr(player: EntityPlayerSP): Float {
        val wire = wireItemAttr
        val attr = if (wire.isNaN()) ArmorBreakerLadder.itemAttr(player.heldItem) else wire
        return withPotions(player, attr)
    }

    internal fun withPotions(player: EntityPlayerSP, attr: Float): Float {
        val weakness = player.getActivePotionEffect(Potion.weakness)
        val strength = player.getActivePotionEffect(Potion.damageBoost)
        return withPotions(attr, if (weakness == null) -1 else weakness.amplifier, if (strength == null) -1 else strength.amplifier)
    }

    internal fun withPotions(attr: Float, weaknessAmplifier: Int, strengthAmplifier: Int): Float {
        var value = attr.toDouble()
        if (weaknessAmplifier >= 0) {
            value += -0.5 * (weaknessAmplifier + 1)
        }

        if (strengthAmplifier >= 0) {
            value *= 1.0 + 1.3 * (strengthAmplifier + 1)
        }

        return Math.max(0.0, Math.min(2048.0, value)).toFloat()
    }

    /** 预测一次攻击对目标造成的伤害（含生物属性附魔加成与暴击）。 */
    fun predictAmount(attr: Float, crit: Boolean, stack: ItemStack?, target: EntityLivingBase?): Float {
        val bonus = EnchantmentHelper.getModifierForCreature(
            stack,
            if (target == null) EnumCreatureAttribute.UNDEFINED else target.creatureAttribute
        )
        return (if (crit && attr > 0.0f) attr * 1.5f else attr) + bonus
    }
}
