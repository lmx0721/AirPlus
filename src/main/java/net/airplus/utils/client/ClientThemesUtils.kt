/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.utils.client

import net.airplus.utils.render.ColorUtils
import net.airplus.utils.render.ColorUtils.mixColors
import java.awt.Color

/**
 * Client theme engine, migrated from AirClient and redesigned around a
 * single theme registry: every color lookup goes through [resolve], so the
 * theme data exists exactly once (AirClient kept four diverging copies).
 *
 * A theme is a pair of gradient colors. [getColor] animates between the two
 * ends with a sine based fade; the `index` parameter shifts the phase so
 * multiple elements animate out of sync.
 */
object ClientThemesUtils {

    /** A named gradient theme. */
    data class ClientTheme(val key: String, val displayName: String, val start: Color, val end: Color)

    /** Current theme, stored as key ("moonpurple"), display name ("Moon Purple") or "#hex". */
    var ClientColorMode: String = "MoonPurple"
        set(value) {
            field = value.lowercase()
        }

    /** Fade speed of the gradient animation, 1 (slow) to 10 (fast). */
    var ThemeFadeSpeed: Int = 7
        set(value) {
            field = value.coerceIn(1, 10)
        }

    /** Animation direction. */
    var updown: Boolean = false

    /** GUI background mode: Synced / Dark / Custom / Neverlose / None / "#hex". */
    var BackgroundMode: String = "Synced"
        set(value) {
            field = value.lowercase()
        }

    var customBgColor: Color = Color(32, 32, 64)
    var neverLoseBgColor: Color = Color(60, 60, 60)

    // ---------------------------------------------------------------------
    // Theme registry - the single source of truth for all theme colors.
    // ---------------------------------------------------------------------

    val themes: List<ClientTheme> = listOf(
        ClientTheme("moonpurple", "Moon Purple", Color(78, 84, 200), Color(143, 148, 251)),
        ClientTheme("azure", "Azure", Color(0, 90, 255), Color(0, 180, 255)),
        ClientTheme("water", "Water", Color(35, 69, 148), Color(108, 170, 207)),
        ClientTheme("fire", "Fire", Color(255, 45, 30), Color(255, 123, 15)),
        ClientTheme("sunset", "Sunset", Color(255, 100, 50), Color(255, 180, 100)),
        ClientTheme("ocean", "Ocean", Color(0, 50, 100), Color(0, 150, 200)),
        ClientTheme("sun", "Sun", Color(255, 143, 0), Color(252, 205, 44)),
        ClientTheme("tree", "Tree", Color(18, 155, 38), Color(76, 255, 102)),
        ClientTheme("mint", "Mint", Color(85, 255, 140), Color(85, 255, 255)),
        ClientTheme("magic", "Magic", Color(255, 180, 255), Color(181, 139, 194)),
        ClientTheme("darknight", "Dark Night", Color(93, 95, 95), Color(203, 200, 204)),
        ClientTheme("noir", "Noir", Color(12, 12, 12), Color(230, 230, 230)),
        ClientTheme("monolith", "Monolith", Color(0, 0, 0), Color(255, 255, 255)),
        ClientTheme("obsidian", "Obsidian", Color(5, 5, 5), Color(40, 40, 45)),
        ClientTheme("terminal", "Terminal", Color(25, 30, 25), Color(15, 155, 15)),
        ClientTheme("matrix", "Matrix", Color(0, 10, 0), Color(0, 180, 0)),
        ClientTheme("vaporwave", "Vapor Wave", Color(20, 0, 40), Color(255, 100, 200)),
        ClientTheme("retrowave", "Retro Wave", Color(30, 0, 50), Color(255, 0, 110)),
        ClientTheme("y2k", "Y2K", Color(180, 120, 255), Color(255, 200, 230)),
        ClientTheme("cyberpink", "Cyber Pink", Color(20, 0, 30), Color(255, 50, 150)),
        ClientTheme("neoncrimson", "Neon Crimson", Color(10, 0, 15), Color(255, 20, 80)),
        ClientTheme("acidgreen", "Acid Green", Color(15, 15, 15), Color(57, 255, 20)),
        ClientTheme("aurora", "Aurora", Color(10, 15, 40), Color(80, 255, 180)),
        ClientTheme("biolum", "Bioluminescence", Color(0, 15, 20), Color(0, 255, 180)),
        ClientTheme("abyss", "Abyss", Color(0, 8, 20), Color(15, 80, 130)),
        ClientTheme("glacier", "Glacier", Color(30, 60, 90), Color(140, 200, 230)),
        ClientTheme("arctic", "Arctic", Color(200, 220, 235), Color(240, 248, 255)),
        ClientTheme("frost", "Frost", Color(180, 210, 230), Color(230, 245, 255)),
        ClientTheme("slate", "Slate", Color(40, 42, 54), Color(98, 114, 164)),
        ClientTheme("sublimevivid", "Sublime Vivid", Color(252, 70, 107), Color(63, 94, 251)),
        ClientTheme("quepal", "Quepal", Color(17, 153, 142), Color(56, 239, 125)),
        ClientTheme("stripe", "Stripe", Color(31, 162, 255), Color(166, 255, 203)),
        ClientTheme("reef", "Reef", Color(0, 210, 255), Color(58, 123, 213)),
        ClientTheme("amin", "Amin", Color(142, 45, 226), Color(74, 0, 224)),
        ClientTheme("magics", "Magics", Color(89, 193, 115), Color(93, 38, 193)),
        ClientTheme("eveningsunshine", "Evening Sunshine", Color(185, 43, 39), Color(21, 101, 192)),
        ClientTheme("lightorange", "Light Orange", Color(255, 183, 94), Color(237, 143, 3)),
        ClientTheme("shifter", "Shifter", Color(188, 78, 156), Color(248, 7, 89)),
        ClientTheme("moonasteroid", "Moon Asteroid", Color(15, 32, 39), Color(44, 83, 100)),
        ClientTheme("summerdog", "Summer Dog", Color(168, 255, 120), Color(120, 255, 214)),
        ClientTheme("sincityred", "Sin City Red", Color(237, 33, 58), Color(147, 41, 30)),
        ClientTheme("timber", "Timber", Color(252, 0, 255), Color(0, 219, 222)),
        ClientTheme("pinotnoir", "Pinot Noir", Color(75, 108, 183), Color(24, 40, 72)),
        ClientTheme("dirtyfog", "Dirty Fog", Color(185, 147, 214), Color(140, 166, 219)),
        ClientTheme("piglet", "Piglet", Color(238, 156, 167), Color(255, 221, 225)),
        ClientTheme("littleleaf", "Little Leaf", Color(118, 184, 82), Color(141, 194, 111)),
        ClientTheme("nelson", "Nelson", Color(242, 112, 156), Color(255, 148, 114)),
        ClientTheme("purplin", "Purplin", Color(106, 48, 147), Color(160, 68, 255)),
        ClientTheme("soundcloud", "SoundCloud", Color(254, 140, 0), Color(248, 54, 0)),
        ClientTheme("amethyst", "Amethyst", Color(157, 80, 187), Color(110, 72, 170)),
        ClientTheme("blush", "Blush", Color(178, 69, 146), Color(241, 95, 121)),
        ClientTheme("mocharose", "Mocha Rose", Color(245, 194, 231), Color(243, 139, 168)),
        ClientTheme("champagne", "Champagne", Color(60, 20, 25), Color(245, 215, 160)),
        ClientTheme("rosegold", "Rose Gold", Color(45, 20, 35), Color(220, 170, 160)),
        ClientTheme("evergreen", "Evergreen", Color(10, 30, 15), Color(80, 200, 100)),
        ClientTheme("dusk", "Dusk", Color(40, 15, 50), Color(220, 100, 50)),
        ClientTheme("dustyrose", "Dusty Rose", Color(180, 140, 150), Color(220, 190, 195)),
        ClientTheme("sage", "Sage", Color(140, 160, 140), Color(190, 210, 180)),
        ClientTheme("cloudburst", "Cloud Burst", Color(150, 160, 180), Color(200, 210, 220)),
        ClientTheme("bloodline", "Bloodline", Color(40, 0, 0), Color(200, 20, 20)),
        ClientTheme("lavender", "Lavender", Color(200, 180, 220), Color(230, 220, 240)),
        ClientTheme("phantom", "Phantom", Color(20, 25, 40), Color(100, 120, 180)),
        ClientTheme("quicksilver", "Quicksilver", Color(180, 190, 200), Color(220, 230, 240)),
        ClientTheme("tropical", "Tropical", Color(0, 80, 100), Color(255, 220, 50)),
        ClientTheme("mango", "Mango", Color(100, 30, 60), Color(255, 150, 50)),
        ClientTheme("rust", "Rust", Color(60, 30, 20), Color(180, 80, 30)),
        ClientTheme("concrete", "Concrete", Color(90, 90, 90), Color(130, 130, 130)),
        ClientTheme("nebula", "Nebula", Color(5, 0, 15), Color(120, 50, 180)),
        ClientTheme("supernova", "Supernova", Color(10, 10, 30), Color(100, 150, 255)),
        ClientTheme("eclipse", "Eclipse", Color(15, 5, 25), Color(80, 0, 120)),
        ClientTheme("iceberg", "Iceberg", Color(150, 200, 220), Color(220, 240, 250)),
        ClientTheme("scarlet", "Scarlet", Color(80, 10, 20), Color(230, 50, 50)),
        ClientTheme("emerald", "Emerald", Color(0, 80, 40), Color(80, 200, 120)),
        ClientTheme("sapphire", "Sapphire", Color(0, 30, 80), Color(50, 100, 200)),
        ClientTheme("ruby", "Ruby", Color(80, 10, 20), Color(220, 30, 60)),
        ClientTheme("topaz", "Topaz", Color(100, 60, 0), Color(255, 180, 50)),
        ClientTheme("jade", "Jade", Color(0, 80, 50), Color(100, 180, 120)),
        ClientTheme("turquoise", "Turquoise", Color(0, 80, 100), Color(50, 200, 220)),
        ClientTheme("coral", "Coral", Color(52, 133, 151), Color(244, 168, 150)),
        ClientTheme("peony", "Peony", Color(255, 120, 255), Color(255, 190, 255)),
        ClientTheme("vergren", "Verdant Green", Color(170, 255, 169), Color(17, 255, 189)),
        ClientTheme("mangopulp", "Mango Pulp", Color(240, 152, 25), Color(237, 222, 93)),
        ClientTheme("aqualicious", "Aqualicious", Color(80, 201, 195), Color(150, 222, 218)),
        ClientTheme("orca", "Orca", Color(68, 160, 141), Color(9, 54, 55)),
        ClientTheme("fdp", "FDP", Color(255, 100, 255), Color(100, 255, 255)),
        ClientTheme("may", "May", Color(255, 80, 255), Color(255, 255, 255)),
        ClientTheme("sundae", "Sundae", Color(28, 28, 27), Color(206, 74, 126)),
        ClientTheme("pumpkin", "Pumpkin", Color(255, 216, 169), Color(241, 166, 98)),
        ClientTheme("polarized", "Polarized", Color(0, 32, 64), Color(173, 239, 209)),
        ClientTheme("flower", "Flower", Color(184, 85, 199), Color(182, 140, 195)),
        ClientTheme("zywl", "Zywl", Color(206, 58, 98), Color(215, 171, 168)),
        ClientTheme("thunder", "Thunder", Color(50, 0, 100), Color(150, 100, 255)),
        ClientTheme("honey", "Honey", Color(200, 150, 50), Color(255, 220, 100)),
        ClientTheme("ice", "Ice", Color(150, 200, 255), Color(220, 240, 255)),
        ClientTheme("velvet", "Velvet", Color(100, 20, 40), Color(180, 60, 80)),
        ClientTheme("plum", "Plum", Color(80, 40, 100), Color(150, 80, 180)),
        ClientTheme("cherry", "Cherry", Color(200, 50, 80), Color(255, 150, 170)),
        // Dynamic themes
        ClientTheme("astolfo", "Astolfo", Color(255, 120, 180), Color(120, 180, 255)),
        ClientTheme("rainbow", "Rainbow", Color(255, 100, 100), Color(100, 180, 255))
    )

    /** Display names for settings dropdowns, registry order. */
    val themeNames: Array<String> = themes.map { it.displayName }.toTypedArray()

    /** Normalized key ("Moon Purple" -> "moonpurple") -> theme. Built once. */
    private val byKey: Map<String, ClientTheme> =
        themes.associateBy { it.key.lowercase().replace(" ", "") }

    private val unknownModes = HashSet<String>()

    private fun normalize(mode: String) = mode.lowercase().replace(" ", "")

    private fun parseHexColor(hexString: String): Color {
        val raw = hexString.replace("#", "")
        return try {
            val colorLong = raw.toLong(16)
            when (raw.length) {
                6 -> Color(colorLong.toInt() and 0xFFFFFF)
                8 -> Color((colorLong and 0xFFFFFFFF).toInt(), true)
                else -> defaultColor()
            }
        } catch (_: NumberFormatException) {
            defaultColor()
        }
    }

    private fun defaultColor(): Color = byKey["moonpurple"]!!.start

    private fun fadeValue(): Double = ThemeFadeSpeed / 5.0 * if (updown) 1 else -1

    /**
     * Resolve the two gradient colors of a theme mode. Handles "#hex",
     * the dynamic Astolfo/Rainbow themes and registry lookups by key or
     * display name. Unknown modes fall back to Moon Purple (logged once).
     */
    private fun resolvePair(mode: String): Pair<Color, Color> {
        if (mode.startsWith("#")) {
            val color = parseHexColor(mode)
            return color to color
        }

        return when (normalize(mode)) {
            "astolfo" ->
                ColorUtils.skyRainbow(0, 0.6f, 1f, 20000f / ThemeFadeSpeed) to
                    ColorUtils.skyRainbow(90, 0.6f, 1f, 20000f / ThemeFadeSpeed)
            "rainbow" ->
                ColorUtils.skyRainbow(0, 1f, 1f, 20000f / ThemeFadeSpeed) to
                    ColorUtils.skyRainbow(90, 1f, 1f, 20000f / ThemeFadeSpeed)
            else -> {
                val theme = byKey[normalize(mode)]
                if (theme != null) {
                    theme.start to theme.end
                } else {
                    if (unknownModes.add(normalize(mode))) {
                        System.err.println("[AirPlus] Unknown theme mode '$mode', falling back to Moon Purple")
                    }
                    val fallback = byKey["moonpurple"]!!
                    fallback.start to fallback.end
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Public color API
    // ---------------------------------------------------------------------

    /** Animated gradient color of the current theme. [index] shifts the phase. */
    @JvmStatic
    fun getColor(index: Int = 0): Color {
        // 帧内缓存：一帧内会调用数十次 getColor，resolvePair + mixColors 为纯计算，
        // 时间源是毫秒级 —— 同毫秒同 index 直接复用，动画精度不受影响
        val now = System.currentTimeMillis()
        val cached = cachedCurrentColor
        if (cached != null && cachedCurrentColorTime == now && cachedCurrentColorIndex == index) {
            return cached
        }

        val pair = resolvePair(ClientColorMode)
        val color = mixColors(pair.first, pair.second, fadeValue(), index)

        cachedCurrentColorTime = now
        cachedCurrentColorIndex = index
        cachedCurrentColor = color
        return color
    }

    private var cachedCurrentColorTime = Long.MIN_VALUE
    private var cachedCurrentColorIndex = Int.MIN_VALUE
    private var cachedCurrentColor: Color? = null

    /** Animated gradient color with a custom alpha. */
    @JvmStatic
    fun getColorWithAlpha(index: Int = 0, alpha: Int): Color =
        getColor(index).let { Color(it.red, it.green, it.blue, alpha.coerceIn(0, 255)) }

    /**
     * One end of the current theme gradient. [type] is "start" or "end"
     * (matching AirClient: "start" is the lighter second color).
     */
    @JvmStatic
    fun setColor(type: String, alpha: Int): Color {
        val pair = resolvePair(ClientColorMode)
        val color = if (type.equals("start", ignoreCase = true)) pair.second else pair.first
        return Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))
    }

    /** Animated gradient color of an arbitrary theme mode. */
    fun getColorForMode(mode: String, index: Int = 0): Color {
        if (mode.startsWith("#")) {
            return parseHexColor(mode)
        }

        // 与 getColor 相同的毫秒级帧内缓存
        val now = System.currentTimeMillis()
        val cached = cachedModeColor
        if (cached != null && cachedModeColorTime == now && cachedModeColorKey == mode to index) {
            return cached
        }

        val color = when (normalize(mode)) {
            "astolfo" -> ColorUtils.skyRainbow(index, 0.6f, 1f, 20000f / ThemeFadeSpeed)
            "rainbow" -> ColorUtils.skyRainbow(index, 1f, 1f, 20000f / ThemeFadeSpeed)
            else -> {
                val pair = resolvePair(mode)
                mixColors(pair.first, pair.second, fadeValue(), index)
            }
        }

        cachedModeColorTime = now
        cachedModeColorKey = mode to index
        cachedModeColor = color
        return color
    }

    private var cachedModeColorTime = Long.MIN_VALUE
    private var cachedModeColorKey: Pair<String, Int>? = null
    private var cachedModeColor: Color? = null

    /** Static (non animated) color pair of a theme, for previews and dots. */
    fun getThemeColorPair(mode: String): Pair<Color, Color> = resolvePair(mode)

    /** Display name of the current theme ("Moon Purple", "#ff5500", ...). */
    fun currentThemeName(): String {
        if (ClientColorMode.startsWith("#")) {
            return ClientColorMode
        }
        val theme = byKey[normalize(ClientColorMode)] ?: byKey["moonpurple"]!!
        return theme.displayName
    }

    /** GUI background color derived from [BackgroundMode]. */
    fun getBackgroundColor(index: Int = 0, alpha: Int = 255): Color {
        val m = BackgroundMode.lowercase()

        if (m.startsWith("#")) {
            return parseHexColor(m).let { Color(it.red, it.green, it.blue, alpha) }
        }

        return when (m) {
            "dark" -> Color(21, 21, 21, alpha)
            "synced" -> getColorWithAlpha(index, alpha).darker().darker()
            "custom" -> Color(customBgColor.red, customBgColor.green, customBgColor.blue, alpha)
            "neverlose" -> Color(neverLoseBgColor.red, neverLoseBgColor.green, neverLoseBgColor.blue, alpha)
            "none" -> Color(0, 0, 0, 0)
            else -> Color(21, 21, 21, alpha)
        }
    }
}
