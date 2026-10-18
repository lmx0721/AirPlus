// skid SetsunaClient
package net.airplus.music.ncm.music;

public enum MusicProvider {
    NETEASE("NetEase Cloud Music");

    private final String displayName;

    MusicProvider(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
