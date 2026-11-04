// skid LeaderClient
package net.airplus.features.module.modules.render

import net.airplus.event.AttackEvent
import net.airplus.event.UpdateEvent
import net.airplus.event.WorldEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.client.asResourceLocation
import net.airplus.utils.client.playSound
import net.minecraft.block.Block
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.effect.EntityLightningBolt
import net.minecraft.init.Blocks
import net.minecraft.util.EnumParticleTypes
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object KillEffectV2 : Module("KillEffectV2", Category.RENDER) {

    var killedTimes = 0
        private set

    private val mode by choices("Mode", arrayOf("Default", "LightArrow", "LightAbyss", "BrightShatter"), "Default")

    private val lightning by boolean("Lightning", true) { mode == "Default" }
    private val explosion by boolean("Explosion", true) { mode == "Default" }
    private val blood by boolean("Blood", true) { mode == "Default" }

    private var target: EntityLivingBase? = null
    private var lastEffectTime = 0L

    private val anims = ArrayList<DeathAnim>()

    val onUpdate = handler<UpdateEvent> {
        // 多帧动画推进（与击杀检测无关，任何阶段都持续播放到结束）
        anims.removeAll { it.tick(mc.theWorld == null) }

        val t = target ?: return@handler
        if (!mc.theWorld.loadedEntityList.contains(t) || t.health <= 0) {
            val now = System.currentTimeMillis()
            // 1s 冷却（不可开关），1s 内不会重复触发
            if (handleEvents() && now - lastEffectTime >= 1000) {
                lastEffectTime = now
                play(t)
            }
            target = null
            killedTimes++
        }
    }

    /**
     * 死亡时间点捕获目标位置快照（目标下一 tick 就可能被清出世界），
     * 之后所有特效都以快照坐标做纯客户端粒子演出，不发任何包。
     */
    private fun play(t: EntityLivingBase) {
        val x = t.posX
        val y = t.posY
        val z = t.posZ
        val h = t.height.toDouble()

        when (mode) {
            "LightArrow" -> anims += DeathAnim("LightArrow", x, y, z, h)
            "LightAbyss" -> anims += DeathAnim("LightAbyss", x, y, z, h)
            "BrightShatter" -> anims += DeathAnim("BrightShatter", x, y, z, h)

            else -> {
                // Default：原有特效
                if (lightning) {
                    val bolt = EntityLightningBolt(mc.theWorld, x, y, z)
                    mc.theWorld.addEntityToWorld((-Math.random() * 100000).toInt(), bolt)
                    mc.playSound("ambient.weather.thunder".asResourceLocation())
                }

                if (explosion) {
                    for (i in 0..8) {
                        mc.effectRenderer.spawnEffectParticle(
                            EnumParticleTypes.FLAME.particleID, x, y + h / 2.0, z,
                            nextFloat(-0.5f, 0.5f), nextFloat(0f, 0.4f), nextFloat(-0.5f, 0.5f)
                        )
                    }
                    mc.playSound("item.fireCharge.use".asResourceLocation())
                }

                if (blood) {
                    for (i in 0 until 10) {
                        mc.effectRenderer.spawnEffectParticle(
                            EnumParticleTypes.BLOCK_CRACK.particleID,
                            x,
                            y + h / 2.0,
                            z,
                            nextFloat(-0.5f, 0.5f),
                            nextFloat(-0.5f, 0.5f),
                            nextFloat(-0.5f, 0.5f),
                            Block.getStateId(Blocks.redstone_block.defaultState)
                        )
                    }
                }
            }
        }
    }

    val onWorld = handler<WorldEvent> {
        target = null
        anims.clear()
    }

    val onAttack = handler<AttackEvent> { event ->
        val entity = event.targetEntity ?: return@handler
        if (entity is EntityLivingBase) {
            target = entity
        }
    }

    override fun onDisable() {
        target = null
        lastEffectTime = 0
        anims.clear()
    }

    /**
     * 一段击杀特效的多 tick 粒子动画，最多同时多个目标各自独立播放。
     * 全部走 EffectRenderer 本地粒子 + 本地音效，无任何网络数据包。
     */
    private class DeathAnim(
        val kind: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val height: Double
    ) : MinecraftInstance {
        private var tick = 0
        private val random = java.util.Random()

        private val chestY = y + height * 0.5
        private val footY = y

        fun tick(worldGone: Boolean): Boolean {
            if (worldGone) return true

            when (kind) {
                "LightArrow" -> tickLightArrow()
                "LightAbyss" -> tickLightAbyss()
                "BrightShatter" -> tickBrightShatter()
            }

            tick++
            return tick >= total
        }

        private val total = when (kind) {
            "LightArrow" -> 36
            "LightAbyss" -> 46
            else -> 42
        }

        // ------------------------------------------------------------------
        // 1. LightArrow：从天而降一支巨大的光箭刺穿敌人身体
        // ------------------------------------------------------------------
        private fun tickLightArrow() {
            val dropTicks = 24

            // 拉弦音效
            if (tick == 0) {
                mc.playSound("random.bow".asResourceLocation(), 0.4f)
            }

            if (tick < dropTicks) {
                // 箭矢从高空垂直落下：箭尖端从高空降到胸口
                val startTipY = chestY + 16.0
                val endTipY = chestY + 0.2
                val tipY = startTipY + (endTipY - startTipY) * tick / dropTicks.toDouble()

                // 箭杆：从尖端向上再延伸约 8 格的长条光柱（白金色）
                val shaftLength = 8.0
                val steps = 18
                for (i in 0..steps) {
                    val py = tipY + shaftLength * (i.toDouble() / steps)
                    val spreadX = nextFloat(-0.08f, 0.08f)
                    val spreadZ = nextFloat(-0.08f, 0.08f)

                    // 主体光柱
                    mc.effectRenderer.spawnEffectParticle(
                        EnumParticleTypes.FLAME.particleID,
                        x + spreadX, py, z + spreadZ,
                        0.0, nextFloat(-0.05f, 0.02f), 0.0
                    )
                    // 外圈光晕
                    if (random.nextInt(3) == 0) {
                        mc.effectRenderer.spawnEffectParticle(
                            EnumParticleTypes.CLOUD.particleID,
                            x + nextFloat(-0.25f, 0.25f), py, z + nextFloat(-0.25f, 0.25f),
                            0.0, -0.01, 0.0
                        )
                    }
                }
                // 尾迹火花
                for (i in 0..3) {
                    mc.effectRenderer.spawnEffectParticle(
                        EnumParticleTypes.FIREWORKS_SPARK.particleID,
                        x + nextFloat(-0.15f, 0.15f), tipY + nextFloat(-2.0, 0.0), z + nextFloat(-0.15f, 0.15f),
                        0.0, 0.0, 0.0
                    )
                }
            }

            // 穿刺瞬间：巨大光爆 + 冲击环 + 雷鸣
            if (tick == dropTicks) {
                mc.effectRenderer.spawnEffectParticle(
                    EnumParticleTypes.EXPLOSION_LARGE.particleID,
                    x, chestY, z, 0.0, 0.0, 0.0
                )
                mc.playSound("random.explode".asResourceLocation(), 0.9f)
                mc.playSound("ambient.weather.thunder".asResourceLocation(), 1.3f)

                // 水平冲击环
                val ring = 36
                for (i in 0 until ring) {
                    val angle = PI * 2.0 * i / ring
                    val rx = x + cos(angle) * 0.6
                    val rz = z + sin(angle) * 0.6
                    mc.effectRenderer.spawnEffectParticle(
                        EnumParticleTypes.FIREWORKS_SPARK.particleID,
                        rx, chestY, rz,
                        cos(angle) * nextFloat(0.3f, 0.7f),
                        nextFloat(-0.05f, 0.05f),
                        sin(angle) * nextFloat(0.3f, 0.7f)
                    )
                }
            }

            // 穿刺后的余震：胸口持续迸射光屑，地面也留一点光
            if (tick in dropTicks until total) {
                for (i in 0..5) {
                    mc.effectRenderer.spawnEffectParticle(
                        EnumParticleTypes.FLAME.particleID,
                        x + nextFloat(-0.4f, 0.4f), chestY + nextFloat(-0.6, 0.6), z + nextFloat(-0.4f, 0.4f),
                        nextFloat(-0.2f, 0.2f), nextFloat(0.0f, 0.15f), nextFloat(-0.2f, 0.2f)
                    )
                }
                if (random.nextInt(2) == 0) {
                    mc.effectRenderer.spawnEffectParticle(
                        EnumParticleTypes.CLOUD.particleID,
                        x + nextFloat(-0.3f, 0.3f), footY + 0.1, z + nextFloat(-0.3f, 0.3f),
                        0.0, 0.02, 0.0
                    )
                }
            }
        }

        // ------------------------------------------------------------------
        // 2. LightAbyss：敌人脚下出现一个光洞吞噬敌人
        // ------------------------------------------------------------------
        private fun tickLightAbyss() {
            if (tick == 0) {
                mc.playSound("portal.trigger".asResourceLocation(), 0.8f)
            }

            // 地面光圈从 2.5 格半径快速收缩 INTO 洞心，洞越缩越深、越亮
            val collapseTicks = 28
            val radius: Double
            if (tick < collapseTicks) {
                val progress = tick.toDouble() / collapseTicks
                radius = 2.5 * (1.0 - progress) + 0.12
            } else {
                radius = 0.12
            }

            // 光圈主体（紫金漩涡）
            val points = 14
            for (i in 0 until points) {
                val angle = PI * 2.0 * i / points + tick * 0.22
                val px = x + cos(angle) * radius
                val pz = z + sin(angle) * radius

                mc.effectRenderer.spawnEffectParticle(
                    EnumParticleTypes.PORTAL.particleID,
                    px, footY + 0.15, pz,
                    -cos(angle) * 0.08, nextFloat(0.01f, 0.08f), -sin(angle) * 0.08
                )
                if (random.nextInt(3) == 0) {
                    mc.effectRenderer.spawnEffectParticle(
                        EnumParticleTypes.SPELL_WITCH.particleID,
                        px, footY + 0.1, pz,
                        0.0, 0.0, 0.0
                    )
                }
            }

            // 洞心向下吸力：敌人身体位置的粒子不断往下坠入光洞
            for (i in 0..4) {
                mc.effectRenderer.spawnEffectParticle(
                    EnumParticleTypes.PORTAL.particleID,
                    x + nextFloat(-0.4f, 0.4f), chestY + nextFloat(-0.6, 0.4), z + nextFloat(-0.4f, 0.4f),
                    nextFloat(-0.05f, 0.05f), nextFloat(-0.25f, -0.08f), nextFloat(-0.05f, 0.05f)
                )
            }

            if (tick == collapseTicks - 1) {
                // 吞噬完成：洞心强闪 + 音效
                mc.effectRenderer.spawnEffectParticle(
                    EnumParticleTypes.EXPLOSION_LARGE.particleID,
                    x, footY + 0.3, z, 0.0, 0.0, 0.0
                )
                mc.playSound("portal.travel".asResourceLocation())
                mc.playSound("random.explode".asResourceLocation(), 0.5f)
            }

            // 收尾：洞心余光 + 几缕紫烟贴地
            if (tick > collapseTicks) {
                for (i in 0..2) {
                    mc.effectRenderer.spawnEffectParticle(
                        EnumParticleTypes.SPELL_WITCH.particleID,
                        x + nextFloat(-0.2f, 0.2f), footY + 0.1, z + nextFloat(-0.2f, 0.2f),
                        0.0, nextFloat(0.01f, 0.05f), 0.0
                    )
                }
                if (tick == total - 1) {
                    mc.playSound("random.fizz".asResourceLocation(), 0.7f)
                }
            }
        }

        // ------------------------------------------------------------------
        // 3. BrightShatter：模型快速变得极亮后爆炸开来，在地上散落一地
        // ------------------------------------------------------------------
        private fun tickBrightShatter() {
            val chargeTicks = 13

            if (tick < chargeTicks) {
                // 充能变亮：全身被越来越密集的白色微粒包裹（发光临界感）
                val progress = tick.toDouble() / chargeTicks
                val bodyRadius = (0.8 - 0.35 * progress).coerceAtLeast(0.1)
                val count = (8 + progress * 22).toInt()
                for (i in 0 until count) {
                    val angle = random.nextDouble() * PI * 2.0
                    val py = footY + random.nextDouble() * height
                    mc.effectRenderer.spawnEffectParticle(
                        EnumParticleTypes.CLOUD.particleID,
                        x + cos(angle) * bodyRadius, py, z + sin(angle) * bodyRadius,
                        nextFloat(-0.03f, 0.03f), nextFloat(0.0f, 0.08f), nextFloat(-0.03f, 0.03f)
                    )
                    if (random.nextInt(3) == 0) {
                        mc.effectRenderer.spawnEffectParticle(
                            EnumParticleTypes.SPELL.particleID,
                            x, py, z, 0.0, 0.0, 0.0
                        )
                    }
                }

                // 升调充能音
                if (tick % 3 == 0) {
                    mc.playSound("random.orb".asResourceLocation(), (0.6f + tick * 0.25f).coerceAtMost(2f))
                }
            }

            // 爆炸瞬间：极亮闪光 + 白色碎片向四周高速飞散
            if (tick == chargeTicks) {
                mc.effectRenderer.spawnEffectParticle(
                    EnumParticleTypes.EXPLOSION_HUGE.particleID,
                    x, chestY, z, 0.0, 0.0, 0.0
                )
                mc.playSound("random.explode".asResourceLocation())
                mc.playSound("item.fireCharge.use".asResourceLocation(), 1.4f)

                // 漫天碎片（雪铲粒子：会被重力拉回并在地上弹跳滑行，适合「散落一地」）
                for (i in 0 until 90) {
                    val angle = random.nextDouble() * PI * 2.0
                    val speed = nextFloat(0.25f, 0.85f)
                    mc.effectRenderer.spawnEffectParticle(
                        EnumParticleTypes.SNOW_SHOVEL.particleID,
                        x, footY + nextFloat(0.2f, 1.8f), z,
                        cos(angle) * speed, nextFloat(0.05f, 0.3f), sin(angle) * speed
                    )
                }
                // 落地玻璃碎屑
                for (i in 0 until 30) {
                    val angle = random.nextDouble() * PI * 2.0
                    val dist = nextFloat(0.4f, 1.6f)
                    mc.effectRenderer.spawnEffectParticle(
                        EnumParticleTypes.BLOCK_CRACK.particleID,
                        x + cos(angle) * dist, footY + nextFloat(0.3f, 1.2f), z + sin(angle) * dist,
                        cos(angle) * 0.1, nextFloat(0.0f, 0.12f), sin(angle) * 0.1,
                        Block.getStateId(Blocks.quartz_block.defaultState)
                    )
                }
            }

            // 余烬：碎片继续洒落，并伴随零星火花
            if (tick > chargeTicks) {
                for (i in 0..4) {
                    mc.effectRenderer.spawnEffectParticle(
                        EnumParticleTypes.SNOW_SHOVEL.particleID,
                        x + nextFloat(-0.6f, 0.6f), footY + nextFloat(0.8f, 1.8f), z + nextFloat(-0.6f, 0.6f),
                        nextFloat(-0.05f, 0.05f), nextFloat(-0.02f, 0.05f), nextFloat(-0.05f, 0.05f)
                    )
                }
                if (random.nextInt(2) == 0) {
                    mc.effectRenderer.spawnEffectParticle(
                        EnumParticleTypes.FIREWORKS_SPARK.particleID,
                        x + nextFloat(-0.5f, 0.5f), footY + nextFloat(0.1f, 1.2f), z + nextFloat(-0.5f, 0.5f),
                        0.0, -0.02, 0.0
                    )
                }
            }
        }

        private fun nextFloat(startInclusive: Number, endInclusive: Number): Double {
            val start = startInclusive.toDouble()
            val end = endInclusive.toDouble()
            if (end - start <= 0.0) return start
            return start + (end - start) * random.nextDouble()
        }
    }

    private fun nextFloat(startInclusive: Number, endInclusive: Number): Double {
        val start = startInclusive.toDouble()
        val end = endInclusive.toDouble()
        if (start == end || end - start <= 0.0) {
            return start
        }
        return start + (end - start) * Math.random()
    }
}
