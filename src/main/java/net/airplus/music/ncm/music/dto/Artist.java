// skid SetsunaClient
package net.airplus.music.ncm.music.dto;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Objects;

/**
 * @author IzumiiKonata
 * Date: 2025/11/7 22:13
 */
public class Artist {

    @SerializedName("id")
    private final long id;
    @SerializedName("name")
    private final String name;
    @SerializedName("tns")
    private final List<String> translatedName;
    @SerializedName("alias")
    private final List<String> aliasName;

    public Artist(long id, String name, List<String> translatedName, List<String> aliasName) {
        this.id = id;
        this.name = name;
        this.translatedName = translatedName;
        this.aliasName = aliasName;
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<String> getTranslatedName() {
        return translatedName;
    }

    public List<String> getAliasName() {
        return aliasName;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Artist artist = (Artist) o;
        return id == artist.id;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
