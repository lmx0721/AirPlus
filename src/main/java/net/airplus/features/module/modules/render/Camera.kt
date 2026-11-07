/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
// skid SetsunaClient
package net.airplus.features.module.modules.render

import net.airplus.event.MotionEvent
import net.airplus.event.Render2DEvent
import net.airplus.event.WorldEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.util.Vec3
import net.minecraftforge.client.event.EntityViewRenderEvent
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import org.lwjgl.opengl.GL11
import kotlin.math.abs
import kotlin.math.exp

/**
 * Removes third-person camera collision (fixed max zoom distance), optionally
 * smooths the camera movement ("Action" mode) and applies a customizable
 * camera tilt (roll): "Fixed" leans at a constant angle, "Dynamic" leans into
 * strafing/turning like a cinematic chase camera.
 */
object Camera : Module("Camera", Category.RENDER) {

    private val distance by float("Distance", 3.5F, 1F..20F)
    private val action by boolean("Action", true)
    private val actionSmoothness by float("ActionSmoothness", 0.3F, 0.1F..0.95F) { action }
    private val actionMaxDistance by float("ActionMaxDistance", 20F, 1F..50F) { action }
    private val viewAnimation by boolean("ViewAnimation", true)
    private val viewAnimationSpeed by float("ViewAnimationSpeed", 15F, 2F..50F) { viewAnimation }
    private val noSecondPerson by boolean("NoSecondPerson", false)

    // 镜头倾斜（Camera Roll）：Fixed 固定角度侧倾，Dynamic 随横移/转向方向侧倾
    private val tiltMode by choices("Tilt", arrayOf("Off", "Fixed", "Dynamic"), "Off")
    private val tiltAngle by float("TiltAngle", 12F, -45F..45F) { tiltMode != "Off" }
    private val tiltSmoothness by float("TiltSmoothness", 10F, 1F..30F) { tiltMode != "Off" }
    private val turnLean by boolean("TurnLean", true) { tiltMode == "Dynamic" }

    private var cameraPos: Vec3? = null

    // ===== 整合模块：CameraClip / NoFOV / NoBob / NoHurtCam / FPS HurtCam =====
    // ViewClip：取消第三人称相机碰撞（原 CameraClip 模块）
    val viewClip by boolean("ViewClip", true)
    // NoFOV：按倍率修改自身 FOV 修饰（原 NoFOV 模块）
    val noFOV by boolean("NoFOV", false)
    val fov by float("FOV", 1f, 0f..1.5f) { noFOV }
    // NoBob：移除视角摆动（原 NoBob 模块）
    val noBob by boolean("NoBob", false)
    // NoHurtCam：取消受伤视角抖动（原 NoHurtCam 模块）
    val noHurtCam by boolean("NoHurtCam", false)
    // FPS HurtCam：受伤时屏幕四周红色渐变边框（Ported from MoonLight Camera）
    val fpsHurtCam by boolean("FPSHurtCam", false)

    val noBobHandler = handler<MotionEvent> {
        if (noBob)
            mc.thePlayer?.distanceWalkedModified = -1f
    }

    val onRender2D = handler<Render2DEvent> { event ->
        val player = mc.thePlayer ?: return@handler
        if (!fpsHurtCam || player.maxHurtTime <= 0)
            return@handler

        val hurtTimePercentage = (player.hurtTime - event.partialTicks) / player.maxHurtTime
        if (hurtTimePercentage <= 0f)
            return@handler

        val scaledResolution = ScaledResolution(mc)
        val width = scaledResolution.scaledWidth.toFloat()
        val height = scaledResolution.scaledHeight.toFloat()
        val lineWidth = 20f

        GL11.glDisable(GL11.GL_TEXTURE_2D)
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glShadeModel(GL11.GL_SMOOTH)
        GL11.glDisable(GL11.GL_ALPHA_TEST)

        // 边缘顶点为不透明红（alpha 随 hurtTime 衰减），内侧顶点透明，形成渐变边框
        fun vertex(x: Float, y: Float, edge: Boolean) {
            GL11.glColor4f(1f, 0f, 0f, if (edge) hurtTimePercentage else 0f)
            GL11.glVertex2f(x, y)
        }

        GL11.glBegin(GL11.GL_QUADS)
        // Left
        vertex(0f, 0f, true)
        vertex(0f, height, true)
        vertex(lineWidth, height - lineWidth, false)
        vertex(lineWidth, lineWidth, false)

        // Right
        vertex(width - lineWidth, lineWidth, false)
        vertex(width - lineWidth, height - lineWidth, false)
        vertex(width, height, true)
        vertex(width, 0f, true)

        // Top
        vertex(0f, 0f, true)
        vertex(lineWidth, lineWidth, false)
        vertex(width - lineWidth, lineWidth, false)
        vertex(width, 0f, true)

        // Bottom
        vertex(lineWidth, height - lineWidth, false)
        vertex(0f, height, true)
        vertex(width, height, true)
        vertex(width - lineWidth, height - lineWidth, false)
        GL11.glEnd()

        GL11.glColor4f(1f, 1f, 1f, 1f)
        GL11.glEnable(GL11.GL_ALPHA_TEST)
        GL11.glShadeModel(GL11.GL_FLAT)
        GL11.glDisable(GL11.GL_BLEND)
        GL11.glEnable(GL11.GL_TEXTURE_2D)
    }

    // 镜头倾斜状态：当前 roll（度）与平滑后的转向角速度（度/秒）
    private var currentRoll = 0F
    private var lastTiltFrameTime = 0L
    private var lastTiltYaw = 0F
    private var yawVelocity = 0F

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
        // 镜头倾斜：Forge CameraSetup 在 orientCamera 末尾按 roll→pitch→yaw 旋转，
        // roll 为最外层旋转，即绕视线轴的真实侧倾
        MinecraftForge.EVENT_BUS.register(this)
    }

    override fun onDisable() {
        MinecraftForge.EVENT_BUS.unregister(this)
        currentRoll = 0F
        yawVelocity = 0F
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

    /**
     * 镜头倾斜：把目标 roll 写入 Forge CameraSetup 事件。
     * 符号约定：正 roll = 向右侧倾（原地平线右端上抬）。
     * - Fixed：恒定 [tiltAngle]（可为负换方向）；
     * - Dynamic：随横移方向侧倾（moveStrafing 左=+1，故取负），
     *   开启 TurnLean 时叠加转向角速度（向转向方向压镜头）。
     */
    @SubscribeEvent
    fun onCameraSetup(event: EntityViewRenderEvent.CameraSetup) {
        val player = mc.thePlayer

        if (player == null || FreeCam.handleEvents()) {
            currentRoll = 0F
            return
        }

        val now = Minecraft.getSystemTime()
        val dt = ((now - lastTiltFrameTime).coerceIn(1L, 100L) / 1000.0).toFloat()
        lastTiltFrameTime = now

        // 帧间 yaw 差（处理 ±180° 回绕）→ 平滑角速度，供 Dynamic 的转向侧倾使用
        var yawDelta = player.rotationYaw - lastTiltYaw
        while (yawDelta > 180F) yawDelta -= 360F
        while (yawDelta < -180F) yawDelta += 360F
        lastTiltYaw = player.rotationYaw
        val targetYawVelocity = (yawDelta / dt).coerceIn(-720F, 720F)
        yawVelocity += (targetYawVelocity - yawVelocity) * (1F - exp(-12F * dt))

        val target = when (tiltMode) {
            "Fixed" -> tiltAngle
            "Dynamic" -> {
                var lean = -player.moveStrafing * tiltAngle
                if (turnLean) {
                    lean += (yawVelocity / 360F).coerceIn(-1F, 1F) * tiltAngle
                }
                lean
            }
            else -> 0F
        }

        currentRoll += (target - currentRoll) * (1F - exp(-tiltSmoothness * dt))
        if (abs(currentRoll) < 0.01F) currentRoll = 0F
        event.roll = currentRoll
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
