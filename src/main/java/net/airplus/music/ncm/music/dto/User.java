// skid SetsunaClient
package net.airplus.music.ncm.music.dto;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import net.airplus.music.ncm.api.CloudMusicApi;
import net.airplus.music.ncm.util.Location;
import net.airplus.music.ncm.util.json.JsonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 用户对象
 */
public class User {

    @SerializedName("userId")
    private final long id;
    @SerializedName("nickname")
    private final String name;
    @SerializedName("signature")
    private final String signature;
    @SerializedName("vipType")
    private final int vip;
    @SerializedName("avatarUrl")
    private final String avatarUrl;

    public User(long id, String name, String signature, int vip, String avatarUrl) {
        this.id = id;
        this.name = name;
        this.signature = signature;
        this.vip = vip;
        this.avatarUrl = avatarUrl;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSignature() {
        return signature;
    }

    public int getVip() {
        return vip;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public final Location getAvatarLocation() {
        return Location.of("tritium/textures/user/" + this.id + "/avatar.png");
    }

    /**
     * 用户歌单
     *
     * @return 歌单列表
     */
    public List<PlayList> playLists(int page, int limit) {
        if (limit == 0) {
            limit = 30;
        }

        JsonObject data = CloudMusicApi.userPlaylist(this.id, limit, limit * page).toJsonObject();

        List<PlayList> playLists = new ArrayList<>();
        data.get("playlist").getAsJsonArray().forEach(playList -> {
            JsonObject obj = playList.getAsJsonObject();
            // fix for missing @SerializedName(..., alternate = {})
            if (obj.has("picUrl")) {
                obj.addProperty("coverImgUrl", obj.get("picUrl").getAsString());
            }

            if (obj.has("playcount")) {
                obj.addProperty("playCount", obj.get("playcount").getAsLong());
            }

            PlayList parse = JsonUtils.parse(obj, PlayList.class);
            playLists.add(parse);
        });

        return playLists;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return id == user.id;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
