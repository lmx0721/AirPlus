/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.render

import net.airplus.event.Render2DEvent
import net.airplus.event.Render3DEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.modules.misc.AntiBot.isBot
import net.airplus.file.FileManager.friendsConfig
import net.airplus.ui.font.Fonts
import net.airplus.ui.font.GameFontRenderer
import net.airplus.ui.font.drawCenteredString
import net.airplus.utils.render.ColorUtils.stripColor
import net.airplus.utils.render.ESPUtil
import net.airplus.utils.render.HudBlur
import net.airplus.utils.render.HudSlices
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.RenderHelper
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.entity.Entity
import net.minecraft.entity.item.EntityArmorStand
import net.minecraft.entity.item.EntityItem
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.ItemStack
import org.lwjgl.opengl.GL11.*
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 从 Samsara NameTags（新版 MC + NanoVG）移植的胶囊式名牌（1.8.9 遗留 GL 版）。
 *
 * 功能对照：
 * - Players / Items / FloatingText：实体过滤（玩家 / 掉落物 / 带可见自定义名的盔甲架，
 *   Samsara 的 Display.TextDisplay 在 1.8.9 无对应物，以具名盔甲架代替）
 * - PlayerArmor：名牌上方展示装备图标（Samsara 槽序 主手→头→胸→腿→脚→副手，1.8.9 无副手）
 * - ArmorDifference：自身与目标护甲差值胶囊（"=" / "+n" / "-n"，橙/绿/红）
 * - FloatingText：悬浮文本多行居中堆叠
 * - Shader：Samsara 预烘焙 9-slice 贴图（shadow.png 外扩阴影 + capsule.png 深胶囊）+ 背景模糊
 * - Scale：整体缩放
 *
 * 渲染流程与 Samsara 一致：收集 → 投影 → 屏幕边缘淡出 → 按距离降序（远的先画）→ 绘制。
 * 投影复用 ESPUtil 的 GLU 投影：Render3D 阶段收集（世界矩阵有效），Render2D 阶段绘制
 * （与 NameTags2 / ESPUtil 的既有模式一致）。
 */
object NameTagOpai : Module("NameTagOpai", Category.RENDER) {

    // ---------------- 设置（Samsara NameTags 全部开关映射） ----------------
    private val players by boolean("Players", true)
    private val items by boolean("Items", true)
    private val playerArmor by boolean("PlayerArmor", true) { players }
    private val armorDifference by boolean("ArmorDifference", true) { players }
    private val floatingText by boolean("FloatingText", true)
    private val shader by boolean("Shader", true)
    private val scale by float("Scale", 0.8F, 0.1F..2F)
    private val font by font("Font", Fonts.fontRegular40)

    // ---------------- Samsara Geometry 常量 ----------------
    private const val RADIUS = 12F            // Geometry.RADIUS
    private const val PADDING = 7F            // Geometry.PADDING
    private const val FLOATING_PADDING = 10F  // Geometry.FLOATING_PADDING
    private const val GAP = 2F                // Geometry.GAP
    private const val HEART_WIDTH = 13.5F     // Geometry.HEART_WIDTH
    private const val HEART_HEIGHT = 13F      // Geometry.HEART_HEIGHT
    private const val HEART_SEGMENTS = 24
    private const val EQUIPMENT_PITCH = 32F   // 装备槽间距（Samsara i * 32）
    private const val EQUIPMENT_ITEM_SCALE = 1.75F // Samsara item scale
    private const val EQUIPMENT_TOP = 36F     // 装备/记分板行距胶囊顶部的距离
    private const val SHADOW_OFFSET = 8F      // NanoVG shadow 的半透明外扩近似
    private const val MIN_OPACITY = 4f / 255f

    // ---------------- Samsara 颜色 ----------------
    private val GREEN = 0xFF88D922.toInt()    // Geometry.GREEN（血量充足 / 护甲优势）
    private val YELLOW = 0xFFFFFF55.toInt()   // Geometry.YELLOW（血量中等）
    private val RED = 0xFFFF3333.toInt()      // Geometry.RED（血量危险 / 护甲劣势）
    private val EQUAL = 0xFFFFAA00.toInt()    // Geometry.EQUAL（护甲持平）
    private val DISTANCE = 0xFF888888.toInt() // Geometry.DISTANCE
    private val COUNT = 0xFFFFCC00.toInt()    // Geometry.COUNT（物品数量）
    private val WHITE = 0xFFFFFFFF.toInt()
    private val SHADOW_COLOR = 0x80000000.toInt()      // Samsara shadow mask 颜色
    private val CAPSULE_SHADER_COLOR = 0x98000000.toInt() // Samsara shader 胶囊色
    private val CAPSULE_PLAIN_COLOR = 0x80000000.toInt()  // Samsara 无 shader 胶囊色

    // ---------------- 数据结构（对应 Samsara Part / Capsule / Layout / View / Projected） ----------------
    private class Projected(
        val entity: Entity,
        val x: Float,
        val y: Float,
        val distanceSquared: Double,
        val opacity: Float,
        val view: View
    )

    private class Part(val text: String, val color: Int, val heart: Boolean)

    private class Capsule(val part: Part, val x: Float, val width: Float)

    private class Layout(val capsules: List<Capsule>, val width: Float)

    private class View(
        val parts: List<Part>,
        val equipment: List<ItemStack>,
        val score: String?,
        val floating: Boolean
    )

    /** 选中字体（GameFontRenderer 已按原版布局折半）；回退原版字体时用 FONT_HEIGHT。 */
    private val tagFontHeight: Int
        get() = (font as? GameFontRenderer)?.fontHeight ?: font.FONT_HEIGHT

    /** Render3D 收集 → Render2D 绘制的投影缓存（每帧重建）。 */
    private val projected = ArrayList<Projected>()

    /** 当前正在绘制的名牌（供 paintCapsule 计算背景模糊的屏幕 AABB）。 */
    private var currentTag: Projected? = null

    // ==================================================================
    // 收集 + 投影 + 排序（Samsara onEvent / Projected）
    // ==================================================================
    val onRender3D = handler<Render3DEvent> {
        projected.clear()

        val world = mc.theWorld ?: return@handler
        val self = mc.thePlayer ?: return@handler
        val sr = ScaledResolution(mc)

        for (entity in world.loadedEntityList) {
            if (entity == self || entity.isDead) continue

            val view = view(entity) ?: continue

            // Samsara rise：展示文本 0 / 物品 碰撞箱高+0.35 / 其余 眼高+0.55
            val rise = if (entity is EntityItem) entity.height + 0.35F else entity.eyeHeight + 0.55F
            val pos = ESPUtil.getInterpolatedPos(entity)
            val point = ESPUtil.projectOn2D(
                pos[0].toFloat(), (pos[1] + rise).toFloat(), pos[2].toFloat(), sr.scaleFactor
            ) ?: continue
            if (point.z < 0f || point.z > 1f) continue

            val opacity = edgeOpacity(point.x, sr.scaledWidth.toFloat())
            if (opacity < MIN_OPACITY) continue

            projected += Projected(entity, point.x, point.y, self.getDistanceSqToEntity(entity), opacity, view)
        }

        // 距离降序：远的先画，近的覆盖在上面；同距按 entityId 稳定排序
        projected.sortWith(compareByDescending<Projected> { it.distanceSquared }.thenBy { it.entity.entityId })
    }

    // ==================================================================
    // 绘制（Samsara Painter.paint / NativeSurface）
    // ==================================================================
    val onRender2D = handler<Render2DEvent> {
        if (projected.isEmpty()) return@handler

        for (tag in projected) {
            GlStateManager.pushMatrix()
            GlStateManager.translate(tag.x, tag.y, 0f)
            GlStateManager.scale(scale, scale, 1f)
            drawTag(tag)
            GlStateManager.popMatrix()
        }

        GlStateManager.color(1f, 1f, 1f, 1f)
        GlStateManager.enableTexture2D()
    }

    // ==================================================================
    // 内容构建（Samsara view() / formattedName / actualHealth / equipment / Armor）
    // ==================================================================

    /**
     * 实体过滤 + 部件构建（对应 Samsara accepts + view）：
     * 玩家 = 血量(带心) + 队伍名 + 护甲差 + 距离 + 装备 + 记分板行；
     * 物品 = 名称 + x数量；具名盔甲架 = 悬浮文本逐行。
     */
    private fun view(entity: Entity): View? {
        if (entity is EntityPlayer) {
            if (!players) return null
            val self = mc.thePlayer ?: return null

            val parts = ArrayList<Part>(4)
            val health = actualHealth(entity)
            parts += Part(health.toString(), healthColor(health), true)
            parts += Part(formattedName(entity), WHITE, false)
            if (armorDifference) parts += armorPart(self.totalArmorValue, entity.totalArmorValue)
            parts += Part(Math.round(self.getDistanceToEntity(entity)).toString() + "m", DISTANCE, false)

            val equipment = if (playerArmor) equipment(entity) else emptyList()
            return View(parts, equipment, belowNameScore(entity), false)
        }

        if (entity is EntityItem) {
            if (!items) return null
            val stack = entity.entityItem ?: return null
            if (stack.stackSize <= 0) return null
            return View(
                listOf(Part(stack.displayName, WHITE, false), Part("x" + stack.stackSize, COUNT, false)),
                emptyList(), null, false
            )
        }

        // FloatingText：Samsara 接受 Display.TextDisplay / 具名可见盔甲架 → 1.8.9 后者
        if (floatingText && entity is EntityArmorStand && entity.alwaysRenderNameTag) {
            val text = entity.customNameTag
            if (text.isNullOrEmpty()) return null
            return View(text.split("\n").map { Part(it, WHITE, false) }, emptyList(), null, true)
        }

        return null
    }

    /**
     * Samsara formattedName：队伍格式化名字基础上叠加状态色 ——
     * bot §3（深青）/ 隐身 §6（金）/ 潜行 §4（深红）/ 好友 §b（天蓝）。
     */
    private fun formattedName(player: EntityPlayer): String {
        val teamName = player.displayName.formattedText
        val statusColor = when {
            isBot(player) -> "§3"
            player.isInvisible -> "§6"
            player.isSneaking -> "§4"
            friendsConfig.friends.any { it.playerName == player.name } -> "§b"
            else -> return teamName
        }
        return statusColor + stripColor(teamName)
    }

    /** Samsara actualHealth：血量目标记分板值优先，否则 血量 + 伤害吸收。 */
    private fun actualHealth(player: EntityPlayer): Int {
        val scoreboard = player.worldScoreboard
        val objective = scoreboard.getObjectiveInDisplaySlot(2)
        if (objective != null && isHealthObjective(objective.displayName)) {
            return maxOf(0, scoreboard.getValueFromObjective(player.name, objective).scorePoints)
        }
        return maxOf(0, (player.health + player.absorptionAmount).roundToInt())
    }

    /** Samsara isHealthObjective（❤ / hp / health / lives）。 */
    private fun isHealthObjective(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        val lower = name.lowercase()
        return "❤" in lower || "hp" in lower || "health" in lower || "lives" in lower
    }

    /** Samsara score：低于名字的记分板行（非血量目标）。 */
    private fun belowNameScore(player: EntityPlayer): String? {
        val scoreboard = player.worldScoreboard
        val objective = scoreboard.getObjectiveInDisplaySlot(2) ?: return null
        if (isHealthObjective(objective.displayName)) return null
        val value = scoreboard.getValueFromObjective(player.name, objective).scorePoints
        return "$value ${objective.displayName}"
    }

    /** Samsara equipment：主手 → 头 → 胸 → 腿 → 脚（1.8.9 无副手槽），仅非空。 */
    private fun equipment(player: EntityPlayer): List<ItemStack> = listOf(
        player.heldItem,
        player.getCurrentArmor(3),
        player.getCurrentArmor(2),
        player.getCurrentArmor(1),
        player.getCurrentArmor(0)
    ).filterNotNull().filter { it.stackSize > 0 }.map { it.copy() }

    /** Samsara Geometry.armorPart："=" 橙 / 正差 绿 / 负差 红。 */
    private fun armorPart(local: Int, target: Int): Part {
        val difference = local - target
        return Part(
            if (difference == 0) "=" else difference.toString(),
            when {
                difference == 0 -> EQUAL
                difference > 0 -> GREEN
                else -> RED
            },
            false
        )
    }

    /** Samsara Geometry.healthColor：>=14 绿 / >=8 黄 / 其余红。 */
    private fun healthColor(health: Int) = when {
        health >= 14 -> GREEN
        health >= 8 -> YELLOW
        else -> RED
    }

    // ==================================================================
    // 布局（Samsara Geometry.layout）
    // ==================================================================
    private fun layout(parts: List<Part>, padding: Float): Layout {
        val capsules = ArrayList<Capsule>(parts.size)
        var x = 0f
        for (part in parts) {
            val width = padding * 2f + font.getStringWidth(part.text) + (if (part.heart) 16f else 0f)
            capsules += Capsule(part, x, width)
            x += width + GAP
        }
        return Layout(capsules, (x - GAP).coerceAtLeast(0f))
    }

    // ==================================================================
    // 绘制细节（Samsara Painter.paintCapsule / NativeSurface.panel / item / score）
    // ==================================================================
    private fun drawTag(tag: Projected) {
        currentTag = tag
        val view = tag.view
        val padding = if (view.floating) FLOATING_PADDING else PADDING
        val height = tagFontHeight + padding * 2f
        val top = -height / 2f
        val lay = layout(view.parts, padding)

        if (view.floating) {
            // 悬浮文本：逐行居中堆叠（Samsara FLOATING_HEIGHT 分支）
            for ((index, capsule) in lay.capsules.withIndex()) {
                paintCapsule(capsule, -capsule.width / 2f, top + index * (height + GAP), height, padding, tag.opacity)
            }
        } else {
            // 水平：胶囊连排整体居中（Samsara horizontal 分支）
            for (capsule in lay.capsules) {
                paintCapsule(capsule, capsule.x - lay.width / 2f, top, height, padding, tag.opacity)
            }
        }

        // 装备图标 / 记分板行均位于胶囊上方（Samsara top - 36）；两者同时存在时错开避免重叠
        if (view.equipment.isNotEmpty()) drawEquipment(view.equipment, top - EQUIPMENT_TOP)
        if (view.score != null) {
            val scoreY = if (view.equipment.isEmpty()) top - EQUIPMENT_TOP else top - EQUIPMENT_TOP - EQUIPMENT_PITCH
            font.drawCenteredString(view.score, 0f, scoreY, tint(WHITE, tag.opacity), true)
        }
    }

    private fun paintCapsule(capsule: Capsule, x: Float, y: Float, height: Float, padding: Float, opacity: Float) {
        // Samsara shader 开启时为每个胶囊注册背景模糊（HudBackdrop.widget，屏幕 AABB = 名单位置 + 局部×缩放）
        if (shader && opacity > 0f) {
            val tag = currentTag
            if (tag != null) {
                HudBlur.blur(
                    tag.x + x * scale, tag.y + y * scale,
                    tag.x + (x + capsule.width) * scale, tag.y + (y + height) * scale,
                    8f, "InternalBlur"
                ) {
                    drawRoundedRect(x, y, x + capsule.width, y + height, -1, RADIUS)
                }
            }
        }
        drawPanel(x, y, capsule.width, height, opacity)

        var textX = x + padding
        if (capsule.part.heart) {
            drawHeart(textX + 1.25f, y + (height - HEART_HEIGHT) / 2f, tint(capsule.part.color, opacity))
            textX += 16f
        }

        val color = tint(capsule.part.color, opacity)
        if (color ushr 24 >= 4) {
            font.drawString(capsule.part.text, textX, y + (height - tagFontHeight) / 2f + 2f, color, true)
        }
    }

    /** Samsara NativeSurface.panel：shader = 9-slice 阴影贴图 + 深胶囊贴图，否则单层半透明胶囊贴图。 */
    private fun drawPanel(x: Float, y: Float, width: Float, height: Float, opacity: Float) {
        if (shader) {
            // Samsara mask("shadow", x-8, y-8, w+16, h+16, 0x80000000)
            HudSlices.nametagMask(
                true, x - SHADOW_OFFSET, y - SHADOW_OFFSET,
                width + SHADOW_OFFSET * 2f, height + SHADOW_OFFSET * 2f,
                RADIUS, tint(SHADOW_COLOR, opacity)
            )
        }
        HudSlices.nametagMask(
            false, x, y, width, height, RADIUS,
            tint(if (shader) CAPSULE_SHADER_COLOR else CAPSULE_PLAIN_COLOR, opacity)
        )
    }

    /** 装备图标行：Samsara item scale 1.75、间距 32，居中排布（1.8.9 GUI 物件渲染）。 */
    private fun drawEquipment(equipment: List<ItemStack>, top: Float) {
        val x0 = -(equipment.size * EQUIPMENT_PITCH - 4f) / 2f
        RenderHelper.enableGUIStandardItemLighting()
        for ((index, stack) in equipment.withIndex()) {
            GlStateManager.pushMatrix()
            GlStateManager.enableRescaleNormal()
            GlStateManager.translate(x0 + index * EQUIPMENT_PITCH, top, 0f)
            GlStateManager.scale(EQUIPMENT_ITEM_SCALE, EQUIPMENT_ITEM_SCALE, 1f)
            mc.renderItem.renderItemAndEffectIntoGUI(stack, 0, 0)
            mc.renderItem.renderItemOverlays(mc.fontRendererObj, stack, 0, 0)
            GlStateManager.popMatrix()
        }
        RenderHelper.disableStandardItemLighting()
        GlStateManager.disableRescaleNormal()
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, 1, 0)
        GlStateManager.enableTexture2D()
        GlStateManager.color(1f, 1f, 1f, 1f)
    }

    /**
     * 心形（Samsara heart.png 的程序化近似）：经典心形参数曲线
     * x = 16sin³t, y = 13cost − 5cos2t − 2cos3t − cos4t，三角形扇填充并按健康色着色。
     */
    private fun drawHeart(x: Float, y: Float, argb: Int) {
        val alpha = (argb ushr 24 and 0xFF) / 255f
        if (alpha <= 0f) return
        val red = (argb ushr 16 and 0xFF) / 255f
        val green = (argb ushr 8 and 0xFF) / 255f
        val blue = (argb and 0xFF) / 255f

        val cx = x + HEART_WIDTH / 2f
        val cy = y + HEART_HEIGHT / 2f
        val sx = (HEART_WIDTH / 32f).toDouble()
        val sy = (HEART_HEIGHT / 29f).toDouble()

        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, 1, 0)
        GlStateManager.disableTexture2D()
        GlStateManager.color(1f, 1f, 1f, 1f)

        val tessellator = Tessellator.getInstance()
        val buffer = tessellator.worldRenderer
        buffer.begin(GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR)
        buffer.pos(cx.toDouble(), cy.toDouble(), 0.0).color(red, green, blue, alpha).endVertex()
        for (i in 0 until HEART_SEGMENTS) {
            val t = i * 2.0 * Math.PI / HEART_SEGMENTS
            val px = 16.0 * Math.sin(t).pow(3)
            val py = 13.0 * Math.cos(t) - 5.0 * Math.cos(2 * t) - 2.0 * Math.cos(3 * t) - Math.cos(4 * t)
            buffer.pos(cx + px * sx, cy - (py + 2.5) * sy, 0.0).color(red, green, blue, alpha).endVertex()
        }
        tessellator.draw()

        GlStateManager.enableTexture2D()
        GlStateManager.disableBlend()
    }

    // ==================================================================
    // 工具（Samsara Geometry.opacity / NativeSurface.tint）
    // ==================================================================

    /** 屏幕左右各 12.5% 边缘 smoothstep 淡出。 */
    private fun edgeOpacity(x: Float, width: Float): Float {
        if (!x.isFinite() || width <= 0f) return 0f
        val edge = abs(x * 2f / width - 1f)
        val t = ((edge - 0.75f) / 0.25f).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }

    /** 整体透明度乘进颜色 alpha（Samsara tint）。 */
    private fun tint(argb: Int, opacity: Float) =
        ((argb ushr 24) * opacity).roundToInt() shl 24 or (argb and 0xFFFFFF)
}
