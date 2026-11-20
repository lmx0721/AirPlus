package net.airplus.ui.glass;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;

/**
 * Open and close motion for every glass-styled vanilla screen.
 * <p>
 * Opening: the backdrop goes from sharp to blurred while the screen scales and fades in.
 * Closing is never delayed: the screen is gone the same frame vanilla closes it, so input,
 * packets and pausing behave exactly as before. What plays afterwards is a snapshot of the
 * last frame, cut to the screen's panel, shrinking and fading over the live game while the
 * blur drains away. Going from one screen to another cross-dissolves the same way.
 * <p>
 * Only screens that draw {@link GlassTheme#screenBackdrop} take part; chat, books and the
 * Click GUI (which animates itself) are left alone.
 */
public final class ScreenTransition {
    private ScreenTransition() { }

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int NONE = 0, EXIT = 1, CROSSFADE = 2;
    private static final float FOCUS_MARGIN = 8;

    // Written by the displayGuiScreen hook, which a connecting thread may call too.
    private static volatile GuiScreen pendingOld;
    private static volatile int pendingSeq;
    private static int consumedSeq;

    private static GuiScreen drawnScreen;
    private static boolean drawnBackdrop, lastBackdrop, overlayDimmed, backdropTicked, insideScreen, inGroup, openSound, openContainer;
    private static final float[][] focus = new float[4][5], ghostFocus = new float[4][5]; // x, y, w, h, r
    private static int focusCount, ghostFocusCount, ghostMode, snapshot = -1;
    private static float ghost;

    // Critically damped: blur that bounces looks wrong.
    private static final Anim.Spring backdrop = Anim.spring(0, 0.34f, 1.0f);
    private static final Anim.Spring enter = Anim.spring(1, 0.38f, 0.78f);

    /** From the displayGuiScreen hook: {@code old} was showing, the current screen replaced it. */
    public static void onSwitch(GuiScreen old) {
        // Several switches in one tick: the screen the player last saw is the first one's.
        if (pendingSeq == consumedSeq) pendingOld = old;
        pendingSeq++;
    }

    /** Start of a frame. The main framebuffer still holds the previous frame here. */
    public static void frameStart() {
        if (consumedSeq != pendingSeq) {
            consumedSeq = pendingSeq;
            GuiScreen old = pendingOld, now = mc.currentScreen;
            pendingOld = null;
            boolean oldGlass = old != null && old == drawnScreen && drawnBackdrop && old != now;
            ghostMode = NONE;
            if (oldGlass && !Anim.reduced()) {
                snapshot = GlassBlur.captureSnapshot();
                if (snapshot != -1) {
                    ghost = 1;
                    ghostMode = now == null ? EXIT : CROSSFADE;
                    ghostFocusCount = focusCount;
                    for (int i = 0; i < focusCount; i++) System.arraycopy(focus[i], 0, ghostFocus[i], 0, 5);
                }
                if (now == null) {
                    if (old instanceof GuiContainer) UiSound.INV_CLOSE.playInventory();
                    else UiSound.CLOSE.play();
                }
            }
            if (now != null && now != old) {
                enter.snap(oldGlass ? 0.6f : 0f);
                openSound = old == null;
                openContainer = now instanceof GuiContainer;
            }
        }
        if (inGroup) {
            // A screen threw while drawing last frame; do not leave its transform behind.
            Draw.popBase();
            inGroup = false;
        }
        lastBackdrop = drawnBackdrop;
        drawnBackdrop = false;
        overlayDimmed = false;
        backdropTicked = false;
        insideScreen = false;
        focusCount = 0;
        Draw.stageBlur(1);
    }

    /** Right before the current screen draws. */
    public static void beforeScreen(GuiScreen screen) {
        drawnScreen = screen;
        insideScreen = true;
    }

    /** A screen that draws its own glass background (the main menu) still cross-dissolves when left. */
    public static void markGlass() {
        if (insideScreen) drawnBackdrop = true;
    }

    /**
     * Called by the backdrop of a glass screen.
     *
     * @return 0..1, how far the blur and dimming have come in
     */
    public static float backdropBegin() {
        if (!insideScreen) return 1;
        drawnBackdrop = true;
        if (!backdropTicked) {
            backdropTicked = true;
            backdrop.update(1, Anim.dt());
            if (openSound) {
                if (openContainer) UiSound.INV_OPEN.playInventory();
                else UiSound.OPEN.play();
                openSound = false;
            }
        }
        return Anim.clamp01(backdrop.value);
    }

    /** After the backdrop: everything the screen draws from here on scales and fades in. */
    public static void groupBegin(float width, float height) {
        if (!insideScreen || inGroup) return;
        float e = enter.update(1, Anim.dt());
        if (enter.settled(1)) return;
        Draw.pushBase(width / 2, height / 2, 0.94f + 0.06f * e, (1 - Anim.clamp01(e)) * 6, Anim.clamp01(e * 1.6f));
        inGroup = true;
    }

    /** True while the screen being drawn is inside its entrance transform. */
    public static boolean inGroup() {
        return inGroup;
    }

    /** Register the main panel of the screen being drawn: this is what shrinks away on close. */
    public static void focus(float x, float y, float w, float h, float r) {
        if (!insideScreen || focusCount >= focus.length || w <= 0 || h <= 0) return;
        float[] rect = focus[focusCount++];
        rect[0] = x; rect[1] = y; rect[2] = w; rect[3] = h; rect[4] = r;
    }

    /** Right after the current screen drew. */
    public static void afterScreen() {
        if (inGroup) {
            Draw.popBase();
            inGroup = false;
        }
        if (ghostMode == CROSSFADE && snapshot != -1) {
            ScaledResolution sr = new ScaledResolution(mc);
            Draw.useGuiScale();
            GlStateManager.disableDepth();
            Draw.pushAlpha(Anim.outCubic(ghost));
            Draw.snapshot(0, 0, sr.getScaledWidth(), sr.getScaledHeight(), 0, snapshot);
            Draw.popAlpha();
            GlStateManager.enableDepth();
        }
        insideScreen = false;
        Draw.stageBlur(1);
    }

    /** After the in-game HUD: the closed screen's blur draining away and its panel shrinking. */
    public static void afterOverlay() {
        boolean screenKeepsBackdrop = mc.currentScreen != null && mc.currentScreen == drawnScreen && lastBackdrop;
        float p = Anim.clamp01(backdrop.value);
        boolean fade = p > 0.004f && !screenKeepsBackdrop;
        boolean exit = ghostMode == EXIT && snapshot != -1 && mc.currentScreen == null;
        if (!fade && !exit) return;

        ScaledResolution sr = new ScaledResolution(mc);
        float w = sr.getScaledWidth(), h = sr.getScaledHeight();
        Draw.useGuiScale();
        GlStateManager.disableDepth();
        if (fade) {
            GlassBlur.invalidate();
            GlassBlur.prime();
            Draw.stageBlur(p);
            GlassTheme.dim(w, h, p);
            Draw.stageBlur(1);
            overlayDimmed = true;
        }
        if (exit) {
            float t = 1 - ghost;
            Draw.pushAlpha(ghost * ghost);
            if (ghostFocusCount == 0) {
                Draw.snapshot(0, 0, w, h, 0, snapshot);
            } else {
                for (int i = 0; i < ghostFocusCount; i++) {
                    float[] r = ghostFocus[i];
                    float x = r[0] - FOCUS_MARGIN, y = r[1] - FOCUS_MARGIN, rw = r[2] + FOCUS_MARGIN * 2, rh = r[3] + FOCUS_MARGIN * 2;
                    Draw.pushTransform(x + rw / 2, y + rh / 2, 1 - 0.07f * Anim.outCubic(t), 0, 5 * t);
                    Draw.snapshot(x, y, rw, rh, r[4] + FOCUS_MARGIN, snapshot);
                    Draw.popTransform();
                }
            }
            Draw.popAlpha();
        }
        GlStateManager.enableDepth();
    }

    /**
     * True when the fading backdrop of the previous screen was already drawn under this
     * frame's screen, so a screen opening right now must not blur and dim a second time.
     */
    public static boolean overlayDimmed() {
        return overlayDimmed && insideScreen;
    }

    /** End of a frame: advance whatever nothing else advanced. */
    public static void frameEnd() {
        float dt = Anim.dt();
        if (!backdropTicked) backdrop.update(0, dt);
        openSound = false;
        if (ghostMode != NONE) {
            ghost -= dt / (ghostMode == EXIT ? 0.2f : 0.15f);
            if (ghost <= 0 || Anim.reduced()) ghostMode = NONE;
        }
    }

    /** World change or window resize: whatever was fading out is no longer valid. */
    public static void reset() {
        ghostMode = NONE;
    }
}
