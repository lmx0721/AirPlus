package net.airplus.music.bm.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Frame-by-frame MP3 decoding on JLayer. After {@link #next()} returns true, {@link #samples()}
 * holds {@link #length()} interleaved 16-bit samples ({@link #channels()} channels).
 *
 * <p>A stream opened in the middle of a file (seeking) starts on a frame boundary JLayer finds by
 * itself; a few broken frames there are skipped instead of ending the song.
 */
final class Mp3Decoder implements Closeable {
    private static final int MAX_BAD_FRAMES = 32;

    private final InputStream in;
    private final Bitstream bitstream;
    private final Decoder decoder = new Decoder();
    private Header first;
    private short[] samples = new short[0];
    private int length, channels = 2, rate = 44100;

    Mp3Decoder(InputStream in) {
        this.in = in;
        this.bitstream = new Bitstream(in);
    }

    /** Decodes the next frame; false at the end of the stream. */
    boolean next() throws IOException {
        int bad = 0;
        while (true) {
            Header h;
            try {
                h = this.bitstream.readFrame();
            } catch (BitstreamException e) {
                if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
                if (++bad > MAX_BAD_FRAMES) throw new IOException("MP3 stream unreadable", e);
                continue;
            }
            if (h == null) return false;
            try {
                SampleBuffer out = (SampleBuffer) this.decoder.decodeFrame(h, this.bitstream);
                if (this.first == null) this.first = h;
                this.channels = out.getChannelCount();
                this.rate = out.getSampleFrequency();
                this.samples = out.getBuffer();
                this.length = out.getBufferLength();
                return true;
            } catch (DecoderException | ArrayIndexOutOfBoundsException e) {
                if (++bad > MAX_BAD_FRAMES) throw new IOException("MP3 frame undecodable", e);
            } finally {
                this.bitstream.closeFrame();
            }
        }
    }

    short[] samples() {
        return this.samples;
    }

    int length() {
        return this.length;
    }

    int channels() {
        return this.channels;
    }

    int rate() {
        return this.rate;
    }

    /** The first decoded frame's header (null before the first frame). */
    Header firstHeader() {
        return this.first;
    }

    /** Bytes of tags before the first audio frame (ID3v2), only meaningful when opened at 0. */
    int audioStart() {
        return Math.max(0, this.bitstream.header_pos());
    }

    @Override
    public void close() {
        try {
            this.bitstream.close();
        } catch (BitstreamException ignored) {
        }
        try {
            this.in.close();
        } catch (IOException ignored) {
        }
    }
}
