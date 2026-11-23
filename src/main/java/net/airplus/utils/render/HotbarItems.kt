/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.render

import net.airplus.utils.client.MinecraftInstance
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.RenderHelper
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.ItemStack

/**
 * 自定义热栏样式共用的物品绘制工具（与 RiseHotbar 的物品阶段一致的 GL 状态序列）：
 * - [render]  : 逐槽绘制物品（拿起 squash 动画），可选原版数量/耐久 overlay
 * - [renderCount] : 自绘数量角标（走自绘耐久线的样式用它替代原版 overlay）
 * - [durabilityRatio] : 可修复物品的耐久剩余比例（0..1），不可修复返回 null
 *
 * 名称浮现（切槽提示）由各样式自行实现：只需要 [SlotChangePopup] 的 alpha 时间线。
 */
object HotbarItems : MinecraftInstance {

    /**
     * 逐槽绘制物品。[positions] 返回槽位 j 的物品左上角 (x, y)（ScaledResolution 单位）。
     * [withOverlays] = false 时不调 renderItemOverlays（样式自绘数量/耐久时使用）。
     * [scale] = 物品缩放（以物品中心缩放，数量/耐久 overlay 一同缩放）；紧凑型样式用 <1 缩放留出呼吸位。
     */
    fun render(
        player: EntityPlayer, partialTicks: Float, withOverlays: Boolean,
        scale: Float = 1F,
        positions: (Int) -> Pair<Int, Int>
    ) {
        GlStateManager.enableRescaleNormal()
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        RenderHelper.enableGUIStandardItemLighting()
        GlStateManager.enableDepth()
        GlStateManager.depthMask(true)

        val renderer = mc.renderItem
        for (j in 0..8) {
            val stack = player.inventory.mainInventory[j] ?: continue
            val (ix, iy) = positions(j)
            GlStateManager.pushMatrix()
            if (scale != 1F) {
                GlStateManager.translate(ix + 8F, iy + 8F, 0F)
                GlStateManager.scale(scale, scale, 1F)
                GlStateManager.translate(-(ix + 8F), -(iy + 8F), 0F)
            }
            val squash = stack.animationsToGo - partialTicks
            if (squash > 0F) {
                GlStateManager.pushMatrix()
                val f1 = 1F + squash / 5F
                GlStateManager.translate(ix + 8F, iy + 12F, 0F)
                GlStateManager.scale(1F / f1, (f1 + 1F) / 2F, 1F)
                GlStateManager.translate(-(ix + 8F), -(iy + 12F), 0F)
            }
            renderer.renderItemAndEffectIntoGUI(stack, ix, iy)
            if (squash > 0F) GlStateManager.popMatrix()
            if (withOverlays) {
                renderer.renderItemOverlays(mc.fontRendererObj, stack, ix, iy)
            }
            GlStateManager.popMatrix()
        }

        RenderHelper.disableStandardItemLighting()
        GlStateManager.disableRescaleNormal()
        GlStateManager.disableBlend()
    }

    /** 自绘数量角标（右下角，白字带阴影，与原版位置一致） */
    fun renderCount(stack: ItemStack, x: Int, y: Int) {
        if (stack.stackSize <= 1) return
        val text = stack.stackSize.toString()
        mc.fontRendererObj.drawStringWithShadow(
            text, (x + 19 - mc.fontRendererObj.getStringWidth(text)).toFloat(), (y + 9).toFloat(), -1
        )
    }

    /** 耐久剩余比例（0..1）；不可修复/无耐久损耗的物品返回 null */
    fun durabilityRatio(stack: ItemStack): Float? {
        if (!stack.isItemStackDamageable || stack.maxDamage <= 0) return null
        return ((stack.maxDamage - stack.itemDamage) / stack.maxDamage.toFloat()).coerceIn(0F, 1F)
    }
}

/**
 * 切槽提示时间线：槽位变化瞬间 alpha 置 1，[durationMs] 内线性淡出。
 * 各自定义热栏的「物品名称浮现」共用。
 */
class SlotChangePopup(private val durationMs: Long = 1600L) {

    private var lastSlot = -1
    private var changeTime = 0L

    /** 当前槽位的提示 alpha（0..1）；槽位未变化且已过期时为 0 */
    fun alpha(currentSlot: Int): Float {
        val now = System.currentTimeMillis()
        if (currentSlot != lastSlot) {
            lastSlot = currentSlot
            changeTime = now
        }
        val t = (now - changeTime) / durationMs.toFloat()
        return if (t >= 1F) 0F else 1F - t
    }
}
