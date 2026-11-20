package net.airplus.music.bm.ui;

import net.airplus.music.bm.MusicService;
import net.airplus.music.bm.api.MusicBackend;
import net.airplus.ui.glass.GlassTheme;
import net.airplus.ui.glass.Anim;
import net.airplus.ui.glass.Draw;
import net.airplus.ui.glass.SmoothFont;
import net.airplus.ui.glass.UiSound;

/**
 * NetEase QR sign-in over the music screen: a QR code made locally from the sign-in key, scanned
 * with the NetEase Cloud Music app; the status is polled every two seconds until it is confirmed
 * on the phone or expires. The resulting cookie stays in the session (stored encrypted).
 */
final class LoginOverlay {
    private enum State { LOADING, WAITING, SCANNED, DONE, EXPIRED, ERROR }

    private static final float W = 236, H = 280;

    private final MusicScreen screen;
    private final QrTexture qr = new QrTexture();
    private final Anim.Smooth show = new Anim.Smooth(0, 14);
    private boolean open;
    /** True while the overlay draws itself (so its own hover checks are not blocked). */
    boolean drawing;
    private State state = State.LOADING;
    private String key = "", content = "", error = "";
    private long lastPoll;
    private boolean polling;
    private int serial;
    private float cardX, cardY;

    LoginOverlay(MusicScreen screen) {
        this.screen = screen;
    }

    boolean visible() {
        return this.open;
    }

    void open() {
        if (this.open) return;
        this.open = true;
        UiSound.OPEN.play();
        this.refresh();
    }

    void close() {
        this.open = false;
        this.serial++;
        this.polling = false;
    }

    boolean blocks(float x, float y) {
        return this.open;
    }

    boolean inside(float x, float y) {
        return x >= this.cardX && x < this.cardX + W && y >= this.cardY && y < this.cardY + H;
    }

    boolean owns(float x0, float y0, float x1, float y1) {
        return x0 >= this.cardX - 0.5f && y0 >= this.cardY - 0.5f && x1 <= this.cardX + W + 0.5f && y1 <= this.cardY + H + 0.5f;
    }

    private void refresh() {
        int s = ++this.serial;
        this.state = State.LOADING;
        this.error = "";
        this.key = "";
        MusicService music = this.screen.music;
        MusicBackend backend = music.backend();
        MusicService.onMain(music.async(backend::qrKey), (k, err) -> {
            if (s != this.serial) return;
            if (err != null) {
                this.state = State.ERROR;
                this.error = err;
                return;
            }
            this.key = k;
            this.content = backend.qrContent(k);
            this.state = State.WAITING;
            this.lastPoll = System.currentTimeMillis();
        });
    }

    private void poll() {
        if (this.polling || this.key.isEmpty()) return;
        if (this.state != State.WAITING && this.state != State.SCANNED) return;
        if (System.currentTimeMillis() - this.lastPoll < 2000) return;
        this.polling = true;
        this.lastPoll = System.currentTimeMillis();
        int s = this.serial;
        String k = this.key;
        MusicService music = this.screen.music;
        MusicService.onMain(music.async(() -> music.backend().qrCheck(k)), (status, err) -> {
            this.polling = false;
            if (s != this.serial || err != null || status == null) return;
            switch (status) {
                case SCANNED:
                    if (this.state != State.SCANNED) UiSound.NOTIFY.play();
                    this.state = State.SCANNED;
                    break;
                case EXPIRED:
                    this.state = State.EXPIRED;
                    break;
                case CONFIRMED:
                    this.state = State.DONE;
                    MusicService.onMain(music.refreshAccount(), (acc, e) -> {
                        UiSound.SUCCESS.play();
                        this.screen.toast(acc != null && acc.signedIn() ? "欢迎，" + acc.nickname : "已登录");
                        this.close();
                    });
                    break;
                default:
                    break;
            }
        });
    }

    void draw() {
        float k = this.show.update(this.open ? 1 : 0, this.screen.dt);
        if (k < 0.01f) {
            if (!this.open) this.qr.release();
            return;
        }
        this.poll();
        MusicScreen s = this.screen;
        this.drawing = true;
        try {
            Draw.pushAlpha(Anim.outCubic(k));
            Draw.rect(s.px, s.py, s.pw, s.ph, GlassTheme.R_PANEL, 0x8c050608);
            this.cardX = s.px + (s.pw - W) / 2;
            this.cardY = s.py + (s.ph - H) / 2;
            float x = this.cardX, y = this.cardY;
            Draw.pushTransform(x + W / 2, y + H / 2, 0.96f + 0.04f * Anim.outBack(k), 0, 6 * (1 - k));
            GlassTheme.panel(x, y, W, H, GlassTheme.R_PANEL, 1.6f);
            SmoothFont title = SmoothFont.bold(10.5f), small = SmoothFont.regular(7.5f);
            title.drawCentered("扫码登录网易云音乐", x + W / 2, y + 14, GlassTheme.TEXT);

            // Close
            float cx = x + W - 16, cy = y + 18;
            boolean overClose = s.over(cx - 7, cy - 7, 14, 14);
            if (overClose) Draw.circle(cx, cy, 7, 0x22ffffff);
            Draw.polyline(1.1f, 0xccffffff, cx - 3, cy - 3, cx + 3, cy + 3);
            Draw.polyline(1.1f, 0xccffffff, cx + 3, cy - 3, cx - 3, cy + 3);
            s.hit(cx - 7, cy - 7, 14, 14, (b, px, py) -> this.close());

            // QR code on a white card
            float q = 160, qx = x + (W - q) / 2, qy = y + 38;
            Draw.rect(qx - 8, qy - 8, q + 16, q + 16, 12, 0xffffffff);
            if (this.state == State.LOADING) {
                MusicIcons.spinner(qx + q / 2, qy + q / 2, 9, System.nanoTime() / 1e9f, 0xff3a3d44);
            } else if (this.state == State.ERROR) {
                SmoothFont f = SmoothFont.regular(7.5f);
                f.drawCentered("获取二维码失败", qx + q / 2, qy + q / 2 - 10, 0xff3a3d44);
                f.drawCentered(f.trim(this.error, q), qx + q / 2, qy + q / 2 + 2, 0xff70757f);
            } else {
                int tex = this.qr.texture(this.content);
                Draw.image(qx, qy, q, q, 0, tex, 0, 0, 1, 1);
                if (this.state == State.EXPIRED || this.state == State.SCANNED || this.state == State.DONE) {
                    Draw.rect(qx - 8, qy - 8, q + 16, q + 16, 12, 0xd8ffffff);
                    SmoothFont big = SmoothFont.bold(9);
                    String msg = this.state == State.EXPIRED ? "二维码已过期" : this.state == State.DONE ? "登录成功" : "已扫码";
                    big.drawCentered(msg, qx + q / 2, qy + q / 2 - 12, 0xff1b1d22);
                    SmoothFont.regular(7).drawCentered(this.state == State.SCANNED ? "请在手机上确认登录" : "", qx + q / 2, qy + q / 2 + 2, 0xff5d626c);
                }
            }

            // Status and actions
            String status;
            switch (this.state) {
                case LOADING:
                    status = "正在获取二维码…";
                    break;
                case WAITING:
                    status = "打开网易云音乐 App，扫一扫";
                    break;
                case SCANNED:
                    status = "已扫码，请在手机上点「确认登录」";
                    break;
                case DONE:
                    status = "登录成功，正在同步账号…";
                    break;
                case EXPIRED:
                    status = "二维码已过期";
                    break;
                default:
                    status = "出错了，可以重试";
                    break;
            }
            small.drawCentered(status, x + W / 2, qy + q + 18, this.state == State.SCANNED ? GlassTheme.OK : GlassTheme.MUTED);
            if (this.state == State.EXPIRED || this.state == State.ERROR) {
                float bw = SmoothFont.bold(7.5f).width("刷新二维码") + 20;
                s.button("qr-refresh", "刷新二维码", x + (W - bw) / 2, qy + q + 32, 20, true, this::refresh);
            } else {
                SmoothFont.regular(6.5f).drawCentered("登录信息加密保存在本机，不会写进配置文件", x + W / 2, qy + q + 34, GlassTheme.FAINT);
            }
            // The card swallows clicks that hit nothing in it.
            Draw.popTransform();
            Draw.popAlpha();
        } finally {
            this.drawing = false;
        }
    }
}
