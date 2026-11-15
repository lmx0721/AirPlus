/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.ui.client.mainmenu

import net.airplus.AirPlus
import net.airplus.ui.client.altmanager.GuiAltManager
import net.airplus.ui.font.Fonts
import net.airplus.utils.client.ClientThemesUtils
import net.airplus.utils.client.asResourceLocation
import net.airplus.utils.client.playSound
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.ui.AbstractScreen
import net.minecraft.client.gui.GuiMultiplayer
import net.minecraft.client.gui.GuiOptions
import net.minecraft.client.gui.GuiScreen
import net.minecraft.client.gui.GuiSelectWorld
import net.minecraft.client.renderer.GlStateManager
import org.lwjgl.input.Keyboard
import java.awt.Color
import java.io.IOException

/**
 * EdMoonlight 风格主菜单（第 14 种，忠实移植自 moonlight 的 wtf.moonlight.gui.main.MainMenu）。
 *
 * 布局：极坐标轨道弧排布 5 项（ARC_SPAN 84°、REST_DIR 147°、REST_PHASE 120°、HOVER_PHASE 48°、
 * EXIT_PHASE 152°），月亮大圆随 hover 方向受光旋转，菜单项为小月亮圆点 + 错峰展开动画；
 * 背景为 MenuSky 流星夜空 + 轨道弧 drawOrbitArc，footer 为客户端名（双色拆分）+ 用户名。
 *
 * 依赖替换（SplashScreen 全部去除）：
 * - SplashScreen.intro() 视为 1.0（不做启动月亮动画；启动淡入由 MainMenuStyles.drawStartupFade 已有逻辑负责）
 * - SplashScreen.textOffset(size) → -font.height / 2（AirPlus 字体 y 取文本顶部，负半行高垂直居中于圆点）
 * - SplashScreen.drawHandoffName → 直接画 footer 文本（客户端名取 AirPlus.CLIENT_NAME）
 * - Interface.color(0) → ClientThemesUtils.getColor(0)（AirPlus 主题渐变强调色，即 moonlight 主题色的对应来源）
 * - 字体映射：moonlight Fonts.interMedium/interSemiBold.get(size) → Fonts.fontMoonlight(size)
 *   （MoonLight 字体管线 1:1 还原，usans.otf 按需烘焙；interMedium/interSemiBold 的粗细差异舍弃）
 * - MenuPrewarm（字体预热）裁剪；JoinView 入服交接动画裁剪，改为 activate() 播放 gui.button.press
 *   音效后画面整体淡出到黑色（约 500ms），淡出完成后才 displayGuiScreen 目标界面。
 */
class MoonlightMainMenu : AbstractScreen() {

    private val hover = FloatArray(LABELS.size)
    private var lightDir = REST_DIR
    private var lightPhase = REST_PHASE
    private var lastFrame = 0L
    private var keySelected = -1
    private var lastMouseX = Int.MIN_VALUE
    private var lastMouseY = Int.MIN_VALUE
    private var hovered = -1
    private val itemX = FloatArray(LABELS.size)
    private val itemY = FloatArray(LABELS.size)
    private val itemW = FloatArray(LABELS.size)
    private var itemH = 0f
    private val reveal = FloatArray(LABELS.size)
    private val dotReveal = FloatArray(LABELS.size)
    private var unfold = 1f
    private var returnSweepStart = -1L

    // ===== 激活淡出过渡：播放音效后整体渐黑（约 500ms），完成后切换目标界面 =====
    private var fadeStart = -1L
    private var pendingScreen: GuiScreen? = null
    private var pendingExit = false

    override fun initGui() {
        lastFrame = 0L
    }

    override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        MenuSky.syncView()

        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else minOf(0.1f, (now - lastFrame) / 1.0e9f)
        lastFrame = now

        if (mouseX != lastMouseX || mouseY != lastMouseY) {
            lastMouseX = mouseX
            lastMouseY = mouseY
            keySelected = -1
        }

        // moonlight Fonts.interMedium.get(17) 的 1:1 字体映射
        val font = Fonts.fontMoonlight(17)
        val moonR = maxOf(36f, minOf(64f, height * 0.19f))
        val orbitR = moonR + 34f
        val padX = 12f
        var maxLabelW = 0f
        for (label in LABELS) maxLabelW = maxOf(maxLabelW, font.getStringWidth(label).toFloat())
        val rowWidth = orbitR + padX + maxLabelW
        val orbitX = width / 2f - (rowWidth - moonR) / 2f
        val orbitY = height / 2f
        itemH = maxOf(18f, orbitR * Math.toRadians((ARC_SPAN / (LABELS.size - 1)).toDouble()).toFloat())

        // SplashScreen.intro() 视为 1.0：clamp01((1.0f - 0.25f) / 0.75f) == 1
        var intro = 1f

        // 返回扫入门控：从子界面回到主菜单时，先等 MenuSky 交接过半且月亮离开轨道热区，再让轨道项扫入
        val returnProgress = MenuSky.returnProgress()
        if (returnProgress < 0.5f) {
            returnSweepStart = 0L
        }
        if (returnSweepStart == 0L && returnProgress >= RETURN_GATE) {
            val xs = FloatArray(LABELS.size)
            val ys = FloatArray(LABELS.size)
            val ends = FloatArray(LABELS.size)
            for (i in LABELS.indices) {
                val a = Math.toRadians(angle(i).toDouble())
                xs[i] = orbitX + Math.cos(a).toFloat() * orbitR - 4f
                ys[i] = orbitY + Math.sin(a).toFloat() * orbitR
                ends[i] = xs[i] + 4f + padX + font.getStringWidth(LABELS[i]) + 6f
            }
            if (MenuSky.moonClearOf(xs, ys, ends, LABELS.size, width.toFloat(), height.toFloat(), 10f)) {
                returnSweepStart = now
            }
        }
        if (returnSweepStart >= 0L) {
            val sweep = if (returnSweepStart == 0L) 0f else (now - returnSweepStart) / RETURN_SWEEP_NS.toFloat()
            intro = minOf(intro, sweep.coerceIn(0f, 1f))
        }

        // 命中检测（轨道项热区，圆点左侧扩 8px）
        var hoveredIndex = -1
        val front = MenuStyle.unfoldFront(intro)
        unfold = intro
        for (i in LABELS.indices) {
            val offset = Math.abs(angle(i)) / 52f
            dotReveal[i] = if (i == LABELS.size / 2) {
                MenuStyle.unfoldCenterScale(intro)
            } else {
                MenuStyle.unfoldPop(MenuStyle.unfoldReach(front, offset))
            }
            reveal[i] = MenuStyle.unfoldLabel(intro)
            val a = Math.toRadians(angle(i).toDouble())
            val ix = orbitX + Math.cos(a).toFloat() * orbitR
            val iy = orbitY + Math.sin(a).toFloat() * orbitR
            itemX[i] = ix
            itemY[i] = iy
            itemW[i] = padX + font.getStringWidth(LABELS[i]) + 6f
            if (reveal[i] > 0.6f &&
                mouseX >= ix - 8f && mouseX <= ix + itemW[i] &&
                mouseY >= iy - itemH / 2f && mouseY < iy + itemH / 2f
            ) {
                hoveredIndex = i
            }
        }

        hovered = if (keySelected >= 0) keySelected else hoveredIndex

        // 大月亮受光方向平滑跟随 hover 项（无 hover 时回到静止姿态）
        val targetDir = if (hovered >= 0) angle(hovered) else REST_DIR
        val targetPhase = if (hovered >= 0) phaseOf(hovered) else REST_PHASE
        val dirLerp = if (dt == 0f) 1f else 1f - Math.exp((-7f * dt).toDouble()).toFloat()
        lightDir += (targetDir - lightDir) * dirLerp
        lightPhase += (targetPhase - lightPhase) * dirLerp
        val hoverLerp = 1f - Math.exp((-14f * dt).toDouble()).toFloat()
        for (i in LABELS.indices) {
            val target = if (i == hovered) 1f else 0f
            hover[i] += (target - hover[i]) * (if (dt == 0f) 1f else hoverLerp)
        }

        // moonlight Interface.color(0) 的 AirPlus 主题色来源
        val accent = ClientThemesUtils.getColor(0)
        MenuSky.setMainPose(width.toFloat(), height.toFloat(), orbitX, orbitY, moonR, lightDir, lightPhase)
        MenuSky.drawScene(width.toFloat(), height.toFloat())
        drawOrbit(orbitX, orbitY, orbitR, unfold)

        for (i in LABELS.indices) {
            val h = hover[i]
            val rv = reveal[i]
            val dr = dotReveal[i]
            if (dr <= 0f) continue
            val dotColor = mixColor(Color(150, 150, 160), accent, h)
            MenuSky.drawMoon(
                itemX[i], itemY[i],
                (1.6f + 1.4f * h) * dr,
                floatArrayOf(0f, 0f, 1f),
                1f,
                dotColor,
                (0.55f + 0.45f * h) * minOf(1f, dr),
                false, null
            )
            val labelAlpha = (0.38f + 0.62f * h) * rv
            if (labelAlpha >= 0.01f) {
                val lx = itemX[i] + padX + 3f * h - 10f * (1f - ease(rv))
                // SplashScreen.textOffset(17) 替代：负半行高使文字垂直居中于圆点
                val ly = itemY[i] - font.height / 2f
                GlStateManager.disableAlpha()
                font.drawString(LABELS[i], lx, ly, withAlpha(Color.WHITE, labelAlpha).rgb)
                GlStateManager.enableAlpha()
            }
        }

        drawFooter()

        // 右下角 Menu Settings 按钮（AirPlus 所有风格通用入口，内部含启动淡入黑罩）
        MainMenuStyles.drawSettingsButton(width, height, mouseX, mouseY)

        super.drawScreen(mouseX, mouseY, partialTicks)

        // ===== 激活淡出黑罩（盖在全部内容之上，约 500ms 渐黑，完成后才切换目标界面）=====
        if (fadeStart >= 0L) {
            val fade = ((System.currentTimeMillis() - fadeStart) / FADE_MS).coerceIn(0f, 1f)
            RenderUtils.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ((fade * 255f).toInt() shl 24))
            if (fade >= 1f) {
                fadeStart = -1L
                val target = pendingScreen
                val exit = pendingExit
                pendingScreen = null
                pendingExit = false
                if (exit || target == null) mc.shutdown() else mc.displayGuiScreen(target)
            }
        }
    }

    /** 轨道弧（-52°~52°，随 unfold 展开可见范围）。 */
    private fun drawOrbit(orbitX: Float, orbitY: Float, orbitR: Float, unfold: Float) {
        val front = MenuStyle.unfoldFront(unfold)
        if (front <= 0f) return
        val half = Math.toRadians(52.0).toFloat()
        MenuStyle.drawOrbitArc(orbitX, orbitY, orbitR, -half, half, -half * front, half * front, 1f)
    }

    private fun drawFooter() {
        // SplashScreen.drawHandoffName / intro() 替代：无交接动画，客户端名 alpha 恒 1、用户名恒 0.3。
        // moonlight 原版 footer 锚点 height - 14；上移至 height - 34 避开右下角 AirPlus 通用 Menu Settings 按钮。
        val nameAlpha = 1f
        val userAlpha = 0.3f
        val font = Fonts.fontMoonlight(14)
        val y = height - 34f - font.height / 2f
        val name = AirPlus.CLIENT_NAME
        val split = splitPoint(name)
        if (0.45f * nameAlpha >= 0.05f) {
            // 客户端名按大写拆分双色：前半主题强调色，后半白色（moonlight 同款 footer）
            val accent = ClientThemesUtils.getColor(0)
            font.drawString(name.substring(0, split), 12f, y, withAlpha(accent, 0.7f * nameAlpha).rgb)
            font.drawString(
                name.substring(split),
                12f + font.getStringWidth(name.substring(0, split)),
                y,
                withAlpha(Color.WHITE, 0.45f * nameAlpha).rgb
            )
        }
        if (0.3f * userAlpha >= 0.05f) {
            val username = mc.session?.username ?: "Player"
            font.drawString(username, width - 12f - font.getStringWidth(username), y, withAlpha(Color.WHITE, userAlpha).rgb)
        }
    }

    /** 激活菜单项：播放音效 → 整体淡出到黑色 → displayGuiScreen 目标界面 / 退出游戏。 */
    private fun activate(index: Int) {
        if (fadeStart >= 0L) return
        mc.playSound("gui.button.press".asResourceLocation())
        pendingExit = index == LABELS.size - 1
        pendingScreen = when (index) {
            0 -> GuiSelectWorld(this)
            1 -> GuiMultiplayer(this)
            2 -> GuiAltManager(this)
            3 -> GuiOptions(this, mc.gameSettings)
            else -> null
        }
        fadeStart = System.currentTimeMillis()
    }

    @Throws(IOException::class)
    override fun keyTyped(typedChar: Char, keyCode: Int) {
        if (fadeStart >= 0L) return
        if (keyCode != Keyboard.KEY_UP && keyCode != Keyboard.KEY_DOWN) {
            if ((keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) &&
                hovered >= 0 && reveal[hovered] > 0.6f
            ) {
                activate(hovered)
            } else {
                super.keyTyped(typedChar, keyCode)
            }
        } else {
            // 方向键选择：未选中时 UP 从末项进入、DOWN 从首项进入
            val base = if (hovered >= 0) hovered else if (keyCode == Keyboard.KEY_UP) LABELS.size else -1
            keySelected = Math.floorMod(base + (if (keyCode == Keyboard.KEY_UP) -1 else 1), LABELS.size)
        }
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int) {
        if (fadeStart >= 0L) return
        if (MainMenuStyles.handleSettingsClick(width, height, mouseX, mouseY, mouseButton)) return

        if (mouseButton == 0) {
            for (i in LABELS.indices) {
                if (reveal[i] > 0.6f &&
                    mouseX >= itemX[i] - 8f && mouseX <= itemX[i] + itemW[i] &&
                    mouseY >= itemY[i] - itemH / 2f && mouseY < itemY[i] + itemH / 2f
                ) {
                    activate(i)
                    return
                }
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton)
    }

    /** 项角度：-42° ~ +42°（ARC_SPAN 84° 均分）。 */
    private fun angle(index: Int): Float = -(ARC_SPAN / 2f) + ARC_SPAN * index / (LABELS.size - 1)

    /** hover 受光仰角：Exit 项使用更大相位（152°），其余 48°。 */
    private fun phaseOf(index: Int): Float = if (index == LABELS.size - 1) EXIT_PHASE else HOVER_PHASE

    private fun mixColor(a: Color, b: Color, t: Float): Color {
        val p = t.coerceIn(0f, 1f)
        return Color(
            (a.red + (b.red - a.red) * p).toInt(),
            (a.green + (b.green - a.green) * p).toInt(),
            (a.blue + (b.blue - a.blue) * p).toInt()
        )
    }

    /** easeOutCubic（moonlight MainMenu.ease）。 */
    private fun ease(t: Float): Float {
        val inv = 1f - t
        return 1f - inv * inv * inv
    }

    private fun withAlpha(color: Color, alpha: Float): Color =
        Color(color.red, color.green, color.blue, (255f * alpha.coerceIn(0f, 1f)).toInt())

    /** 客户端名双色拆分点：首个大写字母处（如 AirPlus -> "Air" + "Plus"）。 */
    private fun splitPoint(name: String): Int {
        for (i in 1 until name.length) {
            if (Character.isUpperCase(name[i])) return i
        }
        return minOf(4, name.length)
    }

    companion object {
        private val LABELS = arrayOf("Singleplayer", "Multiplayer", "Alt Manager", "Settings", "Exit")
        private const val ARC_SPAN = 84f
        const val REST_DIR = 147f
        const val REST_PHASE = 120f
        private const val HOVER_PHASE = 48f
        private const val EXIT_PHASE = 152f
        private const val RETURN_GATE = 0.55f
        private const val RETURN_SWEEP_NS = 600000000L
        private const val FADE_MS = 500f
    }
}
