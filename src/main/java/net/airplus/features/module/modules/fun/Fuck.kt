/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.`fun`

import net.airplus.event.Render2DEvent
import net.airplus.event.UpdateEvent
import net.airplus.event.WorldEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.ui.font.Fonts
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.util.EnumParticleTypes
import net.minecraft.util.MathHelper
import net.minecraftforge.client.event.EntityViewRenderEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import java.awt.Color

/**
 * Fuck —— 站在玩家/生物背后时自动蹲起嘲讽。
 *
 * - 目标距离 2 格（硬编码）；背后扇区固定；蹲满固定进度触发；
 * - 触发后固定 2 秒：镜头晃动 / 雪球破碎白色粒子 / 双方头顶爱心，
 *   三者都持续整整 2 秒（雪花 & 爱心在这 2 秒内持续冒出）；
 * - 所有粒子都是 EffectRenderer 本地粒子（不发包）；
 * - 镜头晃动只走 Forge EntityViewRenderEvent.CameraSetup 叠加临时偏移，
 *   完全本地镜头效果，不修改玩家任何实际视角/发包。
 */
object Fuck : Module("Fuck", Category.FUN, gameDetecting = false) {

    // 全部硬编码，不提供任何自定义项
    private const val RANGE = 2F             // 目标距离 2 格
    private const val BEHIND_DOT = -0.20F    // 背后扇区（~102°）
    private const val FILL_PER_CROUCH = 0.25F
    private const val DRAIN_SPEED = 0.6F
    private const val DURATION_MILLIS = 2000L // 镜头晃动/雪花/爱心统一持续 2 秒
    private const val SNOW_BURST = 40
    private const val SNOW_PER_TICK = 8
    private const val HEARTS_ON_TRIGGER = 5
    private const val HEARTS_PER_TICK = 2
    private const val SHAKE_AMOUNT = 1.2F

    private val progressThreshold = 1F

    private var progress = 0F
    private var wasSneaking = false
    private var lastSquatChange = 0L
    private var lastUpdate = 0L
    private var shakeUntil = 0L

    fun isShaking(): Boolean = handleEvents() && System.currentTimeMillis() < shakeUntil

    val onUpdate = handler<UpdateEvent> {
        val player = mc.thePlayer ?: return@handler
        val world = mc.theWorld ?: return@handler

        val now = System.currentTimeMillis()
        val dt = if (lastUpdate == 0L) 0.05F else ((now - lastUpdate) / 1000F).coerceIn(0F, 0.2F)
        lastUpdate = now

        // 持续 2 秒的效果：雪花持续喷 · 爱心持续冒
        if (now < shakeUntil) {
            spawnSnow(player)
            spawnHearts(player.posX, player.posY + player.height + 0.6, player.posZ, HEARTS_PER_TICK)
            val targets = targetsInRange(player, world)
            for (target in targets) {
                spawnHearts(target.posX, target.posY + target.height + 0.6, target.posZ, HEARTS_PER_TICK)
            }
            return@handler
        }

        val targets = targetsInRange(player, world)

        val sneakNow = player.isSneaking

        // 有效蹲起（背后有目标时切换蹲/立）才会推进进度
        if (sneakNow != wasSneaking) {
            wasSneaking = sneakNow
            if (targets.isNotEmpty()) {
                progress += FILL_PER_CROUCH
                lastSquatChange = now
            }
        }

        // 停止蹲起 1 秒后进度逐渐减少
        if (now - lastSquatChange > 1000) {
            progress -= DRAIN_SPEED * dt
        }

        if (progress >= progressThreshold && targets.isNotEmpty()) {
            trigger(player, targets)
            progress = 0F
        }
        progress = progress.coerceIn(0F, 1F)
    }

    private fun targetsInRange(player: EntityPlayer, world: net.minecraft.world.World): List<EntityLivingBase> =
        world.loadedEntityList.filterIsInstance<EntityLivingBase>()
            .filter { it !== player && it.isEntityAlive && !it.isInvisible &&
                player.getDistanceSqToEntity(it) <= (RANGE * RANGE).toDouble() &&
                isBehind(player, it, BEHIND_DOT) }

    private fun isBehind(player: EntityPlayer, target: EntityLivingBase, threshold: Float): Boolean {
        val toPlayerX = player.posX - target.posX
        val toPlayerZ = player.posZ - target.posZ
        val toPlayerLenSq = toPlayerX * toPlayerX + toPlayerZ * toPlayerZ
        if (toPlayerLenSq < 0.0001) return true
        val toPlayerLen = MathHelper.sqrt_double(toPlayerLenSq)

        // 目标视线方向（水平归一化）
        val look = target.getLook(1F)
        val lookLenSq = look.xCoord * look.xCoord + look.zCoord * look.zCoord
        if (lookLenSq < 0.0001) return false
        val lookLen = MathHelper.sqrt_double(lookLenSq)

        val dot = (toPlayerX / toPlayerLen) * (look.xCoord / lookLen) +
            (toPlayerZ / toPlayerLen) * (look.zCoord / lookLen)
        // dot 接近 1：目标正在看着玩家（不是身后）；dot 越小玩家越在目标的正后方
        return dot < threshold
    }

    private fun trigger(player: EntityPlayer, targets: List<EntityLivingBase>) {
        // 2 秒效果窗口（镜头晃动 + 持续雪花 + 持续爱心，全部 2 秒）
        shakeUntil = System.currentTimeMillis() + DURATION_MILLIS

        // 起手的雪球爆发 + 双方头顶爱心
        spawnSnow(player, SNOW_BURST)
        spawnHearts(player.posX, player.posY + player.height + 0.6, player.posZ, HEARTS_ON_TRIGGER)
        for (target in targets) {
            spawnHearts(target.posX, target.posY + target.height + 0.6, target.posZ, HEARTS_PER_TICK * 2)
        }
    }

    private fun spawnSnow(player: EntityPlayer, count: Int = SNOW_PER_TICK) {
        // 朝向玩家视线前方持续喷射「雪球破碎」白色粒子
        val look = player.lookVec
        repeat(count) {
            val dist = 0.5 + Math.random() * 2.8
            val spread = 0.25
            mc.effectRenderer.spawnEffectParticle(
                EnumParticleTypes.SNOWBALL.particleID,
                player.posX + look.xCoord * dist + (Math.random() - 0.5) * spread,
                player.posY + 1.2 + (Math.random() - 0.5) * 0.6,
                player.posZ + look.zCoord * dist + (Math.random() - 0.5) * spread,
                look.xCoord * (0.15 + Math.random() * 0.35),
                0.02 + Math.random() * 0.05,
                look.zCoord * (0.15 + Math.random() * 0.35)
            )
        }
    }

    private fun spawnHearts(x: Double, y: Double, z: Double, count: Int = 3) {
        repeat(count) {
            mc.effectRenderer.spawnEffectParticle(
                EnumParticleTypes.HEART.particleID,
                x + (Math.random() - 0.5) * 0.8,
                y + Math.random() * 0.4,
                z + (Math.random() - 0.5) * 0.8,
                0.0, 0.0, 0.0
            )
        }
    }

    // ------------------------------------------------------------------
    // 镜头晃动：只在 Forge CameraSetup 上叠加临时偏移 —— 纯本地镜头效果，
    // 不 setTargetRotation / 不改 player.rotationYaw（玩家实际视角完全不动）
    // ------------------------------------------------------------------
    @SubscribeEvent
    fun onCameraSetup(event: EntityViewRenderEvent.CameraSetup) {
        val now = System.currentTimeMillis()
        if (now >= shakeUntil) return

        val remaining = ((shakeUntil - now) / 1000F).coerceIn(0F, 2F)
        val strength = SHAKE_AMOUNT * remaining
        event.yaw += (Math.random().toFloat() - 0.5F) * 2F * strength
        event.pitch += (Math.random().toFloat() - 0.5F) * 2F * strength
        event.roll += (Math.random().toFloat() - 0.5F) * 1.4F * strength
    }

    // ------------------------------------------------------------------
    // HUD 进度条
    // ------------------------------------------------------------------
    val onRender2D = handler<Render2DEvent> {
        mc.thePlayer ?: return@handler
        val barProgress = progress
        if (barProgress < 0.005F) return@handler

        val sr = net.minecraft.client.gui.ScaledResolution(mc)
        val width = sr.scaledWidth.toFloat()
        val height = sr.scaledHeight.toFloat()

        val progressLength = 120F
        val barHeight = 7F
        val startY = (height / 4F * 3F) + 12F
        val startX = width / 2F - progressLength / 2F

        drawRoundedRect(startX, startY, startX + progressLength, startY + barHeight, Color(0, 0, 0, 128).rgb, 2F)
        drawRoundedRect(
            startX, startY,
            startX + progressLength * barProgress.coerceIn(0F, 1F), startY + barHeight,
            Color(255, 96, 150, 255).rgb, 2F
        )

        val text = "SQUAT ${(barProgress * 100).toInt()}%"
        Fonts.fontRegular35.drawString(
            text,
            width / 2F - Fonts.fontRegular35.getStringWidth(text) / 2F,
            startY - 12F,
            Color.WHITE.rgb,
            true
        )
    }

    val onWorld = handler<WorldEvent> {
        progress = 0F
        wasSneaking = false
        shakeUntil = 0L
    }

    override fun onEnable() {
        progress = 0F
        wasSneaking = false
        lastSquatChange = 0L
        lastUpdate = 0L
        shakeUntil = 0L
        MinecraftForge.EVENT_BUS.register(this)
    }

    override fun onDisable() {
        progress = 0F
        shakeUntil = 0L
        MinecraftForge.EVENT_BUS.unregister(this)
    }
}
