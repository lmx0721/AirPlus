/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.render;

import co.uk.hexeption.utils.OutlineUtils;
import net.airplus.features.module.modules.render.*;
import net.airplus.utils.client.ClientUtils;
import net.airplus.utils.render.RenderUtils;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.awt.Color;

import static net.airplus.utils.client.MinecraftInstance.mc;
import static net.minecraft.client.renderer.GlStateManager.*;
import static org.lwjgl.opengl.GL11.*;

@Mixin(RendererLivingEntity.class)
@SideOnly(Side.CLIENT)
public abstract class MixinRendererLivingEntity extends MixinRender {

    @Shadow
    protected ModelBase mainModel;

    @Inject(method = "doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V", at = @At("HEAD"))
    private <T extends EntityLivingBase> void injectChamsPre(T entity, double x, double y, double z, float entityYaw, float partialTicks, CallbackInfo callbackInfo) {
        final ESP esp = ESP.INSTANCE;
        // Cheap check first: mode string compare before the expensive shouldRender()
        if (esp.handleEvents() && "Gaussian".equals(esp.getMode()) && esp.shouldRender(entity)) {
            glEnable(GL_POLYGON_OFFSET_FILL);
            glPolygonOffset(1f, -1000000F);
        }
    }

    @Inject(method = "doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V", at = @At("RETURN"))
    private <T extends EntityLivingBase> void injectChamsPost(T entity, double x, double y, double z, float entityYaw, float partialTicks, CallbackInfo callbackInfo) {
        final ESP esp = ESP.INSTANCE;
        if (esp.handleEvents() && "Gaussian".equals(esp.getMode()) && esp.shouldRender(entity)) {
            glPolygonOffset(1f, 1000000F);
            glDisable(GL_POLYGON_OFFSET_FILL);
        }
    }

    @Inject(method = "canRenderName(Lnet/minecraft/entity/EntityLivingBase;)Z", at = @At("HEAD"), cancellable = true)
    private <T extends EntityLivingBase> void canRenderName(T entity, CallbackInfoReturnable<Boolean> callbackInfoReturnable) {
        // NameTags2：开启时取消原版玩家名牌，由模块自绘
        if (NameTags2.INSTANCE.handleEvents() && entity instanceof net.minecraft.entity.player.EntityPlayer) {
            callbackInfoReturnable.setReturnValue(false);
            return;
        }

        if (NameTags.INSTANCE.shouldRenderNameTags(entity)) {
            callbackInfoReturnable.setReturnValue(false);
        }
    }

    /**
     * @author CCBlueX
     */
    @Inject(method = "renderModel", at = @At("HEAD"), cancellable = true)
    private <T extends EntityLivingBase> void renderModel(T p_renderModel_1_, float p_renderModel_2_, float p_renderModel_3_, float p_renderModel_4_, float p_renderModel_5_, float p_renderModel_6_, float p_renderModel_7_, CallbackInfo ci) {
        boolean visible = !p_renderModel_1_.isInvisible();
        final TrueSight trueSight = TrueSight.INSTANCE;
        boolean semiVisible = !visible && (!p_renderModel_1_.isInvisibleToPlayer(mc.thePlayer) || (trueSight.handleEvents() && trueSight.getEntities()));

        if (visible || semiVisible) {
            final ESP esp = ESP.INSTANCE;
            // Evaluate shouldRender() only once per entity (it may run a visibility raytrace)
            final boolean shouldRenderESP = esp.handleEvents() && esp.shouldRender(p_renderModel_1_);
            boolean shouldRenderGaussianESP = shouldRenderESP && "Gaussian".equals(esp.getMode());

            if (!shouldRenderGaussianESP) {
                if (!bindEntityTexture(p_renderModel_1_)) {
                    return;
                }
            } else {
                bindTexture(0);
                RenderUtils.INSTANCE.glColor(esp.getColor(p_renderModel_1_));
            }

            if (semiVisible) {
                pushMatrix();
                color(1f, 1f, 1f, 0.3F);
                depthMask(false);
                glEnable(GL_BLEND);
                blendFunc(770, 771);
                alphaFunc(516, 0.003921569F);
            }

            if (shouldRenderESP) {
                boolean fancyGraphics = mc.gameSettings.fancyGraphics;
                mc.gameSettings.fancyGraphics = false;

                float gamma = mc.gameSettings.gammaSetting;
                mc.gameSettings.gammaSetting = 100000F;

                final String espMode = esp.getMode();
                if ("wireframe".equalsIgnoreCase(espMode)) {
                    glPushMatrix();
                    // Only save the state groups actually modified below (enable bits, polygon mode, line state)
                    glPushAttrib(GL_ENABLE_BIT | GL_POLYGON_BIT | GL_LINE_BIT);
                    glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
                    glDisable(GL_TEXTURE_2D);
                    glDisable(GL_LIGHTING);
                    glDisable(GL_DEPTH_TEST);
                    glEnable(GL_LINE_SMOOTH);
                    glEnable(GL_BLEND);
                    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                    RenderUtils.INSTANCE.glColor(esp.getColor(p_renderModel_1_));
                    glLineWidth(esp.getWireframeWidth());
                    mainModel.render(p_renderModel_1_, p_renderModel_2_, p_renderModel_3_, p_renderModel_4_, p_renderModel_5_, p_renderModel_6_, p_renderModel_7_);
                    glPopAttrib();
                    glPopMatrix();
                } else if ("outline".equalsIgnoreCase(espMode)) {
                    ClientUtils.INSTANCE.disableFastRender();
                    resetColor();

                    final Color color = esp.getColor(p_renderModel_1_);
                    OutlineUtils.setColor(color);
                    OutlineUtils.renderOne(esp.getOutlineWidth());
                    mainModel.render(p_renderModel_1_, p_renderModel_2_, p_renderModel_3_, p_renderModel_4_, p_renderModel_5_, p_renderModel_6_, p_renderModel_7_);
                    OutlineUtils.setColor(color);
                    OutlineUtils.renderTwo();
                    mainModel.render(p_renderModel_1_, p_renderModel_2_, p_renderModel_3_, p_renderModel_4_, p_renderModel_5_, p_renderModel_6_, p_renderModel_7_);
                    OutlineUtils.setColor(color);
                    OutlineUtils.renderThree();
                    mainModel.render(p_renderModel_1_, p_renderModel_2_, p_renderModel_3_, p_renderModel_4_, p_renderModel_5_, p_renderModel_6_, p_renderModel_7_);
                    OutlineUtils.setColor(color);
                    OutlineUtils.renderFour(color);
                    mainModel.render(p_renderModel_1_, p_renderModel_2_, p_renderModel_3_, p_renderModel_4_, p_renderModel_5_, p_renderModel_6_, p_renderModel_7_);
                    OutlineUtils.setColor(color);
                    OutlineUtils.renderFive();
                    OutlineUtils.setColor(Color.WHITE);
                }
                mc.gameSettings.fancyGraphics = fancyGraphics;
                mc.gameSettings.gammaSetting = gamma;
            }

            mainModel.render(p_renderModel_1_, p_renderModel_2_, p_renderModel_3_, p_renderModel_4_, p_renderModel_5_, p_renderModel_6_, p_renderModel_7_);

            if (shouldRenderGaussianESP) {
                resetColor();
            }

            if (semiVisible) {
                disableBlend();
                alphaFunc(516, 0.1F);
                popMatrix();
                depthMask(true);
            }
        }

        ci.cancel();
    }

    /**
     * Jelly：在 rotateCorpse RETURN 处施加果冻缩放。此时已完成 renderLivingAt 平移与朝向旋转，
     * 矩阵处于 doRender 的 pushMatrix/popMatrix 内，自动恢复无残留；缩放围绕脚底原点，压扁贴地。
     */
    @Inject(method = "rotateCorpse(Lnet/minecraft/entity/EntityLivingBase;FFF)V", at = @At("RETURN"))
    private <T extends EntityLivingBase> void injectJelly(T entity, float yaw, float offset, float partialTicks, CallbackInfo callbackInfo) {
        final float sy = Jelly.INSTANCE.scaleY(entity.getEntityId());
        if (Math.abs(sy - 1f) < 0.002f) {
            return;
        }
        // 体积守恒：XZ 反向补偿
        final float sxz = 1f / (float) Math.sqrt(sy);
        scale(sxz, sy, sxz);
    }

    @Inject(method = "doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V", at = @At(value = "HEAD"))
    private void injectFreeLookPitchPreMovePrevention(CallbackInfo ci) {
        FreeLook.INSTANCE.restoreOriginalRotation();
    }

    @Inject(method = "doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V", at = @At(value = "TAIL"))
    private void injectFreeLookPitchPostMovePrevention(CallbackInfo ci) {
        FreeLook.INSTANCE.useModifiedRotation();
    }

    @Inject(method = "doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V", at = @At("HEAD"), cancellable = true)
    private <T extends EntityLivingBase> void injectMoBendsRender(T entity, double x, double y, double z, float entityYaw, float partialTicks, CallbackInfo ci) {
        @SuppressWarnings("unchecked")
        final net.minecraft.client.renderer.entity.RendererLivingEntity<T> renderer = (net.minecraft.client.renderer.entity.RendererLivingEntity<T>) (Object) this;
        if (MoBendsMod.INSTANCE.onRenderLivingEvent(renderer, entity, x, y, z, entityYaw, partialTicks)) {
            ci.cancel();
        }
    }
}