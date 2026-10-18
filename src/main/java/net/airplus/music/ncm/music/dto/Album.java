// skid SetsunaClient
package net.airplus.music.ncm.music.dto;

import com.google.gson.annotations.SerializedName;
import net.airplus.music.ncm.util.Location;

import java.util.List;
import java.util.Objects;

/**
 * @author IzumiiKonata
 * Date: 2025/11/7 22:16
 */
public class Album {

    @SerializedName("id")
    private final long id;
    @SerializedName("name")
    private final String name;
    @SerializedName("picUrl")
    private final String picUrl;
    @SerializedName("tns")
    private final List<String> translatedName;

    public Album(long id, String name, String picUrl, List<String> translatedName) {
        this.id = id;
        this.name = name;
        this.picUrl = picUrl;
        this.translatedName = translatedName;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getPicUrl() {
        return picUrl;
    }

    public List<String> getTranslatedName() {
        return translatedName;
    }

    public final Location getCoverLocation() {
        return Location.of("tritium/textures/album/" + this.id + "/cover.png");
    }

    public String getCoverUrl(int size) {
        String url = cleanUrl(this.picUrl);
        if (url.isEmpty()) {
            return "";
        }
        if (url.contains("param=")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "param=" + size + "y" + size;
    }

    private static String cleanUrl(String url) {
        if (url == null || url.trim().isEmpty() || "null".equalsIgnoreCase(url)) {
            return "";
        }
        String clean = url.trim();
        if (clean.startsWith("//")) {
            return "https:" + clean;
        }
        return clean;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Album album = (Album) o;
        return id == album.id;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
