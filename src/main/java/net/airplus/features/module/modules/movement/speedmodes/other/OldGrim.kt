/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Ported from SilenceFix Speed（触碰实体加速）。
 */
package net.airplus.features.module.modules.movement.speedmodes.other

import net.airplus.features.module.modules.movement.Speed.oldGrimBehindTarget
import net.airplus.features.module.modules.movement.Speed.oldGrimHurtTimeCheck
import net.airplus.features.module.modules.movement.Speed.oldGrimOnlyJump
import net.airplus.features.module.modules.movement.Speed.oldGrimSpeed
import net.airplus.features.module.modules.movement.Speed.oldGrimTargetStrafe
import net.airplus.features.module.modules.movement.speedmodes.SpeedMode
import net.airplus.features.module.modules.combat.KillAura
import net.airplus.features.module.modules.misc.Teams
import net.airplus.features.module.modules.player.Blink
import net.airplus.features.module.modules.world.scaffolds.Scaffold
import net.airplus.utils.extensions.isMoving
import net.airplus.utils.rotation.RotationUtils
import net.minecraft.client.gui.GuiChat
import net.minecraft.client.settings.GameSettings
import net.minecraft.entity.item.EntityArmorStand
import net.minecraft.entity.item.EntityBoat
import net.minecraft.entity.item.EntityMinecart
import net.minecraft.entity.projectile.EntityFishHook

object OldGrim : SpeedMode("OldGrim") {

    private var isMovingRight = true
    private var lastSwitchTime = 0L
    private val switchDelay = 500L

    override fun onDisable() {
        val player = mc.thePlayer ?: return
        mc.gameSettings.keyBindLeft.pressed = GameSettings.isKeyDown(mc.gameSettings.keyBindLeft)
    }

    override fun onMotion() {
        val player = mc.thePlayer ?: return

        if (Scaffold.handleEvents() || mc.currentScreen is GuiChat ||
            player.hurtTime > 6 && oldGrimHurtTimeCheck ||
            (oldGrimOnlyJump && player.onGround)
        ) {
            mc.gameSettings.keyBindLeft.pressed = GameSettings.isKeyDown(mc.gameSettings.keyBindLeft)
            return
        }

        doBoost()
    }

    private fun doBoost() {
        val player = mc.thePlayer ?: return
        val world = mc.theWorld ?: return

        val playerBox = player.entityBoundingBox.expand(1.0, 1.0, 1.0)
        var entityCount = 0

        for (entity in world.loadedEntityList) {
            if ((entity is net.minecraft.entity.EntityLivingBase ||
                        entity is EntityBoat ||
                        entity is EntityMinecart ||
                        entity is EntityFishHook) &&
                entity !is EntityArmorStand &&
                entity.entityId != player.entityId &&
                playerBox.intersectsWith(entity.entityBoundingBox) &&
                entity.entityId != -8 &&
                entity.entityId != -1337 &&
                !Blink.state &&
                !(entity is net.minecraft.entity.EntityLivingBase && Teams.isInYourTeam(entity))
            ) {
                entityCount++
            }
        }

        if (entityCount > 0 && player.isMoving && player.isSprinting) {
            val strafeOffset = minOf(entityCount, 3) * (oldGrimSpeed / 100)

            val yaw = getMoveYaw()

            val mx = -Math.sin(Math.toRadians(yaw.toDouble()))
            val mz = Math.cos(Math.toRadians(yaw.toDouble()))

            if (player.movementInput.moveForward == 0f && player.movementInput.moveStrafe == 0f) {
                if (player.motionX > strafeOffset) {
                    player.motionX -= strafeOffset
                } else if (player.motionX < -strafeOffset) {
                    player.motionX += strafeOffset
                } else {
                    player.motionX = 0.0
                }
                if (player.motionZ > strafeOffset) {
                    player.motionZ -= strafeOffset
                } else if (player.motionZ < -strafeOffset) {
                    player.motionZ += strafeOffset
                } else {
                    player.motionZ = 0.0
                }
            }

            if (mx < 0.0) {
                if (player.motionX > strafeOffset) {
                    player.motionX -= strafeOffset
                } else
                    player.motionX += mx * strafeOffset
            } else if (mx > 0.0) {
                if (player.motionX < -strafeOffset) {
                    player.motionX += strafeOffset
                } else
                    player.motionX += mx * strafeOffset
            }

            if (mz < 0.0) {
                if (player.motionZ > strafeOffset) {
                    player.motionZ -= strafeOffset
                } else
                    player.motionZ += mz * strafeOffset
            } else if (mz > 0.0) {
                if (player.motionZ < -strafeOffset) {
                    player.motionZ += strafeOffset
                } else
                    player.motionZ += mz * strafeOffset
            }

            if (player.hurtTime > 0) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastSwitchTime >= switchDelay) {
                    isMovingRight = !isMovingRight
                    lastSwitchTime = currentTime
                }

                if (isMovingRight) {
                    mc.gameSettings.keyBindRight.pressed = true
                    mc.gameSettings.keyBindLeft.pressed = false
                } else {
                    mc.gameSettings.keyBindLeft.pressed = true
                    mc.gameSettings.keyBindRight.pressed = false
                }
            }
            if (KillAura.target != null && oldGrimBehindTarget) {
                moveBehindTarget()
            }
        } else {
            mc.gameSettings.keyBindLeft.pressed = GameSettings.isKeyDown(mc.gameSettings.keyBindLeft)
            mc.gameSettings.keyBindRight.pressed = GameSettings.isKeyDown(mc.gameSettings.keyBindRight)
        }
    }

    private fun moveBehindTarget() {
        val player = mc.thePlayer ?: return
        val target = KillAura.target ?: return

        val deltaX = target.posX - player.posX
        val deltaZ = target.posZ - player.posZ
        val angle = Math.atan2(deltaZ, deltaX) + Math.PI

        val mx = -Math.sin(angle)
        val mz = Math.cos(angle)

        player.motionX = mx * oldGrimSpeed / 30
        player.motionZ = mz * oldGrimSpeed / 30
    }

    private fun getMoveYaw(): Float {
        val player = mc.thePlayer ?: return 0f
        var moveYaw = player.rotationYaw

        if (player.moveForward != 0f && player.moveStrafing == 0f) {
            moveYaw += if (player.moveForward > 0) 0f else 180f
        } else if (player.moveForward != 0f) {
            if (player.moveForward > 0) {
                moveYaw += if (player.moveStrafing > 0) -45f else 45f
            } else {
                moveYaw -= if (player.moveStrafing > 0) -45f else 45f
            }
            moveYaw += if (player.moveForward > 0) 0f else 180f
        } else if (player.moveStrafing != 0f) {
            moveYaw += if (player.moveStrafing > 0) -90f else 90f
        }

        if (KillAura.target != null && oldGrimTargetStrafe &&
            (!oldGrimOnlyJump || mc.gameSettings.keyBindJump.isKeyDown)
        ) {
            moveYaw = RotationUtils.currentRotation?.yaw ?: moveYaw
        }
        return moveYaw
    }
}
