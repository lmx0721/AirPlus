package net.airplus.music.bm.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.airplus.music.bm.lyrics.LyricParser;
import net.airplus.music.bm.model.Account;
import net.airplus.music.bm.model.Lyrics;
import net.airplus.music.bm.model.Playlist;
import net.airplus.music.bm.model.SearchResult;
import net.airplus.music.bm.model.Track;
import net.airplus.music.bm.model.TrackUrl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * NetEase JSON → models. A self-hosted NeteaseCloudMusicApiEnhanced returns NetEase's bodies
 * unchanged, so both backends share this parser. Song objects come in two shapes (new
 * {@code ar/al/dt}, old {@code artists/album/duration}); both are read.
 */
public final class NeteaseParser {
    private NeteaseParser() {
    }

    public static JsonObject parse(String body) throws MusicApiException {
        try {
            JsonElement e = new JsonParser().parse(body);
            if (!e.isJsonObject()) throw new MusicApiException(-2, "返回内容不是 JSON 对象");
            return e.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new MusicApiException("返回内容无法解析", e);
        }
    }

    /** Throws unless the body's {@code code} is 200 (or one of {@code alsoOk}). */
    public static JsonObject requireOk(JsonObject o, int... alsoOk) throws MusicApiException {
        int code = integer(o, "code", 200);
        if (code == 200) return o;
        for (int ok : alsoOk) if (code == ok) return o;
        String msg = string(o, "message");
        if (msg.isEmpty()) msg = string(o, "msg");
        throw new MusicApiException(code, msg.isEmpty() ? "网易云返回错误 " + code : msg);
    }

    public static SearchResult search(JsonObject o) {
        JsonObject result = object(o, "result");
        List<Track> tracks = tracks(result == null ? null : result.get("songs"));
        int total = result == null ? tracks.size() : integer(result, "songCount", tracks.size());
        return new SearchResult(tracks, total);
    }

    public static List<Track> tracks(JsonElement array) {
        if (array == null || !array.isJsonArray()) return Collections.emptyList();
        List<Track> out = new ArrayList<>();
        for (JsonElement e : array.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            Track t = track(e.getAsJsonObject());
            if (t != null) out.add(t);
        }
        return out;
    }

    public static Track track(JsonObject s) {
        long id = longValue(s, "id", 0);
        if (id == 0) return null;
        List<String> artists = new ArrayList<>();
        JsonElement ar = s.has("ar") ? s.get("ar") : s.get("artists");
        if (ar != null && ar.isJsonArray()) {
            for (JsonElement a : ar.getAsJsonArray()) {
                if (a.isJsonObject()) {
                    String name = string(a.getAsJsonObject(), "name");
                    if (!name.isEmpty()) artists.add(name);
                }
            }
        }
        JsonObject al = object(s, "al");
        if (al == null) al = object(s, "album");
        String album = al == null ? "" : string(al, "name");
        String cover = al == null ? "" : string(al, "picUrl");
        long duration = s.has("dt") ? longValue(s, "dt", 0) : longValue(s, "duration", 0);
        int fee = integer(s, "fee", 0);
        return new Track(Track.Source.NETEASE, id, string(s, "name"), artists, album, cover, duration, fee, "");
    }

    /** The first entry of a song-url response; {@code null} when NetEase gave no address. */
    public static TrackUrl trackUrl(JsonObject o) {
        JsonElement data = o.get("data");
        if (data == null || !data.isJsonArray() || data.getAsJsonArray().size() == 0) return null;
        JsonObject d = data.getAsJsonArray().get(0).getAsJsonObject();
        String url = string(d, "url");
        if (url.isEmpty()) return null;
        JsonObject trial = object(d, "freeTrialInfo");
        long start = trial == null ? 0 : longValue(trial, "start", 0) * 1000;
        long end = trial == null ? 0 : longValue(trial, "end", 0) * 1000;
        return new TrackUrl(url, integer(d, "br", 0), longValue(d, "size", 0), string(d, "type"), trial != null, start, end);
    }

    public static Lyrics lyrics(JsonObject o) {
        return LyricParser.parse(nested(o, "lrc", "lyric"), nested(o, "tlyric", "lyric"), nested(o, "yrc", "lyric"));
    }

    public static Account account(JsonObject o) {
        JsonObject profile = object(o, "profile");
        if (profile == null) {
            JsonObject data = object(o, "data");
            if (data != null) profile = object(data, "profile");
        }
        if (profile == null) return Account.GUEST;
        return new Account(longValue(profile, "userId", 0), string(profile, "nickname"), string(profile, "avatarUrl"),
                integer(profile, "vipType", 0));
    }

    public static List<Playlist> playlists(JsonObject o) {
        JsonElement arr = o.get("playlist");
        if (arr == null || !arr.isJsonArray()) return Collections.emptyList();
        List<Playlist> out = new ArrayList<>();
        for (JsonElement e : arr.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject p = e.getAsJsonObject();
            JsonObject creator = object(p, "creator");
            out.add(new Playlist(longValue(p, "id", 0), string(p, "name"), string(p, "coverImgUrl"),
                    integer(p, "trackCount", 0), creator == null ? longValue(p, "userId", 0) : longValue(creator, "userId", 0),
                    integer(p, "specialType", 0) == 5));
        }
        return out;
    }

    /** Track ids of a playlist detail response, in playlist order. */
    public static List<Long> playlistTrackIds(JsonObject o) {
        JsonObject playlist = object(o, "playlist");
        if (playlist == null) return Collections.emptyList();
        JsonElement ids = playlist.get("trackIds");
        List<Long> out = new ArrayList<>();
        if (ids != null && ids.isJsonArray()) {
            for (JsonElement e : ids.getAsJsonArray()) if (e.isJsonObject()) out.add(longValue(e.getAsJsonObject(), "id", 0));
        }
        return out;
    }

    public static Set<Long> ids(JsonObject o, String key) {
        JsonElement arr = o.get(key);
        Set<Long> out = new LinkedHashSet<>();
        if (arr != null && arr.isJsonArray()) for (JsonElement e : arr.getAsJsonArray()) out.add(e.getAsLong());
        return out;
    }

    // ------------------------------------------------------------ null-safe access

    public static JsonObject object(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static JsonArray array(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    public static String string(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return "";
        return e.getAsString();
    }

    public static int integer(JsonObject o, String key, int fallback) {
        JsonElement e = o == null ? null : o.get(key);
        try {
            return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? fallback : e.getAsInt();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    public static long longValue(JsonObject o, String key, long fallback) {
        JsonElement e = o == null ? null : o.get(key);
        try {
            return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? fallback : e.getAsLong();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static String nested(JsonObject o, String outer, String inner) {
        return string(object(o, outer), inner);
    }
}
