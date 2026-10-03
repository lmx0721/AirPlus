/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.gui;

import me.liuli.elixir.account.MinecraftAccount;
import net.airplus.event.EventManager;
import net.airplus.event.SessionUpdateEvent;
import net.airplus.features.special.AutoReconnect;
import net.airplus.features.special.ClientFixes;
import net.airplus.file.FileManager;
import net.airplus.ui.client.altmanager.menus.GuiLoginProgress;
import net.airplus.utils.client.ServerUtils;
import net.airplus.utils.kotlin.RandomUtils;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.fml.client.config.GuiSlider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Random;

import static net.airplus.AirPlus.CLIENT_NAME;

@Mixin(GuiDisconnected.class)
public abstract class MixinGuiDisconnected extends MixinGuiScreen {
    @Shadow
    private int field_175353_i;

    private GuiButton reconnectButton;
    private GuiSlider autoReconnectDelaySlider;
    private GuiButton forgeBypassButton;
    private int reconnectTimer;

    @Inject(method = "initGui", at = @At("RETURN"))
    private void initGui(CallbackInfo callbackInfo) {
        reconnectTimer = 0;
        buttonList.add(reconnectButton = new GuiButton(1, width / 2 - 100, height / 2 + field_175353_i / 2 + fontRendererObj.FONT_HEIGHT + 22, 98, 20, "Reconnect"));

        drawReconnectDelaySlider();

        buttonList.add(new GuiButton(3, width / 2 - 100, height / 2 + field_175353_i / 2 + fontRendererObj.FONT_HEIGHT + 44, 98, 20, "Random alt"));
        buttonList.add(new GuiButton(4, width / 2 + 2, height / 2 + field_175353_i / 2 + fontRendererObj.FONT_HEIGHT + 44, 98, 20, "Random username"));
        buttonList.add(forgeBypassButton = new GuiButton(5, width / 2 - 100, height / 2 + field_175353_i / 2 + fontRendererObj.FONT_HEIGHT + 66, "Bypass AntiForge: " + (ClientFixes.INSTANCE.getFmlFixesEnabled() ? "On" : "Off")));

        updateSliderText();
    }

    @Inject(method = "actionPerformed", at = @At("HEAD"))
    private void actionPerformed(GuiButton button, CallbackInfo callbackInfo) throws IOException {
        switch (button.id) {
            case 1:
                ServerUtils.INSTANCE.connectToLastServer();
                break;
            case 3:
                final List<MinecraftAccount> accounts = FileManager.INSTANCE.getAccountsConfig().getAccounts();
                if (accounts.isEmpty())
                    break;
                final MinecraftAccount minecraftAccount = accounts.get(new Random().nextInt(accounts.size()));

                mc.displayGuiScreen(new GuiLoginProgress(minecraftAccount, () -> {
                    mc.addScheduledTask(() -> {
                        EventManager.INSTANCE.call(SessionUpdateEvent.INSTANCE);
                        ServerUtils.INSTANCE.connectToLastServer();
                    });
                    return null;
                }, e -> {
                    mc.addScheduledTask(() -> {
                        mc.displayGuiScreen(new GuiDisconnected(new GuiMultiplayer(new GuiMainMenu()), e.getMessage(), new ChatComponentText(e.getMessage())));
                    });
                    return null;
                }, () -> null));

                break;
            case 4:
                RandomUtils.INSTANCE.randomAccount();
                ServerUtils.INSTANCE.connectToLastServer();
                break;
            case 5:
                ClientFixes.INSTANCE.setFmlFixesEnabled(!ClientFixes.INSTANCE.getFmlFixesEnabled());
                forgeBypassButton.displayString = "Bypass AntiForge: " + (ClientFixes.INSTANCE.getFmlFixesEnabled() ? "On" : "Off");
                FileManager.INSTANCE.getValuesConfig().saveConfig();
                break;
        }
    }

    @Override
    public void updateScreen() {
        if (AutoReconnect.INSTANCE.isEnabled()) {
            reconnectTimer++;
            if (reconnectTimer > AutoReconnect.INSTANCE.getDelay() / 50)
                ServerUtils.INSTANCE.connectToLastServer();
        }
    }

    @Inject(method = "drawScreen", at = @At("RETURN"))
    private void drawScreen(CallbackInfo callbackInfo) {
        if (AutoReconnect.INSTANCE.isEnabled()) {
            updateReconnectButton();
        }
    }

    private void drawReconnectDelaySlider() {
        buttonList.add(autoReconnectDelaySlider =
                new GuiSlider(2, width / 2 + 2, height / 2 + field_175353_i / 2
                        + fontRendererObj.FONT_HEIGHT + 22, 98, 20, "AutoReconnect: ",
                        "ms", AutoReconnect.MIN, AutoReconnect.MAX, AutoReconnect.INSTANCE.getDelay(), false, true,
                        guiSlider -> {
                            AutoReconnect.INSTANCE.setDelay(guiSlider.getValueInt());

                            reconnectTimer = 0;
                            updateReconnectButton();
                            updateSliderText();
                        }));
    }

    private void updateSliderText() {
        if (autoReconnectDelaySlider == null)
            return;

        if (!AutoReconnect.INSTANCE.isEnabled()) {
            autoReconnectDelaySlider.displayString = "AutoReconnect: Off";
        } else {
            autoReconnectDelaySlider.displayString = "AutoReconnect: " + Math.floor(AutoReconnect.INSTANCE.getDelay() / 1000.0) + "s";
        }
    }

    private void updateReconnectButton() {
        if (reconnectButton != null)
            reconnectButton.displayString = "Reconnect" + (AutoReconnect.INSTANCE.isEnabled() ? " (" + (AutoReconnect.INSTANCE.getDelay() / 1000 - reconnectTimer / 20) + ")" : "");
    }
}