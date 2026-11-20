package net.airplus.music.bm.ui;

import net.airplus.music.bm.MusicService;
import net.airplus.music.bm.model.Account;
import net.airplus.music.bm.model.Playlist;
import net.airplus.music.bm.model.SearchResult;
import net.airplus.music.bm.model.Track;
import net.airplus.music.bm.cover.CoverCache;
import net.airplus.ui.glass.GlassTheme;
import net.airplus.ui.glass.Anim;
import net.airplus.ui.glass.Draw;
import net.airplus.music.bm.ui.Interaction;
import net.airplus.ui.glass.ScreenTransition;
import net.airplus.ui.glass.SmoothFont;
import net.airplus.music.bm.ui.TextEdit;
import net.airplus.ui.glass.UiSound;
import net.airplus.music.bm.ui.TextInputScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The music screen: search, daily picks, personal FM, liked songs, playlists, local files and the
 * queue on the left; the selected list (or the lyrics) in the middle; the player bar at the
 * bottom; QR sign-in as an overlay. It does not pause the game.
 *
 * <p>Laid out in design units ({@link #ui} pixels each, at least {@link #MIN_W} x {@link #MIN_H}),
 * like the glass multiplayer screen. Every clickable thing registers a hit rectangle while it is
 * drawn; a click goes to the topmost one under the pointer. The views ({@link TrackListView},
 * {@link NowPlayingBar}, {@link LyricsView}, {@link LoginOverlay}) draw into rectangles this
 * class lays out and use its helpers for hits, hover and buttons.
 */
public final class MusicScreen extends GuiScreen implements TextInputScreen {
    static final Minecraft mc = Minecraft.getMinecraft();
    static final float MIN_W = 720, MIN_H = 400, MARGIN = 14, SIDE_W = 158, BAR_H = 62, GAP = 10;
    private static final long DOUBLE_CLICK_MS = 330;

    // ---- frame
    float ui = 2, zoom = 1, sw = MIN_W, sh = MIN_H, mx, my, dt, openTime;
    boolean mouseDown;
    int wheel;

    // ---- layout (design units)
    float px, py, pw, ph, sideX, sideY, sideW, sideH, mainX, mainY, mainW, mainH, barX, barY, barW;

    // ---- hits
    interface Action {
        void run(int button, float x, float y);
    }

    private static final class Hit {
        float x0, y0, x1, y1;
        Action action;
    }

    private final List<Hit> hits = new ArrayList<>();
    private int hitCount;
    private final float[] clipped = new float[4];
    private final Map<String, Interaction> feels = new HashMap<>();

    // ---- content
    final MusicService music = MusicService.get();
    private final Map<String, TrackPage> pages = new HashMap<>();
    TrackPage page;
    boolean lyricsShown;
    final TextEdit search = new TextEdit(60);
    boolean searchFocused;
    private float searchScroll;
    private String searched = "";
    private long typedAt;
    private int requestSerial;
    private float sideScroll, sideTarget, sideContent;

    // ---- views and overlays
    final TrackListView list = new TrackListView(this);
    final NowPlayingBar bar = new NowPlayingBar(this);
    final LyricsView lyrics = new LyricsView(this);
    final LoginOverlay login = new LoginOverlay(this);
    private Menu menu;
    private String toast = "";
    private long toastAt;

    /** The music screen with the QR sign-in already open. */
    public static MusicScreen signIn() {
        MusicScreen s = new MusicScreen();
        s.login.open();
        return s;
    }

    public MusicScreen() {
        TrackPage start = this.music.current() != null ? this.queuePage() : this.searchPage("");
        this.page = start;
        if (this.music.local().tracks().isEmpty() && !this.music.local().scanning()) this.music.scanLocal(null);
    }

    // ================================================================ lifecycle

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        this.login.close();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public boolean wantsTextInput() {
        return this.searchFocused || this.page != null && this.page.key.startsWith("search:") && !this.login.visible();
    }

    private void measure() {
        float gui = new ScaledResolution(mc).getScaleFactor();
        float scale = gui;
        if (mc.displayWidth / scale < MIN_W || mc.displayHeight / scale < MIN_H) {
            scale = Math.min(mc.displayWidth / MIN_W, mc.displayHeight / MIN_H);
            scale = Math.max(0.5f, (float) Math.floor(scale * 4) / 4f);
        }
        this.ui = scale;
        this.zoom = this.ui / gui;
        this.sw = mc.displayWidth / this.ui;
        this.sh = mc.displayHeight / this.ui;
    }

    private void layout() {
        float w = Math.min(this.sw - MARGIN * 2, 1100), h = Math.min(this.sh - MARGIN * 2, 640);
        this.px = (this.sw - w) / 2;
        this.py = (this.sh - h) / 2;
        this.pw = w;
        this.ph = h;
        this.barX = this.px + GAP;
        this.barW = this.pw - GAP * 2;
        this.barY = this.py + this.ph - GAP - BAR_H;
        this.sideX = this.px + GAP;
        this.sideY = this.py + GAP;
        this.sideW = SIDE_W;
        this.sideH = this.barY - GAP - this.sideY;
        this.mainX = this.sideX + this.sideW + GAP;
        this.mainY = this.py + GAP;
        this.mainW = this.px + this.pw - GAP - this.mainX;
        this.mainH = this.barY - GAP - this.mainY;
    }

    // ================================================================ frame

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.dt = Anim.dt();
        this.openTime += this.dt;
        this.measure();
        this.layout();
        this.mx = Mouse.getX() / this.ui;
        this.my = (mc.displayHeight - Mouse.getY() - 1) / this.ui;
        this.mouseDown = Mouse.isButtonDown(0);
        this.autoSearch();
        this.syncQueuePage();

        GlassTheme.screenBackdrop(this.width, this.height);
        Draw.pushTransform(0, 0, this.zoom, 0, 0);
        Draw.beginBatch();
        try {
            this.hitCount = 0;
            GlassTheme.panel(this.px, this.py, this.pw, this.ph, GlassTheme.R_PANEL, 1.2f);
            ScreenTransition.focus(this.px * this.zoom, this.py * this.zoom, this.pw * this.zoom, this.ph * this.zoom, GlassTheme.R_PANEL * this.zoom);
            this.drawSidebar();
            if (this.lyricsShown) {
                this.lyrics.draw(this.mainX, this.mainY, this.mainW, this.mainH);
            } else {
                this.drawMain();
            }
            this.bar.draw(this.barX, this.barY, this.barW, BAR_H);
            if (this.menu != null) this.drawMenu();
            this.login.draw();
            this.drawToast();
        } finally {
            Draw.endBatch();
            Draw.popTransform();
        }
        this.wheel = 0;
    }

    @Override
    public void updateScreen() {
    }

    // ================================================================ sidebar

    private void drawSidebar() {
        float x = this.sideX, y = this.sideY, w = this.sideW;
        GlassTheme.card(x, y, w, this.sideH, GlassTheme.R_CARD, 0.4f);

        // Account card
        Account acc = this.music.account();
        float ay = y + 8;
        boolean overAcc = this.over(x + 6, ay, w - 12, 34);
        if (overAcc) Draw.rect(x + 6, ay, w - 12, 34, 7, 0x10ffffff);
        float avatar = 24;
        if (acc.signedIn() && !acc.avatarUrl.isEmpty()) {
            CoverCache.Cover c = this.music.covers().remote(acc.avatarUrl, 64);
            if (c != null && c.ready()) Draw.image(x + 11, ay + 5, avatar, avatar, avatar / 2, c.texture, 0, 0, 1, 1);
            else Draw.circle(x + 11 + avatar / 2, ay + 5 + avatar / 2, avatar / 2, 0x22ffffff);
        } else {
            Draw.circle(x + 11 + avatar / 2, ay + 5 + avatar / 2, avatar / 2, 0x22ffffff);
            MusicIcons.heart(x + 11 + avatar / 2, ay + 5 + avatar / 2 + 0.5f, 10, false, 0x99ffffff);
        }
        SmoothFont name = SmoothFont.bold(8), small = SmoothFont.regular(6.5f);
        float tx = x + 11 + avatar + 7, tw = w - (tx - x) - 10;
        if (acc.signedIn()) {
            name.draw(name.trim(acc.nickname, tw), tx, ay + 7, GlassTheme.TEXT);
            small.draw(acc.vip() ? "黑胶 VIP" : "网易云音乐", tx, ay + 18, acc.vip() ? 0xffffcc66 : GlassTheme.MUTED);
            this.hit(x + 6, ay, w - 12, 34, (b, px, py) -> this.openMenu(new String[]{"退出登录"},
                    new Runnable[]{() -> {
                        this.music.signOut();
                        this.toast("已退出登录");
                    }}));
        } else {
            name.draw("未登录", tx, ay + 7, GlassTheme.TEXT);
            small.draw("点击扫码登录", tx, ay + 18, GlassTheme.MUTED);
            this.hit(x + 6, ay, w - 12, 34, (b, px, py) -> this.login.open());
        }

        // Navigation
        float ny = ay + 42;
        ny = this.navItem("搜索", ny, this.page != null && this.page.key.startsWith("search:") && !this.lyricsShown,
                () -> this.show(this.searchPage(this.searched)));
        ny = this.navItem("每日推荐", ny, this.isPage("daily"), () -> this.show(this.dailyPage()));
        ny = this.navItem("私人 FM", ny, this.music.fmActive() && this.isPage("queue"), () -> {
            this.music.startFm();
            this.show(this.queuePage());
        });
        ny = this.navItem("我喜欢的音乐", ny, this.isPage("liked"), () -> this.show(this.likedPage()));
        ny = this.navItem("播放队列", ny, this.isPage("queue") && !this.music.fmActive(), () -> this.show(this.queuePage()));
        ny = this.navItem("本地音乐", ny, this.isPage("local"), () -> this.show(this.localPage()));
        ny = this.navItem("歌词", ny, this.lyricsShown, () -> this.lyricsShown = !this.lyricsShown);

        // Playlists
        ny += 6;
        small.draw("我的歌单", x + 12, ny, GlassTheme.FAINT);
        ny += 12;
        float listTop = ny, listH = y + this.sideH - 6 - listTop;
        List<Playlist> lists = this.music.playlists();
        if (lists.isEmpty()) {
            small.draw(acc.signedIn() ? "加载中…" : "登录后显示", x + 12, ny + 2, GlassTheme.FAINT);
            return;
        }
        this.sideContent = lists.size() * 20;
        if (this.wheel != 0 && this.over(x, listTop, w, listH)) {
            this.sideTarget -= this.wheel / 120f * 40;
            this.wheel = 0;
        }
        this.sideTarget = Math.max(0, Math.min(this.sideTarget, Math.max(0, this.sideContent - listH)));
        this.sideScroll += (this.sideTarget - this.sideScroll) * (1 - (float) Math.exp(-this.dt * 14));
        Draw.pushClip(x, listTop, w, listH);
        float rowY = listTop - this.sideScroll;
        SmoothFont row = SmoothFont.regular(7.5f);
        for (Playlist p : lists) {
            if (rowY + 20 >= listTop && rowY <= listTop + listH) {
                boolean on = this.isPage("playlist:" + p.id);
                boolean hov = this.over(x + 6, rowY, w - 12, 19);
                if (on || hov) Draw.rect(x + 6, rowY, w - 12, 19, 6, on ? 0x1cffffff : 0x0effffff);
                CoverCache.Cover c = this.music.covers().remote(p.coverUrl, 64);
                if (c != null && c.ready()) Draw.image(x + 10, rowY + 3, 13, 13, 3.5f, c.texture, 0, 0, 1, 1);
                else Draw.rect(x + 10, rowY + 3, 13, 13, 3.5f, 0x18ffffff);
                row.draw(row.trim(p.name, w - 42), x + 28, row.centerY(rowY, 19), on ? GlassTheme.TEXT : GlassTheme.MUTED);
                this.hit(x + 6, rowY, w - 12, 19, (b, px, py) -> this.show(this.playlistPage(p)));
            }
            rowY += 20;
        }
        Draw.popClip();
    }

    private float navItem(String label, float y, boolean on, Runnable action) {
        float x = this.sideX + 6, w = this.sideW - 12, h = 20;
        Interaction feel = this.feel("nav:" + label);
        boolean hov = this.over(x, y, w, h);
        feel.update(hov, hov && this.mouseDown, this.dt);
        int bg = on ? 0x22ffffff : Draw.alpha(0xffffff, 0.06f * feel.hot);
        if ((bg >>> 24) > 0) Draw.rect(x, y, w, h, 6, bg);
        if (on) Draw.rect(x + 2, y + 6, 2, h - 12, 1, 0xffffffff);
        SmoothFont f = on ? SmoothFont.bold(8) : SmoothFont.regular(8);
        f.draw(label, x + 10, f.centerY(y, h), on ? GlassTheme.TEXT : GlassTheme.MUTED);
        this.hit(x, y, w, h, (b, px, py) -> {
            UiSound.CLICK.play();
            action.run();
        });
        return y + h + 2;
    }

    private boolean isPage(String key) {
        return !this.lyricsShown && this.page != null && this.page.key.equals(key);
    }

    void show(TrackPage p) {
        this.page = p;
        this.lyricsShown = false;
        this.menu = null;
        if (p.tracks.isEmpty() && !p.loading && p.reload != null) p.reload.run();
    }

    // ================================================================ main area

    private void drawMain() {
        float x = this.mainX, y = this.mainY, w = this.mainW, h = this.mainH;
        TrackPage p = this.page;
        float top = y;
        if (p.key.startsWith("search:")) {
            this.drawSearchField(x, y, w);
            top = y + 34;
        }
        this.list.draw(p, x, top, w, y + h - top);
    }

    private void drawSearchField(float x, float y, float w) {
        float h = 26;
        GlassTheme.field(x, y, w, h, GlassTheme.R_CONTROL + 2, this.searchFocused ? 1 : 0);
        this.searchGlyph(x + 13, y + h / 2);
        SmoothFont f = SmoothFont.regular(8.5f);
        float tx = x + 24, tw = w - 34;
        if (this.search.isEmpty() && !this.searchFocused) {
            f.draw("搜索歌曲、歌手、专辑（直接打字即可）", tx, f.centerY(y, h), GlassTheme.FAINT);
        } else {
            this.drawEdit(this.search, f, tx, y, tw, h, GlassTheme.TEXT, this.searchFocused);
        }
        this.hit(x, y, w, h, (b, px, py) -> {
            this.searchFocused = true;
            this.search.caretToEnd();
        });
    }

    private void searchGlyph(float cx, float cy) {
        int n = 20;
        float[] pts = new float[(n + 1) * 2];
        for (int i = 0; i <= n; i++) {
            double a = Math.PI * 2 * i / n;
            pts[i * 2] = cx - 1 + (float) Math.cos(a) * 3.6f;
            pts[i * 2 + 1] = cy - 1 + (float) Math.sin(a) * 3.6f;
        }
        Draw.polyline(1.1f, 0xaaffffff, pts);
        Draw.polyline(1.3f, 0xaaffffff, cx + 1.6f, cy + 1.6f, cx + 4.2f, cy + 4.2f);
    }

    /** Text of an edit with selection and caret, scrolled to keep the caret in view. */
    private void drawEdit(TextEdit edit, SmoothFont font, float x, float y, float w, float h, int color, boolean focused) {
        String text = edit.toString();
        float full = font.width(text), caretAt = font.width(text.substring(0, Math.min(edit.caret(), text.length())));
        float room = Math.max(1, w - 2), scroll = 0;
        if (full > room) {
            scroll = this.searchScroll;
            if (caretAt - scroll > room) scroll = caretAt - room;
            if (caretAt - scroll < 0) scroll = caretAt;
            scroll = Math.max(0, Math.min(scroll, full - room));
        }
        this.searchScroll = scroll;
        float start = x - scroll;
        int accent = GlassTheme.accent();
        Draw.pushClip(x - 1, y, w + 2, h);
        if (focused && edit.hasSelection()) {
            float from = font.width(text.substring(0, edit.selectionStart())), to = font.width(text.substring(0, edit.selectionEnd()));
            Draw.rect(start + from, y + 5, to - from, h - 10, 2, Draw.alpha(accent, 0.38f));
        }
        if (!text.isEmpty()) font.draw(text, start, font.centerY(y, h) - 0.5f, color);
        Draw.popClip();
        if (focused) {
            float cx = Math.max(x, Math.min(x + w, start + caretAt));
            if (System.currentTimeMillis() % 1000 < 550) Draw.rect(cx, y + 6.5f, 0.8f, h - 13, 0, accent);
            Draw.reportCaret(cx, y + 5, h - 10);
        }
    }

    // ================================================================ pages

    TrackPage searchPage(String query) {
        String key = "search:" + query;
        TrackPage p = this.pages.get(key);
        if (p != null) return p;
        p = new TrackPage(key, query.isEmpty() ? "搜索" : "搜索：" + query);
        TrackPage page = p;
        if (!query.isEmpty()) {
            page.reload = () -> this.loadSearch(page, query, 0);
            page.more = () -> this.loadSearch(page, query, page.tracks.size());
        }
        this.pages.put(key, p);
        return p;
    }

    private void loadSearch(TrackPage p, String query, int offset) {
        if (p.loading) return;
        p.loading = true;
        p.error = "";
        int serial = ++p.serial;
        MusicService.onMain(this.music.search(query, offset, 50), (SearchResult r, String err) -> {
            if (serial != p.serial) return;
            p.loading = false;
            if (err != null) {
                p.error = err;
                return;
            }
            if (offset == 0) p.tracks.clear();
            for (Track t : r.tracks) if (!p.tracks.contains(t)) p.tracks.add(t);
            p.subtitle = "共 " + r.total + " 首";
            p.canLoadMore = !r.tracks.isEmpty() && p.tracks.size() < Math.min(r.total, 600);
        });
    }

    TrackPage dailyPage() {
        return this.remotePage("daily", "每日推荐", true, p -> this.music.dailySongs());
    }

    TrackPage likedPage() {
        TrackPage p = this.pages.get("liked");
        if (p != null) return p;
        p = new TrackPage("liked", "我喜欢的音乐");
        TrackPage page = p;
        page.needsLogin = true;
        page.reload = () -> {
            if (!this.music.signedIn()) {
                page.error = "登录后才能查看";
                return;
            }
            Playlist liked = this.music.likedPlaylist();
            if (liked == null) {
                page.loading = true;
                MusicService.onMain(this.music.loadPlaylists(), (lists, err) -> {
                    page.loading = false;
                    Playlist l = this.music.likedPlaylist();
                    if (l == null) page.error = err == null ? "没有找到「我喜欢的音乐」" : err;
                    else this.loadInto(page, this.music.playlistTracks(l.id));
                });
                return;
            }
            this.loadInto(page, this.music.playlistTracks(liked.id));
        };
        this.pages.put("liked", p);
        return p;
    }

    TrackPage playlistPage(Playlist pl) {
        TrackPage p = this.remotePage("playlist:" + pl.id, pl.name, false, page -> this.music.playlistTracks(pl.id));
        p.subtitle = pl.trackCount + " 首";
        return p;
    }

    TrackPage localPage() {
        TrackPage p = this.pages.get("local");
        if (p != null) {
            if (p.tracks.isEmpty() && !this.music.local().tracks().isEmpty()) p.fill(this.music.local().tracks());
            return p;
        }
        p = new TrackPage("local", "本地音乐");
        TrackPage page = p;
        page.reload = () -> {
            page.loading = true;
            this.music.scanLocal(() -> {
                page.fill(this.music.local().tracks());
                page.subtitle = this.music.local().folder();
                if (page.tracks.isEmpty()) page.error = "这个文件夹里没有 mp3：" + this.music.local().folder();
            });
        };
        if (!this.music.local().tracks().isEmpty()) {
            page.fill(this.music.local().tracks());
            page.subtitle = this.music.local().folder();
        }
        this.pages.put("local", p);
        return p;
    }

    TrackPage queuePage() {
        TrackPage p = this.pages.get("queue");
        if (p == null) {
            p = new TrackPage("queue", "播放队列");
            p.queue = true;
            this.pages.put("queue", p);
        }
        return p;
    }

    /** Keeps the queue page in step with the player's queue. */
    private void syncQueuePage() {
        TrackPage p = this.pages.get("queue");
        if (p == null) return;
        List<Track> q = this.music.queue();
        if (!p.tracks.equals(q)) p.fill(q);
        p.title = this.music.fmActive() ? "私人 FM" : "播放队列";
        String src = this.music.sourceName();
        p.subtitle = q.size() + " 首" + (src.isEmpty() ? "" : " · 来自 " + src) + " · " + this.music.mode().label;
    }

    private interface Loader {
        java.util.concurrent.CompletableFuture<List<Track>> load(TrackPage page);
    }

    private TrackPage remotePage(String key, String title, boolean needsLogin, Loader loader) {
        TrackPage p = this.pages.get(key);
        if (p != null) return p;
        p = new TrackPage(key, title);
        TrackPage page = p;
        page.needsLogin = needsLogin;
        page.reload = () -> {
            if (needsLogin && !this.music.signedIn()) {
                page.error = "登录后才能查看";
                return;
            }
            this.loadInto(page, loader.load(page));
        };
        this.pages.put(key, p);
        return p;
    }

    private void loadInto(TrackPage page, java.util.concurrent.CompletableFuture<List<Track>> future) {
        page.loading = true;
        page.error = "";
        int serial = ++page.serial;
        MusicService.onMain(future, (tracks, err) -> {
            if (serial != page.serial) return;
            page.loading = false;
            if (err != null) {
                page.error = err;
                return;
            }
            page.fill(tracks == null ? Collections.<Track>emptyList() : tracks);
            if (page.subtitle.isEmpty() || page.key.equals("daily") || page.key.equals("liked")) page.subtitle = page.tracks.size() + " 首";
        });
    }

    /** Typing pauses for a moment: search what was typed. */
    private void autoSearch() {
        String q = this.search.toString().trim();
        if (!q.isEmpty() && !q.equals(this.searched) && this.typedAt != 0 && System.currentTimeMillis() - this.typedAt > 450) {
            this.runSearch(q);
        }
    }

    /** For {@link MusicDevCheck}: search as if typed and confirmed. */
    void devSearch(String q) {
        this.runSearch(q);
    }

    private void runSearch(String q) {
        this.typedAt = 0;
        this.searched = q;
        this.requestSerial++;
        this.pages.remove("search:" + q);
        TrackPage p = this.searchPage(q);
        this.page = p;
        this.lyricsShown = false;
        if (p.reload != null) p.reload.run();
    }

    // ================================================================ track actions

    /** A track row was clicked (double click plays). */
    void trackClicked(TrackPage p, int index, int button) {
        if (index < 0 || index >= p.tracks.size()) return;
        Track t = p.tracks.get(index);
        if (button == 1) {
            p.selected = t;
            this.trackMenu(p, index);
            return;
        }
        long now = System.currentTimeMillis();
        boolean twice = t.equals(p.lastClick) && now - p.lastClickAt < DOUBLE_CLICK_MS;
        p.lastClick = t;
        p.lastClickAt = now;
        p.selected = t;
        if (twice) this.playFrom(p, index);
    }

    void playFrom(TrackPage p, int index) {
        if (p.queue) this.music.playIndex(index);
        else this.music.playList(new ArrayList<>(p.tracks), index, p.title);
        UiSound.CLICK.play();
    }

    private void trackMenu(TrackPage p, int index) {
        Track t = p.tracks.get(index);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("播放");
        actions.add(() -> this.playFrom(p, index));
        if (!p.queue) {
            labels.add("下一首播放");
            actions.add(() -> {
                this.music.playNext(t);
                this.toast("将在下一首播放");
            });
            labels.add("加入播放队列");
            actions.add(() -> {
                this.music.enqueue(t);
                this.toast("已加入播放队列");
            });
        } else {
            labels.add("从队列移除");
            actions.add(() -> this.music.removeFromQueue(index));
        }
        if (!t.isLocal()) {
            labels.add(this.music.isLiked(t) ? "取消红心" : "红心");
            actions.add(() -> this.music.toggleLike(t));
        }
        this.openMenu(labels.toArray(new String[0]), actions.toArray(new Runnable[0]));
    }

    // ================================================================ context menu

    private static final class Menu {
        float x, y;
        String[] labels;
        Runnable[] actions;
        final Anim.Smooth show = new Anim.Smooth(0, 18);
    }

    private void openMenu(String[] labels, Runnable[] actions) {
        Menu m = new Menu();
        m.x = this.mx;
        m.y = this.my;
        m.labels = labels;
        m.actions = actions;
        this.menu = m;
        UiSound.EXPAND.play();
    }

    private void drawMenu() {
        Menu m = this.menu;
        float k = m.show.update(1, this.dt);
        SmoothFont f = SmoothFont.regular(8);
        float w = 0;
        for (String l : m.labels) w = Math.max(w, f.width(l));
        w += 24;
        float rowH = 19, h = m.labels.length * rowH + 8;
        float x = Math.min(m.x, this.px + this.pw - w - 4), y = Math.min(m.y, this.py + this.ph - h - 4);
        // A click anywhere else closes it.
        this.hit(0, 0, this.sw, this.sh, (b, px, py) -> this.menu = null);
        Draw.pushAlpha(Anim.outCubic(k));
        Draw.pushTransform(x, y, 0.94f + 0.06f * Anim.outBack(k), 0, 0);
        GlassTheme.panel(x, y, w, h, 8, 1.4f);
        for (int i = 0; i < m.labels.length; i++) {
            float ry = y + 4 + i * rowH;
            boolean hov = this.over(x + 4, ry, w - 8, rowH);
            if (hov) Draw.rect(x + 4, ry, w - 8, rowH, 5, 0x1effffff);
            f.draw(m.labels[i], x + 12, f.centerY(ry, rowH), hov ? GlassTheme.TEXT : 0xffdfe2e8);
            Runnable action = m.actions[i];
            this.hit(x + 4, ry, w - 8, rowH, (b, px, py) -> {
                this.menu = null;
                UiSound.CLICK.play();
                action.run();
            });
        }
        Draw.popTransform();
        Draw.popAlpha();
    }

    // ================================================================ toast

    void toast(String text) {
        this.toast = text;
        this.toastAt = System.currentTimeMillis();
    }

    private void drawToast() {
        long age = System.currentTimeMillis() - this.toastAt;
        if (this.toast.isEmpty() || age > 2200) return;
        float a = age < 180 ? age / 180f : age > 1900 ? (2200 - age) / 300f : 1;
        Draw.pushAlpha(Draw.clamp01(a));
        GlassTheme.toast(this.toast, this.mainX + this.mainW / 2, this.barY - 30);
        Draw.popAlpha();
    }

    // ================================================================ helpers for the views

    Interaction feel(String key) {
        Interaction i = this.feels.get(key);
        if (i == null) {
            i = new Interaction();
            this.feels.put(key, i);
        }
        return i;
    }

    boolean interactive() {
        return this.openTime > 0.12f;
    }

    /** The pointer is over (x, y, w, h) and inside the current clip, and no overlay covers it. */
    boolean over(float x, float y, float w, float h) {
        if (!this.interactive()) return false;
        if (this.login.visible() && !this.login.drawing) return false;
        return this.mx >= x && this.mx < x + w && this.my >= y && this.my < y + h && Draw.insideClip(this.mx, this.my);
    }

    /** Registers a click target (clipped to the current clip). Later targets win. */
    void hit(float x, float y, float w, float h, Action action) {
        this.clipped[0] = x;
        this.clipped[1] = y;
        this.clipped[2] = x + w;
        this.clipped[3] = y + h;
        if (!Draw.clipTo(this.clipped)) return;
        Hit hit;
        if (this.hitCount < this.hits.size()) {
            hit = this.hits.get(this.hitCount);
        } else {
            hit = new Hit();
            this.hits.add(hit);
        }
        this.hitCount++;
        hit.x0 = this.clipped[0];
        hit.y0 = this.clipped[1];
        hit.x1 = this.clipped[2];
        hit.y1 = this.clipped[3];
        hit.action = action;
    }

    /** Takes the wheel movement of this frame if the pointer is over (x, y, w, h); 0 otherwise. */
    int takeWheel(float x, float y, float w, float h) {
        if (this.wheel == 0 || !this.over(x, y, w, h)) return 0;
        int d = this.wheel;
        this.wheel = 0;
        return d;
    }

    /** A pill button with a label; returns its width. */
    float button(String key, String label, float x, float y, float h, boolean primary, Runnable action) {
        SmoothFont f = SmoothFont.bold(7.5f);
        float w = f.width(label) + 20;
        Interaction feel = this.feel(key);
        boolean hov = this.over(x, y, w, h);
        feel.update(hov, hov && this.mouseDown, this.dt);
        float s = feel.scale(0.05f);
        Draw.pushTransform(x + w / 2, y + h / 2, s, 0, 0);
        int bg = primary ? Draw.mix(0xe6ffffff, 0xffffffff, feel.hot) : Draw.alpha(0xffffff, 0.1f + 0.08f * feel.hot);
        Draw.rect(x, y, w, h, h / 2, bg);
        f.drawCentered(label, x + w / 2, f.centerY(y, h), primary ? 0xff111214 : GlassTheme.TEXT);
        Draw.popTransform();
        this.hit(x, y, w, h, (b, px, py) -> {
            feel.pulse();
            UiSound.CLICK.play();
            action.run();
        });
        return w;
    }

    // ================================================================ input

    @Override
    public void handleMouseInput() {
        float x = Mouse.getEventX() / this.ui, y = (mc.displayHeight - Mouse.getEventY() - 1) / this.ui;
        int button = Mouse.getEventButton();
        if (Mouse.getEventButtonState() && button >= 0) this.press(x, y, button);
        int d = Mouse.getEventDWheel();
        if (d != 0) this.wheel += d;
    }

    private void press(float x, float y, int button) {
        if (!this.interactive()) return;
        this.mx = x;
        this.my = y;
        if (this.login.visible() && this.login.blocks(x, y)) {
            // Clicks outside the sign-in card close it; inside it only its own targets count.
            Hit hit = this.hitAt(x, y);
            if (hit != null && this.login.owns(hit.x0, hit.y0, hit.x1, hit.y1)) hit.action.run(button, x, y);
            else if (!this.login.inside(x, y)) this.login.close();
            return;
        }
        Hit hit = this.hitAt(x, y);
        boolean inSearch = hit != null && this.page != null && this.page.key.startsWith("search:")
                && y >= this.mainY && y < this.mainY + 26 && x >= this.mainX && x < this.mainX + this.mainW;
        if (!inSearch) this.searchFocused = false;
        if (hit != null) hit.action.run(button, x, y);
    }

    private Hit hitAt(float x, float y) {
        for (int i = this.hitCount - 1; i >= 0; i--) {
            Hit hit = this.hits.get(i);
            if (x >= hit.x0 && x < hit.x1 && y >= hit.y0 && y < hit.y1) return hit;
        }
        return null;
    }

    @Override
    protected void keyTyped(char typed, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            if (this.menu != null) this.menu = null;
            else if (this.login.visible()) this.login.close();
            else if (this.searchFocused) this.searchFocused = false;
            else if (this.lyricsShown) this.lyricsShown = false;
            else mc.displayGuiScreen(null);
            return;
        }
        if (this.login.visible()) return;
        if (this.searchFocused) {
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
                String q = this.search.toString().trim();
                if (!q.isEmpty()) this.runSearch(q);
                return;
            }
            String before = this.search.toString();
            if (this.search.key(typed, key)) {
                if (!before.equals(this.search.toString())) this.typedAt = System.currentTimeMillis();
                return;
            }
        }
        if (isCtrlKeyDown() && key == Keyboard.KEY_F) {
            this.focusSearch();
            return;
        }
        switch (key) {
            case Keyboard.KEY_SPACE:
                this.music.togglePause();
                return;
            case Keyboard.KEY_LEFT:
                this.music.seek(this.music.positionMs() - 5000);
                return;
            case Keyboard.KEY_RIGHT:
                this.music.seek(this.music.positionMs() + 5000);
                return;
            case Keyboard.KEY_UP:
                this.music.changeVolume(5);
                return;
            case Keyboard.KEY_DOWN:
                this.music.changeVolume(-5);
                return;
            default:
                break;
        }
        // Typing anywhere searches (the input method stays available on this screen).
        if (!isCtrlKeyDown() && typed > ' ' && typed != 127) {
            this.focusSearch();
            String before = this.search.toString();
            this.search.key(typed, key);
            if (!before.equals(this.search.toString())) this.typedAt = System.currentTimeMillis();
        }
    }

    private void focusSearch() {
        if (this.page == null || !this.page.key.startsWith("search:")) this.show(this.searchPage(this.searched));
        this.lyricsShown = false;
        this.searchFocused = true;
        this.search.caretToEnd();
    }
}
