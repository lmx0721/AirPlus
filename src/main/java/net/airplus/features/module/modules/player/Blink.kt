/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.player

import net.airplus.event.*
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.modules.render.Breadcrumbs
import net.airplus.utils.client.BlinkUtils
import net.airplus.utils.render.RenderUtils.glColor
import net.airplus.utils.timing.MSTimer
import org.lwjgl.opengl.GL11.*

object Blink : Module("Blink", Category.PLAYER, gameDetecting = false) {

    private val mode by choices("Mode", arrayOf("Sent", "Received", "Both"), "Sent")

    private val pulse by boolean("Pulse", false)
    private val pulseDelay by int("PulseDelay", 1000, 500..5000) { pulse }

    private val fakePlayerMenu by boolean("FakePlayer", true)

    private val pulseTimer = MSTimer()

    // Blink 起始 / 上次 Pulse 释放时间戳（供 Island HUD 显示当前 blink 时间与 Pulse 进度）
    private var enabledAt = 0L
    private var pulseAt = 0L

    override fun onEnable() {
        pulseTimer.reset()
        enabledAt = System.currentTimeMillis()
        pulseAt = enabledAt

        if (fakePlayerMenu)
            BlinkUtils.addFakePlayer()
    }

    override fun onDisable() {
        if (mc.thePlayer == null)
            return

        BlinkUtils.unblink()
    }

    val onPacket = handler<PacketEvent> { event ->
        val packet = event.packet

        if (mc.thePlayer == null || mc.thePlayer.isDead)
            return@handler

        when (mode.lowercase()) {
            "sent" -> {
                BlinkUtils.blink(packet, event, sent = true, receive = false)
            }

            "received" -> {
                BlinkUtils.blink(packet, event, sent = false, receive = true)
            }

            "both" -> {
                BlinkUtils.blink(packet, event)
            }
        }
    }

    val onMotion = handler<MotionEvent> { event ->
        if (event.eventState == EventState.POST) {
            val thePlayer = mc.thePlayer ?: return@handler

            if (thePlayer.isDead || mc.thePlayer.ticksExisted <= 10) {
                BlinkUtils.unblink()
            }

            when (mode.lowercase()) {
                "sent" -> {
                    BlinkUtils.syncSent()
                }

                "received" -> {
                    BlinkUtils.syncReceived()
                }
            }

            if (pulse && pulseTimer.hasTimePassed(pulseDelay)) {
                BlinkUtils.unblink()
                if (fakePlayerMenu) {
                    BlinkUtils.addFakePlayer()
                }
                pulseTimer.reset()
                // Pulse 释放后 blink 重新开始计时
                pulseAt = System.currentTimeMillis()
                enabledAt = pulseAt
            }
        }
    }

    val onRender3D = handler<Render3DEvent> {
        val color = Breadcrumbs.colors.color()

        synchronized(BlinkUtils.positions) {
            glPushMatrix()
            glDisable(GL_TEXTURE_2D)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
            glEnable(GL_LINE_SMOOTH)
            glEnable(GL_BLEND)
            glDisable(GL_DEPTH_TEST)
            mc.entityRenderer.disableLightmap()
            glBegin(GL_LINE_STRIP)
            glColor(color)

            val renderPosX = mc.renderManager.viewerPosX
            val renderPosY = mc.renderManager.viewerPosY
            val renderPosZ = mc.renderManager.viewerPosZ

            for (pos in BlinkUtils.positions)
                glVertex3d(pos.xCoord - renderPosX, pos.yCoord - renderPosY, pos.zCoord - renderPosZ)

            glColor4d(1.0, 1.0, 1.0, 1.0)
            glEnd()
            glEnable(GL_DEPTH_TEST)
            glDisable(GL_LINE_SMOOTH)
            glDisable(GL_BLEND)
            glEnable(GL_TEXTURE_2D)
            glPopMatrix()
        }
    }

    override val tag
        get() = (BlinkUtils.packets.size + BlinkUtils.packetsReceived.size).toString()

    fun blinkingSend() = handleEvents() && (mode == "Sent" || mode == "Both")
    fun blinkingReceive() = handleEvents() && (mode == "Received" || mode == "Both")

    /** 当前 blink 已持续时间（ms），未启用时返回 0 */
    fun blinkTime(): Long = if (handleEvents()) System.currentTimeMillis() - enabledAt else 0L

    /** Pulse 是否启用 */
    fun pulseEnabled(): Boolean = handleEvents() && pulse

    /** 当前 Pulse 周期进度（0..1），未启用 Pulse 时返回 0 */
    fun pulseProgress(): Float =
        if (pulseEnabled()) ((System.currentTimeMillis() - pulseAt).toFloat() / pulseDelay).coerceIn(0f, 1f) else 0f
}
