package net.airplus.music.bm.ui;

import net.airplus.music.bm.model.Track;

import java.util.ArrayList;
import java.util.List;

/** One list in the music screen (search results, a playlist, the queue...) and its scroll state. */
final class TrackPage {
    final String key;
    String title;
    String subtitle = "";
    final List<Track> tracks = new ArrayList<>();
    boolean loading, canLoadMore;
    String error = "";
    /** Plays by queue index instead of replacing the queue. */
    boolean queue;
    /** Shown when the list needs a sign-in. */
    boolean needsLogin;
    /** (Re)loads the list; may be null. */
    Runnable reload, more;

    float scroll, scrollTarget;
    Track selected, lastClick;
    long lastClickAt;
    /** Serial of the request that filled it, so late answers for an older request are dropped. */
    int serial;

    TrackPage(String key, String title) {
        this.key = key;
        this.title = title;
    }

    void fill(List<Track> list) {
        this.tracks.clear();
        if (list != null) this.tracks.addAll(list);
        this.loading = false;
        this.error = "";
    }
}
