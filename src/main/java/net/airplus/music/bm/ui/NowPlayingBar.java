package net.airplus.music.bm.ui;

import net.airplus.music.bm.MusicService;
import net.airplus.music.bm.PlayMode;
import net.airplus.music.bm.cover.CoverCache;
import net.airplus.music.bm.cover.Palette;
import net.airplus.music.bm.model.Track;
import net.airplus.ui.glass.GlassTheme;
import net.airplus.ui.glass.Draw;
import net.airplus.music.bm.ui.Interaction;
import net.airplus.ui.glass.SmoothFont;
import net.airplus.ui.glass.UiSound;

/**
 * The player bar at the bottom of the music screen: cover and title, transport controls with a
 * seek bar, play mode, like, volume, and the lyrics / queue switches.
 */
final class NowPlayingBar {
    private final MusicScreen screen;
    private boolean seeking, volumeDrag;
    private float seekFraction;

    NowPlayingBar(MusicScreen screen) {
        this.screen = screen;
    }

    void draw(float x, float y, float w, float h) {
        MusicScreen s = this.screen;
        MusicService m = s.music;
        Track t = m.current();
        GlassTheme.card(x, y, w, h, GlassTheme.R_CARD + 2, 0.6f);

        // ---- cover + title
        float cs = h - 18, cx = x + 9, cy = y + 9;
        CoverCache.Cover cover = m.cover(t, 128);
        int accent = cover != null && cover.ready() ? cover.color : Palette.FALLBACK;
        if (t != null && cover != null && cover.ready()) {
            if (m.isPlaying()) Draw.glow(cx + 2, cy + 3, cs - 4, cs - 4, 8, 12 + 10 * m.spectrum().bass(), Draw.alpha(accent, 0.35f));
            Draw.pushAlpha(cover.appear());
            Draw.image(cx, cy, cs, cs, 8, cover.texture, 0, 0, 1, 1);
            Draw.popAlpha();
            s.hit(cx, cy, cs, cs, (b, px, py) -> s.lyricsShown = !s.lyricsShown);
        } else {
            Draw.rect(cx, cy, cs, cs, 8, 0x14ffffff);
        }
        SmoothFont title = SmoothFont.bold(8.5f), small = SmoothFont.regular(7);
        float tx = cx + cs + 10, textW = Math.max(60, w * 0.27f - cs);
        if (t != null) {
            title.draw(title.trim(t.name, textW), tx, y + 15, GlassTheme.TEXT);
            String sub = m.isWaiting() ? (m.status().isEmpty() ? "缓冲中…" : m.status()) : t.artistText() + (m.isTrial() ? " · 试听" : "");
            small.draw(small.trim(sub, textW), tx, y + 28, GlassTheme.MUTED);
        } else {
            title.draw("没有在播放", tx, y + 15, GlassTheme.MUTED);
            small.draw("双击列表里的歌曲开始播放", tx, y + 28, GlassTheme.FAINT);
        }

        // ---- transport
        float mid = x + w / 2, ty = y + 19;
        this.iconButton("prev", mid - 34, ty, 16, () -> m.previous(), 0, false);
        this.iconButton("play", mid, ty, 24, () -> m.togglePause(), 1, m.isPaused() || !m.isActive());
        this.iconButton("next", mid + 34, ty, 16, () -> m.next(), 2, false);
        // Mode (left of prev) and like (right of next)
        PlayMode mode = m.mode();
        SmoothFont modeFont = SmoothFont.regular(6.5f);
        String modeLabel = m.fmActive() ? "私人 FM" : mode.label;
        float mw = modeFont.width(modeLabel) + 12;
        float mx = mid - 58 - mw;
        boolean overMode = s.over(mx, ty - 7, mw, 14);
        Draw.rect(mx, ty - 7, mw, 14, 7, overMode ? 0x26ffffff : 0x12ffffff);
        modeFont.drawCentered(modeLabel, mx + mw / 2, modeFont.centerY(ty - 7, 14), GlassTheme.MUTED);
        if (!m.fmActive()) s.hit(mx, ty - 7, mw, 14, (b, px, py) -> {
            m.setMode(mode.next());
            s.toast(m.mode().label);
            UiSound.CLICK.play();
        });
        if (t != null && !t.isLocal()) {
            boolean liked = m.isLiked(t);
            float hx = mid + 66;
            boolean overLike = s.over(hx - 8, ty - 8, 16, 16);
            MusicIcons.heart(hx, ty, 9, liked, liked ? 0xffff4d6d : overLike ? 0xffffffff : 0xaaffffff);
            s.hit(hx - 8, ty - 8, 16, 16, (b, px, py) -> m.toggleLike(t));
        }

        // ---- seek bar
        long duration = m.durationMs();
        float bx0 = mid - w * 0.2f, bx1 = mid + w * 0.2f, by = y + h - 15;
        float len = bx1 - bx0;
        boolean overBar = s.over(bx0 - 3, by - 5, len + 6, 10);
        if (this.seeking) {
            this.seekFraction = Draw.clamp01((s.mx - bx0) / len);
            if (!s.mouseDown) {
                this.seeking = false;
                m.seek((long) (this.seekFraction * duration));
            }
        }
        float fraction = this.seeking ? this.seekFraction : duration > 0 ? Draw.clamp01(m.positionMs() / (float) duration) : 0;
        float bh = overBar || this.seeking ? 3.2f : 2.4f;
        Draw.rect(bx0, by - bh / 2, len, bh, bh / 2, 0x2affffff);
        Draw.rect(bx0, by - bh / 2, Math.max(bh, len * fraction), bh, bh / 2, Draw.mix(0xffffffff, 0xff000000 | accent, 0.4f));
        if (overBar || this.seeking) Draw.circle(bx0 + len * fraction, by, 3.6f, 0xffffffff);
        SmoothFont time = SmoothFont.regular(6.5f);
        long shown = this.seeking ? (long) (this.seekFraction * duration) : m.positionMs();
        time.drawRight(MusicText.time(t == null ? 0 : shown), bx0 - 7, time.centerY(by - 5, 10), GlassTheme.FAINT);
        time.draw(MusicText.time(t == null ? 0 : duration), bx1 + 7, time.centerY(by - 5, 10), GlassTheme.FAINT);
        if (duration > 0) s.hit(bx0 - 3, by - 6, len + 6, 12, (b, px, py) -> {
            this.seeking = true;
            this.seekFraction = Draw.clamp01((px - bx0) / len);
        });

        // ---- right: volume, lyrics, queue
        float rx = x + w - 12;
        rx -= s.button("bar-queue", "队列", rx - (SmoothFont.bold(7.5f).width("队列") + 20), ty - 9, 18, s.page != null && s.page.queue && !s.lyricsShown,
                () -> s.show(s.queuePage())) + 6;
        rx -= s.button("bar-lyrics", "歌词", rx - (SmoothFont.bold(7.5f).width("歌词") + 20), ty - 9, 18, s.lyricsShown,
                () -> s.lyricsShown = !s.lyricsShown) + 12;
        float vw = 78, vx = rx - vw, vy = ty;
        int volume = m.settings().volume();
        boolean overVol = s.over(vx - 3, vy - 6, vw + 6, 12);
        if (this.volumeDrag) {
            int v = Math.round(Draw.clamp01((s.mx - vx) / vw) * 100);
            if (v != volume) {
                m.settings().setVolume(v);
                volume = v;
            }
            if (!s.mouseDown) this.volumeDrag = false;
        }
        int wheel = s.takeWheel(vx - 30, vy - 8, vw + 34, 16);
        if (wheel != 0) m.changeVolume(wheel > 0 ? 5 : -5);
        float vh = overVol || this.volumeDrag ? 3.2f : 2.4f;
        Draw.rect(vx, vy - vh / 2, vw, vh, vh / 2, 0x2affffff);
        Draw.rect(vx, vy - vh / 2, Math.max(vh, vw * volume / 100f), vh, vh / 2, 0xe6ffffff);
        if (overVol || this.volumeDrag) Draw.circle(vx + vw * volume / 100f, vy, 3.4f, 0xffffffff);
        SmoothFont vol = SmoothFont.regular(6.5f);
        vol.drawRight(volume + "%", vx - 6, vol.centerY(vy - 5, 10), GlassTheme.FAINT);
        s.hit(vx - 3, vy - 6, vw + 6, 12, (b, px, py) -> {
            this.volumeDrag = true;
            m.settings().setVolume(Math.round(Draw.clamp01((px - vx) / vw) * 100));
        });
        // The spectrum, very faint, along the bar's bottom edge.
        if (m.isPlaying()) {
            int bands = 32;
            float sw = (w - 40) / bands;
            for (int i = 0; i < bands; i++) {
                float v = m.spectrum().band(i);
                float bhh = 1 + 7 * v * v;
                Draw.rect(x + 20 + i * sw + sw * 0.2f, y + h - 2 - bhh, sw * 0.6f, bhh, 0.6f, Draw.alpha(accent, 0.18f));
            }
        }
    }

    /** kind: 0 previous, 1 play/pause (round), 2 next. */
    private void iconButton(String key, float cx, float cy, float size, Runnable action, int kind, boolean showPlay) {
        MusicScreen s = this.screen;
        Interaction feel = s.feel("bar:" + key);
        boolean hov = s.over(cx - size / 2, cy - size / 2, size, size);
        feel.update(hov, hov && s.mouseDown, s.dt);
        float k = feel.scale(0.08f);
        Draw.pushTransform(cx, cy, k, 0, 0);
        if (kind == 1) {
            Draw.circle(cx, cy, size / 2, Draw.mix(0xe8ffffff, 0xffffffff, feel.hot));
            if (s.music.isWaiting()) MusicIcons.spinner(cx, cy, size * 0.2f, System.nanoTime() / 1e9f, 0xff111214);
            else if (showPlay) MusicIcons.play(cx + 0.6f, cy, size * 0.38f, 0xff111214);
            else MusicIcons.pause(cx, cy, size * 0.36f, 0xff111214);
        } else {
            if (feel.hot > 0.01f) Draw.circle(cx, cy, size / 2 + 2, Draw.alpha(0xffffff, 0.1f * feel.hot));
            MusicIcons.skip(cx, cy, size * 0.5f, kind == 2, 0xe6ffffff);
        }
        Draw.popTransform();
        s.hit(cx - size / 2, cy - size / 2, size, size, (b, px, py) -> {
            feel.pulse();
            UiSound.CLICK.play();
            action.run();
        });
    }
}
