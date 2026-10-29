/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module

import net.airplus.AirPlus.CLIENT_NAME
import net.minecraft.util.ResourceLocation

enum class Category(val displayName: String) {

    COMBAT("Combat"),
    PLAYER("Player"),
    MOVEMENT("Movement"),
    RENDER("Render"),
    WORLD("World"),
    MISC("Misc"),
    CLIENT("Client");

    val iconResourceLocation = ResourceLocation("${CLIENT_NAME.lowercase()}/tabgui/${name.lowercase()}.png")

    /**
     * Whether this category should be displayed in the ClickGUI.
     */
    fun shouldShow(): Boolean = true

}
