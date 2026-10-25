/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.rotation.aim

import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.extensions.center
import net.airplus.utils.extensions.eyes
import net.airplus.utils.extensions.hitBox
import net.airplus.utils.rotation.RaycastUtils
import net.minecraft.entity.EntityLivingBase

/**
 * 数据采集与运行时共用的「场景三分类」判据（三端共用：RotationRecorder 逐 tick 地面
 * 真值打标 / train.py 作 pair 类别标签 / 运行时 RotationNet & AimTemplateEngine 路由。
 * 改判据必须三处同步）。
 *
 * 三类语义（与 AimSegmentClass 的 FLICK / FINE / TRACK 一一对应）：
 * - TRACK 追踪(3)：准星正压在实体 hitbox 上（且可见）—— 在目标身上持续滑动/跟随，
 *   学习「追踪中」的微修正分布。**含静止目标的亚 gcd 微抖动** —— 这是硬靠
 *   drift 启发式（移动导致 needed 变化）永远抓不到的数据。
 * - LOCATE 定位(1)：不在目标上、瞄准点远离视野（neededDiff >= 12°）—— 大角度甩向
 *   新目标的「爆发-减速」起手。
 * - ADJUST 调整(2)：不在目标上、瞄准点在近处（< 12°）—— 贴身小角度修正的收差尾段。
 *
 * 相比 AimSegmentClass 的「幅值 + 漂移」启发式，scene 是采集时刻的**观测真值**
 * （准星到底压没压在实体上），弥补启发式的两大盲区：
 * 1) 静止目标追踪：drift≈0 且速度低 → 启发式误判 FINE，导致 TRACK 类缺失
 *    「在实体上静止微修正」分布；
 * 2) 大实体贴脸：neededDiff（到 hitbox 最近点）可能 < 12° 但实际是甩头定位。
 * 旧的 v2 数据（scene==0）三端都回退原启发式，保证向后兼容。
 */
object AimScene : MinecraftInstance {

    const val NONE = 0
    const val LOCATE = 1
    const val ADJUST = 2
    const val TRACK = 3

    /** 定位=大角度的幅值下限，与 AimSegmentClass.FLICK_MIN_DIFF_DEG 同值同源 */
    val LOCATE_MIN_DIFF_DEG = AimSegmentClass.FLICK_MIN_DIFF_DEG

    /** 「准星压在实体上」判定射线的最大距离（格）：更远的实体本来就不可能被点击 */
    private const val FACE_RANGE = 6.0

    /**
     * [onTarget] 准星是否压在目标上；[hasTarget] 附近是否存在可瞄准实体
     * （AimBall 模式 = 小球已生成；普通模式 = 最近实体距离 > 0）；
     * [neededDiff] 当前剩余瞄准角度差幅值。
     */
    fun classify(onTarget: Boolean, hasTarget: Boolean, neededDiff: Float): Int = when {
        onTarget -> TRACK
        !hasTarget -> NONE
        neededDiff >= LOCATE_MIN_DIFF_DEG -> LOCATE
        else -> ADJUST
    }

    /**
     * 准星（视线射线）是否压在某个可交互实体（EntityLivingBase）的 hitbox 上，
     * 且视线未被方块遮挡。/[yaw]/[pitch] 为「当前生效视角」：录制端传 player 实际
     * 朝向（GameTickEvent 末尾，本 tick 转动已完成），运行时端传 lastRotations[0]
     * （决策时刻实际生效的旋转），两端语义一致。
     */
    fun crosshairOnEntity(yaw: Float, pitch: Float): Boolean {
        val player = mc.thePlayer ?: return false
        if (mc.theWorld == null) return false

        val entity = RaycastUtils.raycastEntity(
            FACE_RANGE, yaw, pitch
        ) { it is EntityLivingBase && it !== player && !it.isDead } ?: return false

        // AABB 相交了再看可见性：被墙挡住的实体不算「准星在实体上」
        return mc.theWorld.rayTraceBlocks(player.eyes, entity.hitBox.center) == null
    }
}
