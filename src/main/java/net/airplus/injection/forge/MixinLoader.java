/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge;

import net.airplus.injection.transformers.ForgeNetworkTransformer;
import net.airplus.script.remapper.injection.transformers.AbstractJavaLinkerTransformer;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/**
 * CoreMod kept ONLY to register the two non-mixin ASM transformers.
 * Mixin bootstrap / config is now handled by Architectury Loom (forge { mixinConfig } + TweakClass).
 */
public class MixinLoader implements IFMLLoadingPlugin {

    public MixinLoader() {
        System.out.println("[AirPlus] CoreMod loaded (ASM transformers only; mixin bootstrap handled by Loom).");
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[] {
                ForgeNetworkTransformer.class.getName(),
                AbstractJavaLinkerTransformer.class.getName()
        };
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
