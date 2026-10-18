// skid SetsunaClient
package net.airplus.music.ncm.util;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Objects;

public class Location {

    protected final String resourcePath;

    protected Location(String resourcePath) {
        this.resourcePath = resourcePath;
        Objects.requireNonNull(this.resourcePath);
    }

    public String getResourcePath() {
        return this.resourcePath;
    }

    public InputStream getResourceStream() {
        InputStream stream = Location.class.getResourceAsStream(resourcePath);

        if (stream == null)
            throw new MissingResourceException(resourcePath + " not found", Location.class.getName(), resourcePath);

        return stream;
    }

    public String toString() {
        return this.resourcePath;
    }

    private static final Map<String, Location> locationCache = new HashMap<>();

    public static Location of(String path) {
        if (!locationCache.containsKey(path)) {
            Location location = new Location(path);
            locationCache.put(path, location);
            return location;
        }

        return locationCache.get(path);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Location)) return false;
        Location other = (Location) o;
        return resourcePath.equals(other.resourcePath);
    }

    @Override
    public int hashCode() {
        return resourcePath.hashCode();
    }

}
