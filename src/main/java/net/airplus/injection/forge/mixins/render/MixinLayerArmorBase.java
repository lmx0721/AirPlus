/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.render;

import net.airplus.features.module.modules.render.ModelChanger;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ModelChanger（moon ModelModifierModule 移植）：HideArmor 标志生效时，
 * 在盔甲层入口直接取消整层渲染（头盔/胸甲/护腿/靴子一次性隐藏）。
 */
@SideOnly(Side.CLIENT)
@Mixin(LayerArmorBase.class)
public class MixinLayerArmorBase {

    @Inject(method = "renderLayer", at = @At("HEAD"), cancellable = true)
    private <T extends EntityLivingBase> void airplus$hideArmor(T entity, float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks, float netHeadYaw, float headPitch, float scaleFactor, int armorSlot, CallbackInfo ci) {
        if (ModelChanger.INSTANCE.flag(entity, ModelChanger.Flag.HIDE_ARMOR)) {
            ci.cancel();
        }
    }
}
