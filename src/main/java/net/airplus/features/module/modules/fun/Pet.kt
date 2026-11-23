/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.`fun`

import net.airplus.event.PacketEvent
import net.airplus.event.UpdateEvent
import net.airplus.event.WorldEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.minecraft.client.multiplayer.WorldClient
import net.minecraft.entity.EntityLiving
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.passive.EntityOcelot
import net.minecraft.entity.passive.EntityPig
import net.minecraft.entity.passive.EntityVillager
import net.minecraft.entity.passive.EntityWolf
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.network.play.client.C02PacketUseEntity
import net.minecraft.util.MathHelper
import net.minecraft.world.World

/**
 * Pet —— 一群只存在于本机世界的跟随宠物，纯客户端视觉实体。
 *
 * - 「不发数据包」：生成走 world.addEntityToWorld（本地 WorldClient），攻击/交互
 *   宠物时取消 C02PacketUseEntity（LocalNPC 同款），服务器全程无感知；
 * - 「不与玩家发生碰撞」：专用实体子类覆写 canBePushed()=false +
 *   collideWithNearbyEntities()=no-op —— 1.8.9 实体间没有实心碰撞
 *   （Entity.getCollisionBoundingBox 默认 null），唯一的相互影响是
 *   EntityLivingBase#collideWithNearbyEntities 的 applyEntityCollision 推挤，
 *   双向关掉后玩家可穿过宠物、宠物也推不动玩家；跟随停止距离（1.3/1.0 格）
 *   进一步保证包裹盒永不与玩家重叠；
 * - 「跟随」：每个宠物在自身 tick 内（MixinEntityLivingBase → onLivingUpdate HEAD，
 *   prevPos 捕获后、limbSwing/renderYawOffset 消费前）朝目标转向 + moveForward=1，
 *   物理（moveFlying + moveEntity + 重力/摩擦）复用 LocalNPC.tickProxyPhysics 的
 *   vanilla 服务端等价分支，腿部动画与身体朝向随位移自然更新；
 * - 「排队 / 独立」：排队 = 第 0 只跟玩家、第 i 只跟第 i-1 只（队列链）；
 *   独立 = 每只按出生时分配的黄金角环绕点独立跟随玩家；
 * - 朝向目标 lerp 转身、被方块卡住时跳跃、距离 >12 格瞬移兜底，
 *   移速按 Speed 倍率放大基础属性值（可跟上疾跑的玩家）。
 */
object Pet : Module("Pet", Category.FUN, gameDetecting = false) {

    private const val ORBIT_RADIUS = 2.0
    private const val QUEUE_STOP_DIST = 1.3
    private const val ORBIT_STOP_DIST = 1.0
    private const val TELEPORT_DIST_SQ = 12.0 * 12.0

    /** 客户端专用实体 id：与 LocalNPC 的 -1000000000 起始段错开 */
    private var nextFakeId = -1999999999

    private class PetData(val entity: EntityLiving, val baseSpeed: Float, val orbitAngle: Double)

    private val petType by choices("Type", arrayOf("Wolf", "Ocelot", "Villager", "Pig", "Random"), "Wolf")
    private val count by int("Count", 1, 1..10)
    private val followMode by choices("Follow", arrayOf("Queue", "Independent"), "Queue")
    private val speed by float("Speed", 1.0F, 0.5F..2.0F, "x")
    private val delay by float("Delay", 0.5F, 0.1F..10F, "s")

    private val pets = ArrayList<PetData>()
    private var nextSpawn = 0L

    /**
     * MixinEntityLivingBase → EntityLivingBase#onLivingUpdate HEAD，每个宠物
     * 每个自身 tick 调一次（与 LocalNPC.onFakeTick 同一注入点）。
     */
    fun onFakeTick(entity: EntityLivingBase) {
        if (!handleEvents() || pets.isEmpty()) return
        val world = mc.theWorld ?: return
        val data = pets.firstOrNull { it.entity === entity } ?: return
        if (entity.isDead || entity.health <= 0F) return
        tickFollow(data, world)
    }

    val onUpdate = handler<UpdateEvent> {
        val player = mc.thePlayer ?: return@handler
        val world = mc.theWorld ?: return@handler

        // 清理死亡 / 坠入虚空的宠物
        pets.removeAll { data ->
            val dead = data.entity.isDead || data.entity.posY < -64.0
            if (dead) world.removeEntityFromWorld(data.entity.entityId)
            dead
        }

        // 数量调整：调大按 Delay 逐个生成，调小直接移除尾部
        if (pets.size > count) {
            for (data in pets.drop(count)) {
                world.removeEntityFromWorld(data.entity.entityId)
            }
            pets.subList(count, pets.size).clear()
        } else if (pets.size < count && System.currentTimeMillis() >= nextSpawn) {
            nextSpawn = System.currentTimeMillis() + (delay * 1000L).toLong()
            spawn(player, world)
        }
    }

    private fun spawn(player: EntityPlayer, world: WorldClient) {
        val entity: EntityLiving = when (if (petType == "Random") listOf("Wolf", "Ocelot", "Villager", "Pig").random() else petType) {
            "Wolf" -> PetWolf(world)
            "Ocelot" -> PetOcelot(world)
            "Villager" -> PetVillager(world)
            "Pig" -> PetPig(world)
            else -> return
        }

        // 记录未修改的基础移速（movementSpeed 属性值），每 tick 以 base * Speed 重设
        val baseSpeed = entity.getAIMoveSpeed().coerceAtLeast(0.1F)

        // 玩家周围环形偏移（1.5~2.5 格）生成，包裹盒不与玩家重叠
        val angle = Math.random() * Math.PI * 2.0
        val dist = 1.5 + Math.random()
        entity.setLocationAndAngles(
            player.posX + Math.cos(angle) * dist,
            player.posY + 0.5,
            player.posZ + Math.sin(angle) * dist,
            player.rotationYaw,
            0F
        )
        entity.prevRenderYawOffset = entity.renderYawOffset

        // 无 AI：清空全部任务（客户端世界本来不跑 AI，防御性清空保持一致）
        entity.tasks.taskEntries.clear()
        entity.targetTasks.taskEntries.clear()

        val id = nextFakeId--
        world.addEntityToWorld(id, entity)

        // 黄金角（~137.5°）散布独立跟随的环绕点
        pets += PetData(entity, baseSpeed, pets.size * 2.399963229728653)
    }

    private fun tickFollow(data: PetData, world: WorldClient) {
        val player = mc.thePlayer ?: return
        val entity = data.entity

        // 跟随目标：排队 = 前一只（第 0 只跟玩家）；独立 = 玩家周围自己的环绕点
        val queueMode = followMode == "Queue"
        val target: EntityLivingBase = if (queueMode) {
            val idx = pets.indexOf(data)
            if (idx > 0) pets[idx - 1].entity else player
        } else {
            player
        }
        if (target === entity || target.isDead) return

        var tx = target.posX
        var tz = target.posZ
        if (!queueMode) {
            tx += Math.cos(data.orbitAngle) * ORBIT_RADIUS
            tz += Math.sin(data.orbitAngle) * ORBIT_RADIUS
        }

        // 距离过远（玩家疾跑拉开 / 传送）瞬移兜底
        val dy = target.posY - entity.posY
        val distSq = (tx - entity.posX) * (tx - entity.posX) + (tz - entity.posZ) * (tz - entity.posZ) + dy * dy
        if (distSq > TELEPORT_DIST_SQ) {
            entity.setPosition(tx, target.posY, tz)
            entity.motionX = 0.0
            entity.motionY = 0.0
            entity.motionZ = 0.0
            return
        }

        val dx = tx - entity.posX
        val dz = tz - entity.posZ
        val distXZ = MathHelper.sqrt_double(dx * dx + dz * dz)

        // 朝目标平滑转身（头身同步，身体朝向由 bodyHelper 消费 renderYawOffset）
        if (distXZ > 0.05) {
            val targetYaw = Math.toDegrees(Math.atan2(-dx, dz)).toFloat()
            val diff = MathHelper.wrapAngleTo180_float(targetYaw - entity.rotationYaw)
            entity.rotationYaw += diff * 0.5F
            entity.rotationYawHead = entity.rotationYaw
        }

        // 移速按设置倍率放大（setAIMoveSpeed 写 movementSpeed 属性，每 tick 全量重设无叠加）
        entity.setAIMoveSpeed(data.baseSpeed * speed)

        // 距离足够近就停下（包裹盒不与目标重叠 → 无碰撞）
        entity.moveStrafing = 0F
        entity.moveForward = if (distXZ > (if (queueMode) QUEUE_STOP_DIST else ORBIT_STOP_DIST)) 1.0F else 0.0F

        // 被方块卡住时跳跃（0.42 = vanilla 跳跃初速）
        if (entity.onGround && entity.isCollidedHorizontally && entity.moveForward > 0F) {
            entity.motionY = 0.42
        }

        // vanilla 服务端等价物理：moveFlying + moveEntity + 重力/摩擦（与 LocalNPC 共用）
        LocalNPC.tickProxyPhysics(entity, world)
    }

    /**
     * 纯本地：攻击/交互宠物时不向服务器发送任何数据包（服务器不认识假实体 id），
     * 宠物不可被伤害（跟随视觉实体，无血量结算）。
     */
    val onPacket = handler<PacketEvent> { event ->
        val world = mc.theWorld ?: return@handler
        if (pets.isEmpty()) return@handler

        val packet = event.packet as? C02PacketUseEntity ?: return@handler
        val target = runCatching { packet.getEntityFromWorld(world) }.getOrNull() ?: return@handler
        if (pets.any { it.entity === target }) {
            event.cancelEvent()
        }
    }

    val onWorld = handler<WorldEvent> {
        pets.clear()
    }

    override fun onEnable() {
        nextSpawn = 0L
    }

    override fun onDisable() {
        val world = mc.theWorld
        for (data in pets) {
            world?.removeEntityFromWorld(data.entity.entityId)
        }
        pets.clear()
    }

    override val tag
        get() = if (pets.isNotEmpty()) "${pets.size}" else null
}

/**
 * Pet 专用实体：关闭双向实体推挤（canBePushed=false 被推；collideWithNearbyEntities
 * no-op 推人），玩家与宠物互不碰撞。方块碰撞不受影响（走 moveEntity 的 block 分支）。
 */

private class PetWolf(world: World) : EntityWolf(world) {
    override fun canBePushed(): Boolean = false
    override fun collideWithNearbyEntities() {}
}

private class PetOcelot(world: World) : EntityOcelot(world) {
    override fun canBePushed(): Boolean = false
    override fun collideWithNearbyEntities() {}
}

private class PetVillager(world: World) : EntityVillager(world) {
    override fun canBePushed(): Boolean = false
    override fun collideWithNearbyEntities() {}
}

private class PetPig(world: World) : EntityPig(world) {
    override fun canBePushed(): Boolean = false
    override fun collideWithNearbyEntities() {}
}
