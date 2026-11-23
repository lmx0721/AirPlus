/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.render

import net.airplus.features.module.modules.render.Sky
import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.render.shader.MenuShaderBackground
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.WorldClient
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.entity.Entity
import net.minecraft.util.MathHelper
import net.minecraft.util.Vec3
import net.minecraft.world.World
import net.minecraftforge.client.IRenderHandler
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL20
import kotlin.math.tan

/**
 * 世界天空的 Shader 覆盖（"主菜单 shader 背景"搬到游戏内）：
 * 通过 Forge 的 WorldProvider.setSkyRenderer 挂载 [IRenderHandler]（renderSky 最先检查，
 * vanilla 与 OptiFine 合入版都遵守），在天空绘制阶段把 89 个菜单预设之一全屏直绘进
 * 主 framebuffer。地形在天空之后绘制，自然覆盖地平线以下区域，无需任何深度遮罩。
 *
 * 两个绘制 pass：
 * 1. 菜单预设着色器全屏直绘（复用 [MenuShaderBackground] 的 GLSL 120 编译管线 + uniforms，
 *    gl_Position = gl_Vertex 直接落在裁剪空间，无需动投影/模型视图矩阵）
 * 2. 地平线雾化带（skyfog.frag）：按像素世界仰角把天空淡入地形雾色，消除硬接缝；
 *    仰角由相机 pitch + FOV 推出，fovy 复刻 setupCameraTransform 公式
 *
 * 渲染期处于世界雾开启、depthMask(false) 的中间态——所有状态走 GlStateManager
 * 对称调用（禁用前先读真实值），不走 pushAttrib，避免缓存与真实 GL 脱钩。
 * OptiFine shader 包开启时自动让位（skyRenderer 摘除，vanilla 下一帧恢复）。
 */
object ShaderSky : MinecraftInstance {

    private const val FOG_RESOURCE = "/assets/minecraft/airplus/shader/fragment/skyfog.frag"
    private const val FOG_VERTEX = "#version 120\nvoid main(){ gl_Position = gl_Vertex; }"

    // ---- 地平线雾化带着色器（-1 = 编译失败缓存）----
    private var fogProgram = 0
    private var fogCompiled = false

    /** 天空渲染器实例（挂到 WorldProvider.skyRenderer） */
    private val skyHandler = object : IRenderHandler() {
        override fun render(partialTicks: Float, world: WorldClient, mcIn: Minecraft) {
            renderSkyFrame(partialTicks, world)
        }
    }

    /** OptiFine 的 Config.isShaders()（反射，未装 OF 时为 null） */
    private val ofShadersMethod by lazy {
        runCatching { Class.forName("Config").getMethod("isShaders") }.getOrNull()
    }

    private fun optifineShadersActive(): Boolean =
        ofShadersMethod?.let { runCatching { it.invoke(null) as Boolean }.getOrDefault(false) } ?: false

    // ---- 挂载 / 摘除 ----

    /**
     * 世界加载/切换后重新挂载（Sky 模块启用时）；维度切换会重建 WorldClient/provider。
     * force：onEnable 路径使用 —— Module 基类先调 onEnable 再置 state=true，
     * 此时 handleEvents()（=state && isActive）恒为 false，必须绕过 state 门。
     */
    @JvmStatic
    fun applyToWorld(world: World?, force: Boolean = false) {
        world ?: return
        if (force || Sky.handleEvents()) world.provider.setSkyRenderer(skyHandler)
    }

    /** 模块关闭：立即摘除，下一帧起恢复 vanilla 天空 */
    @JvmStatic
    fun removeFrom(world: World?) {
        world?.provider?.setSkyRenderer(null)
    }

    // ---- 主流程 ----

    private fun renderSkyFrame(partialTicks: Float, world: World) {
        if (!Sky.handleEvents() || optifineShadersActive()) {
            // OptiFine shader 包开启时会接管整个天空管线，此时必须让位，
            // 否则 OF 不绘制天空 -> 黑屏；摘除后 vanilla 从下一帧恢复
            world.provider.setSkyRenderer(null)
            return
        }
        val frameWidth = mc.displayWidth
        val frameHeight = mc.displayHeight
        if (frameWidth <= 0 || frameHeight <= 0) return

        // ---- GL 状态保存（读真实值 + GlStateManager 对称调用，缓存安全）----
        val prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        val prevColor = BufferUtils.createFloatBuffer(16)
        // LWJGL 2.9.4-nightly 的 GL_CURRENT_COLOR = 2816 是错值（实为 GL_MODELVIEW_MATRIX），
        // 必须用 LwjglCompat 的规范值 0x0B01，否则「保存颜色」读到的是矩阵、
        // 恢复时把矩阵值写进当前颜色污染后续渲染
        GL11.glGetFloat(LwjglCompat.GL_CURRENT_COLOR, prevColor)
        val fogWasEnabled = GL11.glIsEnabled(GL11.GL_FOG)
        val textureWasEnabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D)
        val alphaWasEnabled = GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
        val blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND)
        val blendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB)
        val blendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB)
        val blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA)
        val blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)
        val depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
        val depthMaskWas = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)

        // ---- 状态禁用（只在原状态为开时调用，保证对称）----
        if (fogWasEnabled) GlStateManager.disableFog()
        if (textureWasEnabled) GlStateManager.disableTexture2D()
        if (alphaWasEnabled) GlStateManager.disableAlpha()
        if (blendWasEnabled) GlStateManager.disableBlend()
        GlStateManager.depthMask(false)
        if (depthWasEnabled) GlStateManager.disableDepth()

        try {
            // ---- Pass 1: 天空主体（Color = GL 纯色直绘 / Shader = 菜单预设着色器）----
            if (Sky.type == "Color") {
                val rgb = Sky.skyColors.color().rgb
                GL11.glColor4f(
                    ((rgb shr 16) and 0xFF) / 255f,
                    ((rgb shr 8) and 0xFF) / 255f,
                    (rgb and 0xFF) / 255f,
                    ((rgb shr 24) and 0xFF) / 255f
                )
                drawFullscreenQuad()
            } else {
                val program = MenuShaderBackground.getProgram(Sky.mode)
                if (program > 0) {
                    GL20.glUseProgram(program)
                    MenuShaderBackground.setPresetUniforms(program, frameWidth, frameHeight, Sky.timeScale)
                    GL11.glColor4f(1f, 1f, 1f, 1f)
                    drawFullscreenQuad()
                } else if (program < 0) {
                    // 预设编译失败：雾色兜底，避免黑屏
                    drawFogFill(world, partialTicks)
                }
                // program == 0：编译预算延迟（250ms 窗口限流），本帧跳过
            }

            // ---- Pass 2: 地平线雾化带 ----
            if (Sky.horizonFade) drawHorizonFade(world, partialTicks, frameWidth, frameHeight)
        } finally {
            // ---- 状态恢复（与禁用严格对称）----
            GL20.glUseProgram(prevProgram)
            if (depthWasEnabled) GlStateManager.enableDepth()
            GlStateManager.depthMask(depthMaskWas)
            if (blendWasEnabled) GlStateManager.enableBlend()
            GlStateManager.tryBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha)
            if (alphaWasEnabled) GlStateManager.enableAlpha()
            if (textureWasEnabled) GlStateManager.enableTexture2D()
            if (fogWasEnabled) GlStateManager.enableFog()
            GL11.glColor4f(prevColor[0], prevColor[1], prevColor[2], prevColor[3])
        }
    }

    private fun drawFullscreenQuad() {
        GL11.glBegin(GL11.GL_QUADS)
        GL11.glVertex2f(-1f, -1f)
        GL11.glVertex2f(1f, -1f)
        GL11.glVertex2f(1f, 1f)
        GL11.glVertex2f(-1f, 1f)
        GL11.glEnd()
    }

    /** 预设编译失败时的雾色兜底填充 */
    private fun drawFogFill(world: World, partialTicks: Float) {
        val fog = fogColor(world, partialTicks)
        GL11.glColor4f(fog.xCoord.toFloat(), fog.yCoord.toFloat(), fog.zCoord.toFloat(), 1f)
        drawFullscreenQuad()
    }

    /** 地平线雾化带：按像素仰角把天空淡入雾色（blend SRC_ALPHA / ONE_MINUS_SRC_ALPHA） */
    private fun drawHorizonFade(world: World, partialTicks: Float, frameWidth: Int, frameHeight: Int) {
        val program = ensureFogProgram()
        if (program <= 0) return

        val entity: Entity = mc.renderViewEntity ?: return
        // FOV：用 fovSetting 近似（映射表中 getFOVModifier 为 private）；疾跑 FOV 变化
        // 引起的雾化带角度误差可忽略。复刻 setupCameraTransform：fovy = FOV * h / w
        val fov = mc.gameSettings.fovSetting
        val fovy = fov * frameHeight / frameWidth
        val tanHalf = tan(Math.toRadians(fovy / 2.0)).toFloat()
        val pitch = MathHelper.wrapAngleTo180_float(
            entity.prevRotationPitch + (entity.rotationPitch - entity.prevRotationPitch) * partialTicks
        )
        val fog = fogColor(world, partialTicks)

        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0)
        GlStateManager.enableBlend()
        GL20.glUseProgram(program)
        uniform1(program, "u_pitch", Math.toRadians(pitch.toDouble()).toFloat())
        uniform1(program, "u_tanHalf", tanHalf)
        uniform1(program, "u_strength", 1f)
        uniform3(program, "u_fogColor", fog.xCoord.toFloat(), fog.yCoord.toFloat(), fog.zCoord.toFloat())
        uniform2(program, "u_resolution", frameWidth.toFloat(), frameHeight.toFloat())
        drawFullscreenQuad()
        // blend 关闭交由外层 finally（blendWasEnabled 语义保持），此处无需重复处理
    }

    /** 地形雾色（与 EntityRenderer.setupFog 同源），0..1 RGB */
    private fun fogColor(world: World, partialTicks: Float): Vec3 = world.getFogColor(partialTicks)

    // ---- uniforms / 编译 ----

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

    /** 懒编译雾化带着色器；-1 = 失败缓存 */
    private fun ensureFogProgram(): Int {
        if (fogCompiled) return fogProgram
        fogCompiled = true
        fogProgram = try {
            val fragSource = ShaderSky::class.java.getResourceAsStream(FOG_RESOURCE)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            } ?: return -1
            val vertex = GL20.glCreateShader(GL20.GL_VERTEX_SHADER)
            val pixel = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER)
            GL20.glShaderSource(vertex, FOG_VERTEX)
            GL20.glShaderSource(pixel, fragSource)
            GL20.glCompileShader(vertex)
            GL20.glCompileShader(pixel)
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
            linked
        } catch (e: Exception) {
            -1
        }
        return fogProgram
    }
}
