package net.airplus.utils.render

import net.airplus.utils.render.shader.ShaderSupport
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.client.shader.Framebuffer
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL11.*
import kotlin.math.abs
import kotlin.math.exp

/**
 * Rise GaussianBlurShader 忠实移植 —— Client BlurMode = "Rise" 专用。
 *
 * 与 InternalBlurShader（程序化高斯函数采样）不同，本实现与 Rise 完全一致：
 * - 高斯核由 CPU 端按 Rise GaussianKernel 公式预先算好（sigma = radius / 2，
 *   w[i] = exp(-0.5 * (i/sigma)^2) / (sigma * 2.5066283)，对称 x2 后归一化），
 *   以 uniform 数组 u_kernel 传入，shader 端不做任何指数运算；
 * - 水平 pass 的采样方向为 (compression, 0)、垂直 pass 为 (0, compression) ——
 *   compression（BlurCompression 设置）直接拉伸采样间距：同样的核权重下，
 *   compression 越大相邻样本拉得越开，模糊在视觉上更"散"（Rise 的独有观感）；
 * - 总触达半径 = radius * compression 屏幕像素（Rise 同款）。
 *
 * 管线：源（主 framebuffer 或调用方指定的基准快照纹理）→ 水平 pass → 输出 FBO
 * → 垂直 pass → pong FBO，不写回主 framebuffer，由 HudBlur 把结果纹理按组件
 * 遮罩裁剪贴回（与 Kawase/InternalBlur 同架构）。FBO 为半分辨率（uv 归一化采样
 * 与分辨率无关，texelSize 与 Rise 一样按全屏像素计算，屏幕空间模糊量完全一致）。
 *
 * 输出 alpha 强制为 1：与 Kawase/InternalBlur 管线一致，避免主 framebuffer 的
 * alpha 被 HUD 混合写低后贴回出现半透明双影。
 *
 * 状态纪律与 InternalBlurShader 完全同款：FBO 无 depth；矩阵 glGetFloatv 保存恢复；
 * blend/alpha/colorMask/depthMask 裸 GL 写入 + GlStateManager 缓存两步法恢复；
 * stencil 开关恢复；纹理 WRAP 记录归还；unit-0 绑定与当前颜色两步法。
 *
 * 铁律：shader 字符串必须纯 ASCII —— LWJGL2 的 glShaderSource(CharSequence) 会把
 * 非 ASCII 字符编码为字节 0x1A（DOS EOF 标记），NVIDIA 编译器读到这里直接截断导致
 * 编译失败。说明只能写在 Kotlin 注释里，绝不能写进 shader 字符串。
 */
object RiseBlurShader {
    private val mc = Minecraft.getMinecraft()

    private var outputFramebuffer: Framebuffer? = null
    private var pongFramebuffer: Framebuffer? = null
    private var shaderProgramID: Int = -1
    private var uniformTextureLocation = -1
    private var uniformTexelSizeLocation = -1
    private var uniformDirectionLocation = -1
    private var uniformRadiusLocation = -1
    private var uniformKernelLocation = -1

    /** 当前已上传的高斯核半径（-1 = 未初始化）；半径变化时重算核并重新上传 */
    private var kernelRadius = -1
    private val kernelWeights = FloatArray(MAX_RADIUS)
    private val kernelUpload = BufferUtils.createFloatBuffer(MAX_RADIUS)

    private const val MAX_RADIUS = 30

    /**
     * 全屏 Rise 高斯模糊（水平 + 垂直各一次 pass）。
     *
     * @param radius 高斯核半径（Rise Interface.blurRadius，1..30）
     * @param compression 采样间距压缩系数（Rise Interface.blurCompression，默认 3.0）
     * @param sourceTexture 非 null 时以该纹理为模糊源（HudBlur 基准快照）；null = 主 framebuffer
     * @return 模糊结果纹理 id（半分辨率，GL_LINEAR 上采样）；着色器不可用时返回 -1
     */
    fun blurFullScreen(radius: Float, compression: Float, sourceTexture: Int? = null): Int {
        ensureShaderInitialized()
        if (shaderProgramID <= 0) return -1

        val sr = ScaledResolution(mc)
        val bufferW = (mc.displayWidth / 2).coerceAtLeast(1)
        val bufferH = (mc.displayHeight / 2).coerceAtLeast(1)
        ensureFramebuffer(bufferW, bufferH)
        val outputA = outputFramebuffer ?: return -1
        val outputB = pongFramebuffer ?: return -1
        val mainBuffer = mc.framebuffer

        // 保存真实 GL 状态 + GlStateManager 缓存
        val scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
        val blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND)
        val alphaEnabled = GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
        val stencilEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST)
        val depthMaskPrev = GL11.glGetInteger(GL11.GL_DEPTH_WRITEMASK) == GL11.GL_TRUE
        colorMaskBuf.clear()
        GL11.glGetInteger(GL11.GL_COLOR_WRITEMASK, colorMaskBuf)
        currentColorBuf.clear()
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, currentColorBuf)
        val prevColorR = currentColorBuf[0]; val prevColorG = currentColorBuf[1]
        val prevColorB = currentColorBuf[2]; val prevColorA = currentColorBuf[3]
        val activeTexturePrev = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        OpenGlHelper.setActiveTexture(GL13.GL_TEXTURE0)
        textureBindingPrev = GL11.glGetInteger(0x8069)
        touchedWraps.clear()

        // 全屏矩阵（glGetFloatv 方式，不占用矩阵栈）
        projMatrixBuf.clear()
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projMatrixBuf)
        modelMatrixBuf.clear()
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelMatrixBuf)
        val prevMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE)
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glLoadIdentity()
        GL11.glOrtho(0.0, sr.scaledWidth_double, sr.scaledHeight_double, 0.0, 1000.0, 3000.0)
        GL11.glTranslated(0.0, 0.0, -2000.0)
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glLoadIdentity()

        try {
            GL11.glDisable(GL11.GL_STENCIL_TEST)
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            GlStateManager.disableBlend()
            GL11.glDisable(GL_ALPHA_TEST)
            GlStateManager.disableAlpha()
            // colorMask 强制开启必须用裸 GL（缓存守卫会吞掉真实状态失步时的写入）
            GL11.glColorMask(true, true, true, true)
            GlStateManager.depthMask(true)

            GL20.glUseProgram(shaderProgramID)
            GL20.glUniform1i(uniformTextureLocation, 0)
            // texelSize 与 Rise 一致按全屏像素计算（uv 归一化，与 FBO 分辨率无关）：
            // 总触达 = radius * compression 全屏像素
            GL20.glUniform2f(uniformTexelSizeLocation, 1.0f / mc.displayWidth, 1.0f / mc.displayHeight)
            uploadKernel(radius.toInt().coerceIn(1, MAX_RADIUS))

            // Pass 1：水平模糊 source → A（Rise：u_direction = (compression, 0)）
            outputA.framebufferClear()
            outputA.bindFramebuffer(true)
            bindSourceAndClamp(sourceTexture ?: mainBuffer.framebufferTexture)
            GL20.glUniform2f(uniformDirectionLocation, compression, 0.0f)
            GlStateManager.color(1f, 1f, 1f, 1f)
            drawQuads(sr)

            // Pass 2：垂直模糊 A → B（Rise：u_direction = (0, compression)）
            outputB.framebufferClear()
            outputB.bindFramebuffer(true)
            bindSourceAndClamp(outputA.framebufferTexture)
            GL20.glUniform2f(uniformDirectionLocation, 0.0f, compression)
            drawQuads(sr)

            GL20.glUseProgram(0)
        } finally {
            GL20.glUseProgram(0)

            GL11.glMatrixMode(GL11.GL_PROJECTION)
            projMatrixBuf.rewind(); GL11.glLoadMatrix(projMatrixBuf)
            GL11.glMatrixMode(GL11.GL_MODELVIEW)
            modelMatrixBuf.rewind(); GL11.glLoadMatrix(modelMatrixBuf)
            GL11.glMatrixMode(prevMatrixMode)

            mainBuffer.bindFramebuffer(true)

            // 归还被改掉的 WRAP：恢复参数需要先把对应纹理重新绑回 unit 0
            for ((tex, wrap) in touchedWraps) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex)
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, wrap.first)
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, wrap.second)
            }

            // unit-0 绑定两步法
            GlStateManager.bindTexture(0)
            GlStateManager.bindTexture(textureBindingPrev)
            OpenGlHelper.setActiveTexture(activeTexturePrev)
            GL13.glActiveTexture(activeTexturePrev)

            // 当前颜色两步法
            GlStateManager.color(0f, 0f, 0f, 0f)
            GlStateManager.color(prevColorR, prevColorG, prevColorB, prevColorA)

            // colorMask 两步法恢复
            val m0 = colorMaskBuf[0] == GL11.GL_TRUE; val m1 = colorMaskBuf[1] == GL11.GL_TRUE
            val m2 = colorMaskBuf[2] == GL11.GL_TRUE; val m3 = colorMaskBuf[3] == GL11.GL_TRUE
            GlStateManager.colorMask(!m0, !m1, !m2, !m3)
            GlStateManager.colorMask(m0, m1, m2, m3)
            GlStateManager.depthMask(depthMaskPrev)
            if (blendEnabled) GlStateManager.enableBlend() else GlStateManager.disableBlend()
            if (alphaEnabled) {
                GL11.glEnable(GL_ALPHA_TEST); GlStateManager.enableAlpha()
            } else {
                GL11.glDisable(GL_ALPHA_TEST); GlStateManager.disableAlpha()
            }
            if (stencilEnabled) GL11.glEnable(GL11.GL_STENCIL_TEST) else GL11.glDisable(GL11.GL_STENCIL_TEST)
            if (scissorEnabled) GL11.glEnable(GL11.GL_SCISSOR_TEST) else GL11.glDisable(GL11.GL_SCISSOR_TEST)
        }

        return outputB.framebufferTexture
    }

    /** 计算 Rise GaussianKernel 同款核并上传（半径变化时才重算）。 */
    private fun uploadKernel(radius: Int) {
        GL20.glUniform1f(uniformRadiusLocation, radius.toFloat())
        if (kernelRadius == radius) return
        kernelRadius = radius

        // Rise GaussianKernel：sigma = size / 2
        // w[i] = exp(-0.5 * (i/sigma)^2) / (sigma * 2.5066283)，对称 x2 后归一化
        val sigma = radius / 2.0f
        var total = 0f
        for (i in 0 until radius) {
            val x = i / sigma
            kernelWeights[i] = (1.0f / (abs(sigma) * 2.5066283f)) * exp(-0.5 * x.toDouble() * x).toFloat()
            total += if (i > 0) kernelWeights[i] * 2f else kernelWeights[0]
        }
        for (i in 0 until radius) kernelWeights[i] /= total

        kernelUpload.clear()
        kernelUpload.put(kernelWeights, 0, radius)
        kernelUpload.flip()
        GL20.glUniform1(uniformKernelLocation, kernelUpload)
    }

    private val projMatrixBuf = BufferUtils.createFloatBuffer(16)
    private val modelMatrixBuf = BufferUtils.createFloatBuffer(16)
    private val colorMaskBuf = BufferUtils.createIntBuffer(16)
    private val currentColorBuf = BufferUtils.createFloatBuffer(16)
    private var textureBindingPrev = 0

    /** 被改成 CLAMP_TO_EDGE 的纹理 id → 原 (WRAP_S, WRAP_T)，finally 恢复用 */
    private val touchedWraps = HashMap<Int, Pair<Int, Int>>()

    private fun bindSourceAndClamp(texId: Int) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId)
        if (!touchedWraps.containsKey(texId)) {
            touchedWraps[texId] =
                GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S) to
                    GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T)
        }
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 33071)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 33071)
    }

    private fun ensureShaderInitialized() {
        if (shaderProgramID != -1) return

        if (!ShaderSupport.isSupported) {
            shaderProgramID = 0
            return
        }

        try {
            val vertexShaderSrc = "#version 120\nvoid main() { gl_TexCoord[0] = gl_MultiTexCoord0; gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex; }"
            // Rise blur.frag 同款核采样（u_kernel 预计算权重），裁掉遮罩分支：
            // airplus 的遮罩由 HudBlur 在贴回阶段用 stencil 完成，全屏模糊不需要
            // u_other_sampler。常量循环上界 + 动态 break（全 GL 实现安全）。
            // 输出 alpha 强制 1（理由见类注释）。
            // 铁律：本字符串必须纯 ASCII（LWJGL2 非 ASCII 低字节编码坑）
            val fragmentShaderSrc = """
                #version 120
                uniform sampler2D textureIn;
                uniform vec2 texelSize;
                uniform vec2 direction;
                uniform float radius;
                uniform float kernel[33];
                const int MAX_RANGE = 32;

                void main() {
                    vec2 uv = gl_TexCoord[0].xy;
                    vec4 sum = texture2D(textureIn, uv) * kernel[0];
                    for (int i = 1; i <= MAX_RANGE; i++) {
                        if (float(i) > radius) break;
                        vec2 offset = float(i) * texelSize * direction;
                        sum += texture2D(textureIn, uv - offset) * kernel[i];
                        sum += texture2D(textureIn, uv + offset) * kernel[i];
                    }
                    gl_FragColor = vec4(sum.rgb, 1.0);
                }
            """.trimIndent()
            val vID = createShader(vertexShaderSrc, GL20.GL_VERTEX_SHADER)
            val fID = createShader(fragmentShaderSrc, GL20.GL_FRAGMENT_SHADER)
            if (vID == 0 || fID == 0) {
                shaderProgramID = 0
                return
            }
            shaderProgramID = GL20.glCreateProgram()
            GL20.glAttachShader(shaderProgramID, vID)
            GL20.glAttachShader(shaderProgramID, fID)
            GL20.glLinkProgram(shaderProgramID)

            if (GL20.glGetProgrami(shaderProgramID, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                GL20.glDeleteProgram(shaderProgramID)
                shaderProgramID = 0
                System.err.println("[RiseBlurShader] 着色器链接失败，已禁用 Rise 模糊效果")
                return
            }

            GL20.glDetachShader(shaderProgramID, vID)
            GL20.glDetachShader(shaderProgramID, fID)
            GL20.glDeleteShader(vID)
            GL20.glDeleteShader(fID)
            GL20.glUseProgram(shaderProgramID)
            uniformTextureLocation = GL20.glGetUniformLocation(shaderProgramID, "textureIn")
            uniformTexelSizeLocation = GL20.glGetUniformLocation(shaderProgramID, "texelSize")
            uniformDirectionLocation = GL20.glGetUniformLocation(shaderProgramID, "direction")
            uniformRadiusLocation = GL20.glGetUniformLocation(shaderProgramID, "radius")
            uniformKernelLocation = GL20.glGetUniformLocation(shaderProgramID, "kernel[0]")
            GL20.glUseProgram(0)
        } catch (e: Exception) {
            shaderProgramID = 0
            System.err.println("[RiseBlurShader] Shader initialization failed (unsupported platform?): ${e.message}")
        }
    }

    private fun ensureFramebuffer(w: Int, h: Int) {
        if (outputFramebuffer == null || outputFramebuffer!!.framebufferWidth != w || outputFramebuffer!!.framebufferHeight != h) {
            outputFramebuffer?.deleteFramebuffer()
            outputFramebuffer = Framebuffer(w, h, false)
            outputFramebuffer!!.setFramebufferFilter(9729)
        }
        if (pongFramebuffer == null || pongFramebuffer!!.framebufferWidth != w || pongFramebuffer!!.framebufferHeight != h) {
            pongFramebuffer?.deleteFramebuffer()
            pongFramebuffer = Framebuffer(w, h, false)
            pongFramebuffer!!.setFramebufferFilter(9729)
        }
    }

    /** 编译单个 shader，失败时打印信息日志并返回 0 */
    private fun createShader(src: String, type: Int): Int {
        val id = GL20.glCreateShader(type)
        GL20.glShaderSource(id, src)
        GL20.glCompileShader(id)
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            System.err.println("[RiseBlurShader] Rise 模糊着色器编译失败: ${GL20.glGetShaderInfoLog(id, 4096)}")
            GL20.glDeleteShader(id)
            return 0
        }
        return id
    }

    fun cleanup() {
        if (shaderProgramID > 0) {
            GL20.glDeleteProgram(shaderProgramID)
        }
        shaderProgramID = -1
        uniformTextureLocation = -1
        uniformTexelSizeLocation = -1
        uniformDirectionLocation = -1
        uniformRadiusLocation = -1
        uniformKernelLocation = -1
        kernelRadius = -1
        outputFramebuffer?.deleteFramebuffer()
        outputFramebuffer = null
        pongFramebuffer?.deleteFramebuffer()
        pongFramebuffer = null
    }

    private fun drawQuads(sr: ScaledResolution) {
        val w = sr.scaledWidth_double
        val h = sr.scaledHeight_double
        glBegin(GL_QUADS)
        glTexCoord2f(0f, 1f); glVertex2d(0.0, 0.0)
        glTexCoord2f(0f, 0f); glVertex2d(0.0, h)
        glTexCoord2f(1f, 0f); glVertex2d(w, h)
        glTexCoord2f(1f, 1f); glVertex2d(w, 0.0)
        glEnd()
    }
}
