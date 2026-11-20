/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.block;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.airplus.viaversion.vialoadingbase.ViaLoadingBase;
import net.airplus.viaversion.viamcp.ViaMCP;
import net.minecraft.block.BlockFarmland;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockFarmland.class)
@SideOnly(Side.CLIENT)
public abstract class MixinBlockFarmland {

    /**
     * PacketFix：跨版本（目标协议 >= 1.12.2）时农田碰撞箱高度为 15/16，
     * 与 1.12.2 服务器判定保持一致。
     */
    @Inject(method = "getCollisionBoundingBox", at = @At("HEAD"), cancellable = true)
    private void packetFix$getCollisionBoundingBox(World worldIn, BlockPos pos, IBlockState state, CallbackInfoReturnable<AxisAlignedBB> cir) {
        if (ViaMCP.isReady() && ViaLoadingBase.getInstance().getTargetVersion().isNewerThanOrEqualTo(ProtocolVersion.v1_12_2)) {
            cir.setReturnValue(new AxisAlignedBB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 0.9375, pos.getZ() + 1));
        }
    }
}
