// skid SetsunaClient
package net.airplus.music.ncm;

/**
 * @author IzumiiKonata
 * Date: 2025/7/2 20:00
 */
public class OptionsUtil {

    private static String COOKIE = "";

    private OptionsUtil() {
    }

    public static void setCookie(String cookie) {
        COOKIE = cookie;
    }

    public static String getCookie() {
        return COOKIE;
    }

    public static RequestUtil.RequestOptions createOptions() {
        return createOptions("");
    }

    public static RequestUtil.RequestOptions createOptions(String crypto) {
        return RequestUtil.RequestOptions.builder()
                .crypto(crypto)
                .cookie(COOKIE)
                .ua("")
                .proxy("")
                .encryptedResponse(null)
                .build();
    }

}
