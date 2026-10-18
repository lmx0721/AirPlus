package net.airplus.ui.client.sodium.gui.options.control;

/**
 * Port of me.jellysquid.mods.sodium.client.gui.options.control.ControlValueFormatter (Sodium 0.4.1).
 * TranslatableText replaced with hardcoded strings (Chinese).
 */
public interface ControlValueFormatter {
    static ControlValueFormatter guiScale() {
        return (v) -> (v == 0) ? "自动" : v + "x";
    }

    static ControlValueFormatter fpsLimit() {
        return (v) -> (v == 260) ? "无限" : v + " FPS";
    }

    static ControlValueFormatter brightness() {
        return (v) -> {
            if (v == 0) {
                return "昏暗";
            } else if (v == 100) {
                return "明亮";
            } else {
                return v + "%";
            }
        };
    }

    String format(int value);

    static ControlValueFormatter quantity(String name) {
        return (v) -> v + " " + name;
    }

    static ControlValueFormatter percentage() {
        return (v) -> v + "%";
    }

    static ControlValueFormatter multiplier() {
        return (v) -> v + "x";
    }

    static ControlValueFormatter quantityOrDisabled(String name, String disableText) {
        return (v) -> v == 0 ? disableText : v + " " + name;
    }

    static ControlValueFormatter number() {
        return String::valueOf;
    }
}
