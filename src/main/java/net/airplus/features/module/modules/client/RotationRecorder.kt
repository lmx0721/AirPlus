/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.airplus.utils.client.chat
import net.airplus.utils.kotlin.SharedScopes
import net.airplus.event.AttackEvent
import net.airplus.event.GameTickEvent
import net.airplus.event.Render2DEvent
import net.airplus.event.Render3DEvent
import net.airplus.event.RotationSetEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.modules.misc.NoRotateSet
import net.airplus.features.module.modules.render.FreeLook
import net.airplus.utils.extensions.component1
import net.airplus.utils.extensions.component2
import net.airplus.utils.extensions.component3
import net.airplus.utils.extensions.minus
import net.airplus.utils.extensions.random
import net.airplus.utils.extensions.renderPos
import net.airplus.utils.render.RenderUtils.glColor
import net.airplus.utils.rotation.RotationUtils
import net.airplus.utils.rotation.aim.AimDatasetStore
import net.airplus.utils.rotation.aim.TickSample
import net.airplus.utils.client.chat
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.entity.EntityLivingBase
import net.minecraft.util.Vec3
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import java.awt.Color
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 录制玩家自己的真实旋转数据：原始鼠标 yaw/pitch 增量 + 上下文特征，
 * 存为 rotations/dataset.json（供离线训练）并自动分段重建 rotations/library.json（模板库）。
 *
 * 启用 = 采集（可切换仅战斗时采集）；禁用 = 分段 + 存盘。
 * 开启 AimBall 设置后进入采集小游戏：在玩家周围随机生成一个小球（准星旁的箭头指示方向），
 * 把准星转入小球判定圈后点击（或持续瞄准一段时间）即完成一轮，小球换位。
 * 每轮把「发现目标 → 甩头 → 命中」的真实鼠标增量按 TickSample 存入数据集。
 * 判定圈 generously 大于视觉球体，命中点不要求精确对准球心——
 * 刻意保留人类的瞄准过冲、回修与端点随机性，避免采集出「机械完美」的分布。
 */
object RotationRecorder : Module("RotationRecorder", Category.CLIENT, gameDetecting = false) {

    /** 仅战斗采集：攻击后 3 秒内的 tick 才计入数据，过滤平时看风景的无关转动。 */
    private val onlyCombatValue = boolean("OnlyCombat", false)

    /** 开关一次清空 rotations/ 下全部数据（dataset/library/net_weights），清完自动复位。 */
    private val clearDataValue = boolean("ClearData", false)

    // ---- AimBall 采集小游戏 ----

    /** 开启后进入小球采集模式。 */
    private val aimBallValue = boolean("AimBall", false)

    // 命中判定角（度）＝准星落入小球判定圈。默认 4°，比人头视角略大
    private val toleranceValue = float("Tolerance", 4f, 2f..8f, "°") { aimBallValue.get() }

    // 完成方式：Click = 瞄准圈内点击；Dwell = 持续瞄准；Both = 任一
    private val modeValue = choices("HitMode", arrayOf("Click", "Dwell", "Both"), "Both") { aimBallValue.get() }

    // Dwell 模式需要持续瞄准的时长
    private val dwellTimeValue = float("DwellTime", 0.5f, 0.2f..1.5f, "s") { aimBallValue.get() }

    // 小球与玩家的距离范围
    private val distanceValue = floatRange("Distance", 4f..6f, 3f..10f) { aimBallValue.get() }

    // 超时换位（秒），0 = 关闭；防止玩家找不到球卡死一轮
    private val timeoutValue = int("Timeout", 30, 0..120, "s") { aimBallValue.get() }

    private val COLOR_IDLE_FILL = Color(255, 255, 255, 50)
    private val COLOR_IDLE_EDGE = Color(255, 255, 255, 190)
    private val COLOR_AIMED_FILL = Color(110, 255, 140, 70)
    private val COLOR_AIMED_EDGE = Color(110, 255, 140, 230)
    private val COLOR_ARROW = Color(255, 255, 255, 210)

    /** 小球绝对坐标；null = 未生成。 */
    private var ballPos: Vec3? = null

    private var aimed = false
    private var aimTicks = 0
    private var roundTicks = 0

    private val buffer = ArrayList<TickSample>()
    private var accYaw = 0f
    private var accPitch = 0f

    private var tickCounter = 0L
    private var lastAttackTick = Long.MIN_VALUE
    private var prevClearData = false
    private var prevClickDown = false
    private var prevAimBall = false

    // 本次会话诊断：被跳过的 tick 数与其中旋转被接管（静默转头模块）的数量
    private var skippedTicks = 0
    private var skippedByRotation = 0

    override fun onEnable() {
        synchronized(buffer) {
            buffer.clear()
        }
        accYaw = 0f
        accPitch = 0f
        tickCounter = 0L
        lastAttackTick = Long.MIN_VALUE
        skippedTicks = 0
        skippedByRotation = 0

        aimTicks = 0
        roundTicks = 0
        aimed = false
        prevAimBall = aimBallValue.get()
        if (aimBallValue.get()) {
            spawnBall()
        } else {
            ballPos = null
        }

        // 开启时汇报一次数据规模，方便确认录制进度
        SharedScopes.IO.launch {
            val (ticks, segments) = AimDatasetStore.stats()
            if (ticks > 0) {
                chat("§7[§bAI Aim§7] §fDataset: §b$ticks§f ticks, §b$segments§f segments.")
            }
        }
    }

    override fun onDisable() {
        flushTick()

        val snapshot = synchronized(buffer) { ArrayList(buffer) }
        buffer.clear()
        if (snapshot.isNotEmpty()) {
            AimDatasetStore.persist(snapshot)
        }

        ballPos = null
        aimed = false
        aimTicks = 0
        roundTicks = 0
    }

    /**
     * Entity.setAngles 的原始鼠标增量。原版对 pitch 做 `rotationPitch -= pitch * 0.15`，
     * 所以实际 pitch 增量取负。
     */
    @Suppress("unused")
    val onRotationSet = handler<RotationSetEvent> { event ->
        accYaw += event.yawDiff
        accPitch += -event.pitchDiff
    }

    @Suppress("unused")
    val onAttack = handler<AttackEvent> { _ ->
        lastAttackTick = tickCounter
    }

    @Suppress("unused")
    val onTick = handler<GameTickEvent> {
        tickCounter++

        // ClearData 是一次性动作：置 true 触发清空后自动复位。
        // 清空必须持 persistMutex 并在 IO 线程执行：否则与进行中的 persist
        // （读旧数据→合并→写回）竞态，旧数据会被完整写回导致「清了没用」。
        if (clearDataValue.get() && !prevClearData) {
            synchronized(buffer) { buffer.clear() }
            accYaw = 0f
            accPitch = 0f
            SharedScopes.IO.launch {
                val removed = AimDatasetStore.clearAll()
                withContext(Dispatchers.Main) {
                    chat("§7[§bAI Aim§7] §fCleared §b$removed§f data file(s).")
                }
            }
        }
        prevClearData = clearDataValue.get()
        if (clearDataValue.get()) clearDataValue.set(false)

        // AimBall 开关热切换：开启时生成小球，关闭时收起
        if (aimBallValue.get() != prevAimBall) {
            prevAimBall = aimBallValue.get()
            if (aimBallValue.get()) {
                spawnBall()
            } else {
                ballPos = null
                aimed = false
            }
        }

        if (aimBallValue.get()) {
            updateBall()
        }

        flushTick()
    }

    /**
     * 小球模式 tick 逻辑：进世界补生成、跑图/超时换位、命中判定与 Dwell 计时。
     */
    private fun updateBall() {
        val player = mc.thePlayer ?: return

        // 进世界前开启时 ballPos 为空，这里补生成
        if (ballPos == null) {
            spawnBall()
            return
        }

        roundTicks++

        // 玩家离锚点太远（跑图）→ 换位到身边
        if (player.getDistance(ballPos!!.xCoord, ballPos!!.yCoord, ballPos!!.zCoord) > 12.0) {
            spawnBall()
            return
        }

        // 超时换位
        val timeout = timeoutValue.get()
        if (timeout > 0 && roundTicks >= timeout * 20) {
            finishRound(hit = false)
            return
        }

        // 命中判定：准星方向与小球方向夹角
        val toBall = RotationUtils.toRotation(ballPos!!)
        val diffYaw = RotationUtils.angleDifference(toBall.yaw, player.rotationYaw)
        val diffPitch = toBall.pitch - player.rotationPitch
        aimed = hypot(diffYaw, diffPitch) <= toleranceValue.get()

        if (aimed) {
            aimTicks++

            val mode = modeValue.get()
            if (mode == "Dwell" || mode == "Both") {
                if (aimTicks >= (dwellTimeValue.get() * 20f).roundToInt()) {
                    finishRound(hit = true)
                }
            }
        } else {
            aimTicks = 0
        }
    }

    /**
     * 点击边沿检测放渲染帧（更贴合点击时机）。
     * 仅在命中圈内且无界面时视为有效点击。
     */
    @Suppress("unused")
    val onRender2D = handler<Render2DEvent> {
        if (!aimBallValue.get()) return@handler
        val ball = ballPos ?: return@handler
        val player = mc.thePlayer ?: return@handler

        // ---- 命中圈指示（箭头）----
        val toBall = RotationUtils.toRotation(ball)
        val diffYaw = RotationUtils.angleDifference(toBall.yaw, player.rotationYaw)
        val diffPitch = toBall.pitch - player.rotationPitch

        if (!aimed) {
            drawArrow(diffYaw, diffPitch)
        }

        // ---- Dwell 进度条（准星下方）----
        if (aimed && (modeValue.get() == "Dwell" || modeValue.get() == "Both")) {
            val need = (dwellTimeValue.get() * 20f).roundToInt().coerceAtLeast(1)
            val progress = (aimTicks.toFloat() / need).coerceIn(0f, 1f)

            val sr = net.minecraft.client.gui.ScaledResolution(mc)
            val cx = sr.scaledWidth / 2f
            val cy = sr.scaledHeight / 2f
            val barW = 30f
            val barH = 2f

            // 2D overlay 阶段可能残留 shader program / 深度状态，全部归位后再画
            GL20.glUseProgram(0)
            GlStateManager.pushMatrix()
            GlStateManager.disableTexture2D()
            GlStateManager.enableBlend()
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
            GlStateManager.disableDepth()
            glColor(Color(255, 255, 255, 70))
            GL11.glBegin(GL11.GL_QUADS)
            GL11.glVertex2f(cx - barW / 2, cy + 10f)
            GL11.glVertex2f(cx - barW / 2 + barW, cy + 10f)
            GL11.glVertex2f(cx - barW / 2 + barW, cy + 10f + barH)
            GL11.glVertex2f(cx - barW / 2, cy + 10f + barH)
            GL11.glEnd()
            glColor(COLOR_AIMED_EDGE)
            GL11.glBegin(GL11.GL_QUADS)
            GL11.glVertex2f(cx - barW / 2, cy + 10f)
            GL11.glVertex2f(cx - barW / 2 + barW * progress, cy + 10f)
            GL11.glVertex2f(cx - barW / 2 + barW * progress, cy + 10f + barH)
            GL11.glVertex2f(cx - barW / 2, cy + 10f + barH)
            GL11.glEnd()
            GlStateManager.enableDepth()
            GlStateManager.color(1f, 1f, 1f, 1f)
            GlStateManager.enableTexture2D()
            GlStateManager.disableBlend()
            GlStateManager.popMatrix()
        }

        // ---- 点击边沿检测（渲染帧）----
        if (mc.currentScreen == null && aimed && Mouse.isButtonDown(0) && !prevClickDown) {
            if (modeValue.get() == "Click" || modeValue.get() == "Both") {
                finishRound(hit = true)
            }
        }
        prevClickDown = Mouse.isButtonDown(0)
    }

    @Suppress("unused")
    val onRender3D = handler<Render3DEvent> {
        if (!aimBallValue.get()) return@handler
        val ball = ballPos ?: return@handler
        val renderManager = mc.renderManager
        val (x, y, z) = ball - renderManager.renderPos

        // 判定圈半径（世界单位）：视角下呈现为固定角度的圆
        val dist = hypot(x, hypot(y, z)).toFloat().coerceAtLeast(0.5f)
        val radius = (dist * Math.tan(Math.toRadians(toleranceValue.get().toDouble()))).toFloat()

        GL20.glUseProgram(0)
        GL11.glPushMatrix()
        GL11.glTranslated(x, y, z)
        GL11.glRotated(-renderManager.playerViewY.toDouble(), 0.0, 1.0, 0.0)
        GL11.glRotated(renderManager.playerViewX.toDouble(), 1.0, 0.0, 0.0)

        GL11.glDisable(GL11.GL_TEXTURE_2D)
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glDisable(GL11.GL_CULL_FACE)

        val fill = if (aimed) COLOR_AIMED_FILL else COLOR_IDLE_FILL
        val edge = if (aimed) COLOR_AIMED_EDGE else COLOR_IDLE_EDGE

        // 填充圆
        glColor(fill)
        GL11.glBegin(GL11.GL_TRIANGLE_FAN)
        GL11.glVertex2d(0.0, 0.0)
        for (i in 0..40) {
            val a = Math.PI * 2 * i / 40.0
            GL11.glVertex2d(cos(a) * radius, sin(a) * radius)
        }
        GL11.glEnd()

        // 中心点
        glColor(edge)
        GL11.glBegin(GL11.GL_QUADS)
        val dot = radius * 0.08
        GL11.glVertex2d(-dot, -dot)
        GL11.glVertex2d(dot, -dot)
        GL11.glVertex2d(dot, dot)
        GL11.glVertex2d(-dot, dot)
        GL11.glEnd()

        // 外圈
        GL11.glLineWidth(1.5f)
        GL11.glBegin(GL11.GL_LINE_LOOP)
        for (i in 0..40) {
            val a = Math.PI * 2 * i / 40.0
            GL11.glVertex2d(cos(a) * radius, sin(a) * radius)
        }
        GL11.glEnd()

        GlStateManager.color(1f, 1f, 1f, 1f)
        GL11.glEnable(GL11.GL_DEPTH_TEST)
        GL11.glEnable(GL11.GL_CULL_FACE)
        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glDisable(GL11.GL_BLEND)
        GL11.glPopMatrix()
    }

    /**
     * 准星旁的方向箭头：指向小球相对准星的方向（yaw 差 → 屏幕横向，pitch 差 → 屏幕纵向）。
     * MC 中 yaw 增大 = 目标在右，pitch 负 = 目标在上方，与屏幕坐标一致。
     * 画法与 PointerESP 一致：glPushAttrib(GL_ALL_ATTRIB_BITS) 整体保存/恢复 GL 属性，
     * 避免残留 shader/深度/混合状态吞掉固定管线图元。
     */
    private fun drawArrow(diffYaw: Float, diffPitch: Float) {
        val sr = net.minecraft.client.gui.ScaledResolution(mc)
        val len = hypot(diffYaw, diffPitch)
        if (len < 1e-3f) return

        val dirX = diffYaw / len
        val dirY = diffPitch / len

        // 把指向「上」(0,-1) 的箭头旋转到 (dirX, dirY)：θ = atan2(dirX, -dirY)
        val angle = Math.toDegrees(Math.atan2(dirX.toDouble(), -dirY.toDouble())).toFloat()

        val ringR = 18f
        val size = 8f

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS)
        GL11.glPushMatrix()
        GL11.glTranslatef(sr.scaledWidth / 2f, sr.scaledHeight / 2f, 0f)
        GL11.glRotatef(angle, 0f, 0f, 1f)

        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glDisable(GL11.GL_TEXTURE_2D)
        GL11.glEnable(GL11.GL_LINE_SMOOTH)
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST)

        glColor(COLOR_ARROW)
        GL11.glBegin(GL11.GL_TRIANGLES)
        GL11.glVertex2f(0f, -ringR - size)
        GL11.glVertex2f(-size * 0.45f, -ringR)
        GL11.glVertex2f(size * 0.45f, -ringR)
        GL11.glEnd()

        GL11.glColor4f(1f, 1f, 1f, 1f)
        GL11.glDisable(GL11.GL_LINE_SMOOTH)
        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glDisable(GL11.GL_BLEND)
        GL11.glPopMatrix()
        GL11.glPopAttrib()
    }

    /**
     * 在玩家眼睛位置周围随机方向生成小球。
     * 水平偏转混合分布：50% 小偏转 ±35°（短距离修正/微调数据），50% 大偏转 ±150°（甩头数据）
     * ——纯均匀 ±150° 采样会几乎全是长距离甩头，缺失短转头样本。
     * 俯仰在绝对角 -60°..+15°（偏上半球），距离按 Distance 设置随机。
     * 大范围随机 + 判定圈宽松 = 命中点天然散布，采集到的是「人找目标」而不是「人描点」。
     * 俯仰改用绝对角并向上半球偏置：原先相对玩家俯仰 -35°..+50° 采样时球大部分落在脚底下。
     */
    private fun spawnBall() {
        val player = mc.thePlayer ?: return

        val eye = Vec3(player.posX, player.posY + player.getEyeHeight(), player.posZ)
        val yawOffset = if ((0..1).random() == 0) (-35f..35f).random() else (-150f..150f).random()
        val yaw = player.rotationYaw + yawOffset
        val dist = distanceValue.random

        var pitch = (-60f..15f).random()
        var pos = ballPosition(eye, yaw, pitch, dist)

        // 兜底：确保球不落到脚底下（眼睛以下 1.2 格以内）
        var tries = 0
        while (pos.yCoord < player.posY + 0.4 && tries++ < 5) {
            pitch = (-60f..-5f).random()
            pos = ballPosition(eye, yaw, pitch, dist)
        }

        ballPos = pos

        aimTicks = 0
        roundTicks = 0
    }

    private fun ballPosition(eye: Vec3, yaw: Float, pitch: Float, dist: Float): Vec3 {
        val dir = RotationUtils.getVectorForRotation(yaw, pitch)
        return eye.addVector(dir.xCoord * dist, dir.yCoord * dist, dir.zCoord * dist)
    }

    private fun finishRound(hit: Boolean) {
        flushTick()

        val snapshot = synchronized(buffer) { ArrayList(buffer) }
        buffer.clear()

        if (hit) {
            val player = mc.thePlayer
            player?.playSound("random.orb", 0.7f, 1.4f)
        }

        // 即使超时未命中，期间的甩头/搜索数据依然有效，一并入库
        if (snapshot.isNotEmpty()) {
            AimDatasetStore.persist(snapshot, report = false)
        }

        spawnBall()
    }

    private fun inCombatWindow(): Boolean =
        lastAttackTick != Long.MIN_VALUE && tickCounter - lastAttackTick <= 60

    private fun flushTick() {
        val gcd = RotationUtils.getFixedAngleDelta()
        val player = mc.thePlayer

        val invalidate = player == null || RotationUtils.currentRotation != null || NoRotateSet.shouldModify(player) || (FreeLook.handleEvents() && mc.gameSettings.thirdPersonView != 0)
            || (onlyCombatValue.get() && !inCombatWindow())

        if (invalidate) {
            // 丢弃本 tick 累计并写入零样本以终止当前段
            skippedTicks++
            if (RotationUtils.currentRotation != null) skippedByRotation++
            accYaw = 0f
            accPitch = 0f
            synchronized(buffer) {
                buffer.add(TickSample(gcd = gcd))
            }
            return
        }

        // AimBall 模式下 entityDist 记录到小球，其余时候记录最近生物
        val ball = ballPos
        val entityDist = if (aimBallValue.get()) {
            ball?.let { player.getDistance(it.xCoord, it.yCoord, it.zCoord).toFloat() } ?: 0f
        } else {
            nearestEntityDistance(player)
        }

        synchronized(buffer) {
            buffer.add(
                TickSample(
                    yawDelta = accYaw,
                    pitchDelta = accPitch,
                    moveSpeed = hypot(player.motionX, player.motionZ).toFloat(),
                    entityDist = entityDist,
                    gcd = gcd,
                )
            )
        }
        accYaw = 0f
        accPitch = 0f
    }

    // 只统计生物实体：掉落物/箭/经验球会把 entityDist 拉成噪声，污染训练特征
    private fun nearestEntityDistance(player: EntityLivingBase): Float =
        mc.theWorld?.loadedEntityList?.asSequence()
            ?.filterIsInstance<EntityLivingBase>()
            ?.filter { it !== player }
            ?.minOfOrNull { player.getDistanceToEntity(it) }
            ?: 0f
}
