/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 * skid edmoonlight
 */
package net.airplus.features.module.modules.player.gapple

import net.airplus.event.EventState
import net.airplus.event.Listenable
import net.airplus.event.PacketEvent
import net.airplus.event.WorldEvent
import net.airplus.event.handler
import net.airplus.utils.client.MinecraftInstance
import net.minecraft.network.play.client.C03PacketPlayer
import net.minecraft.util.Vec3

/**
 * moonlight GrimPlaceComponent 中 AutoGapple（Gapple EdMoonlight 模式）实际用到的子集：
 * wire-level 记录最后一条「真实发送」的 C03 的 rotation / position（server claimed state），
 * 供 serverCanHit / freezeAimHits / serverReachDistance 做服务端视角判定。
 *
 * 只跟踪真实出站的包：被取消（cancel）的包不会真实发送，因此不更新（对齐 moonlight wire hook）。
 */
object GrimState : Listenable, MinecraftInstance {
    private var hasClaim = false
    private var claimX = 0.0
    private var claimY = 0.0
    private var claimZ = 0.0
    private var hasRot = false
    private var claimYaw = 0f
    private var claimPitch = 0f

    /**
     * 对齐 GrimPlaceComponent.onFlying：按包携带的信息更新 claimed position / rotation。
     * isDuplicate 检查（1.17+ 重复包豁免）在此省略：重复包声称相同位置，等效于不更新。
     */
    fun onFlyingPacket(packet: C03PacketPlayer) {
        if (packet.isMoving) {
            claimX = packet.x
            claimY = packet.y
            claimZ = packet.z
            hasClaim = true
        }

        if (packet.rotating) {
            claimYaw = packet.yaw
            claimPitch = packet.pitch
            hasRot = true
        }
    }

    /** 对齐 GrimPlaceComponent.lastSentRotation()：没有 rotation 记录时返回 null。 */
    fun lastSentRotation(): FloatArray? = if (hasRot) floatArrayOf(claimYaw, claimPitch) else null

    /** 对齐 GrimPlaceComponent.claimedFeet()：无 claim 时退化为本地位置。 */
    fun claimedFeet(): Vec3 {
        if (hasClaim) return Vec3(claimX, claimY, claimZ)

        val player = mc.thePlayer
        return Vec3(player?.posX ?: 0.0, player?.posY ?: 0.0, player?.posZ ?: 0.0)
    }

    // priority 拉到 10（升序注册，最后执行）：确保能看到 Gapple / FakeLag 等模块对 SEND 包的 cancel 结果
    val onPacket = handler<PacketEvent>(priority = 10) { event ->
        if (event.eventType != EventState.SEND || event.isCancelled) return@handler

        (event.packet as? C03PacketPlayer)?.let(::onFlyingPacket)
    }

    val onWorld = handler<WorldEvent> {
        // 对齐 GrimPlaceComponent 收到 S01PacketJoinGame 时的重置
        hasClaim = false
        hasRot = false
    }
}
