/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.music

import net.airplus.music.bm.MusicService

/**
 * NewMusicPlayer（BetterMyau 播放器）显示门面，供 [MusicHub] 读取。
 *
 * 从 [MusicService] 取曲目 / 歌词 / 进度；歌词用播放器自带的平滑歌词时钟
 * （lyricTimeMs，试听片段自动对齐歌曲时间轴），保证与播放器内歌词页同步。
 * 所有取值均为空安全：服务未启动 / 无曲目时返回默认值。
 */
object NewMusicFacade {

    private const val UNKNOWN = "无"

    private val service: MusicService
        get() = MusicService.get()

    private val track: net.airplus.music.bm.model.Track?
        get() = service.current()

    /** 正在播放（或队列当前）的曲目名。 */
    val currentMusicName: String
        get() = track?.name ?: UNKNOWN

    /** 歌手名。 */
    val currentArtist: String
        get() = track?.artistText() ?: UNKNOWN

    /** 当前曲目封面 URL（无封面/未播放时为空串）。 */
    val currentCoverUrl: String
        get() = track?.coverUrl ?: ""

    /** 当前歌词行（按播放进度同步；无歌词/未播放时为空串）。 */
    val currentLyricDisplay: String
        get() = lyricAt(0)

    /** 上一句歌词。 */
    val previousLyricDisplay: String
        get() = lyricAt(-1)

    /** 下一句歌词。 */
    val nextLyricDisplay: String
        get() = lyricAt(+1)

    /** 是否正在发声（引擎处于 PLAYING；暂停/缓冲均不算）。 */
    val isCurrentlyPlaying: Boolean
        get() = service.isPlaying()

    /** 是否有活动曲目（播放中或队列存在当前曲）——MusicHub 来源优先级判定用。 */
    val hasActiveTrack: Boolean
        get() = track != null

    /** 播放进度 0..1（歌词时钟口径，含试听偏移）。 */
    val progress: Float
        get() {
            val total = service.durationMs()
            if (total <= 0) return 0F
            return (service.lyricTimeMs().toFloat() / total).coerceIn(0F, 1F)
        }

    /** 当前播放时间（m:ss）。 */
    val currentTimeString: String
        get() = formatMillis(service.lyricTimeMs())

    /** 总时长（m:ss）。 */
    val totalTimeString: String
        get() = formatMillis(service.durationMs())

    /** 暂停 / 继续播放。 */
    fun togglePause() {
        service.togglePause()
    }

    /** 下一曲。 */
    fun next() {
        service.next()
    }

    /** 上一曲。 */
    fun prev() {
        service.previous()
    }

    // ===== 内部 =====

    /**
     * 歌词同步：与播放器歌词页（LyricsView）同一套逻辑——
     * 按 lyricTimeMs + 120ms 提前量定位行，加上 [offset]（-1/0/+1）取文本；
     * 原文为空时回退到翻译。
     */
    private fun lyricAt(offset: Int): String {
        if (!service.isActive()) return ""
        val lyrics = service.lyrics()
        if (lyrics.isEmpty()) return ""
        val lines = lyrics.lines
        val index = (lyrics.indexAt(service.lyricTimeMs() + 120) + offset).coerceIn(0, lines.size - 1)
        val line = lines[index]
        return line.text.ifEmpty { line.translation }
    }

    private fun formatMillis(millis: Long): String {
        val seconds = (millis / 1000).toInt().coerceAtLeast(0)
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
}
