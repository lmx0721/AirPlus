package net.airplus.music.bm.local;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Just enough ID3 to list local MP3s: title, artist, album and the embedded cover (ID3v2.2–2.4,
 * falling back to ID3v1). Chinese files often store GBK text while claiming Latin-1; such text is
 * decoded as UTF-8 if valid, otherwise as GBK.
 */
public final class Id3Reader {
    private static final int MAX_TAG = 16 << 20;
    private static final Charset GBK = charset("GBK");

    public static final class Tags {
        public String title = "", artist = "", album = "";
        public byte[] picture;
    }

    private Id3Reader() {
    }

    public static Tags read(File file, boolean wantPicture) {
        Tags tags = new Tags();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] head = new byte[10];
            if (raf.length() >= 10) {
                raf.readFully(head);
                if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
                    int size = syncsafe(head, 6);
                    if (size > 0 && size <= MAX_TAG && size + 10 <= raf.length()) {
                        byte[] tag = new byte[size];
                        raf.readFully(tag);
                        if ((head[5] & 0x80) != 0 && head[3] < 4) tag = unsync(tag);
                        parseV2(tag, head[3], head[5], tags, wantPicture);
                    }
                }
            }
            if ((tags.title.isEmpty() || tags.artist.isEmpty()) && raf.length() > 128) {
                byte[] v1 = new byte[128];
                raf.seek(raf.length() - 128);
                raf.readFully(v1);
                if (v1[0] == 'T' && v1[1] == 'A' && v1[2] == 'G') {
                    if (tags.title.isEmpty()) tags.title = legacy(v1, 3, 30);
                    if (tags.artist.isEmpty()) tags.artist = legacy(v1, 33, 30);
                    if (tags.album.isEmpty()) tags.album = legacy(v1, 63, 30);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // A broken tag only means less information; the file may still play.
        }
        return tags;
    }

    private static void parseV2(byte[] tag, int version, int flags, Tags tags, boolean wantPicture) {
        int pos = 0;
        if ((flags & 0x40) != 0 && version >= 3 && tag.length >= 4) {
            int ext = version == 4 ? syncsafe(tag, 0) : be32(tag, 0) + 4;
            pos = Math.max(0, Math.min(tag.length, ext));
        }
        boolean v22 = version == 2;
        int headerLen = v22 ? 6 : 10;
        while (pos + headerLen <= tag.length) {
            if (tag[pos] == 0) break; // padding
            String id = new String(tag, pos, v22 ? 3 : 4, StandardCharsets.ISO_8859_1);
            int size = v22 ? ((tag[pos + 3] & 255) << 16 | (tag[pos + 4] & 255) << 8 | (tag[pos + 5] & 255))
                    : version == 4 ? syncsafe(tag, pos + 4) : be32(tag, pos + 4);
            int body = pos + headerLen;
            if (size <= 0 || body + size > tag.length) break;
            switch (id) {
                case "TIT2":
                case "TT2":
                    tags.title = text(tag, body, size);
                    break;
                case "TPE1":
                case "TP1":
                    tags.artist = text(tag, body, size);
                    break;
                case "TALB":
                case "TAL":
                    tags.album = text(tag, body, size);
                    break;
                case "APIC":
                case "PIC":
                    if (wantPicture && tags.picture == null) tags.picture = picture(tag, body, size, v22);
                    break;
                default:
                    break;
            }
            pos = body + size;
        }
    }

    private static String text(byte[] b, int off, int len) {
        if (len <= 1) return "";
        int enc = b[off];
        return decode(b, off + 1, len - 1, enc).replace("\u0000", " / ").trim().replaceAll("( / )+$", "");
    }

    private static byte[] picture(byte[] b, int off, int len, boolean v22) {
        int end = off + len;
        int enc = b[off];
        int p = off + 1;
        if (v22) {
            p += 3; // image format, e.g. "JPG"
        } else {
            while (p < end && b[p] != 0) p++; // MIME type
            p++;
        }
        p++; // picture type
        // Description, terminated by one zero byte (two for UTF-16).
        if (enc == 1 || enc == 2) {
            while (p + 1 < end && !(b[p] == 0 && b[p + 1] == 0)) p += 2;
            p += 2;
        } else {
            while (p < end && b[p] != 0) p++;
            p++;
        }
        if (p >= end) return null;
        byte[] out = new byte[end - p];
        System.arraycopy(b, p, out, 0, out.length);
        return out;
    }

    private static String decode(byte[] b, int off, int len, int enc) {
        switch (enc) {
            case 1:
                return new String(b, off, len, StandardCharsets.UTF_16);
            case 2:
                return new String(b, off, len, StandardCharsets.UTF_16BE);
            case 3:
                return new String(b, off, len, StandardCharsets.UTF_8);
            default:
                return legacy(b, off, len);
        }
    }

    /** "Latin-1" text that is really UTF-8 or GBK more often than not. */
    private static String legacy(byte[] b, int off, int len) {
        int end = off + len;
        while (end > off && (b[end - 1] == 0 || b[end - 1] == ' ')) end--;
        len = end - off;
        boolean ascii = true;
        for (int i = off; i < end; i++) {
            if ((b[i] & 0x80) != 0) {
                ascii = false;
                break;
            }
        }
        if (ascii) return new String(b, off, len, StandardCharsets.ISO_8859_1).trim();
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, off, len)).toString().trim();
        } catch (CharacterCodingException e) {
            return new String(b, off, len, GBK != null ? GBK : StandardCharsets.ISO_8859_1).trim();
        }
    }

    private static int syncsafe(byte[] b, int off) {
        return (b[off] & 0x7f) << 21 | (b[off + 1] & 0x7f) << 14 | (b[off + 2] & 0x7f) << 7 | (b[off + 3] & 0x7f);
    }

    private static int be32(byte[] b, int off) {
        return (b[off] & 255) << 24 | (b[off + 1] & 255) << 16 | (b[off + 2] & 255) << 8 | (b[off + 3] & 255);
    }

    private static byte[] unsync(byte[] in) {
        byte[] out = new byte[in.length];
        int n = 0;
        for (int i = 0; i < in.length; i++) {
            out[n++] = in[i];
            if ((in[i] & 255) == 0xff && i + 1 < in.length && in[i + 1] == 0) i++;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private static Charset charset(String name) {
        try {
            return Charset.forName(name);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
