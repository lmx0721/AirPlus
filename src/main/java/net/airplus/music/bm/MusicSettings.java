package net.airplus.music.bm;

import net.airplus.music.bm.model.Quality;

/**
 * What the player needs from the user's settings. The {@code MusicPlayer} module implements it,
 * so the values live in the normal (shareable) module config; the login cookie does not.
 */
public interface MusicSettings {
    /** 0..100. */
    int volume();

    void setVolume(int volume);

    /** Multiply by Minecraft's master volume. */
    boolean followGameVolume();

    Quality quality();

    /** Skip VIP songs that only come as a 30-second trial. */
    boolean skipTrial();

    /** Use a self-hosted NeteaseCloudMusicApiEnhanced instead of talking to NetEase directly. */
    boolean customApi();

    String apiUrl();

    String localFolder();

    MusicSettings DEFAULTS = new MusicSettings() {
        private int volume = 60;

        @Override
        public int volume() {
            return this.volume;
        }

        @Override
        public void setVolume(int volume) {
            this.volume = volume;
        }

        @Override
        public boolean followGameVolume() {
            return true;
        }

        @Override
        public Quality quality() {
            return Quality.EXHIGH;
        }

        @Override
        public boolean skipTrial() {
            return false;
        }

        @Override
        public boolean customApi() {
            return false;
        }

        @Override
        public String apiUrl() {
            return "http://localhost:3000";
        }

        @Override
        public String localFolder() {
            return "";
        }
    };
}
