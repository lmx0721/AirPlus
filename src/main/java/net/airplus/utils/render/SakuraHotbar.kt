/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.render

import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.render.RenderUtils.drawRoundedBorder
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.airplus.utils.render.RenderUtils.drawRoundedRectAA
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.entity.player.EntityPlayer
import org.lwjgl.opengl.GL11
import java.awt.Color

/**
 * Sakura 主题热栏：整条物品栏被一条可自定义圆角的樱花粉 3px 边框包裹（AA 着色器逐像素
 * coverage，圆角边缘平滑），每个槽位以细樱花粉圆角边框包住物品；被选槽位的边框变为
 * 高不透明度樱花粉，并叠加一圈可自定义颜色的柔和 glow（多层外扩圆角矩形，alpha 递减）。
 *
 * 切换槽位时选中边框与 glow 以 Rise 0.03/ms 插值平滑滑向新槽位（过渡动画）。
 *
 * 布局（ScaledResolution 单位，比 LB/Rise 的 182x22 更紧凑）：
 * - 外框  : 158x22（内区 152x16 + 3px 边框），底边贴屏幕底，圆角 = Sakura-Radius
 * - 槽位  : 9 x 16x16 连续排列（间隙 1px），每格 1px 粉色圆角细边框
 * - 选中格: 边框加宽至 2px 且不透明度拉满 + 四层 glow 外扩（最远 11px）
 * - 物品  : 16px 原始尺寸 + 0.8 中心缩放（在 16px 格内留呼吸位），保留原版拿起 squash 动画
 *
 * 渲染阶段与 RiseHotbar 相同：
 * - BLUR → [HudBlur.blur]（开关控制，遮罩 = 外框圆角矩形，与同帧 HUD 组件共享模糊快照）
 * - REGULAR → 外框 ring + 槽位边框 + glow + 物品（走 [HotbarItems] 公共物品绘制）
 */
object SakuraHotbar : MinecraftInstance {

    /** 设置由 HUD 模块桥接（对齐 RiseHotbar.Settings 的模式），渲染器不直接依赖模块实现。 */
    interface Settings {
        /** 是否对外框区域做背景模糊（HudBlur，受 Client 全局 Blur 模式/强度约束） */
        fun blur(): Boolean

        /** 外框圆角半径（0..10；AA 着色器逐像素 coverage，圆角平滑） */
        fun radius(): Float

        /** 樱花粉边框颜色（ARGB） */
        fun color(): Int

        /** 选中格 glow 颜色（ARGB） */
        fun glowColor(): Int
    }

    // ---- 布局常量 ----
    private const val BORDER = 3F                 // 外框厚度
    private const val OUTER_WIDTH = 158F          // 152 + 2 * 3
    private const val OUTER_HEIGHT = 22F          // 16 + 2 * 3
    private const val CELL = 16F
    private const val CELL_GAP = 1F
    private const val SLOTS = 9
    private const val SLOT_RADIUS = 2.5F
    private const val ITEM_SCALE = 0.8F

    // 3px 外框 ring：外层填粉色、内层填深色底（内层挖空即得圆角边框，AA 着色器保证边缘平滑）
    private const val INNER_BG_ALPHA = 90
    private const val INNER_BG = INNER_BG_ALPHA shl 24

    // 普通槽位细边框：粉色 + 降低不透明度
    private const val SLOT_BORDER_ALPHA = 130

    // 选中格 glow：外扩距离 -> alpha（四层叠加成更大更柔和的光晕）
    private val glowLayers = arrayOf(2F to 80, 5F to 48, 8F to 26, 11F to 12)

    // ---- 平滑状态（选中边框 + glow 的滑动过渡动画）----
    private var lastFrame = 0L
    private var selX = -1F               // 选中边框盒左缘 x；-1 = 尚未初始化，首帧直接吸附

    @JvmStatic
    fun render(resolution: ScaledResolution, player: EntityPlayer, partialTicks: Float, settings: Settings) {
        val sw = resolution.scaledWidth
        val sh = resolution.scaledHeight

        val radius = settings.radius().coerceIn(0F, 10F)

        val ox1 = sw / 2F - OUTER_WIDTH / 2F
        val ox2 = ox1 + OUTER_WIDTH
        val oy2 = sh - 1F
        val oy1 = oy2 - OUTER_HEIGHT
        val ix1 = ox1 + BORDER
        val iy1 = oy1 + BORDER
        val ix2 = ox2 - BORDER
        val iy2 = oy2 - BORDER

        // 9 格连续排列（间隙 1px）：9*16 + 8*1 = 152 恰好铺满内区
        val pitch = CELL + CELL_GAP
        fun cellX(j: Int) = ix1 + j * pitch
        val cellY1 = iy1
        val cellY2 = iy2

        val slot = player.inventory.currentItem.coerceIn(0, 8)
        val pink = settings.color()
        val glow = settings.glowColor()

        // ---- 帧推进（选中框横向滑动，Rise selectorX 同款 0.03F/ms 插值）----
        val now = System.currentTimeMillis()
        val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(0L, 100L)
        lastFrame = now

        // 选中边框盒（17x17：18px 格内缩 0.5，线宽 1 时线落在格缘）
        val selTarget = cellX(slot) + 0.5F
        selX = if (selX < 0F) selTarget
        else lerp(selX, selTarget, (0.03F * dt).coerceIn(0F, 1F))
        val selBoxSize = CELL - 1F

        // ---- BLUR（外框区域，开关控制；与其它 HUD 组件共享同帧模糊快照）----
        if (settings.blur()) {
            HudBlur.blur(ox1, oy1, ox2, oy2, radius) {
                drawRoundedRect(ox1, oy1, ox2, oy2, -1, radius)
            }
        }

        // ---- REGULAR ----
        GL11.glPushMatrix()
        GL11.glDisable(GL11.GL_DEPTH_TEST)

        // 外框 3px ring：外层粉色填充 + 内层深色底挖空（内层半径 = 外层半径 - 边框厚度，
        // 保持内外圆角同心；AA 着色器逐像素 coverage，圆角边缘平滑）
        drawRoundedRectAA(ox1, oy1, ox2, oy2, pink, radius)
        drawRoundedRectAA(ix1, iy1, ix2, iy2, INNER_BG, maxOf(radius - BORDER, 0F))

        // 选中格 glow（四层外扩圆角矩形，alpha 递减，跟随过渡动画滑动）
        for ((expand, alpha) in glowLayers) {
            val color = (alpha shl 24) or (glow and 0xFFFFFF)
            drawRoundedRectAA(
                selX - expand, cellY1 + 0.5F - expand,
                selX + selBoxSize + expand, cellY2 - 0.5F + expand,
                color, SLOT_RADIUS + expand
            )
        }
        // glow 是外扩填充矩形，会渗进选中格内部 —— 用内区底色恢复 16x16 物品区，
        // 让光晕只保留在边框外圈
        drawRoundedRectAA(
            selX + 0.5F, cellY1 + 0.5F, selX + selBoxSize - 0.5F, cellY2 - 0.5F,
            INNER_BG, maxOf(SLOT_RADIUS - 1F, 0F)
        )

        // 每个槽位的 1px 樱花粉圆角细边框
        for (j in 0 until SLOTS) {
            val bx = cellX(j) + 0.5F
            drawRoundedBorder(bx, cellY1 + 0.5F, bx + selBoxSize, cellY2 - 0.5F, 1F, (SLOT_BORDER_ALPHA shl 24) or (pink and 0xFFFFFF), SLOT_RADIUS)
        }

        // 选中格边框：加宽至 2px、不透明度拉满，跟随过渡动画滑动
        drawRoundedBorder(selX, cellY1 + 0.5F, selX + selBoxSize, cellY2 - 0.5F, 2F, pink, SLOT_RADIUS)

        GL11.glEnable(GL11.GL_DEPTH_TEST)
        GL11.glPopMatrix()

        // ---- 物品（0.8 中心缩放 + squash 动画 + 原版数量/耐久 overlay）----
        HotbarItems.render(player, partialTicks, true, ITEM_SCALE) { j ->
            Pair((cellX(j) + CELL / 2F - 8F).toInt(), (cellY1 + CELL / 2F - 8F).toInt())
        }
    }

    private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t
}
