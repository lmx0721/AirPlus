/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.render;

import co.uk.hexeption.utils.OutlineUtils;
import net.airplus.features.module.modules.fun.Jelly;
import net.airplus.features.module.modules.render.*;
import net.airplus.utils.client.ClientUtils;
import net.airplus.utils.render.RenderUtils;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.OpenGlHelper;
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
        // NameTags 模块开启时取消原版玩家名牌，由模块自绘；模块关闭时恢复原版行为
        if (entity instanceof net.minecraft.entity.player.EntityPlayer && NameTags.INSTANCE.getCancelVanillaName()) {
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
        // ModelChanger：按模型类型换模型（Pig 用猪模型 + 猪贴图，Baby 强制 isChild），
        // 并按宽/高/长设置对整段渲染 pushMatrix/scale；finally 恢复矩阵与原值，
        // 避免污染后续实体渲染。
        final ModelChanger.ModelType airplus$type = ModelChanger.INSTANCE.modelType(p_renderModel_1_);
        final boolean airplus$baby = airplus$type == ModelChanger.ModelType.BABY;
        final boolean airplus$pig = airplus$type == ModelChanger.ModelType.PIG;

        final float airplus$scaleX = ModelChanger.INSTANCE.scaleX(p_renderModel_1_);
        final float airplus$scaleY = ModelChanger.INSTANCE.scaleY(p_renderModel_1_);
        final float airplus$scaleZ = ModelChanger.INSTANCE.scaleZ(p_renderModel_1_);
        final boolean airplus$scaled = Math.abs(airplus$scaleX - 1f) > 0.001f
            || Math.abs(airplus$scaleY - 1f) > 0.001f
            || Math.abs(airplus$scaleZ - 1f) > 0.001f;

        final boolean airplus$prevChild = mainModel.isChild;
        final boolean airplus$prevRiding = mainModel.isRiding;
        if (airplus$baby) {
            mainModel.isChild = true;
        }
        final ModelBase airplus$model = airplus$pig ? ModelChanger.INSTANCE.getPigModel() : mainModel;
        if (airplus$pig) {
            airplus$model.isChild = false;
        }
        if (airplus$scaled) {
            pushMatrix();
            scale(airplus$scaleX, airplus$scaleY, airplus$scaleZ);
        }
        try {
        boolean visible = !p_renderModel_1_.isInvisible();
        final TrueSight trueSight = TrueSight.INSTANCE;
        boolean semiVisible = !visible && (!p_renderModel_1_.isInvisibleToPlayer(mc.thePlayer) || (trueSight.handleEvents() && trueSight.getEntities()));

        if (visible || semiVisible) {
            final ESP esp = ESP.INSTANCE;
            // Evaluate shouldRender() only once per entity (it may run a visibility raytrace)
            final boolean shouldRenderESP = esp.handleEvents() && esp.shouldRender(p_renderModel_1_);
            boolean shouldRenderGaussianESP = shouldRenderESP && "Gaussian".equals(esp.getMode());

            if (!shouldRenderGaussianESP) {
                if (airplus$pig) {
                    // 猪模式：绑定原版猪贴图而不是玩家皮肤。
                    // 两步法强制真实绑定：GlStateManager 的 activeTexture / bindTexture /
                    // texture2D / color 全是缓存守卫，本项目多次因缓存失步被静默跳过，
                    // 猪模型采样到残留贴图 → 渲染成紫黑。先把活动单元与缓存收敛一致，
                    // 再用「脏值 1 → 目标」保证 pig 贴图必然真实绑定到 unit 0，
                    // 并把 TEXTURE_2D 与当前颜色两步法归位。
                    OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
                    setActiveTexture(OpenGlHelper.defaultTexUnit);
                    bindTexture(1);
                    mc.getTextureManager().bindTexture(ModelChanger.INSTANCE.getPIG_TEXTURE());
                    disableTexture2D();
                    enableTexture2D();
                    color(0f, 0f, 0f, 0f);
                    color(1f, 1f, 1f, 1f);
                } else if (!bindEntityTexture(p_renderModel_1_)) {
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
                    // ESP pass 内只允许裸 GL 调用（绝不走 GlStateManager 写缓存）：
                    // attrib 掩码补上 GL_CURRENT_BIT（当前颜色）与 GL_COLOR_BUFFER_BIT（blendFunc），
                    // 让 pop 把真实状态完整弹回；GlStateManager 的 color/blendFunc 缓存全程未被写入，
                    // 与弹回后的真实状态天然一致，不产生失步。
                    glPushAttrib(GL_ENABLE_BIT | GL_POLYGON_BIT | GL_LINE_BIT | GL_CURRENT_BIT | GL_COLOR_BUFFER_BIT);
                    glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
                    glDisable(GL_TEXTURE_2D);
                    glDisable(GL_LIGHTING);
                    glDisable(GL_DEPTH_TEST);
                    glEnable(GL_LINE_SMOOTH);
                    glEnable(GL_BLEND);
                    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                    final Color wireframeColor = esp.getColor(p_renderModel_1_);
                    glColor4f(wireframeColor.getRed() / 255F, wireframeColor.getGreen() / 255F,
                        wireframeColor.getBlue() / 255F, wireframeColor.getAlpha() / 255F);
                    glLineWidth(esp.getWireframeWidth());
                    airplus$model.render(p_renderModel_1_, p_renderModel_2_, p_renderModel_3_, p_renderModel_4_, p_renderModel_5_, p_renderModel_6_, p_renderModel_7_);
                    glPopAttrib();
                    glPopMatrix();
                    // 两步法把颜色缓存与真实状态收敛为白色：后续 layers/第一人称手臂（renderPlayerArm
                    // 不重置当前颜色）与手持物品都依赖白色当前颜色，残留 ESP 色会让它们整体染色/发黑。
                    color(0f, 0f, 0f, 0f);
                    color(1f, 1f, 1f, 1f);
                } else if ("outline".equalsIgnoreCase(espMode)) {
                    ClientUtils.INSTANCE.disableFastRender();
                    resetColor();

                    final Color color = esp.getColor(p_renderModel_1_);
                    OutlineUtils.setColor(color);
                    OutlineUtils.renderOne(esp.getOutlineWidth());
                    airplus$model.render(p_renderModel_1_, p_renderModel_2_, p_renderModel_3_, p_renderModel_4_, p_renderModel_5_, p_renderModel_6_, p_renderModel_7_);
                    OutlineUtils.setColor(color);
                    OutlineUtils.renderTwo();
                    airplus$model.render(p_renderModel_1_, p_renderModel_2_, p_renderModel_3_, p_renderModel_4_, p_renderModel_5_, p_renderModel_6_, p_renderModel_7_);
                    OutlineUtils.setColor(color);
                    OutlineUtils.renderThree();
                    airplus$model.render(p_renderModel_1_, p_renderModel_2_, p_renderModel_3_, p_renderModel_4_, p_renderModel_5_, p_renderModel_6_, p_renderModel_7_);
                    OutlineUtils.setColor(color);
                    OutlineUtils.renderFour(color);
                    airplus$model.render(p_renderModel_1_, p_renderModel_2_, p_renderModel_3_, p_renderModel_4_, p_renderModel_5_, p_renderModel_6_, p_renderModel_7_);
                    OutlineUtils.setColor(color);
                    OutlineUtils.renderFive();
                    OutlineUtils.setColor(Color.WHITE);
                    // renderFour 把 unit-1 光照坐标留在 (240,240)（glPushAttrib 不保存 multiTexCoords），
                    // 恢复到实体自身亮度，避免本体普通 pass 与后续渲染全亮/发灰；
                    // 再用两步法把颜色缓存与真实状态收敛为白色（OutlineUtils.setColor 是裸 glColor，
                    // 缓存从未同步），防止残留 ESP 色染到 layers / 第一人称手臂与手持物品上。
                    final int airplus$brightness = p_renderModel_1_.getBrightnessForRender(mc.timer.renderPartialTicks);
                    OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit,
                        (airplus$brightness % 65536) / 1f, (airplus$brightness / 65536) / 1f);
                    color(0f, 0f, 0f, 0f);
                    color(1f, 1f, 1f, 1f);
                }
                mc.gameSettings.fancyGraphics = fancyGraphics;
                mc.gameSettings.gammaSetting = gamma;
            }

            airplus$model.render(p_renderModel_1_, p_renderModel_2_, p_renderModel_3_, p_renderModel_4_, p_renderModel_5_, p_renderModel_6_, p_renderModel_7_);

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
        } finally {
            if (airplus$scaled) {
                popMatrix();
            }
            mainModel.isChild = airplus$prevChild;
            mainModel.isRiding = airplus$prevRiding;
        }

        ci.cancel();
    }

    /**
     * ModelChanger：Image/GIF 模式整体取消 doRender（模型/盔甲层/手持物/名牌全隐藏），
     * 改为在实体脚下绘制面向相机的 billboard 平面贴图（AnimatedImage 解码与逐帧上传）。
     * 声明顺序需在 injectMoBendsRender 之前，保证 billboard 优先于 MoBends 接管。
     */
    @Inject(method = "doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V", at = @At("HEAD"), cancellable = true)
    private <T extends EntityLivingBase> void injectModelChangerBillboard(T entity, double x, double y, double z, float entityYaw, float partialTicks, CallbackInfo ci) {
        if (ModelChanger.INSTANCE.shouldRenderBillboard(entity)) {
            ModelChanger.INSTANCE.renderBillboard(entity, x, y, z, partialTicks);
            ci.cancel();
        }
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