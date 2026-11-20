package net.airplus.music.bm.store;

import org.apache.logging.log4j.Logger;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Base64;

import net.airplus.file.FileManager;
import net.airplus.music.bm.util.BmLog;

/**
 * Protects refresh/access tokens before they are written to disk.
 *
 * On Windows the data is wrapped with DPAPI (CryptProtectData, current user scope)
 * through the JNA platform library that ships with Minecraft 1.8.9. Everywhere else
 * an AES-256-GCM key stored in the client directory is used. Neither scheme stops
 * malware running as the same user, but both stop a copied or shared file from being
 * usable on another machine or account.
 */
public final class TokenCrypto {
    private static final Logger LOGGER = BmLog.LOGGER;
    private static final String DPAPI_PREFIX = "dpapi:";
    private static final String AES_PREFIX = "aes:";
    private static final String KEY_FILE_NAME = "airplus-music.key";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static Method dpapiProtect;
    private static Method dpapiUnprotect;
    private static boolean dpapiChecked;
    private static SecretKey aesKey;

    private TokenCrypto() {
    }

    public static boolean isProtected(String stored) {
        return stored != null && (stored.startsWith(DPAPI_PREFIX) || stored.startsWith(AES_PREFIX));
    }

    public static String protect(String plain) {
        if (plain == null || plain.isEmpty() || isProtected(plain)) {
            return plain == null ? "" : plain;
        }
        byte[] data = plain.getBytes(StandardCharsets.UTF_8);
        try {
            if (dpapiAvailable()) {
                byte[] blob = (byte[]) dpapiProtect.invoke(null, (Object) data);
                return DPAPI_PREFIX + Base64.getEncoder().encodeToString(blob);
            }
        } catch (Exception e) {
            LOGGER.warn("DPAPI protect failed, falling back to AES: {}", e.toString());
        }
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey(), new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(data);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return AES_PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to protect token", e);
        }
    }

    /**
     * @return the plain token, or null when the stored value can no longer be decrypted
     * (different user/machine, lost key). Legacy plaintext values are returned unchanged.
     */
    public static String unprotect(String stored) {
        if (stored == null) {
            return "";
        }
        try {
            if (stored.startsWith(DPAPI_PREFIX)) {
                if (!dpapiAvailable()) {
                    return null;
                }
                byte[] blob = Base64.getDecoder().decode(stored.substring(DPAPI_PREFIX.length()));
                byte[] data = (byte[]) dpapiUnprotect.invoke(null, (Object) blob);
                return new String(data, StandardCharsets.UTF_8);
            }
            if (stored.startsWith(AES_PREFIX)) {
                byte[] in = Base64.getDecoder().decode(stored.substring(AES_PREFIX.length()));
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, aesKey(), new GCMParameterSpec(128, in, 0, 12));
                return new String(cipher.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            LOGGER.warn("Stored token could not be decrypted: {}", e.toString());
            return null;
        }
        return stored;
    }

    private static synchronized boolean dpapiAvailable() {
        if (!dpapiChecked) {
            dpapiChecked = true;
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                try {
                    Class<?> util = Class.forName("com.sun.jna.platform.win32.Crypt32Util");
                    dpapiProtect = util.getMethod("cryptProtectData", byte[].class);
                    dpapiUnprotect = util.getMethod("cryptUnprotectData", byte[].class);
                } catch (Throwable t) {
                    LOGGER.info("DPAPI not available ({}), using AES key file instead", t.toString());
                    dpapiProtect = null;
                    dpapiUnprotect = null;
                }
            }
        }
        return dpapiProtect != null && dpapiUnprotect != null;
    }

    private static synchronized SecretKey aesKey() throws Exception {
        if (aesKey == null) {
            File keyFile = new File(FileManager.INSTANCE.getDir(), KEY_FILE_NAME);
            byte[] raw;
            if (keyFile.exists() && keyFile.length() == 32) {
                raw = Files.readAllBytes(keyFile.toPath());
            } else {
                raw = new byte[32];
                RANDOM.nextBytes(raw);
                Files.write(keyFile.toPath(), raw);
            }
            aesKey = new SecretKeySpec(raw, "AES");
        }
        return aesKey;
    }
}
