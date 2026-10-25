/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.rotation

import net.airplus.config.Configurable
import net.airplus.features.module.Module
import net.airplus.utils.extensions.plus
import net.airplus.utils.extensions.random
import net.airplus.utils.extensions.times
import net.airplus.utils.rotation.RotationUtils.angleDifference
import net.airplus.utils.rotation.RotationUtils.getVectorForRotation
import net.airplus.utils.rotation.RotationUtils.lastRotations
import net.minecraft.util.AxisAlignedBB
import net.minecraft.util.Vec3
import kotlin.math.sign

class RandomizationSettings(owner: Module, val generalApply: () -> Boolean = { true }) : Configurable("Randomization") {

    private val randomizationPattern by choices(
        "RandomizationPattern", arrayOf("None", "Zig-Zag", "LazyFlick"), "None"
    ) { generalApply() }
    private val yawRandomizationChance by floatRange(
        "YawRandomizationChance", 0.8f..1.0f, 0f..1f
    ) { randomizationChosen }
    private val yawRandomizationRange by floatRange(
        "YawRandomizationRange",
        5f..10f,
        0f..30f
    ) { isZizZagActive && randomizationChosen && yawRandomizationChance.start != 1F }
    private val yawSpeedIncreaseMultiplier by intRange(
        "YawSpeedIncreaseMultiplier", 50..120, 0..500, suffix = "%"
    ) { !isZizZagActive && randomizationChosen && yawRandomizationChance.start != 1F }
    private val pitchRandomizationChance by floatRange(
        "PitchRandomizationChance", 0.8f..1.0f, 0f..1f
    ) { randomizationChosen }
    private val pitchRandomizationRange by floatRange(
        "PitchRandomizationRange",
        5f..10f,
        0f..30f
    ) { randomizationChosen && pitchRandomizationChance.start != 1F }

    private val isZizZagActive
        get() = randomizationPattern == "Zig-Zag"

    val randomizationChosen
        get() = randomizationPattern != "None" && generalApply()

    /**
     * AI 瞄准（aimStrategy != Default）时段点跳动的跳点间隔（tick）。
     * AI 引擎（RotationNet / AimTemplateEngine）逐 tick 平滑转换剩余差，若每 tick
     * 都跳点，剩余差方向高频抖动会污染漂移/速度特征并让 AI 输出显得抽搐。
     * 开启 AI 瞄准时按该间隔限流跳点，其余模式不受影响（每 tick 都可跳）。
     */
    private val aiSpotHopInterval by int("AiSpotHopInterval", 4, 1..20) { randomizationChosen }

    private var hopCooldown = 0

    /**
     * 本 tick 是否允许跳点。[aiStable] = AI 瞄准激活（按 [aiSpotHopInterval] 冷却限流）；
     * 其余模式始终允许。
     */
    fun shouldHop(aiStable: Boolean): Boolean {
        if (!aiStable) return true
        if (hopCooldown > 0) {
            hopCooldown--
            return false
        }
        hopCooldown = aiSpotHopInterval - 1
        return true
    }

    fun processNextSpot(box: AxisAlignedBB, rotation: Rotation, eyes: Vec3, range: Double) {
        val intercept = box.calculateIntercept(eyes, eyes + getVectorForRotation(lastRotations.random()) * range)

        // Smooth out randomized rotation pattern using previous rotation to simulate natural movement
        val pitchMovement =
            angleDifference(rotation.pitch, lastRotations[2].pitch).sign.takeIf { it != 0f } ?: (-1..1).random()
                .toFloat()
        val yawMovement = angleDifference(rotation.yaw, lastRotations[2].yaw)

        val yawSign = yawMovement.sign.takeIf { it != 0f } ?: arrayOf(-1f, 1f).random()

        val yawIncrease = if (Math.random() > yawRandomizationChance.random()) {
            if (!isZizZagActive) {
                yawSpeedIncreaseMultiplier.random() / 100f * yawMovement
            } else {
                yawRandomizationRange.random() * yawSign
            }
        } else 0f

        val pitchIncrease = if (Math.random() > pitchRandomizationChance.random()) {
            if (!isZizZagActive) {
                pitchRandomizationRange.random() + pitchMovement
            } else {
                pitchRandomizationRange.random() * pitchMovement
            }
        } else 0f

        if (isZizZagActive || intercept?.hitVec == null) {
            rotation.yaw += yawIncrease
            rotation.pitch += pitchIncrease

            rotation.fixedSensitivity()
        }
    }

    init {
        owner.addValues(this.values)
    }
}