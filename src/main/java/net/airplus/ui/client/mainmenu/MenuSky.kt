/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.ui.client.mainmenu

import net.airplus.utils.client.ClientUtils.LOGGER
import net.airplus.utils.render.RenderUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import java.awt.Color
import java.util.concurrent.ThreadLocalRandom

/**
 * Moonlight MenuSky 移植：着色器流星夜空 + 月亮 + 云层（EdMoonlight 提取的 menu_sky/menu_moon/menu_cloud.frag）。
 *
 * 裁剪与适配：
 * - JoinView 入服交接（beginJoin/endJoin/joining/transitional/handoff/drawSplash）全部裁剪，joining() 恒为 false
 * - OrbitPage.applies 引用删除；Client.LOGGER → ClientUtils.LOGGER
 * - RenderUtil.drawImage/drawRect → RenderUtils.drawImage/drawRect（角点语义）
 * - fastutil uniform 缓存 → HashMap
 * - moon 着色器不可用时的 LOGO 贴图回退使用提取出的 EdMoonlightLogo_256.png
 */
object MenuSky {

    private const val SHADER_DIR = "/assets/minecraft/moonlight/shader/"
    private const val VERTEX_SRC =
        "#version 120\nvoid main() {\n    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;\n}\n"
    private val FALLBACK = Color(14, 18, 40)
    private val MOON = Color(250, 247, 240)
    private val LOGO = ResourceLocation("moonlight/texture/logo/EdMoonlightLogo_256.png")
    private val EPOCH = System.nanoTime()
    private const val TRANSITION_NS = 1100000000L

    private var program = 0
    private var cloudProgram = 0
    private var moonProgram = 0

    private const val MAX_METEORS = 6
    private val meteors = arrayOfNulls<Meteor>(MAX_METEORS)
    private var meteorAt = 1.5f

    private var sunDir = 147f
    private var sunPhase = 120f

    // 各子界面视图的月亮落点（AirPlus 不在子界面背后绘制天空，仅保留 pose 系统完整性）
    private val SINGLE = Pose(0.10f, 0.50f, 0.16f, Float.NaN, 0f, -150f, -60f)
    private val MULTI = Pose(0.07f, 0.52f, 0.21f, Float.NaN, 0f, -60f, 90f)
    private val SINGLE_FORM = Pose(0.075f, 0.62f, 0.15f, Float.NaN, 0f, -150f, -60f)
    private val MULTI_FORM = Pose(0.945f, 0.12f, 0.09f, Float.NaN, 0f, 220f, 90f)
    private val SETTINGS = Pose(0.09f, 0.55f, 0.15f, Float.NaN, 0f, 140f, -40f)
    private val LANGUAGE = Pose(0.075f, 0.52f, 0.13f, Float.NaN, 0f, 190f, -60f)
    private val OPTIONS = Pose(0.955f, 0.6f, 0.16f, Float.NaN, 0f, 200f, -20f)
    private val ALT = Pose(0.085f, 0.47f, 0.13f, Float.NaN, 0f, -120f, 120f)
    private val ALT_FORM = Pose(0.1f, 0.52f, 0.15f, Float.NaN, 0f, -170f, 140f)

    /** 主菜单月亮姿态：x/y 为月亮中心（屏幕比例），r 为半径（高度比例），dir/phase 为受光方向。 */
    private var mainPose = Pose(0.4f, 0.5f, 0.19f, 147f, 120f, 0f, 0f)
    private var from: Pose? = null
    private var current: Pose? = null
    private var currentView: String? = null
    private var transitionStart = 0L
    private var transitionNs = TRANSITION_NS
    private var returning = false

    private val UNIFORMS = HashMap<Int, HashMap<String, Int>>()

    private val OPTIONS_KEYS = arrayOf(
        "Options", "Controls", "Language", "ResourcePack", "Snooper", "Shader", "Customize", "Video", "Sound"
    )

    fun time(): Float = (System.nanoTime() - EPOCH) / 1.0e9f

    /** 每帧由主菜单调用：以当前屏幕尺寸换算主菜单月亮姿态。 */
    fun setMainPose(width: Float, height: Float, moonX: Float, moonY: Float, moonR: Float, dir: Float, phase: Float) {
        mainPose = Pose(moonX / width, moonY / height, moonR / height, dir, phase, 0f, 0f)
    }

    fun setSun(dir: Float, phase: Float) {
        sunDir = dir
        sunPhase = phase
    }

    /** 视图切换进度（0~1，easeInOut，1.1s）。 */
    fun settle(): Float =
        if (from == null) 1f else easeInOut(minOf(1f, (System.nanoTime() - transitionStart) / transitionNs.toFloat()))

    /** 返回主菜单的交接进度：未处于返回状态时恒为 1。 */
    fun returnProgress(): Float = if (returning && from != null) settle() else 1f

    fun syncView(): String {
        val now = System.nanoTime()
        val view = viewOf(Minecraft.getMinecraft().currentScreen)
        if (view != currentView) {
            from = if (currentView != null) current else null
            returning = currentView != null && "main" == view
            currentView = view
            transitionStart = now
            transitionNs = TRANSITION_NS
        }
        return view
    }

    private fun viewOf(screen: Any?): String {
        if (screen == null || screen is MoonlightMainMenu) return "main"
        val name = screen.javaClass.name
        return when {
            name.endsWith("GuiSelectWorld") -> "single"
            name.endsWith("GuiCreateWorld") || name.endsWith("GuiCreateFlatWorld") ||
                name.endsWith("GuiCustomizeWorldScreen") || name.endsWith("GuiRenameWorld") -> "singleForm"
            name.endsWith("GuiMultiplayer") || name.endsWith("GuiConnecting") ||
                name.endsWith("GuiDisconnected") -> "multi"
            name.endsWith("GuiScreenAddServer") || name.endsWith("GuiScreenServerList") -> "multiForm"
            name == "net.minecraft.client.gui.GuiOptions" || name == "net.minecraft.client.gui.GuiControls" -> "settings"
            name == "net.airplus.ui.client.altmanager.GuiAltManager" -> "alt"
            name.startsWith("net.airplus.ui.client.altmanager") -> "altForm"
            name == "net.minecraft.client.gui.GuiLanguage" -> "settings"
            OPTIONS_KEYS.any { name.contains(it) } -> "options"
            else -> currentView ?: "main"
        }
    }

    private fun poseOf(view: String): Pose {
        val pose = when (view) {
            "single" -> SINGLE
            "singleForm" -> SINGLE_FORM
            "multi" -> MULTI
            "multiForm" -> MULTI_FORM
            "options" -> OPTIONS
            "settings" -> SETTINGS
            "language" -> LANGUAGE
            "alt" -> ALT
            "altForm" -> ALT_FORM
            else -> mainPose
        }
        return if (pose.light != null) pose else Pose(pose.x, pose.y, pose.r, sunDir, sunPhase, pose.panX, pose.panY)
    }

    /** 指定视图的月亮位置 [x, y, r]（按当前屏幕尺寸换算）。 */
    fun moonAt(view: String, width: Float, height: Float): FloatArray {
        val pose = poseOf(view)
        return floatArrayOf(pose.x * width, pose.y * height, pose.r * height)
    }

    /** 月亮是否与一组矩形（轨道项热区）保持安全间距。 */
    fun moonClearOf(
        xs: FloatArray, ys: FloatArray, ends: FloatArray, count: Int,
        width: Float, height: Float, pad: Float
    ): Boolean {
        val pose = current ?: return true
        val moonX = pose.x * width
        val moonY = pose.y * height
        val moonR = pose.r * height
        for (i in 0 until count) {
            val nearestX = maxOf(xs[i], minOf(ends[i], moonX))
            if (Math.hypot((nearestX - moonX).toDouble(), (ys[i] - moonY).toDouble()) < moonR + pad) {
                return false
            }
        }
        return true
    }

    /** 绘制整片夜空（天空着色器 + 月亮 + 云层 + 流星），主菜单每帧调用。 */
    fun drawScene(width: Float, height: Float) {
        val view = syncView()
        val target = poseOf(view)
        val progress = settle()
        val pose: Pose
        if (from != null && progress < 1f) {
            pose = lerp(from!!, target, progress)
        } else {
            if (progress >= 1f) from = null
            pose = target
        }
        current = pose
        drawPose(width, height, pose, "main" == view)
    }

    /** 夜空高度渐变纯色填充（moonlight SplashScreen/JoinView 使用的兜底绘制，保留备用）。 */
    fun fillSky(width: Float, height: Float, alpha: Float) {
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.disableAlpha()
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO)
        val segments = 24
        GL11.glBegin(GL11.GL_QUAD_STRIP)
        for (i in 0..segments) {
            val t = i.toFloat() / segments
            val p3 = Math.pow(t.toDouble(), 3.0).toFloat()
            GL11.glColor4f(
                0.039f + 0.036f * t + 0.05f * p3,
                0.047f + 0.051f * t + 0.035f * p3,
                0.11f + 0.09f * t + 0.08f * p3,
                alpha
            )
            GL11.glVertex2f(0f, t * height)
            GL11.glVertex2f(width, t * height)
        }
        GL11.glEnd()
        GL11.glColor4f(1f, 1f, 1f, 1f)
        GlStateManager.enableAlpha()
        GlStateManager.enableTexture2D()
        GlStateManager.color(1f, 1f, 1f, 1f)
    }

    private fun drawPose(width: Float, height: Float, pose: Pose, main: Boolean) {
        val moonX = pose.x * width
        val moonY = pose.y * height
        val moonR = pose.r * height
        val light = pose.light!!
        val lit = (1f + light[2]) / 2f
        draw(width, height, moonX, moonY, moonR, lit, light, pose.panX, pose.panY)
        if (!drawMoon(moonX, moonY, moonR, light, 0.07f, MOON, 1f, true, skyAt((moonY / height).coerceIn(0f, 1f), lit)) && main) {
            // moon 着色器不可用时的 LOGO 贴图回退（EdMoonlight 提取资源）
            val size = moonR * 2f / 0.9188f
            RenderUtils.drawImage(LOGO, moonX - 0.5006f * size, moonY - 0.4863f * size, size.toInt(), size.toInt())
        }
        drawClouds(moonX, moonY, moonR, lit, light, pose.panX, pose.panY)
    }

    private fun draw(
        width: Float, height: Float, moonX: Float, moonY: Float,
        moonR: Float, lit: Float, light: FloatArray, panX: Float, panY: Float
    ) {
        val mc = Minecraft.getMinecraft()
        val t = time()
        if (program == 0) program = compile(loadShader("menu_sky.frag"), "sky")
        if (program < 0) {
            RenderUtils.drawRect(0f, 0f, width, height, FALLBACK.rgb)
        } else {
            val scale = ScaledResolution(mc).scaleFactor
            GlStateManager.disableBlend()
            GlStateManager.disableTexture2D()
            GL20.glUseProgram(program)
            GL20.glUniform2f(uniform(program, "res"), mc.displayWidth.toFloat(), mc.displayHeight.toFloat())
            GL20.glUniform1f(uniform(program, "time"), t)
            GL20.glUniform1f(uniform(program, "scale"), scale.toFloat())
            val driftX = t * 1.6f % 100000f
            val driftY = t * 0.5f % 100000f
            GL20.glUniform2f(uniform(program, "drift"), driftX * scale, driftY * scale)
            GL20.glUniform2f(uniform(program, "pan"), panX * scale, -panY * scale)
            GL20.glUniform2f(uniform(program, "moonC"), moonX * scale, mc.displayHeight - moonY * scale)
            GL20.glUniform1f(uniform(program, "moonR"), maxOf(0.001f, moonR * scale))
            GL20.glUniform1f(uniform(program, "moonLit"), lit)
            GL20.glUniform3f(uniform(program, "moonLight"), light[0], light[1], light[2])
            GL11.glBegin(GL11.GL_QUADS)
            GL11.glVertex2f(0f, 0f)
            GL11.glVertex2f(0f, height)
            GL11.glVertex2f(width, height)
            GL11.glVertex2f(width, 0f)
            GL11.glEnd()
            GL20.glUseProgram(0)
            GlStateManager.enableTexture2D()
            GlStateManager.enableBlend()
            GlStateManager.color(1f, 1f, 1f, 1f)
        }
        drawMeteor(width, height, t)
    }

    private fun drawClouds(
        moonX: Float, moonY: Float, moonR: Float, lit: Float,
        light: FloatArray, panX: Float, panY: Float
    ) {
        if (cloudProgram == 0) cloudProgram = compile(loadShader("menu_cloud.frag"), "cloud")
        if (cloudProgram < 0) return
        val mc = Minecraft.getMinecraft()
        val sr = ScaledResolution(mc)
        val scale = sr.scaleFactor
        val program = cloudProgram
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.disableAlpha()
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO)
        GL20.glUseProgram(program)
        GL20.glUniform2f(uniform(program, "res"), mc.displayWidth.toFloat(), mc.displayHeight.toFloat())
        GL20.glUniform1f(uniform(program, "time"), time() % 20000f)
        GL20.glUniform1f(uniform(program, "scale"), scale.toFloat())
        GL20.glUniform2f(uniform(program, "pan"), panX * scale, -panY * scale)
        GL20.glUniform2f(uniform(program, "moonC"), moonX * scale, mc.displayHeight - moonY * scale)
        GL20.glUniform1f(uniform(program, "moonR"), maxOf(0.001f, moonR * scale))
        GL20.glUniform1f(uniform(program, "moonLit"), lit)
        GL20.glUniform3f(uniform(program, "moonLight"), light[0], light[1], light[2])
        GL11.glBegin(GL11.GL_QUADS)
        GL11.glVertex2f(0f, 0f)
        GL11.glVertex2f(0f, sr.scaledHeight.toFloat())
        GL11.glVertex2f(sr.scaledWidth.toFloat(), sr.scaledHeight.toFloat())
        GL11.glVertex2f(sr.scaledWidth.toFloat(), 0f)
        GL11.glEnd()
        GL20.glUseProgram(0)
        GlStateManager.enableAlpha()
        GlStateManager.enableTexture2D()
        GlStateManager.color(1f, 1f, 1f, 1f)
    }

    /**
     * 绘制月亮/圆点（menu_moon.frag）。surface=true 时按真实球体着色（主菜单大月亮），
     * false 时绘制纯色小圆点（菜单项圆点）。着色器不可用返回 false。
     */
    fun drawMoon(
        x: Float, y: Float, radius: Float, light: FloatArray, earth: Float,
        tint: Color, alpha: Float, surface: Boolean, base: FloatArray?
    ): Boolean {
        if (moonProgram == 0) moonProgram = compile(loadShader("menu_moon.frag"), "moon")
        if (moonProgram < 0) {
            GlStateManager.enableBlend()
            return false
        }
        val mc = Minecraft.getMinecraft()
        val scale = ScaledResolution(mc).scaleFactor
        val program = moonProgram
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.disableAlpha()
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO)
        GL20.glUseProgram(program)
        GL20.glUniform2f(uniform(program, "center"), x * scale, mc.displayHeight - y * scale)
        GL20.glUniform1f(uniform(program, "radius"), radius * scale)
        GL20.glUniform1f(uniform(program, "earth"), earth)
        GL20.glUniform1f(uniform(program, "aa"), if (surface) 1.6f else 1f)
        GL20.glUniform1f(uniform(program, "surface"), if (surface) 1f else 0f)
        GL20.glUniform1f(uniform(program, "occlude"), if (surface) 1f else 0f)
        GL20.glUniform1f(uniform(program, "time"), time())
        GL20.glUniform3f(uniform(program, "light"), light[0], light[1], light[2])
        GL20.glUniform3f(uniform(program, "tint"), tint.red / 255f, tint.green / 255f, tint.blue / 255f)
        val baseColor = base ?: FloatArray(3)
        GL20.glUniform3f(uniform(program, "base"), baseColor[0], baseColor[1], baseColor[2])
        GL20.glUniform1f(uniform(program, "alpha"), alpha)
        val margin = 2f
        val sr = ScaledResolution(mc)
        val x0 = maxOf(0f, x - radius - margin)
        val y0 = maxOf(0f, y - radius - margin)
        val x1 = minOf(sr.scaledWidth.toFloat(), x + radius + margin)
        val y1 = minOf(sr.scaledHeight.toFloat(), y + radius + margin)
        if (x1 > x0 && y1 > y0) {
            GL11.glBegin(GL11.GL_QUADS)
            GL11.glVertex2f(x0, y0)
            GL11.glVertex2f(x0, y1)
            GL11.glVertex2f(x1, y1)
            GL11.glVertex2f(x1, y0)
            GL11.glEnd()
        }
        GL20.glUseProgram(0)
        GlStateManager.enableAlpha()
        GlStateManager.enableTexture2D()
        GlStateManager.color(1f, 1f, 1f, 1f)
        return true
    }

    /** 天空底部颜色采样（与 sky 着色器渐变近似），供月亮底部补色。 */
    fun skyAt(y: Float, lit: Float): FloatArray {
        val p3 = Math.pow(y.toDouble(), 3.0).toFloat()
        val glow = (0.25f + 0.75f * lit) * 0.075f
        return floatArrayOf(
            0.039f + 0.036f * y + 0.05f * p3 + 0.62f * glow,
            0.047f + 0.051f * y + 0.035f * p3 + 0.68f * glow,
            0.11f + 0.09f * y + 0.08f * p3 + 0.9f * glow
        )
    }

    /** 受光方向向量（dir 方位角 + phase 仰角）。 */
    fun lightVector(dir: Float, phase: Float): FloatArray {
        val d = Math.toRadians(dir.toDouble())
        val p = Math.toRadians(phase.toDouble())
        return floatArrayOf(
            (Math.sin(p) * Math.cos(d)).toFloat(),
            (-Math.sin(p) * Math.sin(d)).toFloat(),
            Math.cos(p).toFloat()
        )
    }

    private fun drawMeteor(width: Float, height: Float, time: Float) {
        val random = ThreadLocalRandom.current()
        if (time >= meteorAt) {
            val count = if (random.nextFloat() < 0.3f) 2 + random.nextInt(2) else 1
            for (i in 0 until count) {
                spawnMeteor(width, height, time + i * (0.08f + 0.25f * random.nextFloat()), time, random)
            }
            meteorAt = time + 0.5f + 1.3f * random.nextFloat()
        }

        var anyActive = false
        for (meteor in meteors) {
            if (meteor != null && time >= meteor.start) {
                anyActive = true
                break
            }
        }
        if (!anyActive) return

        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO)
        GL11.glEnable(GL11.GL_LINE_SMOOTH)

        for (i in meteors.indices) {
            val meteor = meteors[i] ?: continue
            if (time < meteor.start) continue
            val progress = (time - meteor.start) / meteor.len
            if (progress >= 1f) {
                meteors[i] = null
            } else {
                val dist = meteor.dist * progress
                val head = maxOf(0f, dist - meteor.tail)
                val alpha = meteor.alpha * minOf(1f, minOf(progress / 0.06f, (1f - progress) / 0.06f))
                GL11.glLineWidth(meteor.width)
                GL11.glBegin(GL11.GL_LINES)
                GL11.glColor4f(0.85f, 0.9f, 1f, 0f)
                GL11.glVertex2f(meteor.x + meteor.dx * head, meteor.y + meteor.dy * head)
                GL11.glColor4f(1f, 1f, 1f, alpha)
                GL11.glVertex2f(meteor.x + meteor.dx * dist, meteor.y + meteor.dy * dist)
                GL11.glEnd()
            }
        }

        GL11.glLineWidth(1f)
        GL11.glDisable(GL11.GL_LINE_SMOOTH)
        GL11.glColor4f(1f, 1f, 1f, 1f)
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO)
        GlStateManager.enableTexture2D()
        GlStateManager.color(1f, 1f, 1f, 1f)
    }

    private fun spawnMeteor(width: Float, height: Float, start: Float, time: Float, random: ThreadLocalRandom) {
        var slot = -1
        for (i in meteors.indices) {
            if (meteors[i] == null) {
                slot = i
                break
            }
        }
        if (slot < 0) return

        // 流星方向约 155°（随时间轻微摆动）+ 抖动，与屏幕边界求交裁剪出生点与长度
        val angleBase = 155.0 + 8.0 * Math.sin((time * 0.05).toDouble())
        val angle = Math.toRadians(angleBase + (random.nextDouble() - 0.5) * 12.0)
        val dx = Math.cos(angle).toFloat()
        val dy = Math.sin(angle).toFloat()
        val px = width * (0.2f + 0.8f * random.nextFloat())
        val py = height * (0.02f + 0.5f * random.nextFloat())
        val bound = maxOf(width, height) * 0.25f
        var tMin = -Float.MAX_VALUE
        var tMax = Float.MAX_VALUE
        val axes = arrayOf(
            floatArrayOf(px, dx, -bound, width + bound),
            floatArrayOf(py, dy, -bound, height + bound)
        )
        for (axis in axes) {
            if (Math.abs(axis[1]) > 1.0e-5f) {
                val t0 = (axis[2] - axis[0]) / axis[1]
                val t1 = (axis[3] - axis[0]) / axis[1]
                tMin = maxOf(tMin, minOf(t0, t1))
                tMax = minOf(tMax, maxOf(t0, t1))
            }
        }

        val seed = random.nextFloat()
        val meteor = Meteor()
        meteor.start = start
        meteor.x = px + dx * tMin
        meteor.y = py + dy * tMin
        meteor.dx = dx
        meteor.dy = dy
        meteor.dist = tMax - tMin
        meteor.len = meteor.dist / (width / (1.6f - 0.6f * seed))
        meteor.tail = width * (0.1f + 0.14f * seed)
        meteor.alpha = 0.35f + 0.5f * seed
        meteor.width = 0.9f + 0.8f * seed
        meteors[slot] = meteor
    }

    private fun lerp(a: Pose, b: Pose, t: Float): Pose {
        val dir = a.dir + (b.dir - a.dir) * t
        val phase = a.phase + (b.phase - a.phase) * t
        val r = Math.exp(
            Math.log(a.r.toDouble()) + (Math.log(b.r.toDouble()) - Math.log(a.r.toDouble())) * t
        ).toFloat()
        return Pose(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t,
            r,
            dir,
            phase,
            a.panX + (b.panX - a.panX) * t,
            a.panY + (b.panY - a.panY) * t
        )
    }

    private fun easeInOut(t: Float): Float =
        if (t < 0.5f) 4f * t * t * t else 1f - Math.pow(-2.0 * t + 2.0, 3.0).toFloat() / 2f

    fun loadShader(name: String): String? = try {
        val stream = MenuSky::class.java.getResourceAsStream(SHADER_DIR + name)
        stream?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        LOGGER.warn("[MenuSky] Failed to load shader {}", name)
        null
    }

    /** 编译着色器程序（固定 #version 120 顶点壳 + 片段源），失败返回 -1（调用方走兜底绘制）。 */
    fun compile(source: String?, name: String): Int {
        if (source == null) {
            LOGGER.warn("[MenuSky] {} shader source missing", name)
            return -1
        }
        return try {
            val vertex = shader(GL20.GL_VERTEX_SHADER, VERTEX_SRC, name)
            val fragment = shader(GL20.GL_FRAGMENT_SHADER, source, name)
            if (vertex >= 0 && fragment >= 0) {
                val program = GL20.glCreateProgram()
                GL20.glAttachShader(program, vertex)
                GL20.glAttachShader(program, fragment)
                GL20.glLinkProgram(program)
                if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                    LOGGER.warn("[MenuSky] {} shader link failed: {}", name, GL20.glGetProgramInfoLog(program, 1024))
                    -1
                } else {
                    program
                }
            } else {
                -1
            }
        } catch (t: Throwable) {
            LOGGER.warn("[MenuSky] {} shader unavailable", name, t)
            -1
        }
    }

    private fun shader(type: Int, source: String, name: String): Int {
        val shader = GL20.glCreateShader(type)
        GL20.glShaderSource(shader, source)
        GL20.glCompileShader(shader)
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            LOGGER.warn("[MenuSky] {} shader compile failed: {}", name, GL20.glGetShaderInfoLog(shader, 1024))
            return -1
        }
        return shader
    }

    private fun uniform(program: Int, name: String): Int =
        UNIFORMS.getOrPut(program) { HashMap() }.getOrPut(name) { GL20.glGetUniformLocation(program, name) }

    private class Meteor {
        var start = 0f
        var x = 0f
        var y = 0f
        var dx = 0f
        var dy = 0f
        var len = 0f
        var dist = 0f
        var tail = 0f
        var alpha = 0f
        var width = 0f
    }

    /** 月亮姿态：dir/phase 记录受光方向用于 lerp，NaN 表示无固定受光（跟随 sunDir/sunPhase）。 */
    class Pose(
        val x: Float, val y: Float, val r: Float,
        val dir: Float, val phase: Float,
        val panX: Float, val panY: Float
    ) {
        // 用 getter 延迟计算：避免在 MenuSky 单例构造期间（SINGLE..ALT_FORM 初始化）访问未就绪的 INSTANCE
        val light: FloatArray?
            get() = if (dir.isNaN()) null else MenuSky.lightVector(dir, phase)
    }
}
