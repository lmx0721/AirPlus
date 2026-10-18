package net.airplus.ui.client.sodium.gui;

import net.airplus.ui.client.sodium.gui.options.OptionFlag;
import net.airplus.ui.client.sodium.gui.options.OptionGroup;
import net.airplus.ui.client.sodium.gui.options.OptionImpact;
import net.airplus.ui.client.sodium.gui.options.OptionImpl;
import net.airplus.ui.client.sodium.gui.options.OptionPage;
import net.airplus.ui.client.sodium.gui.options.control.ControlValueFormatter;
import net.airplus.ui.client.sodium.gui.options.control.CyclingControl;
import net.airplus.ui.client.sodium.gui.options.control.SliderControl;
import net.airplus.ui.client.sodium.gui.options.control.TickBoxControl;
import net.airplus.ui.client.sodium.gui.options.storage.MinecraftOptionsStorage;
import net.minecraft.client.settings.GameSettings;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OptiFine compat for the Sodium options GUI.
 *
 * When OptiFine is installed it replaces GameSettings with a patched subclass
 * carrying public "ofXxx" fields. This class mirrors a curated set of those
 * options into a dedicated "OptiFine" page of the Sodium GUI, using reflection
 * exclusively (OptiFine is not a compile-time dependency).
 *
 * Every option is added only if its backing field exists, so version differences
 * degrade to fewer rows instead of crashes. A page with no usable fields is not
 * created at all.
 *
 * Saving goes through the vanilla MinecraftOptionsStorage: OptiFine patches
 * GameSettings.saveOptions() to persist the ofXxx fields into options.txt.
 */
public class SodiumOptiFineCompat {
    private static final MinecraftOptionsStorage vanillaOpts = new MinecraftOptionsStorage();

    private static Boolean available;
    private static final Map<String, Field> fields = new HashMap<>();

    /** Tri-state used by several OptiFine detail options (0=Default, 1=Fast, 2=Fancy). */
    public enum TriState {
        DEFAULT, FAST, FANCY
    }

    /** Quad-state used by Rain & Snow (0=Default, 1=Fast, 2=Fastest, 3=Off). */
    public enum QuadState {
        DEFAULT, FAST, FASTEST, OFF
    }

    public static boolean isOptiFineAvailable() {
        if (available == null) {
            // 1.8.9-era OptiFine puts Config in the DEFAULT package (class name "Config",
            // see ReplayMod's OptifineReflection); newer versions use "optifine.Config".
            available = classExists("Config") || classExists("optifine.Config");
        }

        return available;
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name, false, SodiumOptiFineCompat.class.getClassLoader());

            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hasField(String name) {
        if (fields.containsKey(name)) {
            return true;
        }

        try {
            fields.put(name, GameSettings.class.getField(name));

            return true;
        } catch (Throwable ignored) {
            // Fall through: some OptiFine builds keep the fields non-public
        }

        try {
            Field field = GameSettings.class.getDeclaredField(name);
            field.setAccessible(true);
            fields.put(name, field);

            return true;
        } catch (Throwable t) {
            // Field renamed or missing in this OptiFine version -> skip the option
            return false;
        }
    }

    private static Object getFieldValue(GameSettings opts, String name) {
        try {
            return fields.get(name).get(opts);
        } catch (Throwable t) {
            t.printStackTrace();

            return null;
        }
    }

    private static void setFieldValue(GameSettings opts, String name, Object value) {
        try {
            fields.get(name).set(opts, value);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public static OptionPage createPage() {
        if (!isOptiFineAvailable()) {
            return null;
        }

        List<OptionGroup> groups = new ArrayList<>();

        OptionGroup.Builder performance = OptionGroup.createBuilder();
        int performanceCount = 0;
        performanceCount += addBool(performance, "平滑 FPS", "ofSmoothFps",
                "通过刷新显卡驱动缓冲区来稳定帧率，可能引入轻微操作延迟。");
        performanceCount += addBool(performance, "平滑世界", "ofSmoothWorld",
                "消除单人游戏中区块更新造成的卡顿尖峰。");
        performanceCount += addBool(performance, "快速渲染", "ofFastRender",
                "使用优化后的渲染算法，大幅提升帧率。完全生效需要重启游戏。");
        performanceCount += addBool(performance, "懒加载区块", "ofLazyChunkLoading",
                "更均匀地加载区块，让单人游戏的世界加载更流畅。");
        performanceCount += addBool(performance, "快速运算", "ofFastMath",
                "使用更快的三角函数实现。效果因硬件而异，较弱显卡可能提升 FPS。");
        performanceCount += addSlider(performance, "区块更新", "ofChunkUpdates",
                "每帧允许的区块几何重建次数。数值越高世界加载越快，但可能降低 FPS。");

        if (performanceCount > 0) {
            groups.add(performance.build());
        }

        OptionGroup.Builder details = OptionGroup.createBuilder();
        int detailsCount = 0;
        detailsCount += addBool(details, "天空", "ofSky",
                "切换天空的渲染。关闭可以提升性能。", OptionFlag.REQUIRES_RENDERER_RELOAD);
        detailsCount += addBool(details, "太阳与月亮", "ofSunMoon",
                "切换太阳和月亮的渲染。", OptionFlag.REQUIRES_RENDERER_RELOAD);
        detailsCount += addBool(details, "星星", "ofStars",
                "切换夜晚星星的渲染。", OptionFlag.REQUIRES_RENDERER_RELOAD);
        detailsCount += addBool(details, "天气", "ofWeather",
                "切换雨雪效果。仅对本地世界生效。");
        detailsCount += addBool(details, "虚空迷雾", "ofVoidFog",
                "切换低高度（靠近虚空）处的迷雾。", OptionFlag.REQUIRES_RENDERER_RELOAD);
        detailsCount += addCycle(details, "晕影", "ofVignette", TriState.class,
                new String[] {"默认", "快速", "高品质"},
                "控制屏幕边缘的轻微变暗效果。");
        detailsCount += addCycle(details, "雨雪", "ofRain", QuadState.class,
                new String[] {"默认", "快速", "极速", "关闭"},
                "控制雨雪的视觉质量。");
        detailsCount += addBool(details, "清澈的水", "ofClearWater",
                "让水变得透明，提升水下可见度。", OptionFlag.REQUIRES_RENDERER_RELOAD);
        detailsCount += addBool(details, "沼泽颜色", "ofSwampColors",
                "切换沼泽群系中植物与水体的特殊颜色。", OptionFlag.REQUIRES_RENDERER_RELOAD);
        detailsCount += addCycle(details, "掉落物", "ofDroppedItems", TriState.class,
                new String[] {"默认", "快速", "高品质"},
                "控制掉落物品的渲染方式。");

        if (detailsCount > 0) {
            groups.add(details.build());
        }

        if (groups.isEmpty()) {
            // No OptiFine fields found -> don't show an empty page
            return null;
        }

        return new OptionPage("OptiFine", com.google.common.collect.ImmutableList.copyOf(groups));
    }

    private static int addBool(OptionGroup.Builder group, String name, String field, String tooltip, OptionFlag... flags) {
        if (!hasField(field)) {
            return 0;
        }

        OptionImpl.Builder<GameSettings, Boolean> builder = OptionImpl.createBuilder(boolean.class, vanillaOpts)
                .setName(name)
                .setTooltip(tooltip)
                .setControl(TickBoxControl::new)
                .setBinding((opts, value) -> setFieldValue(opts, field, value), opts -> (Boolean) getFieldValue(opts, field));

        if (flags.length > 0) {
            builder.setFlags(flags);
        }

        group.add(builder.build());

        return 1;
    }

    private static <E extends Enum<E>> int addCycle(OptionGroup.Builder group, String name, String field,
                                                    Class<E> enumType, String[] labels, String tooltip) {
        if (!hasField(field)) {
            return 0;
        }

        E[] values = enumType.getEnumConstants();

        group.add(OptionImpl.createBuilder(enumType, vanillaOpts)
                .setName(name)
                .setTooltip(tooltip)
                .setControl(option -> new CyclingControl<>(option, enumType, labels))
                .setBinding((opts, value) -> setFieldValue(opts, field, value.ordinal()),
                        opts -> {
                            int raw = ((Number) getFieldValue(opts, field)).intValue();
                            return values[Math.max(0, Math.min(values.length - 1, raw))];
                        })
                .build());

        return 1;
    }

    private static int addSlider(OptionGroup.Builder group, String name, String field, String tooltip) {
        if (!hasField(field)) {
            return 0;
        }

        group.add(OptionImpl.createBuilder(int.class, vanillaOpts)
                .setName(name)
                .setTooltip(tooltip)
                .setControl(option -> new SliderControl(option, 1, 5, 1, ControlValueFormatter.quantity("次")))
                .setBinding((opts, value) -> setFieldValue(opts, field, value),
                        opts -> ((Number) getFieldValue(opts, field)).intValue())
                .build());

        return 1;
    }
}
