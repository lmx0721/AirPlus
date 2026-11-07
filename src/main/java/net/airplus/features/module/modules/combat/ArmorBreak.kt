/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Ported from Moonlight FluxArmorBreaker (Code By cubk).
 */
package net.airplus.features.module.modules.combat

import io.netty.buffer.Unpooled
import net.airplus.event.AttackEvent
import net.airplus.event.EventState
import net.airplus.event.MotionEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.modules.player.Gapple
import net.airplus.utils.client.PacketUtils.sendPacket
import net.minecraft.enchantment.Enchantment
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.entity.EntityLivingBase
import net.minecraft.item.Item
import net.minecraft.item.ItemAxe
import net.minecraft.item.ItemStack
import net.minecraft.item.ItemSword
import net.minecraft.network.PacketBuffer
import net.minecraft.network.play.client.C02PacketUseEntity
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement
import net.minecraft.network.play.client.C09PacketHeldItemChange
import net.minecraft.network.play.client.C0APacketAnimation
import net.minecraft.network.play.client.C17PacketCustomPayload
import net.minecraft.entity.ai.attributes.AttributeModifier
import com.google.common.collect.Multimap
import java.util.LinkedList

object ArmorBreak : Module("ArmorBreak", Category.COMBAT) {

    private val hurtTime by int("HurtTime", 4, 0..10)
    private val includeAxe by boolean("IncludeAxe", false)
    private val includeKBWeapon by boolean("IncludeKBWeapon", false)
    private val earlySwitchTick by int("EarlySwitchTick", 1, 0..9)
    private val c09HotbarSwitch by boolean("C09SwitchInHotbar", false)
    private val noGapple by boolean("NoAutoGapple", true)
    private val noisePayload by boolean("NoisePayload", true)
    private val noiseBlock by boolean("NoiseBlock", true)
    private val doubleHit by boolean("DoubleHit", false)
    private val doubleHitDelay by int("DoubleHitDelay", 1, 1..5)
    private val doubleHitSwitchAxe by boolean("DoubleHitSwitchAxe", true)
    private val forceBlock by boolean("ForceBlock", false)
    private val funMode by boolean("FunMode", false)

    override val tag: String?
        get() = "Flux"

    private var lastSwordDamage = -1.0
    private var switchEnd = false
    private var isHighestWeapon = false
    private var pendingBlock = false
    private var pendingDoubleHit = false
    private var earlyResetDone = false
    private var doubleHitTimer = 0
    private var funIndex = 0
    private var restoreSlot = -1
    private val usedSlots = HashSet<Int>()

    override fun onEnable() {
        pendingBlock = false
        pendingDoubleHit = false
        doubleHitTimer = 0
        funIndex = 0
        earlyResetDone = false
        restoreSlot = -1
        usedSlots.clear()
    }

    override fun onDisable() {
        val player = mc.thePlayer
        if (restoreSlot != -1 && player != null) {
            sendPacket(C09PacketHeldItemChange(player.inventory.currentItem), triggerEvent = false)
        }
        restoreSlot = -1
        usedSlots.clear()
    }

    val isHighestWeaponHeld: Boolean
        get() = isHighestWeapon

    private fun getDamage(stack: ItemStack?): Double {
        if (stack == null) return -1.0
        if (stack.item is ItemAxe) return 13.25

        var damage = 0.0
        try {
            val modifiers = stack.getAttributeModifiers() as Multimap<String, AttributeModifier>
            for (modifier in modifiers.get("generic.attackDamage")) {
                damage += modifier.amount
            }
        } catch (_: Throwable) {
        }

        val sharpness = EnchantmentHelper.getEnchantmentLevel(Enchantment.sharpness.effectId, stack)
        val knockback = EnchantmentHelper.getEnchantmentLevel(Enchantment.knockback.effectId, stack)
        return damage + sharpness * 1.25 + knockback * 0.01
    }

    private fun isWeapon(stack: ItemStack?): Boolean {
        if (stack == null) return false
        val item = stack.item
        if (item is ItemSword) return true
        if (item is ItemAxe && includeAxe) return true
        return includeKBWeapon && EnchantmentHelper.getEnchantmentLevel(Enchantment.knockback.effectId, stack) > 0
    }

    private fun isAutoGappleEating(): Boolean {
        return try {
            Gapple.state && Gapple.isEating
        } catch (_: Throwable) {
            false
        }
    }

    /** 根据目标受伤时间选取下一个武器槽位（无敌帧重置时选最低伤武器，阶梯式向上破甲）。 */
    private fun getSwitchSlot(targetHurtTime: Int): Int {
        val player = mc.thePlayer ?: return -1

        // AutoGapple 吃东西期间：无敌帧重置时打最低剑，其余时间打最高剑
        if (noGapple && isAutoGappleEating()) {
            var lowestSlot = -1
            var lowestDmg = Double.MAX_VALUE
            var highestSlot = -1
            var highestDmg = -1.0

            for (i in 9 until 45) {
                val stack = player.inventoryContainer.getSlot(i).stack ?: continue
                if (!isWeapon(stack)) continue
                val dmg = getDamage(stack)
                if (dmg < lowestDmg) {
                    lowestDmg = dmg
                    lowestSlot = i
                }
                if (dmg > highestDmg) {
                    highestDmg = dmg
                    highestSlot = i
                }
            }

            if (lowestSlot == -1) return -1

            return if (targetHurtTime <= hurtTime) {
                lastSwordDamage = lowestDmg
                usedSlots.clear()
                lowestSlot
            } else {
                if (highestSlot != -1) {
                    lastSwordDamage = highestDmg
                    highestSlot
                } else {
                    lowestSlot
                }
            }
        }

        // 原本的阶梯逻辑
        val damages = LinkedList<Double>()
        val slots = LinkedList<Int>()

        if (targetHurtTime <= hurtTime) {
            lastSwordDamage = -1.0
            switchEnd = false
            isHighestWeapon = false
            usedSlots.clear()
        }

        if (switchEnd) {
            lastSwordDamage = -1.0
            isHighestWeapon = false
            usedSlots.clear()
        }

        for (i in 9 until 45) {
            val stack = player.inventoryContainer.getSlot(i).stack ?: continue
            if (usedSlots.contains(i) || !isWeapon(stack)) continue
            val damage = getDamage(stack)
            if (damage > lastSwordDamage) {
                damages.add(damage)
                slots.add(i)
            }
        }

        var minDamage = Double.MAX_VALUE
        var bestSlot = -1
        while (damages.isNotEmpty()) {
            val dmg = damages.poll() ?: break
            val slot = slots.poll() ?: break
            if (dmg < minDamage) {
                minDamage = dmg
                bestSlot = slot
            }
        }

        return if (minDamage != Double.MAX_VALUE) {
            lastSwordDamage = minDamage
            usedSlots.add(bestSlot)
            bestSlot
        } else {
            switchEnd = true
            isHighestWeapon = true
            -1
        }
    }

    /** 查找快捷栏中锋利度最高的斧头。 */
    private fun findHotbarAxeSlot(): Int {
        val player = mc.thePlayer ?: return -1
        for (i in 0 until 9) {
            val stack = player.inventory.getStackInSlot(i) ?: continue
            if (stack.item is ItemAxe) return i
        }
        return -1
    }

    private val currentTarget: EntityLivingBase?
        get() = KillAura.target

    /** 是否处于破甲工作状态（已启用且有 KillAura 目标），供 InventoryCleaner 等暂停背包操作 */
    val isWorking: Boolean
        get() = state && currentTarget != null

    private fun collectWeaponSlots(): List<Int> {
        val player = mc.thePlayer ?: return emptyList()
        val slots = ArrayList<Int>()
        for (i in 9 until 45) {
            val stack = player.inventoryContainer.getSlot(i).stack ?: continue
            if (isWeapon(stack)) slots.add(i)
        }
        return slots
    }

    /** FunMode：轮询切换背包中所有武器。 */
    private fun funSwitch() {
        val player = mc.thePlayer ?: return
        val slots = collectWeaponSlots()
        if (slots.size >= 2) {
            if (funIndex >= slots.size) funIndex = 0
            val slot = slots[funIndex]
            funIndex++
            mc.playerController.windowClick(player.inventoryContainer.windowId, slot, player.inventory.currentItem, 2, player)
        }
    }

    private val onAttack = handler<AttackEvent> { event ->
        val target = event.targetEntity as? EntityLivingBase ?: return@handler
        val player = mc.thePlayer ?: return@handler

        val slot = getSwitchSlot(target.hurtTime)
        var switched: ItemStack? = null
        if (slot != -1) {
            if (c09HotbarSwitch && slot in 36..44) {
                val hotbarSlot = slot - 36
                val current = player.inventory.currentItem
                if (hotbarSlot != current) {
                    sendPacket(C09PacketHeldItemChange(hotbarSlot), triggerEvent = false)
                    restoreSlot = current
                }
                switched = player.inventory.getStackInSlot(hotbarSlot)

                if (switched?.item is ItemSword) {
                    sendPacket(C08PacketPlayerBlockPlacement(switched), triggerEvent = false)
                }
            } else {
                mc.playerController.windowClick(player.inventoryContainer.windowId, slot, player.inventory.currentItem, 2, player)
                usedSlots.remove(slot)
                switched = player.heldItem
            }

            if (noisePayload) {
                try {
                    val buffer = PacketBuffer(Unpooled.wrappedBuffer(byteArrayOf(1)))
                    sendPacket(C17PacketCustomPayload("test", buffer), triggerEvent = false)
                } catch (_: Throwable) {
                }
            }

            if (noiseBlock && switched != null) {
                val item: Item = switched.item
                if (item is ItemSword || item is ItemAxe) {
                    sendPacket(C08PacketPlayerBlockPlacement(switched), triggerEvent = false)
                    try {
                        sendPacket(C02PacketUseEntity(target, C02PacketUseEntity.Action.ATTACK), triggerEvent = false)
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        if (doubleHit) {
            pendingDoubleHit = true
            doubleHitTimer = doubleHitDelay
        }

        pendingBlock = true
    }

    private val onMotion = handler<MotionEvent> { event ->
        if (event.eventState != EventState.POST) return@handler
        val player = mc.thePlayer ?: return@handler

        // C09 热键切换后恢复原槽位
        if (restoreSlot != -1) {
            sendPacket(C09PacketHeldItemChange(restoreSlot), triggerEvent = false)
            restoreSlot = -1

            val held = player.heldItem
            if (held?.item is ItemSword) {
                sendPacket(C08PacketPlayerBlockPlacement(held), triggerEvent = false)
            }
            pendingBlock = false
        }

        if (funMode && mc.currentScreen == null) {
            funSwitch()
        }

        // 提前重置阶梯（比 HurtTime 早 earlySwitchTick + 1 tick）
        val target = currentTarget
        if (target != null) {
            val threshold = 1 + earlySwitchTick
            if (target.hurtTime < threshold) {
                if (!earlyResetDone) {
                    lastSwordDamage = -1.0
                    switchEnd = false
                    isHighestWeapon = false
                    usedSlots.clear()
                    earlyResetDone = true
                }
            } else {
                earlyResetDone = false
            }
        } else {
            earlyResetDone = false
        }

        if (pendingBlock) {
            pendingBlock = false
            if (forceBlock) {
                val held = player.heldItem
                if (held != null && (held.item is ItemSword || held.item is ItemAxe)) {
                    sendPacket(C08PacketPlayerBlockPlacement(held), triggerEvent = false)
                }
            }
        }

        if (pendingDoubleHit) {
            if (doubleHitTimer > 0) {
                doubleHitTimer--
            } else {
                pendingDoubleHit = false
                val doubleHitTarget = currentTarget
                if (doubleHitTarget != null && !doubleHitTarget.isDead) {
                    val current = player.inventory.currentItem
                    val axeSlot = if (doubleHitSwitchAxe) findHotbarAxeSlot() else -1
                    if (axeSlot != -1 && axeSlot != current) {
                        sendPacket(C09PacketHeldItemChange(axeSlot), triggerEvent = false)
                        sendPacket(C02PacketUseEntity(doubleHitTarget, C02PacketUseEntity.Action.ATTACK), triggerEvent = false)
                        sendPacket(C0APacketAnimation(), triggerEvent = false)
                        sendPacket(C09PacketHeldItemChange(current), triggerEvent = false)

                        val backHeld = player.heldItem
                        if (backHeld?.item is ItemSword) {
                            sendPacket(C08PacketPlayerBlockPlacement(backHeld), triggerEvent = false)
                        }
                    } else {
                        val held = player.heldItem
                        if (held != null) {
                            sendPacket(C02PacketUseEntity(doubleHitTarget, C02PacketUseEntity.Action.ATTACK), triggerEvent = false)
                            sendPacket(C0APacketAnimation(), triggerEvent = false)
                        }
                    }
                }
            }
        }
    }
}
