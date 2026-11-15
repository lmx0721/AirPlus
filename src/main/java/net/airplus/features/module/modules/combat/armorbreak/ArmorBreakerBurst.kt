/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 * skid edmoonlight
 * Ported from Moonlight EdMoonlight ArmorBreakerBurst (NewArmorBreaker dependency).
 * Burst 连击规划：在同一 tick 内按伤害递增序列完成多次换武器 + 攻击，最大化护甲损耗。
 */
package net.airplus.features.module.modules.combat.armorbreak

import net.airplus.features.module.modules.combat.armorbreak.EdMoonlightMode
import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.client.PacketUtils.sendPacket
import net.minecraft.client.entity.EntityPlayerSP
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.ItemAxe
import net.minecraft.item.ItemStack
import net.minecraft.item.ItemSword
import net.minecraft.network.play.client.C02PacketUseEntity
import net.minecraft.network.play.client.C03PacketPlayer

internal class ArmorBreakerBurst : MinecraftInstance {
    private val attrSlot = IntArray(MAX)
    private val bonusSlot = IntArray(MAX)
    private val amounts = FloatArray(MAX)
    private val critFlag = BooleanArray(MAX)

    @Volatile
    var spoofCrit = false

    private var size = 0
    private val path = IntArray(MAX)
    private var pathLen = 0
    private val dpArmor = IntArray(MAX)
    private val dpPrev = IntArray(MAX)

    var last = 0.0f
    var freshTick = -1000
    var target: EntityLivingBase? = null
    var plannedHits = 0
    var plannedArmor = 0

    fun plan(
        player: EntityPlayerSP,
        target: EntityLivingBase,
        crit: Boolean,
        fresh: Boolean,
        threshold: Float,
        wireAttr: Float,
    ): Int {
        size = 0
        val inventory = player.inventory.mainInventory

        if (!wireAttr.isNaN()) {
            val serverAttr = ArmorBreakerServerModel.withPotions(player, wireAttr)

            for (slot in 0 until 36) {
                if (isWeapon(inventory[slot]) && !seenBonus(inventory, slot)) {
                    val amount = ArmorBreakerServerModel.predictAmount(serverAttr, crit, inventory[slot], target)
                    if (amount > threshold && amount > 0.0f && size < MAX) {
                        insert(-1, slot, amount, crit)
                    }
                }
            }

            return solve(fresh, threshold, target is EntityPlayer && target.isBlocking)
        } else {
            for (attrIdx in 0 until 36) {
                if (isWeapon(inventory[attrIdx]) && !seenAttr(inventory, attrIdx)) {
                    val attr = ArmorBreakerServerModel.withPotions(player, ArmorBreakerLadder.itemAttr(inventory[attrIdx]))

                    for (bonusIdx in 0 until 36) {
                        if (isWeapon(inventory[bonusIdx]) && !seenBonus(inventory, bonusIdx)) {
                            for (i in 0 until 2) {
                                val isCrit = if (spoofCrit) i == 1 else crit
                                if (!spoofCrit && i == 1) {
                                    break
                                }

                                val amount = ArmorBreakerServerModel.predictAmount(attr, isCrit, inventory[bonusIdx], target)
                                if (amount > threshold && amount > 0.0f && size < MAX) {
                                    insert(attrIdx, bonusIdx, amount, isCrit)
                                }
                            }
                        }
                    }
                }
            }

            return solve(fresh, threshold, target is EntityPlayer && target.isBlocking)
        }
    }

    /** 按 amount 升序插入候选（去重相同 amount）。 */
    private fun insert(attrIdx: Int, bonusIdx: Int, amount: Float, crit: Boolean) {
        var pos = size - 1
        while (pos >= 0 && amounts[pos] > amount) {
            pos--
        }

        if (pos < 0 || amounts[pos] != amount) {
            val insertAt = pos + 1
            System.arraycopy(amounts, insertAt, amounts, insertAt + 1, size - insertAt)
            System.arraycopy(attrSlot, insertAt, attrSlot, insertAt + 1, size - insertAt)
            System.arraycopy(bonusSlot, insertAt, bonusSlot, insertAt + 1, size - insertAt)
            System.arraycopy(critFlag, insertAt, critFlag, insertAt + 1, size - insertAt)
            critFlag[insertAt] = crit
            amounts[insertAt] = amount
            attrSlot[insertAt] = attrIdx
            bonusSlot[insertAt] = bonusIdx
            size++
        }
    }

    private fun solve(fresh: Boolean, threshold: Float, blocking: Boolean): Int {
        pathLen = 0
        if (size == 0) {
            return 0
        }

        var best = -1

        for (i in 0 until size) {
            dpArmor[i] = armorLoss(if (fresh) amounts[i] else amounts[i] - threshold, blocking)
            dpPrev[i] = -1

            for (j in 0 until i) {
                val chain = dpArmor[j] + armorLoss(amounts[i] - amounts[j], blocking)
                if (chain > dpArmor[i]) {
                    dpArmor[i] = chain
                    dpPrev[i] = j
                }
            }

            if (best < 0 || dpArmor[i] >= dpArmor[best]) {
                best = i
            }
        }

        var index = best
        while (index >= 0) {
            path[pathLen++] = index
            index = dpPrev[index]
        }

        var left = 0
        var right = pathLen - 1
        while (left < right) {
            val tmp = path[left]
            path[left] = path[right]
            path[right] = tmp
            left++
            right--
        }

        plannedHits = pathLen
        plannedArmor = dpArmor[best]
        return pathLen
    }

    /** arm：0 = 用 refreshPacket（带坐标），其它 = C03 refresh。 */
    fun execute(target: EntityLivingBase, arm: Int) {
        val inventory = mc.thePlayer.inventory.mainInventory
        val attrStacks = arrayOfNulls<ItemStack>(pathLen)
        val bonusStacks = arrayOfNulls<ItemStack>(pathLen)
        val attrFromWire = BooleanArray(pathLen)

        for (i in 0 until pathLen) {
            val attrIdx = attrSlot[path[i]]
            attrFromWire[i] = attrIdx < 0
            attrStacks[i] = if (attrIdx < 0) null else inventory[attrIdx]
            bonusStacks[i] = inventory[bonusSlot[path[i]]]
        }

        val useRefreshPacket = arm == 0

        for (i in 0 until pathLen) {
            val attr = ArmorBreakerLadder.itemAttr(attrStacks[i])
            val crit = critFlag[path[i]]
            val needAttr = !attrFromWire[i] && ArmorBreakerServerModel.wireAttr() != attr
            val needCrit = spoofCrit && ArmorBreakerServerModel.wireCritState() != crit
            if (needAttr || needCrit) {
                if (!attrFromWire[i]) {
                    hold(attrStacks[i])
                }

                if (!spoofCrit || !ArmorBreakerServerModel.setCritState(crit)) {
                    sendPacket(
                        if (useRefreshPacket) ArmorBreakerServerModel.refreshPacket() else C03PacketPlayer(ArmorBreakerServerModel.lastGround()),
                        triggerEvent = false
                    )
                }
            }

            hold(bonusStacks[i])
            if (i < pathLen - 1) {
                sendPacket(C02PacketUseEntity(target, C02PacketUseEntity.Action.ATTACK), triggerEvent = false)
            }
        }

        last = amounts[path[pathLen - 1]]
    }

    private fun hold(stack: ItemStack?) {
        val inventory = mc.thePlayer.inventory.mainInventory
        val current = mc.thePlayer.inventory.currentItem
        if (inventory[current] !== stack) {
            for (slot in 0 until 36) {
                if (inventory[slot] === stack) {
                    val windowSlot = if (slot < 9) slot + 36 else slot
                    mc.playerController.windowClick(mc.thePlayer.inventoryContainer.windowId, windowSlot, current, 2, mc.thePlayer)
                    EdMoonlightMode.markSwapped()
                    return
                }
            }
        }
    }

    fun reset() {
        last = 0.0f
        freshTick = -1000
        target = null
    }

    companion object {
        private const val MAX = 64
        private const val SHARPNESS_ID = 16

        private fun armorLoss(amount: Float, blocking: Boolean): Int {
            var value = amount
            if (blocking) {
                value = (1.0f + value) * 0.5f
            }

            return Math.max(1, (value / 4.0f).toInt())
        }

        private fun isWeapon(stack: ItemStack?): Boolean =
            stack != null && (stack.item is ItemSword || stack.item is ItemAxe)

        private fun seenAttr(inventory: Array<ItemStack?>, index: Int): Boolean {
            val attr = ArmorBreakerLadder.itemAttr(inventory[index])
            for (i in 0 until index) {
                if (isWeapon(inventory[i]) && ArmorBreakerLadder.itemAttr(inventory[i]) == attr) {
                    return true
                }
            }

            return false
        }

        private fun seenBonus(inventory: Array<ItemStack?>, index: Int): Boolean {
            val bonus = EnchantmentHelper.getEnchantmentLevel(SHARPNESS_ID, inventory[index]).toFloat()
            for (i in 0 until index) {
                if (isWeapon(inventory[i]) && EnchantmentHelper.getEnchantmentLevel(SHARPNESS_ID, inventory[i]).toFloat() == bonus) {
                    return true
                }
            }

            return false
        }
    }
}
