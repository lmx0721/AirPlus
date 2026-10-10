package net.airplus.ui.client.hud.element.elements.targets2.impl

import net.airplus.config.BoolValue
import net.airplus.config.FloatValue
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.elements.Target2
import net.airplus.ui.client.hud.element.elements.targets2.TargetStyle
import net.airplus.ui.font.Fonts
import net.airplus.utils.render.ColorUtils
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.render.RoundedUtil
import net.airplus.utils.render.Stencil
import net.minecraft.entity.player.EntityPlayer
import org.lwjgl.opengl.GL11
import java.awt.Color

/**
 * Onyx 风格 TargetHUD（重实现自 Onyx 客户端的圆角岛卡布局）：
 * 深色圆角卡片 + 左侧圆角头像 + 名字/距离 + 血量/状态词 + 分层血条（主填充 / 吸收 / 伤害残影）。
 */
class Onyx(inst: Target2) : TargetStyle("Onyx", inst, true) {

    private companion object {
        const val CARD_RADIUS = 12F
        const val AVATAR_RADIUS = 4F
        const val SIDE_PAD = 11F
        const val TOP_PAD = 8F
        const val GAP_AVATAR_TEXT = 9F
        const val ROW_SPACING = 12F
        const val ROWS_HEIGHT = 21F
        const val BAR_GAP_ABOVE = 6F
        const val RIGHT_PAD = 8F
        const val SEGMENT_GAP = 2F
        const val MIN_CONTENT_WIDTH = 96F

        val TRACK_GRAY = Color(56, 56, 60)
        val AVATAR_BACKING = Color(38, 38, 42)
        val DISTANCE_GRAY = Color(165, 165, 170)
        val STATUS_GRAY = Color(165, 165, 170)
        val LOSE_RED = Color(255, 82, 82)
        val GHOST_COLOR = ColorUtils.reAlpha(Color(255, 92, 92), 0.55F)
        val ABSORPTION_COLOR = ColorUtils.reAlpha(Color(255, 213, 79), 0.7F)
    }

    // 设置（基类通过反射收集，仅当前样式为 Onyx 时显示）
    private val barHeightValue = FloatValue("BarHeight", 4F, 2F..8F).apply {
        setSupport { targetInstance.styleValueName.equals("onyx", ignoreCase = true) }
    }
    private val showDistanceValue = BoolValue("ShowDistance", true).apply {
        setSupport { targetInstance.styleValueName.equals("onyx", ignoreCase = true) }
    }
    private val showStatusValue = BoolValue("ShowStatus", true).apply {
        setSupport { targetInstance.styleValueName.equals("onyx", ignoreCase = true) }
    }
    private val ghostBarValue = BoolValue("GhostBar", true).apply {
        setSupport { targetInstance.styleValueName.equals("onyx", ignoreCase = true) }
    }
    private val avatarSizeValue = FloatValue("AvatarSize", 26F, 16F..40F).apply {
        setSupport { targetInstance.styleValueName.equals("onyx", ignoreCase = true) }
    }

    // 伤害残影：比主条更慢的追踪值 + 掉血后的延迟计时
    private var ghostHealth = 0F
    private var ghostDelay = 0F

    private data class Layout(
        val width: Float,
        val height: Float,
        val contentWidth: Float,
        val avatarSize: Float,
        val avatarX: Float,
        val avatarY: Float,
        val textX: Float,
        val row1Y: Float,
        val row2Y: Float,
        val barY: Float,
        val barH: Float
    )

    private fun distanceText(entity: EntityPlayer?): String =
        decimalFormat3.format(entity?.let { mc.thePlayer?.getDistanceToEntity(it) } ?: 0F) + " m"

    private fun healthDiff(entity: EntityPlayer): Float {
        val self = mc.thePlayer ?: return 0F
        return self.health + self.absorptionAmount - (entity.health + entity.absorptionAmount)
    }

    private fun statusText(entity: EntityPlayer?): String {
        val diff = entity?.let { healthDiff(it) } ?: 0F
        return when {
            diff > 1F -> "Winning"
            diff < -1F -> "Losing"
            else -> "Even"
        }
    }

    // barColor 已由 Target2 随淡出处理透明度，直接使用；其余颜色过 getColor 随淡出变透明
    private fun statusColorRgb(entity: EntityPlayer?): Int {
        val diff = entity?.let { healthDiff(it) } ?: 0F
        return when {
            diff > 1F -> targetInstance.barColor.rgb
            diff < -1F -> getColor(LOSE_RED).rgb
            else -> getColor(STATUS_GRAY).rgb
        }
    }

    private fun updateGhost(entity: EntityPlayer) {
        val health = entity.health.coerceIn(0F, entity.maxHealth)
        val dt = RenderUtils.deltaTime.coerceAtMost(100).toFloat()
        if (health >= ghostHealth) {
            // 回血：残影立即快速追上
            ghostDelay = 0F
            ghostHealth += (health - ghostHealth) * (dt / 200F)
        } else {
            // 掉血：延迟 ~400ms 后残影才开始缓慢追踪（Onyx 同款节奏）
            ghostDelay += dt
            if (ghostDelay >= 400F)
                ghostHealth += (health - ghostHealth) * (dt / 350F)
        }
    }

    private fun layout(entity: EntityPlayer?): Layout {
        val avatarSize = avatarSizeValue.get()
        val name = entity?.name ?: ""

        var row1W = Fonts.fontSemibold40.getStringWidth(name).toFloat()
        if (showDistanceValue.get())
            row1W += 4F + Fonts.fontSemibold35.getStringWidth(distanceText(entity))

        val healthText = decimalFormat3.format(entity?.health ?: 20F)
        var row2W = Fonts.fontSemibold40.getStringWidth(healthText).toFloat()
        if (showStatusValue.get())
            row2W += 5F + Fonts.fontSemibold35.getStringWidth(statusText(entity))

        val textX = SIDE_PAD + avatarSize + GAP_AVATAR_TEXT
        val contentWidth = (maxOf(row1W, row2W) + textX + RIGHT_PAD - SIDE_PAD * 2F).coerceAtLeast(MIN_CONTENT_WIDTH)
        val width = contentWidth + SIDE_PAD * 2F

        val contentHeight = avatarSize.coerceAtLeast(ROWS_HEIGHT)
        val barH = barHeightValue.get()
        val barY = TOP_PAD + contentHeight + BAR_GAP_ABOVE
        val height = barY + barH + TOP_PAD

        return Layout(
            width, height, contentWidth,
            avatarSize,
            SIDE_PAD, TOP_PAD + (contentHeight - avatarSize) / 2F,
            textX,
            TOP_PAD + (contentHeight - ROWS_HEIGHT) / 2F,
            TOP_PAD + (contentHeight - ROWS_HEIGHT) / 2F + ROW_SPACING,
            barY, barH
        )
    }

    override fun drawTarget(entity: EntityPlayer) {
        updateAnim(entity.health)
        updateGhost(entity)

        val l = layout(entity)
        val fade = 1F - targetInstance.getFadeProgress()
        val maxHp = entity.maxHealth.coerceAtLeast(1F)
        val name = entity.name

        // 卡片本体（深色岛卡，背景跟随 HUD 背景色设置）
        RoundedUtil.drawRound(0F, 0F, l.width, l.height, CARD_RADIUS, targetInstance.bgColor)

        // 左侧圆角头像（皮肤脸部 8,8 区域，Stencil 裁剪圆角）
        RoundedUtil.drawRound(l.avatarX, l.avatarY, l.avatarSize, l.avatarSize, AVATAR_RADIUS, getColor(AVATAR_BACKING))
        val playerInfo = mc.netHandler.getPlayerInfo(entity.uniqueID)
        if (playerInfo != null) {
            try {
                Stencil.write(false)
                GL11.glDisable(GL11.GL_TEXTURE_2D)
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
                RenderUtils.fastRoundedRect(l.avatarX, l.avatarY, l.avatarX + l.avatarSize, l.avatarY + l.avatarSize, AVATAR_RADIUS)
                GL11.glDisable(GL11.GL_BLEND)
                GL11.glEnable(GL11.GL_TEXTURE_2D)
                Stencil.erase(true)
                drawHead(playerInfo.locationSkin, l.avatarX.toInt(), l.avatarY.toInt(), l.avatarSize.toInt(), l.avatarSize.toInt(), fade)
                Stencil.dispose()
            } catch (e: Exception) {
                Stencil.dispose()
            }
        }

        // 第一行：玩家名 + 距离
        Fonts.fontSemibold40.drawString(name, l.textX, l.row1Y, getColor(Color.WHITE).rgb)
        if (showDistanceValue.get())
            Fonts.fontSemibold35.drawString(
                distanceText(entity),
                l.textX + Fonts.fontSemibold40.getStringWidth(name) + 4F,
                l.row1Y + 2F,
                getColor(DISTANCE_GRAY).rgb
            )

        // 第二行：血量数值 + 状态词
        val healthText = decimalFormat3.format(entity.health)
        Fonts.fontSemibold40.drawString(healthText, l.textX, l.row2Y, targetInstance.barColor.rgb)
        if (showStatusValue.get())
            Fonts.fontSemibold35.drawString(
                statusText(entity),
                l.textX + Fonts.fontSemibold40.getStringWidth(healthText) + 5F,
                l.row2Y + 2F,
                statusColorRgb(entity)
            )

        // 底部血条：底槽 → 伤害残影（下层）→ 主填充 → 吸收段
        val barW = l.contentWidth
        val barRadius = l.barH / 2F
        RoundedUtil.drawRound(SIDE_PAD, l.barY, barW, l.barH, barRadius, getColor(TRACK_GRAY))

        if (ghostBarValue.get()) {
            val ghostW = (ghostHealth.coerceIn(0F, maxHp) / maxHp) * barW
            if (ghostW > 0.5F)
                RoundedUtil.drawRound(SIDE_PAD, l.barY, ghostW, l.barH, barRadius, getColor(GHOST_COLOR))
        }

        val mainW = (easingHealth.coerceIn(0F, maxHp) / maxHp) * barW
        if (mainW > 0.5F)
            RoundedUtil.drawRound(SIDE_PAD, l.barY, mainW, l.barH, barRadius, targetInstance.barColor)

        // 吸收段：从简处理，固定淡黄色（Onyx tertiary 近似）追加在主条右侧 2px 间隙后
        val absorption = entity.absorptionAmount.coerceAtLeast(0F)
        if (absorption > 0F) {
            val absW = (absorption / maxHp * barW).coerceAtMost(barW - mainW - SEGMENT_GAP)
            if (absW > 0.5F)
                RoundedUtil.drawRound(SIDE_PAD + mainW + SEGMENT_GAP, l.barY, absW, l.barH, barRadius, getColor(ABSORPTION_COLOR))
        }
    }

    override fun handleBlur(entity: EntityPlayer) {
        val l = layout(entity)
        RoundedUtil.drawRound(0F, 0F, l.width, l.height, CARD_RADIUS, Color(255, 255, 255, 255))
    }

    override fun handleShadowCut(entity: EntityPlayer) = handleBlur(entity)

    override fun handleShadow(entity: EntityPlayer) {
        val l = layout(entity)
        RoundedUtil.drawRound(0F, 0F, l.width, l.height, CARD_RADIUS, shadowOpaque)
    }

    override fun getBorder(entity: EntityPlayer?): Border {
        val l = layout(entity)
        return Border(0F, 0F, l.width, l.height)
    }
}
