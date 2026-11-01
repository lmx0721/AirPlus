/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.rotation

import net.airplus.config.Configurable
import net.airplus.config.ListValue
import net.airplus.features.module.Module
import net.airplus.utils.extensions.random
import net.airplus.utils.extensions.withGCD
import net.airplus.utils.rotation.aim.Noise3Context
import kotlin.math.abs

// TODO: refactor them all

class AlwaysRotationSettings(owner: Module, generalApply: () -> Boolean = { true }) :
    RotationSettings(owner, generalApply) {
    override val rotationsValue = super.rotationsValue.apply { excludeWithState(true) }
    override val rotationsActive: Boolean = true
}

@Suppress("MemberVisibilityCanBePrivate")
open class RotationSettings(owner: Module, generalApply: () -> Boolean = { true }) : Configurable("RotationSettings") {

    /**
     * 转头模式提供者（模块注入）：Legacy = 原有速度限幅管线；Simple = 最近点瞄准 + 固定速度；
     * Template = 模板库 k-NN；Neural = MLP 推理；Noise3 = C40N 路径规划器。
     * 未注入时恒为 Legacy（其他模块不受影响）。
     */
    var modeProvider: (() -> String)? = null

    /** Simple 模式的固定转头速度提供者（每 tick 度数，水平/竖直共用）。 */
    var speedProvider: (() -> Float)? = null

    /** Noise3 决策上下文（模块每 tick 组装：双瞄准点 + 目标标识 + 参数）。 */
    var noise3Context: Noise3Context? = null

    /**
     * 重置视角时的俯仰角锁定（模块注入，如 Telly 低头）：
     * 非 null 时回归视角阶段仅 Yaw 回归真实视角，Pitch 恒定为该值（正数即向下）。
     */
    var lockedResetPitch: Float? = null

    val aimMode: String
        get() = modeProvider?.invoke() ?: "Legacy"

    open val rotationsValue = boolean("Rotations", true) { aimMode != "Simple" && generalApply() }
    open val applyServerSideValue = boolean("ApplyServerSide", true) { rotationsActive && generalApply() }
    open val simulateShortStopValue = boolean("SimulateShortStop", false) { rotationsActive && aimMode != "Simple" && generalApply() }
    open val rotationDiffBuildUpToStopValue = float("RotationDiffBuildUpToStop", 180f, 50f..720f) { simulateShortStop }
    open val maxThresholdAttemptsToStopValue = int("MaxThresholdAttemptsToStop", 1, 0..5) { simulateShortStop }
    open val shortStopDurationValue = intRange("ShortStopDuration", 1..2, 1..5) { simulateShortStop }
    open val strafeValue = boolean("Strafe", false) { rotationsActive && applyServerSide && generalApply() }
    open val strictValue = boolean("Strict", false) { strafeValue.isActive() && generalApply() }
    open val keepRotationValue = boolean("KeepRotation", true) { rotationsActive && applyServerSide && generalApply() }

    open val resetTicksValue = int("ResetTicks", 1, 1..20) {
        rotationsActive && applyServerSide && aimMode != "Simple" && generalApply()
    }

    open val legitimizeValue = boolean("Legitimize", false) { rotationsActive && aimMode != "Simple" && generalApply() }

    // 转头速度设置仅 Legacy 模式可见：Template / Neural 由 AI 引擎自行规划步长，
    // Simple 用固定 RotationSpeed，Noise3 由 C40N 规划器自行决定每 tick 位移。
    open val horizontalAngleChangeValue =
        floatRange("HorizontalAngleChange", 180f..180f, 1f..180f) { rotationsActive && aimMode == "Legacy" && generalApply() }
    open val verticalAngleChangeValue =
        floatRange("VerticalAngleChange", 180f..180f, 1f..180f) { rotationsActive && aimMode == "Legacy" && generalApply() }

    open val angleResetDifferenceValue = float("AngleResetDifference", 5f.withGCD(), 0.0f..180f) {
        rotationsActive && applyServerSide && aimMode != "Simple" && generalApply()
    }

    open val minRotationDifferenceValue = float(
        "MinRotationDifference", 2f, 0f..4f
    ) { rotationsActive && aimMode != "Simple" && generalApply() }

    open val minRotationDifferenceResetTimingValue = choices(
        "MinRotationDifferenceResetTiming", arrayOf("OnStart", "OnSlowDown", "Always"), "OnStart"
    ) { rotationsActive && aimMode != "Simple" && generalApply() }

    // Variables for easier access
    // Simple 模式强制开启转头（Rotations 设置隐藏，读值走强制分支）；ApplyServerSide 开放为可见设置
    val rotations: Boolean
        get() = aimMode == "Simple" || rotationsValue.isActive()

    val applyServerSide: Boolean
        get() = applyServerSideValue.isActive()
    val simulateShortStop by simulateShortStopValue
    val rotationDiffBuildUpToStop by rotationDiffBuildUpToStopValue
    val maxThresholdAttemptsToStop by maxThresholdAttemptsToStopValue
    val shortStopDuration by shortStopDurationValue
    val strafe by strafeValue
    val strict by strictValue
    val keepRotation by keepRotationValue
    val resetTicks by resetTicksValue
    val legitimize by legitimizeValue
    val horizontalAngleChange by horizontalAngleChangeValue
    val verticalAngleChange by verticalAngleChangeValue
    val angleResetDifference by angleResetDifferenceValue
    val minRotationDifference by minRotationDifferenceValue
    val minRotationDifferenceResetTiming by minRotationDifferenceResetTimingValue

    var prioritizeRequest = false
    var immediate = false
    var instant = false

    var rotDiffBuildUp = 0f
    var maxThresholdReachAttempts = 0

    open val rotationsActive
        get() = rotations

    // Simple 模式用固定 RotationSpeed（speedProvider），其余模式走 Legacy 的区间随机
    val horizontalSpeed
        get() = if (aimMode == "Simple") {
            speedProvider?.invoke() ?: 180f
        } else {
            horizontalAngleChange.random()
        }

    val verticalSpeed
        get() = if (aimMode == "Simple") {
            speedProvider?.invoke() ?: 180f
        } else {
            verticalAngleChange.random()
        }

    fun withoutKeepRotation() = apply {
        keepRotationValue.excludeWithState()
    }

    fun updateSimulateShortStopData(diff: Float) {
        rotDiffBuildUp += diff
    }

    fun resetSimulateShortStopData() {
        rotDiffBuildUp = 0f
        maxThresholdReachAttempts = 0
    }

    fun shouldPerformShortStop(): Boolean {
        if (abs(rotDiffBuildUp) < rotationDiffBuildUpToStop || !simulateShortStop) return false

        if (maxThresholdReachAttempts < maxThresholdAttemptsToStop) {
            maxThresholdReachAttempts++
            return false
        }

        return true
    }

    init {
        owner.addValues(this.values)
    }
}

class RotationSettingsWithRotationModes(
    owner: Module, listValue: ListValue, generalApply: () -> Boolean = { true },
) : RotationSettings(owner, generalApply) {

    override val rotationsValue = super.rotationsValue.apply { excludeWithState() }

    val rotationModeValue = listValue.setSupport { generalApply() }

    val rotationMode by +rotationModeValue

    override val rotationsActive: Boolean
        get() = rotationMode != "Off"
}
