/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Ported from Moonlight ArmorBreaker2 (Code By cubk).
 */
package net.airplus.features.module.modules.combat

import net.airplus.event.EventState
import net.airplus.event.MotionEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.modules.world.scaffolds.Scaffold
import net.airplus.utils.client.PacketUtils.sendPacket
import net.minecraft.enchantment.Enchantment
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.ai.attributes.AttributeModifier
import net.minecraft.item.ItemAxe
import net.minecraft.item.ItemStack
import net.minecraft.item.ItemSword
import net.minecraft.network.play.client.C02PacketUseEntity
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement
import net.minecraft.network.play.client.C09PacketHeldItemChange
import net.minecraft.network.play.client.C0APacketAnimation
import com.google.common.collect.Multimap

object ArmorBreaker2 : Module("ArmorBreaker2", Category.COMBAT) {

    private val includeAxe by boolean("IncludeAxe", false)
    private val axe by boolean("UseAxeBugToArmorBreak", true)

    override val tag: String?
        get() = "Breaker2"

    private var currentIndex = 0

    /** 最近一次 C09 伪装的槽位与时间（供外部 / Mixin 查询）。 */
    var lastSpoofedSlot = -1
    var lastSpoofTime = 0L

    private fun getDamage(stack: ItemStack): Double {
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

    private fun getWeaponList(): List<ItemStack> {
        val player = mc.thePlayer ?: return emptyList()
        val weapons = ArrayList<ItemStack>()
        for (i in 9 until 45) {
            val stack = player.inventoryContainer.getSlot(i).stack ?: continue
            val item = stack.item
            if (item is ItemSword || (item is ItemAxe && includeAxe)) {
                weapons.add(stack)
            }
        }
        return weapons.sortedBy { getDamage(it) }
    }

    private fun getSwitchSlot(targetHurtTime: Int): Int {
        val player = mc.thePlayer ?: return -1
        if (targetHurtTime <= 4) {
            currentIndex = 0
        }

        val weapons = getWeaponList()
        if (weapons.isEmpty()) return -1

        if (currentIndex >= weapons.size) {
            currentIndex = 0
        }

        val target = weapons[currentIndex]
        val heldSlot = player.inventory.currentItem + 36
        for (i in 9 until 45) {
            if (i != heldSlot && player.inventoryContainer.getSlot(i).stack === target) {
                currentIndex++
                return i
            }
        }
        return -1
    }

    /** 查找快捷栏中锋利度最高且未手持的斧头。 */
    private fun getAxeSlot(): Int {
        val player = mc.thePlayer ?: return -1
        var bestSlot = -1
        var bestSharpness = -1

        for (i in 0 until 9) {
            val stack = player.inventoryContainer.getSlot(i + 36).stack ?: continue
            if (stack.item !is ItemAxe) continue
            val sharpness = EnchantmentHelper.getEnchantmentLevel(Enchantment.sharpness.effectId, stack)
            if (sharpness > bestSharpness) {
                bestSharpness = sharpness
                bestSlot = i
            }
        }

        if (bestSlot == player.inventory.currentItem) bestSlot = -1
        return bestSlot
    }

    private val onMotion = handler<MotionEvent> { event ->
        if (event.eventState != EventState.POST) return@handler
        val player = mc.thePlayer ?: return@handler

        val target = KillAura.target ?: return@handler
        if (Scaffold.state) return@handler

        val slot = getSwitchSlot(target.hurtTime)
        if (slot == -1) return@handler

        val axeSlot = getAxeSlot()
        val useAxeBug = axe && axeSlot != -1 && currentIndex == getWeaponList().size - 1

        if (useAxeBug) {
            sendPacket(C09PacketHeldItemChange(axeSlot), triggerEvent = false)
            lastSpoofedSlot = axeSlot
            lastSpoofTime = System.currentTimeMillis()
        }

        sendPacket(C02PacketUseEntity(target, C02PacketUseEntity.Action.ATTACK))

        if (useAxeBug) {
            sendPacket(C09PacketHeldItemChange(player.inventory.currentItem), triggerEvent = false)
        }

        sendPacket(C0APacketAnimation())
        mc.playerController.windowClick(player.inventoryContainer.windowId, slot, player.inventory.currentItem, 2, player)
        sendPacket(C08PacketPlayerBlockPlacement(player.heldItem))
    }
}
