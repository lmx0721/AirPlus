package net.airplus.music.bm;

/** How the queue advances when a song ends. */
public enum PlayMode {
    SEQUENCE("顺序播放"),
    LOOP("列表循环"),
    ONE("单曲循环"),
    SHUFFLE("随机播放");

    public final String label;

    PlayMode(String label) {
        this.label = label;
    }

    public PlayMode next() {
        return values()[(this.ordinal() + 1) % values().length];
    }

    public static PlayMode parse(String s) {
        for (PlayMode m : values()) if (m.name().equalsIgnoreCase(s)) return m;
        return LOOP;
    }
}
