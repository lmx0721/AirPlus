/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Ported from SilenceFix Fly（GrimGhostBlock）。
 *
 * 与原版差异：
 * - 原版依赖 SilenceFix BlinkUtils 的 12 参数 blink 状态机，
 *   这里用本地发包缓冲实现同等语义（缓冲所有发包、脉冲释放）。
 * - 脉冲释放时会保留最后一个 C08 放方块包（及其之后的包）到下一次脉冲：
 *   若全部释放，最后一个放方块包被 Grim 拒绝时其后的移动包全部悬空触发回弹，
 *   脚下刚放置的幽灵方块会被服务端校正抹掉，导致坠机。
 */
package net.airplus.features.module.modules.movement.flymodes.other

import net.airplus.event.EventState
import net.airplus.event.PacketEvent
import net.airplus.event.Render3DEvent
import net.airplus.features.module.modules.movement.flymodes.FlyMode
import net.airplus.utils.client.PacketUtils.sendPacket
import net.minecraft.network.Packet
import net.minecraft.network.play.client.C00PacketKeepAlive
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement
import net.minecraft.network.play.client.C16PacketClientStatus
import org.lwjgl.opengl.GL11
import java.awt.Color
import java.util.LinkedList

object GrimGhostBlock : FlyMode("GrimGhostBlock") {

    private const val PULSE_INTERVAL = 2900L

    // 保留最后一个放方块包时，其后最多允许滞留的包数（约半秒），防止玩家停止放方块时缓冲无限积压
    private const val KEEP_PLACE_MAX_TRAILING = 10

    // 发包缓冲
    private val packetBuffer = LinkedList<Packet<*>>()

    private val positions = LinkedList<DoubleArray>()
    private var pulseTimer = 0L

    val bufferSize
        get() = synchronized(packetBuffer) { packetBuffer.size }

    override fun onEnable() {
        val player = mc.thePlayer ?: return
        packetBuffer.clear()
        synchronized(positions) {
            positions.add(doubleArrayOf(player.posX, player.entityBoundingBox.minY + player.getEyeHeight() / 2.0, player.posZ))
            positions.add(doubleArrayOf(player.posX, player.entityBoundingBox.minY, player.posZ))
        }
        pulseTimer = System.currentTimeMillis()
    }

    override fun onDisable() {
        synchronized(positions) { positions.clear() }
        releaseBuffer(keepLastPlacement = false)
    }

    override fun onPacket(event: PacketEvent) {
        if (event.eventType != EventState.SEND) return

        // 缓冲所有发包（脉冲释放）
        event.cancelEvent()
        synchronized(packetBuffer) { packetBuffer.add(event.packet) }
    }

    override fun onUpdate() {
        val player = mc.thePlayer ?: return

        synchronized(positions) {
            positions.add(doubleArrayOf(player.posX, player.entityBoundingBox.minY, player.posZ))
        }
        if (System.currentTimeMillis() - pulseTimer >= PULSE_INTERVAL) {
            synchronized(positions) { positions.clear() }
            releaseBuffer(keepLastPlacement = true)
            pulseTimer = System.currentTimeMillis()
        }
    }

    override fun onRender3D(event: Render3DEvent) {
        synchronized(positions) {
            GL11.glPushMatrix()
            GL11.glDisable(GL11.GL_TEXTURE_2D)
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
            GL11.glEnable(GL11.GL_LINE_SMOOTH)
            GL11.glEnable(GL11.GL_BLEND)
            GL11.glDisable(GL11.GL_DEPTH_TEST)
            mc.entityRenderer.disableLightmap()
            GL11.glLineWidth(2.0f)
            GL11.glBegin(GL11.GL_LINE_STRIP)
            val color = Color(68, 131, 123, 255)
            GL11.glColor4f(color.red / 255f, color.green / 255f, color.blue / 255f, color.alpha / 255f)
            val renderPosX = mc.renderManager.viewerPosX
            val renderPosY = mc.renderManager.viewerPosY
            val renderPosZ = mc.renderManager.viewerPosZ
            for (pos in positions) {
                GL11.glVertex3d(pos[0] - renderPosX, pos[1] - renderPosY, pos[2] - renderPosZ)
            }
            GL11.glColor4d(1.0, 1.0, 1.0, 1.0)
            GL11.glEnd()
            GL11.glEnable(GL11.GL_DEPTH_TEST)
            GL11.glDisable(GL11.GL_LINE_SMOOTH)
            GL11.glDisable(GL11.GL_BLEND)
            GL11.glEnable(GL11.GL_TEXTURE_2D)
            GL11.glPopMatrix()
        }
    }

    private fun releaseBuffer(keepLastPlacement: Boolean) {
        val toRelease: List<Packet<*>>
        synchronized(packetBuffer) {
            var keepFromIndex = -1
            if (keepLastPlacement && packetBuffer.isNotEmpty()) {
                val lastPlaceIndex = packetBuffer.indexOfLast { it is C08PacketPlayerBlockPlacement }
                // 最后一个放方块包位于缓冲末段（其后仅有少量包）时才保留，
                // 保证服务端始终滞后一个待验证的方块，而不是整段缓冲无法释放
                if (lastPlaceIndex >= 0 && packetBuffer.size - 1 - lastPlaceIndex <= KEEP_PLACE_MAX_TRAILING) {
                    keepFromIndex = lastPlaceIndex
                }
            }
            if (keepFromIndex >= 0) {
                toRelease = ArrayList(packetBuffer.subList(0, keepFromIndex))
                val kept = ArrayList(packetBuffer.subList(keepFromIndex, packetBuffer.size))
                packetBuffer.clear()
                packetBuffer.addAll(kept)
            } else {
                toRelease = ArrayList(packetBuffer)
                packetBuffer.clear()
            }
        }
        // 与原版 blink 语义一致：C16 / C00 不释放
        toRelease.filterNot { it is C16PacketClientStatus || it is C00PacketKeepAlive }
            .forEach { sendPacket(it, false) }
    }
}
