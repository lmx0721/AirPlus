package net.airplus.ui.client.hud.element.elements.targets2

import net.airplus.config.Value
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.elements.Target2
import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.render.ColorUtils
import net.airplus.utils.render.RenderUtils
import net.minecraft.client.gui.Gui
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.entity.player.EnumPlayerModelParts
import net.minecraft.item.ItemSword
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.GL11.*
import java.awt.Color
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.pow

abstract class TargetStyle(val name: String, val targetInstance: Target2, val shaderSupport: Boolean) : MinecraftInstance {

    var easingHealth = 0F
    val shieldIcon = ResourceLocation("liquidbounce+/shield.png")

    val decimalFormat = DecimalFormat("##0.00", DecimalFormatSymbols(Locale.ENGLISH))
    val decimalFormat2 = DecimalFormat("##0.0", DecimalFormatSymbols(Locale.ENGLISH))
    val decimalFormat3 = DecimalFormat("0.#", DecimalFormatSymbols(Locale.ENGLISH))

    val shadowOpaque: Color
        get() = ColorUtils.reAlpha(
            when (targetInstance.shadowColorMode.lowercase(Locale.getDefault())) {
                "background" -> targetInstance.bgColor
                "custom" -> Color(
                    targetInstance.shadowColorRedValue,
                    targetInstance.shadowColorGreenValue,
                    targetInstance.shadowColorBlueValue
                )
                else -> targetInstance.barColor
            }, 1F - targetInstance.animProgress
        )

    abstract fun drawTarget(entity: EntityPlayer)
    abstract fun getBorder(entity: EntityPlayer?): Border?

    /** 多目标动画时长（ms），样式可覆写以接入自己的 Anim Speed 设置 */
    open fun animDurationMs(): Float = targetInstance.multiAnimSpeed

    /** 多目标样式每帧回调：清理内部以目标为键的状态表（对齐 moonlight 各样式 cleanup） */
    open fun cleanup(alive: Set<EntityPlayer>) {}

    /** 当前卡片的渐变进度（0..1，1 为完全显示） */
    protected fun fadeProgress(): Float = (1F - targetInstance.getFadeProgress()).coerceIn(0F, 1F)

    protected fun clamp01(v: Float) = v.coerceIn(0F, 1F)

    /** ColorUtil.applyOpacity 语义：按系数缩放颜色透明度 */
    protected fun applyOpacity(color: Color, factor: Float): Color =
        Color(color.red, color.green, color.blue, (color.alpha * factor).toInt().coerceIn(0, 255))

    // ===== MoonDraw.roundedFace 移植：圆角柔边皮肤脸（带受伤红染）=====

    /**
     * 以三角扇 + 外圈渐隐三角带绘制玩家脸部（8,8 皮肤区域 + 可选帽子层），
     * [hurt] 0..1 控制红染强度，[alpha] 整体透明度，[feather] 边缘柔化宽度。
     */
    protected fun roundedFace(player: EntityPlayer, x: Float, y: Float, size: Float, radius: Float, hurt: Float, alpha: Float, feather: Float) {
        val info = mc.netHandler.getPlayerInfo(player.uniqueID) ?: return
        val tint = 1.0F - 0.45F * hurt.coerceIn(0F, 1F)
        GlStateManager.enableTexture2D()
        GlStateManager.enableBlend()
        GlStateManager.disableCull()
        GlStateManager.disableAlpha()
        OpenGlHelper.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO)
        GlStateManager.shadeModel(GL_SMOOTH)
        mc.textureManager.bindTexture(info.locationSkin)
        roundedLayer(x, y, size, radius, 8F, tint, alpha, feather)
        if (player.isWearing(EnumPlayerModelParts.HAT)) {
            roundedLayer(x, y, size, radius, 40F, tint, alpha, feather)
        }
        GlStateManager.shadeModel(GL_FLAT)
        GlStateManager.enableAlpha()
        GlStateManager.enableCull()
        glColor4f(1.0F, 1.0F, 1.0F, 1.0F)
        GlStateManager.resetColor()
    }

    private fun roundedLayer(x: Float, y: Float, size: Float, radius: Float, texU: Float, tint: Float, alpha: Float, feather: Float) {
        val r = max(0.5F, min(radius, size / 2F))
        val halfFeather = feather / 2F
        val steps = ceil(r * 1.5F).toInt().coerceIn(4, 12)
        val pointCount = steps * 4 + 4
        val px = FloatArray(pointCount)
        val py = FloatArray(pointCount)
        val nx = FloatArray(pointCount)
        val ny = FloatArray(pointCount)
        val corners = arrayOf(
            floatArrayOf(x + r, y + r, 180F),
            floatArrayOf(x + size - r, y + r, 270F),
            floatArrayOf(x + size - r, y + size - r, 0F),
            floatArrayOf(x + r, y + size - r, 90F)
        )
        var idx = 0
        for (corner in corners) {
            for (i in 0..steps) {
                val ang = Math.toRadians((corner[2] + 90.0 * i / steps))
                val cosV = cos(ang).toFloat()
                val sinV = sin(ang).toFloat()
                nx[idx] = cosV
                ny[idx] = sinV
                if (r - halfFeather < 0F) {
                    px[idx] = corner[0] - Math.signum(cosV) * halfFeather
                    py[idx] = corner[1] - Math.signum(sinV) * halfFeather
                } else {
                    px[idx] = corner[0] + cosV * (r - halfFeather)
                    py[idx] = corner[1] + sinV * (r - halfFeather)
                }
                idx++
            }
        }

        val uBase = texU / 64F
        val vBase = 0.125F
        val uSize = 0.125F

        glBegin(GL_TRIANGLE_FAN)
        glColor4f(1.0F, tint, tint, alpha)
        glTexCoord2f(uBase + uSize * 0.5F, vBase + uSize * 0.5F)
        glVertex2f(x + size / 2.0F, y + size / 2.0F)
        for (i in 0..pointCount) {
            val j = i % pointCount
            roundedUv(px[j], py[j], x, y, size, uBase, vBase, uSize)
            glVertex2f(px[j], py[j])
        }
        glEnd()

        glBegin(GL_TRIANGLE_STRIP)
        for (i in 0..pointCount) {
            val j = i % pointCount
            roundedUv(px[j], py[j], x, y, size, uBase, vBase, uSize)
            glColor4f(1.0F, tint, tint, alpha)
            glVertex2f(px[j], py[j])
            val ox = px[j] + nx[j] * feather
            val oy = py[j] + ny[j] * feather
            roundedUv(ox, oy, x, y, size, uBase, vBase, uSize)
            glColor4f(1.0F, tint, tint, 0.0F)
            glVertex2f(ox, oy)
        }
        glEnd()
    }

    private fun roundedUv(vx: Float, vy: Float, x: Float, y: Float, size: Float, uBase: Float, vBase: Float, uSize: Float) {
        val u = (vx - x) / size
        val v = (vy - y) / size
        glTexCoord2f(uBase + uSize * u.coerceIn(0F, 1F), vBase + uSize * v.coerceIn(0F, 1F))
    }

    // ===== 简化版格挡率统计（moonlight TargetBlockRate 的轻量内联实现）=====
    // 目标受击瞬间若正持剑格挡则计入一次格挡，环形窗口 20 次。

    private class BlockRateState {
        val ring = BooleanArray(20)
        var head = 0
        var count = 0
        var blocked = 0
        var lastHurtTime = 0
    }

    private val blockRateStates = HashMap<Int, BlockRateState>()

    protected fun updateBlockRate(entity: EntityPlayer) {
        val st = blockRateStates.getOrPut(entity.entityId) { BlockRateState() }
        if (entity.hurtTime > st.lastHurtTime) {
            val blocking = entity.heldItem?.item is ItemSword && entity.isUsingItem
            if (st.count == 20) {
                if (st.ring[st.head]) st.blocked--
            } else {
                st.count++
            }
            st.ring[st.head] = blocking
            if (blocking) st.blocked++
            st.head = (st.head + 1) % 20
        }
        st.lastHurtTime = entity.hurtTime
    }

    /** -1F 表示暂无样本 */
    protected fun blockRate(entity: EntityPlayer): Float {
        val st = blockRateStates[entity.entityId] ?: return -1F
        return if (st.count == 0) -1F else st.blocked.toFloat() / st.count
    }

    protected fun blockRateText(entity: EntityPlayer): String {
        val rate = blockRate(entity)
        return if (rate < 0F) "--" else Math.round(rate * 100F).toString() + "%"
    }

    /** 格挡率文本颜色：无样本白色、低格挡率偏绿、高格挡率偏红 */
    protected fun blockColor(entity: EntityPlayer): Color {
        val rate = blockRate(entity)
        return if (rate < 0F) Color(255, 255, 255, 120) else ColorUtils.interpolateColor(Color(52, 211, 120), Color(255, 99, 99), rate)
    }

    protected fun cleanupBlockRate(alive: Set<EntityPlayer>) {
        val ids = alive.mapTo(HashSet()) { it.entityId }
        blockRateStates.keys.removeAll { it !in ids }
    }

    open fun updateAnim(targetHealth: Float) {
        if (targetInstance.noAnimValue)
            easingHealth = targetHealth
        else
            easingHealth += ((targetHealth - easingHealth) / 2.0F.pow(10.0F - targetInstance.globalAnimSpeed)) * RenderUtils.deltaTime
    }

    open fun handleDamage(player: EntityPlayer) {}

    open fun handleBlur(player: EntityPlayer) {}

    open fun handleShadowCut(player: EntityPlayer) {}

    open fun handleShadow(player: EntityPlayer) {}

    open val values: List<Value<*>>
        get() = javaClass.declaredFields.mapNotNull { valueField ->
            try {
                valueField.isAccessible = true
                valueField[this] as? Value<*>
            } catch (e: Exception) {
                null
            }
        }

    fun getColor(color: Color) = ColorUtils.reAlpha(color, color.alpha / 255F * (1F - targetInstance.getFadeProgress()))
    fun getColor(color: Int) = getColor(Color(color))

    fun drawHead(skin: ResourceLocation, x: Int = 2, y: Int = 2, width: Int, height: Int, alpha: Float = 1F) {
        glDisable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glDepthMask(false)
        OpenGlHelper.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO)
        glColor4f(1.0F, 1.0F, 1.0F, alpha)
        mc.textureManager.bindTexture(skin)
        Gui.drawScaledCustomSizeModalRect(x, y, 8F, 8F, 8, 8, width, height, 64F, 64F)
        glDepthMask(true)
        glDisable(GL_BLEND)
        glEnable(GL_DEPTH_TEST)
    }

    fun drawHead(
        skin: ResourceLocation,
        x: Float,
        y: Float,
        scale: Float,
        width: Int,
        height: Int,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float = 1F
    ) {
        glPushMatrix()
        glTranslatef(x, y, 0F)
        glScalef(scale, scale, scale)
        glDisable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glDepthMask(false)
        OpenGlHelper.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO)
        glColor4f(red.coerceIn(0F, 1F), green.coerceIn(0F, 1F), blue.coerceIn(0F, 1F), alpha.coerceIn(0F, 1F))
        mc.textureManager.bindTexture(skin)
        Gui.drawScaledCustomSizeModalRect(0, 0, 8F, 8F, 8, 8, width, height, 64F, 64F)
        glDepthMask(true)
        glDisable(GL_BLEND)
        glEnable(GL_DEPTH_TEST)
        glPopMatrix()
        glColor4f(1f, 1f, 1f, 1f)
    }

    fun drawArmorIcon(x: Int, y: Int, width: Int, height: Int) {
        GlStateManager.disableAlpha()
        RenderUtils.drawImage(shieldIcon, x, y, width, height)
        GlStateManager.enableAlpha()
    }
}
