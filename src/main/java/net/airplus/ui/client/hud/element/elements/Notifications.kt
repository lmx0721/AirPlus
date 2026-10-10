/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
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
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.render.RenderUtils.deltaTime
import net.airplus.utils.render.RenderUtils.drawRoundedBorder
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.minecraft.client.gui.FontRenderer
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.WorldRenderer
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.util.ResourceLocation
import java.awt.Color
import kotlin.math.sin

/**
 * CustomHUD Notification element
 */
@ElementInfo(name = "Notifications", single = true, priority = -1)
class Notifications(
    x: Double = 0.0, y: Double = 30.0, scale: Float = 1F, side: Side = Side(Side.Horizontal.RIGHT, Side.Vertical.DOWN)
) : Element("Notifications", x, y, scale, side) {

    val style by choices("Style", arrayOf("Classic", "Modern", "Compact", "Hanabi", "Flux", "Onyx"), "Modern")
    val horizontalFade by choices("HorizontalFade", arrayOf("InOnly", "OutOnly", "Both", "None"), "OutOnly")
    val padding by int("Padding", 5, 1..20)
    val roundRadius by float("RoundRadius", 3f, 0f..10f)
    val color by color("BackgroundColor", Color.BLACK.withAlpha(128))
    val renderBorder by boolean("RenderBorder", false)
    val borderColor by color("BorderColor", Color.BLUE.withAlpha(255)) { renderBorder }
    val borderWidth by float("BorderWidth", 2f, 0.5F..5F) { renderBorder }

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

            notification.drawNotification(this).also { if (!it) verticalOffset += Notification.MAX_HEIGHT + padding }
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
        } else {
            // 宽度按所选标题字体实时计算
            textLength = element.titleFontRenderer.getStringWidth(longestString(element.titleFontRenderer))
            maxTextLength + ICON_SIZE + 16F
        }
        val extraSpace = 4F

        val currentX = when (fadeState) {
            // Onyx 自带滑入动画（progress 由 x 映射），不受默认 HorizontalFade=OutOnly 影响
            FadeState.IN -> if (element.horizontalFade in arrayOf("InOnly", "Both") || onyx) x else notificationWidth
            FadeState.OUT -> if (element.horizontalFade in arrayOf("OutOnly", "Both")) x else notificationWidth
            else -> x
        }

        when {
            hanabi -> drawHanabi(element, currentX, notificationWidth)
            element.style == "Modern" -> drawModern(element, currentX, extraSpace)
            element.style == "Compact" -> drawCompact(element, currentX, extraSpace)
            element.style == "Flux" -> drawFlux(element, currentX, extraSpace)
            element.style == "Onyx" -> drawOnyx(element, currentX, notificationWidth)
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
                    x = if (hanabi || flux || onyx) notificationWidth else maxTextLength + ICON_SIZE + 16F
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
}