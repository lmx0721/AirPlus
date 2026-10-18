/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.music

/**
 * 音乐显示数据源统一门面。
 *
 * Island / HUD 组件一律从这里读取音乐信息。不再手动选择来源：
 * 网易云有活动曲目（播放中或队列当前曲）时优先使用网易云，
 * 否则自动回退到本地音乐播放器（MusicPlayer）。
 */
object MusicHub {

    private val useNetEase: Boolean
        get() = NetEaseMusicFacade.hasActiveTrack

    val currentMusicName: String
        get() = if (useNetEase) NetEaseMusicFacade.currentMusicName else MusicPlayer.currentMusicName

    val currentArtist: String
        get() = if (useNetEase) NetEaseMusicFacade.currentArtist else ""

    /** 当前曲目封面 URL（仅网易云提供；无封面/未播放时为空串）。 */
    val currentCoverUrl: String
        get() = if (useNetEase) NetEaseMusicFacade.currentCoverUrl else ""

    val currentLyricDisplay: String
        get() = if (useNetEase) NetEaseMusicFacade.currentLyricDisplay else MusicPlayer.currentLyricDisplay

    val previousLyricDisplay: String
        get() = if (useNetEase) NetEaseMusicFacade.previousLyricDisplay else MusicPlayer.previousLyricDisplay

    val nextLyricDisplay: String
        get() = if (useNetEase) NetEaseMusicFacade.nextLyricDisplay else MusicPlayer.nextLyricDisplay

    val isCurrentlyPlaying: Boolean
        get() = if (useNetEase) NetEaseMusicFacade.isCurrentlyPlaying else MusicPlayer.isCurrentlyPlaying

    val progress: Float
        get() = if (useNetEase) NetEaseMusicFacade.progress else MusicPlayer.progress

    val currentTimeString: String
        get() = if (useNetEase) NetEaseMusicFacade.currentTimeString else MusicPlayer.currentTimeString

    val totalTimeString: String
        get() = if (useNetEase) NetEaseMusicFacade.totalTimeString else MusicPlayer.totalTimeString

    val timeDisplayString: String
        get() = if (useNetEase) "${NetEaseMusicFacade.currentTimeString} / ${NetEaseMusicFacade.totalTimeString}"
        else MusicPlayer.timeDisplayString

    fun togglePause() {
        if (useNetEase) NetEaseMusicFacade.togglePause() else MusicPlayer.togglePlayPause()
    }

    fun next() {
        if (useNetEase) NetEaseMusicFacade.next() else Unit
    }

    fun prev() {
        if (useNetEase) NetEaseMusicFacade.prev() else Unit
    }
}
