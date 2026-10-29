/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.combat

import net.airplus.event.AttackEvent
import net.airplus.event.Render3DEvent
import net.airplus.event.UpdateEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.attack.EntityUtils.isLookingOnEntities
import net.airplus.utils.attack.EntityUtils.isSelected
import net.airplus.utils.client.EntityLookup
import net.airplus.utils.extensions.fixedSensitivityPitch
import net.airplus.utils.extensions.fixedSensitivityYaw
import net.airplus.utils.extensions.getDistanceToEntityBox
import net.airplus.utils.extensions.isBlock
import net.airplus.utils.human.StackedClickGenerator
import net.airplus.utils.human.StackedClickParams
import net.airplus.utils.kotlin.RandomUtils
import net.airplus.utils.kotlin.RandomUtils.nextFloat
import net.airplus.utils.timing.TimeUtils.randomClickDelay
import net.minecraft.client.entity.EntityPlayerSP
import net.minecraft.client.settings.KeyBinding
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.item.EnumAction
import net.minecraft.item.ItemBlock
import kotlin.random.Random
import kotlin.random.Random.Default.nextBoolean

object AutoClicker : Module("AutoClicker", Category.COMBAT) {

    private val simulateDoubleClicking by boolean("SimulateDoubleClicking", false)
    private val cps by intRange("CPS", 5..8, 1..50)

    /**
     * 点击模式：
     * - 普通：原有随机 CPS 延迟（区间均匀采样）。
     * - 拟真堆叠：堆叠算法模拟真实人类点击 —— 慢漂移 / 连点惯性 / 疲劳累积 /
     *   双击打滑 / 走神卡顿 多层独立噪声逐层叠加（见 [StackedClickGenerator]）。
     */
    private val mode by choices("模式", arrayOf("普通", "拟真堆叠"), "普通")

    private val isStackMode
        get() = mode == "拟真堆叠"

    // 堆叠点击参数（仅拟真堆叠模式可见，设置名与 Noise3 面板同风格使用中文）
    private val huDriftAmplitude by float("漂移幅度", 0.12f, 0f..0.4f) { isStackMode }
    private val huBurstInertia by float("连点惯性", 0.3f, 0f..0.9f) { isStackMode }
    private val huBurstChance by float("爆发概率", 20f, 0f..100f) { isStackMode }
    private val huFatigueAmplitude by float("疲劳幅度", 0.1f, 0f..0.3f) { isStackMode }
    private val huFatiguePerClick by float("单次疲劳", 0.004f, 0f..0.02f) { isStackMode }
    private val huFatigueRecovery by float("疲劳恢复ms", 400f, 50f..2000f) { isStackMode }
    private val huDoubleClickChance by float("双击概率", 8f, 0f..100f) { isStackMode }
    private val huHesitationChance by float("卡顿概率", 3f, 0f..100f) { isStackMode }

    // 点击时抖动（仅拟真堆叠模式可见，替代旧 Jitter 的固定 50% / ±1° 行为）
    private val stackJitter by boolean("点击抖动", true) { isStackMode }
    private val stackJitterChance by float("抖动概率", 30f, 0f..100f) { isStackMode && stackJitter }
    private val stackJitterAmplitude by float("抖动幅度", 0.5f, 0f..3f) { isStackMode && stackJitter }

    /**
     * 自定义挥动速度（CustomSwingSpeed）：本地挥手动画按此 CPS（次/秒）重新播放，
     * 与实际点击 CPS 完全解耦 —— 高 CPS 连点时手臂动画最多以该频率重新起挥，
     * 间隔为 1000 / 值 ms；所有点击模式生效。挥手包照常发送，服务端行为不变
     * （不动发包，只改本地渲染）。
     * 原版等效值约为 3（6 tick 动画播完后才允许重新起挥）。
     */
    val customSwingSpeedValue = int("CustomSwingSpeed", 8, 1..15)

    /** 上一次挥手动画开始时间（节流基准）。 */
    private var lastSwingAnimationStart = 0L

    /**
     * 本次 [swingItem] 是否应被节流（挥动播放间隔未到）。
     * 返回 false 时会顺带记录本次动画开始时间。
     */
    fun shouldGateSwingAnimation(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastSwingAnimationStart < 1000L / customSwingSpeedValue.get()) return true

        lastSwingAnimationStart = now
        return false
    }

    // 左右键独立生成器（两根手指的节奏本来就不同步）
    private val leftStack = StackedClickGenerator()
    private val rightStack = StackedClickGenerator()

    private val hurtTime by int("HurtTime", 10, 0..10) { left }

    private val right by boolean("Right", true)
    private val left by boolean("Left", true)
    private val jitter by boolean("Jitter", false) { !isStackMode }
    private val block by boolean("AutoBlock", false) { left }
    private val blockDelay by int("BlockDelay", 50, 0..100) { block }

    private val requiresNoInput by boolean("RequiresNoInput", false) { left }
    private val maxAngleDifference by float("MaxAngleDifference", 30f, 10f..180f) { left && requiresNoInput }
    private val range by float("Range", 3f, 0.1f..5f) { left && requiresNoInput }

    private val onlyBlocks by boolean("OnlyBlocks", true) { right }

    private var rightDelay = generateNewClickTime()
    private var rightLastSwing = 0L
    private var leftDelay = generateNewClickTime()
    private var leftLastSwing = 0L

    private var lastBlocking = 0L

    private val shouldAutoClick
        get() = mc.thePlayer.capabilities.isCreativeMode || mc.objectMouseOver?.typeOfHit?.isBlock != true

    private var shouldJitter = false

    private var target: EntityLivingBase? = null

    override fun onDisable() {
        rightLastSwing = 0L
        leftLastSwing = 0L
        lastBlocking = 0L
        target = null
        lastSwingAnimationStart = 0L
        leftStack.reset()
        rightStack.reset()
    }

    val onAttack = handler<AttackEvent> { event ->
        if (!left) return@handler
        val targetEntity = event.targetEntity as EntityLivingBase

        target = targetEntity
    }

    val onRender3D = handler<Render3DEvent> {
        mc.thePlayer?.let { thePlayer ->
            val time = System.currentTimeMillis()
            val doubleClick = if (simulateDoubleClicking) RandomUtils.nextInt(-1, 1) else 0

            if (block && thePlayer.swingProgress > 0 && !mc.gameSettings.keyBindUseItem.isKeyDown) {
                mc.gameSettings.keyBindUseItem.pressTime = 0
            }

            if (right && mc.gameSettings.keyBindUseItem.isKeyDown && time - rightLastSwing >= rightDelay) {
                if (!onlyBlocks || thePlayer.heldItem?.item is ItemBlock) {
                    handleRightClick(time, doubleClick)
                }
            }

            if (requiresNoInput) {
                val nearbyEntity = getNearestEntityInRange() ?: return@handler
                if (!isLookingOnEntities(nearbyEntity, maxAngleDifference.toDouble())) return@handler

                if (left && shouldAutoClick && time - leftLastSwing >= leftDelay) {
                    handleLeftClick(time, doubleClick)
                } else if (block && !mc.gameSettings.keyBindUseItem.isKeyDown && shouldAutoClick && shouldAutoRightClick() && mc.gameSettings.keyBindAttack.pressTime != 0) {
                    handleBlock(time)
                }
            } else {
                if (left && mc.gameSettings.keyBindAttack.isKeyDown && !mc.gameSettings.keyBindUseItem.isKeyDown && shouldAutoClick && time - leftLastSwing >= leftDelay) {
                    handleLeftClick(time, doubleClick)
                } else if (block && mc.gameSettings.keyBindAttack.isKeyDown && !mc.gameSettings.keyBindUseItem.isKeyDown && shouldAutoClick && shouldAutoRightClick() && mc.gameSettings.keyBindAttack.pressTime != 0) {
                    handleBlock(time)
                }
            }
        }
    }

    val onTick = handler<UpdateEvent> {
        mc.thePlayer?.let { thePlayer ->

            shouldJitter = mc.objectMouseOver?.typeOfHit?.isBlock != true &&
                    (thePlayer.isSwingInProgress || mc.gameSettings.keyBindAttack.pressTime != 0)

            val jitterEligible = (left && shouldAutoClick && shouldJitter)
                    || (right && !thePlayer.isUsingItem && mc.gameSettings.keyBindUseItem.isKeyDown
                    && ((onlyBlocks && thePlayer.heldItem?.item is ItemBlock) || !onlyBlocks))

            if (jitterEligible) {
                if (isStackMode) {
                    // 拟真堆叠：点击抖动走自定义 概率 × 幅度（开关关闭则不抖）
                    if (stackJitter && Random.nextFloat() * 100f < stackJitterChance) {
                        applyJitter(thePlayer, stackJitterAmplitude)
                    }
                } else if (jitter) {
                    // 普通：原有固定 50% 概率 / ±1° 抖动
                    applyJitter(thePlayer, 1f)
                }
            }
        }
    }

    /** 视角微抖：yaw / pitch 各自独立按 50% 概率施加 ±[amplitude]° 随机偏移。 */
    private fun applyJitter(thePlayer: EntityPlayerSP, amplitude: Float) {
        if (nextBoolean()) thePlayer.fixedSensitivityYaw += nextFloat(-amplitude, amplitude)
        if (nextBoolean()) thePlayer.fixedSensitivityPitch += nextFloat(-amplitude, amplitude)
    }

    private val entities by EntityLookup<EntityLivingBase> {
        isSelected(it, true) && mc.thePlayer.getDistanceToEntityBox(it) <= range
    }

    private fun getNearestEntityInRange(): Entity? {
        val player = mc.thePlayer ?: return null

        return entities.minByOrNull { player.getDistanceToEntityBox(it) }
    }

    private fun shouldAutoRightClick() = mc.thePlayer.heldItem?.itemUseAction in arrayOf(EnumAction.BLOCK)

    private fun handleLeftClick(time: Long, doubleClick: Int) {
        if (target != null && target!!.hurtTime > hurtTime) return

        repeat(1 + doubleClick) {
            KeyBinding.onTick(mc.gameSettings.keyBindAttack.keyCode)

            leftLastSwing = time
            leftDelay = nextLeftClickTime()
        }
    }

    private fun handleRightClick(time: Long, doubleClick: Int) {
        repeat(1 + doubleClick) {
            KeyBinding.onTick(mc.gameSettings.keyBindUseItem.keyCode)

            rightLastSwing = time
            rightDelay = nextRightClickTime()
        }
    }

    private fun handleBlock(time: Long) {
        if (time - lastBlocking >= blockDelay) {
            KeyBinding.onTick(mc.gameSettings.keyBindUseItem.keyCode)

            lastBlocking = time
        }
    }

    fun generateNewClickTime() = randomClickDelay(cps.first, cps.last)

    /**
     * 下一次左键点击延迟：拟真堆叠模式走堆叠生成器（跨点击维护漂移/惯性/疲劳
     * 状态），普通模式保持原有均匀随机。
     */
    private fun nextLeftClickTime() =
        if (isStackMode) leftStack.nextDelay(cps.first, cps.last, stackedParams()) else generateNewClickTime()

    private fun nextRightClickTime() =
        if (isStackMode) rightStack.nextDelay(cps.first, cps.last, stackedParams()) else generateNewClickTime()

    private fun stackedParams() = StackedClickParams(
        huDriftAmplitude, huBurstInertia, huBurstChance, huFatigueAmplitude,
        huFatiguePerClick, huFatigueRecovery, huDoubleClickChance, huHesitationChance,
    )
}
