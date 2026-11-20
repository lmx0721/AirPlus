package net.airplus.ui.glass;

/**
 * The one set of design constants and composite surfaces every glass screen and HUD
 * element uses: frosted panels, inner cards, input fields, the accent colour.
 * Ported from BetterMyau; the HUD-module lookups are replaced by fixed defaults and
 * a simple {@link #setAccent(int)} override.
 */
public final class GlassTheme {
    private GlassTheme() { }

    // Corner radii (units)
    public static final float R_PANEL = 12, R_CARD = 8, R_CONTROL = 5;
    // Text
    public static final int TEXT = 0xfff3f4f7, MUTED = 0xffa7adba, FAINT = 0xff767d8b;
    // Status
    public static final int OK = 0xff34c759, BAD = 0xffff453a, WARN = 0xffffcc00;
    // Glass tint over the blurred backdrop (top, bottom) and its opaque fallback
    private static final int TINT_TOP = 0x6a23272f, TINT_BOTTOM = 0x8615181d;
    private static final int SOLID_TOP = 0xf023272f, SOLID_BOTTOM = 0xf415181d;

    /** Accent colour, ARGB. Fixed default; override with {@link #setAccent(int)} if wanted. */
    private static int accent = 0xff4c9bff;

    public static int accent() {
        return accent;
    }

    public static int accent(long offset) {
        return accent;
    }

    /** Simple configuration entry replacing BetterMyau's HUD colour settings. */
    public static void setAccent(int value) {
        accent = 0xff000000 | value;
    }

    // Per-frame copies of the look settings, so drawing a panel does not look anything up.
    private static boolean liquid = true;
    private static float liquidStrength = 0.6f;
    /** Frame counter, bumped by {@link #beginFrame()}; kept for per-frame caches. */
    private static int frame = 1;

    /** Once per rendered frame, before anything is drawn: advance the clock, read the settings. */
    public static void beginFrame() {
        frame++;
        Draw.resetBatch();
        // Fixed sensible values replacing BetterMyau's HUD animations/liquidGlass settings.
        liquid = true;
        liquidStrength = 0.6f;
        Anim.beginFrame(1, true);
    }

    /** Frosted glass panel: drop shadow, blurred backdrop, tint, top highlight and rim light. */
    public static void panel(float x, float y, float w, float h, float r) {
        panel(x, y, w, h, r, 1);
    }

    /** @param lift 0..2, how far the panel floats above the background (shadow strength) */
    public static void panel(float x, float y, float w, float h, float r, float lift) {
        panel(x, y, w, h, r, lift, GuiPalette.GLASS);
    }

    /** A panel in one of the click GUI's colour themes. */
    public static void panel(float x, float y, float w, float h, float r, float lift, GuiPalette p) {
        panel(x, y, w, h, r, lift, p, liquid, p.blur);
    }

    /**
     * Panel with explicit liquid-lens / backdrop-blur switches. Used by the CustomGui
     * container skin, whose Background-Blur mode (Liquid / Frosted / Off) overrides the
     * global defaults without touching every other glass surface.
     */
    public static void panel(float x, float y, float w, float h, float r, float lift, GuiPalette p,
                             boolean liquidOn, boolean blurOn) {
        if (w <= 0 || h <= 0) return;
        if (lift > 0 && p.shadow > 0.01f) {
            Draw.shadow(x, y + 4 + 3 * lift, w, h, r, 14 + 10 * lift, Draw.alpha(0, Math.min(0.62f, 0.34f * lift + 0.1f) * p.shadow));
            Draw.shadow(x, y + 1, w, h, r, 4, Draw.fade(0x30000000, p.shadow));
        }
        if (blurOn) {
            // Light palettes (FROSTED_LIGHT) skip the lens: their liquid tint coverage is ~0.86,
            // which over the blurred backdrop reads as an opaque white board. BetterMyau's default
            // look for them is the better-glass slab (not ported here), and its frosted material —
            // blurred backdrop + tint + sheen + rim — is the faithful substitute.
            if (liquidOn && !p.light && liquidPanel(x, y, w, h, r, p)) {
                if (p.hairline != 0) Draw.outline(x, y, w, h, r, 0.6f, p.hairline, p.hairline);
                return;
            }
            boolean blur = Draw.blurred(x, y, w, h, r, 1);
            Draw.gradientV(x, y, w, h, r, blur ? p.tintTop : p.solidTop, blur ? p.tintBottom : p.solidBottom);
        } else {
            // Opaque themes never ask for the blurred screen, so it is not rendered at all.
            Draw.gradientV(x, y, w, h, r, p.solidTop, p.solidBottom);
        }
        if (p.sheen >>> 24 != 0) Draw.gradientV(x, y, w, h, r, p.sheen, p.sheen & 0xffffff);
        Draw.outline(x, y, w, h, r, 0.75f, p.rimTop, p.rimBottom);
        if (p.hairline != 0) Draw.outline(x, y, w, h, r, 0.6f, p.hairline, p.hairline);
    }

    /**
     * The liquid-glass version of a panel body: the edge bends the backdrop like a lens and
     * catches light from the top left. Small capsules get a cheaper, subtler lens; anything
     * tiny keeps the plain frosted look.
     *
     * @return false when it was not drawn and the frosted fallback should be used
     */
    private static boolean liquidPanel(float x, float y, float w, float h, float r, GuiPalette p) {
        return liquidPanelBody(x, y, w, h, r, p, liquidStrength);
    }

    /** Liquid glass panel with an explicit lens strength (0..1+), overriding the frame default. */
    public static void liquidPanel(float x, float y, float w, float h, float r, float lift, GuiPalette p, float strength) {
        if (w <= 0 || h <= 0) return;
        if (lift > 0 && p.shadow > 0.01f) {
            Draw.shadow(x, y + 4 + 3 * lift, w, h, r, 14 + 10 * lift, Draw.alpha(0, Math.min(0.62f, 0.34f * lift + 0.1f) * p.shadow));
            Draw.shadow(x, y + 1, w, h, r, 4, Draw.fade(0x30000000, p.shadow));
        }
        if (p.blur && liquidPanelBody(x, y, w, h, r, p, Draw.clamp01(strength))) {
            if (p.hairline != 0) Draw.outline(x, y, w, h, r, 0.6f, p.hairline, p.hairline);
            return;
        }
        boolean blur = p.blur && Draw.blurred(x, y, w, h, r, 1);
        Draw.gradientV(x, y, w, h, r, blur ? p.tintTop : p.solidTop, blur ? p.tintBottom : p.solidBottom);
        if (p.sheen >>> 24 != 0) Draw.gradientV(x, y, w, h, r, p.sheen, p.sheen & 0xffffff);
        Draw.outline(x, y, w, h, r, 0.75f, p.rimTop, p.rimBottom);
        if (p.hairline != 0) Draw.outline(x, y, w, h, r, 0.6f, p.hairline, p.hairline);
    }

    /** Liquid-glass panel body at an explicit lens strength; shared by {@link #panel} and the public overload. */
    private static boolean liquidPanelBody(float x, float y, float w, float h, float r, GuiPalette p, float k) {
        float shortSide = Math.min(w, h);
        if (shortSide < 14) return false;
        // A lighter tint lets the refraction show; text stays readable through the inner shadow.
        int top = Draw.alpha(p.tintTop, p.liquidTop - 0.10f * k), bottom = Draw.alpha(p.tintBottom, p.liquidBottom - 0.09f * k);
        float saturation = p.saturation + 0.13f * k;
        if (h <= 22) {
            return Draw.glass(x, y, w, h, r, r, r, r, top, bottom, 5 * k, Math.min(h * 0.32f, 5), 0,
                    saturation, p.rimMain, p.rimSecond, p.innerShadow, 1.5f);
        }
        float bevel = Math.max(6, Math.min(14, shortSide * 0.10f));
        return Draw.glass(x, y, w, h, r, r, r, r, top, bottom, 10 * k, bevel, 0.12f * k,
                saturation, p.rimMain, p.rimSecond, p.innerShadow, 1.5f);
    }

    /** A lighter surface inside a panel (rows, list items, key caps). */
    public static void card(float x, float y, float w, float h, float r, float emphasis) {
        card(x, y, w, h, r, emphasis, GuiPalette.GLASS);
    }

    public static void card(float x, float y, float w, float h, float r, float emphasis, GuiPalette p) {
        Draw.rect(x, y, w, h, r, p.wash(0.06f + 0.07f * emphasis));
        Draw.outline(x, y, w, h, r, 0.6f, p.wash(0.12f + 0.1f * emphasis), p.wash(0.04f));
    }

    /** Recessed input field; {@code focus} 0..1 lights the rim with the accent colour. */
    public static void field(float x, float y, float w, float h, float r, float focus) {
        field(x, y, w, h, r, focus, GuiPalette.GLASS);
    }

    public static void field(float x, float y, float w, float h, float r, float focus, GuiPalette p) {
        Draw.rect(x, y, w, h, r, p.field);
        Draw.outline(x, y, w, h, r, 0.75f, p.wash(0x10ffffff), p.wash(0x30ffffff));
        if (focus > 0.01f) {
            int accent = p.accentInk(accent());
            // Added light does not show on a light surface; a coloured shadow does.
            if (p.light) Draw.shadow(x, y, w, h, r, 5, Draw.alpha(accent, 0.3f * focus));
            else Draw.glow(x, y, w, h, r, 6, Draw.alpha(accent, 0.28f * focus));
            Draw.outline(x, y, w, h, r, 0.9f, Draw.alpha(accent, 0.9f * focus), Draw.alpha(accent, 0.55f * focus));
        }
    }

    /** Blur and darken the whole screen behind a dialog. {@code amount} 0..1. */
    public static void dim(float width, float height, float amount) {
        dim(0, 0, width, height, amount);
    }

    /** The click GUI's backdrop in one of its themes; opaque themes darken without blurring. */
    public static void dim(float width, float height, float amount, GuiPalette p) {
        if (amount <= 0) return;
        if (p.backdropBlur) Draw.blurred(0, 0, width, height, 0, 0.72f * amount);
        Draw.gradientV(0, 0, width, height, 0, Draw.alpha(p.dimColor, p.dimTop * amount), Draw.alpha(p.dimColor, p.dimBottom * amount));
    }

    private static void dim(float x, float y, float width, float height, float amount) {
        if (amount <= 0) return;
        Draw.blurred(x, y, width, height, 0, 0.72f * amount);
        Draw.gradientV(x, y, width, height, 0, Draw.alpha(0x05070a, 0.28f * amount), Draw.alpha(0x05070a, 0.52f * amount));
    }

    /** Outside a world, paint the menu scene so glass has something to blur. */
    private static void scene(int width, int height) {
        // BetterMyau draws its MenuBackdrop here; AirPlus keeps the vanilla background.
    }

    /** Dialog or screen title. */
    public static void title(String text, float centerX, float y) {
        SmoothFont.bold(11.5f).drawCentered(text, centerX, y, TEXT);
    }

    /** Secondary line under a title; section-sign colour codes are honoured. */
    public static void caption(String text, float centerX, float y) {
        SmoothFont.regular(9).drawCentered(text, centerX, y, MUTED);
    }

    /** Small frosted capsule with a message, centred on {@code centerX}. */
    public static void toast(String text, float centerX, float y) {
        SmoothFont font = SmoothFont.regular(9);
        float w = font.width(text) + 18, h = 17;
        panel(centerX - w / 2, y, w, h, h / 2, 0.6f);
        font.drawCentered(text, centerX, font.centerY(y, h) - 0.5f, TEXT);
    }

    /**
     * Status capsule for in-game indicators (block counts, progress, timers), centred on
     * {@code centerX}. Section-sign colour codes in {@code text} are honoured.
     *
     * @param accent   colour of the leading dot and the progress bar
     * @param progress 0..1 draws a thin bar along the bottom; negative draws none
     * @return the capsule height, so callers can stack several
     */
    public static float pill(String text, float centerX, float y, int accent, float progress) {
        SmoothFont font = SmoothFont.regular(9);
        boolean bar = progress >= 0;
        float h = 17, dot = 4, w = Math.max(font.width(text) + 26, bar ? 64 : 0);
        float x = centerX - w / 2;
        panel(x, y, w, h, h / 2, 0.6f);
        float cy = y + h / 2;
        Draw.glow(x + 8 - dot / 2, cy - dot / 2, dot, dot, dot / 2, 5, Draw.alpha(accent, 0.6f));
        Draw.circle(x + 8, cy, dot / 2, accent);
        font.draw(text, x + 15, font.centerY(y, h) - 0.5f, TEXT);
        if (bar) {
            float bx = x + h / 2, bw = w - h, by = y + h - 3.2f;
            Draw.rect(bx, by, bw, 1.4f, 0.7f, 0x30ffffff);
            float fill = bw * Draw.clamp01(progress);
            if (fill > 0.5f) {
                Draw.glow(bx, by, fill, 1.4f, 0.7f, 4, Draw.alpha(accent, 0.45f));
                Draw.rect(bx, by, fill, 1.4f, 0.7f, accent);
            }
        }
        return h;
    }

    /** Background of a vanilla screen: the scene behind it, blurred and dimmed. */
    public static void screenBackdrop(int width, int height) {
        Draw.useGuiScale();
        if (ScreenTransition.inGroup()) {
            // A second backdrop inside a screen that is still scaling in: cover past the edges.
            dim(-width, -height, width * 3, height * 3, Draw.blurStrength());
            return;
        }
        float p = ScreenTransition.backdropBegin();
        if (ScreenTransition.overlayDimmed()) {
            // The previous screen's backdrop is still on screen under this one; reuse it.
            Draw.stageBlur(p);
        } else {
            scene(width, height);
            GlassBlur.invalidate();
            GlassBlur.prime();
            Draw.stageBlur(p);
            dim(width, height, p);
        }
        ScreenTransition.groupBegin(width, height);
    }

    /** Centered dialog card over a blurred screen (input prompts, login dialogs). */
    public static void backdrop(int width, int height) {
        screenBackdrop(width, height);
        int w = Math.min(340, Math.max(0, width - 24));
        int h = Math.min(180, Math.max(0, height - 24));
        if (w > 4 && h > 4) {
            panel((width - w) / 2f, (height - h) / 2f, w, h, R_PANEL, 1.4f);
            ScreenTransition.focus((width - w) / 2f, (height - h) / 2f, w, h, R_PANEL);
        }
    }

    /** Full-screen glass sheet for the account manager. */
    public static void accountBackdrop(int width, int height) {
        screenBackdrop(width, height);
        int margin = 12;
        if (width > margin * 2 && height > margin * 2) {
            panel(margin, margin, width - margin * 2, height - margin * 2, R_PANEL, 1.2f);
            ScreenTransition.focus(margin, margin, width - margin * 2, height - margin * 2, R_PANEL);
        }
    }
}
