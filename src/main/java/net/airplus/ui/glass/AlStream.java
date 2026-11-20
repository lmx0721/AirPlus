package net.airplus.ui.glass;

import net.airplus.injection.forge.mixins.client.AccessorSoundHandler;
import net.airplus.injection.forge.mixins.client.AccessorSoundManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundHandler;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One streaming OpenAL source in Minecraft's own sound context, fed 16-bit PCM buffers by a
 * worker thread. The interface sounds ({@link AlOutput}) and the music player each own one.
 *
 * <p>Nothing here ever opens an audio device: a second output next to OpenAL (Java Sound did this
 * before) can leave the game's own sounds silent on some devices. While Minecraft's sound engine
 * is not running there is simply no output.
 *
 * <p>Every method that touches OpenAL must be called while holding {@link #LOCK}.
 * {@code MixinSoundManager} takes the same lock through {@link #suspendAll()} before Minecraft
 * destroys the context, so no stream ever touches a context that is going away.
 */
public final class AlStream {
    public static final Object LOCK = new Object();
    private static final List<AlStream> STREAMS = new CopyOnWriteArrayList<>();
    private static volatile boolean suspended;

    private final String name;
    private final int[] buffers;
    private final ArrayDeque<Integer> free = new ArrayDeque<>();
    /** Frames held by each queued buffer, oldest first, to know how much has been heard. */
    private final ArrayDeque<Integer> queuedFrames = new ArrayDeque<>();
    private int source = -1;
    private boolean broken;
    private float gain = 1;
    /** Frames of buffers that finished playing since the last {@link #flush()}. */
    private long playedFrames;

    public AlStream(String name, int bufferCount) {
        this.name = name;
        this.buffers = new int[bufferCount];
        STREAMS.add(this);
    }

    /** Minecraft is about to shut its sound engine down: let go of everything made in its context. */
    public static void suspendAll() {
        synchronized (LOCK) {
            suspended = true;
            boolean alive = AL.isCreated();
            for (AlStream s : STREAMS) s.release(alive);
        }
    }

    /** The shutdown is over; output comes back once the engine reports itself loaded again. */
    public static void resumeAll() {
        suspended = false;
    }

    /** Under {@link #LOCK}: true when there is a live source, creating it after an engine (re)start. */
    public boolean open() {
        boolean alive = !suspended && !this.broken && engineLoaded();
        if (!alive) {
            if (this.source != -1) this.release(!suspended && AL.isCreated());
            return false;
        }
        if (this.source != -1) return true;
        try {
            AL10.alGetError();
            this.source = AL10.alGenSources();
            AL10.alSourcei(this.source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(this.source, AL10.AL_POSITION, 0, 0, 0);
            AL10.alSourcef(this.source, AL10.AL_ROLLOFF_FACTOR, 0);
            AL10.alSourcef(this.source, AL10.AL_GAIN, this.gain);
            AL10.alSourcef(this.source, AL10.AL_PITCH, 1);
            for (int i = 0; i < this.buffers.length; i++) {
                this.buffers[i] = AL10.alGenBuffers();
                this.free.add(this.buffers[i]);
            }
            int error = AL10.alGetError();
            if (error != AL10.AL_NO_ERROR) throw new IllegalStateException("OpenAL error " + error);
            this.queuedFrames.clear();
            this.playedFrames = 0;
            net.airplus.utils.client.ClientUtils.INSTANCE.getLOGGER().info("[{}] Streaming through Minecraft's OpenAL context (source {})", this.name, this.source);
            return true;
        } catch (RuntimeException | LinkageError e) {
            this.broken = true;
            net.airplus.utils.client.ClientUtils.INSTANCE.getLOGGER().warn("[{}] Cannot use Minecraft's OpenAL context; this output is off", this.name, e);
            this.release(true);
            return false;
        }
    }

    /** Under {@link #LOCK}: whether a source currently exists (engine up, opened). */
    public boolean isOpen() {
        return this.source != -1;
    }

    /** Under {@link #LOCK}, after {@link #open()}: recycles finished buffers; returns how many still wait. */
    public int pending() {
        int processed = AL10.alGetSourcei(this.source, AL10.AL_BUFFERS_PROCESSED);
        for (int i = 0; i < processed; i++) {
            this.free.add(AL10.alSourceUnqueueBuffers(this.source));
            Integer frames = this.queuedFrames.poll();
            if (frames != null) this.playedFrames += frames;
        }
        return this.queuedFrames.size();
    }

    /** Under {@link #LOCK}, after {@link #open()}: a free buffer is available for {@link #write}. */
    public boolean hasFree() {
        return !this.free.isEmpty();
    }

    /**
     * Under {@link #LOCK}, after {@link #open()}: queues interleaved 16-bit audio (1 or 2 channels).
     * False when no buffer is free (nothing was queued).
     */
    public boolean write(ByteBuffer pcm, int channels, int rate) {
        // A source that ran dry is STOPPED with everything it played still queued, and playing a
        // stopped source starts again at the head of its queue: unqueue what it already played
        // first, or that audio is heard a second time (and the frame count jumps back).
        if (AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) this.pending();
        Integer buffer = this.free.poll();
        if (buffer == null) return false;
        int frames = pcm.remaining() / (2 * channels);
        AL10.alBufferData(buffer, channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16, pcm, rate);
        AL10.alSourceQueueBuffers(this.source, buffer);
        this.queuedFrames.add(frames);
        return true;
    }

    /** Under {@link #LOCK}: queues 16-bit stereo audio at the interface sound rate. */
    public boolean write(ByteBuffer pcm) {
        return this.write(pcm, 2, Synth.RATE);
    }

    /** Under {@link #LOCK}: starts the source again if it ran dry or was paused while audio is queued. */
    public void play() {
        if (this.source != -1 && !this.queuedFrames.isEmpty()
                && AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
            AL10.alSourcePlay(this.source);
        }
    }

    /** Under {@link #LOCK}: holds the source where it is; {@link #play()} continues. */
    public void pause() {
        if (this.source != -1 && AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) {
            AL10.alSourcePause(this.source);
        }
    }

    /** Under {@link #LOCK}: drops everything queued (seek, track change) and restarts the frame count. */
    public void flush() {
        if (this.source != -1) {
            AL10.alSourceStop(this.source);
            int processed = AL10.alGetSourcei(this.source, AL10.AL_BUFFERS_PROCESSED);
            for (int i = 0; i < processed; i++) this.free.add(AL10.alSourceUnqueueBuffers(this.source));
        }
        this.queuedFrames.clear();
        this.playedFrames = 0;
    }

    /** Under {@link #LOCK}: frames heard since the last {@link #flush()} (finished buffers + offset into the current one). */
    public long playedFrames() {
        if (this.source == -1) return this.playedFrames;
        return this.playedFrames + Math.max(0, AL10.alGetSourcei(this.source, AL11.AL_SAMPLE_OFFSET));
    }

    /** Under {@link #LOCK}: source gain (volume), kept across engine restarts. */
    public void setGain(float gain) {
        this.gain = Math.max(0, gain);
        if (this.source != -1) AL10.alSourcef(this.source, AL10.AL_GAIN, this.gain);
    }

    private void release(boolean contextAlive) {
        if (this.source != -1 && contextAlive) {
            try {
                AL10.alSourceStop(this.source);
                AL10.alSourcei(this.source, AL10.AL_BUFFER, 0);
                AL10.alDeleteSources(this.source);
                // Setting the source's buffer to 0 above detached anything still queued.
                for (int buffer : this.buffers) if (buffer != 0) AL10.alDeleteBuffers(buffer);
                AL10.alGetError();
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
        this.source = -1;
        java.util.Arrays.fill(this.buffers, 0);
        this.free.clear();
        this.queuedFrames.clear();
    }

    private static boolean engineLoaded() {
        try {
            if (!AL.isCreated()) return false;
            SoundHandler handler = Minecraft.getMinecraft().getSoundHandler();
            if (handler == null) return false;
            Object manager = ((AccessorSoundHandler) handler).getSndManager();
            return manager != null && ((AccessorSoundManager) manager).isLoaded();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
