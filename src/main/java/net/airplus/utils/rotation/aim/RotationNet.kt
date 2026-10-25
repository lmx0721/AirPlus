/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.rotation.aim

import com.google.gson.Gson
import net.airplus.lang.translation
import net.airplus.utils.client.chat
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * 路线 B：MLP 推理层。离线用 tools/aim_trainer/train.py 按段分类（初次定位 flick /
 * 追踪 track / 微调 fine，见 [AimSegmentClass]）分别训练的小网络（9→24→24→2 × 3），
 * 权重从 rotations/net_weights.json 加载，输出为 gcd 单位的 per-tick 增量。
 *
 * v4 权重布局：{"version":4,"classes":{"flick":{inputNorm,layers},"track":...,"fine":...}}
 * 每个类别独立训练/归一化。v1/v2/v3 的输入语义不同，直接拒绝回退 Default；
 * v4 缺某类别模型时该类别逐 tick 静默回退 Default（不报缺失）。
 *
 * 输入为 v4 对齐坐标系分解（与 train.py 三处共用同一语义：训练 / rollout / 运行时）：
 * 把剩余差方向作为坐标系，网络学「径向速度剖面 + 瞄准点漂移 + 横向修正」，与绝对朝向解耦。
 * - dir = (neededYaw, neededPitch) / neededDiff 是剩余差方向的单位向量，
 *   Run 创建时锁定（方向重启阈值判断不变）；
 * - velAlong / velCross 是上一 tick 带符号速度在该坐标系下的径向 / 横向投影；
 * - driftAlong / driftCross 是 **瞄准点漂移** d = neededNow - neededPrev + velPrev
 *   （度/tick，raw needed 差分补偿自身增量——详见 AiGeneratedDiff 一侧）在该坐标系
 *   下的径向 / 横向投影。追踪目标时漂移项非零且持续，是 TRACK 类的关键特征；
 *   静止点微调时漂移≈0。
 *
 * 与训练侧 pair 级标签同一规则同一参数，训练分布与运行路由严格一致，
 * 无需 Run 锁定类别。
 *
 * v5 场景真值路由：predict 收 [AimScene] 的 scene（录制/运行两端同一判据），
 * fromScene 直接映射类别，**无启发式兜底**（开发版本无旧格式兼容）——「准星在实体上
 * 静止追踪」（drift≈0、speed 低）不再被速度判据误路由到 FINE。scene==0（无目标
 * 上下文）直接返回 null 交回 Default。predict 的 remDiff/dir/特征全部由 rawNeeded
 * （未加 wander）计算，与训练快照同分布；wander 由 RotationUtils 单独传入，按尾部
 * 衰减叠加在输出增量上（见 predict 内部）。返回 null 表示无法生成（权重缺失 /
 * 剩余角度过小 / 该类别无模型），调用方回退 Default 管线。
 */
object RotationNet {

    private const val INPUT_SIZE = 9
    private const val MIN_DIFF_TO_START = 2f
    private const val DIRECTION_RESTART_ANGLE = 30f

    private val GSON = Gson()

    private class LayerDTO(val w: Array<FloatArray> = arrayOf(), val b: FloatArray = floatArrayOf(), val act: String = "tanh")
    private class ClassNet(
        val inputNorm: FloatArray = floatArrayOf(10f, 1f, 1f, 0.1f, 0.1f, 1f, 1f, 1f, 1f),
        val layers: List<LayerDTO> = emptyList(),
    )
    private class NetDTO(
        val version: Int = 4,
        val classes: Map<String, ClassNet> = emptyMap(),
    )

    private class Layer(val w: Array<FloatArray>, val b: FloatArray, val useTanh: Boolean)
    private class Model(val inputNorm: FloatArray, val layers: List<Layer>)

    @Volatile
    private var classModels: Map<AimSegmentClass, Model> = emptyMap()

    @Volatile
    private var lastModified = Long.MIN_VALUE

    @Volatile
    private var warnedMissing = false

    fun loadIfChanged(force: Boolean = false) {
        val file = java.io.File(AimDatasetStore.dir, "net_weights.json")
        val mtime = if (file.isFile) file.lastModified() else 0L
        if (force || mtime != lastModified) {
            lastModified = mtime
            classModels = emptyMap()
            parse(AimDatasetStore.readNetWeights())
            warnedMissing = false
        }
    }

    private fun parseModel(inputNorm: FloatArray?, layerDTOs: List<LayerDTO>?): Model? {
        if (inputNorm == null || layerDTOs == null) return null
        if (inputNorm.size != INPUT_SIZE) return null
        if (inputNorm.any { !it.isFinite() || it <= 0f }) return null

        val parsed = ArrayList<Layer>(layerDTOs.size)
        var expectedInput = INPUT_SIZE
        for (layer in layerDTOs) {
            if (layer.b.size != layer.w.size || layer.w.isEmpty()) return null
            if (layer.w[0].size != expectedInput) return null
            // NaN/Inf 一旦进入权重会污染整个前向链，最终把 NaN 发进 C03 包，必须逐元素拒绝
            if (layer.b.any { !it.isFinite() } || layer.w.any { row -> row.any { !it.isFinite() } }) return null
            // Gson Unsafe 实例化不会走 Kotlin 默认值：缺 act 字段时是 null 而非 "tanh"
            parsed.add(Layer(layer.w, layer.b, layer.act?.equals("tanh", ignoreCase = true) == true))
            expectedInput = layer.b.size
        }

        if (parsed.size < 2 || parsed.last().b.size != 2) return null
        return Model(inputNorm, parsed)
    }

    private fun parse(json: String?) {
        if (json == null) return

        try {
            val dto = GSON.fromJson(json, NetDTO::class.java)
            // 只接受 v4 分类别布局：v1/v2/v3 的输入语义与 v4 的 9 维不同（缺漂移维度），
            // 直接拒绝 → 无权重 → 回退 Default（重训后恢复）
            if (dto.version != 4) return

            val models = HashMap<AimSegmentClass, Model>()
            for ((key, classNet) in dto.classes) {
                val cls = AimSegmentClass.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
                    ?: continue
                // 单类独立校验：一类的权重结构损坏只丢该类（该类运行时回退 Default），
                // 不拖垮整个文件。
                val model = try {
                    parseModel(classNet.inputNorm, classNet.layers)
                } catch (t: Throwable) {
                    net.airplus.utils.client.ClientUtils.LOGGER.error("[RotationNet] Invalid class '$key'.", t)
                    null
                } ?: continue
                models[cls] = model
            }
            if (models.isEmpty()) return  // 内容全部无效当缺失处理（不给空 map 撑门面）
            classModels = models
        } catch (t: Throwable) {
            net.airplus.utils.client.ClientUtils.LOGGER.error("[RotationNet] Failed to parse net_weights.json.", t)
        }
    }

    /**
     * 进行中的一次旋转：起始方向 + 时长估计 + 总角度差。
     */
    private class Run(
        val dirYaw: Float, val dirPitch: Float,
        val duration: Int, val totalDiff: Float,
    ) {
        var tick = 0
    }

    private var run: Run? = null

    fun predict(
        rawYaw: Float, rawPitch: Float,
        wanderYaw: Float, wanderPitch: Float,
        driftYaw: Float, driftPitch: Float,
        velYaw: Float, velPitch: Float,
        moveSpeed: Float, entityDist: Float,
        gcd: Float,
        scene: Int = 0,
    ): Pair<Float, Float>? {
        loadIfChanged()

        val neededDiff = hypot(rawYaw, rawPitch)

        // 早退保护在 dir 分解之前：neededDiff < 2° 时 cancelled，同时保证
        // neededDiff > 0，dir 单位向量不会除零（T2 顺序确认）。
        if (neededDiff < MIN_DIFF_TO_START) {
            run = null
            return null
        }

        // v5 只认场景真值路由：scene==0（无目标上下文）→ null → 调用方回退 Default。
        val routedClass = AimSegmentClass.fromScene(scene) ?: return null
        val model = classModels[routedClass]

        if (model == null) {
            // 权重完全缺失才提示一次；单类别缺失静默回退 Default 管线
            if (classModels.isEmpty() && !warnedMissing) {
                warnedMissing = true
                chat(translation("aim.missingData"))
            }
            return null
        }
        val net = model.layers
        val inputNorm = model.inputNorm

        val speed = hypot(velYaw, velPitch)

        val active = run?.takeIf {
            angleBetween(it.dirYaw, it.dirPitch, rawYaw, rawPitch) < DIRECTION_RESTART_ANGLE
        } ?: Run(
            dirYaw = rawYaw / neededDiff,
            dirPitch = rawPitch / neededDiff,
            duration = (neededDiff / speed.coerceAtLeast(3f)).roundToInt().coerceIn(3, 40),
            totalDiff = neededDiff,
        ).also { run = it }

        val progress = active.tick.toFloat() / active.duration
        val moving = if (moveSpeed > 0.1f) 1f else 0f
        val movingProgress = 0.1f * moving + 0.9f * progress

        // v4 对齐分解：dir 在 Run 创建时锁定；velAlong/velCross 与 driftAlong/driftCross
        // 是上一 tick 速度 / 瞄准点漂移在该坐标系下的径向/横向投影，公式与 train.py 完全一致。
        // 不做额外 clamp，数值尺度交给 inputNorm（数据 95 分位）归一化。
        val dirYaw = active.dirYaw
        val dirPitch = active.dirPitch
        val velAlong = velYaw * dirYaw + velPitch * dirPitch
        val velCross = velYaw * -dirPitch + velPitch * dirYaw
        val driftAlong = driftYaw * dirYaw + driftPitch * dirPitch
        val driftCross = driftYaw * -dirPitch + driftPitch * dirYaw

        val input = floatArrayOf(
            neededDiff / inputNorm[0],      // remDiff：剩余角度差幅值（恒正）
            velAlong / inputNorm[1],        // 正 = 正在接近目标
            velCross / inputNorm[2],        // 路径弯曲度
            dirYaw / inputNorm[3],          // 保留「水平/垂直甩动」先验
            dirPitch / inputNorm[4],
            entityDist / inputNorm[5],
            movingProgress / inputNorm[6],  // 合并移动标志与进度
            driftAlong / inputNorm[7],      // 瞄准点漂移：径向（目标相对视线后退/逼近）
            driftCross / inputNorm[8],      // 瞄准点漂移：横向（目标横移）
        )

        val out = forward(net, input)

        active.tick++
        if (active.tick >= active.duration) {
            run = null
        }

        var outYaw = out[0] * gcd
        var outPitch = out[1] * gcd

        // 防过冲：单 tick 输出不允许超过剩余角度差，避免绕目标震荡。
        // 真实人类的过冲/回修数据在录制里天然存在，网络剩余 2° 以内交给 Default 管线收尾。
        val outDiff = hypot(outYaw, outPitch)
        if (outDiff > neededDiff && outDiff > 0f) {
            val scale = neededDiff / outDiff
            outYaw *= scale
            outPitch *= scale
        }

        // 拟真游走（J2 raw/wander 分离）：特征/分类/方向锁定全部用 raw（与训练分布一致，
        // wander 不再污染 remDiff/dir/12° 分类边界）；wander 作为「收尾/跟踪阶段注视点
        // 微游走」按尾部衰减叠加到输出增量 —— remDiff >= 12°（非收尾段）fade=1 时
        // wander ≤0.9° 相对总角度可忽略，< 12° 后逐渐成为主要微修正来源。
        val wanderFade = (neededDiff / AimSegmentClass.FLICK_MIN_DIFF_DEG).coerceIn(0f, 1f)
        outYaw += wanderYaw * wanderFade
        outPitch += wanderPitch * wanderFade

        return outYaw to outPitch
    }

    private fun forward(net: List<Layer>, x: FloatArray): FloatArray =
        net.fold(x) { acc, layer ->
            FloatArray(layer.b.size) { i ->
                var sum = layer.b[i]
                for (j in acc.indices) sum += layer.w[i][j] * acc[j]
                if (layer.useTanh) tanh(sum) else sum
            }
        }

    private fun angleBetween(yaw1: Float, pitch1: Float, yaw2: Float, pitch2: Float): Float {
        val len1 = hypot(yaw1, pitch1)
        val len2 = hypot(yaw2, pitch2)
        if (len1 <= 0f || len2 <= 0f) return 180f

        val cos = ((yaw1 * yaw2 + pitch1 * pitch2) / (len1 * len2)).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(cos.toDouble())).toFloat()
    }
}
