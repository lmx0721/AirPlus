package net.airplus.ui.glass;

import java.util.Arrays;
import java.util.Random;

/**
 * Building blocks for the interface sounds: decaying partials, noise transients, FM bells,
 * filters and a small stereo room. Pure Java with no Minecraft classes, so {@link SoundBank}
 * can be compiled and run on its own to audition every sound without starting the game.
 */
public final class Synth {
    public static final int RATE = 44100;
    private static final double TAU = Math.PI * 2;
    /** Exponent that takes an envelope down to -60 dB. */
    private static final double SILENT = 6.908;

    // Comb and allpass lengths of the room, in samples; the right channel differs from the left
    // so the tail is wide instead of sitting in the middle.
    private static final int[] COMB_LEFT = {683, 751, 811, 877};
    private static final int[] COMB_RIGHT = {701, 769, 829, 907};
    private static final int[] ALLPASS_LEFT = {241, 83};
    private static final int[] ALLPASS_RIGHT = {257, 97};

    private Synth() {
    }

    public static Clip clip(float millis) {
        return new Clip(samples(millis));
    }

    static int samples(float millis) {
        return Math.max(1, Math.round(RATE * millis / 1000f));
    }

    /** A stereo clip under construction. Every method returns the clip so a recipe reads as a chain. */
    public static final class Clip {
        public float[] left, right;
        // Fixed seed: a sound is the same every time the game starts.
        private final Random random = new Random(0x5eedL);

        Clip(int length) {
            left = new float[length];
            right = new float[length];
        }

        public int length() {
            return left.length;
        }

        /**
         * A sine partial with a raised-cosine attack and an exponential decay.
         *
         * @param decayMs time for the partial to fall to -60 dB
         * @param pan     -1 left .. 1 right
         */
        public Clip tone(float atMs, float hz, float gain, float attackMs, float decayMs, float pan) {
            return glide(atMs, hz, hz, 1, gain, attackMs, decayMs, pan);
        }

        /** A partial whose pitch settles from {@code fromHz} to {@code toHz} with time constant {@code glideMs}. */
        public Clip glide(float atMs, float fromHz, float toHz, float glideMs, float gain, float attackMs, float decayMs, float pan) {
            int start = Math.round(RATE * atMs / 1000f), attack = samples(attackMs);
            double decay = SILENT / Math.max(1.0, RATE * decayMs / 1000.0);
            double settle = 1.0 / Math.max(1.0, RATE * glideMs / 1000.0);
            float gl = gain * panLeft(pan), gr = gain * panRight(pan);
            double phase = 0;
            for (int i = 0; start + i < left.length; i++) {
                double envelope = Math.exp(-decay * i);
                if (envelope < 1e-4) break;
                if (i < attack) envelope *= 0.5 - 0.5 * Math.cos(Math.PI * i / attack);
                phase += TAU * (toHz + (fromHz - toHz) * Math.exp(-i * settle)) / RATE;
                float sample = (float) (Math.sin(phase) * envelope);
                left[start + i] += sample * gl;
                right[start + i] += sample * gr;
            }
            return this;
        }

        /**
         * A two-operator FM note. The modulation index decays faster than the note, so the attack
         * is bright and glassy and the tail is a plain sine.
         */
        public Clip bell(float atMs, float hz, float ratio, float index, float indexDecayMs, float gain, float attackMs, float decayMs, float pan) {
            int start = Math.round(RATE * atMs / 1000f), attack = samples(attackMs);
            double decay = SILENT / Math.max(1.0, RATE * decayMs / 1000.0);
            double indexDecay = SILENT / Math.max(1.0, RATE * indexDecayMs / 1000.0);
            float gl = gain * panLeft(pan), gr = gain * panRight(pan);
            for (int i = 0; start + i < left.length; i++) {
                double envelope = Math.exp(-decay * i);
                if (envelope < 1e-4) break;
                if (i < attack) envelope *= 0.5 - 0.5 * Math.cos(Math.PI * i / attack);
                double t = i / (double) RATE;
                double modulator = Math.sin(TAU * hz * ratio * t) * index * Math.exp(-indexDecay * i);
                float sample = (float) (Math.sin(TAU * hz * t + modulator) * envelope);
                left[start + i] += sample * gl;
                right[start + i] += sample * gr;
            }
            return this;
        }

        /** Band-passed noise: the tactile part of a click, or (with a slow attack) a breath of air. */
        public Clip noise(float atMs, float attackMs, float decayMs, float centerHz, float q, float gain, float pan) {
            int start = Math.round(RATE * atMs / 1000f), attack = samples(attackMs);
            double decay = SILENT / Math.max(1.0, RATE * decayMs / 1000.0);
            // RBJ band-pass, constant 0 dB peak gain
            double w = TAU * Math.min(centerHz, RATE * 0.45) / RATE, alpha = Math.sin(w) / (2 * q);
            double a0 = 1 + alpha, b0 = alpha / a0, b2 = -alpha / a0, a1 = -2 * Math.cos(w) / a0, a2 = (1 - alpha) / a0;
            double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
            float gl = gain * panLeft(pan), gr = gain * panRight(pan);
            for (int i = 0; start + i < left.length; i++) {
                double envelope = Math.exp(-decay * i);
                if (envelope < 1e-4) break;
                if (i < attack) envelope *= 0.5 - 0.5 * Math.cos(Math.PI * i / attack);
                double x = random.nextDouble() * 2 - 1;
                double y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2;
                x2 = x1;
                x1 = x;
                y2 = y1;
                y1 = y;
                float sample = (float) (y * envelope);
                left[start + i] += sample * gl;
                right[start + i] += sample * gr;
            }
            return this;
        }

        /** Two one-pole low-pass stages (12 dB per octave): rounds off anything harsh. */
        public Clip lowpass(float hz) {
            float k = (float) (1 - Math.exp(-TAU * hz / RATE));
            onePole(left, k);
            onePole(left, k);
            onePole(right, k);
            onePole(right, k);
            return this;
        }

        /** Removes rumble and any DC offset below {@code hz}. */
        public Clip highpass(float hz) {
            float k = (float) (1 - Math.exp(-TAU * hz / RATE));
            for (float[] channel : new float[][]{left, right}) {
                float low = 0;
                for (int i = 0; i < channel.length; i++) {
                    low += k * (channel[i] - low);
                    channel[i] -= low;
                }
            }
            return this;
        }

        /** Gentle tube-like saturation; {@code drive} around 1..3. */
        public Clip saturate(float drive) {
            float norm = (float) Math.tanh(drive);
            for (int i = 0; i < left.length; i++) {
                left[i] = (float) Math.tanh(left[i] * drive) / norm;
                right[i] = (float) Math.tanh(right[i] * drive) / norm;
            }
            return this;
        }

        /**
         * A small room: four damped combs and two allpasses per channel. The clip grows by the
         * length of the tail.
         *
         * @param wet     level of the room against the dry sound
         * @param decayMs time for the tail to fall to -60 dB
         */
        public Clip room(float wet, float decayMs) {
            int length = left.length + samples(decayMs);
            float[] dryLeft = Arrays.copyOf(left, length), dryRight = Arrays.copyOf(right, length);
            float[] wetLeft = reverb(dryLeft, dryRight, COMB_LEFT, ALLPASS_LEFT, decayMs);
            float[] wetRight = reverb(dryLeft, dryRight, COMB_RIGHT, ALLPASS_RIGHT, decayMs);
            for (int i = 0; i < length; i++) {
                dryLeft[i] += wetLeft[i] * wet;
                dryRight[i] += wetRight[i] * wet;
            }
            left = dryLeft;
            right = dryRight;
            return this;
        }

        /** Scales the clip so its loudest sample is {@code peak}. */
        public Clip normalize(float peak) {
            float max = 0;
            for (int i = 0; i < left.length; i++) max = Math.max(max, Math.max(Math.abs(left[i]), Math.abs(right[i])));
            if (max < 1e-6f) return this;
            float scale = peak / max;
            for (int i = 0; i < left.length; i++) {
                left[i] *= scale;
                right[i] *= scale;
            }
            return this;
        }

        /** Drops the inaudible end of the clip and fades the last few milliseconds to zero. */
        public Clip trim() {
            int end = left.length;
            while (end > 1 && Math.abs(left[end - 1]) < 2e-4f && Math.abs(right[end - 1]) < 2e-4f) end--;
            left = Arrays.copyOf(left, end);
            right = Arrays.copyOf(right, end);
            int fade = Math.min(end, samples(4));
            for (int i = 0; i < fade; i++) {
                float f = i / (float) fade;
                left[end - 1 - i] *= f;
                right[end - 1 - i] *= f;
            }
            return this;
        }

        private static void onePole(float[] channel, float k) {
            float state = 0;
            for (int i = 0; i < channel.length; i++) {
                state += k * (channel[i] - state);
                channel[i] = state;
            }
        }

        private static float[] reverb(float[] a, float[] b, int[] combs, int[] allpasses, float decayMs) {
            int length = a.length, delay = samples(6);
            float[] in = new float[length], out = new float[length];
            for (int i = delay; i < length; i++) in[i] = (a[i - delay] + b[i - delay]) * 0.5f;
            for (int size : combs) {
                // Feedback chosen so this comb reaches -60 dB after decayMs.
                float feedback = (float) Math.pow(10, -3.0 * size / Math.max(1.0, RATE * decayMs / 1000.0));
                float[] buffer = new float[size];
                float damped = 0;
                int at = 0;
                for (int i = 0; i < length; i++) {
                    float y = buffer[at];
                    damped = y * 0.72f + damped * 0.28f;
                    buffer[at] = in[i] + damped * feedback;
                    out[i] += y;
                    if (++at == size) at = 0;
                }
            }
            for (int i = 0; i < length; i++) out[i] /= combs.length;
            for (int size : allpasses) {
                float[] buffer = new float[size];
                int at = 0;
                for (int i = 0; i < length; i++) {
                    float delayed = buffer[at], x = out[i];
                    buffer[at] = x + delayed * 0.5f;
                    out[i] = delayed - x;
                    if (++at == size) at = 0;
                }
            }
            return out;
        }

        private static float panLeft(float pan) {
            return (float) Math.cos((Math.max(-1, Math.min(1, pan)) + 1) * Math.PI / 4) * 1.41421356f;
        }

        private static float panRight(float pan) {
            return (float) Math.sin((Math.max(-1, Math.min(1, pan)) + 1) * Math.PI / 4) * 1.41421356f;
        }
    }
}
