package net.airplus.music.bm.ui;

/** Small formatting helpers for the player's interface. */
public final class MusicText {
    private MusicText() {
    }

    /** {@code m:ss} (or {@code h:mm:ss}); "--:--" when unknown. */
    public static String time(long ms) {
        if (ms <= 0) return ms == 0 ? "0:00" : "--:--";
        long s = ms / 1000;
        long h = s / 3600, m = s / 60 % 60, sec = s % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, sec);
        return String.format("%d:%02d", m, sec);
    }
}
