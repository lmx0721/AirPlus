/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 * skid edmoonlight
 * Ported from Moonlight EdMoonlight NewArmorBreaker (wtf.moonlight.module.impl.combat).
 *
 * 移植适配说明（与 moonlight 原始实现的差异）：
 * - AutoGapple → AirPlus Gapple：isEating 联动、attr 冻结/刷新（freezesAttr/attrRefreshMax/
 *   refreshAttr）、shouldQueueWeaponSwap、queueNextClick 均已对接 Gapple 的 EdMoonlight 模式。
 * - KillAura：阻塞状态复用 KillAura.blockStatus；换武器后服务端格挡被打断时补发 C08。
 *   shouldBlock()/shouldAttack()/addons/Ray Cast/HYT rightClickDelayTimer 无对应 API，已精简。
 * - AttackEvent 不可取消：“对齐暴击”的 skip 只能放弃本次换武器（攻击仍会打出）。
 * - VelocityEvent → PacketEvent(RECEIVE) 中的 S12PacketEntityVelocity（自身）。
 * - BlinkComponent → 本对象内的迷你 blink（queue + dispatch + flush）。
 * - SpoofSlotComponent（纯视觉）、TimedPacket 队列（moonlight 中未被使用）、
 *   ArmorBreakerGrimReach 调试计数器：跳过。
 * - AttackOrder（viamcp fixes）→ armorbreak/AttackOrder.kt（C02+C0A 顺序）。
 */
package net.airplus.features.module.modules.combat.armorbreak

import net.airplus.event.AttackEvent
import net.airplus.event.EventState
import net.airplus.event.Listenable
import net.airplus.event.MotionEvent
import net.airplus.event.PacketEvent
import net.airplus.event.UpdateEvent
import net.airplus.event.handler
import net.airplus.features.module.modules.combat.ArmorBreak
import net.airplus.features.module.modules.combat.KillAura
import net.airplus.features.module.modules.combat.ArmorBreak.abIncludeAxe
import net.airplus.features.module.modules.combat.ArmorBreak.abAlignCrits
import net.airplus.features.module.modules.combat.ArmorBreak.abAutoLadderSize
import net.airplus.features.module.modules.combat.ArmorBreak.abBlinkSwitchAxe
import net.airplus.features.module.modules.combat.ArmorBreak.abBurst
import net.airplus.features.module.modules.combat.ArmorBreak.abBurstInterval
import net.airplus.features.module.modules.combat.ArmorBreak.abClimbNoKb
import net.airplus.features.module.modules.combat.ArmorBreak.abCritForecast
import net.airplus.features.module.modules.combat.ArmorBreak.abDebug
import net.airplus.features.module.modules.combat.ArmorBreak.abEarlySwitchTick
import net.airplus.features.module.modules.combat.ArmorBreak.abEdgeRisk
import net.airplus.features.module.modules.combat.ArmorBreak.abForceBlock
import net.airplus.features.module.modules.combat.ArmorBreak.abKeepEqualScore
import net.airplus.features.module.modules.combat.ArmorBreak.abKnockbackForecast
import net.airplus.features.module.modules.combat.ArmorBreak.abMaxAxeHoldTicks
import net.airplus.features.module.modules.combat.ArmorBreak.abMaxLadderSize
import net.airplus.features.module.modules.combat.ArmorBreak.abMinSharpness
import net.airplus.features.module.modules.combat.ArmorBreak.abNoGapple
import net.airplus.features.module.modules.combat.ArmorBreak.abOnlyDiamondAxe
import net.airplus.features.module.modules.combat.ArmorBreak.abOnlySwordWhenGapple
import net.airplus.features.module.modules.combat.ArmorBreak.abPreSwitch
import net.airplus.features.module.modules.combat.ArmorBreak.abServerHitGap
import net.airplus.features.module.modules.combat.ArmorBreak.abSmartRung
import net.airplus.features.module.modules.combat.ArmorBreak.abSwitchMode
import net.airplus.features.module.modules.combat.ArmorBreak.abTickHit
import net.airplus.features.module.modules.combat.ArmorBreak.abTickPlanner
import net.airplus.features.module.modules.combat.ArmorBreak.abTimingMode
import net.airplus.features.module.modules.combat.ArmorBreak.abTraceLog
import net.airplus.features.module.modules.movement.NoJumpDelay
import net.airplus.features.module.modules.player.Gapple
import net.airplus.features.module.modules.world.scaffolds.Scaffold
import net.airplus.utils.client.BlinkUtils
import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.client.PacketUtils.sendPacket
import net.airplus.utils.client.chat
import net.minecraft.client.network.NetworkPlayerInfo
import net.minecraft.enchantment.Enchantment
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.Item
import net.minecraft.item.ItemAxe
import net.minecraft.item.ItemStack
import net.minecraft.item.ItemSword
import net.minecraft.network.Packet
import net.minecraft.network.play.client.C03PacketPlayer
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement
import net.minecraft.network.play.client.C0BPacketEntityAction
import net.minecraft.network.play.server.S0BPacketAnimation
import net.minecraft.network.play.server.S12PacketEntityVelocity
import net.minecraft.network.play.server.S19PacketEntityStatus
import net.minecraft.potion.Potion
import java.util.Arrays
import java.util.HashMap
import java.util.concurrent.ConcurrentLinkedQueue

object EdMoonlightMode : Listenable, MinecraftInstance {

    // ------------------------------------------------------------------
    // Listenable：仅在 ArmorBreak 开启且模式为 EdMoonlight 时接收事件
    // ------------------------------------------------------------------
    override fun handleEvents(): Boolean = ArmorBreak.state && ArmorBreak.mode == "EdMoonlight"

    // moonlight 静态共享计数器（hurtTime/selectCount/swappedSlot）
    private var hurtAge = 0 // moonlight: hurtTime（自目标上次受击以来的 tick 数）
    private var selectCount = 0
    private var spoofTicks = 0

    // 常量
    private const val INVENTORY_SLOT = 36
    private const val LAG_GUARD_TICKS = 8
    private const val SPOOF_KEEP_TICKS = 10
    private const val PRE_IDLE_TICKS = 20

    // tick hit 状态
    private var thClimbHit = false
    private var kbStopped = false
    private var planSkip = false
    private var refreshUsed = 0
    private var refreshCycle = Int.MIN_VALUE
    private var thAcceptTick = -1000
    private var thTarget: EntityLivingBase? = null
    private var thFreshTick = -1000
    private var thLast = 0.0f
    private var dbgCritPred = 0
    private var dbgBlockFix = 0
    @Volatile
    private var dbgCritServer = 0
    @Volatile
    private var dbgCritTargetId = -1
    private var dbgCritTick = 0
    private var dbgSelfReason: String? = null
    private var thSelfAttack = false
    private var thConfirmed = false
    private val thSaved = HashMap<Int, FloatArray>()
    private var thLastHurt = 0
    private val thHitTicks = IntArray(16)
    private val thHitVals = FloatArray(16)
    private var thHitIdx = 0
    private val planner = ArmorBreakerPlanner()
    private val traceNoKb = BooleanArray(64)

    // burst 状态
    private var burstWarned = false
    private val burster = ArmorBreakerBurst()
    private val calib = ArmorBreakerCalib()
    private var burstArm = 0
    private var verifyBase = -1
    private var verifyPlanned = 0
    private var verifyTick = 0
    private var burstConfirmed = false
    private var lastTargetHurt = 0

    // 计时/目标状态
    private var preTick = 0
    private var preFreshTick = 0
    private var preRung = 0
    private var preLastAttackTick = 0
    private var preTarget: Entity? = null
    private var axeSince = -1
    private var axeHoldLock = false
    private var lastAttackTick = -1000
    private var lastAttackGap = 1000
    private var ownBlink = false
    private var classicFreshTick = -1000
    private var classicTarget: Entity? = null
    private var gapEma = 0.0f
    private var autoStage = 0
    private var lastTarget: Entity? = null
    private var dbgTarget: EntityLivingBase? = null
    private var dbgBaseDamage = -1
    private var dbgPieces = 0
    private var dbgStartTick = 0
    private var dbgAttacks = 0
    private var dbgReason: String? = null
    private var smartLast = 0.0f
    private var smartTarget: Entity? = null
    private var dbgPredicted = 0.0f
    private var dbgCrit = false
    private var dbgAction = ""
    private var swappedSlot = -1
    private var swappedItem: Item? = null
    private var lastSwapTime = 0L
    private val ladder = ArmorBreakerLadder()

    // 自有 blink（moonlight BlinkComponent 子集）
    private var ownBlinkActive = false
    private var blinkDispatch = false
    private val blinkPackets = ConcurrentLinkedQueue<Packet<*>>()

    // ------------------------------------------------------------------
    // 事件处理
    // ------------------------------------------------------------------
    private val onUpdate = handler<UpdateEvent> {
        val player = mc.thePlayer ?: return@handler

        if (abBurst != burstWarned) {
            burstWarned = abBurst
            if (burstWarned) {
                chat("§cArmorBreaker Burst mode cannot work with GrimAC §7(multi-hit gets cancelled). §fVanilla / no-anticheat servers only.")
            }
        }

        if (spoofTicks > 0) {
            spoofTicks--
        }

        ArmorBreakerServerModel.trackKnockback(player)
        ArmorBreakerServerModel.kbBack = Math.max(1, Math.min(3, Math.round(Math.max(0, ping()) / 100.0f)))
        val killAuraActive = KillAura.state && KillAura.target != null
        val keepVisual = spoofTicks > 0 || killAuraActive
        if (!keepVisual) {
            // moonlight 原逻辑：SpoofSlotComponent.clearVisualOverride()（纯视觉，AirPlus 无对应组件，跳过）
            swappedSlot = -1
            swappedItem = null
        }

        val held = player.heldItem
        if (held != null && held.item is ItemAxe && gappleSwordOnly() && Gapple.state && Gapple.isEating) {
            switchToBestSword()
        }
    }

    private val onAttack = handler<AttackEvent> { event ->
        if (thSelfAttack) return@handler
        val player = mc.thePlayer ?: return@handler
        val attackTarget = event.targetEntity

        lastAttackGap = preTick - lastAttackTick
        lastAttackTick = preTick
        lastTarget = attackTarget
        if (lastAttackGap >= 1 && lastAttackGap <= 20) {
            gapEma = if (gapEma <= 0.0f) lastAttackGap.toFloat() else gapEma * 0.8f + lastAttackGap * 0.2f
        }

        updateAutoStage()
        if (!abBurst || !(attackTarget is EntityLivingBase && tryBurst(attackTarget))) {
            if (useTickHit() && attackTarget is EntityLivingBase && tickHitReady(true)) {
                planSkip = false
                tickHitDecide(attackTarget, false)
                if (planSkip) {
                    // moonlight: event.setCancelled(true) —— AirPlus AttackEvent 不可取消，
                    // 只能放弃本次换武器（攻击仍会按当前武器打出）
                    planSkip = false
                } else {
                    climbKbStop()
                }
            } else {
                if (attackTarget is EntityLivingBase) {
                    hurtAge = if (attackTarget.hurtTime != 0) 10 - attackTarget.hurtTime else 0
                }

                val gappleOnly = gappleSwordOnly()
                val pre = usePre() && !gappleOnly
                val smart = !pre && abSmartRung
                if (!smart || lastAttackGap != 0 || attackTarget !== smartTarget) {
                    if (!pre && useLagGuard()) {
                        val since = preTick - classicFreshTick
                        if (attackTarget !== classicTarget || since >= 10 || selectCount > hurtAge && since >= LAG_GUARD_TICKS) {
                            selectCount = 0
                        }

                        classicTarget = attackTarget
                    } else if (selectCount > hurtAge) {
                        selectCount = 0
                    }
                }

                if (selectCount == 0) {
                    classicFreshTick = preTick
                }

                if (pre) {
                    onPreSwitchAttack(attackTarget)
                    debugAttack(attackTarget, "Pre", preRung)
                } else {
                    axeHoldLock = false
                    val held = player.heldItem
                    val useBlink = abBlinkSwitchAxe && !Gapple.state && player.hurtTime != 9
                    val ladderArr = getSwordArr()
                    if (gappleOnly) {
                        ladderArr.removeAxes(player.inventory)
                    }

                    if (ladderArr.size <= 1) {
                        debugReason(
                            if (ladderArr.size == 0) "no weapon in inventory"
                            else "only 1 distinct weapon (InvManager Keep Multi Sword off? same sharpness?)"
                        )
                        if (ladderArr.size == 0) {
                            return@handler
                        }
                    } else {
                        dbgReason = null
                    }

                    val heldIsAxe = gappleOnly && held != null && held.item is ItemAxe
                    if (held != null && !isLadderWeapon(held, gappleOnly) && !heldIsAxe) {
                        debugReason("held item is not a ladder weapon: " + held.displayName)
                    } else {
                        spoofTicks = SPOOF_KEEP_TICKS
                        if (useBlink && ownBlinkActive) {
                            // moonlight: BlinkComponent.dispatch() —— 让下个 C03 触发 flush
                            blinkDispatch = true
                            ownBlinkActive = false
                            ownBlink = false
                        }

                        if (smart) {
                            smartRotate(ladderArr, attackTarget, useBlink)
                        } else if (selectCount < ladderArr.size) {
                            if (useBlink && selectCount == ladderArr.size - 1
                                && player.inventory.getStackInSlot(ladderArr.slots[ladderArr.size - 1])?.item is ItemAxe
                            ) {
                                ownBlinkActive = true
                                ownBlink = true
                            }

                            rotateClick(ladderArr.slots[selectCount])
                            selectCount++
                        } else if (abIncludeAxe || hurtAge > 9.0f - abEarlySwitchTick) {
                            rotateClick(ladderArr.slots[0])
                        }

                        afterSwap(player.heldItem !== held, true)
                        debugAttack(attackTarget, (if (useLagGuard()) "LagGuard" else "Classic") + if (smart) "+Smart" else "", selectCount)
                    }
                }
            }
        }
    }

    private val onMotion = handler<MotionEvent> { event ->
        if (event.eventState != EventState.PRE) return@handler
        val player = mc.thePlayer ?: return@handler

        climbKbRestore()
        if (usePre() && !useTickHit()) {
            preSwitchTick()
        }

        if (useTickHit()) {
            tickHitTick()
        }

        smartAxeBack()
        limitAxeHold()
        if (abBurst) {
            burstTick()
        }

        settleBlock()
        debugArmorTick()
        ArmorBreakerTrace.enabled = abDebug && abTraceLog
        val traceTarget = thTarget
            ?: (lastTarget as? EntityLivingBase)?.takeIf { preTick - lastAttackTick <= PRE_IDLE_TICKS && !it.isDead && it.worldObj === mc.theWorld }
        ArmorBreakerTrace.targetId = traceTarget?.entityId ?: -1
        ArmorBreakerTrace.tick(preTick + 1, ping(), traceTarget)
        if (useTickHit()) {
            debugCritTick()
        }

        if (preTick - lastAttackTick > PRE_IDLE_TICKS) {
            lastTarget = null
            classicTarget = null
        }

        preTick++
        ArmorBreakerServerModel.wireTick = preTick
    }

    private val onPacket = handler<PacketEvent> { event ->
        val packet = event.packet
        if (event.eventType == EventState.RECEIVE) {
            if (packet is S19PacketEntityStatus && packet.opCode.toInt() == 2
                && mc.thePlayer != null && packet.entityId == mc.thePlayer.entityId
            ) {
                ArmorBreakerServerModel.onSelfHurtPacket()
            }

            if (abDebug && packet is S0BPacketAnimation && packet.animationType == 4 && packet.entityID == dbgCritTargetId) {
                dbgCritServer++
            }

            ArmorBreakerTrace.onRawReceive(packet)

            // moonlight 用 VelocityEvent；AirPlus 无此事件，改用自身 S12PacketEntityVelocity
            if (packet is S12PacketEntityVelocity && mc.thePlayer != null && packet.entityID == mc.thePlayer.entityId) {
                ArmorBreakerServerModel.onSelfVelocity()
            }
        } else {
            val player = mc.thePlayer
            if (player != null) {
                // 观察者：把已发出/待发出的包喂给服务端状态镜像
                // （moonlight 仅在 AutoGapple blink 队列期间喂 C03，此处对所有 SEND 包喂数据）
                ArmorBreakerServerModel.onWire(packet)
            }

            if (ownBlinkActive && !blinkDispatch && !event.isCancelled) {
                event.cancelEvent()
                blinkPackets.add(packet)
            } else if (blinkDispatch && packet is C03PacketPlayer) {
                // dispatch：先补发排队的包，再让 C03 正常发出
                val iter = blinkPackets.iterator()
                while (iter.hasNext()) {
                    sendPacket(iter.next(), triggerEvent = false)
                    iter.remove()
                }
                blinkDispatch = false
            }
        }
    }

    // ------------------------------------------------------------------
    // 阶梯/选择
    // ------------------------------------------------------------------
    private fun getSwordArr(): ArmorBreakerLadder {
        ladder.build(
            mc.thePlayer!!.inventory,
            abIncludeAxe,
            abKeepEqualScore,
            abOnlyDiamondAxe,
            abMinSharpness,
            usePre() || abSmartRung
        )
        var maxSize = abMaxLadderSize.toInt()
        if (maxSize <= 0 && abAutoLadderSize && gapEma > 0.0f && !useTickHit()) {
            maxSize = Math.max(1, Math.min(9, Math.ceil(10.0f / gapEma - 0.05).toInt()))
        }

        ladder.keepTop(maxSize)
        return ladder
    }

    private fun isLadderWeapon(stack: ItemStack?, gappleOnly: Boolean): Boolean =
        ArmorBreakerLadder.isCandidate(stack, abIncludeAxe && !gappleOnly, abOnlyDiamondAxe, abMinSharpness)

    private fun firstNonAxe(ladderArr: ArmorBreakerLadder): Int {
        for (i in 0 until ladderArr.size) {
            val stack = mc.thePlayer!!.inventory.getStackInSlot(ladderArr.slots[i])
            if (stack != null && stack.item !is ItemAxe) {
                return i
            }
        }

        return -1
    }

    private fun lowestSwordAttr(ladderArr: ArmorBreakerLadder): Int {
        var pick = -1
        var best = 0.0f
        for (i in 0 until ladderArr.size) {
            val stack = mc.thePlayer!!.inventory.getStackInSlot(ladderArr.slots[i])
            if (stack != null && !isAxe(stack)) {
                val attr = ArmorBreakerLadder.itemAttr(stack)
                if (pick < 0 || attr < best) {
                    pick = i
                    best = attr
                }
            }
        }

        return pick
    }

    private fun ladderIndexOfHeld(ladderArr: ArmorBreakerLadder): Int {
        val held = mc.thePlayer!!.heldItem
        for (i in 0 until ladderArr.size) {
            if (mc.thePlayer!!.inventory.getStackInSlot(ladderArr.slots[i]) === held) {
                return i
            }
        }

        return -1
    }

    private fun slot(index: Int): Int =
        if (index >= INVENTORY_SLOT) 8 - (index - INVENTORY_SLOT) else if (index < 9) index + INVENTORY_SLOT else index

    private fun switchToBestSword() {
        val ladderArr = getSwordArr()
        ladderArr.removeAxes(mc.thePlayer!!.inventory)
        if (ladderArr.size != 0) {
            spoofTicks = SPOOF_KEEP_TICKS
            val held = mc.thePlayer!!.heldItem
            swapClick(slot(ladderArr.slots[ladderArr.size - 1]))
            afterSwap(mc.thePlayer!!.heldItem !== held, false)
        }
    }

    private fun getBestSwordStack(): ItemStack? {
        var best: ItemStack? = null
        var bestScore = -1.0f
        for (i in 0 until 36) {
            val stack = mc.thePlayer!!.inventory.getStackInSlot(i)
            val score = calculateWeaponScore(stack)
            if (score > bestScore) {
                bestScore = score
                best = stack
            }
        }

        return best
    }

    // ------------------------------------------------------------------
    // 换武器 / 点击
    // ------------------------------------------------------------------
    private fun swapClick(windowSlot: Int) {
        // moonlight: autoToolDigging() 阻止挖掘时换武器 —— AirPlus AutoTool 无 wasDigging 状态，跳过
        val player = mc.thePlayer ?: return
        // 吃苹果期间放行本次 C0E 点击包（对齐 moonlight AutoGapple.queueNextClick）
        Gapple.queueNextClick()
        mc.playerController.windowClick(player.inventoryContainer.windowId, windowSlot, player.inventory.currentItem, 2, player)
        markSwapped()
        ArmorBreakerServerModel.blockOwed = true
    }

    internal fun markSwapped() {
        lastSwapTime = System.currentTimeMillis()
        val held = mc.thePlayer!!.heldItem
        swappedSlot = if (held != null) mc.thePlayer!!.inventory.currentItem else -1
        swappedItem = held?.item
    }

    private fun holdingSwapped(): Boolean {
        val held = mc.thePlayer!!.heldItem
        return held != null && swappedSlot == mc.thePlayer!!.inventory.currentItem && held.item === swappedItem
    }

    private fun rotateClick(ladderIndex: Int) {
        if (mc.thePlayer!!.inventory.getStackInSlot(ladderIndex) !== mc.thePlayer!!.heldItem) {
            swapClick(slot(ladderIndex))
        }
    }

    private fun flushOwnBlink() {
        if (ownBlink && ownBlinkActive || blinkDispatch && blinkPackets.isNotEmpty()) {
            ownBlinkActive = false
            val iter = blinkPackets.iterator()
            while (iter.hasNext()) {
                sendPacket(iter.next(), triggerEvent = false)
                iter.remove()
            }
            blinkDispatch = false
        }

        ownBlink = false
    }

    // ------------------------------------------------------------------
    // KillAura 联动
    // ------------------------------------------------------------------
    private fun afterSwap(swapped: Boolean, fromTickHit: Boolean) {
        val kaTarget = if (KillAura.state) KillAura.target else null
        val kaBlocking = KillAura.state && KillAura.autoBlock != "Off" && kaTarget != null
        if (!kaBlocking) {
            if (abForceBlock && KillAura.state && kaTarget != null) {
                // moonlight 还检查 KillAura.shouldBlock() —— AirPlus 无对应 API
                sendForceBlock()
            }
        } else if (swapped) {
            // moonlight: isBlocking=false 后由 KillAura.block() 重发 C08；
            // AirPlus KillAura 自管格挡，这里直接补发一次 C08 恢复服务端格挡状态
            if (KillAura.blockStatus) {
                sendForceBlock()
            }
        }
    }

    private fun settleBlock() {
        if (ArmorBreakerServerModel.blockOwed) {
            ArmorBreakerServerModel.blockOwed = false
            val kaTarget = if (KillAura.state) KillAura.target else null
            if (KillAura.state && kaTarget != null && KillAura.autoBlock != "Off" && KillAura.blockStatus) {
                // moonlight: autoBlock in (Vanilla/NCP/Interact) 且 shouldBlock() —— 用 packet 型格挡近似
                sendForceBlock()
                dbgBlockFix++
            }
        }
    }

    private fun sendForceBlock() {
        val held = mc.thePlayer!!.heldItem
        sendPacket(C08PacketPlayerBlockPlacement(held))
    }

    // ------------------------------------------------------------------
    // 计时模式
    // ------------------------------------------------------------------
    private fun useTickHit(): Boolean = abTickHit && !abBurst

    private fun usePre(): Boolean = abPreSwitch || abTimingMode == "Auto" && autoStage == 2

    private fun useLagGuard(): Boolean =
        !abPreSwitch && (abTimingMode == "Lag Guard" || abTimingMode == "Auto" && autoStage == 1)

    private fun updateAutoStage() {
        val delay = ping()
        if (delay >= 0) {
            var stage = autoStage
            if (stage < 2 && delay >= 220) {
                stage = 2
            } else if (stage == 2 && delay < 180) {
                stage = if (delay >= 90) 1 else 0
            } else if (stage == 0 && delay >= 110) {
                stage = 1
            } else if (stage == 1 && delay < 90) {
                stage = 0
            }

            if (stage == 2 && abIncludeAxe && abBlinkSwitchAxe) {
                stage = 1
            }

            if (stage != autoStage) {
                autoStage = stage
                selectCount = 0
                preTarget = null
                if (abDebug) {
                    chat(
                        "ArmorBreaker timing -> " + (if (stage == 0) "Classic" else if (stage == 1) "Lag Guard" else "Pre Switch")
                            + " (ping " + delay + "ms)"
                    )
                }
            }
        }
    }

    private fun ping(): Int {
        if (mc.netHandler == null || mc.thePlayer == null) {
            return -1
        }

        val info: NetworkPlayerInfo? = mc.netHandler.getPlayerInfo(mc.thePlayer.uniqueID)
        return info?.responseTime ?: -1
    }

    // ------------------------------------------------------------------
    // Climb Knockback
    // ------------------------------------------------------------------
    private fun climbKbStop() {
        val player = mc.thePlayer ?: return
        if (abClimbNoKb && thClimbHit && !kbStopped && player.serverSprintState) {
            val packet = C0BPacketEntityAction(player, C0BPacketEntityAction.Action.STOP_SPRINTING)
            if (attrFrozen()) {
                sendPacket(packet, triggerEvent = false)
            } else {
                sendPacket(packet)
            }

            kbStopped = true
        }
    }

    private fun climbKbRestore() {
        val player = mc.thePlayer ?: return
        if (kbStopped) {
            kbStopped = false
            val packet = C0BPacketEntityAction(player, C0BPacketEntityAction.Action.START_SPRINTING)
            if (attrFrozen()) {
                sendPacket(packet, triggerEvent = false)
            } else {
                sendPacket(packet)
            }
        }
    }

    // ------------------------------------------------------------------
    // Pre Switch
    // ------------------------------------------------------------------
    private fun onPreSwitchAttack(target: Entity?) {
        if (target !== preTarget) {
            preTarget = target
            preFreshTick = preTick - 10
        }

        preLastAttackTick = preTick
        if (preTick - preFreshTick >= 10) {
            preFreshTick = preTick
            preRung = 1
            axeHoldLock = false
        } else {
            preRung++
        }
    }

    private fun preSwitchTick() {
        val player = mc.thePlayer ?: return
        if (preTarget == null || preTick - preLastAttackTick > PRE_IDLE_TICKS) {
            preTarget = null
            axeHoldLock = false
        } else if (!gappleSwordOnly() && player.openContainer === player.inventoryContainer) {
            val ladderArr = getSwordArr()
            if (ladderArr.size != 0) {
                val held = player.heldItem
                if (held == null || isLadderWeapon(held, false)) {
                    val rung = if (preTick + 1 - preFreshTick >= 10) 0 else Math.min(preRung, ladderArr.size - 1)
                    val target = player.inventory.getStackInSlot(ladderArr.slots[rung])
                    if (target !== held) {
                        if (target != null && target.item is ItemAxe && abMaxAxeHoldTicks > 0.0f) {
                            if (axeHoldLock) {
                                return
                            }

                            if (lastAttackGap > abMaxAxeHoldTicks.toInt()) {
                                return
                            }
                        }

                        spoofTicks = SPOOF_KEEP_TICKS
                        swapClick(slot(ladderArr.slots[rung]))
                        afterSwap(mc.thePlayer!!.heldItem !== held, false)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Tick Hit
    // ------------------------------------------------------------------
    private fun tickHitTick() {
        val player = mc.thePlayer ?: return
        ArmorBreakerServerModel.trackJump(player)
        ArmorBreakerServerModel.nextOwn = false
        thSyncWireHits()
        val target = thTarget
        if (target != null) {
            if (target.hurtTime > thLastHurt) {
                val diff = preTick - thFreshTick
                if (diff < 1 || diff > 2 + Math.max(0, ping()) / 50) {
                    thResync()
                }

                thConfirmed = true
            }

            thLastHurt = target.hurtTime
            val diff = preTick - thFreshTick
            val eatingNoFreeze = Gapple.state && Gapple.isEating && !attrFrozen()
            if (!thConfirmed && !eatingNoFreeze && diff > 2 + Math.max(0, ping()) / 50 && diff < 10) {
                thFreshTick = -1000
                thLast = 0.0f
            }

            if (target.worldObj !== mc.theWorld) {
                thReset()
            } else if (target.isDead) {
                thTarget = null
                thFreshTick = -1000
                thLast = 0.0f
                thConfirmed = false
            }
        }

        tickHitSelf()
        if (thTarget != null && tickHitReady(true)) {
            if (abTickPlanner) {
                planStage(thTarget!!)
            } else {
                tickHitAxeBack(thTarget!!)
            }
        }
    }

    private fun tickHitSelf() {
        val player = mc.thePlayer ?: return
        if (lastAttackTick != preTick && KillAura.state) {
            val target = KillAura.target
            // moonlight 还检查 KillAura.shouldAttack()（攻击范围）与 Ray Cast addon —— AirPlus 无对应 API
            val reason: String? = when {
                target == null -> null
                !tickHitReady() -> "busy (container/blink/eating/scaffold/non-weapon)"
                player.isUsingItem && !player.isBlocking -> "using item"
                else -> ""
            }
            if (reason != null) {
                if (reason.isNotEmpty()) {
                    dbgSelfReason = reason
                } else if (target != null && tickHitDecide(target, true)) {
                    thSelfAttack = true
                    climbKbStop()
                    try {
                        if (attrFrozen()) {
                            AttackOrder.sendFixedAttackNoPacketEvent(player, target)
                        } else {
                            AttackOrder.sendFixedAttack(player, target)
                        }
                    } finally {
                        thSelfAttack = false
                        climbKbRestore()
                    }
                }
            }
        }
    }

    private fun tickHitReady(): Boolean = tickHitReady(false)

    private fun tickHitReady(self: Boolean): Boolean {
        val player = mc.thePlayer ?: return false
        val eating = Gapple.state && Gapple.isEating
        if (player.openContainer === player.inventoryContainer
            && !ownBlinkActive && !BlinkUtils.isBlinking
            && (!eating || attrFrozen() || self && Gapple.shouldQueueWeaponSwap())
            && !Scaffold.state
        ) {
            val held = player.heldItem
            return held == null || isLadderWeapon(held, gappleSwordOnly())
        }

        return false
    }

    private fun attrFrozen(): Boolean {
        // moonlight: isEnabled(AutoGapple) && gapple.freezesAttr()
        return Gapple.state && Gapple.freezesAttr()
    }

    private fun gappleSwordOnly(): Boolean = abNoGapple && abOnlySwordWhenGapple && Gapple.state

    private fun thReset() {
        thTarget = null
        thFreshTick = -1000
        thLast = 0.0f
        thConfirmed = false
        thLastHurt = 0
        thAcceptTick = -1000
        thSaved.clear()
        thClearHits()
        ArmorBreakerServerModel.resetKnockback()
    }

    private fun thClearHits() {
        Arrays.fill(thHitTicks, -1000)
    }

    private fun thRecordHit(amount: Float) {
        thRecordHit(amount, preTick)
    }

    private fun thRecordHit(amount: Float, tick: Int) {
        thHitTicks[thHitIdx] = tick
        thHitVals[thHitIdx] = amount
        thHitIdx = (thHitIdx + 1) % thHitTicks.size
    }

    private fun thResync() {
        val resyncTick = preTick - 1 - Math.max(0, ping()) / 50
        var last = 0.0f
        for (i in thHitTicks.indices) {
            if (thHitTicks[i] >= resyncTick && thHitVals[i] > last) {
                last = thHitVals[i]
            }
        }

        if (abDebug) {
            chat(String.format("AB resync t=%d fresh %d -> %d last %.2f -> %.2f", preTick, thFreshTick, resyncTick, thLast, last))
        }

        thFreshTick = resyncTick
        thLast = last
        thAcceptTick = preTick
    }

    private fun thSwitchTarget(target: EntityLivingBase) {
        thTarget?.let { current ->
            thSaved[current.entityId] = floatArrayOf(thFreshTick.toFloat(), thLast, if (thConfirmed) 1.0f else 0.0f, preTick.toFloat())
        }

        thSaved.values.removeIf { saved -> preTick - saved[3].toInt() > PRE_IDLE_TICKS }
        val saved = thSaved.remove(target.entityId)
        thTarget = target
        thLastHurt = target.hurtTime
        thClearHits()
        if (saved != null) {
            thFreshTick = saved[0].toInt()
            thLast = saved[1]
            thConfirmed = saved[2] > 0.0f
        } else if (target.hurtTime > 0) {
            thFreshTick = preTick - (10 - target.hurtTime) - Math.max(0, ping()) / 50
            thLast = Float.MAX_VALUE
            thConfirmed = true
        } else {
            thFreshTick = -1000
            thLast = 0.0f
            thConfirmed = false
        }
    }

    private fun thSyncWireHits() {
        val interval = abBurstInterval.toInt()
        val hitGap = if (abTickPlanner) abServerHitGap.toInt() else 1

        while (true) {
            val hit = ArmorBreakerServerModel.wireHits.poll() ?: break
            if (thTarget != null && hit[0].toInt() == thTarget!!.entityId && (hit.size <= 2 || !(hit[2] > 0.0f))) {
                val tick = if (hit.size > 3) Math.min(hit[3].toInt(), preTick) else preTick
                thRecordHit(hit[1], tick)
                if (hitGap <= 1 || tick - thAcceptTick >= hitGap) {
                    if (tick - thFreshTick >= interval) {
                        thFreshTick = tick
                        thLast = hit[1]
                        thConfirmed = false
                        thAcceptTick = tick
                    } else if (hit[1] > thLast) {
                        thLast = hit[1]
                        thAcceptTick = tick
                    }
                }
            }
        }
    }

    private fun tickHitDecide(target: EntityLivingBase, self: Boolean): Boolean {
        thClimbHit = false
        if (target !== thTarget) {
            thSwitchTarget(target)
        }

        thSyncWireHits()
        dbgCritTargetId = target.entityId
        val ladderArr = getSwordArr()
        if (gappleSwordOnly()) {
            ladderArr.removeAxes(mc.thePlayer!!.inventory)
        }

        if (ladderArr.size == 0) {
            return false
        } else if (abTickPlanner) {
            return planDecide(ladderArr, target, self)
        } else {
            val player = mc.thePlayer!!
            val fresh = preTick - thFreshTick >= abBurstInterval.toInt()
            val critNow = ArmorBreakerServerModel.predictCrit(player)
            val critNext = ArmorBreakerServerModel.predictNextCrit(player, self)
            val serverAttr = ArmorBreakerServerModel.serverAttr(player)
            val pick = thPick(ladderArr, serverAttr, critNow, critNext, target, if (fresh) Float.NEGATIVE_INFINITY else thLast)
            val held = player.heldItem
            if (pick >= 0) {
                rotateClick(ladderArr.slots[pick])
                thRecordHit(dbgPredicted)
                thClimbHit = !fresh
                thLast = dbgPredicted
                if (critNow) {
                    dbgCritPred++
                }

                if (fresh) {
                    thFreshTick = preTick
                    thConfirmed = false
                }
            } else {
                if (self) {
                    tickHitStage(ladderArr, target, critNext)
                    if (mc.thePlayer!!.heldItem !== held) {
                        spoofTicks = SPOOF_KEEP_TICKS
                        afterSwap(true, true)
                    }

                    return false
                }

                tickHitStage(ladderArr, target, critNext)
                val amount = ArmorBreakerServerModel.predictAmount(serverAttr, critNow, mc.thePlayer!!.heldItem, target)
                thRecordHit(amount)
                if (amount > thLast) {
                    thLast = amount
                    if (critNow) {
                        dbgCritPred++
                    }
                }
            }

            spoofTicks = SPOOF_KEEP_TICKS
            afterSwap(mc.thePlayer!!.heldItem !== held, true)
            if (abDebug) {
                chat(
                    String.format(
                        "AB tick t=%d %s %s pred=%.2f last=%.2f attr=%.2f crit=%s/%s hurt=%d",
                        preTick,
                        if (self) "self" else "ka",
                        if (pick < 0) "stage" else if (fresh) "fresh" else "climb",
                        if (pick < 0) 0.0f else dbgPredicted,
                        thLast,
                        serverAttr,
                        critNow,
                        critNext,
                        target.hurtTime
                    )
                )
            }

            if (!self || pick >= 0) {
                ArmorBreakerServerModel.nextOwn = true
                ArmorBreakerTrace.label(
                    String.format(
                        "%s-%s-old %.2f %.2f",
                        if (self) "self" else "ka",
                        if (pick < 0) "reject" else if (fresh) "fresh" else "climb",
                        if (pick < 0) 0.0f else dbgPredicted,
                        thLast
                    )
                )
            }

            return pick >= 0
        }
    }

    // ------------------------------------------------------------------
    // Tick Hit Planner
    // ------------------------------------------------------------------
    private fun loadPlanner(ladderArr: ArmorBreakerLadder, target: EntityLivingBase, attr: Float, critNow: Boolean, critNext: Boolean, pending: Boolean) {
        loadPlanner(ladderArr, target, attr, critNow, critNext, pending, false)
    }

    private fun loadPlanner(ladderArr: ArmorBreakerLadder, target: EntityLivingBase, attr: Float, critNow: Boolean, critNext: Boolean, pending: Boolean, stage: Boolean) {
        val player = mc.thePlayer!!
        planner.clear()
        for (i in 0 until ladderArr.size) {
            val stack = player.inventory.getStackInSlot(ladderArr.slots[i])
            planner.add(
                ArmorBreakerServerModel.withPotions(player, ArmorBreakerLadder.itemAttr(stack)),
                ArmorBreakerServerModel.predictAmount(0.0f, false, stack, target),
                stack != null && !isAxe(stack)
            )
        }

        planner.interval = abBurstInterval.toInt()
        planner.hitGap = if (abTickPlanner) abServerHitGap.toInt() else 1
        planner.edgeRisk = abEdgeRisk / 100.0
        planner.critNow = critNow
        planner.critNext = critNext
        val frozen = attrFrozen()
        if (frozen) {
            val crit = ArmorBreakerServerModel.predictCrit(player)
            for (i in 0 until planner.horizon) {
                planner.critPlan[i] = crit
            }

            planner.critPlanLen = planner.horizon
        } else {
            planner.critPlanLen = if (abCritForecast) {
                ArmorBreakerServerModel.forecastCrit(player, pending, critNow, planner.critPlan, planner.horizon, abKnockbackForecast, if (stage) 1 else 0)
            } else {
                0
            }
            if (ArmorBreakerTrace.enabled && planner.critPlanLen > 0) {
                ArmorBreakerServerModel.forecastCrit(player, pending, critNow, traceNoKb, planner.horizon, false, if (stage) 1 else 0)
                val nextKb = if (abKnockbackForecast) ArmorBreakerServerModel.nextKnockback(player.ticksExisted) else -1
                ArmorBreakerTrace.forecast(
                    preTick,
                    if (stage) 's' else if (pending) 'm' else 'h',
                    planner.critPlan,
                    traceNoKb,
                    planner.critPlanLen,
                    if (nextKb < 0) -1 else nextKb - player.ticksExisted
                )
            }
        }

        planner.allowSkip = !frozen && abCritForecast && abAlignCrits
        planner.frozen = frozen
        // moonlight: AutoGapple.attrRefreshMax()/canRefreshAttr() —— 由 Gapple 的 Attr Refresh 设置驱动
        val refreshMax = Gapple.attrRefreshMax()
        planner.refreshMax = refreshMax
        if (refreshCycle != thFreshTick) {
            refreshCycle = thFreshTick
            refreshUsed = 0
        }

        planner.refreshLeft = if (Gapple.canRefreshAttr() && refreshUsed < refreshMax) refreshMax - refreshUsed else 0
        planner.blocking = target is EntityPlayer && target.isBlocking
        planner.prepare(attr, thLast)
    }

    private fun planDecide(ladderArr: ArmorBreakerLadder, target: EntityLivingBase, self: Boolean): Boolean {
        val player = mc.thePlayer!!
        val diff = preTick - thFreshTick
        val fresh = diff >= abBurstInterval.toInt()
        val critNow = ArmorBreakerServerModel.predictCrit(player)
        val critNext = ArmorBreakerServerModel.predictNextCrit(player, self)
        val serverAttr = ArmorBreakerServerModel.serverAttr(player)
        loadPlanner(ladderArr, target, serverAttr, critNow, critNext, self)
        val pick = planner.pickHit(ladderIndexOfHeld(ladderArr), thLast, diff, preTick - thAcceptTick, !self)
        planSkip = !self && planner.skip
        if (planSkip) {
            if (abDebug) {
                chat(String.format("AB tick t=%d ka skip (align crits) attr=%.2f crit=%s", preTick, serverAttr, critNow))
            }

            return false
        }

        val held = player.heldItem
        val accepted = pick >= 0 && planner.accepted
        thClimbHit = accepted && !fresh
        if (pick >= 0) {
            rotateClick(ladderArr.slots[pick])
            dbgPredicted = planner.predicted
            thRecordHit(planner.predicted)
            if (accepted) {
                thLast = planner.predicted
                thAcceptTick = preTick
                if (critNow) {
                    dbgCritPred++
                }

                if (fresh) {
                    thFreshTick = preTick
                    thConfirmed = false
                }
            }
        }

        if (mc.thePlayer!!.heldItem !== held) {
            spoofTicks = SPOOF_KEEP_TICKS
            afterSwap(true, true)
        }

        if (abDebug && (pick >= 0 || !self)) {
            chat(
                String.format(
                    "AB tick t=%d %s %s pred=%.2f last=%.2f attr=%.2f crit=%s/%s hurt=%d",
                    preTick,
                    if (self) "self" else "ka",
                    if (!accepted) "reject" else if (fresh) "fresh" else "climb",
                    if (pick < 0) 0.0f else planner.predicted,
                    thLast,
                    serverAttr,
                    critNow,
                    critNext,
                    target.hurtTime
                )
            )
        }

        if (!self || accepted) {
            ArmorBreakerServerModel.nextOwn = true
            ArmorBreakerTrace.label(
                String.format(
                    "%s-%s-plan %.2f %.2f",
                    if (self) "self" else "ka",
                    if (!accepted) "reject" else if (fresh) "fresh" else "climb",
                    if (pick < 0) 0.0f else planner.predicted,
                    thLast
                )
            )
        }

        return accepted
    }

    private fun planStage(target: EntityLivingBase) {
        val player = mc.thePlayer ?: return
        if (attrFrozen()) {
            // moonlight: planRefresh —— Gapple 冻结 attr 期间不排武器计划，回退 axe 收刀流程
            tickHitAxeBack(target)
        } else if (player.openContainer === player.inventoryContainer && KillAura.state && KillAura.target === target) {
            val ladderArr = getSwordArr()
            if (gappleSwordOnly()) {
                ladderArr.removeAxes(player.inventory)
            }

            if (ladderArr.size != 0) {
                val critNext = ArmorBreakerServerModel.predictNextCrit(player, true)
                loadPlanner(ladderArr, target, ArmorBreakerServerModel.serverAttr(player), critNext, critNext, false, true)
                val pick = planner.pickStage(
                    ladderIndexOfHeld(ladderArr),
                    thLast,
                    preTick + 1 - thFreshTick,
                    preTick + 1 - thAcceptTick
                )
                if (pick < 0) {
                    tickHitAxeBack(target)
                } else {
                    val held = player.heldItem
                    rotateClick(ladderArr.slots[pick])
                    if (planner.refresh && Gapple.refreshAttr()) {
                        refreshUsed++
                    }

                    if (mc.thePlayer!!.heldItem !== held) {
                        spoofTicks = SPOOF_KEEP_TICKS
                        afterSwap(true, false)
                    }
                }
            }
        } else {
            tickHitAxeBack(target)
        }
    }

    // ------------------------------------------------------------------
    // 老式 tick hit（无 planner）
    // ------------------------------------------------------------------
    private fun tickHitStage(ladderArr: ArmorBreakerLadder, target: EntityLivingBase, critNext: Boolean) {
        val player = mc.thePlayer ?: return
        if (attrFrozen()) {
            if (isAxe(player.heldItem)) {
                var pick = -1
                var best = 0.0f
                for (i in 0 until ladderArr.size) {
                    val stack = player.inventory.getStackInSlot(ladderArr.slots[i])
                    if (stack != null && !isAxe(stack) && (pick < 0 || ArmorBreakerLadder.itemAttr(stack) > best)) {
                        pick = i
                        best = ArmorBreakerLadder.itemAttr(stack)
                    }
                }

                if (pick >= 0) {
                    rotateClick(ladderArr.slots[pick])
                }
            }
        } else {
            val fresh = preTick + 1 - thFreshTick >= abBurstInterval.toInt()
            var pick = -1
            var bestAmount = 0.0f
            var bestAttr = 0.0f
            if (!fresh) {
                for (i in 0 until ladderArr.size) {
                    val attacker = player.inventory.getStackInSlot(ladderArr.slots[i])
                    if (!isAxe(attacker)) {
                        val attr = ArmorBreakerServerModel.withPotions(player, ArmorBreakerLadder.itemAttr(attacker))
                        for (j in 0 until ladderArr.size) {
                            val stack = player.inventory.getStackInSlot(ladderArr.slots[j])
                            if (stack != null) {
                                val amount = ArmorBreakerServerModel.predictAmount(attr, critNext, stack, target)
                                if (amount > thLast && (pick < 0 || amount < bestAmount || amount == bestAmount && attr > bestAttr)) {
                                    pick = i
                                    bestAmount = amount
                                    bestAttr = attr
                                }
                            }
                        }
                    }
                }
            }

            if (pick < 0) {
                pick = lowestSwordAttr(ladderArr)
            }

            val held = player.heldItem
            if (pick >= 0
                && (isAxe(held) || ArmorBreakerLadder.itemAttr(player.inventory.getStackInSlot(ladderArr.slots[pick])) != ArmorBreakerLadder.itemAttr(held))
            ) {
                rotateClick(ladderArr.slots[pick])
            }
        }
    }

    private fun tickHitAxeBack(target: EntityLivingBase) {
        val player = mc.thePlayer ?: return
        if (isAxe(player.heldItem) && player.openContainer === player.inventoryContainer) {
            val ladderArr = getSwordArr()
            val held = player.heldItem
            tickHitStage(ladderArr, target, ArmorBreakerServerModel.predictNextCrit(player, true))
            if (mc.thePlayer!!.heldItem !== held) {
                spoofTicks = SPOOF_KEEP_TICKS
                afterSwap(true, false)
            }
        }
    }

    private fun thPick(ladderArr: ArmorBreakerLadder, attr: Float, critNow: Boolean, critNext: Boolean, target: EntityLivingBase, threshold: Float): Int {
        val pick = thPick(ladderArr, attr, critNow, critNext, target, threshold, false)
        return if (pick >= 0) pick else thPick(ladderArr, attr, critNow, critNext, target, threshold, true)
    }

    private fun thPick(ladderArr: ArmorBreakerLadder, attr: Float, critNow: Boolean, critNext: Boolean, target: EntityLivingBase, threshold: Float, allowAxe: Boolean): Int {
        val player = mc.thePlayer!!
        var pick = -1
        var bestAmount = 0.0f
        var bestNext = 0.0f
        var bestAttr = 0.0f
        val frozen = attrFrozen()

        for (i in 0 until ladderArr.size) {
            val stack = player.inventory.getStackInSlot(ladderArr.slots[i])
            if (stack != null && (allowAxe || !isAxe(stack))) {
                val amount = ArmorBreakerServerModel.predictAmount(attr, critNow, stack, target)
                if (amount > threshold) {
                    val stackAttr = ArmorBreakerLadder.itemAttr(stack)
                    if (pick < 0 || !(amount > bestAmount)) {
                        val nextMin = if (frozen) {
                            thNextMin(ladderArr, attr, critNow, amount, target)
                        } else {
                            thNextMin(ladderArr, ArmorBreakerServerModel.withPotions(player, stackAttr), critNext, amount, target)
                        }
                        if (pick < 0 || amount < bestAmount || nextMin < bestNext || nextMin == bestNext && stackAttr > bestAttr) {
                            pick = i
                            bestAmount = amount
                            bestNext = nextMin
                            bestAttr = stackAttr
                        }
                    }
                }
            }
        }

        if (pick >= 0) {
            dbgPredicted = bestAmount
        }

        return pick
    }

    private fun thNextMin(ladderArr: ArmorBreakerLadder, attr: Float, crit: Boolean, above: Float, target: EntityLivingBase): Float {
        val player = mc.thePlayer!!
        var min = Float.POSITIVE_INFINITY
        for (i in 0 until ladderArr.size) {
            val stack = player.inventory.getStackInSlot(ladderArr.slots[i])
            if (stack != null) {
                val amount = ArmorBreakerServerModel.predictAmount(attr, crit, stack, target)
                if (amount > above && amount < min) {
                    min = amount
                }
            }
        }

        return min
    }

    // ------------------------------------------------------------------
    // Smart Rung
    // ------------------------------------------------------------------
    private fun smartRotate(ladderArr: ArmorBreakerLadder, target: Entity?, useBlink: Boolean) {
        val player = mc.thePlayer ?: return
        if (target !== smartTarget) {
            smartTarget = target
            selectCount = 0
        }

        val living = target as? EntityLivingBase
        val critNow = ArmorBreakerServerModel.predictCrit(player)
        val serverAttr = ArmorBreakerServerModel.serverAttr(player)
        val fresh = selectCount == 0
        val eating = Gapple.state && Gapple.isEating // moonlight 还要求 !shouldQueueWeaponSwap，AirPlus 无对应 API
        var pick = if (eating && fresh) {
            frozenFreshPick(ladderArr, serverAttr, critNow, living)
        } else {
            smartPick(ladderArr, serverAttr, critNow, living, if (fresh) Float.NEGATIVE_INFINITY else smartLast)
        }

        if (pick >= 0) {
            smartLast = dbgPredicted
            selectCount++
            dbgAction = if (fresh) "fresh" else "climb"
        } else if (hurtAge > 9.0f - abEarlySwitchTick) {
            pick = smartPick(ladderArr, serverAttr, critNow, living, Float.NEGATIVE_INFINITY)
            dbgAction = "wrap"
        } else {
            pick = lowestSwordAttr(ladderArr)
            val held = player.heldItem
            if (pick >= 0
                && !isAxe(held)
                && (eating || ArmorBreakerLadder.itemAttr(player.inventory.getStackInSlot(ladderArr.slots[pick])) == ArmorBreakerLadder.itemAttr(held))
            ) {
                pick = -1
            }

            dbgAction = "park"
        }

        dbgCrit = critNow
        if (pick >= 0) {
            val slotIndex = ladderArr.slots[pick]
            if (useBlink && player.inventory.getStackInSlot(slotIndex)?.item is ItemAxe && dbgAction != "park") {
                ownBlinkActive = true
                ownBlink = true
            }

            rotateClick(slotIndex)
        }
    }

    private fun smartPick(ladderArr: ArmorBreakerLadder, attr: Float, crit: Boolean, target: EntityLivingBase?, threshold: Float): Int {
        val player = mc.thePlayer!!
        var pick = -1
        var bestAmount = 0.0f
        var bestAttr = 0.0f
        for (i in 0 until ladderArr.size) {
            val stack = player.inventory.getStackInSlot(ladderArr.slots[i])
            if (stack != null) {
                val amount = ArmorBreakerServerModel.predictAmount(attr, crit, stack, target)
                if (amount > threshold) {
                    val stackAttr = ArmorBreakerLadder.itemAttr(stack)
                    if (pick < 0 || amount < bestAmount || amount == bestAmount && stackAttr > bestAttr) {
                        pick = i
                        bestAmount = amount
                        bestAttr = stackAttr
                    }
                }
            }
        }

        if (pick >= 0) {
            dbgPredicted = bestAmount
        }

        return pick
    }

    private fun frozenFreshPick(ladderArr: ArmorBreakerLadder, attr: Float, crit: Boolean, target: EntityLivingBase?): Int {
        val player = mc.thePlayer!!
        val amounts = FloatArray(ladderArr.size)
        var count = 0
        for (i in 0 until ladderArr.size) {
            val stack = player.inventory.getStackInSlot(ladderArr.slots[i])
            if (stack != null) {
                amounts[count++] = ArmorBreakerServerModel.predictAmount(attr, crit, stack, target)
            }
        }

        Arrays.sort(amounts, 0, count)
        var pick = -1
        var bestTotal = -1
        var bestAmount = 0.0f
        for (i in 0 until ladderArr.size) {
            val stack = player.inventory.getStackInSlot(ladderArr.slots[i])
            if (stack != null) {
                val amount = ArmorBreakerServerModel.predictAmount(attr, crit, stack, target)
                var total = Math.max(1, (amount / 4.0f).toInt())
                var carry = amount
                for (j in 0 until count) {
                    if (amounts[j] > carry) {
                        total += Math.max(1, ((amounts[j] - carry) / 4.0f).toInt())
                        carry = amounts[j]
                    }
                }

                if (pick < 0
                    || total > bestTotal
                    || total == bestTotal && (amount > bestAmount || amount == bestAmount && isAxe(player.inventory.getStackInSlot(ladderArr.slots[pick])) && !isAxe(stack))
                ) {
                    pick = i
                    bestTotal = total
                    bestAmount = amount
                }
            }
        }

        if (pick >= 0) {
            dbgPredicted = bestAmount
        }

        return pick
    }

    private fun smartAxeBack() {
        val player = mc.thePlayer ?: return
        if (lastAttackTick == preTick
            && !usePre()
            && abSmartRung
            && isAxe(player.heldItem)
            && player.openContainer === player.inventoryContainer
            && (!useTickHit() || !tickHitReady(true))
        ) {
            val ladderArr = getSwordArr()
            if (isLadderWeapon(player.heldItem, false)) {
                val pick = lowestSwordAttr(ladderArr)
                if (pick >= 0) {
                    val held = player.heldItem
                    rotateClick(ladderArr.slots[pick])
                    if (mc.thePlayer!!.heldItem !== held) {
                        spoofTicks = SPOOF_KEEP_TICKS
                        afterSwap(true, false)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Max Axe Hold
    // ------------------------------------------------------------------
    private fun limitAxeHold() {
        val player = mc.thePlayer ?: return
        val maxHold = abMaxAxeHoldTicks.toInt()
        val held = player.heldItem
        if (maxHold > 0 && held != null && held.item is ItemAxe) {
            if (axeSince < 0) {
                axeSince = preTick
            }

            if (preTick - axeSince + 1 > maxHold) {
                val active = preTick - lastAttackTick <= PRE_IDLE_TICKS || KillAura.state && KillAura.target != null
                if (player.openContainer === player.inventoryContainer && (active || !mc.playerController.getIsHittingBlock())) {
                    if ((active || holdingSwapped()) && !autoToolDigging()) {
                        val ladderArr = getSwordArr()
                        if (!isLadderWeapon(held, false)) {
                            axeSince = -1
                        } else {
                            val pick = firstNonAxe(ladderArr)
                            if (pick >= 0) {
                                flushOwnBlink()
                                swapClick(slot(ladderArr.slots[pick]))
                                afterSwap(mc.thePlayer!!.heldItem !== held, false)
                                val newHeld = mc.thePlayer!!.heldItem
                                if (newHeld != null && newHeld.item is ItemAxe) {
                                    axeSince = preTick
                                } else {
                                    axeSince = -1
                                    axeHoldLock = true
                                    spoofTicks = SPOOF_KEEP_TICKS
                                    // moonlight: Spoof 模式下 setVisualOverride(bestSword) —— 纯视觉，跳过
                                }
                            }
                        }
                    } else {
                        axeSince = -1
                    }
                }
            }
        } else {
            axeSince = -1
        }
    }

    private fun autoToolDigging(): Boolean {
        // moonlight: AutoTool.wasDigging —— AirPlus AutoTool 无挖掘状态标志，恒为 false
        return false
    }

    // ------------------------------------------------------------------
    // Burst
    // ------------------------------------------------------------------
    private fun tryBurst(target: EntityLivingBase): Boolean {
        val player = mc.thePlayer ?: return false
        if (player.openContainer === player.inventoryContainer
            && !ownBlinkActive && !BlinkUtils.isBlinking
            && (!Gapple.state || !Gapple.isEating)
            && (!player.isUsingItem || player.heldItem != null && player.heldItem!!.item is ItemSword)
        ) {
            val held = player.heldItem
            if (held != null && held.item !is ItemSword && held.item !is ItemAxe) {
                return false
            }

            if (target !== burster.target) {
                burster.reset()
                burster.target = target
                calib.dropCycle()
            }

            val interval = abBurstInterval.toInt()
            val fresh = preTick - burster.freshTick >= interval
            if (fresh) {
                burstArm = 0 // moonlight 原实现固定使用 Position 刷新方式
            }

            val crit = ArmorBreakerServerModel.predictCrit(player)
            burster.spoofCrit = !player.isOnLadder
                && !player.isInWater
                && !player.isPotionActive(Potion.blindness)
                && player.ridingEntity == null
            var wireAttr = Float.NaN
            if (burstArm == ArmorBreakerCalib.NONE) {
                wireAttr = ArmorBreakerServerModel.wireAttr()
                if (wireAttr.isNaN()) {
                    wireAttr = ArmorBreakerLadder.itemAttr(held)
                }
            }

            val hits = burster.plan(player, target, crit, fresh, if (fresh) 0.0f else burster.last, wireAttr)
            if (hits > 0) {
                burster.execute(target, burstArm)
                if (fresh && abDebug) {
                    verifyPlanned = burster.plannedArmor
                    verifyBase = armorDamage(target)
                    verifyTick = preTick
                }

                if (fresh) {
                    val summary = calib.onFresh(target, burstArm, burster.plannedArmor, preTick, interval + 8)
                    if (summary != null && abDebug) {
                        chat(summary)
                    }

                    burster.freshTick = preTick
                    burstConfirmed = false
                } else {
                    calib.onMid(target, burster.plannedArmor)
                }

                spoofTicks = SPOOF_KEEP_TICKS
                afterSwap(mc.thePlayer!!.heldItem !== held, true)
            }

            if (abDebug) {
                chat(
                    String.format(
                        "AB burst t=%d %s %s crit=%s hits=%d armor/piece=%d last=%.2f hurt=%d",
                        preTick,
                        if (fresh) "fresh" else "mid",
                        ArmorBreakerCalib.NAMES[burstArm],
                        crit,
                        hits,
                        if (hits > 0) burster.plannedArmor else 0,
                        burster.last,
                        target.hurtTime
                    )
                )
            }

            return true
        } else {
            return false
        }
    }

    private fun burstTick() {
        val target = burster.target ?: return
        if (target.hurtTime > lastTargetHurt) {
            burstConfirmed = true
        }

        lastTargetHurt = target.hurtTime
        burstVerify(target)
        val diff = preTick - burster.freshTick
        val delay = Math.max(0, ping())
        if (!burstConfirmed && diff > 2 + delay / 50 && diff < 10) {
            burster.freshTick = -1000
            burster.last = 0.0f
            if (abDebug) {
                chat("AB burst fresh not confirmed -> retry")
            }
        }

        if (target.isDead || target.worldObj !== mc.theWorld || preTick - lastAttackTick > PRE_IDLE_TICKS) {
            burster.reset()
            calib.dropCycle()
        }
    }

    private fun burstVerify(target: EntityLivingBase) {
        if (verifyBase >= 0 && preTick - verifyTick >= 3 + Math.max(0, ping()) / 50) {
            val damage = armorDamage(target)
            if (damage >= 0) {
                val keep = ArmorBreakerCalib.keepSum(target)
                var pieces = 0
                for (slot in 1..4) {
                    val stack = target.getEquipmentInSlot(slot)
                    if (stack != null && stack.isItemStackDamageable) {
                        pieces++
                    }
                }

                chat(
                    String.format(
                        "AB burst verify planned=%d expected=%.1f actual=%d (%s)",
                        verifyPlanned,
                        if (pieces == 0) 0.0 else verifyPlanned * keep / pieces,
                        damage - verifyBase,
                        ArmorBreakerCalib.NAMES[burstArm]
                    )
                )
            }

            verifyBase = -1
        }
    }

    private fun armorDamage(target: EntityLivingBase): Int {
        var damage = -1
        for (slot in 1..4) {
            val stack = target.getEquipmentInSlot(slot)
            if (stack != null && stack.isItemStackDamageable) {
                damage = Math.max(damage, stack.itemDamage)
            }
        }

        return damage
    }

    // ------------------------------------------------------------------
    // 调试
    // ------------------------------------------------------------------
    private fun debugCritTick() {
        if (abDebug && preTick - dbgCritTick >= 40) {
            dbgCritTick = preTick
            val serverHits = dbgCritServer
            if (dbgCritPred > 0 || serverHits > 0) {
                // moonlight 还包含 ArmorBreakerGrimReach.dbgCancel/dbgAttacks/dbgMiss —— 该组件依赖 KillAura
                // 攻击回调，AirPlus 无对应 hook，跳过
                chat(
                    String.format(
                        "AB crit check 2s: predicted=%d server=%d kb=%d/%d block fix=%d%s",
                        dbgCritPred,
                        serverHits,
                        ArmorBreakerServerModel.dbgKbOnTime,
                        ArmorBreakerServerModel.dbgKb,
                        dbgBlockFix,
                        if (dbgSelfReason == null) "" else " | self idle: " + dbgSelfReason
                    )
                )
            }

            dbgBlockFix = 0
            ArmorBreakerServerModel.dbgKb = 0
            ArmorBreakerServerModel.dbgKbOnTime = 0
            dbgCritPred = 0
            dbgCritServer = 0
            dbgSelfReason = null
        }
    }

    private fun debugArmorTick() {
        if (!abDebug) {
            dbgTarget = null
        } else {
            val player = mc.thePlayer ?: return
            val candidate = lastTarget as? EntityLivingBase
            val target = if (candidate != null && !candidate.isDead && candidate.worldObj === mc.theWorld) candidate else null
            if (target != null && preTick - lastAttackTick <= PRE_IDLE_TICKS) {
                var totalDamage = 0
                var pieces = 0
                for (slot in 1..4) {
                    val stack = target.getEquipmentInSlot(slot)
                    if (stack != null && stack.isItemStackDamageable) {
                        totalDamage += stack.itemDamage
                        pieces++
                    }
                }

                if (target === dbgTarget && pieces == dbgPieces && totalDamage >= dbgBaseDamage) {
                    val elapsed = preTick - dbgStartTick
                    if (elapsed > 0 && elapsed % 40 == 0 && pieces > 0) {
                        val rate = (totalDamage - dbgBaseDamage).toFloat() / pieces / (elapsed / 20.0f)
                        chat(String.format("AB armor %.2f dur/s/piece over %.1fs, %d attacks", rate, elapsed / 20.0f, dbgAttacks))
                    }
                } else {
                    dbgTarget = target
                    dbgPieces = pieces
                    dbgBaseDamage = totalDamage
                    dbgStartTick = preTick
                    dbgAttacks = 0
                }
            } else {
                dbgTarget = null
            }
        }
    }

    private fun debugAttack(target: Entity?, kind: String, rung: Int) {
        if (abDebug) {
            dbgAttacks++
            val held = mc.thePlayer!!.heldItem
            val hurt = if (target is EntityLivingBase) target.hurtTime else -1
            chat(
                String.format(
                    "AB t=%d gap=%d %s rung=%d/%d held=%s hurt=%d ping=%d",
                    preTick,
                    lastAttackGap,
                    kind,
                    rung,
                    ladder.size,
                    if (held == null) "-" else held.displayName + "+" + EnchantmentHelper.getEnchantmentLevel(Enchantment.sharpness.effectId, held),
                    hurt,
                    ping()
                )
                    + if (kind.endsWith("+Smart")) String.format(
                    " %s pred=%.2f last=%.2f crit=%s attr=%.2f",
                    dbgAction,
                    dbgPredicted,
                    smartLast,
                    dbgCrit,
                    ArmorBreakerServerModel.serverAttr(mc.thePlayer!!)
                ) else ""
            )
        }
    }

    private fun debugReason(reason: String) {
        if (abDebug && reason != dbgReason) {
            dbgReason = reason
            chat("ArmorBreaker idle: $reason")
        }
    }

    // ------------------------------------------------------------------
    // 开关清理（由 ArmorBreak.onDisable 调用）
    // ------------------------------------------------------------------
    fun onModeDisabled() {
        hurtAge = 0
        selectCount = 0
        classicTarget = null
        classicFreshTick = -1000
        gapEma = 0.0f
        dbgTarget = null
        lastTarget = null
        preTarget = null
        axeSince = -1
        axeHoldLock = false
        ownBlink = false
        spoofTicks = 0
        smartTarget = null
        smartLast = 0.0f
        burster.reset()
        calib.reset()
        burstConfirmed = false
        lastTargetHurt = 0
        burstWarned = false
        if (mc.thePlayer != null) {
            climbKbRestore()
        }

        kbStopped = false
        thReset()
        swappedSlot = -1
        swappedItem = null
        // 清空自有 blink 队列，避免丢包（moonlight 的 BlinkComponent 由全局 dispatch 兜底）
        blinkDispatch = false
        ownBlinkActive = false
        val iter = blinkPackets.iterator()
        while (iter.hasNext()) {
            sendPacket(iter.next(), triggerEvent = false)
            iter.remove()
        }
        ArmorBreakerTrace.enabled = false
        ArmorBreakerTrace.targetId = -1
        ArmorBreakerTrace.close()
    }

    private fun isAxe(stack: ItemStack?): Boolean = stack != null && stack.item is ItemAxe
}
