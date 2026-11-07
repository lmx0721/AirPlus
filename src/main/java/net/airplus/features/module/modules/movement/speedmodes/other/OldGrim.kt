/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Ported from MoonLight Speed（原 EntityCollide 模式，触碰实体加速）。
 */
package net.airplus.features.module.modules.movement.speedmodes.other

import net.airplus.features.module.modules.movement.Speed.oldGrimExpand
import net.airplus.features.module.modules.movement.speedmodes.SpeedMode
import net.airplus.utils.rotation.RotationUtils
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.item.EntityArmorStand
import kotlin.math.cos
import kotlin.math.sin

object OldGrim : SpeedMode("OldGrim") {

    override fun onUpdate() {
        val player = mc.thePlayer ?: return
        val world = mc.theWorld ?: return

        // 仅在按住前进键时生效，后退/横移不加速
        if (player.moveForward <= 0.0f)
            return

        // 统计与自身碰撞箱（默认扩大 0.8，Expand 开启时扩大 1.0）相交的存活实体
        val box = if (oldGrimExpand)
            player.entityBoundingBox.expand(1.0, 1.0, 1.0)
        else
            player.entityBoundingBox.expand(0.8, 0.8, 0.8)

        var collisions = 0
        for (entity in world.loadedEntityList) {
            if (canCauseSpeed(player, entity) && box.intersectsWith(entity.entityBoundingBox)) {
                collisions++
            }
        }

        // 静默旋转时以当前旋转朝向推进，否则以玩家视角朝向
        val rotation = RotationUtils.currentRotation
        val yaw = Math.toRadians((rotation?.yaw ?: player.rotationYaw).toDouble())

        val boost = 0.078 * collisions
        player.addVelocity(-sin(yaw) * boost, 0.0, cos(yaw) * boost)
    }

    private fun canCauseSpeed(player: net.minecraft.entity.Entity, entity: net.minecraft.entity.Entity): Boolean =
        entity !== player && entity is EntityLivingBase && entity !is EntityArmorStand
}
