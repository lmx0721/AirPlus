/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.client

import net.airplus.event.EntityKilledEvent
import net.airplus.event.StartupEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.asyncPlayWav
import net.airplus.utils.client.ClientUtils.LOGGER
import net.airplus.utils.client.asResourceLocation
import net.airplus.utils.client.playSound
import net.airplus.utils.getMP3S
import net.airplus.utils.getWAVS
import net.airplus.utils.playMP3

object Sound : Module("Sound", Category.CLIENT, canBeEnabled = false) {

    // 内置特殊选项：None = 不播放；Button = 按钮音效（Enable 为原版 gui.button.press 按下音）
    private const val SOUND_NONE = "None"
    private const val SOUND_BUTTON = "Button"

    // 按钮弹起音效：原版没有独立的 release 音效事件（GUI 只在按下时播 gui.button.press）。
    // 原版方块按钮（BlockButton）的实现：按下播 random.click @ pitch 0.6，弹起播
    // random.click @ pitch 0.5 —— 这里沿用弹起的原版参数（pitch 0.5）。
    private const val BUTTON_RELEASE_EVENT = "random.click"
    private const val BUTTON_RELEASE_PITCH = 0.5f

    private val enableSoundFiles = soundFiles("Enable")
    private val disableSoundFiles = soundFiles("Disable")
    private val startupSoundFiles = soundFiles("Startup")
    private val killSoundFiles = soundFiles("Kill")

    val enableSounds by choices(
        "Enable",
        arrayOf(SOUND_NONE, SOUND_BUTTON) + enableSoundFiles,
        enableSoundFiles.firstOrNull() ?: SOUND_NONE
    )

    val disableSounds by choices(
        "Disable",
        arrayOf(SOUND_NONE, SOUND_BUTTON) + disableSoundFiles,
        disableSoundFiles.firstOrNull() ?: SOUND_NONE
    )

    val startupSounds by choices(
        "Startup",
        arrayOf(SOUND_NONE) + startupSoundFiles,
        startupSoundFiles.firstOrNull() ?: SOUND_NONE
    )

    val killSoundEnabled by boolean("KillSound", true)

    val killCooldown by int("KillCooldown", 1, 0..60) { killSoundEnabled }

    private var lastKillSoundTime = 0L

    val killSounds by choices(
        "Kill",
        arrayOf(SOUND_NONE) + killSoundFiles,
        killSoundFiles.firstOrNull() ?: SOUND_NONE
    ) { killSoundEnabled }

    /**
     * 读取指定目录下所有可选音效文件名（WAV + MP3，不含扩展名）
     */
    private fun soundFiles(folder: String): Array<String> =
        (getWAVS("assets/minecraft/airplus/sounds/$folder") + getMP3S("assets/minecraft/airplus/sounds/$folder")).toTypedArray()

    fun playEnableSound() {
        playSoundFromFolder("Enable", enableSounds)
    }

    fun playDisableSound() {
        playSoundFromFolder("Disable", disableSounds)
    }

    fun playToggleSound(enabled: Boolean) {
        if (enabled) {
            playEnableSound()
        } else {
            playDisableSound()
        }
    }

    fun playStartupSound() {
        playSoundFromFolder("Startup", startupSounds)
    }

    fun playKillSound() {
        if (!killSoundEnabled) return

        val currentTime = System.currentTimeMillis()
        if (killCooldown > 0 && currentTime - lastKillSoundTime < killCooldown * 1000L) {
            return
        }
        lastKillSoundTime = currentTime

        playSoundFromFolder("Kill", killSounds)
    }

    /**
     * 播放指定文件夹下的音效：
     * - None：不播放
     * - Button：按钮音效（Disable 时为按钮弹起音效）
     * - 其他：优先 WAV，其次 MP3
     */
    private fun playSoundFromFolder(folder: String, soundName: String) {
        when (soundName) {
            SOUND_NONE -> return
            SOUND_BUTTON -> playButtonSound(release = folder == "Disable")
            else -> {
                val basePath = "airplus/sounds/$folder/$soundName"
                val wavExists = javaClass.getResourceAsStream("/assets/minecraft/$basePath.wav") != null
                val mp3Exists = javaClass.getResourceAsStream("/assets/minecraft/$basePath.mp3") != null

                when {
                    wavExists -> asyncPlayWav("$basePath.wav")
                    mp3Exists -> playMP3("$basePath.mp3")
                }
            }
        }
    }

    /**
     * 播放按钮音效。
     * Enable = 原版 GUI 按下音（gui.button.press）；Disable = 原版方块按钮的弹起音
     * （random.click @ pitch 0.5，BlockButton 弹起时的原版参数）。
     */
    private fun playButtonSound(release: Boolean = false) {
        runCatching {
            if (release) {
                mc.playSound(BUTTON_RELEASE_EVENT.asResourceLocation(), BUTTON_RELEASE_PITCH)
            } else {
                mc.playSound("gui.button.press".asResourceLocation())
            }
        }.onFailure {
            LOGGER.error("[Sound] Failed to play button sound: ${it.message}")
        }
    }

    val onStartup = handler<StartupEvent>(always = true) {
        playStartupSound()
    }

    val onKilled = handler<EntityKilledEvent>(always = true) {
        playKillSound()
    }
}
