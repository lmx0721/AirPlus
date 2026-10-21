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
import net.minecraft.client.Minecraft
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
    private val viewAnimation by boolean("ViewAnimation", true)
    private val viewAnimationSpeed by float("ViewAnimationSpeed", 15F, 2F..50F) { viewAnimation }
    private val noSecondPerson by boolean("NoSecondPerson", false)

    private var cameraPos: Vec3? = null

    // 切换人称动画：F5（或 autoF5）切换时先收缩相机距离到 0，再切到新人称展开
    private var lastAppliedView = 0
    private var heldView = 0
    private var targetView = 0
    private var transitioning = false
    private var transitionProgress = 0F
    private var animatedDistance = 0F
    private var lastFrameTime = 0L

    val onWorldChange = handler<WorldEvent> {
        resetCameraPos()
    }

    override fun onEnable() {
        resetCameraPos()
        syncViewState()
    }

    override fun onDisable() {
        resetCameraPos()
        // 中途关闭模块时立即恢复目标人称，避免视角卡在第三人称
        if (transitioning) {
            mc.gameSettings.thirdPersonView = targetView
            transitioning = false
        }
        transitionProgress = 0F
        lastAppliedView = mc.gameSettings.thirdPersonView
    }

    private fun syncViewState() {
        lastAppliedView = mc.gameSettings.thirdPersonView
        animatedDistance = if (lastAppliedView == 0) 0F else distance
        transitioning = false
        transitionProgress = 0F
    }

    /**
     * 每帧渲染前由 [net.airplus.injection.forge.mixins.render.MixinEntityRenderer] 调用：
     * 检测人称变化，过渡期间将 thirdPersonView 暂时保持在旧人称，同时把相机距离
     * 收缩到 0，到位后切到新人称再展开到目标距离。返回当前动画中的相机距离。
     *
     * 采用固定时长的缓入缓出进度（triangular 距离曲线），相机位置从旧人称平滑地
     * 直接滑向新人称，不再使用指数缓动（其渐近尾巴会造成「卡顿 + 瞬移」）。
     */
    fun updateViewTransition(): Float {
        val settings = mc.gameSettings

        if (mc.thePlayer == null) {
            if (transitioning) {
                settings.thirdPersonView = targetView
                transitioning = false
            }
            lastAppliedView = settings.thirdPersonView
            return 0F
        }

        if (!viewAnimation) {
            if (transitioning) {
                settings.thirdPersonView = targetView
                transitioning = false
            }
            // NoSecondPerson：把第二人称改写回第一人称（F5 循环变成 0 ↔ 1）
            if (noSecondPerson && settings.thirdPersonView == 2) {
                settings.thirdPersonView = 0
            }
            lastAppliedView = settings.thirdPersonView
            animatedDistance = if (settings.thirdPersonView == 0) 0F else distance
            return animatedDistance
        }

        val now = Minecraft.getSystemTime()
        val dt = ((now - lastFrameTime).coerceIn(1L, 100L) / 1000.0).toFloat()
        lastFrameTime = now

        // 本帧游戏逻辑写入的人称（F5 / autoF5 的结果）；此处尚未被过渡逻辑覆盖
        val frameView = settings.thirdPersonView

        if (!transitioning && frameView != lastAppliedView) {
            targetView = requestedView(frameView)
            if (targetView != frameView) settings.thirdPersonView = targetView
            heldView = lastAppliedView
            transitionProgress = 0F
            transitioning = true
        }

        if (transitioning) {
            // 过渡期间再次切换人称：以当前请求为新目标，从当前位置继续
            if (frameView != heldView && frameView != targetView) {
                targetView = requestedView(frameView)
                if (targetView != frameView) settings.thirdPersonView = targetView
            }

            // 固定时长（默认 ViewAnimationSpeed=15 → 约 0.5s），按速度反比缩放
            val duration = (7.5F / viewAnimationSpeed).coerceIn(0.08F, 2F)
            transitionProgress += dt / duration
            val t = transitionProgress.coerceIn(0F, 1F)
            // smoothstep：缓入缓出，两端静止、中段匀速，避免在人物处停滞
            val e = t * t * (3F - 2F * t)

            val heldDistance = if (heldView == 0) 0F else distance
            val targetDistance = if (targetView == 0) 0F else distance

            when {
                // 第一人称 → 第三人称：立即切换视角、距离直接展开。
                // 旧的两段式前半段在第一人称下没有任何视觉变化，会给人「等一下才切换」的感觉
                heldView == 0 -> {
                    settings.thirdPersonView = targetView
                    animatedDistance = targetDistance * e
                }
                // 第三人称 → 第一人称：保持旧人称，距离全程收缩到 0，收缩完成立即切第一人称
                targetView == 0 -> {
                    settings.thirdPersonView = heldView
                    animatedDistance = heldDistance * (1F - e)
                }
                else -> {
                    // 第三人称 ↔ 第三人称（背/ front 视角互换）：保持旧人称收缩到 0，再切新人称展开
                    if (e < 0.5F) {
                        settings.thirdPersonView = heldView
                        animatedDistance = heldDistance * (1F - e * 2F)
                    } else {
                        settings.thirdPersonView = targetView
                        animatedDistance = targetDistance * (e * 2F - 1F)
                    }
                }
            }

            if (t >= 1F) {
                animatedDistance = targetDistance
                settings.thirdPersonView = targetView
                lastAppliedView = targetView
                transitioning = false
            }
        } else {
            val target = if (frameView == 0) 0F else distance
            easeDistance(target, dt.toDouble(), viewAnimationSpeed.toDouble())
        }

        return animatedDistance
    }

    private fun easeDistance(target: Float, dt: Double, speed: Double) {
        val factor = (1.0 - Math.exp(-speed * dt)).toFloat()
        animatedDistance += (target - animatedDistance) * factor
    }

    /**
     * 处理人称请求：启用 NoSecondPerson 时把第二人称（view 2）映射为第一人称，
     * F5 循环由 0→1→2→0 变为 0↔1。
     */
    private fun requestedView(view: Int): Int =
        if (noSecondPerson && view == 2) 0 else view

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
     * Current animated camera distance (settles at the configured Distance value,
     * collapses to 0 during perspective switches), read by
     * [net.airplus.injection.forge.mixins.render.MixinEntityRenderer].
     */
    fun getAnimatedDistance(): Float = animatedDistance

    /**
     * Whether the "Action" camera smoothing is currently active,
     * read by [net.airplus.injection.forge.mixins.render.MixinEntityRenderer].
     */
    fun isActionEnabled(): Boolean = handleEvents() && action

    private fun lerp(delta: Double, from: Double, to: Double) = from + (to - from) * delta
}
