package net.airplus.music.bm.model;

/** A NetEase playlist as listed in the sidebar (tracks are fetched on demand). */
public final class Playlist {
    public final long id;
    public final String name;
    public final String coverUrl;
    public final int trackCount;
    public final long creatorId;
    /** The account's own "I like" list (NetEase specialType 5). */
    public final boolean liked;

    public Playlist(long id, String name, String coverUrl, int trackCount, long creatorId, boolean liked) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.coverUrl = coverUrl == null ? "" : coverUrl;
        this.trackCount = trackCount;
        this.creatorId = creatorId;
        this.liked = liked;
    }
}
