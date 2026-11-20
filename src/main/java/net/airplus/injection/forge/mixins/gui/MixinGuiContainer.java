package net.airplus.injection.forge.mixins.gui;

import net.airplus.config.ColorValue;
import net.airplus.features.module.modules.client.CustomGui;
import net.airplus.features.module.modules.client.GuiImage;
import net.airplus.features.module.modules.player.AutoArmor;
import net.airplus.features.module.modules.player.InventoryCleaner;
import net.airplus.features.module.modules.world.ChestStealer;
import net.airplus.ui.glass.Anim;
import net.airplus.ui.glass.ContainerSkin;
import net.airplus.ui.glass.Draw;
import net.airplus.ui.glass.GlassTheme;
import net.airplus.utils.inventory.InventoryManager;
import net.airplus.utils.render.RenderUtils;
import net.airplus.utils.timing.TickTimer;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiContainer.class)
@SideOnly(Side.CLIENT)
public abstract class MixinGuiContainer extends MixinGuiScreen {

    // Separate TickTimer instances to avoid timing conflicts
    @Unique
    final TickTimer tick0 = new TickTimer();
    @Unique
    final TickTimer tick1 = new TickTimer();
    @Unique
    final TickTimer tick2 = new TickTimer();

    // ── CustomGui: glass container skin (ported from BetterMyau's container screens) ──

    @Shadow
    protected int xSize;
    @Shadow
    protected int ySize;
    @Shadow
    protected int guiLeft;
    @Shadow
    protected int guiTop;
    @Shadow
    private Slot theSlot;

    @Shadow
    protected abstract void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY);

    // drawGradientRect 声明在 Gui（GuiScreen 的父类）而非 GuiContainer，@Shadow 放在这里
    // 会因运行时查不到而崩溃（func_73733_a not located）；已挪到 MixinGui（目标类 Gui）中继承。

    /** This frame, the glass skin stands in for the vanilla texture. */
    @Unique
    private boolean airplus$skinned;

    /** After the dim backdrop: decide whether the glass skin replaces the vanilla texture. */
    @Inject(method = "drawScreen", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/inventory/GuiContainer;drawDefaultBackground()V", shift = At.Shift.AFTER), require = 0)
    private void airplus$glassFrame(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        ContainerSkin.recolor = false;
        airplus$skinned = CustomGui.INSTANCE.shouldSkinContainers((GuiContainer) (Object) this);
    }

    /** The grey texture, or the glass skin in its place. */
    @Redirect(method = "drawScreen", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/inventory/GuiContainer;drawGuiContainerBackgroundLayer(FII)V"), require = 0)
    private void airplus$glassBackground(GuiContainer screen, float partialTicks, int mouseX, int mouseY) {
        if (airplus$skinned) ContainerSkin.draw(screen, this.guiLeft, this.guiTop, this.xSize, this.ySize, mouseX, mouseY);
        else this.drawGuiContainerBackgroundLayer(partialTicks, mouseX, mouseY);
    }

    /** Labels on the glass skin would be dark grey on dark glass: lighten them for this phase. */
    @Inject(method = "drawScreen", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/inventory/GuiContainer;drawGuiContainerForegroundLayer(II)V"), require = 0)
    private void airplus$beforeLabels(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        ContainerSkin.recolor = airplus$skinned;
        bettermyau$lateHighlight = true;
        boolean hovered = bettermyau$slotHovered;
        bettermyau$slotHovered = false;
        airplus$trackHover();
        if (!airplus$highlight()) return;
        // Highlight-Speed：用户自定义的格子动画倍率（高亮框滑动 + 显隐）
        float dt = Anim.dt() * CustomGui.INSTANCE.getHighlightSpeed();
        if (hovered) {
            if (bettermyau$slotShow.value < 0.05f) {
                bettermyau$slotX.snap(bettermyau$slotTargetX);
                bettermyau$slotY.snap(bettermyau$slotTargetY);
            }
            bettermyau$slotX.update(bettermyau$slotTargetX, dt);
            bettermyau$slotY.update(bettermyau$slotTargetY, dt);
        }
        float show = bettermyau$slotShow.update(hovered, dt);
        if (show <= 0.01f) return;
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        airplus$paintSlot(bettermyau$slotX.value, bettermyau$slotY.value, show);
        GlStateManager.enableDepth();
        GlStateManager.color(1, 1, 1, 1);
    }

    // The slot highlight glides from slot to slot and fades out when the cursor leaves the slots.

    /** Glass UI is on for container screens: the animated slot highlight replaces the flat white overlay. */
    @Unique
    private boolean airplus$highlight() {
        return CustomGui.INSTANCE.handleEvents() && CustomGui.INSTANCE.getContainers();
    }

    @Unique private final Anim.Spring bettermyau$slotX = Anim.spring(0, 0.2f, 0.82f);
    @Unique private final Anim.Spring bettermyau$slotY = Anim.spring(0, 0.2f, 0.82f);
    @Unique private final Anim.Smooth bettermyau$slotShow = new Anim.Smooth(0, 18);
    @Unique private float bettermyau$slotTargetX, bettermyau$slotTargetY, bettermyau$slotSize = 18;
    @Unique private boolean bettermyau$slotHovered, bettermyau$lateHighlight;

    /** Hovered slot: rounded accent highlight instead of the flat white overlay. */
    @Redirect(method = "drawScreen", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/inventory/GuiContainer;drawGradientRect(IIIIII)V"), require = 0)
    private void airplus$slotHighlight(GuiContainer screen, int left, int top, int right, int bottom, int startColor, int endColor) {
        if (!airplus$highlight()) {
            this.drawGradientRect(left, top, right, bottom, startColor, endColor);
            return;
        }
        bettermyau$slotTargetX = left - 1;
        bettermyau$slotTargetY = top - 1;
        bettermyau$slotSize = right - left + 2;
        bettermyau$slotHovered = true;
        // Until the late hook above has proven it runs, draw right here like before.
        if (!bettermyau$lateHighlight) airplus$paintSlot(bettermyau$slotTargetX, bettermyau$slotTargetY, 1);
    }

    @Unique
    private void airplus$paintSlot(float x, float y, float show) {
        Draw.syncGuiScale();
        // Highlight-Color：用户自定义的高亮颜色（默认与玻璃主题 accent 一致）
        int accent = CustomGui.INSTANCE.getHighlightColorValue().selectedColor().getRGB();
        float size = bettermyau$slotSize;
        Draw.pushAlpha(show);
        Draw.rect(x, y, size, size, airplus$skinned ? 4 : 3, Draw.alpha(0xffffff, 0.22f));
        Draw.outline(x, y, size, size, airplus$skinned ? 4 : 3, 0.75f, Draw.alpha(accent, 0.9f), Draw.alpha(accent, 0.45f));
        Draw.popAlpha();
    }

    // ── The item under the cursor grows a little; the one just left shrinks back.
    @Unique private Slot bettermyau$grownSlot, bettermyau$shrinkingSlot;
    @Unique private final Anim.Smooth bettermyau$grow = new Anim.Smooth(0, 20), bettermyau$shrink = new Anim.Smooth(0, 20);
    @Unique private boolean bettermyau$slotScaled;

    /** Once per frame, after the slots were drawn: {@code theSlot} is this frame's hovered slot. */
    @Unique
    private void airplus$trackHover() {
        // Highlight-Speed：光标下物品的放大/缩小动画同样按倍率加速
        float dt = Anim.dt() * CustomGui.INSTANCE.getHighlightSpeed();
        Slot hovered = this.theSlot != null && this.theSlot.getHasStack() ? this.theSlot : null;
        if (hovered != bettermyau$grownSlot) {
            bettermyau$shrinkingSlot = bettermyau$grownSlot;
            bettermyau$shrink.snap(bettermyau$grow.value);
            bettermyau$grownSlot = hovered;
            bettermyau$grow.snap(0);
        }
        bettermyau$grow.update(bettermyau$grownSlot != null, dt);
        if (bettermyau$shrink.update(0, dt) <= 0.01f) bettermyau$shrinkingSlot = null;
    }

    @Inject(method = "drawSlot", at = @At("HEAD"), require = 0)
    private void airplus$growSlot(Slot slot, CallbackInfo ci) {
        bettermyau$slotScaled = false;
        if (slot == null || !airplus$highlight()) return;
        float amount = slot == bettermyau$grownSlot ? bettermyau$grow.value : slot == bettermyau$shrinkingSlot ? bettermyau$shrink.value : 0;
        if (amount <= 0.01f) return;
        float cx = slot.xDisplayPosition + 8, cy = slot.yDisplayPosition + 8, scale = 1 + 0.12f * amount;
        GlStateManager.pushMatrix();
        GlStateManager.translate(cx, cy, 0);
        GlStateManager.scale(scale, scale, 1);
        GlStateManager.translate(-cx, -cy, 0);
        bettermyau$slotScaled = true;
    }

    @Inject(method = "drawSlot", at = @At("RETURN"), require = 0)
    private void airplus$growSlotEnd(Slot slot, CallbackInfo ci) {
        if (!bettermyau$slotScaled) return;
        bettermyau$slotScaled = false;
        GlStateManager.popMatrix();
    }

    @Inject(method = "drawScreen", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/inventory/GuiContainer;drawGuiContainerForegroundLayer(II)V", shift = At.Shift.AFTER), require = 0)
    private void airplus$afterLabels(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        ContainerSkin.recolor = false;
    }

    /**
     * GuiImage：容器类界面（箱子/背包等 GuiContainer 子类）drawScreen 尾部绘制自定义图片。
     * GuiImage 内部按白名单过滤（仅 GuiChest / GuiInventory）。
     * ChestStealer SilentGUI 的静默箱子不再创建 GuiScreen，由此天然跳过本注入。
     */
    @Inject(method = "drawScreen", at = @At("TAIL"))
    private void airplus$guiImageDraw(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        GuiImage.INSTANCE.onGuiRender(mc.currentScreen);
    }

    @Inject(method = "drawSlot", at = @At("HEAD"))
    private void drawSlot(Slot slot, CallbackInfo ci) {
        // Instances
        final InventoryManager inventoryManager = InventoryManager.INSTANCE;
        final ChestStealer chestStealer = ChestStealer.INSTANCE;
        final InventoryCleaner inventoryCleaner = InventoryCleaner.INSTANCE;
        final AutoArmor autoArmor = AutoArmor.INSTANCE;
        final RenderUtils renderUtils = RenderUtils.INSTANCE;

        // Slot X/Y
        int x = slot.xDisplayPosition;
        int y = slot.yDisplayPosition;

        // ChestStealer Highlight Values
        int chestStealerBackgroundColor = ((ColorValue) chestStealer.getBackgroundColor()).selectedColor().getRGB();
        int chestStealerBorderColor = ((ColorValue) chestStealer.getBorderColor()).selectedColor().getRGB();

        // InvCleaner & AutoArmor Highlight Values
        int invManagerBackgroundColor = ((ColorValue) inventoryManager.getBackgroundColor()).selectedColor().getRGB();
        int invManagerBorderColor = ((ColorValue) inventoryManager.getBorderColor()).selectedColor().getRGB();

        // Get the current slot being stolen
        int currentSlotChestStealer = inventoryManager.getChestStealerCurrentSlot();
        int currentSlotInvCleaner = inventoryManager.getInvCleanerCurrentSlot();
        int currentSlotAutoArmor = inventoryManager.getAutoArmorCurrentSlot();

        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT);
        GL11.glDisable(GL11.GL_LIGHTING);

        if (mc.currentScreen instanceof GuiChest) {
            if (chestStealer.handleEvents() && !chestStealer.getSilentGUI() && chestStealer.getHighlightSlot()) {
                if (slot.slotNumber == currentSlotChestStealer && currentSlotChestStealer != -1 && currentSlotChestStealer != inventoryManager.getChestStealerLastSlot()) {
                    renderUtils.drawBorderedRect(x, y, x + 16, y + 16, chestStealer.getBorderStrength(), chestStealerBorderColor, chestStealerBackgroundColor);

                    // Prevent rendering the highlighted rectangle twice
                    if (!slot.getHasStack() && tick0.hasTimePassed(100)) {
                        inventoryManager.setChestStealerLastSlot(currentSlotChestStealer);
                        tick0.reset();
                    } else {
                        tick0.update();
                    }
                }
            }
        }

        if (mc.currentScreen instanceof GuiInventory) {
            if (inventoryManager.getHighlightSlotValue().get()) {
                if (inventoryCleaner.handleEvents()) {
                    if (slot.slotNumber == currentSlotInvCleaner && currentSlotInvCleaner != -1 && currentSlotInvCleaner != inventoryManager.getInvCleanerLastSlot()) {
                        renderUtils.drawBorderedRect(x, y, x + 16, y + 16, inventoryManager.getBorderStrength().get(), invManagerBorderColor, invManagerBackgroundColor);

                        // Prevent rendering the highlighted rectangle twice
                        if (!slot.getHasStack() && tick1.hasTimePassed(100)) {
                            inventoryManager.setInvCleanerLastSlot(currentSlotInvCleaner);
                            tick1.reset();
                        } else {
                            tick1.update();
                        }
                    }
                }

                if (autoArmor.handleEvents()) {
                    if (slot.slotNumber == currentSlotAutoArmor && currentSlotAutoArmor != -1 && currentSlotAutoArmor != inventoryManager.getAutoArmorLastSlot()) {
                        renderUtils.drawBorderedRect(x, y, x + 16, y + 16, inventoryManager.getBorderStrength().get(), invManagerBorderColor, invManagerBackgroundColor);

                        // Prevent rendering the highlighted rectangle twice
                        if (!slot.getHasStack() && tick2.hasTimePassed(100)) {
                            inventoryManager.setAutoArmorLastSlot(currentSlotAutoArmor);
                            tick2.reset();
                        } else {
                            tick2.update();
                        }
                    }
                }
            }
        }

        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }
}
