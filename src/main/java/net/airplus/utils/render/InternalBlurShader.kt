package net.airplus.utils.render

import net.airplus.utils.render.shader.ShaderSupport
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.client.shader.Framebuffer
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL11.*

object InternalBlurShader {
    private val mc = Minecraft.getMinecraft()
    private var blurOutputFramebuffer: Framebuffer? = null
    private var blurPongFramebuffer: Framebuffer? = null
    private var shaderProgramID: Int = -1
    private var uniformTextureLocation = -1
    private var uniformTexelSizeLocation = -1
    private var uniformDirectionLocation = -1
    private var uniformRadiusLocation = -1

    /**
     * 全屏 N 轮两 pass 分离高斯，在 **1/2 分辨率**的离屏 FBO 上进行：
     * 源（主 framebuffer 或调用方指定纹理，linear 采样半分辨率下采样）→ 输出 FBO（水平）
     * → ping FBO（垂直），rounds > 1 时以上一轮的结果为源再重复 H/V 两 pass
     * （Dual 模式 = rounds=2）。**不写回主 framebuffer**，之后由调用方（HudBlur）把
     * 结果纹理按遮罩裁剪贴回 —— HudBlur 的贴回/辉光纹理由屏幕像素坐标归一化采样
     * （u = 屏幕像素 / 屏幕宽），与纹理本身分辨率无关，半分辨率结果经 GL_LINEAR
     * 上采样后无缝对接（Kawase 链同款架构）。
     *
     * 半分辨率是性能关键：此前全分辨率高斯 + 固定 101 次迭代循环（常量上界 + continue，
     * 即使 radius 很小 GPU 也要跑满全部迭代）× Dual 的 4 个 pass，低端 GPU 上每帧
     * 开销是 Kawase 的 10 倍以上，表现为「Dual/Better 特别卡」。现在采样在 1/2 分辨率
     * 上进行（半径按 texel 尺寸同步减半，屏幕空间模糊量不变），加上单侧循环 + 提前
     * break（range 由 radius 决定，小半径只跑实际需要的迭代），综合开销下降约一个
     * 数量级，与 Kawase 同级。
     *
     * 与 Dual/HudBlur 同款修复（此前 Better 模式"没有任何效果"）：
     * - FBO 不带 depth attachment（深度状态残留不会丢弃 pass 的写入）；
     * - 矩阵用 glGetFloatv 保存 + glLoadMatrix 恢复（glPopMatrix 全投影栈溢出风险）；
     * - 着色器逐 shader 检查编译状态，链接失败即放弃并打日志（不会静默"看起来没有效果"）；
     * - 片元循环用常量上界 + 循环内动态 break（兼容转译层）；
     * - blend/alpha/colorMask/depthMask 全部真实 GL + GlStateManager 缓存同步两步法。
     *
     * 状态泄漏修复（会顺污染调用链之后的手持物品/HUD/世界渲染）：
     * - GL_STENCIL_TEST 被关闭后此前**从不恢复**：调用方（EmbeddedStencil 等依赖残留
     *   stencil 状态的路径）后续的 stencil 写入全部被丢弃 → 遮罩失效。现在进入前
     *   保存开关状态，finally 里恢复；
     * - 纹理 WRAP_S/T 每帧被改成 GL_CLAMP_TO_EDGE(33071) 且从不恢复：vanilla FBO
     *   纹理默认 WRAP 是 GL_REPEAT(10496)，被残改后调用方后续再采样这些纹理时
     *   UV 越界行为被改变（贴图接缝/平铺错乱）。现在对每个改过的纹理先记录原 WRAP，
     *   finally 里归还；
     * - 读取源绑定改用裸 glBindTexture：Framebuffer.bindFramebufferTexture 内部走
     *   GlStateManager 缓存守卫，缓存失真时会把"必须真实发生的绑定"静默跳过
     *   （源纹理没绑上 = 采样黑/灰）；
     * - GlStateManager.color 是缓存守卫调用，退出时用「先写脏值推开缓存、再写原值」
     *   两步法把当前颜色恢复为调用方进入时的颜色，不做两步会残留 1,1,1,1 白底调制。
     *
     * @param radius 屏幕空间模糊半径（像素）；半分辨率管线内部按 texel 同步减半
     * @param rounds 高斯 H/V 轮数，不少于 1；轮数越大模糊范围越宽且逐轮集中采样
     * @param sourceTexture 非 null 时以该纹理为模糊源（HudBlur 派生强度路径传入本帧
     *        基准快照，避免直接拍摄已被前序组件污染的主 framebuffer）；null = 主 framebuffer
     * @return 模糊结果的纹理 id（半分辨率，GL_LINEAR 上采样）；着色器不可用时返回 -1
     */
    fun blurFullScreen(radius: Float, rounds: Int = 1, sourceTexture: Int? = null): Int {
        ensureShaderInitialized()
        if (shaderProgramID <= 0) return -1

        val sr = ScaledResolution(mc)
        // 半分辨率离屏链：texel = 2 屏幕像素，uniform radius 同步减半后屏幕空间 σ 不变
        val bufferW = (mc.displayWidth / 2).coerceAtLeast(1)
        val bufferH = (mc.displayHeight / 2).coerceAtLeast(1)
        ensureFramebuffer(bufferW, bufferH)
        val outputA = blurOutputFramebuffer ?: return -1
        val outputB = blurPongFramebuffer ?: return -1
        val mainBuffer = mc.framebuffer

        // 保存真实 GL 状态 + GlStateManager 缓存
        val scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
        val blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND)
        val alphaEnabled = GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
        val stencilEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST)
        val depthMaskPrev = GL11.glGetInteger(GL11.GL_DEPTH_WRITEMASK) == GL11.GL_TRUE
        colorMaskBuf.clear()
        GL11.glGetInteger(GL11.GL_COLOR_WRITEMASK, colorMaskBuf)
        // GL_CURRENT_COLOR：调用方进入时的当前颜色（光照在顶点阶段应用，不影响该属性）
        currentColorBuf.clear()
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, currentColorBuf)
        val prevColorR = currentColorBuf[0]; val prevColorG = currentColorBuf[1]
        val prevColorB = currentColorBuf[2]; val prevColorA = currentColorBuf[3]
        val activeTexturePrev = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        OpenGlHelper.setActiveTexture(GL13.GL_TEXTURE0)
        textureBindingPrev = GL11.glGetInteger(0x8069)
        // 被改 WRAP 的纹理 → 原 (WRAP_S, WRAP_T)；同帧多次调用时沿用首次记录的原值
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
            // 中间 pass 写入无 stencil 的独立 FBO，统一强制干净状态（真实 GL + 缓存同步）
            GL11.glDisable(GL11.GL_STENCIL_TEST)
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            GlStateManager.disableBlend()
            GL11.glDisable(GL_ALPHA_TEST)
            GlStateManager.disableAlpha()
            // colorMask 强制开启必须用裸 GL：GlStateManager.colorMask 是缓存守卫调用，
            // 当真实 colorMask 已被某个 raw 写入者改为 false 而缓存仍是 true 时，
            // 守卫会跳过写入 → pass 全部被静默丢弃 → 输出纹理全黑（模糊"变黑"），
            // 且驱动跳过片元着色器导致帧率假性飙升。入口已用 glGetInteger 快照真实值，
            // 这里无条件真实开启，退出时两步法同步回快照值。
            GL11.glColorMask(true, true, true, true)
            GlStateManager.depthMask(true)

            for (round in 0 until rounds.coerceAtLeast(1)) {
                // 源纹理：第 0 轮读调用方指定源（HudBlur 基准快照）或主 framebuffer，
                // 之后每轮读上一轮的结果
                val source = sourceTexture ?: mainBuffer.framebufferTexture
                val sourceThis = if (round == 0) source else outputB.framebufferTexture

                // Pass 1：水平模糊 sourceThis → A
                outputA.framebufferClear()
                outputA.bindFramebuffer(true)
                bindSourceAndClamp(sourceThis)
                GL20.glUseProgram(shaderProgramID)
                // texelSize = 半分辨率 FBO 的 texel 尺寸，radius 减半：
                // sigma = radius/2 texels × 2px/texel = 原半径/2 屏幕像素，与全分辨率管线一致
                GL20.glUniform2f(uniformTexelSizeLocation, 1.0f / bufferW, 1.0f / bufferH)
                GL20.glUniform1i(uniformTextureLocation, 0)
                GL20.glUniform1f(uniformRadiusLocation, radius * 0.5F)
                GL20.glUniform2f(uniformDirectionLocation, 1.0f, 0.0f)
                GlStateManager.color(1f, 1f, 1f, 1f)
                drawQuads()

                // Pass 2：垂直模糊 A → B
                outputB.framebufferClear()
                outputB.bindFramebuffer(true)
                bindSourceAndClamp(outputA.framebufferTexture)
                GL20.glUniform2f(uniformDirectionLocation, 0.0f, 1.0f)
                drawQuads()

                GL20.glUseProgram(0)
            }
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

            // unit-0 绑定两步法：bindTexture 缓存守卫可能指向错误值（pass 期间全程裸绑定），
            // 先绑 0 推开缓存再绑回原纹理，保证 真实 = 缓存 = 进入前的绑定
            GlStateManager.bindTexture(0)
            GlStateManager.bindTexture(textureBindingPrev)
            OpenGlHelper.setActiveTexture(activeTexturePrev)
            GL13.glActiveTexture(activeTexturePrev)

            // 当前颜色两步法：恢复为调用方进入时的颜色（缓存守卫可能吞掉单步恢复）
            GlStateManager.color(0f, 0f, 0f, 0f)
            GlStateManager.color(prevColorR, prevColorG, prevColorB, prevColorA)

            // colorMask 两步法恢复：先反向写推开可能失步的缓存守卫，再写快照值。
            // 单步守卫恢复在 缓存==快照 但 真实≠缓存 时会被跳过，真实状态就此泄漏
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
            // stencil 开关恢复：不恢复会把「stencil 写入被丢弃」泄漏给后续的遮罩重建
            if (stencilEnabled) GL11.glEnable(GL11.GL_STENCIL_TEST) else GL11.glDisable(GL11.GL_STENCIL_TEST)
            if (scissorEnabled) GL11.glEnable(GL11.GL_SCISSOR_TEST) else GL11.glDisable(GL11.GL_SCISSOR_TEST)
        }

        return outputB.framebufferTexture
    }

    private val projMatrixBuf = org.lwjgl.BufferUtils.createFloatBuffer(16)
    private val modelMatrixBuf = org.lwjgl.BufferUtils.createFloatBuffer(16)
    private val colorMaskBuf = org.lwjgl.BufferUtils.createIntBuffer(16)
    private val currentColorBuf = org.lwjgl.BufferUtils.createFloatBuffer(16)
    private var textureBindingPrev = 0

    /** 被改成 CLAMP_TO_EDGE 的纹理 id → 原 (WRAP_S, WRAP_T)，finally 恢复用 */
    private val touchedWraps = HashMap<Int, Pair<Int, Int>>()

    /**
     * 绑定采样源（裸 GL，绕过 Framebuffer.bindFramebufferTexture 的缓存守卫），
     * 首次改 WRAP 前记录原值供 finally 恢复。
     */
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

        // 平台不支持桌面版着色器（如 Android GLES）时直接标记为不可用，跳过模糊
        if (!ShaderSupport.isSupported) {
            shaderProgramID = 0
            return
        }

        try {
            val vertexShaderSrc = "#version 120\nvoid main() { gl_TexCoord[0] = gl_MultiTexCoord0; gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex; }"
            // 常量循环上界 + 循环内动态 continue：uniform 决定循环边界的写法在部分驱动/转译
            // 层上编译失败或静默无效（表现为"没有模糊效果"），对全部 GL 实现安全
            // 注意：GLSL 字符串内必须保持纯 ASCII —— LWJGL2 的 glShaderSource(CharSequence)
            // 会把所有非 ASCII 字符编码为字节 0x1A（DOS EOF 标记），NVIDIA 编译器读到这里
            // 直接按文件结尾截断（"syntax error, unexpected $end at <EOF>"），导致着色器
            // 编译失败、Better/Dual 模式静默失效。中文说明只能写在 Kotlin 注释里。
            // 输出 alpha 强制为 1：与 Kawase 管线一致（kawaseDown/kawaseUp 均输出 alpha=1）。
            // 主 framebuffer 的 alpha 会被 HUD/聊天的 SRC_ALPHA 混合写低，若原样平均，贴回时
            // （HudBlur 用 GL_SRC_ALPHA 混合）模糊区会半透明地叠上未模糊的原画面 → 双影/发虚。
            val fragmentShaderSrc = """
                #version 120
                uniform sampler2D textureIn;
                uniform vec2 texelSize;
                uniform vec2 direction;
                uniform float radius;
                const int MAX_RANGE = 50;

                float gaussian(float x, float sigma) {
                    return exp(-(x*x) / (2.0 * sigma * sigma));
                }

                void main() {
                    vec2 coord = gl_TexCoord[0].xy;
                    // center texel
                    vec4 sum = texture2D(textureIn, coord);
                    float totalWeight = 1.0;
                    int range = int(min(radius, 50.0));
                    float sigma = max(radius / 2.0, 0.5);
                    // Single-sided symmetric loop + break: iterates only to the actual
                    // range instead of always running 101 iterations (the old constant
                    // bound + continue forced the full loop even for small radius).
                    // Constant bound with dynamic break is safe on all GL implementations.
                    for (int i = 1; i <= MAX_RANGE; i++) {
                        if (float(i) > range) break;
                        float weight = gaussian(float(i), sigma);
                        vec2 offset = float(i) * texelSize * direction;
                        sum += (texture2D(textureIn, coord + offset) + texture2D(textureIn, coord - offset)) * weight;
                        totalWeight += 2.0 * weight;
                    }
                    // GLSL source must stay pure ASCII: LWJGL2's glShaderSource(CharSequence)
                    // encodes every non-ASCII char as byte 0x1A (DOS EOF marker) and NVIDIA's
                    // compiler stops right there (unexpected end-of-input error).
                    // Keep all explanations in Kotlin comments, never inside the shader string.
                    gl_FragColor = vec4(sum.rgb / max(totalWeight, 0.0001), 1.0);
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
            blurOutputFramebuffer = Framebuffer(w, h, false)
            blurOutputFramebuffer!!.setFramebufferFilter(9729)
        }
        if (blurPongFramebuffer == null || blurPongFramebuffer!!.framebufferWidth != w || blurPongFramebuffer!!.framebufferHeight != h) {
            blurPongFramebuffer?.deleteFramebuffer()
            blurPongFramebuffer = Framebuffer(w, h, false)
            blurPongFramebuffer!!.setFramebufferFilter(9729)
        }
    }

    /** 编译单个 shader，失败时打印信息日志并返回 0 */
    private fun createShader(src: String, type: Int): Int {
        val id = GL20.glCreateShader(type)
        GL20.glShaderSource(id, src)
        GL20.glCompileShader(id)
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            System.err.println("[InternalBlurShader] Better 模糊着色器编译失败: ${GL20.glGetShaderInfoLog(id, 4096)}")
            GL20.glDeleteShader(id)
            return 0
        }
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
        blurPongFramebuffer?.deleteFramebuffer()
        blurPongFramebuffer = null
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
