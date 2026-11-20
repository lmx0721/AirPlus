package net.airplus.music.bm.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The cookies NetEase knows this client by: a persistent device id, the login token
 * ({@code MUSIC_U}, {@code __csrf}) after a QR sign-in, and an anonymous token ({@code MUSIC_A})
 * for guests. Request headers are built the way NeteaseCloudMusicApiEnhanced's
 * {@code util/request.js} builds them (desktop "pc" client profile).
 *
 * <p>The cookie string is a credential: it is stored encrypted by {@code MusicStore} and must never
 * be logged.
 */
public final class NeteaseSession {
    static final String OS = "pc";
    static final String APP_VER = "3.1.17.204416";
    static final String OS_VER = "Microsoft-Windows-10-Professional-build-19045-64bit";
    static final String CHANNEL = "netease";

    private final Map<String, String> cookies = new LinkedHashMap<>();
    private String deviceId;
    /** Per-run browser-ish identifiers the web endpoints expect. */
    private final String nuid = NeteaseCrypto.randomHex(64, false);
    private final String wnmcid = randomLetters(6) + "." + System.currentTimeMillis() + ".01.0";
    private Runnable onChange = () -> { };

    public NeteaseSession(String deviceId) {
        this.deviceId = deviceId == null || deviceId.isEmpty() ? NeteaseCrypto.randomHex(52, true) : deviceId;
    }

    public synchronized String deviceId() {
        return this.deviceId;
    }

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange == null ? () -> { } : onChange;
    }

    public synchronized boolean signedIn() {
        String u = this.cookies.get("MUSIC_U");
        return u != null && !u.isEmpty();
    }

    public synchronized boolean hasAnonymousToken() {
        String a = this.cookies.get("MUSIC_A");
        return a != null && !a.isEmpty();
    }

    /** {@code name=value; ...} of every cookie we hold (login + anonymous). */
    public synchronized String cookieString() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : this.cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /** Restores cookies saved by {@link #cookieString()} or returned by a self-hosted API. */
    public void importCookieString(String cookie) {
        synchronized (this) {
            if (cookie == null) return;
            for (String part : cookie.split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String name = part.substring(0, eq).trim(), value = part.substring(eq + 1).trim();
                if (isAttribute(name)) continue;
                if (value.isEmpty()) this.cookies.remove(name);
                else this.cookies.put(name, value);
            }
        }
        this.onChange.run();
    }

    /** Takes the cookies a response set (login, token refresh, anonymous registration). */
    public void applySetCookies(List<String> headers) {
        boolean changed = false;
        synchronized (this) {
            for (String header : headers) {
                String first = header.split(";", 2)[0];
                int eq = first.indexOf('=');
                if (eq <= 0) continue;
                String name = first.substring(0, eq).trim(), value = first.substring(eq + 1).trim();
                String lower = header.toLowerCase();
                boolean expired = value.isEmpty() || lower.contains("max-age=0") || lower.contains("expires=thu, 01 jan 1970");
                if (expired) {
                    changed |= this.cookies.remove(name) != null;
                } else if (!value.equals(this.cookies.get(name))) {
                    this.cookies.put(name, value);
                    changed = true;
                }
            }
        }
        if (changed) this.onChange.run();
    }

    /** Forgets the login (keeps the device id and anonymous token). */
    public void signOut() {
        synchronized (this) {
            this.cookies.remove("MUSIC_U");
            this.cookies.remove("__csrf");
            this.cookies.remove("MUSIC_R_T");
        }
        this.onChange.run();
    }

    synchronized String csrf() {
        String c = this.cookies.get("__csrf");
        return c == null ? "" : c;
    }

    /** Cookie header for weapi (music.163.com) requests. */
    synchronized String weapiCookie(boolean loginEndpoint) {
        Map<String, String> c = new LinkedHashMap<>(this.cookies);
        c.put("__remember_me", "true");
        c.put("ntes_kaola_ad", "1");
        c.put("_ntes_nuid", this.nuid);
        c.put("_ntes_nnid", this.nuid + "," + System.currentTimeMillis());
        c.put("WNMCID", this.wnmcid);
        c.put("WEVNSM", "1.0.0");
        c.put("osver", OS_VER);
        c.put("deviceId", this.deviceId);
        c.put("os", OS);
        c.put("channel", CHANNEL);
        c.put("appver", APP_VER);
        if (!loginEndpoint) c.put("NMTID", NeteaseCrypto.randomHex(32, false));
        if (!this.signedIn()) c.remove("MUSIC_U");
        return join(c);
    }

    /** The {@code header} object an eapi request embeds (and sends as its cookie). */
    synchronized Map<String, String> eapiHeader() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("osver", OS_VER);
        h.put("deviceId", this.deviceId);
        h.put("os", OS);
        h.put("appver", APP_VER);
        h.put("versioncode", "140");
        h.put("mobilename", "");
        h.put("buildver", String.valueOf(System.currentTimeMillis() / 1000));
        h.put("resolution", "1920x1080");
        h.put("__csrf", this.csrf());
        h.put("channel", CHANNEL);
        h.put("requestId", System.currentTimeMillis() + "_" + String.format("%04d", (int) (Math.random() * 1000)));
        String u = this.cookies.get("MUSIC_U"), a = this.cookies.get("MUSIC_A");
        if (u != null && !u.isEmpty()) h.put("MUSIC_U", u);
        if (a != null && !a.isEmpty()) h.put("MUSIC_A", a);
        return h;
    }

    static String headerCookie(Map<String, String> header) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : header.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(MusicHttp.urlEncode(e.getKey())).append('=').append(MusicHttp.urlEncode(e.getValue()));
        }
        return sb.toString();
    }

    private static String join(Map<String, String> c) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : c.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(MusicHttp.urlEncode(e.getKey())).append('=').append(MusicHttp.urlEncode(e.getValue()));
        }
        return sb.toString();
    }

    private static boolean isAttribute(String name) {
        String n = name.toLowerCase();
        return n.equals("path") || n.equals("expires") || n.equals("max-age") || n.equals("domain")
                || n.equals("secure") || n.equals("httponly") || n.equals("samesite");
    }

    private static String randomLetters(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append((char) ('a' + (int) (Math.random() * 26)));
        return sb.toString();
    }
}
