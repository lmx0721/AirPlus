/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.rotation.aim

import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import net.airplus.utils.client.ClientUtils.LOGGER
import net.airplus.utils.kotlin.SharedScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.airplus.utils.client.chat
import java.io.File
import java.io.FileReader
import java.io.FileWriter

/**
 * 单 tick 采样：本 tick 内累计的真实鼠标 yaw/pitch 增量 + 上下文 + 瞄准点。
 * yawDelta/pitchDelta 为原始角度（度），已含 GCD 量化（真实鼠标天然满足）。
 *
 * v2 新增 neededYaw/neededPitch：flushTick 时快照的「剩余瞄准角度差」（瞄准点相对
 * 当前视野方向的角度，宽/高单位同 yawDelta，无 wander 噪声）。
 * 注意采样时机：快照发生在本 tick 累计增量 flush **之后**（GameTickEvent 末尾），
 * 即 needed(i) = 第 i tick 转动完之后的剩余差。因此「预测增量 i」所用特征必须取
 * needed(i-1)（预测决策时机的状态）—— train.py / 运行时侧均有对应说明。
 * needed==0 的 invalidate 零样本 tick（AI 接管/无玩家等）在训练侧按快照缺失处理，
 * 漂移特征记 0；这些 tick 本来就不会进入分段。
 *
 * v5 新增 scene（AimScene 场景标签 = 录制时刻的观测真值，见 [AimScene]）：
 * 快照时机与 needed 相同（本 tick 转动完之后），因此「预测增量 i」的类别标签同样
 * 取 scene(i-1)。值域 0=无目标上下文，1=定位，2=调整，3=追踪 ——
 * 训练侧与运行时路由都只认 scene 真值，无启发式兜底（开发版本无旧格式兼容）。
 */
data class TickSample(
    val yawDelta: Float = 0f,
    val pitchDelta: Float = 0f,
    val moveSpeed: Float = 0f,
    val entityDist: Float = 0f,
    val gcd: Float = 0f,
    val neededYaw: Float = 0f,
    val neededPitch: Float = 0f,
    val scene: Int = 0,
)

/**
 * 一段旋转（flick 或连续修正）：per-tick 增量序列 + 段级上下文特征。
 *
 * aimDrift：段内平均瞄准点漂移（度/tick），由 AimSegmenter 从
 * TickSample.neededYaw/neededPitch 差分计算（公式与训练侧一致：d = needed(j) -
 * needed(j-1) + delta(j)），随库导出供诊断（检索路由只用 scene）。
 */
data class Segment(
    val yawDeltas: List<Float> = emptyList(),
    val pitchDeltas: List<Float> = emptyList(),
    val totalDiff: Float = 0f,
    val startSpeed: Float = 0f,
    val duration: Int = 0,
    val moveSpeed: Float = 0f,
    val entityDist: Float = 0f,
    val gcd: Float = 0f,
    val aimDrift: Float = 0f,
    /**
     * 段场景标签 = 段内 [TickSample.scene]（AimScene）的多数派。0 = 全段无标签
     * （无目标上下文，如空地乱转）：模板检索时该段不会被任何类别匹配。
     */
    val scene: Int = 0,
)

private data class DatasetFile(val version: Int = 2, val ticks: List<TickSample> = emptyList())
private data class LibraryFile(val version: Int = 2, val templates: List<Segment> = emptyList())

/**
 * 录制数据与模板库的磁盘持久化，目录为客户端数据目录下的 rotations/。
 * dataset.json 供离线训练（train.py）使用；library.json 供 AimTemplateEngine 运行时检索。
 */
object AimDatasetStore {

    private val GSON = GsonBuilder().setPrettyPrinting().create()

    /** 数据集容量上限（tick 数），超出时丢弃最旧数据，避免 JSON 无限膨胀。 */
    private const val MAX_TICKS = 200_000

    /** 串行化 persist 的读-改-写，防止两次 persist 并发交错导致丢数据。 */
    private val persistMutex = kotlinx.coroutines.sync.Mutex()

    val dir: File
        get() = File(
            net.airplus.file.FileManager.dir, "rotations"
        )

    private val datasetFile: File
        get() = File(dir, "dataset.json")

    val libraryFile: File
        get() = File(dir, "library.json")

    /**
     * 追加新录制的 tick 到 dataset.json，并基于全量数据重建 library.json。
     * 分段与文件 IO 在 IO 协程执行，完成后在主线程 chat 汇报。
     * [report] = false 时不发 chat（高频采集场景如 AimBall 每轮一存，避免刷屏）。
     */
    fun persist(newTicks: List<TickSample>, report: Boolean = true) {
        if (newTicks.isEmpty()) return

        SharedScopes.IO.launch {
            try {
                persistSuspend(newTicks, report)
            } catch (t: Throwable) {
                LOGGER.error("[AimDataset] Failed to persist rotation data.", t)
            }
        }
    }

    /**
     * 同步（阻塞）persist，限时 5 秒。仅供退出钩子 / onDisable 使用：
     * 异步 persist 在玩家紧接着退出游戏时可能根本没跑完，导致本轮录制丢失。
     * report 固定 false，避免 runBlocking 内跳 Main 线程死锁。
     */
    fun persistBlocking(newTicks: List<TickSample>) {
        if (newTicks.isEmpty()) return

        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(5_000) {
                try {
                    persistSuspend(newTicks, report = false)
                } catch (t: Throwable) {
                    LOGGER.error("[AimDataset] Failed to persist rotation data on shutdown.", t)
                }
            }
        }
    }

    private suspend fun persistSuspend(newTicks: List<TickSample>, report: Boolean) {
        persistMutex.withLock {
            dir.mkdirs()

            val old = readDataset()
            val all = ArrayList<TickSample>(old.size + newTicks.size).apply {
                addAll(old)
                addAll(newTicks)
            }
            if (all.size > MAX_TICKS) {
                all.subList(0, all.size - MAX_TICKS).clear()
                LOGGER.info("[AimDataset] Dataset exceeded $MAX_TICKS ticks, trimmed oldest.")
            }

            atomicWrite(datasetFile) { GSON.toJson(DatasetFile(ticks = all), it) }

            val segments = AimSegmenter.segment(all)
            atomicWrite(libraryFile) { GSON.toJson(LibraryFile(templates = segments), it) }

            if (report) {
                withContext(Dispatchers.Main) {
                    chat("§7[§bAI Aim§7] §fRecorded §b${newTicks.size}§f ticks (total §b${all.size}§f), §b${segments.size}§f segments.")
                }
            }
        }
        AimTemplateEngine.loadIfChanged(true)
        RotationNet.loadIfChanged(true)
    }

    /**
     * 原子替换写入：先写临时文件，成功后 rename 覆盖目标。
     * 直接覆盖原文件时，写入中途崩溃/断电会留下损坏的 JSON，而 readDataset 对
     * 损坏文件返回空列表，下次 persist 会把「空读」合并写回 —— 历史数据被永久固化丢失。
     * Windows 上 renameTo 不覆盖已存在目标，rename 前先 delete。
     */
    private inline fun atomicWrite(file: File, content: (java.io.Writer) -> Unit) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            FileWriter(tmp).use { content(it) }
            if (file.exists() && !file.delete()) {
                throw IllegalStateException("Failed to delete old ${file.name}")
            }
            if (!tmp.renameTo(file)) {
                throw IllegalStateException("Failed to rename ${tmp.name} to ${file.name}")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * 清空全部 AI 转头数据（dataset/library/网络权重），并刷新两个引擎的内存缓存。
     * 持 persistMutex 执行：防止与进行中的 persist（读旧→合并→写回）竞态导致旧数据被写回。
     * 必须在 IO 协程调用（persist 持锁期间会 withContext(Main) 发 chat，主线程等待会死锁）。
     * @return 删除的文件数
     */
    suspend fun clearAll(): Int {
        var removed = 0
        persistMutex.withLock {
            for (file in listOf(datasetFile, libraryFile, File(dir, "net_weights.json"))) {
                if (file.isFile && file.delete()) removed++
            }
        }
        AimTemplateEngine.loadIfChanged(true)
        RotationNet.loadIfChanged(true)
        return removed
    }

    /**
     * 当前数据规模与场景标签分布（IO 协程中调用）。
     * 返回 (tick 数, 段数, scene 分布)，scene 分布下标为 [AimScene] 值：
     * [0]=无标签(旧数据), [1]=定位, [2]=调整, [3]=追踪。
     */
    fun stats(): Triple<Int, Int, IntArray> {
        val ticks = readDataset()
        val scenes = IntArray(4)
        for (t in ticks) if (t.scene in 1..3) scenes[t.scene]++
        return Triple(ticks.size, AimSegmenter.segment(ticks).size, scenes)
    }

    private fun readDataset(): List<TickSample> {
        if (!datasetFile.isFile) return emptyList()

        return try {
            FileReader(datasetFile).use { GSON.fromJson(it, DatasetFile::class.java) }?.ticks ?: emptyList()
        } catch (t: Throwable) {
            LOGGER.error("[AimDataset] Failed to read dataset.json.", t)
            emptyList()
        }
    }

    /**
     * 读取模板库，文件不存在或解析失败返回 null（由调用方回退 Default）。
     */
    fun readLibrary(): List<Segment>? {
        if (!libraryFile.isFile) return null

        return try {
            FileReader(libraryFile).use { GSON.fromJson(it, LibraryFile::class.java) }?.templates
        } catch (t: Throwable) {
            LOGGER.error("[AimDataset] Failed to read library.json.", t)
            null
        }
    }

    /**
     * 读取 MLP 权重原始 JSON（交给 RotationNet 自己校验维度），不存在返回 null。
     */
    fun readNetWeights(): String? {
        val file = File(dir, "net_weights.json")
        if (!file.isFile) return null

        return try {
            file.readText()
        } catch (t: Throwable) {
            LOGGER.error("[AimDataset] Failed to read net_weights.json.", t)
            null
        }
    }
}
