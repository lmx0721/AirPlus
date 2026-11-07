/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.entity;

import net.airplus.cape.CapeAPI;
import net.airplus.cape.CapeInfo;
import net.airplus.features.module.modules.misc.NameProtect;
import net.airplus.features.module.modules.render.Camera;
import net.airplus.features.module.modules.render.CustomSkin;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.init.Items;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;

import static net.airplus.utils.client.MinecraftInstance.mc;

@Mixin(AbstractClientPlayer.class)
@SideOnly(Side.CLIENT)
public abstract class MixinAbstractClientPlayer extends MixinEntityPlayer {

    private CapeInfo onlineCapeInfo;

    @Inject(method = "getLocationCape", at = @At("HEAD"), cancellable = true)
    private void getCape(CallbackInfoReturnable<ResourceLocation> callbackInfoReturnable) {
        // 首先检查Cape模块是否提供了自定义披风
        ResourceLocation customCape = CapeAPI.INSTANCE.getCustomCape(getUniqueID());
        if (customCape != null) {
            callbackInfoReturnable.setReturnValue(customCape);
            return;
        }

        // 如果没有自定义披风，尝试加载在线披风
        if (onlineCapeInfo == null) {
            CapeAPI.INSTANCE.loadCape(getUniqueID(), newCapeInfo -> {
                onlineCapeInfo = newCapeInfo;
                return null;
            });
        }

        if (onlineCapeInfo != null && onlineCapeInfo.isCapeAvailable()) {
            callbackInfoReturnable.setReturnValue(onlineCapeInfo.getResourceLocation());
        }
    }

    @Inject(method = "getFovModifier", at = @At("HEAD"), cancellable = true)
    private void getFovModifier(CallbackInfoReturnable<Float> callbackInfoReturnable) {
        final Camera camera = Camera.INSTANCE;

        if (camera.handleEvents() && camera.getNoFOV()) {
            float newFOV = camera.getFov();

            if (!isUsingItem()) {
                callbackInfoReturnable.setReturnValue(newFOV);
                return;
            }

            if (getItemInUse().getItem() != Items.bow) {
                callbackInfoReturnable.setReturnValue(newFOV);
                return;
            }

            int i = getItemInUseDuration();
            float f1 = (float) i / 20f;
            f1 = f1 > 1f ? 1f : f1 * f1;
            newFOV *= 1f - f1 * 0.15f;
            callbackInfoReturnable.setReturnValue(newFOV);
        }
    }

    @Inject(method = "getLocationSkin()Lnet/minecraft/util/ResourceLocation;", at = @At("HEAD"), cancellable = true)
    private void getSkin(CallbackInfoReturnable<ResourceLocation> callbackInfoReturnable) {
        // CustomSkin：自定义皮肤模块优先接管（本地皮肤 / 正版玩家名皮肤）
        ResourceLocation customSkin = CustomSkin.INSTANCE.getSkinForPlayer(getUniqueID());
        if (customSkin != null) {
            callbackInfoReturnable.setReturnValue(customSkin);
            return;
        }

        final NameProtect nameProtect = NameProtect.INSTANCE;

        if (nameProtect.handleEvents() && nameProtect.getSkinProtect()) {
            if (!nameProtect.getAllPlayers() && !Objects.equals(getGameProfile().getName(), mc.thePlayer.getGameProfile().getName()))
                return;

            callbackInfoReturnable.setReturnValue(DefaultPlayerSkin.getDefaultSkin(getUniqueID()));
        }
    }
}
