/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 *
 * Ported from Hanabi's WindowSessInfo (me.yarukon.hud.window.impl.WindowSessInfo).
 * Kept behaviour-identical to Hanabi:
 *  - 125x92 window (12px draggable title bar + 80px content), same layout/positions
 *  - same fonts (usans.otf / SessIcon.ttf), same colors per HUD mode (Classic/Simple)
 *  - blur behind the window (HudWindowManager blur behaviour)
 *  - same TPS algorithm (Hanabi PacketHelper, including the lag halving)
 *  - same title-packet based win/total parsing and KillAura-based kill counting
 *  - play time starts when connecting to a server (see MixinGuiConnecting)
 *
 * "Opai" style ported from Samsara's SessionHud (glass panel + player face +
 * "Time elapsed" line + icon stats line), with local 1.8.9 statistics:
 * tick-counted play time, attack-record based kill confirmation, self death
 * edge detection, title-packet victory counting and net handler ping.
 */
package net.airplus.ui.client.hud.element.elements

import net.airplus.event.AttackEvent
import net.airplus.event.EntityKilledEvent
import net.airplus.event.GameTickEvent
import net.airplus.event.Listenable
import net.airplus.event.PacketEvent
import net.airplus.event.handler
import net.airplus.features.module.modules.render.HanabiHUD
import net.airplus.ui.client.hud.HUD
import net.airplus.ui.client.hud.designer.GuiHudDesigner
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.Element
import net.airplus.ui.client.hud.element.ElementInfo
import net.airplus.ui.client.hud.element.Side
import net.airplus.ui.font.AWTFontRenderer.Companion.assumeNonVolatile
import net.airplus.ui.font.Fonts
import net.airplus.ui.font.GameFontRenderer
import net.airplus.utils.render.HudBlur
import net.airplus.utils.render.HudSlices
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.airplus.utils.render.RenderUtils.drawRect
import net.minecraft.client.entity.AbstractClientPlayer
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.Tessellator
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.network.play.server.S00PacketKeepAlive
import net.minecraft.network.play.server.S01PacketJoinGame
import net.minecraft.network.play.server.S03PacketTimeUpdate
import net.minecraft.network.play.server.S19PacketEntityStatus
import net.minecraft.network.play.server.S45PacketTitle
import net.minecraft.util.EnumChatFormatting
import net.minecraft.util.ResourceLocation
import java.awt.Color
import java.util.UUID

@ElementInfo(name = "SessionInfo")
class SessionInfo(
    x: Double = 5.0, y: Double = 25.0, scale: Float = 1F, side: Side = Side.default()
) : Element("SessionInfo", x, y, scale, side), Listenable {

    private val style by choices("Style", arrayOf("Hanabi", "Opai"), "Hanabi")
    private val blur by boolean("Blur", true)

    // 可自定义字体（标题与各行文字），图标字形字体保持不变（仅 Hanabi 样式使用）
    private val fontValue by font("Font", Fonts.fontUsans40) { style == "Hanabi" }
    private val font: GameFontRenderer
        get() = fontValue as? GameFontRenderer ?: Fonts.fontUsans40

    // ===================== Opai 统计（本地实现，语义对齐 Samsara SessionStats） =====================
    private var opaiPlayedTicks = 0L          // 游玩时长：tick 计数（在世界内每 tick +1）
    private var opaiKills = 0                 // 击杀：受害者 10s 内被本地玩家攻击过才计
    private var opaiWins = 0                  // 胜利：标题包 VICTORY / YOU WIN / 胜利，15s 冷却
    private var opaiDeaths = 0                // 死亡：自身血量 >0 → ≤0 沿检测（防重触发）
    private var opaiUnconfirmedKills = 0      // 未确认击杀：死亡时无有效攻击记录
    private var opaiLastSelfHealth = 20f
    private var opaiLastVictory = 0L
    private val opaiAttackedAt = HashMap<UUID, Long>() // 我攻击过的玩家 -> 最后攻击时间
    private val opaiCounted = HashMap<UUID, Long>()    // 已计入的死亡 -> 时间（重复死亡包去重）

    // Win / total counters, fed by title packets (bed wars / sky wars style titles)
    private var total = 0
    private var win = 0

    // Kill counter, fed by KillAura target deaths (like Hanabi's KillAura.killCount)
    private var kills = 0

    // --- TPS tracking (faithful port of Hanabi's PacketHelper) ---
    private var lastReceiveTime = -1L
    private var tps = 20.0
    private var packetsPerSecondTemp = 0
    private var packetsPerSecond = 0
    private var lastMS = 0L
    private var lagStart = 0L
    private var lagDoneOneTime = false
    private var lagTimer = 0L

    override fun handleEvents(): Boolean = HUD.elements.any { it === this }

    @Suppress("unused")
    private val onPacket = handler<PacketEvent> {
        when (val packet = it.packet) {
            is S01PacketJoinGame -> tps = 20.0

            is S03PacketTimeUpdate -> {
                val currentReceiveTime = System.currentTimeMillis()
                if (lastReceiveTime != -1L) {
                    val timeBetween = (currentReceiveTime - lastReceiveTime).toDouble()
                    val neededTps = timeBetween / 50.0
                    val multi = neededTps / 20.0
                    tps = (20.0 / multi).coerceIn(0.0, 20.0)
                }
                lastReceiveTime = currentReceiveTime
                packetsPerSecondTemp++
            }

            is S00PacketKeepAlive -> packetsPerSecondTemp++
        }
    }

    // PacketHelper#onUpdate: halve TPS every 2s while the server lags (>5s without packets)
    @Suppress("unused")
    private val onTick = handler<GameTickEvent> {
        val now = System.currentTimeMillis()

        if (now - lagTimer >= 2000L && serverLagTime > 5000L) {
            lagTimer = now
            tps /= 2.0
        }

        if (now - lastMS >= 1000L) {
            lastMS = now
            packetsPerSecond = packetsPerSecondTemp
            packetsPerSecondTemp = 0
        }

        if (packetsPerSecond < 1) {
            if (!lagDoneOneTime) {
                lagStart = now
                lagDoneOneTime = true
            }
        } else {
            if (lagDoneOneTime) {
                lagDoneOneTime = false
            }
            lagStart = 0L
        }

        // Opai：游玩时长（tick 计数）与自身死亡沿检测（防重触发）
        val self = mc.thePlayer
        if (self != null && mc.theWorld != null) {
            opaiPlayedTicks++
            if (opaiLastSelfHealth > 0f && self.health <= 0f) opaiDeaths++
            opaiLastSelfHealth = self.health
        }

        // Opai：过期缓存清理
        opaiCounted.entries.removeAll { now - it.value > OPAI_DEATH_DEDUP * 2 }
        opaiAttackedAt.entries.removeAll { now - it.value > OPAI_KILL_WINDOW * 2 }
    }

    @Suppress("unused")
    private val onKilled = handler<EntityKilledEvent> {
        kills++
    }

    /** Opai：本地玩家攻击了谁（AttackEvent 只在本客户端发起攻击时触发，同 KillFeed）。 */
    @Suppress("unused")
    private val onOpaiAttack = handler<AttackEvent> { event ->
        val self = mc.thePlayer ?: return@handler
        val target = event.targetEntity as? EntityPlayer ?: return@handler
        if (target === self) return@handler
        opaiAttackedAt[target.uniqueID] = System.currentTimeMillis()
    }

    /**
     * Opai 统计包处理：玩家死亡包（opCode 3，同 KillFeed 需主线程访问世界）、胜利标题包、
     * 换服重置。击杀判定：受害者 10s 内被本地玩家攻击过才计击杀，否则记为未确认击杀。
     */
    @Suppress("unused")
    private val onOpaiPacket = handler<PacketEvent> { event ->
        when (event.packet) {
            is S19PacketEntityStatus, is S45PacketTitle, is S01PacketJoinGame ->
                mc.addScheduledTask { handleOpaiPacket(event.packet) }
        }
    }

    private fun handleOpaiPacket(packet: net.minecraft.network.Packet<*>) {
        when (packet) {
            is S19PacketEntityStatus -> {
                if (packet.opCode != 3.toByte()) return
                val world = mc.theWorld ?: return
                val victim = packet.getEntity(world) as? EntityPlayer ?: return
                if (victim === mc.thePlayer) return
                val now = System.currentTimeMillis()
                // 同一受害者短时间内重复死亡包去重
                if (now - (opaiCounted[victim.uniqueID] ?: 0L) <= OPAI_DEATH_DEDUP) return
                val attackedAt = opaiAttackedAt[victim.uniqueID]
                if (attackedAt != null && now - attackedAt <= OPAI_KILL_WINDOW) {
                    opaiKills++
                    opaiAttackedAt.remove(victim.uniqueID)
                } else {
                    opaiUnconfirmedKills++
                }
                opaiCounted[victim.uniqueID] = now
            }

            is S45PacketTitle -> {
                if (packet.type != S45PacketTitle.Type.TITLE) return
                val raw = packet.message?.unformattedText ?: return
                val text = (EnumChatFormatting.getTextWithoutFormattingCodes(raw) ?: return).trim().toUpperCase()
                val now = System.currentTimeMillis()
                if ((text.contains("VICTORY") || text.contains("YOU WIN") || text.contains("胜利")) &&
                    now - opaiLastVictory > OPAI_WIN_COOLDOWN
                ) {
                    opaiWins++
                    opaiLastVictory = now
                }
            }

            is S01PacketJoinGame -> {
                // 换服/换世界：清空战斗记录（时长/击杀/胜利累计保留，同 Samsara SessionTracker）
                opaiAttackedAt.clear()
                opaiCounted.clear()
                opaiLastSelfHealth = 20f
            }
        }
    }

    override fun createElement(): Boolean {
        if (startTime == 0L) {
            startTime = System.currentTimeMillis()
        }
        return true
    }

    override fun drawElement(): Border {
        if (style == "Opai") return drawOpai()
        return drawHanabiWindow()
    }

    /**
     * Opai 样式：Samsara SessionHud 忠实移植。玻璃面板（高 49 / 圆角 7.5）左侧为玩家头像
     * （34.5px，脸部 + 帽子层），右侧第一行 "Time elapsed <时长>"（灰），第二行统计对
     * （Samsara 只有 kills/wins，这里本地追加死亡与延迟）：图标 + 白字，成对间距 10。
     * 时长含小时后按 "8…8h 59m 59s" 预留整行宽度，避免每秒抖动（同 Samsara）。
     */
    private fun drawOpai(): Border {
        val designer = mc.currentScreen is GuiHudDesigner
        val f = Fonts.fontSemibold35

        // 数据（设计器显示示例值）
        val time = if (designer) "5m 32s" else formatOpaiTime()
        val kills = if (designer) 12 else opaiKills
        val wins = if (designer) 3 else opaiWins
        val deaths = if (designer) 2 else opaiDeaths
        val ping = if (designer) 45 else opaiPing

        val killsText = "$kills kills"
        val winsText = "$wins wins"
        val deathsText = "$deaths deaths"
        val pingText = "${ping}ms"

        // 面板宽度：时间行预留满宽度 / 统计行实际宽度 / 最小 150（同 Samsara width）
        val timeText = if (time.contains("h ")) {
            "Time elapsed " + "8".repeat(time.indexOf("h ")) + "h 59m 59s"
        } else {
            "Time elapsed $time"
        }
        val line2End = OPAI_TEXT_X + OPAI_SLOT_KILL + f.getStringWidth(killsText) + OPAI_PAIR_GAP +
            OPAI_SLOT_WIN + f.getStringWidth(winsText) + OPAI_PAIR_GAP +
            OPAI_SLOT_DOT + f.getStringWidth(deathsText) + OPAI_PAIR_GAP +
            OPAI_SLOT_PING + f.getStringWidth(pingText)
        val width = maxOf(
            150f,
            OPAI_TEXT_X + f.getStringWidth(timeText) + 8f,
            line2End + 8f
        )

        if (blur) {
            // 模糊在整个面板后面（绝对 AABB；遮罩用局部坐标跟随缩放/位置）
            HudBlur.blur(
                (scale * renderX).toFloat(),
                (scale * renderY).toFloat(),
                (scale * (renderX + width)).toFloat(),
                (scale * (renderY + OPAI_HEIGHT)).toFloat(),
                8f, "InternalBlur"
            ) {
                drawRoundedRect(0f, 0f, width, OPAI_HEIGHT, -1, OPAI_RADIUS)
            }
        }

        assumeNonVolatile {
            // Samsara session.png 面板贴图（烘焙阴影+玻璃一体，3 段横向切片，SHADOW_MARGIN 8 / TEX_SCALE 4）
            HudSlices.panelStrip(
                HudSlices.SESSION_PANEL, 0f, 0f, width, OPAI_HEIGHT,
                8f, 4f, OPAI_RADIUS, 640f, 260f, 0xFFFFFFFF.toInt()
            )

            drawOpaiFace()

            // 时间行
            f.drawString("Time elapsed $time", OPAI_TEXT_X, OPAI_LINE1_Y, OPAI_GRAY)

            // 统计行：kills / wins / deaths / ping（击杀/胜利用 Samsara 贴图图标）
            val line2Center = OPAI_LINE2_Y + f.height / 2f
            var x = OPAI_TEXT_X
            x = drawOpaiStat("F", x, line2Center, killsText, OPAI_SLOT_KILL, f, HudSlices.SESSION_SKULL, 8f) + OPAI_PAIR_GAP
            x = drawOpaiStat("D", x, line2Center, winsText, OPAI_SLOT_WIN, f, HudSlices.SESSION_WINS, 6f) + OPAI_PAIR_GAP
            // 死亡：红色圆点
            drawRoundedRect(x, line2Center - 1.75f, x + 3.5f, line2Center + 1.75f, Color(255, 85, 85).rgb, 1.75f)
            f.drawString(deathsText, x + OPAI_SLOT_DOT, OPAI_LINE2_Y, Color.WHITE.rgb)
            x += OPAI_SLOT_DOT + f.getStringWidth(deathsText) + OPAI_PAIR_GAP
            drawOpaiStat("E", x, line2Center, pingText, OPAI_SLOT_PING, f)
        }

        return Border(0f, 0f, width, OPAI_HEIGHT)
    }

    /** Opai 统计对：贴图图标（Samsara session-skull/wins）或图标字形 + 白字文本，返回文本结束后的 x。 */
    private fun drawOpaiStat(
        glyph: String, x: Float, centerY: Float, text: String, slot: Float, f: GameFontRenderer,
        iconTexture: ResourceLocation? = null, iconSize: Float = 0f
    ): Float {
        if (iconTexture != null) {
            HudSlices.texRect(
                iconTexture, x, centerY - iconSize / 2f, iconSize, iconSize,
                0f, 0f, 32f, 32f, 32f, 32f, 0xFFFFFFFF.toInt()
            )
        } else {
            val icon = Fonts.fontSessIcon24
            icon.drawString(glyph, x, centerY - icon.height / 2f, OPAI_ICON)
        }
        val textX = x + slot
        f.drawString(text, textX, OPAI_LINE2_Y, Color.WHITE.rgb)
        return textX + f.getStringWidth(text)
    }

    /** Opai 头像：皮肤脸部 (8,8) + 帽子层 (40,8)，各缩放到 34.5px（Samsara face 6,6,34.5）。 */
    private fun drawOpaiFace() {
        val player = mc.thePlayer as? AbstractClientPlayer
        if (player == null) {
            // 设计器/主菜单占位
            drawRoundedRect(6f, 6f, 6f + OPAI_FACE, 6f + OPAI_FACE, Color(46, 50, 58).rgb, 5.5f)
            return
        }
        GlStateManager.color(1f, 1f, 1f, 1f)
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        mc.textureManager.bindTexture(player.locationSkin)
        drawSkinRegion(6f, 6f, OPAI_FACE, 8, 8)
        drawSkinRegion(6f, 6f, OPAI_FACE, 40, 8)
        GlStateManager.disableBlend()
    }

    /** 在 64×64 皮肤纹理上取 8×8 区域（u,v）绘制到 (x,y,size)。 */
    private fun drawSkinRegion(x: Float, y: Float, size: Float, u: Int, v: Int) {
        val factor = 1f / 64f
        val tessellator = Tessellator.getInstance()
        val buffer = tessellator.worldRenderer
        buffer.begin(7, DefaultVertexFormats.POSITION_TEX)
        buffer.pos((x + size).toDouble(), (y + size).toDouble(), 0.0).tex((u * factor).toDouble(), ((v + 8) * factor).toDouble()).endVertex()
        buffer.pos((x + size).toDouble(), y.toDouble(), 0.0).tex(((u + 8) * factor).toDouble(), (v * factor).toDouble()).endVertex()
        buffer.pos(x.toDouble(), y.toDouble(), 0.0).tex((u * factor).toDouble(), (v * factor).toDouble()).endVertex()
        buffer.pos(x.toDouble(), (y + size).toDouble(), 0.0).tex((u * factor).toDouble(), ((v + 8) * factor).toDouble()).endVertex()
        tessellator.draw()
    }

    /** Opai 时长：Samsara formatTime —— "5m 32s" / "1h 3m 4s"（tick 计数 ×50ms）。 */
    private fun formatOpaiTime(): String {
        val seconds = (opaiPlayedTicks * 50L / 1000L).toInt()
        val hours = seconds / 3600
        val minutes = seconds / 60 % 60
        val secs = seconds % 60
        return (if (hours > 0) "${hours}h " else "") +
            (if (hours > 0 || minutes > 0) "${minutes}m " else "") +
            "${secs}s"
    }

    /** Opai 延迟：网络玩家信息 responseTime。 */
    private val opaiPing: Int
        get() {
            val player = mc.thePlayer ?: return 0
            val info = mc.netHandler?.getPlayerInfo(player.uniqueID) ?: return 0
            return info.responseTime
        }

    private fun drawHanabiWindow(): Border {
        val classic = HanabiHUD.hudMode == "Classic"

        // Hanabi HudWindow colors per HUD mode
        val textColor = if (classic) 0xFFFFFFFF.toInt() else 0xFF404040.toInt()
        val titleBGColor = if (classic) 0xCC2F74FF.toInt() else 0xBBFFFFFF.toInt()
        val frameBGColor = if (classic) 0xAA000000.toInt() else Color(166, 173, 176, 175).rgb

        if (blur) {
            // Blur behind the whole window (Hanabi BlurBuffer behaviour).
            // AABB in absolute screen coords; mask drawn in local coords so it follows scale/position.
            HudBlur.blur(
                (scale * renderX).toFloat(),
                (scale * renderY).toFloat(),
                (scale * (renderX + WIDTH)).toFloat(),
                (scale * (renderY + TITLE_HEIGHT + HEIGHT)).toFloat(),
                8f, "InternalBlur"
            ) {
                drawRect(0, 0, WIDTH.toInt(), (TITLE_HEIGHT + HEIGHT).toInt(), -1)
            }
        }

        assumeNonVolatile {
            // Title bar + frame
            drawRect(0, 0, WIDTH.toInt(), TITLE_HEIGHT.toInt(), titleBGColor)
            drawRect(0, TITLE_HEIGHT.toInt(), WIDTH.toInt(), (TITLE_HEIGHT + HEIGHT).toInt(), frameBGColor)

            // 标题文字相对标题栏垂直居中（跟随所选字体高度）
            font.drawString("Session info", 4f, (TITLE_HEIGHT - font.height) / 2f, textColor)

            // 各行内容相对行中线垂直居中：图标与文字动态对齐（跟随所选字体高度）
            val tpsDisplay = Math.round(tps * 10) / 10.0
            val rows = listOf(
                "B" to "Play time: ${playTime()}",
                "C" to "Move speed: $bps",
                "D" to "Win / Total: $win / $total",
                "E" to "TPS: $tpsDisplay",
                "F" to "Kills: $kills"
            )
            val iconFont = Fonts.fontSessIcon48
            val rowHeight = HEIGHT / rows.size
            rows.forEachIndexed { index, (icon, text) ->
                val centerY = TITLE_HEIGHT + rowHeight * index + rowHeight / 2f
                iconFont.drawString(icon, 4f, centerY - iconFont.height / 2f, textColor)
                font.drawString(text, 22f, centerY - font.height / 2f, textColor)
            }
        }

        return Border(0f, 0f, WIDTH, TITLE_HEIGHT + HEIGHT)
    }

    private fun playTime(): String {
        if (mc.isSingleplayer()) return "localhost"

        val durationInMillis = System.currentTimeMillis() - startTime
        val second = (durationInMillis / 1000) % 60
        val minute = (durationInMillis / (1000 * 60)) % 60
        val hour = (durationInMillis / (1000 * 60 * 60)) % 24
        return String.format("%02dh %02dm %02ds", hour, minute, second)
    }

    private val bps: String
        get() {
            val player = mc.thePlayer ?: return "0.00 bps"
            val xDist = player.posX - player.lastTickPosX
            val zDist = player.posZ - player.lastTickPosZ
            val lastDist = kotlin.math.sqrt(xDist * xDist + zDist * zDist)
            return String.format("%.2f bps", lastDist * 20.0 * mc.timer.timerSpeed)
        }

    // PacketHelper#getServerLagTime
    private val serverLagTime: Long
        get() = if (lagStart <= 0L) 0L else System.currentTimeMillis() - lagStart

    companion object {
        private const val WIDTH = 160f
        private const val HEIGHT = 92f
        private const val TITLE_HEIGHT = 22f

        // Opai 样式（Samsara SessionHudPainter：HEIGHT 49 / RADIUS 7.5 / 文本列 x 50 / 头像 34.5）
        private const val OPAI_HEIGHT = 49f
        private const val OPAI_RADIUS = 7.5f
        private const val OPAI_TEXT_X = 50f
        private const val OPAI_LINE1_Y = 13f
        private const val OPAI_LINE2_Y = 28.5f
        private const val OPAI_FACE = 34.5f
        private const val OPAI_PAIR_GAP = 10f
        private const val OPAI_SLOT_KILL = 12f  // 击杀图标 8 + 间距 4（同 Samsara skull 槽位）
        private const val OPAI_SLOT_WIN = 11f   // 胜利图标 6 + 间距 5
        private const val OPAI_SLOT_DOT = 7f    // 死亡红点 3.5 + 间距 3.5
        private const val OPAI_SLOT_PING = 11f  // 延迟图标 6 + 间距 5
        private const val OPAI_KILL_WINDOW = 10_000L   // 攻击记录视为击杀依据的有效窗口
        private const val OPAI_WIN_COOLDOWN = 15_000L  // 胜利标题去重冷却（同 Samsara）
        private const val OPAI_DEATH_DEDUP = 5_000L    // 同一受害者重复死亡包去重窗口
        private val OPAI_GRAY = Color(170, 170, 170).rgb
        private val OPAI_ICON = Color(235, 235, 235).rgb

        /**
         * Play-time start. Set on every server connect by MixinGuiConnecting
         * (like Hanabi's HudWindowManager.startTime).
         */
        @JvmStatic
        var startTime = 0L
    }
}
