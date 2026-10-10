/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.item;

import net.airplus.features.module.modules.combat.KillAura;
import net.airplus.features.module.modules.movement.NoSlow;
import net.airplus.features.module.modules.render.Animation;
import net.airplus.features.module.modules.render.Animations;
import net.airplus.features.module.modules.render.AntiBlind;
import net.airplus.features.module.modules.render.NoSwing;
import net.airplus.features.module.modules.render.SilentHotbarModule;
import net.airplus.utils.inventory.SilentHotbar;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemMap;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import static net.minecraft.client.renderer.GlStateManager.*;

@Mixin(ItemRenderer.class)
@SideOnly(Side.CLIENT)
public abstract class MixinItemRenderer {

    @Shadow
    private float prevEquippedProgress;

    @Shadow
    private float equippedProgress;

    @Shadow
    @Final
    private Minecraft mc;
    @Shadow
    private ItemStack itemToRender;

    @Shadow
    protected abstract void rotateArroundXAndY(float angle, float angleY);

    @Shadow
    protected abstract void setLightMapFromPlayer(AbstractClientPlayer clientPlayer);

    @Shadow
    protected abstract void rotateWithPlayerRotations(EntityPlayerSP entityplayerspIn, float partialTicks);

    @Shadow
    protected abstract void renderItemMap(AbstractClientPlayer clientPlayer, float pitch, float equipmentProgress, float swingProgress);

    @Shadow
    protected abstract void transformFirstPersonItem(float equipProgress, float swingProgress);

    @Shadow
    protected abstract void performDrinking(AbstractClientPlayer clientPlayer, float partialTicks);

    @Shadow
    protected abstract void doBowTransformations(float partialTicks, AbstractClientPlayer clientPlayer);

    @Shadow
    protected abstract void doItemUsedTransformations(float swingProgress);

    @Shadow
    public abstract void renderItem(EntityLivingBase entityIn, ItemStack heldStack, ItemCameraTransforms.TransformType transform);

    @Shadow
    protected abstract void renderPlayerArm(AbstractClientPlayer clientPlayer, float equipProgress, float swingProgress);

    /**
     * @author CCBlueX
     */
    @Overwrite
    public void renderItemInFirstPerson(float partialTicks) {
        final KillAura killAura = KillAura.INSTANCE;
        final NoSlow noSlow = NoSlow.INSTANCE;
        final Animations animations = Animations.INSTANCE;

        // NoSwing 同时阻止切换物品时的装备动画（equip progress 固定为 1，取消起手/下落）
        if (NoSwing.INSTANCE.handleEvents())
        {
            this.prevEquippedProgress = 1.0F;
            this.equippedProgress = 1.0F;
        }

        float f = 1f - (prevEquippedProgress + (equippedProgress - prevEquippedProgress) * partialTicks);
        EntityPlayerSP abstractclientplayer = mc.thePlayer;
        float f1 = abstractclientplayer.getSwingProgress(partialTicks);
        float f2 = abstractclientplayer.prevRotationPitch + (abstractclientplayer.rotationPitch - abstractclientplayer.prevRotationPitch) * partialTicks;
        float f3 = abstractclientplayer.prevRotationYaw + (abstractclientplayer.rotationYaw - abstractclientplayer.prevRotationYaw) * partialTicks;
        rotateArroundXAndY(f2, f3);
        setLightMapFromPlayer(abstractclientplayer);
        rotateWithPlayerRotations(abstractclientplayer, partialTicks);
        enableRescaleNormal();
        pushMatrix();

        if (animations.handleEvents()) {
            float scale = animations.getHandItemScale();
            float x = animations.getHandX();
            float y = animations.getHandY();
            float rotX = animations.getHandPosX();
            float rotY = animations.getHandPosY();
            float rotZ = animations.getHandPosZ();

            translate(x, y, scale);
            rotate(rotX, 1f, 0f, 0f);
            rotate(rotY, 0f, 1f, 0f);
            rotate(rotZ, 0f, 0f, 1f);
        }

        if (itemToRender != null) {
            boolean isForceBlocking = (itemToRender.getItem() instanceof ItemSword && !killAura.getAutoBlock().equals("Off") &&
                    (killAura.getRenderBlocking() || killAura.getTarget() != null && (killAura.getBlinkAutoBlock() || killAura.getForceBlockRender()))
                    || noSlow.isUNCPBlocking());

            if (itemToRender.getItem() instanceof ItemMap) {
                renderItemMap(abstractclientplayer, f2, f, f1);
            } else if (abstractclientplayer.getItemInUseCount() > 0 || isForceBlocking) {
                EnumAction enumaction = isForceBlocking ? EnumAction.BLOCK : itemToRender.getItemUseAction();

                switch (enumaction) {
                    case NONE:
                        transformMainHand(f, 0f);
                        break;
                    case EAT:
                    case DRINK:
                        performDrinking(abstractclientplayer, partialTicks);
                        transformMainHand(f, f1);
                        break;
                    case BLOCK:
                        final Animation animation;

                        if (animations.handleEvents()) {
                            animation = animations.getAnimation();
                        } else { // Use 1.7 animation
                            animation = animations.getDefaultAnimation();
                        }

                        if (animation != null) {
                            animation.transform(f1, f, abstractclientplayer);
                        }
                        break;
                    case BOW:
                        transformMainHand(f, f1);
                        doBowTransformations(partialTicks, abstractclientplayer);
                        break;
                }
            } else {
                if (!animations.handleEvents() || !animations.getOddSwing()) {
                    doItemUsedTransformations(f1);
                }

                transformMainHand(f, f1);
            }

            renderItem(abstractclientplayer, itemToRender, ItemCameraTransforms.TransformType.FIRST_PERSON);
        } else if (!abstractclientplayer.isInvisible()) {
            renderPlayerArm(abstractclientplayer, f, f1);
        }

        popMatrix();
        disableRescaleNormal();
        RenderHelper.disableStandardItemLighting();
    }

    /**
     * Onyx 主手变换（迁移自 OpenOnyx HandModule "Main hand"）：
     * Animations.MainHand 开启时，用可自定义的位置/旋转/缩放替换原版 transformFirstPersonItem，
     * 并保留原版挥剑旋转公式与装备进度偏移；未开启时回退原版行为。
     */
    private boolean transformMainHand(float equipProgress, float swingProgress) {
        final Animations animations = Animations.INSTANCE;
        if (!animations.getMainHandEnabled()) {
            transformFirstPersonItem(equipProgress, swingProgress);
            return true;
        }

        translate(
            animations.getMainHandX(),
            animations.getMainHandY() - equipProgress * 0.6F,
            animations.getMainHandZ()
        );
        rotate(animations.getMainHandRotX(), 1.0F, 0.0F, 0.0F);
        rotate(animations.getMainHandRotY(), 0.0F, 1.0F, 0.0F);
        rotate(animations.getMainHandRotZ(), 0.0F, 0.0F, 1.0F);
        final float f = MathHelper.sin(swingProgress * swingProgress * (float) Math.PI);
        final float f1 = MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI);
        rotate(f * -20.0F, 0.0F, 1.0F, 0.0F);
        rotate(f1 * -20.0F, 0.0F, 0.0F, 1.0F);
        rotate(f1 * -80.0F, 1.0F, 0.0F, 0.0F);
        scale(
            animations.getMainHandScaleX(),
            animations.getMainHandScaleY(),
            animations.getMainHandScaleZ()
        );
        return true;
    }

    @Redirect(method = "renderFireInFirstPerson", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;color(FFFF)V"))
    private void renderFireInFirstPerson(float p_color_0_, float p_color_1_, float p_color_2_, float p_color_3_) {
        final AntiBlind antiBlind = AntiBlind.INSTANCE;
        if (p_color_3_ != 1F && antiBlind.handleEvents()) {
            GlStateManager.color(p_color_0_, p_color_1_, p_color_2_, antiBlind.getFireEffect());
        } else {
            GlStateManager.color(p_color_0_, p_color_1_, p_color_2_, p_color_3_);
        }
    }

    @Redirect(method = "updateEquippedItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/InventoryPlayer;getCurrentItem()Lnet/minecraft/item/ItemStack;"))
    private ItemStack hookSilentHotbar(InventoryPlayer instance) {
        SilentHotbarModule module = SilentHotbarModule.INSTANCE;

        int slot = SilentHotbar.INSTANCE.renderSlot(module.handleEvents() && module.getKeepItemInHandInFirstPerson());

        return instance.getStackInSlot(slot);
    }


}
