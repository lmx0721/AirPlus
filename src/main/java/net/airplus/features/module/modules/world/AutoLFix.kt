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

    private val mode by choices("Mode", arrayOf("Southside", "Xinxin", "Custom"), "Xinxin")
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
        else -> (if (customPrefix) victimName else "") + customMessage
    }

    val onWorld = handler<WorldEvent> {
        target = null
        kill = 0
        timeHelper.reset()
    }
}
