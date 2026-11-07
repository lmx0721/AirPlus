/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.skin

import com.google.gson.JsonParser
import net.airplus.file.FileManager
import net.airplus.utils.client.ClientUtils
import net.airplus.utils.client.MinecraftInstance
import net.minecraft.client.renderer.ImageBufferDownload
import net.minecraft.client.renderer.ThreadDownloadImageData
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.util.ResourceLocation
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * SkinAPI —— 按正版玩家名异步拉取皮肤（Mojang API → session server → 皮肤 URL）。
 *
 * 线程纪律：
 * - 解析（两次 HTTP + base64/JSON）全部跑在 ForkJoinPool.commonPool 后台线程；
 * - 纹理注册（textureManager.loadTexture）通过 mc.addScheduledTask 切回主线程，
 *   避免 TextureManager 内部 HashMap 跨线程读写；
 * - 主线程调用 [getSkin] 仅做 map 查询，零阻塞零网络。
 *
 * 失败语义：名字无效 / 非正版 / 无皮肤 / 网络失败 → 冷却 5 分钟后自动重试，不刷请求。
 */
object SkinAPI : MinecraftInstance {

    /** 已就绪的皮肤纹理（key = 玩家名小写） */
    private val cache = ConcurrentHashMap<String, ResourceLocation>()

    /** 正在后台解析中的名字（防止重复请求） */
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** 失败冷却（key = 玩家名小写，value = 冷却截止时间戳） */
    private val failedUntil = ConcurrentHashMap<String, Long>()

    private val downloadDir = File(FileManager.dir, "skins_download").apply { mkdirs() }

    private const val FAIL_COOLDOWN_MS = 5 * 60 * 1000L

    /**
     * 获取指定正版玩家名的皮肤纹理。未就绪时返回 null（后台继续加载，下帧重查）。
     */
    fun getSkin(name: String): ResourceLocation? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val key = trimmed.lowercase()

        cache[key]?.let { return it }

        val fail = failedUntil[key] ?: 0L
        if (System.currentTimeMillis() < fail) return null

        if (pending.add(key)) {
            CompletableFuture
                .supplyAsync { resolveSkinUrl(trimmed) }
                .thenAccept { url ->
                    mc.addScheduledTask {
                        pending.remove(key)
                        if (url == null) {
                            failedUntil[key] = System.currentTimeMillis() + FAIL_COOLDOWN_MS
                            ClientUtils.LOGGER.warn("[SkinAPI] Failed to resolve skin for: $trimmed")
                            return@addScheduledTask
                        }
                        val resourceLocation = ResourceLocation("airplus_customskin_$key")
                        val fallback = DefaultPlayerSkin.getDefaultSkin(UUID.nameUUIDFromBytes(key.toByteArray()))
                        val imageData = ThreadDownloadImageData(
                            File(downloadDir, "$key.png"),
                            url,
                            fallback,
                            ImageBufferDownload()
                        )
                        mc.textureManager.loadTexture(resourceLocation, imageData)
                        cache[key] = resourceLocation
                        ClientUtils.LOGGER.info("[SkinAPI] Loaded skin for: $trimmed -> $resourceLocation")
                    }
                }
        }

        return null
    }

    /**
     * 玩家名 → 皮肤 URL（Mojang UUID 查询 + session server 纹理解码）。
     * 仅后台线程调用。
     */
    private fun resolveSkinUrl(name: String): String? {
        return try {
            val uuidBody = httpGet("https://api.mojang.com/users/profiles/minecraft/${URLEncoder.encode(name, "UTF-8")}")
                ?: return null
            val uuid = JsonParser().parse(uuidBody).asJsonObject.get("id").asString

            val profileBody = httpGet("https://sessionserver.mojang.com/session/minecraft/profile/$uuid?unsigned=false")
                ?: return null
            val properties = JsonParser().parse(profileBody).asJsonObject
                .getAsJsonArray("properties")
            if (properties == null || properties.size() == 0) return null

            val decoded = String(
                Base64.getDecoder().decode(properties[0].asJsonObject.get("value").asString),
                Charsets.UTF_8
            )
            val textures = JsonParser().parse(decoded).asJsonObject
                .getAsJsonObject("textures") ?: return null
            val skin = textures.getAsJsonObject("SKIN") ?: return null
            skin.get("url")?.asString
        } catch (t: Throwable) {
            null
        }
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "AirPlus")
            if (conn.responseCode != 200) {
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (t: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
