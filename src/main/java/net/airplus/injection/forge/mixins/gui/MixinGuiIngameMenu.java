/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.gui;

import net.airplus.features.module.modules.client.GuiImage;
import net.airplus.utils.client.ServerUtils;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngameMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiIngameMenu.class)
public abstract class MixinGuiIngameMenu extends MixinGuiScreen {

    @Inject(method = "initGui", at = @At("RETURN"))
    private void initGui(CallbackInfo callbackInfo) {
        if (!mc.isIntegratedServerRunning()) {
            final GuiButton disconnectButton = buttonList.get(0);
            disconnectButton.xPosition = width / 2 + 2;
            disconnectButton.width = 98;
            disconnectButton.height = 20;
            buttonList.add(new GuiButton(1337, width / 2 - 100, height / 4 + 120 - 16, 98, 20, "Reconnect"));
        }
    }

    @Inject(method = "actionPerformed", at = @At("HEAD"))
    private void actionPerformed(GuiButton button, CallbackInfo callbackInfo) {
        if (button.id == 1337) {
            mc.theWorld.sendQuittingDisconnectingPacket();
            ServerUtils.INSTANCE.connectToLastServer();
        }
    }

    /**
     * GuiImage：ESC 界面 drawScreen 尾部绘制自定义图片（画在 GUI 之上，层级确定）。
     */
    @Inject(method = "drawScreen", at = @At("TAIL"))
    private void airplus$guiImageDraw(int mouseX, int mouseY, float partialTicks, CallbackInfo callbackInfo) {
        GuiImage.INSTANCE.onGuiRender(mc.currentScreen);
    }
}