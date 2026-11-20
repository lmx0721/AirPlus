package net.airplus.music.bm.model;

/** Where to stream a track from, as NetEase resolved it. */
public final class TrackUrl {
    public final String url;
    /** Bits per second, 0 when unknown. */
    public final int bitrate;
    /** File size in bytes, 0 when unknown. */
    public final long size;
    /** Container type as reported ("mp3", "flac", ...). */
    public final String type;
    /** Only a trial clip of a VIP song: {@link #trialStartMs}..{@link #trialEndMs} of the song. */
    public final boolean trial;
    public final long trialStartMs, trialEndMs;

    public TrackUrl(String url, int bitrate, long size, String type, boolean trial, long trialStartMs, long trialEndMs) {
        this.url = url;
        this.bitrate = bitrate;
        this.size = size;
        this.type = type == null ? "" : type.toLowerCase();
        this.trial = trial;
        this.trialStartMs = trialStartMs;
        this.trialEndMs = trialEndMs;
    }

    public boolean isMp3() {
        return this.type.isEmpty() || this.type.equals("mp3");
    }
}
