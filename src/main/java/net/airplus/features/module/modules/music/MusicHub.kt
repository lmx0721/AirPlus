/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.music

/**
 * 音乐显示数据源统一门面。
 *
 * Island / HUD 组件一律从这里读取音乐信息。不再手动选择来源，优先级为：
 * NewMusicPlayer（BetterMyau）有活动曲目时优先使用，
 * 其次网易云，最后自动回退到本地音乐播放器（MusicPlayer）。
 */
object MusicHub {

    private val useNewMusic: Boolean
        get() = NewMusicFacade.hasActiveTrack

    private val useNetEase: Boolean
        get() = !useNewMusic && NetEaseMusicFacade.hasActiveTrack

    val currentMusicName: String
        get() = when {
            useNewMusic -> NewMusicFacade.currentMusicName
            useNetEase -> NetEaseMusicFacade.currentMusicName
            else -> MusicPlayer.currentMusicName
        }

    val currentArtist: String
        get() = when {
            useNewMusic -> NewMusicFacade.currentArtist
            useNetEase -> NetEaseMusicFacade.currentArtist
            else -> ""
        }

    /** 当前曲目封面 URL（仅网易云/新播放器提供；无封面/未播放时为空串）。 */
    val currentCoverUrl: String
        get() = when {
            useNewMusic -> NewMusicFacade.currentCoverUrl
            useNetEase -> NetEaseMusicFacade.currentCoverUrl
            else -> ""
        }

    val currentLyricDisplay: String
        get() = when {
            useNewMusic -> NewMusicFacade.currentLyricDisplay
            useNetEase -> NetEaseMusicFacade.currentLyricDisplay
            else -> MusicPlayer.currentLyricDisplay
        }

    val previousLyricDisplay: String
        get() = when {
            useNewMusic -> NewMusicFacade.previousLyricDisplay
            useNetEase -> NetEaseMusicFacade.previousLyricDisplay
            else -> MusicPlayer.previousLyricDisplay
        }

    val nextLyricDisplay: String
        get() = when {
            useNewMusic -> NewMusicFacade.nextLyricDisplay
            useNetEase -> NetEaseMusicFacade.nextLyricDisplay
            else -> MusicPlayer.nextLyricDisplay
        }

    val isCurrentlyPlaying: Boolean
        get() = when {
            useNewMusic -> NewMusicFacade.isCurrentlyPlaying
            useNetEase -> NetEaseMusicFacade.isCurrentlyPlaying
            else -> MusicPlayer.isCurrentlyPlaying
        }

    val progress: Float
        get() = when {
            useNewMusic -> NewMusicFacade.progress
            useNetEase -> NetEaseMusicFacade.progress
            else -> MusicPlayer.progress
        }

    val currentTimeString: String
        get() = when {
            useNewMusic -> NewMusicFacade.currentTimeString
            useNetEase -> NetEaseMusicFacade.currentTimeString
            else -> MusicPlayer.currentTimeString
        }

    val totalTimeString: String
        get() = when {
            useNewMusic -> NewMusicFacade.totalTimeString
            useNetEase -> NetEaseMusicFacade.totalTimeString
            else -> MusicPlayer.totalTimeString
        }

    val timeDisplayString: String
        get() = when {
            useNewMusic -> "${NewMusicFacade.currentTimeString} / ${NewMusicFacade.totalTimeString}"
            useNetEase -> "${NetEaseMusicFacade.currentTimeString} / ${NetEaseMusicFacade.totalTimeString}"
            else -> MusicPlayer.timeDisplayString
        }

    fun togglePause() {
        when {
            useNewMusic -> NewMusicFacade.togglePause()
            useNetEase -> NetEaseMusicFacade.togglePause()
            else -> MusicPlayer.togglePlayPause()
        }
    }

    fun next() {
        when {
            useNewMusic -> NewMusicFacade.next()
            useNetEase -> NetEaseMusicFacade.next()
        }
    }

    fun prev() {
        when {
            useNewMusic -> NewMusicFacade.prev()
            useNetEase -> NetEaseMusicFacade.prev()
        }
    }
}
