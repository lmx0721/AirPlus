package net.airplus.ui.glass;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * The recipe of every interface sound. Presses and state changes are small struck glass: a sharp
 * contact tick, a bright inharmonic ring that dies away quickly and, for the heavier ones, a short
 * body underneath so they carry weight instead of sounding thin. Things that open get a breath of
 * air. Nothing is sampled.
 *
 * <p>Levels are set against Minecraft's own button click: the main presses land within a few dB of
 * it at the default volume, small repeated ticks sit lower.
 *
 * <p>Pure Java (only {@link Synth}); run {@link #main} to write each sound to a WAV file:
 * {@code javac Synth.java SoundBank.java && java net.airplus.ui.glass.SoundBank <directory>}.
 */
public final class SoundBank {
    public static final String[] NAMES = {
            "click", "toggle-on", "toggle-off", "expand", "collapse", "slider-tick", "open", "close",
            "notify", "success", "error", "hover", "pick", "place", "quick-move", "drop", "hotbar",
            "inventory-open", "inventory-close", "type", "delete", "scroll-tick", "select", "boot", "hit"
    };

    private SoundBank() {
    }

    public static Synth.Clip render(String name) {
        switch (name) {
            case "click": {
                Synth.Clip clip = Synth.clip(150).glide(0, 560, 340, 3, 0.42f, 0.1f, 18, 0);
                return tink(clip, 0, 2093, 1f, 95, 0).highpass(140).saturate(1.3f)
                        .room(0.05f, 90).normalize(0.64f).trim();
            }
            case "toggle-on": {
                // Two notes a fifth apart, rising: G6 then D7.
                Synth.Clip clip = Synth.clip(240);
                tink(clip, 0, 1568, 0.75f, 90, -0.2f);
                tink(clip, 46, 2349.3f, 1f, 150, 0.2f);
                return clip.highpass(160).saturate(1.2f).room(0.07f, 160).normalize(0.57f).trim();
            }
            case "toggle-off": {
                // The same fifth falling, a little darker: C7 then F6.
                Synth.Clip clip = Synth.clip(240);
                tink(clip, 0, 2093, 0.8f, 75, 0.2f);
                tink(clip, 44, 1396.9f, 1f, 120, -0.2f);
                return clip.lowpass(11000).highpass(160).saturate(1.2f).room(0.07f, 150).normalize(0.62f).trim();
            }
            case "expand": {
                Synth.Clip clip = Synth.clip(160)
                        .glide(0, 760, 1180, 18, 0.55f, 2, 60, 0)
                        .noise(0, 4, 30, 3200, 1.2f, 0.22f, 0);
                return tink(clip, 14, 2637, 0.7f, 75, 0.1f).highpass(180)
                        .room(0.08f, 140).normalize(0.75f).trim();
            }
            case "collapse": {
                Synth.Clip clip = Synth.clip(160)
                        .glide(0, 1180, 760, 18, 0.55f, 2, 60, 0)
                        .noise(0, 4, 26, 2400, 1.2f, 0.2f, 0);
                return tink(clip, 12, 1975.5f, 0.65f, 65, -0.1f).lowpass(12000).highpass(180)
                        .room(0.08f, 130).normalize(0.58f).trim();
            }
            case "slider-tick":
                return tink(Synth.clip(50), 0, 3136, 1f, 24, 0).highpass(400).normalize(0.65f).trim();
            case "open":
                return Synth.clip(340)
                        .glide(0, 370, 392, 30, 0.9f, 14, 240, -0.15f)
                        .glide(12, 555, 587.3f, 30, 0.7f, 18, 260, 0.15f)
                        .tone(28, 1174.7f, 0.22f, 6, 220, 0)
                        .tone(40, 2349.3f, 0.12f, 1, 160, 0.1f)
                        .noise(0, 16, 90, 2200, 0.8f, 0.16f, 0)
                        .lowpass(9000).highpass(90).room(0.18f, 360).normalize(0.65f).trim();
            case "close":
                return Synth.clip(280)
                        .glide(0, 620, 392, 50, 1f, 4, 170, 0)
                        .tone(0, 293.7f, 0.4f, 6, 150, 0)
                        .tone(0, 1568, 0.14f, 1, 90, 0)
                        .noise(0, 5, 60, 1800, 0.8f, 0.14f, 0)
                        .lowpass(7500).highpass(90).room(0.14f, 280).normalize(0.65f).trim();
            case "notify":
                return Synth.clip(560)
                        .bell(0, 784, 3.5f, 1.2f, 40, 1f, 1, 300, -0.2f)
                        .bell(88, 1174.7f, 3.5f, 1f, 40, 0.9f, 1, 460, 0.2f)
                        .tone(88, 2349.3f, 0.1f, 1, 220, 0)
                        .noise(0, 0.03f, 2.5f, 7000, 0.8f, 0.25f, 0)
                        .lowpass(12000).highpass(150).room(0.22f, 560).normalize(0.57f).trim();
            case "success":
                return Synth.clip(500)
                        .bell(0, 1046.5f, 2, 0.8f, 30, 0.8f, 1, 200, -0.2f)
                        .bell(60, 1318.5f, 2, 0.8f, 30, 0.8f, 1, 220, 0)
                        .bell(120, 1568, 2, 0.8f, 30, 0.9f, 1, 420, 0.2f)
                        .noise(0, 0.03f, 2.5f, 7000, 0.8f, 0.2f, 0)
                        .lowpass(12000).highpass(150).room(0.2f, 500).normalize(0.54f).trim();
            case "error":
                return Synth.clip(300)
                        .glide(0, 250, 220, 20, 1f, 2, 95, 0)
                        .tone(0, 462, 0.35f, 2, 60, 0)
                        .noise(0, 0.4f, 5, 900, 1f, 0.3f, 0)
                        .glide(105, 225, 196, 20, 1f, 2, 120, 0)
                        .tone(105, 412, 0.35f, 2, 70, 0)
                        .noise(105, 0.4f, 5, 800, 1f, 0.3f, 0)
                        .lowpass(3200).saturate(1.6f).room(0.08f, 160).normalize(0.9f).trim();
            case "hover":
                return tink(Synth.clip(30), 0, 4186, 1f, 12, 0).highpass(800).normalize(0.34f).trim();
            case "pick": {
                Synth.Clip clip = Synth.clip(130).glide(0, 480, 760, 14, 0.6f, 0.8f, 50, 0);
                return tink(clip, 6, 2637, 0.75f, 55, 0).highpass(160).saturate(1.2f)
                        .room(0.06f, 120).normalize(0.73f).trim();
            }
            case "place": {
                Synth.Clip clip = Synth.clip(130).glide(0, 420, 300, 3, 0.55f, 0.1f, 26, 0);
                return tink(clip, 0, 1760, 0.85f, 60, 0).highpass(120).saturate(1.3f)
                        .room(0.06f, 120).normalize(0.71f).trim();
            }
            case "quick-move": {
                Synth.Clip clip = Synth.clip(110).noise(0, 3, 30, 3600, 1.4f, 0.8f, 0);
                return tink(clip, 10, 3136, 0.6f, 40, 0).highpass(300)
                        .room(0.06f, 110).normalize(0.9f).trim();
            }
            case "drop":
                return Synth.clip(170)
                        .glide(0, 520, 230, 40, 1f, 1, 100, 0)
                        .tone(0, 1568, 0.25f, 0.1f, 40, 0)
                        .noise(0, 0.05f, 4, 3200, 1f, 0.35f, 0)
                        .lowpass(9000).highpass(120).room(0.08f, 140).normalize(0.76f).trim();
            case "hotbar": {
                Synth.Clip clip = Synth.clip(60).glide(0, 700, 520, 2, 0.25f, 0.1f, 10, 0);
                return tink(clip, 0, 2793.8f, 1f, 32, 0).highpass(300).saturate(1.2f).normalize(0.62f).trim();
            }
            case "inventory-open":
                return Synth.clip(180)
                        .glide(0, 330, 520, 24, 1f, 4, 80, 0)
                        .tone(20, 2093, 0.16f, 1, 70, 0.1f)
                        .noise(0, 6, 45, 2000, 0.8f, 0.22f, 0)
                        .lowpass(8000).highpass(100).room(0.12f, 220).normalize(0.78f).trim();
            case "inventory-close":
                return Synth.clip(180)
                        .glide(0, 520, 330, 24, 1f, 4, 75, 0)
                        .tone(16, 1568, 0.14f, 1, 60, -0.1f)
                        .noise(0, 6, 40, 1600, 0.8f, 0.2f, 0)
                        .lowpass(6500).highpass(100).room(0.12f, 200).normalize(0.66f).trim();
            case "type": {
                Synth.Clip clip = Synth.clip(40).noise(0, 0.03f, 3, 5200, 1, 0.9f, 0);
                return tink(clip, 0, 3520, 0.45f, 14, 0).highpass(500).normalize(0.86f).trim();
            }
            case "delete": {
                Synth.Clip clip = Synth.clip(44).noise(0, 0.03f, 3.5f, 3600, 1, 0.8f, 0);
                return tink(clip, 0, 2349.3f, 0.5f, 16, 0).highpass(400).normalize(0.72f).trim();
            }
            case "scroll-tick":
                return tink(Synth.clip(30), 0, 2960, 1f, 14, 0).highpass(500).normalize(0.58f).trim();
            case "select": {
                Synth.Clip clip = Synth.clip(120).glide(0, 640, 460, 3, 0.3f, 0.1f, 14, 0);
                return tink(clip, 0, 2637, 1f, 65, 0).highpass(160).saturate(1.2f)
                        .room(0.05f, 100).normalize(0.63f).trim();
            }
            case "boot":
                return boot();
            case "hit": {
                Synth.Clip clip = Synth.clip(90).glide(0, 300, 180, 4, 0.5f, 0.1f, 22, 0)
                        .noise(0, 0.03f, 6, 6000, 0.9f, 0.6f, 0);
                return tink(clip, 0, 3520, 1f, 50, 0).highpass(120).saturate(1.3f)
                        .room(0.04f, 80).normalize(0.75f).trim();
            }
            default:
                throw new IllegalArgumentException("Unknown sound: " + name);
        }
    }

    /**
     * A small struck glass at {@code hz}: four partials at the ratios of a glass bell, the higher
     * ones dying away faster, and a short high tick for the moment of contact. The partials sit a
     * little apart in the stereo field so the ring is wide.
     *
     * @param ringMs time for the fundamental to fall to -60 dB
     */
    private static Synth.Clip tink(Synth.Clip clip, float atMs, float hz, float gain, float ringMs, float pan) {
        return clip.tone(atMs, hz, gain, 0.08f, ringMs, pan)
                .tone(atMs, hz * 2.32f, gain * 0.42f, 0.06f, ringMs * 0.45f, pan + 0.15f)
                .tone(atMs, hz * 4.25f, gain * 0.2f, 0.05f, ringMs * 0.22f, pan - 0.15f)
                .tone(atMs, hz * 6.63f, gain * 0.08f, 0.04f, ringMs * 0.12f, pan)
                .noise(atMs, 0.03f, 2.5f, 7200, 0.8f, gain * 0.55f, pan);
    }

    /**
     * Startup: a wide, slow major chord with an added ninth. Each note is a pair tuned a hair
     * apart between the ears, so the chord shimmers as it fades.
     */
    private static Synth.Clip boot() {
        float[] notes = {110f, 164.81f, 220f, 277.18f, 493.88f, 659.25f};
        float[] gains = {0.9f, 0.7f, 0.8f, 0.6f, 0.42f, 0.3f};
        Synth.Clip clip = Synth.clip(2300);
        for (int i = 0; i < notes.length; i++) {
            float at = i * 22, attack = 160 + i * 20;
            clip.tone(at, notes[i] * 0.9985f, gains[i], attack, 2100, -0.35f)
                    .tone(at, notes[i] * 1.0015f, gains[i], attack, 2100, 0.35f)
                    .tone(at, notes[i] * 2, gains[i] * 0.22f, attack + 40, 1500, 0)
                    .tone(at, notes[i] * 3, gains[i] * 0.07f, attack + 60, 1000, 0);
        }
        return clip.bell(260, 1318.5f, 2, 0.5f, 60, 0.14f, 6, 900, 0.25f)
                .lowpass(5000).highpass(40).room(0.34f, 1500).normalize(0.9f).trim();
    }

    // ---------------------------------------------------------------- audition

    /** Writes every sound as a 16-bit stereo WAV into the directory given as the first argument. */
    public static void main(String[] args) throws IOException {
        File directory = new File(args.length > 0 ? args[0] : "sound-preview");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create " + directory);
        boolean ok = true;
        for (String name : NAMES) {
            Synth.Clip clip = render(name);
            float peak = 0;
            boolean finite = true;
            for (int i = 0; i < clip.length(); i++) {
                float l = clip.left[i], r = clip.right[i];
                if (Float.isNaN(l) || Float.isInfinite(l) || Float.isNaN(r) || Float.isInfinite(r)) finite = false;
                peak = Math.max(peak, Math.max(Math.abs(l), Math.abs(r)));
            }
            boolean good = finite && peak > 0.01f && peak <= 0.9f;
            ok &= good;
            write(new File(directory, name + ".wav"), clip);
            System.out.println(String.format("%-16s %6.0f ms  peak %.2f  %s", name,
                    clip.length() * 1000f / Synth.RATE, peak, good ? "ok" : "BAD"));
        }
        System.out.println(ok ? "ALL OK -> " + directory.getAbsolutePath() : "SOME SOUNDS ARE BAD");
        if (!ok) throw new IllegalStateException("Some sounds failed their checks");
    }

    private static void write(File file, Synth.Clip clip) throws IOException {
        int bytes = clip.length() * 4;
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            out.writeBytes("RIFF");
            out.writeInt(Integer.reverseBytes(36 + bytes));
            out.writeBytes("WAVEfmt ");
            out.writeInt(Integer.reverseBytes(16));
            out.writeShort(Short.reverseBytes((short) 1));
            out.writeShort(Short.reverseBytes((short) 2));
            out.writeInt(Integer.reverseBytes(Synth.RATE));
            out.writeInt(Integer.reverseBytes(Synth.RATE * 4));
            out.writeShort(Short.reverseBytes((short) 4));
            out.writeShort(Short.reverseBytes((short) 16));
            out.writeBytes("data");
            out.writeInt(Integer.reverseBytes(bytes));
            byte[] data = new byte[bytes];
            for (int i = 0; i < clip.length(); i++) {
                int l = Math.round(Math.max(-1, Math.min(1, clip.left[i])) * 32767);
                int r = Math.round(Math.max(-1, Math.min(1, clip.right[i])) * 32767);
                data[i * 4] = (byte) l;
                data[i * 4 + 1] = (byte) (l >> 8);
                data[i * 4 + 2] = (byte) r;
                data[i * 4 + 3] = (byte) (r >> 8);
            }
            out.write(data);
        }
    }
}
