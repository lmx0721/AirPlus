package net.airplus.music.bm.api;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NetEase Cloud Music request encryption, ported from NeteaseCloudMusicApiEnhanced
 * ({@code util/crypto.js}, MIT). Pure functions; the keys below are the public client constants
 * every NetEase client ships with.
 *
 * <ul>
 *   <li><b>weapi</b> (web): AES-128-CBC twice (fixed preset key, then a random 16-char key),
 *       the random key reversed and RSA-encrypted without padding.</li>
 *   <li><b>eapi</b> (desktop/mobile): {@code uri-36cd479b6b5-json-36cd479b6b5-md5}, AES-128-ECB,
 *       upper-case hex.</li>
 *   <li><b>xeapi</b> (X25519 + AES-GCM) is not ported: Java 8 has no X25519. If NetEase ever moves
 *       an endpoint we need to it, switch to a self-hosted API (see docs/音乐播放器.md).</li>
 * </ul>
 */
public final class NeteaseCrypto {
    private static final byte[] IV = "0102030405060708".getBytes(StandardCharsets.UTF_8);
    private static final String PRESET_KEY = "0CoJUm6Qyw8W8jud";
    private static final String EAPI_KEY = "e82ckenh8dichen8";
    private static final String BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String PUBLIC_KEY =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile RSAPublicKey rsaKey;

    private NeteaseCrypto() {
    }

    /** Form fields {@code params} and {@code encSecKey} for a weapi request body. */
    public static Map<String, String> weapi(String json) {
        StringBuilder secret = new StringBuilder(16);
        for (int i = 0; i < 16; i++) secret.append(BASE62.charAt(RANDOM.nextInt(62)));
        String key = secret.toString();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("params", aesCbcBase64(aesCbcBase64(json, PRESET_KEY), key));
        form.put("encSecKey", rsaNoPadding(secret.reverse().toString()));
        return form;
    }

    /** Form field {@code params} for an eapi request to {@code uri} (the {@code /api/...} path). */
    public static Map<String, String> eapi(String uri, String json) {
        String digest = md5Hex("nobody" + uri + "use" + json + "md5forencrypt");
        String data = uri + "-36cd479b6b5-" + json + "-36cd479b6b5-" + digest;
        Map<String, String> form = new LinkedHashMap<>();
        form.put("params", hexUpper(aes("AES/ECB/PKCS5Padding", EAPI_KEY, null, data.getBytes(StandardCharsets.UTF_8))));
        return form;
    }

    public static String md5Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    /** Random upper-case hex, e.g. a 52-character device id like the official clients use. */
    public static String randomHex(int length, boolean upper) {
        String chars = upper ? "0123456789ABCDEF" : "0123456789abcdef";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(chars.charAt(RANDOM.nextInt(16)));
        return sb.toString();
    }

    private static String aesCbcBase64(String text, String key) {
        byte[] out = aes("AES/CBC/PKCS5Padding", key, IV, text.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(out);
    }

    private static byte[] aes(String transformation, String key, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(transformation);
            SecretKeySpec spec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
            if (iv != null) cipher.init(Cipher.ENCRYPT_MODE, spec, new IvParameterSpec(iv));
            else cipher.init(Cipher.ENCRYPT_MODE, spec);
            return cipher.doFinal(input);
        } catch (Exception e) {
            throw new IllegalStateException("AES failed", e);
        }
    }

    /** Textbook RSA (no padding), hex, left-padded to the modulus size like node-forge does. */
    private static String rsaNoPadding(String text) {
        RSAPublicKey key = publicKey();
        BigInteger m = new BigInteger(1, text.getBytes(StandardCharsets.UTF_8));
        String hex = m.modPow(key.getPublicExponent(), key.getModulus()).toString(16);
        int width = (key.getModulus().bitLength() + 7) / 8 * 2;
        StringBuilder sb = new StringBuilder(width);
        for (int i = hex.length(); i < width; i++) sb.append('0');
        return sb.append(hex).toString();
    }

    private static RSAPublicKey publicKey() {
        RSAPublicKey key = rsaKey;
        if (key == null) {
            try {
                byte[] der = Base64.getDecoder().decode(PUBLIC_KEY);
                key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
                rsaKey = key;
            } catch (Exception e) {
                throw new IllegalStateException("NetEase public key unreadable", e);
            }
        }
        return key;
    }

    private static String hexUpper(byte[] bytes) {
        char[] digits = "0123456789ABCDEF".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = digits[(bytes[i] >> 4) & 15];
            out[i * 2 + 1] = digits[bytes[i] & 15];
        }
        return new String(out);
    }
}
