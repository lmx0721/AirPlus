/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 * skid edmoonlight
 * Ported from Moonlight EdMoonlight ArmorBreakerCalib (NewArmorBreaker dependency).
 * Burst refresh 方式校准：统计三种属性刷新方式（Position/Flying/None）的实际护甲损耗，
 * 循环足够多后锁定损耗最大的方式。
 */
package net.airplus.features.module.modules.combat.armorbreak

import net.minecraft.enchantment.Enchantment
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.entity.EntityLivingBase
import net.minecraft.item.ItemArmor
import net.minecraft.item.ItemStack

internal class ArmorBreakerCalib {
    private val cycles = IntArray(ARMS)
    private val actual = DoubleArray(ARMS)
    private val expected = DoubleArray(ARMS)
    private var locked = -1
    private var sinceLock = 0
    private var curArm = -1
    private var curExpected = 0.0
    private var curBase = -1
    private var curPieces = 0
    private var curStartTick = 0
    private var curTarget: EntityLivingBase? = null
    private var lastPieces = 0

    /** 选出本次 burst 使用的刷新方式：优先沿用手动指定，其次锁定值，否则探索最少用的。 */
    fun choose(arm: Int): Int {
        if (arm >= 0) {
            return arm
        } else if (locked >= 0 && sinceLock < REEXPLORE_CYCLES) {
            return locked
        } else {
            if (locked >= 0) {
                resetStats()
            }

            var pick = 0
            for (armIdx in 1 until ARMS) {
                if (cycles[armIdx] < cycles[pick]) {
                    pick = armIdx
                }
            }

            return pick
        }
    }

    private fun armorSum(target: EntityLivingBase): Int {
        var sum = 0
        var pieces = 0

        for (slot in 1..4) {
            val stack = target.getEquipmentInSlot(slot)
            if (stack != null && stack.isItemStackDamageable) {
                sum += stack.itemDamage
                pieces++
            }
        }

        lastPieces = pieces
        return if (pieces == 0) -1 else sum
    }

    fun onFresh(target: EntityLivingBase, arm: Int, damage: Int, tick: Int, maxInterval: Int): String? {
        var result: String? = null
        val sum = armorSum(target)
        if (curArm >= 0
            && target === curTarget
            && sum >= 0
            && curBase >= 0
            && lastPieces == curPieces
            && sum >= curBase
            && tick - curStartTick <= maxInterval
        ) {
            result = record(curArm, sum - curBase, curExpected)
        }

        curArm = arm
        curTarget = target
        curBase = sum
        curPieces = lastPieces
        curStartTick = tick
        curExpected = damage * keepSum(target)
        return result
    }

    fun onMid(target: EntityLivingBase, damage: Int) {
        if (target === curTarget && curArm >= 0) {
            curExpected += damage * keepSum(target)
        }
    }

    fun record(arm: Int, delta: Int, expect: Double): String? {
        cycles[arm]++
        actual[arm] += delta
        expected[arm] += expect
        if (locked >= 0) {
            sinceLock++
            return null
        } else {
            for (armIdx in 0 until ARMS) {
                if (cycles[armIdx] < EXPLORE_CYCLES) {
                    return null
                }
            }

            var best = 0
            for (armIdx in 1 until ARMS) {
                if (actual[armIdx] / cycles[armIdx] > actual[best] / cycles[best]) {
                    best = armIdx
                }
            }

            locked = best
            sinceLock = 0
            return summary()
        }
    }

    fun summary(): String {
        val builder = StringBuilder("AB calib")
        for (armIdx in 0 until ARMS) {
            builder.append(
                String.format(
                    " | %s %d cyc %.2f/%.2f per cycle",
                    NAMES[armIdx],
                    cycles[armIdx],
                    if (cycles[armIdx] == 0) 0.0 else actual[armIdx] / cycles[armIdx],
                    if (cycles[armIdx] == 0) 0.0 else expected[armIdx] / cycles[armIdx]
                )
            )
        }

        if (locked >= 0) {
            builder.append(" -> lock ").append(NAMES[locked])
        }

        return builder.toString()
    }

    private fun resetStats() {
        for (armIdx in 0 until ARMS) {
            cycles[armIdx] = 0
            actual[armIdx] = 0.0
            expected[armIdx] = 0.0
        }

        locked = -1
        sinceLock = 0
    }

    fun dropCycle() {
        curArm = -1
        curTarget = null
        curBase = -1
    }

    fun reset() {
        dropCycle()
        resetStats()
    }

    companion object {
        const val POSITION = 0
        const val FLYING = 1
        const val NONE = 2
        val NAMES = arrayOf("Position", "Flying", "None")
        private const val ARMS = 3
        private const val EXPLORE_CYCLES = 6
        private const val REEXPLORE_CYCLES = 120

        /** 耐久附魔让护甲损耗打折的保留系数之和。 */
        fun keepSum(target: EntityLivingBase): Double {
            var sum = 0.0
            for (slot in 1..4) {
                val stack: ItemStack? = target.getEquipmentInSlot(slot)
                if (stack != null && stack.isItemStackDamageable && stack.item is ItemArmor) {
                    val level = EnchantmentHelper.getEnchantmentLevel(Enchantment.unbreaking.effectId, stack)
                    sum += if (level <= 0) 1.0 else 0.6 + 0.4 / (level + 1)
                }
            }

            return sum
        }
    }
}
