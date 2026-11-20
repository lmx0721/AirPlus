package net.airplus.music.bm.ui;

/**
 * Implemented by screens that want the IME to be enabled while they are open.
 * The BetterMyau IME mixin consumes this; without it the screen simply keeps
 * the vanilla keyboard behavior.
 */
public interface TextInputScreen {
    boolean wantsTextInput();
}
