package net.airplus.ui.glass;

/**
 * Colour scheme of the click GUI: surface tints, text, overlays and how much of the glass
 * machinery (blur, lens, shadows) a theme uses. Plain data, safe to load before GL exists.
 * {@link #GLASS} holds exactly the values the glass UI has always used, so everything that
 * does not pick a theme looks as before.
 */
public final class GuiPalette {
    /** Names as saved in the config, in the order of {@link #byIndex(int)}. */
    public static final String[] NAMES = {"GLASS", "FROSTED_LIGHT", "MIDNIGHT", "SOLID", "AMOLED"};

    /** Dark ink on a light surface: additive light does not show, text on accent may need to be dark. */
    public boolean light;
    /** Panels show the blurred screen behind them. */
    public boolean blur;
    /** The screen behind the GUI is blurred while it is open. */
    public boolean backdropBlur;

    public int text, muted, faint;
    /** Panel tint over the blur (top, bottom) and the opaque version used without blur. */
    public int tintTop, tintBottom, solidTop, solidBottom;
    /** Top highlight and outline of a frosted (non-lens) panel. */
    public int sheen, rimTop, rimBottom;
    /** Thin outline drawn over a liquid-glass panel; 0 for none. */
    public int hairline;
    /** Liquid glass: tint coverage (top, bottom), saturation and rim light. */
    public float liquidTop, liquidBottom, saturation, rimMain, rimSecond, innerShadow;
    /** Drop shadow strength, 0 = none. */
    public float shadow;
    /** Colour of hover washes, separators and other overlays (white on dark themes), and their gain. */
    public int ink;
    public float washGain;
    /** Recessed surfaces: the settings group of a module, input fields. */
    public int sunken, field;
    /** Screen dimming behind the GUI. */
    public int dimColor;
    public float dimTop, dimBottom;
    /** Gain on accent-coloured fills, so they stay strong on a light surface. */
    public float fill;
    /** Preview colour for the theme picker. */
    public int swatch;

    private GuiPalette() { }

    public static final GuiPalette GLASS = new GuiPalette();
    public static final GuiPalette FROSTED_LIGHT = new GuiPalette();
    public static final GuiPalette MIDNIGHT = new GuiPalette();
    public static final GuiPalette SOLID = new GuiPalette();
    public static final GuiPalette AMOLED = new GuiPalette();
    private static final GuiPalette[] ALL = {GLASS, FROSTED_LIGHT, MIDNIGHT, SOLID, AMOLED};

    static {
        GuiPalette p = GLASS;
        p.blur = p.backdropBlur = true;
        p.text = 0xfff3f4f7; p.muted = 0xffa7adba; p.faint = 0xff767d8b;
        p.tintTop = 0x6a23272f; p.tintBottom = 0x8615181d; p.solidTop = 0xf023272f; p.solidBottom = 0xf415181d;
        p.sheen = 0x18ffffff; p.rimTop = 0x58ffffff; p.rimBottom = 0x14ffffff;
        p.liquidTop = 0.416f; p.liquidBottom = 0.525f; p.saturation = 1.22f;
        p.rimMain = 0.22f; p.rimSecond = 0.16f; p.innerShadow = 0.10f;
        p.shadow = 1; p.ink = 0xffffff; p.washGain = 1;
        p.sunken = 0x30000000; p.field = 0x52000000;
        p.dimColor = 0x05070a; p.dimTop = 0.28f; p.dimBottom = 0.52f;
        p.fill = 1; p.swatch = 0xff3b4252;

        p = MIDNIGHT;
        p.set(GLASS);
        p.text = 0xffeef2ff; p.muted = 0xff9fabc9; p.faint = 0xff6b769a;
        p.tintTop = 0x6a17203a; p.tintBottom = 0x860a1024; p.solidTop = 0xf017203a; p.solidBottom = 0xf40a1024;
        p.rimTop = 0x58cfdcff; p.rimBottom = 0x14cfdcff;
        p.dimColor = 0x030614; p.dimTop = 0.32f; p.dimBottom = 0.56f;
        p.swatch = 0xff1b2a5c;

        p = FROSTED_LIGHT;
        p.set(GLASS);
        p.light = true;
        p.text = 0xff1c2029; p.muted = 0xff505867; p.faint = 0xff808796;
        p.tintTop = 0xc8f7f8fb; p.tintBottom = 0xdbeef1f6; p.solidTop = 0xfaf7f8fb; p.solidBottom = 0xfceef1f6;
        p.sheen = 0x30ffffff; p.rimTop = 0xb0ffffff; p.rimBottom = 0x30ffffff;
        p.hairline = 0x24000000;
        p.liquidTop = 0.86f; p.liquidBottom = 0.92f; p.saturation = 1.08f;
        p.rimMain = 0.30f; p.rimSecond = 0.16f; p.innerShadow = 0.03f;
        p.shadow = 0.7f; p.ink = 0x10141c; p.washGain = 1.2f;
        p.sunken = 0x12000000; p.field = 0x18000000;
        p.dimColor = 0x0a0d12; p.dimTop = 0.18f; p.dimBottom = 0.34f;
        p.fill = 1.3f; p.swatch = 0xfff1f3f7;

        p = SOLID;
        p.set(GLASS);
        p.blur = p.backdropBlur = false;
        p.solidTop = 0xff25272e; p.solidBottom = 0xff1b1d23;
        p.tintTop = p.solidTop; p.tintBottom = p.solidBottom;
        p.sheen = 0x0cffffff; p.rimTop = 0x34ffffff; p.rimBottom = 0x10ffffff;
        p.dimColor = 0x000000; p.dimTop = 0.40f; p.dimBottom = 0.58f;
        p.swatch = 0xff2a2c34;

        p = AMOLED;
        p.set(GLASS);
        p.blur = p.backdropBlur = false;
        p.text = 0xffffffff; p.muted = 0xffa3a3ab; p.faint = 0xff68686f;
        p.solidTop = p.solidBottom = p.tintTop = p.tintBottom = 0xff000000;
        p.sheen = 0x00ffffff; p.rimTop = 0x3cffffff; p.rimBottom = 0x26ffffff;
        p.shadow = 0;
        p.sunken = 0x10ffffff; p.field = 0x16ffffff;
        p.dimColor = 0x000000; p.dimTop = 0.55f; p.dimBottom = 0.70f;
        p.swatch = 0xff000000;
    }

    public static int count() {
        return ALL.length;
    }

    public static GuiPalette byIndex(int index) {
        return ALL[index < 0 || index >= ALL.length ? 0 : index];
    }

    /** A palette that can be changed freely, starting as a copy of {@code source}. */
    public static GuiPalette copyOf(GuiPalette source) {
        GuiPalette copy = new GuiPalette();
        copy.set(source);
        return copy;
    }

    public void set(GuiPalette o) {
        light = o.light; blur = o.blur; backdropBlur = o.backdropBlur;
        text = o.text; muted = o.muted; faint = o.faint;
        tintTop = o.tintTop; tintBottom = o.tintBottom; solidTop = o.solidTop; solidBottom = o.solidBottom;
        sheen = o.sheen; rimTop = o.rimTop; rimBottom = o.rimBottom; hairline = o.hairline;
        liquidTop = o.liquidTop; liquidBottom = o.liquidBottom; saturation = o.saturation;
        rimMain = o.rimMain; rimSecond = o.rimSecond; innerShadow = o.innerShadow;
        shadow = o.shadow; ink = o.ink; washGain = o.washGain;
        sunken = o.sunken; field = o.field;
        dimColor = o.dimColor; dimTop = o.dimTop; dimBottom = o.dimBottom;
        fill = o.fill; swatch = o.swatch;
    }

    /** Become the mix of two palettes, {@code t} 0 = {@code a} .. 1 = {@code b}: a theme change fades. */
    public void blend(GuiPalette a, GuiPalette b, float t) {
        if (t <= 0) { set(a); return; }
        if (t >= 1) { set(b); return; }
        GuiPalette flags = t < 0.5f ? a : b;
        light = flags.light; blur = flags.blur; backdropBlur = flags.backdropBlur;
        text = Draw.mix(a.text, b.text, t); muted = Draw.mix(a.muted, b.muted, t); faint = Draw.mix(a.faint, b.faint, t);
        tintTop = Draw.mix(a.tintTop, b.tintTop, t); tintBottom = Draw.mix(a.tintBottom, b.tintBottom, t);
        solidTop = Draw.mix(a.solidTop, b.solidTop, t); solidBottom = Draw.mix(a.solidBottom, b.solidBottom, t);
        sheen = Draw.mix(a.sheen, b.sheen, t); rimTop = Draw.mix(a.rimTop, b.rimTop, t); rimBottom = Draw.mix(a.rimBottom, b.rimBottom, t);
        hairline = Draw.mix(a.hairline, b.hairline, t);
        liquidTop = Anim.lerp(a.liquidTop, b.liquidTop, t); liquidBottom = Anim.lerp(a.liquidBottom, b.liquidBottom, t);
        saturation = Anim.lerp(a.saturation, b.saturation, t);
        rimMain = Anim.lerp(a.rimMain, b.rimMain, t); rimSecond = Anim.lerp(a.rimSecond, b.rimSecond, t);
        innerShadow = Anim.lerp(a.innerShadow, b.innerShadow, t);
        shadow = Anim.lerp(a.shadow, b.shadow, t);
        ink = Draw.mix(a.ink, b.ink, t) & 0xffffff; washGain = Anim.lerp(a.washGain, b.washGain, t);
        sunken = Draw.mix(a.sunken, b.sunken, t); field = Draw.mix(a.field, b.field, t);
        dimColor = Draw.mix(a.dimColor, b.dimColor, t) & 0xffffff;
        dimTop = Anim.lerp(a.dimTop, b.dimTop, t); dimBottom = Anim.lerp(a.dimBottom, b.dimBottom, t);
        fill = Anim.lerp(a.fill, b.fill, t); swatch = b.swatch;
    }

    /** An overlay written as white with some alpha, in this theme's ink. */
    public int wash(int whiteArgb) {
        if (ink == 0xffffff && washGain == 1) return whiteArgb;
        return (Math.min(255, Math.round((whiteArgb >>> 24) * washGain)) << 24) | ink;
    }

    /** {@code Draw.alpha(0xffffff, opacity)} in this theme's ink. */
    public int wash(float opacity) {
        return (Math.min(255, Math.round(Draw.clamp01(opacity) * 255 * washGain)) << 24) | ink;
    }

    /** Opacity of an accent-coloured fill, strengthened on themes that need it. */
    public float fill(float opacity) {
        return Math.min(1, opacity * fill);
    }

    /** Text colour on top of an accent-coloured fill: dark once the fill is too bright for white. */
    public int onAccent(int fill) {
        return luminance(fill) > (light ? 0.62f : 0.9f) ? 0xff14171d : 0xffffffff;
    }

    /**
     * The accent colour as a fill (switches, slider tracks, enabled modules). A near-white
     * accent would vanish on a light surface, so there it turns into dark ink instead.
     */
    public int accentFill(int accent) {
        if (!light) return accent;
        float l = luminance(accent);
        if (l <= 0.72f) return accent;
        // Only colourless brightness is the problem; a bright yellow or green keeps its colour.
        int r = accent >> 16 & 255, g = accent >> 8 & 255, b = accent & 255;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float grey = 1 - Math.min(1, (max - min) / (float) Math.max(1, max) / 0.25f);
        if (grey <= 0) return accent;
        return Draw.mix(accent, 0xff232730, Math.min(0.9f, (l - 0.72f) / 0.28f * 0.9f) * grey);
    }

    /** The accent colour made readable as text or a thin mark on this theme's surface. */
    public int accentInk(int accent) {
        if (!light) return accent;
        float l = luminance(accent);
        return l > 0.45f ? Draw.mix(accent, 0xff000000 | (accent & 0xff000000), Math.min(0.6f, (l - 0.45f) * 1.4f + 0.18f)) : accent;
    }

    private static float luminance(int argb) {
        return ((argb >> 16 & 255) * 0.299f + (argb >> 8 & 255) * 0.587f + (argb & 255) * 0.114f) / 255f;
    }
}
