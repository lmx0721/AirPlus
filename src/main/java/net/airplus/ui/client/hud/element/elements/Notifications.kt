/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 * skid edmoonlight
 */
package net.airplus.ui.client.hud.element.elements


import net.airplus.features.module.Module
import net.airplus.ui.client.hud.HUD.addNotification
import net.airplus.ui.client.hud.HUD.notifications
import net.airplus.ui.client.hud.designer.GuiHudDesigner
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.Element
import net.airplus.ui.client.hud.element.ElementInfo
import net.airplus.ui.client.hud.element.Side
import net.airplus.ui.client.hud.element.elements.Notification.Companion.maxTextLength
import net.airplus.ui.font.Fonts
import net.airplus.ui.font.GameFontRenderer
import net.airplus.utils.client.ClientThemesUtils
import net.airplus.utils.client.ClientUtils
import net.airplus.utils.extensions.lerpWith
import net.airplus.utils.render.ColorUtils.withAlpha
import net.airplus.utils.render.HudBlur
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.render.RenderUtils.deltaTime
import net.airplus.utils.render.RenderUtils.drawRoundedBorder
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.gui.FontRenderer
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.WorldRenderer
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.util.ResourceLocation
import java.awt.Color
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * CustomHUD Notification element
 */
@ElementInfo(name = "Notifications", single = true, priority = -1)
class Notifications(
    x: Double = 0.0, y: Double = 30.0, scale: Float = 1F, side: Side = Side(Side.Horizontal.RIGHT, Side.Vertical.DOWN)
) : Element("Notifications", x, y, scale, side) {

    val style by choices("Style", arrayOf("Classic", "Modern", "Compact", "Hanabi", "Flux", "Onyx", "Naven", "Southside", "Default", "Type 2", "Type 3", "Type 4", "Type 5", "Test2", "Exhi"), "Modern")
    val horizontalFade by choices("HorizontalFade", arrayOf("InOnly", "OutOnly", "Both", "None"), "OutOnly")
    val padding by int("Padding", 5, 1..20)
    val roundRadius by float("RoundRadius", 3f, 0f..10f)
    val color by color("BackgroundColor", Color.BLACK.withAlpha(128))
    val renderBorder by boolean("RenderBorder", false)
    val borderColor by color("BorderColor", Color.BLUE.withAlpha(255)) { renderBorder }
    val borderWidth by float("BorderWidth", 2f, 0.5F..5F) { renderBorder }

    // MoonLight 系列样式背景自定义（颜色 / 透明度 / blur）
    private val moonlightStyles = arrayOf("Default", "Test2", "Exhi", "Type 2", "Type 3", "Type 4", "Type 5")
    val moonBgSynced by boolean("Moonlight-BG-Synced", true) { style in moonlightStyles }
    val moonBgColorValue by color("Moonlight-BG-Color", Color(0, 0, 0)) { style in moonlightStyles && !moonBgSynced }
    val moonBgAlpha by int("Moonlight-BG-Alpha", 100, 0..255) { style in moonlightStyles }
    val moonBgBlur by boolean("Moonlight-Blur", false) { style in moonlightStyles }

    // 可自定义字体
    val titleFont by font("Title-Font", Fonts.fontSemibold40)
    val descFont by font("Description-Font", Fonts.fontSemibold35)

    // 绘制用字体（保证支持 Float 坐标绘制；选原版字体时回退为默认字体）
    val titleFontRenderer: GameFontRenderer
        get() = titleFont as? GameFontRenderer ?: Fonts.fontSemibold40
    val descFontRenderer: GameFontRenderer
        get() = descFont as? GameFontRenderer ?: Fonts.fontSemibold35

    private val exampleNotification = Notification("Example Title", "Example Description")

    private var index = 0

    override fun updateElement() {
        if (mc.currentScreen is GuiHudDesigner && ClientUtils.runTimeTicks % 60 == 0) {
            exampleNotification.severityType = SeverityType.entries[++index % SeverityType.entries.size]
        }
    }

    override fun drawElement(): Border? {
        var verticalOffset = 0f

        maxTextLength = maxOf(100, notifications.maxOfOrNull { it.textLength } ?: 0)

        notifications.removeIf { notification ->
            if (notification != exampleNotification) {
                notification.y = (notification.y..verticalOffset).lerpWith(RenderUtils.deltaTimeNormalized())
            }

            notification.drawNotification(this).also { done ->
                if (!done) {
                    // MoonLight 系列按原版卡片高度 + 原版堆叠偏移（Notification#getHeight 与
                    // NotificationManager#publish 的 actualOffset）；Southside 卡片高度随
                    // 描述换行行数变化（高 + 16）；其余样式沿用 MAX_HEIGHT + Padding
                    val moon = notification.moonMetrics(style)
                    verticalOffset += when {
                        moon != null -> moon.first.toFloat() + moon.second.toFloat()
                        style == "Southside" -> notification.moonSouthsideCardHeight() + 16F
                        else -> Notification.MAX_HEIGHT.toFloat() + padding.toFloat()
                    }
                }
            }
        }

        if (mc.currentScreen is GuiHudDesigner) {
            if (exampleNotification !in notifications) {
                index = 0
                addNotification(exampleNotification)
            }

            exampleNotification.fadeState = Notification.FadeState.STAY
            exampleNotification.textLength = titleFont.getStringWidth(exampleNotification.longestString(titleFont))

            val notificationHeight = Notification.MAX_HEIGHT

            exampleNotification.y = 0F

            return Border(
                -(maxTextLength.toFloat() + 24 + 20), -notificationHeight.toFloat(), 0F, 0F
            )
        }

        return null
    }

    enum class SeverityType(val path: ResourceLocation) {
        SUCCESS(ResourceLocation("airplus/notifications/success.png")), RED_SUCCESS(ResourceLocation("airplus/notifications/redsuccess.png")), INFO(
            ResourceLocation("airplus/notifications/info.png")
        ),
        WARNING(ResourceLocation("airplus/notifications/warning.png")), ERROR(ResourceLocation("airplus/notifications/error.png"))
    }
}

class Notification(
    var title: String,
    var description: String,
    private val delay: Long = 2000L,
    var severityType: Notifications.SeverityType = Notifications.SeverityType.INFO
) {
    var x = 0F

    // Spawn the notification 32 pixels above the last one - if exists.
    var y: Float = (notifications.lastOrNull()?.y ?: 0F) + MAX_HEIGHT * 2
    var textLength = 0

    val longestString: String
        get() = longestString(Fonts.fontSemibold40)

    /**
     * 按指定字体取较长的一行（自定义字体后各通知的宽度按实际绘制字体计算）。
     */
    fun longestString(font: FontRenderer): String =
        arrayOf(title, description).maxBy { font.getStringWidth(it) }

    /**
     * MoonLight 系列样式（1:1）：原版每样式的卡片高度（Notification#getHeight 的 switch）
     * 与堆叠偏移（NotificationManager#publish 中各 case 的 actualOffset）。
     * 非 MoonLight 样式返回 null。
     */
    fun moonMetrics(style: String): Pair<Int, Int>? = when (style) {
        "Default" -> 16 to 3
        "Test2" -> 33 to 10
        "Exhi" -> 26 to 0
        "Type 2" -> 30 to 3
        "Type 3" -> 35 to 8
        "Type 4" -> 27 to 5
        "Type 5" -> 24 to 6
        "Naven" -> 20 to 4
        else -> null
    }

    /**
     * MoonLight 原版字号映射（1:1）：MoonLight 字体管线 glScaled(0.5)（字形按 AWT 栅格尺寸
     * 一半绘制，getHeight() = maxCharBounds / 2），AirPlus AWTFontRenderer 为 glScaled(0.25)，
     * 同视觉尺寸需要 2 倍 AWT 字号，由 Fonts.fontMoonlight 按需烘焙。
     */
    fun moonFont(size: Int): GameFontRenderer = Fonts.fontMoonlight(size)

    /** 去除 § 颜色代码（Naven / Southside 的单行文案使用）。 */
    private fun stripColor(text: String): String {
        if (!text.contains('§')) return text
        return buildString {
            var i = 0
            while (i < text.length) {
                if (text[i] == '§' && i + 1 < text.length) i += 2 else {
                    append(text[i]); i++
                }
            }
        }
    }

    /** 模块开关通知判定（Module#toggle 的标题为状态词，描述为模块名）。 */
    private val isToggleNotification: Boolean
        get() {
            val t = title.trim()
            return t.equals("Enabled", true) || t.equals("Disabled", true) ||
                t == "已启用" || t == "已禁用" || t == "已停用" || t == "已啟用"
        }

    /** 开关通知的开启状态（SouthsideNotification#icon 的 getToggleState 等价：标题即状态词）。 */
    private val isToggleOn: Boolean
        get() {
            val t = title.trim()
            return t.equals("Enabled", true) || t == "已启用" || t == "已啟用"
        }

    /** MoonLight 通知类型（NotificationType 枚举 1:1：OKAY/INFO/NOTIFY/WARNING）。 */
    private enum class MoonType { OKAY, INFO, NOTIFY, WARNING }

    /** AirPlus severity → MoonLight NotificationType（与 moonlightIcon 同一映射）。 */
    private val moonlightNotificationType: MoonType = when (severityType) {
        Notifications.SeverityType.SUCCESS -> MoonType.OKAY
        Notifications.SeverityType.RED_SUCCESS -> MoonType.WARNING
        Notifications.SeverityType.INFO -> MoonType.INFO
        Notifications.SeverityType.WARNING -> MoonType.NOTIFY
        Notifications.SeverityType.ERROR -> MoonType.WARNING
    }

    /** 原版 NotificationManager#typeTint（Default 字形图标颜色）：OKAY 绿 / INFO 蓝 / NOTIFY 黄 / WARNING 红。 */
    private val moonlightTypeTint: Color = when (moonlightNotificationType) {
        MoonType.OKAY -> Color(110, 220, 140)
        MoonType.INFO -> Color(130, 180, 235)
        MoonType.NOTIFY -> Color(235, 200, 100)
        MoonType.WARNING -> Color(235, 105, 105)
    }

    /** MoonLight Southside 图标（SouthsideNotification#icon 1:1）：
     *  开关通知按开关状态 enabled/disabled，其余按类型 enabled/info/warning/error。 */
    private val southsideIcon: ResourceLocation
        get() = when {
            isToggleNotification -> if (isToggleOn) SOUTHSIDE_ENABLED_ICON else SOUTHSIDE_DISABLED_ICON
            else -> when (moonlightNotificationType) {
                MoonType.OKAY -> SOUTHSIDE_ENABLED_ICON
                MoonType.INFO -> SOUTHSIDE_INFO_ICON
                MoonType.NOTIFY -> SOUTHSIDE_WARNING_ICON
                MoonType.WARNING -> SOUTHSIDE_ERROR_ICON
            }
        }

    /**
     * MoonLight 开关通知的完整文案（原版 Module#toggle 传入的 description 为
     * "模块名 Enabled!/Disabled!"，Default 等只画 description 的样式因此显示状态词）：
     * 英文按原版语序 "模块名 Enabled!"，中文按翻译语序 "已启用 模块名!"。
     */
    fun moonToggleMessage(): String {
        val t = stripColor(title).trim()
        val d = stripColor(description).trim()
        val en = t.equals("Enabled", true) || t.equals("Disabled", true)
        return if (en) "$d $t!" else "$t $d!"
    }

    /** Naven 单行文案：模块开关 → 状态词 + 模块名，其余 → 描述（对齐 NavenNotification 的 message）。 */
    fun navenMessage(): String {
        val t = stripColor(title).trim()
        val d = stripColor(description).trim()
        return when {
            d.isEmpty() -> t
            isToggleNotification -> "$t $d"
            else -> d
        }
    }

    /** MoonLight Southside 正文：模块开关 → "Toggled {name} on/off."，其余 → 描述。 */
    fun moonSouthsideMessage(): String {
        val t = stripColor(title).trim()
        val d = stripColor(description).trim()
        return when {
            d.isEmpty() -> t
            t.equals("Enabled", true) -> "Toggled $d on."
            t.equals("Disabled", true) -> "Toggled $d off."
            t == "已启用" || t == "已啟用" -> "Toggled $d on."
            else -> "Toggled $d off."
        }
    }

    /** MoonLight Southside unit（SouthsideNotification unit 计算 1:1）：量化到 0.05 并除以 GUI 缩放。 */
    fun moonSouthsideUnit(): Float {
        val mc = Minecraft.getMinecraft()
        var unit = minOf(mc.displayWidth / 1920F, mc.displayHeight / 1080F)
        if (unit < 1F) unit = minOf(minOf(1F, mc.displayWidth / 1280F), minOf(1F, mc.displayHeight / 800F))
        unit = floor(unit / 0.05F) * 0.05F
        return maxOf(0.05F, unit) / ScaledResolution(mc).scaleFactor
    }

    /** 文本按宽度换行（SouthsideNotification wrap 的 1:1 移植，按空格分词）。 */
    fun moonWrap(font: FontRenderer, message: String, width: Float): List<String> {
        val lines = ArrayList<String>()
        var line = ""
        for (word in message.split(" ")) {
            if (word.isEmpty()) continue
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && font.getStringWidth(candidate) > width) {
                lines.add(line)
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty() || lines.isEmpty()) lines.add(line)
        return lines
    }

    /** MoonLight Southside 卡片高度：设计高 68 + 14.5·换行行数 + 5，按 unit 缩放
     *  （换行按原版字体在 220·unit 设计宽内测量，与绘制一致）。 */
    fun moonSouthsideCardHeight(): Float {
        val unit = moonSouthsideUnit()
        val lines = moonWrap(Minecraft.getMinecraft().fontRendererObj, moonSouthsideMessage(), 220F * unit)
        return (68F + 14.5F * lines.size + 5F) * unit
    }

    /** Naven 卡片色（NavenNotification 类型色映射）：OKAY 绿 / INFO 深 / NOTIFY 暗红 / WARNING 红。 */
    private fun navenLevelColor(): Color = when (severityType) {
        Notifications.SeverityType.SUCCESS -> Color(23, 150, 38)
        Notifications.SeverityType.INFO -> Color(23, 22, 38)
        Notifications.SeverityType.RED_SUCCESS -> Color(138, 90, 92)
        Notifications.SeverityType.WARNING, Notifications.SeverityType.ERROR -> Color(148, 42, 43)
    }

    /** easeInOutQuad（SouthsideNotification slideIn 的缓动曲线）。 */
    private fun easeInOutQuad(t: Float): Float =
        if (t < 0.5F) 2F * t * t else 1F - (-2F * t + 2F).pow(2) / 2F

    private var stay = delay
    private var fadeStep = 0F
    var fadeState = FadeState.IN

    /**
     * Used when the same module state changes within the fade in/stay time window.
     */
    fun replaceModuleNotification(title: String, description: String, severityType: Notifications.SeverityType) {
        if (fadeState.ordinal > 1) {
            return
        }

        // Re-setup every important information
        stay = delay
        this.severityType = severityType
        this.title = title
        this.description = description

        textLength = Fonts.fontSemibold40.getStringWidth(longestString)
        maxTextLength = maxOf(textLength, maxTextLength)

        notifications.sortBy { it.stay }
    }

    companion object {
        fun informative(title: String, message: String, delay: Long = 2000L) =
            Notification(title, message, delay, Notifications.SeverityType.INFO)

        fun informative(title: Module, message: String, delay: Long = 2000L) =
            Notification(title.spacedName, message, delay, Notifications.SeverityType.INFO)

        fun error(title: Module, message: String, delay: Long = 2000L) =
            Notification(title.spacedName, message, delay, Notifications.SeverityType.ERROR)

        fun warning(title: Module, message: String, delay: Long = 2000L) =
            Notification(title.spacedName, message, delay, Notifications.SeverityType.WARNING)

        var maxTextLength = 0
        const val MAX_HEIGHT = 32
        const val ICON_SIZE = 24

        // MoonLight 原版通知图标（Ported from moonlight/texture/noti/）
        private val MOONLIGHT_OKAY_ICON = ResourceLocation("airplus/notifications/moonlight/okay.png")
        private val MOONLIGHT_INFO_ICON = ResourceLocation("airplus/notifications/moonlight/info.png")
        private val MOONLIGHT_NOTIFY_ICON = ResourceLocation("airplus/notifications/moonlight/notify.png")
        private val MOONLIGHT_WARNING_ICON = ResourceLocation("airplus/notifications/moonlight/warning.png")

        // MoonLight Southside 原版专用图标（Ported from moonlight/texture/southside/）
        private val SOUTHSIDE_ENABLED_ICON = ResourceLocation("airplus/notifications/southside/enabled.png")
        private val SOUTHSIDE_DISABLED_ICON = ResourceLocation("airplus/notifications/southside/disabled.png")
        private val SOUTHSIDE_INFO_ICON = ResourceLocation("airplus/notifications/southside/info.png")
        private val SOUTHSIDE_WARNING_ICON = ResourceLocation("airplus/notifications/southside/warning.png")
        private val SOUTHSIDE_ERROR_ICON = ResourceLocation("airplus/notifications/southside/error.png")
        private val MODERN_BG = Color(16, 16, 20, 215)
        private val COMPACT_BG = Color(14, 14, 18, 200)
        private val HANABI_BG = Color(36, 36, 36, 217).rgb
        private val FLUX_BG = Color(38, 41, 43)

        // Onyx 样式（卡片几何对齐 Keybinds 元素；垂直间距沿用 MAX_HEIGHT）
        private const val ONYX_HEIGHT = 34F
        private const val ONYX_RADIUS = 12F
    }

    enum class FadeState {
        IN, STAY, OUT, END
    }

    init {
        textLength = Fonts.fontSemibold40.getStringWidth(longestString)
        maxTextLength = maxOf(maxTextLength, textLength)
    }

    fun drawNotification(element: Notifications): Boolean {
        // Hanabi style: single-line message, width derived from the full text
        val hanabi = element.style == "Hanabi"
        // Flux style: width derived from the Flux-native Poppins fonts
        val flux = element.style == "Flux"
        // Onyx style: dynamic-island card, width clamped to 130..240
        val onyx = element.style == "Onyx"
        // MoonLight 系列样式（Ported from MoonLight NotificationManager，各样式宽度按原公式换算为本客户端字体）
        val moonDefault = element.style == "Default"
        val moonTest2 = element.style == "Test2"
        val moonExhi = element.style == "Exhi"
        val moonType2 = element.style == "Type 2"
        val moonType3 = element.style == "Type 3"
        val moonType4 = element.style == "Type 4"
        val moonType5 = element.style == "Type 5"
        // MoonLight Naven：20px 高彩色胶囊卡，单行文案右缘滑入
        val naven = element.style == "Naven"
        // MoonLight Southside：设计宽 314 的深灰圆角卡 + 底部进度条，按 unit 缩放右缘滑入
        val moonSouthside = element.style == "Southside"
        val moonStyle = moonDefault || moonTest2 || moonExhi || moonType2 || moonType3 || moonType4 || moonType5 ||
            naven || moonSouthside
        val notificationWidth = if (hanabi) {
            val width = element.titleFontRenderer.getStringWidth("$title $description") + 45F
            textLength = width.toInt()
            width
        } else if (flux) {
            // Flux 样式宽度按 Poppins 字体实测（对应 Flux 原版 max(标题, 正文) + 40）
            val width = Fonts.fontFluxTitle.getStringWidth(longestString(Fonts.fontFluxTitle)) + 45F
            textLength = width.toInt()
            width
        } else if (onyx) {
            // Onyx 样式：宽度 = max(130, 最长文本 + 45)，封顶 240
            val width = maxOf(
                130F,
                element.titleFontRenderer.getStringWidth(longestString(element.titleFontRenderer)) + 45F
            ).coerceAtMost(240F)
            textLength = width.toInt()
            width
        } else if (moonDefault) {
            // MoonLight Default（1:1）：interMedium(15) 描述宽 + 5（开关通知的描述为完整文案，见 moonToggleMessage）
            val width = moonFont(15).getStringWidth(moonToggleMessage()) + 5F
            textLength = width.toInt()
            width
        } else if (moonTest2) {
            // MoonLight Test2（1:1）：max(interSemiBold(17) 标题, interRegular(17) 描述) + 10
            val width = maxOf(
                moonFont(17).getStringWidth(title),
                moonFont(17).getStringWidth(description)
            ).toFloat() + 10F
            textLength = width.toInt()
            width
        } else if (moonExhi) {
            // MoonLight Exhi（1:1）：max(100, max(interRegular(18) 标题 + 2, interRegular(14) 描述) + 24)
            val width = maxOf(
                100F,
                maxOf(
                    moonFont(18).getStringWidth(title).toFloat() + 2F,
                    moonFont(14).getStringWidth(description).toFloat()
                ) + 24F
            )
            textLength = width.toInt()
            width
        } else if (moonType2) {
            // MoonLight Type 2（1:1）：max(100, max(interRegular(18) 标题, interRegular(14) 描述) + 70)
            val width = maxOf(
                100F,
                maxOf(
                    moonFont(18).getStringWidth(title).toFloat(),
                    moonFont(14).getStringWidth(description).toFloat()
                ) + 70F
            )
            textLength = width.toInt()
            width
        } else if (moonType3) {
            // MoonLight Type 3（1:1）：max(interRegular(22) 标题, interRegular(20) 描述) + 50
            val width = maxOf(
                moonFont(22).getStringWidth(title),
                moonFont(20).getStringWidth(description)
            ).toFloat() + 50F
            textLength = width.toInt()
            width
        } else if (moonType4) {
            // MoonLight Type 4（1:1）：max(140, max(interRegular(10) 标题, interRegular(6) 描述) + 40)（原版即如此，140 通常主导）
            val width = maxOf(
                140F,
                maxOf(
                    moonFont(10).getStringWidth(title).toFloat(),
                    moonFont(6).getStringWidth(description).toFloat()
                ) + 40F
            )
            textLength = width.toInt()
            width
        } else if (moonType5) {
            // MoonLight Type 5（1:1）：原版用 Minecraft.getMinecraft().fontRendererObj，max(标题, 描述) + 16 + 25
            val width = maxOf(
                Minecraft.getMinecraft().fontRendererObj.getStringWidth(title),
                Minecraft.getMinecraft().fontRendererObj.getStringWidth(description)
            ).toFloat() + 41F
            textLength = width.toInt()
            width
        } else if (naven) {
            // MoonLight Naven（1:1）：siyuan(18) 单行文案宽 + 12
            val width = moonFont(18).getStringWidth(navenMessage()) + 12F
            textLength = width.toInt()
            width
        } else if (moonSouthside) {
            // MoonLight Southside（1:1）：设计宽 314 × unit
            val width = 314F * moonSouthsideUnit()
            textLength = width.toInt()
            width
        } else {
            // 宽度按所选标题字体实时计算
            textLength = element.titleFontRenderer.getStringWidth(longestString(element.titleFontRenderer))
            maxTextLength + ICON_SIZE + 16F
        }
        val extraSpace = 4F

        val currentX = when (fadeState) {
            // Onyx 自带滑入动画（progress 由 x 映射），不受默认 HorizontalFade=OutOnly 影响
            // MoonLight Default 原版为透明度渐入（animation.getOutput 驱动），同样由 x 进度映射
            // Naven / Southside 原版同样右缘滑入，由 x 进度映射
            FadeState.IN -> if (element.horizontalFade in arrayOf("InOnly", "Both") || onyx || moonDefault || naven || moonSouthside) x else notificationWidth
            FadeState.OUT -> if (element.horizontalFade in arrayOf("OutOnly", "Both")) x else notificationWidth
            else -> x
        }

        when {
            hanabi -> drawHanabi(element, currentX, notificationWidth)
            element.style == "Modern" -> drawModern(element, currentX, extraSpace)
            element.style == "Compact" -> drawCompact(element, currentX, extraSpace)
            element.style == "Flux" -> drawFlux(element, currentX, extraSpace)
            element.style == "Onyx" -> drawOnyx(element, currentX, notificationWidth)
            naven -> drawNaven(element, currentX, notificationWidth)
            moonSouthside -> drawMoonSouthside(element, currentX, notificationWidth)
            moonDefault -> drawMoonDefault(element, currentX, notificationWidth)
            moonTest2 -> drawMoonTest2(element, currentX, notificationWidth)
            moonExhi -> drawMoonExhi(element, currentX, notificationWidth)
            moonType2 -> drawMoonType2(element, currentX, notificationWidth)
            moonType3 -> drawMoonType3(element, currentX, notificationWidth)
            moonType4 -> drawMoonType4(element, currentX, notificationWidth)
            moonType5 -> drawMoonType5(element, currentX, notificationWidth)
            else -> drawClassic(element, currentX, extraSpace)
        }

        val delta = deltaTime

        when (fadeState) {
            FadeState.IN -> {
                if (x < notificationWidth) {
                    x += delta
                }
                if (x >= notificationWidth) {
                    fadeState = FadeState.STAY
                    x = notificationWidth
                    fadeStep = notificationWidth
                }
                stay = delay
            }

            FadeState.STAY -> {
                if (textLength != maxTextLength || (onyx && x < notificationWidth)) {
                    maxTextLength = maxOf(textLength, maxTextLength)
                    x = if (hanabi || flux || onyx || moonStyle) notificationWidth else maxTextLength + ICON_SIZE + 16F
                    fadeStep = x
                }
                stay -= delta
                if (stay <= 0) {
                    fadeState = FadeState.OUT
                }
            }

            FadeState.OUT -> if (x > 0) {
                x -= delta
                y -= delta / 4F
            } else {
                fadeState = FadeState.END
            }

            FadeState.END -> return true
        }

        return false
    }

    private fun drawClassic(element: Notifications, currentX: Float, extraSpace: Float) {
        drawRoundedRect(0F, -y - MAX_HEIGHT, -currentX - extraSpace, -y, element.color.rgb, element.roundRadius)

        if (element.renderBorder) {
            drawRoundedBorder(
                0F,
                -y - MAX_HEIGHT,
                -currentX - extraSpace,
                -y,
                element.borderWidth,
                element.borderColor.rgb,
                element.roundRadius
            )
        }

        val nearTopSpot = -y - MAX_HEIGHT + 10

        element.titleFontRenderer.drawString(title, ICON_SIZE + 8F - currentX, nearTopSpot - 5, Color.WHITE.rgb)
        element.descFontRenderer.drawString(
            description, ICON_SIZE + 8F - currentX, nearTopSpot + element.titleFontRenderer.height - 2, Int.MAX_VALUE
        )

        RenderUtils.drawImage(
            severityType.path, -currentX + 2, -y - MAX_HEIGHT + 4, ICON_SIZE, ICON_SIZE, radius = element.roundRadius
        )
    }

    /**
     * Modern 样式：深色背景 + 左侧 severity 强调条 + 居中图标 + 白色标题/灰色描述 + 底部剩余时间进度条
     */
    private fun drawModern(element: Notifications, currentX: Float, extraSpace: Float) {
        val radius = element.roundRadius
        val cardLeft = -currentX - extraSpace
        val cardTop = -y - MAX_HEIGHT
        val cardBottom = -y

        // 深色背景
        drawRoundedRect(cardLeft, cardTop, 0F, cardBottom, MODERN_BG.rgb, radius)

        // 左侧强调条（severity 颜色）
        drawRoundedRect(cardLeft, cardTop, cardLeft + 3F, cardBottom, accentColor.rgb, radius)

        // 居中 severity 图标
        val iconX = cardLeft + 9F
        val iconY = cardTop + (MAX_HEIGHT - 16) / 2F
        RenderUtils.drawImage(severityType.path, iconX, iconY, 16, 16, radius = radius)

        // 标题 + 描述
        val textX = cardLeft + 31F
        element.titleFontRenderer.drawString(title, textX, cardTop + 5F, Color.WHITE.rgb)
        element.descFontRenderer.drawString(
            description, textX, cardTop + 5F + element.titleFontRenderer.height + 1F, Color(170, 170, 170).rgb
        )

        // 底部剩余时间进度条（仅在滑入/停留阶段显示）
        if (fadeState == FadeState.IN || fadeState == FadeState.STAY) {
            val progress = (stay / delay.toFloat()).coerceIn(0F, 1F)
            val barLeft = cardLeft + 4F
            val barRight = -4F
            val barY = cardBottom - 3F
            drawRoundedRect(barLeft, barY, barRight, barY + 1.5F, Color(255, 255, 255, 40).rgb, 0F)
            drawRoundedRect(barLeft, barY, barLeft + (barRight - barLeft) * progress, barY + 1.5F, accentColor.rgb, 0F)
        }
    }

    /**
     * Hanabi 样式：迁移自 cn.hanabi.gui.notifications.Notification。
     * 深色矩形背景 + 白色单行文字（模块名白色 / Enabled、Disabled 灰色）+
     * 左侧 severity 圆点 + 底部随时间增长的白色进度条。
     */
    private fun drawHanabi(element: Notifications, currentX: Float, notificationWidth: Float) {
        val height = 22F
        val cardLeft = -currentX - 4F
        val cardTop = -y - height
        val cardBottom = -y

        // 深色背景（Hanabi reAlpha(color, 0.85f)）
        drawRoundedRect(cardLeft, cardTop, cardLeft + notificationWidth, cardBottom, HANABI_BG, 0F)

        // 底部进度条：随剩余时间增长（Hanabi 用 timer.getLastMs() 计算已流逝比例）
        if (fadeState == FadeState.IN || fadeState == FadeState.STAY) {
            val elapsed = (1F - stay / delay.toFloat()).coerceIn(0F, 1F)
            drawRoundedRect(
                cardLeft, cardBottom - 1F,
                cardLeft + (notificationWidth - 4F) * elapsed + 2F, cardBottom,
                Color(255, 255, 255, 217).rgb, 0F
            )
        }

        // severity 圆点（原 icon.ttf 字形已随字体删除，改用主题色圆点表达严重级别）
        drawRoundedRect(cardLeft + 6F, cardTop + height / 2F - 4F, cardLeft + 14F, cardTop + height / 2F + 4F, accentColor.rgb, 4F)

        // 单行文字：模块名白色，状态（Enabled/Disabled）灰色（文字随所选字体，垂直居中）
        val textX = cardLeft + 20F
        val textFont = element.titleFontRenderer
        val textY = cardTop + (height - textFont.height) / 2F
        textFont.drawString(title, textX, textY, Color.WHITE.rgb)
        if (description.isNotEmpty()) {
            textFont.drawString(
                description,
                textX + textFont.getStringWidth(title) + 3F, textY,
                Color(160, 160, 160).rgb
            )
        }
    }

    private val accentColor: Color
        get() = when (severityType) {
            Notifications.SeverityType.SUCCESS, Notifications.SeverityType.RED_SUCCESS -> Color(76, 175, 80)
            Notifications.SeverityType.INFO -> ClientThemesUtils.getColor()
            Notifications.SeverityType.WARNING -> Color(255, 152, 0)
            Notifications.SeverityType.ERROR -> Color(244, 67, 54)
        }

    /**
     * Compact 样式：无图标的紧凑胶囊。severity 用一枚带呼吸动画的圆点表达，
     * 背景为深色全圆角胶囊并带一层主题色描边，底部保留剩余时间进度条。
     */
    private fun drawCompact(element: Notifications, currentX: Float, extraSpace: Float) {
        val height = MAX_HEIGHT.toFloat()
        val cardLeft = -currentX - extraSpace
        val cardTop = -y - height
        val cardBottom = -y
        val radius = height / 2F

        // 深色全圆角胶囊背景
        drawRoundedRect(cardLeft, cardTop, 0F, cardBottom, COMPACT_BG.rgb, radius)

        // 呼吸圆点（severity 颜色）：滑入时更亮，停留阶段轻柔呼吸
        val breath = 0.5F + 0.5F * sin((System.currentTimeMillis() % 1600L) / 1600F * 2F * Math.PI.toFloat())
        val entryBoost = if (fadeState == FadeState.IN) (currentX / (maxTextLength + ICON_SIZE + 16F)).coerceIn(0F, 1F) else 1F
        val glow = (0.35F + 0.65F * breath) * entryBoost

        val dotCX = cardLeft + 10F
        val dotCY = cardTop + height / 2F

        // 外圈柔光
        drawRoundedRect(dotCX - 7F * glow, dotCY - 7F * glow, dotCX + 7F * glow, dotCY + 7F * glow, accentColor.withAlpha((60 * glow).toInt()).rgb, 7F * glow)
        // 圆点本体
        drawRoundedRect(dotCX - 2.5F, dotCY - 2.5F, dotCX + 2.5F, dotCY + 2.5F, accentColor.rgb, 2.5F)

        // 标题 + 描述
        val textX = cardLeft + 20F
        element.titleFontRenderer.drawString(title, textX, cardTop + 5F, Color.WHITE.rgb)
        element.descFontRenderer.drawString(
            description, textX, cardTop + 5F + element.titleFontRenderer.height + 1F, Color(165, 165, 170).rgb
        )

        // 底部剩余时间进度条（仅在滑入/停留阶段显示）
        if (fadeState == FadeState.IN || fadeState == FadeState.STAY) {
            val progress = (stay / delay.toFloat()).coerceIn(0F, 1F)
            val barLeft = textX
            val barRight = -10F
            val barY = cardBottom - 3.5F
            drawRoundedRect(barLeft, barY, barRight, barY + 1.2F, Color(255, 255, 255, 30).rgb, 0.6F)
            drawRoundedRect(barLeft, barY, barLeft + (barRight - barLeft) * progress, barY + 1.2F, accentColor.withAlpha(220).rgb, 0.6F)
        }
    }

    /**
     * Flux 样式：迁移自 today.flux.gui.hud.notification.Notification（"New" notifMode）。
     * 深色圆角矩形背景 + 左侧 22px 类型色条 + 色条右缘三角箭头装饰 +
     * 类型色标题（PoppinsSemiBold）+ 白色描述（PoppinsRegular），severity 图标画在色条上。
     */
    private fun drawFlux(element: Notifications, currentX: Float, extraSpace: Float) {
        val cardLeft = -currentX - extraSpace
        val cardTop = -y - MAX_HEIGHT
        val cardBottom = -y
        val radius = element.roundRadius

        // 深色背景（Flux 0xff26292b）
        drawRoundedRect(cardLeft, cardTop, 0F, cardBottom, FLUX_BG.rgb, radius)

        // 左侧 22px 类型色条
        drawRoundedRect(cardLeft, cardTop, cardLeft + 22F, cardBottom, fluxAccentColor.rgb, radius)

        // 色条右缘三角箭头装饰（Flux drawArrow）
        drawArrow(cardLeft + 21F, cardTop + 5F, cardLeft + 27F, cardBottom - 5F, fluxAccentColor.rgb)

        // severity 图标：Flux Icon.ttf 字形（A=info B=warning C=error D=success），白色绘制在色条上
        // RED_SUCCESS（模块禁用）用叉号 "C"，与启用的对勾 "D" 区分
        val fluxIcon = when (severityType) {
            Notifications.SeverityType.INFO -> "A"
            Notifications.SeverityType.WARNING -> "B"
            Notifications.SeverityType.ERROR, Notifications.SeverityType.RED_SUCCESS -> "C"
            Notifications.SeverityType.SUCCESS -> "D"
        }
        // 按字体实测宽高在色条内居中（固定值会因字形边界不同而偏移）
        val iconFont = Fonts.fontFluxIcon
        val iconWidth = iconFont.getStringWidth(fluxIcon).toFloat()
        val iconHeight = iconFont.height.toFloat()
        iconFont.drawString(
            fluxIcon,
            cardLeft + (22F - iconWidth) / 2F,
            cardTop + (MAX_HEIGHT - iconHeight) / 2F,
            Color.WHITE.rgb
        )

        // 标题（类型色，PoppinsSemiBold）+ 描述（白色，PoppinsRegular），字体锁定为 Flux 原版设计
        // textTop 取 3F 而非数学居中的 6F：AWT 字形在行高内视觉偏下，整体上移与色条图标对齐
        val textX = cardLeft + 30F
        val textTop = cardTop + 3F
        Fonts.fontFluxTitle.drawString(title, textX, textTop, fluxTitleColor.rgb)
        Fonts.fontFluxDesc.drawString(
            description, textX, textTop + Fonts.fontFluxTitle.height + 2F, Color.WHITE.rgb
        )
    }

    /**
     * Flux 三角箭头装饰，迁移自 today.flux.gui.hud.notification.Notification#drawArrow。
     */
    private fun drawArrow(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        var l = left
        var t = top
        var r = right
        var b = bottom
        if (l < r) {
            val tmp = l
            l = r
            r = tmp
        }
        if (t < b) {
            val tmp = t
            t = b
            b = tmp
        }
        val a = (color shr 24 and 255) / 255.0F
        val red = (color shr 16 and 255) / 255.0F
        val green = (color shr 8 and 255) / 255.0F
        val blue = (color and 255) / 255.0F
        val worldRenderer = Tessellator.getInstance().worldRenderer
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 1)
        GlStateManager.color(red, green, blue, a)
        worldRenderer.begin(7, DefaultVertexFormats.POSITION)
        worldRenderer.pos(l.toDouble(), (b + 6f).toDouble(), 0.0).endVertex()
        worldRenderer.pos(r.toDouble(), b.toDouble(), 0.0).endVertex()
        worldRenderer.pos(r.toDouble(), t.toDouble(), 0.0).endVertex()
        worldRenderer.pos(l.toDouble(), (t - 6f).toDouble(), 0.0).endVertex()
        Tessellator.getInstance().draw()
        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
        GlStateManager.color(1.0F, 1.0F, 1.0F, 2.0F)
    }

    /**
     * Flux 五色强调条（忠实还原 today.flux 配色）。
     * RED_SUCCESS（模块禁用）与 ERROR 同用红色，和启用的绿色区分。
     */
    private val fluxAccentColor: Color
        get() = when (severityType) {
            Notifications.SeverityType.SUCCESS -> Color(114, 181, 94)
            Notifications.SeverityType.RED_SUCCESS, Notifications.SeverityType.ERROR -> Color(240, 71, 71)
            Notifications.SeverityType.INFO -> Color(66, 134, 245)
            Notifications.SeverityType.WARNING -> Color(239, 188, 18)
        }

    /**
     * Flux 标题颜色（SUCCESS 标题色与强调条色不同）。
     */
    private val fluxTitleColor: Color
        get() = when (severityType) {
            Notifications.SeverityType.SUCCESS -> Color(35, 173, 92)
            Notifications.SeverityType.RED_SUCCESS, Notifications.SeverityType.ERROR -> Color(240, 71, 71)
            else -> fluxAccentColor
        }

    /**
     * Onyx 样式：34px 高的圆角岛卡（圆角 12；垂直间距仍按 MAX_HEIGHT=32 推进）。
     * severity 图标 16px + 白色标题/灰色描述 + 底部 accent 剩余时间进度条。
     * 滑入：progress 由现有 fadeState 的 x 推进映射（0→1），14px 位移 + 透明度。
     */
    private fun drawOnyx(element: Notifications, currentX: Float, notificationWidth: Float) {
        val progress = (currentX / notificationWidth).coerceIn(0F, 1F)
        val alpha = progress
        val cardRight = (1F - progress) * 14F // 入场：右侧 14px 位移滑入
        val cardLeft = cardRight - notificationWidth
        val cardTop = -y - ONYX_HEIGHT
        val cardBottom = -y

        // 岛卡背景（深色底、圆角 12，随入场透明度淡入）
        drawRoundedRect(
            cardLeft, cardTop, cardRight, cardBottom,
            Color(20, 20, 24, (220 * alpha).toInt()).rgb, ONYX_RADIUS
        )

        // severity 图标（16px 垂直居中）
        RenderUtils.drawImage(
            severityType.path,
            cardLeft + 10F,
            cardTop + (ONYX_HEIGHT - 16) / 2F,
            16, 16,
            color = Color(255, 255, 255, (255 * alpha).toInt()),
            radius = 4F
        )

        // 标题 + 描述
        val textX = cardLeft + 32F
        element.titleFontRenderer.drawString(title, textX, cardTop + 6F, Color(255, 255, 255, (255 * alpha).toInt()).rgb)
        element.descFontRenderer.drawString(
            description, textX, cardTop + 6F + element.titleFontRenderer.height + 1F,
            Color(170, 170, 175, (255 * alpha).toInt()).rgb
        )

        // 底部 accent 剩余时间进度条（滑入/停留阶段显示）
        if (fadeState == FadeState.IN || fadeState == FadeState.STAY) {
            val remain = (stay / delay.toFloat()).coerceIn(0F, 1F)
            val barLeft = cardLeft + 10F
            val barRight = cardRight - 10F
            val barY = cardBottom - 3.5F
            drawRoundedRect(barLeft, barY, barRight, barY + 1.5F, Color(255, 255, 255, (40 * alpha).toInt()).rgb, 0.75F)
            drawRoundedRect(
                barLeft, barY, barLeft + (barRight - barLeft) * remain, barY + 1.5F,
                accentColor.withAlpha((255 * alpha).toInt()).rgb, 0.75F
            )
        }
    }

    // ===== MoonLight 系列样式（Ported from MoonLight NotificationManager#publish）=====
    // 原版以 Interface 的 bgColor()/color() 为配色，此处映射为 BackgroundColor 设置 / 主题色 / severity 类型色；
    // 出入场由本客户端 fade 系统承担（MoonLight 原版为 Animation 驱动）。

    /**
     * MoonLight 原版通知图标（moonlight/texture/noti/{name}.png），按 severity 映射。
     */
    private val moonlightIcon: ResourceLocation
        get() = when (severityType) {
            // RED_SUCCESS（模块禁用）对应 MoonLight 的 WARNING（红色叉号图标），与启用的 okay 区分
            Notifications.SeverityType.SUCCESS -> MOONLIGHT_OKAY_ICON
            Notifications.SeverityType.RED_SUCCESS -> MOONLIGHT_WARNING_ICON
            Notifications.SeverityType.INFO -> MOONLIGHT_INFO_ICON
            Notifications.SeverityType.WARNING -> MOONLIGHT_NOTIFY_ICON
            Notifications.SeverityType.ERROR -> MOONLIGHT_WARNING_ICON
        }

    private fun moonOpacity(color: Int, opacity: Float): Int {
        val a = ((color shr 24 and 255) * opacity.coerceIn(0F, 1F)).toInt()
        return (color and 0xFFFFFF) or (a shl 24)
    }

    /**
     * MoonLight 系列卡片背景色（可自定义）：
     * Moonlight-BG-Synced 开启时跟随主题色加深（对应 MoonLight bgColor "Synced" 默认），
     * 否则用 Moonlight-BG-Color；透明度由 Moonlight-BG-Alpha 控制（默认 100，同原版 bgAlpha）。
     */
    private fun moonBgColor(element: Notifications): Color {
        val base = if (element.moonBgSynced) Color(ClientThemesUtils.getColor().rgb).darker().darker()
        else element.moonBgColorValue
        return base.withAlpha(element.moonBgAlpha)
    }

    /**
     * MoonLight 系列卡片背景 blur（Moonlight-Blur 开启时）。
     * 模糊 AABB 需换算成屏幕绝对坐标（同 TabGUI），遮罩在当前局部矩阵下绘制；
     * 模糊类型/强度由 Client 模块的全局 Blur 设置统一控制。
     */
    private fun moonBlurBehind(element: Notifications, cardLeft: Float, cardTop: Float, w: Float, h: Float, radius: Float) {
        if (!element.moonBgBlur) return
        val s = element.scale
        HudBlur.blur(
            (s * (cardLeft + element.renderX)).toFloat(),
            (s * (cardTop + element.renderY)).toFloat(),
            (s * (cardLeft + w + element.renderX)).toFloat(),
            (s * (cardTop + h + element.renderY)).toFloat(),
            radius = radius
        ) {
            drawRoundedRect(cardLeft, cardTop, cardLeft + w, cardTop + h, -1, radius)
        }
    }

    /**
     * MoonLight Default（1:1，原 NotificationManager "Default" case，高 16）：
     * 仅描述文字水平居中的胶囊，背景/文字透明度随出入场（原 animation.getOutput 驱动），
     * 背景宽 + 2、圆角 4、堆叠偏移 3。
     */
    private fun drawMoonDefault(element: Notifications, currentX: Float, notificationWidth: Float) {
        val progress = (currentX / notificationWidth).coerceIn(0F, 1F)
        val h = moonMetrics(element.style)?.first?.toFloat() ?: 16F
        val cardLeft = -currentX - 4F
        val cardTop = -y - h
        // MoonLight 开关通知的描述本身即 "模块名 Enabled!" 完整文案
        val message = moonToggleMessage()

        moonBlurBehind(element, cardLeft, cardTop, notificationWidth + 2F, h, 4F)
        drawRoundedRect(
            cardLeft, cardTop, cardLeft + notificationWidth + 2F, cardTop + h,
            moonOpacity(moonBgColor(element).rgb, progress), 4F
        )
        val font = moonFont(15)
        // 原版左侧 15px 图标区：文本在剩余宽度内居中，图标区左侧 6px 处画 0.6px 灰色竖条 + 类型字形图标
        val textCenterX = cardLeft + 15F + (notificationWidth - 15F) / 2F
        val textLeft = textCenterX - font.getStringWidth(message) / 2F
        drawRoundedRect(
            textLeft - 6F, cardTop + 3.5F, textLeft - 5.4F, cardTop + h - 6F,
            moonOpacity(Color(100, 100, 100, 150).rgb, progress), 0F
        )
        // 字形图标（原 Fonts.noti "B"/"A"、Fonts.Icon "q"/"X"，typedIcon 默认 true → typeTint 色）：
        // 锚点 x = cardLeft + (15-8)/2, y = cardTop + (h-2)/2
        val gx = cardLeft + 3.5F
        val gy = cardTop + (h - 2F) / 2F
        val tint = moonOpacity(moonlightTypeTint.rgb, progress)
        when (moonlightNotificationType) {
            Notification.MoonType.INFO -> Fonts.fontMoonNoti(20).drawString("B", gx + 2F, gy, tint)
            Notification.MoonType.NOTIFY -> Fonts.fontMoonNoti(20).drawString("A", gx + 2F, gy, tint)
            Notification.MoonType.WARNING -> Fonts.fontMoonGlyphIcon(20).drawString("q", gx - 0.5F, gy - 1F, tint)
            Notification.MoonType.OKAY -> Fonts.fontMoonGlyphIcon(20).drawString("X", gx - 0.5F, gy - 1F, tint)
        }
        font.drawString(
            message,
            textCenterX - font.getStringWidth(message) / 2F,
            // 对应原版 yVal + getMiddleOfBox(height) + 2（getMiddleOfBox = h/2 - getHeight()/2），
            // MoonLight 的 getHeight() 对应 AirPlus 的 visualLineHeight
            cardTop + h / 2F - font.visualLineHeight / 2F + 2F,
            moonOpacity(Color.WHITE.rgb, progress)
        )
    }

    /**
     * MoonLight Test2（1:1，原 "Test2" case，高 33）：
     * 矩形卡片（背景 Synced 主题深色），标题 (x+7, y+7) 白色、描述 (x+7, y+18) 白色 120 透明度，
     * 底部 1px 主题色进度条随已流逝时间增长（width * elapsed），堆叠偏移 10。
     */
    private fun drawMoonTest2(element: Notifications, currentX: Float, notificationWidth: Float) {
        val h = moonMetrics(element.style)?.first?.toFloat() ?: 33F
        val cardLeft = -currentX - 5F
        val cardTop = -y - h

        moonBlurBehind(element, cardLeft, cardTop, notificationWidth, h, 0F)
        drawRoundedRect(cardLeft, cardTop, cardLeft + notificationWidth, cardTop + h, moonBgColor(element).rgb, 0F)
        moonFont(17).drawString(title, cardLeft + 7F, cardTop + 7F, Color.WHITE.rgb)
        moonFont(17).drawString(description, cardLeft + 7F, cardTop + 18F, Color(255, 255, 255, 120).rgb)
        val elapsed = (1F - stay / delay.toFloat()).coerceIn(0F, 1F)
        drawRoundedRect(
            cardLeft, cardTop + h - 1F, cardLeft + notificationWidth * elapsed, cardTop + h,
            ClientThemesUtils.getColor().rgb, 0F
        )
    }

    /**
     * MoonLight Exhi（1:1，原 "Exhi" case，高 26）：
     * 黑色半透明背景，类型色 1px 进度条自右向左收缩，18px 图标 (x+2, y+4.5)，
     * 标题 (x+21.5, y+4.5)、描述 (x+21.5, y+15.5) 白色。
     */
    private fun drawMoonExhi(element: Notifications, currentX: Float, notificationWidth: Float) {
        val h = moonMetrics(element.style)?.first?.toFloat() ?: 26F
        val cardLeft = -currentX - 5F
        val cardTop = -y - h

        moonBlurBehind(element, cardLeft, cardTop, notificationWidth, h, 0F)
        drawRoundedRect(cardLeft, cardTop, cardLeft + notificationWidth, cardTop + h, moonBgColor(element).rgb, 0F)
        val elapsed = (1F - stay / delay.toFloat()).coerceIn(0F, 1F)
        drawRoundedRect(
            cardLeft + notificationWidth * elapsed, cardTop + h - 1F, cardLeft + notificationWidth, cardTop + h,
            moonlightTypeColor.rgb, 0F
        )
        // 原版 RenderUtils.drawImage 为普通贴图绘制（无圆角）
        RenderUtils.drawImage(moonlightIcon, cardLeft + 2F, cardTop + 4.5F, 18, 18)
        moonFont(18).drawString(title, cardLeft + 21.5F, cardTop + 4.5F, Color.WHITE.rgb)
        moonFont(14).drawString(description, cardLeft + 21.5F, cardTop + 15.5F, Color.WHITE.rgb)
    }

    /**
     * MoonLight Type 2（1:1，原 "Type 2" case，高 30）：
     * 整卡以中心为锚按入场进度缩放（原 GlStateManager.scale(animation.getOutput)），
     * 圆角 4 背景，左侧白色圆（圆心 x+16, y+15, 半径 13）+ 主题色圆环，
     * 圆内为 MoonLight 图标（替代原 noti 字形），标题 (x+34, y+4)、描述 (x+34, y+17)。
     */
    private fun drawMoonType2(element: Notifications, currentX: Float, notificationWidth: Float) {
        val h = moonMetrics(element.style)?.first?.toFloat() ?: 30F
        val cardLeft = -currentX - 5F
        val cardTop = -y - h
        val progress = (currentX / notificationWidth).coerceIn(0.01F, 1F)

        // blur 在缩放矩阵外调用（模糊 AABB 为屏幕绝对坐标）
        moonBlurBehind(element, cardLeft, cardTop, notificationWidth, h, 4F)
        GlStateManager.pushMatrix()
        GlStateManager.translate(cardLeft + notificationWidth / 2F, cardTop + h / 2F, 0F)
        GlStateManager.scale(progress, progress, progress)
        GlStateManager.translate(-(cardLeft + notificationWidth / 2F), -(cardTop + h / 2F), 0F)

        drawRoundedRect(cardLeft, cardTop, cardLeft + notificationWidth, cardTop + h, moonBgColor(element).rgb, 4F)
        // 白色圆 + 主题色圆环（原 filled circle + gradient circle，此处以主题色替代渐变）
        drawRoundedRect(cardLeft + 3F, cardTop + 2F, cardLeft + 29F, cardTop + 28F, Color.WHITE.rgb, 13F)
        drawRoundedBorder(cardLeft + 3F, cardTop + 2F, cardLeft + 29F, cardTop + 28F, 1F, ClientThemesUtils.getColor().rgb, 13F)
        // 原版字形图标（黑色，noti/noti2 字体 42）：OKAY "M" / INFO "B" / NOTIFY "A" / WARNING "L"
        when (moonlightNotificationType) {
            Notification.MoonType.OKAY -> Fonts.fontMoonNoti2(42).drawString("M", cardLeft + 8F, cardTop + 10F, Color.BLACK.rgb)
            Notification.MoonType.INFO -> Fonts.fontMoonNoti(42).drawString("B", cardLeft + 11F, cardTop + 8F, Color.BLACK.rgb)
            Notification.MoonType.NOTIFY -> Fonts.fontMoonNoti(42).drawString("A", cardLeft + 14F, cardTop + 8F, Color.BLACK.rgb)
            Notification.MoonType.WARNING -> Fonts.fontMoonNoti2(42).drawString("L", cardLeft + 9F, cardTop + 10F, Color.BLACK.rgb)
        }
        moonFont(20).drawString(title, cardLeft + 34F, cardTop + 4F, Color.WHITE.rgb)
        moonFont(17).drawString(description, cardLeft + 34F, cardTop + 17F, Color.WHITE.rgb)

        GlStateManager.popMatrix()
    }

    /**
     * MoonLight Type 3（1:1，原 "Type 3" case，高 35）：
     * 圆角 4 背景，主题色 25x25 方块 (x+3, y+5) 圆角 4，内嵌 MoonLight 图标（替代原 darker 字形），
     * 带阴影标题 (x+32, y+6)、描述 (x+32, y+20)。
     */
    private fun drawMoonType3(element: Notifications, currentX: Float, notificationWidth: Float) {
        val h = moonMetrics(element.style)?.first?.toFloat() ?: 35F
        val cardLeft = -currentX - 5F
        val cardTop = -y - h

        moonBlurBehind(element, cardLeft, cardTop, notificationWidth, h, 4F)
        drawRoundedRect(cardLeft, cardTop, cardLeft + notificationWidth, cardTop + h, moonBgColor(element).rgb, 4F)
        drawRoundedRect(cardLeft + 3F, cardTop + 5F, cardLeft + 28F, cardTop + 30F, ClientThemesUtils.getColor().rgb, 4F)
        // 原版字形图标：noti2(50) "k"，颜色为主题色三次 darker
        val glyphColor = ClientThemesUtils.getColor().darker().darker().darker()
        Fonts.fontMoonNoti2(50).drawString("k", cardLeft + 9.5F, cardTop + 10F, glyphColor.rgb)
        moonFont(22).drawStringWithShadow(title, cardLeft + 32F, cardTop + 6F, Color.WHITE.rgb)
        moonFont(20).drawStringWithShadow(description, cardLeft + 32F, cardTop + 20F, Color.WHITE.rgb)
    }

    /**
     * MoonLight Type 4（1:1，原 "Type 4" case，高 27）：
     * 圆角 12 背景，底部 1px 主题色进度条 (x+3 起，宽 width * elapsed - 5)，
     * 标题 (x+6, y+4)，描述 (x+6, y + height - 标题字高)。
     */
    private fun drawMoonType4(element: Notifications, currentX: Float, notificationWidth: Float) {
        val h = moonMetrics(element.style)?.first?.toFloat() ?: 27F
        val cardLeft = -currentX - 5F
        val cardTop = -y - h

        moonBlurBehind(element, cardLeft, cardTop, notificationWidth, h, 12F)
        drawRoundedRect(cardLeft, cardTop, cardLeft + notificationWidth, cardTop + h, moonBgColor(element).rgb, 12F)
        val elapsed = (1F - stay / delay.toFloat()).coerceIn(0F, 1F)
        drawRoundedRect(
            cardLeft + 3F, cardTop + h - 3F, cardLeft + 3F + (notificationWidth * elapsed - 5F).coerceAtLeast(0F),
            cardTop + h - 2F, ClientThemesUtils.getColor().rgb, 0F
        )
        // 原版绘制字体为 interSemiBold(20)/interSemiBold(16)（width() 的 10/6 仅影响卡片宽度，为原版自身怪癖）
        val titleFont = moonFont(20)
        titleFont.drawString(title, cardLeft + 6F, cardTop + 4F, Color.WHITE.rgb)
        moonFont(16).drawString(description, cardLeft + 6F, cardTop + h - titleFont.visualLineHeight, Color.WHITE.rgb)
    }

    /**
     * MoonLight Type 5（1:1，原 "Type 5" case，高 24）：
     * 黑色半透明背景，16px 图标 (x+3.5, y+3)，白色标题 (x+25, y+4)、灰色描述 (x+25, y+12.5)，
     * 底部 1px 类型色进度条自右向左收缩。
     */
    private fun drawMoonType5(element: Notifications, currentX: Float, notificationWidth: Float) {
        val h = moonMetrics(element.style)?.first?.toFloat() ?: 24F
        val cardLeft = -currentX - 5F
        val cardTop = -y - h

        moonBlurBehind(element, cardLeft, cardTop, notificationWidth, h, 0F)
        drawRoundedRect(cardLeft, cardTop, cardLeft + notificationWidth, cardTop + h, moonBgColor(element).rgb, 0F)
        RenderUtils.drawImage(moonlightIcon, cardLeft + 3.5F, cardTop + 3F, 16, 16)
        // 原版 Type 5 文字用 Minecraft.getMinecraft().fontRendererObj 绘制
        Minecraft.getMinecraft().fontRendererObj.drawString(title, cardLeft + 25F, cardTop + 4F, Color.WHITE.rgb, false)
        Minecraft.getMinecraft().fontRendererObj.drawString(description, cardLeft + 25F, cardTop + 12.5F, Color(128, 128, 128).rgb, false)
        val elapsed = (1F - stay / delay.toFloat()).coerceIn(0F, 1F)
        drawRoundedRect(
            cardLeft + notificationWidth * elapsed, cardTop + h - 1F, cardLeft + notificationWidth, cardTop + h,
            moonlightTypeColor.rgb, 0F
        )
    }

    /**
     * MoonLight Naven（1:1，原 NavenNotification）：
     * 20px 高彩色胶囊卡（圆角 10），单行 siyuan(18) 白字带阴影，右缘滑入/滑出；
     * 卡片颜色按 severity 映射原版类型色（OKAY 绿 / INFO 深 / NOTIFY 暗红 / WARNING 红）。
     */
    private fun drawNaven(element: Notifications, currentX: Float, notificationWidth: Float) {
        val h = moonMetrics(element.style)?.first?.toFloat() ?: 20F
        // 原版胶囊会超出锚点 4px 导致右圆角被屏幕边缘截断，这里整体左移 4px 使圆角完整可见
        val cardLeft = -currentX
        val cardTop = -y - h

        drawRoundedRect(cardLeft, cardTop, cardLeft + notificationWidth, cardTop + h, navenLevelColor().rgb, 10F)
        moonFont(18).drawString(navenMessage(), cardLeft + 4F, cardTop + 3F, Color.WHITE.rgb, true)
    }

    /**
     * MoonLight Southside（1:1，原 SouthsideNotification）：
     * 设计宽 314 的深灰圆角卡（GRAY_800），底部 5px PRIMARY_800 进度条内嵌 PRIMARY_500
     * 已流逝填充，白色 0.8 透明度 32px 图标 + 标题 + 描述换行（行高 14.5），
     * 右缘滑入/滑出 330 设计像素。
     * 背板/进度条/图标（矢量与贴图几何）在 glScaled(unit) 设计空间内绘制；文字对齐
     * Type 5 的渲染管线 —— 原版 fontRendererObj 在 GUI 空间直绘（无缩放矩阵，
     * 与 Type 5 文字观感一致）。
     */
    private fun drawMoonSouthside(element: Notifications, currentX: Float, notificationWidth: Float) {
        val unit = moonSouthsideUnit()
        val fontRenderer = Minecraft.getMinecraft().fontRendererObj
        val descLines = moonWrap(fontRenderer, moonSouthsideMessage(), 220F * unit)
        val designH = 68F + 14.5F * descLines.size + 5F
        val cardH = designH * unit
        val cardBottom = -y
        val cardTop = cardBottom - cardH
        // 原版 slideIn：从右缘外 330 设计像素滑到最终位置（右缘留 16px 边距）
        val slide = 330F * unit * easeInOutQuad((currentX / notificationWidth).coerceIn(0F, 1F))
        val cardLeft = -slide

        GlStateManager.pushMatrix()
        GlStateManager.translate(cardLeft, cardTop, 0F)
        GlStateManager.scale(unit, unit, unit)

        // 背板（GRAY_800）
        drawRoundedRect(0F, 0F, 314F, designH, Color(21, 22, 23).rgb, 8F)

        // 底部进度条：PRIMARY_800 底 + PRIMARY_500 已流逝填充（随停留时间增长）
        val elapsed = (1F - stay / delay.toFloat()).coerceIn(0F, 1F)
        drawRoundedRect(0F, designH - 5F, 314F, designH, Color(13, 51, 128).rgb, 8F)
        drawRoundedRect(0F, designH - 5F, 314F * elapsed, designH, Color(25, 103, 255).rgb, 8F)

        // 左侧图标（白色 0.8 透明度，32 设计像素）：原版在 glScaled(unit) 内按
        // iconTop = cardTop + (cardH - 5)/2 - 16 定位（垂直居中于进度条上方区域），
        // 图标为原版专用 southside/{enabled,disabled,info,warning,error}.png（SouthsideNotification#icon 1:1）
        RenderUtils.drawImage(
            southsideIcon, 16F, (designH - 5F - 32F) / 2F,
            32, 32, Color(255, 255, 255, 204)
        )

        GlStateManager.popMatrix()

        // 标题/描述：原版字体直绘（对齐 Type 5 管线），白色 0.8 / 0.6 透明度
        val textX = cardLeft + 64F * unit
        fontRenderer.drawString(
            if (isToggleNotification) "Module" else stripColor(title),
            textX, cardTop + (30F - fontRenderer.FONT_HEIGHT / 2F) * unit,
            Color(255, 255, 255, 204).rgb, false
        )

        // 描述换行（白色 0.6 透明度，行高 14.5 设计像素）
        var lineY = cardTop + (46F - fontRenderer.FONT_HEIGHT / 2F) * unit
        for (line in descLines) {
            fontRenderer.drawString(line, textX, lineY, Color(255, 255, 255, 153).rgb, false)
            lineY += 14.5F * unit
        }
    }

    /**
     * MoonLight NotificationType 颜色映射（OKAY/INFO/NOTIFY/WARNING → severity）。
     */
    private val moonlightTypeColor: Color
        get() = when (severityType) {
            Notifications.SeverityType.SUCCESS -> Color(65, 252, 65)
            Notifications.SeverityType.RED_SUCCESS -> Color(226, 87, 76)
            Notifications.SeverityType.INFO -> Color(127, 174, 210)
            Notifications.SeverityType.WARNING -> Color(255, 255, 94)
            Notifications.SeverityType.ERROR -> Color(226, 87, 76)
        }
}

