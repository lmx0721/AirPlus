// skid SetsunaClient
package net.airplus.features.module.modules.render

import net.airplus.event.AttackEvent
import net.airplus.event.Render2DEvent
import net.airplus.event.UpdateEvent
import net.airplus.event.WorldEvent
import net.airplus.event.async.TickScheduler
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.ui.font.Fonts
import net.airplus.ui.font.GameFontRenderer
import net.airplus.utils.client.asResourceLocation
import net.airplus.utils.render.RenderUtils
import net.minecraft.client.audio.ISound
import net.minecraft.client.audio.PositionedSoundRecord
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.GL11
import java.awt.Color
import java.util.Locale
import java.util.UUID
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Shows a short animated confirmation after a recently attacked target dies.
 * Kill ownership is inferred client-side (same as the Setsuna original).
 *
 * 迁移说明：
 * - Skija 渲染全部换成 1.8.9 的 GL11 即时模式 + RenderUtils 圆角矩形。
 * - Sound Volume 通过继承 [PositionedSoundRecord] 覆写 attenuation 实现 UI 音量。
 * - TOTEM / CHALLENGE / CRITICAL 原版音效是 1.9+ 才有的，1.8.9 不存在，
 *   用最接近的原版音效替代（见 [playSelectedSound] 内注释）。
 */
object KillEffect : Module("KillEffect", Category.RENDER) {

    private const val MAX_TRACKED_TARGETS = 32
    private const val ENTER_DURATION_NANOS = 220_000_000L
    private const val EXIT_DURATION_NANOS = 240_000_000L

    // Settings

    private val styleValue by choices("Style", arrayOf("Combo", "Badge", "Minimal"), "Combo")
    private val targetsValue by choices("Targets", arrayOf("Players", "All"), "Players")
    private val accentValue by color("Accent", Color(87, 226, 255))
    private val scaleValue by float("Scale", 1.0f, 0.5f..2.0f)
    private val yOffsetValue by int("Y Offset", 34, -160..160)
    private val durationValue by float("Duration", 1.8f, 0.6f..4.0f)
    private val confirmWindowValue by float("Confirm Window", 6.0f, 1.0f..12.0f)
    private val comboWindowValue by float("Combo Window", 4.0f, 1.0f..10.0f)
    private val soundValue by choices(
        "Sound",
        arrayOf("None", "Critical", "Level Up", "Totem", "Challenge", "Pling", "Anvil", "Thunder"),
        "Level Up"
    )
    private val soundVolumeValue by float("Sound Volume", 0.75f, 0.1f..1.0f) { soundValue != "None" }
    private val targetNameValue by boolean("Target Name", true)
    private val distanceValue by boolean("Distance", false)
    private val glowValue by boolean("Glow", true)
    private val glowStrengthValue by int("Glow Strength", 5, 1..10) { glowValue }

    // Setsuna 的 ButtonSetting("Preview") 在 AirPlus 没有；用 BoolValue + onChanged 触发，
    // 并在下一 tick 自动回弹为 false（模拟按钮点击）。
    private val previewValue = boolean("Preview", false)

    init {
        previewValue.onChanged {
            if (it) {
                showPreview()
            }
        }
    }

    // State

    private val trackedTargets = LinkedHashMap<UUID, TrackedTarget>()

    private var effectStartedNanos = 0L
    private var lastKillNanos = 0L
    private var victimName = ""
    private var victimDistance = 0.0f
    private var comboCount = 0
    private var previewCombo = 0
    private var showing = false

    /** 当前渲染的缩放（用于让 GL 线宽跟随 Skija 式的画布缩放） */
    private var currentRenderScale = 1.0f

    // Events

    val onUpdate = handler<UpdateEvent> {
        if (mc.thePlayer == null || mc.theWorld == null) {
            trackedTargets.clear()
            return@handler
        }

        val now = System.nanoTime()
        val confirmationNanos = secondsToNanos(confirmWindowValue.toDouble())
        val iterator = trackedTargets.values.iterator()
        while (iterator.hasNext()) {
            val tracked = iterator.next()
            val target = tracked.entity
            if (target.worldObj !== mc.theWorld || now - tracked.attackedAtNanos > confirmationNanos) {
                iterator.remove()
                continue
            }
            if (target.isDead || target.health <= 0.0f) {
                iterator.remove()
                confirmKill(tracked, now)
            }
        }
    }

    val onWorld = handler<WorldEvent> {
        clearState()
    }

    val onAttack = handler<AttackEvent> { event ->
        val player = mc.thePlayer
        val target = event.targetEntity
        if (player == null || mc.theWorld == null || target !is EntityLivingBase ||
            target === player || !target.isEntityAlive
        ) {
            return@handler
        }
        if (targetsValue == "Players" && target !is EntityPlayer) {
            return@handler
        }

        val now = System.nanoTime()
        val uuid = target.uniqueID
        trackedTargets.remove(uuid)
        trackedTargets[uuid] = TrackedTarget(
            target,
            now,
            displayName(target),
            player.getDistanceToEntity(target)
        )
        trimTrackedTargets()
    }

    val onRender2D = handler<Render2DEvent> {
        if (!showing) {
            return@handler
        }

        val now = System.nanoTime()
        val displayNanos = secondsToNanos(durationValue.toDouble())
        val elapsedNanos = now - effectStartedNanos
        if (elapsedNanos < 0L || elapsedNanos >= displayNanos) {
            showing = false
            return@handler
        }

        val elapsed = elapsedNanos / displayNanos.toFloat()
        val enter = easeOutCubic(clamp(elapsedNanos / ENTER_DURATION_NANOS.toFloat()))
        val remainingExit = clamp((displayNanos - elapsedNanos) / EXIT_DURATION_NANOS.toFloat())
        val alpha = enter * smoothStep(remainingExit)
        val popScale = lerp(1.58f, 1.0f, enter) * lerp(0.94f, 1.0f, remainingExit)
        val renderScale = scaleValue * popScale
        val scaledResolution = ScaledResolution(mc)
        val centerX = scaledResolution.scaledWidth * 0.5f
        val centerY = scaledResolution.scaledHeight * 0.5f + yOffsetValue + (1.0f - enter) * 9.0f
        val color = withAlpha(accentValue.rgb, alpha)

        currentRenderScale = renderScale
        GL11.glPushMatrix()
        GL11.glTranslatef(centerX, centerY, 0.0f)
        GL11.glScalef(renderScale, renderScale, 1.0f)
        try {
            // Setsuna 的 glowLayer 模糊光晕在 1.8.9 用分层渐变近似
            if (glowValue && alpha > 0.02f) {
                drawGlowBehind(color, alpha)
            }
            drawStyle(color, alpha, 1.0f - elapsed, comboCount, victimName, victimDistance)
        } finally {
            GL11.glPopMatrix()
        }
    }

    override fun onEnable() {
        clearState()
    }

    override fun onDisable() {
        clearState()
    }

    /** Arraylist tag 等价于 Setsuna 的 getInfo() */
    override val tag: String
        get() = styleValue

    // Kill confirm

    private fun confirmKill(tracked: TrackedTarget, now: Long) {
        val comboNanos = secondsToNanos(comboWindowValue.toDouble())
        comboCount = if (lastKillNanos != 0L && now - lastKillNanos <= comboNanos) comboCount + 1 else 1
        lastKillNanos = now
        showEffect(tracked.name, tracked.distance, now)
    }

    private fun showPreview() {
        previewCombo = previewCombo % 6 + 1
        comboCount = previewCombo
        showEffect("Preview Target", 12.4f, System.nanoTime())
        // 按钮式 Preview：下一 tick 自动回弹到 false
        TickScheduler.schedule {
            previewValue.set(false, false)
            true
        }
    }

    private fun showEffect(name: String, targetDistance: Float, now: Long) {
        victimName = ellipsize(name, 24)
        victimDistance = targetDistance
        effectStartedNanos = now
        showing = true
        playSelectedSound()
    }

    private fun playSelectedSound() {
        // 音效映射（1.8.9 原版音效注册名）：
        // - Critical：1.8.9 没有攻击/暴击音效（1.9 才加入），用"命中确认"音 random.successful_hit 近似
        // - Level Up：random.levelup（原版对应）
        // - Totem：图腾音效 1.11+ 才存在，用经验球音 random.orb 近似
        // - Challenge：UI 挑战完成音效 1.9+ 才存在，用 random.levelup 高音调近似
        // - Pling / Anvil / Thunder：note.pling / random.anvil_land / ambient.weather.thunder（原版对应）
        val (location, pitch) = when (soundValue) {
            "Critical" -> "random.successful_hit" to 1.0f
            "Level Up" -> "random.levelup" to 1.0f
            "Totem" -> "random.orb" to 1.0f
            "Challenge" -> "random.levelup" to 1.19f
            "Pling" -> "note.pling" to 1.0f
            "Anvil" -> "random.anvil_land" to 1.0f
            "Thunder" -> "ambient.weather.thunder" to 1.0f
            else -> return // "None"
        }
        synchronized(mc.soundHandler) {
            mc.soundHandler.playSound(UiSound(location.asResourceLocation(), soundVolumeValue, pitch))
        }
    }

    // Styles

    private fun drawStyle(
        color: Int, alpha: Float, progress: Float,
        displayedCombo: Int, displayedName: String, displayedDistance: Float
    ) {
        when (styleValue) {
            "Combo" -> drawCombo(color, alpha, progress, displayedCombo, displayedName, displayedDistance)
            "Badge" -> drawBadge(color, alpha, progress, displayedCombo, displayedName, displayedDistance)
            "Minimal" -> drawMinimal(color, alpha, progress, displayedCombo, displayedName, displayedDistance)
        }
    }

    private fun drawCombo(
        color: Int, alpha: Float, progress: Float,
        displayedCombo: Int, displayedName: String, displayedDistance: Float
    ) {
        val dim = withAlpha(0xFFFFFFFF.toInt(), alpha * 0.18f)
        val white = withAlpha(0xFFFFFFFF.toInt(), alpha)
        strokeCircle(0.0f, 0.0f, 22.0f, 1.1f, dim)
        strokeArc(0.0f, 0.0f, 22.0f, -90.0f, 360.0f * clamp(progress), 2.0f, color)

        drawDiamond(0.0f, 0.0f, 15.5f, 1.1f, withAlpha(color, alpha * 0.72f))
        drawSkull(0.0f, -0.5f, color, alpha)

        for (i in 0 until 5) {
            val x = (i - 2) * 6.0f
            val pipColor = if (i < min(displayedCombo, 5)) color else dim
            fillCircle(x, 27.0f, 1.35f, pipColor)
        }
        if (displayedCombo > 5) {
            drawCenteredText("+" + (displayedCombo - 5), 31.0f, Fonts.fontRegular30, color)
        }

        drawCenteredText(comboLabel(displayedCombo), 34.0f, Fonts.fontSemibold35, white)
        drawTargetLine(45.0f, alpha, displayedName, displayedDistance)
    }

    private fun drawBadge(
        color: Int, alpha: Float, progress: Float,
        displayedCombo: Int, displayedName: String, displayedDistance: Float
    ) {
        val dim = withAlpha(0xFFFFFFFF.toInt(), alpha * 0.20f)
        val white = withAlpha(0xFFFFFFFF.toInt(), alpha)
        val shield = floatArrayOf(
            0.0f, -22.0f, 19.0f, -10.0f,
            14.0f, 14.0f, 0.0f, 24.0f,
            -14.0f, 14.0f, -19.0f, -10.0f
        )
        drawPolygonOutline(shield, 1.2f, dim)
        drawSkull(0.0f, -1.0f, color, alpha)

        val sweep = 22.0f * clamp(progress)
        for (side in intArrayOf(-1, 1)) {
            for (i in 0 until min(displayedCombo, 4)) {
                val inset = i * 5.0f
                strokePolyline(
                    floatArrayOf(
                        side * (27.0f + inset), -sweep * 0.45f,
                        side * (31.0f + inset), 0.0f,
                        side * (27.0f + inset), sweep * 0.45f
                    ),
                    1.5f, color
                )
            }
        }

        drawCenteredText(comboLabel(displayedCombo), 30.0f, Fonts.fontSemibold35, white)
        drawTargetLine(41.0f, alpha, displayedName, displayedDistance)
    }

    private fun drawMinimal(
        color: Int, alpha: Float, progress: Float,
        displayedCombo: Int, displayedName: String, displayedDistance: Float
    ) {
        val title = comboLabel(displayedCombo)
        val detail = targetLine(displayedName, displayedDistance)
        val titleFont = Fonts.fontSemibold35
        val detailFont = Fonts.fontRegular30
        val titleWidth = titleFont.getStringWidth(title).toFloat()
        val detailWidth = if (detail.isEmpty()) 0.0f else detailFont.getStringWidth(detail).toFloat()
        val width = maxOf(92.0f, minOf(200.0f, maxOf(titleWidth, detailWidth) + 52.0f))
        val height = if (detail.isEmpty()) 28.0f else 37.0f
        val left = -width * 0.5f
        val top = -height * 0.5f

        RenderUtils.drawRoundedRect(left, top, left + width, top + height, withAlpha(0xE60A0E14.toInt(), alpha), 5.0f)
        RenderUtils.drawRoundedRect(left, top, left + 3.0f, top + height * clamp(progress), color, 1.5f)
        drawSkull(left + 19.0f, 0.0f, color, alpha)
        titleFont.drawString(title, left + 37.0f, top + 5.0f, withAlpha(0xFFFFFFFF.toInt(), alpha))
        if (detail.isNotEmpty()) {
            detailFont.drawString(detail, left + 37.0f, top + 18.0f, withAlpha(0xFFB9C1CC.toInt(), alpha))
        }
    }

    private fun drawSkull(centerX: Float, centerY: Float, color: Int, alpha: Float) {
        val cutout = withAlpha(0xFF10151C.toInt(), alpha)
        fillCircle(centerX, centerY - 2.5f, 7.7f, color)
        RenderUtils.drawRoundedRect(
            centerX - 5.4f, centerY + 2.0f, centerX + 5.4f, centerY + 8.0f, color, 2.0f
        )
        RenderUtils.drawRoundedRect(
            centerX - 4.8f, centerY - 4.5f, centerX - 1.8f, centerY - 2.1f, cutout, 0.8f
        )
        RenderUtils.drawRoundedRect(
            centerX + 1.8f, centerY - 4.5f, centerX + 4.8f, centerY - 2.1f, cutout, 0.8f
        )
        for (i in -1..1) {
            fillRect(centerX + i * 3.0f - 0.55f, centerY + 4.0f, 1.1f, 4.0f, cutout)
        }
    }

    private fun drawTargetLine(
        top: Float, alpha: Float,
        displayedName: String, displayedDistance: Float
    ) {
        val line = targetLine(displayedName, displayedDistance)
        if (line.isNotEmpty()) {
            Fonts.fontRegular30.drawCenteredString(line, 0.0f, top, withAlpha(0xFFD7DEE8.toInt(), alpha))
        }
    }

    private fun targetLine(displayedName: String, displayedDistance: Float): String {
        if (!targetNameValue && !distanceValue) {
            return ""
        }
        val distanceText = String.format(Locale.ROOT, "%.1fm", displayedDistance)
        if (!targetNameValue) {
            return distanceText
        }
        return if (distanceValue) displayedName + "  " + distanceText else displayedName
    }

    private fun comboLabel(displayedCombo: Int): String = when (displayedCombo) {
        1 -> "ELIMINATED"
        2 -> "DOUBLE KILL"
        3 -> "TRIPLE KILL"
        4 -> "QUAD KILL"
        5 -> "PENTA KILL"
        else -> displayedCombo.toString() + " KILL CHAIN"
    }

    // Glow（Skija glowLayer 的近似实现：分层柔和圆形叠加）

    private fun drawGlowBehind(color: Int, alpha: Float) {
        val strength = glowStrengthValue.coerceIn(1, 10)
        val layers = 4
        for (i in layers downTo 1) {
            val radius = 26.0f + i * 8.0f
            val layerAlpha = alpha * (0.05f + 0.10f * (strength / 10.0f)) * (1.0f - (i - 1) / layers.toFloat())
            fillCircle(0.0f, -2.0f, radius, withAlpha(color, layerAlpha))
        }
    }

    // GL primitives（对应 Setsuna 的 Skija 绘制）

    /** 统一的 GL 状态包裹：push/pop attrib 保证不污染原版渲染状态缓存 */
    private inline fun glShape(block: () -> Unit) {
        GL11.glPushAttrib(
            GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT or GL11.GL_LINE_BIT or GL11.GL_CURRENT_BIT
        )
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glDisable(GL11.GL_TEXTURE_2D)
        GL11.glEnable(GL11.GL_LINE_SMOOTH)
        block()
        GL11.glDisable(GL11.GL_LINE_SMOOTH)
        GL11.glPopAttrib()
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f)
    }

    private fun glColor(color: Int) {
        GL11.glColor4f(
            (color shr 16 and 0xFF) / 255.0f,
            (color shr 8 and 0xFF) / 255.0f,
            (color and 0xFF) / 255.0f,
            (color ushr 24 and 0xFF) / 255.0f
        )
    }

    private fun fillCircle(x: Float, y: Float, radius: Float, color: Int) {
        glShape {
            glColor(color)
            GL11.glBegin(GL11.GL_TRIANGLE_FAN)
            GL11.glVertex2f(x, y)
            for (i in 0..50) {
                val angle = i * 2.0 * Math.PI / 50.0
                GL11.glVertex2f(x + cos(angle).toFloat() * radius, y + sin(angle).toFloat() * radius)
            }
            GL11.glEnd()
        }
    }

    private fun strokeCircle(x: Float, y: Float, radius: Float, thickness: Float, color: Int) {
        glShape {
            glColor(color)
            GL11.glLineWidth(thickness * currentRenderScale)
            GL11.glBegin(GL11.GL_LINE_LOOP)
            for (i in 0 until 48) {
                val angle = i * 2.0 * Math.PI / 48.0
                GL11.glVertex2f(x + cos(angle).toFloat() * radius, y + sin(angle).toFloat() * radius)
            }
            GL11.glEnd()
        }
    }

    private fun strokeArc(
        x: Float, y: Float, radius: Float,
        start: Float, sweep: Float, thickness: Float, color: Int
    ) {
        if (sweep <= 0.0f) {
            return
        }
        glShape {
            glColor(color)
            GL11.glLineWidth(thickness * currentRenderScale)
            GL11.glBegin(GL11.GL_LINE_STRIP)
            val steps = maxOf(8, (Math.abs(sweep) / 3.0f).toInt())
            for (i in 0..steps) {
                val angle = Math.toRadians((start + sweep * i / steps).toDouble())
                GL11.glVertex2f(x + cos(angle).toFloat() * radius, y + sin(angle).toFloat() * radius)
            }
            GL11.glEnd()
        }
    }

    private fun fillRect(x: Float, y: Float, width: Float, height: Float, color: Int) {
        glShape {
            glColor(color)
            GL11.glBegin(GL11.GL_QUADS)
            GL11.glVertex2f(x, y)
            GL11.glVertex2f(x, y + height)
            GL11.glVertex2f(x + width, y + height)
            GL11.glVertex2f(x + width, y)
            GL11.glEnd()
        }
    }

    private fun drawDiamond(x: Float, y: Float, radius: Float, thickness: Float, color: Int) {
        drawPolygonOutline(
            floatArrayOf(
                x, y - radius,
                x + radius, y,
                x, y + radius,
                x - radius, y
            ),
            thickness, color
        )
    }

    private fun drawPolygonOutline(points: FloatArray, thickness: Float, color: Int) {
        glShape {
            glColor(color)
            GL11.glLineWidth(thickness * currentRenderScale)
            GL11.glBegin(GL11.GL_LINE_LOOP)
            for (i in points.indices step 2) {
                GL11.glVertex2f(points[i], points[i + 1])
            }
            GL11.glEnd()
        }
    }

    private fun strokePolyline(points: FloatArray, thickness: Float, color: Int) {
        glShape {
            glColor(color)
            GL11.glLineWidth(thickness * currentRenderScale)
            GL11.glBegin(GL11.GL_LINE_STRIP)
            for (i in points.indices step 2) {
                GL11.glVertex2f(points[i], points[i + 1])
            }
            GL11.glEnd()
        }
    }

    // Text

    private fun drawCenteredText(text: String, top: Float, font: GameFontRenderer, color: Int) {
        font.drawCenteredString(text, 0.0f, top, color)
    }

    // Tracking helpers

    private fun trimTrackedTargets() {
        while (trackedTargets.size > MAX_TRACKED_TARGETS) {
            val first = trackedTargets.keys.firstOrNull() ?: return
            trackedTargets.remove(first)
        }
    }

    private fun clearState() {
        trackedTargets.clear()
        showing = false
        effectStartedNanos = 0L
        lastKillNanos = 0L
        victimName = ""
        victimDistance = 0.0f
        comboCount = 0
        previewCombo = 0
    }

    private fun displayName(target: EntityLivingBase): String {
        val name = target.displayName.formattedText
        return if (name.isBlank()) target.name else name
    }

    private fun ellipsize(value: String?, maxCodePoints: Int): String {
        val safe = value?.trim() ?: ""
        val count = safe.codePointCount(0, safe.length)
        if (count <= maxCodePoints) {
            return safe
        }
        val end = safe.offsetByCodePoints(0, maxOf(1, maxCodePoints - 3))
        return safe.substring(0, end) + "..."
    }

    // Math

    private fun secondsToNanos(seconds: Double): Long = (seconds * 1_000_000_000.0).toLong()

    private fun easeOutCubic(value: Float): Float {
        val remaining = 1.0f - clamp(value)
        return 1.0f - remaining * remaining * remaining
    }

    private fun smoothStep(value: Float): Float {
        val clamped = clamp(value)
        return clamped * clamped * (3.0f - 2.0f * clamped)
    }

    private fun lerp(from: Float, to: Float, progress: Float): Float = from + (to - from) * progress

    private fun clamp(value: Float): Float = value.coerceIn(0.0f, 1.0f)

    private fun withAlpha(color: Int, alpha: Float): Int {
        val baseAlpha = color ushr 24
        val adjusted = (baseAlpha * clamp(alpha)).roundToInt().coerceIn(0, 255)
        return adjusted shl 24 or (color and 0x00FFFFFF)
    }

    /** UI 音效实例：原版 create() 固定 0.25 音量，这里用可自定义音量 + NONE 衰减的构造 */
    private class UiSound(
        location: ResourceLocation,
        volume: Float,
        pitch: Float,
    ) : PositionedSoundRecord(location, volume, pitch, 0.0f, 0.0f, 0.0f) {
        override fun getAttenuationType(): ISound.AttenuationType = ISound.AttenuationType.NONE
    }

    private data class TrackedTarget(
        val entity: EntityLivingBase,
        val attackedAtNanos: Long,
        val name: String,
        val distance: Float,
    )
}
