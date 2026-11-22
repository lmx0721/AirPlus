/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.client

import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.ui.glass.ContainerSkin
import net.minecraft.client.gui.inventory.GuiContainer
import java.awt.Color

/**
 * BetterMyau 的玻璃材质界面移植：把背包/箱子等原版容器界面换成玻璃皮肤
 * （悬浮玻璃面板 + 顶部标签变亮 + 右侧药水效果卡片）。
 */
object CustomGui : Module("CustomGui", Category.CLIENT, defaultState = true) {
    val containers by boolean("Containers", true)

    // 背板外观模式：Liquid = 液态玻璃透镜（默认）；Frosted = 磨砂模糊（无透镜）；
    // New = 新样式（大圆角方格 + blur 浮层 + blur 物品信息卡 + 淡入）；Off = 不模糊的实色玻璃
    val blurMode by choices("Background-Blur", arrayOf("Liquid", "Frosted", "New", "Off"), "New") { containers }

    // 光标瞄准格子的动画速度（高亮框滑动 / 显隐 / 物品放大全部按该倍率加速）
    val highlightSpeed by float("Highlight-Speed", 1f, 0.1f..5f) { containers }

    // 光标瞄准格子的高亮颜色（描边 + 光晕；默认与玻璃主题 accent 一致）
    val highlightColorValue = color("Highlight-Color", Color(79, 155, 255, 255)) { containers }

    /** 当前容器界面是否应由玻璃皮肤绘制（背包/箱子/工作台/熔炉/发射器/漏斗/酿造台）。 */
    fun shouldSkinContainers(screen: GuiContainer): Boolean =
        handleEvents() && containers && ContainerSkin.handles(screen)
}
