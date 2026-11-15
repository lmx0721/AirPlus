package net.airplus.utils.render.shader.shaders

import net.airplus.utils.render.shader.Shader
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import org.lwjgl.opengl.GL11
import java.awt.Color
import kotlin.math.abs

class RoundedRectShader : Shader("roundedrect.frag") {
    override fun setupUniforms() {
        setupUniform("u_size")
        setupUniform("u_radius")
        setupUniform("u_color")
        setupUniform("u_scale")
    }

    override fun updateUniforms() {
        setUniformf("u_scale", ScaledResolution(mc).scaleFactor.toFloat())
    }

    companion object {
        @JvmField
        val INSTANCE = RoundedRectShader()

        fun draw(x: Float, y: Float, x2: Float, y2: Float, radius: Float, color: Color): RoundedRectShader {
            val width = abs(x2 - x)
            val height = abs(y2 - y)
            val instance = INSTANCE

            net.airplus.utils.render.GLErrorProbe.drain("F-rr-in")
            instance.startShader()

            instance.setUniformf("u_size", width, height)
            instance.setUniformf("u_radius", radius)
            instance.setUniformf("u_color", color.red / 255f, color.green / 255f, color.blue / 255f, color.alpha / 255f)

            GlStateManager.enableBlend()
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
            GlStateManager.enableAlpha()
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.0f)
            drawQuad(x, y, width, height)
            GlStateManager.disableBlend()

            net.airplus.utils.render.GLErrorProbe.drain("G-rr-out")
            instance.stopShader()

            return instance
        }
    }
}
