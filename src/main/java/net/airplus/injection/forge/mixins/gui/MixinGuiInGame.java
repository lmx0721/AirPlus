/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.gui;

import net.airplus.event.EventManager;
import net.airplus.event.Render2DEvent;
import net.airplus.features.module.modules.render.AntiBlind;
import net.airplus.features.module.modules.render.DeltaForceStyle;
import net.airplus.features.module.modules.render.HUD;
import net.airplus.features.module.modules.render.SilentHotbarModule;
import net.airplus.ui.font.AWTFontRenderer;
import net.airplus.utils.client.ClassUtils;
import net.airplus.utils.inventory.SilentHotbar;
import net.airplus.utils.inventory.InventoryUtils;
import net.airplus.utils.render.ColorSettingsKt;
import net.airplus.utils.render.RenderUtils;
import net.airplus.utils.render.shader.shaders.GradientShader;
import net.airplus.utils.render.shader.shaders.RainbowShader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

import static net.minecraft.client.renderer.GlStateManager.*;
import static org.lwjgl.opengl.GL11.*;

@Mixin(GuiIngame.class)
@SideOnly(Side.CLIENT)
public abstract class MixinGuiInGame extends Gui {

    @Shadow
    protected abstract void renderHotbarItem(int index, int xPos, int yPos, float partialTicks, EntityPlayer player);

    @Shadow
    @Final
    protected Minecraft mc;

    @Inject(method = "renderScoreboard", at = @At("HEAD"), cancellable = true)
    private void renderScoreboard(CallbackInfo callbackInfo) {
        if (HUD.INSTANCE.handleEvents())
            callbackInfo.cancel();
    }

    @Redirect(method = "updateTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/InventoryPlayer;getCurrentItem()Lnet/minecraft/item/ItemStack;"))
    private ItemStack hookSilentHotbarHighlightedName(InventoryPlayer instance) {
        SilentHotbarModule module = SilentHotbarModule.INSTANCE;

        int slot = SilentHotbar.INSTANCE.renderSlot(module.handleEvents() && module.getKeepHighlightedName());

        return instance.getStackInSlot(slot);
    }

    @Inject(method = "renderTooltip", at = @At("HEAD"), cancellable = true)
    private void injectCustomHotbar(ScaledResolution resolution, float delta, CallbackInfo ci) {
        // DeltaForceStyle replaces the vanilla hotbar (texture, items and held item name)
        // with its own borderless one — cancel vanilla rendering but keep the 2D event alive.
        if (DeltaForceStyle.INSTANCE.handleEvents()) {
            // Forge renders health/food/armor/air/XP after renderTooltip in GuiIngameForge#renderGameOverlay,
            // gated by static flags that are only set once in <clinit>. Clearing them here (hotbar phase)
            // hides the vanilla stats for this frame; they are restored once the module turns off.
            net.minecraftforge.client.GuiIngameForge.renderHealth = false;
            net.minecraftforge.client.GuiIngameForge.renderFood = false;
            net.minecraftforge.client.GuiIngameForge.renderArmor = false;
            net.minecraftforge.client.GuiIngameForge.renderAir = false;
            net.minecraftforge.client.GuiIngameForge.renderExperiance = false;
            net.minecraftforge.client.GuiIngameForge.renderHealthMount = false;
            DeltaForceStyle.INSTANCE.markVanillaSuppressed();
            liquidBounce$injectRender2DEvent(delta);
            ci.cancel();
            return;
        }

        // Module inactive -> make sure the vanilla stats flags suppressed earlier are back on.
        DeltaForceStyle.INSTANCE.restoreVanillaFlags();

        final HUD hud = HUD.INSTANCE;
        final RenderUtils render = RenderUtils.INSTANCE;

        // BM (BetterMyau glass dock) replaces the vanilla hotbar while the HUD module is on and
        // Hotbar-Style is "BM": cancel vanilla rendering and keep the 2D event alive — the dock
        // itself is drawn in HUD's Render2DEvent handler. When the BM status capsule replaces
        // the vanilla stats, hide them the same way DeltaForceStyle does (independent marker,
        // so the restore above can never undo this frame's suppression).
        if (hud.bmReplacesHotbar()) {
            if (hud.getStatusBars()) {
                net.minecraftforge.client.GuiIngameForge.renderHealth = false;
                net.minecraftforge.client.GuiIngameForge.renderFood = false;
                net.minecraftforge.client.GuiIngameForge.renderArmor = false;
                net.minecraftforge.client.GuiIngameForge.renderAir = false;
                net.minecraftforge.client.GuiIngameForge.renderExperiance = false;
                net.minecraftforge.client.GuiIngameForge.renderHealthMount = false;
                hud.markBmVanillaSuppressed();
            } else {
                hud.restoreBmVanillaFlags();
            }
            liquidBounce$injectRender2DEvent(delta);
            ci.cancel();
            return;
        }

        // BM inactive -> make sure the vanilla stats flags suppressed earlier are back on.
        hud.restoreBmVanillaFlags();

        // Rise inactive -> make sure the vanilla XP flag suppressed earlier is back on.
        hud.restoreRiseVanillaFlags();

        // Rise (Rise HotBar theme) replaces the vanilla hotbar while the HUD module is on and
        // Hotbar-Style is "Rise": cancel vanilla rendering (the vanilla held-item name goes with
        // it) and keep the 2D event alive, same as the BM branch above. The vanilla XP bar is
        // hidden the same way the BM status capsule hides the stats — the Rise style draws its
        // own rounded XP bar + level right above the bar, and hearts/food/armor/air stay vanilla.
        // The bar body itself is drawn in HUD's Render2DEvent handler (HUD.onRender2D), the same
        // phase as the BM dock, so its blur call shares the frame snapshot with other HUD parts.
        if (hud.riseReplacesHotbar()) {
            net.minecraftforge.client.GuiIngameForge.renderExperiance = false;
            hud.markRiseVanillaSuppressed();
            // Rise 热栏本体改在 HUD 的 Render2DEvent 处理器内绘制（见 HUD.onRender2D）：
            // blur 必须与其它 HUD 组件同阶段调用，renderTooltip 阶段的贴回会被事件开头的
            // 基准快照重拍时序吃掉，表现为热栏没有 blur。
            liquidBounce$injectRender2DEvent(delta);
            ci.cancel();
            return;
        }

        // Grid / Sakura 热栏：同样接管原版热栏（本体在 HUD 的 Render2DEvent 处理器内绘制，
        // 与其它 HUD 组件共享同帧模糊快照）。状态条与 XP 均保持原版，无需压制标记。
        if (hud.gridReplacesHotbar() || hud.sakuraReplacesHotbar()) {
            liquidBounce$injectRender2DEvent(delta);
            ci.cancel();
            return;
        }

        if (mc.getRenderViewEntity() instanceof EntityPlayer) {
            EntityPlayer entityPlayer = (EntityPlayer) mc.getRenderViewEntity();
            float slot = entityPlayer.inventory.currentItem;

            if (hud.handleEvents() && hud.getCustomHotbar()) {
                if (hud.getSmoothHotbarSlot()) {
                    slot = InventoryUtils.INSTANCE.getLerpedSlot();
                }

                int middleScreen = resolution.getScaledWidth() / 2;
                int height = resolution.getScaledHeight() - 1;

                float gradientOffset = (System.currentTimeMillis() % 10000) / 10000f;

                float gradientX = (hud.getGradientX() == 0f) ? 0f : 1f / hud.getGradientX();
                float gradientY = (hud.getGradientY() == 0f) ? 0f : 1f / hud.getGradientY();

                float rainbowOffset = (System.currentTimeMillis() % 10000) / 10000f;
                float rainbowX = (hud.getRainbowX() == 0f) ? 0f : 1f / hud.getRainbowX();
                float rainbowY = (hud.getRainbowY() == 0f) ? 0f : 1f / hud.getRainbowY();

                List<float[]> gradientColors = ColorSettingsKt.toColorArray(hud.getBgGradColors(), hud.getMaxHotbarGradientColors());

                GL11.glPushMatrix();
                GL11.glDisable(GL11.GL_DEPTH_TEST);

                boolean isGradient = hud.getHotbarMode().equals("Gradient");
                boolean isRainbow = hud.getHotbarMode().equals("Rainbow");

                AWTFontRenderer.Companion.setAssumeNonVolatile(true);

                if (isGradient) {
                    GradientShader.begin(
                            true,
                            gradientX,
                            gradientY,
                            gradientColors,
                            hud.getGradientHotbarSpeed(),
                            gradientOffset
                    );
                }

                if (isRainbow) {
                    RainbowShader.begin(true, rainbowX, rainbowY, rainbowOffset);
                }

                // Inner - Background
                render.drawRoundedRectInt(
                        middleScreen - 91, height - 22,
                        middleScreen + 91, height,
                        hud.getHbBackgroundColors().color().getRGB(),
                        hud.getRoundedHotbarRadius(),
                        RenderUtils.RoundedCorners.ALL
                );

                if (isRainbow) {
                    RainbowShader.INSTANCE.stopShader();
                }
                if (isGradient) {
                    GradientShader.INSTANCE.stopShader();
                }

                // Inner - Highlight
                render.drawRoundedRect(
                        middleScreen - 91 - 1 + slot * 20 + 1, height - 22,
                        middleScreen - 91 - 1 + slot * 20 + 23, height - 23 - 1 + 24,
                        hud.getHbHighlightColors().color().getRGB(),
                        hud.getRoundedHotbarRadius(),
                        RenderUtils.RoundedCorners.ALL
                );

                // Border - Background
                render.drawRoundedBorder(
                        middleScreen - 91, height - 21.55F,
                        middleScreen + 91 + 0.1F, height - 0.5F,
                        hud.getHbBackgroundBorder(),
                        hud.getHbBackgroundBorderColors().color().getRGB(),
                        hud.getRoundedHotbarRadius()
                );

                // Border - Highlight
                render.drawRoundedBorder(
                        middleScreen - 91 - 1 + slot * 20 + 1, height - 21.5F,
                        middleScreen - 91 - 1 + slot * 20 + 23.15F, height - 23 - 1 + 23.5F,
                        hud.getHbHighlightBorder(),
                        hud.getHbHighlightBorderColors().color().getRGB(),
                        hud.getRoundedHotbarRadius()
                );

                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glPopMatrix();

                enableRescaleNormal();
                glEnable(GL_BLEND);
                tryBlendFuncSeparate(770, 771, 1, 0);
                RenderHelper.enableGUIStandardItemLighting();

                for (int j = 0; j < 9; ++j) {
                    int l = height - 16 - 3;
                    int k = middleScreen - 90 + j * 20 + 2;
                    renderHotbarItem(j, k, l, delta, entityPlayer);
                }

                RenderHelper.disableStandardItemLighting();
                disableRescaleNormal();
                disableBlend();

                AWTFontRenderer.Companion.setAssumeNonVolatile(false);

                ci.cancel();
            }
        }

        liquidBounce$injectRender2DEvent(delta);
    }

    @Inject(method = "renderPumpkinOverlay", at = @At("HEAD"), cancellable = true)
    private void renderPumpkinOverlay(final CallbackInfo callbackInfo) {
        final AntiBlind antiBlind = AntiBlind.INSTANCE;

        if (antiBlind.handleEvents() && antiBlind.getPumpkinEffect())
            callbackInfo.cancel();
    }

    @Inject(method = "renderBossHealth", at = @At("HEAD"), cancellable = true)
    private void renderBossHealth(CallbackInfo callbackInfo) {
        final AntiBlind antiBlind = AntiBlind.INSTANCE;

        if (antiBlind.handleEvents() && antiBlind.getBossHealth())
            callbackInfo.cancel();
    }

    @Unique
    private void liquidBounce$injectRender2DEvent(float delta) {
        if (!ClassUtils.INSTANCE.hasClass("net.labymod.api.LabyModAPI")) {
            EventManager.INSTANCE.call(new Render2DEvent(delta));
        }
    }
}
