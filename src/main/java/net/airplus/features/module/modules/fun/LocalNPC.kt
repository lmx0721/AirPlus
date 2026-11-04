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
import net.minecraft.entity.EntityLiving
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.SharedMonsterAttributes
import net.minecraft.entity.monster.*
import net.minecraft.entity.passive.*
import net.minecraft.network.play.client.C02PacketUseEntity
import net.minecraft.util.BlockPos
import net.minecraft.util.DamageSource
import net.minecraft.util.MathHelper

/**
 * LocalNPC —— 在脚下定时生成「随机游戏生物」，纯客户端本地实体。
 *
 * - 只在本地 WorldClient 中 addEntityToWorld，不向服务器报告任何生成；
 * - 「本地实体缺少 AI / 物力」修复：vanilla 的 updateEntityActionState（AI 任务、
 *   寻路、移动辅助）与 moveEntityWithHeading（碰撞/重力）都在 isServerWorld()
 *   判定里只在服务端世界执行，attackEntityFrom 也会因 `worldObj.isRemote`
 *   直接 return false —— 所以往 WorldClient 里塞出来的实体会悬空不动、打不死。
 *   这里每 tick 手动跑一遍服务端分支（Has-AI 开 AI 任务，物理恒开）；
 * - 「本地伤害」修复：攻击假实体时在取消 C02PacketUseEntity 的同时，
 *   用 vanilla 等价流程（damageEntity + 击退 + hurt 状态包）在本地结算伤害，
 *   血量归零走 vanilla 死亡流程（倒地 + 尸模动画，20 tick 后 setDead）；
 * - 关闭模块或切世界后，所有生成的生物一并消失。
 */
object LocalNPC : Module("LocalNPC", Category.FUN, gameDetecting = false) {

    private val delay by int("Delay", 5, 1..60, "s")
    private val hasAI by boolean("Has-AI", false)
    private val limit by int("Limit", 8, 1..30)
    private val mobType by choices(
        "Type", arrayOf("Random", "Zombie", "Skeleton", "Creeper", "Spider", "Enderman", "Pig", "Cow", "Chicken", "Sheep", "Villager", "Wolf", "Ocelot", "Bat"),
        "Random"
    )

    /** 客户端专用实体 id：负数一定与服务器分配的正数 id 错开，避免 RandomUtils.nextInt 覆盖真实实体 */
    private var nextFakeId = -1000000000

    private val fakes = ArrayList<EntityLiving>()
    private val fakeIds = HashSet<Int>()
    private var nextSpawn = 0L

    private val factories: Map<String, (net.minecraft.world.World) -> EntityLiving> = mapOf(
        "Zombie" to { w: net.minecraft.world.World -> EntityZombie(w) },
        "Skeleton" to { w: net.minecraft.world.World -> EntitySkeleton(w) },
        "Creeper" to { w: net.minecraft.world.World -> EntityCreeper(w) },
        "Spider" to { w: net.minecraft.world.World -> EntitySpider(w) },
        "Enderman" to { w: net.minecraft.world.World -> EntityEnderman(w) },
        "Pig" to { w: net.minecraft.world.World -> EntityPig(w) },
        "Cow" to { w: net.minecraft.world.World -> EntityCow(w) },
        "Chicken" to { w: net.minecraft.world.World -> EntityChicken(w) },
        "Sheep" to { w: net.minecraft.world.World -> EntitySheep(w) },
        "Villager" to { w: net.minecraft.world.World -> EntityVillager(w) },
        "Wolf" to { w: net.minecraft.world.World -> EntityWolf(w) },
        "Ocelot" to { w: net.minecraft.world.World -> EntityOcelot(w) },
        "Bat" to { w: net.minecraft.world.World -> EntityBat(w) }
    )

    val onUpdate = handler<UpdateEvent> {
        val player = mc.thePlayer ?: return@handler
        val world = mc.theWorld ?: return@handler

        // 清理本地已死亡的实体（血量归零 → vanilla 倒地动画 20 tick → isDead）
        fakes.removeAll { entity ->
            val dead = entity.isDead || (entity.health <= 0F && entity.deathTime >= 20)
            if (dead) {
                fakeIds.remove(entity.entityId)
                world.removeEntityFromWorld(entity.entityId)
            }
            dead
        }

        // 定时生成
        val now = System.currentTimeMillis()
        if (fakes.size < limit && now >= nextSpawn) {
            nextSpawn = now + delay * 1000L
            spawn(player, world)
        }

        // vanilla 只在服务端世界给活物跑 AI（updateEntityActionState 含
        // 任务/寻路/moveHelper/lookHelper），客户端世界一律跳过 —— 手动补上
        if (hasAI) {
            for (entity in fakes) {
                if (entity.isDead || entity.health <= 0F) continue
                (entity as EntityLivingBase).updateEntityActionState()
            }
        }

        // 同理：重力/摩擦/位移（moveEntityWithHeading 的服务端分支）也只在服务端跑，
        // 不手动模拟的话实体永远悬空不动
        for (entity in fakes) {
            if (entity.isDead || entity.health <= 0F) continue
            tickProxyPhysics(entity, world)
        }
    }

    private fun spawn(player: net.minecraft.entity.player.EntityPlayer, world: net.minecraft.client.multiplayer.WorldClient) {
        val type = if (mobType == "Random") factories.keys.random() else mobType
        val factory = factories[type] ?: return

        val entity = factory(world)

        // 生成在脚下（轻微随机偏移避开身体重叠）
        val offsetX = (Math.random() - 0.5) * 1.2
        val offsetZ = (Math.random() - 0.5) * 1.2
        entity.setLocationAndAngles(
            player.posX + offsetX,
            player.posY,
            player.posZ + offsetZ,
            player.rotationYaw + (Math.random() - 0.5).toFloat() * 90F,
            0F
        )
        entity.prevRenderYawOffset = entity.renderYawOffset

        // 无 AI：清空全部任务，生物只会被物理影响（仍受重力/击退影响）
        if (!hasAI) {
            entity.tasks.taskEntries.clear()
            entity.targetTasks.taskEntries.clear()
        }

        val id = nextFakeId--
        world.addEntityToWorld(id, entity)

        fakes += entity
        fakeIds += id
    }

    /**
     * 复刻 vanilla EntityLivingBase#moveEntityWithHeading 服务端分支的运动部分
     * （客户端世界 jungle 分支整段跳过），不引入服务器同步插值：
     * 地面/空中通用 + 水/岩浆简化处理。
     */
    private fun tickProxyPhysics(entity: EntityLivingBase, world: net.minecraft.world.World) {
        val x = MathHelper.floor_double(entity.posX)
        val y = MathHelper.floor_double(entity.entityBoundingBox.minY)
        val z = MathHelper.floor_double(entity.posZ)
        val onGroundBlock = world.getBlockState(BlockPos(x, y - 1, z)).block

        if (entity.isInWater() || entity.isInLava()) {
            entity.moveFlying(entity.moveStrafing, entity.moveForward, 0.02F)
            entity.moveEntity(entity.motionX, entity.motionY, entity.motionZ)
            entity.motionX *= 0.5
            entity.motionY *= 0.5
            entity.motionZ *= 0.5
            entity.motionY -= 0.02
            return
        }

        val slipFactor = (if (entity.onGround) onGroundBlock.slipperiness else 0.91F) * 0.91F
        val factor = 0.16277136F / (slipFactor * slipFactor * slipFactor)
        val speed = if (entity.onGround) entity.getAIMoveSpeed() * factor else 0.02F

        // velocity from movement input (same as vanilla moveFlying before moveEntity)
        entity.moveFlying(entity.moveStrafing, entity.moveForward, speed)

        if (entity.isOnLadder()) {
            entity.motionX = MathHelper.clamp_double(entity.motionX, -0.15, 0.15)
            entity.motionZ = MathHelper.clamp_double(entity.motionZ, -0.15, 0.15)
            entity.fallDistance = 0.0F
            if (entity.motionY < -0.15) entity.motionY = -0.15
        }

        // 与 vanilla 顺序一致：先移动，后施加重力与摩擦
        entity.moveEntity(entity.motionX, entity.motionY, entity.motionZ)
        if (entity.isCollidedHorizontally && entity.isOnLadder()) entity.motionY = 0.2
        entity.motionY -= 0.08
        entity.motionY *= 0.98
        entity.motionX *= slipFactor
        entity.motionZ *= slipFactor
    }

    /**
     * 本地攻击结算（复刻 attackTargetEntityWithCurrentItem → EntityLivingBase
     * #attackEntityFrom 的等价流程，绕过客户端世界 isRemote 直接 false 的门）。
     */
    private fun applyLocalDamage(player: net.minecraft.entity.player.EntityPlayer, target: EntityLivingBase, amount: Float) {
        if (target.isDead || target.health <= 0F || amount <= 0F) return

        val source = DamageSource.causePlayerDamage(player)

        // vanilla 伤害抑制窗口（hurtResistantTime 前半段内只能造成更高伤害）
        if (target.hurtResistantTime > target.maxHurtResistantTime / 2F) {
            if (amount <= target.lastDamage) return
            target.damageEntity(source, amount - target.lastDamage)
            target.lastDamage = amount
        } else {
            target.lastDamage = amount
            target.hurtResistantTime = target.maxHurtResistantTime
            target.damageEntity(source, amount)
            target.maxHurtTime = 10
            target.hurtTime = 10
        }

        // vanilla 的 knockBack（击退方向：远离攻击者）
        var dx = player.posX - target.posX
        var dz = player.posZ - target.posZ
        while (dx * dx + dz * dz < 1.0E-4) {
            dx = (Math.random() - Math.random()) * 0.01
            dz = (Math.random() - Math.random()) * 0.01
        }
        target.knockBack(player, amount, dx, dz)

        // 受伤 / 死亡表现：走 vanilla 客户端 Status 处理路径（红闪 + 音效 + 倒地流程）
        if (target.health <= 0F) {
            // setHealth(0) + onDeath → dead = true → vanilla 每 tick onDeathUpdate，
            // deathTime == 20 时 setDead（倒地动画期间实体不会被移除）
            target.handleStatusUpdate(3)
        } else {
            target.handleStatusUpdate(2)
        }
    }

    /**
     * 纯本地：攻击/交互这些假实体时不向服务器发送任何数据包
     * （C02PacketUseEntity 指向假实体 id，服务器不认识，一律取消），
     * 攻击（ATTACK）同时在本地结算伤害。
     */
    val onPacket = handler<PacketEvent> { event ->
        val player = mc.thePlayer ?: return@handler
        val world = mc.theWorld ?: return@handler
        if (fakes.isEmpty()) return@handler

        val packet = event.packet as? C02PacketUseEntity ?: return@handler

        val target = runCatching { packet.getEntityFromWorld(world) }.getOrNull()
        if (target !in fakes) return@handler

        // 本地伤害（vanilla attackTargetEntityWithCurrentItem 在客户端世界
        // 会被 attackEntityFrom 的 isRemote 门直接拦下，这里手动结算）
        if (packet.action == C02PacketUseEntity.Action.ATTACK) {
            val amount = player.getEntityAttribute(SharedMonsterAttributes.attackDamage).getAttributeValue().toFloat()
            applyLocalDamage(player, target as EntityLivingBase, amount)
        }

        event.cancelEvent()
    }

    val onWorld = handler<WorldEvent> {
        fakes.clear()
        fakeIds.clear()
    }

    override fun onEnable() {
        nextSpawn = 0L
    }

    override fun onDisable() {
        // 关闭模块：所有生成的生物直接消失
        val world = mc.theWorld
        for (entity in fakes) {
            world?.removeEntityFromWorld(entity.entityId)
        }
        fakes.clear()
        fakeIds.clear()
    }

    override val tag
        get() = if (fakes.isNotEmpty()) "${fakes.size}" else null
}
