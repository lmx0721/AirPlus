/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Ported from Hanabi's EnvyHUD module (cn.hanabi.modules.modules.render.EnvyHUD).
 * Contains: module arraylist, custom hotbar and keyboard-driven TabGUI.
 * Sound / hit sound / name fix features are intentionally excluded - use the
 * AirPlus Sound module for toggle sounds instead.
 * Logo / potion status rendering was moved to the "Logo" / "Potions" HUD elements.
 */
package net.airplus.features.module.modules.render

import net.airplus.event.KeyEvent
import net.airplus.event.Render2DEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.ModuleManager
import net.airplus.config.BoolValue
import net.airplus.config.FloatValue
import net.airplus.config.FontValue
import net.airplus.config.IntValue
import net.airplus.config.ListValue
import net.airplus.config.Value
import net.airplus.utils.render.ColorSettingsInteger
import net.airplus.utils.render.ColorUtils
import net.airplus.utils.render.ColorUtils.withAlpha
import net.airplus.utils.render.RenderUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Gui
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.RenderHelper
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11
import java.awt.Color
import kotlin.math.abs

object HanabiHUD : Module("HanabiHUD", Category.RENDER, gameDetecting = false) {

    private val MAIN = Color(33, 170, 47).rgb
    private val SECONDARY = Color(23, 23, 23).rgb

    // HUD settings (aligned with Hanabi's HUD module)
    val hudMode by choices("HudMode", arrayOf("Classic", "Simple"), "Classic")
    private val arraylist by boolean("ArrayList", true)
    private val arraylistFade by boolean("Fade", false) { arraylist }
    private val arrayColor by choices("ArrayList Color", arrayOf("Random", "Theme", "Rainbow"), "Rainbow") { arraylist }
    private val rainbowSpeed by float("ArrayList Speed", 3f, 1f..6f) { arraylist && arrayColor == "Rainbow" }
    private val rainbowOffset by float("Rainbow Offset", 2f, 1f..6f) { arraylist && arrayColor == "Rainbow" }
    private val fadeOffset by float("Fade Offset", 14f, 1f..20f) { arraylist && arraylistFade }
    private val designColor = ColorSettingsInteger(this, "Design-Color").with(Color(33, 170, 47))
    private val hotbar by boolean("Hotbar", true)
    private val armor by boolean("Armor", true)
    private val compass by boolean("Compass", true)
    private val posDisplay by boolean("Position", true)

    private val categories = Category.entries.toList()
    private var currentCategoryIndex = 0
    private var currentModIndex = 0
    private var currentSettingIndex = 0
    private var screen = -1
    private var editMode = false
    private var lastActionTime = 0L

    private var hotbarAnim = 0f

    // Compass scale: text to type (1 = cardinal, 2 = 15 degree tick, 3 = intercardinal)
    private val compassDegrees = listOf(
        "N" to 1, "195" to 2, "210" to 2, "NE" to 3, "240" to 2, "255" to 2,
        "E" to 1, "285" to 2, "300" to 2, "SE" to 3, "330" to 2, "345" to 2,
        "S" to 1, "15" to 2, "30" to 2, "SW" to 3, "60" to 2, "75" to 2,
        "W" to 1, "105" to 2, "120" to 2, "NW" to 3, "150" to 2, "165" to 2
    )

    val onRender2D = handler<Render2DEvent> {
        if (mc.thePlayer == null || mc.theWorld == null) return@handler
        if (mc.gameSettings.showDebugInfo) return@handler

        if (arraylist) renderArraylist()
        if (hotbar) renderHotbar()
        if (armor) renderArmor()
        if (compass) renderCompass()
        if (posDisplay) renderPosition()
        renderTabgui()
    }

    val onKey = handler<KeyEvent> {
        when (it.key) {
            Keyboard.KEY_RETURN -> { onAction(); enter() }
            Keyboard.KEY_DOWN -> { onAction(); down() }
            Keyboard.KEY_UP -> { onAction(); up() }
            Keyboard.KEY_RIGHT -> { onAction(); right() }
            Keyboard.KEY_LEFT -> { onAction(); left() }
        }
    }

    private fun onAction() {
        lastActionTime = System.currentTimeMillis()
    }

    private fun modsFor(category: Category): List<Module> = ModuleManager[category]

    private fun currentMods(): List<Module> = modsFor(categories[currentCategoryIndex])

    private fun currentModule(): Module = currentMods()[currentModIndex]

    // ------------------------------------------------------------------
    // Arraylist
    // ------------------------------------------------------------------

    private fun renderArraylist() {
        val sr = ScaledResolution(mc)
        val fontRenderer = mc.fontRendererObj
        val simple = hudMode == "Simple"

        // Only render enabled modules, skipping the render category (like Hanabi's HUD)
        val mods = ModuleManager.filter { it.state }
            .filter { it.category != Category.RENDER }
            .sortedByDescending { fontRenderer.getStringWidth(it.name) }

        val fadeCount = fadeOffset.toInt().coerceAtLeast(1)
        var nextY = 1f
        var index = 0

        for (module in mods) {
            val name = module.name
            val startX = sr.scaledWidth - fontRenderer.getStringWidth(name) - 5

            var color: Color = when (arrayColor) {
                "Random" -> Color.getHSBColor((abs(name.hashCode()) % 360) / 360f, 0.8f, 0.7f)
                "Theme" -> designColor.color()
                else -> rainbowColor(nextY)
            }

            if (arraylistFade) {
                color = ColorUtils.fade(color, index, fadeCount)
                if (simple) color = color.withAlpha(191)
            } else if (simple) {
                color = Color(255, 255, 255, 191)
            }

            val y = nextY.toInt()
            if (!simple) {
                // Background bar + 1px accent line on the right edge
                Gui.drawRect(startX, y - 1, sr.scaledWidth, y + 10, SECONDARY)
                Gui.drawRect(sr.scaledWidth - 1, y - 1, sr.scaledWidth, y + 10, color.rgb)
            }

            fontRenderer.drawStringWithShadow(name, startX + 3f, nextY, color.rgb)

            nextY += if (simple) 13f else 10f
            index++
        }
    }

    private fun rainbowColor(nextY: Float): Color {
        // Mirrors Hanabi's RenderUtil.getRainbow(6000, -15 * nextY, speed, offset, 0.8f, 0.7f)
        val hue = ((System.currentTimeMillis() * rainbowSpeed.toDouble() +
            (-15.0 * nextY / rainbowOffset)) % 6000.0 * 2.0 / 6000.0).toFloat()
        return Color.getHSBColor(hue, 0.8f, 0.7f)
    }

    // ------------------------------------------------------------------
    // Hotbar
    // ------------------------------------------------------------------

    private fun renderHotbar() {
        val sr = ScaledResolution(mc)
        val fontRenderer = mc.fontRendererObj
        val centerX = sr.scaledWidth / 2
        val y = sr.scaledHeight - 22

        // Background
        Gui.drawRect(centerX - 91, y, centerX + 91, y + 22, SECONDARY)

        // Slot overlays: white translucent for the selected slot, black translucent for the others
        for (j in 0..8) {
            val x1 = centerX - 90 + j * 20
            val x2 = x1 + 20
            Gui.drawRect(x1, y + 2, x2, y + 22,
                if (j == mc.thePlayer.inventory.currentItem) Color(255, 255, 255, 110).rgb
                else Color(0, 0, 0, 110).rgb)
        }

        // 2px design color bar under the selected slot, smoothly animated
        hotbarAnim += (mc.thePlayer.inventory.currentItem * 20f - hotbarAnim) * 0.2f
        val indicatorX = centerX - 91 + hotbarAnim
        Gui.drawRect(indicatorX.toInt(), y + 20, indicatorX.toInt() + 20, y + 22, designColor.color().rgb)

        // Bottom left: ping & fps, bottom right: watermark
        val ping = mc.netHandler?.getPlayerInfo(mc.thePlayer.uniqueID)?.responseTime ?: -1
        fontRenderer.drawStringWithShadow(
            "PING:" + (if (ping > 0) "${ping}ms" else "N/A") + "  FPS:" + Minecraft.getDebugFPS(),
            16f, (y + 6).toFloat(), -1
        )
        val watermark = "AirPlus - " + mc.session.username
        fontRenderer.drawStringWithShadow(
            watermark,
            sr.scaledWidth - fontRenderer.getStringWidth(watermark) - 5f, (y + 6).toFloat(), -1
        )

        // Items
        RenderHelper.enableGUIStandardItemLighting()
        for (j in 0..8) {
            val k = centerX - 90 + j * 20 + 2
            val l = y + 4
            val stack = mc.thePlayer.inventory.getStackInSlot(j)
            if (stack == null) {
                fontRenderer.drawStringWithShadow((j + 1).toString(), (k + 4).toFloat(), (y + 7).toFloat(), -1)
                continue
            }

            mc.renderItem.renderItemAndEffectIntoGUI(stack, k, l)
            mc.renderItem.renderItemOverlays(fontRenderer, stack, k, l)
        }
        RenderHelper.disableStandardItemLighting()
        GlStateManager.color(1f, 1f, 1f, 1f)
    }

    // ------------------------------------------------------------------
    // Armor
    // ------------------------------------------------------------------

    private fun renderArmor() {
        val sr = ScaledResolution(mc)
        val centerX = sr.scaledWidth / 2
        val y = sr.scaledHeight - 55

        RenderHelper.enableGUIStandardItemLighting()
        var xOffset = 0
        for (slot in 3 downTo 0) {
            val stack = mc.thePlayer.inventory.armorItemInSlot(slot) ?: continue
            mc.renderItem.renderItemAndEffectIntoGUI(stack, centerX + 15 - xOffset, y)
            mc.renderItem.renderItemOverlays(mc.fontRendererObj, stack, centerX + 15 - xOffset, y)
            xOffset -= 18
        }
        RenderHelper.disableStandardItemLighting()
        GlStateManager.color(1f, 1f, 1f, 1f)
    }

    // ------------------------------------------------------------------
    // Compass
    // ------------------------------------------------------------------

    private fun renderCompass() {
        val sr = ScaledResolution(mc)
        val center = sr.scaledWidth / 2f
        val fontRenderer = mc.fontRendererObj
        val design = designColor.color().rgb

        // 2 pixels per yaw degree, offset so the entries repeat across the 3 passes
        val rewind = (mc.thePlayer.rotationYaw % 360f) * 2f + 360f * 3f

        GlStateManager.enableBlend()
        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        RenderUtils.makeScissorBox(center - 100f, 22f, center + 100f, 48f)

        var count = 0
        repeat(3) {
            for ((text, type) in compassDegrees) {
                val location = center + count * 30f - rewind
                count++

                val alpha = (255f - abs(center - location) * 1.8f).toInt().coerceIn(0, 255)
                if (alpha <= 0) continue

                // Highlight the direction the player is currently facing
                val color = if (abs(center - location) < 15f) design else Color(255, 255, 255, alpha).rgb

                when (type) {
                    1 -> fontRenderer.drawStringWithShadow(text, location - fontRenderer.getStringWidth(text) / 2f, 24f, color)
                    3 -> fontRenderer.drawStringWithShadow(text, location - fontRenderer.getStringWidth(text) / 2f, 26f, color)
                    else -> {
                        Gui.drawRect((location - 0.5f).toInt(), 29, (location + 0.5f).toInt(), 34, color)
                        fontRenderer.drawStringWithShadow(text, location - fontRenderer.getStringWidth(text) / 2f, 38f, color)
                    }
                }
            }
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        GlStateManager.disableBlend()
        GlStateManager.color(1f, 1f, 1f, 1f)
    }

    // ------------------------------------------------------------------
    // Position
    // ------------------------------------------------------------------

    private fun renderPosition() {
        val sr = ScaledResolution(mc)
        val pos = mc.thePlayer.position
        val text = "X:" + pos.x + "   Y:" + pos.y + "   Z:" + pos.z
        mc.fontRendererObj.drawStringWithShadow(
            text,
            sr.scaledWidth / 2f - mc.fontRendererObj.getStringWidth(text) / 2f, 50f, Color(255, 255, 255, 180).rgb
        )
    }

    // ------------------------------------------------------------------
    // TabGUI
    // ------------------------------------------------------------------

    private fun renderTabgui() {
        // Reset the tabgui after 5 seconds of not being used
        if (System.currentTimeMillis() - lastActionTime > 5000 && screen == 0) {
            screen = -1
        }

        // The logo is drawn by the separate "Logo" HUD element now
        if (screen == -1) {
            return
        }

        GlStateManager.pushMatrix()
        GlStateManager.color(1f, 1f, 1f, 1f)

        var startX = 3
        val startY = 3

        // Rendering the categories' first char in a row
        for (c in categories) {
            Gui.drawRect(startX, startY, startX + 15, startY + 15, SECONDARY)

            // Green background on the current category
            if (categories[currentCategoryIndex] == c) {
                Gui.drawRect(startX + 1, startY + 1, startX + 14, startY + 14, MAIN)
            }

            val ch = c.name.substring(0, 1)
            mc.fontRendererObj.drawStringWithShadow(
                ch,
                startX + 8f - mc.fontRendererObj.getStringWidth(ch) / 2f,
                startY + 4f, -1
            )
            startX += 16
        }

        // Modules screen
        if (screen == 1 || screen == 2) {
            var startXMods = 3
            var startYMods = 3 + 17

            Gui.drawRect(startXMods, startYMods, startXMods + getWidestMod(), startYMods + (currentMods().size * 12), SECONDARY)
            Gui.drawRect(startXMods + currentCategoryIndex * 16, startY + 15, startXMods + currentCategoryIndex * 16 + 15, startYMods, SECONDARY)

            for (m in currentMods()) {
                if (currentModule() == m) {
                    Gui.drawRect(startXMods, startYMods, startXMods + mc.fontRendererObj.getStringWidth(m.name) + 4, startYMods + 12, MAIN)
                }

                val x = startXMods + getWidestMod() - 7
                val y = startYMods + 4

                Gui.drawRect(x, y, x + 5, y + 5, -16777216)

                if (m.state) {
                    Gui.drawRect(x + 1, y + 1, x + 4, y + 4, MAIN)
                }

                mc.fontRendererObj.drawStringWithShadow(m.name, startXMods + 2f, startYMods + 2f, -1)
                startYMods += 12
            }
        }

        // Module settings screen
        if (screen == 2) {
            val startXSettings = 3 + getWidestMod() + 2
            val startYSettings = 3 + 17 + currentModIndex * 12
            val settings = currentModule().values

            Gui.drawRect(
                startXSettings, startYSettings,
                startXSettings + getWidestSetting() + 2, startYSettings + (settings.size * 12) - 2,
                SECONDARY
            )

            for ((index, s) in settings.withIndex()) {
                val yPos = startYSettings + index * 12

                // Green background for the selected setting
                if (index == currentSettingIndex) {
                    Gui.drawRect(startXSettings, yPos, startXSettings + mc.fontRendererObj.getStringWidth(getSettingText(s)) + 2, yPos + 11, MAIN)
                }

                mc.fontRendererObj.drawStringWithShadow(getSettingText(s), startXSettings + 1f, yPos + 1f, -1)
            }
        }

        GlStateManager.color(1f, 1f, 1f, 1f)
        GlStateManager.popMatrix()
    }

    private fun getSettingText(s: Value<*>): String = when (s) {
        is ListValue -> "${s.name}: ${s.get()}"
        is FloatValue -> "${s.name}: " + String.format("%.2f", s.get())
        is IntValue -> "${s.name}: ${s.get()}"
        is BoolValue -> "${s.name}: ${s.get()}"
        // FontValue.get() 是 FontRenderer 对象，直接拼接会显示成包名+哈希
        is FontValue -> "${s.name}: ${s.displayName.removePrefix("Font: ")}"
        else -> "${s.name}: ${s.get()}"
    }

    private fun getWidestSetting(): Int {
        var maxWidth = 0
        for (s in currentModule().values) {
            val width = mc.fontRendererObj.getStringWidth(getSettingText(s))
            if (width > maxWidth) maxWidth = width
        }
        return maxWidth
    }

    private fun getWidestMod(): Int {
        var width = categories.size * 16
        for (m in currentMods()) {
            if (mc.fontRendererObj.getStringWidth(m.name) + 14 > width) {
                width = mc.fontRendererObj.getStringWidth(m.name) + 14
            }
        }
        return width
    }

    private fun left() {
        if (screen == 0) {
            currentCategoryIndex = if (currentCategoryIndex > 0) currentCategoryIndex - 1 else categories.size - 1
        } else if (screen == 2) {
            currentSettingIndex = 0
            editMode = false
            screen = 1
        }
    }

    private fun right() {
        if (screen == 0) {
            currentCategoryIndex = if (currentCategoryIndex < categories.size - 1) currentCategoryIndex + 1 else 0
        } else if (screen == 1) {
            if (currentModule().values.isNotEmpty()) {
                currentSettingIndex = 0
                screen = 2
            }
        }
    }

    private fun down() {
        if (editMode) {
            editCurrentSetting(+1)
        } else {
            when (screen) {
                -1 -> screen = 0
                0 -> screen = 1
                1 -> {
                    val size = currentMods().size
                    if (size > 0) currentModIndex = (currentModIndex + 1) % size
                }
                2 -> {
                    val size = currentModule().values.size
                    if (size > 0) currentSettingIndex = (currentSettingIndex + 1) % size
                }
            }
        }
    }

    private fun up() {
        if (editMode) {
            editCurrentSetting(-1)
        } else {
            when {
                screen == 0 -> screen = -1
                screen == 1 -> {
                    val size = currentMods().size
                    if (size > 0) currentModIndex = (currentModIndex - 1 + size) % size
                }
                screen == 2 -> {
                    val size = currentModule().values.size
                    if (size > 0) currentSettingIndex = (currentSettingIndex - 1 + size) % size
                }
            }
        }
    }

    private fun editCurrentSetting(direction: Int) {
        when (val s = currentModule().values.getOrNull(currentSettingIndex) ?: return) {
            is BoolValue -> s.toggle()
            is ListValue -> {
                val index = s.values.indexOf(s.get())
                if (index >= 0) {
                    s.set(s.values[(index + direction + s.values.size) % s.values.size])
                }
            }
            is IntValue -> s.set(s.get() + direction)
            is FloatValue -> s.set(s.get() + 0.1f * direction)
            else -> {}
        }
    }

    private fun enter() {
        if (screen == 1) {
            currentModule().toggle()
        } else if (screen == 2) {
            editMode = !editMode
        }
    }
}
