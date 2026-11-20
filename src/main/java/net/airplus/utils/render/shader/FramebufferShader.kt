/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.render.shader

import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager.*
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.client.renderer.RenderHelper
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.client.shader.Framebuffer
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20.glUseProgram
import java.awt.Color
import kotlin.math.roundToInt

/**
 * @author TheSlowly, Navex
 */
abstract class FramebufferShader(fragmentShader: String) : Shader(fragmentShader) {

    companion object {
        private var framebuffer: Framebuffer? = null
    }
    
    protected var red = 1f
    protected var green = 1f
    protected var blue = 1f
    protected var alpha = 1f
    protected var radius = 5
    protected var fade = 10
    protected var renderScale = 1f
    protected var targetAlpha = 0f
    
    private var entityShadows = false

    /**
     * 记录 [startDraw] 是否真正开始，避免着色器不可用时 [stopDraw] 仍去绑定帧缓冲、绘制全屏四边形。
     */
    private var drawStarted = false

    fun startDraw(partialTicks: Float, renderScale: Float) {
        // 着色器不可用时（如 Android GLES）直接跳过整段离屏渲染
        if (!prepareDraw(renderScale)) return

        mc.entityRenderer.setupCameraTransform(partialTicks, 0)
    }

    /**
     * 公共准备：绑定离屏帧缓冲、关闭实体阴影。返回是否真正开始。
     */
    private fun prepareDraw(renderScale: Float): Boolean {
        // 着色器不可用时（如 Android GLES）直接跳过整段离屏渲染
        if (!isUsable || !ShaderSupport.isSupported) return false

        this.renderScale = renderScale

        pushMatrix()
        enableAlpha()
        pushAttrib()

        framebuffer = setupFrameBuffer(framebuffer, renderScale)
        framebuffer!!.framebufferClear()
        framebuffer!!.bindFramebuffer(true)

        entityShadows = mc.gameSettings.entityShadows
        mc.gameSettings.entityShadows = false

        drawStarted = true
        return true
    }

    fun stopDraw(color: Color, radius: Int, fade: Int, targetAlpha: Float) {
        // 未成功开始绘制时不能继续，否则会把当前画面错误地写回去
        if (!drawStarted) return

        drawStarted = false
        mc.gameSettings.entityShadows = entityShadows
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        mc.framebuffer.bindFramebuffer(true)
        
        red = color.red / 255f
        green = color.green / 255f
        blue = color.blue / 255f
        alpha = color.alpha / 255f
        this.radius = radius
        this.fade = fade
        this.targetAlpha = targetAlpha
        
        mc.entityRenderer.disableLightmap()
        RenderHelper.disableStandardItemLighting()
        
        startShader()
        mc.entityRenderer.setupOverlayRendering()
        drawFramebuffer(framebuffer!!)
        stopShader()
        
        mc.entityRenderer.disableLightmap()
        
        popMatrix()
        popAttrib()
    }

    /**
     * Reuse the existing framebuffer unless the window size or render scale changed.
     * Recreating a full-screen framebuffer every frame is a major GPU allocation hotspot.
     *
     * @author TheSlowly, Navex
     */
    fun setupFrameBuffer(frameBuffer: Framebuffer?, renderScale: Float): Framebuffer {
        val width = (mc.displayWidth * renderScale).roundToInt()
        val height = (mc.displayHeight * renderScale).roundToInt()

        if (frameBuffer != null && frameBuffer.framebufferWidth == width && frameBuffer.framebufferHeight == height) {
            return frameBuffer
        }

        frameBuffer?.deleteFramebuffer()

        return Framebuffer(width, height, true)
    }

    /**
     * @author Navex
     */
    fun drawFramebuffer(framebuffer: Framebuffer) {
        val scaledResolution = ScaledResolution(mc)
        val scaledWidth = scaledResolution.scaledWidth_double
        val scaledHeight = scaledResolution.scaledHeight_double

        val tessellator = Tessellator.getInstance()
        val buffer = tessellator.worldRenderer

        // 关键修复：快照 unit-0 绑定并在退出时两步法还原。裸 glBindTexture 绕过
        // GlStateManager 缓存，若退出时只裸绑回（或干脆不还原），缓存停在旧值 ——
        // 后续守卫式 bindTexture 命中缓存时被静默跳过，字形/图标 quad 采样辉光
        // framebuffer 纹理 → 文字变成方块。两步法：先绑 0 推开缓存，再绑回原纹理，
        // 保证 真实绑定 == 缓存。外层 pushAttrib() 只压 GL_ENABLE_BIT（8256），
        // 不含 GL_TEXTURE_BIT，纹理绑定不会被 attrib 栈还原，必须在这里处理。
        val prevActiveTexture = glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        val prevBinding = glGetInteger(GL_TEXTURE_BINDING_2D)

        glBindTexture(GL_TEXTURE_2D, framebuffer.framebufferTexture)
        buffer.begin(GL_QUADS, DefaultVertexFormats.POSITION_TEX)
        buffer.pos(0.0, 0.0, 1.0).tex(0.0, 1.0).endVertex()
        buffer.pos(0.0, scaledHeight, 1.0).tex(0.0, 0.0).endVertex()
        buffer.pos(scaledWidth, scaledHeight, 1.0).tex(1.0, 0.0).endVertex()
        buffer.pos(scaledWidth, 0.0, 0.0).tex(1.0, 1.0).endVertex()
        tessellator.draw()
        glUseProgram(0)

        bindTexture(0)
        bindTexture(prevBinding)
        OpenGlHelper.setActiveTexture(prevActiveTexture)
    }
}
