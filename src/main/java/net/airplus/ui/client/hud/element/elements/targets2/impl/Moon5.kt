package net.airplus.ui.client.hud.element.elements.targets2.impl

import net.airplus.config.BoolValue
import net.airplus.config.ListValue
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.elements.Target2
import net.airplus.ui.client.hud.element.elements.targets2.TargetStyle
import net.airplus.ui.font.Fonts
import net.airplus.utils.render.RenderUtils
import net.minecraft.client.gui.Gui
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.GL11.*
import java.awt.Color
import kotlin.math.max

/**
 * 从 moon-rebase TargetHudModule 迁移（nightly jS/ga/gb：Moon 布局、圆角皮肤、血量缓动与遮罩）。
 * Design：
 * - Moon = 紧凑 40px 设计（左 32x32 圆角头像 + 名字 + "X.X HP" + 底部血条）；
 * - Basic = 125x50 描边设计（"cur/max" 居中血量 + 延迟/距离信息行）。
 * 颜色沿用 Target2 全局：背景 = Background，血条 = Color（Theme 模式即原 SyncWithModuleList，
 * Custom 即原 moonColor）；Moon5-Glow 即原 ColorfulBackground 光晕。
 */
class Moon5(inst: Target2) : TargetStyle("Moon5", inst, true) {

    private val design = ListValue("Moon5-Design", arrayOf("Moon", "Basic"), "Moon")
    private val glow = BoolValue("Moon5-Glow", true)

    private val isBasic: Boolean
        get() = design.get().equals("Basic", ignoreCase = true)

    // ---------------- 原 gb 几何常量 ----------------
    private fun width(entity: EntityPlayer?): Float {
        if (isBasic) return 125F
        // Nightly hz：紧凑 40px HUD，名字宽度自适应（下限 110）
        return max(110F, Fonts.fontSF40.getStringWidth(entity?.name ?: "") + 45.5F)
    }

    private fun height() = if (isBasic) 50F else 40F

    private fun skin(entity: EntityPlayer): ResourceLocation =
        mc.netHandler.getPlayerInfo(entity.uniqueID)?.locationSkin
            ?: DefaultPlayerSkin.getDefaultSkin(entity.uniqueID)

    /** 圆角头像：基础脸 (8,8) + 帽子层 (40,8)，对应原 RoundedTextureQuad 0.125-0.25 / 0.625-0.25。 */
    private fun drawHeadWithHat(entity: EntityPlayer, x: Int, y: Int, size: Int) {
        val skinLocation = skin(entity)
        drawHead(skinLocation, x, y, size, size)

        glDisable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glDepthMask(false)
        OpenGlHelper.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO)
        glColor4f(1F, 1F, 1F, 1F)
        mc.textureManager.bindTexture(skinLocation)
        Gui.drawScaledCustomSizeModalRect(x, y, 40F, 8F, 8, 8, size, size, 64F, 64F)
        glDepthMask(true)
        glDisable(GL_BLEND)
        glEnable(GL_DEPTH_TEST)
    }

    override fun drawTarget(entity: EntityPlayer) {
        updateAnim(entity.health)

        val w = width(entity)
        val h = height()
        val accent = targetInstance.barColor.rgb
        val fraction = (easingHealth / entity.maxHealth).coerceIn(0F, 1F)

        if (isBasic) {
            // Nightly hy：黑色面板 + 四周 1px 强调色描边
            RenderUtils.quickDrawRect(0F, 0F, w, h, 0xff000000.toInt())
            RenderUtils.quickDrawRect(0F, 0F, w, 1F, accent)
            RenderUtils.quickDrawRect(0F, h - 1F, w, h, accent)
            RenderUtils.quickDrawRect(0F, 0F, 1F, h, accent)
            RenderUtils.quickDrawRect(w - 1F, 0F, w, h, accent)

            // 血条轨道 + 填充
            RenderUtils.quickDrawRect(30F, 17F, w - 4F, 28F, 0x80000000.toInt())
            val barWidth = (w - 34F) * fraction
            if (barWidth > 0F) RenderUtils.quickDrawRect(30F, 17F, 30F + barWidth, 28F, accent)

            // 文本：名字 / "cur/max" 居中 / 延迟+距离信息行（0xffc0c0c0）
            val f = Fonts.fontSF35
            Fonts.fontSF40.drawString(entity.name, 30F, 6F, getColor(-1).rgb)
            val hp = Math.round(entity.health).toString() + "/" + Math.round(entity.maxHealth)
            f.drawString(hp, 30F + (w - 34F) / 2F - f.getStringWidth(hp) / 2F, 18F, getColor(-1).rgb)
            val ping = mc.netHandler?.getPlayerInfo(entity.uniqueID)?.responseTime ?: 0
            val dist = mc.thePlayer?.getDistanceToEntity(entity) ?: 0F
            f.drawString("${ping}ms  " + decimalFormat2.format(dist) + "m", 30F, 31F, getColor(0xffc0c0c0.toInt()).rgb)

            drawHeadWithHat(entity, 4, 4, 32)
        } else {
            // Colorful 光晕（原共享 Effects pass 的近似）：accent 低透明外扩，画在背景之下
            if (glow.get()) {
                RenderUtils.drawRoundedRect(
                    -1F, -1F, w + 1F, h + 1F,
                    Color(targetInstance.barColor.red, targetInstance.barColor.green, targetInstance.barColor.blue, 40).rgb,
                    9F
                )
            }

            // 背景（圆角 8，透明度由 Target2 Background 控制）
            RenderUtils.drawRoundedRect(0F, 0F, w, h, targetInstance.bgColor.rgb, 8F)

            // 血条轨道 + 填充（r3）
            RenderUtils.drawRoundedRect(39F, 26F, w - 5F, 34F, Color(0, 0, 0, 128).rgb, 3F)
            val barWidth = (w - 44F) * fraction
            if (barWidth > 0F) RenderUtils.drawRoundedRect(39F, 26F, 39F + barWidth, 34F, accent, 3F)

            // 头像 + 文本：名字 / "X.X HP"（kU 的 Math.round(*10)/10 格式）
            drawHeadWithHat(entity, 4, 4, 32)
            Fonts.fontSF40.drawString(entity.name, 40F, 7F, getColor(-1).rgb)
            Fonts.fontSF35.drawString(decimalFormat2.format(entity.health) + " HP", 40F, 6F + Fonts.fontSF40.fontHeight, getColor(-1).rgb)
        }
    }

    override fun handleBlur(entity: EntityPlayer) {
        if (isBasic) RenderUtils.quickDrawRect(0F, 0F, 125F, 50F)
        else RenderUtils.quickDrawRect(0F, 0F, width(entity), 40F)
    }

    override fun handleShadowCut(entity: EntityPlayer) = handleBlur(entity)

    override fun handleShadow(entity: EntityPlayer) {
        if (isBasic) RenderUtils.quickDrawRect(0F, 0F, 125F, 50F, shadowOpaque.rgb)
        else RenderUtils.quickDrawRect(0F, 0F, width(entity), 40F, shadowOpaque.rgb)
    }

    override fun getBorder(entity: EntityPlayer?): Border =
        Border(0F, 0F, width(entity), height())
}
