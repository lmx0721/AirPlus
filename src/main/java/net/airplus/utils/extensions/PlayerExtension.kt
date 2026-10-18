/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.extensions

import net.airplus.file.FileManager.friendsConfig
import net.airplus.injection.implementations.IMixinEntity
import net.airplus.utils.attack.CPSCounter
import net.airplus.utils.block.set
import net.airplus.utils.block.state
import net.airplus.utils.block.toVec
import net.airplus.utils.client.MinecraftInstance.Companion.mc
import net.airplus.utils.client.PacketUtils.sendPacket
import net.airplus.utils.inventory.SilentHotbar
import net.airplus.utils.movement.MovementUtils
import net.airplus.utils.render.ColorUtils.stripColor
import net.airplus.utils.rotation.Rotation
import net.airplus.utils.rotation.RotationUtils.getFixedSensitivityAngle
import net.airplus.viaversion.vialoadingbase.ViaLoadingBase
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion
import net.minecraft.client.entity.EntityPlayerSP
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.boss.EntityDragon
import net.minecraft.entity.monster.EntityGhast
import net.minecraft.entity.monster.EntityGolem
import net.minecraft.entity.monster.EntityMob
import net.minecraft.entity.monster.EntitySlime
import net.minecraft.entity.passive.EntityAnimal
import net.minecraft.entity.passive.EntityBat
import net.minecraft.entity.passive.EntitySquid
import net.minecraft.entity.passive.EntityVillager
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.ItemBlock
import net.minecraft.item.ItemStack
import net.minecraft.network.Packet
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement
import net.minecraft.util.AxisAlignedBB
import net.minecraft.util.BlockPos
import net.minecraft.util.EnumFacing
import net.minecraft.util.Vec3
import net.minecraftforge.event.ForgeEventFactory

/**
 * Allows to get the distance between the current entity and [entity] from the nearest corner of the bounding box
 */
fun Entity.getDistanceToEntityBox(entity: Entity) = eyes.distanceTo(getNearestPointBB(eyes, entity.hitBox))

fun Entity.getDistanceToBox(box: AxisAlignedBB) = eyes.distanceTo(getNearestPointBB(eyes, box))

fun EntityPlayerSP.isNearEdge(threshold: Float): Boolean {
    val playerPos = Vec3(posX, posY, posZ)
    val blockPos = BlockPos(playerPos)

    val mutable = BlockPos.MutableBlockPos()
    for (x in -3..3) {
        for (z in -3..3) {
            val checkPos = mutable.set(blockPos, x, -1, z)
            if (worldObj.isAirBlock(checkPos)) {
                val checkPosCenter = Vec3(checkPos.x + 0.5, checkPos.y.toDouble(), checkPos.z + 0.5)
                val distance = playerPos.distanceTo(checkPosCenter)
                if (distance <= threshold) {
                    return true
                }
            }
        }
    }
    return false
}

fun getNearestPointBB(eye: Vec3, box: AxisAlignedBB): Vec3 {
    val origin = doubleArrayOf(eye.xCoord, eye.yCoord, eye.zCoord)
    val destMins = doubleArrayOf(box.minX, box.minY, box.minZ)
    val destMaxs = doubleArrayOf(box.maxX, box.maxY, box.maxZ)
    for (i in 0..2) {
        if (origin[i] > destMaxs[i]) origin[i] = destMaxs[i] else if (origin[i] < destMins[i]) origin[i] = destMins[i]
    }
    return Vec3(origin[0], origin[1], origin[2])
}

fun EntityPlayer.getPing() = mc.netHandler.getPlayerInfo(uniqueID)?.responseTime ?: 0

fun Entity.isAnimal() =
    this is EntityAnimal
            || this is EntitySquid
            || this is EntityGolem
            || this is EntityBat

fun Entity.isMob() =
    this is EntityMob
            || this is EntityVillager
            || this is EntitySlime
            || this is EntityGhast
            || this is EntityDragon

fun EntityPlayer.isClientFriend(): Boolean {
    val entityName = name ?: return false

    return friendsConfig.isFriend(stripColor(entityName))
}

var Entity?.rotation
    get() = Rotation(this?.rotationYaw ?: 0f, this?.rotationPitch ?: 0f)
    set(value) {
        this?.run {
            rotationYaw = value.yaw
            rotationPitch = value.pitch
        }
    }
var Entity?.prevRotation
    get() = Rotation(this?.prevRotationYaw ?: 0f, this?.prevRotationPitch ?: 0f)
    set(value) {
        this?.run {
            prevRotationYaw = value.yaw
            prevRotationPitch = value.pitch
        }
    }

val Entity.hitBox: AxisAlignedBB
    get() {
        val borderSize = collisionBorderSize.toDouble()
        return entityBoundingBox.expand(borderSize, borderSize, borderSize)
    }

val Entity.eyes: Vec3
    get() = getPositionEyes(1f)

val Entity.prevPos: Vec3
    get() = Vec3(prevPosX, prevPosY, prevPosZ)

val Entity.currPos: Vec3
    get() = this.positionVector

val Entity.lastTickPos: Vec3
    get() = Vec3(lastTickPosX, lastTickPosY, lastTickPosZ)

val EntityLivingBase?.isMoving: Boolean
    get() = this?.run { moveForward != 0F || moveStrafing != 0F } == true

val EntityPlayerSP.airTicks
    get() = MovementUtils.airTicks

val EntityPlayerSP.groundTicks
    get() = MovementUtils.groundTicks

val Entity.isInLiquid: Boolean
    get() = isInWater || isInLava

fun Entity.setPosAndPrevPos(currPos: Vec3, prevPos: Vec3 = currPos, lastTickPos: Vec3? = null) {
    setPosition(currPos.xCoord, currPos.yCoord, currPos.zCoord)
    prevPosX = prevPos.xCoord
    prevPosY = prevPos.yCoord
    prevPosZ = prevPos.zCoord

    lastTickPos?.let {
        this.lastTickPosX = it.xCoord
        this.lastTickPosY = it.yCoord
        this.lastTickPosZ = it.zCoord
    }
}

fun EntityPlayerSP.setFixedSensitivityAngles(yaw: Float? = null, pitch: Float? = null) {
    if (yaw != null) fixedSensitivityYaw = yaw

    if (pitch != null) fixedSensitivityPitch = pitch
}

var EntityPlayerSP.fixedSensitivityYaw
    get() = getFixedSensitivityAngle(mc.thePlayer.rotationYaw)
    set(yaw) {
        rotationYaw = getFixedSensitivityAngle(yaw, rotationYaw)
    }

var EntityPlayerSP.fixedSensitivityPitch
    get() = getFixedSensitivityAngle(rotationPitch)
    set(pitch) {
        rotationPitch = getFixedSensitivityAngle(pitch.coerceIn(-90f, 90f), rotationPitch)
    }

val IMixinEntity.interpolatedPosition
    get() = Vec3(lerpX, lerpY, lerpZ)

// Makes fixedSensitivityYaw, ... += work
operator fun EntityPlayerSP.plusAssign(value: Float) {
    fixedSensitivityYaw += value
    fixedSensitivityPitch += value
}

fun Entity.interpolatedPosition(start: Vec3, extraHeight: Float? = null) = Vec3(
    start.xCoord + (posX - start.xCoord) * mc.timer.renderPartialTicks,
    start.yCoord + (posY - start.yCoord) * mc.timer.renderPartialTicks + (extraHeight ?: 0f),
    start.zCoord + (posZ - start.zCoord) * mc.timer.renderPartialTicks
)

fun EntityPlayerSP.stopY() {
    motionY = 0.0
}

fun EntityPlayerSP.stopXZ() {
    motionX = 0.0
    motionZ = 0.0
}

fun EntityPlayerSP.stop() {
    stopXZ()
    stopY()
}

/**
 * Its sole purpose is to prevent duplicate sprint state updates.
 */
infix fun EntityLivingBase.setSprintSafely(new: Boolean) {
    if (new == isSprinting) {
        return
    }

    isSprinting = new
}

/**
 * Block placements which have to be sent directly in front of the next rotation packet.
 *
 * 1.9+ anticheats (e.g. Grim) validate a placement against the rotation of the tick it was sent in, but only
 * if the placement packet arrives at the server shortly before the flying packet - otherwise they fall back to
 * the previous tick's rotation because 1.9+ clients can skip ticks. In this client the block placement is sent
 * during [net.airplus.event.GameTickEvent] (before rendering) while the rotation packet is sent
 * during the player tick (after rendering), so the two can be up to a whole frame apart and the placement is
 * validated against a stale rotation, which stacks violations and ends in swallowed blocks on cross-version.
 *
 * Deferring the placement and flushing it right before the rotation packet keeps both next to each other.
 *
 * 仅在 Via 目标协议为 1.12 - 1.12.2 时启用（见 [grimDeferredPlacementActive]），
 * 其余版本直接发包（[queueDeferredPlacement] 内部回落）。
 */
private val deferredPlacements = ArrayDeque<Packet<*>>()

/**
 * Grim 延迟放置适配只在 Via 目标协议为 1.12 - 1.12.2 时生效：
 * 原生 1.8 或其他跨版本协议没有 Grim 的「放置包必须紧邻飞行包」校验差异，
 * 保持直接发包的上游行为。
 */
private val grimDeferredPlacementActive: Boolean
    get() = ViaLoadingBase.getInstance().targetVersion.let {
        it.isNewerThanOrEqualTo(ProtocolVersion.v1_12) && it.isOlderThanOrEqualTo(ProtocolVersion.v1_12_2)
    }

fun queueDeferredPlacement(packet: Packet<*>) {
    if (!grimDeferredPlacementActive) {
        sendPacket(packet)
        return
    }
    deferredPlacements.add(packet)
}

fun flushDeferredPlacements() {
    while (deferredPlacements.isNotEmpty()) {
        sendPacket(deferredPlacements.removeFirst())
    }
}

// Modified mc.playerController.onPlayerRightClick() that sends correct stack in its C08
fun EntityPlayerSP.onPlayerRightClick(
    clickPos: BlockPos, side: EnumFacing, clickVec: Vec3,
    stack: ItemStack? = inventory.mainInventory[SilentHotbar.currentSlot],
    defer: Boolean = false,
): Boolean {
    val controller = mc.playerController ?: return false

    controller.syncCurrentPlayItem()

    if (clickPos !in worldObj.worldBorder)
        return false

    val (facingX, facingY, facingZ) = (clickVec - clickPos.toVec()).toFloatArray()

    val sendClick = {
        val packet = C08PacketPlayerBlockPlacement(clickPos, side.index, stack, facingX, facingY, facingZ)

        if (defer) queueDeferredPlacement(packet) else sendPacket(packet)

        true
    }

    // If player is a spectator, send click and return true
    if (controller.isSpectator)
        return sendClick()

    val item = stack?.item

    if (item?.onItemUseFirst(stack, this, worldObj, clickPos, side, facingX, facingY, facingZ) == true)
        return true

    val blockState = clickPos.state

    // If click had activated a block, send click and return true
    if ((!isSneaking || item == null || item.doesSneakBypassUse(worldObj, clickPos, this))
        && blockState?.block?.onBlockActivated(
            worldObj,
            clickPos,
            blockState,
            this,
            side,
            facingX,
            facingY,
            facingZ
        ) == true
    )
        return sendClick()

    if (item is ItemBlock && !item.canPlaceBlockOnSide(worldObj, clickPos, side, this, stack))
        return false

    sendClick()

    if (stack == null)
        return false

    val prevMetadata = stack.metadata
    val prevSize = stack.stackSize

    return stack.onItemUse(this, worldObj, clickPos, side, facingX, facingY, facingZ).also {
        if (controller.isInCreativeMode) {
            stack.itemDamage = prevMetadata
            stack.stackSize = prevSize
        } else if (stack.stackSize <= 0) {
            ForgeEventFactory.onPlayerDestroyItem(this, stack)
        }
    }
}

// Modified mc.playerController.sendUseItem() that sends correct stack in its C08
fun EntityPlayerSP.sendUseItem(stack: ItemStack, defer: Boolean = false): Boolean {
    if (mc.playerController.isSpectator)
        return false

    mc.playerController?.syncCurrentPlayItem()

    val packet = C08PacketPlayerBlockPlacement(stack)

    if (defer) queueDeferredPlacement(packet) else sendPacket(packet)

    val prevSize = stack.stackSize

    val newStack = stack.useItemRightClick(worldObj, this)

    return if (newStack != stack || newStack.stackSize != prevSize) {
        if (newStack.stackSize <= 0) {
            mc.thePlayer.inventory.mainInventory[SilentHotbar.currentSlot] = null
            ForgeEventFactory.onPlayerDestroyItem(mc.thePlayer, newStack)
        } else
            mc.thePlayer.inventory.mainInventory[SilentHotbar.currentSlot] = newStack

        true
    } else false
}

fun EntityPlayerSP.tryJump() {
    if (!mc.gameSettings.keyBindJump.isKeyDown) {
        jump()
    }
}

inline fun EntityPlayerSP.attackEntityWithModifiedSprint(
    entity: Entity, affectMovementBySprint: Boolean? = null, swing: () -> Unit
) {
    swing()

    MovementUtils.affectSprintOnAttack = affectMovementBySprint

    try {
        mc.playerController?.attackEntity(this, entity)
    } catch (any: Exception) {
        // Unlikely to happen, but if it does, we just want to make sure affectSprintOnAttack is null.
        any.printStackTrace()
    }

    MovementUtils.affectSprintOnAttack = null

    CPSCounter.registerClick(CPSCounter.MouseButton.LEFT)
}
