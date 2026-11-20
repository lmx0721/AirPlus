/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.gui;

import net.minecraft.client.gui.Gui;
import net.minecraftforge.fml.client.config.GuiSlider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class MixinGui {

    // drawGradientRect（SRG func_73733_a）声明在 Gui 而非 GuiScreen/GuiContainer，
    // @Shadow 必须放在目标为 Gui 的 mixin 里；MixinGuiScreen 继承本 mixin 供子 mixin 使用。
    @Shadow
    protected void drawGradientRect(int left, int top, int right, int bottom, int startColor, int endColor) {
    }

    @Inject(method = "drawTexturedModalRect(IIIIII)V", at = @At("HEAD"), cancellable = true)
    private void injectForgeButtonRenderPrevention(int p_drawTexturedModalRect_1_, int p_drawTexturedModalRect_2_, int p_drawTexturedModalRect_3_, int p_drawTexturedModalRect_4_, int p_drawTexturedModalRect_5_, int p_drawTexturedModalRect_6_, CallbackInfo ci) {
        if ((Object) this instanceof GuiSlider) {
            ci.cancel();
        }
    }
}
