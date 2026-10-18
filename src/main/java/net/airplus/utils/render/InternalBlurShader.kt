package net.airplus.utils.render

import net.airplus.utils.render.shader.ShaderSupport
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.shader.Framebuffer
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL11.*

object InternalBlurShader {
    private val mc = Minecraft.getMinecraft()
    private var blurOutputFramebuffer: Framebuffer? = null
    private var shaderProgramID: Int = -1
    private var uniformTextureLocation = -1
    private var uniformTexelSizeLocation = -1
    private var uniformDirectionLocation = -1
    private var uniformRadiusLocation = -1

    fun blurArea(x: Float, y: Float, width: Float, height: Float, radius: Float) {
        ensureShaderInitialized()
        if (shaderProgramID <= 0) return  // Shader not available on this platform, skip blur

        val sr = ScaledResolution(mc)
        val factor = sr.scaleFactor
        ensureFramebuffer(mc.displayWidth, mc.displayHeight)

        // 提前校验并 return，必须在启用 scissor 之前（否则提前返回会残留 scissor 状态）
        val buffer = blurOutputFramebuffer ?: return
        val mainBuffer = mc.framebuffer

        val sX = (x * factor).toInt()
        val sY = (mc.displayHeight - (y * factor).toInt() - (height * factor).toInt())
        val sW = (width * factor).toInt()
        val sH = (height * factor).toInt()

        // 保存 scissor 状态：结束时按原样恢复，避免关闭调用方外层已开启的 scissor
        val prevScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
        glEnable(GL_SCISSOR_TEST)
        // Strictly limit scissor to blur area (no padding) to prevent blur from bleeding outside GUI bounds.
        // Texture sampling is not affected by scissor, so blur quality remains intact.
        glScissor(sX, sY, sW, sH)

        // 保存将被修改的 GL 状态，结束时恢复，避免纹理/blend 状态泄漏（奇怪的纹理）
        val prevBlend = GL11.glIsEnabled(GL11.GL_BLEND)
        val prevAlpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
        // 第一遍写入独立 FBO，不需要混合
        glDisable(GL_BLEND)
        glDisable(GL_ALPHA_TEST)

        buffer.framebufferClear()
        buffer.bindFramebuffer(true)
        mainBuffer.bindFramebufferTexture()
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 33071)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 33071)
        // 重置当前颜色，避免上一帧残留颜色把纹理调制出奇怪的颜色
        GL11.glColor4f(1f, 1f, 1f, 1f)

        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPushMatrix()
        GL11.glLoadIdentity()
        GL11.glOrtho(0.0, sr.scaledWidth_double, sr.scaledHeight_double, 0.0, 1000.0, 3000.0)
        GL11.glTranslated(0.0, 0.0, -2000.0)

        GL20.glUseProgram(shaderProgramID)
        GL20.glUniform2f(uniformTexelSizeLocation, 1.0f / mc.displayWidth, 1.0f / mc.displayHeight)
        GL20.glUniform1i(uniformTextureLocation, 0)
        GL20.glUniform1f(uniformRadiusLocation, radius)
        GL20.glUniform2f(uniformDirectionLocation, 1.0f, 0.0f)
        drawQuads()

        mainBuffer.bindFramebuffer(true)
        buffer.bindFramebufferTexture()
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 33071)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 33071)

        // 写回主 framebuffer 时开启混合以保留透明度
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDisable(GL_ALPHA_TEST)
        GL11.glColor4f(1f, 1f, 1f, 1f)

        GL20.glUniform2f(uniformDirectionLocation, 0.0f, 1.0f)
        drawQuads()

        GL20.glUseProgram(0)

        // 恢复进入时的 GL 状态（blend / alpha / 纹理绑定 / 当前颜色）
        if (prevBlend) glEnable(GL_BLEND) else glDisable(GL_BLEND)
        if (prevAlpha) glEnable(GL_ALPHA_TEST) else glDisable(GL_ALPHA_TEST)
        GlStateManager.bindTexture(0)
        GlStateManager.color(1f, 1f, 1f, 1f)

        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glPopMatrix()
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glPopMatrix()

        if (prevScissor) glEnable(GL_SCISSOR_TEST) else glDisable(GL_SCISSOR_TEST)
    }

    private fun ensureShaderInitialized() {
        if (shaderProgramID != -1) return

        // 平台不支持桌面版着色器（如 Android GLES）时直接标记为不可用，跳过模糊
        if (!ShaderSupport.isSupported) {
            shaderProgramID = 0
            return
        }

        try {
            val vertexShaderSrc = "#version 120\nvoid main() { gl_TexCoord[0] = gl_MultiTexCoord0; gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex; }"
            val fragmentShaderSrc = "#version 120\nuniform sampler2D textureIn; uniform vec2 texelSize; uniform vec2 direction; uniform float radius;\nfloat gaussian(float x, float sigma) { return exp(-(x*x) / (2.0 * sigma * sigma)); }\nvoid main() { vec2 coord = gl_TexCoord[0].xy; vec4 sum = vec4(0.0); float totalWeight = 0.0; int range = int(min(radius, 50.0)); float sigma = radius / 2.0; for (int i = -range; i <= range; i++) { float weight = gaussian(float(i), sigma); vec2 offset = float(i) * texelSize * direction; sum += texture2D(textureIn, coord + offset) * weight; totalWeight += weight; } gl_FragColor = sum / totalWeight; }"
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

            // 链接失败说明当前平台编译结果不可用，直接放弃，避免用坏程序继续渲染
            if (GL20.glGetProgrami(shaderProgramID, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                GL20.glDeleteProgram(shaderProgramID)
                shaderProgramID = 0
                System.err.println("[InternalBlurShader] 着色器链接失败，已禁用模糊效果")
                return
            }

            // 链接完成后删除 shader 对象释放资源
            GL20.glDetachShader(shaderProgramID, vID)
            GL20.glDetachShader(shaderProgramID, fID)
            GL20.glDeleteShader(vID)
            GL20.glDeleteShader(fID)
            GL20.glUseProgram(shaderProgramID)
            uniformTextureLocation = GL20.glGetUniformLocation(shaderProgramID, "textureIn")
            uniformTexelSizeLocation = GL20.glGetUniformLocation(shaderProgramID, "texelSize")
            uniformDirectionLocation = GL20.glGetUniformLocation(shaderProgramID, "direction")
            uniformRadiusLocation = GL20.glGetUniformLocation(shaderProgramID, "radius")
            GL20.glUseProgram(0)
        } catch (e: Exception) {
            shaderProgramID = 0
            System.err.println("[InternalBlurShader] Shader initialization failed (unsupported platform?): ${e.message}")
        }
    }

    private fun ensureFramebuffer(w: Int, h: Int) {
        if (blurOutputFramebuffer == null || blurOutputFramebuffer!!.framebufferWidth != w || blurOutputFramebuffer!!.framebufferHeight != h) {
            blurOutputFramebuffer?.deleteFramebuffer()
            blurOutputFramebuffer = Framebuffer(w, h, true)
            blurOutputFramebuffer!!.setFramebufferFilter(9729)
        }
    }

    private fun createShader(src: String, type: Int): Int {
        val id = GL20.glCreateShader(type)
        GL20.glShaderSource(id, src)
        GL20.glCompileShader(id)
        return id
    }

    /**
     * 释放所有 GPU 资源（Shader 程序、Framebuffer）。
     * 应在客户端关闭或 GL 上下文即将销毁时调用。
     */
    fun cleanup() {
        if (shaderProgramID != -1) {
            GL20.glDeleteProgram(shaderProgramID)
            shaderProgramID = -1
            uniformTextureLocation = -1
            uniformTexelSizeLocation = -1
            uniformDirectionLocation = -1
            uniformRadiusLocation = -1
        }
        blurOutputFramebuffer?.deleteFramebuffer()
        blurOutputFramebuffer = null
    }

    private fun drawQuads() {
        val sr = ScaledResolution(mc)
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
