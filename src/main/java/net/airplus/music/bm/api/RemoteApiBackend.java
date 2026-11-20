package net.airplus.music.bm.api;

import com.google.gson.JsonObject;
import net.airplus.music.bm.model.Account;
import net.airplus.music.bm.model.Lyrics;
import net.airplus.music.bm.model.Playlist;
import net.airplus.music.bm.model.Quality;
import net.airplus.music.bm.model.SearchResult;
import net.airplus.music.bm.model.Track;
import net.airplus.music.bm.model.TrackUrl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A self-hosted NeteaseCloudMusicApiEnhanced server ({@code node app.js}, default port 3000).
 * The server does the encryption, so this keeps working when NetEase changes something before
 * the client is updated, and it can use the encryption Java 8 cannot (xeapi). The login cookie
 * is sent as the {@code cookie} form field, never in the URL.
 */
public final class RemoteApiBackend implements MusicBackend {
    private final NeteaseSession session;
    private final String base;

    public RemoteApiBackend(NeteaseSession session, String baseUrl) {
        this.session = session;
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.base = b;
    }

    @Override
    public String name() {
        return "自建 API (" + this.base + ")";
    }

    @Override
    public SearchResult search(String keywords, int offset, int limit) throws MusicApiException {
        return NeteaseParser.search(ok(call("/cloudsearch", "keywords", keywords, "type", "1",
                "limit", String.valueOf(limit), "offset", String.valueOf(offset))));
    }

    @Override
    public List<Track> songDetail(List<Long> ids) throws MusicApiException {
        List<Track> out = new ArrayList<>(ids.size());
        for (int from = 0; from < ids.size(); from += 500) {
            StringBuilder sb = new StringBuilder();
            for (Long id : ids.subList(from, Math.min(ids.size(), from + 500))) {
                if (sb.length() > 0) sb.append(',');
                sb.append(id);
            }
            out.addAll(NeteaseParser.tracks(ok(call("/song/detail", "ids", sb.toString())).get("songs")));
        }
        return out;
    }

    @Override
    public TrackUrl trackUrl(long id, Quality quality) throws MusicApiException {
        TrackUrl url = NeteaseParser.trackUrl(ok(call("/song/url/v1", "id", String.valueOf(id), "level", quality.level)));
        if (url == null || url.isMp3()) return url;
        TrackUrl old = NeteaseParser.trackUrl(ok(call("/song/url", "id", String.valueOf(id), "br", String.valueOf(quality.bitrate))));
        return old != null && old.isMp3() ? old : null;
    }

    @Override
    public Lyrics lyrics(long id) throws MusicApiException {
        return NeteaseParser.lyrics(ok(call("/lyric/new", "id", String.valueOf(id))));
    }

    @Override
    public String qrKey() throws MusicApiException {
        JsonObject data = NeteaseParser.object(ok(call("/login/qr/key")), "data");
        String key = NeteaseParser.string(data, "unikey");
        if (key.isEmpty()) throw new MusicApiException(-2, "没有拿到二维码");
        return key;
    }

    @Override
    public String qrContent(String key) {
        return "https://music.163.com/login?codekey=" + key;
    }

    @Override
    public QrCheck qrCheck(String key) throws MusicApiException {
        JsonObject body = call("/login/qr/check", "key", key);
        QrCheck status = QrCheck.of(NeteaseParser.integer(body, "code", 0));
        if (status == QrCheck.CONFIRMED) this.session.importCookieString(NeteaseParser.string(body, "cookie"));
        return status;
    }

    @Override
    public Account account() throws MusicApiException {
        return NeteaseParser.account(ok(call("/user/account")));
    }

    @Override
    public List<Playlist> userPlaylists(long uid) throws MusicApiException {
        return NeteaseParser.playlists(ok(call("/user/playlist", "uid", String.valueOf(uid), "limit", "1000")));
    }

    @Override
    public List<Track> playlistTracks(long playlistId) throws MusicApiException {
        return NeteaseParser.tracks(ok(call("/playlist/track/all", "id", String.valueOf(playlistId), "limit", "5000")).get("songs"));
    }

    @Override
    public List<Track> dailySongs() throws MusicApiException {
        JsonObject data = NeteaseParser.object(ok(call("/recommend/songs")), "data");
        return NeteaseParser.tracks(data == null ? null : data.get("dailySongs"));
    }

    @Override
    public List<Track> personalFm() throws MusicApiException {
        return NeteaseParser.tracks(ok(call("/personal_fm")).get("data"));
    }

    @Override
    public Set<Long> likedIds(long uid) throws MusicApiException {
        return NeteaseParser.ids(ok(call("/likelist", "uid", String.valueOf(uid))), "ids");
    }

    @Override
    public void like(long id, boolean like) throws MusicApiException {
        ok(call("/like", "id", String.valueOf(id), "like", String.valueOf(like)));
    }

    @Override
    public void refreshLogin() throws MusicApiException {
        JsonObject body = call("/login/refresh");
        if (NeteaseParser.integer(body, "code", 0) == 200) this.session.importCookieString(NeteaseParser.string(body, "cookie"));
    }

    @Override
    public void ensureGuest() {
        // The server adds its own anonymous token to cookie-less requests.
    }

    private static JsonObject ok(JsonObject body) throws MusicApiException {
        return NeteaseParser.requireOk(body);
    }

    private JsonObject call(String path, String... params) throws MusicApiException {
        if (this.base.isEmpty()) throw new MusicApiException(-3, "没有设置自建 API 地址");
        Map<String, String> form = new LinkedHashMap<>();
        for (int i = 0; i + 1 < params.length; i += 2) form.put(params[i], params[i + 1]);
        String cookie = this.session.cookieString();
        if (!cookie.isEmpty()) form.put("cookie", cookie);
        form.put("timestamp", String.valueOf(System.currentTimeMillis()));
        MusicHttp.Response r;
        try {
            r = MusicHttp.postForm(this.base + path, form, Collections.<String, String>emptyMap());
        } catch (IOException e) {
            throw new MusicApiException("连不上自建 API " + this.base, e);
        }
        if (r.body.length == 0) throw new MusicApiException(r.status, "自建 API 返回空内容 (HTTP " + r.status + ")");
        return NeteaseParser.parse(r.text());
    }
}
