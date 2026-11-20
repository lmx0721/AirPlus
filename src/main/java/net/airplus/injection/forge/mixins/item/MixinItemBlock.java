/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.item;

import net.airplus.viaversion.vialoadingbase.ViaLoadingBase;
import net.airplus.viaversion.viamcp.ViaMCP;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemBlock.class)
public abstract class MixinItemBlock {

    @Shadow
    @Final
    protected Block block;

    /**
     * ViaFix：跨版本（1.9+ 协议）时 playSoundEffect 播放失效，导致放置方块没有声音。
     * 参照 ViaMCP 的 FixedSoundEngine 接管 onItemUse，
     * 放置成功后改用 playSoundAtPos 播放放置音效（原版路径保持不变）。
     */
    @Inject(method = "onItemUse", at = @At("HEAD"), cancellable = true)
    private void fixBlockPlaceSound(ItemStack stack, EntityPlayer playerIn, World worldIn, BlockPos pos, EnumFacing side, float hitX, float hitY, float hitZ, CallbackInfoReturnable<Boolean> cir) {
        IBlockState iblockstate = worldIn.getBlockState(pos);
        Block block = iblockstate.getBlock();

        if (!block.isReplaceable(worldIn, pos)) {
            pos = pos.offset(side);
        }

        if (stack.stackSize == 0) {
            cir.setReturnValue(false);
            return;
        }

        if (!playerIn.canPlayerEdit(pos, side, stack)) {
            cir.setReturnValue(false);
            return;
        }

        if (!worldIn.canBlockBePlaced(this.block, pos, false, side, null, stack)) {
            cir.setReturnValue(false);
            return;
        }

        int i = ((ItemBlock) (Object) this).getMetadata(stack.getMetadata());
        IBlockState iblockstate1 = this.block.onBlockPlaced(worldIn, pos, side, hitX, hitY, hitZ, i, playerIn);

        if (worldIn.setBlockState(pos, iblockstate1, 3)) {
            iblockstate1 = worldIn.getBlockState(pos);

            if (iblockstate1.getBlock() == this.block) {
                ItemBlock.setTileEntityNBT(worldIn, playerIn, pos, stack);
                this.block.onBlockPlacedBy(worldIn, pos, iblockstate1, playerIn, stack);
            }

            if (ViaMCP.isReady() && ViaLoadingBase.getInstance().getTargetVersion().getVersion() != ViaMCP.NATIVE_VERSION) {
                Minecraft.getMinecraft().theWorld.playSoundAtPos(pos.add(0.5, 0.5, 0.5), this.block.stepSound.getPlaceSound(), (this.block.stepSound.getVolume() + 1.0F) / 2.0F, this.block.stepSound.getFrequency() * 0.8F, false);
            } else {
                worldIn.playSoundEffect((float) pos.getX() + 0.5F, (float) pos.getY() + 0.5F, (float) pos.getZ() + 0.5F, this.block.stepSound.getPlaceSound(), (this.block.stepSound.getVolume() + 1.0F) / 2.0F, this.block.stepSound.getFrequency() * 0.8F);
            }

            --stack.stackSize;
        }

        cir.setReturnValue(true);
    }
}
