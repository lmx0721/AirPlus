/*skid gold bounce
 * LiquidBounce Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/CCBlueX/LiquidBounce/
 * https://github.com/the-OmegaLabs/GoldBounce
 */
package net.airplus.features.module.modules.misc

import net.airplus.event.EntityKilledEvent
import net.airplus.event.StartupEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.asyncPlayWav
import net.airplus.utils.getMP3S
import net.airplus.utils.getWAVS
import net.airplus.utils.playMP3

object Sound : Module("Sound", Category.CLIENT, canBeEnabled = false) {

    val enableSounds by choices(
        "Enable",
        (getWAVS("assets/minecraft/airplus/sounds/Enable") + getMP3S("assets/minecraft/airplus/sounds/Enable")).toTypedArray().takeIf { it.isNotEmpty() }
            ?: arrayOf("None"),
        getMP3S("assets/minecraft/airplus/sounds/Enable").firstOrNull() ?: "None"
    )

    val disableSounds by choices(
        "Disable",
        (getWAVS("assets/minecraft/airplus/sounds/Disable") + getMP3S("assets/minecraft/airplus/sounds/Disable")).toTypedArray().takeIf { it.isNotEmpty() }
            ?: arrayOf("None"),
        getMP3S("assets/minecraft/airplus/sounds/Disable").firstOrNull() ?: "None"
    )

    val startupSounds by choices(
        "Startup",
        getMP3S("assets/minecraft/airplus/sounds/Startup").toTypedArray().takeIf { it.isNotEmpty() }
            ?: arrayOf("None"),
        getMP3S("assets/minecraft/airplus/sounds/Startup").firstOrNull() ?: "Air"
    )

    val killSoundEnabled by boolean("KillSound", true)

    val killCooldown by int("KillCooldown", 1, 0..60) { killSoundEnabled }

    private var lastKillSoundTime = 0L

    val killSounds by choices(
        "Kill",
        (getWAVS("assets/minecraft/airplus/sounds/Kill") + getMP3S("assets/minecraft/airplus/sounds/Kill")).toTypedArray().takeIf { it.isNotEmpty() }
            ?: arrayOf("None"),
        (getWAVS("assets/minecraft/airplus/sounds/Kill") + getMP3S("assets/minecraft/airplus/sounds/Kill")).firstOrNull() ?: "None"
    ) { killSoundEnabled }

    fun playEnableSound() {
        if (enableSounds == "None") return
        playSoundFromFolder("Enable", enableSounds)
    }

    fun playDisableSound() {
        if (disableSounds == "None") return
        playSoundFromFolder("Disable", disableSounds)
    }

    /**
     * Play a sound from the given folder, preferring WAV and falling back to MP3.
     */
    private fun playSoundFromFolder(folder: String, soundName: String) {
        val basePath = "airplus/sounds/$folder/$soundName"
        val wavExists = javaClass.getResourceAsStream("/assets/minecraft/$basePath.wav") != null
        val mp3Exists = javaClass.getResourceAsStream("/assets/minecraft/$basePath.mp3") != null

        when {
            wavExists -> asyncPlayWav("$basePath.wav")
            mp3Exists -> playMP3("$basePath.mp3")
        }
    }

    fun playToggleSound(enabled: Boolean) {
        if (enabled) {
            playEnableSound()
        } else {
            playDisableSound()
        }
    }

    fun playStartupSound() {
        if (startupSounds == "None") return
        playMP3("airplus/sounds/Startup/${startupSounds}.mp3")
    }

    fun playKillSound() {
        if (!killSoundEnabled) return
        if (killSounds == "None") return

        val currentTime = System.currentTimeMillis()
        if (killCooldown > 0 && currentTime - lastKillSoundTime < killCooldown * 1000L) {
            return
        }
        lastKillSoundTime = currentTime


        
        val wavPath = "airplus/sounds/Kill/${killSounds}.wav"
        val mp3Path = "airplus/sounds/Kill/${killSounds}.mp3"
        
        val wavExists = javaClass.getResourceAsStream("/assets/minecraft/$wavPath") != null
        val mp3Exists = javaClass.getResourceAsStream("/assets/minecraft/$mp3Path") != null

        when {
            wavExists -> asyncPlayWav(wavPath)
            mp3Exists -> playMP3(mp3Path)
        }
    }

    val onStartup = handler<StartupEvent>(always = true) {
        playStartupSound()
    }

    val onKilled = handler<EntityKilledEvent>(always = true) {
        playKillSound()
    }
}