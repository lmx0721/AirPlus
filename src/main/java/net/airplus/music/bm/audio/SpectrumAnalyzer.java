package net.airplus.music.bm.audio;

/**
 * Live spectrum of what is being heard. The audio thread {@link #push pushes} every decoded block
 * (mixed to mono, indexed by stream frame); once per frame the render thread {@link #update
 * analyses} the 1024 samples ending at the playhead, so the bars move with the sound coming out
 * of the speakers rather than with the decoder running ahead of it.
 *
 * <p>Output: {@link #BANDS} log-spaced bands (40 Hz – 16 kHz) in 0..1 with fast attack and slower
 * release, the bass level, an overall level, and a beat pulse for little bounces. All of it is
 * computed from the decoded signal, before the volume, so the visuals don't change with volume.
 */
public final class SpectrumAnalyzer {
    public static final int BANDS = 32;
    private static final int N = 1024, LOG_N = 10;
    private static final int RING = 1 << 15;

    // ---- written by the audio thread (under ringLock)
    private final Object ringLock = new Object();
    private final float[] ring = new float[RING];
    private long written;
    private volatile int rate = 44100;

    // ---- render thread only
    private final float[] re = new float[N], im = new float[N], window = new float[N];
    private final float[] bands = new float[BANDS];
    private final float[] raw = new float[BANDS];
    private float bass, level, bassAverage, beat;
    private int beatSerial;
    private float sinceBeat = 1;

    public SpectrumAnalyzer() {
        for (int i = 0; i < N; i++) this.window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1)));
    }

    /** Audio thread: forget everything (new track or seek). Frames restart at 0. */
    void reset(int rate) {
        synchronized (this.ringLock) {
            this.written = 0;
            this.rate = rate;
            java.util.Arrays.fill(this.ring, 0);
        }
    }

    /** Audio thread: interleaved 16-bit samples of the next decoded block. */
    void push(short[] samples, int length, int channels, int rate) {
        synchronized (this.ringLock) {
            this.rate = rate;
            int frames = length / channels;
            for (int f = 0; f < frames; f++) {
                float v;
                if (channels == 1) {
                    v = samples[f] / 32768f;
                } else {
                    int i = f * channels;
                    v = (samples[i] + samples[i + 1]) / 65536f;
                }
                this.ring[(int) ((this.written + f) & (RING - 1))] = v;
            }
            this.written += frames;
        }
    }

    /**
     * Render thread, once per frame.
     *
     * @param playhead stream frame being heard, or -1 when nothing is playing (bars fall)
     */
    public void update(long playhead, float dt) {
        boolean live = playhead >= 0 && this.fill(playhead);
        float attack = 1 - (float) Math.exp(-dt * 30), release = 1 - (float) Math.exp(-dt * 7);
        if (live) {
            this.analyse();
        } else {
            java.util.Arrays.fill(this.raw, 0);
        }
        float sum = 0;
        for (int b = 0; b < BANDS; b++) {
            float target = this.raw[b];
            float k = target > this.bands[b] ? attack : release;
            this.bands[b] += (target - this.bands[b]) * k;
            sum += this.bands[b];
        }
        this.level = sum / BANDS;
        float bassNow = (this.raw[0] + this.raw[1] + this.raw[2] + this.raw[3] + this.raw[4]) / 5f;
        this.bass += (bassNow - this.bass) * (bassNow > this.bass ? attack : release);
        // Beat: bass jumps clearly above its recent average.
        this.bassAverage += (bassNow - this.bassAverage) * (1 - (float) Math.exp(-dt * 1.5));
        this.sinceBeat += dt;
        if (live && bassNow > 0.3f && bassNow > this.bassAverage * 1.28f + 0.04f && this.sinceBeat > 0.22f) {
            this.sinceBeat = 0;
            this.beat = 1;
            this.beatSerial++;
        }
        this.beat *= (float) Math.exp(-dt * 7);
    }

    /** Band {@code i} (0 = lowest) in 0..1. */
    public float band(int i) {
        return this.bands[Math.max(0, Math.min(BANDS - 1, i))];
    }

    /** Average of bands {@code from..to} (inclusive), for drawing fewer, wider bars. */
    public float range(int from, int to) {
        float s = 0;
        int n = 0;
        for (int i = Math.max(0, from); i <= Math.min(BANDS - 1, to); i++, n++) s += this.bands[i];
        return n == 0 ? 0 : s / n;
    }

    public float bass() {
        return this.bass;
    }

    public float level() {
        return this.level;
    }

    /** 1 on a beat, decaying to 0 in a few hundred milliseconds. */
    public float beat() {
        return this.beat;
    }

    /** Increments once per detected beat. */
    public int beatSerial() {
        return this.beatSerial;
    }

    private boolean fill(long playhead) {
        synchronized (this.ringLock) {
            long end = Math.min(playhead, this.written);
            long start = end - N;
            if (start < 0 || this.written - start > RING) return false;
            for (int i = 0; i < N; i++) {
                this.re[i] = this.ring[(int) ((start + i) & (RING - 1))] * this.window[i];
                this.im[i] = 0;
            }
            return true;
        }
    }

    private void analyse() {
        fft(this.re, this.im);
        float nyquist = this.rate / 2f;
        float lo = 40, hi = Math.min(16000, nyquist * 0.95f);
        double ratio = Math.log(hi / lo);
        for (int b = 0; b < BANDS; b++) {
            float f0 = (float) (lo * Math.exp(ratio * b / BANDS));
            float f1 = (float) (lo * Math.exp(ratio * (b + 1) / BANDS));
            int i0 = Math.max(1, (int) (f0 / nyquist * (N / 2)));
            int i1 = Math.max(i0 + 1, (int) Math.ceil(f1 / nyquist * (N / 2)));
            float peak = 0;
            for (int i = i0; i < i1 && i < N / 2; i++) {
                float mag = (float) Math.sqrt(this.re[i] * this.re[i] + this.im[i] * this.im[i]);
                if (mag > peak) peak = mag;
            }
            // Amplitude in dBFS (Hann window halves it), tilted +3 dB/octave so treble shows.
            double db = 20 * Math.log10(peak * 4.0 / N + 1e-9) + 3 * (Math.log(((f0 + f1) / 2) / 1000.0) / Math.log(2));
            float v = (float) ((db + 62) / 52);
            this.raw[b] = v < 0 ? 0 : v > 1 ? 1 : v;
        }
    }

    /** In-place iterative radix-2 FFT. */
    private static void fft(float[] re, float[] im) {
        for (int i = 1, j = 0; i < N; i++) {
            int bit = N >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int s = 1; s <= LOG_N; s++) {
            int m = 1 << s, half = m >> 1;
            double theta = -2 * Math.PI / m;
            float wr = (float) Math.cos(theta), wi = (float) Math.sin(theta);
            for (int k = 0; k < N; k += m) {
                float cr = 1, ci = 0;
                for (int j = 0; j < half; j++) {
                    int a = k + j, b = a + half;
                    float tr = cr * re[b] - ci * im[b], ti = cr * im[b] + ci * re[b];
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    float ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }
}
