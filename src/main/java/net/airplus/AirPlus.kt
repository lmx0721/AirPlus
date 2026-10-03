    /*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus

import com.formdev.flatlaf.themes.FlatMacLightLaf
import kotlinx.coroutines.launch
import net.airplus.api.ClientUpdate
import net.airplus.api.ClientUpdate.gitInfo
import net.airplus.api.loadSettings
import net.airplus.cape.CapeService
import net.airplus.event.ClientShutdownEvent
import net.airplus.event.EventManager
import net.airplus.event.StartupEvent
import net.airplus.features.command.CommandManager
import net.airplus.features.command.CommandManager.registerCommands
import net.airplus.features.module.ModuleManager
import net.airplus.features.module.ModuleManager.registerModules
import net.airplus.features.special.BungeeCordSpoof
import net.airplus.features.special.ClientFixes
import net.airplus.features.special.ClientRichPresence
import net.airplus.features.special.ClientRichPresence.showRPCValue
import net.airplus.utils.inputfix.InputFixInit
import net.airplus.file.FileManager
import net.airplus.file.FileManager.loadAllConfigs
import net.airplus.file.FileManager.saveAllConfigs
import net.airplus.file.configs.models.ClientConfiguration.updateClientWindow
import net.airplus.lang.LanguageManager.loadLanguages
import net.airplus.script.ScriptManager
import net.airplus.script.ScriptManager.enableScripts
import net.airplus.script.ScriptManager.loadScripts
import net.airplus.script.remapper.Remapper
import net.airplus.script.remapper.Remapper.loadSrg
import net.airplus.tabs.BlocksTab
import net.airplus.tabs.ExploitsTab
import net.airplus.tabs.HeadsTab
import net.airplus.ui.client.altmanager.GuiAltManager.Companion.loadActiveGenerators
import net.airplus.ui.client.clickgui.ClickGui
import net.airplus.ui.client.hud.HUD
import net.airplus.ui.font.Fonts
import net.airplus.utils.client.BlinkUtils
import net.airplus.utils.client.ClassUtils.hasForge
import net.airplus.utils.client.ClientUtils.LOGGER
import net.airplus.utils.client.ClientUtils.disableFastRender
import net.airplus.utils.client.PacketUtils
import net.airplus.utils.inventory.InventoryManager
import net.airplus.utils.inventory.InventoryUtils
import net.airplus.utils.inventory.SilentHotbar
import net.airplus.utils.io.MiscUtils
import net.airplus.utils.io.MiscUtils.showErrorPopup
import net.airplus.utils.kotlin.SharedScopes
import net.airplus.utils.movement.BPSUtils
import net.airplus.utils.movement.MovementUtils
import net.airplus.utils.movement.TimerBalanceUtils
import net.airplus.utils.render.MiniMapRegister
import net.airplus.utils.render.shader.Background
import net.airplus.utils.rotation.RotationUtils
import net.airplus.utils.timing.TickedActions
import net.airplus.utils.timing.WaitTickUtils
import net.airplus.viaversion.viamcp.ViaMCP
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import javax.swing.UIManager
import java.util.Properties

object AirPlus {

    /**
     * Client Information
     *
     * This has all the basic information.
     */
    const val CLIENT_NAME = "AirPlus"
    const val CLIENT_AUTHOR = "CCBlueX"
    const val CLIENT_CLOUD = "https://cloud.liquidbounce.net/LiquidBounce"
    const val CLIENT_WEBSITE = "liquidbounce.net"
    const val CLIENT_GITHUB = "https://github.com/CCBlueX/LiquidBounce"

    const val MINECRAFT_VERSION = "1.8.9"

    // 版本号来自 gradle 的 mod_version（构建时注入到 client.properties），不依赖 git
    private val clientProps = Properties().also {
        AirPlus::class.java.classLoader.getResourceAsStream("client.properties")?.use(it::load)
    }
    val clientVersionText = clientProps.getProperty("clientVersion")
        ?: gitInfo["git.build.version"]?.toString() ?: "unknown"
    val clientCommit = gitInfo["git.commit.id.abbrev"]?.let { "git-$it" } ?: "release"
    val clientBranch = gitInfo["git.branch"]?.toString() ?: "release"

    /**
     * Defines if the client is in development mode.
     * This will enable update checking on commit time instead of regular legacy versioning.
     */
    const val IN_DEV = false

    val clientTitle = CLIENT_NAME + " " + clientVersionText + " " + clientCommit + " | " + MINECRAFT_VERSION + if (IN_DEV) " | DEVELOPMENT BUILD" else ""

    var isStarting = true

    // Managers
    val moduleManager = ModuleManager
    val commandManager = CommandManager
    val eventManager = EventManager
    val fileManager = FileManager
    val scriptManager = ScriptManager

    // HUD & ClickGUI
    val hud = HUD

    val clickGui = ClickGui

    // Menu Background
    var background: Background? = null

    // Discord RPC
    val clientRichPresence = ClientRichPresence

    /**
     * Start IO tasks
     */
    fun preload(): Future<*> {

        net.airplus.utils.client.javaVersion

        // Change theme of Swing
        UIManager.setLookAndFeel(FlatMacLightLaf())

        val future = CompletableFuture<Unit>()

        SharedScopes.IO.launch {
            try {
                LOGGER.info("Starting preload tasks of $CLIENT_NAME")

                // Check update
                ClientUpdate.reloadNewestVersion()

                // Load languages
                loadLanguages()

                // Load alt generators
                loadActiveGenerators()

                // Load SRG file
                loadSrg()

                LOGGER.info("Preload tasks of $CLIENT_NAME are completed!")

                future.complete(Unit)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }

        return future
    }

    /**
     * Execute if client will be started
     */
    fun startClient() {
        isStarting = true

        LOGGER.info("Starting $CLIENT_NAME $clientVersionText $clientCommit, by $CLIENT_AUTHOR")


        try {
            // Initialize ViaMCP (protocol translation) and its version slider
            runCatching {
                ViaMCP.create()
                ViaMCP.INSTANCE.initAsyncSlider(160, 8, 110, 20)
            }.onFailure {
                LOGGER.error("Failed to initialize ViaMCP.", it)
            }

            // Initialize the Chinese input fix (port of AirClient's InputFix)
            InputFixInit.init()

            // Load client fonts
            Fonts.loadFonts()

            // Register listeners
            RotationUtils
            ClientFixes
            BungeeCordSpoof
            CapeService
            InventoryUtils
            InventoryManager
            MiniMapRegister
            TickedActions
            MovementUtils
            PacketUtils
            TimerBalanceUtils
            BPSUtils
            WaitTickUtils
            SilentHotbar
            BlinkUtils

            // Load settings
            loadSettings(false) {
                LOGGER.info("Successfully loaded ${it.size} settings.")
            }

            // Register commands
            registerCommands()

            // Setup module manager and register modules
            registerModules()

            runCatching {
                // Remapper
                loadSrg()

                if (!Remapper.mappingsLoaded) {
                    error("Failed to load SRG mappings.")
                }

                // ScriptManager
                loadScripts()
                enableScripts()
            }.onFailure {
                LOGGER.error("Failed to load scripts.", it)
            }

            // Load configs
            loadAllConfigs()

            // Update client window
            updateClientWindow()

            // Tabs (Only for Forge!)
            if (hasForge()) {
                BlocksTab()
                ExploitsTab()
                HeadsTab()
            }

            // Disable Optifine FastRender
            disableFastRender()

            // Setup Discord RPC
            if (showRPCValue) {
                SharedScopes.IO.launch {
                    try {
                        clientRichPresence.setup()
                    } catch (throwable: Throwable) {
                        LOGGER.error("Failed to setup Discord RPC.", throwable)
                    }
                }
            }

            // Login into known token if not empty
            if (CapeService.knownToken.isNotBlank()) {
                SharedScopes.IO.launch {
                    runCatching {
                        CapeService.login(CapeService.knownToken)
                    }.onFailure {
                        LOGGER.error("Failed to login into known cape token.", it)
                    }.onSuccess {
                        LOGGER.info("Successfully logged in into known cape token.")
                    }
                }
            }

            // Refresh cape service
            CapeService.refreshCapeCarriers {
                LOGGER.info("Successfully loaded ${it.size} cape carriers.")
            }

            // Load background
            FileManager.loadBackground()
        } catch (e: Exception) {
            LOGGER.error("Failed to start client: ${e.message}")
            e.showErrorPopup()
        } finally {
            // Set is starting status
            isStarting = false

            if (!FileManager.firstStart && FileManager.backedup) {
                SharedScopes.IO.launch {
                    MiscUtils.showMessageDialog("Warning: backup triggered", "Client update detected! Please check the config folder.")
                }
            }

            EventManager.call(StartupEvent)
            LOGGER.info("Successfully started client")
        }
    }

    /**
     * Execute if client will be stopped
     */
    fun stopClient() {
        // Call client shutdown
        EventManager.call(ClientShutdownEvent)

        // Stop all CoroutineScopes
        SharedScopes.stop()

        // Save all available configs
        saveAllConfigs()
    }

}
