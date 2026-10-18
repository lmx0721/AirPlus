/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Ported from NekoBounce ArmorBreak (Code By GoldBounce, Lizz, NightSky, FDP).

 */
package net.airplus.features.module.modules.combat

import net.airplus.event.AttackEvent
import net.airplus.event.MotionEvent
import net.airplus.event.EventState
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.modules.player.Gapple
import net.airplus.utils.client.PacketUtils.sendPacket
import net.minecraft.enchantment.Enchantment
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.entity.EntityLivingBase
import net.minecraft.item.ItemAxe
import net.minecraft.item.ItemStack
import net.minecraft.item.ItemSword
import net.minecraft.item.ItemTool
import net.minecraft.network.play.client.C0EPacketClickWindow
import java.util.LinkedList

object ArmorBreak : Module("ArmorBreak", Category.COMBAT) {

    private val sendEvent by choices("SendEvent", arrayOf("PreAttack", "Attack", "PostMotion", "PreMotion"), "PreAttack")
    private val clickMode by choices("ClickMode", arrayOf("WindowClick", "SendPacket"), "WindowClick")
    private val minHurtTime by int("MinHurtTime", 3, 0..9)
    private val axe by boolean("AddAxeToArmorBreak", false)
    private val switchBack by boolean("AutoSwitchBackToLowestDamageWeapon", false)
    private val onGap by boolean("onGap", false)

    private var lastSwordDamage = -1.0
    private var switchEnd = false

    override val tag: String?
        get() = sendEvent

    private val onMotion = handler<MotionEvent> { event ->
        val target = KillAura.target ?: return@handler
        when {
            event.eventState == EventState.POST && sendEvent == "PostMotion" -> doIt(target)
            event.eventState == EventState.PRE && sendEvent == "PreMotion" -> doIt(target)
        }
    }

    private val onAttack = handler<AttackEvent> { event ->
        // AirPlus 只有攻击事件（PreAttack / Attack 均映射到 AttackEvent）
        if (sendEvent == "PreAttack" || sendEvent == "Attack") {
            (event.targetEntity as? EntityLivingBase)?.let { doIt(it) }
        }
    }

    /** 根据目标受伤时间，获取伤害最低的武器槽位（从上次伤害往上找，循环切换破甲）。 */
    private fun getSwitchSlot(targetHurtTime: Int): Int {
        val swordDamages = LinkedList<Double>()
        val swordSlots = LinkedList<Int>()

        if (targetHurtTime <= minHurtTime) {
            lastSwordDamage = -1.0
            switchEnd = false
        }
        if (switchEnd && switchBack) {
            lastSwordDamage = -1.0
        }

        val player = mc.thePlayer ?: return -1
        for (i in 9 until 45) {
            val stack = player.inventoryContainer.getSlot(i).stack ?: continue
            val item = stack.item ?: continue
            if (item !is ItemSword && (!axe || item !is ItemAxe)) continue
            val damage = getAttackDamage(stack)
            if (damage > lastSwordDamage) {
                swordDamages.add(damage)
                swordSlots.add(i)
            }
        }

        var minDamage = 10000.0
        var bestSlot = -1
        while (swordDamages.isNotEmpty()) {
            val dmg = swordDamages.poll() ?: break
            val slot = swordSlots.poll() ?: break
            if (dmg < minDamage) {
                minDamage = dmg
                bestSlot = slot
            }
        }

        return if (minDamage != 10000.0) {
            lastSwordDamage = minDamage
            bestSlot
        } else {
            switchEnd = true
            -1
        }
    }

    /** 查找背包中伤害最高的剑。 */
    private fun findBestSword(): Int {
        var maxDamage = -1.0
        var bestSlot = -1
        val player = mc.thePlayer ?: return -1
        for (i in 9 until 45) {
            val stack = player.inventoryContainer.getSlot(i).stack ?: continue
            if (stack.item !is ItemSword) continue
            val damage = getAttackDamage(stack)
            if (damage > maxDamage) {
                maxDamage = damage
                bestSlot = i
            }
        }
        return bestSlot
    }

    /** 执行武器切换动作。 */
    private fun doIt(target: EntityLivingBase) {
        val player = mc.thePlayer ?: return

        // AutoGapple 期间切回最高伤害武器（onGap 开启时不处理）
        if (Gapple.handleEvents() && !onGap) {
            val slot = findBestSword()
            if (slot != -1) {
                sendSwitchPacket(slot, player)
            }
            return
        }

        val slot = getSwitchSlot(target.hurtTime)
        if (slot == -1) return

        when (clickMode) {
            "WindowClick" -> {
                mc.playerController.windowClick(
                    player.inventoryContainer.windowId,
                    slot,
                    player.inventory.currentItem,
                    2,
                    player
                )
            }
            else -> sendSwitchPacket(slot, player)
        }
    }

    /** 发送 ClickWindow 包切换物品。 */
    private fun sendSwitchPacket(slot: Int, player: net.minecraft.entity.player.EntityPlayer) {
        val packet = C0EPacketClickWindow(
            player.openContainer.windowId,
            slot,
            player.inventory.currentItem,
            2,
            player.inventory.getStackInSlot(player.inventory.currentItem + 36),
            player.openContainer.getNextTransactionID(player.inventory)
        )
        sendPacket(packet)
    }

    private fun getAttackDamage(stack: ItemStack): Double {
        var damage = 0f
        val item = stack.item

        if (item is ItemSword) {
            damage += item.damageVsEntity
        } else if (item is ItemTool) {
            damage += item.toolMaterial.damageVsEntity
        }

        damage += EnchantmentHelper.getEnchantmentLevel(Enchantment.sharpness.effectId, stack) * 1.25f
        return damage.toDouble()
    }
}
