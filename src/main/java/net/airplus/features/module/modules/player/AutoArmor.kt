/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.modules.player.InventoryCleaner.canBeRepairedWithOther
import net.airplus.utils.client.PacketUtils.sendPacket
import net.airplus.utils.inventory.ArmorComparator.getBestArmorSet
import net.airplus.utils.inventory.InventoryManager
import net.airplus.utils.inventory.InventoryManager.autoArmorCurrentSlot
import net.airplus.utils.inventory.InventoryManager.autoArmorLastSlot
import net.airplus.utils.inventory.InventoryManager.canClickInventory
import net.airplus.utils.inventory.InventoryManager.hasScheduledInLastLoop
import net.airplus.utils.inventory.InventoryManager.passedPostInventoryCloseDelay
import net.airplus.utils.inventory.InventoryUtils.isFirstInventoryClick
import net.airplus.utils.inventory.InventoryUtils.serverOpenInventory
import net.airplus.utils.inventory.InventoryUtils.toHotbarIndex
import net.airplus.utils.inventory.SilentHotbar
import net.airplus.utils.inventory.hasItemAgePassed
import net.airplus.utils.timing.TickedActions.awaitTicked
import net.airplus.utils.timing.TickedActions.clickNextTick
import net.airplus.utils.timing.TickedActions.isTicked
import net.airplus.utils.timing.TickedActions.nextTick
import net.minecraft.client.gui.inventory.GuiInventory
import net.minecraft.entity.EntityLiving.getArmorPosition
import net.minecraft.item.ItemStack
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement

object AutoArmor : Module("AutoArmor", Category.PLAYER) {
    private val delay by intRange("Delay", 50..50, 0..1000)
    private val minItemAge by int("MinItemAge", 0, 0..2000)

    private val invOpen by +InventoryManager.invOpenValue
    private val simulateInventory by +InventoryManager.simulateInventoryValue

    private val postInventoryCloseDelay by +InventoryManager.postInventoryCloseDelayValue
    private val autoClose by +InventoryManager.autoCloseValue
    private val startDelay by +InventoryManager.startDelayValue
    private val closeDelay by +InventoryManager.closeDelayValue

    // When swapping armor pieces, it grabs the better one, drags and swaps it with equipped one and drops the equipped one (no time of having no armor piece equipped)
    // Has to make more clicks, works slower
    val smartSwap by boolean("SmartSwap", true)

    private val noMove by +InventoryManager.noMoveValue
    private val noMoveAir by +InventoryManager.noMoveAirValue
    private val noMoveGround by +InventoryManager.noMoveGroundValue

    private val hotbar by boolean("Hotbar", true)

    // Sacrifices 1 tick speed for complete undetectability, needed to bypass Vulcan
    private val delayedSlotSwitch by boolean("DelayedSlotSwitch", true) { hotbar }

    // Prevents AutoArmor from hotbar equipping while any screen is open
    private val notInContainers by boolean("NotInContainers", false) { hotbar }

    val highlightSlot by +InventoryManager.highlightSlotValue
    val backgroundColor by +InventoryManager.borderColor

    val borderStrength by +InventoryManager.borderStrength
    val borderColor by +InventoryManager.borderColor

    /** 是否正在装备盔甲（已调度点击但尚未完成），供 ArmorBreak/ArmorBreaker2 让位，避免背包点击与槽位伪装冲突 */
    @Volatile
    private var working = false

    val isWorking: Boolean
        get() = working

    suspend fun equipFromHotbar() {
        if (!shouldOperate(onlyHotbar = true)) {
            autoArmorCurrentSlot = -1
            autoArmorLastSlot = -1
            return
        }

        val thePlayer = mc.thePlayer ?: return

        var hasClickedHotbar = false

        val stacks = withContext(Dispatchers.Main) {
            thePlayer.openContainer.inventorySlots.map { it.stack }
        }

        val bestArmorSet = getBestArmorSet(stacks) ?: return

        for (armorType in 0..3) {
            val (index, stack) = bestArmorSet[armorType] ?: continue

            // Check if the armor piece is in the hotbar
            val hotbarIndex = index?.toHotbarIndex(stacks.size) ?: continue

            if (isTicked(index) || isTicked(armorType + 5))
                continue

            if (!stack.hasItemAgePassed(minItemAge))
                continue

            val armorPos = getArmorPosition(stack) - 1

            // Check if target armor slot isn't occupied
            if (thePlayer.inventory.armorInventory[armorPos] != null)
                continue

            hasClickedHotbar = true
            working = true

            val equippingAction = {
                // Set current slot being stolen for highlighting
                autoArmorCurrentSlot = hotbarIndex

                SilentHotbar.selectSlotSilently(
                    this,
                    hotbarIndex,
                    immediate = true,
                    render = false,
                    resetManually = true
                )

                // Switch selected hotbar slot, right click to equip
                sendPacket(C08PacketPlayerBlockPlacement(stack))

                // Instantly update inventory on client-side to prevent repetitive clicking because of ping
                thePlayer.inventory.armorInventory[armorPos] = stack
                thePlayer.inventory.mainInventory[hotbarIndex] = null
            }

            // Schedule hotbar click
            nextTick(action = equippingAction)

            if (delayedSlotSwitch) {
                delay(delay.random().toLong())
            }
        }

        delay(delay.random().toLong())

        awaitTicked()

        // Sync selected slot next tick
        if (hasClickedHotbar)
            nextTick { SilentHotbar.resetSlot(this) }

        working = false
    }

    suspend fun equipFromInventory() {
        if (!shouldOperate()) {
            autoArmorCurrentSlot = -1
            autoArmorLastSlot = -1
            return
        }

        val thePlayer = mc.thePlayer ?: return

        for (armorType in 0..3) {
            if (!shouldOperate()) {
                autoArmorCurrentSlot = -1
                autoArmorLastSlot = -1
                return
            }

            val stacks = withContext(Dispatchers.Main) {
                thePlayer.openContainer.inventorySlots.map { it.stack }
            }

            val armorSet = getBestArmorSet(stacks) ?: continue

            // Shouldn't iterate over armor set because after waiting for nomove and invopen it could be outdated
            val (index, stack) = armorSet[armorType] ?: continue

            // Index is null when searching in chests for already equipped armor to prevent any accidental impossible interactions
            index ?: continue

            // Check if best item is already scheduled to be equipped next tick
            if (isTicked(index) || isTicked(armorType + 5))
                continue

            if (!stack.hasItemAgePassed(minItemAge))
                continue

            // Don't equip if it can be repaired with other armor piece, wait for the repair to happen first
            // Armor piece will then get equipped right after the repair
            if (canBeRepairedWithOther(stack, stacks))
                continue

            // Set current slot being stolen for highlighting
            autoArmorCurrentSlot = index

            when (stacks[armorType + 5]) {
                // Best armor is already equipped
                stack -> {
                    autoArmorCurrentSlot = -1
                    autoArmorLastSlot = -1
                    continue
                }

                // No item is equipped in armor slot
                null -> {
                    working = true

                    // Equip by shift-clicking
                    click(index, 0, 1)
                }

                else -> {
                    working = true

                    if (smartSwap) {
                        // Player has worse armor equipped, drag the best armor, swap it with currently equipped armor and drop the bad armor
                        // This way there is no time of having no armor (but more clicks)

                        // Grab better armor
                        click(index, 0, 0)

                        // Swap it with currently equipped armor
                        click(armorType + 5, 0, 0)

                        // Drop worse item by dragging and dropping it
                        click(-999, 0, 0)
                    } else {
                        // Normal version

                        // Drop worse armor
                        click(armorType + 5, 0, 4)

                        // Equip better armor
                        click(index, 0, 1)
                    }
                }
            }
        }

        // Wait till all scheduled clicks were sent
        awaitTicked()

        working = false
    }

    fun equipFromHotbarInChest(hotbarIndex: Int?, stack: ItemStack) {
        // AutoArmor is disabled or prohibited from equipping while in containers
        if (hotbarIndex == null || !canEquipFromChest()) {
            autoArmorCurrentSlot = -1
            autoArmorLastSlot = -1
            return
        }

        // Set current slot being stolen for highlighting
        autoArmorCurrentSlot = hotbarIndex

        SilentHotbar.selectSlotSilently(this, hotbarIndex, immediate = true, render = false, resetManually = true)

        sendPacket(C08PacketPlayerBlockPlacement(stack))
    }

    fun canEquipFromChest() = handleEvents() && hotbar && !notInContainers

    private suspend fun shouldOperate(onlyHotbar: Boolean = false): Boolean {
        while (true) {
            if (!handleEvents())
                return false

            if (!passedPostInventoryCloseDelay)
                return false

            if (mc.playerController?.currentGameType?.isSurvivalOrAdventure != true)
                return false

            // It is impossible to equip armor when a container is open; only try to equip by right-clicking from hotbar (if NotInContainers is disabled)
            if (mc.thePlayer?.openContainer?.windowId != 0 && (!onlyHotbar || notInContainers))
                return false

            // Player doesn't need to have inventory open or not to move, when equipping from hotbar
            if (onlyHotbar)
                return hotbar

            if (invOpen && mc.currentScreen !is GuiInventory)
                return false

            // Wait till NoMove check isn't violated
            if (canClickInventory(closeWhenViolating = true))
                return true

            // If NoMove is violated, wait a tick and check again
            // If there is no delay, very weird things happen: https://www.guilded.gg/CCBlueX/groups/1dgpg8Jz/channels/034be45e-1b72-4d5a-bee7-d6ba52ba1657/chat?messageId=94d314cd-6dc4-41c7-84a7-212c8ea1cc2a
            delay(50)
        }
    }

    private suspend fun click(slot: Int, button: Int, mode: Int, allowDuplicates: Boolean = false) {
        // Wait for NoMove or cancel click
        if (!shouldOperate())
            return

        if (simulateInventory || invOpen)
            serverOpenInventory = true

        if (isFirstInventoryClick) {
            // Have to set this manually, because it would delay all clicks until a first scheduled click was sent
            isFirstInventoryClick = false

            delay(startDelay.toLong())
        }

        clickNextTick(slot, button, mode, allowDuplicates)

        hasScheduledInLastLoop = true

        delay(delay.random().toLong())
    }
}
