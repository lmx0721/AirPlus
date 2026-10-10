/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.ui.client.hud.element.elements

import net.airplus.event.Listenable
import net.airplus.event.PacketEvent
import net.airplus.event.handler
import net.airplus.ui.client.hud.HUD
import net.airplus.ui.client.hud.designer.GuiHudDesigner
import net.airplus.ui.client.hud.element.Border
import net.airplus.ui.client.hud.element.Element
import net.airplus.ui.client.hud.element.ElementInfo
import net.airplus.ui.client.hud.element.Side
import net.airplus.ui.font.Fonts
import net.airplus.ui.font.GameFontRenderer
import net.airplus.utils.client.ClientThemesUtils
import net.airplus.utils.extensions.lerpWith
import net.airplus.utils.render.ColorUtils.withAlpha
import net.airplus.utils.render.RenderUtils.drawRoundedBorder
import net.airplus.utils.render.RenderUtils.drawRoundedRect
import net.airplus.utils.render.RenderUtils.deltaTimeNormalized
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.network.play.server.S01PacketJoinGame
import net.minecraft.network.play.server.S02PacketChat
import net.minecraft.network.play.server.S19PacketEntityStatus
import net.minecraft.util.EnumChatFormatting
import java.awt.Color
import kotlin.math.max

/**
 * 击杀播报 HUD 元素
 *
 * 岛系胶囊风格：右侧胶囊堆叠，新条目从右弹簧滑入（back-out 过冲），5s 后淡出；
 * 击杀动词使用主题色，自己参与时名字高亮为主题色。
 *
 * 数据来源与去重（用户要求：检查 entity_event / player_chat，不要重复播报）：
 * - entity_event：S19PacketEntityStatus opCode 3 = 实体死亡。只有受害者没有凶手，
 *   先挂起 PENDING_DELAY，超时仍无聊天佐证则播报无凶手版本。
 * - player_chat：S02PacketChat 解析死亡消息（英文原版 / 常见中文服务器句式），
 *   含凶手信息，作为权威来源立即播报，并吃掉对应的挂起死亡。
 * - 同一受害者 MERGE_WINDOW 内只保留一条：聊天晚到时合并进无凶手条目；
 *   死亡包晚到时查 recentChatDeaths 跳过；重复聊天广播按受害者 1.2s 内去重。
 */
@ElementInfo(name = "KillFeed", single = true)
class KillFeed(
    x: Double = 0.0, y: Double = 110.0, scale: Float = 1F, side: Side = Side(Side.Horizontal.RIGHT, Side.Vertical.UP)
) : Element("KillFeed", x, y, scale, side), Listenable {

    private val lang by choices("Language", arrayOf("Chinese", "English"), "Chinese")
    private val stay by int("StaySeconds", 5, 2..15)
    private val maxEntries by int("MaxEntries", 5, 1..10)
    private val bg by color("BackgroundColor", Color(26, 30, 37, 205))
    private val renderBorder by boolean("RenderBorder", true)
    private val borderColor by color("BorderColor", Color(255, 255, 255, 34)) { renderBorder }

    private val fontValue by font("Font", Fonts.fontRegular35)
    private val font: GameFontRenderer
        get() = fontValue as? GameFontRenderer ?: Fonts.fontRegular35

    // 播报条目（新条目插入头部，index 越小越靠上）
    private val entries = mutableListOf<KillEntry>()

    // 死亡状态包挂起：victim -> 收包时间
    private val pendingDeaths = linkedMapOf<String, Long>()

    // 聊天源最近播报：victim -> 时间（用于死亡包晚到时去重）
    private val recentChatDeaths = hashMapOf<String, Long>()

    private class KillEntry(var killer: String, val victim: String, val born: Long) {
        var animY = 0F
    }

    override fun handleEvents(): Boolean = HUD.elements.any { it === this }

    override fun updateElement() {
        if (mc.currentScreen is GuiHudDesigner) return
        resolvePending()
    }

    // 设计器里展示的固定示例条目（持久化，避免每帧重建导致 animY 归零抽搐）
    private var designerSamples: List<KillEntry>? = null

    @Suppress("unused")
    private val onPacket = handler<PacketEvent> { event ->
        // PacketEvent 在 netty 线程触发；entries/pendingDeaths 与主线程渲染共享，
        // 必须转发到主线程处理，否则并发修改会导致条目丢失/绘制异常
        mc.addScheduledTask { processPacket(event.packet) }
    }

    private fun processPacket(packet: net.minecraft.network.Packet<*>) {
        when (packet) {
            // entity_event：实体状态包，opCode 3 = 死亡
            is S19PacketEntityStatus -> {
                if (packet.opCode == 3.toByte()) {
                    val world = mc.theWorld ?: return
                    val entity = packet.getEntity(world) as? EntityPlayer ?: return
                    onDeathPacket(cleanTag(EnumChatFormatting.getTextWithoutFormattingCodes(entity.name) ?: entity.name))
                }
            }

            // player_chat：聊天包，解析死亡消息（动作栏 GAME_INFO 不解析）
            is S02PacketChat -> {
                // type 2 = GAME_INFO（动作栏消息），不参与死亡消息解析
                if (packet.type == 2.toByte()) return
                val text = packet.chatComponent?.unformattedText ?: return
                parseDeathMessage(text)?.let { (killer, victim) -> onChatKill(killer, victim) }
            }

            // 换服/重连后清空状态
            is S01PacketJoinGame -> {
                entries.clear()
                pendingDeaths.clear()
                recentChatDeaths.clear()
            }
        }
    }

    // ---------------- 数据源处理 ----------------

    /** entity_event 死亡包：先挂起，等待聊天佐证 */
    private fun onDeathPacket(victim: String) {
        if (victim.isBlank()) return
        val now = System.currentTimeMillis()

        // 聊天包已经播报过（死亡包晚到）→ 跳过
        recentChatDeaths[victim]?.let { if (now - it <= MERGE_WINDOW) return }
        // 重复的死亡状态包 → 跳过
        pendingDeaths[victim]?.let { if (now - it <= MERGE_WINDOW) return }

        pendingDeaths[victim] = now
    }

    /** player_chat 死亡消息：权威来源，立即播报 */
    private fun onChatKill(killer: String, victim: String) {
        if (victim.isBlank()) return
        val now = System.currentTimeMillis()

        // 同一受害者短时间内重复的聊天广播 → 跳过
        recentChatDeaths[victim]?.let { if (now - it <= DUPLICATE_CHAT) return }
        recentChatDeaths[victim] = now

        // 吃掉对应的挂起死亡
        pendingDeaths.keys.removeAll { it.equals(victim, ignoreCase = true) }

        addEntry(killer, victim, now)
    }

    /** 挂起超时的死亡包：仍无聊天佐证 → 播报无凶手版本 */
    private fun resolvePending() {
        val now = System.currentTimeMillis()

        pendingDeaths.entries.filter { now - it.value > PENDING_DELAY }.forEach { (victim, _) ->
            pendingDeaths.remove(victim)
            addEntry("", victim, now)
        }

        // 清理过期缓存
        recentChatDeaths.entries.removeAll { now - it.value > MERGE_WINDOW * 2 }
    }

    /** 合并去重后添加播报条目 */
    private fun addEntry(killer: String, victim: String, time: Long) {
        // 同一受害者 MERGE_WINDOW 内已有条目 → 合并（补全凶手信息）而不是重复播报
        val existing = entries.firstOrNull { it.victim.equals(victim, ignoreCase = true) && time - it.born <= MERGE_WINDOW }
        if (existing != null) {
            if (existing.killer.isEmpty() && killer.isNotEmpty()) existing.killer = killer
            return
        }

        entries.add(0, KillEntry(killer, victim, time))
        while (entries.size > maxEntries) entries.removeAt(entries.lastIndex)
    }

    // ---------------- 聊天死亡消息解析 ----------------

    private fun parseDeathMessage(raw: String): Pair<String, String>? {
        val text = (EnumChatFormatting.getTextWithoutFormattingCodes(raw) ?: return null).trim()
        if (text.isEmpty()) return null

        // 去掉服务器前缀标签（[VIP] [Lvl 12] xxx was slain by ...）
        val stripped = PREFIX_TAGS.replace(text, "").trim()

        for (candidate in arrayOf(stripped, text)) {
            for (pattern in PATTERNS) {
                val m = pattern.regex.find(candidate) ?: continue
                val killerRaw = (if (pattern.killerFirst) m.groupValues[1] else m.groupValues[2]).trim()
                val victimRaw = (if (pattern.killerFirst) m.groupValues[2] else m.groupValues[1]).trim()
                if (killerRaw.length > 32 || victimRaw.length > 32) continue

                val fixed = fixNames(killerRaw, victimRaw) ?: continue
                return fixed
            }
        }
        return null
    }

    private fun fixNames(killerRaw: String, victimRaw: String): Pair<String, String>? {
        // 去掉凶手名尾部的 " using xxx"（武器说明）
        var killer = USING_SUFFIX.replace(cleanTag(killerRaw), "").trim()
        var victim = cleanTag(victimRaw)

        val self = mc.thePlayer?.name
        if (self != null) {
            if (victim.equals("You", ignoreCase = true) || victim == "你") victim = self
            if (killer.equals("You", ignoreCase = true) || killer == "你") killer = self
        }

        if (victim.isBlank()) return null
        return killer to victim
    }

    /** 去掉名字上的装饰：前后 [标签]、装饰符号、句尾标点 */
    private fun cleanTag(name: String): String {
        var n = LEADING_TAGS.replace(name.trim(), "").trim()
        n = TRAILING_TAG.replace(n, "").trim()
        return n.trimEnd('.', '，', ',', '!', '！', '。')
    }

    // ---------------- 渲染 ----------------

    override fun drawElement(): Border? {
        val designer = mc.currentScreen is GuiHudDesigner
        val now = System.currentTimeMillis()
        val stayMs = stay * 1000L
        val f = font
        val rowH = f.height + 12F

        if (!designer) {
            entries.removeAll { now - it.born > stayMs + OUT_MS }
        }

        // 设计器里展示固定示例条目（只创建一次，入场动画播完后保持静止）
        val list: List<KillEntry> = if (designer) {
            designerSamples ?: listOf(
                KillEntry("Steve", "Alex", now - 500L),
                KillEntry("Dream", "Noob", now - 400L),
                KillEntry("", "Notch", now - 300L)
            ).also { designerSamples = it }
        } else {
            entries
        }
        if (list.isEmpty()) return null

        // 布局：目标行位置平滑靠拢（条目移除时其余条目上滑）
        var maxW = 0F
        list.forEachIndexed { index, entry ->
            entry.animY = (entry.animY..(index * (rowH + GAP))).lerpWith(deltaTimeNormalized().toFloat())
            maxW = max(maxW, measureEntry(entry, f))
        }

        val accent = ClientThemesUtils.getColor()
        val selfName = mc.thePlayer?.name ?: ""
        val zh = lang == "Chinese"
        val normalText = Color(235, 238, 243)

        list.forEach { entry ->
            val age = now - entry.born

            // 入场：back-out 弹簧滑入（带过冲）
            val inT = (age / IN_MS.toFloat()).coerceIn(0F, 1F)
            val slide = (1F - backOut(inT)) * 18F

            // 透明度：快速淡入，停留 stay 秒后淡出
            var alpha = if (designer) 1F else (age / 120F).coerceIn(0F, 1F)
            val outAge = age - stayMs
            if (!designer && outAge > 0F) alpha *= 1F - (outAge / OUT_MS.toFloat()).coerceIn(0F, 1F)
            if (alpha <= 0F) return@forEach

            val a = (alpha * 255).toInt()
            val w = measureEntry(entry, f)
            val left = -w + slide
            val top = entry.animY

            // 胶囊背景 + 细描边
            drawRoundedRect(left, top, left + w, top + rowH, bg.withAlpha((bg.alpha * alpha).toInt()).rgb, rowH / 2F)
            if (renderBorder) {
                drawRoundedBorder(
                    left, top, left + w, top + rowH,
                    1F, borderColor.withAlpha((borderColor.alpha * alpha).toInt()).rgb, rowH / 2F
                )
            }

            // 文本：killer 击杀 victim（凶手缺失则播报 victim 阵亡）
            val textY = top + (rowH - f.height) / 2F
            var x = left + PAD_H

            if (entry.killer.isNotEmpty()) {
                val killerColor = if (entry.killer == selfName) accent else normalText
                x = drawText(entry.killer, x, textY, killerColor.withAlpha(a), f) + 4F
                x = drawText(if (zh) "击杀" else "killed", x, textY, accent.withAlpha(a), f) + 4F
                val victimColor = if (entry.victim == selfName) accent else normalText
                drawText(entry.victim, x, textY, victimColor.withAlpha(a), f)
            } else {
                val victimColor = if (entry.victim == selfName) accent else normalText
                x = drawText(entry.victim, x, textY, victimColor.withAlpha(a), f) + 4F
                drawText(if (zh) "阵亡" else "died", x, textY, accent.withAlpha(a), f)
            }
        }

        val totalH = list.size * (rowH + GAP) - GAP
        return Border(-(maxW + 8F), 0F, 0F, totalH)
    }

    /** 绘制一段文字并返回结束 x 坐标 */
    private fun drawText(text: String, x: Float, y: Float, color: Color, f: GameFontRenderer): Float {
        f.drawString(text, x, y, color.rgb)
        return x + f.getStringWidth(text)
    }

    private fun measureEntry(entry: KillEntry, f: GameFontRenderer): Float {
        val zh = lang == "Chinese"
        var w = PAD_H * 2
        if (entry.killer.isNotEmpty()) {
            w += f.getStringWidth(entry.killer) + 4F + f.getStringWidth(if (zh) "击杀" else "killed") + 4F
        } else {
            w += f.getStringWidth(entry.victim) + 4F + f.getStringWidth(if (zh) "阵亡" else "died")
        }
        w += f.getStringWidth(entry.victim)
        return w
    }

    /** back-out 缓动：末段过冲再回弹，与灵动岛 spring 手感一致 */
    private fun backOut(t: Float): Float {
        val c1 = 1.70158F
        val c3 = c1 + 1F
        val x = t - 1F
        return 1F + c3 * x * x * x + c1 * x * x
    }

    companion object {
        // 常量
        private const val GAP = 6F          // 胶囊间距
        private const val PAD_H = 12F       // 胶囊水平内边距
        private const val IN_MS = 340L      // 滑入时长
        private const val OUT_MS = 260L     // 淡出时长
        private const val MERGE_WINDOW = 2000L      // 死亡包 ↔ 聊天包合并窗口
        private const val DUPLICATE_CHAT = 1200L    // 重复聊天广播去重窗口
        private const val PENDING_DELAY = 600L      // 死亡包等待聊天佐证的挂起时长

        // 服务器前缀标签（可叠加）：[VIP] [Lvl 12] xxx ...
        private val PREFIX_TAGS = Regex("^(\\[[^\\]]{1,24}]\\s*)+")
        // 名字首尾的装饰标签
        private val LEADING_TAGS = Regex("^(\\[[^\\]]{1,24}]\\s*)+")
        private val TRAILING_TAG = Regex("\\s*\\[[^\\]]{1,30}]$")
        // 凶手名尾部武器说明：was slain by Alex using [Item]
        private val USING_SUFFIX = Regex("\\s+using\\s+.+$")

        // 死亡消息句式（killerFirst：第 1 组是凶手，否则第 2 组是凶手）
        private val PATTERNS = arrayOf(
            // --- 英文原版（凶手在后） ---
            P(Regex("(.+?) (?:was|got) slain by (.+)"), false),
            P(Regex("(.+?) was shot by (.+)"), false),
            P(Regex("(.+?) was killed by (.+)"), false),
            P(Regex("(.+?) got finished off by (.+)"), false),
            P(Regex("(.+?) was blown up by (.+)"), false),
            P(Regex("(.+?) was fireballed by (.+)"), false),
            P(Regex("(.+?) was pummeled by (.+)"), false),
            P(Regex("(.+?) was stung to death by (.+)"), false),
            P(Regex("(.+?) was knocked into the void by (.+)"), false),
            P(Regex("(.+?) was doomed to fall by (.+)"), false),
            P(Regex("(.+?) hit the ground too hard whilst trying to escape (.+)"), false),
            P(Regex("(.+?) was squashed by (.+)"), false),
            // --- 英文兜底 ---
            P(Regex("(.+?) (?:was|got) .+ by (.+)"), false),
            // --- 中文服务器（两个方向） ---
            P(Regex("(.+?) (?:击杀了?|干掉了?|秒杀了?|带走了?|终结了?) ?(.+)"), true),
            P(Regex("(.+?) 被 ?(.+?) (?:击杀了?|干掉了?|秒杀了?|杀死了?|带走了?|终结了?|送回了出生点)"), false),
            P(Regex("(.+?) 被 ?(.+) 杀(?:死|害|了)"), false)
        )

        private data class P(val regex: Regex, val killerFirst: Boolean)
    }
}
