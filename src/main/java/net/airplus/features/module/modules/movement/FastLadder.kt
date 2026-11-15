/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Ported from EdMoonlight FastLadder (Code By cubk).
 */
package net.airplus.features.module.modules.movement

import net.airplus.event.UpdateEvent
import net.airplus.event.WorldEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.client.PacketUtils.sendPacket
import net.minecraft.block.Block
import net.minecraft.block.BlockLadder
import net.minecraft.network.play.client.C07PacketPlayerDigging
import net.minecraft.util.BlockPos
import net.minecraft.util.EnumFacing

object FastLadder : Module("FastLadder", Category.MOVEMENT) {

    private val blockLadder = ArrayList<BlockPos>()
    private var cancel = false
    private var normalClimb = false

    val onUpdate = handler<UpdateEvent> {
        val player = mc.thePlayer ?: return@handler
        if (mc.netHandler == null) return@handler

        if (!player.isOnLadder) {
            normalClimb = false
            cancel = false
            blockLadder.clear()
        }

        if (player.isOnLadder || cancel) {
            if (!normalClimb || !player.isOnLadder) {
                if (player.isOnLadder && mc.gameSettings.keyBindJump.isKeyDown) {
                    blockLadder.clear()
                    cancel = false
                    normalClimb = true
                } else {
                    for ((pos, block) in searchBlocks(4)) {
                        if (block is BlockLadder && !blockLadder.contains(pos)) {
                            blockLadder.add(pos)
                        }
                    }

                    if (blockLadder.isNotEmpty()) {
                        for (pos in blockLadder) {
                            sendPacket(
                                C07PacketPlayerDigging(C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK, pos, EnumFacing.DOWN),
                                false
                            )
                        }

                        if (player.isOnLadder) {
                            cancel = true
                        }
                    }
                }
            }
        }
    }

    val onWorld = handler<WorldEvent> {
        blockLadder.clear()
    }

    private fun searchBlocks(radius: Int): Map<BlockPos, Block> {
        val blocks = HashMap<BlockPos, Block>()

        val thePlayer = mc.thePlayer ?: return blocks
        val world = mc.theWorld ?: return blocks

        for (x in radius downTo -radius + 1) {
            for (y in radius downTo -radius + 1) {
                for (z in radius downTo -radius + 1) {
                    val blockPos = BlockPos(thePlayer.posX + x, thePlayer.posY + y, thePlayer.posZ + z)
                    val block = world.getBlockState(blockPos)?.block ?: continue
                    blocks[blockPos] = block
                }
            }
        }

        return blocks
    }
}
