/*
 * LiquidBounce+ Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/WYSI-Foundation/LiquidBouncePlus/
 *
 * This code belongs to WYSI-Foundation. Please give credits when using this in your repository.
 */
package net.airplus.utils.render

import net.airplus.utils.client.ClientUtils
import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.render.shader.ShaderSupport
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.client.shader.Framebuffer
import net.minecraft.client.shader.ShaderGroup
import net.minecraft.util.ResourceLocation
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11

object BlurUtils : MinecraftInstance {

    private var shaderGroup: ShaderGroup? = null
    private var framebuffer: Framebuffer? = null
    private var frbuffer: Framebuffer? = null
    private var initAttempted = false

    private var lastFactor = 0
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastWeight = 0

    private var lastX = 0F
    private var lastY = 0F
    private var lastW = 0F
    private var lastH = 0F

    private var lastStrength = 5F

    /**
     * 懒加载模糊着色器组。
     *
     * blurArea.fsh 是桌面版 #version 120 着色器，在 Android GLES 上必然编译失败。
     * 原先在 object 初始化阶段就构造 ShaderGroup，会让整个对象初始化直接抛错；
     * 这里改为首次使用时创建并捕获失败，不可用就永久跳过模糊。
     */
    private fun ensureShaderGroup(): Boolean {
        if (initAttempted) return shaderGroup != null
        initAttempted = true
        try {
            val group = ShaderGroup(mc.textureManager, mc.resourceManager, mc.framebuffer, ResourceLocation("shaders/post/blurArea.json"))
            shaderGroup = group
            framebuffer = group.mainFramebuffer
            frbuffer = group.getFramebufferRaw("result")
        } catch (t: Throwable) {
            ClientUtils.LOGGER.warn("[BlurUtils] 模糊着色器初始化失败，已禁用模糊效果: ${t.message}")
        }
        return shaderGroup != null
    }

    private fun setupFramebuffers() {
        val group = shaderGroup ?: return
        try {
            group.createBindFramebuffers(mc.displayWidth, mc.displayHeight)
        } catch (e: Exception) {
            ClientUtils.LOGGER.error("Exception caught while setting up shader group", e)
        }
    }

    /**
     * [x]/[y]/[w]/[h] 为真实 framebuffer 像素坐标（GL 原点在左下角），
     * 与 blurArea.fsh 的区域判断约定一致，不依赖 guiScale。
     */
    private fun setValues(strength: Float, x: Float, y: Float, w: Float, h: Float, force: Boolean = false) {
        if (!force && strength == lastStrength && lastX == x && lastY == y && lastW == w && lastH == h) return
        lastStrength = strength
        lastX = x
        lastY = y
        lastW = w
        lastH = h

        val group = shaderGroup ?: return
        for (i in group.listShaders.indices) {
            val manager = group.listShaders[i].shaderManager ?: continue
            manager.getShaderUniform("Radius")?.set(strength)
            manager.getShaderUniform("BlurXY")?.set(x, y)
            manager.getShaderUniform("BlurCoord")?.set(w, h)
        }
    }

    @JvmStatic
    fun blur(posX: Float, posY: Float, posXEnd: Float, posYEnd: Float, blurStrength: Float, displayClipMask: Boolean, triggerMethod: () -> Unit) {
        if (!OpenGlHelper.isFramebufferEnabled()) return
        // 平台不支持桌面着色器时直接跳过，避免每帧产生 GL 报错
        if (!ShaderSupport.isSupported) return
        if (!ensureShaderGroup()) return

        val group = shaderGroup ?: return
        val blurFramebuffer = framebuffer ?: return
        val resultFramebuffer = frbuffer ?: return

        var x = posX
        var y = posY
        var x2 = posXEnd
        var y2 = posYEnd

        if (x > x2) {
            val z = x
            x = x2
            x2 = z
        }

        if (y > y2) {
            val z = y
            y = y2
            y2 = z
        }

        val sc = ScaledResolution(mc)
        val scaleFactor = sc.scaleFactor
        val width = sc.scaledWidth
        val height = sc.scaledHeight

        if (sizeHasChanged(scaleFactor, width, height)) {
            setupFramebuffers()
        }

        lastFactor = scaleFactor
        lastWidth = width
        lastHeight = height

        // 换算成真实像素坐标（GL 原点在左下角），不再假设 guiScale=2
        val px = x * scaleFactor
        val py = mc.displayHeight - y2 * scaleFactor
        setValues(blurStrength, px, py, (x2 - x) * scaleFactor, (y2 - y) * scaleFactor)

        blurFramebuffer.bindFramebuffer(true)
        group.loadShaderGroup(mc.timer.renderPartialTicks)
        mc.framebuffer.bindFramebuffer(true)

        // 记录将被修改的 alpha 状态（Stencil.erase 会把 alphaFunc 改为 GREATER 0）
        val prevAlphaEnabled = GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
        val alphaRefBuf = BufferUtils.createFloatBuffer(16)
        GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF, alphaRefBuf)
        val prevAlphaRef = alphaRefBuf.get(0)

        try {
            Stencil.write(displayClipMask)
            triggerMethod()

            Stencil.erase(true)
            val prevBlend = GL11.glIsEnabled(GL11.GL_BLEND)
            GlStateManager.enableBlend()
            GlStateManager.blendFunc(770, 771)
            GlStateManager.pushMatrix()
            GlStateManager.colorMask(true, true, true, false)
            GlStateManager.disableDepth()
            GlStateManager.depthMask(false)
            GlStateManager.enableTexture2D()
            GlStateManager.disableLighting()
            GlStateManager.disableAlpha()
            resultFramebuffer.bindFramebufferTexture()
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F)
            val f2 = resultFramebuffer.framebufferWidth.toDouble() / resultFramebuffer.framebufferTextureWidth.toDouble()
            val f3 = resultFramebuffer.framebufferHeight.toDouble() / resultFramebuffer.framebufferTextureHeight.toDouble()
            val tessellator = Tessellator.getInstance()
            val worldrenderer = tessellator.worldRenderer
            worldrenderer.begin(7, DefaultVertexFormats.POSITION_TEX_COLOR)
            worldrenderer.pos(0.0, height.toDouble(), 0.0).tex(0.0, 0.0).color(255, 255, 255, 255).endVertex()
            worldrenderer.pos(width.toDouble(), height.toDouble(), 0.0).tex(f2, 0.0).color(255, 255, 255, 255).endVertex()
            worldrenderer.pos(width.toDouble(), 0.0, 0.0).tex(f2, f3).color(255, 255, 255, 255).endVertex()
            worldrenderer.pos(0.0, 0.0, 0.0).tex(0.0, f3).color(255, 255, 255, 255).endVertex()
            tessellator.draw()
            resultFramebuffer.unbindFramebufferTexture()
            GlStateManager.enableDepth()
            GlStateManager.depthMask(true)
            GlStateManager.colorMask(true, true, true, true)
            GlStateManager.popMatrix()
            // 恢复进入时的 blend 状态，避免泄漏
            if (prevBlend) GlStateManager.enableBlend() else GlStateManager.disableBlend()
        } finally {
            // 异常路径也必须 dispose，否则 stencil/colorMask 残留会导致后续渲染错乱
            Stencil.dispose()
            // 恢复进入时的 alpha 状态与 alphaFunc（Stencil.erase/修改过 alphaFunc）
            if (prevAlphaEnabled) {
                GlStateManager.enableAlpha()
                GlStateManager.alphaFunc(GL11.GL_GREATER, prevAlphaRef)
            } else {
                GlStateManager.disableAlpha()
            }
        }
    }

    @JvmStatic
    fun blurArea(x: Float, y: Float, x2: Float, y2: Float, blurStrength: Float) = blur(x, y, x2, y2, blurStrength, false) {
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        RenderUtils.quickDrawRect(x, y, x2, y2)
        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
    }

    @JvmStatic
    fun blurAreaRounded(x: Float, y: Float, x2: Float, y2: Float, rad: Float, blurStrength: Float) = blur(x, y, x2, y2, blurStrength, false) {
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        RenderUtils.drawRoundedRect(x, y, x2, y2, -0x1, rad)
        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
    }

    fun sizeHasChanged(scaleFactor: Int, width: Int, height: Int): Boolean = (lastFactor != scaleFactor || lastWidth != width || lastHeight != height)
}
