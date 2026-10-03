/*
 * Air Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 */
package net.airplus.utils

import javazoom.jl.player.Player
import net.airplus.utils.client.ClientUtils
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.jar.JarFile
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException
import kotlin.concurrent.thread

/**
 * 播放MP3音效（异步）
 * @param resourcePath 资源路径，如 "airplus/sounds/enable/mac.mp3"
 */
fun playMP3(resourcePath: String) {
    thread(start = true) {
        try {
            val inputStream: InputStream = SoundUtils::class.java.getResourceAsStream("/assets/minecraft/$resourcePath")
                ?: throw IllegalArgumentException("音频资源未找到: $resourcePath")
            val bufferedStream = BufferedInputStream(inputStream)
            val player = Player(bufferedStream)
            player.play()
        } catch (e: Throwable) {
            // Android 等平台可能缺少音频实现，抛出的是 NoClassDefFoundError 等 Error
            ClientUtils.LOGGER.error("[SoundUtils] 播放MP3失败: ${e.message}")
            e.printStackTrace()
        }
    }
}

/**
 * 播放WAV音效
 * @param resourcePath 资源路径，如 "airplus/sounds/kill/cs2.wav"
 */
fun playWavSound(resourcePath: String) {
    try {
        val audioStream = AudioSystem.getAudioInputStream(
            BufferedInputStream(
                SoundUtils::class.java.getResourceAsStream("/assets/minecraft/$resourcePath")
            )
        )
        val clip = AudioSystem.getClip()
        clip.open(audioStream)
        clip.start()
    } catch (e: UnsupportedAudioFileException) {
        ClientUtils.LOGGER.error("[SoundUtils] 不支持的音频格式: $resourcePath")
        e.printStackTrace()
    } catch (e: Throwable) {
        ClientUtils.LOGGER.error("[SoundUtils] 播放WAV失败: ${e.message}")
        e.printStackTrace()
    }
}

/**
 * 异步播放WAV音效
 * 使用 SourceDataLine 流式播放，比 Clip 更可靠（Clip 在游戏运行环境中可能获取音频线失败）
 * @param resourcePath 资源路径
 */
fun asyncPlayWav(resourcePath: String) {
    thread(start = true) {
        try {
            val audioStream = AudioSystem.getAudioInputStream(
                BufferedInputStream(
                    SoundUtils::class.java.getResourceAsStream("/assets/minecraft/$resourcePath")
                )
            )
            val format = audioStream.format
            val line = AudioSystem.getSourceDataLine(format)
            line.open(format, 8192)
            line.start()

            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (audioStream.read(buffer).also { bytesRead = it } != -1) {
                line.write(buffer, 0, bytesRead)
            }

            line.drain()
            line.stop()
            line.close()
            audioStream.close()
        } catch (e: Throwable) {
            ClientUtils.LOGGER.error("[SoundUtils] 异步播放失败: ${e.message}")
            e.printStackTrace()
        }
    }
}

/**
 * 获取指定目录下的所有MP3文件名（不含扩展名）
 * @param resourcePath 资源目录路径，如 "airplus/sounds/enable"
 * @return 文件名列表 */
fun getMP3S(resourcePath: String): List<String> {
    val resourceDir = if (resourcePath.endsWith("/")) resourcePath else "$resourcePath/"
    val mp3Files = mutableListOf<String>()

    try {
        val normalizedPath = resourceDir.removePrefix("/")
        val resourceUrl = SoundUtils::class.java.classLoader.getResource(normalizedPath)
            ?: return emptyList()

        when (resourceUrl.protocol) {
            "jar" -> {
                val jarPath = resourceUrl.path.substringBefore("!")
                    .replace("file:", "")
                    .replace(Regex("(?i)%20"), " ")

                JarFile(URLDecoder.decode(jarPath, "UTF-8")).use { jar ->
                    jar.entries().iterator().forEach { entry ->
                        if (!entry.isDirectory &&
                            entry.name.startsWith(normalizedPath) &&
                            entry.name.equals("$normalizedPath${entry.name.substringAfterLast('/')}", true) &&
                            entry.name.endsWith(".mp3", true)
                        ) {
                            val baseName = entry.name
                                .substringAfterLast('/')
                                .substringBeforeLast('.')
                            mp3Files.add(baseName)
                        }
                    }
                }
            }

            "file" -> {
                val fileDir = File(URLDecoder.decode(resourceUrl.toURI().path, "UTF-8"))
                if (fileDir.exists() && fileDir.isDirectory) {
                    fileDir.walk()
                        .filter { it.isFile && it.extension.equals("mp3", true) }
                        .forEach { mp3Files.add(it.nameWithoutExtension) }
                }
            }
        }
    } catch (e: Exception) {
        ClientUtils.LOGGER.warn("[SoundUtils] 加载MP3资源失败: ${e.message}")
    }

    return mp3Files.distinct()
}

/**
 * 获取指定目录下的所有WAV文件名（不含扩展名）
 * @param resourcePath 资源目录路径，如 "airplus/sounds/kill"
 * @return 文件名列表 */
fun getWAVS(resourcePath: String): List<String> {
    val resourceDir = if (resourcePath.endsWith("/")) resourcePath else "$resourcePath/"
    val wavFiles = mutableListOf<String>()

    try {
        val normalizedPath = resourceDir.removePrefix("/")
        val resourceUrl = SoundUtils::class.java.classLoader.getResource(normalizedPath)
            ?: return emptyList()

        when (resourceUrl.protocol) {
            "jar" -> {
                val jarPath = resourceUrl.path.substringBefore("!")
                    .replace("file:", "")
                    .replace(Regex("(?i)%20"), " ")

                JarFile(URLDecoder.decode(jarPath, "UTF-8")).use { jar ->
                    jar.entries().iterator().forEach { entry ->
                        if (!entry.isDirectory &&
                            entry.name.startsWith(normalizedPath) &&
                            entry.name.equals("$normalizedPath${entry.name.substringAfterLast('/')}", true) &&
                            entry.name.endsWith(".wav", true)
                        ) {
                            val baseName = entry.name
                                .substringAfterLast('/')
                                .substringBeforeLast('.')
                            wavFiles.add(baseName)
                        }
                    }
                }
            }

            "file" -> {
                val fileDir = File(URLDecoder.decode(resourceUrl.toURI().path, "UTF-8"))
                if (fileDir.exists() && fileDir.isDirectory) {
                    fileDir.walk()
                        .filter { it.isFile && it.extension.equals("wav", true) }
                        .forEach { wavFiles.add(it.nameWithoutExtension) }
                }
            }
        }
    } catch (e: Exception) {
        ClientUtils.LOGGER.warn("[SoundUtils] 加载WAV资源失败: ${e.message}")
    }

    return wavFiles.distinct()
}

/**
 * 占位类，用于获取类加载器
 */
private object SoundUtils