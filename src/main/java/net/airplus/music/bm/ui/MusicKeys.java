package net.airplus.music.bm.ui;

import net.airplus.music.bm.util.BmLog;
import net.airplus.music.bm.MusicService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Player hotkeys in the vanilla Controls menu, category "AirPlus 音乐" (all unbound by
 * default). They are registered after Minecraft read options.txt, so the saved keys are read back
 * here; Minecraft saves them itself from then on. Names are plain text (there is no language
 * file), which the Controls menu shows as they are.
 */
public final class MusicKeys {
    private static final String CATEGORY = "AirPlus 音乐";
    private static KeyBinding playPause, next, previous, volumeUp, volumeDown, like;
    private static boolean registered;

    private MusicKeys() {
    }

    public static void init() {
        if (registered) return;
        registered = true;
        try {
            playPause = bind("音乐：播放 / 暂停");
            next = bind("音乐：下一首");
            previous = bind("音乐：上一首");
            volumeUp = bind("音乐：音量 +");
            volumeDown = bind("音乐：音量 -");
            like = bind("音乐：红心当前歌曲");
            restoreSavedKeys();
        } catch (RuntimeException | LinkageError e) {
            BmLog.LOGGER.warn("[Music] Could not register hotkeys", e);
        }
    }

    private static KeyBinding bind(String name) {
        KeyBinding kb = new KeyBinding(name, 0, CATEGORY);
        ClientRegistry.registerKeyBinding(kb);
        return kb;
    }

    /** Reads our "key_..." lines from options.txt the way GameSettings writes them. */
    private static void restoreSavedKeys() {
        File options = new File(Minecraft.getMinecraft().mcDataDir, "options.txt");
        if (!options.isFile()) return;
        KeyBinding[] ours = {playPause, next, previous, volumeUp, volumeDown, like};
        // GameSettings uses FileReader / FileWriter (the platform charset), so the same here.
        try (BufferedReader in = new BufferedReader(new FileReader(options))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.startsWith("key_")) continue;
                int colon = line.lastIndexOf(':');
                if (colon < 0) continue;
                String name = line.substring(4, colon);
                for (KeyBinding kb : ours) {
                    if (kb.getKeyDescription().equals(name)) {
                        try {
                            kb.setKeyCode(Integer.parseInt(line.substring(colon + 1).trim()));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        } catch (IOException e) {
            BmLog.LOGGER.warn("[Music] Could not read hotkeys from options.txt: {}", e.toString());
        }
        KeyBinding.resetKeyBindingArrayAndHash();
    }

    /** Once per client tick (in a world). */
    public static void tick() {
        if (!registered || playPause == null || !MusicService.running()) return;
        MusicService m = MusicService.get();
        while (playPause.isPressed()) m.togglePause();
        while (next.isPressed()) m.next();
        while (previous.isPressed()) m.previous();
        while (volumeUp.isPressed()) m.changeVolume(5);
        while (volumeDown.isPressed()) m.changeVolume(-5);
        while (like.isPressed()) m.toggleLike(m.current());
    }
}
