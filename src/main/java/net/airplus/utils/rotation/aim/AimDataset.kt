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
 * 单 tick 采样：本 tick 内累计的真实鼠标 yaw/pitch 增量 + 上下文。
 * yawDelta/pitchDelta 为原始角度（度），已含 GCD 量化（真实鼠标天然满足）。
 */
data class TickSample(
    val yawDelta: Float = 0f,
    val pitchDelta: Float = 0f,
    val moveSpeed: Float = 0f,
    val entityDist: Float = 0f,
    val gcd: Float = 0f,
)

/**
 * 一段旋转（flick 或连续修正）：per-tick 增量序列 + 段级上下文特征。
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
)

private data class DatasetFile(val version: Int = 1, val ticks: List<TickSample> = emptyList())
private data class LibraryFile(val version: Int = 1, val templates: List<Segment> = emptyList())

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

                    FileWriter(datasetFile).use { GSON.toJson(DatasetFile(ticks = all), it) }

                    val segments = AimSegmenter.segment(all)
                    FileWriter(libraryFile).use { GSON.toJson(LibraryFile(templates = segments), it) }

                    withContext(Dispatchers.Main) {
                        if (report) {
                            chat("§7[§bAI Aim§7] §fRecorded §b${newTicks.size}§f ticks (total §b${all.size}§f), §b${segments.size}§f segments.")
                        }
                    }
                }
                AimTemplateEngine.loadIfChanged(true)
                RotationNet.loadIfChanged(true)
            } catch (t: Throwable) {
                LOGGER.error("[AimDataset] Failed to persist rotation data.", t)
            }
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
     * 当前数据规模（IO 协程中调用）。
     */
    fun stats(): Pair<Int, Int> {
        val ticks = readDataset()
        return ticks.size to AimSegmenter.segment(ticks).size
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
