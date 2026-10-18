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
    private static volatile boolean airplus$textBroken = false;

    @Unique
    private static AWTFontRenderer airplus$bigFont;

    @Unique
    private static AWTFontRenderer airplus$smallFont;

    /** 非 0 时 splash 线程切换到欢迎动画阶段（由 finish() 设置） */
    @Unique
    private static volatile long airplus$welcomeAt = 0L;

    /** 加载阶段最后一条 FML 步骤文字，欢迎开头快速淡出用 */
    @Unique
    private static String airplus$lastStepText = null;

    // 欢迎动画时间轴（ms），对齐参考稿（hero 居中锚定 + "Welcome to" 宽度展开滑入）：
    // 0-400     进度条居中收缩消失（+步骤文字快速淡出）
    // 400-1000  AirPlus 字标（共享元素）从靠上位置移到屏幕正中
    // 1000-1900 停留 900ms（参考稿 run() 的 sleep(900)）
    // 1900-2750 "Welcome to" 宽度 0→满、margin 0→gap、translateX slide→0
    //           （.85s cubic-bezier(.22,1,.36,1)，AirPlus 被推向右侧、整组保持居中）
    // 2750-3700 保持完整 "Welcome to AirPlus"，然后交还主线程（主菜单淡入，
    //           原 splash 淡出已移除，避免双重淡入淡出）
    @Unique
    private static final long WELCOME_A_END = 400L;
    @Unique
    private static final long WELCOME_B_END = 1000L;
    @Unique
    private static final long WELCOME_C_START = 1900L;
    @Unique
    private static final long WELCOME_C_END = 2750L;
    @Unique
    private static final long WELCOME_TOTAL = 3700L;

    // 参考稿 46px 字号的 margin-right 18px / translateX 48px，
    // 按本实现字标视觉 em（Roboto 80f × 0.25 管线缩放 = 20px）等比缩放
    @Unique
    private static final float WELCOME_GAP = 7.83f;
    @Unique
    private static final float WELCOME_SLIDE = 20.87f;

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

            /**
             * easeInOutCubic 缓动（共享元素移动）。
             */
            private float easeInOutCubic(float x) {
                float v = Math.max(0f, Math.min(1f, x));
                return v < 0.5f
                        ? 4f * v * v * v
                        : 1f - (float) Math.pow(-2f * v + 2f, 3) / 2f;
            }

            /**
             * CSS cubic-bezier(x1,y1,x2,y2) 缓动（参考稿 transition 用 .22,1,.36,1）。
             * 用参数 t 的 x 分量二分求根，再取 y 分量。
             */
            private float cssCubicBezier(float p, float x1, float y1, float x2, float y2) {
                float x = Math.max(0f, Math.min(1f, p));
                float lo = 0f, hi = 1f;
                for (int i = 0; i < 24; i++) {
                    float mid = (lo + hi) / 2f;
                    float bx = 3f * (1f - mid) * (1f - mid) * mid * x1
                            + 3f * (1f - mid) * mid * mid * x2 + mid * mid * mid;
                    if (bx < x) lo = mid; else hi = mid;
                }
                float t = (lo + hi) / 2f;
                return 3f * (1f - t) * (1f - t) * t * y1
                        + 3f * (1f - t) * t * t * y2 + t * t * t;
            }

            /**
             * 欢迎动画（finish() 之后在 splash 线程上播放），参考稿实现：
             * 进度条居中收缩 → 字标（共享元素）移到屏幕正中 → 停留 900ms →
             * "Welcome to" 宽度从 0 展开 + translateX 滑入，把 AirPlus 推向右侧
             * （hero 以 flex 居中锚定，整组始终居中）→ 保持 → 交还主线程（主菜单淡入）。
             */
            private void drawWelcome(int sw, int sh, ScaledResolution sr, long t, long now) {
                // 淡出已移除：欢迎画面始终保持完全不透明，过渡交给主菜单的淡入
                float fade = 1f;
                int alpha255 = (int) (fade * 255f);
                int textColor = (alpha255 << 24) | 0x00FFFFFF;

                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO);
                GlStateManager.enableAlpha();
                GlStateManager.enableTexture2D();

                // Phase A：进度条居中收缩消失（起点与加载帧一致：barX=sw*0.3, width=sw*0.4）
                if (t < WELCOME_A_END) {
                    float p = easeInOutCubic(t / (float) WELCOME_A_END);
                    float barW = sw * 0.4f * (1f - p);
                    float barX = sw / 2f - barW / 2f;
                    float barY = sh * 0.30f + 74f;
                    float barHeight = 3f;

                    GlStateManager.disableTexture2D();
                    glColor4f(0.25f, 0.25f, 0.25f, fade);
                    glBegin(GL_QUADS);
                    glVertex2f(barX - 1f, barY - 1f);
                    glVertex2f(barX - 1f, barY + barHeight + 1f);
                    glVertex2f(barX + barW + 1f, barY + barHeight + 1f);
                    glVertex2f(barX + barW + 1f, barY - 1f);
                    glEnd();
                    glColor4f(1f, 1f, 1f, fade);
                    glBegin(GL_QUADS);
                    glVertex2f(barX, barY);
                    glVertex2f(barX, barY + barHeight);
                    glVertex2f(barX + barW, barY + barHeight);
                    glVertex2f(barX + barW, barY);
                    glEnd();
                    GlStateManager.enableTexture2D();

                    // 步骤文字快速淡出
                    if (airplus$lastStepText != null && airplus$smallFont != null) {
                        float a = 1f - t / 250f;
                        if (a > 0f) {
                            drawCenteredText(airplus$smallFont, airplus$lastStepText, sw,
                                    sh * 0.30f + 52f, ((int) (a * 255f) << 24) | 0x00FFFFFF);
                        }
                    }
                }

                if (airplus$bigFont != null) {
                    // Phase B：竖直方向移到屏幕正中；悬浮幅度随 B 相位衰减衔接加载帧
                    // titleY 的 em 行盒居中公式：drawY = 中心 + 2.75 - visualLineHeight/2
                    float tB = easeInOutCubic((t - WELCOME_A_END) / (float) (WELCOME_B_END - WELCOME_A_END));
                    float bob = (float) Math.sin(now / 550.0) * 2.2f * (1f - tB);
                    float titleYStart = sh * 0.30f;
                    float titleYEnd = sh / 2f + 2.75f - airplus$bigFont.getVisualLineHeight() / 2f;
                    float titleY = titleYStart + (titleYEnd - titleYStart) * tB + bob;

                    // 品牌几何（AWTFontRenderer 视觉宽度 = getStringWidth/2）
                    float vwTitle = airplus$bigFont.getStringWidth("AirPlus") / 2f;
                    float vwPrefix = airplus$bigFont.getStringWidth("Welcome to") / 2f;

                    if (t >= WELCOME_C_START && vwPrefix > 0f) {
                        // Phase C：参考稿 —— hero 以 flex 居中锚定，"Welcome to" 宽度从 0 展开
                        // （margin-right 同步展开）把 AirPlus 推向右侧，整组始终保持居中；
                        // 同时盒体 translateX 从右滑入归位
                        float e = cssCubicBezier((t - WELCOME_C_START) / (float) (WELCOME_C_END - WELCOME_C_START),
                                0.22f, 1f, 0.36f, 1f);
                        float wCur = vwPrefix * e;
                        float marginCur = WELCOME_GAP * e;
                        float tx = WELCOME_SLIDE * (1f - e);
                        float heroLeft = sw / 2f - (wCur + marginCur + vwTitle) / 2f;
                        float brandLeft = heroLeft + wCur + marginCur;

                        // "Welcome to"：盒左缘 heroLeft+tx，overflow clip [heroLeft+tx, +wCur]
                        //（与 CSS transform + overflow:hidden 行为一致：裁剪框随盒体一起平移）
                        float prefixX = heroLeft + tx;
                        // opacity .6s ease（CSS ease 用 smoothstep 近似）
                        float o = Math.max(0f, Math.min(1f, (t - WELCOME_C_START) / 600f));
                        o = o * o * (3f - 2f * o);
                        int opAlpha = (int) (alpha255 * o);

                        float vlh = airplus$bigFont.getVisualLineHeight();
                        float emTop = titleY - 2.75f;
                        // scissor 用窗口像素坐标，必须按真实 Display 尺寸换算，且水平/垂直各用
                        // 自己的 factor：窗口在加载期间被调整过或 guiScale 非整数时
                        // mc.displayWidth/Height 与 Display 尺寸不同步，单一 factor 会把裁剪框
                        // 压窄/错位，末尾 "to" 就被裁掉一角（其余走矩阵的绘制不受影响）
                        float pxPerUnitX = Display.getWidth() / (float) sw;
                        float pxPerUnitY = Display.getHeight() / (float) sh;
                        // 左缘前移 1.5f（drawText 内部 x-1.5 平移）；右缘始终贴到 AirPlus 字标
                        // 左缘（"A" 的起点，+1.5f 抵消 drawText 平移）——reveal 线固定贴着字标，
                        // "to" 随滑入从字标后方逐渐露出，结束时完整显示
                        int sx = (int) ((prefixX - 1.5f) * pxPerUnitX);
                        int sy = (int) (Display.getHeight() - (emTop + vlh + 6f) * pxPerUnitY);
                        int sWidth = (int) ((brandLeft - prefixX + 1.5f) * pxPerUnitX);
                        int sHeight = (int) ((vlh + 4f + 6f) * pxPerUnitY);
                        if (sWidth > 0 && sHeight > 0) {
                            glEnable(GL_SCISSOR_TEST);
                            glScissor(sx, sy, sWidth, sHeight);
                            drawText(airplus$bigFont, "Welcome to", prefixX, titleY,
                                    (opAlpha << 24) | 0x00FFFFFF);
                            glDisable(GL_SCISSOR_TEST);
                        }

                        // 字标后画（参考稿 DOM 中 #brand 在 #welcome 之后，滑入文字盖在字标下层）
                        drawText(airplus$bigFont, "AirPlus", brandLeft, titleY, textColor);
                    } else {
                        // C 之前：字标单独居中（参考稿 hero 初始状态，"Welcome to" 宽度为 0）
                        drawText(airplus$bigFont, "AirPlus", sw / 2f - vwTitle / 2f, titleY, textColor);
                    }
                }

                // 品牌文字随整体淡出
                if (airplus$smallFont != null) {
                    int brandAlpha = (alpha255 * 0x60) >> 8;
                    drawCenteredText(airplus$smallFont, AIRPLUS_BRAND, sw, sh - 24f,
                            (brandAlpha << 24) | 0x00FFFFFF);
                }

                GlStateManager.disableTexture2D();
                GlStateManager.disableBlend();
                GlStateManager.resetColor();
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

                        long now = System.currentTimeMillis();

                        if (airplus$welcomeAt == 0L) {
                            // ===== 加载阶段：字标居中靠上，下方进度条，步骤文字紧贴进度条上方 =====
                            if (!airplus$textBroken && airplus$bigFont != null) {
                                try {
                                    GlStateManager.enableAlpha();
                                    GlStateManager.enableBlend();
                                    GlStateManager.tryBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO);
                                    GlStateManager.enableTexture2D();

                                    // 加载阶段轻微上下悬浮（呼吸感）
                                    float bob = (float) Math.sin(now / 550.0) * 2.2f;
                                    float titleY = sh * 0.30f + bob;

                                    // 共享元素：AirPlus 字标（欢迎阶段起点位置与这里一致）
                                    drawCenteredText(airplus$bigFont, "AirPlus", sw, titleY, 0xFFFFFFFF);

                                    if (airplus$smallFont != null) {
                                        if (first != null) {
                                            // 具体加载部分的文字：紧贴进度条上方
                                            String info = first.getTitle() + " - " + first.getMessage()
                                                    + " (" + first.getStep() + "/" + first.getSteps() + ")";
                                            airplus$lastStepText = info;
                                            drawCenteredText(airplus$smallFont, info, sw, sh * 0.30f + 52f, 0x90FFFFFF);
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
                            if (first != null) {
                                float progress = (first.getStep() + 1f) / (first.getSteps() + 1f);
                                float barWidth = sw * 0.4f;
                                float barX = sw * 0.3f;
                                float barY = sh * 0.30f + 74f;
                                float barHeight = 3f;

                                // border
                                glColor4f(0.25f, 0.25f, 0.25f, 1f);
                                glBegin(GL_QUADS);
                                glVertex2f(barX - 1f, barY - 1f);
                                glVertex2f(barX - 1f, barY + barHeight + 1f);
                                glVertex2f(barX + barWidth + 1f, barY + barHeight + 1f);
                                glVertex2f(barX + barWidth + 1f, barY - 1f);
                                glEnd();

                                // fill
                                glColor4f(1f, 1f, 1f, 1f);
                                glBegin(GL_QUADS);
                                glVertex2f(barX, barY);
                                glVertex2f(barX, barY + barHeight);
                                glVertex2f(barX + barWidth * progress, barY + barHeight);
                                glVertex2f(barX + barWidth * progress, barY);
                                glEnd();
                            }
                        } else {
                            // ===== 欢迎阶段（finish() 之后，仍在 splash 线程上播放）=====
                            try {
                                drawWelcome(sw, sh, sr, now - airplus$welcomeAt, now);
                            } catch (Throwable t) {
                                System.out.println("[AirPlus] Welcome draw error:");
                                t.printStackTrace();
                                airplus$textBroken = true;
                                airplus$welcomeAt = 0L; // 回退到加载画面直到 done
                            }
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
     * @reason Trigger the welcome animation phase (drawn by the splash thread in run()),
     * wait for it to finish, then hand the context back to the main thread.
     */
    @Overwrite
    public static void finish() {
        if (!enabled) return;
        try {
            checkThreadState();
            // 切换到欢迎阶段并等 splash 线程播完（进度条收缩 → 字标移到正中 →
            // "Welcome to" 从右向左揭示 → 保持），之后主线程继续进入主菜单，
            // 过渡由主菜单淡入完成（splash 自身不再淡出）
            airplus$welcomeAt = System.currentTimeMillis();
            Thread.sleep(WELCOME_TOTAL);
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
