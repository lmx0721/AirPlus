package net.airplus.utils.render

import net.minecraft.client.Minecraft
import net.minecraft.client.shader.Framebuffer
import org.lwjgl.opengl.EXTFramebufferObject
import org.lwjgl.opengl.EXTPackedDepthStencil
import org.lwjgl.opengl.GL11.*

object EmbeddedStencil {
    // 进入 write() 前的调用方状态，dispose() 时恢复，避免污染后续渲染
    private var prevStencilTest = false
    private var prevClearStencil = 0

    /** 限定清屏范围时保存调用方 scissor 状态用（渲染线程单线程使用）。
     *  GL 查询必须用 direct buffer（heap buffer 会被 LWJGL2 拒绝） */
    private val scissorSave = org.lwjgl.BufferUtils.createIntBuffer(16)

    fun checkSetupFBO(framebuffer: Framebuffer?) {
        if (framebuffer != null && framebuffer.depthBuffer > -1) {
            setupFBO(framebuffer)
            framebuffer.depthBuffer = -1
        }
    }

    fun setupFBO(framebuffer: Framebuffer) {
        EXTFramebufferObject.glDeleteRenderbuffersEXT(framebuffer.depthBuffer)
        val stencilDepthBufferID = EXTFramebufferObject.glGenRenderbuffersEXT()
        EXTFramebufferObject.glBindRenderbufferEXT(EXTFramebufferObject.GL_RENDERBUFFER_EXT, stencilDepthBufferID)
        EXTFramebufferObject.glRenderbufferStorageEXT(EXTFramebufferObject.GL_RENDERBUFFER_EXT, EXTPackedDepthStencil.GL_DEPTH_STENCIL_EXT, Minecraft.getMinecraft().displayWidth, Minecraft.getMinecraft().displayHeight)
        EXTFramebufferObject.glFramebufferRenderbufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, EXTFramebufferObject.GL_DEPTH_ATTACHMENT_EXT, EXTFramebufferObject.GL_RENDERBUFFER_EXT, stencilDepthBufferID)
        EXTFramebufferObject.glFramebufferRenderbufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, EXTFramebufferObject.GL_STENCIL_ATTACHMENT_EXT, EXTFramebufferObject.GL_RENDERBUFFER_EXT, stencilDepthBufferID)
        // 解绑 renderbuffer，避免残留绑定影响后续 renderbuffer 操作
        EXTFramebufferObject.glBindRenderbufferEXT(EXTFramebufferObject.GL_RENDERBUFFER_EXT, 0)
    }

    /**
     * 开启 stencil 写入（GL_ALWAYS REPLACE），可选把 stencil 清屏限定在给定 GL 像素矩形内。
     *
     * @param clearBox null = 全屏清屏（旧行为）；非 null = {x, y, w, h}（GL 像素坐标），
     *        只清除该矩形内的 stencil。正确性依据：调用方（HudBlur）的贴回 quad 自身被
     *        scissor 到同一矩形，区域外的旧 stencil 值不参与本次 GL_EQUAL 贴回判定，
     *        因此无需全屏清除——把每面板一次的全屏 stencil 写入（1080p ≈ 2MB）降到面板面积
     */
    fun write(invert: Boolean, clearBox: IntArray? = null) {
        checkSetupFBO(Minecraft.getMinecraft().framebuffer)
        prevStencilTest = glIsEnabled(GL_STENCIL_TEST)
        prevClearStencil = glGetInteger(GL_STENCIL_CLEAR_VALUE)
        if (clearBox != null) {
            // 保存调用方 scissor 状态 → 限定清屏范围 → 还原（清屏绝不泄漏 scissor）
            val prevScissorOn = glIsEnabled(GL_SCISSOR_TEST)
            scissorSave.clear()
            glGetInteger(GL_SCISSOR_BOX, scissorSave)
            glScissor(clearBox[0], clearBox[1], clearBox[2], clearBox[3])
            glEnable(GL_SCISSOR_TEST)
            glClearStencil(0)
            glClear(GL_STENCIL_BUFFER_BIT)
            glScissor(scissorSave[0], scissorSave[1], scissorSave[2], scissorSave[3])
            if (prevScissorOn) glEnable(GL_SCISSOR_TEST) else glDisable(GL_SCISSOR_TEST)
        } else {
            glClearStencil(0)
            glClear(GL_STENCIL_BUFFER_BIT)
        }
        glEnable(GL_STENCIL_TEST)
        glStencilFunc(GL_ALWAYS, 1, 65535)
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE)
        if (!invert) {
            glColorMask(false, false, false, false)
            glDepthMask(false)
            glStencilFunc(GL_ALWAYS, 1, 65535)
            glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE)
        }
    }

    fun erase(invert: Boolean) {
        glStencilFunc(if (invert) GL_EQUAL else GL_NOTEQUAL, 1, 65535)
        glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE)
        if (invert) {
            glColorMask(true, true, true, true)
            glDepthMask(true)
            glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE)
        } else {
            glColorMask(true, true, true, true)
            glDepthMask(true)
            glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP)
        }
    }

    fun dispose() {
        glDisable(GL_STENCIL_TEST)
        // 恢复 colorMask/depthMask：若调用方在 write 后、erase 前异常退出，
        // colorMask 残留 false 会导致后续渲染"画面消失"
        glColorMask(true, true, true, true)
        glDepthMask(true)
        // 恢复调用方原有状态：若调用方本就开启了 stencil test 则重新启用，
        // 并还原 stencil 清除值，避免污染同一帧内其他渲染
        if (prevStencilTest) glEnable(GL_STENCIL_TEST)
        glClearStencil(prevClearStencil)
    }
}
