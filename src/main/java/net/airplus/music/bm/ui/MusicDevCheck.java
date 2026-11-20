package net.airplus.music.bm.ui;

import net.airplus.music.bm.util.BmLog;
import net.airplus.music.bm.MusicService;
import net.airplus.music.bm.model.Track;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

import java.io.File;
import java.util.Collections;

/**
 * Developer tool for the music player, inert unless the environment variable
 * {@code MYAU_MUSICCHECK} names a directory. It then opens a flat test world, plays a free NetEase
 * song, and saves screenshots of the island capsule (peek, compact, lyrics, paused) and of the
 * music screen (queue, search, lyrics, QR sign-in) into that directory, logging the playhead as it
 * goes so the log shows whether audio really advances; then it quits.
 */
public final class MusicDevCheck {
    private static final String DIR = System.getenv("MYAU_MUSICCHECK");
    public static final boolean ON = DIR != null && !DIR.trim().isEmpty();
    /** "晴天 (弦乐版)": free, no login needed (checked with the API probe). */
    private static final long TEST_SONG = 3455095614L;
    private static final Minecraft mc = Minecraft.getMinecraft();

    private static int stage;
    private static long stageAt;
    private static MusicScreen screen;
    private static long lastLog;

    private MusicDevCheck() {
    }

    /** Start of every frame (main thread). */
    public static void frame() {
        if (!ON) return;
        try {
            run();
        } catch (RuntimeException e) {
            BmLog.LOGGER.warn("[MusicCheck] step {} failed", stage, e);
            next();
        }
    }

    private static float elapsed() {
        return (System.nanoTime() - stageAt) / 1e9f;
    }

    private static void run() {
        MusicService m = MusicService.get();
        float t = elapsed();
        switch (stage) {
            case 0:
                if (mc.currentScreen instanceof GuiMainMenu) {
                    if (stageAt == 0) stageAt = System.nanoTime();
                    else if (t > 3) {
                        next();
                        mc.gameSettings.pauseOnLostFocus = false;
                        WorldSettings settings = new WorldSettings(0L, WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT);
                        settings.enableCommands();
                        mc.launchIntegratedServer("musiccheck", "musiccheck", settings);
                    }
                }
                break;
            case 1:
                if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) {
                    stageAt = System.nanoTime();
                } else if (t > 3) {
                    mc.thePlayer.sendChatMessage("/time set 6000");
                    mc.thePlayer.sendChatMessage("/gamerule doDaylightCycle false");
                    log("in world, loading the test song");
                    MusicService.onMain(m.async(() -> m.backend().songDetail(Collections.singletonList(TEST_SONG))), (list, err) -> {
                        if (err != null || list == null || list.isEmpty()) {
                            log("song detail failed: " + err);
                            return;
                        }
                        m.playList(list, 0, "MusicCheck");
                    });
                    next();
                }
                break;
            case 2:
                if (m.isPlaying()) {
                    log("playing after " + t + " s");
                    next();
                } else if (t > 25) {
                    log("never started playing: state=" + m.engineState() + " status=" + m.status());
                    next();
                }
                break;
            case 3:
                if (t > 1.2f) shot("m01-island-peek");
                break;
            case 4:
                if (t > 6f) {
                    shot("m02-island-compact");
                    m.seek(40_000);
                }
                break;
            case 5:
                if (t > 3f) {
                    shot("m03-island-lyrics");
                    screen = new MusicScreen();
                    screen.show(screen.queuePage());
                    mc.displayGuiScreen(screen);
                }
                break;
            case 6:
                if (t > 2f) {
                    shot("m04-screen-queue");
                    screen.search.set("周杰伦");
                    screen.devSearch("周杰伦");
                }
                break;
            case 7:
                if (t > 4f) {
                    shot("m05-screen-search");
                    screen.lyricsShown = true;
                }
                break;
            case 8:
                if (t > 2.5f) {
                    shot("m06-screen-lyrics");
                    screen.lyricsShown = false;
                    screen.login.open();
                }
                break;
            case 9:
                if (t > 3f) {
                    shot("m07-screen-login");
                    screen.login.close();
                    mc.displayGuiScreen(null);
                    m.setPaused(true);
                }
                break;
            case 10:
                if (t > 1.5f) {
                    shot("m08-island-paused");
                    m.setPaused(false);
                }
                break;
            case 11:
                if (t > 2f) {
                    log("done; quitting");
                    stage = Integer.MIN_VALUE;
                    mc.shutdown();
                }
                break;
            default:
                break;
        }
        if (stage >= 2 && stage < 12 && System.nanoTime() - lastLog > 2_000_000_000L) {
            lastLog = System.nanoTime();
            Track cur = m.current();
            log(String.format("state=%s pos=%d/%d bass=%.2f level=%.2f lyrics=%d track=%s", m.engineState(), m.positionMs(),
                    m.durationMs(), m.spectrum().bass(), m.spectrum().level(), m.lyrics().lines.size(), cur));
        }
    }

    private static void next() {
        stage++;
        stageAt = System.nanoTime();
    }

    private static void shot(String name) {
        try {
            File dir = new File(DIR.trim());
            dir.mkdirs();
            ScreenShotHelper.saveScreenshot(dir, name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            log("saved " + name);
        } catch (Throwable e) {
            BmLog.LOGGER.warn("[MusicCheck] screenshot {} failed", name, e);
        }
        next();
    }

    private static void log(String s) {
        BmLog.LOGGER.info("[MusicCheck] {}", s);
    }
}
