/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.player

import net.airplus.event.ClickBlockEvent
import net.airplus.event.GameTickEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.inventory.SilentHotbar

object AutoTool : Module("AutoTool", Category.PLAYER, subjective = true, gameDetecting = false) {

    private val switchBack by boolean("SwitchBack", false)
    private val onlySneaking by boolean("OnlySneaking", false)
    // 静默切换：开启后切换 AutoTool 时不展示真实的模式物品（客户端视角保持原物品）
    private val silent by boolean("Silent", false)

    val onGameTick = handler<GameTickEvent> {
        if (!switchBack || mc.gameSettings.keyBindAttack.isKeyDown)
            return@handler

        SilentHotbar.resetSlot(this)
    }

    val onClick = handler<ClickBlockEvent> { event ->
        val player = mc.thePlayer ?: return@handler

        val block = mc.theWorld.getBlockState(event.clickedBlock ?: return@handler).block

        if (onlySneaking && !player.isSneaking || block.getBlockHardness(mc.theWorld, event.clickedBlock) == 0f)
            return@handler

        var fastest = 1f

        val slot = (0..8).maxByOrNull {
            val item = player.inventory.getStackInSlot(it) ?: return@maxByOrNull 1f

            item.getStrVsBlock(block).also { speed -> fastest = fastest.coerceAtLeast(speed) }
        } ?: return@handler

        if (fastest == (player.currentEquippedItem?.getStrVsBlock(block) ?: 1f))
            return@handler

        SilentHotbar.selectSlotSilently(this, slot, render = !silent, resetManually = true)
    }

}