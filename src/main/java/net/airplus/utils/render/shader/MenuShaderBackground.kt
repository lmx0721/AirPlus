/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Main menu Shadertoy-style background shaders (ported from Vibe Client).
 * 86 bundled fragment presets under /assets/minecraft/airplus/shader/menu/.
 *
 * Rendering strategy (mirrors Vibe MainMenuShaderManager):
 * - Fullscreen background: render into an off-screen Framebuffer (adaptive pixel
 *   budget, sharp by default) and blit it to the real framebuffer every GUI frame.
 * - Previews: render directly into a sub-viewport of the main framebuffer.
 * - Source compatibility: rewrite Shadertoy sources (any #version / out vec4 / texture())
 *   for GLSL 120 at compile time.
 */
package net.airplus.utils.render.shader

import net.airplus.utils.render.RenderUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.shader.Framebuffer
import org.lwjgl.BufferUtils
import org.lwjgl.input.Mouse
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import java.awt.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object MenuShaderBackground {

    /** All bundled preset names (without .frag), sorted case-insensitively. */
    val SHADER_NAMES: List<String> = listOf(
        "abraxas", "Augustus1", "bash", "BasicStarfield2", "bloodmaxiter", "bluefade", "bluefade2",
        "bluefog", "bubbles", "burningsun", "circle", "Clean", "clean2", "core", "core2",
        "cryptonic_1_12", "cube", "cube2", "deadtome", "debug", "Deutschland", "donut", "Dynamic",
        "dynamic2", "dynamic3", "dynamic4", "eject", "ejectlila", "fade", "fixed_rednight",
        "full_polarlights", "galaxy", "galaxy2", "geometry", "ghosttravel", "heart", "lbday",
        "lennidark", "LilaFrag", "liquidbounce", "master", "matrix", "maxiter", "metal",
        "minecraft", "neonsunrise", "NShader1", "NShader2", "panorama", "pixelgradient",
        "polarlights", "prestige", "purple", "purplefade", "purplewater", "RandomGalaxy2",
        "RandomGalaxy3", "rednight", "rednightnew", "RedOrangeDiashow", "redwaterlike",
        "retrosuntravel", "retrowave", "rickandmortylike", "rings", "rise1", "rise2", "rise3",
        "river", "rkblue", "rknight", "sandstorm", "snowstar", "snowtravel", "space", "space3",
        "spider", "starfog", "startrip", "TheEvolution", "town", "trinity", "tunnel", "ukraine",
        "unicode", "water"
    ).sortedWith(String.CASE_INSENSITIVE_ORDER)

    private const val RESOURCE_PREFIX = "/assets/minecraft/airplus/shader/menu/"
    private const val VERTEX_SOURCE = "#version 120\nvoid main(){ gl_Position = gl_Vertex; }"

    /** Max shader compilations per 250ms window (smoothing the first compile when entering the menu). */
    private const val COMPILE_BUDGET_PER_WINDOW = 2
    private var budgetWindowStart = 0L
    private var budgetUsed = 0

    /** Compiled GL programs keyed by preset name; value < 0 marks a known compile failure. */
    private val programs = HashMap<String, Int>()

    // ===== Fullscreen background (offscreen FBO, adaptive resolution) =====
    private var renderTarget: Framebuffer? = null
    private var targetWidth = -1
    private var targetHeight = -1

    // 自适应离屏分辨率：默认全分辨率渲染（画面清晰）；若实测菜单帧间隔持续偏高
    // （GPU 吃不消重着色器）则逐步降低像素预算（最低 22 万 ≈ 640x360 保流畅），
    // 恢复流畅后自动升回。像素预算变化经 16px 量化，避免 FBO 频繁重建。
    private val MIN_PIXEL_BUDGET = 220_000f
    private val MAX_PIXEL_BUDGET = 8_000_000f
    private var pixelBudget = MAX_PIXEL_BUDGET
    private var lastPresentMs = 0L
    private var frameIntervalEma = 16.6f
    private var adaptCounter = 0

    private val mc = Minecraft.getMinecraft()

    /** Human readable preset title ("BasicStarfield2" -> "Basic Starfield 2"). */
    fun displayName(name: String): String =
        name.replace('_', ' ')

    /** Reads a bundled preset from the classpath, or null when missing. */
    private fun readPreset(name: String): String? = runCatching {
        MenuShaderBackground::class.java.getResourceAsStream("$RESOURCE_PREFIX$name.frag")?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
    }.getOrNull()

    /** Rewrites Shadertoy-flavoured GLSL into GLSL 120 understood by MC 1.8.9's compatibility profile. */
    private fun compatibilitySource(source: String): String {
        var src = source.replace(Regex("(?m)^\\s*#version[^\\r\\n]*"), "#version 120")
        src = src.replace(Regex("(?m)^\\s*#extension[^\\r\\n]*\\r?\\n"), "")
        val outputRegex = Regex("(?m)^\\s*out\\s+vec4\\s+(\\w+)\\s*;")
        val match = outputRegex.find(src)
        if (match != null) {
            val output = match.groupValues[1]
            src = src.replaceRange(match.range, "")
            src = src.replace(Regex("\\b${Regex.escape(output)}\\b"), "gl_FragColor")
        }
        return src.replace("texture(", "texture2D(")
    }

    /** Returns true while compilation budget remains within the current 250ms window. */
    private fun tryCompileBudget(): Boolean {
        val now = System.currentTimeMillis()
        if (now - budgetWindowStart > 250L) {
            budgetWindowStart = now
            budgetUsed = 0
        }
        if (budgetUsed >= COMPILE_BUDGET_PER_WINDOW) return false
        budgetUsed++
        return true
    }

    /** Returns the linked program, 0 when compilation is deferred (budget) and -1 on failure. */
    private fun ensureProgram(name: String): Int {
        programs[name]?.let { return it }
        if (!tryCompileBudget()) return 0
        val source = readPreset(name) ?: run {
            programs[name] = -1
            return -1
        }
        return try {
            val vertex = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SOURCE)
            val pixel = compileShader(GL20.GL_FRAGMENT_SHADER, compatibilitySource(source))
            val linked = GL20.glCreateProgram()
            GL20.glAttachShader(linked, vertex)
            GL20.glAttachShader(linked, pixel)
            GL20.glLinkProgram(linked)
            GL20.glDeleteShader(vertex)
            GL20.glDeleteShader(pixel)
            if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                GL20.glDeleteProgram(linked)
                throw IllegalStateException(GL20.glGetProgramInfoLog(linked, 2048))
            }
            programs[name] = linked
            linked
        } catch (e: Exception) {
            programs[name] = -1
            -1
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GL20.glCreateShader(type)
        GL20.glShaderSource(shader, source)
        GL20.glCompileShader(shader)
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            val error = GL20.glGetShaderInfoLog(shader, 2048)
            GL20.glDeleteShader(shader)
            throw IllegalStateException(error)
        }
        return shader
    }

    private fun uniform1(program: Int, name: String, v: Float) {
        val loc = GL20.glGetUniformLocation(program, name)
        if (loc >= 0) GL20.glUniform1f(loc, v)
    }

    private fun uniform2(program: Int, name: String, a: Float, b: Float) {
        val loc = GL20.glGetUniformLocation(program, name)
        if (loc >= 0) GL20.glUniform2f(loc, a, b)
    }

    private fun uniform3(program: Int, name: String, a: Float, b: Float, c: Float) {
        val loc = GL20.glGetUniformLocation(program, name)
        if (loc >= 0) GL20.glUniform3f(loc, a, b, c)
    }

    private fun uniform4(program: Int, name: String, a: Float, b: Float, c: Float, d: Float) {
        val loc = GL20.glGetUniformLocation(program, name)
        if (loc >= 0) GL20.glUniform4f(loc, a, b, c, d)
    }

    private fun setUniforms(program: Int, renderWidth: Int, renderHeight: Int, frameWidth: Int, frameHeight: Int) {
        val time = (System.currentTimeMillis() % 3600000L) / 1000f
        val mouseX = if (Mouse.isCreated()) Mouse.getX() * renderWidth / max(frameWidth, 1).toFloat() else 0f
        val mouseY = if (Mouse.isCreated()) (frameHeight - Mouse.getY()) * renderHeight / max(frameHeight, 1).toFloat() else 0f
        uniform1(program, "time", time)
        uniform2(program, "resolution", renderWidth.toFloat(), renderHeight.toFloat())
        uniform2(program, "mouse", mouseX, mouseY)
        uniform1(program, "iTime", time)
        uniform2(program, "iResolution", renderWidth.toFloat(), renderHeight.toFloat())
        uniform3(program, "iResolution", renderWidth.toFloat(), renderHeight.toFloat(), 1f)
        uniform4(program, "iMouse", mouseX, mouseY, 0f, 0f)
    }

    /**
     * Fullscreen animated menu background: renders into an offscreen target at most
     * every 16ms and presents it stretched to the real framebuffer every call.
     * The offscreen resolution adapts to the measured menu frame interval
     * (sharp by default, downscaled only when the GPU cannot keep up).
     * Falls back to a dark tint when the preset fails to compile.
     */
    fun drawBackground(name: String, guiWidth: Int, guiHeight: Int) {
        if (guiWidth <= 0 || guiHeight <= 0) return
        val program = ensureProgram(name)
        if (program <= 0) {
            if (program < 0) drawBackgroundFallback(guiWidth, guiHeight)
            return
        }

        val framebufferWidth = mc.displayWidth
        val framebufferHeight = mc.displayHeight
        if (framebufferWidth <= 0 || framebufferHeight <= 0) return

        // Adaptive quality: track the interval between calls (= full menu frame time;
        // a GPU-bound shader stretches it). Adjust the pixel budget every ~45 frames.
        val now = System.currentTimeMillis()
        if (lastPresentMs != 0L) {
            val dt = (now - lastPresentMs).toFloat().coerceAtMost(200f)
            frameIntervalEma += (dt - frameIntervalEma) * 0.08f
        }
        lastPresentMs = now
        if (++adaptCounter >= 45) {
            adaptCounter = 0
            pixelBudget = when {
                frameIntervalEma > 24f -> max(MIN_PIXEL_BUDGET, pixelBudget * 0.72f)
                frameIntervalEma < 11f -> min(MAX_PIXEL_BUDGET, pixelBudget * 1.3f)
                else -> pixelBudget
            }
        }

        var renderWidth = framebufferWidth
        var renderHeight = framebufferHeight
        val pixels = framebufferWidth.toLong() * framebufferHeight.toLong()
        if (pixels > pixelBudget) {
            val scale = sqrt(pixelBudget / pixels.toDouble())
            // 16px 量化：预算微调时不重建 FBO
            renderWidth = max((framebufferWidth * scale).toInt() / 16 * 16, 256)
            renderHeight = max((framebufferHeight * scale).toInt() / 16 * 16, 144)
        }
        ensureRenderTarget(renderWidth, renderHeight)

        // 每帧都渲染（不限动画帧率），流畅度由自适应分辨率保证
        renderBackgroundFrame(program, renderWidth, renderHeight)

        mc.framebuffer.bindFramebuffer(true)
        GlStateManager.color(1f, 1f, 1f, 1f)
        renderTarget!!.framebufferRenderExt(framebufferWidth, framebufferHeight, false)
        // framebufferRenderExt restores physical-pixel ortho; bring back the scaled GUI projection.
        mc.entityRenderer.setupOverlayRendering()
        GlStateManager.enableTexture2D()
        GlStateManager.enableBlend()
        GlStateManager.color(1f, 1f, 1f, 1f)
    }

    private fun renderBackgroundFrame(program: Int, renderWidth: Int, renderHeight: Int) {
        val previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        val previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
        val previousViewport = BufferUtils.createIntBuffer(16)
        GL11.glGetInteger(GL11.GL_VIEWPORT, previousViewport)
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT)
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        try {
            renderTarget!!.bindFramebuffer(true)
            GL11.glViewport(0, 0, renderWidth, renderHeight)
            GL11.glDisable(GL11.GL_TEXTURE_2D)
            GL11.glDisable(GL11.GL_DEPTH_TEST)
            GL11.glDisable(GL11.GL_ALPHA_TEST)
            GL11.glDisable(GL11.GL_BLEND)
            GL11.glColor4f(1f, 1f, 1f, 1f)
            GL20.glUseProgram(program)
            setUniforms(program, renderWidth, renderHeight, mc.displayWidth, mc.displayHeight)
            GL11.glBegin(GL11.GL_QUADS)
            GL11.glVertex2f(-1f, -1f)
            GL11.glVertex2f(1f, -1f)
            GL11.glVertex2f(1f, 1f)
            GL11.glVertex2f(-1f, 1f)
            GL11.glEnd()
        } finally {
            GL20.glUseProgram(previousProgram)
            GL11.glMatrixMode(GL11.GL_MODELVIEW)
            GL11.glPopMatrix()
            GL11.glMatrixMode(GL11.GL_PROJECTION)
            GL11.glPopMatrix()
            GL11.glMatrixMode(GL11.GL_MODELVIEW)
            // The saved draw buffer belongs to the previous framebuffer; restoring it
            // while the shader FBO is bound is invalid after target allocation.
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, previousFramebuffer)
            GL11.glPopAttrib()
        }
    }

    private fun ensureRenderTarget(width: Int, height: Int) {
        val current = renderTarget
        if (current != null && targetWidth == width && targetHeight == height) return
        current?.deleteFramebuffer()
        renderTarget = Framebuffer(width, height, false).also {
            it.setFramebufferFilter(GL11.GL_LINEAR)
        }
        targetWidth = width
        targetHeight = height
    }

    private fun drawBackgroundFallback(guiWidth: Int, guiHeight: Int) {
        RenderUtils.drawRect(
            0f, 0f, guiWidth.toFloat(), guiHeight.toFloat(),
            Color(10, 12, 22).rgb
        )
    }

    /** Frees all GL resources (called on client shutdown paths if needed). */
    fun disposeAll() {
        for (program in programs.values) {
            if (program > 0) GL20.glDeleteProgram(program)
        }
        programs.clear()
        renderTarget?.deleteFramebuffer()
        renderTarget = null
        targetWidth = -1
        targetHeight = -1
    }
}
