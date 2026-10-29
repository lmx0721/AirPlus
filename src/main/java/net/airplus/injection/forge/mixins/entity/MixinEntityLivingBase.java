/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.entity;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.airplus.event.EventManager;
import net.airplus.event.EventState;
import net.airplus.event.JumpEvent;
import net.airplus.features.module.modules.combat.AutoClicker;
import net.airplus.features.module.modules.movement.LiquidWalk;
import net.airplus.features.module.modules.movement.NoJumpDelay;
import net.airplus.features.module.modules.movement.Sprint;
import net.airplus.features.module.modules.render.Animations;
import net.airplus.features.module.modules.render.Rotations;
import net.airplus.features.module.modules.world.scaffolds.Scaffold;
import net.airplus.features.module.modules.world.scaffolds.Tower;
import net.airplus.utils.movement.MovementUtils;
import net.airplus.utils.rotation.Rotation;
import net.airplus.utils.rotation.RotationSettings;
import net.airplus.utils.rotation.RotationUtils;
import net.airplus.utils.extensions.MathExtensionsKt;
import net.airplus.viaversion.vialoadingbase.ViaLoadingBase;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityLivingBase.class)
public abstract class MixinEntityLivingBase extends MixinEntity {

    @Shadow
    public float rotationYawHead;
    @Shadow
    public boolean isJumping;
    @Shadow
    public int jumpTicks;

    @Shadow
    protected abstract float getJumpUpwardsMotion();

    @Shadow
    public abstract PotionEffect getActivePotionEffect(Potion potionIn);

    @Shadow
    public abstract boolean isPotionActive(Potion potionIn);

    @Shadow
    public void onLivingUpdate() {
    }

    @Shadow
    protected abstract void updateFallState(double y, boolean onGroundIn, Block blockIn, BlockPos pos);

    @Shadow
    public abstract float getHealth();

    @Shadow
    public abstract ItemStack getHeldItem();

    @Shadow
    protected abstract void updateAITick();

    /**
     * @author CCBlueX
     */
    @Overwrite
    protected void jump() {
        final JumpEvent prejumpEvent = new JumpEvent(getJumpUpwardsMotion(), EventState.PRE);
        if ((Object) this == Minecraft.getMinecraft().thePlayer) {
            EventManager.INSTANCE.call(prejumpEvent);
            if (prejumpEvent.isCancelled()) return;
        }

        motionY = prejumpEvent.getMotion();

        if (isPotionActive(Potion.jump))
            motionY += (float) (getActivePotionEffect(Potion.jump).getAmplifier() + 1) * 0.1F;

        if (isSprinting()) {
            float fixedYaw = this.rotationYaw;

            final RotationUtils rotationUtils = RotationUtils.INSTANCE;
            final Rotation currentRotation = rotationUtils.getCurrentRotation();
            final RotationSettings rotationData = rotationUtils.getActiveSettings();
            if (currentRotation != null && rotationData != null && rotationData.getStrafe()) {
                fixedYaw = currentRotation.getYaw();
            }

            final Sprint sprint = Sprint.INSTANCE;
            if (sprint.handleEvents() && sprint.getMode().equals("Vanilla") && sprint.getAllDirections() && sprint.getJumpDirections()) {
                fixedYaw += MathExtensionsKt.toDegreesF(MovementUtils.INSTANCE.getDirection()) - this.rotationYaw;
            }

            final float f = fixedYaw * 0.017453292F;
            motionX -= MathHelper.sin(f) * 0.2F;
            motionZ += MathHelper.cos(f) * 0.2F;
        }

        isAirBorne = true;

        if ((Object) this == Minecraft.getMinecraft().thePlayer) {
            final JumpEvent postjumpEvent = new JumpEvent((float) motionY, EventState.POST);
            EventManager.INSTANCE.call(postjumpEvent);
        }
    }

    @Inject(method = "onLivingUpdate", at = @At("HEAD"))
    private void headLiving(CallbackInfo callbackInfo) {
        if (NoJumpDelay.INSTANCE.handleEvents() || Scaffold.INSTANCE.handleEvents() && Tower.INSTANCE.getTowerModeValues().equals("Pulldown")) jumpTicks = 0;
    }

    @Inject(method = "onLivingUpdate", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/EntityLivingBase;isJumping:Z", ordinal = 1))
    private void onJumpSection(CallbackInfo callbackInfo) {
        final LiquidWalk liquidWalk = LiquidWalk.INSTANCE;

        if (liquidWalk.handleEvents() && !isJumping && !isSneaking() && isInWater() && liquidWalk.getMode().equals("Swim")) {
            updateAITick();
        }
    }

    @Inject(method = "getLook", at = @At("HEAD"), cancellable = true)
    private void getLook(CallbackInfoReturnable<Vec3> callbackInfoReturnable) {
        //noinspection ConstantConditions
        if (((EntityLivingBase) (Object) this) instanceof EntityPlayerSP)
            callbackInfoReturnable.setReturnValue(getVectorForRotation(rotationPitch, rotationYaw));
    }

    /**
     * Inject head yaw rotation modification
     */
    @Inject(method = "onLivingUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/EntityLivingBase;updateEntityActionState()V", shift = At.Shift.AFTER))
    private void hookHeadRotations(CallbackInfo ci) {
        Rotation rotation = Rotations.INSTANCE.getRotation();

        //noinspection ConstantValue
        this.rotationYawHead = ((EntityLivingBase) (Object) this) instanceof EntityPlayerSP && Rotations.INSTANCE.shouldUseRealisticMode() && rotation != null ? rotation.getYaw() : this.rotationYawHead;
    }

    /**
     * Inject body rotation modification
     */
    @Redirect(method = "onUpdate", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/EntityLivingBase;rotationYaw:F", ordinal = 0))
    private float hookBodyRotationsA(EntityLivingBase instance) {
        Rotation rotation = Rotations.INSTANCE.getRotation();

        return instance instanceof EntityPlayerSP && Rotations.INSTANCE.shouldUseRealisticMode() && rotation != null ? rotation.getYaw() : instance.rotationYaw;
    }

    /**
     * Inject body rotation modification
     */
    @Redirect(method = "updateDistance", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/EntityLivingBase;rotationYaw:F"))
    private float hookBodyRotationsB(EntityLivingBase instance) {
        Rotation rotation = Rotations.INSTANCE.getRotation();

        return instance instanceof EntityPlayerSP && Rotations.INSTANCE.shouldUseRealisticMode() && rotation != null ? rotation.getYaw() : instance.rotationYaw;
    }

    /**
     * @author SuperSkidder
     * @reason Animations swing speed
     */
    @ModifyConstant(method = "getArmSwingAnimationEnd", constant = @Constant(intValue = 6))
    private int injectAnimationsModule(int constant) {
        Animations module = Animations.INSTANCE;

        return module.handleEvents() ? (2 + (20 - module.getSwingSpeed())) : constant;
    }

    /**
     * AutoClicker CustomSwingSpeed：本地挥手动画按设定的 CPS（次/秒）重新播放，
     * 与实际点击 CPS 解耦。玩家判定在前 —— shouldGateSwingAnimation() 有盖章副作用
     * （间隔已到时会记录本次动画开始时间），非本地玩家绝不能触发。命中时仅取消
     * EntityLivingBase.swingItem 的本地动画重播（动画进度不重置，手臂保持当前状态）；
     * EntityPlayerSP.swingItem 继续执行照发 C0APacketAnimation，
     * 服务端行为不变（不动发包，只改本地渲染）。
     */
    @Inject(method = "swingItem", at = @At("HEAD"), cancellable = true)
    private void gateSwingAnimation(CallbackInfo callbackInfo) {
        if ((Object) this == Minecraft.getMinecraft().thePlayer
                && AutoClicker.INSTANCE.handleEvents()
                && AutoClicker.INSTANCE.shouldGateSwingAnimation()) {
            callbackInfo.cancel();
        }
    }

    /**
     * PacketFix：跨版本（目标协议 >= 1.12.2）时运动归零阈值由 0.005 改为 0.003，
     * 与 1.12.2 服务器判定保持一致。
     */
    @ModifyConstant(method = "onLivingUpdate", constant = @Constant(doubleValue = 0.005D), allow = 3)
    private double packetFix$minimumMotion(double constant) {
        return ViaLoadingBase.getInstance().getTargetVersion().isNewerThanOrEqualTo(ProtocolVersion.v1_12_2) ? 0.003D : constant;
    }
}
