/*
 * Air Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * skid FDP Client
 * https://github.com/SkidderMC/FDPClient
 */
package net.airplus.features.module.modules.render

import net.airplus.event.Render2DEvent
import net.airplus.event.Render3DEvent
import net.airplus.event.WorldEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.ui.font.Fonts
import net.airplus.utils.client.ClientThemesUtils
import net.airplus.utils.render.ESPUtil
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.render.RoundedUtil
import net.airplus.utils.rotation.RotationUtils
import net.minecraft.block.material.Material
import net.minecraft.client.entity.AbstractClientPlayer
import net.minecraft.client.gui.Gui
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.client.renderer.RenderGlobal
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.item.EntityEnderPearl
import net.minecraft.entity.item.EntityExpBottle
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.entity.player.EnumPlayerModelParts
import net.minecraft.entity.projectile.EntityArrow
import net.minecraft.entity.projectile.EntityPotion
import net.minecraft.entity.projectile.EntityThrowable
import net.minecraft.item.*
import net.minecraft.util.*
import org.lwjgl.opengl.GL11
import java.awt.Color
import kotlin.math.*

object Projectiles : Module("Projectiles", Category.RENDER, gameDetecting = false) {

    // ==================== Airplus 原有设置（保持不变） ====================
    private val colorRedValue by int("Color-Red", 255, 0..255)
    private val colorGreenValue by int("Color-Green", 255, 0..255)
    private val colorBlueValue by int("Color-Blue", 255, 0..255)
    private val colorAlphaValue by int("Color-Alpha", 70, 0..255)
    private val dynamicBowPower by boolean("DynamicBowPower", true)

    // ==================== Moonlight Trajectories 移植（增量追加的样式） ====================
    // Airplus = 原有样式（默认，零回归）；Moonlight = moonlight Default（多段渐隐弹道 + 冲击盘/环 + 尾迹等）；
    // Xylitol = moonlight XylitolProjectile 样式（直线弹道 + 十字落点圈）
    private val mode by choices("Mode", arrayOf("Airplus", "Moonlight", "Xylitol"), "Airplus")

    // Moonlight 样式专属选项（moonlight Trajectories 的 Trail / Predict Trajectory / Show Thrower / Through Walls）
    private val moonTrail by boolean("Trail", false) { mode == "Moonlight" }
    private val moonPredictTrajectory by boolean("Predict Trajectory", true) { mode == "Moonlight" }
    private val moonShowThrower by boolean("Show Thrower", true) { mode == "Moonlight" }
    private val moonThroughWalls by boolean("Through Walls", true) { mode == "Moonlight" }

    private val moonOwners = HashMap<Int, EntityPlayer?>()
    private val moonTrails = HashMap<Int, MoonTrail>()
    private val moonLanded = HashMap<Int, Vec3>()
    private val moonTags = ArrayList<MoonTag>()

    // moonlight Trajectories 固定配色：珍珠 / 箭矢 / 其他投掷物 / 命中自己
    private val moonPearlColor = Color(186, 104, 255)
    private val moonArrowColor = Color(235, 235, 240)
    private val moonOtherColor = Color(150, 210, 255)
    private val moonHitColor = Color(255, 90, 90)
    private const val MAX_TRAIL = 120
    private const val TRAIL_LIFE = 650L

    val onRender3D = handler<Render3DEvent> {
        when (mode) {
            "Moonlight" -> renderMoonlight(it.partialTicks)
            "Xylitol" -> renderXylitol()
            else -> renderAirplus(it)
        }
    }

    // Show Thrower：Render3D 投影收集 → Render2D 绘制投掷者名牌
    private val onMoonRender2D = handler<Render2DEvent> {
        if (moonTags.isNotEmpty()) {
            for (tag in moonTags) {
                drawMoonTag(tag)
            }
            GlStateManager.enableTexture2D()
            GlStateManager.color(1f, 1f, 1f, 1f)
        }
    }

    private val onMoonWorld = handler<WorldEvent> {
        moonOwners.clear()
        moonTrails.clear()
        moonLanded.clear()
        moonTags.clear()
    }

    override fun onDisable() {
        moonLanded.clear()
        moonOwners.clear()
        moonTrails.clear()
        moonTags.clear()
    }

    // ==================== Airplus 原有弹道绘制（原样保留，零回归） ====================
    private fun renderAirplus(event: Render3DEvent) {
        mc.thePlayer.heldItem ?: return

        val item = mc.thePlayer.heldItem.item
        val renderManager = mc.renderManager
        var isBow = false
        var motionFactor = 1.5F
        var motionSlowdown = 0.99F
        val gravity: Float
        val size: Float

        when (item) {
            is ItemBow -> {
                if (dynamicBowPower && !mc.thePlayer.isUsingItem) {
                    return
                }

                isBow = true
                gravity = 0.05F
                size = 0.3F

                var power = (if (dynamicBowPower) mc.thePlayer.itemInUseDuration else item.getMaxItemUseDuration(ItemStack(item))) / 20f
                power = (power * power + power * 2F) / 3F
                if (power < 0.1F) {
                    return
                }

                if (power > 1F) {
                    power = 1F
                }

                motionFactor = power * 3F
            }
            is ItemFishingRod -> {
                gravity = 0.04F
                size = 0.25F
                motionSlowdown = 0.92F
            }
            is ItemPotion -> {
                if (!ItemPotion.isSplash(mc.thePlayer.heldItem.itemDamage)) return
                gravity = 0.05F
                size = 0.25F
                motionFactor = 0.5F
            }
            is ItemSnowball, is ItemEnderPearl, is ItemEgg -> {
                gravity = 0.03F
                size = 0.25F
            }
            else -> return
        }

        val yaw = RotationUtils.currentRotation?.yaw ?: mc.thePlayer.rotationYaw
        val pitch = RotationUtils.currentRotation?.pitch ?: mc.thePlayer.rotationPitch

        var posX = renderManager.renderPosX - MathHelper.cos(yaw / 180F * 3.1415927F) * 0.16F
        var posY = renderManager.renderPosY + mc.thePlayer.eyeHeight - 0.10000000149011612
        var posZ = renderManager.renderPosZ - MathHelper.sin(yaw / 180F * 3.1415927F) * 0.16F

        var motionX = (-MathHelper.sin(yaw / 180f * 3.1415927F) * MathHelper.cos(pitch / 180F * 3.1415927F) *
                if (isBow) 1.0 else 0.4)
        var motionY = -MathHelper.sin((pitch +
                if (item is ItemPotion && ItemPotion.isSplash(mc.thePlayer.heldItem.itemDamage)) -20 else 0) /
                180f * 3.1415927f) * if (isBow) 1.0 else 0.4
        var motionZ = (MathHelper.cos(yaw / 180f * 3.1415927F) * MathHelper.cos(pitch / 180F * 3.1415927F) *
                if (isBow) 1.0 else 0.4)
        val distance = MathHelper.sqrt_double(motionX * motionX + motionY * motionY + motionZ * motionZ)

        motionX /= distance
        motionY /= distance
        motionZ /= distance
        motionX *= motionFactor
        motionY *= motionFactor
        motionZ *= motionFactor

        var landingPosition: MovingObjectPosition? = null
        var hasLanded = false
        var hitEntity = false

        val tessellator = Tessellator.getInstance()
        val worldRenderer = tessellator.worldRenderer
        val pos = mutableListOf<Vec3>()

        while (!hasLanded && posY > 0.0) {
            var posBefore = Vec3(posX, posY, posZ)
            var posAfter = Vec3(posX + motionX, posY + motionY, posZ + motionZ)

            landingPosition = mc.theWorld.rayTraceBlocks(posBefore, posAfter, false,
                true, false)

            posBefore = Vec3(posX, posY, posZ)
            posAfter = Vec3(posX + motionX, posY + motionY, posZ + motionZ)

            if (landingPosition != null) {
                hasLanded = true
                posAfter = Vec3(landingPosition.hitVec.xCoord, landingPosition.hitVec.yCoord, landingPosition.hitVec.zCoord)
            }

            val arrowBox = AxisAlignedBB(posX - size, posY - size, posZ - size, posX + size,
                posY + size, posZ + size).addCoord(motionX, motionY, motionZ).expand(1.0, 1.0, 1.0)
            val chunkMinX = MathHelper.floor_double((arrowBox.minX - 2.0) / 16.0)
            val chunkMaxX = MathHelper.floor_double((arrowBox.maxX + 2.0) / 16.0)
            val chunkMinZ = MathHelper.floor_double((arrowBox.minZ - 2.0) / 16.0)
            val chunkMaxZ = MathHelper.floor_double((arrowBox.maxZ + 2.0) / 16.0)

            val collidedEntities = mutableListOf<Entity>()
            for (x in chunkMinX..chunkMaxX)
                for (z in chunkMinZ..chunkMaxZ)
                    mc.theWorld.getChunkFromChunkCoords(x, z)
                        .getEntitiesWithinAABBForEntity(mc.thePlayer, arrowBox, collidedEntities, null)

            for (possibleEntity in collidedEntities) {
                if (possibleEntity.canBeCollidedWith() && possibleEntity !== mc.thePlayer) {
                    val possibleEntityBoundingBox = possibleEntity.entityBoundingBox
                        .expand(size.toDouble(), size.toDouble(), size.toDouble())

                    val possibleEntityLanding = possibleEntityBoundingBox
                        .calculateIntercept(posBefore, posAfter) ?: continue

                    hitEntity = true
                    hasLanded = true
                    landingPosition = possibleEntityLanding
                }
            }

            posX += motionX
            posY += motionY
            posZ += motionZ

            if (mc.theWorld.getBlockState(BlockPos(posX, posY, posZ)).block.material === Material.water) {
                motionX *= 0.6
                motionY *= 0.6
                motionZ *= 0.6
            } else {
                motionX *= motionSlowdown.toDouble()
                motionY *= motionSlowdown.toDouble()
                motionZ *= motionSlowdown.toDouble()
            }

            motionY -= gravity.toDouble()

            pos.add(Vec3(posX - renderManager.renderPosX, posY - renderManager.renderPosY,
                posZ - renderManager.renderPosZ))
        }

        GL11.glDepthMask(false)
        RenderUtils.enableGlCap(GL11.GL_BLEND, GL11.GL_LINE_SMOOTH)
        RenderUtils.disableGlCap(GL11.GL_DEPTH_TEST, GL11.GL_ALPHA_TEST, GL11.GL_TEXTURE_2D)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST)
        RenderUtils.glColor(if (hitEntity) { Color(255, 140, 140, colorAlphaValue) } else { Color(colorRedValue, colorGreenValue, colorBlueValue, colorAlphaValue) })
        GL11.glLineWidth(2f)

        worldRenderer.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION)

        pos.forEach {
            worldRenderer.pos(it.xCoord, it.yCoord, it.zCoord).endVertex()
        }

        tessellator.draw()
        GL11.glPushMatrix()
        GL11.glTranslated(posX - renderManager.renderPosX, posY - renderManager.renderPosY,
            posZ - renderManager.renderPosZ)

        if (landingPosition != null) {
            when (landingPosition.sideHit.axis.ordinal) {
                0 -> GL11.glRotatef(90F, 0F, 0F, 1F)
                2 -> GL11.glRotatef(90F, 1F, 0F, 0F)
            }

            RenderUtils.drawAxisAlignedBB(AxisAlignedBB(-0.5, 0.0, -0.5, 0.5, 0.1, 0.5),
                if (hitEntity) { Color(255, 140, 140, colorAlphaValue) } else { Color(colorRedValue, colorGreenValue, colorBlueValue, colorAlphaValue) })
        }
        GL11.glPopMatrix()
        GL11.glDepthMask(true)
        RenderUtils.resetCaps()
        GL11.glColor4f(1F, 1F, 1F, 1F)
    }

    // ==================== Moonlight Default 模式（wtf.moonlight Trajectories 移植） ====================

    private fun renderMoonlight(partialTicks: Float) {
        if (mc.thePlayer == null || mc.theWorld == null) return
        moonTags.clear()
        renderMoonOwnAim(partialTicks)
        renderMoonFlying(partialTicks, ScaledResolution(mc).scaleFactor)
        moonCleanup()
    }

    /** 自己手中投掷物的弹道预测（moonlight renderOwnAim）。 */
    private fun renderMoonOwnAim(partialTicks: Float) {
        val player = mc.thePlayer ?: return
        val stack = player.heldItem ?: return
        val item = stack.item
        val isBow = item is ItemBow
        val isSplash = item is ItemPotion && ItemPotion.isSplash(stack.itemDamage)
        val isExpBottle = item is ItemExpBottle
        if (!(isBow || isSplash || isExpBottle || item is ItemSnowball || item is ItemEnderPearl || item is ItemEgg)) return

        var pitchOffset = 0f
        val motionFactor: Float
        val gravity: Double
        if (isBow) {
            var power = 1f
            if (player.isUsingItem) {
                val seconds = (stack.maxItemUseDuration - player.itemInUseCount) / 20f
                power = min(1f, (seconds * seconds + seconds * 2f) / 3f)
                if (power < 0.1f) return
            }
            motionFactor = power * 3f
            gravity = 0.05
        } else if (isSplash) {
            motionFactor = 0.5f
            pitchOffset = -20f
            gravity = 0.05
        } else if (isExpBottle) {
            motionFactor = 0.7f
            pitchOffset = -20f
            gravity = 0.07
        } else {
            motionFactor = 1.5f
            gravity = 0.03
        }

        val yaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partialTicks
        val pitch = player.prevRotationPitch + (player.rotationPitch - player.prevRotationPitch) * partialTicks
        val posX = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks -
            MathHelper.cos(yaw / 180f * Math.PI.toFloat()) * 0.16
        val posY = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks + player.eyeHeight - 0.1
        val posZ = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks -
            MathHelper.sin(yaw / 180f * Math.PI.toFloat()) * 0.16

        val yawRad = yaw / 180f * Math.PI.toFloat()
        val pitchRad = pitch / 180f * Math.PI.toFloat()
        var motionX: Double = (-MathHelper.sin(yawRad) * MathHelper.cos(pitchRad)).toDouble()
        var motionY: Double = -MathHelper.sin((pitch + pitchOffset) / 180f * Math.PI.toFloat()).toDouble()
        var motionZ: Double = (MathHelper.cos(yawRad) * MathHelper.cos(pitchRad)).toDouble()
        val motionLength = sqrt(motionX * motionX + motionY * motionY + motionZ * motionZ)
        motionX = motionX / motionLength * motionFactor
        motionY = motionY / motionLength * motionFactor
        motionZ = motionZ / motionLength * motionFactor

        val path = moonSimulate(posX, posY, posZ, motionX, motionY, motionZ, gravity, 300, player, null)
        val color = ClientThemesUtils.getColor()
        drawMoonPath(path, color, if (mc.gameSettings.thirdPersonView == 0) 2.5 else 0.0)
        drawMoonImpact(path, color)
    }

    /** 飞行中投掷物/箭矢的预测、尾迹与投掷者名牌（moonlight renderFlying）。 */
    private fun renderMoonFlying(partialTicks: Float, scaleFactor: Int) {
        if (!moonPredictTrajectory && !moonTrail && !moonShowThrower) return
        val viewerX = mc.renderManager.viewerPosX
        val viewerY = mc.renderManager.viewerPosY
        val viewerZ = mc.renderManager.viewerPosZ

        for (entity in mc.theWorld.loadedEntityList) {
            if (entity !is EntityThrowable && entity !is EntityArrow) continue

            val motionSq = entity.motionX * entity.motionX + entity.motionY * entity.motionY + entity.motionZ * entity.motionZ
            val moving = motionSq > 0.01 &&
                (entity !is EntityArrow || entity.prevPosX != entity.posX || entity.prevPosY != entity.posY || entity.prevPosZ != entity.posZ)

            val color = when (entity) {
                is EntityEnderPearl -> moonPearlColor
                is EntityArrow -> moonArrowColor
                else -> moonOtherColor
            }
            val x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks
            val y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks
            val z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks

            // 投掷物落地后停止预测，并把落点补进尾迹
            if (entity is EntityThrowable) {
                var landedVec = moonLanded[entity.entityId]
                if (landedVec == null) {
                    landedVec = moonBlockBetween(entity.lastTickPosX, entity.lastTickPosY, entity.lastTickPosZ, x, y, z)
                    if (landedVec != null) {
                        moonLanded[entity.entityId] = landedVec
                        val trail = moonTrails[entity.entityId]
                        if (moonTrail && trail != null) {
                            trail.points.addLast(doubleArrayOf(landedVec.xCoord, landedVec.yCoord, landedVec.zCoord, System.currentTimeMillis().toDouble()))
                        }
                    }
                }
                if (landedVec != null) continue
            }

            if (moonTrail && moving) {
                val trail = moonTrails.getOrPut(entity.entityId) { MoonTrail() }
                trail.color = color
                val last = trail.points.peekLast()
                if (last == null || sq(last[0] - x, last[1] - y, last[2] - z) > 0.0025) {
                    trail.points.addLast(doubleArrayOf(x, y, z, System.currentTimeMillis().toDouble()))
                    if (trail.points.size > MAX_TRAIL) {
                        trail.points.removeFirst()
                    }
                }
            }

            if (moving) {
                val owner = moonOwnerOf(entity)
                if (moonPredictTrajectory) {
                    val gravity = when {
                        entity is EntityArrow || entity is EntityPotion -> 0.05
                        entity is EntityExpBottle -> 0.07
                        else -> 0.03
                    }
                    val path = moonSimulate(x, y, z, entity.motionX, entity.motionY, entity.motionZ, gravity, 200, entity, owner)
                    path.points.add(0, doubleArrayOf(x - viewerX, y - viewerY, z - viewerZ))
                    drawMoonPath(path, color, 0.0)
                    val impactColor = if (path.hit != null && path.hit!!.entityHit === mc.thePlayer) moonHitColor else color
                    drawMoonImpact(path, impactColor)
                }

                if (moonShowThrower && owner != null && owner !== mc.thePlayer) {
                    val point = ESPUtil.projectOn2D(
                        (x - viewerX).toFloat(),
                        (y - viewerY + 0.45).toFloat(),
                        (z - viewerZ).toFloat(),
                        scaleFactor
                    )
                    if (point != null && point.z >= 0f && point.z < 1f) {
                        moonTags += MoonTag(
                            owner,
                            EnumChatFormatting.getTextWithoutFormattingCodes(owner.name) ?: owner.name,
                            point.x,
                            point.y,
                            sqrt(sq(x - viewerX, y - viewerY, z - viewerZ)).toFloat(),
                            color
                        )
                    }
                }
            }
        }

        // 尾迹老化与绘制
        if (moonTrail) {
            val now = System.currentTimeMillis()
            val iterator = moonTrails.values.iterator()
            while (iterator.hasNext()) {
                val trail = iterator.next()
                while (trail.points.isNotEmpty() && now - trail.points.peekFirst()!![3].toLong() > TRAIL_LIFE) {
                    trail.points.removeFirst()
                }
                if (trail.points.isEmpty()) {
                    iterator.remove()
                } else {
                    drawMoonTrail(trail, now, viewerX, viewerY, viewerZ)
                }
            }
        } else {
            moonTrails.clear()
        }
    }

    /** 投掷者归属：箭矢直接取 shootingEntity，其余按位置+朝向启发式推断并缓存（moonlight ownerOf）。 */
    private fun moonOwnerOf(entity: Entity): EntityPlayer? {
        if (entity is EntityArrow && entity.shootingEntity is EntityPlayer) {
            return entity.shootingEntity as EntityPlayer
        }
        if (moonOwners.containsKey(entity.entityId)) {
            return moonOwners[entity.entityId]
        }

        var best: EntityPlayer? = null
        var bestCost = Double.MAX_VALUE
        for (player in mc.theWorld.playerEntities) {
            if (!player.isEntityAlive) continue
            if (player === mc.thePlayer && !holdingThrowable()) continue

            val dx = entity.posX - player.posX
            val dy = entity.posY - (player.posY + player.eyeHeight)
            val dz = entity.posZ - player.posZ
            val distance = sqrt(dx * dx + dy * dy + dz * dz)
            if (distance > 4.5) continue

            val yawRad = (if (player === mc.thePlayer) player.rotationYaw else player.rotationYawHead) / 180f * Math.PI.toFloat()
            val pitchRad = player.rotationPitch / 180f * Math.PI.toFloat()
            val forwardX = -MathHelper.sin(yawRad) * MathHelper.cos(pitchRad)
            val forwardY = -MathHelper.sin(pitchRad)
            val forwardZ = MathHelper.cos(yawRad) * MathHelper.cos(pitchRad)
            val dot = dx * forwardX + dy * forwardY + dz * forwardZ
            if (dot < -0.6) continue

            val perp = sqrt(max(0.0, distance * distance - dot * dot))
            val cost = perp + distance * 0.25
            if (cost < bestCost) {
                bestCost = cost
                best = player
            }
        }
        moonOwners[entity.entityId] = best
        return best
    }

    /** 相邻两帧间是否撞到方块（moonlight blockBetween）。 */
    private fun moonBlockBetween(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double): Vec3? {
        if (sq(x2 - x1, y2 - y1, z2 - z1) < 1.0E-8) return null
        val hit = mc.theWorld.rayTraceBlocks(Vec3(x1, y1, z1), Vec3(x2, y2, z2), false, true, false) ?: return null
        return if (hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) hit.hitVec else null
    }

    private fun moonCleanup() {
        val world = mc.theWorld ?: return
        moonLanded.keys.removeAll { id -> world.getEntityByID(id) == null }
        val iterator = moonOwners.entries.iterator()
        while (iterator.hasNext()) {
            val entity = world.getEntityByID(iterator.next().key)
            if (entity == null || entity.isDead) iterator.remove()
        }
    }

    /** 弹道采样：逐步方块射线 + 实体命中，液体中减速（moonlight simulate）。 */
    private fun moonSimulate(
        x: Double, y: Double, z: Double,
        motionX: Double, motionY: Double, motionZ: Double,
        gravity: Double, steps: Int, source: Entity, ignore: Entity?,
    ): MoonPath {
        val path = MoonPath()
        val viewerX = mc.renderManager.viewerPosX
        val viewerY = mc.renderManager.viewerPosY
        val viewerZ = mc.renderManager.viewerPosZ
        var cx = x
        var cy = y
        var cz = z
        var mx = motionX
        var my = motionY
        var mz = motionZ
        if (source === mc.thePlayer) {
            path.points.add(doubleArrayOf(cx - viewerX, cy - viewerY, cz - viewerZ))
        }

        for (step in 0 until steps) {
            if (cy <= -64.0) break
            val before = Vec3(cx, cy, cz)
            val after = Vec3(cx + mx, cy + my, cz + mz)
            var blockHit = mc.theWorld.rayTraceBlocks(before, after, false, true, false)
            var end: Vec3 = after
            if (blockHit != null && blockHit.typeOfHit != MovingObjectPosition.MovingObjectType.MISS) {
                end = blockHit.hitVec
            } else {
                blockHit = null
            }

            val entityHit = moonEntityHit(before, end, source, ignore)
            var hit: MovingObjectPosition? = blockHit
            if (entityHit != null) {
                hit = entityHit
                end = entityHit.hitVec
            }

            if (hit != null) {
                path.points.add(doubleArrayOf(end.xCoord - viewerX, end.yCoord - viewerY, end.zCoord - viewerZ))
                path.hit = hit
                return path
            }

            cx += mx
            cy += my
            cz += mz
            val drag = if (mc.theWorld.isAnyLiquid(AxisAlignedBB(cx - 0.125, cy - 0.125, cz - 0.125, cx + 0.125, cy + 0.125, cz + 0.125))) 0.8 else 0.99
            mx *= drag
            my *= drag
            mz *= drag
            my -= gravity
            path.points.add(doubleArrayOf(cx - viewerX, cy - viewerY, cz - viewerZ))
        }
        return path
    }

    /** 沿采样段寻找被命中的实体（moonlight entityHit）。 */
    private fun moonEntityHit(before: Vec3, after: Vec3, source: Entity, ignore: Entity?): MovingObjectPosition? {
        val shooter = ignore ?: when (source) {
            is EntityThrowable -> source.thrower
            is EntityArrow -> source.shootingEntity
            else -> null
        }
        var best: MovingObjectPosition? = null
        var bestDistance = Double.MAX_VALUE

        for (entity in mc.theWorld.loadedEntityList) {
            if (entity === source || entity === shooter) continue
            if (entity !is EntityLivingBase || !entity.canBeCollidedWith() || !entity.isEntityAlive) continue
            if (source === mc.thePlayer && entity === mc.thePlayer) continue
            if (sq(entity.posX - before.xCoord, entity.posY - before.yCoord, entity.posZ - before.zCoord) > 16384.0) continue

            val intercept = entity.entityBoundingBox.expand(0.3, 0.3, 0.3).calculateIntercept(before, after) ?: continue
            val distance = before.squareDistanceTo(intercept.hitVec)
            if (distance < bestDistance) {
                bestDistance = distance
                best = MovingObjectPosition(entity, intercept.hitVec)
            }
        }
        return best
    }

    private fun beginMoon3D() {
        GlStateManager.pushMatrix()
        GlStateManager.disableTexture2D()
        GlStateManager.disableLighting()
        GlStateManager.disableCull()
        GlStateManager.enableBlend()
        GlStateManager.disableAlpha()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        GlStateManager.shadeModel(GL11.GL_SMOOTH)
        GL11.glEnable(GL11.GL_LINE_SMOOTH)
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST)
    }

    private fun endMoon3D() {
        GL11.glDisable(GL11.GL_LINE_SMOOTH)
        GL11.glLineWidth(1f)
        GlStateManager.shadeModel(GL11.GL_FLAT)
        GlStateManager.depthMask(true)
        GlStateManager.enableDepth()
        GlStateManager.enableAlpha()
        GlStateManager.enableCull()
        GlStateManager.enableTexture2D()
        GL11.glColor4f(1f, 1f, 1f, 1f)
        GlStateManager.resetColor()
        GlStateManager.popMatrix()
    }

    /** 多段渐隐弹道线（moonlight drawPath：穿墙层 + 宽/中/亮三段）。 */
    private fun drawMoonPath(path: MoonPath, color: Color, fadeEnd: Double) {
        val points = path.points
        if (points.size < 2) return

        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val cur = points[i]
            cumulative[i] = cumulative[i - 1] + sqrt(sq(cur[0] - prev[0], cur[1] - prev[1], cur[2] - prev[2]))
        }
        val total = cumulative[points.size - 1]

        beginMoon3D()
        GlStateManager.depthMask(false)
        if (moonThroughWalls) {
            GlStateManager.disableDepth()
            moonStrip(points, cumulative, total, fadeEnd, color, 0.22f, 1.5f)
            GlStateManager.enableDepth()
        }
        moonStrip(points, cumulative, total, fadeEnd, color, 0.16f, 6f)
        moonStrip(points, cumulative, total, fadeEnd, color, 0.35f, 3f)
        moonStrip(points, cumulative, total, fadeEnd, brighten(color), 0.95f, 1.4f)
        endMoon3D()
    }

    private fun moonStrip(
        points: List<DoubleArray>, cumulative: DoubleArray, total: Double, fadeEnd: Double,
        color: Color, alpha: Float, width: Float,
    ) {
        val r = color.red / 255f
        val g = color.green / 255f
        val b = color.blue / 255f
        GL11.glLineWidth(width)
        GL11.glBegin(GL11.GL_LINE_STRIP)

        for (i in points.indices) {
            val point = points[i]
            var fade = 1f
            if (fadeEnd > 0.0) {
                fade = min(1.0, cumulative[i] / total).toFloat()
                fade *= fade
            }
            val head = min(1.0, (total - cumulative[i]) / 0.6 + 0.55).toFloat()
            GL11.glColor4f(r, g, b, alpha * fade * head)
            GL11.glVertex3d(point[0], point[1], point[2])
        }

        GL11.glEnd()
    }

    /** 尾迹：宽淡 + 细浓两段线（moonlight drawTrail）。 */
    private fun drawMoonTrail(trail: MoonTrail, now: Long, viewerX: Double, viewerY: Double, viewerZ: Double) {
        if (trail.points.size < 2) return
        val color = trail.color
        val r = color.red / 255f
        val g = color.green / 255f
        val b = color.blue / 255f
        beginMoon3D()
        GlStateManager.depthMask(false)

        for (pass in 0..1) {
            GL11.glLineWidth(if (pass == 0) 4f else 1.5f)
            GL11.glBegin(GL11.GL_LINE_STRIP)
            for (point in trail.points) {
                val age = 1f - min(1f, (now - point[3].toLong()) / TRAIL_LIFE.toFloat())
                GL11.glColor4f(r, g, b, (if (pass == 0) 0.18f else 0.8f) * age * age)
                GL11.glVertex3d(point[0] - viewerX, point[1] - viewerY, point[2] - viewerZ)
            }
            GL11.glEnd()
        }

        endMoon3D()
    }

    /** 落点冲击效果：贴面圆盘 + 双层扩散环 + 命中实体包围盒（moonlight drawImpact）。 */
    private fun drawMoonImpact(path: MoonPath, colorIn: Color) {
        val hit = path.hit ?: return
        val hitVec = hit.hitVec ?: return
        val viewerX = mc.renderManager.viewerPosX
        val viewerY = mc.renderManager.viewerPosY
        val viewerZ = mc.renderManager.viewerPosZ

        var color = colorIn
        var x = hitVec.xCoord - viewerX
        var y = hitVec.yCoord - viewerY
        var z = hitVec.zCoord - viewerZ
        var normalX = 0.0
        var normalY = 1.0
        var normalZ = 0.0
        val hitEntity = hit.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY && hit.entityHit != null
        if (hitEntity) {
            color = moonHitColor
            val box = hit.entityHit.entityBoundingBox
            x = (box.minX + box.maxX) / 2.0 - viewerX
            y = box.minY - viewerY
            z = (box.minZ + box.maxZ) / 2.0 - viewerZ
        } else if (hit.sideHit != null) {
            normalX = hit.sideHit.frontOffsetX.toDouble()
            normalY = hit.sideHit.frontOffsetY.toDouble()
            normalZ = hit.sideHit.frontOffsetZ.toDouble()
        }

        // 平面基向量：T = |ny|>0.5 ? (1,0,0) : (0,1,0)，B = N×T，U = B×N
        val tangentX: Double
        val tangentY: Double
        val tangentZ: Double
        if (abs(normalY) > 0.5) {
            tangentX = 1.0; tangentY = 0.0; tangentZ = 0.0
        } else {
            tangentX = 0.0; tangentY = 1.0; tangentZ = 0.0
        }
        val biX = normalY * tangentZ - normalZ * tangentY
        val biY = normalZ * tangentX - normalX * tangentZ
        val biZ = normalX * tangentY - normalY * tangentX
        val axisX = biY * normalZ - biZ * normalY
        val axisY = biZ * normalX - biX * normalZ
        val axisZ = biX * normalY - biY * normalX

        val px = x + normalX * 0.012
        val py = y + normalY * 0.012
        val pz = z + normalZ * 0.012
        val radius = if (hitEntity) max(0.45, hit.entityHit.width * 0.75) else 0.32
        val pulse = (System.currentTimeMillis() % 1200L) / 1200f

        beginMoon3D()
        GlStateManager.depthMask(false)

        for (pass in (if (moonThroughWalls) 0 else 1)..1) {
            val alpha = if (pass == 0) 0.3f else 1f
            if (pass == 0) {
                GlStateManager.disableDepth()
            } else {
                GlStateManager.enableDepth()
            }
            moonDisc(px, py, pz, axisX, axisY, axisZ, biX, biY, biZ, radius, color, 0.32f * alpha, 0f)
            moonRing(px, py, pz, axisX, axisY, axisZ, biX, biY, biZ, radius, color, 0.9f * alpha, 1.8f)
            moonRing(px, py, pz, axisX, axisY, axisZ, biX, biY, biZ, radius * (0.35 + pulse), color, 0.6f * alpha * (1f - pulse), 1.4f)
            if (hitEntity) {
                val box = hit.entityHit.entityBoundingBox.offset(-viewerX, -viewerY, -viewerZ).expand(0.05, 0.05, 0.05)
                GL11.glLineWidth(1.6f)
                GL11.glColor4f(color.red / 255f, color.green / 255f, color.blue / 255f, 0.8f * alpha)
                RenderGlobal.drawSelectionBoundingBox(box)
            }
        }

        endMoon3D()
    }

    private fun moonDisc(
        x: Double, y: Double, z: Double,
        axisX: Double, axisY: Double, axisZ: Double,
        otherX: Double, otherY: Double, otherZ: Double,
        radius: Double, color: Color, alpha: Float, alpha2: Float,
    ) {
        val r = color.red / 255f
        val g = color.green / 255f
        val b = color.blue / 255f
        GL11.glBegin(GL11.GL_TRIANGLE_FAN)
        GL11.glColor4f(r, g, b, alpha)
        GL11.glVertex3d(x, y, z)
        GL11.glColor4f(r, g, b, alpha2)

        for (i in 0..48) {
            val angle = Math.PI * 2 * i / 48.0
            val cos = cos(angle) * radius
            val sin = sin(angle) * radius
            GL11.glVertex3d(
                x + axisX * cos + otherX * sin,
                y + axisY * cos + otherY * sin,
                z + axisZ * cos + otherZ * sin
            )
        }

        GL11.glEnd()
    }

    private fun moonRing(
        x: Double, y: Double, z: Double,
        axisX: Double, axisY: Double, axisZ: Double,
        otherX: Double, otherY: Double, otherZ: Double,
        radius: Double, color: Color, alpha: Float, width: Float,
    ) {
        if (alpha <= 0.01f) return
        GL11.glLineWidth(width)
        GL11.glColor4f(color.red / 255f, color.green / 255f, color.blue / 255f, alpha)
        GL11.glBegin(GL11.GL_LINE_LOOP)

        for (i in 0 until 48) {
            val angle = Math.PI * 2 * i / 48.0
            val cos = cos(angle) * radius
            val sin = sin(angle) * radius
            GL11.glVertex3d(
                x + axisX * cos + otherX * sin,
                y + axisY * cos + otherY * sin,
                z + axisZ * cos + otherZ * sin
            )
        }

        GL11.glEnd()
    }

    /** 投掷者名牌：圆角胶囊 + 头像 + 名字/距离（moonlight drawTag）。 */
    private fun drawMoonTag(tag: MoonTag) {
        val nameFont = Fonts.fontMoonlight(14)
        val distanceFont = Fonts.fontMoonlight(12)
        val scale = max(0.7f, min(1f, 1.15f - tag.distance / 80f))
        val distanceText = round(tag.distance).toInt().toString() + "m"
        val iconSize = 11f
        val padding = 3f
        val height = iconSize + padding * 2f
        val width = padding + iconSize + 4f + nameFont.getStringWidth(tag.name) + 4f +
            distanceFont.getStringWidth(distanceText) + padding + 3f

        GlStateManager.pushMatrix()
        GlStateManager.translate(tag.x, tag.y, 0f)
        GlStateManager.scale(scale, scale, 1f)

        val left = -width / 2f
        val top = -height - 4f
        RoundedUtil.drawRound(left, top, width, height, height / 2f, Color(12, 14, 20, 175))
        RoundedUtil.drawRound(left + width - padding - 3f, top + height / 2f - 1.5f, 3f, 3f, 1.5f, tag.color)
        drawMoonHead(tag.owner, left + padding, top + padding, iconSize)

        val textX = left + padding + iconSize + 4f
        val textY = top + height / 2f - nameFont.height / 2f + 1f
        nameFont.drawString(tag.name, textX, textY, -855049)
        distanceFont.drawString(distanceText, textX + nameFont.getStringWidth(tag.name) + 4f, textY + 0.5f, -1711276033)
        GlStateManager.popMatrix()
    }

    private fun drawMoonHead(player: EntityPlayer, x: Float, y: Float, size: Float) {
        if (player !is AbstractClientPlayer) return
        GlStateManager.enableTexture2D()
        GlStateManager.enableBlend()
        GlStateManager.disableAlpha()
        OpenGlHelper.glBlendFunc(770, 771, 1, 0)
        GlStateManager.color(1f, 1f, 1f, 1f)
        mc.textureManager.bindTexture(player.locationSkin)
        Gui.drawScaledCustomSizeModalRect(x.toInt(), y.toInt(), 8f, 8f, 8, 8, size.toInt(), size.toInt(), 64f, 64f)
        if (player.isWearing(EnumPlayerModelParts.HAT)) {
            Gui.drawScaledCustomSizeModalRect(x.toInt(), y.toInt(), 40f, 8f, 8, 8, size.toInt(), size.toInt(), 64f, 64f)
        }
        GlStateManager.enableAlpha()
    }

    private fun holdingThrowable(): Boolean {
        val stack = mc.thePlayer?.heldItem ?: return false
        val item = stack.item
        return item is ItemBow || item is ItemEnderPearl || item is ItemSnowball || item is ItemEgg ||
            item is ItemPotion || item is ItemExpBottle
    }

    // ==================== Xylitol 模式（wtf.moonlight XylitolProjectile 移植） ====================

    private fun renderXylitol() {
        val player = mc.thePlayer ?: return
        val stack = player.heldItem ?: return
        val item = stack.item

        var isBow = false
        var pitchOffset = 0f
        var motionFactor = 1.5f
        var motionSlowdown = 0.99f
        val gravity: Float
        val size: Float
        when (item) {
            is ItemBow -> {
                isBow = true
                gravity = 0.05f
                size = 0.3f
                var power = player.itemInUseDuration / 20f
                power = (power * power + power * 2f) / 3f
                if (power < 0.1f) return
                if (power > 1f) power = 1f
                motionFactor = power * 3f
            }
            is ItemFishingRod -> {
                gravity = 0.04f
                size = 0.25f
                motionSlowdown = 0.92f
            }
            is ItemPotion -> {
                if (!ItemPotion.isSplash(stack.itemDamage)) return
                gravity = 0.05f
                size = 0.25f
                pitchOffset = -20f
                motionFactor = 0.5f
            }
            else -> {
                if (item !is ItemSnowball && item !is ItemEnderPearl && item !is ItemEgg && item !== Item.getItemById(46)) return
                gravity = 0.03f
                size = 0.25f
            }
        }

        // XylitonProjectile 用 PreMotion 捕获的当前朝向，等价于 RotationUtils.currentRotation
        val yaw = RotationUtils.currentRotation?.yaw ?: player.rotationYaw
        val pitch = RotationUtils.currentRotation?.pitch ?: player.rotationPitch

        val renderManager = mc.renderManager
        var posX = renderManager.renderPosX - MathHelper.cos(yaw / 180f * Math.PI.toFloat()) * 0.16
        var posY = renderManager.renderPosY + player.eyeHeight - 0.1f
        var posZ = renderManager.renderPosZ - MathHelper.sin(yaw / 180f * Math.PI.toFloat()) * 0.16
        var motionX = -MathHelper.sin(yaw / 180f * Math.PI.toFloat()) * MathHelper.cos(pitch / 180f * Math.PI.toFloat()) * (if (isBow) 1.0 else 0.4)
        var motionY = -MathHelper.sin((pitch + pitchOffset) / 180f * Math.PI.toFloat()) * (if (isBow) 1.0 else 0.4)
        var motionZ = MathHelper.cos(yaw / 180f * Math.PI.toFloat()) * MathHelper.cos(pitch / 180f * Math.PI.toFloat()) * (if (isBow) 1.0 else 0.4)
        val motionLength = sqrt(motionX * motionX + motionY * motionY + motionZ * motionZ)
        motionX = motionX / motionLength * motionFactor
        motionY = motionY / motionLength * motionFactor
        motionZ = motionZ / motionLength * motionFactor

        var landingPosition: MovingObjectPosition? = null
        var hasLanded = false
        var hitEntity = false

        enableXylitolRender3D()
        xylitolColor(Color(206, 89, 255, 255))
        GL11.glLineWidth(2f)
        GL11.glBegin(GL11.GL_LINE_STRIP)

        while (!hasLanded && posY > 0.0) {
            var posBefore = Vec3(posX, posY, posZ)
            var posAfter = Vec3(posX + motionX, posY + motionY, posZ + motionZ)
            landingPosition = mc.theWorld.rayTraceBlocks(posBefore, posAfter, false, true, false)
            posBefore = Vec3(posX, posY, posZ)
            posAfter = Vec3(posX + motionX, posY + motionY, posZ + motionZ)
            if (landingPosition != null) {
                hasLanded = true
                posAfter = Vec3(landingPosition.hitVec.xCoord, landingPosition.hitVec.yCoord, landingPosition.hitVec.zCoord)
            }

            val arrowBox = AxisAlignedBB(
                posX - size, posY - size, posZ - size,
                posX + size, posY + size, posZ + size
            ).addCoord(motionX, motionY, motionZ).expand(1.0, 1.0, 1.0)

            for (entity in xylitolEntitiesWithin(arrowBox)) {
                if (entity.canBeCollidedWith() && entity !== player) {
                    val intercept = entity.entityBoundingBox
                        .expand(size.toDouble(), size.toDouble(), size.toDouble())
                        .calculateIntercept(posBefore, posAfter)
                    if (intercept != null) {
                        hitEntity = true
                        hasLanded = true
                        landingPosition = intercept
                    }
                }
            }

            posX += motionX
            posY += motionY
            posZ += motionZ

            if (mc.theWorld.getBlockState(BlockPos(posX, posY, posZ)).block.material === Material.water) {
                motionX *= 0.6
                motionY *= 0.6
                motionZ *= 0.6
            } else {
                motionX *= motionSlowdown.toDouble()
                motionY *= motionSlowdown.toDouble()
                motionZ *= motionSlowdown.toDouble()
            }
            motionY -= gravity

            GL11.glVertex3d(posX - renderManager.renderPosX, posY - renderManager.renderPosY, posZ - renderManager.renderPosZ)
        }

        GL11.glEnd()
        GlStateManager.pushMatrix()
        GlStateManager.translate(posX - renderManager.renderPosX, posY - renderManager.renderPosY, posZ - renderManager.renderPosZ)
        if (landingPosition != null && landingPosition.sideHit != null) {
            when (landingPosition.sideHit.index) {
                1 -> if (item is ItemEnderPearl) xylitolColor(Color(255, 248, 0, 255))
                2 -> GlStateManager.rotate(90f, 1f, 0f, 0f)
                3 -> GlStateManager.rotate(90f, 1f, 0f, 0f)
                4 -> GlStateManager.rotate(90f, 0f, 0f, 1f)
                5 -> GlStateManager.rotate(90f, 0f, 0f, 1f)
            }

            if (hitEntity) {
                xylitolColor(Color(255, 248, 0, 255))
            }
        }

        xylitolRenderPoint()
        GlStateManager.popMatrix()
        disableXylitolRender3D()
    }

    /** 落点十字 + 水平圆圈（XylitolProjectile.renderPoint）。 */
    private fun xylitolRenderPoint() {
        GL11.glBegin(GL11.GL_LINES)
        GL11.glVertex3d(-0.5, 0.0, 0.0); GL11.glVertex3d(0.0, 0.0, 0.0)
        GL11.glVertex3d(0.0, 0.0, -0.5); GL11.glVertex3d(0.0, 0.0, 0.0)
        GL11.glVertex3d(0.5, 0.0, 0.0); GL11.glVertex3d(0.0, 0.0, 0.0)
        GL11.glVertex3d(0.0, 0.0, 0.5); GL11.glVertex3d(0.0, 0.0, 0.0)
        GL11.glEnd()
        GlStateManager.rotate(90f, 1f, 0f, 0f)
        val step = 0.024543693f
        GL11.glBegin(GL11.GL_LINE_LOOP)
        for (i in 0 until 256) {
            GL11.glVertex3f(cos(i * step) * 0.5f, sin(i * step) * 0.5f, 0f)
        }
        GL11.glEnd()
    }

    private fun xylitolEntitiesWithin(box: AxisAlignedBB): List<Entity> {
        val entities = ArrayList<Entity>()
        val chunkMinX = MathHelper.floor_double((box.minX - 2.0) / 16.0)
        val chunkMaxX = MathHelper.floor_double((box.maxX + 2.0) / 16.0)
        val chunkMinZ = MathHelper.floor_double((box.minZ - 2.0) / 16.0)
        val chunkMaxZ = MathHelper.floor_double((box.maxZ + 2.0) / 16.0)
        for (cx in chunkMinX..chunkMaxX) {
            for (cz in chunkMinZ..chunkMaxZ) {
                if (mc.theWorld.chunkProvider.chunkExists(cx, cz)) {
                    mc.theWorld.getChunkFromChunkCoords(cx, cz)
                        .getEntitiesWithinAABBForEntity(mc.thePlayer, box, entities, null)
                }
            }
        }
        return entities
    }

    private fun enableXylitolRender3D() {
        GlStateManager.depthMask(false)
        GlStateManager.disableDepth()
        GlStateManager.disableAlpha()
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.blendFunc(770, 771)
        GL11.glEnable(GL11.GL_LINE_SMOOTH)
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST)
        GL11.glLineWidth(1f)
    }

    private fun disableXylitolRender3D() {
        GlStateManager.depthMask(true)
        GlStateManager.enableDepth()
        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
        GlStateManager.enableAlpha()
        GL11.glDisable(GL11.GL_LINE_SMOOTH)
        GlStateManager.resetColor()
        GlStateManager.color(1f, 1f, 1f, 1f)
    }

    private fun xylitolColor(color: Color) {
        GlStateManager.color(color.red / 255f, color.green / 255f, color.blue / 255f, color.alpha / 255f)
    }

    // ==================== 共用小工具 ====================

    private fun sq(x: Double, y: Double, z: Double): Double = x * x + y * y + z * z

    private fun brighten(color: Color): Color = Color(
        min(255, color.red + 60),
        min(255, color.green + 60),
        min(255, color.blue + 60)
    )

    private class MoonPath {
        val points = ArrayList<DoubleArray>()
        var hit: MovingObjectPosition? = null
    }

    private class MoonTag(
        val owner: EntityPlayer,
        val name: String,
        val x: Float,
        val y: Float,
        val distance: Float,
        val color: Color,
    )

    private class MoonTrail {
        val points = java.util.ArrayDeque<DoubleArray>()
        var color: Color = Color.WHITE
    }
}
