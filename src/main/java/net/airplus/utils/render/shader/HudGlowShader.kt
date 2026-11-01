package net.airplus.utils.render.shader

import net.minecraft.client.renderer.OpenGlHelper
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL20

/**
 * HUD 模糊辉光着色器（HudBlur 的 Glow 叠加 pass 专用）。
 *
 * 输入：HudBlur 的全屏预模糊快照（源内容已经被模糊过，无需再做模糊）。
 * 输出：加法混合（GL_ONE, GL_ONE / alpha 通道 GL_ZERO, GL_ONE）使用的调制色：
 * - 遮罩矩形（SDF 圆角矩形，内区）内：场景亮部按亮度权重保留、暗部压低 → 亮部辉光；
 * - 矩形外：按到遮罩边界的 SDF 距离做高斯衰减（C1 连续，边缘无硬圈）→ 边缘光晕。
 *
 * 注意：GLSL 源码必须保持纯 ASCII —— LWJGL2 的 glShaderSource(CharSequence) 会把
 * 非 ASCII 字符编码为字节 0x1A（DOS EOF 标记），NVIDIA 编译器读到这里直接截断导致
 * 编译失败。说明只能写在 Kotlin 注释里，绝不能写进 shader 字符串。
 */
object HudGlowShader {

    private var programID: Int = -1
    private var uniformTexture = -1
    private var uniformIntensity = -1
    private var uniformCenterPx = -1
    private var uniformInnerHalfPx = -1
    private var uniformRadiusPx = -1
    private var uniformFalloffPx = -1
    private var uniformTexelScreen = -1
    private var uniformSpreadPx = -1
    private var uniformTintColor = -1
    private var uniformTintMix = -1

    private val vertexSrc = "#version 120\nvoid main() { gl_TexCoord[0] = gl_MultiTexCoord0; gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex; }"

    private val fragmentSrc = """
        #version 120
        uniform sampler2D textureIn;
        uniform float intensity;
        uniform vec2 centerPx;
        uniform vec2 innerHalfPx;
        uniform float radiusPx;
        uniform float falloffPx;
        uniform vec2 texelScreen;
        uniform float spreadPx;
        uniform vec3 tintColor;
        uniform float tintMix;

        float roundedBoxSDF(vec2 p, vec2 b, float r) {
            vec2 q = abs(p) - b + vec2(r);
            return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
        }

        void main() {
            vec2 uv = gl_TexCoord[0].xy;
            vec3 src;
            if (spreadPx > 0.5) {
                // Cross-shaped 5-tap average: softens the glow source so the bloom
                // spreads smoothly around the blurred contour instead of hugging it.
                vec2 o = vec2(spreadPx) * texelScreen;
                vec3 c0 = texture2D(textureIn, uv).rgb;
                vec3 cs = texture2D(textureIn, uv + vec2(o.x, 0.0)).rgb
                        + texture2D(textureIn, uv - vec2(o.x, 0.0)).rgb
                        + texture2D(textureIn, uv + vec2(0.0, o.y)).rgb
                        + texture2D(textureIn, uv - vec2(0.0, o.y)).rgb;
                src = (c0 * 2.0 + cs) / 6.0;
            } else {
                src = texture2D(textureIn, uv).rgb;
            }
            float lum = dot(src, vec3(0.2126, 0.7152, 0.0722));
            float bright = smoothstep(0.0, 0.55, lum);
            float d = roundedBoxSDF(gl_FragCoord.xy - centerPx, innerHalfPx, radiusPx);
            // Gaussian falloff: inside the mask a = 1; outside a = exp(-d^2 / 2*sigma^2).
            // Both branches meet at d = 0 with zero slope (C1-continuous), so the halo
            // spreads smoothly from the edge instead of the old exp(-d/sigma) whose
            // slope jumps at the border and draws a visible hard rim ring.
            float a = (d <= 0.0) ? 1.0 : exp(-(d * d) / (2.0 * falloffPx * falloffPx));
            float k = intensity * bright * a;
            vec3 glowCol = mix(src, tintColor * min(lum * 1.4, 1.0), tintMix);
            gl_FragColor = vec4(glowCol * k, 1.0);
        }
    """.trimIndent()

    private fun ensureInitialized(): Boolean {
        if (programID > 0) return true
        if (programID == 0) return false

        val v = GL20.glCreateShader(GL20.GL_VERTEX_SHADER)
        GL20.glShaderSource(v, vertexSrc)
        GL20.glCompileShader(v)
        val f = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER)
        GL20.glShaderSource(f, fragmentSrc)
        GL20.glCompileShader(f)
        if (GL20.glGetShaderi(v, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE ||
            GL20.glGetShaderi(f, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE
        ) {
            System.err.println("[HudGlowShader] shader compile failed: ${GL20.glGetShaderInfoLog(f, 1024)}")
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
            System.err.println("[HudGlowShader] program link failed: ${GL20.glGetProgramInfoLog(programID, 1024)}")
            GL20.glDeleteProgram(programID)
            programID = 0
            return false
        }
        uniformTexture = GL20.glGetUniformLocation(programID, "textureIn")
        uniformIntensity = GL20.glGetUniformLocation(programID, "intensity")
        uniformCenterPx = GL20.glGetUniformLocation(programID, "centerPx")
        uniformInnerHalfPx = GL20.glGetUniformLocation(programID, "innerHalfPx")
        uniformRadiusPx = GL20.glGetUniformLocation(programID, "radiusPx")
        uniformFalloffPx = GL20.glGetUniformLocation(programID, "falloffPx")
        uniformTexelScreen = GL20.glGetUniformLocation(programID, "texelScreen")
        uniformSpreadPx = GL20.glGetUniformLocation(programID, "spreadPx")
        uniformTintColor = GL20.glGetUniformLocation(programID, "tintColor")
        uniformTintMix = GL20.glGetUniformLocation(programID, "tintMix")
        return true
    }

    /**
     * 绑定辉光程序并设置 uniforms（调用方负责 FBO/矩阵/混合/纹理绑定）。
     *
     * @param centerXPx 遮罩矩形中心 X（GL 窗口像素坐标，原点左下）
     * @param centerYPx 遮罩矩形中心 Y（GL 窗口像素坐标）
     * @param innerHalfXPx 遮罩矩形半宽（像素）
     * @param innerHalfYPx 遮罩矩形半高（像素）
     * @param radiusPx 遮罩圆角半径（像素）
     * @param falloffPx 边缘光晕高斯 σ（像素）
     * @param texelScreenX/Y 纹理 UV 每屏幕像素的跨度（= 1/快照结果逻辑尺寸，纹理覆盖全屏，
     *        与纹理实际分辨率无关）
     * @param spreadPx 辉光源十字扩散采样半径（像素，0 = 关闭扩散）
     * @param tintR/G/B 染色颜色（0..1）
     * @param tintMix 染色强度（0 = 场景原色，1 = 完全染成 tint 颜色）
     */
    fun use(
        intensity: Float,
        centerXPx: Float,
        centerYPx: Float,
        innerHalfXPx: Float,
        innerHalfYPx: Float,
        radiusPx: Float,
        falloffPx: Float,
        texelScreenX: Float = 0F,
        texelScreenY: Float = 0F,
        spreadPx: Float = 0F,
        tintR: Float = 1F,
        tintG: Float = 1F,
        tintB: Float = 1F,
        tintMix: Float = 0F
    ): Boolean {
        if (!ensureInitialized()) return false
        GL20.glUseProgram(programID)
        if (uniformTexture >= 0) GL20.glUniform1i(uniformTexture, 0)
        if (uniformIntensity >= 0) GL20.glUniform1f(uniformIntensity, intensity)
        if (uniformCenterPx >= 0) GL20.glUniform2f(uniformCenterPx, centerXPx, centerYPx)
        if (uniformInnerHalfPx >= 0) GL20.glUniform2f(uniformInnerHalfPx, innerHalfXPx, innerHalfYPx)
        if (uniformRadiusPx >= 0) GL20.glUniform1f(uniformRadiusPx, radiusPx)
        if (uniformFalloffPx >= 0) GL20.glUniform1f(uniformFalloffPx, falloffPx)
        if (uniformTexelScreen >= 0) GL20.glUniform2f(uniformTexelScreen, texelScreenX, texelScreenY)
        if (uniformSpreadPx >= 0) GL20.glUniform1f(uniformSpreadPx, spreadPx)
        if (uniformTintColor >= 0) GL20.glUniform3f(uniformTintColor, tintR, tintG, tintB)
        if (uniformTintMix >= 0) GL20.glUniform1f(uniformTintMix, tintMix)
        return true
    }

    fun unbind() {
        GL20.glUseProgram(0)
    }

    /** 把 unit0 切换并绑定纹理（裸 GL，绑定前后由调用方保存/恢复缓存状态） */
    fun bindTexture(texId: Int) {
        OpenGlHelper.setActiveTexture(GL13.GL_TEXTURE0)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId)
    }

    fun cleanup() {
        if (programID > 0) {
            GL20.glDeleteProgram(programID)
        }
        programID = -1
        uniformTexture = -1
        uniformIntensity = -1
        uniformCenterPx = -1
        uniformInnerHalfPx = -1
        uniformRadiusPx = -1
        uniformFalloffPx = -1
    }
}
