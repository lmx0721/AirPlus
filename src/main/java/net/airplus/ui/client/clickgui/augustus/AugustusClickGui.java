/*
 * AirClient Hacked Client
 * A free open source mixin-based injection hacked client built on Liquidbounce legacy codebase.
 * https://github.com/lmx0721/AirClient
 */
package net.airplus.ui.client.clickgui.augustus;

import net.airplus.AirPlus;
import net.airplus.config.BoolValue;
import net.airplus.config.ColorValue;
import net.airplus.config.FloatRangeValue;
import net.airplus.config.FloatValue;
import net.airplus.config.FontValue;
import net.airplus.config.IntRangeValue;
import net.airplus.config.IntValue;
import net.airplus.config.ListValue;
import net.airplus.config.TextValue;
import net.airplus.config.Value;
import net.airplus.features.module.Category;
import net.airplus.features.module.Module;
import net.airplus.features.module.modules.render.ClickGUI;
import net.airplus.ui.font.Fonts;
import net.airplus.utils.render.HudBlur;
import net.airplus.utils.render.RenderUtils;
import net.airplus.utils.render.RenderUtils.RoundedCorners;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.MathHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import javax.vecmath.Vector2f;
import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Augustus-styled ClickGUI ported from NightSky to AirClient's module/value APIs.
 */
public class AugustusClickGui extends GuiScreen {

    private enum GuiEvent {
        DRAW,
        CLICK,
        RELEASE
    }

    private static final class ColorPickerState {
        float pickerX;
        float pickerY;
        float hueY;
        float opacityY;
        boolean draggingColor;
        boolean draggingHue;
        boolean draggingOpacity;
    }

    public static float lastPosX = -1337F;
    public static float lastPosY = -1337F;
    public static float lastWidth = 500F;
    public static float lastHeight = 250F;
    // 保存上次关闭时的状态,下次打开时恢复
    private static Category lastSelectedCategory = Category.COMBAT;
    private static String lastSelectedModuleName = null;
    private static float lastModuleScroll = 0F;
    private static float lastValueScroll = 0F;

    private float posX;
    private float posY;
    private float width = 500F;
    private float height = 250F;
    private float dragX;
    private float dragY;
    private float moduleScroll;
    private float valueScroll;
    private float categoryLineX;
    private float targetCategoryLineX;
    private long lastAnimationTime = System.currentTimeMillis();

    private boolean dragging;
    private boolean resizing;
    private boolean waitingForKey;

    // 模块列表悬浮提示：悬浮同一模块 1 秒后在其旁显示模块解释
    private Module hoverModule;
    private long moduleHoverStart;

    private Category selectedCategory = Category.COMBAT;
    private Module selectedModule;

    private FloatValue draggingFloat;
    private IntValue draggingInt;
    // 双滑块拖动状态: draggingRangeValue 指向被拖动的 RangeValue,draggingRangeThumb 标识哪个滑块 ("first" | "last")
    private Value<?> draggingRangeValue;
    private String draggingRangeThumb;

    private final Map<TextValue, GuiTextField> textFields = new HashMap<>();
    private final Map<Value<?>, Float> animatedSliders = new HashMap<>();
    private final Map<Value<?>, Float> animatedRangeFirst = new HashMap<>();
    private final Map<Value<?>, Float> animatedRangeLast = new HashMap<>();
    private final Map<ColorValue, ColorPickerState> colorPickerStates = new HashMap<>();

    public AugustusClickGui() {
        if (lastPosX == -1337F || lastPosY == -1337F) {
            posX = 150F;
            posY = 80F;
        } else {
            posX = lastPosX;
            posY = lastPosY;
            width = lastWidth;
            height = lastHeight;
        }
        // 恢复上次关闭时的状态
        selectedCategory = lastSelectedCategory;
        moduleScroll = lastModuleScroll;
        valueScroll = lastValueScroll;
        if (lastSelectedModuleName != null) {
            selectedModule = AirPlus.INSTANCE.getModuleManager().getModule(lastSelectedModuleName);
        }
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        if (lastPosX == -1337F || lastPosY == -1337F) {
            posX = this.width / 2F - this.width() / 2F;
            posY = this.height / 2F - this.height() / 2F;
            if (posX <= 0F) posX = 150F;
            if (posY <= 0F) posY = 80F;
        }
        super.initGui();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        updateAnimations();
        handle(mouseX, mouseY, -1, GuiEvent.DRAW);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        handle(mouseX, mouseY, mouseButton, GuiEvent.CLICK);
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        handle(mouseX, mouseY, state, GuiEvent.RELEASE);
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (waitingForKey && selectedModule != null) {
            selectedModule.setKeyBind(keyCode == Keyboard.KEY_ESCAPE ? Keyboard.KEY_NONE : keyCode);
            waitingForKey = false;
            return;
        }

        boolean textFocused = false;
        for (GuiTextField field : textFields.values()) {
            if (field.isFocused()) {
                field.textboxKeyTyped(typedChar, keyCode);
                textFocused = true;
            }
        }

        if (textFocused) {
            return;
        }

        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }

        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        lastPosX = posX;
        lastPosY = posY;
        lastWidth = width;
        lastHeight = height;
        // 保存当前状态,下次打开时恢复
        lastSelectedCategory = selectedCategory;
        lastSelectedModuleName = selectedModule != null ? selectedModule.getName() : null;
        lastModuleScroll = moduleScroll;
        lastValueScroll = valueScroll;
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void handle(int mouseX, int mouseY, int mouseButton, GuiEvent event) {
        if (event == GuiEvent.RELEASE) {
            dragging = false;
            resizing = false;
            draggingFloat = null;
            draggingInt = null;
            draggingRangeValue = null;
            draggingRangeThumb = null;
            for (ColorPickerState state : colorPickerStates.values()) {
                state.draggingColor = false;
                state.draggingHue = false;
                state.draggingOpacity = false;
            }
        }

        if (event == GuiEvent.CLICK) {
            if (hovered(mouseX, mouseY, posX, posY, width, 18F) && mouseButton == 0) {
                dragging = true;
                dragX = mouseX - posX;
                dragY = mouseY - posY;
            }

            if (hovered(mouseX, mouseY, posX + width - 10F, posY + height - 10F, 12F, 12F) && mouseButton == 0) {
                resizing = true;
            }
        }

        if (event == GuiEvent.DRAW) {
            if (dragging && Mouse.isButtonDown(0)) {
                posX = mouseX - dragX;
                posY = mouseY - dragY;
            }
            if (resizing && Mouse.isButtonDown(0)) {
                width = Math.max(400F, mouseX - posX);
                height = Math.max(200F, mouseY - posY);
            }
        }

        drawMainWindow(mouseX, mouseY, mouseButton, event);
    }

    private void drawMainWindow(int mouseX, int mouseY, int mouseButton, GuiEvent event) {
        if (event == GuiEvent.DRAW) {
            // 背景模糊 (HudBlur InternalBlur，半透明背景会让模糊透出)
            if (ClickGUI.INSTANCE.getAugBlur()) {
                HudBlur.INSTANCE.blur(posX, posY, posX + width, posY + height, ClickGUI.INSTANCE.getAugBlurStrength(), "InternalBlur", 12F, RoundedCorners.ALL, null);
            }
            // 原有背景与分隔线
            drawRounded(posX, posY, width, height, 12F, new Color(25, 25, 25, 190).getRGB());
            drawRect(posX, posY, width, 18F, new Color(34, 34, 34, 230).getRGB());
            drawTitle("CLICKGUI", posX + 6F, posY + 6F, new Color(220, 220, 220).getRGB());
            drawRect(posX + 90F, posY, 2F, height, new Color(34, 34, 34, 230).getRGB());
            drawRect(posX + 90F, posY + 40F, width - 90F, 2F, new Color(34, 34, 34, 230).getRGB());
            drawRect(posX + width - 9F, posY + height - 9F, 8F, 8F, new Color(70, 70, 70, 160).getRGB());
        }

        drawCategories(mouseX, mouseY, mouseButton, event);
        drawModules(mouseX, mouseY, mouseButton, event);
        drawValues(mouseX, mouseY, mouseButton, event);
        updateDraggingValues(mouseX, mouseY);

        // 模块悬浮提示（最后绘制保证在最上层）
        if (event == GuiEvent.DRAW && hoverModule != null
                && System.currentTimeMillis() - moduleHoverStart >= 1000L) {
            drawModuleTooltip(mouseX, mouseY, hoverModule.getDescription());
        }
    }

    private void drawCategories(int mouseX, int mouseY, int mouseButton, GuiEvent event) {
        float categoryX = posX + 95F;
        float clipX = posX + 92F;
        float clipY = posY + 18F;
        float clipW = width - 94F;
        float clipH = 24F;

        if (event == GuiEvent.DRAW) scissor(clipX, clipY, clipW, clipH, true);

        for (Category category : Category.values()) {
            if (!category.shouldShow()) continue;

            String name = category.name();
            float textWidth = normalWidth(name);
            boolean selected = category == selectedCategory;
            boolean inBounds = categoryX + textWidth >= clipX && categoryX <= clipX + clipW;
            boolean categoryHovered = inBounds && hovered(mouseX, mouseY, categoryX, posY + 20F, textWidth, normalHeight())
                    && hovered(mouseX, mouseY, clipX, clipY, clipW, clipH);

            if (event == GuiEvent.DRAW) {
                int color = selected ? accent().getRGB() : categoryHovered ? new Color(230, 230, 230).getRGB() : new Color(180, 180, 180).getRGB();
                drawNormal(name, categoryX, posY + 25F, color);
                if (selected) {
                    targetCategoryLineX = categoryX;
                    if (categoryLineX == 0F) categoryLineX = targetCategoryLineX;
                }
            } else if (event == GuiEvent.CLICK && categoryHovered && mouseButton == 0) {
                selectedCategory = category;
                selectedModule = null;
                moduleScroll = 0F;
                valueScroll = 0F;
                colorPickerStates.clear();
                targetCategoryLineX = categoryX;
            }

            categoryX += textWidth + 12F;
        }

        if (event == GuiEvent.DRAW && selectedCategory != null) {
            drawRect(categoryLineX, posY + 22F + normalHeight(), normalWidth(selectedCategory.name()), 2F, accent().getRGB());
        }

        if (event == GuiEvent.DRAW) scissor(0F, 0F, 0F, 0F, false);
    }

    private void drawModules(int mouseX, int mouseY, int mouseButton, GuiEvent event) {
        if (selectedCategory == null) return;

        float listX = posX;
        float listY = posY + 18F;
        float listW = 90F;
        float listH = height - 18F;
        float contentHeight = modulesInCategory(selectedCategory).size() * (normalHeight() + 5F);
        moduleScroll = clampScroll(moduleScroll, contentHeight, listH - 8F);

        if (event == GuiEvent.DRAW && hovered(mouseX, mouseY, listX, listY, listW, listH)) {
            moduleScroll = clampScroll(moduleScroll + Mouse.getDWheel() / 10F, contentHeight, listH - 8F);
        }

        if (event == GuiEvent.DRAW) scissor(listX, listY, listW, listH, true);

        float moduleY = posY + 26F + moduleScroll;
        Module hoveredThisFrame = null;
        for (Module module : modulesInCategory(selectedCategory)) {
            boolean inBounds = moduleY >= listY && moduleY + normalHeight() <= listY + listH;
            boolean moduleHovered = inBounds && hovered(mouseX, mouseY, posX + 8F, moduleY - 3F, 74F, normalHeight() + 4F);
            if (event == GuiEvent.DRAW) {
                if (moduleHovered) hoveredThisFrame = module;
                float drawX = posX + 8F;
                if (module == selectedModule) {
                    drawNormal(">", drawX, moduleY, module.getState() ? accent().getRGB() : new Color(210, 210, 210).getRGB());
                    drawX += normalWidth("> ");
                }
                int color = module.getState() ? accent().getRGB() : moduleHovered ? Color.WHITE.getRGB() : new Color(200, 200, 200).getRGB();
                drawNormal(module.getName(), drawX, moduleY, color);
            } else if (event == GuiEvent.CLICK && moduleHovered) {
                if (mouseButton == 0) {
                    module.toggle();
                } else if (mouseButton == 1) {
                    selectedModule = module;
                    valueScroll = 0F;
                    colorPickerStates.clear();
                }
            }
            moduleY += normalHeight() + 5F;
        }

        if (event == GuiEvent.DRAW) {
            if (hoveredThisFrame != null && hoveredThisFrame != hoverModule) {
                hoverModule = hoveredThisFrame;
                moduleHoverStart = System.currentTimeMillis();
            } else if (hoveredThisFrame == null) {
                hoverModule = null;
            }
        }

        if (event == GuiEvent.DRAW) scissor(0F, 0F, 0F, 0F, false);
    }

    private void drawValues(int mouseX, int mouseY, int mouseButton, GuiEvent event) {
        if (selectedModule == null) return;

        float valuesX = posX + 100F;
        float currentY = posY + 48F;

        if (event == GuiEvent.DRAW) {
            drawNormal(selectedModule.getName() + ":", valuesX, currentY, new Color(210, 210, 210).getRGB());
            // 模块解释：跟在模块名 ":" 后面，超宽时截断
            float descX = valuesX + normalWidth(selectedModule.getName() + ":") + 8F;
            String desc = truncateText(selectedModule.getDescription(), posX + width - descX - 6F);
            if (!desc.isEmpty()) {
                drawNormal(desc, descX, currentY, new Color(130, 130, 130).getRGB());
            }
        }
        currentY += normalHeight() + 5F;

        String keyName = selectedModule.getKeyBind() == Keyboard.KEY_NONE ? "None" : Keyboard.getKeyName(selectedModule.getKeyBind());
        boolean keyHovered = hovered(mouseX, mouseY, valuesX, currentY, normalWidth("Key: " + keyName), normalHeight());
        boolean hideHovered = hovered(mouseX, mouseY, valuesX + 72F, currentY, normalWidth("Hide: " + selectedModule.isHidden()), normalHeight());

        if (event == GuiEvent.DRAW) {
            drawNormal(waitingForKey ? "Key: ..." : "Key: " + keyName, valuesX, currentY, waitingForKey ? accent().getRGB() : new Color(150, 150, 150).getRGB());
            drawNormal("Hide: ", valuesX + 72F, currentY, new Color(150, 150, 150).getRGB());
            drawNormal(String.valueOf(selectedModule.isHidden()), valuesX + 72F + normalWidth("Hide: "), currentY, selectedModule.isHidden() ? new Color(0, 180, 0).getRGB() : new Color(180, 0, 0).getRGB());
        } else if (event == GuiEvent.CLICK && mouseButton == 0) {
            if (keyHovered) waitingForKey = true;
            if (hideHovered) selectedModule.setHidden(!selectedModule.isHidden());
        }

        currentY += normalHeight() + 10F;

        if (event == GuiEvent.DRAW && hovered(mouseX, mouseY, posX + 92F, posY + 42F, width - 94F, height - 44F)) {
            valueScroll = clampScroll(valueScroll + Mouse.getDWheel() / 10F, valuesContentHeight(), height - (currentY - posY) - 2F);
        }

        if (event == GuiEvent.DRAW) scissor(posX + 92F, currentY - 2F, width - 94F, height - (currentY - posY) - 2F, true);

        float clipY = currentY - 2F;
        float clipH = height - (currentY - posY) - 2F;
        currentY += valueScroll;
        for (Value<?> value : selectedModule.getValues()) {
            if (!value.shouldRender()) continue;
            float oldY = currentY;
            currentY = drawValue(value, currentY, mouseX, mouseY, mouseButton, event, clipY, clipH);
            if (currentY == oldY) currentY += normalHeight() + 5F;
        }

        valueScroll = clampScroll(valueScroll, valuesContentHeight(), clipH);

        if (event == GuiEvent.DRAW) scissor(0F, 0F, 0F, 0F, false);
    }

    private float drawValue(Value<?> value, float y, int mouseX, int mouseY, int mouseButton, GuiEvent event, float clipY, float clipH) {
        float height = valueHeight(value);
        boolean inBounds = y + height >= clipY && y <= clipY + clipH;
        if (!inBounds) return y + height;
        if (event != GuiEvent.DRAW && !hovered(mouseX, mouseY, posX + 92F, clipY, width - 94F, clipH)) return y + height;

        float x = posX + 100F;
        if (value instanceof BoolValue) {
            BoolValue boolValue = (BoolValue) value;
            String text = value.getName() + ": " + boolValue.get();
            if (event == GuiEvent.DRAW) {
                drawNormal(value.getName() + ": ", x, y, new Color(200, 200, 200).getRGB());
                drawNormal(String.valueOf(boolValue.get()), x + normalWidth(value.getName() + ": "), y, boolValue.get() ? new Color(0, 180, 0).getRGB() : new Color(180, 0, 0).getRGB());
            } else if (event == GuiEvent.CLICK && mouseButton == 0 && hovered(mouseX, mouseY, x, y, normalWidth(text), normalHeight())) {
                boolValue.set(!boolValue.get(), true);
            }
            return y + normalHeight() + 5F;
        }

        if (value instanceof TextValue) {
            TextValue textValue = (TextValue) value;
            float labelWidth = normalWidth(value.getName() + ": ");
            GuiTextField field = textFields.computeIfAbsent(textValue, key -> {
                GuiTextField textField = new GuiTextField(0, mc.fontRendererObj, 0, 0, 100, 14);
                textField.setMaxStringLength(256);
                textField.setText(textValue.get());
                textField.setEnableBackgroundDrawing(false);
                return textField;
            });
            field.xPosition = (int) (x + labelWidth + 4F);
            field.yPosition = (int) (y - 2F);
            field.width = 110;
            field.height = 14;

            if (event == GuiEvent.DRAW) {
                drawNormal(value.getName() + ": ", x, y, new Color(200, 200, 200).getRGB());
                drawRect(field.xPosition - 2F, field.yPosition - 1F, field.width + 4F, field.height + 2F, new Color(34, 34, 34).getRGB());
                drawRect(field.xPosition - 1F, field.yPosition, field.width + 2F, field.height, new Color(45, 45, 45, 220).getRGB());
                field.updateCursorCounter();
                if (field.getText().isEmpty() && !field.isFocused()) {
                    drawNormal("Enter text...", field.xPosition + 3F, y, new Color(120, 120, 120).getRGB());
                } else {
                    field.drawTextBox();
                }
                textValue.set(field.getText(), true);
            } else if (event == GuiEvent.CLICK) {
                field.mouseClicked(mouseX, mouseY, mouseButton);
            }
            return y + normalHeight() + 6F;
        }

        if (value instanceof FloatValue) {
            FloatValue floatValue = (FloatValue) value;
            float sliderX = x + normalWidth(value.getName() + ": ") + 4F;
            drawSlider(value, y, sliderX, 98F, floatValue.get(), floatValue.getMinimum(), floatValue.getMaximum(), event);
            if (event == GuiEvent.CLICK && mouseButton == 0 && hovered(mouseX, mouseY, sliderX, y - 4F, 98F, 10F)) {
                draggingFloat = floatValue;
                updateFloat(mouseX, sliderX, 98F, floatValue);
            }
            return y + normalHeight() + 6F;
        }

        if (value instanceof IntValue) {
            IntValue intValue = (IntValue) value;
            float sliderX = x + normalWidth(value.getName() + ": ") + 4F;
            drawSlider(value, y, sliderX, 98F, intValue.get(), intValue.getMinimum(), intValue.getMaximum(), event);
            if (event == GuiEvent.CLICK && mouseButton == 0 && hovered(mouseX, mouseY, sliderX, y - 4F, 98F, 10F)) {
                draggingInt = intValue;
                updateInt(mouseX, sliderX, 98F, intValue);
            }
            return y + normalHeight() + 6F;
        }

        if (value instanceof IntRangeValue) {
            IntRangeValue rangeValue = (IntRangeValue) value;
            float sliderX = x + normalWidth(value.getName() + ": ") + 4F;
            float sliderW = 98F;
            int first = rangeValue.get().getFirst();
            int last = rangeValue.get().getLast();
            int min = rangeValue.getMinimum();
            int max = rangeValue.getMaximum();
            drawRangeSlider(value, y, sliderX, sliderW, first, last, min, max, event);
            if (event == GuiEvent.CLICK && mouseButton == 0 && hovered(mouseX, mouseY, sliderX, y - 4F, sliderW, 10F)) {
                // 判断离哪个滑块更近
                float progress = ((mouseX - sliderX) / sliderW) * (max - min) + min;
                int distFirst = Math.abs((int) progress - first);
                int distLast = Math.abs((int) progress - last);
                draggingRangeValue = rangeValue;
                draggingRangeThumb = distFirst <= distLast ? "first" : "last";
                updateIntRange(mouseX, sliderX, sliderW, rangeValue);
            }
            return y + normalHeight() + 6F;
        }

        if (value instanceof FloatRangeValue) {
            FloatRangeValue rangeValue = (FloatRangeValue) value;
            float sliderX = x + normalWidth(value.getName() + ": ") + 4F;
            float sliderW = 98F;
            float first = rangeValue.get().getStart();
            float last = rangeValue.get().getEndInclusive();
            float min = rangeValue.getMinimum();
            float max = rangeValue.getMaximum();
            drawRangeSlider(value, y, sliderX, sliderW, first, last, min, max, event);
            if (event == GuiEvent.CLICK && mouseButton == 0 && hovered(mouseX, mouseY, sliderX, y - 4F, sliderW, 10F)) {
                float progress = ((mouseX - sliderX) / sliderW) * (max - min) + min;
                float distFirst = Math.abs(progress - first);
                float distLast = Math.abs(progress - last);
                draggingRangeValue = rangeValue;
                draggingRangeThumb = distFirst <= distLast ? "first" : "last";
                updateFloatRange(mouseX, sliderX, sliderW, rangeValue);
            }
            return y + normalHeight() + 6F;
        }

        if (value instanceof ListValue) {
            ListValue listValue = (ListValue) value;
            float modeX = x + normalWidth(value.getName() + ": ");
            float modeY = y;
            if (event == GuiEvent.DRAW) {
                drawNormal(value.getName() + ": ", x, y, new Color(200, 200, 200).getRGB());
            }
            String[] values = listValue.getValues();
            for (int i = 0; i < values.length; i++) {
                String mode = values[i];
                if (modeX > posX + width - 55F) {
                    modeX = x + normalWidth(value.getName() + ": ");
                    modeY += normalHeight() + 2F;
                }
                boolean selected = mode.equalsIgnoreCase(listValue.get());
                if (event == GuiEvent.DRAW) {
                    drawNormal(mode, modeX, modeY, selected ? accent().getRGB() : new Color(200, 200, 200).getRGB());
                    if (i < values.length - 1) drawNormal(", ", modeX + normalWidth(mode), modeY, new Color(200, 200, 200).getRGB());
                } else if (event == GuiEvent.CLICK && mouseButton == 0 && hovered(mouseX, mouseY, modeX, modeY, normalWidth(mode), normalHeight())) {
                    listValue.set(mode, true);
                }
                modeX += normalWidth(mode + (i < values.length - 1 ? ", " : ""));
            }
            return modeY + normalHeight() + 6F;
        }

        if (value instanceof FontValue) {
            FontValue fontValue = (FontValue) value;
            float labelWidth = normalWidth(value.getName() + ": ");
            if (event == GuiEvent.DRAW) {
                drawNormal(value.getName() + ": ", x, y, new Color(200, 200, 200).getRGB());
                drawNormal(fontValue.toText(), x + labelWidth, y, new Color(200, 200, 200).getRGB());
            } else if (event == GuiEvent.CLICK && hovered(mouseX, mouseY, x, y, width - 102F, normalHeight())) {
                if (mouseButton == 0) fontValue.next();
                else if (mouseButton == 2) fontValue.previous();
            }
            return y + normalHeight() + 5F;
        }

        if (value instanceof ColorValue) {
            return drawColorValue((ColorValue) value, y, mouseX, mouseY, mouseButton, event);
        }

        if (event == GuiEvent.DRAW) {
            drawNormal(value.getName() + ": " + value.toText(), x, y, new Color(200, 200, 200).getRGB());
        }
        return y + normalHeight() + 5F;
    }

    private float drawColorValue(ColorValue colorValue, float y, int mouseX, int mouseY, int mouseButton, GuiEvent event) {
        float x = posX + 100F;
        ColorPickerState state = colorPickerStates.computeIfAbsent(colorValue, key -> new ColorPickerState());
        float previewX = x + normalWidth(colorValue.getName() + ": ") + 4F;
        state.pickerX = x;
        state.pickerY = y + normalHeight() + 4F;
        state.hueY = state.pickerY + 54F;
        state.opacityY = state.hueY + 9F;

        if (event == GuiEvent.DRAW) {
            Color selected = colorValue.selectedColor();
            drawNormal(colorValue.getName() + ": ", x, y, new Color(200, 200, 200).getRGB());
            drawRect(previewX, y - 1F, 22F, 10F, selected.getRGB());

            if (colorValue.getShowPicker()) {
                Color hueColor = Color.getHSBColor(colorValue.getHueSliderY(), 1F, 1F);
                drawRect(state.pickerX, state.pickerY, 100F, 50F, hueColor.getRGB());
                for (int sx = 0; sx < 100; sx++) {
                    float saturation = sx / 100F;
                    drawRect(state.pickerX + sx, state.pickerY, 1F, 50F, new Color(255, 255, 255, (int) (255F * (1F - saturation))).getRGB());
                }
                for (int sy = 0; sy < 50; sy++) {
                    float brightness = 1F - sy / 50F;
                    drawRect(state.pickerX, state.pickerY + sy, 100F, 1F, new Color(0, 0, 0, (int) (255F * (1F - brightness))).getRGB());
                }
                for (int sx = 0; sx < 100; sx++) {
                    drawRect(state.pickerX + sx, state.hueY, 1F, 5F, Color.getHSBColor(sx / 100F, 1F, 1F).getRGB());
                }
                for (int sx = 0; sx < 100; sx++) {
                    int alpha = MathHelper.clamp_int((int) (sx / 99F * 255F), 0, 255);
                    Color alphaColor = new Color(selected.getRed(), selected.getGreen(), selected.getBlue(), alpha);
                    drawRect(state.pickerX + sx, state.opacityY, 1F, 5F, alphaColor.getRGB());
                }

                Vector2f pos = colorValue.getColorPickerPos();
                float markerX = state.pickerX + pos.x * 100F;
                float markerY = state.pickerY + pos.y * 50F;
                drawRect(markerX - 2F, markerY - 2F, 4F, 4F, Color.WHITE.getRGB());
                float hueMarker = state.pickerX + colorValue.getHueSliderY() * 100F;
                drawRect(hueMarker - 1F, state.hueY - 1F, 2F, 7F, Color.WHITE.getRGB());
                float opacityMarker = state.pickerX + colorValue.getOpacitySliderY() * 100F;
                drawRect(opacityMarker - 1F, state.opacityY - 1F, 2F, 7F, Color.WHITE.getRGB());
            }
        } else if (event == GuiEvent.CLICK && mouseButton == 0) {
            if (hovered(mouseX, mouseY, previewX, y - 1F, 22F, 10F)) {
                colorValue.setShowPicker(!colorValue.getShowPicker());
            } else if (colorValue.getShowPicker()) {
                if (hovered(mouseX, mouseY, state.pickerX, state.pickerY, 100F, 50F)) {
                    state.draggingColor = true;
                    updateColor(mouseX, mouseY, colorValue, state);
                } else if (hovered(mouseX, mouseY, state.pickerX, state.hueY, 100F, 5F)) {
                    state.draggingHue = true;
                    updateHue(mouseX, colorValue, state);
                } else if (hovered(mouseX, mouseY, state.pickerX, state.opacityY, 100F, 5F)) {
                    state.draggingOpacity = true;
                    updateOpacity(mouseX, colorValue, state);
                }
            }
        }
        return y + valueHeight(colorValue);
    }

    private void drawSlider(Value<?> value, float y, float sliderX, float sliderWidth, double current, double min, double max, GuiEvent event) {
        if (event != GuiEvent.DRAW) return;

        float x = posX + 100F;
        drawNormal(value.getName() + ": ", x, y, new Color(200, 200, 200).getRGB());
        double target = Math.max(0D, Math.min(sliderWidth, (current - min) / (max - min) * sliderWidth));
        float animated = animatedSliders.getOrDefault(value, (float) target);
        animated += ((float) target - animated) * 0.35F;
        animatedSliders.put(value, animated);

        drawRect(sliderX, y - 3F, sliderWidth + 1F, 10F, new Color(34, 34, 34).getRGB());
        drawRect(sliderX + 1F, y - 2F, Math.max(0F, animated - 1F), 8F, accent().getRGB());
        String valueText = current == Math.rint(current) ? String.valueOf((int) current) : String.valueOf(Math.round(current * 100D) / 100D);
        drawNormal(valueText, sliderX + sliderWidth / 2F - normalWidth(valueText) / 2F, y - 1F, new Color(230, 230, 230).getRGB());
    }

    /**
     * 绘制双滑块 (RangeValue): 与普通滑块样式一致,accent 填充区间从 first 到 last
     */
    private void drawRangeSlider(Value<?> value, float y, float sliderX, float sliderWidth,
                                 double first, double last, double min, double max, GuiEvent event) {
        if (event != GuiEvent.DRAW) return;

        float x = posX + 100F;
        drawNormal(value.getName() + ": ", x, y, new Color(200, 200, 200).getRGB());

        double range = max - min;
        double targetFirst = range == 0D ? 0D : Math.max(0D, Math.min(sliderWidth, (first - min) / range * sliderWidth));
        double targetLast = range == 0D ? 0D : Math.max(0D, Math.min(sliderWidth, (last - min) / range * sliderWidth));

        // 动画插值
        float animFirst = animatedRangeFirst.getOrDefault(value, (float) targetFirst);
        float animLast = animatedRangeLast.getOrDefault(value, (float) targetLast);
        animFirst += ((float) targetFirst - animFirst) * 0.35F;
        animLast += ((float) targetLast - animLast) * 0.35F;
        animatedRangeFirst.put(value, animFirst);
        animatedRangeLast.put(value, animLast);

        // 轨道背景 (与普通滑块一致)
        drawRect(sliderX, y - 3F, sliderWidth + 1F, 10F, new Color(34, 34, 34).getRGB());
        // accent 色填充区间 (与普通滑块完全一致: 从 sliderX+1+animFirst 到 sliderX+animLast)
        float fillStart = sliderX + 1F + animFirst;
        float fillEnd = sliderX + animLast;
        float fillW = Math.max(0F, fillEnd - fillStart);
        drawRect(fillStart, y - 2F, fillW, 8F, accent().getRGB());

        // 显示数值文本
        String firstText = first == Math.rint(first) ? String.valueOf((int) first) : String.valueOf(Math.round(first * 100D) / 100D);
        String lastText = last == Math.rint(last) ? String.valueOf((int) last) : String.valueOf(Math.round(last * 100D) / 100D);
        String valueText = firstText + ".." + lastText;
        drawNormal(valueText, sliderX + sliderWidth / 2F - normalWidth(valueText) / 2F, y - 1F, new Color(230, 230, 230).getRGB());
    }

    private void updateDraggingValues(int mouseX, int mouseY) {
        if (draggingFloat != null && Mouse.isButtonDown(0)) {
            float sliderX = posX + 100F + normalWidth(draggingFloat.getName() + ": ") + 4F;
            updateFloat(mouseX, sliderX, 98F, draggingFloat);
        }
        if (draggingInt != null && Mouse.isButtonDown(0)) {
            float sliderX = posX + 100F + normalWidth(draggingInt.getName() + ": ") + 4F;
            updateInt(mouseX, sliderX, 98F, draggingInt);
        }
        if (draggingRangeValue != null && Mouse.isButtonDown(0)) {
            float sliderX = posX + 100F + normalWidth(draggingRangeValue.getName() + ": ") + 4F;
            if (draggingRangeValue instanceof IntRangeValue) {
                updateIntRange(mouseX, sliderX, 98F, (IntRangeValue) draggingRangeValue);
            } else if (draggingRangeValue instanceof FloatRangeValue) {
                updateFloatRange(mouseX, sliderX, 98F, (FloatRangeValue) draggingRangeValue);
            }
        }
        for (Map.Entry<ColorValue, ColorPickerState> entry : colorPickerStates.entrySet()) {
            ColorValue value = entry.getKey();
            ColorPickerState state = entry.getValue();
            if (state.draggingColor && Mouse.isButtonDown(0)) {
                updateColor(mouseX, mouseY, value, state);
            } else if (state.draggingHue && Mouse.isButtonDown(0)) {
                updateHue(mouseX, value, state);
            } else if (state.draggingOpacity && Mouse.isButtonDown(0)) {
                updateOpacity(mouseX, value, state);
            }
        }
    }

    /**
     * 更新 IntRangeValue 双滑块: 拖动 first 时若超过 last 则联动推进 last,反之亦然
     */
    private void updateIntRange(int mouseX, float sliderX, float sliderWidth, IntRangeValue value) {
        int min = value.getMinimum();
        int max = value.getMaximum();
        double raw = (mouseX - sliderX) * (max - min) / sliderWidth + min;
        int v = (int) MathHelper.clamp_double(Math.round(raw), min, max);
        int currentFirst = value.get().getFirst();
        int currentLast = value.get().getLast();
        if ("first".equals(draggingRangeThumb)) {
            if (v > currentLast) {
                // first 推过 last: 先把 last 提到 v,再设 first (避免 IntRange first>last 异常)
                value.setLast(v, false);
                value.setFirst(v, true);
            } else {
                value.setFirst(v, true);
            }
        } else if ("last".equals(draggingRangeThumb)) {
            if (v < currentFirst) {
                // last 推过 first: 先把 first 降到 v,再设 last
                value.setFirst(v, false);
                value.setLast(v, true);
            } else {
                value.setLast(v, true);
            }
        } else {
            // 未指定 thumb: 按距离判断
            int distFirst = Math.abs(v - currentFirst);
            int distLast = Math.abs(v - currentLast);
            if (distFirst <= distLast) {
                if (v > currentLast) {
                    value.setLast(v, false);
                    value.setFirst(v, true);
                } else {
                    value.setFirst(v, true);
                }
            } else {
                if (v < currentFirst) {
                    value.setFirst(v, false);
                    value.setLast(v, true);
                } else {
                    value.setLast(v, true);
                }
            }
        }
    }

    /**
     * 更新 FloatRangeValue 双滑块: 同样支持联动
     */
    private void updateFloatRange(int mouseX, float sliderX, float sliderWidth, FloatRangeValue value) {
        float min = value.getMinimum();
        float max = value.getMaximum();
        double raw = (mouseX - sliderX) * (max - min) / sliderWidth + min;
        float v = (float) MathHelper.clamp_double(raw, min, max);
        float currentStart = value.get().getStart();
        float currentEnd = value.get().getEndInclusive();
        if ("first".equals(draggingRangeThumb)) {
            if (v > currentEnd) {
                value.setLast(v, false);
                value.setFirst(v, true);
            } else {
                value.setFirst(v, true);
            }
        } else if ("last".equals(draggingRangeThumb)) {
            if (v < currentStart) {
                value.setFirst(v, false);
                value.setLast(v, true);
            } else {
                value.setLast(v, true);
            }
        } else {
            float distFirst = Math.abs(v - currentStart);
            float distLast = Math.abs(v - currentEnd);
            if (distFirst <= distLast) {
                if (v > currentEnd) {
                    value.setLast(v, false);
                    value.setFirst(v, true);
                } else {
                    value.setFirst(v, true);
                }
            } else {
                if (v < currentStart) {
                    value.setFirst(v, false);
                    value.setLast(v, true);
                } else {
                    value.setLast(v, true);
                }
            }
        }
    }

    private void updateFloat(int mouseX, float sliderX, float sliderWidth, FloatValue value) {
        float min = value.getMinimum();
        float max = value.getMaximum();
        double raw = (mouseX - sliderX) * (max - min) / sliderWidth + min;
        double stepped = Math.round(raw * 100D) / 100D;
        value.set((float) MathHelper.clamp_double(stepped, min, max), true);
    }

    private void updateInt(int mouseX, float sliderX, float sliderWidth, IntValue value) {
        int min = value.getMinimum();
        int max = value.getMaximum();
        double raw = (mouseX - sliderX) * (max - min) / sliderWidth + min;
        value.set((int) MathHelper.clamp_double(Math.round(raw), min, max), true);
    }

    private void updateHue(int mouseX, ColorValue value, ColorPickerState state) {
        value.setHueSliderY(MathHelper.clamp_float((mouseX - state.pickerX) / 100F, 0F, 1F));
        updateColorFromPicker(value);
    }

    private void updateOpacity(int mouseX, ColorValue value, ColorPickerState state) {
        value.setOpacitySliderY(MathHelper.clamp_float((mouseX - state.pickerX) / 100F, 0F, 1F));
        updateColorFromPicker(value);
    }

    private void updateColor(int mouseX, int mouseY, ColorValue value, ColorPickerState state) {
        float saturation = MathHelper.clamp_float((mouseX - state.pickerX) / 100F, 0F, 1F);
        float brightnessPicker = MathHelper.clamp_float((mouseY - state.pickerY) / 50F, 0F, 1F);
        value.getColorPickerPos().set(saturation, brightnessPicker);
        updateColorFromPicker(value);
    }

    private void updateColorFromPicker(ColorValue value) {
        Vector2f picker = value.getColorPickerPos();
        Color color = new Color(Color.HSBtoRGB(value.getHueSliderY(), picker.x, 1F - picker.y));
        value.set(new Color(color.getRed(), color.getGreen(), color.getBlue(), MathHelper.clamp_int((int) (value.getOpacitySliderY() * 255F), 0, 255)), true);
    }

    private List<Module> modulesInCategory(Category category) {
        return AirPlus.INSTANCE.getModuleManager().stream()
                .filter(module -> module.getCategory() == category)
                .collect(Collectors.toList());
    }

    private float valuesContentHeight() {
        if (selectedModule == null) return 0F;

        float contentHeight = 0F;
        for (Value<?> value : selectedModule.getValues()) {
            if (value.shouldRender()) contentHeight += valueHeight(value);
        }
        return contentHeight;
    }

    private float valueHeight(Value<?> value) {
        if (value instanceof ColorValue) {
            ColorValue colorValue = (ColorValue) value;
            return normalHeight() + (colorValue.getShowPicker() ? 77F : 5F);
        }
        if (value instanceof ListValue) {
            ListValue listValue = (ListValue) value;
            float modeX = posX + 100F + normalWidth(value.getName() + ": ");
            float rows = 1F;
            for (String mode : listValue.getValues()) {
                if (modeX > posX + width - 55F) {
                    modeX = posX + 100F + normalWidth(value.getName() + ": ");
                    rows += 1F;
                }
                modeX += normalWidth(mode + ", ");
            }
            return rows * normalHeight() + Math.max(0F, rows - 1F) * 2F + 6F;
        }
        return normalHeight() + 6F;
    }

    private float clampScroll(float scroll, float contentHeight, float visibleHeight) {
        float min = Math.min(0F, visibleHeight - contentHeight);
        return MathHelper.clamp_float(scroll, min, 0F);
    }

    private void updateAnimations() {
        long now = System.currentTimeMillis();
        float delta = Math.max(0F, Math.min(0.1F, (now - lastAnimationTime) / 1000F));
        lastAnimationTime = now;
        categoryLineX += (targetCategoryLineX - categoryLineX) * Math.min(1F, delta * 12F);
    }

    private Color accent() {
        return new Color(81, 149, 219);
    }

    private float width() {
        return width;
    }

    private float height() {
        return height;
    }

    private boolean hovered(int mouseX, int mouseY, float x, float y, float w, float h) {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }

    private void drawTitle(String text, float x, float y, int color) {
        Fonts.INSTANCE.getFont40().drawString(text, x, y, color);
    }

    private void drawNormal(String text, float x, float y, int color) {
        Fonts.INSTANCE.getFont35().drawString(text, x, y, color);
    }

    private float normalWidth(String text) {
        return Fonts.INSTANCE.getFont35().getStringWidth(text);
    }

    private float normalHeight() {
        return Fonts.INSTANCE.getFont35().getHeight();
    }

    private void drawRect(float x, float y, float w, float h, int color) {
        RenderUtils.INSTANCE.drawRect(x, y, x + w, y + h, color);
    }

    private void drawRounded(float x, float y, float w, float h, float radius, int color) {
        RenderUtils.INSTANCE.drawRoundedRect(x, y, x + w, y + h, color, radius, RenderUtils.RoundedCorners.ALL);
    }

    private void scissor(float x, float y, float w, float h, boolean start) {
        if (!start) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            return;
        }

        ScaledResolution sr = new ScaledResolution(mc);
        int factor = sr.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((int) (x * factor), (int) ((sr.getScaledHeight() - y - h) * factor), (int) (w * factor), (int) (h * factor));
    }

    /** 将文本截断到指定像素宽度内，超出部分以省略号结尾。 */
    private String truncateText(String text, float maxWidth) {
        if (text == null || text.isEmpty()) return "";
        if (maxWidth <= 0F || normalWidth(text) <= maxWidth) return text;
        String trimmed = text;
        while (trimmed.length() > 1 && normalWidth(trimmed + "…") > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "…";
    }

    /** 按像素宽度折行（中文按字符折行）。 */
    private List<String> wrapText(String text, float maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (line.length() > 0 && normalWidth(line.toString() + c) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder();
            }
            line.append(c);
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    /** 在鼠标旁绘制模块解释悬浮窗。 */
    private void drawModuleTooltip(int mouseX, int mouseY, String description) {
        if (description == null || description.isEmpty()) return;

        List<String> lines = wrapText(description, 190F);
        if (lines.isEmpty()) return;

        float textWidth = 0F;
        for (String line : lines) {
            textWidth = Math.max(textWidth, normalWidth(line));
        }
        float padX = 6F;
        float padY = 4F;
        float w = textWidth + padX * 2F;
        float h = lines.size() * (normalHeight() + 2F) - 2F + padY * 2F;

        ScaledResolution sr = new ScaledResolution(mc);
        float x = mouseX + 10F;
        float y = mouseY + 10F;
        if (x + w > sr.getScaledWidth() - 2F) x = mouseX - w - 8F;
        if (y + h > sr.getScaledHeight() - 2F) y = mouseY - h - 8F;
        x = Math.max(2F, x);
        y = Math.max(2F, y);

        drawRounded(x, y, w, h, 6F, new Color(18, 18, 18, 240).getRGB());
        float textY = y + padY;
        for (String line : lines) {
            drawNormal(line, x + padX, textY, new Color(210, 210, 210).getRGB());
            textY += normalHeight() + 2F;
        }
    }
}
