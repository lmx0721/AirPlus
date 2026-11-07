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
import net.airplus.features.module.modules.combat.KillAura
import net.airplus.features.module.modules.misc.AntiBot.isBot
import net.airplus.features.module.modules.misc.Teams
import net.airplus.file.FileManager.friendsConfig
import net.airplus.ui.font.Fonts
import net.airplus.ui.font.GameFontRenderer
import net.airplus.ui.font.drawCenteredString
import net.airplus.utils.attack.EntityUtils.getHealth
import net.airplus.utils.attack.EntityUtils.isLookingOnEntities
import net.airplus.utils.attack.EntityUtils.isSelected
import net.airplus.utils.client.EntityLookup
import net.airplus.utils.extensions.*
import net.airplus.utils.misc.HYTUtils
import net.airplus.utils.render.ColorUtils
import net.airplus.utils.render.ColorUtils.withAlpha
import net.airplus.utils.render.ESPUtil
import net.airplus.utils.render.HudBlur
import net.airplus.utils.render.HudSlices
import net.airplus.utils.render.RenderUtils.disableGlCap
import net.airplus.utils.render.RenderUtils.drawTexturedModalRect
import net.airplus.utils.render.RenderUtils.enableGlCap
import net.airplus.utils.render.RenderUtils.quickDrawBorderedRect
import net.airplus.utils.render.RenderUtils.quickDrawRect
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.airplus.utils.render.RenderUtils.resetCaps
import net.airplus.utils.rotation.RotationUtils.isEntityHeightVisible
import net.minecraft.client.entity.EntityPlayerSP
import net.minecraft.client.gui.Gui
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager.*
import net.minecraft.client.renderer.RenderHelper
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.item.EntityArmorStand
import net.minecraft.entity.item.EntityItem
import net.minecraft.entity.monster.EntityMob
import net.minecraft.entity.passive.EntityAnimal
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.init.Items
import net.minecraft.item.ItemStack
import net.minecraft.potion.Potion
import net.minecraft.util.EnumChatFormatting
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.GL11.*
import org.lwjgl.util.vector.Vector4f
import java.awt.Color
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 统一的 NameTags 模块：通过顶部的 Mode 设置选择样式，每个样式有独立的设置（随 Mode 切换显隐）。
 *
 * - Legacy：经典 3D 公告栏名牌（原 NameTags）
 * - Xinxin：从 SilenceFix 移植的 2D 方块名牌（原 NameTags2）
 * - Opai：从 Samsara 移植的胶囊式名牌（原 NameTagOpai）
 * - Moon：从 moon-rebase NametagsModule 迁移的原版风名牌
 *
 * 非 Legacy 样式由 MixinRendererLivingEntity#canRenderName 取消原版名牌后自绘。
 */
object NameTags : Module("NameTags", Category.RENDER) {

    // ================= 样式选择（置于最上方） =================
    private val mode by choices("Mode", arrayOf("Legacy", "Xinxin", "Opai", "Moon"), "Legacy")

    /** 供 MixinRendererLivingEntity 使用：非 Legacy 样式全部自绘，需要取消原版玩家名牌。 */
    val cancelVanillaName: Boolean
        get() = mode != "Legacy"

    // ================= Legacy 样式设置（名称保持向后兼容） =================
    private val renderSelf by boolean("RenderSelf", false) { mode == "Legacy" }
    private val health by boolean("Health", true) { mode == "Legacy" }
    private val healthFromScoreboard by boolean("HealthFromScoreboard", false) { mode == "Legacy" && health }
    private val absorption by boolean("Absorption", false) { mode == "Legacy" && (health || healthBar) }
    private val roundedHealth by boolean("RoundedHealth", true) { mode == "Legacy" && health }

    private val healthPrefix by boolean("HealthPrefix", false) { mode == "Legacy" && health }
    private val healthPrefixText by text("HealthPrefixText", "") { mode == "Legacy" && health && healthPrefix }

    private val healthSuffix by boolean("HealthSuffix", true) { mode == "Legacy" && health }
    private val healthSuffixText by text("HealthSuffixText", " HP") { mode == "Legacy" && health && healthSuffix }

    private val ping by boolean("Ping", false) { mode == "Legacy" }
    private val healthBar by boolean("Bar", true) { mode == "Legacy" }
    private val distance by boolean("Distance", false) { mode == "Legacy" }
    private val armor by boolean("Armor", true) { mode == "Legacy" }
    private val bot by boolean("Bots", true) { mode == "Legacy" }
    private val potion by boolean("Potions", true) { mode == "Legacy" }
    private val clearNames by boolean("ClearNames", false) { mode == "Legacy" }
    private val font by font("Font", Fonts.fontRegular40) { mode == "Legacy" }
    private val scale by float("Scale", 1F, 1F..4F) { mode == "Legacy" }
    private val fontShadow by boolean("Shadow", true) { mode == "Legacy" }

    private val background by boolean("Background", true) { mode == "Legacy" }
    private val backgroundColor by color("BackgroundColor", Color.BLACK.withAlpha(70)) { mode == "Legacy" && background }

    private val border by boolean("Border", true) { mode == "Legacy" }
    private val borderColor by color("BorderColor", Color.BLACK.withAlpha(100)) { mode == "Legacy" && border }

    private val maxRenderDistance by int("MaxRenderDistance", 50, 1..200) { mode == "Legacy" }.onChanged { value ->
        maxRenderDistanceSq = value.toDouble().pow(2)
    }

    private val onLook by boolean("OnLook", false) { mode == "Legacy" }
    private val maxAngleDifference by float("MaxAngleDifference", 90f, 5.0f..90f) { mode == "Legacy" && onLook }

    private val thruBlocks by boolean("ThruBlocks", true) { mode == "Legacy" }

    private var maxRenderDistanceSq = 0.0
        set(value) {
            field = if (value <= 0.0) maxRenderDistance.toDouble().pow(2.0) else value
        }

    private val inventoryBackground = ResourceLocation("textures/gui/container/inventory.png")
    private val decimalFormat = DecimalFormat("##0.00", DecimalFormatSymbols(Locale.ENGLISH))

    private val entities by EntityLookup<EntityLivingBase>()
        .filter { bot || !isBot(it) }
        .filter { !onLook || isLookingOnEntities(it, maxAngleDifference.toDouble()) }
        .filter { thruBlocks || isEntityHeightVisible(it) }

    // ================= Xinxin 样式设置（SilenceFix，原 NameTags2） =================
    private val xinxinPlayers by boolean("XinxinPlayers", true) { mode == "Xinxin" }
    private val xinxinAnimals by boolean("XinxinAnimals", true) { mode == "Xinxin" }
    private val xinxinMobs by boolean("XinxinMobs", false) { mode == "Xinxin" }
    private val xinxinInvisible by boolean("XinxinInvisible", false) { mode == "Xinxin" }
    private val xinxinItems by boolean("XinxinItems", true) { mode == "Xinxin" }

    // ================= Opai 样式设置（Samsara，原 NameTagOpai） =================
    private val opaiPlayers by boolean("OpaiPlayers", true) { mode == "Opai" }
    private val opaiItems by boolean("OpaiItems", true) { mode == "Opai" }
    private val opaiPlayerArmor by boolean("OpaiPlayerArmor", true) { mode == "Opai" && opaiPlayers }
    private val opaiArmorDifference by boolean("OpaiArmorDifference", true) { mode == "Opai" && opaiPlayers }
    private val opaiFloatingText by boolean("OpaiFloatingText", true) { mode == "Opai" }
    private val opaiShader by boolean("OpaiShader", true) { mode == "Opai" }
    private val opaiScale by float("OpaiScale", 0.8F, 0.1F..2F) { mode == "Opai" }
    private val opaiFont by font("OpaiFont", Fonts.fontRegular40) { mode == "Opai" }

    // ================= Moon 样式设置（moon-rebase NametagsModule 迁移） =================
    private val moonPlayers by boolean("MoonPlayers", true) { mode == "Moon" }
    private val moonAnimals by boolean("MoonAnimals", false) { mode == "Moon" }
    private val moonMobs by boolean("MoonMobs", false) { mode == "Moon" }
    private val moonInvisible by boolean("MoonInvisible", false) { mode == "Moon" }
    private val moonHealth by boolean("MoonHealth", true) { mode == "Moon" }
    private val moonDistance by boolean("MoonDistance", true) { mode == "Moon" }
    private val moonSeparateInfo by boolean("MoonSeparateInfo", true) { mode == "Moon" }
    private val moonRound by boolean("MoonRound", true) { mode == "Moon" }
    private val moonFont by font("MoonFont", Fonts.fontRegular35) { mode == "Moon" }

    // ==================================================================
    // Legacy：经典 3D 公告栏名牌
    // ==================================================================
    val onRender3D = handler<Render3DEvent> {
        if (mode != "Legacy") return@handler
        if (mc.theWorld == null || mc.thePlayer == null) return@handler

        glPushAttrib(GL_ENABLE_BIT)
        glPushMatrix()

        // Disable lightning and depth test
        glDisable(GL_LIGHTING)
        glDisable(GL_DEPTH_TEST)

        glEnable(GL_LINE_SMOOTH)

        // Enable blend
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        for (entity in entities) {
            val isRenderingSelf =
                entity is EntityPlayerSP && (mc.gameSettings.thirdPersonView != 0 || FreeCam.handleEvents())

            if (!isRenderingSelf || !renderSelf) {
                if (!isSelected(entity, false)) continue
            }

            val name = entity.displayName.unformattedText ?: continue

            val distanceSquared = mc.thePlayer.getDistanceSqToEntity(entity)

            // In case user has FreeCam enabled, we restore the position back to normal,
            // so it renders the name-tag at the player's body position instead of the FreeCam position.
            if (isRenderingSelf) {
                FreeCam.restoreOriginalPosition()
            }

            if (distanceSquared <= maxRenderDistanceSq) {
                renderNameTag(entity, isRenderingSelf, if (clearNames) ColorUtils.stripColor(name) else name)
            }

            if (isRenderingSelf) {
                FreeCam.useModifiedPosition()
            }
        }

        glDisable(GL_BLEND)
        glDisable(GL_LINE_SMOOTH)

        glPopMatrix()
        glPopAttrib()

        // Reset color
        glColor4f(1F, 1F, 1F, 1F)
    }

    private fun renderNameTag(entity: EntityLivingBase, isRenderingSelf: Boolean, name: String) {
        val thePlayer = mc.thePlayer ?: return

        // Set fontrenderer local
        val fontRenderer = font

        // Push
        glPushMatrix()

        // Translate to player position
        val renderManager = mc.renderManager
        val rotateX = if (mc.gameSettings.thirdPersonView == 2) -1.0f else 1.0f

        val (x, y, z) = entity.interpolatedPosition(entity.lastTickPos) - renderManager.renderPos

        glTranslated(x, y + entity.eyeHeight.toDouble() + 0.55, z)

        glRotatef(-renderManager.playerViewY, 0F, 1F, 0F)
        glRotatef(renderManager.playerViewX * rotateX, 1F, 0F, 0F)

        // Disable lightning and depth test
        disableGlCap(GL_LIGHTING, GL_DEPTH_TEST)

        // Enable blend
        enableGlCap(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        // Modify tag
        val bot = isBot(entity)
        val nameColor = if (bot) "§3" else if (entity.isInvisible) "§6" else if (entity.isSneaking) "§4" else "§7"
        val playerPing = if (entity is EntityPlayer) entity.getPing() else 0
        val playerDistance = thePlayer.getDistanceToEntity(entity)

        val distanceText = if (distance && !isRenderingSelf) "§7${playerDistance.roundToInt()} m " else ""
        val pingText =
            if (ping && entity is EntityPlayer) "§7[" + (if (playerPing > 200) "§c" else if (playerPing > 100) "§e" else "§a") + playerPing + "ms§7] " else ""
        val healthText = if (health) " " + getHealthString(entity) else ""
        val botText = if (bot) " §c§lBot" else ""

        val text = "$distanceText$pingText$nameColor$name$healthText$botText"

        // Calculate health color based on entity's health
        val healthColor = when {
            entity.health <= 0 -> Color(255, 0, 0)
            else -> {
                val healthRatio = (getHealth(entity, healthFromScoreboard) / entity.maxHealth).coerceIn(0.0F, 1.0F)
                val red = (255 * (1 - healthRatio)).toInt()
                val green = (255 * healthRatio).toInt()
                Color(red, green, 0)
            }
        }

        // Scale
        val scale = ((playerDistance / 4F).coerceAtLeast(1F) / 150F) * scale

        glScalef(-scale, -scale, scale)

        val width = fontRenderer.getStringWidth(text) * 0.5f
        fontRenderer.drawString(
            text, 1F + -width, if (fontRenderer == Fonts.minecraftFont) 1F else 1.5F, 0xFFFFFF, fontShadow
        )

        val dist = width + 4F - (-width - 2F)

        glDisable(GL_TEXTURE_2D)
        glEnable(GL_BLEND)

        val bgColor = if (background) {
            // Background
            backgroundColor
        } else {
            // Transparent
            Color(0, 0, 0, 0)
        }

        if (border) quickDrawBorderedRect(
            -width - 2F,
            -2F,
            width + 4F,
            fontRenderer.FONT_HEIGHT + 2F + if (healthBar) 2F else 0F,
            2F,
            borderColor.rgb,
            bgColor.rgb
        )
        else quickDrawRect(
            -width - 2F, -2F, width + 4F, fontRenderer.FONT_HEIGHT + 2F + if (healthBar) 2F else 0F, bgColor.rgb
        )

        if (healthBar) {
            quickDrawRect(
                -width - 2F,
                fontRenderer.FONT_HEIGHT + 3F,
                -width - 2F + dist,
                fontRenderer.FONT_HEIGHT + 4F,
                Color(50, 50, 50).rgb
            )
            quickDrawRect(
                -width - 2F,
                fontRenderer.FONT_HEIGHT + 3F,
                -width - 2F + (dist * (getHealth(entity, healthFromScoreboard) / entity.maxHealth).coerceIn(0F, 1F)),
                fontRenderer.FONT_HEIGHT + 4F,
                healthColor.rgb
            )
        }

        glEnable(GL_TEXTURE_2D)

        fontRenderer.drawString(
            text, 1F + -width, if (fontRenderer == Fonts.minecraftFont) 1F else 1.5F, Color.white.rgb, fontShadow
        )

        var foundPotion = false

        if (potion && entity is EntityPlayer) {
            val potions =
                entity.activePotionEffects.map { Potion.potionTypes[it.potionID] }.filter { it.hasStatusIcon() }
            if (potions.isNotEmpty()) {
                foundPotion = true

                color(1.0F, 1.0F, 1.0F, 1.0F)
                disableLighting()
                enableTexture2D()

                val minX = (potions.size * -20) / 2

                glPushMatrix()
                enableRescaleNormal()
                for ((index, potion) in potions.withIndex()) {
                    color(1.0F, 1.0F, 1.0F, 1.0F)
                    mc.textureManager.bindTexture(inventoryBackground)
                    val i1 = potion.statusIconIndex
                    drawTexturedModalRect(minX + index * 20, -22, 0 + i1 % 8 * 18, 198 + i1 / 8 * 18, 18, 18, 0F)
                }
                disableRescaleNormal()
                glPopMatrix()

                enableAlpha()
                disableBlend()
                enableTexture2D()
            }
        }

        if (armor && entity is EntityPlayer) {
            RenderHelper.enableGUIStandardItemLighting()
            for (index in 0..4) {
                val itemStack = entity.getEquipmentInSlot(index) ?: continue

                mc.renderItem.zLevel = -147F
                mc.renderItem.renderItemAndEffectIntoGUI(
                    itemStack, -50 + index * 20, if (potion && foundPotion) -42 else -22
                )
            }
            RenderHelper.disableStandardItemLighting()

            enableAlpha()
            disableBlend()
            enableTexture2D()
        }

        // Reset caps
        resetCaps()

        // Reset color
        resetColor()
        glColor4f(1F, 1F, 1F, 1F)

        // Pop
        glPopMatrix()
    }

    private fun getHealthString(entity: EntityLivingBase): String {
        val prefix = if (healthPrefix) healthPrefixText else ""
        val suffix = if (healthSuffix) healthSuffixText else ""

        val result = getHealth(entity, healthFromScoreboard, absorption)

        val healthPercentage = (getHealth(entity, healthFromScoreboard) / entity.maxHealth).coerceIn(0.0F, 1.0F)
        val healthColor = when {
            entity.health <= 0 -> "§4"
            healthPercentage >= 0.75 -> "§a"
            healthPercentage >= 0.5 -> "§e"
            healthPercentage >= 0.25 -> "§6"
            else -> "§c"
        }

        return "$healthColor$prefix${if (roundedHealth) result.roundToInt() else decimalFormat.format(result)}$suffix"
    }

    fun shouldRenderNameTags(entity: Entity) =
        handleEvents() && entity is EntityLivingBase && (ESP.handleEvents() && ESP.renderNameTags || isSelected(
            entity,
            false
        ) && (bot || !isBot(entity)))

    // ==================================================================
    // Xinxin 样式：SilenceFix 2D 方块名牌（原 NameTags2）
    // ==================================================================
    private val xinxinFont get() = Fonts.fontRegular35
    private val xinxinEntityPosition = HashMap<Entity, Vector4f>()
    private val xinxinItemPosition = HashMap<EntityItem, Vector4f>()
    private val xinxinDistanceFormat = DecimalFormat("0.0")

    private val xinxinRender3D = handler<Render3DEvent> {
        if (mode != "Xinxin") return@handler
        xinxinEntityPosition.clear()
        xinxinItemPosition.clear()

        val world = mc.theWorld ?: return@handler

        for (entity in world.loadedEntityList) {
            if (xinxinShouldRender(entity) && ESPUtil.isInView(entity)) {
                xinxinEntityPosition[entity] = ESPUtil.getEntityPositionsOn2D(entity)
            }

            if (xinxinItems && xinxinShouldRenderItem(entity) && ESPUtil.isInView(entity)) {
                xinxinItemPosition[entity as EntityItem] = ESPUtil.getEntityPositionsOn2D(entity)
            }
        }
    }

    private val xinxinRender2D = handler<Render2DEvent> {
        if (mode != "Xinxin") return@handler
        for ((item, pos) in xinxinItemPosition) {
            xinxinDrawItemTag(item, pos.x, pos.y + 10, pos.z)
        }

        for ((entity, pos) in xinxinEntityPosition) {
            if (entity !is EntityLivingBase) continue
            xinxinDrawNameTag(entity, pos.x, pos.y, pos.z)
        }
    }

    private fun xinxinDrawNameTag(entity: EntityLivingBase, x: Float, y: Float, width: Float) {
        val f = xinxinFont
        var name = entity.displayName.formattedText
        name = xinxinRank(entity) + EnumChatFormatting.WHITE.toString() + name
        val sep = " | "
        val hpStr = max(0f, entity.health).roundToInt().toString() + "HP"
        val nameW = f.getStringWidth(name).toFloat()
        val sepW = f.getStringWidth(sep).toFloat()
        val hpW = f.getStringWidth(hpStr).toFloat()
        val textW = nameW + sepW + hpW
        val padX = 4f
        val padY = 2f
        val tagW = textW + padX * 2f
        val tagH = f.FONT_HEIGHT + padY * 2f
        val mid = x + (width - x) / 2.0f
        val boxL = mid - tagW / 2.0f
        val boxT = y - (tagH + 6.0f)
        val boxR = boxL + tagW
        val boxB = boxT + tagH
        val bg = Color(20, 20, 20, 130).rgb
        val cName = 0xFFFFFFFF.toInt()
        val cSep = Color(190, 190, 190).rgb
        val cHP = Color(230, 70, 70).rgb
        Gui.drawRect(boxL.toInt(), boxT.toInt(), boxR.toInt(), boxB.toInt(), bg)
        var tx = boxL + padX
        val ty = boxT + (tagH - f.FONT_HEIGHT) / 2f + 2.0f
        f.drawString(name, tx, ty, cName); tx += nameW
        f.drawString(sep, tx, ty, cSep); tx += sepW
        f.drawString(hpStr, tx, ty, cHP)
    }

    private fun xinxinDrawItemTag(item: EntityItem, x: Float, y: Float, width: Float) {
        val stack = item.entityItem ?: return
        val f = xinxinFont

        val itemName = xinxinItemName(stack)
        if (itemName.isNullOrEmpty()) return

        val count = if (stack.stackSize > 1) " x" + stack.stackSize else ""
        val sep = " | "
        val distStr = String.format("%.1fm", mc.thePlayer.getDistanceToEntity(item))

        val special = xinxinIsTargetItem(stack)

        val nameW = f.getStringWidth(itemName + count).toFloat()
        val sepW = if (special) f.getStringWidth(sep).toFloat() else 0f
        val dW = if (special) f.getStringWidth(distStr).toFloat() else 0f
        val textW = nameW + sepW + dW

        val padX = 4f
        val padY = 2f
        val tagW = textW + padX * 2f
        val tagH = f.FONT_HEIGHT + padY * 2f

        val mid = x + (width - x) / 2.0f
        val boxL = mid - tagW / 2.0f
        val boxT = y - (tagH + 5.0f)
        val boxR = boxL + tagW
        val boxB = boxT + tagH

        val bg = Color(20, 20, 20, 130).rgb
        val cSep = Color(190, 190, 190).rgb
        val cRed = Color(230, 70, 70).rgb
        val cName = if (special) xinxinItemColor(stack) else 0xFFFFFFFF.toInt()

        if (special) {
            Gui.drawRect(boxL.toInt(), boxT.toInt(), boxR.toInt(), boxB.toInt(), bg)
        }

        val ty = boxT + (tagH - f.FONT_HEIGHT) / 2f + 2.0f
        var tx = if (special) boxL + padX else mid - textW / 2.0f

        f.drawString(itemName + count, tx, ty, cName)
        if (special) {
            tx += nameW
            f.drawString(sep, tx, ty, cSep)
            tx += sepW
            f.drawString(distStr, tx, ty, cRed)
        }
    }

    private fun xinxinItemName(stack: ItemStack): String? {
        return when (stack.item) {
            Items.golden_apple ->
                if (stack.metadata == 1)
                    EnumChatFormatting.GOLD.toString() + "附魔金苹果"
                else
                    EnumChatFormatting.YELLOW.toString() + "金苹果"
            Items.gold_ingot -> EnumChatFormatting.GOLD.toString() + "金锭"
            Items.iron_ingot -> EnumChatFormatting.GRAY.toString() + "铁锭"
            Items.diamond -> EnumChatFormatting.AQUA.toString() + "钻石"
            else -> stack.displayName
        }
    }

    private fun xinxinItemColor(stack: ItemStack): Int {
        return when (stack.item) {
            Items.golden_apple ->
                if (stack.metadata == 1) Color(255, 170, 0).rgb else Color(255, 255, 85).rgb
            Items.gold_ingot -> Color(255, 255, 85).rgb
            Items.iron_ingot -> Color(200, 200, 200).rgb
            Items.diamond -> Color(85, 255, 255).rgb
            else -> -1
        }
    }

    private fun xinxinRank(entity: EntityLivingBase): String {
        if (entity is EntityPlayer) {
            if (entity == mc.thePlayer) {
                return EnumChatFormatting.GREEN.toString() + "[You] "
            }
            if (entity == KillAura.target) {
                return EnumChatFormatting.DARK_RED.toString() + "[杀戮目标] "
            }
            if (Teams.handleEvents() && Teams.isInYourTeam(entity)) {
                return EnumChatFormatting.GREEN.toString() + "[队伍] "
            }
            if (HYTUtils.isStrength(entity) > 0) {
                return EnumChatFormatting.DARK_RED.toString() + "[力量狗] "
            }
            if (HYTUtils.isRegen(entity) > 0) {
                return EnumChatFormatting.DARK_RED.toString() + "[生命恢复狗] "
            }
            if (HYTUtils.isHoldingGodAxe(entity)) {
                return EnumChatFormatting.DARK_RED.toString() + "[秒人斧] "
            }
            if (HYTUtils.isKBBall(entity.heldItem)) {
                return EnumChatFormatting.DARK_RED.toString() + "[击退粘液球] "
            }
            if (HYTUtils.hasEatenGoldenApple(entity) > 0) {
                return EnumChatFormatting.DARK_RED.toString() + "[金苹果] "
            }
        }
        return ""
    }

    private fun xinxinShouldRender(entity: Entity): Boolean {
        if (entity.isDead) {
            return false
        }
        if (entity == mc.thePlayer && xinxinPlayers) {
            return mc.gameSettings.thirdPersonView != 0
        }

        if (entity is EntityPlayer && xinxinPlayers) {
            return true
        }

        if (entity is EntityAnimal && xinxinAnimals) {
            return true
        }

        if (entity is EntityMob && xinxinMobs) {
            return true
        }

        if (entity.isInvisible() && xinxinInvisible) {
            return true
        }

        return false
    }

    private fun xinxinShouldRenderItem(entity: Entity): Boolean =
        entity is EntityItem && !entity.isDead

    private fun xinxinIsTargetItem(stack: ItemStack?): Boolean =
        stack != null && (
            stack.item == Items.golden_apple ||
                stack.item == Items.gold_ingot ||
                stack.item == Items.iron_ingot ||
                stack.item == Items.diamond
            )

    // ==================================================================
    // Opai 样式：Samsara 胶囊名牌（原 NameTagOpai）
    // ==================================================================

    // ---------------- Samsara Geometry 常量 ----------------
    private const val OPAI_RADIUS = 12F            // Geometry.RADIUS
    private const val OPAI_PADDING = 7F            // Geometry.PADDING
    private const val OPAI_FLOATING_PADDING = 10F  // Geometry.FLOATING_PADDING
    private const val OPAI_GAP = 2F                // Geometry.GAP
    private const val OPAI_HEART_WIDTH = 13.5F     // Geometry.HEART_WIDTH
    private const val OPAI_HEART_HEIGHT = 13F      // Geometry.HEART_HEIGHT
    private const val OPAI_HEART_SEGMENTS = 24
    private const val OPAI_EQUIPMENT_PITCH = 32F   // 装备槽间距（Samsara i * 32）
    private const val OPAI_EQUIPMENT_ITEM_SCALE = 1.75F // Samsara item scale
    private const val OPAI_EQUIPMENT_TOP = 36F     // 装备/记分板行距胶囊顶部的距离
    private const val OPAI_SHADOW_OFFSET = 8F      // NanoVG shadow 的半透明外扩近似
    private const val OPAI_MIN_OPACITY = 4f / 255f

    // ---------------- Samsara 颜色 ----------------
    private val OPAI_GREEN = 0xFF88D922.toInt()    // Geometry.GREEN（血量充足 / 护甲优势）
    private val OPAI_YELLOW = 0xFFFFFF55.toInt()   // Geometry.YELLOW（血量中等）
    private val OPAI_RED = 0xFFFF3333.toInt()      // Geometry.RED（血量危险 / 护甲劣势）
    private val OPAI_EQUAL = 0xFFFFAA00.toInt()    // Geometry.EQUAL（护甲持平）
    private val OPAI_DISTANCE = 0xFF888888.toInt() // Geometry.DISTANCE
    private val OPAI_COUNT = 0xFFFFCC00.toInt()    // Geometry.COUNT（物品数量）
    private val OPAI_WHITE = 0xFFFFFFFF.toInt()
    private val OPAI_SHADOW_COLOR = 0x80000000.toInt()      // Samsara shadow mask 颜色
    private val OPAI_CAPSULE_SHADER_COLOR = 0x98000000.toInt() // Samsara shader 胶囊色
    private val OPAI_CAPSULE_PLAIN_COLOR = 0x80000000.toInt()  // Samsara 无 shader 胶囊色

    // ---------------- 数据结构 ----------------
    private class OpaiProjected(
        val entity: Entity,
        val x: Float,
        val y: Float,
        val distanceSquared: Double,
        val opacity: Float,
        val view: OpaiView
    )

    private class OpaiPart(val text: String, val color: Int, val heart: Boolean)

    private class OpaiCapsule(val part: OpaiPart, val x: Float, val width: Float)

    private class OpaiLayout(val capsules: List<OpaiCapsule>, val width: Float)

    private class OpaiView(
        val parts: List<OpaiPart>,
        val equipment: List<ItemStack>,
        val score: String?,
        val floating: Boolean
    )

    /** 选中字体（GameFontRenderer 已按原版布局折半）；回退原版字体时用 FONT_HEIGHT。 */
    private val opaiTagFontHeight: Int
        get() = (opaiFont as? GameFontRenderer)?.fontHeight ?: opaiFont.FONT_HEIGHT

    /** Render3D 收集 → Render2D 绘制的投影缓存（每帧重建）。 */
    private val opaiProjected = ArrayList<OpaiProjected>()

    /** 当前正在绘制的名牌（供 paintCapsule 计算背景模糊的屏幕 AABB）。 */
    private var opaiCurrentTag: OpaiProjected? = null

    private val opaiRender3D = handler<Render3DEvent> {
        if (mode != "Opai") return@handler
        opaiProjected.clear()

        val world = mc.theWorld ?: return@handler
        val self = mc.thePlayer ?: return@handler
        val sr = ScaledResolution(mc)

        for (entity in world.loadedEntityList) {
            if (entity == self || entity.isDead) continue

            val view = opaiView(entity) ?: continue

            // Samsara rise：展示文本 0 / 物品 碰撞箱高+0.35 / 其余 眼高+0.55
            val rise = if (entity is EntityItem) entity.height + 0.35F else entity.eyeHeight + 0.55F
            val pos = ESPUtil.getInterpolatedPos(entity)
            val point = ESPUtil.projectOn2D(
                pos[0].toFloat(), (pos[1] + rise).toFloat(), pos[2].toFloat(), sr.scaleFactor
            ) ?: continue
            if (point.z < 0f || point.z > 1f) continue

            val opacity = opaiEdgeOpacity(point.x, sr.scaledWidth.toFloat())
            if (opacity < OPAI_MIN_OPACITY) continue

            opaiProjected += OpaiProjected(entity, point.x, point.y, self.getDistanceSqToEntity(entity), opacity, view)
        }

        // 距离降序：远的先画，近的覆盖在上面；同距按 entityId 稳定排序
        opaiProjected.sortWith(compareByDescending<OpaiProjected> { it.distanceSquared }.thenBy { it.entity.entityId })
    }

    private val opaiRender2D = handler<Render2DEvent> {
        if (mode != "Opai") return@handler
        if (opaiProjected.isEmpty()) return@handler

        for (tag in opaiProjected) {
            pushMatrix()
            translate(tag.x, tag.y, 0f)
            scale(opaiScale, opaiScale, 1f)
            opaiDrawTag(tag)
            popMatrix()
        }

        color(1f, 1f, 1f, 1f)
        enableTexture2D()
    }

    /**
     * 实体过滤 + 部件构建（对应 Samsara accepts + view）：
     * 玩家 = 血量(带心) + 队伍名 + 护甲差 + 距离 + 装备 + 记分板行；
     * 物品 = 名称 + x数量；具名盔甲架 = 悬浮文本逐行。
     */
    private fun opaiView(entity: Entity): OpaiView? {
        if (entity is EntityPlayer) {
            if (!opaiPlayers) return null
            val self = mc.thePlayer ?: return null

            val parts = ArrayList<OpaiPart>(4)
            val health = opaiActualHealth(entity)
            parts += OpaiPart(health.toString(), opaiHealthColor(health), true)
            parts += OpaiPart(opaiFormattedName(entity), OPAI_WHITE, false)
            if (opaiArmorDifference) parts += opaiArmorPart(self.totalArmorValue, entity.totalArmorValue)
            parts += OpaiPart(Math.round(self.getDistanceToEntity(entity)).toString() + "m", OPAI_DISTANCE, false)

            val equipment = if (opaiPlayerArmor) opaiEquipment(entity) else emptyList()
            return OpaiView(parts, equipment, opaiBelowNameScore(entity), false)
        }

        if (entity is EntityItem) {
            if (!opaiItems) return null
            val stack = entity.entityItem ?: return null
            if (stack.stackSize <= 0) return null
            return OpaiView(
                listOf(OpaiPart(stack.displayName, OPAI_WHITE, false), OpaiPart("x" + stack.stackSize, OPAI_COUNT, false)),
                emptyList(), null, false
            )
        }

        // FloatingText：Samsara 接受 Display.TextDisplay / 具名可见盔甲架 → 1.8.9 后者
        if (opaiFloatingText && entity is EntityArmorStand && entity.alwaysRenderNameTag) {
            val text = entity.customNameTag
            if (text.isNullOrEmpty()) return null
            return OpaiView(text.split("\n").map { OpaiPart(it, OPAI_WHITE, false) }, emptyList(), null, true)
        }

        return null
    }

    /**
     * Samsara formattedName：队伍格式化名字基础上叠加状态色 ——
     * bot §3（深青）/ 隐身 §6（金）/ 潜行 §4（深红）/ 好友 §b（天蓝）。
     */
    private fun opaiFormattedName(player: EntityPlayer): String {
        val teamName = player.displayName.formattedText
        val statusColor = when {
            isBot(player) -> "§3"
            player.isInvisible -> "§6"
            player.isSneaking -> "§4"
            friendsConfig.friends.any { it.playerName == player.name } -> "§b"
            else -> return teamName
        }
        return statusColor + ColorUtils.stripColor(teamName)
    }

    /** Samsara actualHealth：血量目标记分板值优先，否则 血量 + 伤害吸收。 */
    private fun opaiActualHealth(player: EntityPlayer): Int {
        val scoreboard = player.worldScoreboard
        val objective = scoreboard.getObjectiveInDisplaySlot(2)
        if (objective != null && opaiIsHealthObjective(objective.displayName)) {
            return maxOf(0, scoreboard.getValueFromObjective(player.name, objective).scorePoints)
        }
        return maxOf(0, (player.health + player.absorptionAmount).roundToInt())
    }

    /** Samsara isHealthObjective（❤ / hp / health / lives）。 */
    private fun opaiIsHealthObjective(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        val lower = name.lowercase()
        return "❤" in lower || "hp" in lower || "health" in lower || "lives" in lower
    }

    /** Samsara score：低于名字的记分板行（非血量目标）。 */
    private fun opaiBelowNameScore(player: EntityPlayer): String? {
        val scoreboard = player.worldScoreboard
        val objective = scoreboard.getObjectiveInDisplaySlot(2) ?: return null
        if (opaiIsHealthObjective(objective.displayName)) return null
        val value = scoreboard.getValueFromObjective(player.name, objective).scorePoints
        return "$value ${objective.displayName}"
    }

    /** Samsara equipment：主手 → 头 → 胸 → 腿 → 脚（1.8.9 无副手槽），仅非空。 */
    private fun opaiEquipment(player: EntityPlayer): List<ItemStack> = listOf(
        player.heldItem,
        player.getCurrentArmor(3),
        player.getCurrentArmor(2),
        player.getCurrentArmor(1),
        player.getCurrentArmor(0)
    ).filterNotNull().filter { it.stackSize > 0 }.map { it.copy() }

    /** Samsara Geometry.armorPart："=" 橙 / 正差 绿 / 负差 红。 */
    private fun opaiArmorPart(local: Int, target: Int): OpaiPart {
        val difference = local - target
        return OpaiPart(
            if (difference == 0) "=" else difference.toString(),
            when {
                difference == 0 -> OPAI_EQUAL
                difference > 0 -> OPAI_GREEN
                else -> OPAI_RED
            },
            false
        )
    }

    /** Samsara Geometry.healthColor：>=14 绿 / >=8 黄 / 其余红。 */
    private fun opaiHealthColor(health: Int) = when {
        health >= 14 -> OPAI_GREEN
        health >= 8 -> OPAI_YELLOW
        else -> OPAI_RED
    }

    // ---------------- 布局（Samsara Geometry.layout） ----------------
    private fun opaiLayout(parts: List<OpaiPart>, padding: Float): OpaiLayout {
        val capsules = ArrayList<OpaiCapsule>(parts.size)
        var x = 0f
        for (part in parts) {
            val width = padding * 2f + opaiFont.getStringWidth(part.text) + (if (part.heart) 16f else 0f)
            capsules += OpaiCapsule(part, x, width)
            x += width + OPAI_GAP
        }
        return OpaiLayout(capsules, (x - OPAI_GAP).coerceAtLeast(0f))
    }

    // ---------------- 绘制细节（Samsara Painter.paintCapsule / NativeSurface.panel / item / score） ----------------
    private fun opaiDrawTag(tag: OpaiProjected) {
        opaiCurrentTag = tag
        val view = tag.view
        val padding = if (view.floating) OPAI_FLOATING_PADDING else OPAI_PADDING
        val height = opaiTagFontHeight + padding * 2f
        val top = -height / 2f
        val lay = opaiLayout(view.parts, padding)

        if (view.floating) {
            // 悬浮文本：逐行居中堆叠（Samsara FLOATING_HEIGHT 分支）
            for ((index, capsule) in lay.capsules.withIndex()) {
                opaiPaintCapsule(capsule, -capsule.width / 2f, top + index * (height + OPAI_GAP), height, padding, tag.opacity)
            }
        } else {
            // 水平：胶囊连排整体居中（Samsara horizontal 分支）
            for (capsule in lay.capsules) {
                opaiPaintCapsule(capsule, capsule.x - lay.width / 2f, top, height, padding, tag.opacity)
            }
        }

        // 装备图标 / 记分板行均位于胶囊上方（Samsara top - 36）；两者同时存在时错开避免重叠
        if (view.equipment.isNotEmpty()) opaiDrawEquipment(view.equipment, top - OPAI_EQUIPMENT_TOP)
        if (view.score != null) {
            val scoreY = if (view.equipment.isEmpty()) top - OPAI_EQUIPMENT_TOP else top - OPAI_EQUIPMENT_TOP - OPAI_EQUIPMENT_PITCH
            opaiFont.drawCenteredString(view.score, 0f, scoreY, opaiTint(OPAI_WHITE, tag.opacity), true)
        }
    }

    private fun opaiPaintCapsule(capsule: OpaiCapsule, x: Float, y: Float, height: Float, padding: Float, opacity: Float) {
        // Samsara shader 开启时为每个胶囊注册背景模糊（HudBackdrop.widget，屏幕 AABB = 名单位置 + 局部×缩放）
        if (opaiShader && opacity > 0f) {
            val tag = opaiCurrentTag
            if (tag != null) {
                HudBlur.blur(
                    tag.x + x * opaiScale, tag.y + y * opaiScale,
                    tag.x + (x + capsule.width) * opaiScale, tag.y + (y + height) * opaiScale
                ) {
                    drawRoundedRect(x, y, x + capsule.width, y + height, -1, OPAI_RADIUS)
                }
            }
        }
        opaiDrawPanel(x, y, capsule.width, height, opacity)

        var textX = x + padding
        if (capsule.part.heart) {
            opaiDrawHeart(textX + 1.25f, y + (height - OPAI_HEART_HEIGHT) / 2f, opaiTint(capsule.part.color, opacity))
            textX += 16f
        }

        val color = opaiTint(capsule.part.color, opacity)
        if (color ushr 24 >= 4) {
            opaiFont.drawString(capsule.part.text, textX, y + (height - opaiTagFontHeight) / 2f + 2f, color, true)
        }
    }

    /** Samsara NativeSurface.panel：shader = 9-slice 阴影贴图 + 深胶囊贴图，否则单层半透明胶囊贴图。 */
    private fun opaiDrawPanel(x: Float, y: Float, width: Float, height: Float, opacity: Float) {
        if (opaiShader) {
            // Samsara mask("shadow", x-8, y-8, w+16, h+16, 0x80000000)
            HudSlices.nametagMask(
                true, x - OPAI_SHADOW_OFFSET, y - OPAI_SHADOW_OFFSET,
                width + OPAI_SHADOW_OFFSET * 2f, height + OPAI_SHADOW_OFFSET * 2f,
                OPAI_RADIUS, opaiTint(OPAI_SHADOW_COLOR, opacity)
            )
        }
        HudSlices.nametagMask(
            false, x, y, width, height, OPAI_RADIUS,
            opaiTint(if (opaiShader) OPAI_CAPSULE_SHADER_COLOR else OPAI_CAPSULE_PLAIN_COLOR, opacity)
        )
    }

    /** 装备图标行：Samsara item scale 1.75、间距 32，居中排布（1.8.9 GUI 物件渲染）。 */
    private fun opaiDrawEquipment(equipment: List<ItemStack>, top: Float) {
        val x0 = -(equipment.size * OPAI_EQUIPMENT_PITCH - 4f) / 2f
        RenderHelper.enableGUIStandardItemLighting()
        for ((index, stack) in equipment.withIndex()) {
            pushMatrix()
            enableRescaleNormal()
            translate(x0 + index * OPAI_EQUIPMENT_PITCH, top, 0f)
            scale(OPAI_EQUIPMENT_ITEM_SCALE, OPAI_EQUIPMENT_ITEM_SCALE, 1f)
            mc.renderItem.renderItemAndEffectIntoGUI(stack, 0, 0)
            mc.renderItem.renderItemOverlays(mc.fontRendererObj, stack, 0, 0)
            popMatrix()
        }
        RenderHelper.disableStandardItemLighting()
        disableRescaleNormal()
        enableBlend()
        tryBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, 1, 0)
        enableTexture2D()
        color(1f, 1f, 1f, 1f)
    }

    /**
     * 心形（Samsara heart.png 的程序化近似）：经典心形参数曲线
     * x = 16sin³t, y = 13cost − 5cos2t − 2cos3t − cos4t，三角形扇填充并按健康色着色。
     */
    private fun opaiDrawHeart(x: Float, y: Float, argb: Int) {
        val alpha = (argb ushr 24 and 0xFF) / 255f
        if (alpha <= 0f) return
        val red = (argb ushr 16 and 0xFF) / 255f
        val green = (argb ushr 8 and 0xFF) / 255f
        val blue = (argb and 0xFF) / 255f

        val cx = x + OPAI_HEART_WIDTH / 2f
        val cy = y + OPAI_HEART_HEIGHT / 2f
        val sx = (OPAI_HEART_WIDTH / 32f).toDouble()
        val sy = (OPAI_HEART_HEIGHT / 29f).toDouble()

        enableBlend()
        tryBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, 1, 0)
        disableTexture2D()
        color(1f, 1f, 1f, 1f)

        val tessellator = Tessellator.getInstance()
        val buffer = tessellator.worldRenderer
        buffer.begin(GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR)
        buffer.pos(cx.toDouble(), cy.toDouble(), 0.0).color(red, green, blue, alpha).endVertex()
        for (i in 0 until OPAI_HEART_SEGMENTS) {
            val t = i * 2.0 * Math.PI / OPAI_HEART_SEGMENTS
            val px = 16.0 * Math.sin(t).pow(3)
            val py = 13.0 * Math.cos(t) - 5.0 * Math.cos(2 * t) - 2.0 * Math.cos(3 * t) - Math.cos(4 * t)
            buffer.pos(cx + px * sx, cy - (py + 2.5) * sy, 0.0).color(red, green, blue, alpha).endVertex()
        }
        tessellator.draw()

        enableTexture2D()
        disableBlend()
    }

    // ---------------- 工具（Samsara Geometry.opacity / NativeSurface.tint） ----------------

    /** 屏幕左右各 12.5% 边缘 smoothstep 淡出。 */
    private fun opaiEdgeOpacity(x: Float, width: Float): Float {
        if (!x.isFinite() || width <= 0f) return 0f
        val edge = abs(x * 2f / width - 1f)
        val t = ((edge - 0.75f) / 0.25f).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }

    /** 整体透明度乘进颜色 alpha（Samsara tint）。 */
    private fun opaiTint(argb: Int, opacity: Float) =
        ((argb ushr 24) * opacity).roundToInt() shl 24 or (argb and 0xFFFFFF)

    // ==================================================================
    // Moon 样式：moon-rebase NametagsModule 迁移（原版风公告栏名牌）
    // ==================================================================
    private val MOON_BG_COLOR = Color(0, 0, 0, 120).rgb
    private val MOON_INFO_COLOR = 0xFFC0C0C0.toInt()

    /** moon targets 过滤：隐身优先，其次 玩家/动物/其余生物。 */
    private fun moonApplies(entity: Entity): Boolean {
        if (entity.isInvisible() && moonInvisible) return true
        if (entity is EntityPlayer) return moonPlayers
        if (entity is EntityAnimal) return moonAnimals
        return entity is EntityLivingBase && moonMobs
    }

    private val moonRender3D = handler<Render3DEvent> {
        if (mode != "Moon") return@handler
        val world = mc.theWorld ?: return@handler
        for (entity in world.loadedEntityList) {
            if (entity !is EntityLivingBase || entity.isDead) continue
            if (!moonApplies(entity)) continue
            moonRenderNameTag(entity)
        }
    }

    /**
     * 原版风名牌：名字（可选 信息行 "X HP  Ym"），圆角/方形深色背景，
     * SeparateInformation 开启时信息独立成行，否则同行灰色显示。
     */
    private fun moonRenderNameTag(entity: EntityLivingBase) {
        val thePlayer = mc.thePlayer ?: return
        val f = moonFont
        val name = entity.displayName?.unformattedText ?: return
        if (name.isEmpty()) return

        val info = buildString {
            if (moonHealth) append(Math.round(entity.health).toString() + " HP")
            if (moonDistance) {
                if (isNotEmpty()) append("  ")
                append(Math.round(thePlayer.getDistanceToEntity(entity)).toString() + "m")
            }
        }

        glPushMatrix()

        val renderManager = mc.renderManager
        val rotateX = if (mc.gameSettings.thirdPersonView == 2) -1.0f else 1.0f
        val (x, y, z) = entity.interpolatedPosition(entity.lastTickPos) - renderManager.renderPos
        glTranslated(x, y + entity.eyeHeight.toDouble() + 0.55, z)
        glRotatef(-renderManager.playerViewY, 0F, 1F, 0F)
        glRotatef(renderManager.playerViewX * rotateX, 1F, 0F, 0F)

        // Disable lightning and depth test
        disableGlCap(GL_LIGHTING, GL_DEPTH_TEST)
        enableGlCap(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        val distance = thePlayer.getDistanceToEntity(entity)
        val scale = ((distance / 4F).coerceAtLeast(1F) / 150F) * 1.2F
        glScalef(-scale, -scale, scale)

        val nameW = f.getStringWidth(name).toFloat()
        val infoW = if (info.isEmpty()) 0F else f.getStringWidth(info).toFloat()
        val gap = if (info.isEmpty()) 0F else f.getStringWidth("  ").toFloat()
        val lineH = ((f as? GameFontRenderer)?.fontHeight ?: f.FONT_HEIGHT).toFloat()

        if (moonSeparateInfo && info.isNotEmpty()) {
            // 信息独立成行：两行居中堆叠
            val w = max(nameW, infoW)
            val h = lineH * 2F + 3F
            if (moonRound) drawRoundedRect(-w / 2F - 3F, -3F, w / 2F + 3F, h, MOON_BG_COLOR, 3F)
            else quickDrawRect(-w / 2F - 3F, -3F, w / 2F + 3F, h, MOON_BG_COLOR)
            f.drawString(name, -nameW / 2F, 0F, 0xFFFFFF, true)
            f.drawString(info, -infoW / 2F, lineH + 2F, MOON_INFO_COLOR, true)
        } else {
            // 同行：名字 + 两个空格 + 灰色信息
            val w = nameW + gap + infoW
            if (moonRound) drawRoundedRect(-w / 2F - 3F, -3F, w / 2F + 3F, lineH + 3F, MOON_BG_COLOR, 3F)
            else quickDrawRect(-w / 2F - 3F, -3F, w / 2F + 3F, lineH + 3F, MOON_BG_COLOR)
            var tx = -w / 2F
            f.drawString(name, tx, 0F, 0xFFFFFF, true)
            tx += nameW + gap
            if (info.isNotEmpty()) f.drawString(info, tx, 0F, MOON_INFO_COLOR, true)
        }

        resetCaps()
        resetColor()
        glColor4f(1F, 1F, 1F, 1F)

        glPopMatrix()
    }
}
