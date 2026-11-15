package net.airplus.ui.client.hud.element.elements.targets2.impl

import net.airplus.config.BoolValue
import net.airplus.config.ColorValue
import net.airplus.config.FloatValue
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.elements.Target2
import net.airplus.ui.client.hud.element.elements.targets2.TargetStyle
import net.airplus.ui.font.Fonts
import net.airplus.utils.render.EmbeddedStencil
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.render.RenderUtils.deltaTime
import net.airplus.utils.render.animation.AnimationUtil
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.RenderHelper
import net.minecraft.entity.Entity
import net.minecraft.entity.player.EntityPlayer
import org.lwjgl.opengl.GL11
import java.awt.Color

/**
 * Opai（移植自 Target.kt renderOpaiHUD）：
 * EmbeddedStencil 裁剪的圆角面板 + 白色 35 透明度描边 + 圆角头像（受击缩放/红染动画）
 * + 三层血条（轨道/缓动条/实时条）+ 名称/血量文本 + 护甲物品栏。
 * blur 移交 Target2 的 handleBlur；阴影光晕保留为样式内逻辑（Opai-ShadowCheck）。
 */
class Opai(inst: Target2) : TargetStyle("Opai", inst, true) {

    private val opaiThemeColor = ColorValue("Opai-themeColor", Color(242, 172, 244)).apply { setSupport { supported() } }
    private val opaiShadowCheck = BoolValue("Opai-ShadowCheck", false).apply { setSupport { supported() } }
    private val opaiShadowStrengh = FloatValue("Opai-shadowStrengh", 0.5f, 0.0f..1.0f).apply { setSupport { supported() && opaiShadowCheck.get() } }

    private fun supported() = targetInstance.styleValueName.equals("opai", ignoreCase = true)

    /** 每个目标独立的 Opai 动画状态（头像受击动画 + 血条宽度缓动） */
    private class OpaiState {
        var animX = 135F
        var avatarScale = 1f
        var avatarTargetScale = 1f
        var avatarTintAlpha = 0f
        var isHitAnimating = false
        var hitAnimTimer = 0f
        var prevHealth = -1f
    }

    private val states = HashMap<EntityPlayer, OpaiState>()
    private val hitAnimDuration = 500f

    /** 原 Target.kt 的帧率自适应 lerp */
    private fun lerp(start: Float, end: Float, speed: Float): Float =
        start + (end - start) * speed * (deltaTime / (1000F / 60F))

    /** 面板几何参数（原 renderOpaiHUD 的局部变量） */
    private class OpaiDims(
        val panelWidth: Float,
        val panelHeight: Float,
        val resultProgressWidth: Float,
        val targetName: String,
        val targetNameWidth: Int,
        val targetHealth: Int,
        val textsDrawBegin: Float
    )

    private fun dims(entity: EntityPlayer): OpaiDims {
        val targetName = entity.name + "  "
        val targetNameWidth = Fonts.fontRegular35.getStringWidth(targetName)
        val targetHealth = entity.health.toInt()
        val targetHealthWidth = Fonts.fontRegular35.getStringWidth(targetHealth.toString())
        val textsDrawBegin = 3.5f + 30f + 3.5f
        val allTextLen = targetNameWidth + targetHealthWidth
        val resultProgressWidth = maxOf(135f, textsDrawBegin + allTextLen + 8f)
        val panelWidth = 3.5f * 2 + resultProgressWidth
        val panelHeight = 3.5f + 30f + 3.5f + 5f + 3.5f
        return OpaiDims(panelWidth, panelHeight, resultProgressWidth, targetName, targetNameWidth, targetHealth, textsDrawBegin)
    }

    /** 原 renderOpaiHUD 的阴影光晕圆环（EmbeddedStencil 裁剪外圈） */
    private fun drawOpaiShadowRings(theme: Color, panelWidth: Float, panelHeight: Float, currentRadius: Float) {
        val maxDist = (opaiShadowStrengh.get() * 13f).toInt()
        for (i in maxDist downTo 1) {
            val alpha = (100 * (1f - i.toFloat() / (maxDist + 1))).toInt().coerceIn(1, 100)
            RenderUtils.drawRoundedRect(
                -i.toFloat(), -i.toFloat(),
                panelWidth + i.toFloat(), panelHeight + i.toFloat(),
                Color(theme.red, theme.green, theme.blue, alpha).rgb,
                currentRadius + i.toFloat()
            )
        }
    }

    override fun drawTarget(entity: EntityPlayer) {
        val st = states.getOrPut(entity) { OpaiState() }
        states.keys.removeAll { it !== entity && (it.isDead || !it.isEntityAlive) }

        val d = dims(entity)
        val panelWidth = d.panelWidth
        val panelHeight = d.panelHeight
        val currentRadius = 5f
        val themeColor = opaiThemeColor.get()

        val progressBarLength = d.resultProgressWidth / entity.maxHealth * entity.health
        st.animX = AnimationUtil.base(st.animX.toDouble(), progressBarLength.toDouble(), 0.2).toFloat()

        try {
            EmbeddedStencil.checkSetupFBO(mc.framebuffer)
            EmbeddedStencil.write(false)
            RenderUtils.drawRoundedRect(0f, 0f, panelWidth, panelHeight, Color.WHITE.rgb, currentRadius)

            EmbeddedStencil.erase(false)
            if (opaiShadowCheck.get()) {
                drawOpaiShadowRings(themeColor, panelWidth, panelHeight, currentRadius)
            }

            EmbeddedStencil.erase(true)
            // 面板底色（原 Color(0,0,0,opaiBackGroundAlpha)）改用 Target2 的 Background 设置
            RenderUtils.drawRoundedBorderRect(
                0f, 0f, panelWidth, panelHeight, 0.2f,
                targetInstance.bgColor.rgb, targetInstance.bgColor.rgb, currentRadius
            )

            EmbeddedStencil.dispose()

            GL11.glDisable(GL11.GL_TEXTURE_2D)
            GL11.glEnable(GL11.GL_BLEND)
            GL11.glEnable(GL11.GL_LINE_SMOOTH)
            GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST)
            GL11.glLineWidth(1.5f)
            RenderUtils.drawRoundedBorderRect(
                -0.5f, -0.5f, panelWidth + 0.5f, panelHeight + 0.5f,
                0.5f, Color(0, 0, 0, 0).rgb, getColor(Color(255, 255, 255, 35)).rgb, currentRadius + 0.5f
            )
            GL11.glLineWidth(1.0f)
            GL11.glDisable(GL11.GL_LINE_SMOOTH)
            GL11.glEnable(GL11.GL_TEXTURE_2D)

        } catch (e: Exception) {
            if (opaiShadowCheck.get()) {
                drawOpaiShadowRings(themeColor, panelWidth, panelHeight, currentRadius)
            }
            RenderUtils.drawRoundedBorderRect(
                0f, 0f, panelWidth, panelHeight, 0.2f,
                targetInstance.bgColor.rgb, targetInstance.bgColor.rgb, currentRadius
            )
        }

        // 头像 + 受击动画
        val headTexture = mc.renderManager.getEntityRenderObject<Entity>(entity)
            ?.getEntityTexture(entity)
        if (headTexture != null) {
            val headX = 3.5f
            val headY = 3.5f
            val headSize = 30f
            val headRadius = 5f

            if (st.prevHealth < 0f) st.prevHealth = entity.health
            if (entity.health < st.prevHealth && !st.isHitAnimating) {
                st.isHitAnimating = true
                st.hitAnimTimer = 0f
                st.avatarTargetScale = 0.85f
            }
            if (st.isHitAnimating) {
                st.hitAnimTimer += deltaTime
                val progress = (st.hitAnimTimer / hitAnimDuration).coerceIn(0f, 1f)
                when {
                    progress < 0.4f -> {
                        st.avatarTargetScale = 0.85f
                        st.avatarTintAlpha = lerp(st.avatarTintAlpha, 1f, 0.25f)
                    }
                    progress < 0.75f -> {
                        st.avatarTargetScale = 1.12f
                        st.avatarTintAlpha = lerp(st.avatarTintAlpha, 0.6f, 0.25f)
                    }
                    else -> {
                        st.avatarTargetScale = 1f
                        st.avatarTintAlpha = lerp(st.avatarTintAlpha, 0f, 0.25f)
                    }
                }
                if (progress >= 1f) {
                    st.isHitAnimating = false
                    st.avatarTargetScale = 1f
                    st.avatarTintAlpha = 0f
                    st.hitAnimTimer = 0f
                }
            } else {
                st.avatarTargetScale = 1f
                st.avatarTintAlpha = lerp(st.avatarTintAlpha, 0f, 0.12f)
            }
            st.avatarScale = lerp(st.avatarScale, st.avatarTargetScale, 0.22f)
            st.prevHealth = entity.health

            GlStateManager.pushMatrix()
            GlStateManager.translate(headX + headSize / 2f, headY + headSize / 2f, 0f)
            GlStateManager.scale(st.avatarScale, st.avatarScale, 1f)
            GlStateManager.translate(-headX - headSize / 2f, -headY - headSize / 2f, 0f)

            EmbeddedStencil.checkSetupFBO(mc.framebuffer)
            EmbeddedStencil.write(false)
            RenderUtils.drawRoundedRect(headX, headY, headX + headSize, headY + headSize, Color.WHITE.rgb, headRadius)
            EmbeddedStencil.erase(true)
            RenderUtils.drawHead(
                headTexture, headX.toInt(), headY.toInt(),
                8f, 8f, 8, 8, headSize.toInt(), headSize.toInt(), 64f, 64f, Color.WHITE
            )
            EmbeddedStencil.dispose()

            if (st.avatarTintAlpha > 0.01f) {
                val tint = Color(255, 50, 50, (st.avatarTintAlpha * 140).toInt())
                RenderUtils.drawRoundedRect(headX, headY, headX + headSize, headY + headSize, tint.rgb, headRadius)
            }

            GlStateManager.popMatrix()
        }

        // 血条：轨道 + 缓动条 + 实时条
        RenderUtils.drawRoundedBorderRect(
            3.5f, 3.5f + 30f + 3.5f, d.resultProgressWidth, 3.5f + 30f + 3.5f + 5f,
            0.3f, getColor(Color(0, 0, 0, 200)).rgb, getColor(Color(0, 0, 0, 200)).rgb, 5f
        )
        RenderUtils.drawRoundedBorderRect(
            3.5f, 3.5f + 30f + 3.5f, st.animX, 3.5f + 30f + 3.5f + 5f,
            0.3f, getColor(Color(themeColor.red, themeColor.green, themeColor.blue, 150)).rgb,
            getColor(Color(themeColor.red, themeColor.green, themeColor.blue, 150)).rgb, 4f
        )
        RenderUtils.drawRoundedBorderRect(
            3.5f, 3.5f + 30f + 3.5f, progressBarLength, 3.5f + 30f + 3.5f + 5f,
            0.3f, getColor(themeColor).rgb, getColor(themeColor).rgb, 4f
        )

        // 文本
        Fonts.fontRegular35.drawString(d.targetName, d.textsDrawBegin + 3.5f, 3.5f * 2, getColor(Color.WHITE).rgb)
        Fonts.fontRegular35.drawString(
            d.targetHealth.toString(),
            d.textsDrawBegin + d.targetNameWidth + 3.5f,
            3.5f * 2 - 1F,
            getColor(themeColor).rgb
        )

        // 护甲物品栏
        if (entity is EntityPlayer) {
            GL11.glEnable(GL11.GL_TEXTURE_2D)
            GlStateManager.color(1F, 1F, 1F, 1F)
            RenderHelper.enableGUIStandardItemLighting()
            var armorX = d.textsDrawBegin
            for (index in 3 downTo 0) {
                val stack = entity.inventory.armorInventory[index] ?: continue
                mc.renderItem.renderItemIntoGUI(stack, armorX.toInt(), (3.5f + 30f - 18).toInt())
                mc.renderItem.renderItemOverlays(mc.fontRendererObj, stack, armorX.toInt(), (3.5f + 30f - 18).toInt())
                armorX += 18f
            }
            RenderHelper.disableStandardItemLighting()
        }
    }

    override fun handleBlur(entity: EntityPlayer) {
        val d = dims(entity)
        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        RenderUtils.drawRoundedRect(0f, 0f, d.panelWidth, d.panelHeight, Color.WHITE.rgb, 5f)
        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
    }

    override fun handleShadowCut(entity: EntityPlayer) = handleBlur(entity)

    override fun handleShadow(entity: EntityPlayer) {
        val d = dims(entity)
        RenderUtils.originalRoundedRect(0f, 0f, d.panelWidth, d.panelHeight, 5f, shadowOpaque.rgb)
    }

    override fun getBorder(entity: EntityPlayer?): Border {
        entity ?: return Border(0f, 0f, 150f, 50f)
        val d = dims(entity)
        return Border(0f, 0f, d.panelWidth, d.panelHeight)
    }

    override fun cleanup(alive: Set<EntityPlayer>) {
        states.keys.removeAll { it !in alive }
    }
}
