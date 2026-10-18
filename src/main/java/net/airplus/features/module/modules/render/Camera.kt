/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
// skid SetsunaClient
package net.airplus.features.module.modules.render

import net.airplus.event.WorldEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.minecraft.util.Vec3

/**
 * Removes third-person camera collision (fixed max zoom distance) and optionally
 * smooths the camera movement ("Action" mode).
 */
object Camera : Module("Camera", Category.RENDER) {

    private val distance by float("Distance", 3.5F, 1F..20F)
    private val action by boolean("Action", true)
    private val actionSmoothness by float("ActionSmoothness", 0.3F, 0.1F..0.95F) { action }
    private val actionMaxDistance by float("ActionMaxDistance", 20F, 1F..50F) { action }

    private var cameraPos: Vec3? = null

    val onWorldChange = handler<WorldEvent> {
        resetCameraPos()
    }

    override fun onEnable() {
        resetCameraPos()
    }

    override fun onDisable() {
        resetCameraPos()
    }

    fun updateActionCamera(targetPos: Vec3) {
        val currentPos = cameraPos ?: run {
            cameraPos = targetPos
            return
        }

        val distanceToTarget = currentPos.distanceTo(targetPos)
        val maxDistance = actionMaxDistance.toDouble()

        if (distanceToTarget > maxDistance) {
            cameraPos = targetPos
            return
        }

        val smoothFactor = actionSmoothness.toDouble() * (1.0 - Math.exp(-distanceToTarget / maxDistance))

        cameraPos = Vec3(
            lerp(smoothFactor, currentPos.xCoord, targetPos.xCoord),
            lerp(smoothFactor, currentPos.yCoord, targetPos.yCoord),
            lerp(smoothFactor, currentPos.zCoord, targetPos.zCoord)
        )
    }

    fun resetCameraPos() {
        cameraPos = null
    }

    fun getCameraPos(): Vec3? = cameraPos

    /**
     * Fixed third-person camera distance replacing the vanilla collision-clamped zoom,
     * read by [net.airplus.injection.forge.mixins.render.MixinEntityRenderer].
     */
    fun getFixedDistance(): Float = distance

    /**
     * Whether the "Action" camera smoothing is currently active,
     * read by [net.airplus.injection.forge.mixins.render.MixinEntityRenderer].
     */
    fun isActionEnabled(): Boolean = handleEvents() && action

    private fun lerp(delta: Double, from: Double, to: Double) = from + (to - from) * delta
}
