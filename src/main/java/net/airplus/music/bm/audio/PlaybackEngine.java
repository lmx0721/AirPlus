package net.airplus.music.bm.audio;

import javazoom.jl.decoder.Header;
import net.airplus.music.bm.util.BmLog;
import net.airplus.ui.glass.AlStream;
import org.lwjgl.BufferUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Plays one MP3 at a time through Minecraft's OpenAL context ({@link AlStream}). A single daemon
 * thread decodes ahead (about two seconds of audio queued), reports where the playhead is, and
 * feeds the {@link SpectrumAnalyzer}. It knows nothing about NetEase: it is handed an
 * {@link AudioSource}; network sources download on their own thread ({@link Prefetch}), so this
 * thread only decodes what has already arrived and never blocks on the network mid-song.
 *
 * <p>Threads: every public method may be called from any thread. Track changes, seeks and stops
 * are requests the audio thread picks up (latest wins) after a short fade-out; pause and volume
 * take effect through a fade on the audio thread, so nothing clicks. The listener is called on the
 * audio thread.
 *
 * <p>Starting, and recovering after the queue ran dry, waits until {@link #PRIME} chunks are
 * queued: a slow connection then gives one short pause instead of a stutter.
 *
 * <p>When Minecraft reloads its sound engine (F3+T, changing the output device) the queued audio
 * is lost with the old context; playback resumes where it was once the engine is back.
 */
public final class PlaybackEngine {
    public enum State { IDLE, LOADING, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

    public interface Listener {
        /** The decoder found the first frame; {@code durationMs} is final now (0 if unknown). */
        void onReady(long generation, long durationMs);

        /** The track played to its end. */
        void onEnded(long generation);

        void onError(long generation, String message);
    }

    /** About 93 ms per chunk at 44.1 kHz; 24 queued is about 2.2 s, 8 to (re)start about 0.75 s. */
    private static final int CHUNK_FRAMES = 4096, AHEAD = 24, PRIME = 8;
    /** Decode a chunk only when this much compressed audio is already downloaded (or the file is). */
    private static final int READY_BYTES = 16 * 1024;
    private static final float FADE_SECONDS = 0.08f;
    private static final int RETRIES = 2;
    private static final long DRY_LOG_NANOS = 10_000_000_000L;

    private final AlStream stream = new AlStream("Music", AHEAD + 2);
    private final SpectrumAnalyzer spectrum = new SpectrumAnalyzer();
    private final Object signal = new Object();
    private volatile Listener listener;

    // ---- requests (any thread → audio thread)
    private Request pending;
    private volatile boolean paused;
    private volatile float gain = 0.5f;
    private volatile long generation;
    private volatile Session current;

    /**
     * Where the playhead was at {@code nanos}, published as one object so a reader never pairs a
     * position with the wrong timestamp.
     */
    private static final class Clock {
        final long ms, frame, nanos;
        final boolean advancing;

        Clock(long ms, long frame, long nanos, boolean advancing) {
            this.ms = ms;
            this.frame = frame;
            this.nanos = nanos;
            this.advancing = advancing;
        }
    }

    // ---- published state (audio thread → anyone)
    private volatile State state = State.IDLE;
    private volatile Clock clock = new Clock(0, -1, System.nanoTime(), false);
    private volatile long durationMs;
    private volatile int rate = 44100;

    // ---- audio thread only
    private Session session;
    private float fade;
    private long lastLoop = System.nanoTime();
    /** Enough is queued to play; false at the start and after the queue ran dry. */
    private boolean primed;
    private long lastDryLog;
    private final ByteBuffer pcm = BufferUtils.createByteBuffer(CHUNK_FRAMES * 2 * 2 + 4608 * 2).order(ByteOrder.nativeOrder());
    private short[] chunk = new short[CHUNK_FRAMES * 2 + 4608];

    public PlaybackEngine() {
        Thread thread = new Thread(this::run, "BetterMyau-Music-Audio");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 2);
        thread.start();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------ control (any thread)

    /**
     * Starts a new track (the old one fades out).
     *
     * @param durationHintMs known length, or 0 to work it out from the file
     * @return the generation number the listener reports for this track
     */
    public long load(AudioSource source, long startMs, long durationHintMs, boolean startPaused) {
        long gen;
        synchronized (this.signal) {
            gen = ++this.generation;
            this.paused = startPaused;
            if (this.pending != null && this.pending.source != null) this.pending.source.close();
            this.pending = new Request(Request.LOAD, gen, source, Math.max(0, startMs), durationHintMs);
            this.state = State.LOADING;
            this.durationMs = Math.max(0, durationHintMs);
            this.clock = new Clock(Math.max(0, startMs), -1, System.nanoTime(), false);
            this.signal.notifyAll();
        }
        this.abortCurrentRead();
        return gen;
    }

    public void seek(long ms) {
        synchronized (this.signal) {
            if (this.state == State.IDLE || this.state == State.ERROR) return;
            if (this.pending != null && this.pending.kind == Request.LOAD) {
                // The track has not started yet: start it there instead.
                Request load = this.pending;
                this.pending = new Request(Request.LOAD, load.generation, load.source, Math.max(0, ms), load.durationHint);
            } else {
                this.pending = new Request(Request.SEEK, this.generation, null, Math.max(0, ms), 0);
            }
            this.clock = new Clock(Math.max(0, ms), -1, System.nanoTime(), false);
            this.signal.notifyAll();
        }
        this.abortCurrentRead();
    }

    public void stop() {
        synchronized (this.signal) {
            this.generation++;
            if (this.pending != null && this.pending.source != null) this.pending.source.close();
            this.pending = new Request(Request.STOP, this.generation, null, 0, 0);
            this.state = State.IDLE;
            Clock c = this.clock;
            this.clock = new Clock(c.ms, -1, System.nanoTime(), false);
            this.signal.notifyAll();
        }
        this.abortCurrentRead();
    }

    public void setPaused(boolean paused) {
        synchronized (this.signal) {
            this.paused = paused;
            this.signal.notifyAll();
        }
    }

    public boolean isPaused() {
        return this.paused;
    }

    /** Output gain, 0..1 (the service turns the volume setting into this). */
    public void setGain(float gain) {
        this.gain = Math.max(0, Math.min(1, gain));
    }

    // ------------------------------------------------------------ state (any thread)

    public State state() {
        return this.state;
    }

    /** Where the listener is in the track, extrapolated between audio-thread updates. */
    public long positionMs() {
        Clock c = this.clock;
        long p = c.ms;
        if (c.advancing) p += Math.min(250, (System.nanoTime() - c.nanos) / 1_000_000L);
        long d = this.durationMs;
        return d > 0 ? Math.min(p, d) : p;
    }

    /** Whether the playhead is moving right now (playing, not paused, not waiting for data). */
    public boolean advancing() {
        return this.clock.advancing;
    }

    public long durationMs() {
        return this.durationMs;
    }

    /** Render thread: advances the spectrum to what is being heard right now. */
    public SpectrumAnalyzer updateSpectrum(float dt) {
        Clock c = this.clock;
        long frame = c.frame;
        if (frame >= 0 && c.advancing) frame += Math.min(250_000_000L, System.nanoTime() - c.nanos) * this.rate / 1_000_000_000L;
        this.spectrum.update(this.state == State.PLAYING ? frame : -1, dt);
        return this.spectrum;
    }

    public SpectrumAnalyzer spectrum() {
        return this.spectrum;
    }

    // ------------------------------------------------------------ audio thread

    private static final class Request {
        static final int LOAD = 0, SEEK = 1, STOP = 2;
        final int kind;
        final long generation, ms, durationHint;
        final AudioSource source;

        Request(int kind, long generation, AudioSource source, long ms, long durationHint) {
            this.kind = kind;
            this.generation = generation;
            this.source = source;
            this.ms = ms;
            this.durationHint = durationHint;
        }
    }

    private static final class Session {
        final long generation;
        final AudioSource source;
        long durationMs;
        Mp3Decoder decoder;
        volatile InputStream in;
        /** Position in the track where the current decoder (and the OpenAL frame count) starts. */
        long baseMs;
        /** Tags before the first frame and the first header, for seeking by byte offset. */
        int audioStart = -1;
        Header header;
        boolean decoderDone, done, readyReported, lostOutput;
        int retries;

        Session(long generation, AudioSource source, long durationHint) {
            this.generation = generation;
            this.source = source;
            this.durationMs = Math.max(0, durationHint);
        }
    }

    private void abortCurrentRead() {
        Session s = this.current;
        InputStream in = s == null ? null : s.in;
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void run() {
        while (true) {
            try {
                this.loop();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                BmLog.LOGGER.warn("[Music] Audio thread error", t);
                Session s = this.session;
                if (s != null) this.fail(s, "播放出错：" + t);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void loop() throws InterruptedException {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - this.lastLoop) / 1e9f);
        this.lastLoop = now;

        Request request;
        synchronized (this.signal) {
            request = this.pending;
            boolean idle = request == null && (this.session == null || this.session.done) && this.fade <= 0;
            if (idle) {
                this.signal.wait(100);
                return;
            }
        }
        Session s = this.session;
        boolean audible = s != null && !s.done && this.fade > 0;
        // Fade out before anything that interrupts the sound; fade with pause / resume.
        boolean wantSound = request == null && !this.paused && s != null && !s.done;
        this.fade = wantSound ? Math.min(1, this.fade + dt / FADE_SECONDS) : Math.max(0, this.fade - dt / FADE_SECONDS);
        if (request != null && (!audible || this.fade <= 0)) {
            synchronized (this.signal) {
                if (this.pending == request) this.pending = null;
            }
            this.apply(request);
            return;
        }
        if (s == null || s.done) {
            synchronized (AlStream.LOCK) {
                if (this.stream.open()) this.stream.setGain(0);
            }
            Thread.sleep(5);
            return;
        }
        if (!this.step(s)) Thread.sleep(this.paused ? 20 : 6);
    }

    private void apply(Request r) {
        Session old = this.session;
        if (r.kind == Request.SEEK) {
            if (old == null) return;
            old.done = false;
            old.retries = 0;
            this.openAt(old, r.ms);
            return;
        }
        if (old != null) {
            if (old.decoder != null) old.decoder.close();
            old.source.close();
        }
        this.session = null;
        this.current = null;
        synchronized (AlStream.LOCK) {
            if (this.stream.open()) this.stream.flush();
        }
        this.primed = false;
        this.spectrum.reset(this.rate);
        if (r.kind == Request.STOP) {
            this.state = State.IDLE;
            Clock c = this.clock;
            this.clock = new Clock(c.ms, -1, System.nanoTime(), false);
            return;
        }
        Session s = new Session(r.generation, r.source, r.durationHint);
        this.session = s;
        this.current = s;
        this.openAt(s, r.ms);
    }

    /** (Re)opens the source at {@code ms}: byte offset from the bitrate (or the VBR table of contents). */
    private void openAt(Session s, long ms) {
        if (s.decoder != null) s.decoder.close();
        s.decoder = null;
        s.decoderDone = false;
        synchronized (AlStream.LOCK) {
            if (this.stream.open()) this.stream.flush();
        }
        this.primed = false;
        long offset = 0, skipMs = 0;
        long length = s.source.length();
        if (ms > 0) {
            if (length > 0 && s.durationMs > 0 && s.audioStart >= 0) {
                double fraction = Math.min(0.999, ms / (double) s.durationMs);
                long audioBytes = length - s.audioStart;
                byte[] toc = s.header != null && s.header.vbr() ? s.header.vbr_toc() : null;
                if (toc != null && toc.length >= 100) {
                    double pct = fraction * 100;
                    int i = Math.min(99, (int) pct);
                    double a = toc[i] & 0xff, b = i < 99 ? toc[i + 1] & 0xff : 256;
                    offset = s.audioStart + (long) ((a + (b - a) * (pct - i)) / 256.0 * audioBytes);
                } else {
                    offset = s.audioStart + (long) (fraction * audioBytes);
                }
            } else {
                skipMs = ms; // no size known yet: decode from the start and drop what comes before
            }
        }
        try {
            InputStream in = s.source.open(offset);
            s.in = in;
            s.decoder = new Mp3Decoder(in);
            s.baseMs = ms;
            if (this.state != State.PAUSED) this.state = this.paused ? State.PAUSED : State.BUFFERING;
            if (skipMs > 0) this.skip(s, skipMs);
        } catch (IOException e) {
            if (this.isStale(s)) return;
            this.fail(s, "无法打开音频：" + e.getMessage());
            return;
        }
        this.spectrum.reset(s.decoder.rate());
        this.clock = new Clock(ms, 0, System.nanoTime(), false);
    }

    private void skip(Session s, long ms) throws IOException {
        double done = 0;
        while (done < ms && s.decoder.next()) {
            this.noteFirstFrame(s);
            done += s.decoder.length() / (double) s.decoder.channels() * 1000.0 / s.decoder.rate();
        }
    }

    /** One round: keep the OpenAL queue full and publish the playhead. False when there was nothing to do. */
    private boolean step(Session s) {
        boolean room, reopen = false, ended = false;
        int queued = 0;
        synchronized (AlStream.LOCK) {
            if (!this.stream.open()) {
                // Engine reloading: the context and our queued audio are gone. Wait for it.
                s.lostOutput = true;
                this.publishStill();
                return false;
            }
            if (s.lostOutput) {
                s.lostOutput = false;
                reopen = true;
                room = false;
            } else {
                queued = this.stream.pending();
                this.stream.setGain(this.gain * this.fade * this.fade);
                if (this.primed && queued == 0 && !s.decoderDone) {
                    // Ran dry: wait until a good amount is queued again before playing on.
                    this.primed = false;
                    this.noteDry(s);
                }
                if (!this.primed && (queued >= PRIME || s.decoderDone && queued > 0)) this.primed = true;
                this.publish(s, queued);
                if (this.paused && this.fade <= 0) {
                    this.stream.pause();
                    return false;
                }
                if (s.decoderDone && queued == 0) {
                    s.done = true;
                    this.state = State.ENDED;
                    this.publishStill();
                    ended = true;
                    room = false;
                } else {
                    if (this.primed && queued > 0 && !this.paused) this.stream.play();
                    room = !s.decoderDone && s.decoder != null && queued < AHEAD && this.stream.hasFree();
                }
            }
        }
        // Network and listener work happen outside the lock: the interface sounds share it.
        if (reopen) {
            this.openAt(s, this.clock.ms);
            return true;
        }
        if (ended) {
            Listener l = this.listener;
            if (l != null) l.onEnded(s.generation);
            return false;
        }
        if (!room) return false;
        // Only decode what has already arrived, so this thread keeps publishing the playhead
        // (and the state shows BUFFERING) while the download catches up.
        if (!s.source.ready(s.in, READY_BYTES)) return false;
        this.decodeChunk(s);
        return true;
    }

    private void publish(Session s, int queued) {
        long frames = this.stream.playedFrames();
        int r = s.decoder != null ? s.decoder.rate() : this.rate;
        this.rate = r;
        boolean playing = !this.paused && this.primed && queued > 0;
        this.clock = new Clock(s.baseMs + frames * 1000L / Math.max(1, r), frames, System.nanoTime(), playing);
        if (this.paused) this.state = State.PAUSED;
        else if (playing) this.state = State.PLAYING;
        else if (!s.decoderDone) this.state = State.BUFFERING;
    }

    /** The playhead stops where it is (output lost, track ended). */
    private void publishStill() {
        Clock c = this.clock;
        if (c.advancing) this.clock = new Clock(c.ms, c.frame, c.nanos, false);
    }

    private void noteDry(Session s) {
        long now = System.nanoTime();
        if (now - this.lastDryLog < DRY_LOG_NANOS) return;
        this.lastDryLog = now;
        long at = this.clock.ms / 1000;
        long ahead = s.in != null ? s.source.ahead(s.in) : -1;
        BmLog.LOGGER.info("[Music] Buffer ran dry at {}:{} ({})", at / 60, String.format("%02d", at % 60),
                ahead >= 0 ? "downloaded " + ahead / 1024 + " KB ahead" : "local file");
    }

    private void decodeChunk(Session s) {
        int filled = 0, channels = 0, rate = 0;
        try {
            while (filled < CHUNK_FRAMES * Math.max(1, channels)) {
                if (!s.decoder.next()) {
                    s.decoderDone = true;
                    break;
                }
                this.noteFirstFrame(s);
                int ch = s.decoder.channels(), r = s.decoder.rate(), len = s.decoder.length();
                if (channels == 0) {
                    channels = ch;
                    rate = r;
                } else if (ch != channels || r != rate) {
                    break; // format changed mid-stream (rare): the next chunk starts with it
                }
                if (filled + len > this.chunk.length) this.chunk = java.util.Arrays.copyOf(this.chunk, filled + len);
                System.arraycopy(s.decoder.samples(), 0, this.chunk, filled, len);
                filled += len;
            }
        } catch (IOException e) {
            if (this.isStale(s)) return;
            if (s.retries++ < RETRIES && !(s.source instanceof AudioSource.FileSource)) {
                BmLog.LOGGER.info("[Music] Stream interrupted, reconnecting ({})", e.getMessage());
                this.openAt(s, this.clock.ms);
                return;
            }
            if (filled == 0) {
                this.fail(s, "音频读取失败：" + e.getMessage());
                return;
            }
            s.decoderDone = true;
        }
        if (filled == 0 || channels == 0) return;
        // Sized for a full chunk plus one frame; a stranger stream is dropped rather than overflowing.
        if (this.pcm.capacity() < filled * 2) return;
        this.spectrum.push(this.chunk, filled, channels, rate);
        this.pcm.clear();
        this.pcm.asShortBuffer().put(this.chunk, 0, filled);
        this.pcm.limit(filled * 2);
        synchronized (AlStream.LOCK) {
            if (this.isStale(s) || !this.stream.open()) return;
            if (!this.stream.write(this.pcm, channels, rate)) {
                BmLog.LOGGER.warn("[Music] No free OpenAL buffer; a chunk was dropped");
                return;
            }
            // While priming, play() waits for step() to see enough queued.
            if (this.primed && !this.paused) this.stream.play();
        }
    }

    private void noteFirstFrame(Session s) {
        if (s.readyReported) return;
        s.readyReported = true;
        s.header = s.decoder.firstHeader();
        // The first open of a session is always at byte 0, so this is where the audio begins.
        if (s.audioStart < 0) s.audioStart = s.decoder.audioStart();
        if (s.durationMs <= 0 && s.header != null && s.source.length() > 0) {
            s.durationMs = (long) s.header.total_ms((int) Math.min(Integer.MAX_VALUE, s.source.length() - Math.max(0, s.audioStart)));
        }
        this.durationMs = s.durationMs;
        Listener l = this.listener;
        if (l != null) l.onReady(s.generation, s.durationMs);
    }

    /** Work for {@code s} is moot: another track, a seek or a stop is waiting. */
    private boolean isStale(Session s) {
        synchronized (this.signal) {
            return s != this.session || this.pending != null;
        }
    }

    private void fail(Session s, String message) {
        if (s.decoder != null) s.decoder.close();
        s.source.close();
        s.done = true;
        s.decoderDone = true;
        this.state = State.ERROR;
        this.publishStill();
        Listener l = this.listener;
        if (l != null) l.onError(s.generation, message);
    }
}
