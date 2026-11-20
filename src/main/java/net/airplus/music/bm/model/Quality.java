package net.airplus.music.bm.model;

/** MP3 qualities NetEase serves; only MP3 is decoded, so lossless levels are not offered. */
public enum Quality {
    STANDARD("standard", 128000),
    HIGHER("higher", 192000),
    EXHIGH("exhigh", 320000);

    public final String level;
    public final int bitrate;

    Quality(String level, int bitrate) {
        this.level = level;
        this.bitrate = bitrate;
    }
}
