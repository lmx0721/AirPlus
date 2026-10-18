// skid xylitol
package net.airplus.utils.render.shader

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.client.shader.Framebuffer
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Kawase Blur 实现
 * 迁移自 xylitol 客户端
 *
 * 修复记录（原来"没有任何效果"的根因）：
 * 1. 中间 pass 与最终 pass 都用 [ShaderUtil.drawQuads] 全屏四边形，却依赖调用方的
 *    GL 环境矩阵。项目里其它可用模糊（InternalBlurShader / BlurEffects）都会自建
 *    "单位 modelview + GUI 正交投影"再画，KawaseBlur 没做这一步——调用方一旦处于
 *    平移/缩放过的 modelview（Island 的 glPushMatrix、HUD 组件的局部变换）或非标准
 *    投影下，四边形就画到错误区域甚至完全不可见。现在所有 pass 都自建矩阵。
 * 2. 调用方（Island/HudBlur）通常在 EmbeddedStencil.erase(true)（GL_EQUAL 1）内调用，
 *    而中间 pass 写入的是无 stencil 附着的独立 FBO，stencil 状态依赖驱动实现，
 *    且最终 pass 前的 scissor 会覆盖调用方的 scissor box 只恢复开关不恢复 box。
 *    现在统一保存/恢复 stencil 开关与完整 scissor 状态（box + 开关）。
 * 3. 同帧多实例（Island + 计分板等）参数不同会导致整条 FBO 链每帧被删除重建，
 *    驱动侧极易出现未就绪/陈旧纹理 → 模糊区域整体发灰。现在按 iterations 缓存
 *    FBO 链，不同参数共存时各自持有链，只在尺寸变化时重建。
 * 4. alpha test / 纹理单元 / 清除色全部显式控制：GlStateManager 缓存与真实状态
 *    失同步时（项目历史教训），残留的 alpha test 或错误 unit 会让 pass 全部丢弃
 *    或采样错误纹理，最终画出一块"白/灰"色块。现在 pass 内用裸 GL 保证真实状态，
 *    同时调用 GlStateManager 同步其缓存，退出时恢复调用方原状态。
 */
object KawaseBlur {
    private val mc = Minecraft.getMinecraft()

    private var kawaseDown: ShaderUtil? = null
    private var kawaseUp: ShaderUtil? = null

    /** 当前使用的 FBO 链（index 0 = 全分辨率主链）。供兼容保留的公共字段 */
    @JvmField
    var framebuffer = Framebuffer(1, 1, false)

    /** 按 iterations 缓存的 FBO 链：key=iterations，list[0]=全分辨率，list[1..n+1]=降采样级 */
    private val chains = HashMap<Int, MutableList<Framebuffer>>()

    /** 着色器初始化失败后不再每帧重试（避免重复创建 GL 对象与报错刷屏） */
    private var initFailed = false

    /** scissor 状态保存用（渲染线程单线程使用）。GL 查询必须用 direct buffer */
    private var prevScissorEnabled = false
    private val scissorBox: IntBuffer = BufferUtils.createIntBuffer(16)

    /** colorMask 保存用：GL_COLOR_WRITEMASK 返回 4 个值。注意 LWJGL 2 的 glGetInteger
     *  buffer 版强制要求剩余容量 ≥ 16，不足直接抛 IllegalArgumentException（曾导致游戏崩溃），
     *  因此这里分配 16，实际只用前 4 个 */
    private val colorMaskBuf: IntBuffer = BufferUtils.createIntBuffer(16)

    /**
     * 矩阵保存用。不使用 glPushMatrix/glPopMatrix：
     * GL 投影矩阵栈深度只有 2，与调用方叠加或中途异常都会栈溢出（GL 1283），
     * 之后 pop 出错误的矩阵导致整个画面元素偏移。改用 glGetFloatv 保存 +
     * glLoadMatrix 恢复，与矩阵栈完全无关。
     */
    private val projMatrix: FloatBuffer = BufferUtils.createFloatBuffer(16)
    private val modelMatrix: FloatBuffer = BufferUtils.createFloatBuffer(16)
    private var prevMatrixMode = 0
    private var matricesPushed = false

    /** active texture unit 保存用（pass 内固定 unit 0，退出恢复） */
    private var prevActiveTexture = GL13.GL_TEXTURE0

    private fun initShaders() {
        if (initFailed) return
        if (kawaseDown == null) {
            try { kawaseDown = ShaderUtil("kawaseDown") } catch (e: Exception) {
                e.printStackTrace()
                initFailed = true
            }
        }
        if (kawaseUp == null && !initFailed) {
            try { kawaseUp = ShaderUtil("kawaseUp") } catch (e: Exception) {
                e.printStackTrace()
                initFailed = true
            }
        }
    }

    fun setupUniforms(offset: Float) {
        kawaseDown?.setUniformf("offset", offset, offset)
        kawaseUp?.setUniformf("offset", offset, offset)
    }

    /**
     * 获取（必要时重建）指定 iterations 的 FBO 链。
     * 链按 iterations 缓存：同帧多个不同参数的调用者不再互相删除/重建对方正在使用的 FBO
     * （此前共享单链 + 参数不同 = 每帧两次全链重建，是模糊区域发灰/闪烁的根源）。
     */
    private fun ensureChain(iterations: Int): List<Framebuffer> {
        var chain = chains[iterations]
        val main = chain?.firstOrNull()
        if (chain == null || chain.size != iterations + 2 ||
            main == null ||
            main.framebufferWidth != mc.displayWidth ||
            main.framebufferHeight != mc.displayHeight
        ) {
            chain?.forEach { it.deleteFramebuffer() }
            val newChain = mutableListOf<Framebuffer>()

            val mainFb = Framebuffer(mc.displayWidth, mc.displayHeight, false)
            clearFboBlack(mainFb)
            mainFb.bindFramebuffer(false)
            newChain.add(mainFb)

            for (i in 1..iterations + 1) {
                val width = (mc.displayWidth / Math.pow(2.0, i.toDouble())).toInt().coerceAtLeast(1)
                val height = (mc.displayHeight / Math.pow(2.0, i.toDouble())).toInt().coerceAtLeast(1)
                val currentBuffer = Framebuffer(width, height, false)
                currentBuffer.setFramebufferFilter(GL11.GL_LINEAR)

                GlStateManager.bindTexture(currentBuffer.framebufferTexture)
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL14.GL_MIRRORED_REPEAT)
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL14.GL_MIRRORED_REPEAT)
                GlStateManager.bindTexture(0)

                newChain.add(currentBuffer)
            }
            chains[iterations] = newChain
            chain = newChain
        }
        framebuffer = chain!![0]
        return chain!!
    }

    /** 绑定 FBO 并以"不透明黑"清屏：不依赖 Framebuffer.framebufferColor 的白/透明清除值，
     *  避免任何 pass 未覆盖时把白色残留进模糊结果（表现为灰色块） */
    private fun clearFboBlack(fb: Framebuffer) {
        fb.bindFramebuffer(true)
        GlStateManager.clearColor(0f, 0f, 0f, 1f)
        GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT)
    }

    /**
     * 自建"单位 modelview + GUI 正交投影"矩阵（不占用矩阵栈，见 [projMatrix]），
     * 保证全屏四边形在任何调用方矩阵下都精确覆盖整个 viewport。
     * 必须与 [popGuiMatrix] 成对出现（建议放 try/finally）。
     */
    private fun pushGuiMatrix(sr: ScaledResolution) {
        prevMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE)
        projMatrix.rewind()
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projMatrix)
        modelMatrix.rewind()
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelMatrix)
        matricesPushed = true

        GL11.glMatrixMode(GL11.GL_PROJECTION)
        GL11.glLoadIdentity()
        GL11.glOrtho(0.0, sr.scaledWidth_double, sr.scaledHeight_double, 0.0, 1000.0, 3000.0)
        GL11.glTranslated(0.0, 0.0, -2000.0)
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        GL11.glLoadIdentity()
    }

    private fun popGuiMatrix() {
        if (!matricesPushed) return
        matricesPushed = false
        GL11.glMatrixMode(GL11.GL_PROJECTION)
        projMatrix.rewind()
        GL11.glLoadMatrix(projMatrix)
        GL11.glMatrixMode(GL11.GL_MODELVIEW)
        modelMatrix.rewind()
        GL11.glLoadMatrix(modelMatrix)
        GL11.glMatrixMode(prevMatrixMode)
    }

    private fun saveScissor() {
        prevScissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
        // GL_SCISSOR_BOX 返回 4 个值，必须用 buffer 版本读取
        //（单值版 glGetInteger 遇多值 pname 返回 0，曾导致恢复成 glScissor(0,0,0,0)，
        //  再叠加 scissor 开关残留启用 → 模糊之后整个画面被裁剪成空、屏幕冻结）
        scissorBox.clear()
        GL11.glGetInteger(GL11.GL_SCISSOR_BOX, scissorBox)
    }

    private fun restoreScissor() {
        val box = scissorBox
        GL11.glScissor(box[0], box[1], box[2], box[3])
        if (prevScissorEnabled) GL11.glEnable(GL11.GL_SCISSOR_TEST) else GL11.glDisable(GL11.GL_SCISSOR_TEST)
    }

    /** 保存 colorMask / depthMask（EmbeddedStencil 依赖它们，clear 会改写） */
    private var prevDepthMask = true

    private fun saveColorState() {
        colorMaskBuf.clear()
        GL11.glGetInteger(GL11.GL_COLOR_WRITEMASK, colorMaskBuf)
        prevDepthMask = GL11.glGetInteger(GL11.GL_DEPTH_WRITEMASK) == GL11.GL_TRUE
        GlStateManager.colorMask(true, true, true, true)
        GlStateManager.depthMask(true)
    }

    private fun restoreColorState() {
        GlStateManager.colorMask(
            colorMaskBuf[0] == GL11.GL_TRUE, colorMaskBuf[1] == GL11.GL_TRUE,
            colorMaskBuf[2] == GL11.GL_TRUE, colorMaskBuf[3] == GL11.GL_TRUE
        )
        GlStateManager.depthMask(prevDepthMask)
    }

    /** 裸 GL 关闭 alpha test 并同步 GlStateManager 缓存（缓存失同步时单纯走缓存可能不生效） */
    private fun forceDisableAlphaTest() {
        GL11.glDisable(GL11.GL_ALPHA_TEST)
        GlStateManager.disableAlpha()
    }

    /**
     * 全屏 downsample + upsample 链，结果写入 chain[0]。
     *
     * @param stencilFramebufferTexture 非 null 时最后一次 upsample 启用 check，
     *        通过 unit16 上的 stencil framebuffer 纹理把形状外的像素置 alpha=0
     *        （供 [renderBlur] 的形状遮罩路径使用）
     */
    private fun kawaseCore(
        iterations: Int,
        offset: Int,
        stencilFramebufferTexture: Int?
    ) {
        val chain = ensureChain(iterations)

        val down = kawaseDown!!
        val up = kawaseUp!!
        val sr = ScaledResolution(mc)

        // 保存状态
        val prevStencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST)
        val prevBlend = GL11.glIsEnabled(GL11.GL_BLEND)
        val prevAlpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
        saveScissor()
        saveColorState()

        // 纹理单元固定到 0（pass 用裸 glBindTexture 绑定，shader 固定采样 unit 0；
        // 调用方若停留在其它 unit 会导致采样错误纹理 → 整块纯色/灰）
        prevActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        OpenGlHelper.setActiveTexture(GL13.GL_TEXTURE0)

        // 中间 pass 写入无 stencil 的独立 FBO，必须关掉调用方的 stencil 测试
        //（例如 Island 外层 EmbeddedStencil 的 GL_EQUAL），否则结果受 stencil 残留影响
        GL11.glDisable(GL11.GL_STENCIL_TEST)
        // 中间 pass 同样不需要调用方的 scissor（避免把模糊链的写入裁掉一截）
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        GlStateManager.disableBlend()
        forceDisableAlphaTest()

        try {
            pushGuiMatrix(sr)

            // Downsample 阶段
            renderFBO(chain[1], mc.framebuffer.framebufferTexture, down, offset.toFloat())
            for (i in 1 until iterations) {
                renderFBO(chain[i + 1], chain[i].framebufferTexture, down, offset.toFloat())
            }

            // Upsample 阶段（中间步骤 check=0）
            for (i in iterations downTo 2) {
                renderFBO(chain[i - 1], chain[i].framebufferTexture, up, offset.toFloat())
            }

            // 最后一次 upsample 到 chain[0]：
            // check=0 → alpha=1.0（完全不透明）；check=1 → 用 stencil 纹理做形状遮罩
            val lastBuffer = chain[0]
            clearFboBlack(lastBuffer)
            up.init()
            up.setUniformf("offset", offset.toFloat(), offset.toFloat())
            up.setUniformi("inTexture", 0)
            if (stencilFramebufferTexture != null) {
                up.setUniformi("check", 1)
                up.setUniformi("textureToCheck", 16)
                up.setUniformf("halfpixel", 1.0f / lastBuffer.framebufferWidth, 1.0f / lastBuffer.framebufferHeight)
                up.setUniformf("iResolution", lastBuffer.framebufferWidth.toFloat(), lastBuffer.framebufferHeight.toFloat())

                // 用 GlStateManager 兼容方式切换单元，保持 activeTexture 缓存同步
                OpenGlHelper.setActiveTexture(GL13.GL_TEXTURE16)
                bindTexture(stencilFramebufferTexture)
                OpenGlHelper.setActiveTexture(GL13.GL_TEXTURE0)
                bindTexture(chain[1].framebufferTexture)
                GlStateManager.color(1f, 1f, 1f, 1f)
                ShaderUtil.drawQuads()
                up.unload()
            } else {
                up.setUniformi("check", 0)
                up.setUniformf("halfpixel", 1.0f / lastBuffer.framebufferWidth, 1.0f / lastBuffer.framebufferHeight)
                up.setUniformf("iResolution", lastBuffer.framebufferWidth.toFloat(), lastBuffer.framebufferHeight.toFloat())
                bindTexture(chain[1].framebufferTexture)
                GlStateManager.color(1f, 1f, 1f, 1f)
                ShaderUtil.drawQuads()
                up.unload()
            }
        } finally {
            // 任何异常都必须恢复矩阵，否则下一帧起整个画面都会错位
            popGuiMatrix()
            restoreColorState()
            OpenGlHelper.setActiveTexture(prevActiveTexture)
        }

        // 恢复调用方的 stencil / scissor 开关（box 由 restoreCallerState 在最终 pass 后恢复）。
        // 最终 pass 之前恢复 GL_EQUAL 形状裁剪可继续生效。
        if (prevStencil) GL11.glEnable(GL11.GL_STENCIL_TEST) else GL11.glDisable(GL11.GL_STENCIL_TEST)
        if (prevScissorEnabled) GL11.glEnable(GL11.GL_SCISSOR_TEST) else GL11.glDisable(GL11.GL_SCISSOR_TEST)

        // 记录恢复用的 blend/alpha/blendFunc（最终 pass 会改写它们）
        val prevBlendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC)
        val prevBlendDst = GL11.glGetInteger(GL11.GL_BLEND_DST)
        kawaseCoreResult = KawaseCoreResult(prevBlend, prevAlpha, prevBlendSrc, prevBlendDst)
    }

    private var kawaseCoreResult: KawaseCoreResult = KawaseCoreResult(true, true, GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)

    private class KawaseCoreResult(val prevBlend: Boolean, val prevAlpha: Boolean, val blendSrc: Int, val blendDst: Int)

    fun renderBlur(stencilFrameBufferTexture: Int, iterations: Int, offset: Int) {
        initShaders()
        if (kawaseDown == null || kawaseUp == null) {
            System.err.println("KawaseBlur shaders not initialized, skipping blur render")
            return
        }
        kawaseCore(iterations, offset, stencilFrameBufferTexture)

        // 渲染到主 framebuffer（全屏）
        val sr = ScaledResolution(mc)
        mc.framebuffer.bindFramebuffer(true)
        pushGuiMatrix(sr)
        val prevUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        OpenGlHelper.setActiveTexture(GL13.GL_TEXTURE0)
        try {
            bindTexture(framebuffer.framebufferTexture)
            forceDisableAlphaTest()
            startBlend()
            GlStateManager.color(1f, 1f, 1f, 1f)
            ShaderUtil.drawQuads()
            GlStateManager.bindTexture(0)
        } finally {
            OpenGlHelper.setActiveTexture(prevUnit)
            popGuiMatrix()
            restoreCallerState()
        }
    }

    /**
     * 渲染指定区域的模糊效果
     * 注意：此方法会先进行全屏模糊，然后在最终渲染到主framebuffer时使用scissor限制区域
     * @param x 区域左上角 X 坐标（像素坐标）
     * @param y 区域左上角 Y 坐标（像素坐标）
     * @param width 区域宽度（像素坐标）
     * @param height 区域高度（像素坐标）
     */
    fun renderBlur(stencilFrameBufferTexture: Int, iterations: Int, offset: Int, x: Int, y: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        initShaders()
        if (kawaseDown == null || kawaseUp == null) {
            System.err.println("KawaseBlur shaders not initialized, skipping blur render")
            return
        }
        kawaseCore(iterations, offset, stencilFrameBufferTexture)

        // 渲染到主 framebuffer，使用 scissor 限制区域
        val sr = ScaledResolution(mc)
        mc.framebuffer.bindFramebuffer(true)
        pushGuiMatrix(sr)

        // 输入是左上角原点的像素坐标，转换到 OpenGL 左下角原点
        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        GL11.glScissor(x, mc.displayHeight - (y + height), width, height)

        val prevUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        OpenGlHelper.setActiveTexture(GL13.GL_TEXTURE0)
        try {
            bindTexture(framebuffer.framebufferTexture)
            forceDisableAlphaTest()
            startBlend()
            GlStateManager.color(1f, 1f, 1f, 1f)
            ShaderUtil.drawQuads()
            GlStateManager.bindTexture(0)
        } finally {
            OpenGlHelper.setActiveTexture(prevUnit)
            popGuiMatrix()
            restoreCallerState()
        }
    }

    /**
     * 基于 Scissor 裁剪的区域模糊（不使用 stencil framebuffer）。
     *
     * 流程：
     *  1. 对主 framebuffer 全屏进行 Kawase 下采样 + 上采样，得到全屏模糊结果。
     *  2. 最后一次 upsample 使用 check=0（alpha=1.0），让模糊结果完全不透明。
     *  3. 将模糊结果渲染回主 framebuffer 时，用 glScissor 限制只在指定矩形区域内绘制，
     *     区域外保持原样，避免白屏。
     *
     * 坐标参数均为 framebuffer 像素坐标（原点在左下角）。
     *
     * @param iterations 下采样/上采样次数
     * @param offset     模糊偏移量
     * @param x          裁剪区域左下角 X（像素）
     * @param y          裁剪区域左下角 Y（像素）
     * @param width      裁剪区域宽度（像素）
     * @param height     裁剪区域高度（像素）
     */
    fun renderBlurScissor(iterations: Int, offset: Int, x: Int, y: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        initShaders()
        if (kawaseDown == null || kawaseUp == null) {
            System.err.println("KawaseBlur shaders not initialized, skipping blur render")
            return
        }

        kawaseCore(iterations, offset, null)

        // 渲染到主 framebuffer，用 scissor 限制只在指定区域绘制模糊结果
        val sr = ScaledResolution(mc)
        mc.framebuffer.bindFramebuffer(true)
        pushGuiMatrix(sr)

        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        GL11.glScissor(x, y, width, height)

        val prevUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        OpenGlHelper.setActiveTexture(GL13.GL_TEXTURE0)
        try {
            bindTexture(framebuffer.framebufferTexture)
            forceDisableAlphaTest()
            startBlend()
            GlStateManager.color(1f, 1f, 1f, 1f)
            ShaderUtil.drawQuads()
            GlStateManager.bindTexture(0)
        } finally {
            OpenGlHelper.setActiveTexture(prevUnit)
            popGuiMatrix()
            restoreCallerState()
        }
    }

    /** 恢复 scissor box / blend / blendFunc / alpha / 颜色，避免状态泄漏到后续渲染 */
    private fun restoreCallerState() {
        restoreScissor()
        val result = kawaseCoreResult
        if (result.prevBlend) GlStateManager.enableBlend() else GlStateManager.disableBlend()
        // 走 GlStateManager 同步恢复，保证其内部 blendFunc 缓存与真实 GL 状态一致
        GlStateManager.blendFunc(result.blendSrc, result.blendDst)
        // alpha test 裸 GL 恢复真实状态 + GlStateManager 同步缓存
        if (result.prevAlpha) {
            GL11.glEnable(GL11.GL_ALPHA_TEST)
            GlStateManager.enableAlpha()
        } else {
            GL11.glDisable(GL11.GL_ALPHA_TEST)
            GlStateManager.disableAlpha()
        }
        GlStateManager.color(1f, 1f, 1f, 1f)
    }

    private fun renderFBO(framebuffer: Framebuffer, framebufferTexture: Int, shader: ShaderUtil, offset: Float) {
        clearFboBlack(framebuffer)
        // true：viewport 同步为该 FBO 的尺寸。否则 viewport 仍是主帧大小，
        // 小尺寸 mip FBO 只能收到全屏四边形左下角的一块（纹理坐标 0..0.5），模糊结果错位
        framebuffer.bindFramebuffer(true)
        shader.init()
        bindTexture(framebufferTexture)
        // 重置当前颜色，避免上一帧残留颜色把纹理调制出奇怪的颜色
        GlStateManager.color(1f, 1f, 1f, 1f)
        shader.setUniformf("offset", offset, offset)
        shader.setUniformi("inTexture", 0)
        shader.setUniformi("check", 0)
        shader.setUniformf("halfpixel", 1.0f / framebuffer.framebufferWidth, 1.0f / framebuffer.framebufferHeight)
        shader.setUniformf("iResolution", framebuffer.framebufferWidth.toFloat(), framebuffer.framebufferHeight.toFloat())
        ShaderUtil.drawQuads()
        shader.unload()
    }

    /**
     * 绑定纹理（调用方保证 active unit 已固定为 0）
     */
    fun bindTexture(texture: Int) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture)
    }

    /**
     * 开启混合
     */
    fun startBlend() {
        GlStateManager.enableBlend()
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
    }

    /**
     * 关闭混合
     */
    fun endBlend() {
        GlStateManager.disableBlend()
    }
}
