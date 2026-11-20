package net.airplus.music.bm.api;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * The few HTTP calls the music player makes, on {@link HttpURLConnection} (Java 8, no extra
 * dependency). Old Java 8 runtimes sometimes lack a root certificate a CDN needs; a failed TLS
 * handshake is retried once with the client's bundled trust store ({@link SSLUtils}). Requests are
 * never downgraded to plain HTTP because they may carry the login cookie.
 */
public final class MusicHttp {
    private static final int CONNECT_TIMEOUT = 10_000, READ_TIMEOUT = 15_000;
    private static volatile SSLSocketFactory fallback;

    private MusicHttp() {
    }

    public static final class Response {
        public final int status;
        public final byte[] body;
        public final List<String> setCookies;

        Response(int status, byte[] body, List<String> setCookies) {
            this.status = status;
            this.body = body;
            this.setCookies = setCookies;
        }

        public String text() {
            return new String(this.body, StandardCharsets.UTF_8);
        }
    }

    public static Response postForm(String url, Map<String, String> form, Map<String, String> headers) throws IOException {
        byte[] body = encodeForm(form).getBytes(StandardCharsets.UTF_8);
        try {
            return request("POST", url, body, headers, false);
        } catch (SSLException e) {
            return request("POST", url, body, headers, true);
        }
    }

    public static Response get(String url, Map<String, String> headers) throws IOException {
        try {
            return request("GET", url, null, headers, false);
        } catch (SSLException e) {
            return request("GET", url, null, headers, true);
        }
    }

    /** Downloads a small resource (cover art), refusing anything larger than {@code maxBytes}. */
    public static byte[] download(String url, int maxBytes) throws IOException {
        Response r = get(url, Collections.<String, String>emptyMap());
        if (r.status / 100 != 2) throw new IOException("HTTP " + r.status);
        if (r.body.length > maxBytes) throw new IOException("Too large: " + r.body.length);
        return r.body;
    }

    /**
     * Opens a streaming GET (audio), optionally from byte {@code from} on. The caller reads and
     * closes {@link HttpURLConnection#getInputStream()}.
     */
    public static HttpURLConnection openStream(String url, long from) throws IOException {
        try {
            return openStream(url, from, false);
        } catch (SSLException e) {
            return openStream(url, from, true);
        }
    }

    private static HttpURLConnection openStream(String url, long from, boolean bundledTrust) throws IOException {
        HttpURLConnection c = connect("GET", url, Collections.<String, String>emptyMap(), bundledTrust);
        c.setReadTimeout(20_000);
        c.setRequestProperty("Accept-Encoding", "identity");
        if (from > 0) c.setRequestProperty("Range", "bytes=" + from + "-");
        int status = c.getResponseCode();
        if (status / 100 != 2) {
            c.disconnect();
            throw new StatusException(status, "HTTP " + status + " for audio stream");
        }
        return c;
    }

    /** The server answered, but not with 2xx: retrying the same request will not help (except 5xx). */
    public static final class StatusException extends IOException {
        public final int status;

        StatusException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue() == null ? "" : e.getValue()));
        }
        return sb.toString();
    }

    public static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Response request(String method, String url, byte[] body, Map<String, String> headers,
                                    boolean bundledTrust) throws IOException {
        HttpURLConnection c = connect(method, url, headers, bundledTrust);
        try {
            if (body != null) {
                c.setDoOutput(true);
                if (c.getRequestProperty("Content-Type") == null) {
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                }
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body);
                }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] data = in == null ? new byte[0] : readAll(in, "gzip".equalsIgnoreCase(c.getContentEncoding()));
            List<String> cookies = new ArrayList<>();
            for (Map.Entry<String, List<String>> h : c.getHeaderFields().entrySet()) {
                if (h.getKey() != null && h.getKey().equalsIgnoreCase("Set-Cookie")) cookies.addAll(h.getValue());
            }
            return new Response(status, data, cookies);
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection connect(String method, String url, Map<String, String> headers,
                                             boolean bundledTrust) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (bundledTrust && c instanceof HttpsURLConnection) {
            ((HttpsURLConnection) c).setSSLSocketFactory(bundledFactory());
        }
        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Encoding", "gzip");
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        return c;
    }

    private static SSLSocketFactory bundledFactory() throws SSLException {
        SSLSocketFactory f = fallback;
        if (f == null) {
            try {
                f = BmSsl.getSSLContext().getSocketFactory();
            } catch (RuntimeException | LinkageError e) {
                throw new SSLException("No bundled trust store", e);
            }
            fallback = f;
        }
        return f;
    }

    private static byte[] readAll(InputStream raw, boolean gzip) throws IOException {
        try (InputStream in = gzip ? new GZIPInputStream(raw) : raw) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
