package net.airplus.injection.forge.mixins.client;

import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.audio.SoundManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@SideOnly(Side.CLIENT)
@Mixin(SoundHandler.class)
public interface AccessorSoundHandler {
    @Accessor("sndManager")
    SoundManager getSndManager();
}
