/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.entity;

import net.airplus.event.AttackEvent;
import net.airplus.event.ClickWindowEvent;
import net.airplus.event.ClientSlotChangeEvent;
import net.airplus.event.EventManager;
import net.airplus.features.module.modules.misc.AbortBreaking;
import net.airplus.features.module.modules.render.Execution;
import net.airplus.utils.attack.CooldownHelper;
import net.airplus.utils.inventory.SilentHotbar;
import net.airplus.utils.inventory.InventoryUtils;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerControllerMP.class)
@SideOnly(Side.CLIENT)
public class MixinPlayerControllerMP {

    @Inject(method = "attackEntity", at = @At("HEAD"), cancellable = true)
    private void lockAttackOnExecution(EntityPlayer entityPlayer, Entity targetEntity, CallbackInfo callbackInfo) {
        // 处决动画期间锁定攻击（手动点击、KillAura 等所有经过 PlayerController 的攻击）
        if (Execution.INSTANCE.isPlaying()) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "attackEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/PlayerControllerMP;syncCurrentPlayItem()V"))
    private void attackEntity(EntityPlayer entityPlayer, Entity targetEntity, CallbackInfo callbackInfo) {
        EventManager.INSTANCE.call(new AttackEvent(targetEntity));
        CooldownHelper.INSTANCE.resetLastAttackedTicks();
    }

    @Inject(method = "getIsHittingBlock", at = @At("HEAD"), cancellable = true)
    private void getIsHittingBlock(CallbackInfoReturnable<Boolean> callbackInfoReturnable) {
        if (AbortBreaking.INSTANCE.handleEvents()) callbackInfoReturnable.setReturnValue(false);
    }

    @Inject(method = "windowClick", at = @At("HEAD"), cancellable = true)
    private void windowClick(int windowId, int slotId, int mouseButtonClicked, int mode, EntityPlayer playerIn, CallbackInfoReturnable<ItemStack> callbackInfo) {
        final ClickWindowEvent event = new ClickWindowEvent(windowId, slotId, mouseButtonClicked, mode);
        EventManager.INSTANCE.call(event);

        if (event.isCancelled()) {
            callbackInfo.cancel();
            return;
        }

        // Only reset click delay, if a click didn't get cancelled
        InventoryUtils.INSTANCE.getCLICK_TIMER().reset();
    }

    @Redirect(method = "syncCurrentPlayItem", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/player/InventoryPlayer;currentItem:I"))
    private int hookSilentHotbarA(InventoryPlayer instance) {
        SilentHotbar silentHotbar = SilentHotbar.INSTANCE;

        int prevSlot = instance.currentItem;
        int serverSlot = silentHotbar.getCurrentSlot();

        ClientSlotChangeEvent event = new ClientSlotChangeEvent(prevSlot, serverSlot);
        EventManager.INSTANCE.call(event);

        return event.getModifiedSlot();
    }

    @Redirect(method = "sendUseItem", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/player/InventoryPlayer;currentItem:I"))
    private int hookSilentHotbarB(InventoryPlayer instance) {
        return SilentHotbar.INSTANCE.getCurrentSlot();
    }
}