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
import net.airplus.utils.render.RenderUtils
import net.airplus.utils.render.RenderUtils.disableGlCap
import net.airplus.utils.render.RenderUtils.drawTexturedModalRect
import net.airplus.utils.render.RenderUtils.enableGlCap
import net.airplus.utils.render.RenderUtils.quickDrawBorderedRect
import net.airplus.utils.render.RenderUtils.quickDrawRect
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.airplus.utils.render.RenderUtils.resetCaps
import net.airplus.utils.render.RoundedUtil
import net.airplus.utils.rotation.RotationUtils.isEntityHeightVisible
import net.minecraft.client.entity.AbstractClientPlayer
import net.minecraft.client.entity.EntityPlayerSP
import net.minecraft.client.gui.Gui
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager.*
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.client.renderer.RenderHelper
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.enchantment.Enchantment
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.item.EntityArmorStand
import net.minecraft.entity.item.EntityItem
import net.minecraft.entity.monster.EntityMob
import net.minecraft.entity.passive.EntityAnimal
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.entity.player.EnumPlayerModelParts
import net.minecraft.init.Items
import net.minecraft.item.ItemArmor
import net.minecraft.item.ItemAxe
import net.minecraft.item.ItemBow
import net.minecraft.item.ItemStack
import net.minecraft.item.ItemSword
import net.minecraft.potion.Potion
import net.minecraft.util.AxisAlignedBB
import net.minecraft.util.EnumChatFormatting
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.GL11.*
import org.lwjgl.util.vector.Vector4f
import java.awt.Color
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*
import kotlin.math.abs
import kotlin.math.ceil
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
 * - Apple：从 moonlight 移植的圆角面板名牌（头像 + 血量环 + 装备行）
 *
 * 模块开启时由 MixinRendererLivingEntity#canRenderName 取消原版玩家名牌，各样式均自绘名牌。
 */
object NameTags : Module("NameTags", Category.RENDER) {

    // ================= 样式选择（置于最上方） =================
    private val mode by choices("Mode", arrayOf("Legacy", "Xinxin", "Opai", "Moon", "Apple"), "Legacy")

    /** 供 MixinRendererLivingEntity 使用：模块开启时取消原版玩家名牌（所有样式均自绘），关闭时恢复原版行为。 */
    val cancelVanillaName: Boolean
        get() = handleEvents()

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
    private val moonBgColor by color("MoonBgColor", Color.BLACK.withAlpha(120)) { mode == "Moon" }
    private val moonBgBlur by boolean("MoonBgBlur", false) { mode == "Moon" }
    private val moonFont by font("MoonFont", Fonts.fontRegular35) { mode == "Moon" }

    // ================= Apple 样式设置（moonlight，原 Apple 面板名牌） =================
    private val appleScale by float("AppleScale", 1F, 0.3F..2.5F) { mode == "Apple" }
    private val appleAvatar by boolean("AppleAvatar", true) { mode == "Apple" }
    private val appleHealthRing by boolean("AppleHealthRing", true) { mode == "Apple" }
    private val appleTeamColors by boolean("AppleTeamColors", true) { mode == "Apple" }
    private val appleDistance by boolean("AppleDistance", false) { mode == "Apple" }
    private val appleArmor by boolean("AppleArmor", true) { mode == "Apple" }
    private val appleHeldItem by boolean("AppleHeldItem", true) { mode == "Apple" && appleArmor }
    private val appleDurability by boolean("AppleDurability", true) { mode == "Apple" && appleArmor }
    private val appleEnchantLevels by boolean("AppleEnchantLevels", true) { mode == "Apple" && appleArmor }
    private val appleDetailRange by float("AppleDetailRange", 40F, 8F..256F) { mode == "Apple" && appleArmor }
    private val appleBackground by boolean("AppleBackground", true) { mode == "Apple" }
    private val appleBackgroundAlpha by float("AppleBackgroundAlpha", 215F, 60F..255F) { mode == "Apple" && appleBackground }

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

        val renderManager = mc.renderManager
        val rotateX = if (mc.gameSettings.thirdPersonView == 2) -1.0f else 1.0f
        val (x, y, z) = entity.interpolatedPosition(entity.lastTickPos) - renderManager.renderPos

        val nameW = f.getStringWidth(name).toFloat()
        val infoW = if (info.isEmpty()) 0F else f.getStringWidth(info).toFloat()
        val gap = if (info.isEmpty()) 0F else f.getStringWidth("  ").toFloat()
        val lineH = ((f as? GameFontRenderer)?.fontHeight ?: f.FONT_HEIGHT).toFloat()

        val twoLine = moonSeparateInfo && info.isNotEmpty()
        val bgHalfW = (if (twoLine) max(nameW, infoW) else nameW + gap + infoW) / 2F + 3F
        val bgH = if (twoLine) lineH * 2F + 3F else lineH + 3F

        // Background Blur：公告板背景矩形四角投影为屏幕 AABB（需在 billboard 变换前、相机矩阵阶段调用）
        val distance = thePlayer.getDistanceToEntity(entity)
        val scale = ((distance / 4F).coerceAtLeast(1F) / 150F) * 1.2F
        val blurBox = if (moonBgBlur)
            moonBlurBox(x, y + entity.eyeHeight.toDouble() + 0.55, z, bgHalfW, bgH, scale, rotateX) else null

        glPushMatrix()

        glTranslated(x, y + entity.eyeHeight.toDouble() + 0.55, z)
        glRotatef(-renderManager.playerViewY, 0F, 1F, 0F)
        glRotatef(renderManager.playerViewX * rotateX, 1F, 0F, 0F)

        // Disable lightning and depth test
        disableGlCap(GL_LIGHTING, GL_DEPTH_TEST)
        enableGlCap(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        glScalef(-scale, -scale, scale)

        // Opai 同款背景模糊：AABB 为屏幕坐标，遮罩在当前公告板局部矩阵下绘制
        if (blurBox != null) {
            HudBlur.blur(blurBox[0], blurBox[1], blurBox[2], blurBox[3]) {
                if (moonRound) drawRoundedRect(-bgHalfW, -3F, bgHalfW, bgH, -1, 3F)
                else quickDrawRect(-bgHalfW, -3F, bgHalfW, bgH, -1)
            }
        }

        if (twoLine) {
            // 信息独立成行：两行居中堆叠
            val bg = moonBgColor.rgb
            if (moonRound) drawRoundedRect(-bgHalfW, -3F, bgHalfW, bgH, bg, 3F)
            else quickDrawRect(-bgHalfW, -3F, bgHalfW, bgH, bg)
            f.drawString(name, -nameW / 2F, 0F, 0xFFFFFF, true)
            f.drawString(info, -infoW / 2F, lineH + 2F, MOON_INFO_COLOR, true)
        } else {
            // 同行：名字 + 两个空格 + 灰色信息
            val bg = moonBgColor.rgb
            if (moonRound) drawRoundedRect(-bgHalfW, -3F, bgHalfW, bgH, bg, 3F)
            else quickDrawRect(-bgHalfW, -3F, bgHalfW, bgH, bg)
            var tx = -bgHalfW + 3F
            f.drawString(name, tx, 0F, 0xFFFFFF, true)
            tx += nameW + gap
            if (info.isNotEmpty()) f.drawString(info, tx, 0F, MOON_INFO_COLOR, true)
        }

        resetCaps()
        resetColor()
        glColor4f(1F, 1F, 1F, 1F)

        glPopMatrix()
    }

    /**
     * Moon 名牌 Background Blur：按 glScalef(-scale,-scale,scale) 后的公告板矩形四角做
     * 世界空间 → 屏幕空间投影（与 billboard 旋转顺序 RotY(playerViewY) → RotX(playerViewX) 一致），
     * 返回缩放 GUI 坐标下的 AABB（x0, y0, x1, y1）；任一角被裁剪则放弃模糊。
     */
    private fun moonBlurBox(
        tx: Double, ty: Double, tz: Double, halfW: Float, height: Float, scale: Float, rotateX: Float
    ): FloatArray? {
        val sr = ScaledResolution(mc)
        val rm = mc.renderManager
        val cosB = Math.cos(Math.toRadians(-rm.playerViewY.toDouble())).toFloat()
        val sinB = Math.sin(Math.toRadians(-rm.playerViewY.toDouble())).toFloat()
        val angleA = Math.toRadians(rm.playerViewX * rotateX.toDouble())
        val cosA = Math.cos(angleA).toFloat()
        val sinA = Math.sin(angleA).toFloat()

        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        val lx = floatArrayOf(-halfW, halfW, halfW, -halfW)
        val ly = floatArrayOf(-3F, -3F, height, height)
        for (i in 0 until 4) {
            // 与 glScalef(-scale, -scale, scale) 一致的局部缩放
            val sx = -lx[i] * scale
            val sy = -ly[i] * scale
            val wx = tx + (sx * cosB + sy * sinA * sinB).toDouble()
            val wy = ty + (sy * cosA).toDouble()
            val wz = tz + (-sx * sinB + sy * sinA * cosB).toDouble()
            val p = ESPUtil.projectOn2D(wx.toFloat(), wy.toFloat(), wz.toFloat(), sr.scaleFactor) ?: return null
            if (p.z < 0f || p.z > 1f) return null
            x0 = minOf(x0, p.x); x1 = maxOf(x1, p.x)
            y0 = minOf(y0, p.y); y1 = maxOf(y1, p.y)
        }
        return floatArrayOf(x0, y0, x1, y1)
    }

    // ==================================================================
    // Apple 样式：moonlight Apple 名牌（圆角面板 + 头像 + 血量弧 + 装备行）
    //
    // 移植说明：
    // - moonlight 用 font(Fonts.interX, size, scale) 动态字号；airplus 字体固定尺寸，
    //   故以 appleUnit = 字体 FONT_HEIGHT / 13（moonlight 基准字号）放大全部几何常量，
    //   再把整个名牌块放入 glScale(appleScale) 的局部坐标系绘制（opai 同款手法），
    //   字形随矩阵缩放，比例与 moonlight 一致。
    // - moonlight 依赖 ItemDetector 的金苹果统计徽章：airplus 无对应模块，已移除。
    // - 透过墙壁可见：按 moonlight 逻辑不做遮挡检测（纯投影渲染）。
    // ==================================================================

    // ---------------- Apple 字体 / 颜色 ----------------
    private val appleNameFont get() = Fonts.fontSFApple35
    private val appleValueFont get() = Fonts.fontSFApple35
    private val appleInfoFont get() = Fonts.fontRegular30
    private val appleBadgeFont get() = Fonts.fontUsans20

    /** moonlight 基准 13px 字号 → airplus 字体的几何放大系数。 */
    private val appleUnit get() = appleNameFont.FONT_HEIGHT / 13F

    private val APPLE_IOS_GREEN = Color(52, 199, 89)
    private val APPLE_IOS_ORANGE = Color(255, 159, 10)
    private val APPLE_IOS_RED = Color(255, 69, 58)
    private val APPLE_TEXT_SECONDARY = Color(255, 255, 255, 140).rgb
    private val APPLE_RING_TRACK = Color(255, 255, 255, 40)
    private val APPLE_BADGE_SHADOW = Color(0, 0, 0, 210).rgb

    // ---------------- Apple 状态 ----------------
    private class AppleTagState(initialHealth: Float) {
        var health = initialHealth
        var offset = 0F
        var placed = false
        var detail = true
    }

    private class AppleTag(val player: EntityPlayer, val state: AppleTagState) {
        var target = false
        var self = false
        var distance = 0F
        var name = ""
        var hpText = ""
        var infoText: String? = null
        var nameW = 0F
        var hpW = 0F
        var infoW = 0F
        var healthPct = 0F
        var livePct = 1F
        val items = ArrayList<ItemStack>(5)
        var inset = 0F
        var avatarSize = 0F
        var nameX = 0F
        var rightPad = 0F
        var panelW = 0F
        var panelH = 0F
        var icon = 0F
        var iconGap = 0F
        var rowPad = 0F
        var rowW = 0F
        var rowH = 0F
        var rowGap = 0F
        var blockW = 0F
        var blockH = 0F
        var centerX = 0F
        var naturalTop = 0F
    }

    private val appleEntityPosMap = HashMap<EntityPlayer, FloatArray>()
    private val appleStateMap = HashMap<EntityPlayer, AppleTagState>()
    private val appleLayout = ArrayList<AppleTag>()
    private var appleLayoutDirty = true
    private var appleScreenWidth = 0F
    private var appleScreenHeight = 0F
    private var applePixelScreen = 1F

    private val appleWorldHandler = handler<WorldEvent> {
        appleEntityPosMap.clear()
        appleStateMap.clear()
        appleLayout.clear()
        appleLayoutDirty = true
    }

    /** 3D 阶段：8 点 AABB 投影（moonlight projectPlayers）。 */
    private val appleRender3D = handler<Render3DEvent> {
        if (mode != "Apple") return@handler
        appleEntityPosMap.clear()
        appleLayoutDirty = true

        val world = mc.theWorld ?: return@handler
        val sr = ScaledResolution(mc)
        appleScreenWidth = sr.scaledWidth.toFloat()
        appleScreenHeight = sr.scaledHeight.toFloat()
        applePixelScreen = 1F / max(1, sr.scaleFactor)

        val renderPos = mc.renderManager.renderPos
        for (player in world.playerEntities) {
            val (ix, iy, iz) = player.interpolatedPosition(player.lastTickPos) - renderPos
            val hw = player.width / 2.0
            val bb = AxisAlignedBB(
                ix - hw, iy, iz - hw,
                ix + hw, iy + player.height + (if (player.isSneaking) -0.2 else 0.1), iz + hw
            ).expand(0.1, 0.1, 0.1)

            var any = false
            var minXf = Float.MAX_VALUE
            var minYf = Float.MAX_VALUE
            var maxXf = -1F
            var maxYf = -1F
            for (corner in appleCorners(bb)) {
                val point = ESPUtil.projectOn2D(corner[0].toFloat(), corner[1].toFloat(), corner[2].toFloat(), sr.scaleFactor)
                    ?: continue
                if (point.z < 0F || point.z >= 1F) continue
                any = true
                minXf = minOf(minXf, point.x)
                minYf = minOf(minYf, point.y)
                maxXf = maxOf(maxXf, point.x)
                maxYf = maxOf(maxYf, point.y)
            }
            if (!any) continue

            appleEntityPosMap[player] = floatArrayOf(minXf, minYf, maxXf, maxYf)
        }
    }

    private fun appleCorners(bb: AxisAlignedBB): Array<DoubleArray> = arrayOf(
        doubleArrayOf(bb.minX, bb.minY, bb.minZ), doubleArrayOf(bb.minX, bb.maxY, bb.minZ),
        doubleArrayOf(bb.minX, bb.maxY, bb.maxZ), doubleArrayOf(bb.minX, bb.minY, bb.maxZ),
        doubleArrayOf(bb.maxX, bb.minY, bb.minZ), doubleArrayOf(bb.maxX, bb.maxY, bb.minZ),
        doubleArrayOf(bb.maxX, bb.maxY, bb.maxZ), doubleArrayOf(bb.maxX, bb.minY, bb.maxZ)
    )

    private val appleRender2D = handler<Render2DEvent> {
        if (mode != "Apple") return@handler
        if (appleEntityPosMap.isEmpty()) return@handler
        appleEnsureLayout()

        for (index in appleLayout.indices.reversed()) {
            appleRenderTag(appleLayout[index])
        }

        color(1F, 1F, 1F, 1F)
        enableTexture2D()
    }

    /** 布局 + 碰撞避让 + 位移动画（moonlight ensureLayout）。 */
    private fun appleEnsureLayout() {
        if (!appleLayoutDirty) return
        appleLayoutDirty = false
        appleLayout.clear()
        appleStateMap.keys.retainAll(appleEntityPosMap.keys)

        val s = appleScale
        val u = appleUnit
        val killAuraTarget = if (KillAura.handleEvents()) KillAura.target else null
        val tags = ArrayList<AppleTag>()
        for ((player, pos) in appleEntityPosMap) {
            if ((player !== mc.thePlayer || mc.gameSettings.thirdPersonView != 0) && player.isEntityAlive) {
                val state = appleStateMap.getOrPut(player) { AppleTagState(player.health) }
                tags += appleBuildTag(player, pos, state, player === killAuraTarget, s, u)
            }
        }

        tags.sortWith { a, b ->
            if (a.target != b.target) if (a.target) -1 else 1
            else if (a.self != b.self) if (a.self) 1 else -1
            else {
                val byDist = (a.distance / 2F).toInt().compareTo((b.distance / 2F).toInt())
                if (byDist != 0) byDist else a.player.entityId.compareTo(b.player.entityId)
            }
        }

        val placed = ArrayList<FloatArray>()
        val stackGap = 1.5F * u * s
        for (tag in tags) {
            val blockW = tag.blockW * s
            val blockH = tag.blockH * s
            val blockX = tag.centerX - blockW / 2F
            var top = tag.naturalTop
            var retry = true
            var iter = 0
            while (retry && iter++ < 64) {
                retry = false
                for (rect in placed) {
                    if (blockX < rect[0] + rect[2] && blockX + blockW > rect[0] &&
                        top < rect[1] + rect[3] && top + blockH > rect[1]
                    ) {
                        top = rect[1] - blockH - stackGap
                        retry = true
                    }
                }
            }

            placed += floatArrayOf(blockX, top, blockW, blockH)
            val target = top - tag.naturalTop
            if (!tag.state.placed) {
                tag.state.offset = target
                tag.state.placed = true
            } else {
                tag.state.offset = appleAnimate(tag.state.offset, target, 14F)
                if (abs(tag.state.offset - target) < 0.25F) tag.state.offset = target
            }

            val visTop = tag.naturalTop + tag.state.offset
            if (blockX + blockW >= 0F && blockX <= appleScreenWidth && visTop + blockH >= 0F && visTop <= appleScreenHeight) {
                appleLayout += tag
            }
        }
    }

    /** 构建单个名牌（moonlight buildTag）。 */
    private fun appleBuildTag(player: EntityPlayer, pos: FloatArray, state: AppleTagState, isTarget: Boolean, s: Float, u: Float): AppleTag {
        val tag = AppleTag(player, state)
        tag.target = isTarget
        tag.self = player === mc.thePlayer
        tag.distance = if (tag.self) 0F else mc.thePlayer.getDistanceToEntity(player)

        val formatted = player.displayName.formattedText
        val name = if (appleTeamColors) formatted else ColorUtils.stripColor(formatted)
        tag.name = name
        tag.nameW = appleNameFont.getStringWidth(name).toFloat()

        val maxHp = max(1F, player.maxHealth)
        state.health = appleAnimate(state.health, player.health, 8F)
        tag.healthPct = appleClamp01(state.health / maxHp)
        tag.livePct = appleClamp01(player.health / maxHp)
        tag.hpText = ceil(max(0F, player.health)).toInt().toString()
        tag.hpW = appleValueFont.getStringWidth(tag.hpText).toFloat()

        val absorption = ceil(player.absorptionAmount).toInt()
        var info = if (absorption > 0) "+$absorption" else ""
        if (appleDistance && !tag.self) {
            info = if (info.isEmpty()) "${tag.distance.roundToInt()}m" else "$info  ${tag.distance.roundToInt()}m"
        }
        tag.infoText = info.ifEmpty { null }
        tag.infoW = tag.infoText?.let { appleInfoFont.getStringWidth(it).toFloat() } ?: 0F

        appleLayoutPanel(tag, u)
        appleLayoutItemRow(tag, player, state, u)

        tag.centerX = pos[0] + (pos[2] - pos[0]) / 2F
        tag.blockW = max(tag.panelW, tag.rowW)
        tag.blockH = tag.panelH + (if (tag.rowW > 0F) tag.rowH + tag.rowGap else 0F)
        tag.naturalTop = pos[1] - 4F * u * s - tag.blockH * s
        return tag
    }

    /** 面板布局（moonlight layoutPanel）。 */
    private fun appleLayoutPanel(tag: AppleTag, u: Float) {
        tag.panelH = 20F * u
        tag.inset = 2F * u
        val hasAvatar = appleAvatar || appleHealthRing
        tag.avatarSize = if (hasAvatar) tag.panelH - tag.inset * 2F else 0F

        val left = if (hasAvatar) tag.inset + tag.avatarSize + 5F * u else 8F * u
        val right = 8F * u
        val hpInfoW = tag.hpW + (tag.infoText?.let { 3F * u + tag.infoW } ?: 0F)
        tag.nameX = left
        tag.panelW = max(left + tag.nameW + 8F * u + hpInfoW + right, 56F * u)
        tag.rightPad = right
    }

    /** 装备行布局（moonlight layoutItemRow，去掉金苹果统计）。 */
    private fun appleLayoutItemRow(tag: AppleTag, player: EntityPlayer, state: AppleTagState, u: Float) {
        val detail = tag.target || tag.self || tag.distance <= appleDetailRange + (if (state.detail) 2F else 0F)
        state.detail = detail
        if (!appleArmor || !detail) return

        if (appleHeldItem) player.heldItem?.let { tag.items += it }
        for (slot in 3 downTo 0) player.inventory.armorInventory[slot]?.let { tag.items += it }

        val count = tag.items.size
        if (count > 0) {
            tag.icon = 14F * u
            tag.iconGap = 3F * u
            tag.rowPad = 6F * u
            tag.rowH = tag.icon + 6F * u
            tag.rowW = tag.rowPad * 2F + count * tag.icon + (count - 1) * tag.iconGap
            tag.rowGap = 2.5F * u
        }
    }

    /** 渲染名牌（moonlight renderApple）：局部坐标系原点 = 名牌块底部中心。 */
    private fun appleRenderTag(tag: AppleTag) {
        val s = appleScale
        val u = appleUnit
        val pixel = applePixelScreen / s

        val bottomY = tag.naturalTop + tag.state.offset + tag.blockH * s
        pushMatrix()
        translate(tag.centerX, bottomY, 0F)
        scale(s, s, 1F)

        val bg = Color(0, 0, 0, appleBackgroundAlpha.roundToInt())
        val panelTop = -tag.panelH
        if (appleBackground) {
            RoundedUtil.drawRound(-tag.panelW / 2F, panelTop, tag.panelW, tag.panelH, tag.panelH / 2F, bg)
        }

        if (tag.avatarSize > 0F) {
            val ax = -tag.panelW / 2F + tag.inset + tag.avatarSize / 2F
            val ay = panelTop + tag.inset + tag.avatarSize / 2F
            val ringW = 1.6F * u
            var faceSize = tag.avatarSize
            if (appleHealthRing) {
                val radius = (tag.avatarSize - ringW) / 2F
                appleDrawArc(ax, ay, radius, ringW, 1F, APPLE_RING_TRACK, pixel)
                if (tag.healthPct > 0.005F) {
                    appleDrawArc(ax, ay, radius, ringW, tag.healthPct, appleRingColor(tag.livePct), pixel)
                }
                faceSize = tag.avatarSize - (ringW + 1.2F * u) * 2F
            }
            if (appleAvatar && faceSize > 2F) {
                appleDrawFace(tag.player, ax - faceSize / 2F, ay - faceSize / 2F, faceSize, tag.player.hurtTime / 10F, pixel)
            }
        }

        val nameY = panelTop + (tag.panelH - appleNameFont.FONT_HEIGHT) / 2F + 2F
        var rightX = -tag.panelW / 2F + tag.panelW - tag.rightPad
        appleNameFont.drawStringWithShadow(tag.name, -tag.panelW / 2F + tag.nameX, nameY, -1)

        val infoText = tag.infoText
        if (infoText != null) {
            rightX -= tag.infoW
            val infoY = panelTop + (tag.panelH - appleInfoFont.FONT_HEIGHT) / 2F + 2F
            appleInfoFont.drawString(infoText, rightX, infoY, APPLE_TEXT_SECONDARY)
            rightX -= 3F * u
        }

        val hpColor = if (tag.livePct <= 0.3F) APPLE_IOS_RED.rgb else -1
        appleValueFont.drawStringWithShadow(tag.hpText, rightX - tag.hpW, nameY, hpColor)

        if (tag.rowW > 0F) {
            val rowTop = panelTop - tag.rowGap - tag.rowH
            if (appleBackground) {
                RoundedUtil.drawRound(-tag.rowW / 2F, rowTop, tag.rowW, tag.rowH, tag.rowH / 2F, bg)
            }
            appleRenderItemRow(tag, rowTop, u)
        }

        popMatrix()
        color(1F, 1F, 1F, 1F)
    }

    /** 装备行绘制（moonlight renderItemRow）。 */
    private fun appleRenderItemRow(tag: AppleTag, rowTop: Float, u: Float) {
        val itemScale = tag.icon / 16F
        val itemY = rowTop + (tag.rowH - tag.icon) / 2F
        var x = -tag.rowW / 2F + tag.rowPad

        for (stack in tag.items) {
            appleRenderItemStack(stack, x, itemY, itemScale)
            disableDepth()
            disableLighting()

            if (appleEnchantLevels) {
                val enchantLevel = appleEnchantBadgeLevel(stack)
                val badgeText = when {
                    enchantLevel > 0 -> enchantLevel.toString()
                    stack.stackSize > 1 -> stack.stackSize.toString()
                    else -> null
                }
                if (badgeText != null) appleDrawBadge(appleBadgeFont, badgeText, x + tag.icon, itemY + tag.icon, u)
            }

            if (appleDurability && stack.isItemStackDamageable && stack.maxDamage > 0 && stack.itemDamage > 0) {
                val frac = appleClamp01((stack.maxDamage - stack.itemDamage).toFloat() / stack.maxDamage)
                val barW = tag.icon - 3F * u
                val barX = x + 1.5F * u
                val barY = itemY + tag.icon + 1F * u
                val barH = max(1F, 1.2F * u)
                RoundedUtil.drawRound(barX, barY, barW, barH, barH / 2F, Color(255, 255, 255, 40))
                RoundedUtil.drawRound(barX, barY, max(barW * frac, barH), barH, barH / 2F, appleDurabilityColor(frac))
            }

            x += tag.icon + tag.iconGap
        }
    }

    /** 物品渲染（moonlight RenderUtil.renderItemStack 的局部坐标等价实现）。 */
    private fun appleRenderItemStack(stack: ItemStack, x: Float, y: Float, scale: Float) {
        pushMatrix()
        translate(x, y, 0F)
        scale(scale, scale, 1F)
        enableRescaleNormal()
        enableBlend()
        tryBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, 1, 0)
        RenderHelper.enableGUIStandardItemLighting()
        mc.renderItem.renderItemAndEffectIntoGUI(stack, 0, 0)
        RenderHelper.disableStandardItemLighting()
        disableRescaleNormal()
        disableBlend()
        popMatrix()
    }

    /** 角标：黑描边 + 白字（moonlight drawBadge）。 */
    private fun appleDrawBadge(font: GameFontRenderer, text: String, xRight: Float, yBottom: Float, u: Float) {
        val x = xRight - font.getStringWidth(text) + 1F * u
        val y = yBottom - 0.5F * u - font.FONT_HEIGHT + 2F
        font.drawString(text, x - 0.5F, y, APPLE_BADGE_SHADOW)
        font.drawString(text, x + 0.5F, y, APPLE_BADGE_SHADOW)
        font.drawString(text, x, y - 0.5F, APPLE_BADGE_SHADOW)
        font.drawString(text, x, y + 0.5F, APPLE_BADGE_SHADOW)
        font.drawString(text, x, y, -1)
    }

    /** 血量弧：渐变边缘的圆弧带 + 圆头端点（moonlight drawArc）。 */
    private fun appleDrawArc(cx: Float, cy: Float, radius: Float, width: Float, pct: Float, color: Color, pixel: Float) {
        val sweep = 360F * appleClamp01(pct)
        val segments = max(8, ceil(sweep / 6F).toInt())
        val half = width / 2F
        val edge = pixel * 0.75F
        val radii = floatArrayOf(radius - half - edge, radius - half, radius + half, radius + half + edge)
        val alphas = floatArrayOf(0F, 1F, 1F, 0F)
        val red = color.red / 255F
        val green = color.green / 255F
        val blue = color.blue / 255F
        val alpha = color.alpha / 255F

        enableBlend()
        disableTexture2D()
        disableCull()
        tryBlendFuncSeparate(770, 771, 1, 0)
        shadeModel(GL_SMOOTH)

        for (band in 0..2) {
            glBegin(GL_QUAD_STRIP)
            for (i in 0..segments) {
                val angle = Math.toRadians(-90.0 + sweep * i / segments)
                val cos = Math.cos(angle).toFloat()
                val sin = Math.sin(angle).toFloat()
                glColor4f(red, green, blue, alpha * alphas[band])
                glVertex2f(cx + cos * radii[band], cy + sin * radii[band])
                glColor4f(red, green, blue, alpha * alphas[band + 1])
                glVertex2f(cx + cos * radii[band + 1], cy + sin * radii[band + 1])
            }
            glEnd()
        }

        shadeModel(GL_FLAT)
        enableTexture2D()
        enableCull()
        glColor4f(1F, 1F, 1F, 1F)
        resetColor()

        if (sweep < 359F) {
            val start = Math.toRadians(-90.0)
            val end = Math.toRadians((-90.0 + sweep).toDouble())
            RoundedUtil.drawRound(
                cx + Math.cos(start).toFloat() * radius - half, cy + Math.sin(start).toFloat() * radius - half,
                width, width, half, color
            )
            RoundedUtil.drawRound(
                cx + Math.cos(end).toFloat() * radius - half, cy + Math.sin(end).toFloat() * radius - half,
                width, width, half, color
            )
        }
    }

    /** 圆形皮肤头像（受击变红），软边缘 + 帽子层（moonlight drawFace/drawFaceLayer）。 */
    private fun appleDrawFace(player: EntityPlayer, x: Float, y: Float, size: Float, hurt: Float, pixel: Float) {
        if (player !is AbstractClientPlayer) return
        val brightness = 1F - 0.45F * appleClamp01(hurt)
        enableTexture2D()
        enableBlend()
        disableCull()
        disableAlpha()
        OpenGlHelper.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, 1, 0)
        shadeModel(GL_SMOOTH)
        mc.textureManager.bindTexture(player.locationSkin)
        appleDrawFaceLayer(x, y, size, 8F, brightness, pixel)
        if (player.isWearing(EnumPlayerModelParts.HAT)) {
            appleDrawFaceLayer(x, y, size, 40F, brightness, pixel)
        }
        shadeModel(GL_FLAT)
        enableAlpha()
        enableCull()
        glColor4f(1F, 1F, 1F, 1F)
        resetColor()
    }

    private fun appleDrawFaceLayer(x: Float, y: Float, size: Float, layer: Float, brightness: Float, pixel: Float) {
        val segments = 40
        val half = size / 2F
        val cx = x + half
        val cy = y + half
        val edge = minOf(pixel, half * 0.25F)
        val inner = half - edge
        val u0 = layer / 64F
        val v0 = 0.125F
        val uSize = 0.125F
        val ringRatio = inner / half

        glBegin(GL_TRIANGLE_FAN)
        glColor4f(1F, brightness, brightness, 1F)
        glTexCoord2f(u0 + uSize * 0.5F, v0 + uSize * 0.5F)
        glVertex2f(cx, cy)
        for (i in 0..segments) {
            val angle = (Math.PI * 2) * i / segments
            val cos = Math.cos(angle).toFloat()
            val sin = Math.sin(angle).toFloat()
            glTexCoord2f(u0 + uSize * (0.5F + cos * 0.5F * ringRatio), v0 + uSize * (0.5F + sin * 0.5F * ringRatio))
            glVertex2f(cx + cos * inner, cy + sin * inner)
        }
        glEnd()

        glBegin(GL_QUAD_STRIP)
        for (i in 0..segments) {
            val angle = (Math.PI * 2) * i / segments
            val cos = Math.cos(angle).toFloat()
            val sin = Math.sin(angle).toFloat()
            glColor4f(1F, brightness, brightness, 1F)
            glTexCoord2f(u0 + uSize * (0.5F + cos * 0.5F * ringRatio), v0 + uSize * (0.5F + sin * 0.5F * ringRatio))
            glVertex2f(cx + cos * inner, cy + sin * inner)
            glColor4f(1F, brightness, brightness, 0F)
            glTexCoord2f(u0 + uSize * (0.5F + cos * 0.5F), v0 + uSize * (0.5F + sin * 0.5F))
            glVertex2f(cx + cos * half, cy + sin * half)
        }
        glEnd()
    }

    // ---------------- Apple 工具 ----------------

    /** moonlight RenderUtil.animate（帧时间 lerp）：speed ≈ 每秒收敛次数。 */
    private fun appleAnimate(current: Float, target: Float, speed: Float): Float {
        val factor = (RenderUtils.deltaTime / 1000F * speed).coerceIn(0F, 1F)
        return current + (target - current) * factor
    }

    private fun appleClamp01(value: Float) = value.coerceIn(0F, 1F)

    private fun appleRingColor(pct: Float): Color =
        if (pct > 0.5F) APPLE_IOS_GREEN else if (pct > 0.25F) APPLE_IOS_ORANGE else APPLE_IOS_RED

    private fun appleDurabilityColor(pct: Float): Color =
        if (pct > 0.5F) Color(255, 255, 255, 220) else if (pct > 0.25F) APPLE_IOS_ORANGE else APPLE_IOS_RED

    private fun appleEnchantBadgeLevel(stack: ItemStack): Int = when (stack.item) {
        is ItemArmor -> EnchantmentHelper.getEnchantmentLevel(Enchantment.protection.effectId, stack)
        is ItemSword, is ItemAxe -> EnchantmentHelper.getEnchantmentLevel(Enchantment.sharpness.effectId, stack)
        is ItemBow -> EnchantmentHelper.getEnchantmentLevel(Enchantment.power.effectId, stack)
        else -> 0
    }
}
