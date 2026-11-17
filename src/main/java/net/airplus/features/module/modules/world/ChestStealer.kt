/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
@file:Suppress("unused")

package net.airplus.features.module.modules.world

import kotlinx.coroutines.delay
import net.airplus.AirPlus.hud
import net.airplus.event.PacketEvent
import net.airplus.event.Render2DEvent
import net.airplus.event.UpdateEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.modules.movement.InventoryMove
import net.airplus.features.module.modules.player.AutoArmor
import net.airplus.features.module.modules.player.InventoryCleaner
import net.airplus.features.module.modules.player.InventoryCleaner.canBeSortedTo
import net.airplus.features.module.modules.player.InventoryCleaner.isStackUseful
import net.airplus.ui.client.hud.element.elements.Notification
import net.airplus.utils.client.chat
import net.airplus.utils.extensions.component1
import net.airplus.utils.extensions.component2
import net.airplus.utils.inventory.InventoryManager
import net.airplus.utils.inventory.InventoryManager.canClickInventory
import net.airplus.utils.inventory.InventoryManager.chestStealerCurrentSlot
import net.airplus.utils.inventory.InventoryManager.chestStealerLastSlot
import net.airplus.utils.inventory.InventoryUtils.countSpaceInInventory
import net.airplus.utils.inventory.InventoryUtils.hasSpaceInInventory
import net.airplus.utils.inventory.SilentHotbar
import net.airplus.utils.render.RenderUtils.drawRect
import net.airplus.utils.timing.TickedActions.awaitTicked
import net.airplus.utils.timing.TickedActions.clickNextTick
import net.airplus.utils.timing.TickedActions.isTicked
import net.airplus.utils.timing.TickedActions.nextTick
import net.airplus.utils.timing.TimeUtils.randomDelay
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.inventory.ContainerChest
import net.minecraft.entity.EntityLiving.getArmorPosition
import net.minecraft.init.Blocks
import net.minecraft.item.ItemArmor
import net.minecraft.item.ItemStack
import net.minecraft.network.play.client.C02PacketUseEntity
import net.minecraft.network.play.client.C07PacketPlayerDigging
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement
import net.minecraft.network.play.client.C0APacketAnimation
import net.minecraft.network.play.client.C0DPacketCloseWindow
import net.minecraft.network.play.server.S2DPacketOpenWindow
import net.minecraft.network.play.server.S2EPacketCloseWindow
import net.minecraft.network.play.server.S30PacketWindowItems
import java.awt.Color
import kotlin.math.sqrt

object ChestStealer : Module("ChestStealer", Category.WORLD) {

    private val smartDelay by boolean("SmartDelay", false)
    private val multiplier by int("DelayMultiplier", 120, 0..500) { smartDelay }
    private val smartOrder by boolean("SmartOrder", true) { smartDelay }

    private val simulateShortStop by boolean("SimulateShortStop", false)

    private val delay by intRange("Delay", 50..50, 0..500) { !smartDelay }
    private val startDelay by intRange("StartDelay", 50..100, 0..500)
    private val closeDelay by intRange("CloseDelay", 50..100, 0..500)

    private val noMove by +InventoryManager.noMoveValue
    private val noMoveAir by +InventoryManager.noMoveAirValue
    private val noMoveGround by +InventoryManager.noMoveGroundValue

    private val chestTitle by boolean("ChestTitle", true)

    private val randomSlot by boolean("RandomSlot", true)

    private val progressBar by boolean("ProgressBar", true).subjective()

    val silentGUI by boolean("SilentGUI", false).subjective()

    // SilentGUI 静默偷取期间拦截与偷取无关的交互数据包：挖掘（C07）、右键使用/放置/格挡（C08）、
    // 攻击（C02）、挥手（C0A）。偷取自身的窗口点击（C0E）、静默换手持（C09）、关容器（C0D）不受影响
    private val stopActions by boolean("StopActions", true) { silentGUI }

    /**
     * 静默偷取进行中（无界面箱子容器已挂载、ChestStealer 正在操作）。
     * KillAura 在此期间暂停攻击，其他模块可用它规避“开箱同时攻击/移动”的检测异常。
     */
    var silentlyStealing = false
        private set

    val highlightSlot by boolean("Highlight-Slot", false) { !silentGUI }.subjective()
    val backgroundColor =
        color("BackgroundColor", Color(128, 128, 128)) { highlightSlot && !silentGUI }.subjective()

    val borderStrength by int("Border-Strength", 3, 1..5) { highlightSlot && !silentGUI }.subjective()
    val borderColor = color("BorderColor", Color(128, 128, 128)) { highlightSlot && !silentGUI }.subjective()

    private val chestDebug by choices("Chest-Debug", arrayOf("Off", "Text", "Notification"), "Off").subjective()
    private val itemStolenDebug by boolean("ItemStolen-Debug", false) { chestDebug != "Off" }.subjective()

    private var progress: Float? = null
        set(value) {
            field = value?.coerceIn(0f, 1f)

            if (field == null)
                easingProgress = 0f
        }

    private var easingProgress = 0f

    private var receivedId: Int? = null

    private var stacks = emptyList<ItemStack?>()

    private suspend fun shouldOperate(): Boolean {
        while (true) {
            if (!handleEvents())
                return false

            if (mc.playerController?.currentGameType?.isSurvivalOrAdventure != true)
                return false

            if (mc.thePlayer?.openContainer !is ContainerChest)
                return false

            if (mc.thePlayer?.openContainer?.windowId != receivedId)
                return false

            // Wait till NoMove check isn't violated
            if (canClickInventory())
                return true

            // If NoMove is violated, wait a tick and check again
            // If there is no delay, very weird things happen: https://www.guilded.gg/CCBlueX/groups/1dgpg8Jz/channels/034be45e-1b72-4d5a-bee7-d6ba52ba1657/chat?messageId=94d314cd-6dc4-41c7-84a7-212c8ea1cc2a
            delay(50)
        }
    }

    suspend fun stealFromChest() {
        if (!handleEvents())
            return

        val thePlayer = mc.thePlayer ?: return

        val container = thePlayer.openContainer as? ContainerChest ?: return

        if (!shouldOperate())
            return

        // Check if chest isn't a custom gui
        if (chestTitle && Blocks.chest.localizedName !in container.lowerChestInventory.name)
            return

        val silentSteal = silentGUI
        if (silentSteal)
            silentlyStealing = true

        try {
            progress = 0f

            delay(startDelay.random().toLong())

            debug("Stealing items..")

            // Go through the chest multiple times, till there are no useful items anymore
            while (true) {
                if (!shouldOperate())
                    return

                // 背包满：IC 开启时按其判定逻辑在箱子容器内直接丢弃背包垃圾，腾出空间继续偷；
                // IC 未开启则关箱并通过通知提醒用户
                if (!hasSpaceInInventory()) {
                    if (InventoryCleaner.handleEvents()) {
                        dropGarbageInChest()

                        // 刷新窗口物品快照，让后续偷取/排序判定基于清理后的背包状态
                        stacks = thePlayer.openContainer.inventory

                        // 清理后仍无可用空间（剩余物品按 IC 判定全部有用）→ 停止偷取并关箱
                        if (!hasSpaceInInventory()) {
                            progress = 1f
                            delay(closeDelay.random().toLong())

                            nextTick { SilentHotbar.resetSlot() }
                            break
                        }
                    } else {
                        hud.addNotification(
                            Notification.informative(this, "背包已满，ChestStealer 无法继续工作", 5000L)
                        )

                        progress = 1f
                        delay(closeDelay.random().toLong())

                        nextTick { SilentHotbar.resetSlot() }
                        break
                    }
                }

                var hasTaken = false
                var inventoryFull = false

                val itemsToSteal = getItemsToSteal()

                run scheduler@{
                    itemsToSteal.forEachIndexed { index, (slot, stack, sortableTo) ->
                        // Wait for NoMove or cancel click
                        if (!shouldOperate()) {
                            nextTick { SilentHotbar.resetSlot() }
                            chestStealerCurrentSlot = -1
                            chestStealerLastSlot = -1
                            return
                        }

                        if (!hasSpaceInInventory()) {
                            chestStealerCurrentSlot = -1
                            chestStealerLastSlot = -1
                            inventoryFull = true
                            return@scheduler
                        }

                        hasTaken = true

                        // Set current slot being stolen for highlighting
                        chestStealerCurrentSlot = slot

                        val stealingDelay = if (smartDelay && index + 1 < itemsToSteal.size) {
                            val dist = squaredDistanceOfSlots(slot, itemsToSteal[index + 1].index)
                            val trueDelay = sqrt(dist.toDouble()) * multiplier
                            randomDelay(trueDelay.toInt(), trueDelay.toInt() + 20)
                        } else {
                            delay.random()
                        }

                        if (itemStolenDebug) debug("item: ${stack.displayName.lowercase()} | slot: $slot | delay: ${stealingDelay}ms")

                        // If target is sortable to a hotbar slot, steal and sort it at the same time, else shift + left-click
                        clickNextTick(slot, sortableTo ?: 0, if (sortableTo != null) 2 else 1) {
                            progress = (index + 1) / itemsToSteal.size.toFloat()

                            if (!AutoArmor.canEquipFromChest())
                                return@clickNextTick

                            val item = stack.item

                            if (item !is ItemArmor || thePlayer.inventory.armorInventory[getArmorPosition(stack) - 1] != null)
                                return@clickNextTick

                            // TODO: should the stealing be suspended until the armor gets equipped and some delay on top of that, maybe toggleable?
                            // Try to equip armor piece from hotbar 1 tick after stealing it
                            nextTick {
                                val hotbarStacks = thePlayer.inventory.mainInventory.take(9)

                                // Can't get index of stack instance, because it is different even from the one returned from windowClick()
                                val newIndex = hotbarStacks.indexOfFirst { it?.getIsItemStackEqual(stack) == true }

                                if (newIndex != -1)
                                    AutoArmor.equipFromHotbarInChest(newIndex, stack)
                            }
                        }

                        delay(stealingDelay.toLong())

                        if (simulateShortStop && Math.random() > 0.75) {
                            val minDelays = randomDelay(150, 300)
                            val maxDelays = randomDelay(minDelays, 500)
                            val randomDelay = randomDelay(minDelays, maxDelays).toLong()

                            delay(randomDelay)
                        }
                    }
                }

                // Wait till all scheduled clicks were sent
                awaitTicked()

                // 背包在偷取中途被填满：跳回外层，先按 IC 逻辑清理背包（或关箱）再继续偷
                if (inventoryFull) {
                    stacks = thePlayer.openContainer.inventory
                    continue
                }

                // If no clicks were sent in the last loop stop searching
                if (!hasTaken) {
                    progress = 1f
                    delay(closeDelay.random().toLong())

                    nextTick { SilentHotbar.resetSlot() }
                    break
                }

                // Before closing the chest, check all items once more, whether server hadn't cancelled some of the actions.
                stacks = thePlayer.openContainer.inventory
            }

            // Wait before the chest gets closed (if it gets closed out of tick loop it could throw npe)
            nextTick {
                chestStealerCurrentSlot = -1
                chestStealerLastSlot = -1
                thePlayer.closeScreen()
                progress = null

                debug("Chest closed")
            }

            awaitTicked()
        } finally {
            if (silentSteal)
                silentlyStealing = false
        }
    }

    /**
     * 背包满时借用 InventoryCleaner 的判定逻辑，在箱子容器内直接丢弃背包中的垃圾。
     * 箱子容器槽位布局：前 [ContainerChest.lowerChestInventory.sizeInventory] 格为箱子，
     * 之后 36 格为玩家背包。mode 4 + button 1 = 丢弃整组，全程无需关箱。
     */
    private suspend fun dropGarbageInChest() {
        val thePlayer = mc.thePlayer ?: return

        val container = thePlayer.openContainer as? ContainerChest ?: return

        val chestSize = container.lowerChestInventory.sizeInventory

        debug("Inventory is full, dropping garbage..")

        for (index in chestSize until chestSize + 36) {
            if (!shouldOperate())
                return

            val stacks = thePlayer.openContainer.inventory
            val stack = stacks.getOrNull(index) ?: continue

            // 与 InventoryCleaner.dropGarbage 一致：只丢弃判定为无用的物品
            if (isStackUseful(stack, stacks))
                continue

            chestStealerCurrentSlot = index

            clickNextTick(index, 1, 4)

            delay(delay.random().toLong())
        }

        chestStealerCurrentSlot = -1
        chestStealerLastSlot = -1

        awaitTicked()
    }

    private fun squaredDistanceOfSlots(from: Int, to: Int): Int {
        fun getCoords(slot: Int): IntArray {
            val x = slot % 9
            val y = slot / 9
            return intArrayOf(x, y)
        }

        val (x1, y1) = getCoords(from)
        val (x2, y2) = getCoords(to)
        return (x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)
    }

    private data class ItemTakeRecord(
        val index: Int,
        val stack: ItemStack,
        val sortableToSlot: Int?
    )

    private fun getItemsToSteal(): List<ItemTakeRecord> {
        val sortBlacklist = BooleanArray(9)

        var spaceInInventory = countSpaceInInventory()

        val itemsToSteal = stacks.dropLast(36)
            .mapIndexedNotNullTo(ArrayList(32)) { index, stack ->
                stack ?: return@mapIndexedNotNullTo null

                if (isTicked(index)) return@mapIndexedNotNullTo null

                val mergeableCount = mc.thePlayer.inventory.mainInventory.sumOf { otherStack ->
                    otherStack ?: return@sumOf 0

                    if (otherStack.isItemEqual(stack) && ItemStack.areItemStackTagsEqual(stack, otherStack))
                        otherStack.maxStackSize - otherStack.stackSize
                    else 0
                }

                val canMerge = mergeableCount > 0
                val canFullyMerge = mergeableCount >= stack.stackSize

                // Clicking this item wouldn't take it from chest or merge it
                if (!canMerge && spaceInInventory <= 0) return@mapIndexedNotNullTo null

                // If stack can be merged without occupying any additional slot, do not take stack limits into account
                // TODO: player could theoretically already have too many stacks in inventory before opening the chest so no more should even get merged
                // TODO: if it can get merged but would also need another slot, it could simulate 2 clicks, one which maxes out the stack in inventory and second that puts excess items back
                if (InventoryCleaner.handleEvents() && !isStackUseful(stack, stacks, noLimits = canFullyMerge))
                    return@mapIndexedNotNullTo null

                var sortableTo: Int? = null

                // If stack can get merged, do not try to sort it, normal shift + left-click will merge it
                if (!canMerge && InventoryCleaner.handleEvents() && InventoryCleaner.sort) {
                    for (hotbarIndex in 0..8) {
                        if (sortBlacklist[hotbarIndex])
                            continue

                        if (!canBeSortedTo(hotbarIndex, stack.item))
                            continue

                        val hotbarStack = stacks.getOrNull(stacks.size - 9 + hotbarIndex)

                        // If occupied hotbar slot isn't already sorted or isn't strictly best, sort to it
                        if (!canBeSortedTo(hotbarIndex, hotbarStack?.item) || !isStackUseful(
                                hotbarStack,
                                stacks,
                                strictlyBest = true
                            )
                        ) {
                            sortableTo = hotbarIndex
                            sortBlacklist[hotbarIndex] = true
                            break
                        }
                    }
                }

                // If stack gets fully merged, no slot in inventory gets occupied
                if (!canFullyMerge) spaceInInventory--

                ItemTakeRecord(index, stack, sortableTo)
            }.also { it ->
                if (randomSlot)
                    it.shuffle()

                // Prioritise armor pieces with lower priority, so that as many pieces can get equipped from hotbar after chest gets closed
                it.sortByDescending { it.stack.item is ItemArmor }

                // Prioritize items that can be sorted
                it.sortByDescending { it.sortableToSlot != null }

                // Fully prioritise armor pieces when it is possible to equip armor while in chest
                if (AutoArmor.canEquipFromChest())
                    it.sortByDescending { it.stack.item is ItemArmor }

                if (smartOrder) {
                    sortBasedOnOptimumPath(it)
                }
            }

        return itemsToSteal
    }

    private fun sortBasedOnOptimumPath(itemsToSteal: MutableList<ItemTakeRecord>) {
        for (i in itemsToSteal.indices) {
            var nextIndex = i
            var minDistance = Int.MAX_VALUE
            var next: ItemTakeRecord? = null
            for (j in i + 1 until itemsToSteal.size) {
                val distance = squaredDistanceOfSlots(itemsToSteal[i].index, itemsToSteal[j].index)
                if (distance < minDistance) {
                    minDistance = distance
                    next = itemsToSteal[j]
                    nextIndex = j
                }
            }
            if (next != null) {
                itemsToSteal[nextIndex] = itemsToSteal[i + 1]
                itemsToSteal[i + 1] = next
            }
        }
    }

    // Progress bar
    val onRender2D = handler<Render2DEvent> { event ->
        if (!progressBar || mc.thePlayer?.openContainer !is ContainerChest)
            return@handler

        val progress = progress ?: return@handler

        val (scaledWidth, scaledHeight) = ScaledResolution(mc)

        val minX = scaledWidth * 0.3f
        val maxX = scaledWidth * 0.7f
        val minY = scaledHeight * 0.75f
        val maxY = minY + 10f

        easingProgress += (progress - easingProgress) / 6f * event.partialTicks

        drawRect(minX - 2, minY - 2, maxX + 2, maxY + 2, Color(200, 200, 200).rgb)
        drawRect(minX, minY, maxX, maxY, Color(50, 50, 50).rgb)
        drawRect(
            minX,
            minY,
            minX + (maxX - minX) * easingProgress,
            maxY,
            Color.HSBtoRGB(easingProgress / 5, 1f, 1f) or 0xFF0000
        )
    }

    // 静默偷取期间冻结移动输入（模拟真实开箱：开箱时不能移动），视角不受影响。
    // InventoryMove 开启时：其 NotInChests / Undetectable 在真实开箱时会冻结输入，
    // 但静默开箱没有 GuiScreen，IM 自身检测不到箱子 → 由这里按 IM 的设置补上同样的冻结；
    // 若两项都未开启，则由 IM 的逻辑决定是否允许移动。
    private val silentMovementKeys = arrayOf(
        mc.gameSettings.keyBindForward,
        mc.gameSettings.keyBindBack,
        mc.gameSettings.keyBindRight,
        mc.gameSettings.keyBindLeft,
        mc.gameSettings.keyBindJump,
        mc.gameSettings.keyBindSprint,
        mc.gameSettings.keyBindSneak
    )

    private val onUpdate = handler<UpdateEvent>(priority = Byte.MIN_VALUE) {
        val thePlayer = mc.thePlayer ?: return@handler

        if (!handleEvents() || !silentGUI || mc.currentScreen != null)
            return@handler

        if (thePlayer.openContainer !is ContainerChest)
            return@handler

        if (InventoryMove.handleEvents()) {
            if (InventoryMove.notInChests || InventoryMove.undetectable)
                silentMovementKeys.forEach { it.pressed = false }

            return@handler
        }

        silentMovementKeys.forEach { it.pressed = false }
    }

    val onPacket = handler<PacketEvent> { event ->
        when (val packet = event.packet) {
            is C0DPacketCloseWindow, is S2EPacketCloseWindow -> {
                receivedId = null
                progress = null
            }

            is S2DPacketOpenWindow -> {
                receivedId = null
                progress = null

                // SilentGUI 会在无界面情况下打开任意 chest 类容器（含服务器自定义界面）。
                // 若开启了 ChestTitle 且不是真箱子，容器会静默挂着且没有任何途径关闭，
                // 在容器于主线程挂载完成后的下一 tick 直接关闭它。
                if (silentGUI && chestTitle && packet.getWindowId() != 0
                    && Blocks.chest.localizedName !in packet.getWindowTitle().unformattedText
                ) {
                    nextTick { mc.thePlayer?.closeScreen() }
                }
            }

            is S30PacketWindowItems -> {
                // Chests never have windowId 0
                val packetWindowId = packet.func_148911_c()

                if (packetWindowId == 0)
                    return@handler

                if (receivedId != packetWindowId) {
                    debug("Chest opened with ${stacks.size} items")
                }

                receivedId = packetWindowId

                stacks = packet.itemStacks.toList()
            }

            // 静默偷取期间不发送与偷取无关的交互数据包：挖掘、右键使用/放置/格挡、攻击、挥手
            is C02PacketUseEntity, is C07PacketPlayerDigging,
            is C08PacketPlayerBlockPlacement, is C0APacketAnimation ->
                if (stopActions && silentlyStealing)
                    event.cancelEvent()
        }
    }

    private fun debug(message: String) {
        if (chestDebug == "Off") return

        when (chestDebug.lowercase()) {
            "text" -> chat(message)
            "notification" -> hud.addNotification(Notification.informative(this, message, 500L))
        }
    }
}
