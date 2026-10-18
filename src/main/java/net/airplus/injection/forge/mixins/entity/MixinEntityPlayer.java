/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.entity;

import com.mojang.authlib.GameProfile;
import net.airplus.features.module.modules.combat.KeepSprint;
import net.airplus.features.module.modules.combat.KillAura;
import net.airplus.features.module.modules.movement.NoSlow;
import net.airplus.utils.attack.CooldownHelper;
import net.airplus.utils.client.ClassUtils;
import net.airplus.utils.movement.MovementUtils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.FoodStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import static net.airplus.utils.client.MinecraftInstance.mc;

@Mixin(EntityPlayer.class)
public abstract class MixinEntityPlayer extends MixinEntityLivingBase {

    @Shadow
    public abstract ItemStack getHeldItem();

    @Shadow
    public abstract GameProfile getGameProfile();

    @Shadow
    protected abstract boolean canTriggerWalking();

    @Shadow
    protected abstract String getSwimSound();

    @Shadow
    public abstract FoodStats getFoodStats();

    @Shadow
    protected int flyToggleTimer;

    @Shadow
    public PlayerCapabilities capabilities;

    @Shadow
    public abstract int getItemInUseDuration();

    @Shadow
    public abstract ItemStack getItemInUse();

    @Shadow
    public abstract boolean isUsingItem();

    @Shadow
    public InventoryPlayer inventory;
    @Shadow public ItemStack itemInUse;
    @Shadow public int itemInUseCount;
    private ItemStack cooldownStack;
    private int cooldownStackSlot;

    @Inject(method = "onUpdate", at = @At("RETURN"))
    private void injectCooldown(final CallbackInfo callbackInfo) {
        if (getGameProfile() == mc.thePlayer.getGameProfile()) {
            CooldownHelper.INSTANCE.incrementLastAttackedTicks();
            CooldownHelper.INSTANCE.updateGenericAttackSpeed(getHeldItem());

            if (cooldownStackSlot != inventory.currentItem || !ItemStack.areItemStacksEqual(cooldownStack, getHeldItem())) {
                CooldownHelper.INSTANCE.resetLastAttackedTicks();
            }

            cooldownStack = getHeldItem();
            cooldownStackSlot = inventory.currentItem;
        }
    }

    @ModifyConstant(method = "attackTargetEntityWithCurrentItem", constant = @Constant(doubleValue = 0.6))
    private double injectKeepSprintA(double constant) {
        if (KeepSprint.INSTANCE.handleEvents() && isSprinting()) {
            if (KeepSprint.INSTANCE.isRise()) {
                // Rise 模式：null 表示该次攻击不接管（buffer 耗尽 / OnlyInAir 拦截），走原版 0.6
                // hurtTime 定义在父类 EntityLivingBase 上，不能直接 @Shadow，通过转换访问
                final int hurtTime = ((EntityLivingBase) (Object) this).hurtTime;
                Double slowDown = KeepSprint.INSTANCE.beginRiseAttack(hurtTime, this.onGround);
                return slowDown != null ? slowDown : constant;
            }
            return KeepSprint.INSTANCE.getMotionAfterAttack();
        }
        return constant;
    }

    @Redirect(method = "attackTargetEntityWithCurrentItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/EntityPlayer;setSprinting(Z)V"))
    private void injectKeepSprintB(EntityPlayer instance, boolean sprint) {
        boolean keepSprint = Boolean.FALSE.equals(MovementUtils.INSTANCE.getAffectSprintOnAttack());

        if (KeepSprint.INSTANCE.handleEvents()) {
            if (KeepSprint.INSTANCE.isRise()) {
                // Rise 模式：sprintSlowDown* 为 true（或该次未接管）时执行原版疾跑重置，否则保持疾跑
                Boolean riseSprint = KeepSprint.INSTANCE.getLastRiseSprint();
                if (riseSprint == null || riseSprint) {
                    instance.setSprinting(sprint);
                }
            }
            // Legacy 模式：不调用 setSprinting，始终保持疾跑
        } else if (!keepSprint) {
            instance.setSprinting(sprint);
        }

        // Only affect motion when sprinting. Knock-back modifier factor is ignored.
        if (keepSprint && !KeepSprint.INSTANCE.handleEvents() && isSprinting()) {
            // Reverse the motion effects done by sprinting
            motionX /= 0.6;
            motionZ /= 0.6;
        }
    }

    @Inject(method = "attackTargetEntityWithCurrentItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;attackEntityFrom(Lnet/minecraft/util/DamageSource;F)Z"), locals = LocalCapture.CAPTURE_FAILHARD)
    private void injectSprintState(Entity entity, CallbackInfo ci, float f, int i, float f1, boolean flag, boolean flag1, int j, double d0, double d1, double d2) {
        Boolean sprint = MovementUtils.INSTANCE.getAffectSprintOnAttack();

        if (sprint == null || !sprint || isSprinting())
            return;

        // This will be used later in line 1058
        //noinspection UnusedAssignment
        i++;
    }

    @Inject(method = "getItemInUseCount", at = @At("HEAD"), cancellable = true)
    private void injectGetItemInUseCount(CallbackInfoReturnable<Integer> cir) {
        final KillAura killAura = KillAura.INSTANCE;
        final NoSlow noSlow = NoSlow.INSTANCE;

        if ((Object) this == mc.thePlayer) {
            ItemStack stack = mc.thePlayer.getHeldItem();
            if (itemInUseCount > 0 || !ClassUtils.INSTANCE.hasClass("com.orangemarshall.animations.BlockhitAnimation") || stack == null) {
                return;
            }

            boolean isForceBlocking = (stack.getItem() instanceof ItemSword && !killAura.getAutoBlock().equals("Off") &&
                    (killAura.getRenderBlocking() || killAura.getTarget() != null && (killAura.getBlinkAutoBlock() || killAura.getForceBlockRender()))
                    || noSlow.isUNCPBlocking());

            if (isForceBlocking) {
                cir.setReturnValue(Integer.MAX_VALUE);
            }
        }
    }
}
