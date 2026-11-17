/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.render

import net.airplus.event.Render2DEvent
import net.airplus.event.Render3DEvent
import net.airplus.event.WorldEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.ui.font.Fonts
import net.airplus.utils.block.block
import net.airplus.utils.extensions.*
import net.airplus.utils.render.OnyxBlockAnimator
import net.airplus.utils.render.ONYX_LINEAR_EASE
import net.airplus.utils.render.RenderUtils.drawBorderedRect
import net.airplus.utils.render.Tween
import net.airplus.utils.render.RenderUtils.drawFilledBox
import net.airplus.utils.render.RenderUtils.drawSelectionBoundingBox
import net.airplus.utils.render.RenderUtils.glColor
import net.minecraft.block.Block
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager.resetColor
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.init.Blocks
import net.minecraft.util.AxisAlignedBB
import net.minecraft.util.BlockPos
import net.minecraft.util.Vec3
import org.lwjgl.opengl.GL11.*
import java.awt.Color
import kotlin.math.min

object BlockOverlay : Module("BlockOverlay", Category.RENDER, gameDetecting = false) {
    private val mode by choices("Mode", arrayOf("Box", "OtherBox", "Outline"), "Box")
    private val depth3D by boolean("Depth3D", false)
    private val thickness by float("Thickness", 2F, 1F..5F)

    val info by boolean("Info", false)

    private val color by color("Color", Color(68, 117, 255, 100))

    // ---- Onyx 动画（迁移自 Onyx BlockOverlayModule）----
    private val animation by boolean("Animation", true)

    // 出现方式：Fade 纯透明；Pop 透明 + 从 62% 缩放弹入；Grow 从中心 0 长到 100%
    private val appearance by choices("Appearance", arrayOf("Fade", "Pop", "Grow"), "Fade") { animation }
    private val appearTime by float("Appear", 140F, 0F..600F, "ms") { animation }
    private val fadeTime by float("Fade", 180F, 0F..600F, "ms") { animation }
    // 滑向相邻方块时长
    private val moveTime by float("Move", 110F, 0F..600F, "ms") { animation }

    // 挖掘进度填充（Onyx 的 "Grow from centre" 进度模式未迁移，只做整面填充）
    private val breakProgress by boolean("BreakProgress", true)
    private val progressDirection by choices("Direction", arrayOf("Top to bottom", "Bottom to top"), "Top to bottom") { breakProgress }
    private val progressOpacity by float("ProgressOpacity", 45F, 1F..100F, "%") { breakProgress }
    // 进度填充渐变第二色：按 y 在 color 与 SecondColor 之间渐变（简化自 Onyx 的 glint 渐变 shader）
    private val secondColor by color("SecondColor", -7055617) { breakProgress }

    // 盒子外扩（占一个方块的百分比）
    private val padding by float("Padding", 0.6F, 0F..5F, "%")
    private val throughWalls by boolean("ThroughWalls", false)

    val currentBlock: BlockPos?
        get() {
            val world = mc.theWorld ?: return null
            val blockPos = mc.objectMouseOver?.blockPos ?: return null

            if (blockPos.block !in arrayOf(
                    Blocks.air,
                    Blocks.water,
                    Blocks.lava
                ) && world.worldBorder.contains(blockPos)
            )
                return blockPos

            return null
        }

    // ---- Onyx 同款缓动（Util.IFACE2 / IFACE7 / IFACE）：Move 用 (0.2,0,0,1)，出现用 (0.05,0.7,0.1,1)，淡出用 (0.3,0,0.8,0.15) ----
    // 出现/滑动/淡出缓动已移入共享动画器 OnyxBlockAnimator

    // 共享 Onyx 动画状态机（世界坐标，对应 Onyx 的 anim.Cls 补间器）
    private val animator = OnyxBlockAnimator()

    // 挖掘进度（朝当前 damage 平滑）
    private val progressTween = Tween(0F, ONYX_LINEAR_EASE)
    // 挖掘进度对应的注视目标（换目标时进度归零）
    private var lastProgressTarget: BlockPos? = null

    private val tessellator = Tessellator.getInstance()
    private val worldRenderer = tessellator.worldRenderer

    // 共享动画器的设置（每次渲染从模块设置构建）
    private val animatorSettings
        get() = OnyxBlockAnimator.Settings(appearance, appearTime, fadeTime, moveTime, padding)

    val onRender3D = handler<Render3DEvent> {
        val targetPos = currentBlock

        if (!animation) {
            // 关闭动画：保持原有即时渲染行为，并每帧重置动画状态（便于重新开启时从头出现）
            resetAnimationState()
            val blockPos = targetPos ?: return@handler
            val block = blockPos.block ?: return@handler
            val thePlayer = mc.thePlayer ?: return@handler

            block.setBlockBoundsBasedOnState(mc.theWorld, blockPos)
            val pos = thePlayer.interpolatedPosition(thePlayer.lastTickPos)
            val axisAlignedBB = block.getSelectedBoundingBox(mc.theWorld, blockPos).expand(0.002, 0.002, 0.002)

            render(axisAlignedBB, pos, 1F, 0F)
            return@handler
        }

        // ---- Onyx 动画路径 ----
        val targetAABB = targetPos?.let { getSelectedAABB(it) }
        val frame = animator.update(targetPos, targetAABB, animatorSettings)
        if (frame == null) return@handler

        // 挖掘进度朝当前 damage 平滑（Onyx 用 70ms 线性）；换目标/离开目标时归零
        if (targetPos != lastProgressTarget) progressTween.snap(0F)
        lastProgressTarget = targetPos
        if (targetPos != null) progressTween.tweenTo(curBlockDamage(), 70L)
        progressTween.update()

        val thePlayer = mc.thePlayer ?: return@handler
        val pos = thePlayer.interpolatedPosition(thePlayer.lastTickPos)

        render(frame.box, pos, frame.visibility, progressTween.value)
    }

    private fun getSelectedAABB(blockPos: BlockPos): AxisAlignedBB? {
        val world = mc.theWorld ?: return null
        val block = blockPos.block ?: return null
        block.setBlockBoundsBasedOnState(world, blockPos)
        return block.getSelectedBoundingBox(world, blockPos)
    }

    // 当前挖掘进度（只在挖方块时非 0）
    private fun curBlockDamage(): Float {
        val controller = mc.playerController ?: return 0F
        return if (controller.isHittingBlock) controller.curBlockDamageMP.coerceIn(0F, 1F) else 0F
    }

    private fun render(box: AxisAlignedBB, offset: Vec3, vis: Float, progress: Float) {
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glEnable(GL_LINE_SMOOTH)
        glHint(GL_LINE_SMOOTH_HINT, GL_NICEST)
        val outlineColor = Color(color.red, color.green, color.blue, (color.alpha * vis).toInt().coerceIn(0, 255))
        glColor(outlineColor)
        glLineWidth(thickness)
        glDisable(GL_TEXTURE_2D)
        if (depth3D || throughWalls) glDisable(GL_DEPTH_TEST)
        glDepthMask(false)

        val rel = box.offset(-offset)

        if (mode.lowercase() in arrayOf("box", "otherbox"))
            drawFilledBox(rel)

        // 挖掘进度填充
        if (breakProgress && progress > 0.001F)
            drawProgressFill(rel, vis, progress)

        if (mode.lowercase() in arrayOf("box", "outline")) {
            // 渐变填充会覆盖当前颜色，画描边前重设
            glColor(outlineColor)
            drawSelectionBoundingBox(rel)
        }

        if (depth3D || throughWalls) glEnable(GL_DEPTH_TEST)
        glEnable(GL_TEXTURE_2D)
        glDisable(GL_BLEND)
        glDisable(GL_LINE_SMOOTH)
        glDepthMask(true)
        resetColor()
    }

    /**
     * 挖掘进度填充：随 mc.playerController.curBlockDamage 在盒内画填充面，
     * Top to bottom 从顶往下 / Bottom to top 从底往上（Onyx 的 "Grow from centre" 进度模式不做）。
     * 颜色按 y 在 color（底）与 SecondColor（顶）之间渐变（简化自 Onyx 的 glint 渐变 shader），
     * 填充 alpha = 颜色 alpha × 出现/消失进度 × ProgressOpacity。
     */
    private fun drawProgressFill(box: AxisAlignedBB, vis: Float, progress: Float) {
        val alpha = (color.alpha * vis * (progressOpacity / 100F)).toInt().coerceIn(0, 255)
        if (alpha <= 0) return

        val height = (box.maxY - box.minY) * progress
        val raw = if (progressDirection == "Bottom to top")
            AxisAlignedBB(box.minX, box.minY, box.minZ, box.maxX, box.minY + height, box.maxZ)
        else
            AxisAlignedBB(box.minX, box.maxY - height, box.minZ, box.maxX, box.maxY, box.maxZ)
        // 每个面稍微内缩，避免与外框 z-fighting（Onyx 的 0.0015 间隙）
        val gx = min(0.0015, (raw.maxX - raw.minX) / 2.0)
        val gy = min(0.0015, (raw.maxY - raw.minY) / 2.0)
        val gz = min(0.0015, (raw.maxZ - raw.minZ) / 2.0)
        val fill = AxisAlignedBB(
            raw.minX + gx, raw.minY + gy, raw.minZ + gz,
            raw.maxX - gx, raw.maxY - gy, raw.maxZ - gz
        )

        // 按 y 相对整个插值盒取渐变色（进度增长时渐变保持稳定）
        val heightInv = 1.0 / maxOf(1.0E-6, box.maxY - box.minY)
        fun vertexColor(y: Double): Int {
            val t = ((y - box.minY) * heightInv).coerceIn(0.0, 1.0)
            return Color(
                (color.red + (secondColor.red - color.red) * t).toInt().coerceIn(0, 255),
                (color.green + (secondColor.green - color.green) * t).toInt().coerceIn(0, 255),
                (color.blue + (secondColor.blue - color.blue) * t).toInt().coerceIn(0, 255)
            ).rgb
        }
        fun vertex(x: Double, y: Double, z: Double) {
            val c = vertexColor(y)
            worldRenderer.pos(x, y, z).color((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, alpha).endVertex()
        }

        worldRenderer.begin(7, DefaultVertexFormats.POSITION_COLOR)
        // 顶点顺序与 RenderUtils.drawFilledBox 一致（部分面正反各画一次，兼容背面剔除）
        vertex(fill.minX, fill.minY, fill.minZ)
        vertex(fill.minX, fill.maxY, fill.minZ)
        vertex(fill.maxX, fill.minY, fill.minZ)
        vertex(fill.maxX, fill.maxY, fill.minZ)
        vertex(fill.maxX, fill.minY, fill.maxZ)
        vertex(fill.maxX, fill.maxY, fill.maxZ)
        vertex(fill.minX, fill.minY, fill.maxZ)
        vertex(fill.minX, fill.maxY, fill.maxZ)
        vertex(fill.maxX, fill.maxY, fill.minZ)
        vertex(fill.maxX, fill.minY, fill.minZ)
        vertex(fill.minX, fill.maxY, fill.minZ)
        vertex(fill.minX, fill.minY, fill.minZ)
        vertex(fill.minX, fill.maxY, fill.maxZ)
        vertex(fill.minX, fill.minY, fill.maxZ)
        vertex(fill.maxX, fill.maxY, fill.maxZ)
        vertex(fill.maxX, fill.minY, fill.maxZ)
        vertex(fill.minX, fill.maxY, fill.minZ)
        vertex(fill.maxX, fill.maxY, fill.minZ)
        vertex(fill.maxX, fill.maxY, fill.maxZ)
        vertex(fill.minX, fill.maxY, fill.maxZ)
        vertex(fill.minX, fill.maxY, fill.minZ)
        vertex(fill.minX, fill.maxY, fill.maxZ)
        vertex(fill.maxX, fill.maxY, fill.maxZ)
        vertex(fill.maxX, fill.maxY, fill.minZ)
        vertex(fill.minX, fill.minY, fill.minZ)
        vertex(fill.maxX, fill.minY, fill.minZ)
        vertex(fill.maxX, fill.minY, fill.maxZ)
        vertex(fill.minX, fill.minY, fill.maxZ)
        vertex(fill.minX, fill.minY, fill.minZ)
        vertex(fill.minX, fill.minY, fill.maxZ)
        vertex(fill.maxX, fill.minY, fill.maxZ)
        vertex(fill.maxX, fill.minY, fill.minZ)
        tessellator.draw()
    }

    private fun resetAnimationState() {
        animator.reset()
        progressTween.snap(0F)
        lastProgressTarget = null
    }

    val onRender2D = handler<Render2DEvent> {
        if (!info) return@handler

        val blockPos = currentBlock ?: return@handler
        val block = blockPos.block ?: return@handler

        val info = "${block.localizedName} §7ID: ${Block.getIdFromBlock(block)}"
        val (width, height) = ScaledResolution(mc)

        drawBorderedRect(
            width / 2 - 2F,
            height / 2 + 5F,
            width / 2 + Fonts.fontSemibold40.getStringWidth(info) + 2F,
            height / 2 + 16F,
            3F, Color.BLACK.rgb, Color.BLACK.rgb
        )

        resetColor()
        Fonts.fontSemibold40.drawString(info, width / 2f, height / 2f + 7f, Color.WHITE.rgb, false)
    }

    override fun onDisable() {
        resetAnimationState()
    }

    val onWorld = handler<WorldEvent> {
        resetAnimationState()
    }
}
