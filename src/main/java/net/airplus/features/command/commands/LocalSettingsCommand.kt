/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.command.commands

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.airplus.config.SettingsUtils
import net.airplus.features.command.Command
import net.airplus.file.FileManager.settingsDir
import net.airplus.ui.client.hud.HUD.addNotification
import net.airplus.ui.client.hud.element.elements.Notification
import net.airplus.utils.client.ClientUtils.LOGGER
import net.airplus.utils.kotlin.SharedScopes
import net.airplus.utils.kotlin.StringUtils
import java.awt.Desktop
import java.io.File
import java.io.IOException

object LocalSettingsCommand : Command("localsettings", "localsetting", "localconfig") {

    /**
     * Execute commands with provided [args]
     */
    override fun execute(args: Array<String>) {
        val usedAlias = args[0].lowercase()

        if (args.size <= 1) {
            chatSyntax("$usedAlias <load/save/list/delete/folder>")
            return
        }

        SharedScopes.IO.launch {
            when (args[1].lowercase()) {
                "load" -> loadSettings(args)
                "save" -> saveSettings(args)
                "delete" -> deleteSettings(args)
                "list" -> listSettings()
                "folder" -> openSettingsFolder()
                else -> chatSyntax("$usedAlias <load/save/list/delete/folder>")
            }
        }
    }

    private suspend fun loadSettings(args: Array<String>) {
        withContext(Dispatchers.IO) {
            if (args.size <= 2) {
                chatSyntax("${args[0].lowercase()} load <name>")
                return@withContext
            }

            val settingsFile = File(settingsDir, args[2] + ".txt")

            if (!settingsFile.exists()) {
                chat("§cSettings file does not exist! §e(Ensure its .txt)")
                return@withContext
            }

            try {
                chat("§9Loading settings...")
                val settings = settingsFile.readText()
                chat("§9Set settings...")
                SettingsUtils.applyScript(settings)
                chat("§6Settings applied successfully.")

                val comment = extractComment(settings)
                if (comment.isNotBlank()) {
                    chat("§9Comment: §7$comment")
                    addNotification(Notification.informative("Local Settings", comment))
                } else {
                    addNotification(Notification.informative("Local Settings Command", "Updated Settings"))
                }
                playEdit()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun saveSettings(args: Array<String>) {
        withContext(Dispatchers.IO) {
            if (args.size <= 2) {
                chatSyntax("${args[0].lowercase()} save <name> [all/values/binds/states]...")
                return@withContext
            }

            val settingsFile = File(settingsDir, args[2] + ".txt")

            try {
                if (settingsFile.exists())
                    settingsFile.delete()

                settingsFile.createNewFile()

                // 解析可选项与注释：
                // .localsettings save <name>                        -> 默认选项，无注释
                // .localsettings save <name> <注释...>              -> 默认选项，带注释
                // .localsettings save <name> <all/values/...> <注释...>
                val optionArg = args.getOrNull(3)?.lowercase()
                val knownOption = optionArg in setOf("all", "default", "values", "binds", "states")
                val option = if (knownOption) optionArg else null
                val commentStart = when {
                    knownOption -> 4
                    args.size > 3 -> 3
                    else -> args.size
                }
                val comment = if (args.size > commentStart) StringUtils.toCompleteString(args, commentStart) else ""

                val values = option == "all" || option == null || option == "default" || option == "values"
                val binds = option == "all" || option == "binds"
                val states = option == "all" || option == null || option == "default" || option == "states"

                if (!values && !binds && !states) {
                    chatSyntaxError()
                    return@withContext
                }

                chat("§9Creating settings...")
                val settingsScript = SettingsUtils.generateScript(values, binds, states)
                val scriptWithComment = if (comment.isNotBlank()) "# $comment\n\n$settingsScript" else settingsScript

                chat("§9Saving settings...")
                settingsFile.writeText(scriptWithComment)

                chat("§6Settings saved successfully.")
                if (comment.isNotBlank()) {
                    chat("§9Comment: §7$comment")
                }
            } catch (throwable: Throwable) {
                chat("§cFailed to create local config: §3${throwable.message}")
                LOGGER.error("Failed to create local config.", throwable)
            }
        }
    }

    /**
     * 提取设置脚本开头的注释行（以 # 开头），拼接为一行文本。
     */
    private fun extractComment(script: String): String =
        script.lineSequence()
            .takeWhile { it.startsWith("#") }
            .map { it.removePrefix("#").trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")

    private suspend fun deleteSettings(args: Array<String>) {
        withContext(Dispatchers.IO) {
            if (args.size <= 2) {
                chatSyntax("${args[0].lowercase()} delete <name>")
                return@withContext
            }

            val settingsFile = File(settingsDir, args[2] + ".txt")

            if (!settingsFile.exists()) {
                chat("§cSettings file does not exist!")
                return@withContext
            }

            settingsFile.delete()
            chat("§6Settings file deleted successfully.")
        }
    }

    private suspend fun listSettings() {
        withContext(Dispatchers.IO) {
            chat("§cSettings:")

            val settings = settingsDir.listFiles() ?: return@withContext

            for (file in settings) {
                chat("> " + file.name.removeSuffix(".txt"))
            }
        }
    }

    private suspend fun openSettingsFolder() {
        withContext(Dispatchers.IO) {
            try {
                Desktop.getDesktop().open(settingsDir)
            } catch (e: IOException) {
                LOGGER.error("Failed to open settings folder.", e)
                chat("§cFailed to open settings folder.")
            }
        }
    }


    override fun tabComplete(args: Array<String>): List<String> {
        if (args.isEmpty()) return emptyList()

        return when (args.size) {
            1 -> listOf("delete", "list", "load", "save", "folder").filter { it.startsWith(args[0], true) }

            2 -> {
                when (args[0].lowercase()) {
                    "delete", "load", "save" -> {
                        val settings = settingsDir.listFiles() ?: return emptyList()
                        settings.map { it.name.removeSuffix(".txt") }.filter { it.startsWith(args[1], true) }
                    }

                    else -> emptyList()
                }
            }

            3 -> {
                when (args[0].lowercase()) {
                    "save" -> listOf("all", "default", "values", "binds", "states").filter {
                        it.startsWith(
                            args[2],
                            true
                        )
                    }

                    else -> emptyList()
                }
            }

            else -> emptyList()
        }
    }
}