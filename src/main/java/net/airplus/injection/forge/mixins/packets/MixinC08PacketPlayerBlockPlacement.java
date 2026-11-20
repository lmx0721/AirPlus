/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.packets;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.airplus.utils.rotation.Rotation;
import net.airplus.utils.rotation.RotationUtils;
import net.airplus.viaversion.vialoadingbase.ViaLoadingBase;
import net.airplus.viaversion.viamcp.ViaMCP;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;

@Mixin(C08PacketPlayerBlockPlacement.class)
@SideOnly(Side.CLIENT)
public abstract class MixinC08PacketPlayerBlockPlacement {

    @Shadow
    private BlockPos position;

    @Shadow
    private int placedBlockDirection;

    @Shadow
    private ItemStack stack;

    @Shadow
    private float facingX;

    @Shadow
    private float facingY;

    @Shadow
    private float facingZ;

    /**
     * PacketFix：跨版本（目标协议 > 1.8，协议号 > 47）时修正方块放置的命中数据。
     *
     * 1.9+ 服务端在收到 C08 后，会取其后 1 tick 内的 C03（飞行包）中携带的旋转
     * 做射线检测来校验本次放置（Grim 的判断方式）。因此这里：
     *
     * 1. 用即将随下一个 C03 发送的旋转（RotationUtils.currentRotation）重新做射线检测，
     *    使 C08 的命中偏移与 Grim 校验时使用的 C03 旋转一致；若旋转未命中同一方块同一面，
     *    则回退为原始偏移。
     * 2. 命中偏移按 1/16 精度缩放写入：1.9-1.13 服务器直接按 byte/16 解析，
     *    1.14+ 由 ViaVersion 转换为 float（byte/16），两种情况均需 *16 缩放，
     *    否则服务端读到的命中点会错误地落在方块角上，导致校验不通过。
     */
    @Inject(method = "writePacketData", at = @At("HEAD"), cancellable = true)
    private void packetFix$writePacketData(PacketBuffer buf, CallbackInfo ci) throws IOException {
        buf.writeBlockPos(this.position);
        buf.writeByte(this.placedBlockDirection);
        buf.writeItemStackToBuffer(this.stack);

        if (ViaMCP.isReady() && ViaLoadingBase.getInstance().getTargetVersion().getVersion() > ProtocolVersion.v1_8.getVersion()) {
            float[] facing = packetFix$recalculateFacing();

            buf.writeByte((int) (facing[0] * 16.0F));
            buf.writeByte((int) (facing[1] * 16.0F));
            buf.writeByte((int) (facing[2] * 16.0F));
        } else {
            buf.writeByte((int) (this.facingX * 16.0F));
            buf.writeByte((int) (this.facingY * 16.0F));
            buf.writeByte((int) (this.facingZ * 16.0F));
        }

        ci.cancel();
    }

    /**
     * 用下一个 C03 将携带的旋转重新射线检测，计算与 C03 旋转一致的命中偏移。
     * 旋转不可用或未命中同一方块同一面时返回原始偏移。
     */
    private float[] packetFix$recalculateFacing() {
        float[] fallback = {this.facingX, this.facingY, this.facingZ};

        // 非有效方块面（255 = 对空使用物品），无需重算
        if (this.placedBlockDirection < 0 || this.placedBlockDirection > 5) {
            return fallback;
        }

        try {
            Minecraft mc = Minecraft.getMinecraft();
            EntityPlayer player = mc.thePlayer;

            if (player == null) {
                return fallback;
            }

            Rotation rotation = RotationUtils.INSTANCE.getCurrentRotation();

            if (rotation == null) {
                rotation = new Rotation(player.rotationYaw, player.rotationPitch);
            }

            Vec3 eyes = player.getPositionEyes(1.0F);
            Vec3 direction = RotationUtils.INSTANCE.getVectorForRotation(rotation);
            double reach = mc.playerController.getBlockReachDistance();
            Vec3 end = eyes.addVector(direction.xCoord * reach, direction.yCoord * reach, direction.zCoord * reach);

            MovingObjectPosition raytrace = player.worldObj.rayTraceBlocks(eyes, end, false, false, true);

            if (raytrace == null || raytrace.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
                return fallback;
            }

            if (!raytrace.getBlockPos().equals(this.position)
                    || raytrace.sideHit.getIndex() != this.placedBlockDirection) {
                return fallback;
            }

            Vec3 offset = raytrace.hitVec.subtract(
                    new Vec3(this.position.getX(), this.position.getY(), this.position.getZ())
            );

            return new float[]{(float) offset.xCoord, (float) offset.yCoord, (float) offset.zCoord};
        } catch (Exception any) {
            return fallback;
        }
    }
}
