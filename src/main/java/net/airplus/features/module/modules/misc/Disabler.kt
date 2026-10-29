/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.misc

import net.airplus.AirPlus.hud
import net.airplus.event.*
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.ui.client.hud.element.elements.Notification
import net.airplus.utils.client.PacketUtils.sendPacket
import net.airplus.utils.client.chat
import net.airplus.utils.extensions.airTicks
import net.airplus.utils.extensions.isInLiquid
import net.airplus.utils.extensions.isMoving
import net.airplus.utils.extensions.tryJump
import net.airplus.utils.inventory.InventoryUtils
import net.airplus.utils.timing.MSTimer
import net.airplus.utils.timing.TimeUtils
import io.netty.buffer.Unpooled
import net.minecraft.block.Block
import net.minecraft.item.ItemSword
import net.minecraft.network.PacketBuffer
import net.minecraft.network.play.client.C07PacketPlayerDigging.Action.RELEASE_USE_ITEM
import net.minecraft.network.play.client.C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK
import net.minecraft.client.gui.inventory.GuiInventory
import net.minecraft.init.Items
import net.minecraft.item.ItemStack
import net.minecraft.network.Packet
import net.minecraft.network.play.INetHandlerPlayClient
import net.minecraft.network.play.client.*
import net.minecraft.network.play.client.C03PacketPlayer.*
import net.minecraft.network.play.server.S07PacketRespawn
import net.minecraft.network.play.server.S08PacketPlayerPosLook
import net.minecraft.network.play.server.S32PacketConfirmTransaction
import net.minecraft.potion.Potion
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.sqrt

object Disabler : Module("Disabler", Category.MISC) {

    // OldGrim: 启用后应用并展示原 Disabler2（SilenceFix 移植）的功能
    val oldGrim by boolean("OldGrim", false)

    private val badPacketsA by boolean("BadPacketsA", true) { oldGrim }
    val badPacketH by boolean("BadPacketH", false) { oldGrim }
    private val badPacketsF by boolean("BadPacketsF", true) { oldGrim }
    private val blockValue by boolean("CancelBlockingPacket", false) { oldGrim }
    private val digValue by boolean("Digging", true) { oldGrim }
    private val oldPostValue by boolean("OldPost", false) { oldGrim }
    private val fastBreak by boolean("FastBreak", true) { oldGrim }
    private val fakePingValue by boolean("FakePing", false) { oldGrim }
    private val grimDebug by boolean("GrimDebug", true) { oldGrim }

    val startSprint by boolean("StartSprint", true)
    private val grimPlace by boolean("GrimPlace", false)

    private val vulcanScaffold by boolean("VulcanScaffold", false)
    private val vulcanPacketTick by int("VulcanScaffoldPacketTick", 15, 1..20) { vulcanScaffold }

    val verusFly by boolean("VerusFly", false)
    val verusCombat by boolean("VerusCombat", false)
    val onlyCombat by boolean("OnlyCombat", true) { verusCombat }

    private val intaveFly by boolean("intaveFly", false)
    private var shouldDelay = false
    private val packets = LinkedBlockingQueue<Packet<INetHandlerPlayClient>>()

    private val noRotationDisabler by boolean("NoRotationDisabler", false)
    private val modifyMode by choices(
        "Mode",
        arrayOf("ConvertNull", "Spoof", "Zero", "SpoofZero", "Negative", "OffsetYaw", "Invalid"),
        "NoRotationDisabler"
    ) { noRotationDisabler }
    private val offsetAmount by float("OffsetAmount", 6f, -180f..180f) { noRotationDisabler }

    private val basicDisabler by boolean("BasicDisabler", false)
    private val cancelC00 by boolean("CancelC00", true) { basicDisabler }
    private val cancelC0F by boolean("CancelC0F", true) { basicDisabler }
    private val cancelC0A by boolean("CancelC0A", true) { basicDisabler }
    private val cancelC0B by boolean("CancelC0B", true) { basicDisabler }
    private val cancelC07 by boolean("CancelC07", true) { basicDisabler }
    private val cancelC13 by boolean("CancelC13", true) { basicDisabler }
    private val cancelC03 by boolean("CancelC03", true) { basicDisabler }
    private val c03NoMove by boolean("C03-NoMove", true) { basicDisabler }

    private val watchdogMotion by boolean("WatchdogMotion", false)
    private val notWhenStarAvailable by boolean("NotWithStar", true) { watchdogMotion }

    private val watchdogInventory by boolean("WatchdogInventory", false)
    private var c16 = false
    private var c0d = false

    val spigotSpam by boolean("SpigotSpam", false)
    val message by text("Message", "/skill") { spigotSpam }

    // OldGrim (原 Disabler2) 状态
    private val grimPacketsMap = HashMap<Packet<*>, Long>()
    private var grimLastSlot = -1
    private var grimLastSprinting = false
    private var grimC0a = false

    private val chatDebug by boolean("ChatDebug", false)
    private val notificationDebug by boolean("NotificationDebug", false)

    private var transaction = false
    var isOnCombat = false

    private var flags = 0
    private var execute = false
    private var jump = false

    private val hasStar
        get() = InventoryUtils.findItem(36, 44, Items.nether_star) != null

    private val betaVerus by boolean("VerusBeta", false)
    private val betaVerusSilentFlagApply by boolean("SilentFlagApply", false) { betaVerus }
    private val betaVerusBufferSize by int("BufferSize", 300, 0..1000) { betaVerus }
    private val betaVerusRepeatTimesValue by int("RepeatTimes", 1, 1..5) { betaVerus }
    private val betaVerusRepeatTimesFighting by int("BRepeatTimesFighting", 1, 1..5) { betaVerus }
    private val betaVerusFlagDelay by int("FlagDelay", 40, 35..60) { betaVerus }

    private var betaVerus2Stat = false
    private var betaVerusModified = false
    private val betaVerusPacketBuffer = ArrayDeque<Packet<INetHandlerPlayClient>>()
    private var betaVerusLagTimer = MSTimer()

    private val betaVerusRepeatTimes: Int
        get() = if (isOnCombat) betaVerusRepeatTimesFighting else betaVerusRepeatTimesValue

    private val verusExperimental by boolean("VerusExperimental", false)
    private val verusExpVoidTP by boolean("ExpVoidTP", false) { verusExperimental }
    private val verusExpVoidTPDelay by int("ExpVoidTPDelay", 1000, 0..30000) { verusExpVoidTP }
    private val miniblox by boolean("MinibloxDesync", false)
    private var lastVoidTP = 0L
    private var cancelNext = 0

    val onPacket = handler<PacketEvent> { event ->
        val player = mc.thePlayer ?: return@handler
        val packet = event.packet

        // OldGrim (原 Disabler2)
        if (oldGrim && event.eventType == EventState.SEND) {
            if (!player.isDead) {
                // BadPacketH: 攻击前必须先有挥动手臂包（补发 C0A）
                if (packet is C0APacketAnimation) {
                    grimC0a = true
                } else if (packet is C02PacketUseEntity && badPacketH) {
                    if (packet.action == C02PacketUseEntity.Action.ATTACK) {
                        if (!grimC0a) {
                            sendPacket(C0APacketAnimation(), false)
                        }
                        grimC0a = false
                    }
                }

                // BadPacketsF: 重复的疾跑状态包
                if (badPacketsF && packet is C0BPacketEntityAction) {
                    when (packet.action) {
                        C0BPacketEntityAction.Action.START_SPRINTING -> {
                            if (grimLastSprinting) {
                                event.cancelEvent()
                            }
                            grimLastSprinting = true
                        }

                        C0BPacketEntityAction.Action.STOP_SPRINTING -> {
                            if (!grimLastSprinting) {
                                event.cancelEvent()
                            }
                            grimLastSprinting = false
                        }

                        else -> {}
                    }
                }

                // OldPost: 攻击/交互包后附带一个无效的 C0F
                if (oldPostValue && mc.currentServerData != null &&
                    (packet is C0APacketAnimation || packet is C02PacketUseEntity || packet is C0EPacketClickWindow
                            || packet is C08PacketPlayerBlockPlacement || packet is C07PacketPlayerDigging)
                ) {
                    sendPacket(C0FPacketConfirmTransaction(114, 514.toShort(), true), false)
                }

                // FastBreak: STOP_DESTROY_BLOCK 前先发 ABORT_DESTROY_BLOCK
                if (fastBreak && packet is C07PacketPlayerDigging && packet.status == STOP_DESTROY_BLOCK) {
                    sendPacket(
                        C07PacketPlayerDigging(
                            C07PacketPlayerDigging.Action.ABORT_DESTROY_BLOCK,
                            packet.position,
                            packet.facing
                        ), false
                    )
                }

                // BadPacketsA: 相同槽位的 C09 重复包
                if (badPacketsA && packet is C09PacketHeldItemChange) {
                    val slot = packet.slotId
                    if (slot == grimLastSlot && slot != -1) {
                        event.cancelEvent()
                    }
                    grimLastSlot = packet.slotId
                }

                // FakePing: 延迟 KeepAlive / ClientStatus
                if (fakePingValue && (packet is C00PacketKeepAlive || packet is C16PacketClientStatus) &&
                    player.health > 0.0f && !grimPacketsMap.containsKey(packet)
                ) {
                    event.cancelEvent()
                    synchronized(grimPacketsMap) {
                        grimPacketsMap[packet] = System.currentTimeMillis() + TimeUtils.randomDelay(199999, 9999999)
                    }
                }

                // 取消持剑时的 "使用物品" 包
                if (blockValue && packet is C08PacketPlayerBlockPlacement) {
                    val heldItem = player.heldItem
                    if (heldItem != null && heldItem.item is ItemSword &&
                        (packet.position.x == -1 || packet.facingX == -1.0f) && packet.facingZ == -1.0f
                    ) {
                        if (grimDebug && player.ticksExisted % 10 == 0) {
                            debugMessage("Cancelled sword use packet")
                        }
                        event.cancelEvent()
                    }
                }

                // Digging: 取消持剑时的 RELEASE_USE_ITEM 并附带垃圾包
                if (digValue && packet is C07PacketPlayerDigging) {
                    val heldItem = player.heldItem
                    if (heldItem != null && heldItem.item is ItemSword && packet.status == RELEASE_USE_ITEM) {
                        if (grimDebug && player.ticksExisted % 10 == 0) {
                            debugMessage("Cancelled RELEASE_USE_ITEM")
                        }
                        event.cancelEvent()
                        sendPacket(
                            C0EPacketClickWindow(0, 36, 0, 2, ItemStack(Block.getBlockById(166)), 0.toShort()), false
                        )
                        sendPacket(C09PacketHeldItemChange(player.inventory.currentItem % 8 + 1), false)
                        sendPacket(C17PacketCustomPayload("test", PacketBuffer(Unpooled.buffer())), false)
                        sendPacket(C09PacketHeldItemChange(player.inventory.currentItem), false)
                    }
                }
            }
        }

        // Basic Disabler
        if (basicDisabler) {
            when (packet) {
                is C00PacketKeepAlive -> if (cancelC00) {
                    event.cancelEvent()
                    debugMessage("Cancelled C00-KeepAlive")
                }

                is C0FPacketConfirmTransaction -> if (cancelC0F) {
                    event.cancelEvent()
                    debugMessage("Cancelled C0F-Transaction")
                }

                is C0APacketAnimation -> if (cancelC0A) {
                    event.cancelEvent()
                    debugMessage("Cancelled C0A-Swing")
                }

                is C0BPacketEntityAction -> if (cancelC0B) {
                    event.cancelEvent()
                    debugMessage("Cancelled C0B-Action")
                }

                is C07PacketPlayerDigging -> if (cancelC07) {
                    event.cancelEvent()
                    debugMessage("Cancelled C07-Digging")
                }

                is C13PacketPlayerAbilities -> if (cancelC13) {
                    event.cancelEvent()
                    debugMessage("Cancelled C13-Abilities")
                }

                is C03PacketPlayer -> if (cancelC03 && !(packet is C04PacketPlayerPosition || packet is C05PacketPlayerLook || packet is C06PacketPlayerPosLook)) {
                    if (c03NoMove && player.isMoving) return@handler
                    event.cancelEvent()
                    debugMessage("Cancelled C03-Flying")
                }
            }
        }

        // NoRotationDisabler
        if (noRotationDisabler && packet is C03PacketPlayer) {
            when (modifyMode) {
                "ConvertNull" -> {
                    if (packet.isMoving) {
                        sendPacket(
                            C04PacketPlayerPosition(
                                packet.x,
                                packet.y,
                                packet.z,
                                packet.onGround
                            ), false
                        )
                    } else {
                        sendPacket(C03PacketPlayer(packet.onGround), false)
                    }
                    event.cancelEvent()
                }

                "Spoof" -> {
                    if (packet.getRotating()) {
                        packet.yaw = player.rotationYaw
                        packet.pitch = player.rotationPitch
                    }
                }

                "Zero" -> {
                    if (packet.getRotating()) {
                        packet.yaw = 0.0f
                        packet.pitch = 0.0f
                    }
                }

                "SpoofZero" -> {
                    if (packet.isMoving) {
                        sendPacket(
                            C06PacketPlayerPosLook(
                                packet.x,
                                packet.y,
                                packet.z,
                                0.0f,
                                0.0f,
                                packet.onGround
                            ), false
                        )
                    } else {
                        sendPacket(
                            C06PacketPlayerPosLook(
                                player.posX,
                                player.posY,
                                player.posZ,
                                0.0f,
                                0.0f,
                                packet.onGround
                            ), false
                        )
                    }
                    event.cancelEvent()
                }

                "Negative" -> {
                    if (packet.getRotating()) {
                        packet.yaw = -packet.yaw
                        packet.pitch = -packet.pitch
                    }
                }

                "OffsetYaw" -> {
                    if (packet.getRotating()) {
                        packet.yaw += offsetAmount
                    }
                }
            }
        }

        /**
         * Working on Hypixel (Watchdog)
         * Tested on: play.hypixel.net
         * Credit: @localp / Nextgen
         * Original: @billionaire
         */
        if (watchdogMotion) {
            when (packet) {
                is S07PacketRespawn -> {
                    flags = 0
                    execute = false
                    jump = true
                }

                is S08PacketPlayerPosLook -> {
                    if (++flags >= 20) {
                        execute = false
                        flags = 0
                    }
                }
            }
        }

        // Watchdog Inventory
        if (watchdogInventory) {
            if (packet is C16PacketClientStatus) {
                if (c16) {
                    event.cancelEvent()
                }
                c16 = true
            }
            if (packet is C0DPacketCloseWindow) {
                if (c0d) {
                    event.cancelEvent()
                }
                c0d = true
            }
        }

        // Grim Place
        if (grimPlace) {
            if (packet is C08PacketPlayerBlockPlacement && packet.placedBlockDirection in 0..5) {
                event.cancelEvent()
                sendPacket(
                    C08PacketPlayerBlockPlacement(
                        packet.position,
                        6 + packet.placedBlockDirection * 7,
                        packet.stack,
                        packet.placedBlockOffsetX,
                        packet.placedBlockOffsetY,
                        packet.placedBlockOffsetZ
                    ), false
                )
                debugMessage("§cModified §aPlace §cPacket§7.")
            }
        }

        // Intave Fly
        if (intaveFly) {
            if (packet is S08PacketPlayerPosLook) {
                if (player.capabilities.isFlying) {
                    shouldDelay = true
                    debugMessage("§cStarted Canceling IntaveFly")
                }
            }

            if (packet is S32PacketConfirmTransaction && shouldDelay) {
                event.cancelEvent()
                packets.add(packet as Packet<INetHandlerPlayClient>)
            }
        }

        // Verus Combat
        if (verusCombat) {
            if (player.ticksExisted <= 20) {
                isOnCombat = false
                return@handler
            }

            if (onlyCombat && !isOnCombat) {
                return@handler
            }

            /**
             * Works by just simply ignore transaction and not accepting them and send invalid one
             * to bypass Verus cancel transaction "patch" (not sending transaction for too long)
             * This could work on other anti-cheat that don't have a proper transaction handling system
             * But this will mostly be used on Verus, disabled any combat check related to transaction.
             *
             * Credit: @ghost / LB Nextgen
             */
            if (packet is S32PacketConfirmTransaction) {
                event.cancelEvent()
                sendPacket(
                    C0FPacketConfirmTransaction(
                        if (transaction) 1 else -1,
                        if (transaction) -1 else 1,
                        transaction
                    ), false
                )
                transaction = !transaction
            }

            isOnCombat = false
        }

        if (betaVerus) {
            if (packet is C0FPacketConfirmTransaction) {
                betaVerusPacketBuffer.add(packet as Packet<INetHandlerPlayClient>)
                event.cancelEvent()
                if (betaVerusPacketBuffer.size > betaVerusBufferSize) {
                    if (!betaVerus2Stat) {
                        betaVerus2Stat = true
                        hud.addNotification(Notification.informative(this, "AntiCheat is disabled.", 2000L))
                    }
                    val packeted = betaVerusPacketBuffer.poll()
                    repeat(betaVerusRepeatTimes) {
                        sendPacket(packeted, false)
                    }
                }
                debugMessage("Packet C0F IN BufferSize=${betaVerusPacketBuffer.size}")
            } else if (packet is C03PacketPlayer) {
                if (player.ticksExisted % betaVerusFlagDelay == 0 && player.ticksExisted > betaVerusFlagDelay + 1 && !betaVerusModified) {
                    debugMessage("Packet C03 -> BetaVerus Y offset")
                    betaVerusModified = true
                    packet.y -= 11.4514
                    packet.onGround = false
                }
            } else if (packet is S08PacketPlayerPosLook && betaVerusSilentFlagApply) {
                val x = packet.x - player.posX
                val y = packet.y - player.posY
                val z = packet.z - player.posZ
                val diff = sqrt(x * x + y * y + z * z)
                if (diff <= 8) {
                    event.cancelEvent()
                    debugMessage("Silent Flag")
                    sendPacket(
                        C06PacketPlayerPosLook(
                            packet.x,
                            packet.y,
                            packet.z,
                            packet.yaw,
                            packet.pitch,
                            true
                        ),
                        false
                    )
                }
            }
            if (player.ticksExisted <= 7) {
                betaVerusLagTimer.reset()
                betaVerusPacketBuffer.clear()
            }
        }

        // Verus Experimental
        if (verusExperimental) {
            if (verusExpVoidTP && packet is C03PacketPlayer) {
                if (player.ticksExisted > 20 && player.posY > -64) {
                    if (lastVoidTP + verusExpVoidTPDelay < System.currentTimeMillis()) {
                        lastVoidTP = System.currentTimeMillis()
                        sendPacket(
                            C04PacketPlayerPosition(
                                player.posX,
                                -48.0,
                                player.posZ,
                                true
                            ), false
                        )
                        sendPacket(
                            C04PacketPlayerPosition(
                                player.posX,
                                player.posY,
                                player.posZ,
                                false
                            ), false
                        )
                        sendPacket(
                            C04PacketPlayerPosition(
                                player.posX,
                                player.posY,
                                player.posZ,
                                player.onGround
                            ), false
                        )
                        cancelNext = 2
                        event.cancelEvent()
                        debugMessage("VerusExp VoidTP attempt")
                    }
                }
            } else if (verusExpVoidTP && packet is S08PacketPlayerPosLook && cancelNext > 0) {
                cancelNext--
                event.cancelEvent()
                debugMessage("VerusExp cancelled server position look")
            }
        }
        // fixes movement desyncs, we need to send C0CInput packets with correct inputs.
        if (miniblox && (packet is C04PacketPlayerPosition || packet is C05PacketPlayerLook)) {
            sendPacket(
                C0CPacketInput(
                    player.moveStrafing,
                    player.moveForward,
                    player.isJumping,
                    player.isSneaking
                )
            )
        }
    }

    val onJump = handler<JumpEvent> { event ->
        if (!watchdogMotion) return@handler

        if (event.eventState == EventState.POST) {
            if (!jump) return@handler
            jump = false
            execute = true
        }
    }

    val onMotion = handler<MotionEvent> { event ->
        val player = mc.thePlayer ?: return@handler
        if (!watchdogMotion) return@handler
        if (event.eventState != EventState.PRE) return@handler
        if (notWhenStarAvailable && hasStar) return@handler

        if (execute && player.airTicks >= 10) {
            if (player.airTicks % 2 == 0) {
                event.x += 0.095
            }
            player.setVelocity(0.0, 0.0, 0.0)
        }
    }

    val onUpdate = handler<UpdateEvent> {
        val player = mc.thePlayer ?: return@handler

        // OldGrim: FakePing 延迟发包
        if (oldGrim && fakePingValue) {
            try {
                synchronized(grimPacketsMap) {
                    val iterator = grimPacketsMap.entries.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        if (entry.value >= System.currentTimeMillis()) continue
                        sendPacket(entry.key)
                        iterator.remove()
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }

        // watchdogMotion jump
        if (watchdogMotion) {
            if (jump) {
                player.tryJump()
            }
        }

        // Watchdog Inventory
        if (watchdogInventory) {
            c16 = false
            c0d = false
            if (mc.currentScreen is GuiInventory) {
                if (player.ticksExisted % (if (player.isPotionActive(Potion.moveSpeed)) 3 else 4) == 0) {
                    sendPacket(C0DPacketCloseWindow(), false)
                } else if (player.ticksExisted % (if (player.isPotionActive(Potion.moveSpeed)) 3 else 4) == 1) {
                    sendPacket(C16PacketClientStatus(C16PacketClientStatus.EnumState.OPEN_INVENTORY_ACHIEVEMENT), false)
                }
            }
        }

        // Verus Fly
        if (verusFly) {
            if (!isOnCombat && !player.isDead) {
                val pos = player.position.add(0, if (player.posY > 0) -255 else 255, 0) ?: return@handler
                sendPacket(
                    C08PacketPlayerBlockPlacement(
                        pos,
                        256,
                        ItemStack(Items.water_bucket),
                        0F,
                        0.5F + Math.random().toFloat() * 0.44F,
                        0F
                    )
                )
            } else {
                isOnCombat = false
            }
        }

        // Vulcan Scaffold
        if (vulcanScaffold) {
            if (!(player.isInLiquid || player.isDead || player.isOnLadder)) {
                if (player.isMoving && player.ticksExisted % vulcanPacketTick == 0) {
                    sendPacket(C0BPacketEntityAction(player, C0BPacketEntityAction.Action.START_SNEAKING))
                    sendPacket(C0BPacketEntityAction(player, C0BPacketEntityAction.Action.STOP_SNEAKING))
                }
            }
        }

        if (betaVerus) {
            betaVerusModified = false
            if (betaVerusLagTimer.hasTimePassed(490L)) {
                betaVerusLagTimer.reset()
                if (betaVerusPacketBuffer.isNotEmpty()) {
                    val packet = betaVerusPacketBuffer.poll()
                    repeat(betaVerusRepeatTimes) {
                        sendPacket(packet, false)
                    }
                    debugMessage("Packet Buffer Dump")
                } else {
                    debugMessage("Empty Packet Buffer")
                }
            }
        }
    }

    val onAttack = handler<AttackEvent> {
        isOnCombat = true
    }

    val onWorld = handler<WorldEvent> {
        isOnCombat = false
        // OldGrim 状态重置
        grimLastSlot = -1
        grimLastSprinting = false
        synchronized(grimPacketsMap) {
            grimPacketsMap.clear()
        }
        if (betaVerus) {
            betaVerus2Stat = false
            betaVerusPacketBuffer.clear()
            betaVerusLagTimer.reset()
        }
    }

    private fun debugMessage(msg: String) {
        if (chatDebug) {
            chat("§f$msg")
        }
        if (notificationDebug) {
            hud.addNotification(Notification.informative(this, "§f$msg", 1000L))
        }
    }
}
