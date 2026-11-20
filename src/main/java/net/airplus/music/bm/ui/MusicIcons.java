package net.airplus.music.bm.ui;

import net.airplus.ui.glass.Draw;

/**
 * Player glyphs drawn from shapes (no font glyphs, which the system fonts may lack): filled with
 * flat quads, edged with an antialiased outline so they stay clean at a few units across.
 */
public final class MusicIcons {
    private static final float[] buf = new float[512];

    private MusicIcons() {
    }

    /** ▶ centred on (cx, cy), {@code size} tall. */
    public static void play(float cx, float cy, float size, int color) {
        float h = size / 2, w = size * 0.866f;
        float x0 = cx - w * 0.4f, x1 = cx + w * 0.6f;
        triangle(x0, cy - h, x1, cy, x0, cy + h, color);
    }

    /** ❚❚ centred on (cx, cy). */
    public static void pause(float cx, float cy, float size, int color) {
        float bw = size * 0.3f, gap = size * 0.2f;
        Draw.rect(cx - gap / 2 - bw, cy - size / 2, bw, size, bw * 0.4f, color);
        Draw.rect(cx + gap / 2, cy - size / 2, bw, size, bw * 0.4f, color);
    }

    /** ▶❙ (next) or ❙◀ (previous) centred on (cx, cy). */
    public static void skip(float cx, float cy, float size, boolean forward, int color) {
        float h = size / 2, w = size * 0.7f, bar = size * 0.18f;
        float dir = forward ? 1 : -1;
        float tip = cx + dir * (w / 2 - bar * 0.5f), back = cx - dir * w / 2;
        triangle(back, cy - h, tip, cy, back, cy + h, color);
        float bx = forward ? cx + w / 2 - bar * 0.5f : cx - w / 2 - bar * 0.5f;
        Draw.rect(bx, cy - h, bar, size, bar * 0.4f, color);
    }

    /** ♥, filled or as an outline, centred on (cx, cy), about {@code size} wide. */
    public static void heart(float cx, float cy, float size, boolean filled, int color) {
        int n = 48;
        float s = size / 34f;
        float[] pts = new float[(n + 1) * 2];
        for (int i = 0; i <= n; i++) {
            double t = Math.PI * 2 * i / n;
            double x = 16 * Math.pow(Math.sin(t), 3);
            double y = 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t);
            pts[i * 2] = cx + (float) x * s;
            pts[i * 2 + 1] = cy - (float) y * s - size * 0.04f;
        }
        if (filled) {
            int k = 0;
            for (int i = 0; i < n && k + 8 <= buf.length; i++) {
                buf[k++] = cx;
                buf[k++] = cy + size * 0.05f;
                buf[k++] = pts[i * 2];
                buf[k++] = pts[i * 2 + 1];
                buf[k++] = pts[i * 2 + 2];
                buf[k++] = pts[i * 2 + 3];
                buf[k++] = pts[i * 2 + 2];
                buf[k++] = pts[i * 2 + 3];
            }
            Draw.quads(color, buf, k);
            Draw.polyline(0.5f, color, pts);
        } else {
            Draw.polyline(Math.max(0.8f, size * 0.09f), color, pts);
        }
    }

    /** A loading spinner: an arc turning with {@code phase} (seconds). */
    public static void spinner(float cx, float cy, float radius, float phase, int color) {
        int n = 18;
        float[] pts = new float[n * 2];
        double start = phase * Math.PI * 2 * 1.1;
        for (int i = 0; i < n; i++) {
            double a = start + i / (double) (n - 1) * Math.PI * 1.4;
            pts[i * 2] = cx + (float) Math.cos(a) * radius;
            pts[i * 2 + 1] = cy + (float) Math.sin(a) * radius;
        }
        Draw.polyline(Math.max(0.8f, radius * 0.28f), color, pts);
    }

    private static void triangle(float ax, float ay, float bx, float by, float cx, float cy, int color) {
        buf[0] = ax;
        buf[1] = ay;
        buf[2] = bx;
        buf[3] = by;
        buf[4] = cx;
        buf[5] = cy;
        buf[6] = cx;
        buf[7] = cy;
        Draw.quads(color, buf, 8);
        Draw.polyline(0.5f, color, ax, ay, bx, by, cx, cy, ax, ay);
    }
}
