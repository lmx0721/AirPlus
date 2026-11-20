package net.airplus.music.bm.model;

import java.util.Collections;
import java.util.List;

/** One page of song search results. */
public final class SearchResult {
    public final List<Track> tracks;
    public final int total;

    public SearchResult(List<Track> tracks, int total) {
        this.tracks = Collections.unmodifiableList(tracks);
        this.total = total;
    }
}
