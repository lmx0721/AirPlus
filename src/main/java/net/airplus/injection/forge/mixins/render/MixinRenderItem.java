/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.render;

import net.airplus.features.module.modules.render.Glint;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RenderItem.class)
public abstract class MixinRenderItem {

    @Shadow
    protected abstract void renderModel(IBakedModel model, int color);

    /**
     * 让未附魔物品也进入原版光效分支（TargetItems = All 时）
     */
    @Redirect(
            method = "renderItem",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/item/ItemStack;hasEffect()Z")
    )
    private boolean airplus$forceGlint(ItemStack stack) {
        return stack.hasEffect() || Glint.INSTANCE.shouldForceEffect(stack);
    }

    /**
     * 替换光效绘制颜色；模块关闭时保持原版颜色与单趟绘制
     */
    @Redirect(
            method = "renderEffect",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/RenderItem;renderModel(Lnet/minecraft/client/resources/model/IBakedModel;I)V"
            )
    )
    private void airplus$renderEffect(RenderItem instance, IBakedModel model, int color) {
        Integer customColor = Glint.INSTANCE.getCurrentColor();
        if (customColor == null) {
            renderModel(model, color);
            return;
        }

        int passes = Glint.INSTANCE.getPasses();
        for (int i = 0; i < passes; i++) {
            renderModel(model, customColor);
        }
    }
}
