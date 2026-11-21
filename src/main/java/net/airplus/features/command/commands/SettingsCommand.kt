/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.command.commands

import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.airplus.api.autoSettingsList
import net.airplus.api.loadSettings
import net.airplus.config.SettingsUtils
import net.airplus.features.command.Command
import net.airplus.ui.client.hud.HUD.addNotification
import net.airplus.ui.client.hud.element.elements.Notification
import net.airplus.utils.client.ClientUtils.LOGGER
import net.airplus.utils.kotlin.SharedScopes

object SettingsCommand : Command("autosettings", "autosetting", "settings", "setting", "config") {

    private val mutex = Mutex()

    /**
     * Execute commands with provided [args]
     */
    override fun execute(args: Array<String>) {
        val usedAlias = args[0].lowercase()

        if (args.size <= 1) {
            chatSyntax("$usedAlias <load/list>")
            return
        }

        if (mutex.isLocked) {
            chat("§cPrevious task is not finished!")
            return
        }

        SharedScopes.IO.launch {
            mutex.withLock {
                when (args[1].lowercase()) {
                    "load" -> loadSettings(args)
                    "list" -> listSettings()
                    else -> chatSyntax("$usedAlias <load/list>")
                }
            }
        }
    }

    // Load subcommand
    private fun loadSettings(args: Array<String>) {
        if (args.size < 3) {
            chatSyntax("${args[0].lowercase()} load <name/url>")
            return
        }

        try {
            val settings = SettingsUtils.loadFromUrl(args[2])

            chat("Applying settings...")
            SettingsUtils.applyScript(settings)
            chat("§6Settings applied successfully")
            addNotification(Notification("Settings Command", "Successfully updated settings!"))
            playEdit()
        } catch (e: Exception) {
            LOGGER.error("Failed to load settings", e)
            chat("Failed to load settings: ${e.message}")
        }
    }

    // List subcommand
    private fun listSettings() {
        chat("Loading settings...")
        loadSettings(false) {
            for (setting in it) {
                chat("> ${setting.settingId} (Last updated: ${setting.date}, Status: ${setting.statusType.displayName})")
            }
        }
    }

    override fun tabComplete(args: Array<String>): List<String> {
        if (args.isEmpty()) {
            return emptyList()
        }

        return when (args.size) {
            1 -> listOf("list", "load").filter { it.startsWith(args[0], true) }
            2 -> {
                when (args[0].lowercase()) {
                    "load" -> {
                        if (autoSettingsList == null) {
                            loadSettings(true, 500)
                        }

                        return autoSettingsList?.filter { it.settingId.startsWith(args[1], true) }?.map { it.settingId }
                            ?: emptyList()
                    }

                    else -> emptyList()
                }
            }

            else -> emptyList()
        }
    }
}
