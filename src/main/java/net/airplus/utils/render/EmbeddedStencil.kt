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

    fun write(invert: Boolean) {
        checkSetupFBO(Minecraft.getMinecraft().framebuffer)
        prevStencilTest = glIsEnabled(GL_STENCIL_TEST)
        prevClearStencil = glGetInteger(GL_STENCIL_CLEAR_VALUE)
        glClearStencil(0)
        glClear(GL_STENCIL_BUFFER_BIT)
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
