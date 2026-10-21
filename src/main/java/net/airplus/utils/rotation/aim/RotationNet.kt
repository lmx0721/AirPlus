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
 * 路线 B：MLP 推理层。离线用 tools/aim_trainer/train.py 训练的小网络（6→24→24→2），
 * 权重从 rotations/net_weights.json 加载，输出为 gcd 单位的 per-tick 增量。
 *
 * 返回 null 表示无法生成（权重缺失 / 剩余角度过小），调用方回退 Default 管线。
 */
object RotationNet {

    private const val INPUT_SIZE = 6
    private const val MIN_DIFF_TO_START = 2f
    private const val DIRECTION_RESTART_ANGLE = 30f

    private val GSON = Gson()

    private class LayerDTO(val w: Array<FloatArray> = arrayOf(), val b: FloatArray = floatArrayOf(), val act: String = "tanh")
    private class NetDTO(
        val version: Int = 1,
        val inputNorm: FloatArray = floatArrayOf(60f, 60f, 30f, 16f, 1f, 1f),
        val layers: List<LayerDTO> = emptyList(),
    )

    private class Layer(val w: Array<FloatArray>, val b: FloatArray, val useTanh: Boolean)

    @Volatile
    private var layers: List<Layer>? = null

    @Volatile
    private var inputNorm = floatArrayOf(60f, 60f, 30f, 16f, 1f, 1f)

    @Volatile
    private var lastModified = Long.MIN_VALUE

    @Volatile
    private var warnedMissing = false

    fun loadIfChanged(force: Boolean = false) {
        val file = java.io.File(AimDatasetStore.dir, "net_weights.json")
        val mtime = if (file.isFile) file.lastModified() else 0L
        if (force || mtime != lastModified) {
            lastModified = mtime
            layers = null
            parse(AimDatasetStore.readNetWeights())
            warnedMissing = false
        }
    }

    private fun parse(json: String?) {
        if (json == null) return

        try {
            val dto = GSON.fromJson(json, NetDTO::class.java)
            if (dto.inputNorm.size != INPUT_SIZE) return

            val parsed = ArrayList<Layer>(dto.layers.size)
            var expectedInput = INPUT_SIZE
            for (layer in dto.layers) {
                if (layer.b.size != layer.w.size || layer.w.isEmpty()) return
                if (layer.w[0].size != expectedInput) return
                parsed.add(Layer(layer.w, layer.b, layer.act.equals("tanh", ignoreCase = true)))
                expectedInput = layer.b.size
            }

            if (parsed.size < 2 || parsed.last().b.size != 2) return

            inputNorm = dto.inputNorm
            layers = parsed
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
        neededYaw: Float, neededPitch: Float,
        velYaw: Float, velPitch: Float,
        moveSpeed: Float, entityDist: Float,
        gcd: Float,
    ): Pair<Float, Float>? {
        loadIfChanged()
        val net = layers

        if (net == null) {
            if (!warnedMissing) {
                warnedMissing = true
                chat(translation("aim.missingData"))
            }
            return null
        }

        val neededDiff = hypot(neededYaw, neededPitch)

        if (neededDiff < MIN_DIFF_TO_START) {
            run = null
            return null
        }

        val speed = hypot(velYaw, velPitch)

        val active = run?.takeIf {
            angleBetween(it.dirYaw, it.dirPitch, neededYaw, neededPitch) < DIRECTION_RESTART_ANGLE
        } ?: Run(
            dirYaw = neededYaw / neededDiff,
            dirPitch = neededPitch / neededDiff,
            duration = (neededDiff / speed.coerceAtLeast(3f)).roundToInt().coerceIn(3, 40),
            totalDiff = neededDiff,
        ).also { run = it }

        val progress = active.tick.toFloat() / active.duration
        val moving = if (moveSpeed > 0.1f) 1f else 0f

        val input = floatArrayOf(
            neededYaw / inputNorm[0],
            neededPitch / inputNorm[1],
            speed / inputNorm[2],
            entityDist / inputNorm[3],
            moving * inputNorm[4],
            progress * inputNorm[5],
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
