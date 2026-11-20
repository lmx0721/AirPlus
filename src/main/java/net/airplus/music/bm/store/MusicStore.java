package net.airplus.music.bm.store;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.airplus.music.bm.util.BmLog;
import net.airplus.file.FileManager;
import net.airplus.music.bm.model.Account;
import net.airplus.music.bm.model.Track;
import net.airplus.music.bm.util.BmFileUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The player's own files in {@code config/Myau/music/} (a sub-folder, so the config list never
 * shows them):
 * <ul>
 *   <li>{@code session.json}: device id, cached account, and the NetEase cookie encrypted with
 *       {@link TokenCrypto} (Windows DPAPI). Never part of a shareable module config.</li>
 *   <li>{@code state.json}: queue, position, play mode, so a restart picks up where it was.</li>
 *   <li>{@code cache/}: cover art.</li>
 * </ul>
 */
public final class MusicStore {
    public static final File DIR = new File(FileManager.INSTANCE.getDir(), "music");
    private static final File SESSION = new File(DIR, "session.json");
    private static final File STATE = new File(DIR, "state.json");

    private MusicStore() {
    }

    public static File cacheDir() {
        File dir = new File(DIR, "cache");
        if (!dir.isDirectory() && !dir.mkdirs()) BmLog.LOGGER.warn("[Music] Cannot create {}", dir);
        return dir;
    }

    // ------------------------------------------------------------ session

    public static final class Session {
        public String deviceId = "";
        public String cookie = "";
        public Account account = Account.GUEST;
    }

    public static Session loadSession() {
        Session s = new Session();
        JsonObject o = read(SESSION);
        if (o == null) return s;
        s.deviceId = str(o, "deviceId");
        String stored = str(o, "cookie");
        if (!stored.isEmpty()) {
            String plain = TokenCrypto.unprotect(stored);
            s.cookie = plain == null ? "" : plain;
        }
        JsonObject a = o.has("account") && o.get("account").isJsonObject() ? o.getAsJsonObject("account") : null;
        if (a != null) {
            s.account = new Account(a.has("uid") ? a.get("uid").getAsLong() : 0, str(a, "nickname"), str(a, "avatar"),
                    a.has("vipType") ? a.get("vipType").getAsInt() : 0);
        }
        return s;
    }

    public static void saveSession(String deviceId, String cookie, Account account) {
        JsonObject o = new JsonObject();
        o.addProperty("deviceId", deviceId);
        o.addProperty("cookie", cookie == null || cookie.isEmpty() ? "" : TokenCrypto.protect(cookie));
        JsonObject a = new JsonObject();
        a.addProperty("uid", account.uid);
        a.addProperty("nickname", account.nickname);
        a.addProperty("avatar", account.avatarUrl);
        a.addProperty("vipType", account.vipType);
        o.add("account", a);
        write(SESSION, o);
    }

    // ------------------------------------------------------------ playback state

    public static final class State {
        public final List<Track> queue = new ArrayList<>();
        public int index = -1;
        public long positionMs;
        public String mode = "";
        public String source = "";
    }

    public static State loadState() {
        State s = new State();
        JsonObject o = read(STATE);
        if (o == null) return s;
        if (o.has("queue") && o.get("queue").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("queue")) {
                try {
                    if (e.isJsonObject()) s.queue.add(Track.fromJson(e.getAsJsonObject()));
                } catch (RuntimeException ignored) {
                }
            }
        }
        s.index = o.has("index") ? o.get("index").getAsInt() : -1;
        s.positionMs = o.has("position") ? o.get("position").getAsLong() : 0;
        s.mode = str(o, "mode");
        s.source = str(o, "source");
        if (s.index >= s.queue.size()) s.index = s.queue.isEmpty() ? -1 : 0;
        return s;
    }

    public static void saveState(List<Track> queue, int index, long positionMs, String mode, String source) {
        JsonObject o = new JsonObject();
        JsonArray q = new JsonArray();
        // Big playlists are kept to a sensible size around the current track.
        int from = Math.max(0, Math.min(index - 200, queue.size() - 1000));
        int to = Math.min(queue.size(), from + 1000);
        for (int i = from; i < to; i++) q.add(queue.get(i).toJson());
        o.add("queue", q);
        o.addProperty("index", index < 0 ? -1 : index - from);
        o.addProperty("position", positionMs);
        o.addProperty("mode", mode);
        o.addProperty("source", source);
        write(STATE, o);
    }

    // ------------------------------------------------------------ files

    private static JsonObject read(File file) {
        if (!file.isFile()) return null;
        try {
            JsonElement e = new JsonParser().parse(BmFileUtil.readString(file));
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (IOException | RuntimeException e) {
            BmLog.LOGGER.warn("[Music] {} is unreadable, starting fresh", file.getName());
            BmFileUtil.quarantine(file);
            return null;
        }
    }

    private static void write(File file, JsonObject o) {
        try {
            if (!DIR.isDirectory() && !DIR.mkdirs()) throw new IOException("cannot create " + DIR);
            BmFileUtil.writeAtomic(file, new GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (IOException | RuntimeException e) {
            BmLog.LOGGER.warn("[Music] Could not save {}: {}", file.getName(), e.toString());
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
}
