package net.airplus.music.bm.audio;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Bytes of an MP3 file, readable from any offset (seeking reopens at a byte position). The
 * engine closes the returned stream from another thread to abort a blocked read, and closes the
 * source itself once the track is done with.
 */
public interface AudioSource {
    /** Opens the file at byte {@code offset}. */
    InputStream open(long offset) throws IOException;

    /** Total size in bytes, or -1 while unknown. */
    long length();

    /**
     * True when the stream returned by {@link #open} has {@code bytes} ready to read without
     * waiting on the network (or will never get more). Local files are always ready.
     */
    default boolean ready(InputStream in, int bytes) {
        return !(in instanceof Prefetch.Reader) || ((Prefetch.Reader) in).ready(bytes);
    }

    /** Bytes downloaded ahead of the stream's read position, or -1 when that does not apply. */
    default long ahead(InputStream in) {
        return in instanceof Prefetch.Reader ? ((Prefetch.Reader) in).ahead() : -1;
    }

    /** The track is done with: stop any download. */
    default void close() {
    }

    /** A network stream, downloaded ahead of the decoder on its own thread ({@link Prefetch}). */
    static AudioSource http(String url, long sizeHint) {
        return new Http(url, sizeHint);
    }

    static AudioSource file(File file) {
        return new FileSource(file);
    }

    final class Http implements AudioSource {
        private final String url;
        private final long sizeHint;
        private Prefetch download;
        private boolean closed;

        Http(String url, long sizeHint) {
            this.url = url;
            this.sizeHint = sizeHint > 0 ? sizeHint : -1;
        }

        /**
         * Reuses the running download when {@code offset} is inside (or just past) what it has, so
         * seeking back costs no new connection; otherwise starts a new one from {@code offset}.
         */
        @Override
        public synchronized InputStream open(long offset) throws IOException {
            if (this.closed) throw new IOException("Source closed");
            long at = Math.max(0, offset);
            if (this.download == null || !this.download.covers(at)) {
                if (this.download != null) this.download.close();
                this.download = new Prefetch(this.url, at, this.length());
            }
            return this.download.reader(at);
        }

        @Override
        public synchronized long length() {
            long known = this.download != null ? this.download.length() : -1;
            return known > 0 ? known : this.sizeHint;
        }

        @Override
        public synchronized void close() {
            this.closed = true;
            if (this.download != null) this.download.close();
            this.download = null;
        }
    }

    final class FileSource implements AudioSource {
        private final File file;

        FileSource(File file) {
            this.file = file;
        }

        @Override
        public InputStream open(long offset) throws IOException {
            FileInputStream in = new FileInputStream(this.file);
            long skip = Math.max(0, offset);
            while (skip > 0) {
                long n = in.skip(skip);
                if (n <= 0) break;
                skip -= n;
            }
            return new BufferedInputStream(in, 64 * 1024);
        }

        @Override
        public long length() {
            return this.file.length();
        }
    }
}
