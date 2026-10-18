/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.render

import co.uk.hexeption.utils.OutlineUtils
import net.airplus.event.Render2DEvent
import net.airplus.event.Render3DEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.modules.world.ChestAura.clickedTileEntities
import net.airplus.utils.attack.EntityUtils.isLookingOnEntities
import net.airplus.utils.block.toVec
import net.airplus.utils.client.ClientUtils.LOGGER
import net.airplus.utils.client.ClientUtils.disableFastRender
import net.airplus.utils.extensions.*
import net.airplus.utils.render.ColorSettingsInteger
import net.airplus.utils.render.RenderUtils.draw2D
import net.airplus.utils.render.RenderUtils.drawBlockBox
import net.airplus.utils.render.RenderUtils.drawEntityBox
import net.airplus.utils.render.RenderUtils.glColor
import net.airplus.utils.render.shader.shaders.GlowShader
import net.airplus.utils.rotation.RotationUtils.isEntityHeightVisible
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher
import net.minecraft.entity.item.EntityMinecartChest
import net.minecraft.tileentity.*
import org.lwjgl.opengl.GL11.*
import java.awt.Color
import kotlin.math.pow

object StorageESP : Module("StorageESP", Category.RENDER) {
    private val mode by
    choices("Mode", arrayOf("Box", "OtherBox", "Outline", "Glow", "2D", "WireFrame"), "Outline")

    private val glowRenderScale by float("Glow-Renderscale", 1f, 0.5f..2f) { mode == "Glow" }
    private val glowRadius by int("Glow-Radius", 4, 1..5) { mode == "Glow" }
    private val glowFade by int("Glow-Fade", 10, 0..30) { mode == "Glow" }
    private val glowTargetAlpha by float("Glow-Target-Alpha", 0f, 0f..1f) { mode == "Glow" }

    private val espColorMode by choices("ESP-ColorMode", arrayOf("None", "Custom"), "None")
    private val espColor = ColorSettingsInteger(this, "ESPColor")
    { espColorMode == "Custom" }.with(255, 179, 72)

    private val maxRenderDistance by int("MaxRenderDistance", 100, 1..500).onChanged { value ->
        maxRenderDistanceSq = value.toDouble().pow(2)
    }

    private val onLook by boolean("OnLook", false)
    private val maxAngleDifference by float("MaxAngleDifference", 90f, 5.0f..90f) { onLook }

    private val thruBlocks by boolean("ThruBlocks", true)

    private var maxRenderDistanceSq = 0.0
        set(value) {
            field = if (value <= 0.0) maxRenderDistance.toDouble().pow(2.0) else value
        }

    private val chest by boolean("Chest", true)
    private val enderChest by boolean("EnderChest", true)
    private val furnace by boolean("Furnace", true)
    private val dispenser by boolean("Dispenser", true)
    private val hopper by boolean("Hopper", true)
    private val enchantmentTable by boolean("EnchantmentTable", false)
    private val brewingStand by boolean("BrewingStand", false)
    private val sign by boolean("Sign", false)

    // Constant colors, previously allocated per tile/entity per frame
    private val minecartChestColor = Color(0, 66, 255)

    private fun getColor(tileEntity: TileEntity): Color? {
        return if (espColorMode == "Custom") {
            when {
                chest && tileEntity is TileEntityChest && tileEntity !in clickedTileEntities ->
                    Color(espColor.color().rgb)

                enderChest && tileEntity is TileEntityEnderChest && tileEntity !in clickedTileEntities ->
                    Color(espColor.color().rgb)

                furnace && tileEntity is TileEntityFurnace -> Color(espColor.color().rgb)
                dispenser && tileEntity is TileEntityDispenser -> Color(espColor.color().rgb)
                hopper && tileEntity is TileEntityHopper -> Color(espColor.color().rgb)
                enchantmentTable && tileEntity is TileEntityEnchantmentTable -> Color(espColor.color().rgb)
                brewingStand && tileEntity is TileEntityBrewingStand -> Color(espColor.color().rgb)
                sign && tileEntity is TileEntitySign -> Color(espColor.color().rgb)
                else -> null
            }
        } else {
            when {
                chest && tileEntity is TileEntityChest && tileEntity !in clickedTileEntities -> Color(0, 66, 255)
                enderChest && tileEntity is TileEntityEnderChest && tileEntity !in clickedTileEntities -> Color.MAGENTA
                furnace && tileEntity is TileEntityFurnace -> Color.BLACK
                dispenser && tileEntity is TileEntityDispenser -> Color.BLACK
                hopper && tileEntity is TileEntityHopper -> Color.GRAY
                enchantmentTable && tileEntity is TileEntityEnchantmentTable -> Color(166, 202, 240) // Light blue
                brewingStand && tileEntity is TileEntityBrewingStand -> Color.ORANGE
                sign && tileEntity is TileEntitySign -> Color.RED
                else -> null
            }
        }
    }

    val onRender3D = handler<Render3DEvent> { event ->
        try {
            if (mode == "Outline") {
                disableFastRender()
                OutlineUtils.checkSetupFBO()
            }

            val gamma = mc.gameSettings.gammaSetting

            mc.gameSettings.gammaSetting = 100000f

            for (tileEntity in mc.theWorld.loadedTileEntityList) {
                val tileEntityPos = tileEntity.pos

                // Cheap distance cull first, before type checks and color allocation
                val distanceSquared = mc.thePlayer.getDistanceSq(
                    tileEntityPos.x.toDouble(),
                    tileEntityPos.y.toDouble(),
                    tileEntityPos.z.toDouble()
                )

                if (distanceSquared > maxRenderDistanceSq)
                    continue

                val color = getColor(tileEntity) ?: continue

                if (!(tileEntity is TileEntityChest || tileEntity is TileEntityEnderChest)) {
                    drawBlockBox(tileEntity.pos, color, mode != "OtherBox")

                    if (tileEntity !is TileEntityEnchantmentTable)
                        continue
                }

                if (onLook && !isLookingOnEntities(tileEntity, maxAngleDifference.toDouble()))
                    continue

                if (!thruBlocks && !isEntityHeightVisible(tileEntity)) continue

                when (mode) {
                    "OtherBox", "Box" -> drawBlockBox(tileEntity.pos, color, mode != "OtherBox")
                    "2D" -> draw2D(tileEntity.pos, color.rgb, Color.BLACK.rgb)
                    "Outline" -> {
                        glColor(color)
                        OutlineUtils.renderOne(3F)
                        TileEntityRendererDispatcher.instance.renderTileEntity(tileEntity, event.partialTicks, -1)
                        OutlineUtils.renderTwo()
                        TileEntityRendererDispatcher.instance.renderTileEntity(tileEntity, event.partialTicks, -1)
                        OutlineUtils.renderThree()
                        TileEntityRendererDispatcher.instance.renderTileEntity(tileEntity, event.partialTicks, -1)
                        OutlineUtils.renderFour(color)
                        TileEntityRendererDispatcher.instance.renderTileEntity(tileEntity, event.partialTicks, -1)
                        OutlineUtils.renderFive()
                        OutlineUtils.setColor(Color.WHITE)
                    }

                    "WireFrame" -> {
                        glPushMatrix()
                        glPushAttrib(GL_ALL_ATTRIB_BITS)
                        glPolygonMode(GL_FRONT_AND_BACK, GL_LINE)
                        glDisable(GL_TEXTURE_2D)
                        glDisable(GL_LIGHTING)
                        glDisable(GL_DEPTH_TEST)
                        glEnable(GL_LINE_SMOOTH)
                        glEnable(GL_BLEND)
                        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
                        glLineWidth(1.5f)
                        glColor(color)
                        TileEntityRendererDispatcher.instance.renderTileEntity(tileEntity, event.partialTicks, -1)
                        glColor(color)
                        TileEntityRendererDispatcher.instance.renderTileEntity(tileEntity, event.partialTicks, -1)
                        glPopAttrib()
                        glPopMatrix()
                    }
                }
            }

            for (entity in mc.theWorld.loadedEntityList) {
                // Distance check directly from coordinates (avoids allocating a BlockPos per entity)
                val distanceSquared = mc.thePlayer.getDistanceSq(entity.posX, entity.posY, entity.posZ)

                if (distanceSquared <= maxRenderDistanceSq) {
                    if (entity is EntityMinecartChest) {
                        if (onLook && !isLookingOnEntities(entity, maxAngleDifference.toDouble()))
                            continue

                        if (!thruBlocks && !isEntityHeightVisible(entity)) continue

                        when (mode) {
                            "OtherBox", "Box" -> drawEntityBox(entity, minecartChestColor, mode != "OtherBox")

                            "2d" -> draw2D(entity.position, minecartChestColor.rgb, Color.BLACK.rgb)
                            "Outline" -> {
                                val entityShadow = mc.gameSettings.entityShadows
                                mc.gameSettings.entityShadows = false
                                glColor(minecartChestColor)
                                OutlineUtils.renderOne(3f)
                                mc.renderManager.renderEntityStatic(entity, mc.timer.renderPartialTicks, true)
                                OutlineUtils.renderTwo()
                                mc.renderManager.renderEntityStatic(entity, mc.timer.renderPartialTicks, true)
                                OutlineUtils.renderThree()
                                mc.renderManager.renderEntityStatic(entity, mc.timer.renderPartialTicks, true)
                                OutlineUtils.renderFour(minecartChestColor)
                                mc.renderManager.renderEntityStatic(entity, mc.timer.renderPartialTicks, true)
                                OutlineUtils.renderFive()
                                OutlineUtils.setColor(Color.WHITE)
                                mc.gameSettings.entityShadows = entityShadow
                            }

                            "WireFrame" -> {
                                val entityShadow = mc.gameSettings.entityShadows
                                mc.gameSettings.entityShadows = false
                                glPushMatrix()
                                glPushAttrib(GL_ALL_ATTRIB_BITS)
                                glPolygonMode(GL_FRONT_AND_BACK, GL_LINE)
                                glDisable(GL_TEXTURE_2D)
                                glDisable(GL_LIGHTING)
                                glDisable(GL_DEPTH_TEST)
                                glEnable(GL_LINE_SMOOTH)
                                glEnable(GL_BLEND)
                                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
                                glColor(minecartChestColor)
                                mc.renderManager.renderEntityStatic(entity, mc.timer.renderPartialTicks, true)
                                glColor(minecartChestColor)
                                glLineWidth(1.5f)
                                mc.renderManager.renderEntityStatic(entity, mc.timer.renderPartialTicks, true)
                                glPopAttrib()
                                glPopMatrix()
                                mc.gameSettings.entityShadows = entityShadow
                            }
                        }
                    }
                }
            }

            glColor(Color(255, 255, 255, 255))
            mc.gameSettings.gammaSetting = gamma
        } catch (_: Exception) {
        }
    }

    // 优先级低于 0：glow 全屏合成 pass 先于 HUD 绘制，避免辉光盖在 HUD 界面上
    val onRender2D = handler<Render2DEvent>(priority = (Byte.MIN_VALUE + 1).toByte()) { event ->
        if (mc.theWorld == null || mode != "Glow")
            return@handler

        val renderManager = mc.renderManager

        try {
            mc.theWorld.loadedTileEntityList
                .groupBy { getColor(it) }
                .forEach { (color, tileEntities) ->
                    color ?: return@forEach

                    GlowShader.startDraw(event.partialTicks, glowRenderScale)

                    for (entity in tileEntities) {
                        val pos = entity.pos.toVec()
                        val distanceSquared = mc.thePlayer.getDistanceSq(pos.xCoord, pos.yCoord, pos.zCoord)

                        if (distanceSquared > maxRenderDistanceSq)
                            continue

                        if (onLook && !isLookingOnEntities(entity, maxAngleDifference.toDouble()))
                            continue

                        if (!thruBlocks && !isEntityHeightVisible(entity))
                            continue

                        val (x, y, z) = pos - renderManager.renderPos

                        TileEntityRendererDispatcher.instance.renderTileEntityAt(entity, x, y, z, event.partialTicks)
                    }

                    GlowShader.stopDraw(color, glowRadius, glowFade, glowTargetAlpha)
                }
        } catch (ex: Exception) {
            LOGGER.error("An error occurred while rendering all storages for shader esp", ex)
        }
    }
}
