/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.misc

import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.minecraft.block.Block
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.util.MovingObjectPosition
import net.minecraft.util.Vec3
import net.minecraft.world.World

object GhostHand : Module("GhostHand", Category.MISC) {

    val block by block("Block", 54)
    private val smartCheck by boolean("SmartCheck", true)

    /**
     * 重入保护。
     *
     * 调用链为：isCollidable hook → [shouldBypassCollision] → [isAimingAtTarget] → [World.rayTraceBlocks]，
     * 而射线检测内部遍历方块时会再次查询 isCollidable（即再次进入 hook），不设防会无限递归导致 StackOverflow。
     * 射线检测进行中触发的内部 isCollidable 调用一律走 vanilla 行为（本模块不接管）。
     */
    private val resolving = ThreadLocal.withInitial { false }

    /**
     * 按游戏刻缓存检测结果：isCollidable 会被碰撞与渲染逻辑高频调用，同一名刻内
     * 玩家的位置与朝向一致（partialTick 固定为 1f），结果不会变化，无需重新步进射线。
     */
    private var cachedTick = Long.MIN_VALUE
    private var cachedAiming = false

    /**
     * 仅当准星在交互距离内瞄准目标方块（如箱子）时才生效，
     * 由 [net.airplus.injection.forge.mixins.block.MixinBlock] 的 isCollidable 注入调用。
     *
     * 不能直接依赖 mc.objectMouseOver：玩家走进墙体内部后（GhostHand 的主要使用场景），
     * 射线起点位于墙体方块内，objectMouseOver 会命中玩家所处的墙体方块而不是箱子，
     * 导致检测失败。这里改为手动沿视线步进，跳过所有非目标方块（含玩家所处的方块）。
     */
    fun isAimingAtTarget(): Boolean {
        val player = mc.thePlayer ?: return false
        val world = mc.theWorld ?: return false

        // 重入：射线检测内部的 isCollidable 再次调用进来时直接返回，避免无限递归
        if (resolving.get()) return false

        // 同一名刻内结果不变，直接复用缓存
        val tick = world.totalWorldTime
        if (cachedTick == tick) return cachedAiming

        resolving.set(true)
        val result = try {
            rayTraceForTarget(player, world)
        } finally {
            resolving.set(false)
        }
        cachedTick = tick
        cachedAiming = result
        return result
    }

    private fun rayTraceForTarget(player: EntityPlayer, world: World): Boolean {
        val reach = mc.playerController?.blockReachDistance ?: return false
        val eyes = player.getPositionEyes(1f)
        val look = player.getLook(1f)
        val end = Vec3(
            eyes.xCoord + look.xCoord * reach,
            eyes.yCoord + look.yCoord * reach,
            eyes.zCoord + look.zCoord * reach
        )

        var start = eyes
        // 上限防止极端情况下的死循环（如目标方块 ID 无效）
        repeat(16) {
            val hit = world.rayTraceBlocks(start, end, false, false, false) ?: return false
            if (hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return false
            val pos = hit.blockPos ?: return false
            if (Block.getIdFromBlock(world.getBlockState(pos).block) == block) return true
            // 未命中目标方块：从命中点稍前方继续步进（含玩家所处的墙体方块）
            val d = hit.hitVec.distanceTo(eyes) + 0.05
            start = Vec3(
                eyes.xCoord + look.xCoord * d,
                eyes.yCoord + look.yCoord * d,
                eyes.zCoord + look.zCoord * d
            )
        }
        return false
    }

    /** SmartCheck 关闭时不做瞄准检测，恢复全局穿透 */
    fun shouldBypassCollision(blockId: Int): Boolean =
        handleEvents() && blockId != block && (!smartCheck || isAimingAtTarget())
}
