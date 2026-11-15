/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.movement

import net.airplus.event.*
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.client.PacketUtils
import net.airplus.utils.inventory.InventoryManager
import net.airplus.utils.inventory.InventoryManager.canClickInventory
import net.airplus.utils.inventory.InventoryManager.hasScheduledInLastLoop
import net.airplus.utils.inventory.InventoryUtils.serverOpenContainer
import net.airplus.utils.inventory.InventoryUtils.serverOpenInventory
import net.minecraft.client.gui.GuiChat
import net.minecraft.client.gui.GuiIngameMenu
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.inventory.GuiChest
import net.minecraft.client.gui.inventory.GuiInventory
import net.minecraft.client.settings.GameSettings
import net.minecraft.client.settings.KeyBinding
import net.minecraft.network.play.client.C0DPacketCloseWindow
import net.minecraft.network.play.client.C0EPacketClickWindow
import org.lwjgl.input.Mouse

object InventoryMove : Module("InventoryMove", Category.MOVEMENT, gameDetecting = false) {

    private val notInChests by boolean("NotInChests", false)
    val aacAdditionPro by boolean("AACAdditionPro", false)
    private val intave by boolean("Intave", false)
    private val saveC0E by boolean("SaveC0E", false)
    private val noSprintWhenClosed by boolean("NoSprintWhenClosed", false) { saveC0E }

    private val isIntave = (mc.currentScreen is GuiInventory || mc.currentScreen is GuiChest) && intave
    private val clickWindowList = ArrayDeque<C0EPacketClickWindow>()

    private val noMove by +InventoryManager.noMoveValue
    private val noMoveAir by +InventoryManager.noMoveAirValue
    private val noMoveGround by +InventoryManager.noMoveGroundValue
    private val undetectable by +InventoryManager.undetectableValue

    // If player violates nomove check and inventory is open, close inventory and reopen it when still
    private val silentlyCloseAndReopen by boolean(
        "SilentlyCloseAndReopen",
        false
    ) { noMove && (noMoveAir || noMoveGround) }

    // Reopen closed inventory just before a click (could flag for clicking too fast after opening inventory)
    private val reopenOnClick by boolean(
        "ReopenOnClick",
        false
    ) { silentlyCloseAndReopen && noMove && (noMoveAir || noMoveGround) }

    private val inventoryMotion by float("InventoryMotion", 1F, 0F..2F)

    // Rise（Ported from Rise GrimInventoryMove）
    // GUI 内疾跑状态由 GrimInventoryMoveDisabler（Disabler.Rise-GrimInventoryMove）读取 managerExtraSprintTicks
    val grim by boolean("Rise", false)
    val managerExtraSprintTicks by int("Rise-ManagerExtraSprintTicks", 9, 0..20) { grim }

    private val affectedBindings = arrayOf(
        mc.gameSettings.keyBindForward,
        mc.gameSettings.keyBindBack,
        mc.gameSettings.keyBindRight,
        mc.gameSettings.keyBindLeft,
        mc.gameSettings.keyBindJump,
        mc.gameSettings.keyBindSprint
    )

    val onUpdate = handler<UpdateEvent>(priority = -1) {
        val player = mc.thePlayer ?: return@handler
        val screen = mc.currentScreen

        if (shouldFreezeInputs(screen)) {
            unPressKeys()
            return@handler
        }

        if (screen is GuiInventory || screen is GuiChest) {
            player.motionX *= inventoryMotion
            player.motionZ *= inventoryMotion
        }

        // Rise：背包/箱子界面打开时取消疾跑（Rise 的 SprintEvent 近似，在 onUpdate 内实现）
        if (grim && (screen is GuiInventory || screen is GuiChest))
            player.isSprinting = false

        if (silentlyCloseAndReopen && screen is GuiInventory) {
            if (canClickInventory(closeWhenViolating = true) && !reopenOnClick) serverOpenInventory = true
        }

        for (affectedBinding in affectedBindings)
            affectedBinding.pressed =
                isButtonPressed(affectedBinding) || affectedBinding == mc.gameSettings.keyBindSprint && Sprint.handleEvents() && Sprint.mode == "Legit" && (!Sprint.onlyOnSprintPress || mc.thePlayer.isSprinting) || affectedBinding == mc.gameSettings.keyBindForward && AutoWalk.handleEvents()
    }

    private fun shouldFreezeInputs(screen: GuiScreen?): Boolean {
        // Don't make player move when chat or ESC menu are open
        if (screen is GuiChat || screen is GuiIngameMenu) return true

        // Undetectable：只冻结服务器可感知的原版界面（背包/箱子等）。
        // 客户端自身的 GUI（各种样式的 ClickGUI、HUD 编辑器、音乐播放器等）
        // 对服务器不可见，移动与不打开 GUI 时无异，因此不冻结输入。
        if (undetectable && screen != null && !screen.javaClass.name.startsWith("net.airplus.")) return true

        if (notInChests && screen is GuiChest) return true

        return false
    }

    val onStrafe = handler<StrafeEvent> {
        if (isIntave) {
            mc.gameSettings.keyBindSneak.pressed = true
        }
    }

    val onJump = handler<JumpEvent> { event ->
        if (isIntave) event.cancelEvent()
    }

    val onClick = handler<ClickWindowEvent> { event ->
        if (!canClickInventory()) event.cancelEvent()
        else if (reopenOnClick) {
            hasScheduledInLastLoop = false
            serverOpenInventory = true
        }
    }

    val onPacket = handler<PacketEvent> { event ->
        val packet = event.packet
        val player = mc.thePlayer ?: return@handler

        if (!saveC0E) return@handler

        if (noSprintWhenClosed) {
            if (clickWindowList.isNotEmpty() && !(serverOpenInventory || serverOpenContainer)) player.isSprinting =
                false

            if (packet is C0DPacketCloseWindow) {
                event.cancelEvent()
                player.isSprinting = false
                if (!player.serverSprintState) PacketUtils.sendPacket(C0DPacketCloseWindow(), false)
            }
        }

        if (serverOpenInventory || serverOpenContainer) {
            if (packet is C0EPacketClickWindow) {
                clickWindowList.add(packet)
                event.cancelEvent()
            }
        } else if (clickWindowList.isNotEmpty()) {
            clickWindowList.forEach {
                PacketUtils.sendPacket(it, false)
            }
            clickWindowList.clear()
        }
    }

    override fun onDisable() {
        for (affectedBinding in affectedBindings) affectedBinding.pressed = isButtonPressed(affectedBinding)
    }

    private fun isButtonPressed(keyBinding: KeyBinding): Boolean {
        return if (keyBinding.keyCode < 0) {
            Mouse.isButtonDown(keyBinding.keyCode + 100)
        } else {
            GameSettings.isKeyDown(keyBinding)
        }
    }

    private fun unPressKeys() {
        affectedBindings.forEach {
            it.pressed = false
        }
    }

    override val tag
        get() = when {
            aacAdditionPro -> "AACAdditionPro"
            inventoryMotion != 1F -> inventoryMotion.toString()
            else -> null
        }
}
