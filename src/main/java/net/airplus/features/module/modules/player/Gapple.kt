/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 * skid edmoonlight
 */
package net.airplus.features.module.modules.player

import net.airplus.event.*
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.modules.combat.ArmorBreak
import net.airplus.features.module.modules.combat.KillAura
import net.airplus.features.module.modules.player.gapple.GrimServerModel
import net.airplus.features.module.modules.player.gapple.GrimState
import net.airplus.features.module.modules.world.scaffolds.Scaffold
import net.airplus.ui.font.Fonts
import net.airplus.utils.GlowUtils
import net.airplus.utils.client.PacketUtils.sendPacket
import net.airplus.utils.client.chat
import net.airplus.utils.render.RenderUtils.drawRoundedGradientRectCorner
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.airplus.utils.rotation.RotationUtils
import net.airplus.utils.timing.MSTimer
import net.airplus.viaversion.vialoadingbase.ViaLoadingBase
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.item.ItemAppleGold
import net.minecraft.item.ItemStack
import net.minecraft.item.ItemSword
import net.minecraft.network.Packet
import net.minecraft.network.handshake.client.C00Handshake
import net.minecraft.network.login.client.C00PacketLoginStart
import net.minecraft.network.login.client.C01PacketEncryptionResponse
import net.minecraft.network.play.client.C01PacketChatMessage
import net.minecraft.network.play.client.C02PacketUseEntity
import net.minecraft.network.play.client.C03PacketPlayer
import net.minecraft.network.play.client.C07PacketPlayerDigging
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement
import net.minecraft.network.play.client.C09PacketHeldItemChange
import net.minecraft.network.play.client.C0APacketAnimation
import net.minecraft.network.play.client.C0DPacketCloseWindow
import net.minecraft.network.play.client.C0EPacketClickWindow
import net.minecraft.network.play.client.C16PacketClientStatus
import net.minecraft.network.play.client.C03PacketPlayer.C05PacketPlayerLook
import net.minecraft.network.play.server.S08PacketPlayerPosLook
import net.minecraft.network.play.server.S12PacketEntityVelocity
import net.minecraft.network.play.server.S27PacketExplosion
import net.minecraft.network.status.client.C00PacketServerQuery
import net.minecraft.network.status.client.C01PacketPing
import net.minecraft.util.AxisAlignedBB
import net.minecraft.util.MovingObjectPosition
import net.minecraft.util.Vec3
import java.awt.Color
import java.util.concurrent.LinkedBlockingQueue

object Gapple : Module("Gapple", Category.PLAYER) {
    // Legacy = 原 Stuck 模式（定身 + 拦截 C03 计数 + 结束后补发）原样保留；
    // EdMoonlight = moonlight AutoGapple 完整移植（C03+全部出站包队列缓存、32 包吃苹果等）
    private val mode by choices("Mode", arrayOf("Legacy", "EdMoonlight"), "Legacy")

    // ==================== Legacy（原 Stuck）设置与状态 ====================
    private val c by int("C03PacketPlayer", 32, 32..40)
    private val progressBar2 by boolean("ProgressBar", true)
    private val chatMessage by boolean("ChatMessage", true)

    private var x = 0.0
    private var y = 0.0
    private var z = 0.0
    private var cancelMove = false
    private var r = false
    var ticks = 0
        private set
    private var pauseTicks = 0
    private var legacyYaw = 0f
    private var legacyPitch = 0f

    // ==================== EdMoonlight（moonlight AutoGapple）设置 ====================
    private val delay by float("Delay", 50f, 0f..1000f) { mode == "EdMoonlight" }
    private val health by float("Health", 15f, 0f..40f) { mode == "EdMoonlight" }
    private val smartMode by boolean("Smart Mode", false) { mode == "EdMoonlight" }
    private val count by int("Use Count", 5, 3..10) { mode == "EdMoonlight" && !smartMode }
    private val noMove by boolean("Stop Move When Eating", false) { mode == "EdMoonlight" }
    val alwaysAttack by boolean("Always Attack", false) { mode == "EdMoonlight" }
    private val autoClose by boolean("Close When No Golden Apple", true) { mode == "EdMoonlight" }
    private val lagValue by boolean("Lag When In Air", false) { mode == "EdMoonlight" }
    private val invalidC03 by boolean("Send Invalid C03", false) { mode == "EdMoonlight" }
    val attrRefresh by boolean("ArmorBreaker Attr Refresh", false) { mode == "EdMoonlight" && alwaysAttack }
    private val attrRefreshCount by int("Attr Refresh Per Cycle", 2, 1..4) { mode == "EdMoonlight" && alwaysAttack && attrRefresh }
    private val critFreeze by boolean("Crit Freeze", true) { mode == "EdMoonlight" && alwaysAttack }
    private val critFreezeWait by int("Crit Freeze Max Wait", 10, 1..20) { mode == "EdMoonlight" && alwaysAttack && critFreeze }
    private val aimFreeze by boolean("Aim Freeze", true) { mode == "EdMoonlight" && alwaysAttack }
    private val aimFreezeWait by int("Aim Freeze Max Wait", 4, 1..10) { mode == "EdMoonlight" && alwaysAttack && aimFreeze }
    // moonlight 中该开关联动 Scaffold.isBridging()；
    // AirPlus 对应为 Scaffold.isBridging()（最近放过方块即视为搭路中）
    private val pauseOnScaffold by boolean("Pause While Bridging", true) { mode == "EdMoonlight" }
    private val debug by boolean("Debug", true) { mode == "EdMoonlight" }

    // ==================== EdMoonlight 状态 ====================
    private val timer = MSTimer()
    private var movingPackets = 0
    private var edSlot = -1
    private val packets = LinkedBlockingQueue<Packet<*>>()
    private var needSkip = false
    private var velocityLifted = false
    private var skipTicks = 0
    private var setbackPending = false
    private var setbackCooldown = 0
    private var attackedCheck = false
    private var endCheck = false
    private var critWaitTicks = 0
    private var lastCritWait = 0
    private var aimWaitTicks = 0
    private var lastAimWait = 0
    private var eatFinishTicks = 0
    private var eatStartTime = 0L
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var reachBuffer = 0.0
    private var reachBufferTick = -1

    // 拦截时已同步过 GrimServerModel 的 C03（对齐 moonlight onQueued 的 queued 去重，避免回放时重复计数）
    private val wireMarked = HashSet<Packet<*>>()

    private const val SETBACK_COOLDOWN_TICKS = 10
    private const val CRIT_FREEZE_URGENT_HP = 8.0f

    // moonlight 的 public static queueNextClick 由其破甲点击器设置以放行一次 C0E 点击包；
    // AirPlus 由 ArmorBreaker EdMoonlight 的 swapClick 通过 queueNextClick() 设置
    private var queueNextClick = false

    var isEating = false
        private set

    /** 单次放行：放行一条 C0E 后立即复位标记。 */
    private fun consumeNextClick(): Boolean {
        val allowed = queueNextClick
        queueNextClick = false
        return allowed
    }

    private val edMoonlight get() = mode == "EdMoonlight"

    // 模式切换时全量重置，避免两种模式的状态互相污染
    private var lastMode: String? = null
    private fun ensureModeFresh() {
        if (lastMode == mode) return
        lastMode = mode

        // Legacy（stopStuck 会恢复定身前的动量，不能只清标志位）
        ticks = 0
        pauseTicks = 0
        cancelMove = false
        stopStuck()

        // EdMoonlight
        packets.clear()
        wireMarked.clear()
        movingPackets = 0
        edSlot = -1
        needSkip = false
        velocityLifted = false
        skipTicks = 0
        setbackPending = false
        setbackCooldown = 0
        attackedCheck = false
        endCheck = false
        eatFinishTicks = 0
        queueNextClick = false
        reachBuffer = 0.0
        reachBufferTick = -1
        GrimServerModel.reset()
    }

    fun getEatingProgress(): Float = when (mode) {
        "EdMoonlight" -> if (isEating) (movingPackets / 32f).coerceIn(0f, 1f) else 0f
        else -> if (isEating && c > 0) (ticks.toFloat() / c.toFloat()).coerceIn(0f, 1f) else 0f
    }

    // ==================== EdMoonlight 公共 API（对齐 moonlight AutoGapple） ====================

    /** 吃苹果期间冻结 ArmorBreaker 属性计算（对齐 moonlight freezesAttr）。 */
    fun freezesAttr(): Boolean = state && isEating && alwaysAttack

    /** 对齐 moonlight shouldQueueWeaponSwap：供换武器模块判断是否该排队。 */
    fun shouldQueueWeaponSwap(): Boolean = state && isEating && !alwaysAttack

    /**
     * 放行下一条 C0E 点击包（对齐 moonlight AutoGapple.queueNextClick）。
     * ArmorBreaker EdMoonlight 换武器 windowClick 前调用，避免点击包被吃苹果队列缓存延迟。
     */
    fun queueNextClick() {
        queueNextClick = true
    }

    fun attrRefreshMax(): Int =
        if (freezesAttr() && attrRefresh) attrRefreshCount else 0

    fun canRefreshAttr(): Boolean =
        attrRefreshMax() > 0 && movingPackets > 0 && movingPackets < 30 && !packets.isEmpty()

    /** 从缓存队列提前释放一条 C03（供破甲 Attr Refresh 使用）。 */
    fun refreshAttr(): Boolean {
        if (!canRefreshAttr()) return false

        while (!packets.isEmpty()) {
            val packet = packets.peek()
            if (packet is C01PacketChatMessage) return false

            packets.poll()
            sendQueuedPacket(packet)
            if (packet is C03PacketPlayer) {
                movingPackets--
                return true
            }
        }

        return false
    }

    /** 对齐 moonlight prepareDirectAttack：判断当前是否能直接攻击目标（服务端视角命中判定）。 */
    fun prepareDirectAttack(entity: Entity?): Boolean {
        val allowed = !freezesAttr() || entity == null || serverCanHit(entity)
        if (allowed) {
            if (reachBufferTick != mc.thePlayer.ticksExisted) {
                reachBufferTick = mc.thePlayer.ticksExisted
                reachBuffer = maxOf(0.0, reachBuffer - 0.25)
            }

            return true
        }

        if (reachBuffer <= 0.0 && serverReachDistance(entity!!) <= 3.0) {
            reachBuffer = 1.0
            if (debug) chat("Direct attack off aim (server rotation frozen), further misses held")
            return true
        }

        return false
    }

    /** 对齐 moonlight isEatBurstNear：即将吃下苹果（32 包接近满）。 */
    fun isEatBurstNear(): Boolean = isEatBurstNear(4)

    fun isEatBurstNear(near: Int): Boolean = isEating && movingPackets >= 32 - near

    /** 对齐 moonlight isEatJustFinished：刚吃完（3 tick 内）。 */
    fun isEatJustFinished(): Boolean = eatFinishTicks > 0

    // ==================== 生命周期 ====================

    override fun onEnable() {
        ensureModeFresh()

        if (edMoonlight) {
            // 对齐 moonlight AutoGapple.onEnable
            packets.clear()
            endCheck = true
            edSlot = -1
            needSkip = false
            velocityLifted = false
            skipTicks = 0
            setbackPending = false
            setbackCooldown = 0
            movingPackets = 0
            isEating = false
            eatFinishTicks = 0
            wireMarked.clear()
            GrimServerModel.reset()
            timer.reset()
            return
        }

        // Legacy（原 Stuck 行为原样保留）
        isEating = false
        ticks = 0
        pauseTicks = 0
        stopStuck()

        val slot = getGApple()
        if (slot < 0) {
            if (chatMessage) chat("§c没有苹果了！")
            state = false
            return
        }

        isEating = true
        if (chatMessage) chat("§a开始吃金苹果")
    }

    override fun onDisable() {
        if (edMoonlight) {
            // 对齐 moonlight AutoGapple.onDisable
            isEating = false
            eatFinishTicks = 0
            release()
            wireMarked.clear()
            return
        }

        // Legacy
        ticks = 0
        pauseTicks = 0
        isEating = false
        cancelMove = false
        if (r) {
            val player = mc.thePlayer ?: return
            player.motionX = x
            player.motionY = y
            player.motionZ = z
            r = false
        }
    }

    // ==================== Legacy 事件 ====================

    private fun onLegacyPacket(event: PacketEvent) {
        val packet = event.packet

        // Stuck：定身期间拦截 C03 计数
        if (packet is C03PacketPlayer && cancelMove && ticks < c) {
            if (packet is C05PacketPlayerLook) {
                legacyYaw = packet.yaw
                legacyPitch = packet.pitch
            }
            ticks++
            event.cancelEvent()
        }
    }

    private fun onLegacyTick() {
        val slot = getGApple()

        if (slot < 0) {
            if (chatMessage) chat("§c没有苹果了！")
            state = false
            return
        }

        if (pauseTicks == 0) {
            stuck()
        } else if (pauseTicks > 0) {
            stopStuck()
            pauseTicks--
        }

        if (ticks >= c) {
            sendPacket(C09PacketHeldItemChange(slot))
            sendPacket(C08PacketPlayerBlockPlacement(mc.thePlayer.inventory.getStackInSlot(slot)))
            release()
            sendPacket(C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem))
            sendPacket(C08PacketPlayerBlockPlacement(mc.thePlayer.inventory.getStackInSlot(mc.thePlayer.inventory.currentItem)))
            pauseTicks++
            ticks = 0

            if (mc.thePlayer.ticksExisted % 20 == 0 && chatMessage) {
                chat("§6正在吃金苹果...")
            }
        }
    }

    private fun stuck() {
        if (!r) {
            val player = mc.thePlayer
            x = player.motionX
            y = player.motionY
            z = player.motionZ
            r = true
        }
        cancelMove = true
    }

    private fun stopStuck() {
        cancelMove = false
        if (r) {
            val player = mc.thePlayer
            player.motionX = x
            player.motionY = y
            player.motionZ = z
            r = false
        }
    }

    private fun release() {
        // Legacy：把定身期间拦截的 C03 补发出去
        sendPacket(C05PacketPlayerLook(legacyYaw, legacyPitch, mc.thePlayer.onGround))
        for (i in 1 until ticks) {
            sendPacket(C03PacketPlayer(mc.thePlayer.onGround))
        }
    }

    // ==================== EdMoonlight 事件 ====================

    private fun onEdMoonlightMotion(event: MotionEvent) {
        // 对齐 moonlight onMotion POST：追加 release 边界标记（chat 包）
        if (event.eventState == EventState.POST) {
            if (isEating) {
                packets.add(C01PacketChatMessage("release"))
            }
            return
        }

        val player = mc.thePlayer ?: return

        // 对齐 moonlight onMotion PRE
        if (!player.isEntityAlive) {
            isEating = false
            packets.clear()
            wireMarked.clear()
            movingPackets = 0
            endCheck = true
            return
        }

        if (eatFinishTicks > 0) {
            eatFinishTicks--
        }

        if (setbackPending) {
            abortForSetback()
        }

        if (setbackCooldown > 0) {
            setbackCooldown--
            if (isEating) {
                isEating = false
                release()
            }
        } else if (mc.playerController?.currentGameType?.isSurvivalOrAdventure != true
            || !timer.hasTimePassed(delay)
        ) {
            isEating = false
            release()
        } else {
            val target = KillAura.target
            edSlot = getGApple()
            if (edSlot == -1 && autoClose && !hasGappleInInventory()) {
                toggle()
            } else {
                val hp = player.health
                val shouldEat =
                    hp < health || (isEating && movingPackets > 0 && hp < player.maxHealth)

                // moonlight：Clutch.isWorking() → AirPlus Scaffold 的 Clutch 保命中（激活或冻结扣包）；
                // Scaffold.isBridging() → 正在搭路（最近放过方块），由 Pause While Bridging 开关控制
                val clutchWorking = Scaffold.isClutchWorking()
                val bridging = pauseOnScaffold && Scaffold.isBridging()
                if (!shouldEat || (!clutchWorking && !bridging)) {
                    if (!shouldEat) {
                        critWaitTicks = 0
                        aimWaitTicks = 0
                    }

                    if (edSlot == -1 || !shouldEat || (isEating && !endCheck)
                        || (!waitCritFreeze(hp, target) && !waitAimFreeze(hp, target))
                    ) {
                        if (edSlot != -1 && shouldEat) {
                            if (!isEating) {
                                eatStartTime = System.currentTimeMillis()
                            }

                            if (!isEating || endCheck) {
                                lastCritWait = critWaitTicks
                                critWaitTicks = 0
                                lastAimWait = aimWaitTicks
                                aimWaitTicks = 0
                            }

                            isEating = true
                            if (movingPackets >= 32) {
                                // 吃下苹果
                                if (edSlot != player.inventory.currentItem) {
                                    sendPacket(C09PacketHeldItemChange(edSlot), triggerEvent = false)
                                }

                                sendPacket(
                                    C08PacketPlayerBlockPlacement(player.inventory.getStackInSlot(edSlot)),
                                    triggerEvent = false
                                )
                                val deferredAttacks = release(collectAttacks = true)
                                if (edSlot != player.inventory.currentItem) {
                                    sendPacket(
                                        C09PacketHeldItemChange(player.inventory.currentItem),
                                        triggerEvent = false
                                    )
                                }

                                sendDeferredAttacks(deferredAttacks)
                                val held: ItemStack? = player.heldItem
                                if (held != null && held.item is ItemSword) {
                                    // moonlight 在 KillAura.via 模式下走 PlayerUtil.sendViaC08（Via 注入）；
                                    // AirPlus KillAura 没有 via 模式，一律走普通 C08 分支
                                    sendPacket(C08PacketPlayerBlockPlacement(held), triggerEvent = false)
                                }

                                if (debug) {
                                    chat(
                                        "Ate gapple in ${System.currentTimeMillis() - eatStartTime} ms"
                                            + (if (lastCritWait > 0) " (crit wait ${lastCritWait}t)" else "")
                                            + (if (lastAimWait > 0) " (aim wait ${lastAimWait}t)" else "")
                                    )
                                }

                                eatStartTime = System.currentTimeMillis()
                                eatFinishTicks = 3
                                timer.reset()
                            } else {
                                val flushNow = endCheck || (
                                    if (smartMode) {
                                        val size = KillAura.targets.size
                                        (size <= 1 && target != null && target.hurtTime <= 1 && !attackedCheck)
                                            || (size > 1 && player.ticksExisted % 10 == 0)
                                    } else {
                                        player.ticksExisted % count == 0
                                    }
                                    )

                                if (flushNow) {
                                    if (target != null && target.hurtTime == 0) {
                                        attackedCheck = true
                                    }

                                    // invalidC03：中间轮不回放队列，等 endCheck（吃下轮）一次性回放
                                    if (invalidC03 && !endCheck) {
                                        return
                                    }

                                    endCheck = false

                                    // moonlight：NewArmorBreaker.beforeAttrFreeze()；
                                    // AirPlus ArmorBreak 没有对应接口，跳过

                                    while (!packets.isEmpty()) {
                                        val packet = packets.poll()
                                        if (packet is C01PacketChatMessage) {
                                            return
                                        }

                                        if (packet is C03PacketPlayer) {
                                            movingPackets--
                                        }

                                        sendQueuedPacket(packet)
                                    }

                                    return
                                }

                                attackedCheck = false
                            }
                        }
                    } else {
                        if (isEating) {
                            isEating = false
                            release()
                        }
                    }
                } else {
                    if (isEating) {
                        isEating = false
                        release()
                        if (debug) {
                            chat(if (clutchWorking) "Eat aborted: clutch" else "Eat aborted: bridging")
                        }
                    }
                }
            }
        }
    }

    private fun onEdMoonlightPacket(event: PacketEvent) {
        if (mc.thePlayer == null || mc.playerController?.currentGameType?.isSurvivalOrAdventure != true) {
            return
        }

        val packet = event.packet

        if (event.eventType == EventState.SEND) {
            if (event.isCancelled) {
                return
            }

            when (packet) {
                is C00Handshake,
                is C00PacketLoginStart,
                is C00PacketServerQuery,
                is C01PacketPing,
                is C01PacketEncryptionResponse,
                is C01PacketChatMessage,
                -> return
            }

            val excluded = packet is C09PacketHeldItemChange
                || (packet is C0EPacketClickWindow && consumeNextClick())
                || packet is C16PacketClientStatus
                || packet is C0DPacketCloseWindow

            if (!excluded && isEating) {
                if (packet is C03PacketPlayer) {
                    movingPackets++

                    // moonlight：shouldQueueWeaponSwap（eating && !alwaysAttack）时 onQueued 同步 server model
                    if (!alwaysAttack) {
                        GrimServerModel.onWire(packet)
                        wireMarked.add(packet)
                    }

                    if (packet.rotating) {
                        lastYaw = packet.yaw
                        lastPitch = packet.pitch
                    }
                } else if (invalidC03) {
                    return
                }

                event.cancelEvent()
                packets.add(packet)
            }
        } else {
            // RECEIVE
            if ((packet is S12PacketEntityVelocity && packet.entityID == mc.thePlayer.entityId)
                || (packet is S27PacketExplosion
                    && (packet.func_149149_c() != 0.0F || packet.func_149144_d() != 0.0F || packet.func_149147_e() != 0.0F))
            ) {
                needSkip = true
                velocityLifted = false
                skipTicks = 0
            } else if (packet is S08PacketPlayerPosLook && isEating) {
                setbackPending = true
                isEating = false
            }
        }
    }

    private fun onEdMoonlightWorld() {
        packets.clear()
        wireMarked.clear()
        isEating = false
        movingPackets = 0
        eatFinishTicks = 0
        endCheck = true
        needSkip = false
        attackedCheck = false
    }

    private fun onEdMoonlightMoveMath(event: MoveMathEvent) {
        val player = mc.thePlayer ?: return

        if (needSkip) {
            // 速度包 needSkip：落地（或超时）前不冻结移动，让客户端吃上击退，避免吃苹果时被服务端回拉
            if (!player.onGround) {
                velocityLifted = true
                skipTicks = 0
            } else if (velocityLifted || ++skipTicks > 5) {
                needSkip = false
                velocityLifted = false
                skipTicks = 0
            }
            return
        }

        // 吃苹果期间冻结客户端位置（对齐 SilenceFix/moonlight 的 MoveMath 取消）：
        // 出站 C03 已进队列缓存，服务端视角位置不动，客户端侧也停住保持两侧一致。
        // 之前只在 Lag When In Air / invalidC03 下取消，导致普通模式下客户端照常移动而服务端位置冻结。
        if (isEating) {
            event.cancelEvent()
        }
    }

    private fun onEdMoonlightSlowDown(event: SlowDownEvent) {
        if (shouldSlow()) {
            // AirPlus 的 SlowDownEvent 是使用物品减速系数（默认 0.2），清零 = 完全不吃减速
            event.strafe = 0f
            event.forward = 0f
        }
    }

    private fun onEdMoonlightMovementInput(event: MovementInputEvent) {
        if (!shouldSlow()) return

        val input = event.originalInput
        if (noMove) {
            input.moveForward = 0f
            input.moveStrafe = 0f
        } else {
            if (Math.abs(input.moveForward) > 0.2f) {
                input.moveForward *= 0.2f
            }

            if (Math.abs(input.moveStrafe) > 0.2f) {
                input.moveStrafe *= 0.2f
            }
        }
    }

    // ==================== EdMoonlight 内部逻辑 ====================

    private fun waitCritFreeze(hp: Float, target: EntityLivingBase?): Boolean {
        // moonlight 的 isEnabled(NewArmorBreaker.class) 条件映射为 AirPlus 的 ArmorBreak.state
        if (alwaysAttack && critFreeze && target != null && hp >= CRIT_FREEZE_URGENT_HP
            && ArmorBreak.state && critWaitTicks < critFreezeWait && !freezeCrit()
        ) {
            critWaitTicks++
            return true
        }

        return false
    }

    private fun waitAimFreeze(hp: Float, target: EntityLivingBase?): Boolean {
        if (alwaysAttack && aimFreeze && target != null && hp >= CRIT_FREEZE_URGENT_HP
            && aimWaitTicks < aimFreezeWait && serverReachDistance(target) <= 3.0 && !freezeAimHits(target)
        ) {
            aimWaitTicks++
            return true
        }

        return false
    }

    private fun freezeAimHits(entity: Entity): Boolean {
        var rotation = GrimState.lastSentRotation()
        var claimed = GrimState.claimedFeet()

        if (isEating) {
            for (queued in packets) {
                if (queued is C01PacketChatMessage) {
                    break
                }

                if (queued is C03PacketPlayer) {
                    if (queued.rotating) {
                        rotation = floatArrayOf(queued.yaw, queued.pitch)
                    }

                    if (queued.isMoving) {
                        claimed = Vec3(queued.x, queued.y, queued.z)
                    }
                    break
                }
            }
        }

        val rot = rotation ?: return true

        val box = entity.entityBoundingBox.contract(0.15, 0.15, 0.15)
        return rayHits(rot, claimed, box, doubleArrayOf(mc.thePlayer.getEyeHeight().toDouble()))
    }

    private fun freezeCrit(): Boolean {
        if (isEating) {
            for (queued in packets) {
                if (queued is C01PacketChatMessage) {
                    break
                }

                if (queued is C03PacketPlayer) {
                    return GrimServerModel.predictCritAfter(mc.thePlayer, queued)
                }
            }
        }

        return GrimServerModel.predictCrit(mc.thePlayer)
    }

    private fun shouldSlow(): Boolean {
        val player = mc.thePlayer ?: return false
        if (getGApple() == -1) return false

        return isEating || (
            setbackCooldown <= 0
                && mc.playerController?.currentGameType?.isSurvivalOrAdventure == true
                && timer.hasTimePassed(delay)
                && player.health < health
            )
    }

    private fun abortForSetback() {
        setbackPending = false
        isEating = false

        while (!packets.isEmpty()) {
            val packet = packets.poll()
            val replayable = packet !is C01PacketChatMessage && packet !is C03PacketPlayer
                && packet !is C02PacketUseEntity && packet !is C0APacketAnimation
                && packet !is C08PacketPlayerBlockPlacement
            if (replayable) {
                sendPacket(packet, triggerEvent = false)
            }
        }

        movingPackets = 0
        endCheck = true
        needSkip = false
        velocityLifted = false
        skipTicks = 0
        setbackCooldown = SETBACK_COOLDOWN_TICKS
        wireMarked.clear()
        if (debug) {
            chat("Setback while eating, eat aborted")
        }
    }

    /**
     * 对齐 moonlight release：
     * collectAttacks=true 时收集攻击包（C02 ATTACK / C0A）供吃下后延迟发送，并丢弃吃苹果用的 C08(255)；
     * invalidC03 / 空中 lag 场景下把队列里的 C03 替换成「无效版」（仅最后 rotation）回放。
     */
    private fun release(collectAttacks: Boolean = false): List<Packet<*>> {
        val attacks = ArrayList<Packet<*>>()
        if (mc.netHandler == null) {
            return attacks
        }

        val player = mc.thePlayer
        var replacedC03 = 0
        val inAirLag = lagValue && player != null && !player.onGround

        while (!packets.isEmpty()) {
            val packet = packets.poll()

            if (collectAttacks) {
                val isAttack = (packet is C02PacketUseEntity && packet.action == C02PacketUseEntity.Action.ATTACK)
                    || packet is C0APacketAnimation
                if (isAttack) {
                    attacks.add(packet)
                    continue
                }

                if (packet is C08PacketPlayerBlockPlacement && packet.getPlacedBlockDirection() == 255) {
                    continue
                }
            }

            val isReleaseUseItem = packet is C07PacketPlayerDigging
                && packet.getStatus() == C07PacketPlayerDigging.Action.RELEASE_USE_ITEM

            if (packet !is C01PacketChatMessage && !isReleaseUseItem && packet !is C0DPacketCloseWindow) {
                if (invalidC03 || (inAirLag && packet is C03PacketPlayer)) {
                    if (packet is C03PacketPlayer) {
                        val replacement: C03PacketPlayer = if (replacedC03 == 0) {
                            C05PacketPlayerLook(lastYaw, lastPitch, player?.onGround ?: true)
                        } else {
                            C03PacketPlayer(player?.onGround ?: true)
                        }

                        // 替换包真实出站，手动同步 wire 状态（noEvent 直发不触发 SEND 事件）
                        GrimServerModel.onWire(replacement)
                        GrimState.onFlyingPacket(replacement)
                        sendPacket(replacement, triggerEvent = false)
                        replacedC03++
                    }
                    // invalidC03 模式下非 C03 包不回放（moonlight 原样：丢弃）
                } else {
                    if (packet is C03PacketPlayer) {
                        if (!wireMarked.remove(packet)) {
                            GrimServerModel.onWire(packet)
                        }
                        GrimState.onFlyingPacket(packet)
                    }
                    sendPacket(packet, triggerEvent = false)
                }
            }
        }

        endCheck = true
        movingPackets = 0
        wireMarked.clear()
        return attacks
    }

    /** 回放缓存队列里的原始包（moonlight sendPacketNoEvent 等价），并同步 wire 状态。 */
    private fun sendQueuedPacket(packet: Packet<*>) {
        if (packet is C03PacketPlayer) {
            // 拦截时已同步过的包（!alwaysAttack）不重复计数，对齐 moonlight queued 去重
            if (!wireMarked.remove(packet)) {
                GrimServerModel.onWire(packet)
            }
            GrimState.onFlyingPacket(packet)
        }

        sendPacket(packet, triggerEvent = false)
    }

    private fun sendDeferredAttacks(attacks: List<Packet<*>>) {
        // LinkedHashMap 按 entityId 去重（保留每目标最后一次攻击）
        val deferred = LinkedHashMap<Int, C02PacketUseEntity>()

        for (attack in attacks) {
            if (attack is C02PacketUseEntity) {
                // AirPlus 的 C02PacketUseEntity 没有 getEntityId()，通过 getEntityFromWorld 取回
                val entityId = attack.getEntityFromWorld(mc.theWorld ?: continue)?.entityId ?: -1
                deferred.remove(entityId)
                deferred[entityId] = attack
            }
        }

        val v1_8 = ViaLoadingBase.getInstance().targetVersion.isOlderThanOrEqualTo(ProtocolVersion.v1_8)

        for (attack in deferred.values) {
            if (v1_8) {
                // v1.8 攻击顺序：动画先行
                sendPacket(C0APacketAnimation(), triggerEvent = false)
                sendPacket(attack, triggerEvent = false)
            } else {
                sendPacket(attack, triggerEvent = false)
                sendPacket(C0APacketAnimation(), triggerEvent = false)
            }
        }
    }

    // ==================== 服务端命中判定（moonlight GrimPlaceComponent 子集） ====================

    private fun grimTargetBox(entity: Entity): AxisAlignedBB {
        val version = ViaLoadingBase.getInstance().targetVersion
        var box = entity.entityBoundingBox
        val lastTickBox = box.offset(
            entity.lastTickPosX - entity.posX,
            entity.lastTickPosY - entity.posY,
            entity.lastTickPosZ - entity.posZ
        )
        box = box.union(lastTickBox)
        val expand = 5.0E-4 + when {
            version.isOlderThanOrEqualTo(ProtocolVersion.v1_8) -> 0.1
            version.isOlderThan(ProtocolVersion.v1_18_2) -> 0.03
            else -> 2.0E-4
        }
        return box.expand(expand, expand, expand)
    }

    private fun grimEyeHeights(): DoubleArray {
        val version = ViaLoadingBase.getInstance().targetVersion
        return when {
            version.isNewerThanOrEqualTo(ProtocolVersion.v1_14) -> doubleArrayOf(0.4, 1.27, 1.62)
            version.isNewerThanOrEqualTo(ProtocolVersion.v1_9) -> doubleArrayOf(0.4, 1.54, 1.62)
            else -> doubleArrayOf(1.54, 1.62)
        }
    }

    private fun serverCanHit(entity: Entity): Boolean {
        val rotation = GrimState.lastSentRotation() ?: return true
        return rayHits(rotation, GrimState.claimedFeet(), grimTargetBox(entity), grimEyeHeights())
    }

    /** mc.thePlayer.getLookCustom(yaw, pitch) 的等效实现走 RotationUtils.getVectorForRotation。 */
    private fun rayHits(rotation: FloatArray, claimedFeet: Vec3, box: AxisAlignedBB, eyeHeights: DoubleArray): Boolean {
        val look = RotationUtils.getVectorForRotation(rotation[0], rotation[1])

        for (eyeHeight in eyeHeights) {
            val eyes = Vec3(claimedFeet.xCoord, claimedFeet.yCoord + eyeHeight, claimedFeet.zCoord)
            if (box.isVecInside(eyes)) {
                return true
            }

            val end = eyes.addVector(look.xCoord * 6.0, look.yCoord * 6.0, look.zCoord * 6.0)
            val hit: MovingObjectPosition? = box.calculateIntercept(eyes, end)
            if (hit != null && eyes.distanceTo(hit.hitVec) <= 3.0) {
                return true
            }
        }

        return false
    }

    private fun serverReachDistance(entity: Entity): Double {
        val claimed = GrimState.claimedFeet()
        val box = grimTargetBox(entity)
        var distance = Double.MAX_VALUE

        for (eyeHeight in grimEyeHeights()) {
            val eyeX = claimed.xCoord
            val eyeY = claimed.yCoord + eyeHeight
            val eyeZ = claimed.zCoord
            val dx = eyeX - Math.max(box.minX, Math.min(eyeX, box.maxX))
            val dy = eyeY - Math.max(box.minY, Math.min(eyeY, box.maxY))
            val dz = eyeZ - Math.max(box.minZ, Math.min(eyeZ, box.maxZ))
            distance = Math.min(distance, Math.sqrt(dx * dx + dy * dy + dz * dz))
        }

        return distance
    }

    // ==================== 公共工具 ====================

    private fun getGApple(): Int {
        val player = mc.thePlayer ?: return -1
        for (i in 0..8) {
            val stack = player.inventory.getStackInSlot(i)
            if (stack != null && stack.item is ItemAppleGold) {
                return i
            }
        }
        return -1
    }

    private fun hasGappleInInventory(): Boolean {
        val player = mc.thePlayer ?: return false
        for (i in 0 until 36) {
            val stack = player.inventory.getStackInSlot(i)
            if (stack != null && stack.item is ItemAppleGold) {
                return true
            }
        }
        return false
    }

    // ==================== 统一事件挂载 ====================

    val onPacket = handler<PacketEvent> { event ->
        ensureModeFresh()
        if (edMoonlight) {
            onEdMoonlightPacket(event)
        } else {
            onLegacyPacket(event)
        }
    }

    val onMove = handler<MoveEvent> { event ->
        ensureModeFresh()
        if (mode == "Legacy" && cancelMove) {
            event.cancelEvent()
        }
    }

    val onTick = handler<GameTickEvent> {
        ensureModeFresh()
        if (mode == "Legacy") {
            onLegacyTick()
        }
    }

    val onMotion = handler<MotionEvent> {
        ensureModeFresh()
        if (edMoonlight) {
            onEdMoonlightMotion(it)
        }
    }

    val onMoveMath = handler<MoveMathEvent> { event ->
        ensureModeFresh()
        if (edMoonlight) {
            onEdMoonlightMoveMath(event)
        }
    }

    val onSlowDown = handler<SlowDownEvent> { event ->
        ensureModeFresh()
        if (edMoonlight) {
            onEdMoonlightSlowDown(event)
        }
    }

    val onMovementInput = handler<MovementInputEvent> { event ->
        ensureModeFresh()
        if (edMoonlight) {
            onEdMoonlightMovementInput(event)
        }
    }

    val onWorld = handler<WorldEvent> {
        ensureModeFresh()
        if (edMoonlight) {
            onEdMoonlightWorld()
        }
    }

    val onRender2D = handler<Render2DEvent> {
        ensureModeFresh()
        if (!isEating || !progressBar2) {
            return@handler
        }

        val scaledScreen = ScaledResolution(mc)
        val width = scaledScreen.scaledWidth.toFloat()
        val height = scaledScreen.scaledHeight.toFloat()

        // moonlight 的 ProgressBarRenderer.submit("Eating", movingPackets / 32F, ...)；
        // AirPlus 无全局进度条设施，复用 Legacy 的绘制（EdMoonlight 数据源为 movingPackets/32）
        if (mode == "Legacy") {
            drawProgressBar(width, height) { (ticks.toFloat() / c.toFloat()).coerceIn(0f, 1f) }
        } else {
            drawProgressBar(width, height) { (movingPackets / 32f).coerceIn(0f, 1f) }
        }
    }

    private fun drawProgressBar(width: Float, height: Float, progress: () -> Float) {
        val progressLength = 140F
        val startY = height / 4 * 3
        val startX = width / 2 - progressLength / 2

        val progressRatio = progress().coerceIn(0f, 1f)
        val currentProgress = progressLength * progressRatio
        val progressPercent = (progressRatio * 100).toInt()

        GlowUtils.drawGlow(
            startX - 2, startY - 2,
            progressLength + 4, 11F,
            4,
            Color(0, 0, 0, 120)
        )

        drawRoundedRect(startX, startY, startX + progressLength, startY + 7F, Color(0, 0, 0, 128).rgb, 2F)

        if (currentProgress != 0f) {
            drawRoundedGradientRectCorner(
                startX, startY,
                startX + currentProgress, startY + 7F,
                3f,
                Color(76, 157, 240, 255).rgb,
                Color(53, 200, 167, 255).rgb
            )
        }

        val percentText = "$progressPercent%"
        Fonts.fontRegular35.drawString(
            percentText,
            startX + progressLength + 5,
            startY,
            Color(255, 255, 255, 255).rgb,
            true
        )
    }
}
