/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.injection.forge.mixins.render;

import com.google.common.base.Predicates;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.airplus.event.EventManager;
import net.airplus.event.Render3DEvent;
import net.airplus.features.module.modules.combat.Backtrack;
import net.airplus.features.module.modules.misc.OverrideRaycast;
import net.airplus.features.module.modules.player.Reach;
import net.airplus.features.module.modules.render.*;
import net.airplus.utils.client.ClientUtils;
import net.airplus.utils.rotation.Rotation;
import net.airplus.utils.rotation.RotationUtils;
import net.airplus.viaversion.vialoadingbase.ViaLoadingBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItemFrame;
import net.minecraft.potion.Potion;
import net.minecraft.util.*;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Mixin(EntityRenderer.class)
@SideOnly(Side.CLIENT)
public abstract class MixinEntityRenderer {

    @Shadow
    private Entity pointedEntity;

    @Shadow
    private Minecraft mc;

    // Camera: vanilla keeps both at 4.0F (updateRenderer copies distance into temp each frame),
    // orientCamera interpolates d3 = temp + (distance - temp) * partialTicks
    @Shadow
    private float thirdPersonDistance;

    @Shadow
    private float thirdPersonDistanceTemp;

    // @Final marks the target field as final; the Java declaration must not be final, otherwise
    // javac rejects the mixin class (blank final field in the implicit default constructor).
    @Final
    @Shadow
    private int[] lightmapColors;
    @Final
    @Shadow
    private DynamicTexture lightmapTexture;

    @Shadow
    private float torchFlickerX;

    @Shadow
    private float bossColorModifier;
    @Shadow
    private float bossColorModifierPrev;

    @Shadow
    private boolean lightmapUpdateNeeded;

    @Shadow
    protected abstract float getNightVisionBrightness(EntityLivingBase p_getNightVisionBrightness_1_, float p_getNightVisionBrightness_2_);

    @Inject(method = "renderWorldPass", at = @At(value = "INVOKE", target = "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V", shift = At.Shift.AFTER))
    private void renderWorldPass(int pass, float partialTicks, long finishTimeNano, CallbackInfo callbackInfo) {
        /*
          This is done so it supports Opti-Fine while also supporting any mod that cancels the ForgeHooksClient.renderFirstPersonHand event.
          For example, OrangeMarshall's 1.7 Animations mod.
         */
        if (ClientUtils.INSTANCE.getProfilerName().equals("hand")) {
            FreeLook.INSTANCE.runWithoutSavingRotations(() -> {
                FreeLook.INSTANCE.restoreOriginalRotation();
                EventManager.INSTANCE.call(new Render3DEvent(partialTicks));
                /*
                 * 手部物品半透明根治（第四轮：全面状态同步）。
                 * 机制：手部物品经 renderItemModelTransform 总是带 blend(770,771) 渲染且不重置当前颜色，
                 * fragment alpha = 方块图集 alpha × 当前颜色 alpha（× 残留纹理 alpha）。Render3DEvent /
                 * 上一帧 HUD 的任何 GlStateManager 缓存失同步都会让以下缓存守卫调用被跳过：
                 *  - active texture unit 卡在非 0 → 方块图集绑到错误单元，unit 0 残留 HUD 贴图
                 *    （如 potion 玻璃 alpha≈0.4）→ 物品被其 alpha 调制而半透明
                 *  - bindTexture 缓存失同步 → 图集重绑被跳过，unit 0 采样残留贴图
                 *  - enableColorMaterial/enableLighting 缓存失同步 → 材质不跟踪当前色，
                 *    以固定 diffuse alpha=0.8 渲染
                 *  - blendFunc 缓存失同步 → 错误混合因子
                 * 全部两步法强制真实 GL 调用，把缓存与实际状态拉回一致。
                 */
                airplus$dumpHandState();
                GL20.glUseProgram(0); // 防 shader 泄漏：固定管线手部渲染若被残留 program 劫持会透滤
                // 纹理单元强制归 0：OpenGlHelper 是真实调用；GlStateManager 索引也要拉回，
                // 否则后续 enableTexture2D/bindTexture 会作用在错误单元
                OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
                GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
                // unit-0 绑定两步法：脏缓存强制 vanilla 随后的 bindTexture(方块图集) 真实执行
                GlStateManager.bindTexture(0);
                // unit-0 TEXTURE_2D 开关两步法：保证实际开启且缓存一致
                GlStateManager.disableTexture2D();
                GlStateManager.enableTexture2D();
                GlStateManager.enableBlend();
                GlStateManager.disableBlend();
                // blendFunc 两步法：强制真实 glBlendFuncSeparate
                GlStateManager.tryBlendFuncSeparate(0, 0, 0, 0);
                GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                // 当前颜色两步法
                GlStateManager.color(0.0F, 0.0F, 0.0F, 0.0F);
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                GlStateManager.depthMask(true);
                // colorMaterial/lighting 两步法：手部渲染第一步 enableStandardItemLighting
                // 的对应调用有缓存，先脏化缓存保证真实执行
                GlStateManager.disableColorMaterial();
                GlStateManager.disableLighting();
                FreeLook.INSTANCE.useModifiedRotation();
                return null;
            });
        }
    }

    @Inject(method = "hurtCameraEffect", at = @At("HEAD"), cancellable = true)
    private void injectHurtCameraEffect(CallbackInfo callbackInfo) {
        if (NoHurtCam.INSTANCE.handleEvents()) {
            callbackInfo.cancel();
        }
    }

    @ModifyConstant(method = "orientCamera", constant = @Constant(intValue = 8))
    private int injectCameraClip(int eight) {
        // 8 is the bound of vanilla's 8-direction third-person camera collision raytrace
        // loop; 0 skips it entirely (no camera collision)
        return CameraClip.INSTANCE.handleEvents() || Camera.INSTANCE.handleEvents() ? 0 : eight;
    }

    @Inject(at = @At("HEAD"), method = "updateCameraAndRender")
    private void injectCameraModifications(float p_updateCameraAndRender_1_, long p_updateCameraAndRender_2_, CallbackInfo ci) {
        FreeCam.INSTANCE.useModifiedPosition();

        // Camera: per-frame perspective-switch animation state update, must run before
        // orientCamera / renderWorldPass read thirdPersonView this frame
        if (Camera.INSTANCE.handleEvents()) {
            Camera.INSTANCE.updateViewTransition();
        }
    }

    @Inject(method = "orientCamera", at = @At(value = "HEAD"))
    private void injectFreeLook(float p_orientCamera_1_, CallbackInfo ci) {
        FreeLook.INSTANCE.useModifiedRotation();
    }

    /**
     * Camera: replace the vanilla third-person camera distance (4.0F, clamped by the
     * collision raytrace loop skipped in {@link #injectCameraClip}) with the animated
     * distance (settles at the fixed Distance value), the equivalent of SetsunaClient's
     * Camera#getMaxZoom hook. While a perspective switch is in progress the animated
     * distance collapses to 0 and expands again, producing the zoom transition.
     */
    @Inject(method = "orientCamera", at = @At("HEAD"))
    private void injectCameraDistance(float p_orientCamera_1_, CallbackInfo ci) {
        final Camera camera = Camera.INSTANCE;

        if (camera.handleEvents() && !FreeCam.INSTANCE.handleEvents()) {
            final float animated = camera.getAnimatedDistance();
            this.thirdPersonDistance = animated;
            this.thirdPersonDistanceTemp = animated;
        } else {
            this.thirdPersonDistance = 4.0F;
            this.thirdPersonDistanceTemp = 4.0F;
        }
    }

    /**
     * Camera: "Action" camera smoothing, the equivalent of SetsunaClient's
     * Camera#alignWithEntity setPosition hook. Appending a world-space translate at TAIL
     * post-multiplies the camera matrix, moving the camera from the real anchor (the
     * interpolated eye position) to the smoothed one returned by
     * {@link Camera#updateActionCamera}. Non-back third-person views reset the state.
     */
    @Inject(method = "orientCamera", at = @At("TAIL"))
    private void injectCameraAction(float p_orientCamera_1_, CallbackInfo ci) {
        final Camera camera = Camera.INSTANCE;

        // Only the third-person back view (thirdPersonView == 1) is smoothed
        if (!camera.handleEvents() || this.mc.gameSettings.thirdPersonView != 1) {
            camera.resetCameraPos();
            return;
        }

        // FreeCam manipulates the camera itself and takes priority
        if (FreeCam.INSTANCE.handleEvents()) {
            camera.resetCameraPos();
            return;
        }

        if (!camera.isActionEnabled()) {
            return;
        }

        final Entity entity = this.mc.getRenderViewEntity();
        if (entity == null) {
            camera.resetCameraPos();
            return;
        }

        // The camera anchor vanilla uses in orientCamera: the interpolated eye position
        final Vec3 targetPos = new Vec3(
                entity.prevPosX + (entity.posX - entity.prevPosX) * (double) p_orientCamera_1_,
                entity.prevPosY + (entity.posY - entity.prevPosY) * (double) p_orientCamera_1_ + (double) entity.getEyeHeight(),
                entity.prevPosZ + (entity.posZ - entity.prevPosZ) * (double) p_orientCamera_1_);

        camera.updateActionCamera(targetPos);

        final Vec3 cameraPos = camera.getCameraPos();
        if (cameraPos != null) {
            GlStateManager.translate(targetPos.xCoord - cameraPos.xCoord,
                    targetPos.yCoord - cameraPos.yCoord,
                    targetPos.zCoord - cameraPos.zCoord);
        }
    }

    /**
     * FPSBoost: stop the per-tick torch flicker. The lightmap is still refreshed
     * by the 500ms fallback in {@link #updateLightmap}, so day/night transitions work.
     */
    @Inject(method = "updateTorchFlicker", at = @At("HEAD"), cancellable = true)
    private void injectStaticLightmap(CallbackInfo ci) {
        final FPSBoost fpsBoost = FPSBoost.INSTANCE;

        if (fpsBoost.handleEvents() && fpsBoost.getStaticLightmap()) {
            ci.cancel();
        }
    }

    /**
     * FPSBoost: skip rain/snow quad rendering.
     */
    @Inject(method = "renderRainSnow", at = @At("HEAD"), cancellable = true)
    private void injectNoWeather(CallbackInfo ci) {
        final FPSBoost fpsBoost = FPSBoost.INSTANCE;

        if (fpsBoost.handleEvents() && fpsBoost.getNoWeather()) {
            ci.cancel();
        }
    }

    /**
     * FPSBoost: stop rain/snow particle spawning.
     */
    @Inject(method = "addRainParticles", at = @At("HEAD"), cancellable = true)
    private void injectNoRainParticles(CallbackInfo ci) {
        final FPSBoost fpsBoost = FPSBoost.INSTANCE;

        if (fpsBoost.handleEvents() && fpsBoost.getNoWeather()) {
            ci.cancel();
        }
    }

    @Inject(at = @At("TAIL"), method = "updateCameraAndRender")
    private void injectCameraRestorations(float p_updateCameraAndRender_1_, long p_updateCameraAndRender_2_, CallbackInfo ci) {
        FreeLook.INSTANCE.restoreOriginalRotation();
        FreeCam.INSTANCE.restoreOriginalPosition();
    }

    // FPSBoost: getMouseOver result cache - the raycast is recomputed every frame
    // even when the view, position and world state are completely unchanged.
    @Unique
    private boolean airplus$mouseOverCacheValid;
    @Unique
    private static long airplus$lastHandStateLog = 0L;
    @Unique
    private static final FloatBuffer airplus$dumpBuf = ByteBuffer
            .allocateDirect(16).order(ByteOrder.nativeOrder()).asFloatBuffer();

    /**
     * 手部渲染前把实际 GL 状态（绕过一切缓存，直接 glGet）节流转储，用于定位
     * 「非 blend/program 泄漏型」的半透明根因。每 5s 一条；定位完成后可移除。
     */
    @Unique
    private static void airplus$dumpHandState() {
        if (System.currentTimeMillis() - airplus$lastHandStateLog <= 5000L) {
            return;
        }
        airplus$lastHandStateLog = System.currentTimeMillis();
        airplus$dumpBuf.clear();
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, airplus$dumpBuf);
        LogManager.getLogger("AirPlus").warn(
                "[HandStateDump] blend={} src=0x{} dst=0x{} program={} color=({},{},{},{}) activeTex=0x{} texBind={} tex2D={} colMat={}/{} lighting={} alphaTest={} fog={}",
                GL11.glIsEnabled(GL11.GL_BLEND),
                Integer.toHexString(GL11.glGetInteger(GL11.GL_BLEND_SRC)),
                Integer.toHexString(GL11.glGetInteger(GL11.GL_BLEND_DST)),
                GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
                airplus$dumpBuf.get(0), airplus$dumpBuf.get(1),
                airplus$dumpBuf.get(2), airplus$dumpBuf.get(3),
                Integer.toHexString(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)),
                GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),
                GL11.glIsEnabled(GL11.GL_TEXTURE_2D),
                GL11.glIsEnabled(GL11.GL_COLOR_MATERIAL),
                GL11.glGetInteger(GL11.GL_COLOR_MATERIAL_PARAMETER),
                GL11.glIsEnabled(GL11.GL_LIGHTING),
                GL11.glIsEnabled(GL11.GL_ALPHA_TEST),
                GL11.glIsEnabled(GL11.GL_FOG));
    }
    @Unique
    private World airplus$mouseOverCacheWorld;
    @Unique
    private float airplus$mouseOverCacheYaw;
    @Unique
    private float airplus$mouseOverCachePitch;
    @Unique
    private double airplus$mouseOverCacheX;
    @Unique
    private double airplus$mouseOverCacheY;
    @Unique
    private double airplus$mouseOverCacheZ;
    @Unique
    private float airplus$mouseOverCacheEyeHeight;
    @Unique
    private int airplus$mouseOverCacheEntityCount;
    @Unique
    private long airplus$mouseOverCacheTime;
    @Unique
    private boolean airplus$mouseOverCacheSpoofed;
    @Unique
    private float airplus$mouseOverCacheSpoofYaw;
    @Unique
    private float airplus$mouseOverCacheSpoofPitch;

    /**
     * FPSBoost: reuse the previous frame's raycast result while nothing relevant changed.
     */
    @Unique
    private boolean airplus$canReuseMouseOver() {
        final FPSBoost fpsBoost = FPSBoost.INSTANCE;
        if (!fpsBoost.handleEvents() || !fpsBoost.getMouseOverCache()) {
            return false;
        }

        if (!this.airplus$mouseOverCacheValid || mc.thePlayer == null || mc.theWorld == null) {
            return false;
        }

        // Only cache the first person/local player view
        if (mc.getRenderViewEntity() != mc.thePlayer || mc.theWorld != this.airplus$mouseOverCacheWorld) {
            return false;
        }

        // Silent rotations (KillAura etc.) change the raycast direction without moving the view
        final Rotation currentRotation = RotationUtils.INSTANCE.getCurrentRotation();
        final boolean spoofed = currentRotation != null && OverrideRaycast.INSTANCE.shouldOverride();
        if (spoofed != this.airplus$mouseOverCacheSpoofed
                || spoofed && (Math.abs(currentRotation.getYaw() - this.airplus$mouseOverCacheSpoofYaw) > 0.01F
                || Math.abs(currentRotation.getPitch() - this.airplus$mouseOverCacheSpoofPitch) > 0.01F)) {
            return false;
        }

        final float deltaYaw = Math.abs(mc.thePlayer.rotationYaw - this.airplus$mouseOverCacheYaw);
        final float deltaPitch = Math.abs(mc.thePlayer.rotationPitch - this.airplus$mouseOverCachePitch);
        if (deltaYaw > 0.01F || deltaPitch > 0.01F) {
            return false;
        }

        final double dx = mc.thePlayer.posX - this.airplus$mouseOverCacheX;
        final double dy = mc.thePlayer.posY - this.airplus$mouseOverCacheY;
        final double dz = mc.thePlayer.posZ - this.airplus$mouseOverCacheZ;
        if (dx * dx + dy * dy + dz * dz > 1.0E-6
                || Math.abs(mc.thePlayer.getEyeHeight() - this.airplus$mouseOverCacheEyeHeight) > 1.0E-4) {
            return false;
        }

        // Entities moving through the crosshair still need to be picked up (100ms window)
        return mc.theWorld.loadedEntityList.size() == this.airplus$mouseOverCacheEntityCount
                && Minecraft.getSystemTime() - this.airplus$mouseOverCacheTime < 100L;
    }

    @Unique
    private void airplus$updateMouseOverCache() {
        this.airplus$mouseOverCacheValid = true;
        this.airplus$mouseOverCacheWorld = mc.theWorld;
        this.airplus$mouseOverCacheYaw = mc.thePlayer.rotationYaw;
        this.airplus$mouseOverCachePitch = mc.thePlayer.rotationPitch;
        this.airplus$mouseOverCacheX = mc.thePlayer.posX;
        this.airplus$mouseOverCacheY = mc.thePlayer.posY;
        this.airplus$mouseOverCacheZ = mc.thePlayer.posZ;
        this.airplus$mouseOverCacheEyeHeight = mc.thePlayer.getEyeHeight();
        this.airplus$mouseOverCacheEntityCount = mc.theWorld.loadedEntityList.size();
        this.airplus$mouseOverCacheTime = Minecraft.getSystemTime();

        final Rotation currentRotation = RotationUtils.INSTANCE.getCurrentRotation();
        this.airplus$mouseOverCacheSpoofed = currentRotation != null && OverrideRaycast.INSTANCE.shouldOverride();
        if (this.airplus$mouseOverCacheSpoofed) {
            this.airplus$mouseOverCacheSpoofYaw = currentRotation.getYaw();
            this.airplus$mouseOverCacheSpoofPitch = currentRotation.getPitch();
        }
    }

    /**
     * @author CCBlueX
     */
    @Inject(method = "getMouseOver", at = @At("HEAD"), cancellable = true)
    private void getMouseOver(float p_getMouseOver_1_, CallbackInfo ci) {
        if (this.airplus$canReuseMouseOver()) {
            ci.cancel();
            return;
        }

        Entity entity = mc.getRenderViewEntity();
        if (entity != null && mc.theWorld != null) {
            mc.mcProfiler.startSection("pick");
            mc.pointedEntity = null;

            final Reach reach = Reach.INSTANCE;

            double d0 = reach.handleEvents() ? reach.getMaxRange() : mc.playerController.getBlockReachDistance();
            Vec3 vec3 = entity.getPositionEyes(p_getMouseOver_1_);
            Rotation rotation = new Rotation(mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch);
            Vec3 vec31 = RotationUtils.INSTANCE.getVectorForRotation(RotationUtils.INSTANCE.getCurrentRotation() != null && OverrideRaycast.INSTANCE.shouldOverride() ? RotationUtils.INSTANCE.getCurrentRotation() : rotation);
            double p_rayTrace_1_ = (reach.handleEvents() ? reach.getBuildReach() : d0);
            Vec3 vec32 = vec3.addVector(vec31.xCoord * p_rayTrace_1_, vec31.yCoord * p_rayTrace_1_, vec31.zCoord * p_rayTrace_1_);
            mc.objectMouseOver = entity.worldObj.rayTraceBlocks(vec3, vec32, false, false, true);
            double d1 = d0;
            boolean flag = false;
            if (mc.playerController.extendedReach()) {
                // d0 = 6;
                d1 = 6;
            } else if (d0 > 3) {
                flag = true;
            }

            if (mc.objectMouseOver != null) {
                d1 = mc.objectMouseOver.hitVec.distanceTo(vec3);
            }

            if (reach.handleEvents()) {
                double p_rayTrace_1_2 = reach.getBuildReach();
                Vec3 vec322 = vec3.addVector(vec31.xCoord * p_rayTrace_1_2, vec31.yCoord * p_rayTrace_1_2, vec31.zCoord * p_rayTrace_1_2);
                final MovingObjectPosition movingObjectPosition = entity.worldObj.rayTraceBlocks(vec3, vec322, false, false, true);

                if (movingObjectPosition != null) d1 = movingObjectPosition.hitVec.distanceTo(vec3);
            }

            pointedEntity = null;
            Vec3 vec33 = null;
            List<Entity> list = mc.theWorld.getEntities(Entity.class, Predicates.and(EntitySelectors.NOT_SPECTATING, p_apply_1_ -> p_apply_1_ != null && p_apply_1_.canBeCollidedWith() && p_apply_1_ != entity));
            double d2 = d1;

            // Reused across entities to avoid per-entity allocations (this runs every frame)
            final ArrayList<AxisAlignedBB> boxes = new ArrayList<>(2);

            for (Entity entity1 : list) {
                final float f1 = entity1.getCollisionBorderSize();

                boxes.clear();
                final AxisAlignedBB entityBox = entity1.getEntityBoundingBox();
                boxes.add(f1 == 0.0F ? entityBox : entityBox.expand(f1, f1, f1));

                // Only run the backtracking loop (and allocate its closure) when it can actually produce data
                final Backtrack backtrack = Backtrack.INSTANCE;
                if (backtrack.getState() && entity1 instanceof net.minecraft.entity.player.EntityPlayer && !"Modern".equals(backtrack.getMode())) {
                    backtrack.loopThroughBacktrackData(entity1, () -> {
                        boxes.add(entity1.getEntityBoundingBox().expand(f1, f1, f1));
                        return false;
                    });
                }

                for (final AxisAlignedBB axisalignedbb : boxes) {
                    MovingObjectPosition movingobjectposition = axisalignedbb.calculateIntercept(vec3, vec32);
                    if (axisalignedbb.isVecInside(vec3)) {
                        if (d2 >= 0) {
                            pointedEntity = entity1;
                            vec33 = movingobjectposition == null ? vec3 : movingobjectposition.hitVec;
                            d2 = 0;
                        }
                    } else if (movingobjectposition != null) {
                        double d3 = vec3.distanceTo(movingobjectposition.hitVec);
                        if (d3 < d2 || d2 == 0) {
                            if (entity1 == entity.ridingEntity && !entity.canRiderInteract()) {
                                if (d2 == 0) {
                                    pointedEntity = entity1;
                                    vec33 = movingobjectposition.hitVec;
                                }
                            } else {
                                pointedEntity = entity1;
                                vec33 = movingobjectposition.hitVec;
                                d2 = d3;
                            }
                        }
                    }
                }
            }

            // PacketFix：跨版本（目标协议 >= 1.12.2）时实体拾取最大距离由 3.0 改为 2.9
            final double maxPickDistance = ViaLoadingBase.getInstance().getTargetVersion().isNewerThanOrEqualTo(ProtocolVersion.v1_12_2) ? 2.9D : 3.0D;

            if (pointedEntity != null && flag && vec3.distanceTo(vec33) > (reach.handleEvents() ? reach.getCombatReach() : maxPickDistance)) {
                pointedEntity = null;
                mc.objectMouseOver = new MovingObjectPosition(MovingObjectPosition.MovingObjectType.MISS, Objects.requireNonNull(vec33), null, new BlockPos(vec33));
            }

            if (pointedEntity != null && (d2 < d1 || mc.objectMouseOver == null)) {
                mc.objectMouseOver = new MovingObjectPosition(pointedEntity, vec33);
                if (pointedEntity instanceof EntityLivingBase || pointedEntity instanceof EntityItemFrame) {
                    mc.pointedEntity = pointedEntity;
                }
            }

            mc.mcProfiler.endSection();

            if (entity == mc.thePlayer) {
                this.airplus$updateMouseOverCache();
            }
        }

        ci.cancel();
    }

    // FPSBoost: with StaticLightmap enabled, updateTorchFlicker is cancelled and this
    // overwritten method (unlike vanilla) has no internal time-based fallback - force a
    // refresh every 500ms so day/night transitions still work.
    @Unique
    private long airplus$lastLightmapRefresh;
    @Unique
    private World airplus$lastLightmapWorld;

    /**
     * @author opZywl
     * @reason Update Light Map
     */
    @Inject(method = "updateLightmap", at = @At("HEAD"), cancellable = true)
    private void updateLightmap(float p_updateLightmap_1_, CallbackInfo ci) {
        final Ambience ambience = Ambience.INSTANCE;

        boolean forceLightmapRefresh = false;
        final FPSBoost fpsBoost = FPSBoost.INSTANCE;
        if (fpsBoost.handleEvents() && fpsBoost.getStaticLightmap()) {
            if (this.mc.theWorld != this.airplus$lastLightmapWorld) {
                this.airplus$lastLightmapWorld = this.mc.theWorld;
                this.airplus$lastLightmapRefresh = 0L;
            }

            final long now = Minecraft.getSystemTime();
            if (now - this.airplus$lastLightmapRefresh >= 500L) {
                this.airplus$lastLightmapRefresh = now;
                forceLightmapRefresh = true;
            }
        } else {
            this.airplus$lastLightmapRefresh = 0L;
        }

        if (this.lightmapUpdateNeeded || forceLightmapRefresh) {
            this.mc.mcProfiler.startSection("lightTex");
            World world = this.mc.theWorld;
            // FPSBoost can force this refresh every 500ms, which may hit a world whose
            // player has not been spawned (or has already been removed) yet.
            if (world != null && this.mc.thePlayer != null) {
                float f = world.getSunBrightness(1.0F);
                float f1 = f * 0.95F + 0.05F;

                for (int i = 0; i < 256; ++i) {
                    float f2 = world.provider.getLightBrightnessTable()[i / 16] * f1;
                    float f3 = world.provider.getLightBrightnessTable()[i % 16] * (this.torchFlickerX * 0.1F + 1.5F);
                    if (world.getLastLightningBolt() > 0) {
                        f2 = world.provider.getLightBrightnessTable()[i / 16];
                    }

                    float f4 = f2 * (f * 0.65F + 0.35F);
                    float f5 = f2 * (f * 0.65F + 0.35F);
                    float f6 = f3 * ((f3 * 0.6F + 0.4F) * 0.6F + 0.4F);
                    float f7 = f3 * (f3 * f3 * 0.6F + 0.4F);
                    float f8 = f4 + f3;
                    float f9 = f5 + f6;
                    float f10 = f2 + f7;
                    f8 = f8 * 0.96F + 0.03F;
                    f9 = f9 * 0.96F + 0.03F;
                    f10 = f10 * 0.96F + 0.03F;
                    if (this.bossColorModifier > 0.0F) {
                        float f11 = this.bossColorModifierPrev + (this.bossColorModifier - this.bossColorModifierPrev) * p_updateLightmap_1_;
                        f8 = f8 * (1.0F - f11) + f8 * 0.7F * f11;
                        f9 = f9 * (1.0F - f11) + f9 * 0.6F * f11;
                        f10 = f10 * (1.0F - f11) + f10 * 0.6F * f11;
                    }

                    if (world.provider.getDimensionId() == 1) {
                        f8 = 0.22F + f3 * 0.75F;
                        f9 = 0.28F + f6 * 0.75F;
                        f10 = 0.25F + f7 * 0.75F;
                    }

                    if (this.mc.thePlayer.isPotionActive(Potion.nightVision)) {
                        float f15 = this.getNightVisionBrightness(this.mc.thePlayer, p_updateLightmap_1_);
                        float f12 = 1.0F / f8;
                        if (f12 > 1.0F / f9) {
                            f12 = 1.0F / f9;
                        }

                        if (f12 > 1.0F / f10) {
                            f12 = 1.0F / f10;
                        }

                        f8 = f8 * (1.0F - f15) + f8 * f12 * f15;
                        f9 = f9 * (1.0F - f15) + f9 * f12 * f15;
                        f10 = f10 * (1.0F - f15) + f10 * f12 * f15;
                    }

                    if (f8 > 1.0F) {
                        f8 = 1.0F;
                    }

                    if (f9 > 1.0F) {
                        f9 = 1.0F;
                    }

                    if (f10 > 1.0F) {
                        f10 = 1.0F;
                    }

                    float f16 = this.mc.gameSettings.gammaSetting;
                    float f17 = 1.0F - f8;
                    float f13 = 1.0F - f9;
                    float f14 = 1.0F - f10;
                    f17 = 1.0F - f17 * f17 * f17 * f17;
                    f13 = 1.0F - f13 * f13 * f13 * f13;
                    f14 = 1.0F - f14 * f14 * f14 * f14;
                    f8 = f8 * (1.0F - f16) + f17 * f16;
                    f9 = f9 * (1.0F - f16) + f13 * f16;
                    f10 = f10 * (1.0F - f16) + f14 * f16;
                    f8 = f8 * 0.96F + 0.03F;
                    f9 = f9 * 0.96F + 0.03F;
                    f10 = f10 * 0.96F + 0.03F;
                    if (f8 > 1.0F) {
                        f8 = 1.0F;
                    }

                    if (f9 > 1.0F) {
                        f9 = 1.0F;
                    }

                    if (f10 > 1.0F) {
                        f10 = 1.0F;
                    }

                    if (f8 < 0.0F) {
                        f8 = 0.0F;
                    }

                    if (f9 < 0.0F) {
                        f9 = 0.0F;
                    }

                    if (f10 < 0.0F) {
                        f10 = 0.0F;
                    }

                    int j = 255;
                    int k = (int) (f8 * 255.0F);
                    int l = (int) (f9 * 255.0F);
                    int i1 = (int) (f10 * 255.0F);
                    this.lightmapColors[i] = ambience.handleEvents() && ambience.getWorldColor() ? ambience.getColor().getRGB() : j << 24 | k << 16 | l << 8 | i1;
                }

                this.lightmapTexture.updateDynamicTexture();
                this.lightmapUpdateNeeded = false;
            }

            // Kept outside the guard: vanilla also closes the section when there is no world,
            // and an unbalanced startSection() would corrupt the profiler section stack that
            // other injections (e.g. the "hand" check in renderWorldPass) rely on.
            this.mc.mcProfiler.endSection();
        }

        ci.cancel();
    }

    /**
     * Properly implement the confusion option from AntiBlind module
     */
    @Redirect(method = "setupCameraTransform", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/entity/EntityPlayerSP;isPotionActive(Lnet/minecraft/potion/Potion;)Z"))
    private boolean injectAntiBlindA(EntityPlayerSP instance, Potion potion) {
        AntiBlind module = AntiBlind.INSTANCE;

        return (!module.handleEvents() || !module.getConfusionEffect()) && instance.isPotionActive(potion);
    }

    @Redirect(method = {"setupFog", "updateFogColor"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/EntityLivingBase;isPotionActive(Lnet/minecraft/potion/Potion;)Z"))
    private boolean injectAntiBlindB(EntityLivingBase instance, Potion potion) {
        if (instance != mc.thePlayer) {
            return instance.isPotionActive(potion);
        }

        AntiBlind module = AntiBlind.INSTANCE;

        return (!module.handleEvents() || !module.getConfusionEffect()) && instance.isPotionActive(potion);
    }
}