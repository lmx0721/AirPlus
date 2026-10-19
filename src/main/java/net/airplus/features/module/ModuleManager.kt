/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module

import net.airplus.event.KeyEvent
import net.airplus.event.Listenable
import net.airplus.event.handler
import net.airplus.features.command.CommandManager.registerCommand
import net.airplus.features.module.modules.client.*
import net.airplus.features.module.modules.combat.*
import net.airplus.features.module.modules.exploit.*
import net.airplus.features.module.modules.misc.*
import net.airplus.features.module.modules.movement.*
import net.airplus.features.module.modules.music.MusicPlayer
import net.airplus.features.module.modules.music.NetEaseMusic
import net.airplus.features.module.modules.player.*
import net.airplus.features.module.modules.render.*
import net.airplus.features.module.modules.world.*
import net.airplus.features.module.modules.world.Timer
import net.airplus.features.module.modules.world.scaffolds.Scaffold
import net.airplus.utils.client.ClientUtils.LOGGER
import java.util.*

private val MODULE_REGISTRY = TreeSet(Comparator.comparing(Module::name))

object ModuleManager : Listenable, Collection<Module> by MODULE_REGISTRY {

    /**
     * Register all modules
     */
    fun registerModules() {
        LOGGER.info("[ModuleManager] Loading modules...")

        // Register modules
        val modules = arrayOf(
            AbortBreaking,
            Aimbot,
            Ambience,
            Animations,
            AntiAFK,
            AntiBlind,
            AntiBot,
            AnticheatDetector,
            AntiExploit,
            AntiHunger,
            AntiFireball,
            AntiVoid,
            ArmorBreak,
            AtAllProvider,
            AttackEffect,
            AttackEffects,
            AutoAccount,
            AutoArmor,
            AutoBow,
            AutoBreak,
            AutoClicker,
            AutoDisable,
            AutoFish,
            AutoProjectile,
            AutoPlay,
            AutoLeave,
            AutoPot,
            AutoRespawn,
            AutoRod,
            AutoSoup,
            AutoLFix,
            AutoTool,
            AutoWalk,
            AutoWeapon,
            BAHalo,
            Backtrack,
            BedDefender,
            BedPlates,
            BedProtectionESP,
            BetterFPS,
            Blink,
            BlockESP,
            BlockHit,
            BlockOverlay,
            PointerESP,
            ProjectileAimbot,
            Breadcrumbs,
            Camera,
            Cape,
            ChestAura,
            ChestStealer,
            CivBreak,
            ClickGUI,
            Client,
            ClientFixes,
            Clip,
            ColorMixer,
            ComponentOnHover,
            ConsoleSpammer,
            Criticals,
            Damage,
            DamageParticle,
            DeltaForceStyle,
            Derp,
            ESP,
            Eagle,
            FakeLag,
            FastBow,
            FastBreak,
            FastLadder,
            FastClimb,
            FastPlace,
            FastStairs,
            FastUse,
            FlagCheck,
            Fly,
            Fly2,
            FollowTargetHud,
            ForceUnicodeChat,
            FreeCam,
            FPSBoost,
            Freeze,
            Fucker,
            Fullbright,
            Gapple,
            GameDetector,
            Ghost,
            GhostHand,
            HanabiHUD,
            HUD,
            HUDEdit,
            Health,
            Island,
            HighJump,
            HitBox,
            IceSpeed,
            InventoryCleaner,
            InventoryMove,
            ItemESP,
            ItemPhysics,
            Jelly,
            JumpCircle,
            KeepAlive,
            KeepContainer,
            KeepTabList,
            KeyPearl,
            Kick,
            KillAura,
            KillEffect,
            KillEffectV2,
            LiquidWalk,
            LongJump,
            MidClick,
            MoBendsMod,
            MoreCarry,
            MotionBlur,
            MultiActions,
            NameProtect,
            NameTags,
            NameTags2,
            NoBob,
            NoClip,
            NoFOV,
            NoFall,
            NoFluid,
            NoFriends,
            NoHurtCam,
            NoJumpDelay,
            NoRotateSet,
            NoSlotSet,
            NoSlow,
            NoSlowBreak,
            NoSwing,
            Notifier,
            NoWeb,
            Nuker,
            PacketDebugger,
            Parkour,
            PingSpoof,
            Plugins,
            PortalMenu,
            Projectiles,
            ProphuntESP,
            RawInput,
            Reach,
            Refill,
            Regen,
            ResourcePackSpoof,
            Rotations,
            SafeWalk,
            Scaffold,
            ServerCrasher,
            Sneak,
            Sound,
            Spammer,
            Speed,
            Sprint,
            StaffDetector,
            Step,
            StorageESP,
            Strafe,
            SuperKnockback,
            Teleport,
            TeleportHit,
            TPAura,
            TNTBlock,
            TNTESP,
            TNTTimer,
            TargetManager,
            TargetMark,
            Teams,
            ThemeManager,
            TimerRange,
            Timer,
            Tracers,
            TrueSight,
            VehicleOneHit,
            Velocity,
            WallClimb,
            XRay,
            Zoot,
            KeepSprint,
            Disabler,
            OverrideRaycast,
            TickBase,
            FreeLook,
            SilentHotbarModule,
            MusicPlayer,
            NetEaseMusic
        )

        registerModules(modules = modules)

        LOGGER.info("[ModuleManager] Loaded ${modules.size} modules.")
    }

    /**
     * Register [module]
     */
    fun registerModule(module: Module) {
        MODULE_REGISTRY += module
        generateCommand(module)
    }

    /**
     * Register a list of modules
     */
    @SafeVarargs
    fun registerModules(vararg modules: Module) = modules.forEach(this::registerModule)

    /**
     * Unregister module
     */
    fun unregisterModule(module: Module) {
        MODULE_REGISTRY.remove(module)
        module.onUnregister()
    }

    /**
     * Generate command for [module]
     */
    internal fun generateCommand(module: Module) {
        val values = module.values

        if (values.isEmpty())
            return

        registerCommand(ModuleCommand(module, values))
    }

    /**
     * Get module by [moduleClass]
     */
    operator fun get(moduleClass: Class<out Module>) = MODULE_REGISTRY.find { it.javaClass === moduleClass }

    /**
     * Get module by [moduleName]
     */
    operator fun get(moduleName: String) = MODULE_REGISTRY.find { it.name.equals(moduleName, ignoreCase = true) }

    /**
     * Get modules by [category]
     */
    operator fun get(category: Category) = MODULE_REGISTRY.filter { it.category === category }

    @Deprecated(message = "Only for outdated scripts", replaceWith = ReplaceWith("get(moduleClass)"))
    fun getModule(moduleClass: Class<out Module>) = get(moduleClass)

    @Deprecated(message = "Only for outdated scripts", replaceWith = ReplaceWith("get(moduleName)"))
    fun getModule(moduleName: String) = get(moduleName)

    /**
     * Handle incoming key presses
     */
    private val onKey = handler<KeyEvent> { event ->
        MODULE_REGISTRY.forEach { if (it.keyBind == event.key) it.toggle() }
    }

}
