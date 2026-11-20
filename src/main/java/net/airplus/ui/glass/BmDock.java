package net.airplus.ui.glass;

import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;

/**
 * The in-game hotbar as a floating glass dock: a highlight that springs from slot to slot, the
 * held item lifted and enlarged, and above it one slim capsule with health, armour, hunger, air
 * and the experience level. Replaces the vanilla hotbar and status icons while the HUD module's
 * "BM" Hotbar-Style is on; drawn in the 2D pass so it shares the frame's blur with the rest of
 * the HUD. Ported from BetterMyau's Dock, reading settings through {@link Settings}.
 */
public final class BmDock {
    /** Settings come from the HUD module (BM properties) instead of BetterMyau's Hotbar module. */
    public interface Settings {
        boolean statusBars();

        boolean healthNumbers();

        boolean itemName();

        boolean magnify();

        /** 80..130, like the original Hotbar "Scale" property. */
        int scalePercent();

        /** 0..20, like the original Hotbar "Offset Y" property. */
        int offsetY();
    }

    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final float SLOT = 20, PAD = 2, RADIUS = 8;
    public static final float WIDTH = SLOT * 9 + PAD * 2, HEIGHT = SLOT + PAD * 2;
    private static final float STATUS_H = 12, GAP = 4;

    private static final int HEALTH = 0xffff453a, POISON = 0xff9bd64a, WITHER = 0xff6b6470, ABSORB = 0xffffd60a;
    private static final int FOOD = 0xffff9f0a, HUNGER = 0xff8fa650, ARMOR = 0xffc9d3e0, AIR = 0xff64d2ff, XP = 0xff80ff20;

    private static final Anim.SpringRect selector = new Anim.SpringRect(0.26f, 0.7f);
    private static final Anim.Smooth[] lift = new Anim.Smooth[9];
    private static final Anim.Smooth health = new Anim.Smooth(1, 14), ghost = new Anim.Smooth(1, 3.2f);
    private static final Anim.Smooth absorb = new Anim.Smooth(0, 14), second = new Anim.Smooth(1, 14);
    private static final Anim.Smooth armor = new Anim.Smooth(0, 14), air = new Anim.Smooth(1, 14), airShow = new Anim.Smooth(0, 12);
    private static final Anim.Smooth line = new Anim.Smooth(0, 14);
    private static final Anim.Spring nameShow = Anim.spring(0, 0.3f, 0.82f);

    private static ItemStack lastStack;
    private static String name = "";
    private static float nameTimer;
    private static int lastSlot = -1;
    private static long swapAt;

    static {
        for (int i = 0; i < lift.length; i++) lift[i] = new Anim.Smooth(0, 18);
    }

    private BmDock() {
    }

    /** The player changed the slot themselves (number key or wheel): the next change gets a sound. */
    public static void userSwap() {
        swapAt = System.nanoTime();
    }

    /** Space, from the bottom of the screen, that the dock and its status capsule occupy. */
    public static int reservedHeight(Settings settings) {
        return Math.round((settings.offsetY() + HEIGHT + GAP + STATUS_H + 2) * settings.scalePercent() / 100f);
    }

    /** Per-frame entry: resets batch state, syncs the gui scale, draws, restores HUD state. */
    public static void render(Settings settings, EntityPlayer player, float partialTicks) {
        GlassTheme.beginFrame();
        GlState.hudDefaults();
        Draw.useGuiScale();
        dock(settings, player, partialTicks);
        GlState.hudDefaults();
    }

    private static void dock(Settings settings, EntityPlayer player, float partialTicks) {
        float dt = Anim.dt();
        // Whole units, like ScaledResolution, so items and sprites land on pixels.
        int sw = (int) Math.ceil(mc.displayWidth / Draw.scale()), sh = (int) Math.ceil(mc.displayHeight / Draw.scale());
        float x = sw / 2 - WIDTH / 2, y = sh - settings.offsetY() - HEIGHT;
        boolean status = settings.statusBars() && mc.playerController.shouldDrawHUD();
        float top = status ? y - GAP - STATUS_H : y;

        Draw.pushTransform(sw / 2f, sh, settings.scalePercent() / 100f, 0, 0);
        if (status) status(settings, player, x, y - GAP - STATUS_H, dt);

        GlassTheme.panel(x, y, WIDTH, HEIGHT, RADIUS, 0.8f);
        int slot = Math.max(0, Math.min(8, player.inventory.currentItem));
        if (slot != lastSlot) {
            if (lastSlot >= 0 && System.nanoTime() - swapAt < 200_000_000L) UiSound.hotbar(slot);
            lastSlot = slot;
        }
        selector.to(x + PAD + slot * SLOT, y + PAD, SLOT, SLOT, 6, dt);
        // Stretches along its path while it travels, like something soft being flicked.
        float stretch = Math.min(6, Math.abs(selector.x.velocity) * 0.016f);
        float hx = selector.x.value - stretch / 2, hy = y + PAD + stretch * 0.1f, hw = SLOT + stretch, hh = SLOT - stretch * 0.2f;
        Draw.glow(hx, hy, hw, hh, 6, 6, Draw.alpha(GlassTheme.accent(), 0.2f));
        Draw.rect(hx, hy, hw, hh, 6, Draw.alpha(0xffffff, 0.16f));
        Draw.outline(hx, hy, hw, hh, 6, 0.75f, Draw.alpha(0xffffff, 0.62f), Draw.alpha(0xffffff, 0.2f));

        if (settings.itemName() && mc.gameSettings.heldItemTooltips) itemName(player, sw / 2f, top, dt);
        items(settings, player, x, y, slot, partialTicks, dt);
        Draw.popTransform();
    }

    // ---------------------------------------------------------------- items

    private static void items(Settings settings, EntityPlayer player, float x, float y, int slot, float partialTicks, float dt) {
        int state = GlState.capture();
        RenderItem renderer = mc.getRenderItem();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        RenderHelper.enableGUIStandardItemLighting();
        boolean magnify = settings.magnify();
        for (int i = 0; i < 9; i++) {
            float raised = lift[i].update(magnify && i == slot ? 1 : 0, dt);
            ItemStack stack = player.inventory.mainInventory[i];
            if (stack == null) continue;
            int ix = (int) (x + PAD + i * SLOT + 2), iy = (int) (y + PAD + 2);
            // Vanilla's little squash when a stack has just been picked up.
            float bounce = stack.animationsToGo - partialTicks;
            boolean transform = raised > 0.01f || bounce > 0;
            if (transform) {
                GlStateManager.pushMatrix();
                GlStateManager.translate(ix + 8, iy + 12 - raised * 1.4f, 0);
                float grow = 1 + raised * 0.13f;
                if (bounce > 0) {
                    float f = 1 + bounce / 5f;
                    GlStateManager.scale(grow / f, grow * (f + 1) / 2f, 1);
                } else {
                    GlStateManager.scale(grow, grow, 1);
                }
                GlStateManager.translate(-(ix + 8), -(iy + 12), 0);
            }
            renderer.renderItemAndEffectIntoGUI(stack, ix, iy);
            if (transform) GlStateManager.popMatrix();
            renderer.renderItemOverlays(mc.fontRendererObj, stack, ix, iy);
        }
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableRescaleNormal();
        GlState.restore(state);
        GlState.hudDefaults();
    }

    /** The held item's name in a small capsule, shown for two seconds after the item changes. */
    private static void itemName(EntityPlayer player, float centerX, float top, float dt) {
        ItemStack current = player.inventory.getCurrentItem();
        if (current == null) {
            nameTimer = 0;
        } else {
            boolean same = lastStack != null && current.getItem() == lastStack.getItem()
                    && ItemStack.areItemStackTagsEqual(current, lastStack)
                    && (current.isItemStackDamageable() || current.getMetadata() == lastStack.getMetadata());
            if (!same) {
                nameTimer = 2;
                name = current.getRarity().rarityColor + current.getDisplayName();
            }
        }
        lastStack = current;
        if (nameTimer > 0) nameTimer -= dt;
        float show = nameShow.update(nameTimer > 0 ? 1 : 0, dt);
        if (show <= 0.02f) return;

        SmoothFont font = SmoothFont.regular(8.5f);
        float w = font.width(name) + 16, h = 15, px = centerX - w / 2, py = top - 5 - h;
        Draw.pushTransform(centerX, py + h / 2, Anim.lerp(0.9f, 1, Math.min(1, show)), 0, (1 - Math.min(1, show)) * 4);
        Draw.pushAlpha(show);
        GlassTheme.panel(px, py, w, h, h / 2, 0.5f);
        font.drawCentered(name, centerX, font.centerY(py, h) - 0.5f, GlassTheme.TEXT);
        Draw.popAlpha();
        Draw.popTransform();
    }

    // ---------------------------------------------------------------- status

    private static void status(Settings settings, EntityPlayer player, float x, float y, float dt) {
        GlassTheme.panel(x, y, WIDTH, STATUS_H, STATUS_H / 2, 0.5f);
        boolean numbers = settings.healthNumbers();
        SmoothFont font = SmoothFont.bold(7.5f);
        float mid = x + WIDTH / 2, textY = font.centerY(y, STATUS_H) - 0.5f;
        float numberWidth = numbers ? font.width("20.0") + 3 : 0;
        float barLeft = x + 15 + numberWidth, barRight = mid - 13, barWidth = barRight - barLeft;
        float barY = y + 3, thinY = y + 8.4f;

        // Health on the left, armour as a hairline under it.
        float max = Math.max(1, player.getMaxHealth()), hp = Math.max(0, player.getHealth()), extra = player.getAbsorptionAmount();
        float shown = health.update(Math.min(1, hp / max), dt);
        // The pale trail left behind for a moment after taking damage.
        if (ghost.value < shown) ghost.snap(shown);
        ghost.update(shown, dt);
        boolean poison = player.isPotionActive(Potion.poison), wither = player.isPotionActive(Potion.wither);
        int color = wither ? WITHER : poison ? POISON : HEALTH;
        float pulse = hp / max <= 0.3f && !Anim.reduced() ? 0.72f + 0.28f * (float) Math.sin(System.nanoTime() / 1.4e8) : 1;
        sprite(x + 4, y + 1.5f, 16, 52 + (wither ? 72 : poison ? 36 : 0), hardcore() ? 45 : 0);
        if (numbers) font.draw(number(hp), x + 15, textY, GlassTheme.TEXT);
        track(barLeft, barY, barWidth, 4);
        if (ghost.value > shown + 0.004f) fill(barLeft, barY, barWidth, 4, ghost.value, Draw.alpha(0xffffff, 0.75f), false);
        if (pulse < 1) Draw.glow(barLeft, barY, barWidth * shown, 4, 2, 5, Draw.alpha(color, 0.5f * (1 - pulse) / 0.28f));
        fill(barLeft, barY, barWidth, 4, shown, Draw.fade(color, pulse), false);
        float golden = absorb.update(Math.min(1, extra / max), dt);
        if (golden > 0.004f) fill(barLeft, barY - 1.6f, barWidth, 1.2f, golden, ABSORB, false);
        float plate = armor.update(Math.min(1, player.getTotalArmorValue() / 20f), dt);
        if (plate > 0.004f) {
            track(barLeft, thinY, barWidth, 1.4f);
            fill(barLeft, thinY, barWidth, 1.4f, plate, ARMOR, false);
        }

        // Hunger on the right (the mount's health while riding), air as a hairline under it.
        float rightLeft = mid + 13, rightBar = x + WIDTH - 15 - numberWidth - rightLeft;
        Entity ridden = player.ridingEntity;
        if (ridden instanceof EntityLivingBase) {
            EntityLivingBase mount = (EntityLivingBase) ridden;
            float amount = second.update(Math.min(1, mount.getHealth() / Math.max(1, mount.getMaxHealth())), dt);
            sprite(x + WIDTH - 13, y + 1.5f, 52, 88, 9);
            if (numbers) font.drawRight(number(mount.getHealth()), x + WIDTH - 15, textY, GlassTheme.TEXT);
            track(rightLeft, barY, rightBar, 4);
            fill(rightLeft, barY, rightBar, 4, amount, HEALTH, true);
        } else {
            boolean hunger = player.isPotionActive(Potion.hunger);
            int level = player.getFoodStats().getFoodLevel();
            float amount = second.update(Math.min(1, level / 20f), dt);
            sprite(x + WIDTH - 13, y + 1.5f, hunger ? 133 : 16, hunger ? 88 : 52, 27);
            if (numbers) font.drawRight(Integer.toString(level), x + WIDTH - 15, textY, GlassTheme.TEXT);
            track(rightLeft, barY, rightBar, 4);
            fill(rightLeft, barY, rightBar, 4, amount, hunger ? HUNGER : FOOD, true);
        }
        boolean underwater = player.isInsideOfMaterial(Material.water);
        float breath = air.update(Math.max(0, Math.min(1, player.getAir() / 300f)), dt);
        float breathShow = airShow.update(underwater || breath < 0.995f, dt);
        if (breathShow > 0.02f) {
            Draw.pushAlpha(breathShow);
            track(rightLeft, thinY, rightBar, 1.4f);
            fill(rightLeft, thinY, rightBar, 1.4f, breath, AIR, true);
            Draw.popAlpha();
        }

        // Experience: the level in the middle, progress as a line between the capsule and the dock.
        // While riding a horse the line is the jump charge instead, as in vanilla.
        boolean horse = mc.thePlayer != null && mc.thePlayer.isRidingHorse();
        boolean hidden = false;
        float lineX = x + RADIUS, lineW = WIDTH - RADIUS * 2, lineY = y + STATUS_H + (GAP - 1.4f) / 2;
        if (horse) {
            float jump = line.update(mc.thePlayer.getHorseJumpPower(), dt);
            Draw.rect(lineX, lineY, lineW, 1.4f, 0.7f, 0x50000000);
            if (jump > 0.004f) Draw.rect(lineX, lineY, Math.max(1.4f, lineW * jump), 1.4f, 0.7f, GlassTheme.accent());
        } else if (mc.playerController.gameIsSurvivalOrAdventure()) {
            float progress = line.update(hidden ? 0 : Math.max(0, Math.min(1, mc.thePlayer.experience)), dt);
            Draw.rect(lineX, lineY, lineW, 1.4f, 0.7f, 0x50000000);
            if (progress > 0.004f) Draw.rect(lineX, lineY, Math.max(1.4f, lineW * progress), 1.4f, 0.7f, XP);
            int level = hidden ? 0 : mc.thePlayer.experienceLevel;
            if (level > 0) font.drawCentered(Integer.toString(level), mid, textY, XP);
        }
    }

    private static void track(float x, float y, float w, float h) {
        Draw.rect(x, y, w, h, h / 2, 0x5a000000);
    }

    /** The filled part of a bar; {@code fromRight} anchors it to the right end. */
    private static void fill(float x, float y, float w, float h, float amount, int color, boolean fromRight) {
        if (amount <= 0.004f) return;
        float length = Math.max(h, w * Math.min(1, amount));
        Draw.rect(fromRight ? x + w - length : x, y, length, h, h / 2, color);
    }

    /** One 9x9 icon from the vanilla icon sheet over its empty container. */
    private static void sprite(float x, float y, int containerU, int u, int v) {
        GlStateManager.color(1, 1, 1, Draw.alpha());
        GlStateManager.enableBlend();
        mc.getTextureManager().bindTexture(Gui.icons);
        int ix = Math.round(x), iy = Math.round(y);
        Gui.drawModalRectWithCustomSizedTexture(ix, iy, containerU, v, 9, 9, 256, 256);
        Gui.drawModalRectWithCustomSizedTexture(ix, iy, u, v, 9, 9, 256, 256);
        GlStateManager.color(1, 1, 1, 1);
    }

    private static String number(float value) {
        float rounded = Math.round(value * 2) / 2f;
        return rounded == (int) rounded ? Integer.toString((int) rounded) : Float.toString(rounded);
    }

    private static boolean hardcore() {
        return mc.theWorld != null && mc.theWorld.getWorldInfo().isHardcoreModeEnabled();
    }
}
