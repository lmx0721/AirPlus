/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.world

import net.airplus.event.AttackEvent
import net.airplus.event.GameTickEvent
import net.airplus.event.WorldEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.utils.timing.MSTimer
import java.util.concurrent.ThreadLocalRandom

/**
 * 击杀嘲讽模块，支持 Southside 连杀播报 / 欣欣公益 / 自定义三种模式。
 */
object AutoLFix : Module("AutoLFix", Category.COMBAT) {

    private val mode by choices("Mode", arrayOf("Southside", "Xinxin", "Rise", "Custom"), "Xinxin")
    private val fake by boolean("fake", true)
    private val customPrefix by boolean("TargetPrefix", true) { mode == "Custom" }
    private val customMessage by text("CustomMessage", "你已被 AirPlus 客户端击毙") { mode == "Custom" }

    private val xinxinMessages = arrayOf(
        "你怎么被欣欣最强的客户端拷打了呢 我们也有布吉岛客户端呢 免费获取点击右边代码",
        "这就是花雨庭最强对刀客户端 我们也有布吉岛客户端呢 免费获取点击右边代码",
        "你的付费客户端怎么打不过欣欣公益呢 我们也有布吉岛客户端呢 免费获取点击右边代码",
        "现在知道啥是花雨庭最强金苹果了吧 我们也有布吉岛客户端呢 免费获取点击右边代码",
        "花雨庭第一大端 用户最多 我们也有布吉岛客户端呢 客户端最稳定且最暴力 免费获取点击右边代码",
        "你也是公益客户端 为什么不来用最强的欣欣啊 我们也有布吉岛客户端呢 免费获取点击右边代码",
        "你还不知道欣欣公益无需脱盒吗 无需脱盒工具箱 我们也有布吉岛客户端呢 免费获取点击右边代码",
        "全网首发空岛破甲 我们也有布吉岛客户端呢 客户端最稳定且最暴力 免费获取点击右边代码",
        "全网首发起床跑吃 我们也有布吉岛客户端呢 客户端最稳定且最暴力 免费获取点击右边代码",
        "全网首发无需盒 我们也有布吉岛客户端呢 免费获取点击右边代码",
    )

    // Rise 客户端 Insults 嘲讽文本（%s 会被替换为被击杀玩家名）
    private val riseInsults = arrayOf(
        "Wow! My combo is Rise'n!",
        "Why would someone as bad as you %s not use Rise 6.0?",
        "Here's your ticket to spectator from Rise 6.0, %s!",
        "I see you're a pay to lose player, %s, huh?",
        "Do you need some PvP advice, %s? Well Rise 6.0 is all you need.",
        "Hey %s! Wise up, don't waste another day without Rise.",
        "%s, you didn't even stand a chance against Rise.",
        "%s, we regret to inform you that your free trial of life has unfortunately expired.",
        "RISE against other cheaters by getting Rise, %s!",
        "You can pay for that loss by getting Rise, %s.",
        "%s, remember to use hand sanitizer to get rid of bacteria like you!",
        "Hey %s, try not to drown in your own salt.",
        "Having problems with forgetting to left click, %s? Rise 6.0 can fix it!",
        "Come on %s, is that all you have against Rise 6.0?",
        "Rise up today by getting Rise 6.0, %s!",
        "Get Rise, %s, you need it.",
        "how about you rise up to heaven by ending it, %s",
        "Did you know Watchdog has banned 6346 players in the last 7 days, %s."
    )

    private var target: net.minecraft.entity.Entity? = null

    private val timeHelper = MSTimer()

    private var kill = 0

    val onAttack = handler<AttackEvent> { event ->
        target = event.targetEntity
    }

    val onTick = handler<GameTickEvent> {
        val currentTarget = target ?: return@handler
        if (currentTarget.isDead) {
            kill++
            if (timeHelper.hasTimePassed(1000)) {
                val message = buildMessage(currentTarget.name)
                if (message != null && !fake) {
                    mc.thePlayer?.sendChatMessage(message)
                }
                timeHelper.reset()
            }
            target = null
        }
    }

    private fun buildMessage(victimName: String): String = when (mode) {
        "Southside" -> {
            val prefix = when {
                kill <= 1 -> "一破，卧龙出山,"
                kill == 2 -> "双连,一战成名,"
                kill == 3 -> "三连,举世皆惊,"
                kill == 4 -> "四连,天下无敌,"
                else -> "五连,诛天灭地,"
            }
            "${prefix}${victimName}你已被 southside 客户端击毙"
        }
        "Xinxin" -> victimName + xinxinMessages[ThreadLocalRandom.current().nextInt(xinxinMessages.size)]
        "Rise" -> String.format(riseInsults[ThreadLocalRandom.current().nextInt(riseInsults.size)], victimName)
        else -> (if (customPrefix) victimName else "") + customMessage
    }

    val onWorld = handler<WorldEvent> {
        target = null
        kill = 0
        timeHelper.reset()
    }
}
