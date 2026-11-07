/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Ported from MoonLight AspectRatio（修改世界渲染纵横比）。
 */
package net.airplus.features.module.modules.render

import net.airplus.features.module.Category
import net.airplus.features.module.Module

object AspectRatio : Module("AspectRatio", Category.RENDER, gameDetecting = false) {
    val aspect by float("Aspect", 1f, 0.1f..5f)
}
