/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.render

import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.render.MotionBlurRenderer

/**
 * 动态模糊（BetterMyau 迁移）。基于深度重投影的逐像素速度缓冲 + TileMax/NeighborMax
 * + McGuire 重构滤波（见 MotionBlurRenderer）。MixinEntityRenderer 在世界渲染完成后、
 * 手部/ESP 之前调用 apply —— 只模糊世界，手部/HUD/ESP 保持清晰。
 *
 * Quality：每像素采样数 Low/Medium/High/Ultra → 8/12/20/32。
 * Shutter：快门角度（相对 60fps 帧），180° 电影感，360° 全帧拖影，与帧率解耦。
 * Max Length：最长拖尾（屏幕高度百分比）。
 * Translation：开启后走动/飞行等平移也参与模糊，不只旋转。
 */
object MotionBlur : Module("MotionBlur", Category.RENDER) {

    private val quality by choices("Quality", arrayOf("Low", "Medium", "High", "Ultra"), "High")

    /** 快门角度（度，30..360），相对 60fps 帧。 */
    val shutter by int("Shutter", 180, 30..360)

    /** 最长拖尾长度（屏幕高度百分比 1..10）。 */
    val maxLength by int("Max Length", 4, 1..10)

    /** 平移（走动/飞行）是否也参与模糊，不只旋转。 */
    val translation by boolean("Translation", true)

    override val tag
        get() = quality

    /** Quality → 每像素采样数。 */
    fun samples(): Int = when (quality.lowercase()) {
        "low" -> 8
        "medium" -> 12
        "high" -> 20
        else -> 32
    }

    override fun onEnable() {
        // 首帧只记录基准，不模糊，避免陈旧差值拉出假拖尾
        MotionBlurRenderer.reset()
    }

    override fun onDisable() {
        MotionBlurRenderer.release()
    }
}
