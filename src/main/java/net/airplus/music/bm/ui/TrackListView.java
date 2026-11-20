package net.airplus.music.bm.ui;

import net.airplus.music.bm.MusicService;
import net.airplus.music.bm.audio.SpectrumAnalyzer;
import net.airplus.music.bm.cover.CoverCache;
import net.airplus.music.bm.model.Track;
import net.airplus.ui.glass.GlassTheme;
import net.airplus.ui.glass.Draw;
import net.airplus.ui.glass.SmoothFont;

/**
 * A list of tracks: header with title and "play all", rows with cover, title / artists, album and
 * length, the playing one marked with live bars. Eased scrolling, more results load as the end
 * comes into view. Double click plays, right click opens the track menu.
 */
final class TrackListView {
    private static final float HEADER = 30, ROW = 30, STEP = 32;

    private final MusicScreen screen;

    TrackListView(MusicScreen screen) {
        this.screen = screen;
    }

    void draw(TrackPage p, float x, float y, float w, float h) {
        MusicService music = this.screen.music;
        SmoothFont title = SmoothFont.bold(12), sub = SmoothFont.regular(7.5f);
        float end = title.draw(p.title, x + 4, y + 4, GlassTheme.TEXT);
        if (!p.subtitle.isEmpty()) sub.draw(sub.trim(p.subtitle, w * 0.5f), end + 8, y + 8, GlassTheme.FAINT);
        if (!p.tracks.isEmpty()) {
            String label = p.queue ? (music.isPaused() ? "继续播放" : "暂停") : "播放全部";
            float bw = SmoothFont.bold(7.5f).width(label) + 20;
            this.screen.button("play-all:" + p.key, label, x + w - bw - 4, y + 3, 18, true, () -> {
                if (p.queue) music.togglePause();
                else this.screen.playFrom(p, 0);
            });
        }

        float ly = y + HEADER, lh = h - HEADER;
        if (p.tracks.isEmpty()) {
            this.drawEmpty(p, x, ly, w, lh);
            return;
        }
        float content = p.tracks.size() * STEP + (p.loading || p.canLoadMore ? 30 : 0);
        int wheel = this.screen.takeWheel(x, ly, w, lh);
        if (wheel != 0) p.scrollTarget -= wheel / 120f * STEP * 3;
        p.scrollTarget = Math.max(0, Math.min(p.scrollTarget, Math.max(0, content - lh)));
        p.scroll += (p.scrollTarget - p.scroll) * (1 - (float) Math.exp(-this.screen.dt * 14));
        if (p.canLoadMore && !p.loading && p.more != null && p.scroll > content - lh - STEP * 6) p.more.run();

        Draw.pushClip(x, ly, w, lh);
        int first = Math.max(0, (int) (p.scroll / STEP) - 1);
        int last = Math.min(p.tracks.size() - 1, (int) ((p.scroll + lh) / STEP) + 1);
        Track current = music.current();
        SpectrumAnalyzer sp = music.spectrum();
        for (int i = first; i <= last; i++) {
            float ry = ly + i * STEP - p.scroll;
            this.drawRow(p, i, p.tracks.get(i), x, ry, w - 8, current, sp);
        }
        if (p.loading) {
            float sy = ly + p.tracks.size() * STEP - p.scroll + 14;
            MusicIcons.spinner(x + w / 2, sy, 5, System.nanoTime() / 1e9f, 0xaaffffff);
        }
        Draw.popClip();

        // Scrollbar
        if (content > lh) {
            float th = Math.max(24, lh * lh / content);
            float ty = ly + (lh - th) * (p.scroll / (content - lh));
            Draw.rect(x + w - 3, ty, 2.5f, th, 1.25f, 0x30ffffff);
        }
    }

    private void drawRow(TrackPage p, int index, Track t, float x, float y, float w, Track current, SpectrumAnalyzer sp) {
        MusicService music = this.screen.music;
        boolean playing = t.equals(current) && (!p.queue || index == music.queueIndex());
        boolean hov = this.screen.over(x, y, w, ROW);
        boolean selected = t.equals(p.selected);
        int bg = playing ? 0x1cffffff : selected ? 0x16ffffff : hov ? 0x0dffffff : 0;
        if (bg != 0) Draw.rect(x, y, w, ROW, 7, bg);
        // The row itself first: the heart and the play spot registered later win over it.
        this.screen.hit(x, y, w, ROW, (b, px, py) -> this.screen.trackClicked(p, index, b));

        // Index / now playing / play on hover
        float ix = x + 13, cy = y + ROW / 2;
        if (playing && !music.isPaused()) {
            for (int b = 0; b < 3; b++) {
                float v = sp.range(b * 6, b * 6 + 6);
                float bh = 2 + 9 * v;
                Draw.rect(ix - 5 + b * 3.6f, cy - bh / 2, 2, bh, 1, 0xffffffff);
            }
        } else if (hov) {
            MusicIcons.play(ix, cy, 7, 0xffffffff);
        } else {
            SmoothFont n = SmoothFont.regular(7);
            n.drawCentered(String.valueOf(index + 1), ix, n.centerY(y, ROW), playing ? GlassTheme.TEXT : GlassTheme.FAINT);
        }

        // Cover
        float cx = x + 28;
        CoverCache.Cover c = music.cover(t, 64);
        if (c != null && c.ready()) {
            Draw.pushAlpha(c.appear());
            Draw.image(cx, y + 4, 22, 22, 5, c.texture, 0, 0, 1, 1);
            Draw.popAlpha();
        } else {
            Draw.rect(cx, y + 4, 22, 22, 5, 0x14ffffff);
        }

        // Title, badges, artists
        SmoothFont title = SmoothFont.bold(8), small = SmoothFont.regular(6.5f), album = SmoothFont.regular(7);
        float tx = cx + 30, albumX = x + w * 0.6f, durX = x + w - 10;
        float heartX = durX - 40;
        float titleMax = albumX - tx - 14;
        String name = title.trim(t.name, titleMax - (t.vip() ? 22 : 0));
        float te = title.draw(name, tx, y + 5, playing ? 0xffffffff : GlassTheme.TEXT);
        if (t.vip()) {
            float bx = te + 4;
            Draw.rect(bx, y + 6, 17, 8, 2.5f, 0x00000000);
            Draw.outline(bx, y + 6, 17, 8, 2.5f, 0.6f, 0xccffcc66, 0x88ffcc66);
            SmoothFont.bold(5).drawCentered("VIP", bx + 8.5f, y + 7.4f, 0xffffcc66);
        }
        if (t.isLocal()) small.draw("本地", te + 4, y + 6.5f, GlassTheme.FAINT);
        small.draw(small.trim(t.artistText(), titleMax), tx, y + 17, GlassTheme.MUTED);
        if (!t.album.isEmpty()) album.draw(album.trim(t.album, heartX - albumX - 10), albumX, album.centerY(y, ROW), GlassTheme.FAINT);
        SmoothFont dur = SmoothFont.regular(7);
        dur.drawRight(MusicText.time(t.durationMs), durX, dur.centerY(y, ROW), GlassTheme.FAINT);

        // Heart (on hover, or when liked)
        boolean liked = music.isLiked(t);
        if (!t.isLocal() && (hov || liked)) {
            float hx = heartX + 14;
            boolean overHeart = this.screen.over(hx - 7, cy - 7, 14, 14);
            MusicIcons.heart(hx, cy, 8, liked, liked ? 0xffff4d6d : overHeart ? 0xffffffff : 0x99ffffff);
            this.screen.hit(hx - 7, cy - 7, 14, 14, (b, px, py) -> music.toggleLike(t));
        }
        // Play button on hover (left part)
        if (hov) this.screen.hit(x, y, 26, ROW, (b, px, py) -> {
            if (b == 0) this.screen.playFrom(p, index);
            else this.screen.trackClicked(p, index, b);
        });
    }

    private void drawEmpty(TrackPage p, float x, float y, float w, float h) {
        SmoothFont f = SmoothFont.regular(8.5f);
        float cx = x + w / 2, cy = y + h / 2 - 20;
        if (p.loading) {
            MusicIcons.spinner(cx, cy, 7, System.nanoTime() / 1e9f, 0xccffffff);
            return;
        }
        if (!p.error.isEmpty()) {
            f.drawCentered(f.trim(p.error, w - 40), cx, cy - 8, GlassTheme.MUTED);
            if (p.needsLogin && !this.screen.music.signedIn()) {
                float bw = SmoothFont.bold(7.5f).width("扫码登录") + 20;
                this.screen.button("empty-login", "扫码登录", cx - bw / 2, cy + 8, 20, true, this.screen.login::open);
            } else if (p.reload != null) {
                float bw = SmoothFont.bold(7.5f).width("重试") + 20;
                this.screen.button("empty-retry:" + p.key, "重试", cx - bw / 2, cy + 8, 20, false, p.reload);
            }
            return;
        }
        if (p.key.startsWith("search:") && p.reload == null) {
            f.drawCentered("输入关键词搜索网易云音乐", cx, cy - 4, GlassTheme.MUTED);
            SmoothFont.regular(7).drawCentered("回车立即搜索 · 空格 播放/暂停 · ←/→ 快退快进 · ↑/↓ 音量", cx, cy + 10, GlassTheme.FAINT);
            return;
        }
        if (p.key.equals("local")) {
            f.drawCentered("没有找到本地歌曲", cx, cy - 8, GlassTheme.MUTED);
            SmoothFont.regular(7).drawCentered("在 MusicPlayer 模块的 local-folder 设置文件夹，或用 .music local <路径>", cx, cy + 6, GlassTheme.FAINT);
            float bw = SmoothFont.bold(7.5f).width("重新扫描") + 20;
            if (p.reload != null) this.screen.button("rescan", "重新扫描", cx - bw / 2, cy + 18, 20, false, p.reload);
            return;
        }
        f.drawCentered(p.queue ? "播放队列是空的" : "这里还没有歌曲", cx, cy, GlassTheme.MUTED);
    }
}
