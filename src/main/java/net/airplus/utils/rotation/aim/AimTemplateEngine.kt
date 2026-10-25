/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.rotation.aim

import net.airplus.lang.translation
import net.airplus.utils.client.chat
import net.airplus.utils.kotlin.RandomUtils.nextFloat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 路线 A：模板库引擎。从 library.json 检索与当前上下文最相似的真实旋转段，
 * 把其 **完整 signed 增量序列** 旋转到当前剩余差方向的坐标系后，按 neededDiff /
 * totalDiff 比例整体缩放，逐 tick 重放。
 *
 * 相比旧的「归一化累计曲线」，signed 序列重放保留了段内的反向修正、过冲回拉与
 * yaw/pitch 曲率等真实人手特征，只在幅值上做整体缩放。
 *
 * v5.1 改动：
 * - 闭环微导引（转头执行）：重放不再是「选中即锁定」的纯开环 —— 每 tick 把剩余
 *   未播放的增量序列（方向旋转 + 幅值重标定）微导引回当前剩余差的坐标系，移动
 *   目标不再依赖 30° 方向重启才修正航向；
 * - 复读惩罚（模板选取）：加权随机检索对最近用过的段加距离罚项，同上下文不再
 *   连续复放同一段动作（类内候选不足时仍允许复用，不搜空）。
 *
 * v5.2 俯仰形状匹配（检索）：训练库的段存在系统性俯仰形状（如假玩家贴身追踪，
 *   段的 pitch 分量占比大且方向朝下）。若当前需要向上修正却抽到「下弯形状」的
 *   段，重放路径会先朝偏差侧弯曲再靠闭环微导引拉回 —— 表现为持续朝地面转动 +
 *   收尾命中偏差。检索距离加入「需要的俯仰分量占比 dnPitch/neededDiff」与模板
 *   段方向俯仰占比 segPitchSum/totalDiff（带符号 ∈ [-1,1]）之差，俯仰形状契合
 *   的段优先被抽中，从运行时侧压制重放路径的俯仰弯曲 / 低头漂移。
 *
 * 不变式（与 RotationNet v3 同一坐标系思想）：模板库里存的是录制时刻的绝对
 * 方向，检索只匹配幅值/上下文 —— 重放前必须把模板整段旋转对齐到当前剩余差
 * 方向（2D (yaw,pitch) 平面的带符号夹角）。否则目标在录制方向的反方向时，
 * 机器人会照着录制时的绝对朝向甩头 → 方向反转 + 单侧持续漂移。
 *
 * 返回 null 表示无法生成（数据缺失 / 剩余角度过小 / 段播放完毕 / 同类别无候选模板），
 * 调用方回退 Default 管线。
 */
object AimTemplateEngine {

    private const val K = 5
    private const val MIN_DIFF_TO_START = 2f
    private const val DIRECTION_RESTART_ANGLE = 30f
    private const val EPS = 0.1f

    /** 闭环微导引的剩余幅值重标定上下限：每 tick 把剩余序列总幅值对齐到当前 neededDiff，
     * clamp 限制单 tick 重标定深度 —— 不把帧间噪声放大成 bite 幅值抖动，形状仍保形。 */
    private const val RESTEER_SCALE_MIN = 0.5f
    private const val RESTEER_SCALE_MAX = 2f

    /** 近期已用模板（FIFO）：同一上下文连续抽到同一段会产生「复读机」动作，
     * 检索打分时对近期段加惩罚（非硬排除 —— 类内候选不足时仍允许复用）。 */
    private const val RECENT_KEEP = 3
    private const val REPEAT_PENALTY = 0.9f

    /**
     * 俯仰形状匹配距离权重（v5.2）：带符号俯仰占比差 ∈ [0,2] 乘以该权重计入
     * 检索距离。量级参考 ln(ratio) 项的典型分布（0~1）—— 0.45 使「需要上修
     * 却抽到下弯段」的错配明显劣于俯仰契合的候选，又不至于淹没幅值/速度特征。
     */
    private const val PITCH_SHAPE_WEIGHT = 0.45f

    /**
     * Fitts 定律系数：人手甩动时长随角度近似 1 + K·ln(角度比) 增长——大角度主要靠
     * 「转更久」而非「转更快」，峰值角速度受鼠标物理上限约束基本守恒。
     * 幅值仍按 neededDiff/totalDiff 精确缩放（总角度吻合），时长单独伸缩。
     */
    private const val FITTS_K = 0.5
    /** 时长缩放下限：过大（0.6）会在幅值比 < 0.3 时把重放缓成每 tick 几个 gcd 的
     * 极小增量蠕行 —— 表现为「突然进入极小幅度的转动而不是瞄准」，对战中致命。 */
    private const val MIN_DURATION_SCALE = 0.2f
    private const val MAX_DURATION_SCALE = 2.5f
    private const val MAX_RUN_TICKS = 100

    /** 幅值匹配带：模板总角度需要落在 neededDiff 的 [-4x, +4x] 内才参与检索，
     * 带外模板重放质量差（形状畸变/蠕行），宁可放弃匹配回退 Default 管线。 */
    private const val RATIO_MIN = 0.25f
    private const val RATIO_MAX = 4f

    @Volatile
    private var templates: List<Segment>? = null

    @Volatile
    private var lastModified = Long.MIN_VALUE

    @Volatile
    private var warnedMissing = false

    fun loadIfChanged(force: Boolean = false) {
        val file = AimDatasetStore.libraryFile
        val mtime = if (file.isFile) file.lastModified() else 0L
        if (force || mtime != lastModified) {
            lastModified = mtime
            templates = AimDatasetStore.readLibrary()
            warnedMissing = false
        }
    }

    /**
     * 进行中的一次旋转：旋转对齐后缩放的 per-tick signed 增量序列。
     * startDirYaw/Pitch 记录 Run 创建时锁定的剩余差方向（对齐基准），用于检测
     * 目标切换/移动（方向重启）。比较对象必须是锁定基准而不是上一 tick 的方向：
     * 对战里目标持续移动，剩余差方向会逐 tick 漂移，若与上一 tick 比较则漂移
     * 永远不触发重启，序列会朝偏离方向播完 —— 表现为「迟迟不瞄准移动目标」。
     */
    private class Run(
        val deltas: Array<Pair<Float, Float>>,
        val startDirYaw: Float, val startDirPitch: Float,
    ) {
        var tick = 0
    }

    private var run: Run? = null

    /** 近期已用模板的 FIFO 队列（相等性用 [Segment] data class equals）。 */
    private val recentTemplates = ArrayList<Segment>(RECENT_KEEP)

    // 亚 gcd 余数累加：Template 输出的小增量可能不足半个 gcd，直接四舍五入会产生
    // 连续死 tick 后突然跳一大块；把余量累计到下一 tick，凑满一个 gcd 才输出。
    private var carryYaw = 0f
    private var carryPitch = 0f

    /**
     * @param rawYaw / rawPitch 当前指向目标所需的剩余 yaw/pitch 角度差（每 tick 重算，
     * raw 无 wander —— 特征/分类/方向/缩放全部基于它，与训练分布一致）
     * @param wanderYaw / wanderPitch 拟真注视点游走偏移：由本引擎按尾部衰减叠加到
     * 输出增量（见 sample 内部），不进特征
     * @param driftYaw / driftPitch 上一 tick 的瞄准点漂移（度/tick，raw needed 差分补偿
     * 自身增量；决定 Track 分类与段落漂移引用同一个量）
     * @param scene 决策时刻的场景标签（AimScene，录制端逐 tick 真值 / 运行时按同一
     * 判据现场判定）。非 0 时直接映射类别路由，跳过漂移启发表 ——
     * 准星在实体上的静止追踪（drift≈0）不再误入 FINE。0 = 无场景（回退启发式）。
     * @param velYaw / velPitch 上一 tick 的实际旋转增量（当前角速度）
     * @param moveSpeed 玩家水平速度
     * @param entityDist 最近实体距离
     * @param gcd 当前灵敏度下的最小量化角（getFixedAngleDelta()）
     */
    fun sample(
        rawYaw: Float, rawPitch: Float,
        wanderYaw: Float, wanderPitch: Float,
        driftYaw: Float, driftPitch: Float,
        velYaw: Float, velPitch: Float,
        moveSpeed: Float, entityDist: Float,
        gcd: Float,
        scene: Int = 0,
    ): Pair<Float, Float>? {
        loadIfChanged()
        val libs = templates

        if (libs.isNullOrEmpty()) {
            if (!warnedMissing) {
                warnedMissing = true
                chat(translation("aim.missingData"))
            }
            return null
        }

        val neededDiff = hypot(rawYaw, rawPitch)

        if (neededDiff < MIN_DIFF_TO_START) {
            run = null
            carryYaw = 0f
            carryPitch = 0f
            return null
        }

        val current = run
        val active = if (
            current != null &&
            angleBetween(current.startDirYaw, current.startDirPitch, rawYaw, rawPitch) < DIRECTION_RESTART_ANGLE
        ) {
            current
        } else {
            // 新 Run 必须挂回 run：否则每 tick 都会再次 selectAndStart、永远只重放
            // deltas[0]（人手甩动的爆发首 tick），表现为每 tick 全新起甩的剧烈抽动。
            selectAndStart(
                libs, rawYaw, rawPitch, neededDiff, driftYaw, driftPitch,
                hypot(velYaw, velPitch), moveSpeed, entityDist, gcd, scene,
            )
                ?.also { run = it }
                ?: return null
        }

        // v5.1 闭环微导引：模板重放本质是开环 —— 段被选中时按当时的剩余差方向对齐并
        // 整体缩放后锁定，此后目标持续移动会让剩余差方向/幅值逐 tick 漂移，剩余序列
        // 仍朝旧方向播完，只有漂移超过 DIRECTION_RESTART_ANGLE(30°) 才丢弃重启 ——
        // 移动目标表现为「朝修正前的位置迟钝甩头」。这里每 tick 把**剩余未播放的**
        // 增量序列微导引回当前剩余差的坐标系：
        // - 方向：把剩余段总方向旋转 θ = atan2(叉积, 点积) 对齐当前剩余差方向（正交
        //   变换不破坏速度剖面/曲率/过冲特征）；
        // - 幅值：剩余总幅值重标定 neededDiff/remDiff（clamp [0.5, 2]）—— 序列始终
        //   精确落在当前瞄准点上，且不会因帧间噪声放大 bite 抖动。
        // 首 tick 的对齐在 selectAndStart 内已完成，此处重导引是幂等增量校正，无需特判。
        // 防过冲钳制（下方）仍以前后保险兜底。
        val remStart = active.tick
        if (remStart < active.deltas.size) {
            var remYaw = 0f
            var remPitch = 0f
            for (i in remStart until active.deltas.size) {
                remYaw += active.deltas[i].first
                remPitch += active.deltas[i].second
            }
            val remDiff = hypot(remYaw, remPitch)
            if (remDiff > EPS) {
                val dnYaw = rawYaw / neededDiff   // neededDiff >= MIN_DIFF_TO_START，不会除零
                val dnPitch = rawPitch / neededDiff
                val prYaw = remYaw / remDiff
                val prPitch = remPitch / remDiff
                val theta = Math.atan2(
                    (prYaw * dnPitch - prPitch * dnYaw).toDouble(),  // sin：剩余段→当前差的带符号夹角
                    (prYaw * dnYaw + prPitch * dnPitch).toDouble(),  // cos
                )
                val cosA = Math.cos(theta).toFloat()
                val sinA = Math.sin(theta).toFloat()
                val rescale = (neededDiff / remDiff).coerceIn(RESTEER_SCALE_MIN, RESTEER_SCALE_MAX)
                for (i in remStart until active.deltas.size) {
                    val (dy, dp) = active.deltas[i]
                    active.deltas[i] = Pair((dy * cosA - dp * sinA) * rescale, (dy * sinA + dp * cosA) * rescale)
                }
            }
        }

        if (active.tick >= active.deltas.size) {
            // 序列播放完但剩余差仍大：留给下次检索开新段 / Default 收尾
            run = null
            carryYaw = 0f
            carryPitch = 0f
            return null
        }

        var (yawDelta, pitchDelta) = active.deltas[active.tick]

        // 首 tick 只与当前角速度混合「幅值」，方向沿用模板（已在 selectAndStart
        // 中整体旋转对齐当前剩余差方向）。混合带符号向量会在上一 tick 刚过冲
        // 回拉时把首 tick 一半增量甩向反方向。
        if (active.tick == 0) {
            val mag = hypot(yawDelta, pitchDelta)
            if (mag > 0f) {
                val blended = mag * 0.5f + hypot(velYaw, velPitch) * 0.5f
                yawDelta = yawDelta / mag * blended
                pitchDelta = pitchDelta / mag * blended
            }
        }

        active.tick++
        if (active.tick >= active.deltas.size) {
            run = null
            carryYaw = 0f
            carryPitch = 0f
        }

        // 防过冲钳制：单 tick 输出不允超过当前剩余角度差（与 RotationNet 一致），
        // 否则越过后下一 tick 方向反号，触发方向重启 → 乒乓震荡。
        val outDiff = hypot(yawDelta, pitchDelta)
        if (outDiff > neededDiff && outDiff > 0f) {
            val scale = neededDiff / outDiff
            yawDelta *= scale
            pitchDelta *= scale
        }

        // 拟真游走（同 RotationNet）：特征/检索/缩放全 raw；wander 按尾部衰减叠加到
        // 输出增量（量化前叠加，让其进入亚 gcd 余数累计，表现为偶发的 1-gcd 微修正）
        val wanderFade = (neededDiff / AimSegmentClass.FLICK_MIN_DIFF_DEG).coerceIn(0f, 1f)
        yawDelta += wanderYaw * wanderFade
        pitchDelta += wanderPitch * wanderFade

        if (gcd <= 0f) return yawDelta to pitchDelta

        // 亚 gcd 量化 + 余数累计：先加上前期累计余量再取整，余量留到下一 tick
        val x = yawDelta + carryYaw
        val y = pitchDelta + carryPitch
        val qi = (x / gcd).roundToInt()
        val qj = (y / gcd).roundToInt()
        carryYaw = x - qi * gcd
        carryPitch = y - qj * gcd

        return (qi * gcd) to (qj * gcd)
    }

    /**
     * k-NN 检索：对数尺度距离（角度差量级 + 起始速度量级 + 移动状态 + 实体距离），
     * 加权随机取一条。所有特征统一 log 尺度，避免绝对差被角度项淹没。
     *
     * v4 类别过滤：先按三段共用 [AimSegmentClass] 判据把当前上下文分类（定位/追踪/
     * 微调），只在同类模板中检索 —— 微调场景不会抽到全力甩头模板，甩头也不会
     * 抽到蠕动模板。类内候选为空 → 返回 null → 调用方回退 Default 管线。
     *
     * v5 场景真值路由：[scene] 必须非 0（录制观测真值 → AimSegmentClass.fromScene
     * 直接映射类别），否则返回 null 由调用方回退 Default。模板侧同样只按 scene
     * 匹配 —— scene==0 的段落（无目标上下文）不会被任何类别选中。
     * 开发版本无旧格式兼容，不做启发式回退。
     */
    private fun selectAndStart(
        libs: List<Segment>, neededYaw: Float, neededPitch: Float, neededDiff: Float,
        driftYaw: Float, driftPitch: Float,
        speed: Float, moveSpeed: Float, entityDist: Float, gcd: Float, scene: Int,
    ): Run? {
        val contextClass = AimSegmentClass.fromScene(scene) ?: return null

        // 需要的俯仰分量占比（带符号 ∈ [-1,1]）：检索形状特征与段方向对齐共用
        val dnPitchFrac = neededPitch / neededDiff

        val candidates = libs.asSequence()
            .filter { it.totalDiff > 0f && it.duration >= 3 && it.gcd > 0f }
            .filter { AimSegmentClass.fromScene(it.scene) == contextClass }
            .map { template -> template to neededDiff / template.totalDiff }
            .filter { (_, ratio) -> ratio in RATIO_MIN..RATIO_MAX }
            .map { (template, ratio) ->
                // v5.1 复读惩罚：近期（最近 RECENT_KEEP 段）用过的模板在检索距离上加
                // 罚项 —— 受 1/(d+0.1) 权重平方级压低。是软惩罚而非硬排除：类内候选
                // 总量很少（尤其 FINE/TRACK 段）时不至于搜空导致回退 Default。
                // v5.2 俯仰形状匹配：模板段方向的带符号俯仰占比与当前需要的差计入
                // 距离（路径方向会被整体旋转对齐，但段内 pitch/yaw 的**形状分配**
                // 不变 —— 俯仰占比契合的段重放中途的弯曲 / 拉回量更小，命中更准）。
                val dtPitchFrac = template.pitchDeltas.sum() / template.totalDiff
                val base = abs(ln(ratio)) +
                    0.5f * abs(ln((speed + EPS) / (template.startSpeed + EPS))) +
                    0.3f * abs(ln((moveSpeed + EPS) / (template.moveSpeed + EPS))) +
                    0.3f * abs(ln((entityDist + 1f) / (template.entityDist + 1f))) +
                    PITCH_SHAPE_WEIGHT * abs(dtPitchFrac - dnPitchFrac)
                template to (base + if (template in recentTemplates) REPEAT_PENALTY else 0f)
            }
            .sortedBy { it.second }
            .take(K)
            .toList()

        if (candidates.isEmpty()) return null

        // 权重 1/(d+0.1) 的加权随机（浮点统一 Double 计算，避免累加精度不一致）
        val totalWeight = candidates.sumOf { (1.0 / (it.second + 0.1f).toDouble()) }
        var roll = nextFloat(0f, totalWeight.toFloat())
        var chosen = candidates.last().first
        for ((template, d) in candidates) {
            roll -= (1.0 / (d + 0.1f).toDouble()).toFloat()
            if (roll <= 0f) {
                chosen = template
                break
            }
        }

        // v5.1 记录最近使用：抽出后才入队（同一模板连用两次仍有可能 —— 类内只有它时）
        recentTemplates.remove(chosen)
        recentTemplates.add(chosen)
        while (recentTemplates.size > RECENT_KEEP) recentTemplates.removeAt(0)

        // 幅值按 ratio 整体缩放（重放总角度精确等于 neededDiff），时长按 Fitts 定律
        // 1 + K·ln(ratio) 伸缩后对累计曲线重采样，保留模板原速度剖面形状：
        // 大角度拉长时长而不是压扁速度；小角度（幅值比 << 1）必须允许等比压缩
        // 时长（下限 0.2），否则每 tick 只剩几个 gcd 的蠕动增量，对战响应极差。
        val ratio = neededDiff / chosen.totalDiff
        val n = chosen.yawDeltas.size
        val durationScale = (1f + FITTS_K * ln(ratio.toDouble())).toFloat()
            .coerceIn(MIN_DURATION_SCALE, MAX_DURATION_SCALE)
        val outLen = (n * durationScale).roundToInt().coerceIn(2, MAX_RUN_TICKS)
        val resampled = resample(chosen.yawDeltas, chosen.pitchDeltas, outLen)

        // 方向对齐（与 Neural v3 同一整数坐标思想的重放版）：把模板整段从「录制时刻
        // 的段方向 dt」旋转到「当前剩余差方向 dn」，夹角 θ = atan2(叉积, 点积)，
        // 2D (yaw, pitch) 平面的带符号旋转矩阵 [cos -sin; sin cos] 应用到每个
        // 缩放后的 delta。旋转是正交变换：速度剖面、曲率、过冲回拉、反向修正全部
        // 保形，只改变绝对朝向。若 dt 与 dn 几乎反平行，θ≈180° 完整翻转 —— 这正是
        // 让「录的是向右甩、目标在左边」场景能正确输出向左甩的关键。
        val segYawSum = chosen.yawDeltas.sum()
        val segPitchSum = chosen.pitchDeltas.sum()
        val dtYaw = segYawSum / chosen.totalDiff
        val dtPitch = segPitchSum / chosen.totalDiff
        val dnYaw = neededYaw / neededDiff   // neededDiff >= MIN_DIFF_TO_START = 2°，dn 不会除零
        val dnPitch = neededPitch / neededDiff
        val theta = Math.atan2(
            (dtYaw * dnPitch - dtPitch * dnYaw).toDouble(),  // sin：dt→dn 的带符号夹角
            (dtYaw * dnYaw + dtPitch * dnPitch).toDouble(),  // cos
        )
        val cosA = Math.cos(theta).toFloat()
        val sinA = Math.sin(theta).toFloat()

        val deltas = Array(resampled.size) { i ->
            val sy = resampled[i].first * ratio
            val sp = resampled[i].second * ratio
            Pair(sy * cosA - sp * sinA, sy * sinA + sp * cosA)
        }
        // 对齐基准方向锁定进 Run：方向重启判断以此为锚（见 Run 注释）
        return Run(deltas, dnYaw, dnPitch)
    }

    /**
     * 对 signed 增量序列做线性时间重采样：先累计成曲线，再在 [0, n] 上按 outLen
     * 均匀取样取差分。总角度精确不变，段内的反向修正/过冲回拉等形状特征保留。
     */
    private fun resample(yaw: List<Float>, pitch: List<Float>, outLen: Int): Array<Pair<Float, Float>> {
        val n = yaw.size
        if (outLen == n) return Array(n) { Pair(yaw[it], pitch[it]) }

        val cy = FloatArray(n + 1)
        val cp = FloatArray(n + 1)
        for (i in 0 until n) {
            cy[i + 1] = cy[i] + yaw[i]
            cp[i + 1] = cp[i] + pitch[i]
        }

        fun sampleY(s: Double): Float {
            val i = s.toInt().coerceIn(0, n - 1)
            val f = (s - i).toFloat()
            return cy[i] + (cy[i + 1] - cy[i]) * f
        }
        fun sampleP(s: Double): Float {
            val i = s.toInt().coerceIn(0, n - 1)
            val f = (s - i).toFloat()
            return cp[i] + (cp[i + 1] - cp[i]) * f
        }

        val out = Array(outLen) { Pair(0f, 0f) }
        var lastY = 0f
        var lastP = 0f
        for (i in 1..outLen) {
            val s = if (i == outLen) n.toDouble() else i * n.toDouble() / outLen
            val y = sampleY(s)
            val p = sampleP(s)
            out[i - 1] = Pair(y - lastY, p - lastP)
            lastY = y
            lastP = p
        }
        return out
    }

    private fun angleBetween(yaw1: Float, pitch1: Float, yaw2: Float, pitch2: Float): Float {
        val dot = yaw1 * yaw2 + pitch1 * pitch2
        val len1 = hypot(yaw1, pitch1)
        val len2 = hypot(yaw2, pitch2)
        if (len1 <= 0f || len2 <= 0f) return 180f
        val cos = (dot / (len1 * len2)).coerceIn(-1f, 1f)
        // kotlin.math 没有浮点 acos 的度数封装，手动转换
        return Math.toDegrees(kotlin.math.acos(cos.toDouble())).toFloat()
    }
}
