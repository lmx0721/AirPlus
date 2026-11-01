/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.combat

import net.airplus.AirPlus
import net.airplus.event.*
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.modules.combat.Backtrack.runWithSimulatedPosition
import net.airplus.features.module.modules.misc.Teams
import net.airplus.features.module.modules.player.Blink
import net.airplus.features.module.modules.world.Fucker
import net.airplus.features.module.modules.world.Nuker
import net.airplus.features.module.modules.world.scaffolds.*
import net.airplus.utils.attack.CPSCounter
import net.airplus.utils.attack.CooldownHelper.getAttackCooldownProgress
import net.airplus.utils.attack.CooldownHelper.resetLastAttackedTicks
import net.airplus.utils.attack.EntityUtils.isLookingOnEntities
import net.airplus.utils.attack.EntityUtils.isSelected
import net.airplus.utils.client.BlinkUtils
import net.airplus.utils.client.ClientUtils.runTimeTicks
import net.airplus.utils.client.PacketUtils.sendPacket
import net.airplus.utils.client.PacketUtils.sendPackets
import net.airplus.utils.extensions.*
import net.airplus.utils.human.StackedClickGenerator
import net.airplus.utils.human.StackedClickParams
import net.airplus.utils.inventory.InventoryUtils.serverOpenInventory
import net.airplus.utils.inventory.ItemUtils.isConsumingItem
import net.airplus.utils.inventory.SilentHotbar
import net.airplus.utils.kotlin.RandomUtils.nextInt
import net.airplus.utils.render.ColorSettingsInteger
import net.airplus.utils.render.ColorUtils.withAlpha
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.render.RenderUtils.drawCircle
import net.airplus.utils.render.RenderUtils.drawEntityBox
import net.airplus.utils.render.RenderUtils.drawPlatform
import net.airplus.utils.rotation.RandomizationSettings
import net.airplus.utils.rotation.RaycastUtils.raycastEntity
import net.airplus.utils.rotation.RaycastUtils.runWithModifiedRaycastResult
import net.airplus.utils.rotation.Rotation
import net.airplus.utils.rotation.RotationSettings
import net.airplus.utils.rotation.RotationUtils
import net.airplus.utils.rotation.RotationUtils.currentRotation
import net.airplus.utils.rotation.RotationUtils.getVectorForRotation
import net.airplus.utils.rotation.RotationUtils.isRotationFaced
import net.airplus.utils.rotation.RotationUtils.isVisible
import net.airplus.utils.rotation.RotationUtils.rotationDifference
import net.airplus.utils.rotation.RotationUtils.searchCenter
import net.airplus.utils.rotation.RotationUtils.serverRotation
import net.airplus.utils.rotation.RotationUtils.setTargetRotation
import net.airplus.utils.rotation.RotationUtils.toRotation
import net.airplus.utils.rotation.aim.AimScene
import net.airplus.utils.rotation.aim.Noise3Context
import net.airplus.utils.rotation.aim.Noise3Params
import net.airplus.utils.simulation.SimulatedPlayer
import net.airplus.utils.timing.MSTimer
import net.airplus.utils.timing.TickedActions.nextTick
import net.airplus.utils.timing.TimeUtils.randomClickDelay
import net.minecraft.client.gui.inventory.GuiContainer
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.item.EntityArmorStand
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.ItemAxe
import net.minecraft.item.ItemSword
import net.minecraft.network.play.client.C02PacketUseEntity
import net.minecraft.network.play.client.C02PacketUseEntity.Action.*
import net.minecraft.network.play.client.C07PacketPlayerDigging
import net.minecraft.network.play.client.C07PacketPlayerDigging.Action.RELEASE_USE_ITEM
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement
import net.minecraft.potion.Potion
import net.minecraft.util.*
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11
import java.awt.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object KillAura : Module("KillAura", Category.COMBAT, Keyboard.KEY_R) {
    /**
     * OPTIONS
     */

    private val simulateCooldown by boolean("SimulateCooldown", false)
    private val simulateDoubleClicking by boolean("SimulateDoubleClicking", false) { !simulateCooldown }

    // CPS - Attack speed
    private val cps by intRange("CPS", 5..8, 1..50) { !simulateCooldown }.onChanged {
        attackDelay = nextAttackDelay()
    }

    /**
     * 点击模式：
     * - 普通：原有随机 CPS 延迟（区间均匀采样）。
     * - 拟真堆叠：堆叠算法模拟真实人类点击 —— 慢漂移 / 连点惯性 / 疲劳累积 /
     *   双击打滑 / 走神卡顿 多层独立噪声逐层叠加（见 [StackedClickGenerator]），
     *   参数见下方拟人点击组。与 AutoClicker 的堆叠点击互不共享状态。
     */
    private val clickMode by choices("点击模式", arrayOf("普通", "拟真堆叠"), "普通") { !simulateCooldown }

    private val isStackClickMode
        get() = clickMode == "拟真堆叠"

    // 拟人点击参数（仅拟真堆叠点击模式可见，中文面板，与 AutoClicker 面板同风格）
    private val acDriftAmplitude by float("点击漂移幅度", 0.12f, 0f..0.4f) { isStackClickMode }
    private val acBurstInertia by float("点击连点惯性", 0.3f, 0f..0.9f) { isStackClickMode }
    private val acBurstChance by float("点击爆发概率", 20f, 0f..100f) { isStackClickMode }
    private val acFatigueAmplitude by float("点击疲劳幅度", 0.1f, 0f..0.3f) { isStackClickMode }
    private val acFatiguePerClick by float("点击单次疲劳", 0.004f, 0f..0.02f) { isStackClickMode }
    private val acFatigueRecovery by float("点击疲劳恢复ms", 400f, 50f..2000f) { isStackClickMode }
    private val acDoubleClickChance by float("点击双击概率", 8f, 0f..100f) { isStackClickMode }
    private val acHesitationChance by float("点击卡顿概率", 3f, 0f..100f) { isStackClickMode }

    /** 拟真堆叠点击生成器（KillAura 独立实例）。 */
    private val stackGenerator = StackedClickGenerator()

    /**
     * 下一次攻击延迟：拟真堆叠点击模式走堆叠生成器（跨点击维护漂移/惯性/疲劳
     * 状态），普通模式保持原有均匀随机。
     */
    private fun nextAttackDelay(): Int =
        if (isStackClickMode) {
            stackGenerator.nextDelay(cps.first, cps.last, stackedClickParams())
        } else {
            randomClickDelay(cps.first, cps.last)
        }

    private fun stackedClickParams() = StackedClickParams(
        acDriftAmplitude, acBurstInertia, acBurstChance, acFatigueAmplitude,
        acFatiguePerClick, acFatigueRecovery, acDoubleClickChance, acHesitationChance,
    )

    private val hurtTime by int("HurtTime", 10, 0..10) { !simulateCooldown }

    private val activationSlot by boolean("ActivationSlot", false) { !isSimpleMode }
    private val preferredSlot by int("PreferredSlot", 1, 1..9) { activationSlot }

    private val clickOnly by boolean("ClickOnly", false) { !isSimpleMode }

    // Range
    // TODO: Make block range independent from attack range
    private val range: Float by float("Range", 3.7f, 1f..8f).onChanged {
        blockRange = blockRange.coerceAtMost(it)
    }
    private val scanRange by float("ScanRange", 2f, 0f..10f)
    private val throughWallsRange by float("ThroughWallsRange", 3f, 0f..8f)
    private val rangeSprintReduction by float("RangeSprintReduction", 0f, 0f..0.4f)

    // Modes
    private val priority by choices(
        "Priority", arrayOf(
            "Health",
            "Distance",
            "Direction",
            "LivingTime",
            "Armor",
            "HurtResistance",
            "HurtTime",
            "HealthAbsorption",
            "RegenAmplifier",
            "OnLadder",
            "InLiquid",
            "InWeb"
        ), "Distance"
    )
    private val targetMode by choices("TargetMode", arrayOf("Single", "Switch", "Multi"), "Switch")
    private val limitedMultiTargets by int("LimitedMultiTargets", 0, 0..50) { targetMode == "Multi" }
    private val maxSwitchFOV by float("MaxSwitchFOV", 90f, 30f..180f) { targetMode == "Switch" }

    // Delay
    private val switchDelay by int("SwitchDelay", 15, 1..1000) { targetMode == "Switch" }

    // Bypass
    private val swingValue = boolean("Swing", true)
    private val swing
        get() = swingValue.isActive()
    private val keepSprintValue = boolean("KeepSprint", true)
    private val keepSprint
        get() = keepSprintValue.isActive()

    // Settings
    private val autoF5 by boolean("AutoF5", false) { !isSimpleMode }
    private val onScaffold by boolean("OnScaffold", false) { !isSimpleMode }
    private val onDestroyBlock by boolean("OnDestroyBlock", false) { !isSimpleMode }

    // AutoBlock
    val autoBlock by choices("AutoBlock", arrayOf("Off", "Packet", "Fake", "BlockOnNoHit", "HurtTime"), "Packet")
    private val blockOnNoHitRange by float("BlockOnNoHitRange", 6f, 1f..16f) { autoBlock == "BlockOnNoHit" }
    private val blockMaxRange by float("BlockMaxRange", 3f, 0f..8f) { autoBlock == "Packet" || autoBlock == "HurtTime" }

    /**
     * HurtTime AutoBlock：不整轮无脑 block，跟自己的受击节奏走 ——
     * 挨打瞬间先松开格挡（受击期间原版不给格挡减伤，白挡收招/反打），等自己
     * hurtTime 回落到 [blockOwnHurtTime] 以下且目标仍在 [blockMaxRange]
     * （stopblock 范围，默认 3 格）内时再起格挡，按 [hurtTimeBlockTicksValue]
     * 随机持续时长（默认 2..4 tick）维持后松开，等待下一次挨打再重置节奏。
     * 起挡为直发 C08PacketPlayerBlockPlacement（use-item 形式）、松挡直发
     * C07 RELEASE_USE_ITEM —— 不能模拟右键走 vanilla 流程：准星压在目标上时
     * vanilla rightClickMouse 走实体 interact 路径（C08PacketUseEntity），
     * 既不会真正起格挡，又会把 interact 包夹进攻击序列（multiaction 命中）。
     */
    private val blockOwnHurtTime by int("BlockOwnHurtTime", 2, 0..10) { autoBlock == "HurtTime" }
    private val hurtTimeBlockTicksValue = intRange("HurtTimeBlockTicks", 2..4, 1..10) { autoBlock == "HurtTime" }
    // HurtTime 方向检查：对方视线与「对方 → 自己 hitbox 中心」方向的夹角超过该值
    // （对方根本没看向自己）时不格挡
    private val hurtTimeDirectionDiff by float("HurtTimeDirectionDiff", 40f, 0f..180f) { autoBlock == "HurtTime" }
    private val unblockMode by choices(
        "UnblockMode", arrayOf("Stop", "Switch", "Empty"), "Stop"
    ) { autoBlock == "Packet" }
    private val releaseAutoBlock by boolean("ReleaseAutoBlock", true) { autoBlock !in arrayOf("Off", "Fake", "BlockOnNoHit") }
    val forceBlockRender by boolean("ForceBlockRender", true) {
        autoBlock !in arrayOf(
            "Off", "Fake", "BlockOnNoHit"
        ) && releaseAutoBlock
    }
    private val ignoreTickRule by boolean("IgnoreTickRule", false) {
        autoBlock !in arrayOf(
            "Off", "Fake", "BlockOnNoHit"
        ) && releaseAutoBlock
    }
    private val blockRate by int("BlockRate", 100, 1..100) { autoBlock !in arrayOf("Off", "Fake", "HurtTime") && releaseAutoBlock }

    private val uncpAutoBlock by boolean("UpdatedNCPAutoBlock", false) {
        autoBlock !in arrayOf(
            "Off", "Fake", "BlockOnNoHit"
        ) && !releaseAutoBlock
    }

    private val switchStartBlock by boolean("SwitchStartBlock", false) { autoBlock !in arrayOf("Off", "Fake", "BlockOnNoHit", "HurtTime") }

    private val interactAutoBlock by boolean("InteractAutoBlock", true) { autoBlock !in arrayOf("Off", "Fake", "BlockOnNoHit", "HurtTime") }

    val blinkAutoBlock by boolean("BlinkAutoBlock", false) { autoBlock !in arrayOf("Off", "Fake", "BlockOnNoHit") }

    private val blinkBlockTicks by int("BlinkBlockTicks", 3, 2..5) {
        autoBlock !in arrayOf(
            "Off", "Fake", "BlockOnNoHit"
        ) && blinkAutoBlock
    }

    // AutoBlock conditions
    private val smartAutoBlock by boolean("SmartAutoBlock", false) { autoBlock == "Packet" }

    // Ignore all blocking conditions, except for block rate, when standing still
    private val forceBlock by boolean("ForceBlockWhenStill", true) { smartAutoBlock }

    // Don't block if target isn't holding a sword or an axe
    private val checkWeapon by boolean("CheckEnemyWeapon", true) { smartAutoBlock }

    // TODO: Make block range independent from attack range
    private var blockRange: Float by float("BlockRange", range, 1f..8f) {
        smartAutoBlock
    }.onChange { _, new ->
        new.coerceAtMost(this@KillAura.range)
    }

    // Don't block when you can't get damaged
    private val maxOwnHurtTime by int("MaxOwnHurtTime", 3, 0..10) { smartAutoBlock }

    // Don't block if target isn't looking at you
    private val maxDirectionDiff by float("MaxOpponentDirectionDiff", 60f, 30f..180f) { smartAutoBlock }

    // Don't block if target is swinging an item and therefore cannot attack
    private val maxSwingProgress by int("MaxOpponentSwingProgress", 1, 0..5) { smartAutoBlock }

    // Rotations
    private val options = RotationSettings(this).withoutKeepRotation()

    /**
     * 转头模式（位于 Rotations 下方，init 里调整 GUI 顺序）：
     * - Legacy：原有管线，无改动（默认）。
     * - Simple：瞄准 hitbox 上与当前视角夹角最小的点（最近点瞄准），固定速度转头；
     *   该模式下设置精简为 AutoBlock / Strafe / Strict / RotationSpeed（+ Range / CPS /
     *   目标选择 / Mark），ApplyServerSide / Swing / RayCast 开放为可见设置，
     *   FailSwing / SwingOnlyInAir 不再强制启用（默认关闭）。
     * - Template / Neural：AI 学习转头（无转头速度设置），无数据时自动回退 Legacy 管线。
     * - Noise3：C40N 路径规划器转头（无转头速度设置），参数面板见下方 Noise3 组。
     */
    private val rotationModeValue = choices(
        "RotationMode", arrayOf("Legacy", "Simple", "Template", "Neural", "Noise3"), "Legacy"
    ) { options.rotationsActive }
    private val rotationMode by rotationModeValue

    private val isSimpleMode
        get() = rotationMode == "Simple"

    /** Simple 模式固定转头速度（每 tick 度数，同时控制水平与竖直方向）。 */
    private val rotationSpeedValue = int("RotationSpeed", 60, 1..180) { isSimpleMode }
    private val rotationSpeed by rotationSpeedValue

    // RayCast（Simple 模式下同样开放为可见设置；子项 RayCastIgnored / LivingRayCast 仍隐藏，读值不变）
    private val raycastValue = boolean("RayCast", true) { options.rotationsActive }
    private val raycast
        get() = raycastValue.isActive()
    private val raycastIgnored by boolean(
        "RayCastIgnored", false
    ) { raycastValue.isActive() && options.rotationsActive && options.aimMode != "Simple" }
    private val livingRaycastValue = boolean("LivingRayCast", true) {
        raycastValue.isActive() && options.rotationsActive && options.aimMode != "Simple"
    }
    private val livingRaycast
        get() = isSimpleMode || livingRaycastValue.isActive()

    // Hit delay
    private val useHitDelay by boolean("UseHitDelay", false) { !isSimpleMode }
    private val hitDelayTicks by int("HitDelayTicks", 1, 1..5) { useHitDelay }

    private val generateClicksBasedOnDist by boolean("GenerateClicksBasedOnDistance", false) { !isSimpleMode }
    private val cpsMultiplier by intRange("CPS-Multiplier", 1..2, 1..10) { generateClicksBasedOnDist }
    private val distanceFactor by floatRange("DistanceFactor", 5F..10F, 1F..10F) { generateClicksBasedOnDist }

    private val generateSpotBasedOnDistance by boolean("GenerateSpotBasedOnDistance", false) {
        !isSimpleMode && options.rotationsActive
    }

    // Simple 模式下随机化整组隐藏且不生效（randomizationChosen 依赖 generalApply）
    private val randomization = RandomizationSettings(this) {
        options.rotationsActive && !isSimpleMode
    }
    private val outBorder by boolean("OutBorder", false) { !isSimpleMode && options.rotationsActive }

    // 竖直方向选点范围（hitbox 高度比例：0 = 脚底，1 = 头顶），取代旧的 Head/Body/Feet 三档。
    // 默认 0.1..0.9 与旧 Head..Feet 档位等价；searchCenter 会在该区间内均匀采样候选点。
    private val verticalBodySearchRangeValue = floatRange(
        "VerticalBodySearchRange", 0.1f..0.9f, 0f..1f
    ) { !isSimpleMode && options.rotationsActive }
    private val verticalBodySearchRange by verticalBodySearchRangeValue

    private val horizontalBodySearchRange by floatRange(
        "HorizontalBodySearchRange", 0f..1f, 0f..1f
    ) { !isSimpleMode && options.rotationsActive }

    private val fov by float("FOV", 180f, 0f..180f)

    // Prediction
    private val predictClientMovement by int("PredictClientMovement", 2, 0..5) { !isSimpleMode }
    private val predictOnlyWhenOutOfRange by boolean(
        "PredictOnlyWhenOutOfRange", false
    ) { predictClientMovement != 0 && !isSimpleMode }
    // 保留 Value 引用：init 里把「转头模式」(RotationMode) 移到该值之后展示（rotation 相关设置的最下方）
    private val predictEnemyPositionValue = float("PredictEnemyPosition", 1.5f, -1f..2f) { !isSimpleMode }
    private val predictEnemyPosition by predictEnemyPositionValue

    // AI 瞄准点改进（仅 Template / Neural 模式可见且生效；其余模式保持用户原始选点与预判）
    /** 总开关：远距自动收敛上半身选点 + 预判下限。 */
    private val aiAimSpotValue = boolean("AiAimSpot", true) { options.aimMode == "Template" || options.aimMode == "Neural" }
    private val aiAimSpot by aiAimSpotValue

    /** 远距收敛起始距离：目标 hitbox 距离 >= 该值时把选点范围收敛 Head..Body（上半身视角内变化率低且不易被遮挡）。 */
    private val aiFarSpotDistanceValue = float("AiFarSpotDistance", 8f, 3f..20f) {
        aiAimSpot && (options.aimMode == "Template" || options.aimMode == "Neural")
    }
    private val aiFarSpotDistance by aiFarSpotDistanceValue

    /** 远距收敛恢复距离（滞回下界）：目标贴近该距离以下才恢复用户的 bodyPoints 配置，避免阈值附近每 tick 反复切范围。 */
    private val aiFarSpotRecoverDistanceValue = float("AiFarSpotRecoverDistance", 7.5f, 2f..20f) {
        aiAimSpot && (options.aimMode == "Template" || options.aimMode == "Neural")
    }
    private val aiFarSpotRecoverDistance by aiFarSpotRecoverDistanceValue

    /** AI 预判下限：AI 逐 tick 平滑收差需要数 tick，预判低于该值会一直打在目标移动的身后。 */
    private val aiMinPredictionValue = float("AiMinPrediction", 1f, 0f..3f) {
        aiAimSpot && (options.aimMode == "Template" || options.aimMode == "Neural")
    }
    private val aiMinPrediction by aiMinPredictionValue

    /** AI 距离化选点的滞回状态（阈值见 AiFarSpotDistance / AiFarSpotRecoverDistance）。 */
    private var aiFarSpot = false

    // Noise3 参数（仅 Noise3 模式可见；规格 noise3.txt 的全部可调参数）
    private val n3PointSelection by choices(
        "Noise3选点模式", arrayOf("直接选点", "预判平滑"), "预判平滑"
    ) { rotationMode == "Noise3" }
    private val n3AimSpeed by float("Noise3瞄准速度", 15f, 1f..100f) { rotationMode == "Noise3" }
    private val n3DecelPre by float("Noise3减速前系数", 1f, 0.5f..2f) { rotationMode == "Noise3" }
    private val n3DecelPost by float("Noise3减速后系数", 1f, 0.5f..8f) { rotationMode == "Noise3" }
    private val n3DecelPostHighspeed by float("Noise3高速减速系数", 2f, 0.5f..8f) { rotationMode == "Noise3" }
    private val n3HighspeedThreshold by float("Noise3高速阈值", 10f, 5f..20f) { rotationMode == "Noise3" }
    private val n3SpeedAtLargeRot by float("Noise3大角度速度", 30f, 1f..100f) { rotationMode == "Noise3" }
    private val n3SpeedAtInitialRot by float("Noise3小角度速度", 30f, 1f..100f) { rotationMode == "Noise3" }
    private val n3LargeRotThreshold by float("Noise3大角度阈值", 30f, 20f..180f) { rotationMode == "Noise3" }
    private val n3ResyncChance by float("Noise3重同步概率", 0f, 0f..100f) { rotationMode == "Noise3" }
    private val n3ResyncUpdateDelay by float("Noise3重同步间隔", 1f, 1f..5f) { rotationMode == "Noise3" }
    private val n3RemoveNoiseChance by float("Noise3去噪概率", 0f, 0f..100f) { rotationMode == "Noise3" }
    private val n3NoNoiseRecalcDelay by float("Noise3去噪间隔", 1f, 1f..8f) { rotationMode == "Noise3" }
    private val n3ResyncDelayMs by float("Noise3重同步延迟ms", 0f, 0f..1000f) { rotationMode == "Noise3" }
    private val n3ResyncRateMin by float("Noise3重同步下限", 0.4f, 0f..1f) { rotationMode == "Noise3" }
    private val n3ResyncRateMax by float("Noise3重同步上限", 0.6f, 0f..1f) { rotationMode == "Noise3" }

    private val forceFirstHit by boolean("ForceFirstHit", false) { !respectMissCooldown && !useHitDelay }

    // Extra swing
    private val failSwingValue = boolean("FailSwing", false) { swing && !isSimpleMode && options.rotationsActive }
    private val failSwing
        get() = failSwingValue.isActive()
    private val respectMissCooldown by boolean(
        "RespectMissCooldown", false
    ) { swing && failSwing && !isSimpleMode && options.rotationsActive }
    private val swingOnlyInAirValue = boolean("SwingOnlyInAir", false) {
        swing && failSwing && !isSimpleMode && options.rotationsActive
    }
    private val swingOnlyInAir
        get() = swingOnlyInAirValue.isActive()
    private val maxRotationDifferenceToSwing by float(
        "MaxRotationDifferenceToSwing", 180f, 0f..180f
    ) { swing && failSwing && !isSimpleMode && options.rotationsActive }
    private val swingWhenTicksLate = boolean("SwingWhenTicksLate", false) {
        swing && failSwing && maxRotationDifferenceToSwing != 180f && !isSimpleMode && options.rotationsActive
    }
    private val ticksLateToSwing by int(
        "TicksLateToSwing", 4, 0..20
    ) { swing && failSwing && swingWhenTicksLate.isActive() && options.rotationsActive }
    private val renderBoxOnSwingFail by boolean("RenderBoxOnSwingFail", false) { failSwing && !isSimpleMode }
    private val renderBoxColor = ColorSettingsInteger(this, "RenderBoxColor") { renderBoxOnSwingFail }.with(Color.CYAN)
    private val renderBoxFadeSeconds by float("RenderBoxFadeSeconds", 1f, 0f..5f) { renderBoxOnSwingFail }

    // Inventory
    private val simulateClosingInventory by boolean("SimulateClosingInventory", false) { !noInventoryAttack }
    private val noInventoryAttack by boolean("NoInvAttack", false) { !isSimpleMode }
    private val noInventoryDelay by int("NoInvDelay", 200, 0..500) { noInventoryAttack }
    private val noConsumeAttack by choices(
        "NoConsumeAttack", arrayOf("Off", "NoHits", "NoRotation"), "Off"
    ) { !isSimpleMode }.subjective()

    // Visuals
    private val mark by choices("Mark", arrayOf("None", "Platform", "Box", "Circle"), "Circle").subjective()
    private val fakeSharp by boolean("FakeSharp", true).subjective()
    private val renderAimPointBox by boolean("RenderAimPointBox", false).subjective()
    private val aimPointBoxColor by color("AimPointBoxColor", Color.CYAN) { renderAimPointBox }.subjective()
    private val aimPointBoxSize by float("AimPointBoxSize", 0.1f, 0f..0.2F) { renderAimPointBox }.subjective()

    // Circle options
    private val circleStartColor by color("CircleStartColor", Color.BLUE) { mark == "Circle" }.subjective()
    private val circleEndColor by color("CircleEndColor", Color.CYAN.withAlpha(0)) { mark == "Circle" }.subjective()
    private val fillInnerCircle by boolean("FillInnerCircle", false) { mark == "Circle" }.subjective()
    private val withHeight by boolean("WithHeight", true) { mark == "Circle" }.subjective()
    private val animateHeight by boolean("AnimateHeight", false) { withHeight }.subjective()
    private val heightRange by floatRange("HeightRange", 0.0f..0.4f, -2f..2f) { withHeight }.subjective()
    private val extraWidth by float("ExtraWidth", 0F, 0F..2F) { mark == "Circle" }.subjective()
    private val animateCircleY by boolean("AnimateCircleY", true) { fillInnerCircle || withHeight }.subjective()
    private val circleYRange by floatRange("CircleYRange", 0F..0.5F, 0F..2F) { animateCircleY }.subjective()
    private val duration by float(
        "Duration", 1.5F, 0.5F..3F, suffix = "Seconds"
    ) { animateCircleY || animateHeight }.subjective()

    // Box option
    private val boxOutline by boolean("Outline", true) { mark == "Box" }.subjective()

    // Range circle - attack range display
    /** 在 [range] 攻击范围边界绕玩家绘制一圈圆（颜色含透明度、边数可自定义）。 */
    private val rangeCircleValue = boolean("RangeCircle", false).subjective()

    /** 范围圈颜色（ColorValue 自带透明度滑条）。 */
    private val rangeCircleColorValue = color("RangeCircleColor", Color(37, 126, 255, 120)) {
        rangeCircleValue.get()
    }.subjective()

    /**
     * 范围圈边数：顶点 = 边数，调低可省性能、调高更圆润；180 顶点以上每帧开销
     * 仍只有一次 LINE_LOOP，但建议保持默认 60 的流畅度折中。
     */
    private val rangeCircleSidesValue = int("RangeCircleSides", 60, 8..180) {
        rangeCircleValue.get()
    }.subjective()

    /**
     * MODULE
     */

    init {
        // GUI 顺序调整：把「转头模式」(RotationMode) 与 Simple 模式的「RotationSpeed」
        // 移到 Rotations 正下方，一次读完整个转头模式区块。
        options.modeProvider = { rotationMode }
        options.speedProvider = { rotationSpeed.toFloat() }

        val list = get()
        if (list.remove(rotationModeValue)) {
            list.add(list.indexOf(options.rotationsValue) + 1, rotationModeValue)

            list.remove(rotationSpeedValue)
            list.add(list.indexOf(rotationModeValue) + 1, rotationSpeedValue)
        }
    }

    // Target
    var target: EntityLivingBase? = null
    private var lastTarget: EntityLivingBase? = null
    private var hittable = false
    private val prevTargetEntities = mutableListOf<Int>()

    // Attack delay
    private val attackTimer = MSTimer()
    private var attackDelay = nextAttackDelay()
    private var clicks = 0
    private var attackTickTimes = mutableListOf<Pair<MovingObjectPosition, Int>>()

    // Container Delay
    private var containerOpen = -1L

    // Block status
    var renderBlocking = false
    var blockStatus = false
    private var blockStopInDead = false

    // Switch Delay
    private val switchTimer = MSTimer()

    // HurtTime AutoBlock 状态：本次格挡窗口剩余 tick / 上 tick 自己的 hurtTime
    private var hurtBlockTicksLeft = 0
    private var lastOwnHurtTime = 0
    // HurtTime 格挡窗口：true 表示当前格挡由本模块状态机持有（C08 已发、待 C07）
    private var hurtBlockKeyDown = false
    // 窗口自然走完后等下一次挨打再开新一轮（hurtTime 长期低于阈值时不无限反复 block）
    private var hurtBlockAwaitHit = false
    // 本 tick 刚松过挡：C07 释放包已直发，该 tick 禁止攻击，留出包序间隔
    private var hurtBlockReleasedThisTick = false

    // Blink AutoBlock
    private var blinked = false

    // Swing fails
    private val swingFails = mutableListOf<SwingFailData>()

    /**
     * Disable kill aura module
     */
    override fun onToggle(state: Boolean) {
        target = null
        // 清掉 Noise3 上下文，避免模块关闭后残留上下文劫持视角重置路径（审查 B1）
        options.noise3Context = null
        hittable = false
        prevTargetEntities.clear()
        attackTickTimes.clear()
        attackTimer.reset()
        clicks = 0
        // 拟真堆叠点击生成器复位（连点惯性/疲劳状态不跨开关保留）
        stackGenerator.reset()

        // HurtTime AutoBlock 状态复位
        hurtBlockTicksLeft = 0
        lastOwnHurtTime = 0
        hurtBlockAwaitHit = false
        hurtBlockReleasedThisTick = false
        if (hurtBlockKeyDown) stopHurtBlockKey()

        if (blinkAutoBlock) {
            BlinkUtils.unblink()
            blinked = false
        }

        if (autoF5) mc.gameSettings.thirdPersonView = 0

        stopBlocking(true)

        synchronized(swingFails) {
            swingFails.clear()
        }
    }

    val onRotationUpdate = handler<RotationUpdateEvent> {
        update()
    }

    fun update() {
        if (cancelRun || (noInventoryAttack && (mc.currentScreen is GuiContainer || System.currentTimeMillis() - containerOpen < noInventoryDelay))) return

        // Check if last target died
        lastTarget?.let {
            if (it.isDead || it.health <= 0) {
                EventManager.call(EntityKilledEvent(it))
                lastTarget = null
            }
        }

        // Update target
        updateTarget()

        // Record current target for kill detection
        target?.let { lastTarget = it }

        if (autoF5) {
            if (mc.gameSettings.thirdPersonView != 1 && target != null) {
                mc.gameSettings.thirdPersonView = 1
            }
        }
    }

    val onWorld = handler<WorldEvent> {
        attackTickTimes.clear()

        if (blinkAutoBlock && BlinkUtils.isBlinking) BlinkUtils.unblink()

        synchronized(swingFails) {
            swingFails.clear()
        }
    }

    /**
     * Tick event
     */
    val onTick = handler<GameTickEvent>(priority = 2) {
        val player = mc.thePlayer ?: return@handler

        // HurtTime 格挡清理：模式切换或本 tick 会提前中断的场景（界面打开/
        // 取消运行/容器占用等）先收挡补发 C07，避免服务端残留格挡状态
        if (hurtBlockKeyDown && (autoBlock != "HurtTime" || mc.currentScreen != null || cancelRun || shouldPrioritize() ||
                    (clickOnly && !mc.gameSettings.keyBindAttack.isKeyDown) ||
                    (noInventoryAttack && (mc.currentScreen is GuiContainer || System.currentTimeMillis() - containerOpen < noInventoryDelay))
                )) {
            stopHurtBlockKey()
            hurtBlockTicksLeft = 0
        }

        // 手持非剑但模块仍处于持挡状态（C08 已发）：必须补发 C07 释放，
        // 否则服务端格挡状态残留（直发包模式下 vanilla 不会代为收挡）
        if (blockStatus && player.heldItem?.item !is ItemSword) {
            stopBlocking(forceStop = true)
            return@handler
        }

        if (shouldPrioritize()) {
            target = null
            renderBlocking = false
            return@handler
        }

        if (clickOnly && !mc.gameSettings.keyBindAttack.isKeyDown) {
            clicks = 0
            return@handler
        }

        if (blockStatus && autoBlock == "Packet" && releaseAutoBlock && !ignoreTickRule) {
            clicks = 0
            stopBlocking()
            return@handler
        }

        if (cancelRun) {
            target = null
            hittable = false
            stopBlocking()
            return@handler
        }

        if (noInventoryAttack && (mc.currentScreen is GuiContainer || System.currentTimeMillis() - containerOpen < noInventoryDelay)) {
            target = null
            hittable = false
            if (mc.currentScreen is GuiContainer) containerOpen = System.currentTimeMillis()
            return@handler
        }

        if (simulateCooldown && getAttackCooldownProgress() < 1f) {
            return@handler
        }

        // BlockOnNoHit: only hold the block while there is nothing to attack,
        // release it as soon as we are attacking so hits are not interfered
        if (autoBlock == "BlockOnNoHit") {
            if (target == null) {
                // Only block while an enemy is inside BlockOnNoHitRange
                if (hasEnemyInBlockOnNoHitRange()) {
                    if (!blockStatus && player.heldItem?.item is ItemSword) {
                        startBlocking(player, false)
                    }
                } else if (blockStatus) {
                    stopBlocking(true)
                }

                return@handler
            }

            if (blockStatus) {
                stopBlocking(true)
            }
        }

        // HurtTime AutoBlock：hurtTime 回落到阈值下且目标仍在 stopblock 范围内时
        // 起格挡，按 HurtTimeBlockTicks 随机时长维持后松开（见 hurtTimeBlockTick）
        if (autoBlock == "HurtTime") {
            hurtTimeBlockTick(player)

            // 格挡期间暂停攻击：清空本 tick 积累的点击，松挡后恢复输出
            if (hurtBlockKeyDown) {
                clicks = 0
                return@handler
            }

            // 刚松挡的 tick 同样禁止攻击：C07 已在本 tick 直发，留出一整 tick
            // 间隔再恢复攻击，模拟真人「松挡 → 收招 → 出刀」的节奏，避免
            // C07 → C02 同 tick 贴脸连发被 multiaction / post 类反作弊命中
            if (hurtBlockReleasedThisTick) {
                hurtBlockReleasedThisTick = false
                clicks = 0
                return@handler
            }
        }

        if (target == null && !blockStopInDead) {
            blockStopInDead = true
            stopBlocking()
            return@handler
        }

        if (blinkAutoBlock) {
            when (player.ticksExisted % (blinkBlockTicks + 1)) {
                0 -> {
                    if (blockStatus && !blinked && !BlinkUtils.isBlinking) {
                        blinked = true
                    }
                }

                1 -> {
                    if (blockStatus && blinked && BlinkUtils.isBlinking) {
                        stopBlocking()
                    }
                }

                blinkBlockTicks -> {
                    if (!blockStatus && blinked && BlinkUtils.isBlinking) {
                        BlinkUtils.unblink()
                        blinked = false

                        startBlocking(target!!, interactAutoBlock, autoBlock == "Fake") // block again
                    }
                }
            }
        }

        if (target != null) {
            if (player.getDistanceToEntityBox(target!!) > blockMaxRange && blockStatus) {
                stopBlocking(true)
                return@handler
            } else {
                if (autoBlock != "Off" && !releaseAutoBlock) {
                    renderBlocking = true
                }
            }

            // Usually when you butterfly click, you end up clicking two (and possibly more) times in a single tick.
            // Sometimes you also do not click. The positives outweigh the negatives, however.
            val extraClicks = if (simulateDoubleClicking && !simulateCooldown) nextInt(-1, 1) else 0

            // Generate clicks based on distance from us to target.
            val generatedClicks = if (generateClicksBasedOnDist) {
                val distance = player.getDistanceToEntityBox(target!!)
                ((distance / distanceFactor.random()) * cpsMultiplier.random()).roundToInt()
            } else 0

            var maxClicks = clicks + extraClicks + generatedClicks

            val prevHittable = hittable

            updateHittable()

            if (!prevHittable && hittable && maxClicks == 0 && forceFirstHit) {
                maxClicks++
            }

            repeat(maxClicks) {
                val wasBlocking = blockStatus

                runAttack(it == 0, it + 1 == maxClicks)
                clicks--

                if (wasBlocking && !blockStatus && (releaseAutoBlock && !ignoreTickRule || autoBlock == "Off")) {
                    return@handler
                }
            }
        } else {
            renderBlocking = false
        }
    }

    /**
     * Render event
     */
    val onRender3D = handler<Render3DEvent> {
        handleFailedSwings()

        drawAimPointBox()

        // 攻击范围显示：独立于目标，只要开启就在玩家脚下画范围圈
        if (rangeCircleValue.get()) {
            drawRangeCircle()
        }

        if (cancelRun) {
            target = null
            hittable = false
            return@handler
        }

        if (noInventoryAttack && (mc.currentScreen is GuiContainer || System.currentTimeMillis() - containerOpen < noInventoryDelay)) {
            target = null
            hittable = false
            if (mc.currentScreen is GuiContainer) containerOpen = System.currentTimeMillis()
            return@handler
        }

        target ?: return@handler

        if (attackTimer.hasTimePassed(attackDelay)) {
            if (cps.last > 0) clicks++
            attackTimer.reset()

            attackDelay = nextAttackDelay()
        }

        val hittableColor = if (hittable) Color(37, 126, 255, 70) else Color(255, 0, 0, 70)

        if (targetMode != "Multi") {
            when (mark.lowercase()) {
                "none" -> return@handler
                "platform" -> drawPlatform(target!!, hittableColor)
                "box" -> drawEntityBox(target!!, hittableColor, boxOutline)
                "circle" -> drawCircle(
                    target!!,
                    duration * 1000F,
                    heightRange.takeIf { animateHeight } ?: heightRange.endInclusive..heightRange.endInclusive,
                    extraWidth,
                    fillInnerCircle,
                    withHeight,
                    circleYRange.takeIf { animateCircleY },
                    circleStartColor.rgb,
                    circleEndColor.rgb
                )
            }
        }
    }

    /**
     * Attack enemy
     */
    private fun runAttack(isFirstClick: Boolean, isLastClick: Boolean) {
        val currentTarget = this.target ?: return

        val player = mc.thePlayer ?: return
        val world = mc.theWorld ?: return

        if (noConsumeAttack == "NoHits" && isConsumingItem()) {
            return
        }

        // Settings
        val multi = targetMode == "Multi"
        val manipulateInventory = simulateClosingInventory && !noInventoryAttack && serverOpenInventory

        if (hittable && currentTarget.hurtTime > hurtTime) {
            return
        }

        // Check if enemy is not hittable
        if (!hittable && options.rotationsActive) {
            if (swing && failSwing) {
                val rotation = currentRotation ?: player.rotation

                // Can humans keep click consistency when performing massive rotation changes?
                // (10-30 rotation difference/doing large mouse movements for example)
                // Maybe apply to attacks too?
                if (rotationDifference(rotation) > maxRotationDifferenceToSwing) {
                    // At the same time there is also a chance of the user clicking at least once in a while
                    // when the consistency has dropped a lot.
                    val shouldIgnore = swingWhenTicksLate.isActive() && ticksSinceClick() >= ticksLateToSwing

                    if (!shouldIgnore) {
                        return
                    }
                }

                runWithModifiedRaycastResult(rotation, range.toDouble(), throughWallsRange.toDouble()) {
                    if (swingOnlyInAir && !it.typeOfHit.isMiss) {
                        return@runWithModifiedRaycastResult
                    }

                    // Left click miss cool-down logic:
                    // When you click and miss, you receive a 10 tick cool down.
                    // It decreases gradually (tick by tick) when you hold the button.
                    // If you click and then release the button, the cool down drops from where it was immediately to 0.
                    // Most humans will release the button 1-2 ticks max after clicking, leaving them with an average of 10 CPS.
                    // The maximum CPS allowed when you miss a hit is 20 CPS, if you click and release immediately, which is highly unlikely.
                    // With that being said, we force an average of 10 CPS by doing this below, since 10 CPS when missing is possible.
                    if (respectMissCooldown && ticksSinceClick() <= 1 && it.typeOfHit.isMiss) {
                        return@runWithModifiedRaycastResult
                    }

                    val shouldEnterBlockBreakProgress =
                        !shouldDelayClick(it.typeOfHit) || attackTickTimes.lastOrNull()?.first?.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK

                    if (shouldEnterBlockBreakProgress) {
                        // Close inventory when open
                        if (manipulateInventory && isFirstClick) serverOpenInventory = false
                    }

                    val prevCooldown = mc.leftClickCounter

                    // Is any GUI coming from our client?
                    val isAnyClientGuiActive = mc.currentScreen?.javaClass?.`package`?.name?.contains(
                        AirPlus.CLIENT_NAME, ignoreCase = true
                    ) == true

                    if (isAnyClientGuiActive) {
                        mc.leftClickCounter = 0
                    }

                    if (!shouldDelayClick(it.typeOfHit)) {
                        attackTickTimes += it to runTimeTicks

                        if (it.typeOfHit.isEntity) {
                            val entity = it.entityHit

                            // Use own function instead of clickMouse() to maintain keep sprint, auto block, etc
                            if (entity is EntityLivingBase && isSelected(entity, true)) {
                                attackEntity(entity, isLastClick)
                            } else attackTickTimes -= it to runTimeTicks
                        } else {
                            // Imitate game click
                            mc.clickMouse()

                            if (renderBoxOnSwingFail) {
                                synchronized(swingFails) {
                                    val centerDistance = (currentTarget.hitBox.center - player.eyes).lengthVector()
                                    val spot = player.eyes + getVectorForRotation(rotation) * centerDistance

                                    swingFails += SwingFailData(spot, System.currentTimeMillis())
                                }
                            }
                        }
                    }

                    if (shouldEnterBlockBreakProgress && isLastClick) {
                        /**
                         * This is used to update the block breaking progress, resulting in sending an animation packet.
                         *
                         * Setting this function's parameter to [false] would still obey vanilla clicking logic,
                         * but only if you were releasing the click button immediately after pressing. Does not seem legit
                         * in the long term, right? This is why we are going to set it to [true], so it can send the animation packet.
                         */
                        mc.sendClickBlockToController(true)
                        /**
                         * Since we want to simulate proper clicking behavior, we schedule the block break progress stop
                         * in the next tick, since that is a doable action by the average player.
                         */
                        nextTick {
                            mc.sendClickBlockToController(false)

                            // Swings are sent a tick after stopping the block break progress.
                            clicks = 0

                            // [manipulateInventory] could have been changed at that point, but it is okay because
                            // serverOpenInventory's backing fields check for same values.
                            if (manipulateInventory) serverOpenInventory = true
                        }
                    }

                    if (isAnyClientGuiActive) {
                        mc.leftClickCounter = prevCooldown
                    }
                }
            }

            return
        }

        // Close inventory when open
        if (manipulateInventory && isFirstClick) serverOpenInventory = false

        blockStopInDead = false

        if (!multi) {
            attackEntity(currentTarget, isLastClick)
        } else {
            var targets = 0

            for (entity in world.loadedEntityList) {
                val distance = player.getDistanceToEntityBox(entity)

                if (entity is EntityLivingBase && isSelected(entity, true) && distance <= getRange(entity)) {
                    attackEntity(entity, isLastClick)

                    targets += 1

                    if (limitedMultiTargets != 0 && limitedMultiTargets <= targets) break
                }
            }
        }

        if (!isLastClick) return

        val switchMode = targetMode == "Switch"

        if (!switchMode || switchTimer.hasTimePassed(switchDelay)) {
            prevTargetEntities += currentTarget.entityId

            if (switchMode) {
                switchTimer.reset()
            }
        }

        // Open inventory
        if (manipulateInventory) serverOpenInventory = true
    }

    /**
     * Update current target
     */
    private fun updateTarget() {
        if (shouldPrioritize()) return

        // Reset fixed target to null
        target = null
        // 目标本 tick 起算清空：若后续未能重新选定目标并走到 updateRotations 的
        // Noise3Context 组装，残留的过期上下文会在视角重置路径里
        // 把 serverRotation 拉向已失效的瞄准点（审查 B1）
        options.noise3Context = null

        val switchMode = targetMode == "Switch"

        val theWorld = mc.theWorld ?: return
        val thePlayer = mc.thePlayer ?: return

        var bestTarget: EntityLivingBase? = null
        var bestValue: Double? = null

        for (entity in theWorld.loadedEntityList) {
            if (entity !is EntityLivingBase || !isSelected(
                    entity, true
                ) || switchMode && entity.entityId in prevTargetEntities
            ) continue

            val distance = Backtrack.runWithNearestTrackedDistance(entity) { thePlayer.getDistanceToEntityBox(entity) }

            if (switchMode && distance > range && prevTargetEntities.isNotEmpty()) continue

            val entityFov = rotationDifference(entity)

            if (distance > maxRange || fov != 180F && entityFov > fov) continue

            if (switchMode && !isLookingOnEntities(entity, maxSwitchFOV.toDouble())) continue

            val currentValue = when (priority.lowercase()) {
                "distance" -> distance
                "direction" -> entityFov.toDouble()
                "health" -> entity.health.toDouble()
                "livingtime" -> -entity.ticksExisted.toDouble()
                "armor" -> entity.totalArmorValue.toDouble()
                "hurtresistance" -> entity.hurtResistantTime.toDouble()
                "hurttime" -> entity.hurtTime.toDouble()
                "healthabsorption" -> (entity.health + entity.absorptionAmount).toDouble()
                "regenamplifier" -> if (entity.isPotionActive(Potion.regeneration)) {
                    entity.getActivePotionEffect(Potion.regeneration).amplifier.toDouble()
                } else -1.0

                "inweb" -> if (entity.isInWeb) -1.0 else Double.MAX_VALUE
                "onladder" -> if (entity.isOnLadder) -1.0 else Double.MAX_VALUE
                "inliquid" -> if (entity.isInWater || entity.isInLava) -1.0 else Double.MAX_VALUE
                else -> null
            } ?: continue

            if (bestValue == null || currentValue < bestValue) {
                bestValue = currentValue
                bestTarget = entity
            }
        }

        if (bestTarget != null) {
            if (Backtrack.runWithNearestTrackedDistance(bestTarget) { updateRotations(bestTarget) }) {
                target = bestTarget
                return
            }
        }

        if (prevTargetEntities.isNotEmpty()) {
            prevTargetEntities.clear()
            updateTarget()
        }
    }

    /**
     * Check if there is any enemy (player) inside BlockOnNoHitRange
     */
    private fun hasEnemyInBlockOnNoHitRange(): Boolean {
        val player = mc.thePlayer ?: return false
        val world = mc.theWorld ?: return false

        return world.loadedEntityList.any {
            it is EntityPlayer && it != player && !it.isDead &&
                    player.getDistanceToEntity(it) <= blockOnNoHitRange &&
                    !(Teams.handleEvents() && Teams.isInYourTeam(it))
        }
    }

    /**
     * HurtTime 模式起挡：直发 C08PacketPlayerBlockPlacement（use-item 形式）。
     * 不模拟右键 —— 准星压在目标上时 vanilla rightClickMouse 发的是实体
     * interact 包（C08PacketUseEntity），既不真正起格挡，也污染攻击包序。
     */
    private fun startHurtBlockKey() {
        val target = this.target ?: return

        hurtBlockKeyDown = true
        startBlocking(target, interact = false)
    }

    /**
     * HurtTime 模式松挡：直发 C07 RELEASE_USE_ITEM（blockStatus 为真才发包，
     * 与起挡 C08 一一对应），并标记本 tick 释放、禁止同 tick 攻击
     */
    private fun stopHurtBlockKey() {
        if (hurtBlockKeyDown) hurtBlockReleasedThisTick = true
        hurtBlockKeyDown = false
        stopBlocking(forceStop = true)
    }

    /**
     * HurtTime 模式方向检查：目标视线指向与「目标 → 自己 hitbox 中心」方向的夹角
     * 超过 [hurtTimeDirectionDiff]（对方根本没看向自己）时不格挡。
     */
    private fun hurtBlockDirectionOk(player: EntityPlayer): Boolean {
        val t = target ?: return false
        val rotationToPlayer = toRotation(player.hitBox.center, true, t)
        return rotationDifference(rotationToPlayer, t.rotation) <= hurtTimeDirectionDiff
    }

    /**
     * HurtTime AutoBlock 状态机（每 tick 调用，autoBlock == "HurtTime" 时生效）：
     *
     * - 刚挨打（own hurtTime 上升）：立即松开既有格挡 —— 受击期间原版不给格挡
     *   减伤，继续白挡只会拖累收招/反打；
     * - hurtTime 回落到 [blockOwnHurtTime] 以下且目标仍在 [blockMaxRange]
     *   （stopblock 范围）内、视线未偏离自己（[hurtBlockDirectionOk]）→ 起格挡
     *   （直发 C08 placement），持续 [hurtTimeBlockTicksValue] 随机取值（默认 2..4 tick）后松开；
     * - 持续窗口内补挡：若窗口还没走完就因受击/异常被取消，重新起挡补上，
     *   让「保持 block 的时长」始终接近设定区间（startBlocking 对已持挡状态幂等）；
     * - 松开后等待下一次挨打（hurtTime 重新抬升）再进入新一轮，避免 hurtTime
     *   一直低于阈值时无限反复 block。
     */
    private fun hurtTimeBlockTick(player: EntityPlayer) {
        if (player.heldItem?.item !is ItemSword) {
            if (hurtBlockKeyDown) stopHurtBlockKey()

            hurtBlockTicksLeft = 0
            lastOwnHurtTime = player.hurtTime
            hurtBlockAwaitHit = false
            return
        }

        // 挨打：own hurtTime 被重新拉起 → 松开格挡，等待下次回落再起挡
        if (player.hurtTime > lastOwnHurtTime) {
            hurtBlockTicksLeft = 0
            hurtBlockAwaitHit = false
            if (hurtBlockKeyDown) stopHurtBlockKey()
        }
        lastOwnHurtTime = player.hurtTime

        // 格挡保持窗口：还没到点就持挡；目标离开 stopblock 范围 / 视线偏离自己则提前收起
        if (hurtBlockTicksLeft > 0) {
            if (target == null || player.getDistanceToEntityBox(target!!) > blockMaxRange || !hurtBlockDirectionOk(player)) {
                if (hurtBlockKeyDown) stopHurtBlockKey()
                hurtBlockTicksLeft = 0
                return
            }

            hurtBlockTicksLeft--
            if (hurtBlockTicksLeft <= 0) {
                stopHurtBlockKey()
                // 窗口自然走完：等下一次挨打（hurtTime 重新抬升）再进入新一轮，
                // 否则 hurtTime 一直低于阈值时会无限反复 block
                hurtBlockAwaitHit = true
            } else {
                // 窗口内被打断（受击取消格挡/异常收挡等）→ 重新起挡补上
                startHurtBlockKey()
            }
            return
        }

        // 仍在持挡但 hurtTime ≥ 阈值（受击恢复期）→ 松开，保持节奏
        if (hurtBlockKeyDown && player.hurtTime >= blockOwnHurtTime) {
            stopHurtBlockKey()
        }

        // hurtTime 回落到阈值以下、窗口未被跳过（等挨打）、目标在 stopblock 范围内
        // 且视线未偏离自己 → 新一轮格挡，持续时长按 HurtTimeBlockTicks 区间随机取值
        if (!hurtBlockKeyDown && !hurtBlockAwaitHit && player.hurtTime < blockOwnHurtTime && target != null) {
            if (player.getDistanceToEntityBox(target!!) <= blockMaxRange && hurtBlockDirectionOk(player)) {
                hurtBlockTicksLeft = hurtTimeBlockTicksValue.get().random()
                startHurtBlockKey()
            }
        }
    }

    /**
     * Attack [entity]
     */
    private fun attackEntity(entity: EntityLivingBase, isLastClick: Boolean) {
        val thePlayer = mc.thePlayer

        if (shouldPrioritize()) return

        if (thePlayer.isBlocking && (autoBlock == "Off" && blockStatus || autoBlock == "Packet" && releaseAutoBlock)) {
            stopBlocking()

            if (!ignoreTickRule || autoBlock == "Off") {
                return
            }
        } else if (autoBlock == "BlockOnNoHit" && blockStatus) {
            // BlockOnNoHit only holds the block when there is nothing to attack,
            // release it right before attacking without skipping the attack itself
            stopBlocking(true)
        }

        // The function is only called when we are facing an entity
        if (shouldDelayClick(MovingObjectPosition.MovingObjectType.ENTITY)) {
            return
        }

        if (!blinkAutoBlock || !BlinkUtils.isBlinking) {
            val affectSprint = false.takeIf { KeepSprint.handleEvents() || keepSprint }

            thePlayer.attackEntityWithModifiedSprint(entity, affectSprint) { if (swing) thePlayer.swingItem() }

            // Apply enchantment critical effect if FakeSharp is enabled
            if (EnchantmentHelper.getModifierForCreature(
                    thePlayer.heldItem, entity.creatureAttribute
                ) <= 0F && fakeSharp
            ) {
                thePlayer.onEnchantmentCritical(entity)
            }
        }

        // Start blocking after attack
        // HurtTime 模式由 hurtTimeBlockTick 状态机管理格挡节奏（受击后回落才起挡），
        // 攻击后不直接起挡，否则会绕过 hurtTime/blockWindows 的节拍
        if (autoBlock !in arrayOf("Off", "BlockOnNoHit", "HurtTime") && (thePlayer.isBlocking || canBlock) && (!blinkAutoBlock && isLastClick || blinkAutoBlock && (!blinked || !BlinkUtils.isBlinking))) {
            startBlocking(entity, interactAutoBlock, autoBlock == "Fake")
        }

        resetLastAttackedTicks()
    }

    /**
     * Update rotations to enemy
     */
    private fun updateRotations(entity: Entity): Boolean {
        val player = mc.thePlayer ?: return false

        if (shouldPrioritize()) return false

        if (!options.rotationsActive) {
            return player.getDistanceToEntityBox(entity) <= range
        }

        // AI 瞄准激活判定：RotationMode 为 Template / Neural 时 AI 引擎接管转换。
        // 后续两处联动 —— ① AI 瞄准点稳定（随机化限流跳点）② 瞄准点改进（AiAimSpot 总开关）。
        val aiAimActive = options.aimMode == "Template" || options.aimMode == "Neural"

        // 瞄准点改进（AiAimSpot，仅 Template / Neural 下可见生效）：① 预判下限 ② 远距收敛上半身。
        // Default 模式走秒响应管线，保持用户原始的 PredictEnemyPosition / bodyPoints 配置。
        val aiAimSpotActive = aiAimActive && aiAimSpot

        // 预判（追踪数据联动）：瞄准点基于对方动向偏移到未来位置。
        // prediction = (currPos - prevPos) × (2 + predictEnemyPosition)。
        // AI 瞄准点改进开启时 enforce 最小预判（AiMinPrediction）：AI 逐 tick
        // 平滑收差到达需要数 tick，不领先目标移动轴心就会一直打在身后；
        // 纯 Default 秒响应管线保持用户原值。
        // Simple 模式隐藏预判设置且不生效：外推用 1 tick 前的旧速度 × 3.5，
        // 目标变向 / 被击退时瞄准点会甩到可见 hitbox 之外的空地上（旧速度
        // 指向的方向），表现为「瞄准奇怪的地方、不实时」—— Simple 直接瞄准
        // 当前可见 hitbox，不做速度外推。
        val enemyPredict = when {
            aiAimSpotActive -> predictEnemyPosition.coerceAtLeast(aiMinPrediction)
            else -> predictEnemyPosition
        }
        val prediction = if (isSimpleMode) {
            Vec3(0.0, 0.0, 0.0)
        } else {
            entity.currPos.subtract(entity.prevPos).times(2 + enemyPredict.toDouble())
        }

        val boundingBox = entity.hitBox.offset(prediction)

        // 距离化选点：远距离时躯干点视角内变化率低但被身体遮挡的概率高，头部是最稳定
        // 的命中目标 —— 距离 >= AiFarSpotDistance 时把竖直选点区间收敛到上半身
        // （与 0.5..0.9 即旧 Head..Body 档取交集，交集为空则回退该区间）；近距保持
        // 用户配置的 VerticalBodySearchRange。
        // 滞回（AiFarSpotDistance 收敛 / AiFarSpotRecoverDistance 恢复）：目标在阈值
        // 附近移动时避免每 tick 反复切换搜索范围。
        val targetDist = player.getDistanceToEntityBox(entity)
        if (aiAimSpotActive) {
            if (aiFarSpot) {
                // 恢复距离强制低于收敛起始距离，保证滞回区间存在
                if (targetDist < aiFarSpotRecoverDistance.coerceAtMost(aiFarSpotDistance - 0.1f)) aiFarSpot = false
            } else if (targetDist >= aiFarSpotDistance) {
                aiFarSpot = true
            }
        } else {
            aiFarSpot = false
        }
        val effectiveVerticalRange = if (aiFarSpot) {
            val lo = max(verticalBodySearchRange.start, 0.5f)
            val hi = min(verticalBodySearchRange.endInclusive, 0.9f)
            if (lo <= hi) lo..hi else 0.5f..0.9f
        } else {
            verticalBodySearchRange
        }
        val (currPos, oldPos) = player.currPos to player.prevPos

        // 客户端移动预判（PredictClientMovement）：Simple 模式隐藏该设置且不生效 ——
        // 预判会把眼睛移到未来位置，最近点瞄准便不再追踪可见 hitbox（瞄准点
        // 跟着外推位置漂移，表现为瞄准奇怪的地方 / 不实时），故 Simple 跳过模拟。
        if (!isSimpleMode) {
            val selfPredictTicks = predictClientMovement
            val usePredictOnlyWhenOutOfRange = predictOnlyWhenOutOfRange

            val simPlayer = SimulatedPlayer.fromClientPlayer(RotationUtils.modifiedInput)

            simPlayer.rotationYaw = (currentRotation ?: player.rotation).yaw

            var pos = currPos

            repeat(selfPredictTicks) {
                val previousPos = simPlayer.pos

                simPlayer.tick()

                if (usePredictOnlyWhenOutOfRange) {
                    player.setPosAndPrevPos(simPlayer.pos)

                    val currDist = player.getDistanceToEntityBox(entity)

                    player.setPosAndPrevPos(previousPos)

                    val prevDist = player.getDistanceToEntityBox(entity)

                    player.setPosAndPrevPos(currPos, oldPos)
                    pos = simPlayer.pos

                    if (currDist <= range && currDist <= prevDist) {
                        return@repeat
                    }
                }

                pos = previousPos
            }

            player.setPosAndPrevPos(pos)
        }

        // 瞄准点改进 ③ 追踪禁跳（v5.1）：随机化跳点的语义是「把瞄准点甩到 hitbox
        // 另一处」，准星已经压在目标上（AimScene.TRACK）时跳点会把 5~10° 的差重新
        // 注入，刚建立的追踪状态被强制打回成一次新的 FLICK 定位 —— 表现为追踪中
        // 突然重新甩头。因此 AI 模式下「在身上」不跳点；亚度级的注视点微游走由
        // RotationUtils 的 wander 提供，跟踪continuity不受影响。不在身上时维持
        // AiSpotHopInterval 冷却限流的原节奏。
        val aiSpotHopAllowed = if (aiAimActive && randomization.randomizationChosen) {
            val currentRot = currentRotation
            currentRot == null || !AimScene.crosshairOnEntity(currentRot.yaw, currentRot.pitch)
        } else true

        // 按转头模式决定本 tick 的目标旋转：
        // - Simple：当前 hitbox 上与当前视角夹角最小的点（最近点瞄准），固定
        //   RotationSpeed，不做任何预判外推（预判设置在 Simple 下隐藏且不生效）。
        // - Noise3：主搜索结果作为瞄准点，另取一份无预判位置的瞄准点组成双点上下文，
        //   由 C40N 规划器在 performAngleChange 里决定每 tick 实际应用的旋转。
        // - 其余（Legacy / Template / Neural）：原有 searchCenter 管线。
        val rotation = when (options.aimMode) {
            "Simple" -> {
                if (targetDist > range + scanRange) {
                    player.setPosAndPrevPos(currPos, oldPos)
                    return false
                }
                nearestPointRotation(entity)
            }
            "Noise3" -> {
                val main = searchCenter(
                    boundingBox,
                    generateSpotBasedOnDistance,
                    outBorder && !attackTimer.hasTimePassed(attackDelay / 2),
                    if (aiSpotHopAllowed) randomization else null,
                    predict = false,
                    lookRange = range + scanRange,
                    attackRange = range,
                    throughWallsRange = throughWallsRange,
                    verticalRange = effectiveVerticalRange,
                    horizontalSearch = horizontalBodySearchRange,
                    aiStableSpot = aiAimActive,
                )

                if (main != null) {
                    // 直接选点：直接使用主搜索结果（带预判）；预判平滑：与无预判位置的
                    // 瞄准点取中值平滑。无预判份的搜索不挂随机化，避免跳点状态被双重推进。
                    val aim1: Rotation
                    val aim2: Rotation
                    if (n3PointSelection == "预判平滑") {
                        aim2 = main
                        aim1 = searchCenter(
                            entity.hitBox,
                            generateSpotBasedOnDistance,
                            false,
                            null,
                            predict = false,
                            lookRange = range + scanRange,
                            attackRange = range,
                            throughWallsRange = throughWallsRange,
                            verticalRange = effectiveVerticalRange,
                            horizontalSearch = horizontalBodySearchRange,
                            aiStableSpot = aiAimActive,
                        ) ?: main
                    } else {
                        aim1 = main
                        aim2 = main
                    }

                    options.noise3Context = Noise3Context(
                        aim1, aim2, entity.entityId, entity.rotationYaw, entity.rotationPitch,
                        Noise3Params(
                            n3PointSelection, n3AimSpeed, n3DecelPre, n3DecelPost, n3DecelPostHighspeed,
                            n3HighspeedThreshold, n3SpeedAtLargeRot, n3SpeedAtInitialRot, n3LargeRotThreshold,
                            n3ResyncChance, n3ResyncUpdateDelay, n3RemoveNoiseChance, n3NoNoiseRecalcDelay,
                            n3ResyncDelayMs, n3ResyncRateMin, n3ResyncRateMax,
                        )
                    )
                }

                main
            }
            else -> searchCenter(
                boundingBox,
                generateSpotBasedOnDistance,
                outBorder && !attackTimer.hasTimePassed(attackDelay / 2),
                if (aiSpotHopAllowed) randomization else null,
                predict = false,
                lookRange = range + scanRange,
                attackRange = range,
                throughWallsRange = throughWallsRange,
                verticalRange = effectiveVerticalRange,
                horizontalSearch = horizontalBodySearchRange,
                aiStableSpot = aiAimActive,
            )
        }

        if (rotation == null) {
            player.setPosAndPrevPos(currPos, oldPos)

            return false
        }

        setTargetRotation(rotation, options = options)

        player.setPosAndPrevPos(currPos, oldPos)

        return true
    }

    /**
     * Simple 模式的最优瞄准点：取玩家眼睛到目标 hitbox 的欧氏最近点
     * （点到 AABB 的标准解析解：逐轴把眼睛坐标 clamp 进箱子范围），
     * 即「玩家可攻击到的最近的瞄准点」—— 按空间最短距离选点，而非最短
     * 旋转角度。眼睛已在箱内时最近点即眼睛本身、方向未定义，沿当前视线
     * 即可（角度差为零）。不做预判外推，瞄准点始终跟随可见 hitbox 实时刷新。
     */
    private fun nearestPointRotation(entity: Entity): Rotation? {
        val player = mc.thePlayer ?: return null

        val eyes = player.eyes
        val look = getVectorForRotation(currentRotation ?: player.rotation)
        val box = entity.hitBox

        // 眼睛已在箱内：沿当前视线，角度差为零
        if (box.isVecInside(eyes)) {
            return toRotation(eyes.add(Vec3(look.xCoord * 4.0, look.yCoord * 4.0, look.zCoord * 4.0)), false)
                .fixedSensitivity()
        }

        // 眼睛到 AABB 的最近点（clamp 截断）
        val closest = Vec3(
            eyes.xCoord.coerceIn(box.minX, box.maxX),
            eyes.yCoord.coerceIn(box.minY, box.maxY),
            eyes.zCoord.coerceIn(box.minZ, box.maxZ),
        )

        return toRotation(closest, false).fixedSensitivity()
    }

    private fun ticksSinceClick() = runTimeTicks - (attackTickTimes.lastOrNull()?.second ?: 0)

    /**
     * Check if enemy is hittable with current rotations
     */
    private fun updateHittable() {
        val eyes = mc.thePlayer.eyes

        val currentRotation = currentRotation ?: mc.thePlayer.rotation
        val target = this.target ?: return

        if (shouldPrioritize()) return

        if (!options.rotationsActive) {
            hittable = mc.thePlayer.getDistanceToEntityBox(target) <= range
            return
        }

        var chosenEntity: Entity? = null

        if (raycast) {
            chosenEntity = raycastEntity(
                range.toDouble(), currentRotation.yaw, currentRotation.pitch
            ) { entity -> !livingRaycast || entity is EntityLivingBase && entity !is EntityArmorStand }

            if (chosenEntity != null && chosenEntity is EntityLivingBase && (NoFriends.handleEvents() || !(chosenEntity is EntityPlayer && chosenEntity.isClientFriend()))) {
                if (raycastIgnored && target != chosenEntity) {
                    this.target = chosenEntity
                }
            }

            hittable = this.target == chosenEntity
        } else {
            hittable = isRotationFaced(target, range.toDouble(), currentRotation)
        }

        var shouldExcept = false

        if (!hittable || shouldExcept) {
            return
        }

        val targetToCheck = chosenEntity ?: this.target ?: return

        // If player is inside entity, automatic yes because the intercept below cannot check for that
        // Minecraft does the same, see #EntityRenderer line 353
        if (targetToCheck.hitBox.isVecInside(eyes)) {
            return
        }

        var checkNormally = true

        if (Backtrack.handleEvents()) {
            Backtrack.loopThroughBacktrackData(targetToCheck) {
                var result = false

                checkIfAimingAtBox(targetToCheck, currentRotation, eyes, onSuccess = {
                    checkNormally = false

                    result = true
                }, onFail = {
                    result = false
                })

                return@loopThroughBacktrackData result
            }
        }

        if (!checkNormally) {
            return
        }

        // Recreate raycast logic
        val intercept = targetToCheck.hitBox.calculateIntercept(
            eyes, eyes + getVectorForRotation(currentRotation) * range.toDouble()
        )

        // Is the entity box raycast vector visible? If not, check through-wall range
        hittable =
            isVisible(intercept.hitVec) || mc.thePlayer.getDistanceToEntityBox(targetToCheck) <= throughWallsRange
    }

    /**
     * Start blocking
     */
    private fun startBlocking(interactEntity: Entity, interact: Boolean, fake: Boolean = false) {
        val player = mc.thePlayer ?: return

        if (blockStatus && (!uncpAutoBlock || !blinkAutoBlock) || shouldPrioritize()) return

        if (mc.thePlayer.isBlocking) {
            blockStatus = true
            renderBlocking = true
            return
        }

        if (unblockMode == "Empty" && player.inventory.firstEmptyStack !in 0..8) {
            return
        }

        if (!fake) {
            if (!(blockRate > 0 && nextInt(endExclusive = 100) <= blockRate)) return

            if (interact) {
                val positionEye = player.eyes

                val boundingBox = interactEntity.hitBox

                val (yaw, pitch) = currentRotation ?: player.rotation

                val vec = getVectorForRotation(Rotation(yaw, pitch))

                val lookAt = positionEye.add(vec * maxRange.toDouble())

                val movingObject = boundingBox.calculateIntercept(positionEye, lookAt) ?: return
                val hitVec = movingObject.hitVec

                sendPackets(
                    C02PacketUseEntity(interactEntity, hitVec - interactEntity.positionVector),
                    C02PacketUseEntity(interactEntity, INTERACT)
                )

            }

            if (switchStartBlock) {
                switchToSlot((SilentHotbar.currentSlot + 1) % 9)
            }

            sendPacket(C08PacketPlayerBlockPlacement(player.heldItem))
            blockStatus = true
        }

        renderBlocking = true

        CPSCounter.registerClick(CPSCounter.MouseButton.RIGHT)
    }

    /**
     * Stop blocking
     */
    private fun stopBlocking(forceStop: Boolean = false) {
        val player = mc.thePlayer ?: return

        if (!forceStop) {
            if (blockStatus && !mc.thePlayer.isBlocking) {

                when (unblockMode.lowercase()) {
                    "stop" -> {
                        sendPacket(C07PacketPlayerDigging(RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN))
                    }

                    "switch" -> {
                        switchToSlot((SilentHotbar.currentSlot + 1) % 9)
                    }

                    "empty" -> {
                        player.inventory.firstEmptyStack.takeIf { it in 0..8 }.let {
                            if (it == null) {
                                sendPacket(C07PacketPlayerDigging(RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN))
                                return@let
                            }

                            switchToSlot(it)
                        }
                    }
                }

                blockStatus = false
            }
        } else {
            if (blockStatus) {
                sendPacket(C07PacketPlayerDigging(RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN))
            }

            blockStatus = false
        }

        renderBlocking = false
    }

    val onPacket = handler<PacketEvent> { event ->
        val player = mc.thePlayer ?: return@handler
        val packet = event.packet

        if (autoBlock == "Off" || !blinkAutoBlock || !blinked) return@handler

        if (player.isDead || player.ticksExisted < 20) {
            BlinkUtils.unblink()
            return@handler
        }

        if (Blink.blinkingSend() || Blink.blinkingReceive()) {
            BlinkUtils.unblink()
            return@handler
        }

        BlinkUtils.blink(packet, event)
    }

    /**
     * Checks if raycast landed on a different object
     *
     * The game requires at least 1 tick of cool-down on raycast object type change (miss, block, entity)
     * We are doing the same thing here but allow more cool-down.
     */
    private fun shouldDelayClick(currentType: MovingObjectPosition.MovingObjectType): Boolean {
        if (!useHitDelay) {
            return false
        }

        val lastAttack = attackTickTimes.lastOrNull()

        return lastAttack != null && lastAttack.first.typeOfHit != currentType && runTimeTicks - lastAttack.second <= hitDelayTicks
    }

    private fun checkIfAimingAtBox(
        targetToCheck: Entity, currentRotation: Rotation, eyes: Vec3, onSuccess: () -> Unit,
        onFail: () -> Unit = { },
    ) {
        if (targetToCheck.hitBox.isVecInside(eyes)) {
            onSuccess()
            return
        }

        // Recreate raycast logic
        val intercept = targetToCheck.hitBox.calculateIntercept(
            eyes, eyes + getVectorForRotation(currentRotation) * range.toDouble()
        )

        if (intercept != null) {
            // Is the entity box raycast vector visible? If not, check through-wall range
            hittable =
                isVisible(intercept.hitVec) || mc.thePlayer.getDistanceToEntityBox(targetToCheck) <= throughWallsRange

            if (hittable) {
                onSuccess()
                return
            }
        }

        onFail()
    }

    private fun switchToSlot(slot: Int) {
        SilentHotbar.selectSlotSilently(this, slot, immediate = true)
        SilentHotbar.resetSlot(this, true)
    }

    private fun shouldPrioritize(): Boolean = when {
        !onScaffold && (Scaffold.handleEvents() && (Scaffold.placeRotation != null || currentRotation != null) || Tower.handleEvents() && Tower.isTowering) -> true

        !onDestroyBlock && (Fucker.handleEvents() && !Fucker.noHit && Fucker.pos != null && !Fucker.isOwnBed || Nuker.handleEvents()) -> true

        activationSlot && SilentHotbar.currentSlot != preferredSlot - 1 -> true

        else -> false
    }

    private fun handleFailedSwings() {
        if (!renderBoxOnSwingFail) return

        val box = AxisAlignedBB(0.0, 0.0, 0.0, 0.05, 0.05, 0.05)

        synchronized(swingFails) {
            val fadeSeconds = renderBoxFadeSeconds * 1000L
            val colorSettings = renderBoxColor

            val renderManager = mc.renderManager

            swingFails.removeAll {
                val timestamp = System.currentTimeMillis() - it.startTime
                val transparency = (0f..255f).lerpWith(1 - (timestamp / fadeSeconds).coerceAtMost(1.0F))

                val offsetBox = box.offset(it.vec3 - renderManager.renderPos)

                RenderUtils.drawAxisAlignedBB(offsetBox, colorSettings.color(a = transparency.roundToInt()))

                timestamp > fadeSeconds
            }
        }
    }

    private fun drawAimPointBox() {
        val player = mc.thePlayer ?: return
        val target = this.target ?: return

        if (!renderAimPointBox) {
            return
        }

        val f = aimPointBoxSize.toDouble()

        val box = AxisAlignedBB(0.0, 0.0, 0.0, f, f, f)

        val renderManager = mc.renderManager

        runWithSimulatedPosition(player, player.interpolatedPosition(player.prevPos)) {
            runWithSimulatedPosition(target, target.interpolatedPosition(target.prevPos)) {
                val rotationVec = player.eyes + getVectorForRotation(
                    serverRotation.lerpWith(currentRotation ?: player.rotation, mc.timer.renderPartialTicks)
                ) * player.getDistanceToEntityBox(target).coerceAtMost(range.toDouble())

                val offSetBox = box.offset(rotationVec - renderManager.renderPos)

                RenderUtils.drawAxisAlignedBB(offSetBox, aimPointBoxColor)
            }
        }
    }

    /**
     * 攻击范围显示：在 [range] 攻击范围边界绕玩家绘制一个圆（高度略抬离地面，
     * 避免与地形 z-fighting）。颜色/透明度走 [rangeCircleColorValue]，边数走
     * [rangeCircleSidesValue]。
     *
     * 性能考虑：每帧只有一个 LINE_LOOP，顶点数 = 边数（8..180）；三角函数查表
     * 缓存，只在边数变化时重算一次 —— 常态开销接近于零。
     */
    private fun drawRangeCircle() {
        val player = mc.thePlayer ?: return
        val radius = range
        if (radius <= 0f) return

        val sides = rangeCircleSidesValue.get().coerceIn(8, 180)
        val cosTable = rangeCircleCos(sides)
        val sinTable = rangeCircleSin(sides)

        val renderManager = mc.renderManager
        val pos = player.interpolatedPosition(player.prevPos)
        val cx = pos.xCoord - renderManager.renderPosX
        val cy = pos.yCoord + 0.3 - renderManager.renderPosY
        val cz = pos.zCoord - renderManager.renderPosZ

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS)
        GL11.glPushMatrix()

        GL11.glDisable(GL11.GL_TEXTURE_2D)
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glEnable(GL11.GL_LINE_SMOOTH)
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST)
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glDisable(GL11.GL_CULL_FACE)

        RenderUtils.glColor(rangeCircleColorValue.get())
        GL11.glLineWidth(1.5f)
        GL11.glBegin(GL11.GL_LINE_LOOP)
        for (i in 0 until sides) {
            GL11.glVertex3d(cx + cosTable[i] * radius, cy, cz + sinTable[i] * radius)
        }
        GL11.glEnd()

        GL11.glColor4f(1f, 1f, 1f, 1f)
        GL11.glDisable(GL11.GL_LINE_SMOOTH)
        GL11.glEnable(GL11.GL_CULL_FACE)
        GL11.glEnable(GL11.GL_DEPTH_TEST)
        GL11.glEnable(GL11.GL_TEXTURE_2D)
        GL11.glDisable(GL11.GL_BLEND)
        GL11.glPopMatrix()
        GL11.glPopAttrib()
    }

    /** 范围圈顶点 trig 查表缓存：只在边数变化时重算。 */
    private var rangeCircleSidesCache = 0
    private var rangeCircleCosCache = FloatArray(0)
    private var rangeCircleSinCache = FloatArray(0)

    private fun rangeCircleCos(sides: Int): FloatArray {
        if (sides != rangeCircleSidesCache) {
            rangeCircleSidesCache = sides
            val cos = FloatArray(sides)
            val sin = FloatArray(sides)
            for (i in 0 until sides) {
                val angle = Math.PI * 2.0 * i / sides
                cos[i] = Math.cos(angle).toFloat()
                sin[i] = Math.sin(angle).toFloat()
            }
            rangeCircleCosCache = cos
            rangeCircleSinCache = sin
        }
        return rangeCircleCosCache
    }

    private fun rangeCircleSin(sides: Int) = run {
        rangeCircleCos(sides)
        rangeCircleSinCache
    }

    /**
     * Check if run should be cancelled
     */
    private val cancelRun
        inline get() = mc.thePlayer.isSpectator || !isAlive(mc.thePlayer) || noConsumeAttack == "NoRotation" && isConsumingItem()

    /**
     * Check if [entity] is alive
     */
    private fun isAlive(entity: EntityLivingBase) = entity.isEntityAlive && entity.health > 0

    /**
     * Check if player is able to block
     */
    private val canBlock: Boolean
        get() {
            val player = mc.thePlayer ?: return false

            if (target != null && player.heldItem?.item is ItemSword) {
                if (smartAutoBlock) {
                    if (player.isMoving && forceBlock) return false

                    if (checkWeapon && target?.heldItem?.item !is ItemSword && target?.heldItem?.item !is ItemAxe) return false

                    if (player.hurtTime > maxOwnHurtTime) return false

                    val rotationToPlayer = toRotation(player.hitBox.center, true, target!!)

                    if (rotationDifference(rotationToPlayer, target!!.rotation) > maxDirectionDiff) return false

                    if (target!!.swingProgressInt > maxSwingProgress) return false

                    if (target!!.getDistanceToEntityBox(player) > blockRange) return false
                }

                if (player.getDistanceToEntityBox(target!!) > blockMaxRange) return false

                return true
            }

            return false
        }

    /**
     * Range
     */
    private val maxRange
        get() = max(range + scanRange, throughWallsRange)

    private fun getRange(entity: Entity) =
        (if (mc.thePlayer.getDistanceToEntityBox(entity) >= throughWallsRange) range + scanRange else throughWallsRange) - if (mc.thePlayer.isSprinting) rangeSprintReduction else 0F

    /**
     * HUD Tag
     */
    override val tag
        get() = targetMode

    val isBlockingChestAura
        get() = handleEvents() && target != null
}

data class SwingFailData(val vec3: Vec3, val startTime: Long)

