package net.airplus.music.bm.local;

import net.airplus.music.bm.util.BmLog;
import net.airplus.music.bm.audio.Mp3Info;
import net.airplus.music.bm.lyrics.LyricParser;
import net.airplus.music.bm.model.Lyrics;
import net.airplus.music.bm.model.Track;

import java.io.File;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * MP3 files in a folder of the player's choosing (sub-folders a few levels deep). Titles come from
 * ID3 tags, else from "Artist - Title" file names; a same-named {@code .lrc} next to a song is
 * its lyrics, and the embedded picture its cover.
 */
public final class LocalLibrary {
    private static final int MAX_FILES = 3000, MAX_DEPTH = 4;

    private volatile List<Track> tracks = Collections.emptyList();
    private volatile boolean scanning;
    private volatile String folder = "";

    public List<Track> tracks() {
        return this.tracks;
    }

    public boolean scanning() {
        return this.scanning;
    }

    public String folder() {
        return this.folder;
    }

    /** Rescans {@code dirs} on {@code io}; {@code done} runs on that thread afterwards. */
    public void scan(List<File> dirs, ExecutorService io, Runnable done) {
        StringBuilder names = new StringBuilder();
        for (File d : dirs) {
            if (names.length() > 0) names.append("、");
            names.append(d.getAbsolutePath());
        }
        this.folder = names.toString();
        this.scanning = true;
        io.execute(() -> {
            List<Track> found = new ArrayList<>();
            try {
                for (File dir : dirs) if (dir.isDirectory()) walk(dir, 0, found);
                found.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
                this.tracks = Collections.unmodifiableList(found);
                BmLog.LOGGER.info("[Music] Local library: {} songs in {}", found.size(), this.folder);
            } catch (RuntimeException e) {
                BmLog.LOGGER.warn("[Music] Local library scan failed", e);
            } finally {
                this.scanning = false;
                if (done != null) done.run();
            }
        });
    }

    private static void walk(File dir, int depth, List<Track> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (out.size() >= MAX_FILES) return;
            if (f.isDirectory()) {
                if (depth < MAX_DEPTH && !f.getName().startsWith(".")) walk(f, depth + 1, out);
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".mp3")) {
                out.add(track(f));
            }
        }
    }

    static Track track(File f) {
        Id3Reader.Tags tags = Id3Reader.read(f, false);
        String base = f.getName().substring(0, f.getName().length() - 4);
        String title = tags.title, artist = tags.artist;
        if (title.isEmpty()) {
            int dash = base.indexOf(" - ");
            if (dash > 0) {
                if (artist.isEmpty()) artist = base.substring(0, dash).trim();
                title = base.substring(dash + 3).trim();
            } else {
                title = base;
            }
        }
        List<String> artists = new ArrayList<>();
        if (!artist.isEmpty()) {
            for (String a : artist.split("\\s*[/&、,;]\\s*")) if (!a.isEmpty()) artists.add(a);
        }
        String path = f.getAbsolutePath();
        long id = ((long) path.hashCode() << 32) ^ (f.length() & 0xffffffffL);
        return new Track(Track.Source.LOCAL, id, title, artists, tags.album, "", Mp3Info.durationMs(f), 0, path);
    }

    /** A track for any single MP3 path (e.g. typed into a command). */
    public static Track fromFile(File f) {
        return track(f);
    }

    /** The embedded cover, or null. IO thread. */
    public static byte[] coverBytes(Track t) {
        return Id3Reader.read(new File(t.path), true).picture;
    }

    /** {@code song.lrc} beside {@code song.mp3}, or {@link Lyrics#EMPTY}. IO thread. */
    public static Lyrics lyrics(Track t) {
        File mp3 = new File(t.path);
        String name = mp3.getName();
        File lrc = new File(mp3.getParentFile(), name.substring(0, name.lastIndexOf('.')) + ".lrc");
        if (!lrc.isFile()) return Lyrics.EMPTY;
        try {
            byte[] bytes = Files.readAllBytes(lrc.toPath());
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                text = new String(bytes, Charset.forName("GBK"));
            }
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
            return LyricParser.parse(text, "", "");
        } catch (IOException | RuntimeException e) {
            return Lyrics.EMPTY;
        }
    }
}
