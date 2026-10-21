/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.client.chat
import net.airplus.utils.kotlin.SharedScopes
import net.airplus.utils.rotation.aim.AimDatasetStore
import net.airplus.utils.rotation.aim.RotationNet
import java.io.File

/**
 * 游戏内一键训练：启用后把内置的 train.py 解压到 rotations/，
 * 用 Python 跑离线训练，把 dataset.json 训练成 Neural 模式可用的 net_weights.json。
 * 训练完成后自动关闭模块并热加载权重（RotationNet 检测文件变动后即可推理）。
 */
object AiTrainer : Module("AiTrainer", Category.CLIENT, gameDetecting = false) {

    private const val SCRIPT_RESOURCE = "/assets/minecraft/airplus/scripts/train.py"

    @Volatile
    private var process: Process? = null

    override fun onEnable() {
        SharedScopes.IO.launch { train() }
    }

    override fun onDisable() {
        process?.destroy()
        process = null
    }

    private suspend fun train() {
        val rotations = AimDatasetStore.dir
        val dataset = File(rotations, "dataset.json")

        if (!dataset.isFile || dataset.length() < 32L) {
            fail("没有找到采集数据（rotations/dataset.json），先开启 RotationRecorder 采集。")
            return
        }

        // 解压内置训练脚本（每次覆盖，保持与构建版本一致）
        val script = File(rotations, "train.py")
        try {
            val input = AiTrainer::class.java.getResourceAsStream(SCRIPT_RESOURCE)
                ?: run {
                    fail("内置训练脚本缺失（$SCRIPT_RESOURCE）。")
                    return
                }
            input.use { stream -> script.outputStream().use { stream.copyTo(it) } }
        } catch (t: Throwable) {
            fail("无法写出训练脚本: ${t.message}")
            return
        }

        val python = findPython()
        if (python == null) {
            fail("未找到 Python，请先安装 Python 并加入 PATH（依赖 numpy）。")
            return
        }

        chat("§7[§bAI Aim§7] §f开始训练（数据: §b${dataset.length() / 1024}§fKB），期间可能掉帧...")

        try {
            val pb = ProcessBuilder(python, script.absolutePath, "dataset.json", "-o", "net_weights.json")
            pb.directory(rotations)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            process = proc

            val tail = ArrayDeque<String>()
            proc.inputStream.bufferedReader().forEachLine { line ->
                synchronized(tail) {
                    tail.addLast(line)
                    if (tail.size > 5) tail.removeFirst()
                }
                relayLine(line)
            }

            val code = proc.waitFor()
            process = null

            withContext(Dispatchers.Main) {
                if (code == 0) {
                    RotationNet.loadIfChanged(true)
                    chat("§7[§bAI Aim§7] §a训练完成，权重已写入 net_weights.json，Neural 模式现在可用。")
                } else {
                    val lastLines = synchronized(tail) { tail.joinToString("\n") }
                    val hint = if (lastLines.contains("numpy", ignoreCase = true)) "\n§c提示: 需要安装 numpy（pip install numpy）。" else ""
                    chat("§7[§bAI Aim§7] §c训练失败（exit $code）:\n$lastLines$hint")
                }
                state = false
            }
        } catch (t: Throwable) {
            process = null
            fail("训练进程异常: ${t.message}")
        }
    }

    /** 只转发关键进度行，避免 epoch 日志刷屏。 */
    private fun relayLine(line: String) {
        val trimmed = line.trim()
        val interesting = trimmed.startsWith("[data]") || trimmed.startsWith("[warn]") ||
            trimmed.startsWith("[export]") || trimmed.startsWith("[done]") ||
            trimmed.startsWith("[eval]") || trimmed.startsWith("[train] early stop")
        if (!interesting) return

        SharedScopes.IO.launch {
            withContext(Dispatchers.Main) {
                chat("§7[§bAI Aim§7] §f$trimmed")
            }
        }
    }

    private suspend fun fail(message: String) {
        withContext(Dispatchers.Main) {
            chat("§7[§bAI Aim§7] §c$message")
            state = false
        }
    }

    /** 依次尝试常见 Python 命令，返回第一个可用的。 */
    private fun findPython(): String? {
        for (exe in arrayOf("python", "python3", "py")) {
            try {
                val probe = ProcessBuilder(exe, "--version").redirectErrorStream(true).start()
                val output = probe.inputStream.bufferedReader().readText()
                val ok = probe.waitFor() == 0 && output.contains("Python", ignoreCase = true)
                if (ok) return exe
            } catch (_: Throwable) {
            }
        }
        return null
    }
}
