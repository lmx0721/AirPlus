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
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.entity.EntityLiving
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.SharedMonsterAttributes
import net.minecraft.entity.ai.attributes.AttributeModifier
import net.minecraft.entity.monster.*
import net.minecraft.entity.passive.*
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.network.play.client.C02PacketUseEntity
import net.minecraft.potion.Potion
import net.minecraft.util.BlockPos
import net.minecraft.util.DamageSource
import net.minecraft.util.EnumParticleTypes
import net.minecraft.util.MathHelper
import net.minecraft.village.VillageCollection

/**
 * LocalNPC —— 在脚下定时生成「随机游戏生物」，纯客户端本地实体。
 *
 * - 只在本地 WorldClient 中 addEntityToWorld，不向服务器报告任何生成；
 * - 「AI / 物理 / 朝向」修复：客户端世界不执行 updateEntityActionState（AI 任务、
 *   寻路、moveHelper/lookHelper）也不执行 moveEntityWithHeading 的服务端分支
 *   （moveFlying + 碰撞 + 重力），必须手动模拟。但模拟必须在实体自身 tick 内
 *   进行 —— MixinEntityLivingBase 在 EntityLivingBase#onLivingUpdate 的 HEAD
 *   注入 onFakeTick：此时 prevPos 已由 Entity#onEntityUpdate 捕获，moveEntity
 *   之后 travel 的 limbSwing 与 bodyHelper 的 renderYawOffset 都能拿到正确的
 *   位移增量（腿部动画 + 身体朝向随移动更新）。若改在 UpdateEvent（玩家 tick）
 *   里移动，prevPos 会被实体自己的 onEntityUpdate 覆盖成移动后的位置，
 *   delta 恒为 0 → 实体僵硬滑行、身体朝向冻结、腿部动画静止；
 * - 「本地伤害」修复：攻击假实体时取消 C02PacketUseEntity 并按 vanilla 等价
 *   流程本地结算（手持武器属性修饰符 + 附魔 + 跳劈暴击 → damageEntity + 击退
 *   + hurt 状态）。手持武器的 attackDamage 修饰符由 EntityLivingBase#onUpdate
 *   的装备变更段在服务端世界应用，客户端世界不会应用，必须手动复算；
 *   血量归零走 vanilla 死亡流程（倒地动画 20 tick 后消失）；
 * - 「苦力怕爆炸」：Has-AI 开启时 vanilla swell 任务自然驱动 fuse（闪白 + 嘶声），
 *   fuse 计满后 vanilla explode() 在客户端世界是 no-op，由这里补本地爆炸
 *   （音效 + 大爆炸粒子，isSmoking=false → 不破坏方块不伤实体）并移除苦力怕；
 *   Has-AI 关闭时由玩家 3 格内靠近手动驱动 fuse；
 * - despawnEntity 随机 setDead 的漏洞：生成时 enablePersistence 关掉；
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

    /**
     * MixinEntityLivingBase → EntityLivingBase#onLivingUpdate HEAD，每个假实体
     * 每个自身 tick 调一次（执行序与 vanilla 服务端 AI + 移动一致）。
     */
    fun onFakeTick(entity: EntityLivingBase) {
        if (!handleEvents() || fakes.isEmpty() || !fakeIds.contains(entity.entityId)) return
        val world = mc.theWorld ?: return
        if (entity.isDead || entity.health <= 0F) return

        // vanilla 服务端 AI 分支（任务/寻路/moveHelper 转向/lookHelper/jumpHelper）
        if (hasAI) {
            ensureVillageCollection(world)
            entity.updateEntityActionState()
        }

        // 苦力怕 fuse 计满 → 本地爆炸并移除（本 tick 不再跑物理）
        if (entity is EntityCreeper && tickCreeperFuse(entity, world)) return

        // vanilla 服务端物理分支（moveFlying + moveEntity + 重力/摩擦）
        tickProxyPhysics(entity, world)
    }

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
    }

    private fun spawn(player: EntityPlayer, world: WorldClient) {
        val type = if (mobType == "Random") factories.keys.random() else mobType
        val factory = factories[type] ?: return

        val entity = factory(world)

        // 环形偏移（1.2~2.5 格）避开玩家碰撞体，向上找「两格空气」的落点，
        // 避免生成在方块里被 pushOutOfBlocks 反复推出造成抖动
        val angle = Math.random() * Math.PI * 2.0
        val dist = 1.2 + Math.random() * 1.3
        val spawnX = player.posX + Math.cos(angle) * dist
        val spawnZ = player.posZ + Math.sin(angle) * dist
        val baseY = MathHelper.floor_double(player.posY)
        var spawnY = player.posY
        for (dy in 0..3) {
            val y = baseY + dy
            val floorX = MathHelper.floor_double(spawnX)
            val floorZ = MathHelper.floor_double(spawnZ)
            if (world.isAirBlock(BlockPos(floorX, y, floorZ)) &&
                world.isAirBlock(BlockPos(floorX, y + 1, floorZ))
            ) {
                spawnY = y.toDouble()
                break
            }
        }

        entity.setLocationAndAngles(
            spawnX,
            spawnY,
            spawnZ,
            player.rotationYaw + (Math.random() - 0.5).toFloat() * 90F,
            0F
        )
        entity.prevRenderYawOffset = entity.renderYawOffset

        // despawnEntity（updateEntityActionState 内）会按存活时长随机 setDead
        // 假实体（idleTime > 600 后每 tick 1/800 概率），假实体不该消失
        entity.enablePersistence()

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
     * 客户端 WorldClient 从不初始化 villageCollectionObj：vanilla 客户端世界从不
     * 执行实体 AI 路径（EntityLivingBase#onLivingUpdate 只在 isServerWorld()
     * （!isRemote 且 AI 未禁用）时才调 updateEntityActionState），而村民的
     * updateAITasks 会直接解引用 worldObj.getVillageCollection() → Has-AI 假村民
     * 必 NPE 崩游戏。这里懒初始化一个空 VillageCollection：村庄查询永远返回
     * 「附近没有村庄」，村民 AI 退化为 wander，其余生物不受影响。
     */
    private fun ensureVillageCollection(world: WorldClient) {
        if (world.villageCollectionObj == null) {
            world.villageCollectionObj = VillageCollection(world)
        }
    }

    /**
     * 苦力怕 fuse：Has-AI 开启时 vanilla updateAITasks 已驱动
     * timeSinceIgnited（闪白插值 + 嘶声都是 vanilla 行为），fuse 计满后
     * vanilla explode() 在客户端世界直接 return，这里补上本地爆炸。
     * Has-AI 关闭时由玩家 3 格内靠近手动驱动 fuse。
     *
     * @return true 表示已引爆并移除，本 tick 跳过物理
     */
    private fun tickCreeperFuse(creeper: EntityCreeper, world: WorldClient): Boolean {
        val player = mc.thePlayer
        if (!hasAI && player != null) {
            val near = player.getDistanceToEntity(creeper) < 3F
            if (near && creeper.timeSinceIgnited == 0) {
                creeper.playSound("random.fuse", 1.0F, 0.5F)
            }
            creeper.lastActiveTime = creeper.timeSinceIgnited
            creeper.timeSinceIgnited = if (near) creeper.timeSinceIgnited + 1
            else (creeper.timeSinceIgnited - 1).coerceAtLeast(0)
        }

        if (creeper.timeSinceIgnited >= creeper.fuseTime) {
            explodeCreeper(creeper, world)
            return true
        }
        return false
    }

    /**
     * 本地爆炸：createExplosion(isSmoking=false) 只出音效与击退判定，
     * 不破坏方块；客户端世界 attackEntityFrom 直接 false，不伤任何实体。
     * 粒子复刻 S2APacketExplosion 客户端处理（hugeexplosion + largeexplode
     * + 烟雾）。
     */
    private fun explodeCreeper(creeper: EntityCreeper, world: WorldClient) {
        val x = creeper.posX
        val y = creeper.posY
        val z = creeper.posZ
        val strength = 3F * (if (creeper.getPowered()) 2F else 1F)

        world.createExplosion(creeper, x, y, z, strength, false)

        world.spawnParticle(EnumParticleTypes.EXPLOSION_HUGE, x, y, z, 1.0, 0.0, 0.0)
        for (i in 0 until 16) {
            val dx = Math.random() * 2.0 - 1.0
            val dy = Math.random() * 2.0 - 1.0
            val dz = Math.random() * 2.0 - 1.0
            world.spawnParticle(EnumParticleTypes.EXPLOSION_LARGE, x + dx * 0.5, y + dy * 0.5, z + dz * 0.5, dx, dy, dz)
        }
        for (i in 0 until 8) {
            world.spawnParticle(
                EnumParticleTypes.EXPLOSION_NORMAL,
                x + Math.random() * 1.6 - 0.8,
                y + Math.random() * 1.6 - 0.8,
                z + Math.random() * 1.6 - 0.8,
                0.0, 0.0, 0.0
            )
        }

        fakeIds.remove(creeper.entityId)
        world.removeEntityFromWorld(creeper.entityId)
        fakes.remove(creeper)
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
     * vanilla 服务器侧攻击伤害等价计算：attackDamage 属性（基础 1.0 +
     * 手持物品修饰符）→ 跳劈暴击 ×1.5 → 附魔加成。
     */
    private fun attackDamageOf(player: EntityPlayer, target: EntityLivingBase): Float {
        var damage = 1.0F
        val key = SharedMonsterAttributes.attackDamage.getAttributeUnlocalizedName()
        val held = player.heldItem

        if (held != null) {
            for (entry in held.getAttributeModifiers().entries()) {
                if (entry.key == key) {
                    val modifier: AttributeModifier = entry.value
                    damage = when (modifier.getOperation()) {
                        // op1 相对属性基值（attackDamage 基值 1.0），与当前累计值等价
                        0 -> damage + modifier.getAmount().toFloat()
                        1 -> damage + modifier.getAmount().toFloat()
                        else -> damage * (1.0F + modifier.getAmount().toFloat())
                    }
                }
            }
        }

        // vanilla 跳劈暴击条件（只放大基础伤害，附魔随后相加）
        if (player.fallDistance > 0F && !player.onGround && !player.isOnLadder() && !player.isInWater() &&
            !player.isPotionActive(Potion.blindness) && player.ridingEntity == null && !player.isSprinting()
        ) {
            damage *= 1.5F
        }

        if (held != null) {
            damage += EnchantmentHelper.getModifierForCreature(held, target.getCreatureAttribute())
        }

        return damage
    }

    /**
     * 本地攻击结算（复刻 attackTargetEntityWithCurrentItem → EntityLivingBase
     * #attackEntityFrom 的等价流程，绕过客户端世界 isRemote 直接 false 的门）。
     */
    private fun applyLocalDamage(player: EntityPlayer, target: EntityLivingBase, amount: Float) {
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
            applyLocalDamage(player, target as EntityLivingBase, attackDamageOf(player, target))
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
