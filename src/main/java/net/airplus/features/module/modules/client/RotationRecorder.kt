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
import net.airplus.utils.extensions.hitBox
import net.airplus.utils.extensions.minus
import net.airplus.utils.extensions.random
import net.airplus.utils.extensions.renderPos
import net.airplus.utils.extensions.setPosAndPrevPos
import net.airplus.utils.render.RenderUtils.glColor
import net.airplus.utils.rotation.RotationUtils
import net.airplus.utils.rotation.aim.AimDatasetStore
import net.airplus.utils.rotation.aim.AimScene
import net.airplus.utils.rotation.aim.AimSegmenter
import net.airplus.utils.rotation.aim.TickSample
import net.airplus.utils.client.chat
import net.minecraft.client.entity.EntityOtherPlayerMP
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.util.Vec3
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import java.awt.Color
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin


object RotationRecorder : Module("RotationRecorder", Category.CLIENT, gameDetecting = false) {

    /**
     * 采集模式（choice）：
     * - Combat：仅战斗采集，录制单位 = 本次攻击 → 下一次攻击之间的段（[combatGapValue]
     *   内有下一次攻击才保留），过滤平时看风景的无关转动；
     * - AimBall：进入采集小游戏，在玩家周围随机生成一个小球（准星旁的箭头指示方向），
     *   把准星转入小球判定圈后点击（或持续瞄准一段时间）即完成一轮，小球换位。
     *   每轮把「发现目标 → 甩头 → 命中」的真实鼠标增量按 TickSample 存入数据集。
     *
     */
    private val collectModeValue = choices("CollectMode", arrayOf("Combat", "AimBall"), "Combat")

    /** 当前采集模式是否为 Combat（战斗段采集）。 */
    private val combatMode: Boolean
        get() = collectModeValue.get().equals("Combat", ignoreCase = true)

    /** 当前采集模式是否为 AimBall（小球/FakePlayer 采集）。 */
    private val aimBallMode: Boolean
        get() = collectModeValue.get().equals("AimBall", ignoreCase = true)

    /**
     * Combat 攻击间隔上限（秒，默认 1 秒）：距上次攻击超过该时长仍没有下一次攻击 →
     * 舍弃当前段（只有连击节奏中的数据才保留）。
     */
    private val combatGapValue = float("CombatGap", 1f, 0.5f..5f, "s") { combatMode }

    /** [combatGapValue] 的 tick 视图：段窗口判定逐 tick 使用。 */
    private val combatGapTicks: Long
        get() = (combatGapValue.get() * 20f).toLong().coerceAtLeast(1L)

    /**
     * 段质量检查倍数：整段累计旋转角（逐 tick 合角求和）> 段首 needed × 该倍数 →
     * 该段的旋转量远超「转到目标所需」，判定为乱甩/乱看 junk，整段舍弃。
     */
    private const val ROTATION_NEEDED_MAX_RATIO = 1.3f

    /** 舍弃段数据时在聊天栏输出原因（排查采集过滤逻辑用）。 */
    private val debugDiscardValue = boolean("DebugDiscard", false) { combatMode }

    /** 开关一次清空 rotations/ 下全部数据（dataset/library/net_weights），清完自动复位。 */
    private val clearDataValue = boolean("ClearData", false)

    // ---- AimBall 采集小游戏 ----

    /**
     * 小球移动速度（格/秒）。速度只在【追踪 TRACK】轮生效（追踪轮强制有运动目标：
     * 设定值 > 0 用设定值，否则用默认 4 格/秒）；【定位/调整】轮小球保持静止，
     * 贴合两类场景的数据语义（定位=纯甩头、调整=静止目标小幅修正）。
     */
    private val ballSpeedValue = float("BallSpeed", 3f, 0f..8f, "格/秒") { aimBallMode }

    // 命中判定角（度）＝准星落入小球判定圈。默认 4°，比人头视角略大。
    // 仅 Ball 模式使用：FakePlayer 模式的瞄准判定是视线射线压 hitbox，无角度容差
    private val toleranceValue = float("Tolerance", 4f, 2f..8f, "°") { aimBallMode && !fakePlayerMode }

    // 完成方式：Click = 瞄准圈内点击；Dwell = 持续瞄准；Both = 任一。
    // 仅 Ball 模式使用：FakePlayer 模式无命中交互（接触玩家即完成轮）
    private val modeValue = choices("HitMode", arrayOf("Click", "Dwell", "Both"), "Both") { aimBallMode && !fakePlayerMode }

    // Dwell 模式需要持续瞄准的时长
    private val dwellTimeValue = float("DwellTime", 0.5f, 0.2f..1.5f, "s") { aimBallMode && !fakePlayerMode }

    // 小球与玩家的距离范围
    private val distanceValue = floatRange("Distance", 4f..6f, 3f..10f) { aimBallMode }

    // 超时换位（秒），0 = 关闭；找不到球快速止损换位重开（未命中轮不入库）
    private val timeoutValue = int("Timeout", 3, 0..120, "s") { aimBallMode }

    /**
     * 单轮最长时长（秒），0 = 关闭：轮超过该时长即使最终命中也整轮丢弃重开 ——
     * 长时间挣扎瞄准的轮里满是来回找目标与漂移修正，入库只会拉低模板/网络质量
     * （与 Timeout 的区别：Timeout 只丢弃未命中轮，本上限连「磨了很久才命中」的
     * 轮一起丢）。FakePlayer 模式没有 Timeout，该设置是唯一的止损上限。
     */
    private val maxRoundTimeValue = int("MaxRoundTime", 10, 3..60, "s") { aimBallMode }

    // ---- AimBall 采集场景权重 ----

    /**
     * 每轮开球按权重随机决定本轮采集场景（定位/调整/追踪三类训练数据自由配比，
     * 权重 0 = 该类不出现）：
     * - 定位 LOCATE：大偏角开球（±60..150°），采集整段「甩向新目标」的爆发-减速；
     * - 调整 ADJUST：准星近旁小偏角开球（±3..11°，剩余差 < FLICK 阈值 12°），采集收差尾段；
     * - 追踪 TRACK：小偏角 + 强制运动目标，采集「准星压在目标身上」的连续跟随。
     */
    private val locateWeightValue = int("LocateWeight", 30, 0..100) { aimBallMode }
    private val adjustWeightValue = int("AdjustWeight", 30, 0..100) { aimBallMode }
    private val trackWeightValue = int("TrackWeight", 40, 0..100) { aimBallMode }

    // ---- FakePlayer 采集模式 ----

    /**
     * AimBall 目标形态（choice）：Ball = 抽象小球；FakePlayer = 只在客户端渲染的
     * 假玩家（不加入 world 实体列表、不发任何数据包，对服务器完全不可见）。
     * FakePlayer 模式下它从 [fakePlayerDistanceValue] 范围内的随机距离出生，带着
     * 斜左右 strafe 与随机起跳朝玩家跑来，接触到玩家后立即在玩家周围重新生成
     * 一个新的假玩家 —— 玩家需瞄准它的躯干采集数据。三类权重仍然生效，决定
     * 假玩家每一轮的出生偏角分布。
     */
    private val fakePlayerValue = choices("TargetMode", arrayOf("Ball", "FakePlayer"), "Ball") { aimBallMode }

    /** 当前 TargetMode 是否为 FakePlayer（choice 字符串的布尔视图）。 */
    private val fakePlayerMode: Boolean
        get() = fakePlayerValue.get().equals("FakePlayer", ignoreCase = true)

    /**
     * FakePlayer 出生距离范围（格）：每轮开球在该范围内随机取出生点，
     * 出生后朝玩家跑来（默认 4..7 格，可拉到 3..15 格）。
     */
    private val fakePlayerDistanceValue = floatRange("FakePlayerDistance", 4f..7f, 3f..15f, "格") { aimBallMode && fakePlayerMode }

    /** 假玩家朝玩家行走的速度（格/秒），0 = 站桩不动（只有跳）。默认取 1.8 疾跑速度 5.6，
     *  假玩家全程 setSprinting(true)（一直疾跑的拟人逼近）。 */
    private val fakePlayerSpeedValue = float("FakePlayerSpeed", 5.6f, 0f..8f, "格/秒") { aimBallMode && fakePlayerMode }

    // ---- 已采集数据量定期汇报 ----

    /**
     * 开启后每隔 ReportDelay 秒在聊天栏输出一次当前已采集的数据量（历史落盘总量
     * + 本轮未落盘缓冲），方便日常采集时掌握进度。ReportDelay 范围 1..20 秒，默认 10 秒。
     */
    private val dataReportValue = boolean("DataReport", false)
    private val dataReportDelayValue = int("ReportDelay", 10, 1..20, "s") { dataReportValue.get() }

    private val COLOR_IDLE_FILL = Color(255, 255, 255, 50)
    private val COLOR_IDLE_EDGE = Color(255, 255, 255, 190)
    private val COLOR_AIMED_FILL = Color(110, 255, 140, 70)
    private val COLOR_AIMED_EDGE = Color(110, 255, 140, 230)
    private val COLOR_ARROW = Color(255, 255, 255, 210)

    /**
     * FakePlayer 与玩家的「接触」判定半径（水平距离平方）：两方 hitbox 相触
     * （中心距 1 格）即认为接触，重开一轮。
     */
    private const val FAKE_CONTACT_DIST_SQ = 1.0

    /**
     * FakePlayer 瞄准判定射线的最大距离（格）：与 [AimScene] 的 FACE_RANGE 同值同语义 ——
     * 普通实体模式的「准星压在实体上」真值也在 6 格内判定，保证采集端 TRACK 真值
     * 与运行时场景判定同分布（更远的假玩家本来就不可能被点击）。
     */
    private const val FAKE_HIT_RANGE = 6.0

    /** FakePlayer 被地形挡住视线多久（tick）后放弃本轮、重开一个新的假玩家：
     * 玩家完全看不到目标时只好乱转视角搜索，重开比硬等更保数据质量。 */
    private const val BLOCKED_LOS_TICKS = 40

    /**
     * FakePlayer 轮的瞄准质量门：本轮 [RotationRecorder.AimScene]（aimed = 准星在
     * 目标判定圈内）tick 占比低于该值 → 接触时整轮丢弃。无命中交互后，完全不看
     * 目标的「挂机轮」不再写进模板库（懒散漂移的 tracking 数据会拉低运行时命中率）。
     */
    private const val FAKE_MIN_AIM_FRACTION = 0.4f

    /** 小球绝对坐标；null = 未生成。 */
    private var ballPos: Vec3? = null

    /**
     * 上 tick 结算时的小球坐标：渲染帧用它与 [ballPos] 按 partialTicks 插值显示。
     * 小球逐 tick 移动（step = ballSpeed/20 格/tick），渲染直接取 ballPos 只有
     * 20Hz 步进 —— 移动时看起来卡卡的。插值只用于显示，命中判定保持 tick 粒度。
     */
    private var lastBallPos: Vec3? = null

    /** 本轮采集场景（AimScene.LOCATE/ADJUST/TRACK），开球时按权重随机决定。 */
    private var roundMode = AimScene.LOCATE

    /** TRACK 轮的运动速度（格/秒）：BallSpeed > 0 用设定值，否则取默认，保证追踪轮必有运动目标。 */
    private var roundTrackSpeed = 4f

    /**
     * FakePlayer 模式的客户端假玩家实体：只在渲染管线里出现（不 addEntityToWorld）。
     * 每轮开球重置姿态坐标，渲染帧按 lastTick→pos 与 prevRotation 插值。
     */
    private var fakePlayerInstance: EntityOtherPlayerMP? = null

    // FakePlayer 拟人模拟状态：垂直速度 / 起跳冷却 / strafe 航向与节奏 / 摆臂冷却
    private var fakeVelY = 0f
    private var fakeJumpTicks = 0
    private var fakeStrafeYawOffset = 0f
    private var fakeStrafeTicks = 0
    private var fakeSwingTicks = 0

    /** FakePlayer 连续被地形挡住视线的 tick 计数（≥ BLOCKED_LOS_TICKS 重开新假玩家）。 */
    private var fakeBlockedTicks = 0

    /** 本 FakePlayer 轮内 aimed（准星在判定圈内）的 tick 数：接触时算质量门占比。 */
    private var fakeAimTicks = 0

    /**
     * FakePlayer 站立的脚底地面 Y：只在玩家 onGround 时同步（起跳跟随会造成「越跳
     * 越把假玩家拽上天」的非物理垂直运动，v5.3 修复）。出生时也用它（玩家空中开球
     * 不把假玩家生在半空）。
     */
    private var fakeGroundY = 0.0

    private var aimed = false
    private var aimTicks = 0
    private var roundTicks = 0

    // 移动小球状态（平滑游走）：航向（yaw，度）/ 航向角速度（度/tick）/ 垂直速度
    // 比例与其平滑目标 / 直到下次重定目标的剩余 tick 数。航向逐 tick 积分角速度，
    // 角速度与垂直比例都低通趋近各自目标 —— 轨迹是连续弧线，没有离散急转弯。
    private var ballVelYaw = 0f
    private var ballTurnRate = 0f
    private var ballTurnTarget = 0f
    private var ballVelPitch = 0f
    private var ballVelPitchTarget = 0f
    private var ballMoveTicksLeft = 0

    private val buffer = ArrayList<TickSample>()

    /**
     * Combat 攻击预滚窗口长度（tick）：窗口外样本滚入 [preBuffer]，攻击发生时
     * 补录进数据集。0.75 秒覆盖大多数甩头段的起手部分，又不至于把攻击前的
     * 长时间无关扫视带进来。
     */
    private const val PRE_ROLL_TICKS = 15

    /** Combat 窗口外的预滚缓冲：滚动保留最近 [PRE_ROLL_TICKS] 条有效样本。 */
    private val preBuffer = ArrayDeque<TickSample>()
    private var accYaw = 0f
    private var accPitch = 0f

    /**
     * Combat 当前段（本次攻击 → 下一次攻击之间）的未决样本：攻击 tick 开段
     * （先补录 [preBuffer]），下一次攻击 tick / 击杀确认时结算入库，[combatGapValue]
     * 秒内无下一次攻击或质量检查不过则整段舍弃 —— 未决数据在结算前不进 [buffer]，舍弃即可
     * 直接丢弃，不会污染已落盘/已缓冲的数据。
     */
    private val pendingSegment = ArrayList<TickSample>()

    /** 上一条写入 buffer 的是否为真实样本：false = 当前在段之外（终止零样本只写一次）。 */
    private var lastFlushValid = false

    private var tickCounter = 0L
    private var lastAttackTick = Long.MIN_VALUE

    /** 最近一次攻击的实体（Combat/战斗窗口内用它做 entityDist/瞄准点基准）。 */
    private var combatTarget: EntityLivingBase? = null
    private var prevClickDown = false

    // CollectMode（Combat / AimBall）热切换记录
    private var prevCollectMode = "Combat"

    // 本次会话诊断：被跳过的 tick 数、其中可见旋转接管（视角被直接改写）的数量，
    // 以及窗口外样本被预滚缓冲挤掉的 tick 数（三者均在 DataReport 中汇报）
    private var skippedTicks = 0
    private var skippedByViewTakeover = 0
    private var prerollDroppedTicks = 0

    // TargetMode（Ball / FakePlayer）热切换记录
    private var prevTargetMode = "Ball"

    // 数据量定期汇报：上次汇报的时间戳
    private var lastReportTime = 0L

        /** JVM 退出钩子：异步 persist 在玩家紧接着退出游戏时可能没跑完，退出前同步 flush 缓冲。 */
    private var shutdownHook: Thread? = null

    override fun onEnable() {
        // ClearData 语义：设置打开后，再开启模块 = 清空数据并自动关闭本模块（不进入录制）。
        // 清空持 persistMutex 且在 IO 线程执行（与进行中 persist 的 读旧→合并→写回 互斥）。
        // 不走 tick 分支的旧方案：模块刚启用时的 onDisable flushTick / 立即录制也存在
        // 与 clearAll 竞态的窗口，清空后旧数据可能被写回，表现为「没清理数据」。
        if (clearDataValue.get()) {
            clearDataValue.set(false)
            // 缓冲一并清空：随后自动关闭触发的 onDisable flushTick/persist 不会把
            // 残留样本写回刚清空的 dataset.json
            synchronized(buffer) { buffer.clear() }
            preBuffer.clear()
            pendingSegment.clear()
            accYaw = 0f
            accPitch = 0f
            SharedScopes.IO.launch {
                try {
                    val removed = AimDatasetStore.clearAll()
                    withContext(Dispatchers.Main) {
                        chat("§7[§bAI Aim§7] §fCleared §b$removed§f data file(s).")
                        state = false
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        chat("§7[§bAI Aim§7] §c清空数据失败: ${t.message}")
                        state = false
                    }
                }
            }
            return
        }

        synchronized(buffer) {
            buffer.clear()
        }
        preBuffer.clear()
        pendingSegment.clear()
        accYaw = 0f
        accPitch = 0f
        lastFlushValid = false
        tickCounter = 0L
        lastAttackTick = Long.MIN_VALUE
        combatTarget = null
        skippedTicks = 0
        skippedByViewTakeover = 0
        prerollDroppedTicks = 0

        aimTicks = 0
        roundTicks = 0
        aimed = false
        roundMode = AimScene.LOCATE
        roundTrackSpeed = 4f
        fakePlayerInstance = null
        fakeVelY = 0f
        fakeJumpTicks = 0
        fakeStrafeYawOffset = 0f
        fakeStrafeTicks = 0
        fakeSwingTicks = 0
        fakeBlockedTicks = 0
        fakeAimTicks = 0
        prevCollectMode = collectModeValue.get()
        prevTargetMode = fakePlayerValue.get()
        lastReportTime = 0L
        if (aimBallMode) {
            spawnBall()
        } else {
            ballPos = null
            lastBallPos = null
        }

        if (shutdownHook == null) {
            shutdownHook = Thread {
                val snapshot = synchronized(buffer) { ArrayList(buffer) }
                synchronized(buffer) { buffer.clear() }
                AimDatasetStore.persistBlocking(snapshot)
            }.also { Runtime.getRuntime().addShutdownHook(it) }
        }

        // 开启时汇报一次数据规模与场景标签分布，确认采集进度/三类数据平衡
        SharedScopes.IO.launch {
            val (ticks, segments, scenes) = AimDatasetStore.stats()
            if (ticks > 0) {
                chat(
                    "§7[§bAI Aim§7] §fDataset: §b$ticks§f ticks, §b$segments§f segments, " +
                        "scenes locate §b${scenes[AimScene.LOCATE]}§f / adjust §b${scenes[AimScene.ADJUST]}§f " +
                        "/ track §b${scenes[AimScene.TRACK]}§f (unlabelled §7${scenes[AimScene.NONE]}§f)."
                )
            }
        }
    }

    override fun onDisable() {
        flushTick()

        // 手动停止录制：未决段视为有效数据直接提交保留（跳过质量检查，带终止标记），
        // 不再等待下一次攻击结算
        finalizePendingSegment(
            RotationUtils.getFixedAngleDelta(),
            terminate = true,
            checkQuality = false,
            reason = "手动停止录制"
        )

        val snapshot = synchronized(buffer) { ArrayList(buffer) }
        buffer.clear()
        preBuffer.clear()
        pendingSegment.clear()
        // 同步落盘：若走异步 persist，玩家紧接着退出游戏时协程可能没跑完，本轮录制丢失
        if (snapshot.isNotEmpty()) {
            AimDatasetStore.persistBlocking(snapshot)
        }

        shutdownHook?.let {
            try {
                Runtime.getRuntime().removeShutdownHook(it)
            } catch (_: IllegalStateException) {
                // JVM 正在退出，钩子即将/已经执行，无需移除
            }
        }
        shutdownHook = null

        ballPos = null
        lastBallPos = null
        aimed = false
        aimTicks = 0
        roundTicks = 0
        resetBallMovement()
        fakePlayerInstance = null
        fakeVelY = 0f
        fakeJumpTicks = 0
        fakeStrafeYawOffset = 0f
        fakeStrafeTicks = 0
        fakeSwingTicks = 0
        fakeBlockedTicks = 0
        fakeAimTicks = 0
        roundMode = AimScene.LOCATE
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
    val onAttack = handler<AttackEvent> { event ->
        lastAttackTick = tickCounter

        // 战斗目标锁定：记录本次攻击的实体，攻击间隔窗口（[combatGapValue]）内
        // entityDist 与瞄准点以它为基准，而不是「最近任意生物」—— 团战/多人场景下
        // 最近实体可能是队友或旁观者，needed/entityDist 会指向噪声目标污染训练数据。
        if (event.targetEntity is EntityLivingBase && event.targetEntity !== mc.thePlayer) {
            combatTarget = event.targetEntity
        }
    }

    @Suppress("unused")
    val onTick = handler<GameTickEvent> {
        tickCounter++

        // CollectMode 热切换：切到 AimBall 时生成小球，切走时收起
        if (collectModeValue.get() != prevCollectMode) {
            prevCollectMode = collectModeValue.get()
            if (aimBallMode) {
                spawnBall()
            } else {
                ballPos = null
                lastBallPos = null
                aimed = false
            }
        }

        // TargetMode 热切换：Ball ↔ FakePlayer 换形时立即重新开球
        if (aimBallMode && fakePlayerValue.get() != prevTargetMode) {
            prevTargetMode = fakePlayerValue.get()
            spawnBall()
        }

        if (aimBallMode) {
            updateBall()
        }

        // 数据量定期汇报：每 ReportDelay 秒在聊天栏输出一次已采集样本量。
        // Combat 模式下落盘发生在关闭模块时，磁盘量（stats）会话内不变，正在录制的
        // 样本在 pendingSegment/preBuffer（未结算，可能被质量门/间隔超时舍弃）、
        // buffer（已结算待落盘）——三分项都要显示，否则主数字看起来一直冻结在开启值。
        if (dataReportValue.get()) {
            val now = System.currentTimeMillis()
            if (now - lastReportTime >= dataReportDelayValue.get() * 1000L) {
                lastReportTime = now
                val buffered = synchronized(buffer) { buffer.size }
                val pending = pendingSegment.size + preBuffer.size
                SharedScopes.IO.launch {
                    try {
                        val (ticks, segments, scenes) = AimDatasetStore.stats()
                        chat(
                            "§7[§bAI Aim§7] §fCollected §b${ticks + buffered + pending}§f ticks " +
                                "(§b$ticks§f on disk, §b$buffered§f buffered, §b$pending§f recording; " +
                                "§b$segments§f segments) - locate §b${scenes[AimScene.LOCATE]}§f / adjust " +
                                "§b${scenes[AimScene.ADJUST]}§f / track §b${scenes[AimScene.TRACK]}§f. " +
                                "§7Session: skipped §f$skippedTicks§7 (view takeover §f$skippedByViewTakeover§7), " +
                                "preroll dropped §f$prerollDroppedTicks§7."
                        )
                    } catch (_: Throwable) {
                        // 汇报失败不影响采集
                    }
                }
            }
        }

        flushTick()
    }

    /**
     * 小球/FakePlayer 模式 tick 逻辑：进世界补生成、接触/超时重开、命中判定与
     * Dwell 计时。移动按形态/场景分派：
     * FakePlayer → 拟人模拟（[simulateFakePlayer]），从出生点带着斜左右 strafe
     *   与起跳朝玩家跑来，接触到玩家（[FAKE_CONTACT_DIST_SQ]）即重开一轮：
     *   在玩家周围按 [fakePlayerDistanceValue] 重新生成一个新的假玩家；
     * 普通小球 → 仅 TRACK 轮运动（[moveTrackBall]），定位/调整轮保持静止 ——
     * 这样三类场景的数据语义独立：定位=纯甩头、调整=静止目标小幅修正、
     * 追踪=持续压着运动目标跟随。
     */
    private fun updateBall() {
        val player = mc.thePlayer ?: return

        // 进世界前开启时目标为空，这里补生成（FakePlayer 模式还要等本体实体就位）
        if (ballPos == null || fakePlayerMode && fakePlayerInstance == null) {
            spawnBall()
            return
        }

        // 快照本 tick 移动前坐标：渲染帧按 partialTicks 在 lastBallPos→ballPos 插值
        lastBallPos = ballPos

        roundTicks++

        // 移动分派
        if (fakePlayerMode) {
            simulateFakePlayer(player)
        } else if (roundMode == AimScene.TRACK) {
            moveTrackBall(player)
        }

        // FakePlayer 接触判定：碰到玩家 = 本轮完成（flush 已采数据入库 + 提示音，
        // 假玩家消失并在周边重新生成一个新假玩家）
        if (fakePlayerMode) {
            val fake = fakePlayerInstance
            if (fake != null) {
                val dx = player.posX - fake.posX
                val dz = player.posZ - fake.posZ
                if (dx * dx + dz * dz <= FAKE_CONTACT_DIST_SQ) {
                    // 瞄准质量门：aimed tick 占比过低（挂机/完全没盯目标）→ 本轮
                    // 视为无效搜索数据丢弃，只重开新假玩家
                    val aimFraction = fakeAimTicks.toFloat() / roundTicks.coerceAtLeast(1)
                    finishRound(hit = aimFraction >= FAKE_MIN_AIM_FRACTION)
                    return
                }
            }

            // LOS 卡死兜底：假玩家被地形挡住（玩家眼睛 → 假玩家胸口「ballPos」的
            // 视线射线命中方块；出生点视线检测未拦到的角落情况）连续超过
            // BLOCKED_LOS_TICKS → 玩家完全看不到目标，只能乱转视角搜索，
            // 直接重开一个新的假玩家
            fakeBlockedTicks = if (
                mc.theWorld != null && mc.theWorld.rayTraceBlocks(
                    Vec3(player.posX, player.posY + player.getEyeHeight(), player.posZ),
                    ballPos!!,
                ) != null
            ) fakeBlockedTicks + 1 else 0
            if (fakeBlockedTicks >= BLOCKED_LOS_TICKS) {
                spawnBall()
                return
            }
        }

        // 玩家离目标太远（跑图）→ 换位。FakePlayer 本身一直朝玩家追（玩家跑图会被
        // 跟上），只在距离超出出生范围上限 + 余量时才重开，避免提前回收追击轮。
        val driftLimit = if (fakePlayerMode) fakePlayerDistanceValue.get().endInclusive + 3.0 else 12.0
        if (player.getDistance(ballPos!!.xCoord, ballPos!!.yCoord, ballPos!!.zCoord) > driftLimit) {
            spawnBall()
            return
        }

        // 超时换位
        val timeout = timeoutValue.get()
        if (timeout > 0 && roundTicks >= timeout * 20) {
            finishRound(hit = false)
            return
        }

        // 单轮时长上限：磨了很久才命中/接触的「挣扎轮」整轮丢弃（含已命中轮），
        // 避免长时间找目标与漂移修正的数据入库污染模板库
        val maxRound = maxRoundTimeValue.get()
        if (maxRound > 0 && roundTicks >= maxRound * 20) {
            finishRound(hit = false)
            return
        }

        // 命中判定：
        // - FakePlayer 模式 = 视线射线（玩家实际朝向）压在假玩家 hitbox 上（相交 +
        //   交点无方块遮挡，与普通实体模式的 AimScene.crosshairOnEntity 同判据）。
        //   aimed 只用于 scene 真值打标与箭头显示判断 —— 无命中交互，Click/Dwell
        //   不结束轮，轮以假玩家接触玩家为唯一完成方式；
        // - 小球模式 = 准星方向与球心方向夹角 ≤ Tolerance（判定圈）。
        aimed = if (fakePlayerMode) {
            val fake = fakePlayerInstance
            fake != null && crosshairOnFakeHitbox(player, fake)
        } else {
            val toBall = RotationUtils.toRotation(ballPos!!)
            val diffYaw = RotationUtils.angleDifference(toBall.yaw, player.rotationYaw)
            val diffPitch = toBall.pitch - player.rotationPitch
            hypot(diffYaw, diffPitch) <= toleranceValue.get()
        }

        if (fakePlayerMode) {
            aimTicks = 0
            if (aimed) fakeAimTicks++
        } else if (aimed) {
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
     * TRACK 轮小球平滑游走：航向逐 tick 积分角速度，角速度/垂直比例低通趋向
     * 每 20..50 tick 重定的目标 —— 轨迹天然是连续弯曲的弧线。速度用本轮
     * [roundTrackSpeed]（BallSpeed 设定值优先，否则默认 4 格/秒），运动目标是
     * 「准星压在目标身上」的追踪数据的关键来源。
     */
    private fun moveTrackBall(player: Entity) {
        if (ballMoveTicksLeft <= 0) {
            ballMoveTicksLeft = (20..50).random()
            // 目标角速度最大 ±3.5°/tick（≈ ±70°/s 转弯率，低通平滑生效）；
            // 垂直比例目标 ±0.45（垂直速度 ≤ 水平速度的 45%，缓上缓下）
            ballTurnTarget = (-3.5f..3.5f).random()
            ballVelPitchTarget = (-0.45f..0.45f).random()
        }
        ballMoveTicksLeft--

        // 低通趋近：让转弯与垂直分量连续过渡，而不是每 20..50 tick 一次硬切换
        ballTurnRate += (ballTurnTarget - ballTurnRate) * 0.08f
        ballVelPitch += (ballVelPitchTarget - ballVelPitch) * 0.06f

        val step = roundTrackSpeed / 20f
        val dir = RotationUtils.getVectorForRotation(ballVelYaw, 0f)
        val pos = ballPos!!
        var next = Vec3(
            pos.xCoord + dir.xCoord * step,
            pos.yCoord + ballVelPitch * step,
            pos.zCoord + dir.zCoord * step,
        )

        // 漂出当前距离范围上限 2 格：航向缓转向玩家方向折返（每 tick 12% 的
        // 带符号角差），螺旋拉回而不是瞬移航向（toRotation = 玩家→球，+180 = 球→玩家）
        if (player.getDistance(next.xCoord, next.yCoord, next.zCoord) > distanceValue.random + 2.0) {
            ballVelYaw += RotationUtils.angleDifference(
                RotationUtils.toRotation(ballPos!!).yaw + 180f, ballVelYaw
            ) * 0.12f
        }

        // 垂直边界：不低于脚底上方 0.3、不高于视线上方 3；越界翻转垂直目标让
        // 速度经低通自然反转，位置钳制只是防止继续钻地
        val floorLimit = player.posY + 0.3
        val ceilLimit = player.posY + player.getEyeHeight() + 3.0
        if (next.yCoord < floorLimit) {
            next = Vec3(next.xCoord, floorLimit, next.zCoord)
            if (ballVelPitch < 0f) ballVelPitchTarget = -ballVelPitchTarget
        } else if (next.yCoord > ceilLimit) {
            next = Vec3(next.xCoord, ceilLimit, next.zCoord)
            if (ballVelPitch > 0f) ballVelPitchTarget = -ballVelPitchTarget
        }

        ballPos = next
    }

    /**
     * FakePlayer 拟人模拟（客户端假玩家，不发包、不进 world）：
     * - 水平：始终朝玩家跑来（速度 [fakePlayerSpeedValue]），航向叠加 ±(15..45)°
     *   的随机 strafe 偏角 —— 即斜左右向移动；每 20..40 tick 换向一次（幅度随机
     *   维持，站在对角冲线上），轨迹是斜着逼近的折线弧而不会直冲；
     * - 垂直：随机冷却起跳（原版跳跃初速 0.42 / 重力 0.08 / 摩擦 0.98），落到玩家
     *   所在的海拔平面，空中保持 strafe —— 跳动+斜移是真人逼近时的常见形态；
     * - 动画：limbSwing 按水平位移推进（原版同公式），随机间隔摆臂；
     * - 朝向：头/身始终面向玩家。
     * 接触到玩家（距离 ≤ 1 格）由 [updateBall] 负责重开一轮 —— 在玩家周围重新
     * 生成一个新的假玩家，而不是贴身转圈。
     * 每次模拟前先把 lastTickPos / prevRotation 保存好，渲染帧按 partialTicks 插值。
     */
    private fun simulateFakePlayer(player: Entity) {
        val fake = fakePlayerInstance ?: return

        val prevX = fake.posX
        val prevY = fake.posY
        val prevZ = fake.posZ
        val prevYaw = fake.rotationYaw
        val groundY = player.posY

        // 朝向玩家的 yaw（Minecraft yaw：atan2(dz, dx)·180/π - 90）
        val dx = player.posX - prevX
        val dz = player.posZ - prevZ
        val toPlayerYaw = Math.toDegrees(Math.atan2(dz, dx)).toFloat() - 90f

        // strafe 航向每 20..40 tick 换向：新偏角在对侧随机取值，斜左右交替逼近
        if (fakeStrafeTicks <= 0) {
            fakeStrafeTicks = (20..40).random()
            fakeStrafeYawOffset = if (fakeStrafeYawOffset >= 0f) -(15f..45f).random()
            else (15f..45f).random()
        }
        fakeStrafeTicks--

        // 斜左右向移动：朝玩家方向 + strafe 偏角，直到接触玩家（接触重开由
        // updateBall 处理），全程不减速不绕圈 —— 目标语义是「从远处跑来的人」
        val moveYaw = toPlayerYaw + fakeStrafeYawOffset
        val moveSpeed = fakePlayerSpeedValue.get() / 20f

        val dir = RotationUtils.getVectorForRotation(moveYaw, 0f)
        val moveX = dir.xCoord * moveSpeed
        val moveZ = dir.zCoord * moveSpeed

        // 随机起跳：冷却归零且在地面 → 原版跳跃初速；重力/摩擦与原版一致。
        // 空中保持 strafe —— 起跳同时横向移动才是真人跳跃的形态。
        if (fakeJumpTicks > 0) fakeJumpTicks--
        if (fakeVelY == 0f && fakeJumpTicks <= 0) {
            fakeJumpTicks = (15..60).random()
            fakeVelY = 0.42f
        }
        fakeVelY = (fakeVelY - 0.08f) * 0.98f
        var newY = prevY + fakeVelY
        if (newY <= groundY) {
            newY = groundY
            fakeVelY = 0f
        }

        // 随机摆臂：既当走路摆手、也当攻击前摇的观感
        if (fakeSwingTicks > 0) fakeSwingTicks--
        if (fakeSwingTicks <= 0 && !fake.isSwingInProgress) {
            fakeSwingTicks = (30..120).random()
            fake.isSwingInProgress = true
            fake.swingProgressInt = 0
        }

        // 摆臂动画推进（原版 onLivingUpdate swing 部分的等价逻辑，armSwing 周期 6 tick）
        if (fake.isSwingInProgress) {
            fake.swingProgressInt++
            if (fake.swingProgressInt >= 6) {
                fake.swingProgressInt = 0
                fake.isSwingInProgress = false
            }
        }
        fake.swingProgress = fake.swingProgressInt / 6f

        // 一直疾跑：摆臂/体态呈疾跑姿态（匀冲刺直奔玩家，逼近更紧凑）
        fake.setSprinting(true)

        val newX = prevX + moveX
        val newZ = prevZ + moveZ

        // 朝向：头/身面向玩家，各 prev 字段同步给渲染帧插值
        fake.prevRotationYaw = prevYaw
        fake.prevRotationYawHead = fake.rotationYawHead
        fake.prevRenderYawOffset = fake.renderYawOffset
        fake.rotationYaw = toPlayerYaw
        fake.rotationYawHead = toPlayerYaw
        fake.renderYawOffset = toPlayerYaw

        // 行走动画：limbSwing/limbSwingAmount 按水平位移推进（原版同公式）
        val moved = hypot(newX - prevX, newZ - prevZ).toFloat()
        fake.prevLimbSwingAmount = fake.limbSwingAmount
        fake.limbSwingAmount += (moved.coerceIn(0f, 0.5f) * 2f - fake.limbSwingAmount) * 0.4f
        fake.limbSwing += fake.limbSwingAmount

        // 姿态坐标：lastTick 保存旧位置，pos 指向新位置 —— 渲染帧按 partialTicks 插值。
        // 位置必须走 setPosition（而非直接写字段）：它会同步重建 entityBoundingBox，
        // hitbox 瞄准判定（crosshairOnFakeHitbox / hitboxAimDiff）依赖包围盒跟随移动。
        fake.lastTickPosX = prevX
        fake.lastTickPosY = prevY
        fake.lastTickPosZ = prevZ
        fake.setPosition(newX, newY, newZ)

        // ballPos = 假玩家头部中线（玩家眼高）：不再作为瞄准判定基准（判定已改为
        // 视线射线压 hitbox），仅供 LOS 检测 / 距离上限 / 方向箭头参考 —— 头部中线
        // 是遮挡与引导语义上最稳的代表点
        ballPos = Vec3(newX, newY + player.getEyeHeight(), newZ)
    }

    /** 生成新球时重置移动状态：航向随机，角速度/垂直分量归零由低通摊开（首轮平滑加速）。 */
    private fun resetBallMovement() {
        ballVelYaw = (-180f..180f).random()
        ballTurnRate = 0f
        ballTurnTarget = 0f
        ballVelPitch = 0f
        ballVelPitchTarget = 0f
        ballMoveTicksLeft = 0
    }

    /**
     * 点击边沿检测放渲染帧（更贴合点击时机）。
     * 仅在命中圈内且无界面时视为有效点击。
     */
    @Suppress("unused")
    val onRender2D = handler<Render2DEvent> { event ->
        if (!aimBallMode) return@handler
        val current = ballPos ?: return@handler
        val player = mc.thePlayer ?: return@handler

        // 与 3D 渲染同一插值：箭头以渲染帧率跟随小球，而不是 20Hz 抖动
        val t = event.partialTicks.coerceIn(0f, 1f)
        val last = lastBallPos ?: current
        val ball = Vec3(
            last.xCoord + (current.xCoord - last.xCoord) * t,
            last.yCoord + (current.yCoord - last.yCoord) * t,
            last.zCoord + (current.zCoord - last.zCoord) * t,
        )

        // ---- 命中圈指示（箭头）----
        val toBall = RotationUtils.toRotation(ball)
        val diffYaw = RotationUtils.angleDifference(toBall.yaw, player.rotationYaw)
        val diffPitch = toBall.pitch - player.rotationPitch

        if (!aimed) {
            drawArrow(diffYaw, diffPitch)
        }

        // ---- Dwell 进度条（准星下方）----
        if (aimed && !fakePlayerMode && (modeValue.get() == "Dwell" || modeValue.get() == "Both")) {
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
        if (!fakePlayerMode && mc.currentScreen == null && aimed && Mouse.isButtonDown(0) && !prevClickDown) {
            if (modeValue.get() == "Click" || modeValue.get() == "Both") {
                finishRound(hit = true)
            }
        }
        prevClickDown = Mouse.isButtonDown(0)
    }

    @Suppress("unused")
    val onRender3D = handler<Render3DEvent> { event ->
        if (!aimBallMode) return@handler
        val current = ballPos ?: return@handler

        // FakePlayer 模式：不画判定圈，直接渲染客户端假玩家模型（瞄准点仍按 ballPos 判定）
        if (fakePlayerMode) {
            renderFakePlayer(event)
            return@handler
        }

        val renderManager = mc.renderManager

        // 渲染帧插值：小球逐 tick 移动，直接画 ballPos 只有 20Hz 步进（看起来卡卡的）。
        // 用 partialTicks 在上 tick 位置与本 tick 位置间插值，让轨迹衔接渲染帧率。
        // 插值只影响显示，命中判定（updateBall）仍按 tick 粒度进行。
        val t = event.partialTicks.coerceIn(0f, 1f)
        val last = lastBallPos ?: current
        val (x, y, z) = Vec3(
            last.xCoord + (current.xCoord - last.xCoord) * t,
            last.yCoord + (current.yCoord - last.yCoord) * t,
            last.zCoord + (current.zCoord - last.zCoord) * t,
        ) - renderManager.renderPos

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
     * 渲染客户端假玩家（不 addEntityToWorld，纯 RenderManager 手动渲染 —— 对服务器
     * 完全不可见）。lastTick→pos 与 prevRotation 按 partialTicks 插值，得到平滑
     * 的行走/跳跃/转身动画（姿态推进在 [simulateFakePlayer] 内逐 tick 完成）。
     */
    private fun renderFakePlayer(event: Render3DEvent) {
        val fake = fakePlayerInstance ?: return
        val renderManager = mc.renderManager
        val t = event.partialTicks.coerceIn(0f, 1f)

        val brightness = fake.getBrightnessForRender(t)
        OpenGlHelper.setLightmapTextureCoords(
            OpenGlHelper.lightmapTexUnit,
            (brightness % 65536).toFloat(),
            (brightness / 65536).toFloat()
        )

        renderManager.isRenderShadow = false
        try {
            renderManager.renderEntityWithPosYaw(
                fake,
                fake.lastTickPosX + (fake.posX - fake.lastTickPosX) * t - renderManager.renderPosX,
                fake.lastTickPosY + (fake.posY - fake.lastTickPosY) * t - renderManager.renderPosY,
                fake.lastTickPosZ + (fake.posZ - fake.lastTickPosZ) * t - renderManager.renderPosZ,
                fake.prevRotationYaw + (fake.rotationYaw - fake.prevRotationYaw) * t,
                t
            )
        } catch (_: Exception) {
            // 渲染失败静默跳过本帧，不中断采集
        }
        renderManager.isRenderShadow = true

        GlStateManager.color(1f, 1f, 1f, 1f)
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
     * 按权重随机挑本轮采集场景（LocateWeight/AdjustWeight/TrackWeight 三项设置
     * 自由配比，权重 0 = 该类本轮不出现；三项全 0 兜底为 TRACK，避免永远开不了球）。
     */
    private fun pickRoundMode(): Int {
        val locate = locateWeightValue.get().coerceAtLeast(0)
        val adjust = adjustWeightValue.get().coerceAtLeast(0)
        val track = trackWeightValue.get().coerceAtLeast(0)
        val total = locate + adjust + track
        if (total <= 0) return AimScene.TRACK

        var roll = (0 until total).random()
        roll -= locate
        if (roll < 0) return AimScene.LOCATE
        roll -= adjust
        return if (roll < 0) AimScene.ADJUST else AimScene.TRACK
    }

    /**
     * 开新一轮：按 [pickRoundMode] 权重随机决定场景，不同场景采用不同的出生偏角
     * 分布（数据语义由分布保证，训练侧 scene 真值不必再靠启发式区分）：
     * - LOCATE 定位：大偏角 ±(60..150)°、绝对俯仰 -60..+15°—— 整段「甩向新目标」
     *   的爆发-减速样本；
     * - ADJUST 调整：准星近旁 ±(3..11)°、相对俯仰 ±6° —— 剩余差 < FLICK 阈值 12°，
     *   命中前全部落入「贴身小角度修正」；
     * - TRACK 追踪：±(2..8)°、相对俯仰 ±4° —— 很快进判定圈，配合强制运动目标采集
     *   「压在目标身上」的连续跟随。
     * FakePlayer 模式下出生的是假玩家：只用偏角决定出生方位，落在玩家脚海拔的
     * 地平面（拟人模拟自己负责之后的行走/跳跃/strafe）。
     * 命中换位后玩家朝向 ≈ 旧目标方向，小偏转相对当前朝向采样 → 天然形成
     * 「连续修正链」数据（两次瞄准之间的小幅转场）。
     */
    private fun spawnBall(mode: Int = pickRoundMode()) {
        val player = mc.thePlayer ?: return

        roundMode = mode

        val eye = Vec3(player.posX, player.posY + player.getEyeHeight(), player.posZ)

        val yawOffset: Float
        var pitch: Float
        when (roundMode) {
            AimScene.LOCATE -> {
                yawOffset = (60f..150f).random() * (if ((0..1).random() == 0) 1f else -1f)
                pitch = (-60f..15f).random()
            }
            AimScene.ADJUST -> {
                yawOffset = (3f..11f).random() * (if ((0..1).random() == 0) 1f else -1f)
                pitch = (player.rotationPitch + (-6f..6f).random()).coerceIn(-75f, 60f)
            }
            else -> {
                // TRACK 轮先定本轮运动速度：BallSpeed > 0 用设定值，否则默认
                roundTrackSpeed = if (ballSpeedValue.get() > 0f) ballSpeedValue.get() else 4f
                yawOffset = (2f..8f).random() * (if ((0..1).random() == 0) 1f else -1f)
                pitch = (player.rotationPitch + (-4f..4f).random()).coerceIn(-75f, 60f)
            }
        }
        val yaw = player.rotationYaw + yawOffset

        if (fakePlayerMode) {
            val world = mc.theWorld
            if (world == null) {
                ballPos = null
                lastBallPos = null
                return
            }

            // FakePlayer 出生距离：走 FakePlayerDistance 范围（默认 4..7 格，
            // 可拉到 3..15），出生后一直疾跑朝玩家逼近、接触即完成本轮。
            // LOS 参考点取玩家眼高（见下方注释）；瞄准判定走 hitbox 射线。
            // v5.2 地形/视线检测：出生点被方块挡住（玩家眼睛 → 假玩家瞄准点的视线
            // 射线命中方块）时玩家完全看不到目标，只能乱转视角搜索。被挡就
            // 换 ±(15..60)° 方向与新距离重试；重试用尽仍被挡则接受最后的候选
            // （运行中的 LOS 卡死重开作为兜底）。
            val aimY = player.posY + player.getEyeHeight()
            var spawnYaw = yaw
            var spawnX = eye.xCoord
            var spawnZ = eye.zCoord
            var spawnTries = 0
            while (spawnTries++ < 5) {
                val fakeDist = fakePlayerDistanceValue.random
                val dir = RotationUtils.getVectorForRotation(spawnYaw, pitch)
                spawnX = eye.xCoord + dir.xCoord * fakeDist
                spawnZ = eye.zCoord + dir.zCoord * fakeDist
                if (world.rayTraceBlocks(eye, Vec3(spawnX, aimY, spawnZ)) == null) break
                spawnYaw = yaw + (15f..60f).random() * (if ((0..1).random() == 0) 1f else -1f)
            }

            val fake = fakePlayerInstance
                ?: EntityOtherPlayerMP(world, player.gameProfile).also { fakePlayerInstance = it }

            // 出生：脚海拔 = 玩家所在平面，prev/lastTick 全部对齐出生点（首轮不做插值拖尾）
            fake.setPosAndPrevPos(Vec3(spawnX, player.posY, spawnZ), lastTickPos = Vec3(spawnX, player.posY, spawnZ))
            val faceYaw = Math.toDegrees(Math.atan2(player.posZ - spawnZ, player.posX - spawnX)).toFloat() - 90f
            fake.rotationYaw = faceYaw
            fake.prevRotationYaw = faceYaw
            fake.rotationYawHead = faceYaw
            fake.prevRotationYawHead = faceYaw
            fake.renderYawOffset = faceYaw
            fake.prevRenderYawOffset = faceYaw
            fake.limbSwing = 0f
            fake.limbSwingAmount = 0f
            fake.prevLimbSwingAmount = 0f
            fake.isSwingInProgress = false
            fake.swingProgressInt = 0
            fake.swingProgress = 0f

            // 拟人模拟状态重置：先给一拍起跳/strafe 延迟，出生不至于瞬间机械感
            fakeVelY = 0f
            fakeJumpTicks = (15..60).random()
            fakeStrafeYawOffset = 0f
            fakeStrafeTicks = 0
            fakeSwingTicks = 0

            // 出生点视线检测参考点已按眼高计算（aimY）；ballPos 同步用眼高 ——
            // ballPos 仅作 LOS / 距离 / 箭头参考（瞄准判定走 hitbox 射线）。
            // v5.2 俯仰去偏历史：旧值 1.2（胸口）恒低于玩家眼高 1.62，会让追踪
            // 数据系统性朝下压；抬到视线同高后 LOS 参考点保持中性。
            ballPos = Vec3(spawnX, aimY, spawnZ)
            lastBallPos = ballPos

            aimTicks = 0
            roundTicks = 0
            fakeBlockedTicks = 0
            fakeAimTicks = 0
            resetBallMovement()
            return
        }

        val dist = distanceValue.random
        var pos = ballPosition(eye, yaw, pitch, dist)

        // 兜底：确保球不落到脚底下（眼睛以下 1.2 格以内）
        var tries = 0
        while (pos.yCoord < player.posY + 0.4 && tries++ < 5) {
            pitch = (-60f..-5f).random()
            pos = ballPosition(eye, yaw, pitch, dist)
        }

        ballPos = pos
        lastBallPos = pos

        aimTicks = 0
        roundTicks = 0
        resetBallMovement()
    }

    private fun ballPosition(eye: Vec3, yaw: Float, pitch: Float, dist: Float): Vec3 {
        val dir = RotationUtils.getVectorForRotation(yaw, pitch)
        return eye.addVector(dir.xCoord * dist, dir.yCoord * dist, dir.zCoord * dist)
    }

    /**
     * 结束本轮：flush 缓冲并按「本轮是否成功完成」决定是否入库。
     * v5.2 只有成功完成的轮才入库 —— Ball 模式成功 = 命中（点击 / 持续瞄准达标）；
     * FakePlayer 模式无命中交互，成功 = 假玩家接触玩家（本轮「出生 → 逼近 → 跟随
     * 瞄准」作为完整有效的追踪数据入库）。超时/未命中的轮视为搜索轮整体丢弃：
     * 找不到目标期间乱转视角的数据不再污染模板库（分段器层另有方向一致性兜底
     * 过滤，见 [AimSegmenter]）。
     */
    private fun finishRound(hit: Boolean) {
        flushTick()

        val snapshot = synchronized(buffer) { ArrayList(buffer) }
        buffer.clear()

        if (hit) {
            val player = mc.thePlayer
            player?.playSound("random.orb", 0.7f, 1.4f)
        }

        if (hit && snapshot.isNotEmpty()) {
            AimDatasetStore.persist(snapshot, report = false)
        }

        spawnBall()
    }

    /**
     * 是否处于攻击间隔窗口内：距上次攻击不超过 [combatGapTicks]（[combatGapValue]
     * 秒，默认 1 秒）。Combat 的录制单位 = 本次攻击 → 下一次攻击之间的段：
     * 攻击 tick 开段（先补录预滚），下一次攻击 tick 结算上一段并开新段；
     * 窗口内没有再次攻击 → 整段舍弃。
     */
    private fun inCombatWindow(): Boolean =
        lastAttackTick != Long.MIN_VALUE && tickCounter - lastAttackTick <= combatGapTicks

    /**
     * 战斗窗口内的目标锁定实体：最近一次攻击（[onAttack]）的目标。
     * 仅当实体仍存活且还在当前世界时返回；否则 null → 回退「最近任意生物」。
     */
    private fun lockedCombatTarget(player: EntityLivingBase): EntityLivingBase? =
        combatTarget?.takeIf {
            it !== player && !it.isDead &&
                mc.theWorld?.loadedEntityList?.contains(it) == true
        }

    private fun flushTick() {
        val gcd = RotationUtils.getFixedAngleDelta()
        val player = mc.thePlayer

        // 击杀确认立即结算当前段：目标死亡/离场后击杀后乱看（对尸体、找下一个目标）
        // 不入库 —— 已录的接近段（含致命一击）提交保留并写终止标记；同时释放实体
        // 引用，避免长期持有已回收的 Entity
        if (combatMode) {
            val target = combatTarget
            if (target != null && (target.isDead || target.health <= 0f ||
                    mc.theWorld?.loadedEntityList?.contains(target) != true)
            ) {
                combatTarget = null
                lastAttackTick = Long.MIN_VALUE
                finalizePendingSegment(gcd, terminate = true, checkQuality = true, reason = "击杀确认")
                writeSegmentTerminator(gcd)
            }
        }

        val inWindow = inCombatWindow()

        // 攻击间隔超时：距上次攻击 [combatGapValue] 秒内没有再次攻击 → 整段舍弃
        // （当前段不满足「本次攻击 → 下一次攻击」的连击节奏，判定为孤立攻击/乱点）
        if (combatMode && !inWindow && pendingSegment.isNotEmpty()) {
            discardPendingSegment(gcd, "${combatGapValue.get()}s 内没有再次攻击")
        }

        // 窗口结束后即释放实体引用
        if (!inWindow) combatTarget = null

        // 采集有效性判据（hardInvalid = 本 tick 数据不可信）：
        // - 静默 spoof（currentRotation，KillAura/Scaffold/AI 转头等）只改发包旋转，
        //   第一人称视角不动 —— accYaw（setAngles 原始鼠标增量）与 needed/scene 真值
        //   （基于真实视角）都是纯人类数据，正常录制（否则边打边录会被静默转头模块
        //   整场跳过，数据几乎不增长且无任何提示）；
        // - 可见旋转接管（isViewOverridden，ApplyServerSide=false 直接写视角）/
        //   NoRotateSet（冻结服务端旋转）/ FreeLook 第三人称：视角或旋转上下文被
        //   模块改写，真值被污染，跳过。
        val viewOverridden = RotationUtils.isViewOverridden
        val hardInvalid = player == null || viewOverridden || NoRotateSet.shouldModify(player) || (FreeLook.handleEvents() && mc.gameSettings.thirdPersonView != 0)

        if (hardInvalid) {
            skippedTicks++
            if (viewOverridden) skippedByViewTakeover++
            accYaw = 0f
            accPitch = 0f
            preBuffer.clear() // 接管期间的 tick 也不允许之后被补录进数据集

            // 接管打断当前段：已录数据不回撤（提交保留）+ 写段落终止标记
            finalizePendingSegment(gcd, terminate = true, checkQuality = false, reason = "旋转被接管")
            // 全零样本只作为「段落终止标记」：仅在上一 flush 刚写过真实样本的转折点
            // 连续写 EXIT_TICKS + GAP_TICKS 条（3+2），保证分段器的退出滞回
            // （连续 3 tick speed < EXIT）立刻结算当前段、GAP 间隔也由零样本消耗，
            // 下一段首 tick 正常进入。之后连续失效 tick 不再写零：否则挂机十分钟
            // ≈ 1.2 万条全零样本白占 20 万 tick 预算，分段/训练侧本来也会逐条跳过。
            writeSegmentTerminator(gcd)
            return
        }

        // AimBall 模式下 entityDist 记录到小球；其余时候锁定本窗口内最近攻击的
        // 实体，没有战斗上下文（或该实体已失效）时回退最近活体生物
        val ball = ballPos
        val lockedTarget = if (inWindow && !aimBallMode) lockedCombatTarget(player) else null
        val entityDist = when {
            aimBallMode ->
                ball?.let { player.getDistance(it.xCoord, it.yCoord, it.zCoord).toFloat() } ?: 0f
            lockedTarget != null -> player.getDistanceToEntity(lockedTarget)
            else -> nearestEntityDistance(player)
        }

        val (neededYaw, neededPitch) = currentAimDiff(player, lockedTarget)
        val neededDiff = hypot(neededYaw, neededPitch)

        // 场景地面真值（AimScene 三分类，与运行时 / train.py 同判据同阈值）：
        // - AimBall 模式：aimed（准星在小球判定圈内，updateBall 本 tick 已更新，
        //   此处快照 = 本 tick 转动完成后的状态）= 准星在目标上 → TRACK；
        // - 普通模式：视线射线（player 实际朝向）落在可见实体 hitbox 上 → TRACK；
        // - 其余按 neededDiff 分 定位（>= 12°）/ 调整。
        // 训练侧直接拿 scene(i-1) 作 pair 类别标签 —— 静止目标上追踪（drift≈0、速度低）
        // 这类启发式永远错标成「微调」的数据，由观测真值正确归入 TRACK。
        val onTarget = if (aimBallMode) {
            aimed
        } else {
            AimScene.crosshairOnEntity(player.rotationYaw, player.rotationPitch)
        }
        val scene = AimScene.classify(onTarget, entityDist > 0f, neededDiff)

        val sample = TickSample(
            yawDelta = accYaw,
            pitchDelta = accPitch,
            moveSpeed = hypot(player.motionX, player.motionZ).toFloat(),
            entityDist = entityDist,
            gcd = gcd,
            neededYaw = neededYaw,
            neededPitch = neededPitch,
            scene = scene,
        )

        if (!combatMode) {
            // AimBall 模式：逐 tick 直接入库（无段结算逻辑）
            synchronized(buffer) {
                buffer.add(sample)
            }
            accYaw = 0f
            accPitch = 0f
            lastFlushValid = true
            return
        }

        // Combat 段录制：段内（距上次攻击 ≤ combatGapValue 秒）→ 录入未决段；段外 → 预滚缓冲
        if (inWindow) {
            // 本 tick 刚发生攻击且已有未决段：本次攻击 = 上一段（攻击 N → 攻击 N+1）
            // 的收尾 —— 先按质量检查结算上一段，再开新段
            if (lastAttackTick == tickCounter && pendingSegment.isNotEmpty()) {
                finalizePendingSegment(gcd, terminate = false, checkQuality = true, reason = "下一次攻击结算")
            }
            // 段未开始（攻击 tick 开段 / 上一段刚被结算或舍弃）：先补录预滚的
            // 攻击前样本，衔接成完整甩头段
            if (pendingSegment.isEmpty()) {
                pendingSegment.addAll(preBuffer)
                preBuffer.clear()
            }
            pendingSegment.add(sample)
        } else {
            // 攻击预滚：窗口外样本滚入缓冲（只留最近 PRE_ROLL_TICKS 条），
            // 攻击发生、开新段时补录 —— 第一击之前的甩头段不再整段丢失
            preBuffer.addLast(sample)
            while (preBuffer.size > PRE_ROLL_TICKS) {
                preBuffer.removeFirst()
                prerollDroppedTicks++
            }
        }
        accYaw = 0f
        accPitch = 0f
    }

    /**
     * 结算未决段：通过质量检查（[checkQuality]，整段累计旋转 ≤ 段首 needed ×
     * [ROTATION_NEEDED_MAX_RATIO]）后写入主缓冲。[terminate] = 段在此真正结束
     * （击杀/接管/手动停止）→ 追加段落终止标记；false = 由下一次攻击衔接（连续
     * 连击数据保持连续，不插终止标记）。质量检查不过 → 整段舍弃（[discardPendingSegment]）。
     */
    private fun finalizePendingSegment(gcd: Float, terminate: Boolean, checkQuality: Boolean, reason: String) {
        if (pendingSegment.isEmpty()) return

        if (checkQuality) {
            var rotation = 0f
            for (s in pendingSegment) rotation += hypot(s.yawDelta, s.pitchDelta)
            val first = pendingSegment.first()
            val needed = hypot(first.neededYaw, first.neededPitch)
            // 段首 needed 过小（开段时准星已压在目标上）时比值无意义，跳过检查
            // —— 保住「追踪后点击」这类 TRACK 段，避免任何微小旋转都被判超
            if (needed >= 1f && rotation > needed * ROTATION_NEEDED_MAX_RATIO) {
                discardPendingSegment(
                    gcd,
                    "整段旋转 ${rotation.roundToInt()}° > 段首 needed ${needed.roundToInt()}° × " +
                        "${ROTATION_NEEDED_MAX_RATIO}（$reason）"
                )
                return
            }
        }

        synchronized(buffer) {
            buffer.addAll(pendingSegment)
            if (terminate) {
                repeat(AimSegmenter.EXIT_TICKS + AimSegmenter.GAP_TICKS) {
                    buffer.add(TickSample(gcd = gcd))
                }
            }
        }
        pendingSegment.clear()
        lastFlushValid = !terminate
    }

    /** 舍弃未决段：直接丢弃（未决数据从未进 [buffer]），并补写段落终止标记防止上一段与后续数据被并成一段。 */
    private fun discardPendingSegment(gcd: Float, reason: String) {
        val ticks = pendingSegment.size
        pendingSegment.clear()
        writeSegmentTerminator(gcd)
        if (debugDiscardValue.get() && ticks > 0) {
            chat("§7[§bAI Aim§7] §cDiscarded §f$ticks§c ticks §7($reason)")
        }
    }

    /**
     * 段落终止标记：仅在 buffer 尾部还是未终结的真实样本时，连续写
     * EXIT_TICKS + GAP_TICKS 条全零样本（给分段器的退出滞回 + 段间隔）。
     */
    private fun writeSegmentTerminator(gcd: Float) {
        if (!lastFlushValid) return
        synchronized(buffer) {
            repeat(AimSegmenter.EXIT_TICKS + AimSegmenter.GAP_TICKS) {
                buffer.add(TickSample(gcd = gcd))
            }
        }
        lastFlushValid = false
    }

    /**
     * 当前 tick 的瞄准点剩余差 (neededYaw, neededPitch)，raw（无 wander、无 gcd 量化）。
     * AimBall 模式 → 小球球心方向差（几何同 updateBall 命中判定）；否则 → 锁定的
     * 战斗目标（[lockedCombatTarget]）或最近活体生物 hitbox 上离视线最近的点与准星的
     * 角度差（人手瞄准的几何语义）。
     * 无目标返回 (0,0)。快照时机 = flushTick 末尾，即本 tick 转动**之后**的剩余差；
     * 因此训练/运行时预测「本 tick 增量」所用特征必须取 needed(i-1)（见 TickSample 注释，
     * 与 train.py / RotationNet 三处同语义）。
     */
    private fun currentAimDiff(player: EntityLivingBase, lockedTarget: EntityLivingBase?): Pair<Float, Float> {
        val ball = ballPos
        if (aimBallMode && fakePlayerMode) {
            // FakePlayer 采集：needed 与普通实体同语义 —— hitbox 上离眼睛最近点的
            // 角度差（而非固定头部点的角度差），录制分布与运行时真实对战一致。
            val fake = fakePlayerInstance ?: return 0f to 0f
            return hitboxAimDiff(player, fake)
        }
        if (aimBallMode && ball != null) {
            val toBall = RotationUtils.toRotation(ball)
            return RotationUtils.angleDifference(toBall.yaw, player.rotationYaw) to
                (toBall.pitch - player.rotationPitch)
        }

        val target = lockedTarget ?: mc.theWorld?.loadedEntityList?.asSequence()
            ?.filterIsInstance<EntityLivingBase>()
            ?.filter { it !== player && !it.isDead }
            ?.minByOrNull { player.getDistanceToEntity(it) }
            ?: return 0f to 0f

        return hitboxAimDiff(player, target)
    }

    /**
     * 目标 hitbox 上离眼睛最近点（眼睛坐标钳入包围盒）与当前视角的角度差 ——
     * 人手瞄准的几何语义，FakePlayer 采集与普通实体共用同一实现。
     */
    private fun hitboxAimDiff(player: EntityLivingBase, target: Entity): Pair<Float, Float> {
        val eyes = Vec3(player.posX, player.posY + player.getEyeHeight(), player.posZ)
        val bb = target.hitBox
        val point = Vec3(
            eyes.xCoord.coerceIn(bb.minX, bb.maxX),
            eyes.yCoord.coerceIn(bb.minY, bb.maxY),
            eyes.zCoord.coerceIn(bb.minZ, bb.maxZ),
        )
        val rot = RotationUtils.toRotation(point)
        return RotationUtils.angleDifference(rot.yaw, player.rotationYaw) to (rot.pitch - player.rotationPitch)
    }

    /**
     * 准星（玩家实际朝向的视线射线）是否压在假玩家 hitbox 上：hitbox 相交 +
     * 交点无方块遮挡 —— 与普通实体的 [AimScene.crosshairOnEntity] 同判据
     * （[FAKE_HIT_RANGE] = AimScene.FACE_RANGE），保证采集端 TRACK 真值与
     * 运行时场景判定同分布。
     */
    private fun crosshairOnFakeHitbox(player: EntityPlayer, fake: EntityOtherPlayerMP): Boolean {
        val eyes = Vec3(player.posX, player.posY + player.getEyeHeight(), player.posZ)
        val look = RotationUtils.getVectorForRotation(player.rotationYaw, player.rotationPitch)
        val end = Vec3(
            eyes.xCoord + look.xCoord * FAKE_HIT_RANGE,
            eyes.yCoord + look.yCoord * FAKE_HIT_RANGE,
            eyes.zCoord + look.zCoord * FAKE_HIT_RANGE,
        )
        val hit = fake.hitBox.calculateIntercept(eyes, end) ?: return false
        val world = mc.theWorld ?: return false
        return world.rayTraceBlocks(eyes, hit.hitVec) == null
    }

    // 只统计存活生物实体：掉落物/尸体会把 entityDist 拉成噪声，且与 currentAimDiff
    // （needed 的目标集，过滤 isDead）保持一致，避免「距离指向尸体、瞄准点指向活体」的矛盾配对
    private fun nearestEntityDistance(player: EntityLivingBase): Float =
        mc.theWorld?.loadedEntityList?.asSequence()
            ?.filterIsInstance<EntityLivingBase>()
            ?.filter { it !== player && !it.isDead }
            ?.minOfOrNull { player.getDistanceToEntity(it) }
            ?: 0f
}
