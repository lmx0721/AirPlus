// skid SetsunaClient
package net.airplus.music.ncm.music.dto;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import net.airplus.music.ncm.api.CloudMusicApi;
import net.airplus.music.ncm.music.CloudMusic;
import net.airplus.music.ncm.music.MusicProvider;
import net.airplus.music.ncm.util.Location;
import net.airplus.music.ncm.util.Tuple;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class Music {

    static final int STEREO = 8192;
    static final int INSTRUMENTAL = 131072;
    static final int DOLBY_ATMOS = 262144;
    static final int DIRTY = 1048576;
    static final long HIRES = 17179869184L;

    @SerializedName("name")
    private final String name;

    @SerializedName("mainTitle")
    private final String mainTitle;

    @SerializedName("additionalTitle")
    private final String additionalTitle;

    @SerializedName("id")
    private final long id;

    @SerializedName("ar")
    private final List<Artist> artists;

    @SerializedName("alia")
    private final List<String> aliasName;

    @SerializedName("al")
    private final Album album;

    @SerializedName("dt")
    private final long duration;

    @SerializedName("mark")
    private final long featureFlag;

    @SerializedName("publishTime")
    private final long publishTime;

    @SerializedName("tns")
    private final List<String> translatedName;

    private transient String artistsName, translatedNames;
    private transient MusicProvider provider = MusicProvider.NETEASE;
    private transient String providerId;

    public Music(String name, String mainTitle, String additionalTitle, long id,
                 List<Artist> artists, List<String> aliasName, Album album,
                 long duration, long featureFlag, long publishTime, List<String> translatedName) {
        this.name = name;
        this.mainTitle = mainTitle;
        this.additionalTitle = additionalTitle;
        this.id = id;
        this.artists = artists;
        this.aliasName = aliasName;
        this.album = album;
        this.duration = duration;
        this.featureFlag = featureFlag;
        this.publishTime = publishTime;
        this.translatedName = translatedName;
    }

    public String getName() {
        return name;
    }

    public String getMainTitle() {
        return mainTitle;
    }

    public String getAdditionalTitle() {
        return additionalTitle;
    }

    public long getId() {
        return id;
    }

    public List<Artist> getArtists() {
        return artists;
    }

    public List<String> getAliasName() {
        return aliasName;
    }

    public Album getAlbum() {
        return album;
    }

    public long getDuration() {
        return duration;
    }

    public long getFeatureFlag() {
        return featureFlag;
    }

    public long getPublishTime() {
        return publishTime;
    }

    public List<String> getTranslatedName() {
        return translatedName;
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

    public void setArtistsName(String artistsName) {
        this.artistsName = artistsName;
    }

    public void setTranslatedNames(String translatedNames) {
        this.translatedNames = translatedNames;
    }

    public final Location getCoverLocation() {
        return Location.of("tritium/textures/music/" + this.id + "/cover.png");
    }

    public final Location getBlurredCoverLocation() {
        return Location.of("tritium/textures/music/" + this.id + "/cover_blurred.png");
    }

    public final Location getSmallCoverLocation() {
        return Location.of("tritium/textures/music/" + this.id + "/cover_small.png");
    }

    public String getArtistsName() {
        if (this.artistsName == null) {
            this.artistsName = this.buildArtistsNames();

            if (this.artistsName.isEmpty()) {
                this.artistsName = "Unknown";
            }
        }

        return this.artistsName;
    }

    public String getTranslatedNames() {
        if (this.translatedNames == null) {
            this.translatedNames = this.buildTranslatedNames();
        }

        return this.translatedNames;
    }

    private String buildTranslatedNames() {

        if (this.translatedName == null || this.translatedName.isEmpty())
            return "";

        return String.join(", ", this.translatedName);
    }

    private String buildArtistsNames() {
        if (this.artists == null || this.artists.isEmpty()) {
            return "";
        }
        List<String> artistsList = this.artists.stream().map(Artist::getName).collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < artistsList.size(); i++) {
            String artistName = artistsList.get(i);

            if (i != artistsList.size() - 1) {
                sb.append(artistName).append(", ");
            } else {
                sb.append(artistName);
            }
        }

        return sb.toString();
    }

    public String getCoverUrl(int size) {
        return this.album == null ? "" : this.album.getCoverUrl(size);
    }

    /**
     * 更新歌曲播放次数
     * 这个方法目前会触发网易云风控, 不要使用
     */
    @Deprecated
    public void updPlayCount(PlayList pl, float sec) {
    }

    /**
     * 获得歌曲 url
     *
     * @return 歌曲文件 url
     */
    public Tuple<String, String> getPlayUrl() {
        JsonObject result = CloudMusicApi.songUrlV1(this.id, CloudMusic.quality.getQuality().toLowerCase()).toJsonObject();
        JsonObject music = result.get("data").getAsJsonArray().get(0).getAsJsonObject();
        if (music.get("code").getAsInt() != 200) {
            return null;
        }

        String url = music.get("url").getAsString();

        String type = music.get("type").getAsString();

        if (type.isEmpty())
            type = "mp3";

        return new Tuple<>(url, type);
    }

    public void setLike(boolean like) {
        CloudMusicApi.like(this.id, like);
    }

    public boolean isInstrumental() {
        return (this.featureFlag & INSTRUMENTAL) != 0;
    }

    public boolean isDolbyAtmos() {
        return (this.featureFlag & DOLBY_ATMOS) != 0;
    }

    public boolean isDirty() {
        return (this.featureFlag & DIRTY) != 0;
    }

    public boolean isHiRes() {
        return (this.featureFlag & HIRES) != 0;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Music music = (Music) o;
        return getProvider() == music.getProvider() && getProviderId().equals(music.getProviderId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getProvider(), getProviderId());
    }
}
