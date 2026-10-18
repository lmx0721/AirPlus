package net.airplus.ui.client.sodium.gui;

import net.airplus.ui.client.sodium.gui.options.Option;
import net.airplus.ui.client.sodium.gui.options.OptionFlag;
import net.airplus.ui.client.sodium.gui.options.OptionGroup;
import net.airplus.ui.client.sodium.gui.options.OptionImpact;
import net.airplus.ui.client.sodium.gui.options.OptionPage;
import net.airplus.ui.client.sodium.gui.options.control.ControlElement;
import net.airplus.ui.client.sodium.gui.options.storage.OptionStorage;
import net.airplus.ui.client.sodium.gui.widgets.FlatButtonWidget;
import net.airplus.ui.client.sodium.util.Dim2i;
import net.minecraft.client.gui.GuiScreen;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

/**
 * Port of me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI (Sodium 0.4.1),
 * adapted to the 1.8.9 GuiScreen API:
 * - Screen.render -> drawScreen; children (Drawable/Element) are managed in explicit lists
 *   because 1.8.9 has no addDrawableChild.
 * - Slider dragging is driven through mouseClickMove/mouseReleased.
 * - The donation button and the Shift+P escape hatch are removed (the latter would recurse
 *   into this screen through the GuiVideoSettings displayGuiScreen interceptor).
 */
public class SodiumOptionsGUI extends GuiScreen {
    private final List<OptionPage> pages = new ArrayList<>();

    private final List<ControlElement<?>> controls = new ArrayList<>();
    private final List<FlatButtonWidget> buttons = new ArrayList<>();

    private final GuiScreen prevScreen;

    private OptionPage currentPage;

    private FlatButtonWidget applyButton, closeButton, undoButton;

    private boolean hasPendingChanges;
    private ControlElement<?> hoveredElement;

    public SodiumOptionsGUI(GuiScreen prevScreen) {
        this.prevScreen = prevScreen;

        this.pages.add(SodiumGameOptionPages.general());
        this.pages.add(SodiumGameOptionPages.quality());
        this.pages.add(SodiumGameOptionPages.performance());

        // OptiFine installed -> mirror its video options into a dedicated tab
        OptionPage optiFinePage = SodiumOptiFineCompat.createPage();
        if (optiFinePage != null) {
            this.pages.add(optiFinePage);
        }
    }

    public void setPage(OptionPage page) {
        this.currentPage = page;

        this.rebuildGUI();
    }

    @Override
    public void initGui() {
        this.rebuildGUI();
    }

    private void rebuildGUI() {
        this.controls.clear();
        this.buttons.clear();

        if (this.currentPage == null) {
            if (this.pages.isEmpty()) {
                throw new IllegalStateException("No pages are available?!");
            }

            // Just use the first page for now
            this.currentPage = this.pages.get(0);
        }

        this.rebuildGUIPages();
        this.rebuildGUIOptions();

        this.undoButton = new FlatButtonWidget(new Dim2i(this.width - 211, this.height - 30, 65, 20), "撤销", this::undoChanges);
        this.applyButton = new FlatButtonWidget(new Dim2i(this.width - 142, this.height - 30, 65, 20), "应用", this::applyChanges);
        this.closeButton = new FlatButtonWidget(new Dim2i(this.width - 73, this.height - 30, 65, 20), "完成", this::close);

        this.buttons.add(this.undoButton);
        this.buttons.add(this.applyButton);
        this.buttons.add(this.closeButton);
    }

    private void rebuildGUIPages() {
        int x = 6;
        int y = 6;

        for (OptionPage page : this.pages) {
            int width = 12 + this.fontRendererObj.getStringWidth(page.getName());

            FlatButtonWidget button = new FlatButtonWidget(new Dim2i(x, y, width, 18), page.getName(), () -> this.setPage(page));
            button.setSelected(this.currentPage == page);

            x += width + 6;

            this.buttons.add(button);
        }
    }

    private void rebuildGUIOptions() {
        int x = 6;
        int y = 28;

        for (OptionGroup group : this.currentPage.getGroups()) {
            // Add each option's control element
            for (Option<?> option : group.getOptions()) {
                ControlElement<?> element = option.getControl().createElement(new Dim2i(x, y, 200, 18));

                this.controls.add(element);

                // Move down to the next option
                y += 18;
            }

            // Add padding beneath each option group
            y += 4;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        this.updateControls();

        for (FlatButtonWidget button : this.buttons) {
            button.render(mouseX, mouseY);
        }

        for (ControlElement<?> control : this.controls) {
            control.render(mouseX, mouseY);
        }

        if (this.hoveredElement != null) {
            this.renderOptionTooltip(this.hoveredElement);
        }
    }

    private void updateControls() {
        ControlElement<?> hovered = this.controls.stream()
                .filter(ControlElement::isHovered)
                .findFirst()
                .orElse(null);

        boolean hasChanges = this.getAllOptions()
                .anyMatch(Option::hasChanged);

        this.applyButton.setEnabled(hasChanges);
        this.undoButton.setVisible(hasChanges);
        this.closeButton.setEnabled(!hasChanges);

        this.hasPendingChanges = hasChanges;
        this.hoveredElement = hovered;
    }

    private Stream<Option<?>> getAllOptions() {
        return this.pages.stream()
                .flatMap(s -> s.getOptions().stream());
    }

    private void renderOptionTooltip(ControlElement<?> element) {
        Dim2i dim = element.getDimensions();

        int textPadding = 3;
        int boxPadding = 3;

        int boxWidth = 200;

        int boxY = dim.y();
        int boxX = dim.getLimitX() + boxPadding;

        Option<?> option = element.getOption();
        List<String> tooltip = new ArrayList<>(this.fontRendererObj.listFormattedStringToWidth(option.getTooltip(), boxWidth - (textPadding * 2)));

        OptionImpact impact = option.getImpact();

        if (impact != null) {
            tooltip.add("性能影响: " + impact.getLocalizedName());
        }

        int boxHeight = (tooltip.size() * 12) + boxPadding;
        int boxYLimit = boxY + boxHeight;
        int boxYCutoff = this.height - 40;

        // If the box is going to be cutoff on the Y-axis, move it back up the difference
        if (boxYLimit > boxYCutoff) {
            boxY -= boxYLimit - boxYCutoff;
        }

        this.drawGradientRect(boxX, boxY, boxX + boxWidth, boxY + boxHeight, 0xE0000000, 0xE0000000);

        for (int i = 0; i < tooltip.size(); i++) {
            this.fontRendererObj.drawString(tooltip.get(i), boxX + textPadding, boxY + textPadding + (i * 12), 0xFFFFFFFF);
        }
    }

    private void applyChanges() {
        final HashSet<OptionStorage<?>> dirtyStorages = new HashSet<>();
        final EnumSet<OptionFlag> flags = EnumSet.noneOf(OptionFlag.class);

        this.getAllOptions().forEach((option -> {
            if (!option.hasChanged()) {
                return;
            }

            option.applyChanges();

            flags.addAll(option.getFlags());
            dirtyStorages.add(option.getStorage());
        }));

        if (flags.contains(OptionFlag.REQUIRES_RENDERER_RELOAD)) {
            this.mc.renderGlobal.loadRenderers();
        }

        // OptionFlag.REQUIRES_RENDERER_UPDATE has no 1.8.9 equivalent; terrain updates are scheduled
        // automatically by the vanilla chunk update path.

        if (flags.contains(OptionFlag.REQUIRES_ASSET_RELOAD)) {
            this.mc.scheduleResourcesRefresh();
        }

        for (OptionStorage<?> storage : dirtyStorages) {
            storage.save();
        }

        // Re-run initGui in case the GUI scale changed
        net.minecraft.client.gui.ScaledResolution scaledResolution = new net.minecraft.client.gui.ScaledResolution(this.mc);
        this.setWorldAndResolution(this.mc, scaledResolution.getScaledWidth(), scaledResolution.getScaledHeight());
    }

    private void undoChanges() {
        this.getAllOptions()
                .forEach(Option::reset);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        for (FlatButtonWidget button : this.buttons) {
            if (button.mouseClicked(mouseX, mouseY, mouseButton)) {
                return;
            }
        }

        for (ControlElement<?> control : this.controls) {
            if (control.mouseClicked(mouseX, mouseY, mouseButton)) {
                return;
            }
        }

        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        for (ControlElement<?> control : this.controls) {
            control.mouseDragged(mouseX, mouseY, clickedMouseButton);
        }

        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        for (ControlElement<?> control : this.controls) {
            control.mouseReleased();
        }

        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        // Faithful to Sodium's shouldCloseOnEsc: ESC is ignored while there are pending changes
        if (keyCode == 1 && !this.hasPendingChanges) {
            this.close();
            return;
        }

        super.keyTyped(typedChar, keyCode);
    }

    public void close() {
        this.mc.displayGuiScreen(this.prevScreen);
    }
}
