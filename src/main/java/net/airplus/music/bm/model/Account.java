package net.airplus.music.bm.model;

/** The signed-in NetEase account; {@link #GUEST} when nobody is signed in. */
public final class Account {
    public static final Account GUEST = new Account(0, "", "", 0);

    public final long uid;
    public final String nickname;
    public final String avatarUrl;
    /** NetEase vipType: 0 none, otherwise some kind of VIP. */
    public final int vipType;

    public Account(long uid, String nickname, String avatarUrl, int vipType) {
        this.uid = uid;
        this.nickname = nickname == null ? "" : nickname;
        this.avatarUrl = avatarUrl == null ? "" : avatarUrl;
        this.vipType = vipType;
    }

    public boolean signedIn() {
        return this.uid != 0;
    }

    public boolean vip() {
        return this.vipType != 0;
    }
}
