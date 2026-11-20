package net.airplus.ui.glass;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Interface sounds for the glass UI. Every sound is synthesized when the class loads (recipes in
 * {@link SoundBank}, no audio files) and mixed in stereo on one daemon thread, which streams the
 * mix into Minecraft's own OpenAL context ({@link AlOutput}). No second audio device is opened,
 * so the game's sounds and these always share one output.
 *
 * <p>Each play is varied a little in pitch and level so repeated clicks never sound stamped out,
 * and pointer-driven sounds lean towards the side of the screen the cursor is on.
 */
public enum UiSound {
    /** Buttons and plain clicks. */
    CLICK("click", 22, 0.025f, true),
    /** A switch or module turning on. */
    TOGGLE_ON("toggle-on", 30, 0.012f, false),
    /** A switch or module turning off. */
    TOGGLE_OFF("toggle-off", 30, 0.012f, false),
    /** A row expanding, a dropdown opening, a field gaining focus. */
    EXPAND("expand", 30, 0.02f, true),
    /** A row or panel folding away. */
    COLLAPSE("collapse", 30, 0.02f, true),
    /** One step of a slider being dragged. */
    SLIDER_TICK("slider-tick", 45, 0.01f, true),
    /** A screen opening. */
    OPEN("open", 80, 0, false),
    /** A screen closing. */
    CLOSE("close", 80, 0, false),
    /** A notification appearing. */
    NOTIFY("notify", 120, 0, false),
    /** Something finished well. */
    SUCCESS("success", 120, 0, false),
    /** Something was rejected. */
    ERROR("error", 120, 0.01f, false),
    /** The cursor entering a control: barely audible, off by default. */
    HOVER("hover", 60, 0.03f, true),
    /** An item lifted onto the cursor. */
    PICK("pick", 25, 0.03f, true),
    /** The cursor's item set down in a slot. */
    PLACE("place", 25, 0.03f, true),
    /** A shift-click sending a stack across. */
    QUICK_MOVE("quick-move", 25, 0.03f, true),
    /** An item thrown out of the inventory. */
    DROP("drop", 40, 0.03f, true),
    /** The selected hotbar slot changing. */
    HOTBAR("hotbar", 18, 0.008f, false),
    /** A container or the inventory opening. */
    INV_OPEN("inventory-open", 80, 0.015f, false),
    /** A container or the inventory closing. */
    INV_CLOSE("inventory-close", 80, 0.015f, false),
    /** A character typed into a text field. */
    TYPE("type", 12, 0.04f, true),
    /** A character removed from a text field. */
    DELETE("delete", 12, 0.04f, true),
    /** A list scrolling past one row. */
    SCROLL_TICK("scroll-tick", 40, 0.03f, true),
    /** A list row chosen. */
    SELECT("select", 30, 0.02f, true),
    /** Startup chord, played once when the main menu first appears. */
    BOOT("boot", 5000, 0, false),
    /** A hit confirmed on the crosshair's target. */
    HIT("hit", 40, 0.02f, false);

    private static final int CHUNK = 256; // about 6 ms
    /** Chunks kept queued in OpenAL while sounds play: about 23 ms, enough to ride out a late wake-up. */
    private static final int AHEAD = 4;
    private static final int MAX_VOICES = 16;
    /** A sound asked for while the sound engine is (re)starting is still played if it gets there in time. */
    private static final long STALE_NANOS = 1_500_000_000L;
    private static final LinkedBlockingQueue<Voice> queue = new LinkedBlockingQueue<>(48);
    private static volatile boolean started;

    private final float[] left, right;
    private final long gapNanos;
    private final float variance;
    private final boolean pointer;
    private volatile long lastPlayed;

    /**
     * @param gapMs    shortest time between two plays of this sound; closer repeats are skipped
     * @param variance random pitch spread, as a fraction of the pitch
     * @param pointer  true when the sound answers the mouse and should follow it sideways
     */
    UiSound(String recipe, int gapMs, float variance, boolean pointer) {
        Synth.Clip clip = SoundBank.render(recipe);
        this.left = clip.left;
        this.right = clip.right;
        this.gapNanos = gapMs * 1_000_000L;
        this.variance = variance;
        this.pointer = pointer;
    }

    /** Play this sound. */
    public void play() {
        play(1);
    }

    /** Play this sound at {@code pitch} times its normal pitch. */
    public void play(float pitch) {
        emit(pitch);
    }

    /** Inventory and hotbar feedback. */
    public void playInventory() {
        playInventory(1);
    }

    /** {@link #playInventory()} at a chosen pitch. */
    public void playInventory(float pitch) {
        emit(pitch);
    }

    /** Module toggle sound. */
    public static void toggle(boolean enabled) {
        (enabled ? TOGGLE_ON : TOGGLE_OFF).emit(1);
    }

    /** {@link #SLIDER_TICK} at its plain pitch. */
    public static void sliderTick() {
        SLIDER_TICK.play();
    }

    /** {@link #SLIDER_TICK} whose pitch rises with the slider's position (0..1). */
    public static void sliderTick(float position) {
        SLIDER_TICK.play(0.86f + 0.34f * Math.max(0, Math.min(1, position)));
    }

    /** {@link #HOVER}. */
    public static void hover() {
        HOVER.play();
    }

    /** {@link #HOTBAR}, stepping up a little in pitch from the first slot to the ninth. */
    public static void hotbar(int slot) {
        HOTBAR.playInventory(0.94f + 0.015f * Math.max(0, Math.min(8, slot)));
    }

    private void emit(float pitch) {
        long now = System.nanoTime();
        if (now - lastPlayed < gapNanos && lastPlayed != 0) return;
        lastPlayed = now;

        // Fixed volume replacing BetterMyau's HUD ui-sound-volume setting.
        float gain = 0.6f;
        try {
            gain *= Minecraft.getMinecraft().gameSettings.getSoundLevel(SoundCategory.MASTER);
        } catch (RuntimeException ignored) {
        }
        if (gain <= 0.004f) return;

        ThreadLocalRandom random = ThreadLocalRandom.current();
        float rate = pitch * (1 + (random.nextFloat() * 2 - 1) * variance);
        if (variance > 0) gain *= 1 - random.nextFloat() * 0.08f;
        float pan = pointer ? pointerPan() : 0;
        start();
        // Balance on an already stereo clip: the far side dips, the near side stays as it is.
        float gl = gain * Math.min(1, 1 - pan), gr = gain * Math.min(1, 1 + pan);
        queue.offer(new Voice(left, right, gl, gr, Math.max(0.5f, Math.min(2f, rate))));
    }

    /** Where the cursor is across the window, -0.35 (left edge) .. 0.35, or 0 while it is grabbed. */
    private static float pointerPan() {
        try {
            if (Minecraft.getMinecraft().currentScreen == null || Mouse.isGrabbed()) return 0;
            int width = Display.getWidth();
            if (width <= 0) return 0;
            float x = Mouse.getX() / (float) width;
            return (Math.max(0, Math.min(1, x)) - 0.5f) * 0.7f;
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- mixer

    private static final class Voice {
        final float[] left, right;
        final float gainLeft, gainRight, rate;
        final long born = System.nanoTime();
        double position;

        Voice(float[] left, float[] right, float gainLeft, float gainRight, float rate) {
            this.left = left;
            this.right = right;
            this.gainLeft = gainLeft;
            this.gainRight = gainRight;
            this.rate = rate;
        }
    }

    private static synchronized void start() {
        if (started) return;
        started = true;
        Thread thread = new Thread(UiSound::run, "AirPlus UI sound");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
    }

    private static void run() {
        List<Voice> voices = new ArrayList<>();
        float[] mixLeft = new float[CHUNK], mixRight = new float[CHUNK];
        ByteBuffer pcm = BufferUtils.createByteBuffer(CHUNK * 4);
        try {
            while (true) {
                // Nothing playing: sleep until somebody asks for a sound.
                if (voices.isEmpty()) voices.add(queue.take());
                Voice next;
                while ((next = queue.poll()) != null) voices.add(next);
                // Saturated: the oldest voices are nearly over, so they are the ones to go.
                while (voices.size() > MAX_VOICES) voices.remove(0);

                boolean ready;
                synchronized (AlOutput.LOCK) {
                    ready = AlOutput.open();
                    if (ready) {
                        int pending = AlOutput.pending();
                        while (!voices.isEmpty() && pending < AHEAD) {
                            mix(voices, mixLeft, mixRight, pcm);
                            if (!AlOutput.write(pcm)) break;
                            pending++;
                        }
                        AlOutput.play();
                    }
                }
                if (!ready) {
                    // The sound engine is starting or reloading: hold on to fresh sounds only.
                    long now = System.nanoTime();
                    voices.removeIf(voice -> now - voice.born > STALE_NANOS);
                    Thread.sleep(15);
                } else if (!voices.isEmpty()) {
                    Thread.sleep(1);
                }
            }
        } catch (InterruptedException ignored) {
        } catch (RuntimeException | LinkageError e) {
            net.airplus.utils.client.ClientUtils.INSTANCE.getLOGGER().warn("[UiSound] Audio thread stopped", e);
        }
    }

    /** Mixes the next chunk of every voice into {@code pcm}, dropping voices that have finished. */
    private static void mix(List<Voice> voices, float[] mixLeft, float[] mixRight, ByteBuffer pcm) {
        java.util.Arrays.fill(mixLeft, 0);
        java.util.Arrays.fill(mixRight, 0);
        for (int v = voices.size() - 1; v >= 0; v--) {
            Voice voice = voices.get(v);
            int last = voice.left.length - 1;
            double position = voice.position;
            int i = 0;
            for (; i < CHUNK; i++) {
                int index = (int) position;
                if (index >= last) break;
                float t = (float) (position - index);
                mixLeft[i] += (voice.left[index] + (voice.left[index + 1] - voice.left[index]) * t) * voice.gainLeft;
                mixRight[i] += (voice.right[index] + (voice.right[index + 1] - voice.right[index]) * t) * voice.gainRight;
                position += voice.rate;
            }
            voice.position = position;
            if (i < CHUNK) voices.remove(v);
        }
        pcm.clear();
        for (int i = 0; i < CHUNK; i++) {
            int l = Math.round(limit(mixLeft[i]) * 32767), r = Math.round(limit(mixRight[i]) * 32767);
            pcm.put((byte) l).put((byte) (l >> 8)).put((byte) r).put((byte) (r >> 8));
        }
        pcm.flip();
    }

    /** Untouched up to 0.8, then a soft knee that never quite reaches full scale. */
    private static float limit(float x) {
        float a = Math.abs(x);
        if (a <= 0.8f) return x;
        float over = 0.8f + 0.19f * (float) Math.tanh((a - 0.8f) / 0.19f);
        return x < 0 ? -over : over;
    }
}
