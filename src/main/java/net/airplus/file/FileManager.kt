/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.file

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.airplus.AirPlus.CLIENT_NAME
import net.airplus.AirPlus.MINECRAFT_VERSION
import net.airplus.AirPlus.background
import net.airplus.AirPlus.isStarting
import net.airplus.file.configs.*
import net.airplus.utils.client.ClientUtils.LOGGER
import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.io.zipFilesTo
import net.airplus.utils.render.shader.Background
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly
import java.io.File

private val FILE_CONFIGS = ArrayList<FileConfig>()

@SideOnly(Side.CLIENT)
object FileManager : MinecraftInstance, Iterable<FileConfig> by FILE_CONFIGS {

    val dir = File(mc.mcDataDir, "$CLIENT_NAME-$MINECRAFT_VERSION")
    val fontsDir = File(dir, "fonts")
    val settingsDir = File(dir, "settings")
    val themesDir = File(dir, "themes")

    val modulesConfig = +ModulesConfig(File(dir, "modules.json"))
    val valuesConfig = +ValuesConfig(File(dir, "values.json"))
    val clickGuiConfig = +ClickGuiConfig(File(dir, "clickgui.json"))
    val accountsConfig = +AccountsConfig(File(dir, "accounts.json"))
    val friendsConfig = +FriendsConfig(File(dir, "friends.json"))
    val xrayConfig = +XRayConfig(File(dir, "xray-blocks.json"))
    val hudConfig = +HudConfig(File(dir, "hud.json"))
    val shortcutsConfig = +ShortcutsConfig(File(dir, "shortcuts.json"))

    // User-selected background file (copied from local pick; extension is preserved to detect png/frag)
    private val backgroundFileExtensions = arrayOf("png", "frag", "glsl", "shader")

    fun backgroundFileFor(extension: String) = File(dir, "userbackground.${extension.lowercase()}")

    fun existingBackgroundFile(): File? =
        backgroundFileExtensions.map { backgroundFileFor(it) }.firstOrNull { it.exists() }

    var firstStart = false
        private set

    var backedup = false
        private set

    val PRETTY_GSON: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Constructor of file manager
     * Setup everything important
     */
    init {
        setupFolder()
    }

    /**
     * Register a FileConfig to FileManager
     * @author MukjepScarlet
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline operator fun <T : FileConfig> T.unaryPlus(): T = apply {
        FILE_CONFIGS.add(this)
    }

    /**
     * Setup folder
     */
    private fun setupFolder() {
        if (!dir.exists()) {
            dir.mkdir()
            firstStart = true
        }
        if (!fontsDir.exists()) fontsDir.mkdir()
        if (!settingsDir.exists()) settingsDir.mkdir()
        if (!themesDir.exists()) themesDir.mkdir()
    }

    /**
     * Backup all configs as a ZIP file.
     * @author MukjepScarlet
     */
    fun backupAllConfigs(previousVersion: String, currentVersion: String) {
        try {
            FILE_CONFIGS.mapNotNull { it.file.takeIf(File::isFile) }.zipFilesTo(File(dir, "backup_${previousVersion}_${currentVersion}.zip"))
            backedup = true
            LOGGER.info("[FileManager] Successfully backed up all configs.")
        } catch (e: Exception) {
            LOGGER.error("[FileManager] Failed backup configs!", e)
        }
    }

    /**
     * Load all configs in file manager
     */
    fun loadAllConfigs() {
        FILE_CONFIGS.forEach {
            try {
                loadConfig(it)
            } catch (e: Exception) {
                LOGGER.error("[FileManager] Failed to load config file of ${it.file.name}.", e)
            }
        }
    }

    /**
     * Load a list of configs
     *
     * @param configs list
     */
    fun loadConfigs(vararg configs: FileConfig) {
        for (fileConfig in configs) loadConfig(fileConfig)
    }

    /**
     * Load one config
     *
     * @param config to load
     */
    fun loadConfig(config: FileConfig) {
        if (!config.hasConfig()) {
            LOGGER.info("[FileManager] Skipped loading config: ${config.file.name}.")
            config.loadDefault()
            saveConfig(config, false)
            return
        }

        try {
            config.loadConfig()
            LOGGER.info("[FileManager] Loaded config: ${config.file.name}.")
        } catch (t: Throwable) {
            LOGGER.error("[FileManager] Failed to load config file: ${config.file.name}.", t)
        }
    }

    /**
     * Save all configs in file manager
     */
    fun saveAllConfigs() {
        FILE_CONFIGS.forEach {
            try {
                saveConfig(it)
            } catch (e: Exception) {
                LOGGER.error("[FileManager] Failed to save config file of ${it.file.name}.", e)
            }
        }
    }

    /**
     * Save a list of configs
     *
     * @param configs list
     */
    fun saveConfigs(vararg configs: FileConfig) {
        for (fileConfig in configs) saveConfig(fileConfig)
    }

    /**
     * Save one config
     *
     * @param config         to save
     * @param ignoreStarting check starting
     */
    fun saveConfig(config: FileConfig, ignoreStarting: Boolean = true) {
        if (ignoreStarting && isStarting) return

        try {
            if (!config.hasConfig()) config.createConfig()
            config.saveConfig()
            LOGGER.info("[FileManager] Saved config: ${config.file.name}.")
        } catch (t: Throwable) {
            LOGGER.error("[FileManager] Failed to save config file: ${config.file.name}.", t)
        }
    }

    /**
     * Load the user-selected background (png image or frag shader), if any
     */
    fun loadBackground() {
        val file = existingBackgroundFile() ?: return
        background = try {
            Background.fromFile(file)
        } catch (t: Throwable) {
            LOGGER.error("[FileManager] Failed to load background file: ${file.name}.", t)
            null
        }
    }
}