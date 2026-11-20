package net.airplus.music.bm.api;

/** A NetEase request that failed: network error, HTTP error, or a non-success {@code code}. */
public class MusicApiException extends Exception {
    /** NetEase's own result code, or -1 for transport errors. */
    public final int code;

    public MusicApiException(int code, String message) {
        super(message);
        this.code = code;
    }

    public MusicApiException(String message, Throwable cause) {
        super(message, cause);
        this.code = -1;
    }

    /** NetEase says the login is missing or expired. */
    public boolean needsLogin() {
        return this.code == 301 || this.code == 302;
    }

    /** Readable reason for the player's notifications. */
    public String describe() {
        switch (this.code) {
            case -1:
                return "网络错误：" + (getCause() != null && getCause().getMessage() != null ? getCause().getMessage() : getMessage());
            case 301:
            case 302:
                return "需要登录网易云账号";
            case -460:
            case -462:
                return "网易云风控拦截，请稍后再试或登录";
            case 404:
                return "资源不存在";
            default:
                return getMessage() == null ? "请求失败 (" + this.code + ")" : getMessage();
        }
    }
}
