/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.render;

import net.airplus.features.module.modules.render.FPSBoost;
import net.airplus.features.module.modules.render.FreeCam;
import net.airplus.features.module.modules.render.MoBendsMod;
import net.airplus.injection.implementations.IMixinEntity;
import net.airplus.utils.client.MinecraftInstance;
import net.airplus.utils.client.PacketUtilsKt;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderGlobal.class)
public abstract class MixinRenderGlobal {

    @Redirect(method = "renderEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/EntityLivingBase;isPlayerSleeping()Z"))
    private boolean injectFreeCam(EntityLivingBase instance) {
        // MoreBends「Visible In First Person」：第一人称下让 vanilla 渲染视口实体（自己），
        // 本体随后经 MixinRendererLivingEntity 的 MoBends 钩子换成 Bends 动画渲染器
        if (instance == MinecraftInstance.mc.thePlayer && MoBendsMod.INSTANCE.renderSelfInFirstPerson()) {
            return true;
        }
        return FreeCam.INSTANCE.renderPlayerFromAllPerspectives(instance);
    }

    @Redirect(method = "renderEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/RenderManager;shouldRender(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;DDD)Z"))
    private boolean injectFreeCamB(RenderManager instance, Entity entity, ICamera camera, double x, double y, double z) {
        if (entity instanceof EntityLivingBase) {
            IMixinEntity iEntity = (IMixinEntity) entity;

            if (iEntity.getTruePos()) {
                PacketUtilsKt.interpolatePosition(iEntity);
            }
        }

        // FPSBoost: cull distant entities (x/y/z is the render view entity position)
        final FPSBoost fpsBoost = FPSBoost.INSTANCE;
        if (fpsBoost.handleEvents() && fpsBoost.getEntityDistance() > 0
                && entity.getDistanceSq(x, y, z) > (double) fpsBoost.getEntityDistance() * fpsBoost.getEntityDistance()) {
            return false;
        }

        return FreeCam.INSTANCE.handleEvents() || instance.shouldRender(entity, camera, x, y, z);
    }

    /**
     * FPSBoost: skip cloud geometry - vanilla rebuilds it every frame.
     */
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void injectCloudsOff(float partialTicks, int renderPass, CallbackInfo ci) {
        final FPSBoost fpsBoost = FPSBoost.INSTANCE;

        if (fpsBoost.handleEvents() && "Off".equals(fpsBoost.getClouds())) {
            ci.cancel();
        }
    }

    /**
     * FPSBoost: distance culling for TileEntities. Vanilla only frustum-culls them,
     * so distant chests/furnaces/mob spawners are still rendered every frame.
     * This intercepts all 3 renderTileEntity call sites inside renderEntities.
     */
    @Redirect(method = "renderEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/tileentity/TileEntityRendererDispatcher;renderTileEntity(Lnet/minecraft/tileentity/TileEntity;FI)V"))
    private void injectTileEntityDistance(TileEntityRendererDispatcher dispatcher, TileEntity tileEntity, float partialTicks, int destroyStage) {
        final FPSBoost fpsBoost = FPSBoost.INSTANCE;

        if (fpsBoost.handleEvents() && fpsBoost.getTileEntityDistance() > 0
                && tileEntity.getDistanceSq(TileEntityRendererDispatcher.staticPlayerX, TileEntityRendererDispatcher.staticPlayerY, TileEntityRendererDispatcher.staticPlayerZ)
                > (double) fpsBoost.getTileEntityDistance() * fpsBoost.getTileEntityDistance()) {
            return;
        }

        dispatcher.renderTileEntity(tileEntity, partialTicks, destroyStage);
    }
}
