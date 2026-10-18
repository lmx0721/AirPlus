// skid SetsunaClient
package net.airplus.features.module.modules.render

import net.airplus.event.Render2DEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.ui.font.Fonts
import net.airplus.utils.render.RenderUtils
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.RenderHelper
import net.minecraft.enchantment.Enchantment
import net.minecraft.init.Items
import net.minecraft.item.ItemAxe
import net.minecraft.item.ItemBow
import net.minecraft.item.ItemPickaxe
import net.minecraft.item.ItemSword
import net.minecraft.item.ItemStack
import net.minecraftforge.client.GuiIngameForge
import org.lwjgl.opengl.GL11
import java.awt.Color
import java.util.StringJoiner
import kotlin.math.max
import kotlin.math.min

/** Delta Force-inspired player HUD with a native, borderless hotbar. */
object DeltaForceStyle : Module("DeltaForce Style", Category.RENDER) {

    private val slotCount by boolean("Slot Count", false)

    private val AVATAR = 32.0f
    private val BAR_WIDTH = 92.0f
    private val BAR_HEIGHT = 5.0f
    private val HOTBAR_ITEM = 16.0f
    private val HOTBAR_GAP = 2.0f
    private val LEFT = 8.0f
    private val BOTTOM = 8.0f
    private val HUD_ENTRY_OFFSET = 112.0f
    private const val TRANSITION_DURATION_NANOS = 550_000_000L
    private const val ABSORPTION_COLOR: Int = 0xFFFFD45A.toInt()

    /** 护甲图标：缩小后贴在血条上方、靠血条右缘，大小与左侧血量数字相当。 */
    private const val ARMOR_ICON = 9.0f
    private const val ARMOR_PITCH = 11.0f

    private var displayedHealth = 1.0f
    private var displayedAbsorption = 0.0f
    private var displayedXp = 0.0f
    private var transitionStartedNanos = 0L

    /** 是否已通过 MixinGuiInGame 关闭了原版血量/护甲/饥饿等绘制（退出时恢复）。 */
    @Volatile
    private var vanillaSuppressed = false

    /** MixinGuiInGame 在 DeltaForce 激活时调用：记录原版 HUD 已被抑制。 */
    fun markVanillaSuppressed() {
        vanillaSuppressed = true
    }

    /** 模块关闭后恢复原版血量/护甲/饥饿/氧气/经验绘制（这些静态 flag 每帧不会自动复位）。 */
    fun restoreVanillaFlags() {
        if (!vanillaSuppressed) return
        vanillaSuppressed = false
        try {
            GuiIngameForge.renderHealth = true
            GuiIngameForge.renderFood = true
            GuiIngameForge.renderArmor = true
            GuiIngameForge.renderAir = true
            GuiIngameForge.renderExperiance = true
            GuiIngameForge.renderHealthMount = true
        } catch (ignored: Throwable) {
        }
    }

    override fun onEnable() {
        transitionStartedNanos = System.nanoTime()
    }

    override fun onDisable() {
        restoreVanillaFlags()
    }

    val onRender2D = handler<Render2DEvent> {
        val player = mc.thePlayer ?: return@handler
        if (mc.theWorld == null) return@handler

        val scaledResolution = ScaledResolution(mc)
        val width = scaledResolution.scaledWidth.toFloat()
        val height = scaledResolution.scaledHeight.toFloat()
        val partialTicks = it.partialTicks

        val maximum = max(1.0f, player.maxHealth)
        val health = (player.health / maximum).coerceIn(0.0f, 1.0f)
        val absorption = max(0.0f, player.absorptionAmount / maximum)
        val xp = player.experience.coerceIn(0.0f, 1.0f)
        val frame = min(0.12f, max(0.016f, partialTicks / 20.0f))
        displayedHealth = smooth(displayedHealth, health, frame)
        displayedAbsorption = smooth(displayedAbsorption, absorption, frame)
        displayedXp = smooth(displayedXp, xp, frame)

        val transitionOffset = HUD_ENTRY_OFFSET * (1.0f - transitionProgress())

        val x = LEFT
        val y = height - BOTTOM - AVATAR + transitionOffset

        drawAvatar(x, y)

        val infoX = x + AVATAR + 7.0f
        val barY = y + 13.0f

        // 经验条（底部中央白条）：进度 = 当前等级经验进度，条上方居中显示等级
        val xpBarWidth = min(160.0f, max(80.0f, width * 0.16f))
        val xpBarX = (width - xpBarWidth) * 0.5f
        val xpBarY = height - 7.0f + transitionOffset
        RenderUtils.drawRect(xpBarX, xpBarY, xpBarX + xpBarWidth, xpBarY + 3.0f, 0x5521262D)
        if (displayedXp > 0.0f) {
            RenderUtils.drawRect(xpBarX, xpBarY, xpBarX + xpBarWidth * displayedXp, xpBarY + 3.0f, Color.WHITE.rgb)
        }
        if (player.experienceLevel > 0) {
            val levelText = player.experienceLevel.toString()
            val levelFont = Fonts.fontSemibold35
            levelFont.drawCenteredString(levelText, width / 2f, xpBarY - levelFont.FONT_HEIGHT - 2.0f, 0xFFFFFFFF.toInt())
        }

        // 血量数字紧贴血条正上方，名字移到血条下方
        val healthText = Math.round((displayedHealth + displayedAbsorption) * 100.0f).toString() + "/100"
        val healthFont = Fonts.fontSemibold40
        healthFont.drawString(healthText, infoX, barY - healthFont.FONT_HEIGHT - 2.0f, Color(0xFFF5F7FA.toInt(), true).rgb)
        RenderUtils.drawRect(infoX, barY, infoX + BAR_WIDTH, barY + BAR_HEIGHT, 0x6621262D)
        if (displayedHealth > 0.0f) {
            RenderUtils.drawRect(
                infoX, barY,
                infoX + BAR_WIDTH * displayedHealth.coerceIn(0.0f, 1.0f),
                barY + BAR_HEIGHT, Color.WHITE.rgb
            )
        }
        val absorptionWidth = displayedAbsorption.coerceIn(0.0f, 1.0f)
        val absorptionEnd = (displayedHealth + displayedAbsorption).coerceIn(0.0f, 1.0f)
        val absorptionStart = max(0.0f, absorptionEnd - absorptionWidth)
        if (absorptionEnd > absorptionStart) {
            RenderUtils.drawRect(
                infoX + BAR_WIDTH * absorptionStart, barY,
                infoX + BAR_WIDTH * absorptionEnd, barY + BAR_HEIGHT, ABSORPTION_COLOR
            )
        }
        Fonts.fontRegular35.drawString(player.name, infoX, barY + BAR_HEIGHT + 5.0f, 0xCFFFFFFF.toInt())

        // 护甲图标：缩小后贴在血条上方、右缘与血条右端对齐；
        // 左→右顺序为头盔/护甲/护腿/鞋子，未穿戴的中间栏位自动补齐（顺序不变）
        val worn = ArrayList<ItemStack?>(4)
        val armorInv = player.inventory.armorInventory
        if (armorInv[3] != null) worn.add(armorInv[3]) // 头盔
        if (armorInv[2] != null) worn.add(armorInv[2]) // 护甲
        if (armorInv[1] != null) worn.add(armorInv[1]) // 护腿
        if (armorInv[0] != null) worn.add(armorInv[0]) // 鞋子
        if (worn.isNotEmpty()) {
            val armorScale = ARMOR_ICON / 16.0f
            val armorEndX = infoX + BAR_WIDTH
            val armorStartX = armorEndX - ARMOR_ICON - (worn.size - 1) * ARMOR_PITCH
            val armorY = barY - ARMOR_ICON - 4.0f
            GlStateManager.pushMatrix()
            GlStateManager.enableRescaleNormal()
            GlStateManager.enableBlend()
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO)
            RenderHelper.enableGUIStandardItemLighting()
            for ((index, stack) in worn.withIndex()) {
                if (stack == null) continue
                GlStateManager.pushMatrix()
                GlStateManager.translate(
                    (armorStartX + index * ARMOR_PITCH).toDouble(), armorY.toDouble(), 0.0
                )
                GlStateManager.scale(armorScale.toDouble(), armorScale.toDouble(), 1.0)
                mc.renderItem.renderItemAndEffectIntoGUI(stack, 0, 0)
                mc.renderItem.renderItemOverlays(mc.fontRendererObj, stack, 0, 0)
                GlStateManager.popMatrix()
            }
            RenderHelper.disableStandardItemLighting()
            GlStateManager.disableRescaleNormal()
            GlStateManager.disableBlend()
            GlStateManager.popMatrix()
        }

        val hotbarX = LEFT + AVATAR + 7.0f + BAR_WIDTH + 12.0f
        val hotbarY = height - BOTTOM - 20.0f + transitionOffset

        // Borderless hotbar items
        GlStateManager.pushMatrix()
        GlStateManager.enableRescaleNormal()
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO)
        RenderHelper.enableGUIStandardItemLighting()

        for (slot in 0..8) {
            val stack = player.inventory.getStackInSlot(slot) ?: continue
            val itemX = hotbarX + slot * (HOTBAR_ITEM + HOTBAR_GAP)
            mc.renderItem.renderItemAndEffectIntoGUI(stack, Math.round(itemX), Math.round(hotbarY))
            mc.renderItem.renderItemOverlays(mc.fontRendererObj, stack, Math.round(itemX), Math.round(hotbarY))
        }

        RenderHelper.disableStandardItemLighting()
        GlStateManager.disableRescaleNormal()
        GlStateManager.disableBlend()
        GlStateManager.popMatrix()

        if (slotCount) {
            for (slot in 0..8) {
                val countX = hotbarX + slot * (HOTBAR_ITEM + HOTBAR_GAP) - 2.0f
                Fonts.fontRegular30.drawStringWithShadow(
                    Integer.toString(slot + 1), countX, hotbarY + 11.0f, 0xDFFFFFFF.toInt()
                )
            }
        }

        // 右下角：手持物品 4 倍放大展示 + 名称/附魔摘要（参考 SetsunaClient，但不限工具、所有物品都显示）
        // 仅剑/斧/弓/镐/箭旋转展示（45° 基础角再旋转 180° = 225°），其余物品不旋转
        val held = player.heldItem
        if (held != null) {
            val labelFont = Fonts.fontRegular35
            val label = heldItemLabel(held)
            val labelWidth = labelFont.getStringWidth(label).toFloat()

            // 64px（16px 物品 × 4 倍），旋转物品中心与 SetsunaClient 一致
            val itemCenterX = width - 48.0f
            val itemCenterY = height - 64.0f + transitionOffset
            val rotates = held.item is ItemSword || held.item is ItemAxe || held.item is ItemBow
                    || held.item is ItemPickaxe || held.item === Items.arrow

            GlStateManager.pushMatrix()
            GlStateManager.enableRescaleNormal()
            GlStateManager.enableBlend()
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO)
            RenderHelper.enableGUIStandardItemLighting()
            GlStateManager.translate(itemCenterX.toDouble(), itemCenterY.toDouble(), 0.0)
            if (rotates) GlStateManager.rotate(225.0f, 0.0f, 0.0f, 1.0f)
            GlStateManager.scale(4.0, 4.0, 1.0)
            mc.renderItem.renderItemAndEffectIntoGUI(held, -8, -8)
            RenderHelper.disableStandardItemLighting()
            GlStateManager.disableRescaleNormal()
            GlStateManager.disableBlend()
            GlStateManager.popMatrix()

            val labelY = height - 26.0f + transitionOffset
            labelFont.drawString(
                label,
                min(itemCenterX - 36.0f, width - 12.0f - labelWidth),
                labelY,
                Color(0xDFFFFFFF.toInt(), true).rgb
            )
        }
    }

    /** 物品名称 + 附魔摘要（最多 3 条，超出部分显示 +N）。 */
    private fun heldItemLabel(stack: ItemStack): String {
        val enchantments = enchantmentSummary(stack)
        val itemName = stack.displayName ?: ""
        return if (enchantments.isEmpty()) itemName else "$itemName  $enchantments"
    }

    private fun enchantmentSummary(stack: ItemStack): String {
        val tagList = stack.enchantmentTagList ?: return ""
        if (tagList.tagCount() == 0) return ""
        val joiner = StringJoiner(" / ")
        var shown = 0
        for (i in 0 until tagList.tagCount()) {
            val enchTag = tagList.getCompoundTagAt(i)
            val enchantment = Enchantment.getEnchantmentById(enchTag.getShort("id").toInt()) ?: continue
            joiner.add(enchantment.getTranslatedName(enchTag.getShort("lvl").toInt()))
            if (++shown >= 3) break
        }
        val total = tagList.tagCount()
        return if (total > shown) joiner.toString() + " +" + (total - shown) else joiner.toString()
    }

    private fun drawAvatar(x: Float, y: Float) {
        RenderUtils.withClipping(
            main = {
                // Rounded background doubles as the stencil shape and the fallback look
                RenderUtils.drawRoundedRect(x, y, x + AVATAR, y + AVATAR, Color(0x27, 0x31, 0x3D).rgb, 8.0f)
            },
            toClip = {
                GlStateManager.enableBlend()
                GlStateManager.tryBlendFuncSeparate(
                    GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO
                )
                GlStateManager.enableTexture2D()
                GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f)

                val drawX = x.toInt()
                val drawY = y.toInt()
                GlStateManager.pushMatrix()
                GlStateManager.translate((x - drawX).toDouble(), (y - drawY).toDouble(), 0.0)
                // Face layer (UV 8,8 8x8) and hat layer (UV 40,8 8x8) of the player skin scaled to 32x32
                RenderUtils.drawHead(null, drawX, drawY, 8.0f, 8.0f, 8, 8, 32, 32, 64.0f, 64.0f, Color.WHITE)
                RenderUtils.drawHead(null, drawX, drawY, 40.0f, 8.0f, 8, 8, 32, 32, 64.0f, 64.0f, Color.WHITE)
                GlStateManager.popMatrix()

                GlStateManager.disableBlend()
            }
        )
    }

    private fun smooth(current: Float, target: Float, frame: Float): Float {
        val factor = 1.0f - Math.pow(0.001, frame.toDouble()).toFloat()
        return current + (target - current) * factor
    }

    /** Cubic ease-out: most of the movement happens early and settles softly. */
    fun transitionProgress(): Float {
        if (!state) return 0.0f
        if (transitionStartedNanos == 0L) return 1.0f
        val linear = ((System.nanoTime() - transitionStartedNanos)
                / TRANSITION_DURATION_NANOS.toFloat()).coerceIn(0.0f, 1.0f)
        val remaining = 1.0f - linear
        return 1.0f - remaining * remaining * remaining
    }
}
