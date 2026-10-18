// skid SetsunaClient
package net.airplus.music.ncm.music.dto;

import com.google.gson.JsonArray;
import com.google.gson.annotations.SerializedName;
import net.airplus.music.ncm.RequestUtil;
import net.airplus.music.ncm.api.CloudMusicApi;
import net.airplus.music.ncm.music.CloudMusic;
import net.airplus.music.ncm.music.MusicProvider;
import net.airplus.music.ncm.util.Location;
import net.airplus.music.ncm.util.json.JsonUtils;
import net.airplus.music.ncm.util.other.multithreading.MultiThreadingUtil;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 歌单对象
 */
public class PlayList {

    @SerializedName("id")
    private final long id;

    @SerializedName("name")
    private final String name;

    @SerializedName("coverImgUrl")
    private final String coverUrl;

    @SerializedName("trackCount")
    private final int count;

    @SerializedName("playCount")
    private final long playCount;

    @SerializedName("creator")
    private final User creator;

    @SerializedName("description")
    private final String description;

    @SerializedName("subscribed")
    private final boolean subscribed;

    @SerializedName("createTime")
    private final long createTime;

    // unique fields
    public transient List<Music> musics;
    private transient boolean searchMode = false;
    public transient boolean musicsQueried = false, musicsLoaded = false;
    private transient MusicProvider provider = MusicProvider.NETEASE;
    private transient String providerId;

    public PlayList(long id, String name, String coverUrl, int count, long playCount,
                    User creator, String description, boolean subscribed, long createTime) {
        this.id = id;
        this.name = name;
        this.coverUrl = coverUrl;
        this.count = count;
        this.playCount = playCount;
        this.creator = creator;
        this.description = description;
        this.subscribed = subscribed;
        this.createTime = createTime;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public int getCount() {
        return count;
    }

    public long getPlayCount() {
        return playCount;
    }

    public User getCreator() {
        return creator;
    }

    public String getDescription() {
        return description;
    }

    public boolean isSubscribed() {
        return subscribed;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setMusics(List<Music> musics) {
        this.musics = musics;
    }

    public boolean isSearchMode() {
        return searchMode;
    }

    public void setSearchMode(boolean searchMode) {
        this.searchMode = searchMode;
    }

    public void setMusicsQueried(boolean musicsQueried) {
        this.musicsQueried = musicsQueried;
    }

    public void setMusicsLoaded(boolean musicsLoaded) {
        this.musicsLoaded = musicsLoaded;
    }

    public MusicProvider getProvider() {
        return provider == null ? MusicProvider.NETEASE : provider;
    }

    public void setProvider(MusicProvider provider) {
        this.provider = provider;
    }

    public String getProviderId() {
        return providerId == null || providerId.trim().isEmpty() ? String.valueOf(id) : providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String cacheKey() {
        return getProvider().name().toLowerCase() + "_" + getProviderId().replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public final Location getCoverLocation() {
        return Location.of("tritium/textures/playlist/" + this.id + "/cover.png");
    }

    public List<Music> getMusics() {

        if (this.musics == null)
            this.musics = new CopyOnWriteArrayList<>();

        if (!musics.isEmpty() && (this.musicsQueried || searchMode)) {
            return this.musics;
        }

        if (!this.musicsQueried && !searchMode) {
            this.musicsQueried = true;

            MultiThreadingUtil.runAsync(new Runnable() {
                @Override
                public void run() {
                    queryMusics();
                }
            });
        }

        return this.musics;
    }

    public void loadMusicsWithCallback(MusicsLoadedCallback callback) {

        if (this.musics == null)
            this.musics = new CopyOnWriteArrayList<>();

        if (!musics.isEmpty() && (this.musicsQueried || searchMode)) {
            callback.onMusicsLoaded(musics);
            return;
        }

        if (!this.musicsQueried && !searchMode) {
            this.musicsQueried = true;

            MultiThreadingUtil.runAsync(new Runnable() {
                @Override
                public void run() {
                    queryMusics();
                    callback.onMusicsLoaded(musics);
                }
            });
        }
    }

    private void queryMusics() {
        RequestUtil.RequestAnswer requestAnswer;
        try {
            requestAnswer = CloudMusicApi.playlistTrackAll(id, 8);
        } catch (Exception e) {
            this.musicsQueried = false;
            e.printStackTrace();
            return;
        }

        JsonArray songs = requestAnswer.toJsonObject().getAsJsonArray("songs");
        songs.forEach(element -> this.musics.add(JsonUtils.parse(CloudMusic.normalizeSong(element.getAsJsonObject()), Music.class)));

        musicsLoaded = true;
    }

    public interface MusicsLoadedCallback {
        void onMusicsLoaded(List<Music> musics);
    }

    public void updPlayCount() {
        CloudMusicApi.playlistUpdatePlaycount(this.id);
    }

    public void addToList(long musicId) {
        CloudMusicApi.playlistTracks("add", this.id, String.valueOf(musicId));
    }

    public void removeFromList(long musicId) {
        CloudMusicApi.playlistTracks("del", this.id, String.valueOf(musicId));
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        PlayList playList = (PlayList) o;
        return getProvider() == playList.getProvider() && getProviderId().equals(playList.getProviderId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getProvider(), getProviderId());
    }
}
