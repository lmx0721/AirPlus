package net.airplus.music.bm.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.airplus.music.bm.model.Account;
import net.airplus.music.bm.model.Lyrics;
import net.airplus.music.bm.model.Playlist;
import net.airplus.music.bm.model.Quality;
import net.airplus.music.bm.model.SearchResult;
import net.airplus.music.bm.model.Track;
import net.airplus.music.bm.model.TrackUrl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Talks to NetEase directly with the weapi / eapi encryption of {@link NeteaseCrypto}.
 *
 * <p>Every endpoint is listed once in the table below (path + encryption), taken from the module
 * of the same name in NeteaseCloudMusicApiEnhanced. When NetEase changes something, compare the
 * entry with that project's {@code module/*.js} and fix it here; nothing else needs to know.
 */
public final class DirectNeteaseBackend implements MusicBackend {
    private static final String WEB = "https://music.163.com";
    private static final String API = "https://interface.music.163.com";
    private static final String WEB_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0";
    private static final String API_UA = "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)";
    private static final String ANON_XOR_KEY = "3go8&$8*3*3h0k(2)2";

    enum Crypto { WEAPI, EAPI }

    /** One NetEase endpoint: the {@code /api/...} path and how its body is encrypted. */
    static final class Endpoint {
        final String uri;
        final Crypto crypto;

        Endpoint(String uri, Crypto crypto) {
            this.uri = uri;
            this.crypto = crypto;
        }
    }

    // ---- endpoint table (module name in NeteaseCloudMusicApiEnhanced in the comment)
    static final Endpoint SEARCH = new Endpoint("/api/cloudsearch/pc", Crypto.EAPI);                    // cloudsearch
    static final Endpoint SONG_DETAIL = new Endpoint("/api/v3/song/detail", Crypto.WEAPI);              // song_detail
    static final Endpoint SONG_URL_V1 = new Endpoint("/api/song/enhance/player/url/v1", Crypto.EAPI);   // song_url_v1 (upstream: xeapi)
    static final Endpoint SONG_URL = new Endpoint("/api/song/enhance/player/url", Crypto.EAPI);         // song_url
    static final Endpoint LYRIC = new Endpoint("/api/song/lyric/v1", Crypto.EAPI);                      // lyric_new
    static final Endpoint QR_KEY = new Endpoint("/api/login/qrcode/unikey", Crypto.EAPI);               // login_qr_key
    static final Endpoint QR_CHECK = new Endpoint("/api/login/qrcode/client/login", Crypto.EAPI);       // login_qr_check
    static final Endpoint ACCOUNT = new Endpoint("/api/nuser/account/get", Crypto.WEAPI);               // user_account
    static final Endpoint USER_PLAYLIST = new Endpoint("/api/user/playlist", Crypto.WEAPI);             // user_playlist
    static final Endpoint PLAYLIST_DETAIL = new Endpoint("/api/v6/playlist/detail", Crypto.EAPI);       // playlist_detail
    static final Endpoint DAILY = new Endpoint("/api/v3/discovery/recommend/songs", Crypto.WEAPI);      // recommend_songs
    static final Endpoint FM = new Endpoint("/api/v1/radio/get", Crypto.WEAPI);                         // personal_fm
    static final Endpoint LIKE_LIST = new Endpoint("/api/song/like/get", Crypto.EAPI);                  // likelist
    static final Endpoint LIKE = new Endpoint("/api/radio/like", Crypto.WEAPI);                         // like
    static final Endpoint REFRESH = new Endpoint("/api/login/token/refresh", Crypto.EAPI);              // login_refresh
    static final Endpoint ANONYMOUS = new Endpoint("/api/register/anonimous", Crypto.WEAPI);            // register_anonimous (old weapi form)

    private final NeteaseSession session;

    public DirectNeteaseBackend(NeteaseSession session) {
        this.session = session;
    }

    @Override
    public String name() {
        return "直连网易云";
    }

    @Override
    public SearchResult search(String keywords, int offset, int limit) throws MusicApiException {
        JsonObject d = new JsonObject();
        d.addProperty("s", keywords);
        d.addProperty("type", 1);
        d.addProperty("limit", limit);
        d.addProperty("offset", offset);
        d.addProperty("total", true);
        return NeteaseParser.search(ok(call(SEARCH, d)));
    }

    @Override
    public List<Track> songDetail(List<Long> ids) throws MusicApiException {
        List<Track> out = new ArrayList<>(ids.size());
        for (int from = 0; from < ids.size(); from += 500) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + 500));
            StringBuilder c = new StringBuilder("[");
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) c.append(',');
                c.append("{\"id\":").append(chunk.get(i)).append('}');
            }
            JsonObject d = new JsonObject();
            d.addProperty("c", c.append(']').toString());
            out.addAll(NeteaseParser.tracks(ok(call(SONG_DETAIL, d)).get("songs")));
        }
        return out;
    }

    @Override
    public TrackUrl trackUrl(long id, Quality quality) throws MusicApiException {
        JsonObject d = new JsonObject();
        d.addProperty("ids", "[" + id + "]");
        d.addProperty("level", quality.level);
        d.addProperty("encodeType", "mp3");
        TrackUrl url;
        MusicApiException failure = null;
        try {
            url = NeteaseParser.trackUrl(ok(call(SONG_URL_V1, d)));
            // No address at all: no copyright for this account. The old endpoint won't have one either.
            if (url == null || url.isMp3()) return url;
        } catch (MusicApiException e) {
            failure = e;
        }
        // v1 failed or gave a format we can't decode: the older endpoint, addressed by bitrate.
        JsonObject old = new JsonObject();
        JsonArray ids = new JsonArray();
        ids.add(new JsonPrimitive(String.valueOf(id)));
        old.addProperty("ids", ids.toString());
        old.addProperty("br", quality.bitrate);
        try {
            TrackUrl fallback = NeteaseParser.trackUrl(ok(call(SONG_URL, old)));
            if (fallback != null && fallback.isMp3()) return fallback;
        } catch (MusicApiException e) {
            if (failure != null) throw failure;
        }
        if (failure != null) throw failure;
        return null;
    }

    @Override
    public Lyrics lyrics(long id) throws MusicApiException {
        JsonObject d = new JsonObject();
        d.addProperty("id", id);
        d.addProperty("cp", false);
        for (String k : new String[]{"tv", "lv", "rv", "kv", "yv", "ytv", "yrv"}) d.addProperty(k, 0);
        return NeteaseParser.lyrics(ok(call(LYRIC, d)));
    }

    @Override
    public String qrKey() throws MusicApiException {
        JsonObject d = new JsonObject();
        d.addProperty("type", 3);
        String key = NeteaseParser.string(ok(call(QR_KEY, d)), "unikey");
        if (key.isEmpty()) throw new MusicApiException(-2, "没有拿到二维码");
        return key;
    }

    @Override
    public String qrContent(String key) {
        return "https://music.163.com/login?codekey=" + key;
    }

    @Override
    public QrCheck qrCheck(String key) throws MusicApiException {
        JsonObject d = new JsonObject();
        d.addProperty("key", key);
        d.addProperty("type", 3);
        Result r = request(QR_CHECK, d);
        QrCheck status = QrCheck.of(NeteaseParser.integer(r.body, "code", 0));
        if (status == QrCheck.CONFIRMED) this.session.applySetCookies(r.cookies);
        return status;
    }

    @Override
    public Account account() throws MusicApiException {
        return NeteaseParser.account(ok(call(ACCOUNT, new JsonObject())));
    }

    @Override
    public List<Playlist> userPlaylists(long uid) throws MusicApiException {
        JsonObject d = new JsonObject();
        d.addProperty("uid", uid);
        d.addProperty("limit", 1000);
        d.addProperty("offset", 0);
        d.addProperty("includeVideo", true);
        return NeteaseParser.playlists(ok(call(USER_PLAYLIST, d)));
    }

    @Override
    public List<Track> playlistTracks(long playlistId) throws MusicApiException {
        JsonObject d = new JsonObject();
        d.addProperty("id", playlistId);
        d.addProperty("n", 100000);
        d.addProperty("s", 8);
        List<Long> ids = NeteaseParser.playlistTrackIds(ok(call(PLAYLIST_DETAIL, d)));
        return ids.isEmpty() ? new ArrayList<Track>() : this.songDetail(ids);
    }

    @Override
    public List<Track> dailySongs() throws MusicApiException {
        JsonObject data = NeteaseParser.object(ok(call(DAILY, new JsonObject())), "data");
        return NeteaseParser.tracks(data == null ? null : data.get("dailySongs"));
    }

    @Override
    public List<Track> personalFm() throws MusicApiException {
        return NeteaseParser.tracks(ok(call(FM, new JsonObject())).get("data"));
    }

    @Override
    public Set<Long> likedIds(long uid) throws MusicApiException {
        JsonObject d = new JsonObject();
        d.addProperty("uid", uid);
        return NeteaseParser.ids(ok(call(LIKE_LIST, d)), "ids");
    }

    @Override
    public void like(long id, boolean like) throws MusicApiException {
        JsonObject d = new JsonObject();
        d.addProperty("alg", "itembased");
        d.addProperty("trackId", id);
        d.addProperty("like", like);
        d.addProperty("time", "3");
        ok(call(LIKE, d));
    }

    @Override
    public void refreshLogin() throws MusicApiException {
        Result r = request(REFRESH, new JsonObject());
        if (NeteaseParser.integer(r.body, "code", 0) == 200) this.session.applySetCookies(r.cookies);
    }

    @Override
    public void ensureGuest() throws MusicApiException {
        if (this.session.signedIn() || this.session.hasAnonymousToken()) return;
        String deviceId = this.session.deviceId();
        StringBuilder xored = new StringBuilder(deviceId.length());
        for (int i = 0; i < deviceId.length(); i++) {
            xored.append((char) (deviceId.charAt(i) ^ ANON_XOR_KEY.charAt(i % ANON_XOR_KEY.length())));
        }
        String digest;
        try {
            byte[] md5 = java.security.MessageDigest.getInstance("MD5").digest(xored.toString().getBytes(StandardCharsets.UTF_8));
            digest = Base64.getEncoder().encodeToString(md5);
        } catch (Exception e) {
            throw new MusicApiException("MD5 unavailable", e);
        }
        String username = Base64.getEncoder().encodeToString((deviceId + " " + digest).getBytes(StandardCharsets.UTF_8));
        JsonObject d = new JsonObject();
        d.addProperty("username", username);
        Result r = request(ANONYMOUS, d);
        if (NeteaseParser.integer(r.body, "code", 0) == 200) this.session.applySetCookies(r.cookies);
    }

    // ------------------------------------------------------------ transport

    private static final class Result {
        final JsonObject body;
        final List<String> cookies;

        Result(JsonObject body, List<String> cookies) {
            this.body = body;
            this.cookies = cookies;
        }
    }

    private JsonObject call(Endpoint endpoint, JsonObject data) throws MusicApiException {
        return request(endpoint, data).body;
    }

    private static JsonObject ok(JsonObject body) throws MusicApiException {
        return NeteaseParser.requireOk(body);
    }

    private Result request(Endpoint endpoint, JsonObject data) throws MusicApiException {
        Map<String, String> headers = new HashMap<>();
        Map<String, String> form;
        String url;
        data.addProperty("e_r", false);
        if (endpoint.crypto == Crypto.WEAPI) {
            data.addProperty("csrf_token", this.session.csrf());
            headers.put("Referer", WEB);
            headers.put("User-Agent", WEB_UA);
            headers.put("Cookie", this.session.weapiCookie(endpoint.uri.contains("login")));
            form = NeteaseCrypto.weapi(data.toString());
            url = WEB + "/weapi/" + endpoint.uri.substring(5);
        } else {
            Map<String, String> header = this.session.eapiHeader();
            JsonObject h = new JsonObject();
            for (Map.Entry<String, String> e : header.entrySet()) h.addProperty(e.getKey(), e.getValue());
            data.add("header", h);
            headers.put("User-Agent", API_UA);
            headers.put("Cookie", NeteaseSession.headerCookie(header));
            form = NeteaseCrypto.eapi(endpoint.uri, data.toString());
            url = API + "/eapi/" + endpoint.uri.substring(5);
        }
        MusicHttp.Response response;
        try {
            response = MusicHttp.postForm(url, form, headers);
        } catch (IOException e) {
            throw new MusicApiException(endpoint.uri + " 网络请求失败", e);
        }
        if (response.status / 100 != 2 && response.body.length == 0) {
            throw new MusicApiException(response.status, "HTTP " + response.status);
        }
        JsonObject body = NeteaseParser.parse(response.text());
        return new Result(body, response.setCookies);
    }

    /** For {@code .music diag}: the endpoint table as "path (crypto)" lines. */
    public static Map<String, String> describeEndpoints() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Endpoint e : new Endpoint[]{SEARCH, SONG_DETAIL, SONG_URL_V1, SONG_URL, LYRIC, QR_KEY, QR_CHECK, ACCOUNT,
                USER_PLAYLIST, PLAYLIST_DETAIL, DAILY, FM, LIKE_LIST, LIKE, REFRESH, ANONYMOUS}) {
            out.put(e.uri, e.crypto.name().toLowerCase());
        }
        return out;
    }
}
