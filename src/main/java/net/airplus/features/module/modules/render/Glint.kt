/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.render

import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.render.ColorSettingsInteger
import net.minecraft.item.ItemStack
import java.awt.Color
import kotlin.math.PI
import kotlin.math.sin

/**
 * Glint：自定义附魔光效颜色 / 样式，并可让未附魔物品也叠加光效。
 *
 * 实际绘制由 [net.airplus.injection.forge.mixins.render.MixinRenderItem] 接管：
 * - 重定向 RenderItem.renderItem 中的 ItemStack.hasEffect()，让未附魔物品进入光效分支
 * - 重定向 RenderItem.renderEffect 内的 renderModel(model, color) 调用，改为按样式颜色多次绘制
 *
 * 注意：原版 renderEffect 使用 GL_SRC_COLOR + GL_ONE 纯加色混合，alpha 不生效，
 * 因此亮度/呼吸效果只能通过 RGB 缩放实现。
 */
object Glint : Module("Glint", Category.RENDER) {

    private val style by choices("Style", arrayOf("Solid", "Rainbow", "Pulse"), "Solid")
    val colors = ColorSettingsInteger(this, "Color") { style != "Rainbow" }.with(180, 130, 255)

    private val speed by float("Speed", 1F, 0.1F..5F) { style != "Solid" }
    private val pulseMin by float("Pulse-Min", 0.35F, 0F..1F) { style == "Pulse" }

    /** 叠加绘制次数：越多越亮（加色混合），1 为最暗 */
    private val intensity by int("Intensity", 2, 1..6)

    /** Enchanted：仅替换已附魔物品的颜色；All：未附魔物品也叠加光效 */
    private val targetItems by choices("TargetItems", arrayOf("Enchanted", "All"), "Enchanted")

    val targetAll
        get() = targetItems == "All"

    /**
     * 供 MixinRenderItem 判断是否强制让未附魔物品走光效分支
     */
    fun shouldForceEffect(stack: ItemStack): Boolean =
        state && targetAll && stack.stackSize > 0

    /**
     * 当前帧的光效颜色（ARGB）；模块关闭时返回 null，调用方应回退到原版颜色
     */
    fun getCurrentColor(): Int? {
        if (!state) return null

        val now = System.currentTimeMillis()
        val base = when (style) {
            "Rainbow" -> {
                val hue = ((now * speed / 4000.0) % 1.0).toFloat()
                Color(Color.HSBtoRGB(hue, 1F, 1F))
            }

            "Pulse" -> {
                val c = colors.color()
                val factor = pulseMin + (1F - pulseMin) *
                        (0.5F + 0.5F * sin(now / 1000.0 * speed * 2.0 * PI)).toFloat()
                Color(
                    (c.red * factor).toInt().coerceIn(0, 255),
                    (c.green * factor).toInt().coerceIn(0, 255),
                    (c.blue * factor).toInt().coerceIn(0, 255)
                )
            }

            else -> colors.color()
        }

        return (0xFF shl 24) or (base.red shl 16) or (base.green shl 8) or base.blue
    }

    val passes
        get() = if (state) intensity else 1
}
