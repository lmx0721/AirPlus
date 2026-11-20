package net.airplus.music.bm.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Small file helpers used by the music store writer.
 * Writes go to a sibling temp file first and are then moved into place, so an
 * interrupted write never leaves a truncated JSON file behind.
 */
public final class BmFileUtil {
    private BmFileUtil() {
    }

    public static String readString(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    public static void writeAtomic(File file, String content) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create directory " + parent);
        }
        File temp = new File(parent, file.getName() + ".tmp");
        Files.write(temp.toPath(), content.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // ATOMIC_MOVE is not supported on every file system; fall back to a plain replace.
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Keeps a copy of a file that failed to parse so the user can recover it by hand.
     *
     * @return the backup file, or null when nothing could be saved
     */
    public static File quarantine(File file) {
        if (!file.exists()) {
            return null;
        }
        File backup = new File(file.getParentFile(), file.getName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } catch (IOException e) {
            return null;
        }
    }
}
