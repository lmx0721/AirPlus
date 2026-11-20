package net.airplus.music.bm.cover;

import java.awt.Color;
import java.awt.image.BufferedImage;

/**
 * Picks the glow colour of a cover: the hue that is both vivid and plentiful, brightened enough to
 * glow on dark glass. Grey covers get a soft neutral light.
 */
public final class Palette {
    /** Used before a cover has loaded, and for colourless covers. */
    public static final int FALLBACK = 0x9fb8ff;
    private static final int HUES = 18;

    private Palette() {
    }

    public static int accent(BufferedImage image) {
        int step = Math.max(1, image.getWidth() / 24);
        float[] score = new float[HUES];
        float[] sumH = new float[HUES], sumS = new float[HUES], sumV = new float[HUES];
        float[] count = new float[HUES];
        float[] hsb = new float[3];
        float colourful = 0, total = 0;
        for (int y = 0; y < image.getHeight(); y += step) {
            for (int x = 0; x < image.getWidth(); x += step) {
                int argb = image.getRGB(x, y);
                if ((argb >>> 24) < 128) continue;
                Color.RGBtoHSB(argb >> 16 & 255, argb >> 8 & 255, argb & 255, hsb);
                total++;
                float s = hsb[1], v = hsb[2];
                if (s < 0.18f || v < 0.18f) continue;
                colourful++;
                int bucket = Math.min(HUES - 1, (int) (hsb[0] * HUES));
                // Vivid and bright pixels count most; a little extra for being plentiful.
                float w = s * s * (0.35f + v);
                score[bucket] += w;
                // Average the hue as a vector-free mean within the bucket (buckets are narrow).
                sumH[bucket] += hsb[0] * w;
                sumS[bucket] += s * w;
                sumV[bucket] += v * w;
                count[bucket] += w;
            }
        }
        if (total == 0 || colourful / total < 0.04f) return FALLBACK;
        int best = 0;
        for (int i = 1; i < HUES; i++) {
            // Neighbouring buckets reinforce each other (one hue split across a boundary).
            float a = score[i] + 0.5f * (score[(i + 1) % HUES] + score[(i + HUES - 1) % HUES]);
            float b = score[best] + 0.5f * (score[(best + 1) % HUES] + score[(best + HUES - 1) % HUES]);
            if (a > b) best = i;
        }
        if (count[best] <= 0) return FALLBACK;
        float h = sumH[best] / count[best];
        float s = Math.min(0.85f, Math.max(0.45f, sumS[best] / count[best]));
        float v = Math.max(0.82f, sumV[best] / count[best]);
        return Color.HSBtoRGB(h, s, Math.min(1f, v)) & 0xffffff;
    }
}
