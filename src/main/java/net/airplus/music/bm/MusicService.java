package net.airplus.music.bm;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.airplus.music.bm.util.BmLog;
import net.airplus.music.bm.api.DirectNeteaseBackend;
import net.airplus.music.bm.api.MusicApiException;
import net.airplus.music.bm.api.MusicBackend;
import net.airplus.music.bm.api.NeteaseSession;
import net.airplus.music.bm.api.RemoteApiBackend;
import net.airplus.music.bm.audio.AudioSource;
import net.airplus.music.bm.audio.PlaybackEngine;
import net.airplus.music.bm.audio.SpectrumAnalyzer;
import net.airplus.music.bm.cover.CoverCache;
import net.airplus.music.bm.local.LocalLibrary;
import net.airplus.music.bm.model.Account;
import net.airplus.music.bm.model.Lyrics;
import net.airplus.music.bm.model.Playlist;
import net.airplus.music.bm.model.SearchResult;
import net.airplus.music.bm.model.Track;
import net.airplus.music.bm.model.TrackUrl;
import net.airplus.music.bm.store.MusicStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import net.airplus.ui.client.hud.HUD;
import net.airplus.ui.client.hud.element.elements.Notification;
import net.airplus.ui.client.hud.element.elements.Notifications;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * The music player: the queue, what is playing, the NetEase account, and the glue between the
 * API layer ({@link MusicBackend}) and the audio layer ({@link PlaybackEngine}). Everything the
 * interface needs goes through here.
 *
 * <p><b>Threads.</b> Public methods are for the main (render) thread; the queue and the current
 * track are only touched there. Network work runs on the IO pool and comes back through
 * {@link #onMain}. Engine callbacks are moved to the main thread as well.
 */
public final class MusicService {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static MusicService instance;

    public static MusicService get() {
        if (instance == null) instance = new MusicService();
        return instance;
    }

    /** Whether the service exists yet (it starts with the client). */
    public static boolean running() {
        return instance != null;
    }

    private final ExecutorService io = Executors.newFixedThreadPool(3,
            new ThreadFactoryBuilder().setDaemon(true).setNameFormat("BetterMyau-Music-IO-%d").build());
    private final PlaybackEngine engine = new PlaybackEngine();
    private final CoverCache covers = new CoverCache(this.io);
    private final LocalLibrary local = new LocalLibrary();
    private final NeteaseSession session;
    private volatile MusicBackend backend;
    private String backendKey = "";
    private MusicSettings settings = MusicSettings.DEFAULTS;

    // ---- queue (main thread)
    private final List<Track> queue = new ArrayList<>();
    private int index = -1;
    private String source = "";
    private PlayMode mode = PlayMode.LOOP;
    private boolean fm;
    private final ArrayDeque<Integer> shuffleHistory = new ArrayDeque<>();
    private final Random random = new Random();

    // ---- current track (main thread)
    private Track current;
    private TrackUrl currentUrl;
    private Lyrics lyrics = Lyrics.EMPTY;
    private long loadSerial, engineGeneration = -1;
    private boolean loading;
    private long resumeAtMs = -1;
    private long trackChangedNanos;
    private int failures;
    private long skipAtNanos;
    private String status = "";

    // ---- account (main thread)
    private Account account = Account.GUEST;
    private final Set<Long> liked = new HashSet<>();
    private List<Playlist> playlists = Collections.emptyList();

    private long lastSaveNanos;
    private boolean fmFetching;

    private MusicService() {
        MusicStore.Session saved = MusicStore.loadSession();
        this.session = new NeteaseSession(saved.deviceId);
        if (!saved.cookie.isEmpty()) this.session.importCookieString(saved.cookie);
        if (this.session.signedIn()) this.account = saved.account;
        this.session.setOnChange(this::saveSession);
        if (saved.deviceId.isEmpty()) this.saveSession();

        MusicStore.State state = MusicStore.loadState();
        this.queue.addAll(state.queue);
        this.index = state.index;
        this.mode = PlayMode.parse(state.mode);
        this.source = state.source;
        if (this.index >= 0 && this.index < this.queue.size()) {
            this.current = this.queue.get(this.index);
            this.resumeAtMs = state.positionMs;
        }

        this.engine.setListener(new PlaybackEngine.Listener() {
            @Override
            public void onReady(long generation, long durationMs) {
            }

            @Override
            public void onEnded(long generation) {
                mc.addScheduledTask(() -> {
                    if (generation == MusicService.this.engineGeneration) MusicService.this.advance(true);
                });
            }

            @Override
            public void onError(long generation, String message) {
                mc.addScheduledTask(() -> {
                    if (generation == MusicService.this.engineGeneration) MusicService.this.failed(message);
                });
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveStateNow, "BetterMyau-Music-save"));
        // Sign-in check and guest token in the background.
        this.async(() -> {
            MusicBackend b = this.backend();
            b.ensureGuest();
            if (this.session.signedIn()) {
                b.refreshLogin();
                return b.account();
            }
            return Account.GUEST;
        }).whenComplete((acc, err) -> mc.addScheduledTask(() -> {
            if (err == null && acc != null) this.setAccount(acc);
        }));
    }

    // ------------------------------------------------------------ settings / per frame

    public void setSettings(MusicSettings settings) {
        this.settings = settings == null ? MusicSettings.DEFAULTS : settings;
    }

    public MusicSettings settings() {
        return this.settings;
    }

    private final List<Runnable> nextFrame = new ArrayList<>();

    /**
     * Runs {@code task} at the start of the next frame. A chat command that opens a screen needs
     * this: chat closes itself (and whatever screen was just opened) after sending the line.
     */
    public void runNextFrame(Runnable task) {
        this.nextFrame.add(task);
    }

    /** Main thread, every frame (menus included). */
    public void frame() {
        if (!this.nextFrame.isEmpty()) {
            List<Runnable> tasks = new ArrayList<>(this.nextFrame);
            this.nextFrame.clear();
            for (Runnable r : tasks) {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    BmLog.LOGGER.warn("[Music] Deferred task failed", e);
                }
            }
        }
        float master = this.settings.followGameVolume() ? mc.gameSettings.getSoundLevel(SoundCategory.MASTER) : 1;
        float v = this.settings.volume() / 100f;
        this.engine.setGain(v * v * master);

        long now = System.nanoTime();
        if (this.skipAtNanos != 0 && now >= this.skipAtNanos) {
            this.skipAtNanos = 0;
            this.advance(true);
        }
        if (this.fm && !this.fmFetching && this.index >= this.queue.size() - 2) this.fetchMoreFm();
        if (this.current != null && this.isActive() && now - this.lastSaveNanos > 15_000_000_000L) {
            this.lastSaveNanos = now;
            this.saveStateAsync();
        }
    }

    // ------------------------------------------------------------ backend

    /** The backend for the current settings (rebuilt when they change). Any thread. */
    public MusicBackend backend() {
        MusicSettings s = this.settings;
        String key = s.customApi() ? "custom:" + s.apiUrl() : "direct";
        MusicBackend b = this.backend;
        if (b == null || !key.equals(this.backendKey)) {
            synchronized (this) {
                if (this.backend == null || !key.equals(this.backendKey)) {
                    this.backend = s.customApi() ? new RemoteApiBackend(this.session, s.apiUrl()) : new DirectNeteaseBackend(this.session);
                    this.backendKey = key;
                }
                b = this.backend;
            }
        }
        return b;
    }

    // ------------------------------------------------------------ async helpers

    public <T> CompletableFuture<T> async(Callable<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        this.io.execute(() -> {
            try {
                f.complete(work.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    /** Runs {@code done} on the main thread with the result or a readable error message. */
    public static <T> void onMain(CompletableFuture<T> future, BiConsumer<T, String> done) {
        future.whenComplete((value, error) -> mc.addScheduledTask(() -> done.accept(value, error == null ? null : describe(error))));
    }

    public static String describe(Throwable t) {
        Throwable e = t;
        while ((e instanceof CompletionException || e instanceof java.util.concurrent.ExecutionException) && e.getCause() != null) e = e.getCause();
        if (e instanceof MusicApiException) return ((MusicApiException) e).describe();
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    public ExecutorService io() {
        return this.io;
    }

    // ------------------------------------------------------------ browsing

    public CompletableFuture<SearchResult> search(String keywords, int offset, int limit) {
        return this.async(() -> this.backend().search(keywords, offset, limit));
    }

    public CompletableFuture<List<Track>> dailySongs() {
        return this.async(() -> this.backend().dailySongs());
    }

    public CompletableFuture<List<Track>> playlistTracks(long id) {
        return this.async(() -> this.backend().playlistTracks(id));
    }

    public CompletableFuture<List<Playlist>> loadPlaylists() {
        long uid = this.account.uid;
        CompletableFuture<List<Playlist>> f = this.async(() -> uid == 0 ? Collections.<Playlist>emptyList() : this.backend().userPlaylists(uid));
        onMain(f, (list, err) -> {
            if (list != null) this.playlists = list;
        });
        return f;
    }

    public List<Playlist> playlists() {
        return this.playlists;
    }

    /** The account's "I like" playlist, if loaded. */
    public Playlist likedPlaylist() {
        for (Playlist p : this.playlists) if (p.liked) return p;
        return this.playlists.isEmpty() ? null : this.playlists.get(0).creatorId == this.account.uid ? this.playlists.get(0) : null;
    }

    public LocalLibrary local() {
        return this.local;
    }

    public void scanLocal(Runnable done) {
        String folder = this.settings.localFolder();
        List<File> dirs = folder == null || folder.trim().isEmpty() ? defaultLocalFolders()
                : Collections.singletonList(new File(folder.trim()));
        this.local.scan(dirs, this.io, done == null ? null : () -> mc.addScheduledTask(done));
    }

    /** No folder set: the user's Music and Downloads folders (where songs usually are). */
    public static List<File> defaultLocalFolders() {
        List<File> out = new ArrayList<>();
        File home = new File(System.getProperty("user.home"));
        for (String name : new String[]{"Music", "Downloads"}) {
            File f = new File(home, name);
            if (f.isDirectory()) out.add(f);
        }
        if (out.isEmpty()) out.add(MusicStore.DIR);
        return out;
    }

    public CoverCache covers() {
        return this.covers;
    }

    /** Cover of a track at {@code size}px (local art from the file's tags). Main thread. */
    public CoverCache.Cover cover(Track t, int size) {
        if (t == null) return null;
        if (t.isLocal()) return this.covers.get("local:" + t.path, size, () -> LocalLibrary.coverBytes(t));
        return this.covers.remote(t.coverUrl, size);
    }

    // ------------------------------------------------------------ playback control

    /** Replaces the queue with {@code tracks} and plays {@code start}. */
    public void playList(List<Track> tracks, int start, String sourceName) {
        if (tracks == null || tracks.isEmpty()) return;
        this.fm = false;
        this.queue.clear();
        this.queue.addAll(tracks);
        this.source = sourceName == null ? "" : sourceName;
        this.shuffleHistory.clear();
        this.failures = 0;
        this.startTrack(Math.max(0, Math.min(start, tracks.size() - 1)), 0, false);
    }

    public void play(Track track) {
        int at = this.queue.indexOf(track);
        if (at >= 0) {
            this.startTrack(at, 0, false);
        } else {
            this.queue.add(this.index + 1, track);
            this.startTrack(this.index + 1, 0, false);
        }
    }

    public void playNext(Track track) {
        if (this.queue.isEmpty()) {
            this.playList(Collections.singletonList(track), 0, "");
            return;
        }
        int existing = this.queue.indexOf(track);
        if (existing >= 0 && existing != this.index) {
            this.queue.remove(existing);
            if (existing < this.index) this.index--;
        }
        this.queue.add(Math.min(this.queue.size(), this.index + 1), track);
        this.saveStateAsync();
    }

    public void enqueue(Track track) {
        if (this.queue.isEmpty()) {
            this.playList(Collections.singletonList(track), 0, "");
            return;
        }
        if (!this.queue.contains(track)) this.queue.add(track);
        this.saveStateAsync();
    }

    public void removeFromQueue(int i) {
        if (i < 0 || i >= this.queue.size()) return;
        if (i == this.index) {
            this.queue.remove(i);
            if (this.queue.isEmpty()) {
                this.stop();
            } else {
                this.startTrack(Math.min(i, this.queue.size() - 1), 0, this.engine.isPaused());
            }
            return;
        }
        this.queue.remove(i);
        if (i < this.index) this.index--;
        this.saveStateAsync();
    }

    public void playIndex(int i) {
        if (i >= 0 && i < this.queue.size()) {
            this.failures = 0;
            this.startTrack(i, 0, false);
        }
    }

    public void togglePause() {
        if (!this.isActive()) {
            // Nothing loaded (fresh start or stopped): play the current track.
            this.setPaused(false);
            return;
        }
        this.setPaused(!this.engine.isPaused());
    }

    public void setPaused(boolean paused) {
        if (!paused && !this.isActive()) {
            // Nothing loaded yet (fresh start, or stopped): start the current track.
            if (this.current != null && this.index >= 0) {
                long at = this.resumeAtMs > 0 ? this.resumeAtMs : 0;
                this.startTrack(this.index, at, false);
            }
            return;
        }
        this.engine.setPaused(paused);
        this.trackChangedNanos = System.nanoTime();
        this.saveStateAsync();
    }

    public void next() {
        this.failures = 0;
        this.advance(false);
    }

    public void previous() {
        if (this.queue.isEmpty()) return;
        if (this.isActive() && this.engine.positionMs() > 3000) {
            this.seek(0);
            return;
        }
        int target;
        if (this.mode == PlayMode.SHUFFLE && !this.fm && !this.shuffleHistory.isEmpty()) {
            target = this.shuffleHistory.pop();
        } else {
            target = this.index - 1;
            if (target < 0) target = this.mode == PlayMode.SEQUENCE ? 0 : this.queue.size() - 1;
        }
        this.failures = 0;
        this.startTrack(target, 0, false, false);
    }

    public void stop() {
        this.loadSerial++;
        this.engine.stop();
        this.engineGeneration = -1;
        this.loading = false;
        this.resumeAtMs = this.current == null ? -1 : 0;
        this.saveStateAsync();
    }

    public void seek(long ms) {
        if (!this.isActive()) {
            this.resumeAtMs = Math.max(0, ms);
            return;
        }
        long d = this.durationMs();
        this.engine.seek(d > 0 ? Math.max(0, Math.min(ms, d - 500)) : Math.max(0, ms));
    }

    public void setMode(PlayMode mode) {
        this.mode = mode;
        this.shuffleHistory.clear();
        this.saveStateAsync();
    }

    public void changeVolume(int delta) {
        this.settings.setVolume(Math.max(0, Math.min(100, this.settings.volume() + delta)));
    }

    /** Personal FM: an endless queue NetEase picks; ends any other queue. */
    public void startFm() {
        if (!this.requireLogin()) return;
        this.status = "正在获取私人 FM…";
        onMain(this.async(() -> this.backend().personalFm()), (tracks, err) -> {
            if (err != null || tracks == null || tracks.isEmpty()) {
                this.notifyUser("私人 FM", err == null ? "没有拿到歌曲" : err, false);
                return;
            }
            this.playList(tracks, 0, "私人 FM");
            this.fm = true;
        });
    }

    public boolean fmActive() {
        return this.fm;
    }

    private void fetchMoreFm() {
        this.fmFetching = true;
        onMain(this.async(() -> this.backend().personalFm()), (tracks, err) -> {
            this.fmFetching = false;
            if (tracks == null || !this.fm) return;
            for (Track t : tracks) if (!this.queue.contains(t)) this.queue.add(t);
        });
    }

    // ------------------------------------------------------------ track lifecycle

    private void advance(boolean automatic) {
        if (this.queue.isEmpty()) return;
        int target;
        if (automatic && this.mode == PlayMode.ONE && !this.fm && this.index >= 0) {
            target = this.index;
        } else if (this.mode == PlayMode.SHUFFLE && !this.fm && this.queue.size() > 1) {
            do {
                target = this.random.nextInt(this.queue.size());
            } while (target == this.index);
        } else {
            target = this.index + 1;
            if (target >= this.queue.size()) {
                if (automatic && (this.mode == PlayMode.SEQUENCE || this.fm)) {
                    // End of the list.
                    this.stop();
                    return;
                }
                target = 0;
            }
        }
        this.startTrack(target, 0, false, true);
    }

    private void startTrack(int i, long startMs, boolean paused) {
        this.startTrack(i, startMs, paused, true);
    }

    private void startTrack(int i, long startMs, boolean paused, boolean remember) {
        if (i < 0 || i >= this.queue.size()) return;
        if (remember && this.index >= 0 && this.index != i) {
            this.shuffleHistory.push(this.index);
            while (this.shuffleHistory.size() > 200) this.shuffleHistory.removeLast();
        }
        Track t = this.queue.get(i);
        boolean changed = !t.equals(this.current);
        this.index = i;
        this.current = t;
        this.currentUrl = null;
        this.resumeAtMs = -1;
        if (changed) this.lyrics = Lyrics.EMPTY;
        this.loading = true;
        this.status = "加载中…";
        this.trackChangedNanos = System.nanoTime();
        long serial = ++this.loadSerial;
        this.engine.stop();
        this.engineGeneration = -1;

        if (changed || this.lyrics.isEmpty()) this.loadLyrics(t, serial);
        if (t.isLocal()) {
            File f = new File(t.path);
            if (!f.isFile()) {
                this.failed("文件不存在：" + f.getName());
                return;
            }
            this.begin(AudioSource.file(f), startMs, t.durationMs, paused);
            return;
        }
        MusicBackend b = this.backend();
        onMain(this.async(() -> b.trackUrl(t.id, this.settings.quality())), (url, err) -> {
            if (serial != this.loadSerial) return;
            if (err != null) {
                this.failed(err);
                return;
            }
            if (url == null) {
                this.failed(t.vip() ? "需要黑胶 VIP" : "暂无版权");
                return;
            }
            if (url.trial && this.settings.skipTrial()) {
                this.failed("VIP 歌曲，仅试听片段，已跳过");
                return;
            }
            this.currentUrl = url;
            long duration = url.trial && url.trialEndMs > url.trialStartMs ? url.trialEndMs - url.trialStartMs : t.durationMs;
            this.begin(AudioSource.http(url.url, url.size), url.trial ? 0 : startMs, duration, paused);
        });
    }

    private void begin(AudioSource source, long startMs, long durationMs, boolean paused) {
        this.loading = false;
        this.status = "";
        this.engineGeneration = this.engine.load(source, startMs, durationMs, paused);
        this.failures = 0;
        this.saveStateAsync();
    }

    private void loadLyrics(Track t, long serial) {
        MusicBackend b = this.backend();
        onMain(this.async(() -> t.isLocal() ? LocalLibrary.lyrics(t) : b.lyrics(t.id)), (lyr, err) -> {
            if (serial != this.loadSerial && !t.equals(this.current)) return;
            if (t.equals(this.current)) this.lyrics = lyr == null ? Lyrics.EMPTY : lyr;
        });
    }

    private void failed(String message) {
        this.loading = false;
        Track t = this.current;
        this.notifyUser(t == null ? "音乐" : t.name, message, false);
        this.failures++;
        if (this.failures < Math.min(5, this.queue.size())) {
            this.status = message + "，即将跳过";
            this.skipAtNanos = System.nanoTime() + 1_200_000_000L;
        } else {
            this.status = message;
            this.engine.stop();
            this.engineGeneration = -1;
        }
    }

    // ------------------------------------------------------------ account

    public Account account() {
        return this.account;
    }

    public boolean signedIn() {
        return this.account.signedIn();
    }

    private boolean requireLogin() {
        if (this.signedIn()) return true;
        this.notifyUser("网易云音乐", "这个功能需要先扫码登录（.music login）", false);
        return false;
    }

    /** Called once the QR sign-in is confirmed (the session already holds the cookie). */
    public CompletableFuture<Account> refreshAccount() {
        CompletableFuture<Account> f = this.async(() -> this.backend().account());
        onMain(f, (acc, err) -> {
            if (acc != null) this.setAccount(acc);
        });
        return f;
    }

    private void setAccount(Account acc) {
        if (!acc.signedIn() && this.session.signedIn()) {
            // The saved login expired.
            this.session.signOut();
            this.notifyUser("网易云音乐", "登录已过期，请重新扫码", false);
        }
        this.account = acc;
        this.saveSession();
        this.liked.clear();
        this.playlists = Collections.emptyList();
        if (acc.signedIn()) {
            long uid = acc.uid;
            onMain(this.async(() -> this.backend().likedIds(uid)), (ids, err) -> {
                if (ids != null && uid == this.account.uid) this.liked.addAll(ids);
            });
            this.loadPlaylists();
        }
    }

    public void signOut() {
        this.session.signOut();
        this.account = Account.GUEST;
        this.liked.clear();
        this.playlists = Collections.emptyList();
        this.saveSession();
        this.fm = false;
    }

    public NeteaseSession session() {
        return this.session;
    }

    public boolean isLiked(Track t) {
        return t != null && !t.isLocal() && this.liked.contains(t.id);
    }

    public void toggleLike(Track t) {
        if (t == null || t.isLocal() || !this.requireLogin()) return;
        boolean like = !this.liked.contains(t.id);
        if (like) this.liked.add(t.id);
        else this.liked.remove(t.id);
        onMain(this.async(() -> {
            this.backend().like(t.id, like);
            return true;
        }), (ok, err) -> {
            if (err != null) {
                if (like) this.liked.remove(t.id);
                else this.liked.add(t.id);
                this.notifyUser("红心", err, false);
            }
        });
    }

    // ------------------------------------------------------------ state for the interface

    public Track current() {
        return this.current;
    }

    public List<Track> queue() {
        return Collections.unmodifiableList(this.queue);
    }

    public int queueIndex() {
        return this.index;
    }

    public String sourceName() {
        return this.source;
    }

    public PlayMode mode() {
        return this.mode;
    }

    /** Loaded in this session (playing, paused, buffering): the player is "on". */
    public boolean isActive() {
        PlaybackEngine.State s = this.engine.state();
        return this.loading || (s != PlaybackEngine.State.IDLE && s != PlaybackEngine.State.ERROR && this.engineGeneration >= 0)
                || this.skipAtNanos != 0;
    }

    /** Not making sound on purpose: paused, ended, or nothing loaded yet (a restored track). */
    public boolean isPaused() {
        return !this.isActive() || this.engine.isPaused() || this.engine.state() == PlaybackEngine.State.ENDED;
    }

    public boolean isPlaying() {
        return this.engine.state() == PlaybackEngine.State.PLAYING;
    }

    /** Loading or waiting for the network. */
    public boolean isWaiting() {
        PlaybackEngine.State s = this.engine.state();
        return this.loading || s == PlaybackEngine.State.LOADING || s == PlaybackEngine.State.BUFFERING;
    }

    public PlaybackEngine.State engineState() {
        return this.engine.state();
    }

    public long positionMs() {
        if (!this.isActive()) return Math.max(0, this.resumeAtMs);
        return this.engine.positionMs();
    }

    public long durationMs() {
        long d = this.engine.durationMs();
        if (d <= 0 && this.current != null) d = this.currentUrl != null && this.currentUrl.trial ? 30_000 : this.current.durationMs;
        return d;
    }

    /** Lyric time minus playback position: where a trial clip starts in the song, else 0. */
    public long lyricOffsetMs() {
        return this.currentUrl != null && this.currentUrl.trial ? this.currentUrl.trialStartMs : 0;
    }

    // The lyric clock: smooth, and never backwards while playing.
    private double lyricMs;
    private long lyricNanos;
    private boolean lyricSet;

    /**
     * Position in the song's own timeline (a trial clip starts in the middle), for lyrics. The
     * engine's playhead moves in steps (OpenAL reports its offset once per mix period, about
     * 20 ms) and is re-anchored every few milliseconds, so word-by-word highlighting drawn straight
     * from it trembles. This clock runs on real time while the engine says the playhead is moving
     * and is pulled gently toward the engine's value; a big difference (seek, new track, a stall)
     * moves it at once.
     */
    public synchronized long lyricTimeMs() {
        long raw = this.positionMs() + this.lyricOffsetMs();
        long now = System.nanoTime();
        boolean moving = this.isActive() && this.engine.advancing();
        if (!this.lyricSet) {
            this.lyricSet = true;
            this.lyricMs = raw;
        } else {
            double dt = Math.min(0.1, (now - this.lyricNanos) / 1e9);
            double predicted = this.lyricMs + (moving ? dt * 1000 : 0);
            double error = raw - predicted;
            if (Math.abs(error) > 300) {
                predicted = raw;
            } else if (moving) {
                // Close the gap over a quarter of a second; never step back while playing.
                predicted = Math.max(this.lyricMs, predicted + error * Math.min(1, dt * 4));
            } else if (error > 0) {
                predicted = raw; // held: only catch up, so a pause never rewinds the line
            }
            this.lyricMs = predicted;
        }
        this.lyricNanos = now;
        return (long) this.lyricMs;
    }

    public boolean isTrial() {
        return this.currentUrl != null && this.currentUrl.trial;
    }

    public Lyrics lyrics() {
        return this.lyrics;
    }

    /** When the track (or play / pause) last changed, for the island to show it off. */
    public long trackChangedNanos() {
        return this.trackChangedNanos;
    }

    public String status() {
        return this.status;
    }

    private long spectrumNanos;

    /**
     * Render thread: the live spectrum, advanced to now. Any number of callers per frame (island,
     * lyrics, the music screen) see the same values; it advances by real time.
     */
    public SpectrumAnalyzer spectrum() {
        long now = System.nanoTime();
        float dt = (now - this.spectrumNanos) / 1e9f;
        if (dt > 0.004f) {
            this.spectrumNanos = now;
            return this.engine.updateSpectrum(Math.min(0.1f, dt));
        }
        return this.engine.spectrum();
    }

    // ------------------------------------------------------------ diagnostics

    /** Probes every endpoint and reports one line each (for {@code .music diag}). */
    public CompletableFuture<List<String>> diagnose() {
        return this.async(() -> {
            List<String> out = new ArrayList<>();
            MusicBackend b = this.backend();
            out.add("&7接口：&f" + b.name() + (this.session.signedIn() ? " &a已登录" : " &e游客"));
            long probe = 0;
            try {
                b.ensureGuest();
                SearchResult r = b.search("周杰伦", 0, 5);
                out.add("&a✔ &f搜索 &7(" + r.total + " 条)");
                for (Track t : r.tracks) if (!t.vip()) {
                    probe = t.id;
                    break;
                }
                if (probe == 0 && !r.tracks.isEmpty()) probe = r.tracks.get(0).id;
            } catch (MusicApiException e) {
                out.add("&c✘ &f搜索 &7" + e.describe());
            }
            if (probe != 0) {
                long id = probe;
                out.add(check("歌曲详情", () -> b.songDetail(Collections.singletonList(id)).size() + " 首"));
                out.add(check("播放地址", () -> {
                    TrackUrl u = b.trackUrl(id, this.settings.quality());
                    return u == null ? "无（版权）" : u.type + " " + u.bitrate / 1000 + "kbps" + (u.trial ? " 试听" : "");
                }));
                out.add(check("歌词", () -> {
                    Lyrics l = b.lyrics(id);
                    return l.lines.size() + " 行" + (l.wordTimed ? "（逐字）" : "");
                }));
            }
            out.add(check("扫码登录", () -> b.qrKey().isEmpty() ? "无" : "可用"));
            if (this.session.signedIn()) {
                out.add(check("账号", () -> b.account().nickname));
                out.add(check("每日推荐", () -> b.dailySongs().size() + " 首"));
            }
            return out;
        });
    }

    private interface Probe {
        String run() throws Exception;
    }

    private static String check(String name, Probe p) {
        try {
            return "&a✔ &f" + name + " &7" + p.run();
        } catch (MusicApiException e) {
            return "&c✘ &f" + name + " &7" + e.describe();
        } catch (Exception e) {
            return "&c✘ &f" + name + " &7" + e;
        }
    }

    // ------------------------------------------------------------ persistence / notices

    private void saveSession() {
        String deviceId = this.session.deviceId();
        String cookie = this.session.cookieString();
        Account acc = this.account;
        this.io.execute(() -> MusicStore.saveSession(deviceId, cookie, acc));
    }

    private void saveStateAsync() {
        List<Track> q = new ArrayList<>(this.queue);
        int i = this.index;
        long pos = this.positionMs();
        String m = this.mode.name(), src = this.source;
        this.io.execute(() -> MusicStore.saveState(q, i, pos, m, src));
    }

    private void saveStateNow() {
        try {
            MusicStore.saveState(new ArrayList<>(this.queue), this.index, this.positionMs(), this.mode.name(), this.source);
        } catch (RuntimeException ignored) {
        }
    }

    public void notifyUser(String title, String message, boolean positive) {
        BmLog.LOGGER.info("[Music] {}: {}", title, message);
        if (mc.thePlayer != null) {
            HUD.INSTANCE.addNotification(new Notification(title, message, 2000L,
                    positive ? Notifications.SeverityType.SUCCESS : Notifications.SeverityType.ERROR));
        }
    }
}
