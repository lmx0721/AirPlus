/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.gui;

import net.airplus.features.module.modules.render.HUD;
import net.airplus.ui.font.GameFontRenderer;
import net.airplus.utils.render.ChatHudRenderer;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.Constant;

import java.util.List;

@Mixin(GuiNewChat.class)
public abstract class MixinGuiNewChat {

    @Shadow
    @Final
    private List<ChatLine> drawnChatLines;

    @Shadow
    private int scrollPos;

    @Shadow
    private boolean isScrolled;

    @Shadow
    public abstract int getLineCount();

    @Shadow
    public abstract boolean getChatOpen();

    private int previousDrawnChatLinesSize;

    @Redirect(method = {"getChatComponent", "drawChat"}, at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/FontRenderer;FONT_HEIGHT:I"))
    private int injectFontChat(FontRenderer instance) {
        FontRenderer font = HUD.INSTANCE.getChatFont();
        if (HUD.INSTANCE.shouldModifyChatFont() && font instanceof GameFontRenderer) {
            return ((GameFontRenderer) font).getHeight();
        }
        return instance.FONT_HEIGHT;
    }

    @Redirect(method = "drawChat", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/FontRenderer;drawStringWithShadow(Ljava/lang/String;FFI)I"))
    private int injectFontChatB(FontRenderer instance, String text, float x, float y, int color) {
        return HUD.INSTANCE.shouldModifyChatFont() ? HUD.INSTANCE.getChatFont().drawStringWithShadow(text, x, y, color) : instance.drawStringWithShadow(text, x, y, color);
    }

    @Redirect(method = "getChatComponent", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/FontRenderer;getStringWidth(Ljava/lang/String;)I"))
    private int injectFontChatC(FontRenderer instance, String text) {
        return HUD.INSTANCE.shouldModifyChatFont() ? HUD.INSTANCE.getChatFont().getStringWidth(text) : instance.getStringWidth(text);
    }

    /**
     * 接管聊天历史渲染：圆角背景 + 可选模糊、新消息入场动画、平滑滚动。
     */
    @Inject(method = "drawChat", at = @At("HEAD"), cancellable = true)
    private void injectCustomChatRender(int updateCounter, CallbackInfo ci) {
        if (!HUD.INSTANCE.shouldRenderCustomChat()) return;

        ChatHudRenderer.INSTANCE.renderChat(
                drawnChatLines,
                scrollPos,
                isScrolled,
                updateCounter,
                getLineCount(),
                getChatOpen()
        );
        ci.cancel();
    }

    /**
     * 记录 setChatLine 前的可见行数量，用于计算新增行数。
     */
    @Inject(method = "setChatLine", at = @At("HEAD"))
    private void captureDrawnChatLinesSize(IChatComponent chatComponent, int chatLineId, int updateCounter, boolean displayOnly, CallbackInfo ci) {
        previousDrawnChatLinesSize = drawnChatLines.size();
    }

    /**
     * 非历史重建（refreshChat）的新行记录出生时间，驱动入场动画。
     */
    @Inject(method = "setChatLine", at = @At("TAIL"))
    private void trackNewChatLines(IChatComponent chatComponent, int chatLineId, int updateCounter, boolean displayOnly, CallbackInfo ci) {
        if (!displayOnly) {
            ChatHudRenderer.INSTANCE.onChatLineAdded(drawnChatLines, drawnChatLines.size() - previousDrawnChatLinesSize);
        }
    }

    @Inject(method = "clearChatMessages", at = @At("TAIL"))
    private void resetChatAnimationState(CallbackInfo ci) {
        ChatHudRenderer.INSTANCE.onClear();
    }

    /**
     * 移除聊天历史 100 条的长度限制（chatLines / chatMessages 的截断上限）。
     */
    @ModifyConstant(method = "setChatLine", constant = @Constant(intValue = 100))
    private int removeChatHistoryLimit(int original) {
        return Integer.MAX_VALUE;
    }
}
