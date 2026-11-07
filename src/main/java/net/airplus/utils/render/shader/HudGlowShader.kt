package net.airplus.utils.render.shader

import net.airplus.utils.client.ClientUtils
import net.airplus.utils.client.MinecraftInstance
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.shader.Framebuffer
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL20
import java.awt.Color

private const val GL_ZERO = 0
private const val GL_ONE = 1

/**
 * HUD 辉光（Rise 式形状 Bloom）—— HudBlur 的 BlurGlow pass 专用。
 *
 * 旧实现是「全屏内容 Bloom + 组件 AABB 裁剪」：每帧对预模糊快照做亮部提取 + 分离高斯，
 * 再把结果按组件 AABB（外扩 2σ）加法贴回。问题：辉光源是整块屏幕内容，AABB 内外都被
 * 亮色场景灌满，贴回区域被 scissor 硬切成方形边界，视觉效果是「组件外圈套了一个只亮
 * 一半的矩形」，而不是跟随组件形状的柔和光晕。
 *
 * 新实现参考 Rise 的 BloomShader（HUD glow）管线，光晕完全由组件遮罩形状驱动：
 * 1. 累积（[accumulate]，HudBlur.blur 内逐组件调用）：把组件遮罩形状以白色
 *    （alpha = 该组件辉光强度）画进共享的半分辨率「辉光源 FBO」——同帧所有组件
 *    的形状在同一张纹理上累积，零逐组件管线开销；
 * 2. 合成（[flush]，Render2DEvent 派发末尾统一调用，每帧最多一次）：
 *    - 水平高斯 pass：辉光源 FBO → 输出 FBO。逐样本 rgb *= alpha 消除暗边，
 *      最后 rgb /= alpha 归一化（Rise bloom.frag 同款预处理）；
 *    - 垂直高斯 pass：输出 FBO → 主 framebuffer。采样辉光源原始形状纹理，
 *      形状内部（alpha > 0）discard——光晕只画在组件外圈，元素本体内容不被罩色。
 *    高斯核为真实高斯权重（sigma = radius / 2，Rise GaussianKernel 同款），
 *    半径由 GlowRadius 驱动、采样方向拉伸由 GlowCompression 驱动（Rise Interface 同款）。
 *
 * 强度语义：形状 alpha 编码 min(intensity, 1)（淡出动画逐帧变化无需重建管线）；
 * 强度 > 100% 的部分由垂直 pass 的 rgb 亮度增益（gain uniform）补足（FBO alpha 无法超过 1）。
 *
 * 缓存：FBO 半分辨率（性能与旧 Bloom 持平），尺寸随屏幕分辨率自动重建；
 * 会话（帧）切换或 flush 后形状纹理清空，下一轮重新累积。
 *
 * 注意：GLSL 源码必须保持纯 ASCII —— LWJGL2 的 glShaderSource(CharSequence) 会把
 * 非 ASCII 字符编码为字节 0x1A（DOS EOF 标记），NVIDIA 编译器读到这里直接截断导致
 * 编译失败。说明只能写在 Kotlin 注释里，绝不能写进 shader 字符串。
 */
object HudGlowShader : MinecraftInstance {

    private const val MAX_RADIUS = 30

    private var programID: Int = -1
    private var uniformTexture = -1
    private var uniformShape = -1
    private var uniformTexelSize = -1
    private var uniformDirection = -1
    private var uniformRadius = -1
    private var uniformGain = -1
    private var uniformKernel = -1

    /** 辉光源 FBO：本帧所有组件的遮罩形状（白色 + 强度 alpha）累积于此（半分辨率，无 depth） */
    private var inputFramebuffer: Framebuffer? = null

    /** 水平高斯中间结果 FBO（半分辨率，无 depth） */
    private var outputFramebuffer: Framebuffer? = null

    /** 辉光源 FBO 是否有未合成的形状 */
    private var dirty = false

    // 一次性诊断日志开关（定位「辉光无效果」的静默失败点）
    private var firstAccumLogged = false
    private var firstFlushLogged = false
    private var resizeMismatchLogged = false

    private var kernelRadius = -1
    private val kernelWeights = FloatArray(MAX_RADIUS)
    private val kernelUpload = BufferUtils.createFloatBuffer(MAX_RADIUS)

    /**
     * 累积一个组件的辉光形状。在 HudBlur.blur 内调用：此刻调用方矩阵、viewport 均
     * 处于原状（遮罩形状与模糊区域用同一套矩阵绘制到同尺寸 FBO，像素逐一对齐），
     * 因此本函数不改动任何矩阵，直接复用调用方状态绘制 [drawShape]。
     *
     * @param session HudBlur 的帧会话号（跨会话时清空重新累积）
     * @param intensity 本组件辉光强度（0..1，含 strengthScale 淡出）
     * @param drawShape 遮罩形状绘制 lambda（与模糊遮罩同一 lambda）
     */
    fun accumulate(session: Long, intensity: Float, drawShape: () -> Unit) {
        val bufferW = (mc.displayWidth / 2).coerceAtLeast(1)
        val bufferH = (mc.displayHeight / 2).coerceAtLeast(1)
        if (!ensureFramebuffers(bufferW, bufferH)) return
        val input = inputFramebuffer ?: return

        // 只在无未合成形状时清空 —— flush 合成完会自行清空 FBO。
        // 不再按 session 清空：自定义聊天栏的形状在 Render2DEvent 派发结束（flush）之后
        // 才累积（drawChat 阶段），若下一帧首组件累积时按 session 清空，会把聊天的
        // 未合成形状直接抹掉 → 聊天辉光时有时无。flush 后清空的顺序保证所有累积形状
        // 恰好合成一次（聊天辉光滞后一帧，不可感知）。
        if (!dirty) {
            input.setFramebufferColor(0F, 0F, 0F, 0F)
            input.framebufferClear()
            dirty = true
            if (!firstAccumLogged) {
                firstAccumLogged = true
                ClientUtils.LOGGER.warn(
                    "[HudGlowShader] 诊断: accumulate 首次触发（形状开始累积, FBO={}x{}）", bufferW, bufferH
                )
            }
        }

        // 状态快照（与 HudBlur / InternalBlurShader 同款纪律）：accumulate 运行在
        // 调用方（Island 外层 stencil 作用域、TabGUI scissor 等）的任意 GL 状态之下
        val scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
        scissorBuf.clear()
        GL11.glGetInteger(GL11.GL_SCISSOR_BOX, scissorBuf)
        val stencilEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST)
        val stFunc = GL11.glGetInteger(GL11.GL_STENCIL_FUNC)
        val stRef = GL11.glGetInteger(GL11.GL_STENCIL_REF)
        val stValueMask = GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK)
        val stWriteMask = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK)
        val stFail = GL11.glGetInteger(GL11.GL_STENCIL_FAIL)
        val stZFail = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL)
        val stZPass = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS)
        val depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
        val alphaEnabled = GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
        val blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND)
        val blendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC)
        val blendDst = GL11.glGetInteger(GL11.GL_BLEND_DST)
        val blendSrcAlpha = GL11.glGetInteger(0x80CA)
        val blendDstAlpha = GL11.glGetInteger(0x80CB)
        colorMaskBuf.clear()
        GL11.glGetInteger(GL11.GL_COLOR_WRITEMASK, colorMaskBuf)
        currentColorBuf.clear()
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, currentColorBuf)
        val prevColorR = currentColorBuf[0]; val prevColorG = currentColorBuf[1]
        val prevColorB = currentColorBuf[2]; val prevColorA = currentColorBuf[3]
        val activeTexturePrev = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        val textureBindingPrev = GL11.glGetInteger(0x8069)

        try {
            input.bindFramebuffer(true)
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            GL11.glDisable(GL11.GL_STENCIL_TEST)
            GL11.glDisable(GL11.GL_DEPTH_TEST)
            GL11.glDisable(GL11.GL_ALPHA_TEST)
            GL11.glEnable(GL11.GL_BLEND)
            // RGB 标准混合；alpha 通道按覆盖度累积（重叠形状亮度不平方饱和）
            GL14.glBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA
            )
            GL11.glColorMask(true, true, true, true)
            GL11.glColor4f(1F, 1F, 1F, intensity.coerceIn(0F, 1F))
            drawShape()
        } finally {
            mc.framebuffer.bindFramebuffer(true)

            // unit-0 绑定两步法（drawShape 可能改绑纹理，如 drawRoundedRect 的 SDF 贴图）
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureBindingPrev)
            GL13.glActiveTexture(activeTexturePrev)

            // 当前颜色两步法
            GL11.glColor4f(0F, 0F, 0F, 0F)
            GL11.glColor4f(prevColorR, prevColorG, prevColorB, prevColorA)

            // colorMask 两步法恢复
            val m0 = colorMaskBuf[0] == GL11.GL_TRUE; val m1 = colorMaskBuf[1] == GL11.GL_TRUE
            val m2 = colorMaskBuf[2] == GL11.GL_TRUE; val m3 = colorMaskBuf[3] == GL11.GL_TRUE
            GL11.glColorMask(!m0, !m1, !m2, !m3)
            GL11.glColorMask(m0, m1, m2, m3)

            // blend 因数两步法（try 里改过真实因数，先写脏值推开缓存守卫）
            GlStateManager.tryBlendFuncSeparate(GL_ZERO, GL_ONE, GL_ZERO, GL_ONE)
            GlStateManager.tryBlendFuncSeparate(
                blendSrc, blendDst, blendSrcAlpha, blendDstAlpha
            )
            if (blendEnabled) {
                GlStateManager.disableBlend()
                GlStateManager.enableBlend()
            } else {
                GlStateManager.enableBlend()
                GlStateManager.disableBlend()
            }
            if (alphaEnabled) GL11.glEnable(GL11.GL_ALPHA_TEST) else GL11.glDisable(GL11.GL_ALPHA_TEST)
            if (depthEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST) else GL11.glDisable(GL11.GL_DEPTH_TEST)
            // stencil：先恢复 op/func/writemask 再按原开关状态还原测试
            GL11.glStencilOp(stFail, stZFail, stZPass)
            GL11.glStencilFunc(stFunc, stRef, stValueMask)
            GL11.glStencilMask(stWriteMask)
            if (stencilEnabled) GL11.glEnable(GL11.GL_STENCIL_TEST) else GL11.glDisable(GL11.GL_STENCIL_TEST)
            GL11.glScissor(scissorBuf[0], scissorBuf[1], scissorBuf[2], scissorBuf[3])
            if (scissorEnabled) GL11.glEnable(GL11.GL_SCISSOR_TEST) else GL11.glDisable(GL11.GL_SCISSOR_TEST)
        }
    }

    /**
     * 合成阶段：对本轮累积的形状纹理做 水平 + 垂直 两 pass 高斯，并把垂直结果
     * 以标准 alpha 混合贴回主 framebuffer（形状内部 discard，只画外圈光晕）。
     * 在 Render2DEvent 派发末尾（所有 HUD 组件绘制完毕后）调用，每帧最多一次。
     *
     * @param glowColor 辉光颜色（以 RGB 为准，alpha 通道不参与）
     * @param radius Rise Bloom 高斯核半径（GlowRadius，1..30；sigma = radius / 2）
     * @param compression 采样间距压缩系数（GlowCompression，水平/垂直 pass 的
     *        采样方向 = (compression, 0) / (0, compression)，Rise Interface.bloomCompression 同款）
     * @param gain 亮度增益（强度 > 100% 时补足 FBO alpha 封顶损失的亮度，通常 >= 1）
     */
    fun flush(glowColor: Color, radius: Float, compression: Float, gain: Float = 1F) {
        if (!dirty) return
        dirty = false

        val input = inputFramebuffer ?: return
        val output = outputFramebuffer ?: return
        val bufferW = (mc.displayWidth / 2).coerceAtLeast(1)
        val bufferH = (mc.displayHeight / 2).coerceAtLeast(1)
        // 中途 resize（FBO 与当前屏幕不匹配）→ 本轮形状作废，下轮重新累积
        if (input.framebufferWidth != bufferW || input.framebufferHeight != bufferH) {
            if (!resizeMismatchLogged) {
                resizeMismatchLogged = true
                ClientUtils.LOGGER.warn(
                    "[HudGlowShader] 诊断: flush 时 FBO 尺寸不匹配（{}x{} != {}x{}），本轮形状作废",
                    input.framebufferWidth, input.framebufferHeight, bufferW, bufferH
                )
            }
            return
        }
        if (!ensureInitialized()) {
            if (!firstFlushLogged) {
                firstFlushLogged = true
                ClientUtils.LOGGER.warn("[HudGlowShader] 诊断: flush 因着色器不可用被跳过（详见上方编译/链接失败日志）")
            }
            return
        }

        ensureKernel(radius.toInt().coerceIn(1, MAX_RADIUS))

        val sr = ScaledResolution(mc)

        // 状态快照（flush 跑在事件派发末尾，外部状态未知，全量保存恢复）
        val scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
        scissorBuf.clear()
        GL11.glGetInteger(GL11.GL_SCISSOR_BOX, scissorBuf)
        val stencilEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST)
        val depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
        val depthMaskPrev = GL11.glGetInteger(GL11.GL_DEPTH_WRITEMASK) == GL11.GL_TRUE
        val alphaEnabled = GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
        val blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND)
        val blendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC)
        val blendDst = GL11.glGetInteger(GL11.GL_BLEND_DST)
        val blendSrcAlpha = GL11.glGetInteger(0x80CA)
        val blendDstAlpha = GL11.glGetInteger(0x80CB)
        colorMaskBuf.clear()
        GL11.glGetInteger(GL11.GL_COLOR_WRITEMASK, colorMaskBuf)
        currentColorBuf.clear()
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, currentColorBuf)
        val prevColorR = currentColorBuf[0]; val prevColorG = currentColorBuf[1]
        val prevColorB = currentColorBuf[2]; val prevColorA = currentColorBuf[3]

        val prevMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE)
        projMatrixBuf.clear()
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projMatrixBuf)
        modelMatrixBuf.clear()
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelMatrixBuf)
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glLoadIdentity()
        GL11.glOrtho(0.0, sr.scaledWidth_double, sr.scaledHeight_double, 0.0, 1000.0, 3000.0)
        GL11.glTranslated(0.0, 0.0, -2000.0)
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glLoadIdentity()

        val activeTexturePrev = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        val textureBindingPrev = GL11.glGetInteger(0x8069)
        GL13.glActiveTexture(GL13.GL_TEXTURE1)
        val textureBindingPrev1 = GL11.glGetInteger(0x8069)

        try {
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            GL11.glDisable(GL11.GL_STENCIL_TEST)
            GL11.glDisable(GL11.GL_DEPTH_TEST)
            GL11.glDisable(GL11.GL_ALPHA_TEST)
            GL11.glDisable(GL11.GL_BLEND)
            GL11.glColorMask(true, true, true, true)
            GL11.glDepthMask(true)

            GL20.glUseProgram(programID)
            GL20.glUniform1i(uniformTexture, 0)
            GL20.glUniform1i(uniformShape, 1)
            // texelSize 按全屏像素计算（Rise 同款；uv 归一化采样与 FBO 分辨率无关），
            // 采样方向 = (compression, 0) / (0, compression)，总触达 = radius * compression px
            GL20.glUniform2f(uniformTexelSize, 1.0f / mc.displayWidth, 1.0f / mc.displayHeight)
            GL20.glUniform1f(uniformRadius, kernelRadius.toFloat())
            kernelUpload.clear()
            kernelUpload.put(kernelWeights, 0, kernelRadius)
            kernelUpload.flip()
            GL20.glUniform1(uniformKernel, kernelUpload)

            bindGlowTextures(input.framebufferTexture, input.framebufferTexture)

            // Pass 1：水平高斯（形状纹理 input → output，覆写渲染）
            output.setFramebufferColor(0F, 0F, 0F, 0F)
            output.framebufferClear()
            output.bindFramebuffer(true)
            GL20.glUniform2f(uniformDirection, compression, 0.0f)
            GL20.glUniform1f(uniformGain, 1F)
            GL11.glColor4f(1F, 1F, 1F, 1F)
            drawFullscreenQuad(sr)

            // Pass 2：垂直高斯 + 贴回主 framebuffer（标准 alpha 混合，颜色 = 辉光染色）
            mc.framebuffer.bindFramebuffer(true)
            bindGlowTextures(output.framebufferTexture, input.framebufferTexture)
            GL20.glUniform2f(uniformDirection, 0.0f, compression)
            GL20.glUniform1f(uniformGain, gain.coerceIn(1F, 10F))
            GL11.glEnable(GL11.GL_BLEND)
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
            GL11.glColor4f(
                glowColor.red / 255F,
                glowColor.green / 255F,
                glowColor.blue / 255F,
                1F
            )
            drawFullscreenQuad(sr)

            GL20.glUseProgram(0)

            // 合成完成：清空辉光源 FBO，让下一次累积从干净状态开始。顺序重要 ——
            // accumulate 侧不再按 session 清空（见 accumulate 注释），此处是唯一的
            // 清空点；聊天栏等晚段累积的形状会在下一轮派发末尾被合成，恰好各一次。
            input.setFramebufferColor(0F, 0F, 0F, 0F)
            input.framebufferClear()

            if (!firstFlushLogged) {
                firstFlushLogged = true
                ClientUtils.LOGGER.warn(
                    "[HudGlowShader] 诊断: flush 首次合成完成（taps={}, gain={}, 全链路通畅）",
                    kernelRadius, gain.coerceIn(1F, 10F)
                )
            }
        } finally {
            GL20.glUseProgram(0)
            mc.framebuffer.bindFramebuffer(true)

            GL11.glMatrixMode(GL11.GL_PROJECTION)
            projMatrixBuf.rewind(); GL11.glLoadMatrix(projMatrixBuf)
            GL11.glMatrixMode(GL11.GL_MODELVIEW)
            modelMatrixBuf.rewind(); GL11.glLoadMatrix(modelMatrixBuf)
            GL11.glMatrixMode(prevMatrixMode)

            // unit-0 / unit-1 绑定还原（两步法推开缓存守卫）
            GL13.glActiveTexture(GL13.GL_TEXTURE1)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureBindingPrev1)
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureBindingPrev)
            GL13.glActiveTexture(activeTexturePrev)

            // 当前颜色两步法
            GL11.glColor4f(0F, 0F, 0F, 0F)
            GL11.glColor4f(prevColorR, prevColorG, prevColorB, prevColorA)

            // colorMask 两步法恢复
            val m0 = colorMaskBuf[0] == GL11.GL_TRUE; val m1 = colorMaskBuf[1] == GL11.GL_TRUE
            val m2 = colorMaskBuf[2] == GL11.GL_TRUE; val m3 = colorMaskBuf[3] == GL11.GL_TRUE
            GL11.glColorMask(!m0, !m1, !m2, !m3)
            GL11.glColorMask(m0, m1, m2, m3)
            GL11.glDepthMask(depthMaskPrev)

            // blend 开关与因数两步法还原
            GlStateManager.tryBlendFuncSeparate(GL_ZERO, GL_ONE, GL_ZERO, GL_ONE)
            GlStateManager.tryBlendFuncSeparate(
                blendSrc, blendDst, blendSrcAlpha, blendDstAlpha
            )
            if (blendEnabled) {
                GlStateManager.disableBlend()
                GlStateManager.enableBlend()
            } else {
                GlStateManager.enableBlend()
                GlStateManager.disableBlend()
            }
            if (alphaEnabled) GL11.glEnable(GL11.GL_ALPHA_TEST) else GL11.glDisable(GL11.GL_ALPHA_TEST)
            if (depthEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST) else GL11.glDisable(GL11.GL_DEPTH_TEST)
            if (stencilEnabled) GL11.glEnable(GL11.GL_STENCIL_TEST) else GL11.glDisable(GL11.GL_STENCIL_TEST)
            GL11.glScissor(scissorBuf[0], scissorBuf[1], scissorBuf[2], scissorBuf[3])
            if (scissorEnabled) GL11.glEnable(GL11.GL_SCISSOR_TEST) else GL11.glDisable(GL11.GL_SCISSOR_TEST)
        }
    }

    /** 绑定 bloom 采样源（unit0 = 模糊结果，unit1 = 形状纹理），裸 GL。 */
    private fun bindGlowTextures(diffuseTex: Int, shapeTex: Int) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, diffuseTex)
        GL13.glActiveTexture(GL13.GL_TEXTURE1)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, shapeTex)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
    }

    private fun drawFullscreenQuad(sr: ScaledResolution) {
        val w = sr.scaledWidth_double
        val h = sr.scaledHeight_double
        GL11.glBegin(GL11.GL_QUADS)
        GL11.glTexCoord2f(0f, 1f); GL11.glVertex2d(0.0, 0.0)
        GL11.glTexCoord2f(0f, 0f); GL11.glVertex2d(0.0, h)
        GL11.glTexCoord2f(1f, 0f); GL11.glVertex2d(w, h)
        GL11.glTexCoord2f(1f, 1f); GL11.glVertex2d(w, 0.0)
        GL11.glEnd()
    }

    /** 计算归一化高斯核（sigma = radius / 2，Rise GaussianKernel 同款公式）。 */
    private fun ensureKernel(radius: Int) {
        if (radius == kernelRadius) return
        kernelRadius = radius
        val sigma = radius / 2.0
        var sum = 0.0
        val raw = DoubleArray(radius)
        for (i in 0 until radius) {
            val x = i / sigma
            raw[i] = 1.0 / (sigma * 2.5066283) * kotlin.math.exp(-0.5 * x * x)
            sum += if (i > 0) raw[i] * 2.0 else raw[0]
        }
        kernelWeights.fill(0f)
        for (i in 0 until radius) kernelWeights[i] = (raw[i] / sum).toFloat()
    }

    private fun ensureFramebuffers(w: Int, h: Int): Boolean {
        if (inputFramebuffer == null || inputFramebuffer!!.framebufferWidth != w || inputFramebuffer!!.framebufferHeight != h) {
            inputFramebuffer?.deleteFramebuffer()
            inputFramebuffer = Framebuffer(w, h, false).also {
                it.setFramebufferFilter(9729)
                it.setFramebufferColor(0F, 0F, 0F, 0F)
            }
            dirty = false
        }
        if (outputFramebuffer == null || outputFramebuffer!!.framebufferWidth != w || outputFramebuffer!!.framebufferHeight != h) {
            outputFramebuffer?.deleteFramebuffer()
            outputFramebuffer = Framebuffer(w, h, false).also {
                it.setFramebufferFilter(9729)
                it.setFramebufferColor(0F, 0F, 0F, 0F)
            }
        }
        return inputFramebuffer != null && outputFramebuffer != null
    }

    // ===== 着色器 =====

    private val vertexSrc =
        "#version 120\nvoid main() { gl_TexCoord[0] = gl_MultiTexCoord0; gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex; }"

    // Rise bloom.frag 同款：逐样本 rgb *= alpha 消除暗边，末端 rgb /= alpha 归一化；
    // 垂直 pass（direction.x == 0）采样形状纹理，形状内部（alpha > 0）discard，
    // 光晕只画在组件外圈。采样循环用常量上界 + 动态 break（全 GL 实现安全）。
    // gl_Color：pass1 glColor = (1,1,1,1) 不受影响；pass2 应用 Client.GlowColor 染色。
    // gain：强度 > 100% 的亮度增益（FBO alpha 上限为 1，超出部分由 rgb 乘子补足）。
    // 铁律：shader 字符串内绝不能出现非 ASCII 字符 —— LWJGL2 glShaderSource 按低字节
    // 编码（中文 → 任意 ASCII 字节，含 CR/LF），会咬断 // 注释行产生碎片代码，
    // NVIDIA 编译器报 C1503 undefined variable "P" → 编译失败 → 辉光静默消失（曾踩坑）。
    private val fragmentSrc = """
        #version 120
        uniform sampler2D textureIn;
        uniform sampler2D shapeIn;
        uniform vec2 texelSize;
        uniform vec2 direction;
        uniform float radius;
        uniform float gain;
        uniform float kernel[33];
        const int MAX_RANGE = 32;

        void main() {
            vec2 uv = gl_TexCoord[0].xy;
            if (direction.x == 0.0) {
                if (texture2D(shapeIn, uv).a > 0.0) {
                    discard;
                }
            }
            vec4 c = texture2D(textureIn, uv);
            c.rgb *= c.a;
            vec4 sum = c * kernel[0];
            for (int i = 1; i <= MAX_RANGE; i++) {
                if (float(i) > radius) break;
                vec2 offset = texelSize * direction * float(i);
                vec4 s = texture2D(textureIn, uv + offset);
                vec4 t = texture2D(textureIn, uv - offset);
                s.rgb *= s.a;
                t.rgb *= t.a;
                sum += (s + t) * kernel[i];
            }
            gl_FragColor = vec4((sum.rgb / max(sum.a, 0.0001)) * gain * gl_Color.rgb, sum.a);
        }
    """.trimIndent()

    private fun ensureInitialized(): Boolean {
        if (programID > 0) return true
        if (programID == 0) return false
        if (!ShaderSupport.isSupported) {
            programID = 0
            return false
        }

        val v = GL20.glCreateShader(GL20.GL_VERTEX_SHADER)
        GL20.glShaderSource(v, vertexSrc)
        GL20.glCompileShader(v)
        val f = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER)
        // 防 LWJGL2 低字节编码坑：shader 源码含非 ASCII 时直接拒绝（编译必然失败）
        if (!fragmentSrc.all { it.code < 128 } || !vertexSrc.all { it.code < 128 }) {
            ClientUtils.LOGGER.warn("[HudGlowShader] shader 源码含非 ASCII 字符，拒绝编译（见 fragmentSrc 铁律注释）")
            programID = 0
            return false
        }
        GL20.glShaderSource(f, fragmentSrc)
        GL20.glCompileShader(f)
        if (GL20.glGetShaderi(v, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE ||
            GL20.glGetShaderi(f, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE
        ) {
            ClientUtils.LOGGER.warn(
                "[HudGlowShader] shader compile failed: {}", GL20.glGetShaderInfoLog(f, 1024)
            )
            GL20.glDeleteShader(v)
            GL20.glDeleteShader(f)
            programID = 0
            return false
        }
        programID = GL20.glCreateProgram()
        GL20.glAttachShader(programID, v)
        GL20.glAttachShader(programID, f)
        GL20.glLinkProgram(programID)
        GL20.glDetachShader(programID, v)
        GL20.glDetachShader(programID, f)
        GL20.glDeleteShader(v)
        GL20.glDeleteShader(f)
        if (GL20.glGetProgrami(programID, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            ClientUtils.LOGGER.warn(
                "[HudGlowShader] program link failed: {}", GL20.glGetProgramInfoLog(programID, 1024)
            )
            GL20.glDeleteProgram(programID)
            programID = 0
            return false
        }
        uniformTexture = GL20.glGetUniformLocation(programID, "textureIn")
        uniformShape = GL20.glGetUniformLocation(programID, "shapeIn")
        uniformTexelSize = GL20.glGetUniformLocation(programID, "texelSize")
        uniformDirection = GL20.glGetUniformLocation(programID, "direction")
        uniformRadius = GL20.glGetUniformLocation(programID, "radius")
        uniformGain = GL20.glGetUniformLocation(programID, "gain")
        uniformKernel = GL20.glGetUniformLocation(programID, "kernel")
        return true
    }

    fun cleanup() {
        if (programID > 0) GL20.glDeleteProgram(programID)
        programID = -1
        uniformTexture = -1
        uniformShape = -1
        uniformTexelSize = -1
        uniformDirection = -1
        uniformRadius = -1
        uniformGain = -1
        uniformKernel = -1

        inputFramebuffer?.deleteFramebuffer()
        inputFramebuffer = null
        outputFramebuffer?.deleteFramebuffer()
        outputFramebuffer = null

        dirty = false
        kernelRadius = -1
    }

    // ===== 状态保存 buffer（渲染线程单线程复用） =====

    private val projMatrixBuf = BufferUtils.createFloatBuffer(16)
    private val modelMatrixBuf = BufferUtils.createFloatBuffer(16)
    private val colorMaskBuf = BufferUtils.createIntBuffer(16)
    private val currentColorBuf = BufferUtils.createFloatBuffer(16)
    private val scissorBuf = BufferUtils.createIntBuffer(16)
}
