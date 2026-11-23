/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.render

import net.airplus.event.WorldEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.render.ColorSettingsInteger
import net.airplus.utils.render.ShaderSky
import net.airplus.utils.render.shader.MenuShaderBackground

/**
 * Sky：覆盖世界天空。
 * - Color：自定义纯色天空（GL 直绘）
 * - Shader：主菜单 Shader 预设（MenuShaderBackground 的 89 个 GLSL 120 碎片着色器）
 *
 * 走 Forge WorldProvider.setSkyRenderer 钩子，无 mixin；地形自然覆盖地平线以下，
 * 可选地平线雾化带消除天空与地形雾色的硬接缝。OptiFine shader 包开启时自动让位 vanilla。
 */
object Sky : Module("Sky", Category.RENDER) {

    /** 天空类型：纯色 / 菜单 Shader */
    val type by choices("Type", arrayOf("Color", "Shader"), "Shader")

    /** 纯色天空颜色（Type = Color 时生效） */
    val skyColors = ColorSettingsInteger(this, "Sky-Colors", applyMax = true)
    { type == "Color" }.with(r = 120, g = 167, b = 255, a = 255)

    /** 天空预设（与主菜单背景共用同一套 shader 资源与编译管线） */
    val mode by choices("Mode", MenuShaderBackground.SHADER_NAMES.toTypedArray(), "galaxy")
    { type == "Shader" }

    /** 地平线雾化带：天空在地平线附近淡入地形雾色 */
    val horizonFade by boolean("Horizon-Fade", true)

    /** 预设动画时间倍率（部分预设自带慢速动画，>1 加速 / <1 减速） */
    val timeScale by float("Time-Scale", 1F, 0.1F..3F)
    { type == "Shader" }

    override fun onEnable() {
        // force = true：Module 基类先调 onEnable 再置 state，handleEvents() 此处恒为 false
        ShaderSky.applyToWorld(mc.theWorld, force = true)
    }

    override fun onDisable() {
        ShaderSky.removeFrom(mc.theWorld)
    }

    /** 维度切换：WorldClient/provider 重建后重新挂载 */
    val onWorldChange = handler<WorldEvent> { event ->
        if (handleEvents()) ShaderSky.applyToWorld(event.worldClient ?: mc.theWorld)
    }
}
