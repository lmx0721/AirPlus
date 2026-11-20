package net.airplus.music.bm.api;

import net.airplus.music.bm.model.Account;
import net.airplus.music.bm.model.Lyrics;
import net.airplus.music.bm.model.Playlist;
import net.airplus.music.bm.model.Quality;
import net.airplus.music.bm.model.SearchResult;
import net.airplus.music.bm.model.Track;
import net.airplus.music.bm.model.TrackUrl;

import java.util.List;
import java.util.Set;

/**
 * Everything the player asks of NetEase. Calls block and throw {@link MusicApiException}; the
 * service runs them on its IO threads. Two implementations return the same models:
 * {@link DirectNeteaseBackend} (talks to NetEase itself) and {@link RemoteApiBackend} (a
 * self-hosted NeteaseCloudMusicApiEnhanced server).
 */
public interface MusicBackend {
    String name();

    SearchResult search(String keywords, int offset, int limit) throws MusicApiException;

    List<Track> songDetail(List<Long> ids) throws MusicApiException;

    /** Playable MP3 address; {@code null} when NetEase has none for this account (no copyright). */
    TrackUrl trackUrl(long id, Quality quality) throws MusicApiException;

    Lyrics lyrics(long id) throws MusicApiException;

    /** Starts a QR sign-in; the QR code encodes {@link #qrContent(String)}. */
    String qrKey() throws MusicApiException;

    String qrContent(String key);

    /** Polls a QR sign-in. On {@link QrCheck#CONFIRMED} the session holds the login cookie. */
    QrCheck qrCheck(String key) throws MusicApiException;

    /** The signed-in account, or {@link Account#GUEST}. */
    Account account() throws MusicApiException;

    List<Playlist> userPlaylists(long uid) throws MusicApiException;

    List<Track> playlistTracks(long playlistId) throws MusicApiException;

    List<Track> dailySongs() throws MusicApiException;

    List<Track> personalFm() throws MusicApiException;

    Set<Long> likedIds(long uid) throws MusicApiException;

    void like(long id, boolean like) throws MusicApiException;

    /** Extends the login; harmless when it fails. */
    void refreshLogin() throws MusicApiException;

    /** Best-effort guest token so guests can search and play free songs. */
    void ensureGuest() throws MusicApiException;

    enum QrCheck {
        EXPIRED(800), WAITING(801), SCANNED(802), CONFIRMED(803), UNKNOWN(0);

        public final int code;

        QrCheck(int code) {
            this.code = code;
        }

        public static QrCheck of(int code) {
            for (QrCheck q : values()) if (q.code == code) return q;
            return UNKNOWN;
        }
    }
}
