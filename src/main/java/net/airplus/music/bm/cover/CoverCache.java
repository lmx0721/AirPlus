package net.airplus.music.bm.cover;

import net.airplus.music.bm.util.BmLog;
import net.airplus.music.bm.api.MusicHttp;
import net.airplus.music.bm.api.NeteaseCrypto;
import net.airplus.music.bm.store.MusicStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

/**
 * Album art (and avatars) as GL textures. {@link #get} returns at once; the image is fetched
 * (disk cache first, then the network) and scaled on an IO thread, uploaded on the main thread,
 * and its accent colour taken for the glow. Textures are scaled on the CPU to the size asked for
 * and filtered linearly, so small thumbnails stay smooth.
 */
public final class CoverCache {
    private static final int MAX_TEXTURES = 96;
    private static final int MAX_DISK_FILES = 600;
    private static final int MAX_BYTES = 4 << 20;

    /** One cover at one size. Fields are written on the main thread. */
    public static final class Cover {
        /** GL texture id, 0 until uploaded. */
        public volatile int texture;
        /** Accent colour (opaque RGB), {@link Palette#FALLBACK} until known. */
        public volatile int color = Palette.FALLBACK;
        public volatile boolean failed;
        /** When the texture arrived (nanoTime), for a fade-in. */
        public volatile long readyNanos;
        DynamicTexture tex;
        boolean requested;

        public boolean ready() {
            return this.texture != 0;
        }

        /** 0..1 over a quarter second after the texture arrived. */
        public float appear() {
            if (this.texture == 0) return 0;
            return Math.min(1, (System.nanoTime() - this.readyNanos) / 250_000_000f);
        }
    }

    private final ExecutorService io;
    private final LinkedHashMap<String, Cover> covers = new LinkedHashMap<>(64, 0.75f, true);

    public CoverCache(ExecutorService io) {
        this.io = io;
        this.io.execute(CoverCache::pruneDisk);
    }

    /** NetEase picture at {@code size}×{@code size} (the CDN resizes it). Main thread. */
    public Cover remote(String url, int size) {
        if (url == null || url.isEmpty()) return null;
        String full = url.contains("?") ? url : url + "?param=" + size + "y" + size;
        String key = full + "#" + size;
        return this.get(key, size, () -> {
            File cached = new File(MusicStore.cacheDir(), NeteaseCrypto.md5Hex(full) + ".img");
            if (cached.isFile() && cached.length() > 0) return Files.readAllBytes(cached.toPath());
            byte[] bytes = MusicHttp.download(full, MAX_BYTES);
            try {
                Files.write(cached.toPath(), bytes);
            } catch (IOException ignored) {
            }
            return bytes;
        });
    }

    /** Image bytes from somewhere else (a local file's embedded art). Main thread. */
    public Cover get(String key, int size, Callable<byte[]> bytes) {
        String full = key + "#" + size;
        Cover c = this.covers.get(full);
        if (c == null) {
            c = new Cover();
            this.covers.put(full, c);
            this.evict();
        }
        if (!c.requested) {
            c.requested = true;
            Cover target = c;
            this.io.execute(() -> this.load(target, size, bytes));
        }
        return c;
    }

    private void load(Cover cover, int size, Callable<byte[]> source) {
        BufferedImage scaled;
        int color;
        try {
            byte[] bytes = source.call();
            if (bytes == null || bytes.length == 0) throw new IOException("no image");
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) throw new IOException("unsupported image");
            scaled = scale(image, size);
            color = Palette.accent(scaled);
        } catch (Exception e) {
            cover.failed = true;
            return;
        }
        Minecraft.getMinecraft().addScheduledTask(() -> {
            try {
                DynamicTexture tex = new DynamicTexture(scaled);
                GlStateManager.bindTexture(tex.getGlTextureId());
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
                cover.tex = tex;
                cover.color = color;
                cover.readyNanos = System.nanoTime();
                cover.texture = tex.getGlTextureId();
            } catch (RuntimeException e) {
                cover.failed = true;
                BmLog.LOGGER.warn("[Music] Cover upload failed", e);
            }
        });
    }

    private void evict() {
        if (this.covers.size() <= MAX_TEXTURES) return;
        Iterator<Map.Entry<String, Cover>> it = this.covers.entrySet().iterator();
        while (this.covers.size() > MAX_TEXTURES && it.hasNext()) {
            Cover c = it.next().getValue();
            it.remove();
            if (c.tex != null) c.tex.deleteGlTexture();
            c.texture = 0;
        }
    }

    private static BufferedImage scale(BufferedImage src, int size) {
        // Centre-crop to a square, then a high-quality downscale (in steps for big shrinks).
        int side = Math.min(src.getWidth(), src.getHeight());
        BufferedImage cur = src.getSubimage((src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        int w = side;
        do {
            w = Math.max(size, w / 2);
            if (cur.getWidth() <= size) w = size;
            BufferedImage next = new BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(cur, 0, 0, w, w, null);
            g.dispose();
            cur = next;
        } while (w > size);
        return cur;
    }

    private static void pruneDisk() {
        File[] files = MusicStore.cacheDir().listFiles();
        if (files == null || files.length <= MAX_DISK_FILES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - MAX_DISK_FILES; i++) {
            if (!files[i].delete()) files[i].deleteOnExit();
        }
    }
}
