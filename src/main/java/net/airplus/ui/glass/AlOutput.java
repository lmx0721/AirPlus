package net.airplus.ui.glass;

import java.nio.ByteBuffer;

/**
 * The interface sounds' output: one {@link AlStream} in Minecraft's own OpenAL context, fed small
 * stereo buffers by {@link UiSound}'s mixer thread. The other streams (the music player) live
 * beside it; {@link #suspend()} / {@link #resume()} cover all of them.
 *
 * <p>Every OpenAL call is made under {@link #LOCK} (the same object as {@link AlStream#LOCK}).
 */
public final class AlOutput {
    static final Object LOCK = AlStream.LOCK;
    private static final AlStream UI = new AlStream("UiSound", 8);

    private AlOutput() {
    }

    /** Minecraft is about to shut its sound engine down: let go of every stream made in its context. */
    public static void suspend() {
        AlStream.suspendAll();
    }

    /** The shutdown is over; output comes back once the engine reports itself loaded again. */
    public static void resume() {
        AlStream.resumeAll();
    }

    /** Under {@link #LOCK}: true when there is a live source to write to. */
    static boolean open() {
        return UI.open();
    }

    /** Under {@link #LOCK}, after {@link #open()}: buffers waiting to be heard. */
    static int pending() {
        return UI.pending();
    }

    /** Under {@link #LOCK}, after {@link #open()}: queues one chunk of 16-bit stereo audio. */
    static boolean write(ByteBuffer pcm) {
        return UI.write(pcm);
    }

    /** Under {@link #LOCK}: starts the source again if it ran dry while there is audio queued. */
    static void play() {
        UI.play();
    }
}
