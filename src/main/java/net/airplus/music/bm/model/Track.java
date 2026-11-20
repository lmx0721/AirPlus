package net.airplus.music.bm.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One song, from NetEase or from a local file. Immutable. */
public final class Track {
    public enum Source { NETEASE, LOCAL }

    public final Source source;
    /** NetEase song id; for local files a stable hash of the path. */
    public final long id;
    public final String name;
    public final List<String> artists;
    public final String album;
    /** Remote cover URL (NetEase) or empty; local covers come from the file's own tags. */
    public final String coverUrl;
    public final long durationMs;
    /** NetEase fee flag: 0/8 free, 1 VIP, 4 paid album. */
    public final int fee;
    /** Local file path, empty for NetEase tracks. */
    public final String path;

    public Track(Source source, long id, String name, List<String> artists, String album, String coverUrl,
                 long durationMs, int fee, String path) {
        this.source = source;
        this.id = id;
        this.name = name == null || name.isEmpty() ? "未知歌曲" : name;
        this.artists = artists == null ? Collections.<String>emptyList() : Collections.unmodifiableList(new ArrayList<>(artists));
        this.album = album == null ? "" : album;
        this.coverUrl = coverUrl == null ? "" : coverUrl;
        this.durationMs = Math.max(0, durationMs);
        this.fee = fee;
        this.path = path == null ? "" : path;
    }

    public boolean isLocal() {
        return this.source == Source.LOCAL;
    }

    /** Needs a VIP account (or a purchase) for the full song; others may still get a trial clip. */
    public boolean vip() {
        return this.fee == 1 || this.fee == 4;
    }

    public String artistText() {
        if (this.artists.isEmpty()) return "未知歌手";
        StringBuilder sb = new StringBuilder();
        for (String a : this.artists) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(a);
        }
        return sb.toString();
    }

    /** Same song with a known duration (local files learn theirs while decoding). */
    public Track withDuration(long durationMs) {
        return new Track(this.source, this.id, this.name, this.artists, this.album, this.coverUrl, durationMs, this.fee, this.path);
    }

    /** Identity across sources: two tracks with the same key are the same song. */
    public String key() {
        return this.source == Source.LOCAL ? "local:" + this.path : "netease:" + this.id;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Track && ((Track) o).key().equals(this.key());
    }

    @Override
    public int hashCode() {
        return this.key().hashCode();
    }

    @Override
    public String toString() {
        return this.name + " - " + this.artistText();
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("source", this.source.name());
        o.addProperty("id", this.id);
        o.addProperty("name", this.name);
        JsonArray ar = new JsonArray();
        for (String a : this.artists) ar.add(new JsonPrimitive(a));
        o.add("artists", ar);
        o.addProperty("album", this.album);
        o.addProperty("cover", this.coverUrl);
        o.addProperty("duration", this.durationMs);
        o.addProperty("fee", this.fee);
        if (!this.path.isEmpty()) o.addProperty("path", this.path);
        return o;
    }

    public static Track fromJson(JsonObject o) {
        Source source = "LOCAL".equals(str(o, "source")) ? Source.LOCAL : Source.NETEASE;
        List<String> artists = new ArrayList<>();
        if (o.has("artists") && o.get("artists").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("artists")) artists.add(e.getAsString());
        }
        return new Track(source, o.has("id") ? o.get("id").getAsLong() : 0, str(o, "name"), artists,
                str(o, "album"), str(o, "cover"), o.has("duration") ? o.get("duration").getAsLong() : 0,
                o.has("fee") ? o.get("fee").getAsInt() : 0, str(o, "path"));
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
}
