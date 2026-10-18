package net.airplus.ui.client.sodium.gui.options;

/**
 * Port of me.jellysquid.mods.sodium.client.gui.options.OptionImpact (Sodium 0.4.1).
 * TranslatableText replaced with hardcoded color-coded strings.
 */
public enum OptionImpact implements TextProvider {
    LOW("\u00a7a低"),
    MEDIUM("\u00a7e中"),
    HIGH("\u00a76高"),
    VARIES("\u00a7f可变");

    private final String text;

    OptionImpact(String text) {
        this.text = text;
    }

    @Override
    public String getLocalizedName() {
        return this.text;
    }
}
