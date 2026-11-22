package net.airplus.ui.glass;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiHopper;
import net.minecraft.client.gui.inventory.GuiBrewingStand;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiCrafting;
import net.minecraft.client.gui.inventory.GuiDispenser;
import net.minecraft.client.gui.inventory.GuiFurnace;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import java.util.Collection;
import java.util.List;

/**
 * Glass replacement for the grey vanilla container textures: one panel, a rounded well under
 * every slot (taken from the container itself, so any chest size fits) and drawn versions of
 * the few moving parts (furnace flame and progress, brewing progress, the player preview).
 * Slot positions, items, labels and all behaviour stay vanilla.
 */
public final class ContainerSkin {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final ResourceLocation INVENTORY = new ResourceLocation("textures/gui/container/inventory.png");
    private static final float PAD = 4;

    /** Colour vanilla labels are drawn in on these screens instead of their dark grey. */
    public static final int LABEL = 0xffdfe3ea;
    /** True while a skinned container draws its foreground: {@code MixinFontRenderer} lightens the labels. */
    public static boolean recolor;

    // ── Styles New 模式：大圆角方格 + blur 浮层 + blur 物品信息卡 ──

    /** Styles New 模式进行中（由 {@link #draw} 每帧刷新，供 mixin 调整悬停高亮与物品放大）。 */
    private static boolean newLook;

    public static boolean isNewLook() {
        return newLook;
    }

    /** 面板淡入动画：界面打开后的第一帧从 0 淡入到 1。 */
    private static final Anim.Smooth OPEN = new Anim.Smooth(0, 9);
    private static Object openScreen;

    /** 信息卡淡入动画：换到另一个槽位时重新淡入。 */
    private static final Anim.Smooth TOOLTIP_SHOW = new Anim.Smooth(0, 22);
    private static Slot tooltipSlot;

    private ContainerSkin() {
    }

    /**
     * Only the exact vanilla screens: a mod's subclass may lay its texture out differently.
     * The enchanting table, anvil, beacon, villager, horse and creative screens draw too much
     * from their textures and keep the framed vanilla look.
     */
    public static boolean handles(GuiContainer screen) {
        Class<?> type = screen.getClass();
        return type == GuiInventory.class || type == GuiChest.class || type == GuiCrafting.class || type == GuiFurnace.class
                || type == GuiDispenser.class || type == GuiHopper.class || type == GuiBrewingStand.class;
    }

    /** Stands in for {@code drawGuiContainerBackgroundLayer}. */
    public static void draw(GuiContainer screen, int left, int top, int xSize, int ySize, int mouseX, int mouseY) {
        Draw.syncGuiScale();
        float x = left - PAD, y = top - PAD, w = xSize + PAD * 2, h = ySize + PAD * 2;
        // 背板模糊模式（CustomGui.Background-Blur）：Liquid = 液态玻璃透镜（默认），
        // Frosted = 磨砂模糊（无透镜折射），New = 大圆角 blur 浮层（更强投影 + 淡入），
        // Off = 不请求模糊纹理的实色玻璃。
        String mode = net.airplus.features.module.modules.client.CustomGui.INSTANCE.getBlurMode();
        newLook = "New".equals(mode);
        if (newLook) {
            // 淡入：界面打开后 ~0.4s 内面板渐显（物品是原版绘制，不参与透明度）
            if (screen != openScreen) {
                OPEN.snap(0);
                openScreen = screen;
            }
            Draw.pushAlpha(OPEN.update(true, Anim.dt()));
            GlassTheme.panel(x, y, w, h, 14, 2.0f, GuiPalette.GLASS, false, true);
            Draw.popAlpha();
        } else if ("Off".equals(mode)) {
            GlassTheme.panel(x, y, w, h, GlassTheme.R_PANEL, 1.3f, GuiPalette.GLASS, false, false);
        } else if ("Frosted".equals(mode)) {
            GlassTheme.panel(x, y, w, h, GlassTheme.R_PANEL, 1.3f, GuiPalette.GLASS, false, true);
        } else {
            GlassTheme.panel(x, y, w, h, GlassTheme.R_PANEL, 1.3f);
        }
        ScreenTransition.focus(x, y, w, h, GlassTheme.R_PANEL);

        List<Slot> slots = screen.inventorySlots.inventorySlots;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            well(left + slot.xDisplayPosition - 1, top + slot.yDisplayPosition - 1);
        }

        int accent = GlassTheme.accent();
        if (screen instanceof GuiInventory) {
            // The stage the player preview stands on, then the 2x2 crafting arrow.
            GlassTheme.field(left + 26, top + 8, 52, 70, 6, 0);
            arrow(left + 127, top + 44.5f, 9, 0x99ffffff);
            GlStateManager.color(1, 1, 1, 1);
            GuiInventory.drawEntityOnScreen(left + 51, top + 75, 30, left + 51 - mouseX, top + 75 - 50 - mouseY, mc.thePlayer);
        } else if (screen instanceof GuiCrafting) {
            arrow(left + 91, top + 43.5f, 18, 0x99ffffff);
        } else if (screen instanceof GuiFurnace && !slots.isEmpty()) {
            IInventory furnace = slots.get(0).inventory;
            int burn = furnace.getField(0), burnTotal = furnace.getField(1), cook = furnace.getField(2), cookTotal = furnace.getField(3);
            float fire = burn > 0 ? Math.min(1, burn / (float) (burnTotal == 0 ? 200 : burnTotal)) : 0;
            float done = cook > 0 && cookTotal > 0 ? Math.min(1, cook / (float) cookTotal) : 0;
            // Fuel left: a small column between the input and the fuel slot, draining downwards.
            Draw.rect(left + 62, top + 37, 4, 13, 2, 0x5a000000);
            if (fire > 0.01f) {
                float filled = Math.max(4, 13 * fire);
                Draw.glow(left + 62, top + 50 - filled, 4, filled, 2, 5, Draw.alpha(0xff9f0a, 0.45f));
                Draw.rect(left + 62, top + 50 - filled, 4, filled, 2, 0xffff9f0a);
            }
            progress(left + 80, top + 42, 22, done, accent);
        } else if (screen instanceof GuiBrewingStand && !slots.isEmpty()) {
            int time = slots.get(0).inventory.getField(0);
            float done = time > 0 ? Math.max(0, Math.min(1, 1 - time / 400f)) : 0;
            Draw.rect(left + 99, top + 17, 3, 26, 1.5f, 0x5a000000);
            if (done > 0.01f) Draw.rect(left + 99, top + 17, 3, Math.max(3, 26 * done), 1.5f, accent);
        }
        GlStateManager.enableTexture2D();
        GlStateManager.color(1, 1, 1, 1);
    }

    /** One slot: a soft recess, darker along its top edge and lit along the bottom. */
    private static void well(float x, float y) {
        if (newLook) {
            // Styles New：平滑大圆角方形井格，浅白底 + 极细亮描边（不再是深色凹陷井格）
            Draw.rect(x, y, 18, 18, 6, 0x0effffff);
            Draw.outline(x, y, 18, 18, 6, 0.6f, 0x18ffffff, 0x0cffffff);
            return;
        }
        Draw.rect(x, y, 18, 18, 4, 0x38000000);
        Draw.outline(x, y, 18, 18, 4, 0.6f, 0x30000000, 0x22ffffff);
    }

    // ── Styles New：物品信息卡（blur + shadow，悬停时淡入）──

    /** 非悬停帧重置信息卡动画，保证下一次悬停总是重新淡入。 */
    public static void tooltipIdle() {
        if (TOOLTIP_SHOW.value > 0) {
            TOOLTIP_SHOW.snap(0);
            tooltipSlot = null;
        }
    }

    /**
     * Styles New 模式的物品信息卡：blur + shadow 的玻璃卡替代原版紫色渐变框，
     * 文字走客户端 {@code FontRenderer}（已被客户端字体渲染接管）。
     * 返回 true 表示已接管本次 tooltip 绘制。
     */
    public static boolean tooltip(Slot slot, ItemStack stack, int x, int y) {
        if (!newLook) return false;
        if (slot != tooltipSlot) {
            TOOLTIP_SHOW.snap(0);
            tooltipSlot = slot;
        }
        float show = TOOLTIP_SHOW.update(true, Anim.dt());
        if (show <= 0.01f) return true;

        List<String> lines = stack.getTooltip(mc.thePlayer, mc.gameSettings.advancedItemTooltips);
        for (int i = 0; i < lines.size(); i++)
            lines.set(i, (i == 0 ? stack.getRarity().rarityColor : EnumChatFormatting.GRAY) + (String) lines.get(i));
        FontRenderer font = mc.fontRendererObj;

        int tw = 0;
        for (String line : lines) tw = Math.max(tw, font.getStringWidth(line));
        tw += 10;
        int th = lines.size() * 10 + 6;
        int tx = x + 9, ty = y - 14;
        if (mc.currentScreen != null) {
            tx = Math.max(4, Math.min(tx + tw > mc.currentScreen.width - 4 ? x - tw - 9 : tx, mc.currentScreen.width - tw - 4));
            ty = Math.max(4, Math.min(ty, mc.currentScreen.height - th - 4));
        }

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        Draw.pushAlpha(show);
        GlassTheme.panel(tx, ty, tw, th, 8, 1.4f, GuiPalette.GLASS, false, true);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1, 1, 1, 1);
        int cy = ty + 4;
        for (String line : lines) {
            font.drawStringWithShadow(line, tx + 5, cy, Draw.fade(0xffffffff, show));
            cy += 10;
        }
        Draw.popAlpha();
        GlStateManager.enableDepth();
        return true;
    }

    /** A line ending in an arrow head pointing right, starting at {@code x}. */
    private static void arrow(float x, float y, float length, int color) {
        Draw.polyline(1.3f, color, x, y, x + length, y);
        Draw.chevron(x + length - 1, y, 3, 0, 1.3f, color);
    }

    /** A horizontal progress track with an arrow head at its end. */
    private static void progress(float x, float y, float length, float amount, int accent) {
        Draw.rect(x, y - 1.5f, length - 4, 3, 1.5f, 0x5a000000);
        if (amount > 0.01f) Draw.rect(x, y - 1.5f, Math.max(3, (length - 4) * amount), 3, 1.5f, accent);
        Draw.chevron(x + length - 1, y, 3, 0, 1.3f, amount >= 0.99f ? accent : 0x99ffffff);
    }

    /** Active potion effects beside the inventory, as glass cards instead of the grey badges. */
    public static void effects(int left, int top) {
        if (mc.thePlayer == null) return;
        Collection<PotionEffect> effects = mc.thePlayer.getActivePotionEffects();
        if (effects.isEmpty()) return;
        Draw.syncGuiScale();
        SmoothFont title = SmoothFont.bold(8.5f), detail = SmoothFont.regular(8);
        int x = left - 124, y = top - (int) PAD;
        int step = effects.size() > 5 ? 132 / (effects.size() - 1) : 33;
        GlStateManager.disableLighting();
        for (PotionEffect effect : effects) {
            Potion potion = Potion.potionTypes[effect.getPotionID()];
            if (potion == null) continue;
            GlassTheme.panel(x, y, 114, 29, GlassTheme.R_CARD, 0.6f);
            if (potion.hasStatusIcon()) {
                int index = potion.getStatusIconIndex();
                GlStateManager.color(1, 1, 1, 1);
                GlStateManager.enableBlend();
                mc.getTextureManager().bindTexture(INVENTORY);
                Gui.drawModalRectWithCustomSizedTexture(x + 6, y + 5, index % 8 * 18, 198 + index / 8 * 18, 18, 18, 256, 256);
            }
            potion.renderInventoryEffect(x, y, effect, mc);
            if (potion.shouldRenderInvText(effect)) {
                String name = I18n.format(potion.getName());
                int level = effect.getAmplifier();
                if (level >= 1 && level <= 3) name = name + " " + I18n.format("enchantment.level." + (level + 1));
                title.draw(title.trim(name, 80), x + 29, y + 5, GlassTheme.TEXT);
                detail.draw(Potion.getDurationString(effect), x + 29, y + 15.5f, GlassTheme.MUTED);
            }
            y += step;
        }
        GlStateManager.enableTexture2D();
        GlStateManager.color(1, 1, 1, 1);
    }
}
