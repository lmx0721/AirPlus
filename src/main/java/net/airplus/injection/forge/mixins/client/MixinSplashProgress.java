/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.client;

import net.airplus.AirPlus;
import net.airplus.injection.forge.StartupSplash;
import net.airplus.ui.font.AWTFontRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.client.SplashProgress;
import net.minecraftforge.fml.common.ProgressManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.commons.io.IOUtils;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.Drawable;
import org.lwjgl.opengl.SharedDrawable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.awt.Font;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;

import static org.lwjgl.opengl.GL11.*;

/**
 * 启动加载屏：把 Forge 自带的 Mojang logo splash 换成 Flux 风格加载动画
 * （纯黑背景 + "Loading AirPlus..." 大字 + FML 进度条/进度信息），
 * 加载完成后显示 "Welcome to AirPlus!" 再交给主菜单。
 *
 * 刻意不复用 Fonts/GameFontRenderer（其依赖链 FileManager/NameProtect/
 * vanilla FontRenderer 在 splash 阶段未必就绪），直接用 AWTFontRenderer 的
 * loadingScreen=true 模式（裸 glBindTexture，专为加载屏设计）。
 *
 * Forge 1.8.9 的绘制循环在匿名 Runnable 里无法直接注入，
 * 因此整体覆写 start()/finish()，但完整复刻其 SharedDrawable 上下文交接、
 * lock/mutex 锁语义与 clearGL 状态复位，保证 finish()/pause()/resume() 继续可用。
 */
@Mixin(SplashProgress.class)
@SideOnly(Side.CLIENT)
public abstract class MixinSplashProgress {

    @Shadow
    private static volatile boolean done;

    @Shadow
    private static volatile boolean pause;

    @Shadow
    private static Thread thread;

    @Shadow
    private static volatile Throwable threadError;

    @Shadow
    private static Drawable d;

    @Shadow
    @Final
    private static Lock lock;

    @Shadow
    @Final
    static Semaphore mutex;

    @Shadow
    private static boolean enabled;

    @Shadow
    private static void checkThreadState() {
    }

    @Shadow
    public static int getMaxTextureSize() {
        return -1;
    }

    @Unique
    private static volatile long airplus$welcomeAt = 0L;

    @Unique
    private static volatile boolean airplus$textBroken = false;

    @Unique
    private static AWTFontRenderer airplus$bigFont;

    @Unique
    private static AWTFontRenderer airplus$smallFont;

    /**
     * @author AirPlus
     * @reason Replace Forge's Mojang-logo splash with the Flux-style client loading
     * animation (black background + "Loading AirPlus..." text). Lock/context/thread
     * semantics replicate Forge 1.8.9 SplashProgress#start so finish()/pause()/resume()
     * keep working.
     */
    @Overwrite
    public static void start() {
        // Respect config/splash.properties ("enabled") so users can still opt out.
        File configFile = new File(Minecraft.getMinecraft().mcDataDir, "config/splash.properties");
        Properties config = new Properties();
        FileReader reader = null;
        try {
            reader = new FileReader(configFile);
            config.load(reader);
        } catch (IOException ignored) {
        } finally {
            IOUtils.closeQuietly(reader);
        }

        boolean defaultEnabled = !System.getProperty("os.name").toLowerCase().contains("mac");
        enabled = Boolean.parseBoolean(config.getProperty("enabled", Boolean.toString(defaultEnabled)))
                && (!FMLClientHandler.instance().hasOptifine() || Launch.blackboard.containsKey("optifine.ForgeSplashCompatible"));

        if (!enabled) return;

        try {
            d = new SharedDrawable(Display.getDrawable());
            Display.getDrawable().releaseContext();
            d.makeCurrent();
        } catch (LWJGLException e) {
            e.printStackTrace();
            enabled = false;
            return;
        }

        // Call ASAP if splash is enabled so that threading doesn't cause issues later (Forge original)
        getMaxTextureSize();

        thread = new Thread(new Runnable() {

            private long start = 0L;

            private void setGL() {
                lock.lock();
                try {
                    Display.getDrawable().makeCurrent();
                } catch (LWJGLException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }
                glClearColor(0f, 0f, 0f, 1f);
                glDisable(GL_LIGHTING);
                glDisable(GL_DEPTH_TEST);
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            }

            private void clearGL() {
                Minecraft mc = Minecraft.getMinecraft();
                mc.displayWidth = Display.getWidth();
                mc.displayHeight = Display.getHeight();
                mc.resize(mc.displayWidth, mc.displayHeight);
                glClearColor(1f, 1f, 1f, 1f);
                glEnable(GL_DEPTH_TEST);
                glDepthFunc(GL_LEQUAL);
                glEnable(GL_ALPHA_TEST);
                glAlphaFunc(GL_GREATER, 0.1f);
                try {
                    Display.getDrawable().releaseContext();
                } catch (LWJGLException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                } finally {
                    lock.unlock();
                }
            }

            /** Reads a bundled TTF as an AWT font (same files the main menu fonts use). */
            private Font loadTTF(String resourcePath, float size) {
                try {
                    InputStream stream = AirPlus.class.getResourceAsStream(resourcePath);
                    if (stream == null) {
                        System.out.println("[AirPlus] Splash font resource not found: " + resourcePath);
                        return null;
                    }
                    return Font.createFont(Font.TRUETYPE_FONT, stream).deriveFont(Font.PLAIN, size);
                } catch (Exception e) {
                    System.out.println("[AirPlus] Splash font load failed: " + resourcePath);
                    e.printStackTrace();
                    return null;
                }
            }

            private void initFonts() {
                Font big = loadTTF("/assets/minecraft/airplus/fonts/RobotoLight.ttf", 80f);
                Font small = loadTTF("/assets/minecraft/airplus/fonts/Roboto.ttf", 30f);

                if (big != null)
                    airplus$bigFont = new AWTFontRenderer(big, 0, 65535, true);
                if (small != null)
                    airplus$smallFont = new AWTFontRenderer(small, 0, 65535, true);

                System.out.println("[AirPlus] Splash fonts initialized (big=" + (airplus$bigFont != null)
                        + ", small=" + (airplus$smallFont != null) + ")");
            }

            /**
             * Replicates GameFontRenderer.drawString's matrix composition
             * (translate by (x, y-3+0.5), then AWTFontRenderer.drawString at origin).
             */
            private void drawText(AWTFontRenderer renderer, String text, float x, float y, int color) {
                glPushMatrix();
                glTranslated(x - 1.5, y - 2.5, 0.0);
                renderer.drawString(text, 0.0, 0.0, color);
                glPopMatrix();
            }

            /**
             * AWTFontRenderer pipeline: getStringWidth returns native/2, but the glyphs
             * are drawn at native/4 (internal glScaled(0.25)). Visual width = 0.5 * W,
             * so the correct centering offset is W/4 (GameFontRenderer's own drawCenteredString
             * delegates to AWTFontRenderer units the same way).
             */
            private void drawCenteredText(AWTFontRenderer renderer, String text, int sw, float y, int color) {
                float x = sw / 2f - renderer.getStringWidth(text) / 4f;
                drawText(renderer, text, x, y, color);
            }

            public void run() {
                setGL();
                start = System.currentTimeMillis();
                try {
                    initFonts();
                } catch (Throwable t) {
                    System.out.println("[AirPlus] Splash font init error:");
                    t.printStackTrace();
                }
                while (!done) {
                    try {
                        // Newest FML progress bar (same iteration order as Forge's original loop)
                        ProgressManager.ProgressBar first = null;
                        Iterator<ProgressManager.ProgressBar> i = ProgressManager.barIterator();
                        if (i.hasNext()) first = i.next();

                        glClear(GL_COLOR_BUFFER_BIT);

                        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
                        int sw = sr.getScaledWidth();
                        int sh = sr.getScaledHeight();

                        glViewport(0, 0, Display.getWidth(), Display.getHeight());
                        glMatrixMode(GL_PROJECTION);
                        glLoadIdentity();
                        glOrtho(0, sw, sh, 0, -1, 1);
                        glMatrixMode(GL_MODELVIEW);
                        glLoadIdentity();

                        // Solid black background (Flux loading style)
                        glDisable(GL_TEXTURE_2D);
                        glColor3f(0f, 0f, 0f);
                        glBegin(GL_QUADS);
                        glVertex2f(0, 0);
                        glVertex2f(0, sh);
                        glVertex2f(sw, sh);
                        glVertex2f(sw, 0);
                        glEnd();

                        boolean welcome = airplus$welcomeAt > 0L;

                        // Text (Flux-style fade-in)
                        if (!airplus$textBroken && airplus$bigFont != null) {
                            try {
                                GlStateManager.enableAlpha();
                                GlStateManager.enableBlend();
                                GlStateManager.tryBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO);
                                GlStateManager.enableTexture2D();

                                String title = welcome ? "Welcome To AirPlus!" : "Loading AirPlus...";
                                long at = welcome ? airplus$welcomeAt : start;
                                float alpha = Math.min(1f, (System.currentTimeMillis() - at) / 800f);
                                int titleColor = ((int) (alpha * 255f) << 24) | 0xFFFFFF;
                                drawCenteredText(airplus$bigFont, title, sw, sh / 2f - 24f, titleColor);

                                if (!welcome && airplus$smallFont != null) {
                                    if (first != null) {
                                        String info = first.getTitle() + " - " + first.getMessage()
                                                + " (" + first.getStep() + "/" + first.getSteps() + ")";
                                        drawCenteredText(airplus$smallFont, info, sw, sh / 2f + 24f, 0x90FFFFFF);
                                    }
                                    drawCenteredText(airplus$smallFont, AIRPLUS_BRAND, sw, sh - 24f, 0x60FFFFFF);
                                }

                                GlStateManager.disableTexture2D();
                                GlStateManager.disableBlend();
                                GlStateManager.resetColor();
                            } catch (Throwable t) {
                                System.out.println("[AirPlus] Splash text draw error:");
                                t.printStackTrace();
                                airplus$textBroken = true;
                            }
                        }

                        // Pure-GL progress bar (works even when fonts are broken)
                        if (!welcome && first != null) {
                            float barWidth = sw * 0.4f;
                            float barX = sw * 0.3f;
                            float barY = sh * 0.75f;
                            float barHeight = 3f;

                            // border
                            glColor3f(0.25f, 0.25f, 0.25f);
                            glBegin(GL_QUADS);
                            glVertex2f(barX - 1f, barY - 1f);
                            glVertex2f(barX - 1f, barY + barHeight + 1f);
                            glVertex2f(barX + barWidth + 1f, barY + barHeight + 1f);
                            glVertex2f(barX + barWidth + 1f, barY - 1f);
                            glEnd();

                            // fill
                            float progress = (first.getStep() + 1f) / (first.getSteps() + 1f);
                            glColor3f(1f, 1f, 1f);
                            glBegin(GL_QUADS);
                            glVertex2f(barX, barY);
                            glVertex2f(barX, barY + barHeight);
                            glVertex2f(barX + barWidth * progress, barY + barHeight);
                            glVertex2f(barX + barWidth * progress, barY);
                            glEnd();
                        }

                        // We use mutex to indicate safely to the main thread that we're taking
                        // the display global lock (Forge original comment)
                        mutex.acquireUninterruptibly();
                        Display.update();
                        mutex.release();
                        if (pause) {
                            clearGL();
                            setGL();
                        }
                        Display.sync(100);
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
                clearGL();
                StartupSplash.animationPlayed = true;
            }
        });
        thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                e.printStackTrace();
                threadError = e;
            }
        });
        thread.start();
        checkThreadState();
    }

    /**
     * @author AirPlus
     * @reason Show the "Welcome to AirPlus!" phase once loading finishes, then hand the
     * context back to the main thread.
     */
    @Overwrite
    public static void finish() {
        if (!enabled) return;
        try {
            checkThreadState();
            airplus$welcomeAt = System.currentTimeMillis();
            Thread.sleep(1600L); // let the splash thread draw the welcome phase
            done = true;
            thread.join();
            d.releaseContext();
            Display.getDrawable().makeCurrent();
        } catch (Exception e) {
            e.printStackTrace();
            try {
                d.releaseContext();
                Display.getDrawable().makeCurrent();
            } catch (Exception e2) {
                e2.printStackTrace();
            }
            enabled = false;
        }
    }

    @Unique
    private static final String AIRPLUS_BRAND = "AirPlus 1.8.9";
}
