/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Blob background shader for the main menu.
 * Ported from Flux Client (today.flux.utility.shader.GLSLShader + ShaderBlob).
 * Original bubble shader: "hatsuyuki" by Catzpaw 2016.
 *
 * Also supports user-provided .frag fragment shader sources (local file selection).
 */
package net.airplus.utils.render.shader;

import net.airplus.utils.render.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.apache.commons.io.IOUtils;
import org.lwjgl.opengl.ARBFragmentShader;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.ARBVertexShader;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Fullscreen animated background (GLSL program with a single vertex + fragment stage).
 * Merges Flux's GLSLShader base class and its ShaderBlob subclass into one class.
 *
 * Two constructors:
 * - no-arg: the built-in bubble shader (assets/minecraft/airplus/shader/blob.frag)
 * - String: a user-provided GLSL fragment shader source (vertex stage stays built-in);
 *   "resolution" (vec2) and "time" (float) uniforms are exposed to the user shader when present.
 */
public class FluxBlobShader {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final String VERTEX_RESOURCE = "/assets/minecraft/airplus/shader/vertex.vert";
    private static final String FRAGMENT_RESOURCE = "/assets/minecraft/airplus/shader/blob.frag";

    private int program = 0;
    private float time;

    private Map<String, Integer> uniformsMap;

    public FluxBlobShader() {
        this(readFragmentResource(FRAGMENT_RESOURCE));
    }

    public FluxBlobShader(String fragmentShaderSource) {
        if (fragmentShaderSource == null)
            return;

        int vertexShaderID;
        int fragmentShaderID;

        try {
            InputStream vertexStream = FluxBlobShader.class.getResourceAsStream(VERTEX_RESOURCE);
            vertexShaderID = createShader(IOUtils.toString(vertexStream), ARBVertexShader.GL_VERTEX_SHADER_ARB);
            IOUtils.closeQuietly(vertexStream);

            fragmentShaderID = createShader(fragmentShaderSource, ARBFragmentShader.GL_FRAGMENT_SHADER_ARB);
        } catch (final Exception e) {
            e.printStackTrace();
            return;
        }

        if (vertexShaderID == 0 || fragmentShaderID == 0)
            return;

        program = ARBShaderObjects.glCreateProgramObjectARB();

        if (program == 0)
            return;

        ARBShaderObjects.glAttachObjectARB(program, vertexShaderID);
        ARBShaderObjects.glAttachObjectARB(program, fragmentShaderID);

        ARBShaderObjects.glLinkProgramARB(program);
        ARBShaderObjects.glValidateProgramARB(program);
    }

    /** Reads a classpath resource as text; returns null on failure (shader simply won't be available). */
    private static String readFragmentResource(String resourcePath) {
        try {
            InputStream stream = FluxBlobShader.class.getResourceAsStream(resourcePath);
            if (stream == null)
                return null;
            return IOUtils.toString(stream);
        } catch (final Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /** @return true when the program compiled and linked successfully. */
    public boolean isAvailable() {
        return program != 0;
    }

    /** Deletes the underlying GL program (call when replacing the shader to avoid leaks). */
    public void destroy() {
        if (program != 0) {
            ARBShaderObjects.glDeleteObjectARB(program);
            program = 0;
        }
        uniformsMap = null;
    }

    public void renderShader(int width, int height) {
        try {
            startShader();
            final Tessellator instance = Tessellator.getInstance();
            final WorldRenderer worldRenderer = instance.getWorldRenderer();
            worldRenderer.begin(7, DefaultVertexFormats.POSITION);
            worldRenderer.pos(0, height, 0.0D).endVertex();
            worldRenderer.pos(width, height, 0.0D).endVertex();
            worldRenderer.pos(width, 0, 0.0D).endVertex();
            worldRenderer.pos(0, 0, 0.0D).endVertex();
            instance.draw();
            stopShader();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void startShader() {
        GL11.glPushMatrix();
        GL20.glUseProgram(program);

        if (uniformsMap == null) {
            uniformsMap = new HashMap<>();
            setupUniform("resolution");
            setupUniform("time");
        }

        updateUniforms();
    }

    private void stopShader() {
        GL20.glUseProgram(0);
        GL11.glPopMatrix();
    }

    private void setupUniform(final String uniformName) {
        uniformsMap.put(uniformName, GL20.glGetUniformLocation(program, uniformName));
    }

    private void updateUniforms() {
        final ScaledResolution scaledResolution = new ScaledResolution(mc);

        final int resolutionID = uniformsMap.get("resolution");
        if (resolutionID > -1) {
            GL20.glUniform2f(resolutionID, (float) scaledResolution.getScaledWidth() * 2, (float) scaledResolution.getScaledHeight() * 2);
        }

        final int timeID = uniformsMap.get("time");
        if (timeID > -1) {
            GL20.glUniform1f(timeID, time);
        }

        time += 0.001f * RenderUtils.INSTANCE.getDeltaTime();
    }

    private int createShader(String shaderSource, int shaderType) {
        int shader = 0;

        try {
            shader = ARBShaderObjects.glCreateShaderObjectARB(shaderType);

            if (shader == 0)
                return 0;

            ARBShaderObjects.glShaderSourceARB(shader, shaderSource);
            ARBShaderObjects.glCompileShaderARB(shader);

            if (ARBShaderObjects.glGetObjectParameteriARB(shader, ARBShaderObjects.GL_OBJECT_COMPILE_STATUS_ARB) == GL11.GL_FALSE)
                throw new RuntimeException("Error creating shader: " + getLogInfo(shader));

            return shader;
        } catch (final Exception e) {
            ARBShaderObjects.glDeleteObjectARB(shader);
            throw e;
        }
    }

    private String getLogInfo(int i) {
        return ARBShaderObjects.glGetInfoLogARB(i, ARBShaderObjects.glGetObjectParameteriARB(i, ARBShaderObjects.GL_OBJECT_INFO_LOG_LENGTH_ARB));
    }
}
