/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.block;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.airplus.features.module.modules.movement.FastClimb;
import net.airplus.viaversion.vialoadingbase.ViaLoadingBase;
import net.airplus.viaversion.viamcp.ViaMCP;
import net.minecraft.block.BlockLadder;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(BlockLadder.class)
@SideOnly(Side.CLIENT)
public abstract class MixinBlockLadder extends MixinBlock {

    @Shadow
    @Final
    public static PropertyDirection FACING;

    @ModifyConstant(method = "setBlockBoundsBasedOnState", constant = @Constant(floatValue = 0.125F))
    private float injectAACWallClimb(float constant) {
        // PacketFix：跨版本（目标协议 >= 1.12.2）时梯子厚度为 0.1875（3/16）
        if (ViaMCP.isReady() && ViaLoadingBase.getInstance().getTargetVersion().isNewerThanOrEqualTo(ProtocolVersion.v1_12_2)) {
            return 0.1875f;
        }

        FastClimb fastClimb = FastClimb.INSTANCE;

        return fastClimb.handleEvents() && fastClimb.getMode().equals("AAC3.0.0") ? 0.99f : constant;
    }
}
