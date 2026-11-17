/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.world

import net.airplus.event.*
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.ui.font.Fonts
import net.airplus.utils.block.BlockUtils.getCenterDistance
import net.airplus.utils.block.BlockUtils.searchBlocks
import net.airplus.utils.block.block
import net.airplus.utils.block.blockById
import net.airplus.utils.block.center
import net.airplus.utils.client.PacketUtils.sendPacket
import net.airplus.utils.extensions.eyes
import net.airplus.utils.render.OnyxBlockAnimator
import net.airplus.utils.render.RenderUtils.drawBlockBox
import net.airplus.utils.render.RenderUtils.drawBlockDamageText
import net.airplus.utils.render.drawOnyxFrame
import net.airplus.utils.rotation.RotationSettings
import net.airplus.utils.rotation.RotationUtils.faceBlock
import net.airplus.utils.rotation.RotationUtils.setTargetRotation
import net.airplus.utils.timing.TickTimer
import net.minecraft.block.Block
import net.minecraft.block.BlockLiquid
import net.minecraft.init.Blocks.air
import net.minecraft.init.Blocks.bedrock
import net.minecraft.item.ItemSword
import net.minecraft.network.play.client.C07PacketPlayerDigging
import net.minecraft.network.play.client.C07PacketPlayerDigging.Action.START_DESTROY_BLOCK
import net.minecraft.network.play.client.C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK
import net.minecraft.util.BlockPos
import net.minecraft.util.AxisAlignedBB
import net.minecraft.util.EnumFacing
import java.awt.Color
import kotlin.math.roundToInt

object Nuker : Module("Nuker", Category.WORLD, gameDetecting = false) {

    /**
     * OPTIONS
     */

    private val allBlocks by boolean("AllBlocks", true)
    private val blocks by block("Block", 1) { !allBlocks }

    private val radius by float("Radius", 5.2F, 1F..6F)
    private val throughWalls by boolean("ThroughWalls", false)
    private val priority by choices("Priority", arrayOf("Distance", "Hardness", "LightOpacity"), "Distance")

    private val options = RotationSettings(this).apply {
        immediate = true

        resetTicksValue.excludeWithState()
        withoutKeepRotation()
    }

    private val layer by boolean("Layer", false)
    private val hitDelay by int("HitDelay", 4, 0..20)
    private val nuke by int("Nuke", 1, 1..20)
    private val nukeDelay by int("NukeDelay", 1, 1..20)

    private val blockProgress by boolean("BlockProgress", true).subjective()

    private val scale by float("Scale", 2F, 1F..6F) { blockProgress }.subjective()
    private val font by font("Font", Fonts.fontSemibold40) { blockProgress }.subjective()
    private val fontShadow by boolean("Shadow", true) { blockProgress }.subjective()

    private val color by color("Color", Color(200, 100, 0)) { blockProgress }.subjective()

    // Onyx 覆盖模式（共享 OnyxBlockAnimator）
    private val overlayMode by choices("OverlayMode", arrayOf("Normal", "Onyx"), "Normal")
    private val overlayColor by color("OverlayColor", Color(255, 60, 60, 90)) { overlayMode == "Onyx" }
    private val overlayAppearance by choices("OverlayAppearance", arrayOf("Fade", "Pop", "Grow"), "Fade") { overlayMode == "Onyx" }
    private val overlayAppear by float("OverlayAppear", 140F, 0F..600F, "ms") { overlayMode == "Onyx" }
    private val overlayFade by float("OverlayFade", 180F, 0F..600F, "ms") { overlayMode == "Onyx" }
    private val overlayMove by float("OverlayMove", 110F, 0F..600F, "ms") { overlayMode == "Onyx" }
    private val overlayPadding by float("OverlayPadding", 0.6F, 0F..5F, "%") { overlayMode == "Onyx" }

    /**
     * VALUES
     */

    private val attackedBlocks = hashSetOf<BlockPos>()
    private var currentBlock: BlockPos? = null
    private var blockHitDelay = 0

    private val nukeTimer = TickTimer()
    private var nukedCount = 0

    var currentDamage = 0F

    // Onyx 覆盖动画器
    private val overlayAnimator = OnyxBlockAnimator()

    /** 目标方块的 SelectedBoundingBox（方块真实模型边界）。 */
    private fun overlayAABB(blockPos: BlockPos): AxisAlignedBB? {
        val world = mc.theWorld ?: return null
        val block = blockPos.block ?: return null
        block.setBlockBoundsBasedOnState(world, blockPos)
        return block.getSelectedBoundingBox(world, blockPos)
    }

    override fun onDisable() {
        overlayAnimator.reset()
    }

    val onWorld = handler<WorldEvent> {
        overlayAnimator.reset()
    }

    val onUpdate = handler<UpdateEvent> {
        // Block hit delay
        if (blockHitDelay > 0 && !FastBreak.handleEvents()) {
            blockHitDelay--
            return@handler
        }

        // Reset bps
        nukeTimer.update()

        if (nukeTimer.hasTimePassed(nukeDelay)) {
            nukedCount = 0
            nukeTimer.reset()
        }

        // Clear blocks
        attackedBlocks.clear()

        val player = mc.thePlayer ?: return@handler
        val world = mc.theWorld ?: return@handler

        val eyes = player.eyes

        if (!mc.playerController.isInCreativeMode) {
            val validBlocks = searchBlocks(radius.roundToInt() + 1, null) { pos, block ->
                if (getCenterDistance(pos) <= radius && validBlock(block)) {
                    if (!allBlocks && Block.getIdFromBlock(block) != blocks) {
                        return@searchBlocks false
                    }

                    // Layer: Break all blocks above you
                    if (layer && pos.y < player.posY) {
                        return@searchBlocks false
                    }

                    // ThroughWalls: Just break blocks in your sight
                    // Raytrace player eyes to block position (through walls check) and check if block is visible
                    throughWalls || world.rayTraceBlocks(eyes, pos.center, false, true, false)?.blockPos == pos
                } else false // Bad block
            }

            val safePos = BlockPos(player).down()

            val sortedBlocks = when (priority) {
                "Distance" -> validBlocks.entries.sortedBy { (pos, _) ->
                    val distance = getCenterDistance(pos)
                    if (pos.x == safePos.x && safePos.y <= pos.y && pos.z == safePos.z) Double.MAX_VALUE - distance // Last block
                    else distance
                }

                "Hardness" -> validBlocks.entries.sortedByDescending { (pos, block) ->
                    val hardness = block.getPlayerRelativeBlockHardness(player, world, pos).toDouble()
                    if (pos.x == safePos.x && safePos.y <= pos.y && pos.z == safePos.z) Double.MIN_VALUE + hardness // Last block
                    else hardness
                }

                "LightOpacity" -> validBlocks.entries.sortedByDescending { (pos, block) ->
                    val opacity = block.getLightOpacity(world, pos).toDouble()
                    if (pos.x == safePos.x && safePos.y <= pos.y && pos.z == safePos.z) Double.MIN_VALUE + opacity // Last block
                    else opacity
                }

                else -> return@handler // Handle invalid priority
            }

            for ((blockPos, block) in sortedBlocks) {
                // Reset current damage in case of block switch
                if (blockPos != currentBlock) currentDamage = 0F

                // Change head rotations to next block
                if (options.rotationsActive) {
                    val rotation = faceBlock(blockPos) ?: return@handler // In case of a mistake. Prevent flag.

                    setTargetRotation(rotation.rotation, options = options)
                }

                // Set next target block
                currentBlock = blockPos
                attackedBlocks += blockPos

                EventManager.call(ClickBlockEvent(blockPos, EnumFacing.DOWN))

                // Start block breaking
                if (currentDamage == 0F) {
                    sendPacket(C07PacketPlayerDigging(START_DESTROY_BLOCK, blockPos, EnumFacing.DOWN))

                    // End block break if able to break instant
                    if (block.getPlayerRelativeBlockHardness(player, world, blockPos) >= 1F) {
                        currentDamage = 0F
                        player.swingItem()
                        mc.playerController.onPlayerDestroyBlock(blockPos, EnumFacing.DOWN)
                        blockHitDelay = hitDelay
                        validBlocks -= blockPos
                        nukedCount++
                        continue // Next break
                    }
                }

                // Break block
                player.swingItem()
                currentDamage += block.getPlayerRelativeBlockHardness(player, world, blockPos)
                world.sendBlockBreakProgress(player.entityId, blockPos, (currentDamage * 10F).toInt() - 1)

                // End of breaking block
                if (currentDamage >= 1F) {
                    sendPacket(C07PacketPlayerDigging(STOP_DESTROY_BLOCK, blockPos, EnumFacing.DOWN))
                    mc.playerController.onPlayerDestroyBlock(blockPos, EnumFacing.DOWN)
                    blockHitDelay = hitDelay
                    currentDamage = 0F
                }

                return@handler // Break out
            }
        } else {
            // Fast creative mode nuker (CreativeStorm option)

            // Unable to break with swords in creative mode
            if (player.heldItem?.item is ItemSword) return@handler

            searchBlocks(radius.roundToInt() + 1, null) { pos, block ->
                if (getCenterDistance(pos) <= radius && validBlock(block)) {
                    // Layer: Break all blocks above you
                    if (layer && pos.y < player.posY) {
                        return@searchBlocks false
                    }

                    // ThroughWalls: Only break blocks in sight
                    // Raytrace player eyes to block position (through walls check) and check if block is visible
                    val isVisible =
                        throughWalls || world.rayTraceBlocks(eyes, pos.center, false, true, false)?.blockPos == pos

                    if (isVisible) {
                        // Instant break block
                        sendPacket(C07PacketPlayerDigging(START_DESTROY_BLOCK, pos, EnumFacing.DOWN))
                        player.swingItem()
                        sendPacket(C07PacketPlayerDigging(STOP_DESTROY_BLOCK, pos, EnumFacing.DOWN))
                        attackedBlocks += pos
                    }

                    return@searchBlocks true
                }

                return@searchBlocks false
            }
        }
    }

    val onRender3D = handler<Render3DEvent> {
        val player = mc.thePlayer ?: return@handler

        if (blocks.blockById == air) return@handler

        val onyx = overlayMode == "Onyx"

        if (onyx) {
            // Onyx 动画覆盖：对当前挖掘目标播放出现/淡出/滑动动画
            val aabb = currentBlock?.let { overlayAABB(it) }
            val settings = OnyxBlockAnimator.Settings(
                overlayAppearance, overlayAppear, overlayFade, overlayMove, overlayPadding
            )
            val frame = overlayAnimator.update(currentBlock, aabb, settings)
            if (frame != null) drawOnyxFrame(frame.box, frame.visibility, overlayColor)
        }

        for (pos in attackedBlocks) {
            if (blockProgress) {
                pos.drawBlockDamageText(
                    currentDamage,
                    font,
                    fontShadow,
                    color.rgb,
                    scale,
                )
            }

            // Onyx 模式下当前目标已由动画层绘制，其余被攻击方块仍用普通框
            if (onyx && pos == currentBlock) continue
            drawBlockBox(pos, Color.RED, true)
        }

        // Safe block
        if (!layer) {
            val safePos = BlockPos(player).down()
            val safeBlock = safePos.block
            if (safeBlock != null && validBlock(safeBlock)) drawBlockBox(safePos, Color.GREEN, true)
        }
    }

    /**
     * Check if [block] is a valid block to break
     */
    private fun validBlock(block: Block) = block != air && block !is BlockLiquid && block != bedrock

}