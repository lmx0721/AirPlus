package net.airplus.ui.glass;

import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Antialiased text from the fonts installed on the machine (Segoe UI first, with a CJK
 * fallback). Glyphs are rasterised at the exact pixel size of the current {@link Draw#scale()},
 * so text stays sharp at every GUI scale. Colours are ARGB; "§" colour codes are honoured.
 */
public final class SmoothFont {
    private static final String[] LATIN = {"Segoe UI", "SF Pro Text", "Helvetica Neue", "Inter", "Noto Sans"};
    private static final String[] LATIN_BOLD = {"Segoe UI Semibold", "SF Pro Text Semibold", "Inter SemiBold"};
    private static final String[] CJK = {"Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", "Source Han Sans SC"};
    private static final int[] CODES = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
    private static final int PAD = 3;

    // Least recently used first. Dropping only the oldest font (instead of clearing all of them)
    // keeps fonts that callers are still holding this frame alive.
    private static final java.util.LinkedHashMap<Long, SmoothFont> cache = new java.util.LinkedHashMap<>(64, 0.75f, true);
    private static Set<String> families;

    /** @param size font size in units (roughly the cap-to-descender height) */
    public static SmoothFont of(float size, boolean bold) {
        int pixels = Math.max(6, Math.round(size * Draw.scale()));
        long key = ((long) Math.round(size * 100) << 32) | ((long) pixels << 1) | (bold ? 1 : 0);
        SmoothFont font = cache.get(key);
        if (font == null) {
            if (cache.size() >= 40) {
                java.util.Iterator<SmoothFont> eldest = cache.values().iterator();
                eldest.next().dispose();
                eldest.remove();
            }
            font = new SmoothFont(pixels, bold, Draw.scale());
            cache.put(key, font);
        }
        return font;
    }

    public static SmoothFont regular(float size) { return of(size, false); }
    public static SmoothFont bold(float size) { return of(size, true); }

    private static Font pick(String[] names, int style, int pixels) {
        if (families == null) {
            try {
                families = new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            } catch (Throwable e) {
                families = new HashSet<>();
                net.airplus.utils.client.ClientUtils.INSTANCE.getLOGGER().warn("[Glass] cannot list system fonts, using the default sans-serif", e);
            }
        }
        for (String name : names) if (families.contains(name)) return new Font(name, style, pixels);
        return null;
    }

    private static final class Page {
        int texture, x, y, rowHeight;
    }

    private static final class Glyph {
        int texture, width, height;
        float u0, v0, u1, v1, advance;
    }

    private final Font primary, fallback, last;
    private final float unit;          // units per pixel
    private final int ascent, lineHeight, pageSize;
    private final Graphics2D metricsGraphics;
    private final List<Page> pages = new ArrayList<>();
    private final Glyph[] ascii = new Glyph[256];
    private final Map<Character, Glyph> others = new HashMap<>();

    private SmoothFont(int pixels, boolean bold, float scale) {
        Font latin = bold ? pick(LATIN_BOLD, Font.PLAIN, pixels) : null;
        if (latin == null) latin = pick(LATIN, bold ? Font.BOLD : Font.PLAIN, pixels);
        this.last = new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, pixels);
        this.primary = latin != null ? latin : last;
        Font cjk = pick(CJK, bold ? Font.BOLD : Font.PLAIN, pixels);
        this.fallback = cjk != null ? cjk : last;
        this.unit = 1f / scale;
        this.pageSize = pixels > 40 ? 1024 : 512;
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        this.metricsGraphics = hints(probe.createGraphics());
        FontMetrics metrics = metricsGraphics.getFontMetrics(primary);
        this.ascent = metrics.getAscent();
        this.lineHeight = metrics.getAscent() + metrics.getDescent();
        for (char c = 32; c < 127; c++) glyph(c);
    }

    private static Graphics2D hints(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    private void dispose() {
        for (Page page : pages) GL11.glDeleteTextures(page.texture);
        pages.clear();
        metricsGraphics.dispose();
    }

    private Glyph glyph(char c) {
        Glyph glyph = c < 256 ? ascii[c] : others.get(c);
        if (glyph == null) {
            glyph = rasterise(c);
            if (c < 256) ascii[c] = glyph; else others.put(c, glyph);
        }
        return glyph;
    }

    private Glyph rasterise(char c) {
        Font font = primary.canDisplay(c) ? primary : fallback.canDisplay(c) ? fallback : last;
        String text = String.valueOf(c);
        FontMetrics metrics = metricsGraphics.getFontMetrics(font);
        float advance = (float) metrics.getStringBounds(text, metricsGraphics).getWidth();
        int width = (int) Math.ceil(advance) + PAD * 2, height = lineHeight + PAD * 2;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = hints(image.createGraphics());
        g.setFont(font);
        g.setColor(java.awt.Color.WHITE);
        g.drawString(text, PAD, PAD + ascent);
        g.dispose();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        for (int pixel : pixels) buffer.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) (pixel >>> 24));
        buffer.flip();

        Page page = pages.isEmpty() ? null : pages.get(pages.size() - 1);
        if (page != null && page.x + width > pageSize) {
            page.x = 0;
            page.y += page.rowHeight;
            page.rowHeight = 0;
        }
        if (page == null || page.y + height > pageSize) {
            page = new Page();
            page.texture = GL11.glGenTextures();
            // 两步法绑定（先绑 0 再绑目标）：防止缓存值与真实绑定撞车时受保护的
            // bindTexture 被跳过 → 像素子图上传到错误纹理 → 字形内容错乱成方块。
            GlStateManager.bindTexture(0);
            GlStateManager.bindTexture(page.texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, pageSize, pageSize, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                    BufferUtils.createByteBuffer(pageSize * pageSize * 4));
            pages.add(page);
        }
        GlStateManager.bindTexture(0);
        GlStateManager.bindTexture(page.texture);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, page.x, page.y, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

        Glyph glyph = new Glyph();
        glyph.texture = page.texture;
        glyph.width = width;
        glyph.height = height;
        glyph.advance = advance;
        glyph.u0 = page.x / (float) pageSize;
        glyph.v0 = page.y / (float) pageSize;
        glyph.u1 = (page.x + width) / (float) pageSize;
        glyph.v1 = (page.y + height) / (float) pageSize;
        page.x += width;
        page.rowHeight = Math.max(page.rowHeight, height);
        return glyph;
    }

    /** Line height in units. */
    public float height() {
        return lineHeight * unit;
    }

    /** The y at which to draw so the line is vertically centred in a row. */
    public float centerY(float top, float rowHeight) {
        return top + (rowHeight - height()) / 2f;
    }

    /**
     * The same text substitutions the vanilla font renderer gets through its font mixin,
     * so names drawn with this font stay hidden too. AirPlus applies those substitutions
     * directly through its own font renderer mixin, so this is the identity.
     */
    public static String filter(String text) {
        return text;
    }

    public float width(String text) {
        if (text == null) return 0;
        text = filter(text);
        float width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) { i++; continue; }
            width += glyph(c).advance;
        }
        return width * unit;
    }

    /** Shorten {@code text} with an ellipsis so it fits in {@code maxWidth} units. */
    public String trim(String text, float maxWidth) {
        if (text == null || width(text) <= maxWidth) return text;
        String shown = filter(text);
        float limit = maxWidth - width("…");
        float width = 0;
        int end = 0;
        for (int i = 0; i < shown.length(); i++) {
            char c = shown.charAt(i);
            if (c == '§' && i + 1 < shown.length()) { i++; end = i + 1; continue; }
            width += glyph(c).advance * unit;
            if (width > limit) break;
            end = i + 1;
        }
        return shown.substring(0, end) + "…";
    }

    public void drawCentered(String text, float centerX, float y, int color) {
        draw(text, centerX - width(text) / 2f, y, color);
    }

    public void drawRight(String text, float rightX, float y, int color) {
        draw(text, rightX - width(text), y, color);
    }

    public void drawShadowed(String text, float x, float y, int color) {
        draw(text, x, y + 0.5f, Draw.fade(0xff000000, (color >>> 24) / 255f * 0.45f), false);
        draw(text, x, y, color, true);
    }

    /** @return the x after the last glyph */
    public float draw(String text, float x, float y, int color) {
        return draw(text, x, y, color, true);
    }

    private float draw(String text, float x, float y, int color, boolean codes) {
        text = filter(text);
        if (text == null || text.isEmpty()) return x;
        float alpha = (color >>> 24) / 255f * Draw.alpha();
        if (alpha <= 0.003f) return x + width(text);
        for (int i = 0; i < text.length(); i++) glyph(text.charAt(i));

        float scale = 1f / unit;
        float pen = Math.round(x * scale), top = Math.round(y * scale) - PAD;
        float r = (color >> 16 & 255) / 255f, g = (color >> 8 & 255) / 255f, b = (color & 255) / 255f;

        Draw.fixedFunction();
        if (Draw.PERF) Draw.statTexts++;
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        int bound = -1;
        boolean drawing = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                int code = "0123456789abcdef".indexOf(Character.toLowerCase(text.charAt(++i)));
                if (codes) {
                    int rgb = code >= 0 ? CODES[code] : color;
                    if (code >= 0 || Character.toLowerCase(text.charAt(i)) == 'r') {
                        r = (rgb >> 16 & 255) / 255f; g = (rgb >> 8 & 255) / 255f; b = (rgb & 255) / 255f;
                        if (drawing) GL11.glColor4f(r, g, b, alpha);
                    }
                }
                continue;
            }
            Glyph glyph = glyph(c);
            if (glyph.texture != bound) {
                if (drawing) GL11.glEnd();
                // 两步法：即使缓存值恰好等于目标纹理也强制真实绑定到位，
                // 防止上游裸 glBindTexture 造成的缓存/真实绑定脱钩 → 字形采样错误纹理 → 方块。
                GlStateManager.bindTexture(0);
                GlStateManager.bindTexture(glyph.texture);
                bound = glyph.texture;
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glColor4f(r, g, b, alpha);
                drawing = true;
            }
            float x0 = (Math.round(pen) - PAD) * unit, y0 = top * unit;
            float x1 = x0 + glyph.width * unit, y1 = y0 + glyph.height * unit;
            GL11.glTexCoord2f(glyph.u0, glyph.v1); GL11.glVertex2f(x0, y1);
            GL11.glTexCoord2f(glyph.u1, glyph.v1); GL11.glVertex2f(x1, y1);
            GL11.glTexCoord2f(glyph.u1, glyph.v0); GL11.glVertex2f(x1, y0);
            GL11.glTexCoord2f(glyph.u0, glyph.v0); GL11.glVertex2f(x0, y0);
            pen += glyph.advance;
        }
        if (drawing) GL11.glEnd();
        if (Draw.batching()) {
            Draw.holdState();
            return pen * unit;
        }
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.enableAlpha();
        GlStateManager.resetColor();
        GlStateManager.color(1, 1, 1, 1);
        return pen * unit;
    }
}
