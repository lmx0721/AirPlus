/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 * skid edmoonlight
 * Ported from Moonlight EdMoonlight ArmorBreakerLadder (NewArmorBreaker dependency).
 */
package net.airplus.features.module.modules.combat.armorbreak

import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.entity.SharedMonsterAttributes
import net.minecraft.entity.ai.attributes.AttributeModifier
import net.minecraft.entity.player.InventoryPlayer
import net.minecraft.item.Item
import net.minecraft.item.ItemAxe
import net.minecraft.item.ItemBlock
import net.minecraft.item.ItemPickaxe
import net.minecraft.item.ItemShears
import net.minecraft.item.ItemSpade
import net.minecraft.item.ItemStack
import net.minecraft.item.ItemSword
import com.google.common.collect.Multimap

internal const val SHARPNESS_ID = 16

/** 剑的客户端评分：基础伤害 + 锋利度 * 1.25（moonlight NewArmorBreaker.calculateWeaponScore）。 */
internal fun calculateWeaponScore(stack: ItemStack?): Float {
    if (stack != null && stack.item is ItemSword) {
        val sword = stack.item as ItemSword
        var score = sword.damageVsEntity
        val sharpness = EnchantmentHelper.getEnchantmentLevel(net.minecraft.enchantment.Enchantment.sharpness.effectId, stack)
        return score + sharpness * 1.25f
    }
    return -1.0f
}

/** 阶梯武器表：按伤害从低到高排序的槽位序列（破甲阶梯依次向上打）。 */
internal class ArmorBreakerLadder {
    val slots = IntArray(36)
    val scores = FloatArray(36)
    var size = 0

    fun build(
        inventory: InventoryPlayer,
        includeAxe: Boolean,
        keepEqualScoreWeapons: Boolean,
        onlyDiamondAxe: Boolean,
        minSharpness: Float,
        useServerDamage: Boolean,
    ) {
        size = 0

        for (slot in 0 until 36) {
            val stack = inventory.getStackInSlot(slot) ?: continue
            val item = stack.item

            if (item is ItemSword) {
                offer(slot, if (useServerDamage) serverDamage(stack) else calculateWeaponScore(stack), keepEqualScoreWeapons)
            }

            if (item is ItemAxe && includeAxe
                && (!onlyDiamondAxe || Item.getIdFromItem(item) == 279)
                && !(EnchantmentHelper.getEnchantmentLevel(SHARPNESS_ID, stack) < minSharpness)
            ) {
                offer(
                    slot,
                    if (useServerDamage) serverDamage(stack)
                    else item.toolMaterial.damageVsEntity + EnchantmentHelper.getEnchantmentLevel(SHARPNESS_ID, stack) * 1.25f + 100.0f,
                    keepEqualScoreWeapons
                )
            }
        }

        // 按分数升序插入排序
        for (i in 1 until size) {
            val score = scores[i]
            val slot = slots[i]

            var j = i - 1
            while (j >= 0 && scores[j] > score) {
                scores[j + 1] = scores[j]
                slots[j + 1] = slots[j]
                j--
            }

            scores[j + 1] = score
            slots[j + 1] = slot
        }
    }

    private fun offer(slot: Int, score: Float, keepEqual: Boolean) {
        if (!keepEqual) {
            val bits = score.toRawBits()
            for (i in 0 until size) {
                if (scores[i].toRawBits() == bits) {
                    return
                }
            }
        }

        if (size < scores.size) {
            scores[size] = score
            slots[size] = slot
            size++
        }
    }

    /** 只保留伤害最高的 [count] 个（0 = 不限制）。 */
    fun keepTop(count: Int) {
        if (count > 0 && size > count) {
            val skip = size - count
            System.arraycopy(slots, skip, slots, 0, count)
            System.arraycopy(scores, skip, scores, 0, count)
            size = count
        }
    }

    /** 移除所有斧头（吃金苹果期间只用剑）。 */
    fun removeAxes(inventory: InventoryPlayer) {
        var kept = 0
        for (i in 0 until size) {
            val stack = inventory.getStackInSlot(slots[i])
            if (stack == null || stack.item !is ItemAxe) {
                slots[kept] = slots[i]
                scores[kept] = scores[i]
                kept++
            }
        }
        size = kept
    }

    fun containsStack(inventory: InventoryPlayer, stack: ItemStack?): Boolean {
        for (i in 0 until size) {
            if (inventory.getStackInSlot(slots[i]) === stack) {
                return true
            }
        }
        return false
    }

    companion object {

        /** 服务端视角的武器总伤害：属性值 + 锋利度 * 1.25。 */
        fun serverDamage(stack: ItemStack): Float =
            itemAttr(stack) + EnchantmentHelper.getEnchantmentLevel(SHARPNESS_ID, stack) * 1.25f

        /**
         * 服务端视角的武器基础属性伤害（含玩家 +1 基础伤害）。
         * 优先读取 NBT AttributeModifiers（0 号 operation），否则按物品类型推算。
         */
        fun itemAttr(stack: ItemStack?): Float {
            if (stack == null) return 1.0f

            var value: Float
            if (stack.hasTagCompound() && stack.tagCompound.hasKey("AttributeModifiers", 9)) {
                value = 0.0f
                val modifiers = stack.getAttributeModifiers() as Multimap<String, AttributeModifier>
                for (modifier in modifiers.get(SharedMonsterAttributes.attackDamage.attributeUnlocalizedName)) {
                    if (modifier.operation == 0) {
                        value += modifier.amount.toFloat()
                    }
                }
            } else {
                value = when (val item = stack.item) {
                    is ItemSword -> 4.0f + item.damageVsEntity
                    is ItemAxe -> 3.0f + item.toolMaterial.damageVsEntity
                    is ItemPickaxe -> 2.0f + item.toolMaterial.damageVsEntity
                    is ItemSpade -> 1.0f + item.toolMaterial.damageVsEntity
                    else -> 0.0f
                }
            }

            return 1.0f + value
        }

        fun isCandidate(stack: ItemStack?, includeAxe: Boolean, onlyDiamondAxe: Boolean, minSharpness: Float): Boolean {
            if (stack == null) return false
            val item = stack.item
            return if (item is ItemSword) {
                true
            } else {
                item is ItemAxe && includeAxe
                    && (!onlyDiamondAxe || Item.getIdFromItem(item) == 279)
                    && !(EnchantmentHelper.getEnchantmentLevel(SHARPNESS_ID, stack) < minSharpness)
            }
        }

        @Suppress("unused")
        fun isDipItem(stack: ItemStack?): Boolean {
            if (stack == null) return false
            val item = stack.item
            return if (item is ItemSword || item is ItemAxe) {
                false
            } else {
                item is ItemBlock || item is ItemPickaxe || item is ItemSpade || item is ItemShears || item.javaClass == Item::class.java
            }
        }
    }
}
