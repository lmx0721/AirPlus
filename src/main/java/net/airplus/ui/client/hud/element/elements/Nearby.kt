/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.ui.client.hud.element.elements

import net.airplus.ui.client.hud.designer.GuiHudDesigner
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.Element
import net.airplus.ui.client.hud.element.ElementInfo
import net.airplus.ui.client.hud.element.Side
import net.airplus.ui.font.AWTFontRenderer.Companion.assumeNonVolatile
import net.airplus.ui.font.Fonts
import net.airplus.ui.font.GameFontRenderer
import net.airplus.utils.render.ColorSettingsInteger
import net.airplus.utils.render.ColorUtils
import net.airplus.utils.render.HudBlur
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.minecraft.client.entity.AbstractClientPlayer
import net.minecraft.client.gui.Gui
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting
import net.minecraft.client.renderer.RenderHelper.enableGUIStandardItemLighting
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.init.Items
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.GL11.GL_ONE
import org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA
import org.lwjgl.opengl.GL11.GL_SRC_ALPHA
import org.lwjgl.opengl.GL11.glPopMatrix
import org.lwjgl.opengl.GL11.glPushMatrix
import org.lwjgl.opengl.GL11.glScalef
import org.lwjgl.opengl.GL11.glTranslatef
import java.awt.Color
import kotlin.math.max

/**
 * 附近玩家面板 HUD 元素
 *
 * 展示周围玩家的头像、名字（带 tablist 颜色）、血条、装备与手持物品。
 * 布局：头像左侧留出宽裕空间，右侧紧凑；没戴盔甲的行不占盔甲位，手持物品始终保留位置。
 * 出现/消失采用与 Combo 同款的 bounce 弹簧动画。
 */
@ElementInfo(name = "Nearby")
class Nearby(
    x: Double = 5.0, y: Double = 40.0, scale: Float = 1F, side: Side = Side.default(),
) : Element("Nearby", x, y, scale, side) {

    private val range by int("Range", 32, 8..128)
    private val maxRows by int("MaxRows", 5, 1..10)
    private val showEquipment by boolean("ShowEquipment", true)
    private val showHeldItem by boolean("ShowHeldItem", true)

    private val enableBounce by boolean("Bounce", true)
    private val animTension by float("BounceTension", 0.01f, 0.01f..1.0f) { enableBounce }
    private val animFriction by float("BounceFriction", 0.12f, 0.01f..1.0f) { enableBounce }

    private val bgBlur by boolean("Background-Blur", true)
    private val bgBlurStrength by float("Background-Blur-Strength", 8F, 1F..30F) { bgBlur }
    private val bgBlurMode by choices("Background-Blur-Mode", HudBlur.MODES, "InternalBlur") { bgBlur }

    private val bgRadius by float("Round-Radius", 6F, 0F..12F)
    private val bgColors = ColorSettingsInteger(this, "BackgroundColor").with(Color(20, 22, 28, 150))
    private val barBackColors = ColorSettingsInteger(this, "BarBackground").with(Color(255, 255, 255, 45))
    private val barColors = ColorSettingsInteger(this, "BarColor").with(Color(90, 200, 110))

    private val font by font("Font", Fonts.fontRegular35)

    private class RowAnim {
        var alpha = 0F
        var velAlpha = 0F
        var hpFrac = -1F // 血条平滑填充（-1 = 首次出现直接到位）
        var last: Row? = null
    }

    private val rowAnims = linkedMapOf<String, RowAnim>()

    // 整体 bounce 弹簧状态
    private var panelAlpha = 1F
    private var panelScale = 1F
    private var velAlpha = 0F
    private var velScale = 0F
    private var lastVisible = true

    private data class Row(
        val name: String,
        val coloredName: String,
        val hp: Float,
        val maxHp: Float,
        val armor: List<ItemStack>,
        val held: ItemStack?,
        val skin: ResourceLocation?,
    )

    private fun spring(current: Float, target: Float, velocity: Float): Pair<Float, Float> {
        val displacement = target - current
        val force = displacement * animTension
        val drag = velocity * animFriction
        val acceleration = force - drag
        val newVelocity = velocity + acceleration
        return (current + newVelocity) to newVelocity
    }

    override fun drawElement(): Border {
        val rows = collectRows()
        val present = rows.map { it.name }.toSet()

        // 行入场/离场动画（弹簧淡入 + 滑入，血条平滑跟随真实血量）
        rowAnims.forEach { (name, anim) ->
            val target = if (name in present) 1F else 0F
            val (next, vel) = spring(anim.alpha, target, anim.velAlpha)
            anim.alpha = next.coerceIn(0F, 1F)
            anim.velAlpha = vel
            if (name in present) {
                anim.last = rows.firstOrNull { it.name == name } ?: anim.last
                val row = anim.last
                if (row != null) {
                    val frac = (row.hp / row.maxHp.coerceAtLeast(1F)).coerceIn(0F, 1F)
                    if (anim.hpFrac < 0F) anim.hpFrac = frac
                    anim.hpFrac += (frac - anim.hpFrac) * 0.18F
                }
            }
        }
        rowAnims.entries.removeAll { it.value.alpha < 0.03F && it.key !in present }
        rows.forEach { rowAnims.getOrPut(it.name) { RowAnim() } }

        val drawnAnims = rowAnims.values.filter { it.alpha >= 0.03F && it.last != null }
        val visible = drawnAnims.isNotEmpty()

        // 整体 bounce 弹簧（出现/消失）
        if (enableBounce) {
            if (visible && !lastVisible) {
                panelAlpha = 0F
                panelScale = 0F
                velAlpha = 0F
                velScale = 0F
            }

            val targetValue = if (visible) 1F else 0F

            val (nextAlpha, vA) = spring(panelAlpha, targetValue, velAlpha)
            panelAlpha = nextAlpha.coerceIn(0F, 1F)
            velAlpha = vA

            val (nextScale, vS) = spring(panelScale, targetValue, velScale)
            panelScale = nextScale.coerceAtLeast(0F)
            velScale = vS

            lastVisible = visible

            if (!visible && (panelAlpha < 0.01F || panelScale < 0.01F))
                return Border(0F, 0F, 0F, 0F)
        } else {
            panelAlpha = 1F
            panelScale = 1F
            lastVisible = visible
            if (!visible)
                return Border(0F, 0F, 0F, 0F)
        }

        val fontRenderer = font
        val fontHeight = ((fontRenderer as? GameFontRenderer)?.height ?: fontRenderer.FONT_HEIGHT).toFloat()
        val faceSize = 10F
        val itemSize = 14F
        val leftPad = 16F // 头像左侧留出宽裕空间
        val rightPad = 3F // 右侧空间收紧
        val padY = 5F
        val rowGap = 3F
        val rowH = maxOf(faceSize, itemSize, fontHeight) + 4F
        val barW = 55F
        val barH = 5F
        val panelA = if (enableBounce) panelAlpha else 1F

        val maxNameW = drawnAnims
            .maxOfOrNull { fontRenderer.getStringWidth(it.last!!.coloredName).toFloat() }
            ?.coerceAtMost(90F) ?: 60F

        // 每行宽度：盔甲只按实际件数占位，手持物品始终保留位置
        fun rowWidth(row: Row): Float {
            var w = leftPad + faceSize + 5F + maxNameW + 6F + barW
            if (showEquipment && row.armor.isNotEmpty())
                w += 5F + row.armor.size * (itemSize + 2F) - 2F
            if (showHeldItem)
                w += 5F + itemSize
            return w + rightPad
        }

        var contentW = drawnAnims.maxOfOrNull { rowWidth(it.last!!) } ?: 110F
        contentW = max(contentW, 110F)

        val totalH = padY * 2 + drawnAnims.size * (rowH + rowGap) - rowGap

        val panelPivotX = contentW / 2F
        val panelPivotY = totalH / 2F
        val panelS = if (enableBounce) panelScale else 1F

        assumeNonVolatile {
            glPushMatrix()
            glTranslatef(panelPivotX, panelPivotY, 0F)
            glScalef(panelS, panelS, 1F)
            glTranslatef(-panelPivotX, -panelPivotY, 0F)

            // 背景 blur（绝对屏幕坐标 = scale * (render + local)，随 bounce 缩放）
            if (bgBlur) {
                val s = scale
                val ox = renderX.toFloat()
                val oy = renderY.toFloat()
                val lx1 = panelPivotX + (0F - panelPivotX) * panelS
                val ly1 = panelPivotY + (0F - panelPivotY) * panelS
                val lx2 = panelPivotX + (contentW - panelPivotX) * panelS
                val ly2 = panelPivotY + (totalH - panelPivotY) * panelS
                HudBlur.blur(
                    (s * (lx1 + ox)).toFloat(), (s * (ly1 + oy)).toFloat(),
                    (s * (lx2 + ox)).toFloat(), (s * (ly2 + oy)).toFloat(),
                    bgBlurStrength, bgBlurMode
                ) {
                    drawRoundedRect(0F, 0F, contentW, totalH, -1, bgRadius)
                }
            }

            val bg = bgColors.color()
            drawRoundedRect(
                0F, 0F, contentW, totalH,
                Color(bg.red, bg.green, bg.blue, (bg.alpha * panelA).toInt()).rgb, bgRadius
            )

            var y = padY
            for (anim in drawnAnims) {
                val row = anim.last!!
                val a = (anim.alpha * panelA).coerceIn(0F, 1F)
                val a255 = (a * 255).toInt()
                val rx = (1F - anim.alpha) * 8F // 滑入偏移

                // 头像（左侧留白 + 行内垂直居中）
                val faceX = rx + leftPad
                val faceY = y + (rowH - faceSize) / 2F
                val skin = row.skin
                if (skin != null) {
                    GlStateManager.enableBlend()
                    GlStateManager.tryBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, 0)
                    GlStateManager.color(1f, 1f, 1f, a)
                    mc.textureManager.bindTexture(skin)
                    Gui.drawScaledCustomSizeModalRect(
                        faceX.toInt(), faceY.toInt(), 8f, 8f, 8, 8, faceSize.toInt(), faceSize.toInt(), 64f, 64f
                    )
                    GlStateManager.color(1f, 1f, 1f, 1f)
                    GlStateManager.disableBlend()
                } else {
                    val pc = barColors.color()
                    drawRoundedRect(faceX, faceY, faceX + faceSize, faceY + faceSize, Color(pc.red, pc.green, pc.blue, (a255 * 0.7f).toInt()).rgb, 3F)
                }

                // 名字（带 tablist 颜色）
                val textY = y + (rowH - fontHeight) / 2F
                fontRenderer.drawString(row.coloredName, faceX + faceSize + 5F, textY, Color(255, 255, 255, a255).rgb, true)

                // 血条（血量低时渐变为红色，填充平滑动画）
                var bx = faceX + faceSize + 5F + maxNameW + 6F
                val frac = anim.hpFrac.coerceIn(0F, 1F)
                val barY = y + rowH / 2F - barH / 2F
                val back = barBackColors.color()
                drawRoundedRect(
                    bx, barY, bx + barW, barY + barH,
                    Color(back.red, back.green, back.blue, (back.alpha * a).toInt()).rgb, 2F
                )
                if (frac > 0F) {
                    val bc = barColors.color()
                    val hpColor = ColorUtils.interpolateColor(Color(230, 90, 90), bc, frac)
                    drawRoundedRect(
                        bx, barY, bx + barW * frac, barY + barH,
                        Color(hpColor.red, hpColor.green, hpColor.blue, a255).rgb, 2F
                    )
                }
                bx += barW

                // 装备图标（头盔→胸甲→护腿→靴子），没有盔甲的行不占位
                if (showEquipment && row.armor.isNotEmpty()) {
                    bx += 5F
                    GlStateManager.color(1f, 1f, 1f, a)
                    for (stack in row.armor) {
                        renderItemIcon(stack, bx.toInt(), (y + (rowH - itemSize) / 2F).toInt())
                        bx += itemSize + 2F
                    }
                }

                // 手持物品（始终保留位置）
                if (showHeldItem) {
                    bx += 5F
                    val held = row.held
                    if (held != null && held.item != null) {
                        GlStateManager.color(1f, 1f, 1f, a)
                        renderItemIcon(held, bx.toInt(), (y + (rowH - itemSize) / 2F).toInt())
                    }
                }

                y += rowH + rowGap
            }

            glPopMatrix()
        }

        return Border(0F, 0F, contentW, totalH)
    }

    private fun renderItemIcon(stack: ItemStack, x: Int, y: Int) {
        glPushMatrix()
        enableGUIStandardItemLighting()
        try {
            mc.renderItem.renderItemAndEffectIntoGUI(stack, x, y)
            mc.renderItem.renderItemOverlays(mc.fontRendererObj, stack, x, y)
        } catch (_: Exception) {
        }
        disableStandardItemLighting()
        // 物品渲染会污染 GL 状态（光照/深度/混合），不还原会导致其他元素的 blur 变灰
        GlStateManager.disableLighting()
        GlStateManager.disableDepth()
        GlStateManager.disableBlend()
        GlStateManager.enableAlpha()
        GlStateManager.color(1f, 1f, 1f, 1f)
        glPopMatrix()
    }

    private fun collectRows(): List<Row> {
        val self = mc.thePlayer
        val world = mc.theWorld
        val editing = mc.currentScreen is GuiHudDesigner

        if (self == null || world == null)
            return if (editing) sampleRows() else emptyList()

        // 附近玩家：按距离排序，取最近 maxRows 个
        val nearby = world.playerEntities
            .filterIsInstance<EntityPlayer>()
            .filter { it !== self && it.getDistanceSqToEntity(self) < range.toDouble() * range.toDouble() }
            .sortedBy { it.getDistanceSqToEntity(self) }
            .take(maxRows)

        if (nearby.isEmpty() && editing)
            return sampleRows()

        return nearby.map { p ->
            Row(
                name = p.gameProfile.name,
                coloredName = p.displayName?.formattedText ?: p.gameProfile.name,
                hp = p.health,
                maxHp = p.maxHealth,
                armor = if (showEquipment) (0..3).mapNotNull { p.getCurrentArmor(it) } else emptyList(),
                held = if (showHeldItem) p.heldItem else null,
                skin = (p as? AbstractClientPlayer)?.locationSkin,
            )
        }
    }

    private fun sampleRows() = listOf(
        Row(
            "Steve", "§aSteve", 17F, 20F,
            listOf(
                ItemStack(Items.diamond_helmet), ItemStack(Items.diamond_chestplate),
                ItemStack(Items.diamond_leggings), ItemStack(Items.diamond_boots)
            ),
            ItemStack(Items.diamond_sword), null
        ),
        Row("Alex", "§aAlex", 8F, 20F, emptyList(), ItemStack(Items.bow), null),
        Row("Notch", "§aNotch", 4F, 20F, emptyList(), null, null),
    )
}
