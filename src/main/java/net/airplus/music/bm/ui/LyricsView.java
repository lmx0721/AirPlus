package net.airplus.music.bm.ui;

import net.airplus.music.bm.MusicService;
import net.airplus.music.bm.audio.SpectrumAnalyzer;
import net.airplus.music.bm.cover.CoverCache;
import net.airplus.music.bm.cover.Palette;
import net.airplus.music.bm.model.Lyrics;
import net.airplus.music.bm.model.Track;
import net.airplus.ui.glass.GlassTheme;
import net.airplus.ui.glass.Draw;
import net.airplus.ui.glass.SmoothFont;

/**
 * The lyrics page: the cover large on the left with the live spectrum under it, the lyrics on the
 * right scrolling smoothly to keep the sung line in the middle. The current line fills word by
 * word (word-timed lyrics), the others fade with distance. Click a line to jump to it.
 */
final class LyricsView {
    private static final float LINE = 24, LINE_TRANSLATED = 36;

    private final MusicScreen screen;
    private float scroll = -1;
    private Lyrics shown;

    LyricsView(MusicScreen screen) {
        this.screen = screen;
    }

    void draw(float x, float y, float w, float h) {
        MusicService m = this.screen.music;
        Track t = m.current();
        if (t == null) {
            SmoothFont f = SmoothFont.regular(9);
            f.drawCentered("没有在播放的歌曲", x + w / 2, y + h / 2 - 6, GlassTheme.MUTED);
            return;
        }
        // ---- left: cover, title, spectrum
        float side = Math.min(h - 70, w * 0.36f);
        float cx = x + 16, cy = y + Math.max(8, (h - side - 64) / 2);
        CoverCache.Cover cover = m.cover(t, 300);
        int accent = cover != null && cover.ready() ? cover.color : Palette.FALLBACK;
        SpectrumAnalyzer sp = m.spectrum();
        if (cover != null && cover.ready()) {
            Draw.glow(cx + 4, cy + 6, side - 8, side - 8, 14, 24 + 16 * sp.bass(), Draw.alpha(accent, 0.3f + 0.25f * sp.bass()));
            Draw.pushAlpha(cover.appear());
            Draw.image(cx, cy, side, side, 14, cover.texture, 0, 0, 1, 1);
            Draw.popAlpha();
        } else {
            Draw.rect(cx, cy, side, side, 14, 0x14ffffff);
        }
        SmoothFont title = SmoothFont.bold(11), sub = SmoothFont.regular(8);
        title.draw(title.trim(t.name, side), cx, cy + side + 12, GlassTheme.TEXT);
        sub.draw(sub.trim(t.artistText() + (t.album.isEmpty() ? "" : " — " + t.album), side), cx, cy + side + 27, GlassTheme.MUTED);
        int bands = SpectrumAnalyzer.BANDS;
        float bw = side / bands, by = cy + side + 52;
        for (int i = 0; i < bands; i++) {
            float v = sp.band(i);
            float bh = 1.5f + 14 * v * v;
            Draw.rect(cx + i * bw + bw * 0.18f, by - bh, bw * 0.64f, bh, Math.min(bw * 0.32f, 1.2f), Draw.alpha(Draw.mix(accent, 0xffffff, 0.3f), 0.55f + 0.4f * v));
        }

        // ---- right: lyrics
        float lx = cx + side + 28, lw = x + w - 16 - lx;
        Lyrics lyrics = m.lyrics();
        if (lyrics != this.shown) {
            this.shown = lyrics;
            this.scroll = -1;
        }
        if (lyrics.isEmpty()) {
            SmoothFont f = SmoothFont.regular(9);
            f.drawCentered(m.isWaiting() ? "歌词加载中…" : "暂无歌词，请欣赏", lx + lw / 2, y + h / 2 - 6, GlassTheme.MUTED);
            return;
        }
        long now = m.lyricTimeMs();
        int current = lyrics.indexAt(now + 120);
        boolean translations = false;
        for (Lyrics.Line l : lyrics.lines) {
            if (!l.translation.isEmpty()) {
                translations = true;
                break;
            }
        }
        float step = translations ? LINE_TRANSLATED : LINE;
        float target = Math.max(0, current) * step;
        if (this.scroll < 0) this.scroll = target;
        this.scroll += (target - this.scroll) * (1 - (float) Math.exp(-this.screen.dt * 7));
        float centre = y + h * 0.42f;
        long offset = m.lyricOffsetMs();

        Draw.pushClip(lx - 6, y, lw + 12, h);
        SmoothFont big = SmoothFont.bold(11), normal = SmoothFont.regular(9.5f), tr = SmoothFont.regular(7.5f);
        int first = Math.max(0, (int) ((this.scroll - (centre - y)) / step) - 1);
        int last = Math.min(lyrics.lines.size() - 1, (int) ((this.scroll + (y + h - centre)) / step) + 1);
        for (int i = first; i <= last; i++) {
            Lyrics.Line line = lyrics.lines.get(i);
            float ly = centre + i * step - this.scroll;
            float distance = Math.abs(ly - centre) / (h * 0.5f);
            float alpha = Draw.clamp01(1 - distance * 0.85f);
            boolean isCurrent = i == current;
            SmoothFont f = isCurrent ? big : normal;
            float rowTop = ly - 4;
            boolean hov = this.screen.over(lx - 6, rowTop, lw + 12, step - 2);
            if (hov) Draw.rect(lx - 6, rowTop, lw + 12, step - 2, 7, 0x0effffff);
            Draw.pushAlpha(alpha);
            String text = f.trim(line.text, lw);
            if (isCurrent && line.wordTimed()) {
                float fill = this.fill(line, f, now);
                f.draw(text, lx, ly, 0x66ffffff);
                Draw.pushClip(lx, rowTop, fill, step);
                f.draw(text, lx, ly, 0xffffffff);
                Draw.popClip();
            } else {
                f.draw(text, lx, ly, isCurrent ? 0xffffffff : 0x8cffffff);
            }
            if (!line.translation.isEmpty()) {
                tr.draw(tr.trim(line.translation, lw), lx, ly + f.height() + 2, isCurrent ? GlassTheme.MUTED : 0x66ffffff);
            }
            Draw.popAlpha();
            long seekTo = Math.max(0, line.startMs - offset);
            this.screen.hit(lx - 6, rowTop, lw + 12, step - 2, (b, px, py) -> m.seek(seekTo));
        }
        Draw.popClip();
    }

    private float fill(Lyrics.Line line, SmoothFont font, long now) {
        StringBuilder done = new StringBuilder();
        for (Lyrics.Word word : line.words) {
            float p = word.progress(now);
            if (p >= 1) {
                done.append(word.text);
                continue;
            }
            float before = font.width(done.toString());
            return before + (font.width(done + word.text) - before) * p;
        }
        return font.width(done.toString());
    }
}
