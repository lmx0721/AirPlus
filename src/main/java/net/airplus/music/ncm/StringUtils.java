// skid SetsunaClient
package net.airplus.music.ncm;

/**
 * @author IzumiiKonata
 * Date: 2025/7/2 19:54
 */
public class StringUtils {

    private StringUtils() {
    }

    public static boolean isBlank(CharSequence cs) {
        int strLen;
        if (cs != null && (strLen = cs.length()) != 0) {
            for (int i = 0; i < strLen; ++i) {
                if (!Character.isWhitespace(cs.charAt(i))) {
                    return false;
                }
            }

            return true;
        } else {
            return true;
        }
    }

    public static boolean isNotBlank(CharSequence cs) {
        return !isBlank(cs);
    }

}
