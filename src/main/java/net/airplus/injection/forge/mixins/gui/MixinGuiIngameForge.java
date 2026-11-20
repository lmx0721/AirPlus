package net.airplus.injection.forge.mixins.gui;

import net.airplus.features.module.modules.render.HUD;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.GuiIngameForge;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forge draws the held item name in {@link GuiIngameForge#renderGameOverlay} through this
 * separate method, after the hotbar phase — cancelling vanilla renderTooltip does not touch
 * it. The BM glass dock draws its own held-item capsule, so cancel this while it is active.
 */
@SideOnly(Side.CLIENT)
@Mixin(GuiIngameForge.class)
public abstract class MixinGuiIngameForge {
    @Inject(method = "renderToolHightlight", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void airplus$heldItemName(ScaledResolution res, CallbackInfo callbackInfo) {
        if (HUD.INSTANCE.bmReplacesHotbar())
            callbackInfo.cancel();
    }
}
