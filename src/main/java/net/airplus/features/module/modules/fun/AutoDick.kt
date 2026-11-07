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
import net.airplus.utils.block.BlockUtils
import net.airplus.utils.block.canBeClicked
import net.airplus.utils.block.center
import net.airplus.utils.block.isReplaceable
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
import net.airplus.utils.rotation.RotationUtils.rotationDifference
import net.airplus.utils.rotation.RotationUtils.setTargetRotation
import net.airplus.utils.rotation.RotationUtils.toRotation
import net.minecraft.block.BlockBush
import net.minecraft.init.Blocks
import net.minecraft.item.ItemBlock
import net.minecraft.item.ItemStack
import net.minecraft.network.play.client.C0APacketAnimation
import net.minecraft.network.play.client.C07PacketPlayerDigging
import net.minecraft.network.play.client.C07PacketPlayerDigging.Action.START_DESTROY_BLOCK
import net.minecraft.network.play.client.C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK
import net.minecraft.util.BlockPos
import net.minecraft.util.EnumFacing
import net.minecraft.util.MathHelper
import net.minecraft.util.MovingObjectPosition
import net.minecraft.util.Vec3
import net.minecraft.util.Vec3i
import net.minecraft.world.World
import net.minecraftforge.event.ForgeEventFactory
import java.awt.Color

/**
 * AutoDick —— 按玩家朝向在正前方一格自动建造一道小拱门：
 *
 *   （空）（方块1）（空）
 *   （空）（方块2）（空）
 *   （方块3）（方块4）（方块5）
 *
 * 底排 3/4/5 → 中层 2 → 顶层 1，建成后自动挖掉正前方的方块 4，
 * 留出一道可通行的小拱门。
 *
 * 放置逻辑（本次重写的核心，Scaffold 同款思路）：
 * 1. 目标选取：所有未完成的方块里取「离玩家最近」的一个（悬空的会因找不到
 *    支撑面自然被跳过，不会抢先放置）；
 * 2. 支撑搜索：枚举目标六个方向的邻块，只接受 [canBeClicked] 的可点击实心方块
 *    （支持台阶 / 部分方块作为支撑）；
 * 3. 瞄准点搜索：在支撑面（朝向目标的那一面）上做 3×3 网格采样，每个采样点要求
 *    - 在触及距离内；
 *    - 眼睛到该点视线无遮挡（不被第三方方块挡住）；
 *    - 以该点计算的旋转（经 sensitivity 修正）做射线检测，确切命中支撑块
 *      及其对应侧面（保证不隔墙放置、点到的就是能放上去的面）；
 * 4. 择优：在所有可行解中取「与当前视角旋转差」最小的一个 —— 转头量最小，
 *    动作最自然，也降低被反作弊盯上的概率。
 */
object AutoDick : Module("AutoDick", Category.FUN, gameDetecting = false) {

    private val placeDelayTicks by int("PlaceDelay", 4, 1..20)
    private val buildDistance by int("BuildDistance", 1, 1..6)
    private val swing by boolean("Swing", true)
    private val switchMode by choices("SwitchMode", arrayOf("Off", "Spoof"), "Spoof")
    private val mark by boolean("Mark", true)

    private val options = RotationSettings(this)

    // 建造顺序：3、4、5、2、1；完成后挖掉索引 1（方块 4）
    private val digIndex = 1

    /** 支撑面瞄准点采样网格（沿面两个切向轴的偏移，0.5 = 面中心） */
    private val faceSamples = doubleArrayOf(0.25, 0.5, 0.75)

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

        // 建造目标里取离玩家最近的空位（无支撑的自然找不到放置点，不会抢先放置）
        val nextMissing = order.filter { isFree(it) }.minByOrNull { BlockUtils.getCenterDistance(it) }

        if (nextMissing == null) {
            // 全部建成 → 挖掉方块 4
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

        val placeRotation = findPlaceRotation(player, world, nextMissing) ?: return@handler

        if (options.rotationsActive) {
            setTargetRotation(placeRotation.rotation, options, if (options.keepRotation) options.resetTicks else 1)
        }

        if (place(player, blockStack(player), placeRotation)) {
            placeCooldown = placeDelayTicks
        }
    }

    // ------------------------------------------------------------------
    // 形状坐标（随玩家朝向实时刷新）
    // ------------------------------------------------------------------
    /**
     * 锚点 = 玩家所在方块 + 玩家面对的水平 EnumFacing（0°=南、90°=西）。
     * 与连续坐标不同，这样算出的三块底排永远是相邻的三个不同方块，
     * 站在两个方块中间（脚正好跨边界）也不会出现漏格。
     * BuildDistance：距离玩家 1~6 格处建造（受实际放置距离上限约束）。
     */
    private fun buildOrder(player: net.minecraft.entity.player.EntityPlayer): List<BlockPos> {
        val base = BlockPos(
            MathHelper.floor_double(player.posX),
            MathHelper.floor_double(player.posY),
            MathHelper.floor_double(player.posZ)
        )
        val facing = EnumFacing.getHorizontal(MathHelper.floor_double(player.rotationYaw / 90.0 + 0.5) and 3)
        val front = base.offset(facing, buildDistance)
        val right = facing.rotateY()

        return listOf(
            front.offset(right.opposite), // 方块 3（左）
            front,                        // 方块 4（正前方一格，完成后挖掉）
            front.offset(right),          // 方块 5（右）
            front.up(),                   // 方块 2
            front.up(2)                   // 方块 1
        )
    }

    private fun isFree(pos: BlockPos): Boolean {
        val world = mc.theWorld ?: return false
        val block = world.getBlockState(pos).block
        return block == Blocks.air || block.isReplaceable(world, pos)
    }

    // ------------------------------------------------------------------
    // 放置点 / 瞄准点搜索
    // ------------------------------------------------------------------
    /**
     * 为目标方块寻找最佳 [PlaceRotation]：
     * 六方向支撑邻块 × 支撑面 3×3 采样点，全部可行解里取旋转差最小的。
     * 找不到任何可见可点击的支撑面时返回 null（本 tick 不放置）。
     */
    private fun findPlaceRotation(
        player: net.minecraft.entity.player.EntityPlayer,
        world: World,
        target: BlockPos
    ): PlaceRotation? {
        val reach = mc.playerController?.blockReachDistance ?: return null
        val eyes = player.eyes
        val currRotation = RotationUtils.currentRotation ?: Rotation(player.rotationYaw, player.rotationPitch)

        var best: PlaceRotation? = null
        var bestDiff = Float.MAX_VALUE

        for (side in EnumFacing.values()) {
            val neighbor = target.offset(side)
            // 只把可点击的实心方块当支撑（台阶/部分方块也算，隔墙/容器/悬空全排除）
            if (!neighbor.canBeClicked()) continue

            // 支撑面 = 邻块朝向目标的那一面，外法线指向目标
            val normal = side.opposite.directionVec

            for (vec in faceSamplePoints(neighbor, normal)) {
                // 触及距离
                if (eyes.distanceTo(vec) > reach) continue

                // 视线遮挡：从眼睛打到面内一点（沿法线回缩 0.01 避免端点落在
                // 邻块边界上被误判命中），中途撞到任何方块都视为被挡
                val losEnd = vec.addVector(
                    normal.x * 0.01, normal.y * 0.01, normal.z * 0.01
                )
                if (world.rayTraceBlocks(eyes, losEnd, false, true, false) != null) continue

                // 用 sensitivity 修正后的旋转做最终命中验证
                val rotation = toRotation(vec, false, player).fixedSensitivity()
                val raytrace = performBlockRaytrace(rotation, reach) ?: continue

                // 必须确切命中支撑块与对应侧面（不隔墙放置、不点到别的面）
                if (raytrace.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK ||
                    raytrace.blockPos != neighbor || raytrace.sideHit != side.opposite
                ) continue

                // 择优：转头量最小
                val diff = rotationDifference(rotation, currRotation)
                if (diff < bestDiff) {
                    bestDiff = diff
                    best = PlaceRotation(
                        net.airplus.utils.block.PlaceInfo(raytrace.blockPos, raytrace.sideHit, raytrace.hitVec),
                        rotation
                    )
                }
            }
        }

        return best
    }

    /**
     * 支撑面采样点：面中心（法线方向推 0.5）沿两个切向轴做 3×3 网格采样。
     * 多点采样解决「面中心被挡 / 角度刁钻」时单点判定失败的问题，
     * 同时给出多个候选旋转供择优。
     */
    private fun faceSamplePoints(neighbor: BlockPos, normal: Vec3i): List<Vec3> {
        val (tangentU, tangentV) = when {
            normal.x != 0 -> Vec3i(0, 1, 0) to Vec3i(0, 0, 1)
            normal.y != 0 -> Vec3i(1, 0, 0) to Vec3i(0, 0, 1)
            else -> Vec3i(1, 0, 0) to Vec3i(0, 1, 0)
        }

        val points = ArrayList<Vec3>(faceSamples.size * faceSamples.size)
        for (u in faceSamples) {
            for (v in faceSamples) {
                points.add(
                    Vec3(
                        neighbor.x + 0.5 + normal.x * 0.5 + tangentU.x * (u - 0.5) + tangentV.x * (v - 0.5),
                        neighbor.y + 0.5 + normal.y * 0.5 + tangentU.y * (u - 0.5) + tangentV.y * (v - 0.5),
                        neighbor.z + 0.5 + normal.z * 0.5 + tangentU.z * (u - 0.5) + tangentV.z * (v - 0.5)
                    )
                )
            }
        }
        return points
    }

    private fun blockStack(player: net.minecraft.entity.player.EntityPlayer): ItemStack? {
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
        world: World,
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
            val rotation = toRotation(pos.center, false, player)
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
