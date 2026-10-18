// skid SetsunaClient
package net.airplus.features.module.modules.music

import net.airplus.music.ncm.music.AudioPlayer
import net.airplus.music.ncm.music.CloudMusic
import net.airplus.music.ncm.music.NcmLyrics
import net.airplus.music.ncm.music.dto.Music

/**
 * 网易云音乐显示门面（移植自 SetsunaClient：歌词/曲目信息同步逻辑从 MusicScreen 抽出，
 * 供 HUD 组件读取，避免 HUD 直接接触后端内部状态）。
 *
 * 所有取值均为空安全：后端未初始化 / 未登录 / 无播放时返回默认值。
 */
object NetEaseMusicFacade {

    private const val UNKNOWN = "无"

    /** 正在播放（或队列当前）的曲目名。 */
    val currentMusicName: String
        get() = playing?.name ?: queued?.name ?: UNKNOWN

    /** 歌手名。 */
    val currentArtist: String
        get() = playing?.artistsName ?: queued?.artistsName ?: UNKNOWN

    /** 当前曲目封面 URL（无封面/未播放时为空串）。 */
    val currentCoverUrl: String
        get() = playing?.getCoverUrl(64) ?: queued?.getCoverUrl(64) ?: ""

    /** 当前歌词行（按播放进度同步；无歌词/未播放时为空串）。 */
    val currentLyricDisplay: String
        get() = lyricAt(0)

    /** 上一句歌词。 */
    val previousLyricDisplay: String
        get() = lyricAt(-1)

    /** 下一句歌词。 */
    val nextLyricDisplay: String
        get() = lyricAt(1)

    /** 是否正在播放（播放器存在且未暂停）。 */
    val isCurrentlyPlaying: Boolean
        get() = CloudMusic.player?.let { !it.isPausing() } ?: false

    /** 是否有活动曲目（播放中或队列存在当前曲）——MusicHub 来源优先级判定用。 */
    val hasActiveTrack: Boolean
        get() = CloudMusic.currentlyPlaying != null || CloudMusic.playList.getOrNull(CloudMusic.curIdx) != null

    /** 播放进度 0..1（来自 AudioPlayer 当前/总毫秒）。 */
    val progress: Float
        get() {
            val player = CloudMusic.player ?: return 0F
            val total = player.totalTimeMillis
            if (total <= 0F) return 0F
            return (player.currentTimeMillis / total).coerceIn(0F, 1F)
        }

    /** 当前播放时间（m:ss）。 */
    val currentTimeString: String
        get() = formatMillis(CloudMusic.player?.currentTimeMillis ?: 0F)

    /** 总时长（m:ss；播放器未就绪时退回曲目元数据时长）。 */
    val totalTimeString: String
        get() {
            val player = CloudMusic.player
            val total = player?.totalTimeMillis ?: 0F
            if (total > 0F) return formatMillis(total)
            val duration = playing?.duration ?: queued?.duration ?: 0L
            return formatMillis(duration.toFloat())
        }

    /** 暂停 / 继续播放。 */
    fun togglePause() {
        val player = CloudMusic.player ?: return
        try {
            if (player.isPausing()) player.unpause() else player.pause()
        } catch (ignored: RuntimeException) {
        }
    }

    /** 下一曲。 */
    fun next() = runSafely { CloudMusic.next() }

    /** 上一曲。 */
    fun prev() = runSafely { CloudMusic.prev() }

    /** 设置音量（0..1）。 */
    fun setVolume(volume: Float) = runSafely {
        CloudMusic.player?.setVolume(volume.coerceIn(0F, 1F))
    }

    // ===== 内部 =====

    private val playing: Music?
        get() = CloudMusic.currentlyPlaying

    private val queued: Music?
        get() = CloudMusic.playList.getOrNull(CloudMusic.curIdx)

    /**
     * 歌词同步：确保当前曲目歌词已异步加载（NcmLyrics.ensureLoaded 对同曲幂等），
     * 再按播放器当前毫秒定位行，加上 [offset]（-1/0/+1）取文本。
     */
    private fun lyricAt(offset: Int): String {
        val current = playing ?: return ""
        return try {
            NcmLyrics.ensureLoaded(current)
            val lines = NcmLyrics.getLines()
            if (lines.isEmpty()) return ""
            val player: AudioPlayer? = CloudMusic.player
            val millis = if (player != null && player.totalTimeMillis > 0F) {
                player.currentTimeMillis
            } else {
                0F
            }
            val index = (NcmLyrics.currentIndex(millis) + offset).coerceIn(0, lines.size - 1)
            lines[index].text()
        } catch (ignored: Exception) {
            ""
        }
    }

    private inline fun runSafely(block: () -> Unit) {
        try {
            block()
        } catch (ignored: Exception) {
        }
    }

    private fun formatMillis(millis: Float): String {
        val seconds = (millis / 1000F).toInt().coerceAtLeast(0)
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
}
