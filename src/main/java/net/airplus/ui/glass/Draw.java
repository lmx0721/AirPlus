package net.airplus.ui.glass;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.awt.Color;

/**
 * Antialiased 2D primitives for the glass UI. One quad per shape; corners, outlines, shadows,
 * glows and highlights are evaluated in the fragment shader.
 * <p>
 * Coordinates are "units": {@link #scale()} pixels each. Screens call {@link #useGuiScale()}
 * (Minecraft's GUI scale) or {@link #setScale(float)} before drawing.
 */
public final class Draw {
    private Draw() { }

    private static final Minecraft mc = Minecraft.getMinecraft();

    private static float scale = 2;
    private static float alpha = 1;
    private static final float[] alphaStack = new float[16];
    private static int alphaDepth;

    // Current transform: screenUnits = units * tScale + (tX, tY). Mirrors the GL matrix so
    // scissor rectangles and antialiasing stay correct while panels are animated.
    private static float tSX = 1, tSY = 1, tX, tY;
    private static final float[][] transformStack = new float[16][4];
    private static int transformDepth;

    // Base state: what setScale() resets to. A screen transition scales and fades a whole
    // screen through this, so the useGuiScale() calls inside that screen keep the transition.
    private static boolean baseActive;
    private static float baseAlpha = 1, baseScale = 1, baseTX, baseTY;

    // Blur strength (0..1) of the backdrop sampled by blurred()/glass(). The stage value
    // survives setScale(); it is how a screen fades its blur in and out.
    private static float stageBlur = 1, blurStrength = 1;

    // Clip stack: logical rectangle (units, for hit tests) and screen rectangle (for scissor).
    private static final float[][] clipStack = new float[16][8];
    private static int clipDepth;
    private static boolean leakReported;

    // ---------------------------------------------------------------- statistics

    /** Counters for the click GUI's performance overlay; only kept with {@code -Dairplus.guiPerf=true}. */
    public static final boolean PERF = Boolean.getBoolean("airplus.guiPerf");
    public static int statShapes, statBinds, statUniforms, statTexts, statBlurs;

    public static void resetStats() {
        statShapes = statBinds = statUniforms = statTexts = statBlurs = 0;
    }

    // ---------------------------------------------------------------- batching

    // Inside a batch the shape program stays bound and the 2D state stays set between
    // primitives instead of being torn down and rebuilt around each one. The pixels are the
    // same; only redundant state changes go. Both can be switched off to compare.
    private static boolean batchAllowed = !Boolean.getBoolean("airplus.draw.nobatch");
    private static boolean dedup = !Boolean.getBoolean("airplus.draw.nodedup");
    private static int batchDepth, boundProgram;
    private static boolean stateHeld;

    /** Turn batching and uniform de-duplication on or off at run time (for A/B checks). */
    public static void setOptimised(boolean on) {
        resetBatch();
        batchAllowed = on;
        dedup = on;
        invalidateUniforms();
    }

    /**
     * Start a run of glass primitives and {@link SmoothFont} text with nothing else in between.
     * Must be paired with {@link #endBatch()}; call {@link #flush()} before any other drawing.
     */
    public static void beginBatch() {
        if (batchDepth > 0 || (batchAllowed && GlassShaders.shape().ok)) batchDepth++;
    }

    public static void endBatch() {
        if (batchDepth > 0 && --batchDepth == 0) flush();
    }

    /** Unbind the program and put the 2D state back to what the rest of the game expects. */
    public static void flush() {
        if (boundProgram != 0) {
            GL20.glUseProgram(0);
            boundProgram = 0;
        }
        if (stateHeld) {
            stateHeld = false;
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GlStateManager.enableAlpha();
            GlStateManager.enableTexture2D();
            GlStateManager.resetColor();
            GlStateManager.color(1, 1, 1, 1);
        }
    }

    /** Safety net, once per frame: a batch that was never ended does not outlive the frame. */
    public static void resetBatch() {
        batchDepth = 0;
        flush();
    }

    static boolean batching() {
        return batchDepth > 0;
    }

    /** Before fixed-function drawing (text, lines): make sure no program is left bound. */
    static void fixedFunction() {
        if (boundProgram != 0) {
            GL20.glUseProgram(0);
            boundProgram = 0;
        }
    }

    /** A primitive leaves its blend / alpha-test / colour state for {@link #flush()} to undo. */
    static void holdState() {
        stateHeld = true;
    }

    private static void use(GlassShaders.Program program) {
        if (batchDepth > 0) {
            if (boundProgram == program.id) return;
            boundProgram = program.id;
        }
        GL20.glUseProgram(program.id);
        if (PERF) statBinds++;
    }

    // ---------------------------------------------------------------- frame state

    /** Reset per-frame state and use Minecraft's GUI scale. */
    public static void useGuiScale() {
        syncGuiScale();
        setScale(syncScale);
    }

    /** Reset per-frame state and use {@code pixelsPerUnit}. */
    public static void setScale(float pixelsPerUnit) {
        scale = Math.max(0.25f, pixelsPerUnit);
        alpha = baseAlpha;
        alphaDepth = 0;
        tSX = tSY = baseScale;
        tX = baseTX;
        tY = baseTY;
        if (transformDepth > 0) {
            // A pushTransform without its popTransform (usually a handler that threw halfway):
            // drop its GL matrices too, or the modelview stack grows every frame until it overflows.
            for (int i = 0; i < transformDepth; i++) GlStateManager.popMatrix();
            if (!leakReported) {
                leakReported = true;
                net.airplus.utils.client.ClientUtils.INSTANCE.getLOGGER().warn("[Glass] {} pushTransform call(s) were not popped; popped them on setScale", transformDepth);
            }
        }
        transformDepth = 0;
        if (clipDepth > 0) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        clipDepth = 0;
        blurStrength = stageBlur;
    }

    /**
     * Scale everything drawn from now on by {@code s} around (cx, cy), move it down by
     * {@code dy} and fade the glass parts to {@code a}; survives {@link #setScale(float)}.
     * Vanilla text and items follow the scale (GL matrix) but not the fade.
     */
    public static void pushBase(float cx, float cy, float s, float dy, float a) {
        if (baseActive) return;
        baseActive = true;
        baseScale = s;
        baseTX = cx - cx * s;
        baseTY = cy + dy - cy * s;
        baseAlpha = clamp01(a);
        GlStateManager.pushMatrix();
        GlStateManager.translate(baseTX, baseTY, 0);
        GlStateManager.scale(s, s, 1);
        setScale(scale);
    }

    public static void popBase() {
        if (!baseActive) return;
        baseActive = false;
        baseScale = 1;
        baseTX = baseTY = 0;
        baseAlpha = 1;
        GlStateManager.popMatrix();
        setScale(scale);
    }

    public static boolean baseActive() {
        return baseActive;
    }

    /** Set the backdrop blur strength (0 sharp .. 1 full) for the rest of this stage. */
    public static void stageBlur(float strength) {
        stageBlur = blurStrength = clamp01(strength);
    }

    public static float blurStrength() {
        return blurStrength;
    }

    /** For legacy callers that draw outside a glass screen: follow Minecraft's GUI scale. */
    public static void syncGuiScale() {
        if (mc.displayWidth != syncWidth || mc.displayHeight != syncHeight || mc.gameSettings.guiScale != syncSetting) {
            syncWidth = mc.displayWidth;
            syncHeight = mc.displayHeight;
            syncSetting = mc.gameSettings.guiScale;
            syncScale = new ScaledResolution(mc).getScaleFactor();
        }
        scale = syncScale;
    }

    private static int syncWidth, syncHeight, syncSetting = -1;
    private static float syncScale = 2;

    public static float scale() { return scale; }
    public static float alpha() { return alpha; }

    /**
     * Maps a rectangle in the current units (through every pushed transform) to screen units, i.e.
     * the GUI coordinates mouse events use: {@code out = {x, y, w, h}}.
     */
    public static void toScreen(float x, float y, float w, float h, float[] out) {
        out[0] = x * tSX + tX;
        out[1] = y * tSY + tY;
        out[2] = w * tSX;
        out[3] = h * tSY;
    }

    /** Minecraft's GUI scale factor, without changing the current {@link #scale()}. */
    public static float guiScaleFactor() {
        float current = scale;
        syncGuiScale();
        float gui = scale;
        scale = current;
        return gui;
    }

    /**
     * A focused text field drew its caret at (x, y) with height h (units, current transform):
     * tell the input method, so its windows open next to it. Same mapping as {@link #pushClip}.
     * No-op here: AirPlus has no IME control wired to the glass package yet.
     */
    public static void reportCaret(float x, float y, float h) {
    }

    public static void pushAlpha(float factor) {
        if (alphaDepth < alphaStack.length) alphaStack[alphaDepth] = alpha;
        alphaDepth++;
        alpha *= clamp01(factor);
    }

    public static void popAlpha() {
        if (alphaDepth <= 0) return;
        alphaDepth--;
        if (alphaDepth < alphaStack.length) alpha = alphaStack[alphaDepth];
    }

    /** Scale by {@code s} around (cx, cy), then move by (dx, dy). */
    public static void pushTransform(float cx, float cy, float s, float dx, float dy) {
        pushTransform(cx, cy, s, s, dx, dy);
    }

    /** Scale by (sx, sy) around (cx, cy), then move by (dx, dy): squash and stretch. */
    public static void pushTransform(float cx, float cy, float sx, float sy, float dx, float dy) {
        if (transformDepth < transformStack.length) {
            float[] saved = transformStack[transformDepth];
            saved[0] = tSX; saved[1] = tSY; saved[2] = tX; saved[3] = tY;
        }
        transformDepth++;
        float ox = cx + dx - cx * sx, oy = cy + dy - cy * sy;
        tX = ox * tSX + tX;
        tY = oy * tSY + tY;
        tSX *= sx;
        tSY *= sy;
        GlStateManager.pushMatrix();
        GlStateManager.translate(ox, oy, 0);
        GlStateManager.scale(sx, sy, 1);
    }

    public static void popTransform() {
        if (transformDepth <= 0) return;
        transformDepth--;
        if (transformDepth < transformStack.length) {
            float[] saved = transformStack[transformDepth];
            tSX = saved[0]; tSY = saved[1]; tX = saved[2]; tY = saved[3];
        }
        GlStateManager.popMatrix();
    }

    // ---------------------------------------------------------------- clipping

    public static void pushClip(float x, float y, float w, float h) {
        float lx0 = x, ly0 = y, lx1 = x + w, ly1 = y + h;
        float sx0 = x * tSX + tX, sy0 = y * tSY + tY, sx1 = (x + w) * tSX + tX, sy1 = (y + h) * tSY + tY;
        if (clipDepth > 0 && clipDepth <= clipStack.length) {
            float[] top = clipStack[clipDepth - 1];
            lx0 = Math.max(lx0, top[0]); ly0 = Math.max(ly0, top[1]);
            lx1 = Math.min(lx1, top[2]); ly1 = Math.min(ly1, top[3]);
            sx0 = Math.max(sx0, top[4]); sy0 = Math.max(sy0, top[5]);
            sx1 = Math.min(sx1, top[6]); sy1 = Math.min(sy1, top[7]);
        }
        if (clipDepth < clipStack.length) {
            float[] entry = clipStack[clipDepth];
            entry[0] = lx0; entry[1] = ly0; entry[2] = lx1; entry[3] = ly1;
            entry[4] = sx0; entry[5] = sy0; entry[6] = sx1; entry[7] = sy1;
        }
        clipDepth++;
        applyScissor();
    }

    public static void popClip() {
        if (clipDepth <= 0) return;
        clipDepth--;
        applyScissor();
    }

    private static void applyScissor() {
        if (clipDepth <= 0) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            return;
        }
        float[] top = clipStack[Math.min(clipDepth, clipStack.length) - 1];
        int x0 = (int) Math.floor(top[4] * scale), x1 = (int) Math.ceil(top[6] * scale);
        int y0 = (int) Math.floor(top[5] * scale), y1 = (int) Math.ceil(top[7] * scale);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(Math.max(0, x0), Math.max(0, mc.displayHeight - y1), Math.max(0, x1 - x0), Math.max(0, y1 - y0));
    }

    /** True when the point (units) is inside the current clip rectangle. */
    public static boolean insideClip(float x, float y) {
        if (clipDepth <= 0) return true;
        float[] top = clipStack[Math.min(clipDepth, clipStack.length) - 1];
        return x >= top[0] && x < top[2] && y >= top[1] && y < top[3];
    }

    /** Intersect a rectangle (units) with the current clip. @return false when nothing is left */
    public static boolean clipTo(float[] rect) {
        if (clipDepth > 0) {
            float[] top = clipStack[Math.min(clipDepth, clipStack.length) - 1];
            rect[0] = Math.max(rect[0], top[0]); rect[1] = Math.max(rect[1], top[1]);
            rect[2] = Math.min(rect[2], top[2]); rect[3] = Math.min(rect[3], top[3]);
        }
        return rect[2] > rect[0] && rect[3] > rect[1];
    }

    // ---------------------------------------------------------------- shapes

    /** Antialiased open polyline through (x0, y0, x1, y1, ...). */
    public static void polyline(float width, int color, float... points) {
        if (alpha <= 0.003f) return;
        fixedFunction();
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(Math.max(1f, width * scale * (float) Math.sqrt(tSX * tSY)));
        GL11.glColor4f((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, (color >>> 24) / 255f * alpha);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        for (int i = 0; i + 1 < points.length; i += 2) GL11.glVertex2f(points[i], points[i + 1]);
        GL11.glEnd();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(1f);
        if (batchDepth > 0) {
            stateHeld = true;
            return;
        }
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.resetColor();
        GlStateManager.color(1, 1, 1, 1);
    }

    /** Filled quads, four (x, y) points each, the first {@code floats} values of {@code points}; flat colour. */
    public static void quads(int color, float[] points, int floats) {
        if (alpha <= 0.003f || floats < 8) return;
        fixedFunction();
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, (color >>> 24) / 255f * alpha);
        GL11.glBegin(GL11.GL_QUADS);
        for (int i = 0; i + 1 < floats; i += 2) GL11.glVertex2f(points[i], points[i + 1]);
        GL11.glEnd();
        if (batchDepth > 0) {
            stateHeld = true;
            return;
        }
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.resetColor();
        GlStateManager.color(1, 1, 1, 1);
    }

    /** A ">" chevron centred on (cx, cy), rotated by {@code degrees} (90 = pointing down). */
    public static void chevron(float cx, float cy, float size, float degrees, float width, int color) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(cx, cy, 0);
        GlStateManager.rotate(degrees, 0, 0, 1);
        polyline(width, color, -size / 2, -size, size / 2, 0, -size / 2, size);
        GlStateManager.popMatrix();
    }

    public static void rect(float x, float y, float w, float h, float r, int color) {
        shape(x, y, w, h, r, r, r, r, 0, 0, color, color, 0, 0, 0, 0, 0, 0, 0, -1, false);
    }

    /** Per-corner radii: top-left, top-right, bottom-right, bottom-left. */
    public static void rect(float x, float y, float w, float h, float tl, float tr, float br, float bl, int color) {
        shape(x, y, w, h, tl, tr, br, bl, 0, 0, color, color, 0, 0, 0, 0, 0, 0, 0, -1, false);
    }

    public static void gradientV(float x, float y, float w, float h, float r, int top, int bottom) {
        shape(x, y, w, h, r, r, r, r, 0, 0, top, bottom, 0, 1, 0, 0, 0, 0, 0, -1, false);
    }

    public static void gradientH(float x, float y, float w, float h, float r, int left, int right) {
        shape(x, y, w, h, r, r, r, r, 0, 0, left, right, 1, 0, 0, 0, 0, 0, 0, -1, false);
    }

    /** Outline drawn inside the rectangle, with a vertical gradient. */
    public static void outline(float x, float y, float w, float h, float r, float thickness, int top, int bottom) {
        shape(x, y, w, h, r, r, r, r, 0, thickness, top, bottom, 0, 1, 0, 0, 0, 0, 0, -1, false);
    }

    /** Soft drop shadow: the rectangle blurred outwards by {@code soft} units. */
    public static void shadow(float x, float y, float w, float h, float r, float soft, int color) {
        shape(x, y, w, h, r, r, r, r, soft, 0, color, color, 0, 0, 0, 0, 0, 0, 0, -1, false);
    }

    /** Additive halo around the rectangle. */
    public static void glow(float x, float y, float w, float h, float r, float soft, int color) {
        shape(x, y, w, h, r, r, r, r, soft, 0, color, color, 0, 0, 0, 0, 0, 0, 0, -1, true);
    }

    /** Fill limited to a radial falloff around (sx, sy): a highlight that follows the cursor. */
    public static void spot(float x, float y, float w, float h, float r, float sx, float sy, float radius, int color) {
        shape(x, y, w, h, r, r, r, r, 0, 0, color, color, 0, 0, sx - (x + w / 2), sy - (y + h / 2), radius, 0, 0, -1, true);
    }

    /** Outline limited to a radial falloff around (sx, sy). */
    public static void spotOutline(float x, float y, float w, float h, float r, float thickness, float sx, float sy, float radius, int color) {
        shape(x, y, w, h, r, r, r, r, 0, thickness, color, color, 0, 0, sx - (x + w / 2), sy - (y + h / 2), radius, 0, 0, -1, true);
    }

    /** {@link #spot} and {@link #spotOutline} with normal blending, for light surfaces where adding light shows nothing. */
    public static void spotBlend(float x, float y, float w, float h, float r, float thickness, float sx, float sy, float radius, int color) {
        shape(x, y, w, h, r, r, r, r, 0, thickness, color, color, 0, 0, sx - (x + w / 2), sy - (y + h / 2), radius, 0, 0, -1, false);
    }

    /**
     * Whether a rectangle (units) touches the current clip. Callers that skip drawing on this
     * must pass the rectangle grown by the reach of its glows and shadows.
     */
    public static boolean visible(float x, float y, float w, float h) {
        if (clipDepth <= 0) return true;
        float[] top = clipStack[Math.min(clipDepth, clipStack.length) - 1];
        return x + w > top[0] && x < top[2] && y + h > top[1] && y < top[3];
    }

    /** Diagonal band of light; {@code position} runs from 0 (left of the shape) to 1 (right). */
    public static void sheen(float x, float y, float w, float h, float r, float position, int color) {
        float span = w + h * 0.6f, width = Math.max(8, span * 0.16f);
        float pos = -span / 2 - width * 2 + (span + width * 4) * position;
        shape(x, y, w, h, r, r, r, r, 0, 0, color, color, 0, 0, 0, 0, 0, pos, width, -1, true);
    }

    public static void circle(float cx, float cy, float radius, int color) {
        rect(cx - radius, cy - radius, radius * 2, radius * 2, radius, color);
    }

    /** The blurred screen behind the rectangle. @return false when blur is unavailable */
    public static boolean blurred(float x, float y, float w, float h, float r, float opacity) {
        int texture = GlassBlur.texture(blurStrength);
        if (texture == -1 || !GlassShaders.shape().ok) return false;
        int color = ((int) (clamp01(opacity) * 255) << 24) | 0xffffff;
        shape(x, y, w, h, r, r, r, r, 0, 0, color, color, 0, 0, 0, 0, 0, 0, 0, texture, false);
        return true;
    }

    /** Blurred screen with per-corner radii: top-left, top-right, bottom-right, bottom-left. */
    public static boolean blurred(float x, float y, float w, float h, float tl, float tr, float br, float bl, float opacity) {
        int texture = GlassBlur.texture(blurStrength);
        if (texture == -1 || !GlassShaders.shape().ok) return false;
        int color = ((int) (clamp01(opacity) * 255) << 24) | 0xffffff;
        shape(x, y, w, h, tl, tr, br, bl, 0, 0, color, color, 0, 0, 0, 0, 0, 0, 0, texture, false);
        return true;
    }

    /** Rainbow strip for hue pickers. */
    public static void hueBar(float x, float y, float w, float h) {
        float step = w / 6f;
        for (int i = 0; i < 6; i++) {
            int a = 0xff000000 | Color.HSBtoRGB(i / 6f, 1, 1), b = 0xff000000 | Color.HSBtoRGB((i + 1) / 6f, 1, 1);
            float overlap = i < 5 ? 0.5f : 0;
            gradientH(x + step * i, y, step + overlap, h, 0, a, b);
        }
    }

    private static void shape(float x, float y, float w, float h, float tl, float tr, float br, float bl,
                              float soft, float stroke, int colA, int colB, float gx, float gy,
                              float spotX, float spotY, float spotR, float bandPos, float bandWidth,
                              int texture, boolean additive) {
        if (w <= 0 || h <= 0 || alpha <= 0.003f) return;
        float limit = Math.min(w, h) / 2;
        tl = Math.min(tl, limit); tr = Math.min(tr, limit); br = Math.min(br, limit); bl = Math.min(bl, limit);
        GlassShaders.Program program = GlassShaders.shape();
        if (!program.ok) {
            if (soft <= 0 && stroke <= 0 && spotR <= 0 && bandWidth <= 0 && texture == -1) legacyRect(x, y, w, h, tl, colA);
            return;
        }
        float aa = 1f / (scale * Math.min(tSX, tSY));
        float pad = soft > 0 ? soft : aa;
        float hw = w / 2, hh = h / 2, cx = x + hw, cy = y + hh;

        begin(additive, texture);
        use(program);
        if (PERF) statShapes++;
        if (uShape != program.id) resolveShape(program);
        // Uniform values live in the program object, so one that already holds the wanted
        // value is not sent again. The caches start as NaN, which never compares equal.
        if (!dedup || hw != cHalfW || hh != cHalfH) {
            GL20.glUniform2f(uHalfSize, cHalfW = hw, cHalfH = hh);
            if (PERF) statUniforms++;
        }
        if (!dedup || tl != cTl || tr != cTr || br != cBr || bl != cBl) {
            GL20.glUniform4f(uRadii, cTl = tl, cTr = tr, cBr = br, cBl = bl);
            if (PERF) statUniforms++;
        }
        if (!dedup || aa != cAa) {
            GL20.glUniform1f(uAa, cAa = aa);
            if (PERF) statUniforms++;
        }
        if (!dedup || soft != cSoft) {
            GL20.glUniform1f(uSoft, cSoft = soft);
            if (PERF) statUniforms++;
        }
        if (!dedup || stroke != cStroke) {
            GL20.glUniform1f(uStroke, cStroke = stroke);
            if (PERF) statUniforms++;
        }
        if (!dedup || colA != cColA || alpha != cAlphaA) {
            uniformColor(uColA, cColA = colA);
            cAlphaA = alpha;
            if (PERF) statUniforms++;
        }
        if (!dedup || colB != cColB || alpha != cAlphaB) {
            uniformColor(uColB, cColB = colB);
            cAlphaB = alpha;
            if (PERF) statUniforms++;
        }
        if (!dedup || gx != cGx || gy != cGy) {
            GL20.glUniform2f(uGrad, cGx = gx, cGy = gy);
            if (PERF) statUniforms++;
        }
        if (!dedup || spotX != cSpotX || spotY != cSpotY || spotR != cSpotR) {
            GL20.glUniform3f(uSpot, cSpotX = spotX, cSpotY = spotY, cSpotR = spotR);
            if (PERF) statUniforms++;
        }
        if (!dedup || bandPos != cBandPos || bandWidth != cBandWidth) {
            GL20.glUniform2f(uBand, cBandPos = bandPos, cBandWidth = bandWidth);
            if (PERF) statUniforms++;
        }
        float texMode = texture != -1 ? 1 : 0;
        if (!dedup || texMode != cTexMode) {
            GL20.glUniform1f(uTexMode, cTexMode = texMode);
            if (PERF) statUniforms++;
        }
        if (texture != -1) {
            float screenW = mc.displayWidth, screenH = mc.displayHeight;
            if (!dedup || !cTexSet) {
                GL20.glUniform1i(uTex, 0);
                cTexSet = true;
                if (PERF) statUniforms++;
            }
            if (!dedup || screenW != cScreenW || screenH != cScreenH) {
                GL20.glUniform2f(uScreen, cScreenW = screenW, cScreenH = screenH);
                if (PERF) statUniforms++;
            }
        }
        quad(cx, cy, hw + pad, hh + pad);
        end();
    }

    // Uniform locations of the shape program and the values it currently holds.
    private static int uShape = -1, uHalfSize, uRadii, uAa, uSoft, uStroke, uColA, uColB, uGrad, uSpot, uBand, uTexMode, uTex, uScreen;
    private static float cHalfW, cHalfH, cTl, cTr, cBr, cBl, cAa, cSoft, cStroke, cAlphaA, cAlphaB, cGx, cGy,
            cSpotX, cSpotY, cSpotR, cBandPos, cBandWidth, cTexMode, cScreenW, cScreenH;
    private static int cColA, cColB;
    private static boolean cTexSet;

    static {
        invalidateUniforms();
    }

    private static void resolveShape(GlassShaders.Program program) {
        uShape = program.id;
        uHalfSize = program.loc("halfSize");
        uRadii = program.loc("radii");
        uAa = program.loc("aa");
        uSoft = program.loc("soft");
        uStroke = program.loc("stroke");
        uColA = program.loc("colA");
        uColB = program.loc("colB");
        uGrad = program.loc("grad");
        uSpot = program.loc("spot");
        uBand = program.loc("band");
        uTexMode = program.loc("texMode");
        uTex = program.loc("tex");
        uScreen = program.loc("screen");
        invalidateUniforms();
    }

    private static void invalidateUniforms() {
        cHalfW = cHalfH = cTl = cTr = cBr = cBl = cAa = cSoft = cStroke = cAlphaA = cAlphaB = cGx = cGy
                = cSpotX = cSpotY = cSpotR = cBandPos = cBandWidth = cTexMode = cScreenW = cScreenH = Float.NaN;
        cTexSet = false;
    }

    private static void begin(boolean additive, int texture) {
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, additive ? GL11.GL_ONE : GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if (texture != -1) {
            GlStateManager.enableTexture2D();
            GlStateManager.bindTexture(texture);
        }
    }

    /** One quad centred on (cx, cy); the texture coordinate is the offset from the centre. */
    private static void quad(float cx, float cy, float ex, float ey) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(-ex, ey); GL11.glVertex2f(cx - ex, cy + ey);
        GL11.glTexCoord2f(ex, ey); GL11.glVertex2f(cx + ex, cy + ey);
        GL11.glTexCoord2f(ex, -ey); GL11.glVertex2f(cx + ex, cy - ey);
        GL11.glTexCoord2f(-ex, -ey); GL11.glVertex2f(cx - ex, cy - ey);
        GL11.glEnd();
    }

    private static void end() {
        if (batchDepth > 0) {
            stateHeld = true;
            return;
        }
        GL20.glUseProgram(0);
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1, 1, 1, 1);
    }

    /**
     * Liquid glass: the blurred backdrop bent by a lens along the edge, tinted, with a
     * directional rim light. One quad replaces blur + tint + highlight + outline.
     *
     * @param tintTop     ARGB; alpha is how much the tint covers the backdrop
     * @param refract     how far the edge bends the backdrop, in units
     * @param bevel       width of the curved edge, in units
     * @param dispersion  0..1 colour fringing at the edge (costs two extra samples per pixel)
     * @param saturation  1 = unchanged
     * @param rimMain     brightness of the lit (top-left) edge
     * @param rimSecond   brightness of the opposite edge
     * @param innerShadow darkening just inside the unlit edge
     * @param grain       noise amplitude in 1/255 steps, hides banding
     * @return false when liquid glass cannot be drawn and the caller should fall back
     */
    public static boolean glass(float x, float y, float w, float h, float tl, float tr, float br, float bl,
                                int tintTop, int tintBottom, float refract, float bevel, float dispersion,
                                float saturation, float rimMain, float rimSecond, float innerShadow, float grain) {
        if (w <= 0 || h <= 0) return false;
        GlassShaders.Program program = GlassShaders.glass();
        if (!program.ok) return false;
        int texture = GlassBlur.texture(blurStrength);
        if (texture == -1) return false;
        if (alpha <= 0.003f) return true;
        float hw = w / 2, hh = h / 2, limit = Math.min(hw, hh);
        tl = Math.min(tl, limit); tr = Math.min(tr, limit); br = Math.min(br, limit); bl = Math.min(bl, limit);
        float zoom = Math.min(tSX, tSY), aa = 1f / (scale * zoom);

        begin(false, texture);
        use(program);
        if (PERF) { statShapes++; statUniforms += 11; }
        GL20.glUniform2f(program.loc("halfSize"), hw, hh);
        GL20.glUniform4f(program.loc("radii"), tl, tr, br, bl);
        GL20.glUniform1f(program.loc("aa"), aa);
        rawColor(program.loc("tintA"), tintTop);
        rawColor(program.loc("tintB"), tintBottom);
        GL20.glUniform1f(program.loc("opacity"), alpha);
        GL20.glUniform1i(program.loc("tex"), 0);
        GL20.glUniform2f(program.loc("screen"), mc.displayWidth, mc.displayHeight);
        GL20.glUniform4f(program.loc("lens"), refract * scale * zoom, Math.min(Math.max(bevel, aa), limit), dispersion, saturation);
        GL20.glUniform4f(program.loc("rim"), rimMain, rimSecond, innerShadow, grain);
        GL20.glUniform3f(program.loc("snap"), 0, 0, 0);
        quad(x + hw, y + hh, hw + aa, hh + aa);
        end();
        return true;
    }

    /**
     * A rounded cut-out of a full-screen snapshot texture, showing the pixels that were at
     * this rectangle when the snapshot was taken. Transforms move and scale the cut-out, so
     * a closed screen can shrink and fade away after it is gone.
     */
    public static boolean snapshot(float x, float y, float w, float h, float r, int texture) {
        if (w <= 0 || h <= 0 || texture == -1) return false;
        GlassShaders.Program program = GlassShaders.glass();
        if (!program.ok) return false;
        if (alpha <= 0.003f) return true;
        float hw = w / 2, hh = h / 2;
        r = Math.min(r, Math.min(hw, hh));
        float aa = 1f / (scale * Math.min(tSX, tSY));

        begin(false, texture);
        use(program);
        GL20.glUniform2f(program.loc("halfSize"), hw, hh);
        GL20.glUniform4f(program.loc("radii"), r, r, r, r);
        GL20.glUniform1f(program.loc("aa"), aa);
        GL20.glUniform1f(program.loc("opacity"), alpha);
        GL20.glUniform1i(program.loc("tex"), 0);
        GL20.glUniform2f(program.loc("screen"), mc.displayWidth, mc.displayHeight);
        GL20.glUniform3f(program.loc("snap"), (x + hw) * scale, mc.displayHeight - (y + hh) * scale, scale);
        quad(x + hw, y + hh, hw + aa, hh + aa);
        end();
        return true;
    }

    /**
     * A region of a texture in a rounded rectangle: a player's face, a server icon.
     * (u0, v0) is the top-left of the region and (u1, v1) its bottom-right, 0..1.
     */
    public static void image(float x, float y, float w, float h, float r, int texture, float u0, float v0, float u1, float v1) {
        image(x, y, w, h, r, texture, u0, v0, u1, v1, 0xffffffff);
    }

    /** {@link #image(float, float, float, float, float, int, float, float, float, float)} multiplied by {@code tint}. */
    public static void image(float x, float y, float w, float h, float r, int texture, float u0, float v0, float u1, float v1, int tint) {
        if (w <= 0 || h <= 0 || texture <= 0 || alpha <= 0.003f) return;
        GlassShaders.Program program = GlassShaders.image();
        if (!program.ok) {
            legacyImage(x, y, w, h, texture, u0, v0, u1, v1, tint);
            return;
        }
        float hw = w / 2, hh = h / 2;
        r = Math.min(r, Math.min(hw, hh));
        float aa = 1f / (scale * Math.min(tSX, tSY));

        begin(false, texture);
        use(program);
        if (PERF) { statShapes++; statUniforms += 6; }
        GL20.glUniform2f(program.loc("halfSize"), hw, hh);
        GL20.glUniform4f(program.loc("radii"), r, r, r, r);
        GL20.glUniform1f(program.loc("aa"), aa);
        GL20.glUniform4f(program.loc("uv"), u0, v0, u1, v1);
        uniformColor(program.loc("tint"), tint);
        GL20.glUniform1i(program.loc("tex"), 0);
        quad(x + hw, y + hh, hw + aa, hh + aa);
        end();
    }

    /**
     * The GL name of a texture, loading it first if needed (a skin that has not been bound yet).
     * @return 0 when it cannot be loaded
     */
    public static int textureId(net.minecraft.util.ResourceLocation location) {
        if (location == null) return 0;
        try {
            net.minecraft.client.renderer.texture.TextureManager manager = mc.getTextureManager();
            net.minecraft.client.renderer.texture.ITextureObject texture = manager.getTexture(location);
            if (texture == null) {
                manager.bindTexture(location);
                texture = manager.getTexture(location);
            }
            return texture == null ? 0 : texture.getGlTextureId();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Square fallback for drivers without shaders. */
    private static void legacyImage(float x, float y, float w, float h, int texture, float u0, float v0, float u1, float v1, int tint) {
        fixedFunction();
        GlStateManager.enableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.bindTexture(texture);
        GlStateManager.color((tint >> 16 & 255) / 255f, (tint >> 8 & 255) / 255f, (tint & 255) / 255f, (tint >>> 24) / 255f * alpha);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(u0, v1); GL11.glVertex2f(x, y + h);
        GL11.glTexCoord2f(u1, v1); GL11.glVertex2f(x + w, y + h);
        GL11.glTexCoord2f(u1, v0); GL11.glVertex2f(x + w, y);
        GL11.glTexCoord2f(u0, v0); GL11.glVertex2f(x, y);
        GL11.glEnd();
        GlStateManager.color(1, 1, 1, 1);
    }

    /** Colour uniform that is not multiplied by the current alpha. */
    private static void rawColor(int location, int argb) {
        GL20.glUniform4f(location, (argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f, (argb & 255) / 255f, (argb >>> 24) / 255f);
    }

    private static void uniformColor(int location, int argb) {
        GL20.glUniform4f(location, (argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f, (argb & 255) / 255f,
                (argb >>> 24) / 255f * alpha);
    }

    /** Fixed-function fallback for drivers that reject the shape shader. */
    private static void legacyRect(float x, float y, float w, float h, float radius, int color) {
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, (color >>> 24) / 255f * alpha);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glBegin(GL11.GL_POLYGON);
        corner(x + radius, y + radius, radius, 180);
        corner(x + w - radius, y + radius, radius, 270);
        corner(x + w - radius, y + h - radius, radius, 0);
        corner(x + radius, y + h - radius, radius, 90);
        GL11.glEnd();
        if (cull) GL11.glEnable(GL11.GL_CULL_FACE);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1, 1, 1, 1);
    }

    private static void corner(float cx, float cy, float radius, int startDegrees) {
        for (int i = 0; i <= 90; i += 10) {
            double angle = Math.toRadians(startDegrees + i);
            GL11.glVertex2d(cx + Math.cos(angle) * radius, cy + Math.sin(angle) * radius);
        }
    }

    // ---------------------------------------------------------------- colour helpers

    public static float clamp01(float value) {
        return value < 0 ? 0 : Math.min(value, 1);
    }

    /** Replace the alpha of {@code color} with {@code opacity} (0..1). */
    public static int alpha(int color, float opacity) {
        return (Math.round(clamp01(opacity) * 255) << 24) | (color & 0xffffff);
    }

    /** Multiply the alpha of {@code color} by {@code factor}. */
    public static int fade(int color, float factor) {
        return (Math.round((color >>> 24) * clamp01(factor)) << 24) | (color & 0xffffff);
    }

    public static int mix(int from, int to, float amount) {
        amount = clamp01(amount);
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int a = (from >>> shift) & 255, b = (to >>> shift) & 255;
            result |= Math.round(a + (b - a) * amount) << shift;
        }
        return result;
    }
}
