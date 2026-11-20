package net.airplus.music.bm.audio;

import net.airplus.music.bm.util.BmLog;
import net.airplus.music.bm.api.MusicHttp;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads one HTTP audio stream on its own thread, as fast as the network allows, into 64 KB
 * blocks in memory. The decoder reads from those blocks through a {@link Reader} and so never
 * waits on the network itself; a slow or throttled CDN only has to keep up on average.
 *
 * <p>Before this, the audio thread read the socket directly with half a second of sound queued:
 * once the first burst was used up (a few lines into a song), any hiccup longer than that was
 * heard as a gap.
 *
 * <ul>
 *   <li>Memory is bounded: the download waits once it is {@link #AHEAD_LIMIT} ahead of the reader,
 *       and blocks more than {@link #KEEP_BEHIND} behind it are dropped.</li>
 *   <li>A broken connection is resumed exactly where it stopped ({@code Range: bytes=<end>-}),
 *       up to {@link #RETRIES} times in a row. Only then does the reader see the error.</li>
 *   <li>A reader may be opened anywhere in the downloaded range (seeking back needs no new
 *       connection) or a little past its end (it waits for the bytes).</li>
 * </ul>
 */
final class Prefetch {
    private static final int BLOCK = 64 * 1024;
    private static final long AHEAD_LIMIT = 16L << 20, KEEP_BEHIND = 4L << 20;
    /** A reader opened this far past the downloaded end still waits for this download. */
    private static final long REACH = 512 * 1024;
    private static final int RETRIES = 4;
    /** Nobody has read for this long while the download waits: the source was abandoned. */
    private static final long IDLE_NANOS = 90_000_000_000L;

    private final String url;
    private final Object lock = new Object();
    /** File offset of {@code blocks.get(0)}; always the first offset plus a whole number of blocks. */
    private long base;
    private final List<byte[]> blocks = new ArrayList<>();
    /** File offset just past the last downloaded byte. */
    private long end;
    /** Where the most recent read left off, for flow control and dropping old blocks. */
    private long readAt;
    private long lastReadNanos = System.nanoTime();
    private volatile long length;
    private boolean complete, closed;
    private IOException failure;
    /** The response being read, closed from {@link #close()} to cut a blocked read short. */
    private volatile InputStream current;

    /**
     * @param lengthHint total size in bytes when known, else -1 (learnt from the response)
     */
    Prefetch(String url, long offset, long lengthHint) {
        this.url = url;
        this.base = this.end = this.readAt = Math.max(0, offset);
        this.length = lengthHint;
        Thread thread = new Thread(this::run, "BetterMyau-Music-Download");
        thread.setDaemon(true);
        thread.start();
    }

    long length() {
        return this.length;
    }

    /** Whether a reader at {@code offset} can be served by this download. */
    boolean covers(long offset) {
        synchronized (this.lock) {
            return !this.closed && this.failure == null && offset >= this.base && offset <= this.end + REACH;
        }
    }

    Reader reader(long offset) {
        synchronized (this.lock) {
            this.readAt = offset;
            this.lastReadNanos = System.nanoTime();
            this.lock.notifyAll();
        }
        return new Reader(offset);
    }

    /** Stops the download for good and wakes every reader. */
    void close() {
        synchronized (this.lock) {
            this.closed = true;
            this.lock.notifyAll();
        }
        InputStream in = this.current;
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ------------------------------------------------------------ download thread

    private void run() {
        int failures = 0;
        while (true) {
            long from;
            synchronized (this.lock) {
                if (this.closed) return;
                from = this.end;
                if (this.length > 0 && from >= this.length) {
                    this.complete = true;
                    this.lock.notifyAll();
                    return;
                }
            }
            HttpURLConnection c = null;
            InputStream in = null;
            try {
                c = MusicHttp.openStream(this.url, from);
                this.learnLength(c, from);
                in = c.getInputStream();
                this.current = in;
                synchronized (this.lock) {
                    if (this.closed) return;
                }
                // A server that ignores Range sends the whole file again: skip to where we are.
                if (from > 0 && c.getResponseCode() != 206) skipFully(in, from);
                if (this.pump(in)) return;
                // Cut short without an error: resume, but only keep trying while it makes progress.
                synchronized (this.lock) {
                    if (this.end > from) failures = 0;
                    else if (++failures > RETRIES) {
                        this.failure = new IOException("Audio stream keeps ending early");
                        this.lock.notifyAll();
                        return;
                    }
                }
            } catch (IOException e) {
                boolean progressed;
                synchronized (this.lock) {
                    if (this.closed) return;
                    progressed = this.end > from;
                }
                // Count failures in a row: a connection that delivered something starts the count again.
                failures = progressed ? 1 : failures + 1;
                boolean fatal = e instanceof MusicHttp.StatusException && ((MusicHttp.StatusException) e).status / 100 == 4;
                if (fatal || failures > RETRIES) {
                    synchronized (this.lock) {
                        this.failure = e;
                        this.lock.notifyAll();
                    }
                    return;
                }
                BmLog.LOGGER.info("[Music] Download interrupted at {} KB, resuming ({}/{}): {}", from / 1024, failures, RETRIES, e.getMessage());
                try {
                    Thread.sleep(300L * failures);
                } catch (InterruptedException ie) {
                    return;
                }
            } finally {
                this.current = null;
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
                if (c != null) c.disconnect();
            }
        }
    }

    /**
     * Copies the response into blocks until it ends. True when the file is complete (or the
     * download was closed); false when the response ended early and should be resumed.
     */
    private boolean pump(InputStream in) throws IOException {
        while (true) {
            byte[] block;
            int offset;
            boolean fresh;
            synchronized (this.lock) {
                // Far enough ahead of the reader: wait for it, unless it has gone away.
                while (!this.closed && this.end - this.readAt > AHEAD_LIMIT) {
                    if (System.nanoTime() - this.lastReadNanos > IDLE_NANOS) {
                        this.closed = true;
                        break;
                    }
                    try {
                        this.lock.wait(500);
                    } catch (InterruptedException e) {
                        this.closed = true;
                    }
                }
                if (this.closed) return true;
                offset = (int) ((this.end - this.base) % BLOCK);
                fresh = offset == 0;
                block = fresh ? new byte[BLOCK] : this.blocks.get(this.blocks.size() - 1);
            }
            // Outside the lock: readers only look below `end`, and this writes above it. A fresh
            // block joins the list only once it holds something.
            int n = in.read(block, offset, BLOCK - offset);
            synchronized (this.lock) {
                if (n < 0) {
                    long total = this.length;
                    if (total > 0 && this.end < total) return false; // cut short: resume
                    this.complete = true;
                    if (total <= 0) this.length = this.end;
                    this.lock.notifyAll();
                    return true;
                }
                if (fresh) this.blocks.add(block);
                this.end += n;
                this.lock.notifyAll();
            }
        }
    }

    private void learnLength(HttpURLConnection c, long from) {
        if (this.length > 0) return;
        long total = -1;
        String range = c.getHeaderField("Content-Range");
        if (range != null && range.contains("/")) {
            try {
                total = Long.parseLong(range.substring(range.lastIndexOf('/') + 1).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (total <= 0 && c.getContentLength() > 0) total = c.getContentLength() + Math.max(0, from);
        if (total > 0) this.length = total;
    }

    private static void skipFully(InputStream in, long bytes) throws IOException {
        byte[] scratch = new byte[8192];
        while (bytes > 0) {
            int n = in.read(scratch, 0, (int) Math.min(scratch.length, bytes));
            if (n < 0) throw new IOException("Stream ended while skipping");
            bytes -= n;
        }
    }

    // ------------------------------------------------------------ reading

    /** The decoder's view of the download, from one offset on. Closing it leaves the download running. */
    final class Reader extends InputStream {
        private long pos;
        private boolean closed;

        private Reader(long pos) {
            this.pos = pos;
        }

        /** True when {@code bytes} are buffered past the read position, or nothing more will come. */
        boolean ready(int bytes) {
            synchronized (Prefetch.this.lock) {
                return Prefetch.this.complete || Prefetch.this.failure != null || Prefetch.this.closed
                        || Prefetch.this.end - this.pos >= bytes;
            }
        }

        /** Bytes downloaded past the read position. */
        long ahead() {
            synchronized (Prefetch.this.lock) {
                return Math.max(0, Prefetch.this.end - this.pos);
            }
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = this.read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            Object lock = Prefetch.this.lock;
            synchronized (lock) {
                while (true) {
                    if (this.closed || Prefetch.this.closed) throw new IOException("Stream closed");
                    if (this.pos < Prefetch.this.base) throw new IOException("Position no longer buffered");
                    if (this.pos < Prefetch.this.end) break;
                    if (Prefetch.this.complete) return -1;
                    if (Prefetch.this.failure != null) throw new IOException(Prefetch.this.failure.getMessage(), Prefetch.this.failure);
                    try {
                        lock.wait(250);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted");
                    }
                }
                long rel = this.pos - Prefetch.this.base;
                byte[] block = Prefetch.this.blocks.get((int) (rel / BLOCK));
                int at = (int) (rel % BLOCK);
                int n = (int) Math.min(len, Math.min(BLOCK - at, Prefetch.this.end - this.pos));
                System.arraycopy(block, at, b, off, n);
                this.pos += n;
                Prefetch.this.readAt = this.pos;
                Prefetch.this.lastReadNanos = System.nanoTime();
                // Drop blocks well behind the reader (seeking back that far reconnects).
                while (Prefetch.this.blocks.size() > 1 && this.pos - Prefetch.this.base > KEEP_BEHIND + BLOCK) {
                    Prefetch.this.blocks.remove(0);
                    Prefetch.this.base += BLOCK;
                }
                lock.notifyAll();
                return n;
            }
        }

        @Override
        public int available() {
            synchronized (Prefetch.this.lock) {
                return (int) Math.min(Integer.MAX_VALUE, Math.max(0, Prefetch.this.end - this.pos));
            }
        }

        @Override
        public void close() {
            synchronized (Prefetch.this.lock) {
                this.closed = true;
                Prefetch.this.lock.notifyAll();
            }
        }
    }
}
