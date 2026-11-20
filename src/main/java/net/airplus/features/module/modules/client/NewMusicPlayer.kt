/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.client

import net.airplus.event.GameTickEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.music.bm.MusicService
import net.airplus.music.bm.MusicSettings
import net.airplus.music.bm.model.Quality
import net.airplus.music.bm.ui.MusicDevCheck
import net.airplus.music.bm.ui.MusicKeys
import net.airplus.music.bm.ui.MusicScreen

/**
 * The BetterMyau music player's settings, and its key opens the music screen (it never
 * stays "on", like the click GUI). The values live in the normal module config; the
 * NetEase login does not (see [net.airplus.music.bm.store.MusicStore]).
 */
object NewMusicPlayer : Module("NewMusicPlayer", Category.CLIENT, canBeEnabled = false), MusicSettings {

    private val volumeValue = int("Volume", 60, 0..100, "%")
    private val followGameVolumeValue = boolean("FollowGameVolume", true)
    private val qualityValue = choices("Quality", arrayOf("STANDARD", "HIGHER", "EXHIGH"), "EXHIGH")
    private val skipTrialValue = boolean("SkipTrial", false)
    private val apiValue = choices("Api", arrayOf("DIRECT", "CUSTOM"), "DIRECT")
    private val apiUrlValue = text("ApiUrl", "http://localhost:3000") { apiValue.get() == "CUSTOM" }
    private val localFolderValue = text("LocalFolder", "")

    init {
        MusicService.get().setSettings(this)
        MusicKeys.init()

        // Player hotkeys and per-tick service upkeep. always = true: the music service
        // keeps running even though this module never stays enabled.
        handler<GameTickEvent>(always = true) {
            MusicKeys.tick()
            MusicService.get().frame()
            MusicDevCheck.frame()
        }
    }

    /** The key (or a click in the GUI) opens the player instead of switching a state. */
    override fun onEnable() {
        super.onEnable()
        mc.displayGuiScreen(MusicScreen())
    }

    // ------------------------------------------------------------ MusicSettings

    override fun volume(): Int = volumeValue.get()

    override fun setVolume(volume: Int) {
        volumeValue.set(volume.coerceIn(0, 100))
    }

    override fun followGameVolume(): Boolean = followGameVolumeValue.get()

    override fun quality(): Quality {
        val all = Quality.values()
        val index = all.indexOfFirst { it.name == qualityValue.get() }
        return all[index.coerceIn(0, all.size - 1)]
    }

    override fun skipTrial(): Boolean = skipTrialValue.get()

    override fun customApi(): Boolean = apiValue.get() == "CUSTOM"

    override fun apiUrl(): String = apiUrlValue.get()

    override fun localFolder(): String = localFolderValue.get()
}
