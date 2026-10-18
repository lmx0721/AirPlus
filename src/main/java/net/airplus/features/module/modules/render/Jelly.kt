/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.render

import net.airplus.features.module.Category
import net.airplus.features.module.Module
import kotlin.math.abs

/**
 * Jelly module
 *
 * Makes all living entities loop a jelly squash-and-rebound deformation.
 * Pure math only — the GL scale is applied by MixinRendererLivingEntity (rotateCorpse RETURN),
 * inside doRender's pushMatrix/popMatrix so the matrix restores itself.
 */
object Jelly : Module("Jelly", Category.RENDER, gameDetecting = false) {

    private val mode by choices("Mode", arrayOf("Sync", "Wave"), "Sync")
    val intensity by float("Intensity", 0.35f, 0f..1f)
    val speed by float("Speed", 1f, 0.1f..3f)

    /**
     * Vertical scale for the given entity.
     * Called from the mixin: Jelly.INSTANCE.scaleY(entityId); returns 1f when disabled.
     */
    fun scaleY(entityId: Int): Float {
        if (!handleEvents()) return 1f
        val a = intensity * 0.65f
        val cycle = (1000f / speed).toLong()
        val phase = if (mode == "Wave") entityId * 0.618034f else 0f
        // 必须用 Long 取模：Long % Float 会先把毫秒时间戳转 Float，超出 24 位尾数精度，
        // 相位会被量化冻结（表现为只有静态形变、不循环）
        val p = ((System.currentTimeMillis() % cycle).toFloat() / cycle + phase).mod(1f)
        // 线性三角波：p=0/1 处为 1，p=0.5 处压到最低，压扁与回弹均为匀速，周期边界连续
        val sy = 1f - a * (1f - abs(2f * p - 1f))
        return sy.coerceIn(0.35f, 1.35f)
    }
}
