/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.ui.client.mainmenu

import net.airplus.AirPlus.clientVersionText
import net.airplus.file.configs.models.ClientConfiguration
import net.airplus.ui.client.altmanager.GuiAltManager
import net.airplus.ui.font.Fonts
import net.airplus.utils.render.HudBlur
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.ui.AbstractScreen
import net.minecraft.client.gui.GuiLanguage
import net.minecraft.client.gui.GuiMultiplayer
import net.minecraft.client.gui.GuiOptions
import net.minecraft.client.gui.GuiSelectWorld
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraftforge.fml.client.GuiModList
import java.awt.Color
import kotlin.math.abs

/**
 * Hanabi 面板式主菜单（迁移自 Hanabi GuiCustomMainMenu，风格名 Hanabi2）。
 * 鼠标视差背景图 + 粒子 + 中央模糊面板（上半深色信息区 + 下半按钮条 G-M 图标按钮）。
 * 原 Hanabi logo 图标字形（icon 字体 \ue904）本客户端没有对应字体，用蓝色 "Hanabi2" 文字替代。
 */
class Hanabi2MainMenu : AbstractScreen() {

    private var particleEngine: ParticleEngine? = null

    private var currentX = 0f
    private var currentY = 0f

    private class MenuButton(val icon: String, val text: String, val action: () -> Unit) {
        var x = 0f
        var y = 0f
        var yAnimation = 0f
    }

    // 图标字形 G-M（Hanabi SessIcon 字体），与原版一一对应
    private val buttons = listOf(
        MenuButton("G", "SinglePlayer") { mc.displayGuiScreen(GuiSelectWorld(this)) },
        MenuButton("H", "MultiPlayer") { mc.displayGuiScreen(GuiMultiplayer(this)) },
        MenuButton("I", "AltManager") { mc.displayGuiScreen(GuiAltManager(this)) },
        MenuButton("J", "Mods") { mc.displayGuiScreen(GuiModList(this)) },
        MenuButton("K", "Options") { mc.displayGuiScreen(GuiOptions(this, mc.gameSettings)) },
        MenuButton("L", "Languages") { mc.displayGuiScreen(GuiLanguage(this, mc.gameSettings, mc.languageManager)) },
        MenuButton("M", "Quit") { mc.shutdown() }
    )

    override fun initGui() {
        if (particleEngine == null) particleEngine = ParticleEngine()
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        val sr = ScaledResolution(mc)
        width = sr.scaledWidth
        height = sr.scaledHeight

        // 不先画一个透明渐变就会白屏（同 Flux 主菜单）
        drawGradientRect(0, 0, width, height, 0x00FFFFFF, 0x00FFFFFF)

        // 鼠标视差背景（原 Hanabi：背景图放大 60 并随鼠标微移；动态背景不支持视差，直接统一绘制）
        val xDiff = (mouseX - width / 2f - currentX) / sr.scaleFactor
        val yDiff = (mouseY - height / 2f - currentY) / sr.scaleFactor
        currentX += xDiff * 0.3f
        currentY += yDiff * 0.3f
        val bgResource = MainMenuStyles.backgroundResource(ClientConfiguration.customMenuBackgroundImageIndex)
        if (bgResource != null) {
            GlStateManager.translate(currentX / 30f, currentY / 15f, 0f)
            RenderUtils.drawImage(bgResource, -30, -30, width + 60, height + 60)
            GlStateManager.translate(-currentX / 30f, -currentY / 15f, 0f)
        } else {
            MainMenuStyles.drawMenuBackground(width, height, 0)
        }

        particleEngine?.render()

        // 中央面板区域模糊 + 深色面板（上半信息区 + 下半按钮条）
        val panelX = width / 2f - 50f * (buttons.size / 2f)
        val panelX2 = width / 2f + 50f * (buttons.size / 2f)
        HudBlur.blur(panelX, height / 2f - 50f, panelX2, height / 2f + 50f)
        RenderUtils.drawRect(panelX, height / 2f - 50f, panelX2, height / 2f + 50f, 0x7D000000)
        RenderUtils.drawRect(panelX, height / 2f + 20f, panelX2, height / 2f + 50f, 0x3E000000)

        // 按钮条（50×30 一字排开，悬停底部蓝色渐变亮条）
        var startX = panelX
        for (b in buttons) {
            drawButton(b, startX, height / 2f + 20f, mouseX, mouseY)
            startX += 50f
        }

        // 面板信息区：蓝色 logo + 名称/版本 + 右侧登录玩家名
        Fonts.fontUsans50.drawString("AirClient", panelX + 10f, height / 2f - 36f, LOGO_COLOR)
        val brandX = panelX + 10f + Fonts.fontUsans50.getStringWidth("AirClient") + 8f
        Fonts.fontUsans50.drawString("client", brandX, height / 2f - 38f, Color.WHITE.rgb)
        Fonts.fontUsans40.drawString("Build $clientVersionText", panelX + 80f, height / 2f - 14f, Color.WHITE.rgb)
        val s = "Logged in as " + (mc.session?.username ?: "Player")
        Fonts.fontUsans40.drawString(s, panelX2 - Fonts.fontUsans40.getStringWidth(s) - 10f, height / 2f - 5f, Color.WHITE.rgb)

        MainMenuStyles.drawSettingsButton(width, height, mouseX, mouseY)

        super.drawScreen(mouseX, mouseY, partialTicks)
    }

    private fun drawButton(b: MenuButton, x: Float, y: Float, mouseX: Int, mouseY: Int) {
        b.x = x
        b.y = y

        val iconFont = Fonts.fontSessIcon60
        iconFont.drawString(b.icon, x + 25f - iconFont.getStringWidth(b.icon) / 2f - 2f, y + 15f, Color.WHITE.rgb)

        b.yAnimation = smoothAnimation(b.yAnimation, if (isHovering(mouseX, mouseY, x, y, x + 50f, y + 30f)) 2f else 0f, 50f, 0.3f)
        RenderUtils.drawGradientRect(x, y + 30f - b.yAnimation * 3f, x + 50f, y + 30f, 0x0034B2FF, 0x7834B2FF, 0f)
        RenderUtils.drawRect(x, y + 30f - b.yAnimation, x + 50f, y + 30f, 0xff34b2ff.toInt())
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        if (MainMenuStyles.handleSettingsClick(width, height, mouseX, mouseY, mouseButton)) {
            return
        }

        if (mouseButton == 0) {
            for (b in buttons) {
                if (isHovering(mouseX, mouseY, b.x, b.y, b.x + 50f, b.y + 30f)) {
                    b.action.invoke()
                    return
                }
            }
        }

        super.mouseClicked(mouseX, mouseY, mouseButton)
    }

    private fun isHovering(mouseX: Int, mouseY: Int, x: Float, y: Float, x2: Float, y2: Float): Boolean =
        mouseX >= x && mouseX < x2 && mouseY >= y && mouseY < y2

    // ---------- Hanabi 动画函数（内联移植） ----------

    /** Hanabi RenderUtil.getAnimationState：delta-time 步进动画。 */
    private fun getAnimationState(animation: Float, finalState: Float, speed: Float): Float {
        val add = RenderUtils.deltaTime * (speed / 1000f)
        var ani = animation
        if (ani < finalState) {
            ani = if (ani + add < finalState) ani + add else finalState
        } else if (ani - add > finalState) {
            ani -= add
        } else {
            ani = finalState
        }
        return ani
    }

    /** Hanabi RenderUtil.smoothAnimation。 */
    private fun smoothAnimation(ani: Float, finalState: Float, speed: Float, scale: Float): Float =
        getAnimationState(ani, finalState, maxOf(10f, abs(ani - finalState) * speed) * scale)

    companion object {
        // 原 Hanabi logo 图标颜色 0xff2f64fd
        private const val LOGO_COLOR = 0xff2f64fd.toInt()
    }
}
