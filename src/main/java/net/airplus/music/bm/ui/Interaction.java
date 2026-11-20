package net.airplus.music.bm.ui;

import net.airplus.ui.glass.Anim;
import net.airplus.ui.glass.UiSound;

/**
 * Hover and press feedback for one control: a smoothed hover amount and a springy press that
 * overshoots on release. Every glass control (Click GUI rows, vanilla buttons, key caps)
 * shares this so they all feel the same.
 */
public final class Interaction {
    private final Anim.Smooth hover = new Anim.Smooth(0, 18);
    private final Anim.Spring press = Anim.spring(0, 0.26f, 0.6f);
    private boolean wasHovered;

    /** 0..1, how strongly the control is hovered. */
    public float hot;
    /** Roughly 0..1 while held; swings slightly negative as it springs back. */
    public float down;

    /**
     * @param hovered the cursor is over the control
     * @param held    a mouse button is down while hovering
     * @param dt      seconds since the last frame
     */
    public void update(boolean hovered, boolean held, float dt) {
        if (hovered && !wasHovered) UiSound.hover();
        wasHovered = hovered;
        hot = hover.update(hovered, dt);
        down = press.update(hovered && held ? 1 : 0, dt);
    }

    /** Call when the control fires: gives the press spring a push so quick clicks still bounce. */
    public void pulse() {
        press.kick(9f);
    }

    /** Scale factor for a control that shrinks by {@code depth} (e.g. 0.035) when pressed. */
    public float scale(float depth) {
        return 1 - depth * Math.max(-0.4f, Math.min(1.2f, down));
    }
}
