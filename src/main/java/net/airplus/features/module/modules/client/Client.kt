/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.client

import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.ui.font.AWTFontRenderer
import net.airplus.ui.font.Fonts

/**
 * Client 字体设置模块（不可开关）：
 * - MainFont：客户端主字体（自动取自字体注册表全部非图标字体，切换后 Regular/Semibold/ExtraBold/Bold180 重新烘焙）
 * - FontSize / FontWeight：全局字号（百分比）与字体粗细（描边像素），作用于核心 UI 文本字体
 * - ChineseFont：中文回退字体（主字体缺字形时使用，仅内置两款中文字体可选）
 * - ChineseFontWeight：中文回退字形在全局粗细之上的额外描边量
 * - 回退字号支持两种方案：Offset（渲染器字号 + 偏移）/ Percent（渲染器字号 × 百分比）
 */
object Client : Module("Client", Category.CLIENT, canBeEnabled = false) {

    // 主字体：自动获取注册表全部非图标字体（不含 Minecraft 原版字体与 Semibold 别名族）
    private val mainFontValue = choices("MainFont", Fonts.mainFontCandidates(), "HarmonyOS Sans SC Regular")
        .onChanged { Fonts.applyMainFont(it) }

    // 全局字号（百分比）：仅在烘焙核心 UI 文本字体时套用
    private val fontSizeValue = int("FontSize", 100, 50..200, "%")
        .onChanged { applyFontStyle() }

    // 全局字体粗细（描边像素）：0 表示沿用原生字重
    private val fontWeightValue = float("FontWeight", 0f, 0f..3f, "px")
        .onChanged { applyFontStyle() }

    // 中文回退字体（仅可选择内置的两款中文字体）
    private val chineseFontValue = choices("ChineseFont", arrayOf("HarmonyOS Sans SC", "Setsuna"), "HarmonyOS Sans SC")
        .onChanged { applyChineseFont() }

    // 回退字体字号方案
    private val chineseFontSizeModeValue = choices("ChineseFontSizeMode", arrayOf("Offset", "Percent"), "Offset")
        .onChanged { applyChineseFont() }
    private val chineseFontOffsetValue = int("ChineseFontOffset", 0, -10..10) { chineseFontSizeModeValue.get() == "Offset" }
        .onChanged { applyChineseFont() }
    private val chineseFontScaleValue = int("ChineseFontScale", 100, 50..200, "%") { chineseFontSizeModeValue.get() == "Percent" }
        .onChanged { applyChineseFont() }

    // 中文回退字形额外粗细（在全局 FontWeight 之上叠加）
    private val chineseFontWeightValue = float("ChineseFontWeight", 0f, 0f..3f, "px")
        .onChanged { applyChineseFont() }

    init {
        syncFallbackFont()
    }

    /**
     * 应用全局字号/粗细：写入静态字段并请求渲染线程重烘焙核心字体。
     * 配置加载可能发生在非渲染线程，因此实际烘焙延迟到 Render2D 回调执行。
     */
    fun applyFontStyle() {
        Fonts.globalFontSizeScale = fontSizeValue.get()
        AWTFontRenderer.textWeight = fontWeightValue.get()
        AWTFontRenderer.requestStyleReload()
    }

    /** 中文回退设置变更：同步字段并请求渲染线程清除回退字形缓存。 */
    fun applyChineseFont() {
        syncFallbackFont()
        AWTFontRenderer.requestFallbackInvalidate()
    }

    /** 将回退字体设置同步到 AWTFontRenderer（纯静态字段赋值，无烘焙开销）。 */
    fun syncFallbackFont() {
        AWTFontRenderer.fallbackFontFile = when (chineseFontValue.get()) {
            "Setsuna" -> "Setsuna_pf_normal.ttf"
            else -> "HarmonyOS_Sans_SC_Regular.ttf"
        }
        AWTFontRenderer.fallbackFontSizeMode = chineseFontSizeModeValue.get()
        AWTFontRenderer.fallbackFontSizeOffset = chineseFontOffsetValue.get()
        AWTFontRenderer.fallbackFontSizeScale = chineseFontScaleValue.get()
        AWTFontRenderer.fallbackTextWeight = chineseFontWeightValue.get()
    }
}
