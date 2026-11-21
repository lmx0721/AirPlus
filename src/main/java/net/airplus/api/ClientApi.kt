package net.airplus.api

import net.airplus.utils.io.applyBypassHttps
import net.airplus.utils.io.decodeJson
import net.airplus.utils.io.get
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// AirPlus 自己的云参数源（GitHub 仓库 + jsDelivr CDN）
// 仓库结构：index.json 为配置列表，settings/<settingId>.txt 为具体配置脚本
private const val SETTINGS_CLOUD_BASE = "https://cdn.jsdelivr.net/gh/lmx0721/AirPlus-Cloud@main"

// AirPlus 自己的更新源（GitHub Releases）
private const val GITHUB_RELEASE_ENDPOINT = "https://api.github.com/repos/lmx0721/AirPlus/releases/latest"

private val client = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .applyBypassHttps()
    .build()

/**
 * ClientApi
 */
object ClientApi {

    /**
     * 获取 AirPlus 最新的 GitHub Release
     */
    fun getNewestRelease(): GitHubRelease {
        client.get(GITHUB_RELEASE_ENDPOINT).use { response ->
            if (!response.isSuccessful) error("Request failed: ${response.code}")
            return response.body.charStream().decodeJson()
        }
    }

    /**
     * 从云参数仓库拉取配置列表（index.json）
     */
    fun getSettingsList(): List<AutoSettings> {
        val url = "$SETTINGS_CLOUD_BASE/index.json"
        client.get(url).use { response ->
            if (!response.isSuccessful) error("Request failed: ${response.code}")
            return response.body.charStream().decodeJson()
        }
    }

    /**
     * 从云参数仓库拉取指定配置脚本
     */
    fun getSettingsScript(settingId: String): String {
        val url = "$SETTINGS_CLOUD_BASE/settings/$settingId.txt"
        client.get(url).use { response ->
            if (!response.isSuccessful) error("Request failed: ${response.code}")
            return response.body.string()
        }
    }
}
