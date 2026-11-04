/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.`fun`

import net.airplus.event.Render3DEvent
import net.airplus.event.UpdateEvent
import net.airplus.event.WorldEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.attack.CPSCounter
import net.airplus.utils.block.PlaceInfo
import net.airplus.utils.block.center
import net.airplus.utils.client.PacketUtils.sendPacket
import net.airplus.utils.extensions.eyes
import net.airplus.utils.extensions.onPlayerRightClick
import net.airplus.utils.inventory.InventoryUtils
import net.airplus.utils.inventory.SilentHotbar
import net.airplus.utils.inventory.inventorySlot
import net.airplus.utils.rotation.PlaceRotation
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.rotation.Rotation
import net.airplus.utils.rotation.RotationSettings
import net.airplus.utils.rotation.RotationUtils
import net.airplus.utils.rotation.RotationUtils.getVectorForRotation
import net.airplus.utils.rotation.RotationUtils.setTargetRotation
import net.minecraft.block.BlockBush
import net.minecraft.init.Blocks
import net.minecraft.item.ItemBlock
import net.minecraft.item.ItemStack
import net.minecraft.network.play.client.C07PacketPlayerDigging.Action.START_DESTROY_BLOCK
import net.minecraft.network.play.client.C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK
import net.minecraft.network.play.client.C0APacketAnimation
import net.minecraft.network.play.client.C07PacketPlayerDigging
import net.minecraft.util.BlockPos
import net.minecraft.util.EnumFacing
import net.minecraft.util.MathHelper
import net.minecraft.util.MovingObjectPosition
import net.minecraft.util.Vec3
import net.minecraftforge.event.ForgeEventFactory
import java.awt.Color

/**
 * AutoDick —— 按玩家朝向在正前方一格自动建造：
 *
 *   （空）（方块1）（空）
 *   （空）（方块2）（空）
 *   （方块3）（方块4）（方块5）
 *
 * 底排 3/4/5 → 中层 2 → 顶层 1（建造顺序 3-4-5-2-1），
 * 建成后自动挖掉正前方的方块 4，留出一道可通行的小拱门。
 *
 * - 形状对齐到方块网格：以「玩家脚下方块 + 面对的水平朝向」为锚点，
 *   站在两个方块中间（脚跨方块边界）时形状不会跑偏/漏格；
 * - 放置射线判定用 Scaffold 同款参数，确保瞄准支撑面时能确切命中支撑块，
 *   找到 PlaceRotation 后先转头再放置。
 */
object AutoDick : Module("AutoDick", Category.FUN, gameDetecting = false) {

    private val placeDelayTicks by int("PlaceDelay", 4, 1..20)
    private val swing by boolean("Swing", true)
    private val switchMode by choices("SwitchMode", arrayOf("Off", "Spoof"), "Spoof")
    private val mark by boolean("Mark", true)

    private val options = RotationSettings(this)

    // 建造顺序：3、4、5、2、1；完成后挖掉索引 1（方块 4）
    private val digIndex = 1

    private var placeCooldown = 0
    private var currentDamage = 0F
    private var breaking: BlockPos? = null

    // 上一次完成的「建 → 挖 4」周期对应的形状坐标：
    // 蹲在原地不动时不无限重建-重挖，只有形状被外力改变或玩家转向新位置后才开新一轮
    private var doneShape: List<BlockPos>? = null

    override fun onEnable() {
        reset()
        doneShape = null
    }

    override fun onDisable() {
        reset()
        doneShape = null
        SilentHotbar.resetSlot(this, true)
    }

    val onWorld = handler<WorldEvent> {
        reset()
        doneShape = null
    }

    private fun reset() {
        placeCooldown = 0
        currentDamage = 0F
        breaking = null
    }

    val onUpdate = handler<UpdateEvent> {
        val player = mc.thePlayer ?: return@handler
        val world = mc.theWorld ?: return@handler

        val order = buildOrder(player)

        // 上个周期刚挖完且形状未变 → 这一轮已完成，原地待机不再循环建/挖
        if (doneShape != null && order == doneShape) return@handler

        // 全部建成 → 挖掉方块 4
        val nextMissing = order.firstOrNull { pos -> isFree(pos) }

        if (nextMissing == null) {
            dig(player, world, order[digIndex], order)
            return@handler
        }

        // 建造目标又有空位（刚被破坏等）→ 回到建造流程
        if (breaking != null) reset()
        if (doneShape != null && order != doneShape) doneShape = null

        if (placeCooldown > 0) {
            placeCooldown--
            return@handler
        }

        val placeRotation = pickPlaceRotation(player, world, nextMissing) ?: return@handler

        if (options.rotationsActive)
            setTargetRotation(placeRotation.rotation, options, if (options.keepRotation) options.resetTicks else 1)

        if (place(player, isEnabledBlockStack(player, nextMissing), placeRotation))
            placeCooldown = placeDelayTicks
    }

    private fun isFree(pos: BlockPos): Boolean {
        val world = mc.theWorld ?: return false
        val block = world.getBlockState(pos).block
        return block == Blocks.air || block.isReplaceable(world, pos)
    }

    // ------------------------------------------------------------------
    // 形状坐标（随玩家朝向实时刷新）
    // ------------------------------------------------------------------
    /**
     * 锚点 = 玩家所在方块 + 玩家面对的水平 EnumFacing（0°=南、90°=西）。
     * 与连续坐标不同，这样算出的三块底排永远是相邻的三个不同方块，
     * 站在两个方块中间（脚正好跨边界）也不会出现漏格。
     */
    private fun buildOrder(player: net.minecraft.entity.player.EntityPlayer): List<BlockPos> {
        val base = BlockPos(
            MathHelper.floor_double(player.posX),
            MathHelper.floor_double(player.posY),
            MathHelper.floor_double(player.posZ)
        )
        val facing = EnumFacing.getHorizontal(MathHelper.floor_double(player.rotationYaw / 90.0 + 0.5) and 3)
        val front = base.offset(facing)
        val right = facing.rotateY()

        return listOf(
            front.offset(right.opposite), // 方块 3（左）
            front,                        // 方块 4（正前方一格，完成后挖掉）
            front.offset(right),          // 方块 5（右）
            front.up(),                   // 方块 2
            front.up(2)                   // 方块 1
        )
    }

    // ------------------------------------------------------------------
    // 放置
    // ------------------------------------------------------------------
    /**
     * 为目标块找一个支撑邻面（六方向中存在可点击的实心邻块），
     * 返回 PlaceRotation（瞄准该面中心），找不到返回 null。
     */
    private fun pickPlaceRotation(
        player: net.minecraft.entity.player.EntityPlayer,
        world: net.minecraft.world.World,
        target: BlockPos
    ): PlaceRotation? {
        val reach = mc.playerController?.blockReachDistance ?: return null

        for (dir in EnumFacing.values()) {
            val neighbor = target.offset(dir)
            if (!world.isBlockNormalCube(neighbor, false)) continue

            // 目标面中心：邻块中心 + 指向目标方向的一半块长
            val toTarget = Vec3(
                (target.x - neighbor.x).toDouble(),
                (target.y - neighbor.y).toDouble(),
                (target.z - neighbor.z).toDouble()
            ).normalize()
            val targetVec = neighbor.center.addVector(toTarget.xCoord * 0.5, toTarget.yCoord * 0.5, toTarget.zCoord * 0.5)

            val rotation = RotationUtils.toRotation(targetVec, false, player)
            // 同 Scaffold：越过面中心继续打 4.5 格（射线穿进支撑块内部），
            // 才能确切取到 blockPos == neighbor 的命中点（面中心恰好在边界平面上）
            val raytrace = performBlockRaytrace(rotation, reach) ?: continue

            // 视线必须落在同一个邻块上（保证不隔墙放置）
            if (raytrace.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || raytrace.blockPos != neighbor) continue

            return PlaceRotation(
                PlaceInfo(raytrace.blockPos, raytrace.sideHit, raytrace.hitVec),
                Rotation(rotation.yaw, rotation.pitch)
            )
        }
        return null
    }

    private fun isEnabledBlockStack(player: net.minecraft.entity.player.EntityPlayer, target: BlockPos): ItemStack? {
        var stack = player.inventorySlot(SilentHotbar.currentSlot + 36).stack

        if (stack == null || stack.item !is ItemBlock || (stack.item as ItemBlock).block is BlockBush
            || InventoryUtils.BLOCK_BLACKLIST.contains((stack.item as ItemBlock).block) || stack.stackSize <= 0
        ) {
            val blockSlot = InventoryUtils.findBlockInHotbar() ?: return null

            if (switchMode != "Off") {
                SilentHotbar.selectSlotSilently(
                    this,
                    blockSlot,
                    immediate = true,
                    render = false,
                    resetManually = true
                )
            }

            stack = player.inventorySlot(blockSlot).stack
        }

        return stack
    }

    private fun place(player: net.minecraft.client.entity.EntityPlayerSP, stack: ItemStack?, placeRotation: PlaceRotation): Boolean {
        if (stack == null) return false

        val clickedSuccessfully = player.onPlayerRightClick(
            placeRotation.placeInfo.blockPos, placeRotation.placeInfo.enumFacing, placeRotation.placeInfo.vec3, stack,
        )

        if (clickedSuccessfully) {
            if (swing) player.swingItem() else sendPacket(C0APacketAnimation())

            val prevSize = stack.stackSize
            if (stack.stackSize <= 0) {
                player.inventory.mainInventory[SilentHotbar.currentSlot] = null
                ForgeEventFactory.onPlayerDestroyItem(player, stack)
            } else if (stack.stackSize != prevSize || mc.playerController.isInCreativeMode)
                mc.entityRenderer.itemRenderer.resetEquippedProgress()

            CPSCounter.registerClick(CPSCounter.MouseButton.RIGHT)
        }

        return clickedSuccessfully
    }

    // ------------------------------------------------------------------
    // 挖掘（同 Nuker 的本地伤害积累：START → 伤害攒满 → STOP）
    // ------------------------------------------------------------------
    private fun dig(
        player: net.minecraft.entity.player.EntityPlayer,
        world: net.minecraft.world.World,
        pos: BlockPos,
        order: List<BlockPos>
    ) {
        val state = world.getBlockState(pos)
        val block = state.block

        if (block == Blocks.air || block.isReplaceable(world, pos)) {
            currentDamage = 0F
            breaking = null

            // 方块 4 已挖掉 → 记录本周期完成
            doneShape = order
            return
        }

        if (breaking != pos) {
            reset()
            breaking = pos
        }

        // 转向要挖的方块
        if (options.rotationsActive) {
            val rotation = RotationUtils.toRotation(pos.center, false, player)
            setTargetRotation(rotation, options, if (options.keepRotation) options.resetTicks else 1)
        }

        val hardness = block.getPlayerRelativeBlockHardness(player, world, pos)

        if (currentDamage == 0F) {
            sendPacket(C07PacketPlayerDigging(START_DESTROY_BLOCK, pos, EnumFacing.DOWN))

            // 能瞬间挖掉的方块直接完成
            if (hardness >= 1F) {
                currentDamage = 0F
                player.swingItem()
                mc.playerController.onPlayerDestroyBlock(pos, EnumFacing.DOWN)
                breaking = null

                // 方块 4 已挖掉 → 记录本周期完成
                doneShape = order
                return
            }
        }

        player.swingItem()
        currentDamage += hardness
        world.sendBlockBreakProgress(player.entityId, pos, (currentDamage * 10F).toInt() - 1)

        if (currentDamage >= 1F) {
            sendPacket(C07PacketPlayerDigging(STOP_DESTROY_BLOCK, pos, EnumFacing.DOWN))
            mc.playerController.onPlayerDestroyBlock(pos, EnumFacing.DOWN)
            currentDamage = 0F
            breaking = null

            // 方块 4 已挖掉 → 记录本周期完成
            doneShape = order
        }
    }

    // ------------------------------------------------------------------
    // 目标方块渲染
    // ------------------------------------------------------------------
    val onRender3D = handler<Render3DEvent> {
        val player = mc.thePlayer ?: return@handler
        val world = mc.theWorld ?: return@handler

        val order = buildOrder(player)

        if (breaking != null) {
            RenderUtils.drawBlockBox(breaking!!, Color(240, 60, 60, 90), false)
            return@handler
        }

        if (!mark) return@handler

        for ((index, pos) in order.withIndex()) {
            val color = if (isFree(pos)) Color(0, 168, 89, 82) else Color(68, 117, 255, 72)
            // 顺序索引越高越靠后完成
            RenderUtils.drawBlockBox(pos, when (index) {
                digIndex -> Color(255, 210, 60, 95)
                else -> color
            }, false)
        }
    }

    private fun performBlockRaytrace(rotation: Rotation, maxReach: Float): MovingObjectPosition? {
        val player = mc.thePlayer ?: return null
        val world = mc.theWorld ?: return null

        val eyes = player.eyes
        val rotationVec = getVectorForRotation(rotation)

        val reach = eyes.addVector(rotationVec.xCoord * maxReach, rotationVec.yCoord * maxReach, rotationVec.zCoord * maxReach)

        return world.rayTraceBlocks(eyes, reach, false, false, true)
    }
}
