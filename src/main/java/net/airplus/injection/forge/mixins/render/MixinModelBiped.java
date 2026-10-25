/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.render;

import net.airplus.features.module.modules.render.ESP;
import net.airplus.features.module.modules.render.Rotations;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static net.airplus.utils.client.MinecraftInstance.mc;

@Mixin(ModelBiped.class)
@SideOnly(Side.CLIENT)
public class MixinModelBiped {

    @Shadow
    public ModelRenderer bipedRightArm;

    @Shadow
    public int heldItemRight;

    @Shadow
    public ModelRenderer bipedHead;

    @Inject(method = "setRotationAngles", at = @At(value = "FIELD", target = "Lnet/minecraft/client/model/ModelBiped;swingProgress:F"))
    private void revertSwordAnimation(float p_setRotationAngles_1_, float p_setRotationAngles_2_, float p_setRotationAngles_3_, float p_setRotationAngles_4_, float p_setRotationAngles_5_, float p_setRotationAngles_6_, Entity p_setRotationAngles_7_, CallbackInfo callbackInfo) {
        if (heldItemRight == 3) bipedRightArm.rotateAngleY = 0F;

        if (Rotations.INSTANCE.shouldRotate() && p_setRotationAngles_7_ instanceof EntityPlayer && p_setRotationAngles_7_.equals(mc.thePlayer)) {
            bipedHead.rotateAngleX = (float) Math.toRadians(Rotations.INSTANCE.lerp(mc.timer.renderPartialTicks, Rotations.INSTANCE.getPrevHeadPitch(), Rotations.INSTANCE.getHeadPitch()));
        }
    }

    @Inject(method = "setRotationAngles", at = @At("TAIL"))
    private void airplus$captureSkeleton(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, float scaleFactor, Entity entity, CallbackInfo callbackInfo) {
        if (entity instanceof EntityPlayer && ESP.INSTANCE.getSkeleton()) {
            ESP.INSTANCE.updateSkeleton((EntityPlayer) entity, (ModelBiped) (Object) this);
        }
    }
}