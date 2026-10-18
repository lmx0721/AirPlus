// skid SetsunaClient
package net.airplus.features.module.modules.music

import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.music.ncm.music.CloudMusic
import net.airplus.ui.client.screen.MusicScreen

/**
 * 网易云/QQ 音乐入口模块（移植自 SetsunaClient NetEaseMusicModule）。
 * 本模块不维持启用状态：开启即打开音乐工作台界面（与 HUDEdit 同款模式）。
 *
 * 说明：Setsuna 源端的「导入背景图 / 重置背景 / 背景不透明度」设置依赖文件选择器，
 * AirPlus 的模块设置没有对应管线，此处省略（不移植）。
 * 界面配色已固定为网易云音乐风格（红色强调 + 深色半透明面板），不再提供 Color Preset。
 */
object NetEaseMusic : Module("NetEase Music", Category.CLIENT, canBeEnabled = false) {

    /** 音量（0..1），变更时实时写入 AudioPlayer。 */
    private val volumeValue = float("音量", 0.25f, 0f..1f).onChanged { volume ->
        try {
            CloudMusic.player?.setVolume(volume.coerceIn(0f, 1f))
        } catch (ignored: Exception) {
        }
    }

    /** 当前音量（0..1）。 */
    val volume: Float
        get() = volumeValue.get().coerceIn(0f, 1f)

    /** 供界面滑条回写设置（onChanged 会同步到播放器）。 */
    fun setVolumeFromScreen(value: Float) {
        volumeValue.set(value.coerceIn(0f, 1f))
    }

    override fun onEnable() {
        super.onEnable()
        // 开屏前把设置里的音量同步到播放器（若播放器已存在）
        try {
            CloudMusic.player?.setVolume(volume)
        } catch (ignored: Exception) {
        }
        mc.displayGuiScreen(MusicScreen())
    }
}
