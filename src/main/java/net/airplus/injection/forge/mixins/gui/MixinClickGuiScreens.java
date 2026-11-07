/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.gui;

import net.airplus.features.module.modules.client.GuiImage;
import net.minecraft.client.gui.GuiScreen;
import net.airplus.ui.client.clickgui.ClickGui;
import net.airplus.ui.client.clickgui.astolfo.AstolfoClickGui;
import net.airplus.ui.client.clickgui.augustus.AugustusClickGui;
import net.airplus.ui.client.clickgui.flat.FlatClickGui;
import net.airplus.ui.client.clickgui.moonlight.MoonLightClickGui;
import net.airplus.ui.client.clickgui.neverlose.NeverloseScreen;
import net.airplus.ui.client.clickgui.opai.OpaiScreen;
import net.airplus.ui.client.clickgui.rise.RiseClickGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GuiImage：所有 ClickGUI 样式界面（无公共基类，均为独立 GuiScreen 子类）的
 * drawScreen TAIL 统一注入，绘制自定义图片。由 GuiImage 内部按开关过滤。
 */
@Mixin({
        ClickGui.class,
        RiseClickGui.class,
        FlatClickGui.class,
        AstolfoClickGui.class,
        AugustusClickGui.class,
        NeverloseScreen.class,
        MoonLightClickGui.class,
        OpaiScreen.class
})
public abstract class MixinClickGuiScreens extends GuiScreen {

    @Inject(method = "drawScreen", at = @At("TAIL"))
    private void airplus$guiImageDraw(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        GuiImage.INSTANCE.onGuiRender((GuiScreen) (Object) this);
    }
}
