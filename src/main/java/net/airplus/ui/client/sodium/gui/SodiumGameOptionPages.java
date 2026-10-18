package net.airplus.ui.client.sodium.gui;

import net.airplus.ui.client.sodium.gui.options.Option;
import net.airplus.ui.client.sodium.gui.options.OptionFlag;
import net.airplus.ui.client.sodium.gui.options.OptionImpact;
import net.airplus.ui.client.sodium.gui.options.OptionGroup;
import net.airplus.ui.client.sodium.gui.options.OptionImpl;
import net.airplus.ui.client.sodium.gui.options.OptionPage;
import net.airplus.ui.client.sodium.gui.options.control.ControlValueFormatter;
import net.airplus.ui.client.sodium.gui.options.control.CyclingControl;
import net.airplus.ui.client.sodium.gui.options.control.SliderControl;
import net.airplus.ui.client.sodium.gui.options.control.TickBoxControl;
import net.airplus.ui.client.sodium.gui.options.storage.FPSBoostStorage;
import net.airplus.ui.client.sodium.gui.options.storage.MinecraftOptionsStorage;
import net.airplus.features.module.modules.render.FPSBoost;
import com.google.common.collect.ImmutableList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.GameSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of me.jellysquid.mods.sodium.client.gui.SodiumGameOptionPages (Sodium 0.4.1).
 * The option definitions are rewritten for the 1.8.9 GameSettings; the page/group/builder
 * structure is kept identical to upstream. Sodium-specific options are dropped.
 * 界面文本已汉化。
 */
public class SodiumGameOptionPages {
    private static final MinecraftOptionsStorage vanillaOpts = new MinecraftOptionsStorage();
    private static final FPSBoostStorage fpsBoostOpts = new FPSBoostStorage();

    public enum GraphicsMode {
        FAST, FANCY
    }

    public enum SmoothLightingMode {
        OFF, MINIMUM, MAXIMUM
    }

    public enum ParticlesMode {
        ALL, DECREASED, MINIMAL
    }

    public enum CloudsMode {
        FANCY, FAST, OFF
    }

    public static OptionPage general() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName("渲染距离")
                        .setTooltip("以玩家为中心渲染的区块数量。数值越高视野越好，但会显著降低帧率。")
                        .setControl(option -> new SliderControl(option, 2, 32, 1, ControlValueFormatter.quantity("区块")))
                        .setBinding((options, value) -> options.renderDistanceChunks = value, options -> options.renderDistanceChunks)
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName("亮度")
                        .setTooltip("调节游戏画面亮度。调亮后洞穴里会更容易看清。")
                        .setControl(opt -> new SliderControl(opt, 0, 100, 1, ControlValueFormatter.brightness()))
                        .setBinding((opts, value) -> opts.gammaSetting = value * 0.01F, (opts) -> (int) (opts.gammaSetting / 0.01F))
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName("GUI 缩放")
                        .setTooltip("控制界面大小。“自动”会选取能容纳的最大缩放。")
                        .setControl(option -> new SliderControl(option, 0, 4, 1, ControlValueFormatter.guiScale()))
                        .setBinding((opts, value) -> {
                            opts.guiScale = value;

                            // Re-run initGui so the open screen adapts to the new scale (like Sodium's onResolutionChanged)
                            Minecraft client = Minecraft.getMinecraft();
                            GuiScreen screen = client.currentScreen;
                            if (screen != null) {
                                net.minecraft.client.gui.ScaledResolution scaledResolution = new net.minecraft.client.gui.ScaledResolution(client);
                                screen.setWorldAndResolution(client, scaledResolution.getScaledWidth(), scaledResolution.getScaledHeight());
                            }
                        }, opts -> opts.guiScale)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName("全屏")
                        .setTooltip("切换游戏是否以全屏模式运行。")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> {
                            opts.fullScreen = value;

                            Minecraft client = Minecraft.getMinecraft();
                            if (client.isFullScreen() != opts.fullScreen) {
                                client.toggleFullscreen();

                                // The client might not be able to enter full-screen mode
                                opts.fullScreen = client.isFullScreen();
                            }
                        }, (opts) -> opts.fullScreen)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName("垂直同步")
                        .setTooltip("将游戏帧率与显示器刷新率同步，减少画面撕裂，但可能略微降低性能。")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.enableVsync = value, opts -> opts.enableVsync)
                        .setImpact(OptionImpact.VARIES)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName("最大帧率")
                        .setTooltip("限制最大每秒帧数。较低的数值可以减少发热并省电。")
                        .setControl(option -> new SliderControl(option, 5, 260, 5, ControlValueFormatter.fpsLimit()))
                        .setBinding((opts, value) -> opts.limitFramerate = value, opts -> opts.limitFramerate)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName("视角摇晃")
                        .setTooltip("切换玩家移动时镜头的摇晃效果。")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.viewBobbing = value, opts -> opts.viewBobbing)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName("平滑摄像机")
                        .setTooltip("切换类似旁观模式的电影感镜头平滑效果。")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.smoothCamera = value, opts -> opts.smoothCamera)
                        .build())
                .build());

        return new OptionPage("常规", ImmutableList.copyOf(groups));
    }

    public static OptionPage quality() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(GraphicsMode.class, vanillaOpts)
                        .setName("图形")
                        .setTooltip("控制游戏的整体画质。“高品质”会启用额外的视觉细节，但可能降低性能。")
                        .setControl(option -> new CyclingControl<>(option, GraphicsMode.class, new String[] {"快速", "高品质"}))
                        .setBinding((opts, value) -> opts.fancyGraphics = value == GraphicsMode.FANCY, opts -> opts.fancyGraphics ? GraphicsMode.FANCY : GraphicsMode.FAST)
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(SmoothLightingMode.class, vanillaOpts)
                        .setName("平滑光照")
                        .setTooltip("控制方块光照的平滑着色，让光照更自然，但会略微降低性能。")
                        .setControl(option -> new CyclingControl<>(option, SmoothLightingMode.class, new String[] {"关闭", "最小", "最大"}))
                        .setBinding((opts, value) -> opts.ambientOcclusion = value.ordinal(), opts -> SmoothLightingMode.values()[Math.min(2, Math.max(0, opts.ambientOcclusion))])
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(ParticlesMode.class, vanillaOpts)
                        .setName("粒子效果")
                        .setTooltip("控制爆炸、火焰、水滴等粒子的数量。")
                        .setControl(option -> new CyclingControl<>(option, ParticlesMode.class, new String[] {"全部", "减少", "最少"}))
                        .setBinding((opts, value) -> opts.particleSetting = value.ordinal(), opts -> ParticlesMode.values()[Math.min(2, Math.max(0, opts.particleSetting))])
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(CloudsMode.class, vanillaOpts)
                        .setName("云")
                        .setTooltip("控制云的渲染方式。关闭云在某些设备上可以提升性能。")
                        .setControl(option -> new CyclingControl<>(option, CloudsMode.class, new String[] {"高品质", "快速", "关闭"}))
                        .setBinding((opts, value) -> opts.clouds = value.ordinal(), opts -> CloudsMode.values()[Math.min(2, Math.max(0, opts.clouds))])
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName("实体阴影")
                        .setTooltip("切换实体脚下的简单阴影。")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.entityShadows = value, opts -> opts.entityShadows)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setName("Mipmap 等级")
                        .setTooltip("通过预生成缩小贴图来降低远处的纹理噪点。更改后需要重新加载资源。")
                        .setControl(option -> new SliderControl(option, 0, 4, 1, ControlValueFormatter.multiplier()))
                        .setBinding((opts, value) -> opts.mipmapLevels = value, opts -> opts.mipmapLevels)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName("替代方块")
                        .setTooltip("切换草、石头等方块是否使用替代方块模型。")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.allowBlockAlternatives = value, opts -> opts.allowBlockAlternatives)
                        .build())
                .build());

        return new OptionPage("画质", ImmutableList.copyOf(groups));
    }

    public static OptionPage performance() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(int.class, fpsBoostOpts)
                        .setName("区块构建线程")
                        .setTooltip("用于重建区块几何的worker线程数量。线程越多移动时的卡顿越少，重新进入世界后生效。")
                        .setControl(o -> new SliderControl(o, 2, 16, 1, ControlValueFormatter.quantity("线程")))
                        .setBinding((opts, value) -> opts.getChunkWorkersSetting().set(value, true), FPSBoost::getChunkWorkers)
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setName("使用 VBO")
                        .setTooltip("使用顶点缓冲对象（VBO）渲染区块几何，在某些硬件上可以提升性能。")
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.useVbo = value, opts -> opts.useVbo)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .build());

        return new OptionPage("性能", ImmutableList.copyOf(groups));
    }
}
