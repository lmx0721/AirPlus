package net.airplus.ui.glass;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import java.nio.IntBuffer;

/**
 * Dual Kawase blur of the main framebuffer. The result is cached until {@link #invalidate()},
 * so every glass surface drawn in the same stage of a frame shares a single blur.
 * <p>
 * Two pyramids are kept: the full-strength one every resting surface uses, and a second one
 * for a partial strength, so a screen can fade its blur in and out as it opens and closes.
 */
public final class GlassBlur {
    private GlassBlur() { }

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int GL_FRAMEBUFFER_BINDING = 0x8CA6;
    private static final IntBuffer VIEWPORT = BufferUtils.createIntBuffer(16);
    private static final float FULL_OFFSET = 1.6f;

    private static Framebuffer[] full = new Framebuffer[0], part = new Framebuffer[0];
    private static int builtWidth, builtHeight;
    private static boolean validFull, validPart, failed;
    private static float partStrength = -1;

    /** Drop the cached blur; the next surface re-blurs what is on screen at that moment. */
    public static void invalidate() {
        validFull = validPart = false;
    }

    public static boolean available() {
        return !failed && OpenGlHelper.shadersSupported && OpenGlHelper.isFramebufferEnabled()
                && mc.getFramebuffer() != null && GlassShaders.down().ok && GlassShaders.up().ok;
    }

    /** @return the blurred screen texture (half resolution), or -1 when blur cannot be used */
    public static int texture() {
        return texture(1f);
    }

    /**
     * Render the full-strength blur now, from whatever is on screen at this moment, so surfaces
     * drawn later this frame all sample the same clean scene instead of whatever had been drawn
     * by the time the first of them asked. Call right after {@link #invalidate()}.
     */
    public static void prime() {
        texture(1f);
    }

    /**
     * @param strength 0 (barely blurred) .. 1 (full blur)
     * @return the screen blurred to that strength, or -1 when blur cannot be used
     */
    public static int texture(float strength) {
        if (!available()) return -1;
        try {
            if (strength >= 0.995f) {
                if (!validFull) {
                    render(false, Integer.MAX_VALUE, FULL_OFFSET);
                    validFull = true;
                }
                return full[0].framebufferTexture;
            }
            // Quantised so surfaces asking for almost the same strength share one render.
            float q = Math.round(Math.max(0, strength) * 64) / 64f;
            if (!validPart || q != partStrength) {
                int count = levelCount(mc.getFramebuffer().framebufferTextureHeight);
                float x = q * count;
                int n = Math.max(1, (int) Math.ceil(x));
                float f = Math.max(0, x - (n - 1));
                // One more level doubles the radius, so the offset restarts at half.
                float offset = n == 1 ? Anim.lerp(0.35f, FULL_OFFSET, f) : Anim.lerp(0.8f, FULL_OFFSET, f);
                render(true, n, offset);
                partStrength = q;
                validPart = true;
            }
            return part[0].framebufferTexture;
        } catch (RuntimeException e) {
            failed = true;
            net.airplus.utils.client.ClientUtils.INSTANCE.getLOGGER().error("[Glass] blur disabled", e);
            return -1;
        }
    }

    private static int levelCount(int height) {
        return height >= 1300 ? 4 : 3;
    }

    private static Framebuffer[] pyramid(int width, int height, int count) {
        Framebuffer[] levels = new Framebuffer[count];
        for (int i = 0; i < count; i++) {
            int w = Math.max(1, width >> (i + 1)), h = Math.max(1, height >> (i + 1));
            Framebuffer level = new Framebuffer(w, h, false);
            level.setFramebufferFilter(GL11.GL_LINEAR);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, level.framebufferTexture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            levels[i] = level;
        }
        return levels;
    }

    private static void build(int width, int height) {
        for (Framebuffer level : full) level.deleteFramebuffer();
        for (Framebuffer level : part) level.deleteFramebuffer();
        int count = levelCount(height);
        full = pyramid(width, height, count);
        part = pyramid(width, height, count);
        builtWidth = width;
        builtHeight = height;
        validFull = validPart = false;
    }

    /**
     * @param partial which pyramid to fill
     * @param depth   how many levels to blur through (clamped to the pyramid)
     * @param offset  sample spread; larger is blurrier
     */
    private static void render(boolean partial, int depth, float offset) {
        Framebuffer main = mc.getFramebuffer();
        int width = main.framebufferTextureWidth, height = main.framebufferTextureHeight;
        if (width <= 0 || height <= 0) throw new IllegalStateException("empty framebuffer");
        if (Draw.PERF) Draw.statBlurs++;

        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousFramebuffer = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);
        int previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        VIEWPORT.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
        // 记录 unit0 进入前的真实绑定（pass() 用裸 GL 绑定，不经过守卫缓存）
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int previousTexture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            if (width != builtWidth || height != builtHeight || full.length == 0) {
                build(width, height);
            }
            Framebuffer[] levels = partial ? part : full;
            int n = Math.max(1, Math.min(depth, levels.length));
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDepthMask(false);
            GL11.glColorMask(true, true, true, true);
            GL11.glEnable(GL11.GL_TEXTURE_2D);

            GlassShaders.Program down = GlassShaders.down();
            GL20.glUseProgram(down.id);
            GL20.glUniform1i(down.loc("tex"), 0);
            GL20.glUniform1f(down.loc("offset"), offset);
            int source = main.framebufferTexture, sw = width, sh = height;
            for (int i = 0; i < n; i++) {
                Framebuffer level = levels[i];
                pass(down, source, sw, sh, level);
                source = level.framebufferTexture;
                sw = level.framebufferTextureWidth;
                sh = level.framebufferTextureHeight;
            }
            GlassShaders.Program up = GlassShaders.up();
            GL20.glUseProgram(up.id);
            GL20.glUniform1i(up.loc("tex"), 0);
            GL20.glUniform1f(up.loc("offset"), offset);
            for (int i = n - 2; i >= 0; i--) {
                Framebuffer from = levels[i + 1];
                pass(up, from.framebufferTexture, from.framebufferTextureWidth, from.framebufferTextureHeight, levels[i]);
            }
        } finally {
            GL20.glUseProgram(previousProgram);
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, previousFramebuffer);
            GL11.glPopAttrib();
            GL11.glViewport(VIEWPORT.get(0), VIEWPORT.get(1), VIEWPORT.get(2), VIEWPORT.get(3));
            // 重同步守卫缓存：pass() 的裸 GL 绑定不经过 GlStateManager，缓存仍记着进入前的
            // 纹理。若不修正，后续 GlStateManager.bindTexture 撞缓存值时被静默跳过，
            // 字形 quad 继续采样模糊金字塔纹理 —— 整屏文字变成模糊方块。
            // 两步法（先绑 0 推开缓存，再绑回进入前纹理）保证 真实 == 缓存 == 进入前。
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager.bindTexture(0);
            GlStateManager.bindTexture(previousTexture0);
            GL13.glActiveTexture(previousUnit);
        }
    }

    private static void pass(GlassShaders.Program program, int texture, int texWidth, int texHeight, Framebuffer target) {
        OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, target.framebufferObject);
        GL11.glViewport(0, 0, target.framebufferTextureWidth, target.framebufferTextureHeight);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL20.glUniform2f(program.loc("halfpixel"), 0.5f / texWidth, 0.5f / texHeight);
        quad();
    }

    private static void quad() {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 0); GL11.glVertex2f(-1, -1);
        GL11.glTexCoord2f(1, 0); GL11.glVertex2f(1, -1);
        GL11.glTexCoord2f(1, 1); GL11.glVertex2f(1, 1);
        GL11.glTexCoord2f(0, 1); GL11.glVertex2f(-1, 1);
        GL11.glEnd();
    }

    private static Framebuffer snapshot;

    /**
     * Copies the main framebuffer as it is right now into a private full-size texture, leaving
     * all GL state as it was. Called at the start of a frame it holds the previous frame, so a
     * screen that has just closed can still be shown fading out.
     *
     * @return the snapshot texture, or -1 when it could not be taken
     */
    public static int captureSnapshot() {
        if (!available()) return -1;
        GlassShaders.Program copy = GlassShaders.copy();
        Framebuffer main = mc.getFramebuffer();
        if (!copy.ok || main == null) return -1;
        int width = main.framebufferTextureWidth, height = main.framebufferTextureHeight;
        if (width <= 0 || height <= 0) return -1;
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousFramebuffer = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);
        int previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        VIEWPORT.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
        // 记录 unit0 进入前的真实绑定（快照拷贝用裸 GL 绑定，不经过守卫缓存）
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int previousTexture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            if (snapshot == null || snapshot.framebufferTextureWidth != width || snapshot.framebufferTextureHeight != height) {
                if (snapshot != null) snapshot.deleteFramebuffer();
                snapshot = new Framebuffer(width, height, false);
                snapshot.setFramebufferFilter(GL11.GL_LINEAR);
            }
            Framebuffer target = snapshot;
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDepthMask(false);
            GL11.glColorMask(true, true, true, true);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL20.glUseProgram(copy.id);
            GL20.glUniform1i(copy.loc("tex"), 0);
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, target.framebufferObject);
            GL11.glViewport(0, 0, target.framebufferTextureWidth, target.framebufferTextureHeight);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, main.framebufferTexture);
            quad();
        } catch (RuntimeException e) {
            net.airplus.utils.client.ClientUtils.INSTANCE.getLOGGER().error("[Glass] snapshot failed", e);
            return -1;
        } finally {
            GL20.glUseProgram(previousProgram);
            OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, previousFramebuffer);
            GL11.glPopAttrib();
            GL11.glViewport(VIEWPORT.get(0), VIEWPORT.get(1), VIEWPORT.get(2), VIEWPORT.get(3));
            // 重同步守卫缓存（裸 GL 绑定不经过 GlStateManager，理由同 render()）
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager.bindTexture(0);
            GlStateManager.bindTexture(previousTexture0);
            GL13.glActiveTexture(previousUnit);
        }
        return snapshot.framebufferTexture;
    }

    /**
     * Draws the blurred screen over the whole viewport, ignoring the current matrices. Callers
     * limit the area with scissor or stencil (this is what the legacy BlurUtil.blur did).
     */
    public static boolean drawFullscreen() {
        return drawFullscreen(1f);
    }

    /** {@link #drawFullscreen()} with the blur at {@code strength} (0..1, see {@link #texture(float)}). */
    public static boolean drawFullscreen(float strength) {
        int texture = texture(strength);
        GlassShaders.Program copy = GlassShaders.copy();
        if (texture == -1 || !copy.ok) return false;
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousUnit = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int previousTexture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_TEXTURE_BIT);
        try {
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL20.glUseProgram(copy.id);
            GL20.glUniform1i(copy.loc("tex"), 0);
            quad();
        } finally {
            GL20.glUseProgram(previousProgram);
            GL11.glPopAttrib();
            // 重同步守卫缓存（裸 GL 绑定不经过 GlStateManager，理由同 render()）
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager.bindTexture(0);
            GlStateManager.bindTexture(previousTexture0);
            GL13.glActiveTexture(previousUnit);
        }
        return true;
    }
}
