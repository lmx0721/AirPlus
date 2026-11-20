package net.airplus.ui.glass;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * Keeps Minecraft's {@link GlStateManager} cache in step with the real GL state.
 * <p>
 * GlStateManager skips a call when its cache already holds the requested value. If anything
 * changes GL behind its back (raw {@code GL11.glEnable/glDisable}, {@code glPopAttrib}), the
 * cache and GL disagree and later {@code enableBlend()} calls do nothing: glyphs then draw as
 * solid blocks and soft shadows as black boxes. Every setter here first moves the cache to the
 * opposite value and then to the wanted one, so the second call always reaches GL whatever
 * the two disagreed about. No {@code glGet} queries, so it never stalls the pipeline.
 * <p>
 * Rule for new code: never wrap GlStateManager calls in {@code glPushAttrib/glPopAttrib}; if
 * raw GL is unavoidable, call {@link #restore(int)} or {@link #hudDefaults()} afterwards.
 */
public final class GlState {
    private GlState() { }

    public static final int BLEND = 1, ALPHA = 1 << 1, DEPTH = 1 << 2, CULL = 1 << 3, LIGHTING = 1 << 4, TEXTURE = 1 << 5;

    /**
     * The real state of the capabilities that matter for 2D drawing. Uses {@code glIsEnabled},
     * so call it only on paths that are not per entity.
     */
    public static int capture() {
        int mask = 0;
        if (GL11.glIsEnabled(GL11.GL_BLEND)) mask |= BLEND;
        if (GL11.glIsEnabled(GL11.GL_ALPHA_TEST)) mask |= ALPHA;
        if (GL11.glIsEnabled(GL11.GL_DEPTH_TEST)) mask |= DEPTH;
        if (GL11.glIsEnabled(GL11.GL_CULL_FACE)) mask |= CULL;
        if (GL11.glIsEnabled(GL11.GL_LIGHTING)) mask |= LIGHTING;
        if (GL11.glIsEnabled(GL11.GL_TEXTURE_2D)) mask |= TEXTURE;
        return mask;
    }

    /** Put GL and the cache back to a state taken with {@link #capture()}. */
    public static void restore(int mask) {
        blend((mask & BLEND) != 0);
        alpha((mask & ALPHA) != 0);
        depth((mask & DEPTH) != 0);
        cull((mask & CULL) != 0);
        lighting((mask & LIGHTING) != 0);
        texture((mask & TEXTURE) != 0);
        GlStateManager.resetColor();
    }

    /**
     * The state HUD drawing expects: blending with the usual function, alpha test, textures,
     * no lighting, no shader program, texture unit 0, white colour. Cheap enough to call at
     * the start of every overlay stage.
     */
    public static void hudDefaults() {
        Draw.resetBatch();
        GL20.glUseProgram(0);
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        blend(true);
        GlStateManager.tryBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ONE);
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        alpha(true);
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
        texture(true);
        lighting(false);
        GlStateManager.resetColor();
        GlStateManager.color(1, 1, 1, 1);
    }

    public static void blend(boolean on) {
        if (on) { GlStateManager.disableBlend(); GlStateManager.enableBlend(); }
        else { GlStateManager.enableBlend(); GlStateManager.disableBlend(); }
    }

    public static void alpha(boolean on) {
        if (on) { GlStateManager.disableAlpha(); GlStateManager.enableAlpha(); }
        else { GlStateManager.enableAlpha(); GlStateManager.disableAlpha(); }
    }

    public static void depth(boolean on) {
        if (on) { GlStateManager.disableDepth(); GlStateManager.enableDepth(); }
        else { GlStateManager.enableDepth(); GlStateManager.disableDepth(); }
    }

    public static void cull(boolean on) {
        if (on) { GlStateManager.disableCull(); GlStateManager.enableCull(); }
        else { GlStateManager.enableCull(); GlStateManager.disableCull(); }
    }

    public static void lighting(boolean on) {
        if (on) { GlStateManager.disableLighting(); GlStateManager.enableLighting(); }
        else { GlStateManager.enableLighting(); GlStateManager.disableLighting(); }
    }

    public static void texture(boolean on) {
        if (on) { GlStateManager.disableTexture2D(); GlStateManager.enableTexture2D(); }
        else { GlStateManager.enableTexture2D(); GlStateManager.disableTexture2D(); }
    }
}
