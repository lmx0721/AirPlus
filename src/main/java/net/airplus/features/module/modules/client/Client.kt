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
import java.awt.Color

/**
 * Client 字体设置模块（不可开关）：
 * - MainFont：客户端主字体（自动取自字体注册表全部非图标字体，切换后 Regular/Semibold/ExtraBold/Bold180 重新烘焙）
 * - FontSize / FontWeight：全局字号（百分比）与字体粗细（描边像素），作用于核心 UI 文本字体
 * - ChineseFont：中文回退字体（主字体缺字形时使用，仅内置两款中文字体可选）
 * - ChineseFontWeight：中文回退字形在全局粗细之上的额外描边量
 * - 回退字号支持两种方案：Offset（渲染器字号 + 偏移）/ Percent（渲染器字号 × 百分比）
 */
object Client : Module("Client", Category.CLIENT, canBeEnabled = false) {

    // ===== 全局 Blur 设置 =====
    // 客户端所有使用模糊的 UI（HUD 元素背景、ClickGUI、聊天栏、主题选择器、音乐界面、菜单等）
    // 统一读取这里的模糊类型与强度；单帧只对整个屏幕做一次全屏模糊，
    // 之后由 HudBlur 把预模糊纹理按各组件的遮罩形状裁剪贴回对应位置。
    private val blurModeValue = choices("BlurMode", arrayOf("None", "Kawase", "Dual", "Better", "Rise"), "Better")

    // 模糊强度（int 滑条），internal 供个别风格 GUI 直接绑定
    internal val blurStrengthValue = int("BlurStrength", 10, 1..50)

    // Rise 模糊专用：高斯核半径（Rise Interface.blurRadius）与采样间距压缩系数
    // （Rise Interface.blurCompression）。核权重按 sigma = radius / 2 预计算，
    // compression 直接拉伸水平/垂直 pass 的采样方向（总触达 = radius * compression px）。
    // 仅 BlurMode = Rise 时可调
    internal val blurRadiusValue = int("BlurRadius", 12, 1..30) { blurModeValue.get() == "Rise" }
    internal val blurCompressionValue = float("BlurCompression", 3.0f, 1f..10f) { blurModeValue.get() == "Rise" }

    // 模糊辉光：独立开关，可叠加在任意 BlurMode 上（BlurMode = None 时无模糊快照，不生效）。
    // 效果 = Rise 式形状 Bloom：blur 时把各组件的遮罩形状（alpha = 强度）累积进共享
    // 辉光源 FBO，帧末统一做水平 + 垂直两 pass 高斯并贴回主 framebuffer——光晕只画在
    // 组件形状外圈（形状内部 discard），跟随组件实际轮廓柔和晕开，无方形硬边。
    internal val blurGlowValue = boolean("Glow", false)

    // 辉光强度（百分比），仅 BlurGlow 开启时可调
    internal val blurGlowStrengthValue = int("GlowStrength", 60, 10..1000, "%") { blurGlowValue.get() }

    // 辉光颜色：光晕的染色颜色（以 RGB 为准，alpha 通道不参与）。
    // 仅 BlurGlow 开启时可调
    internal val blurGlowColorValue = color("GlowColor", Color(255, 255, 255, 0)) { blurGlowValue.get() }

    // Rise Bloom 专用：高斯核半径（Rise Interface.bloomRadius）与采样间距压缩系数
    // （Rise Interface.bloomCompression）。核权重按 sigma = radius / 2 预计算。
    // 仅 Glow 开启时可调
    internal val blurGlowRadiusValue = int("GlowRadius", 14, 1..30) { blurGlowValue.get() }
    internal val blurGlowCompressionValue = float("GlowCompression", 2.0f, 1f..10f) { blurGlowValue.get() }

    /** 当前全局模糊类型（"None" 表示禁用所有模糊） */
    val blurMode: String
        get() = blurModeValue.get()

    /** 当前全局模糊强度 */
    val blurStrength: Float
        get() = blurStrengthValue.get().toFloat()

    /** Rise 模糊高斯核半径（1..30，仅 BlurMode = Rise 时使用） */
    val blurRadius: Float
        get() = blurRadiusValue.get().toFloat()

    /** Rise 模糊采样间距压缩系数（仅 BlurMode = Rise 时使用） */
    val blurCompression: Float
        get() = blurCompressionValue.get()

    /** 是否启用模糊辉光叠加 */
    val blurGlow: Boolean
        get() = blurGlowValue.get()

    /** 模糊辉光强度（1.0 = 100%） */
    val blurGlowStrength: Float
        get() = blurGlowStrengthValue.get().toFloat()

    /** 模糊辉光颜色（光晕染色，以 RGB 为准） */
    val blurGlowColor: Color
        get() = blurGlowColorValue.get()

    /** Rise Bloom 高斯核半径（1..30） */
    val blurGlowRadius: Float
        get() = blurGlowRadiusValue.get().toFloat()

    /** Rise Bloom 采样间距压缩系数 */
    val blurGlowCompression: Float
        get() = blurGlowCompressionValue.get()

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
