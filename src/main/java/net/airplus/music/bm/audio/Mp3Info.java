package net.airplus.music.bm.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Header;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/** Quick facts about an MP3 file without decoding it (for the local library). */
public final class Mp3Info {
    private Mp3Info() {
    }

    /** Length in milliseconds from the first frame header (VBR-aware), 0 if unreadable. */
    public static long durationMs(File file) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 16 * 1024)) {
            Bitstream bitstream = new Bitstream(in);
            Header h = bitstream.readFrame();
            if (h == null) return 0;
            int audioBytes = (int) Math.min(Integer.MAX_VALUE, file.length() - Math.max(0, bitstream.header_pos()));
            return (long) h.total_ms(audioBytes);
        } catch (Exception | LinkageError e) {
            return 0;
        }
    }
}
