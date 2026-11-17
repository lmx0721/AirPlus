/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.movement

import net.airplus.event.EventState
import net.airplus.event.Listenable
import net.airplus.event.MotionEvent
import net.airplus.event.PacketEvent
import net.airplus.event.SlowDownEvent
import net.airplus.event.UpdateEvent
import net.airplus.event.handler
import net.airplus.utils.skid.rise.RiseGrimUtils
import net.airplus.utils.skid.rise.RiseGrimUtils.moveFlying
import net.airplus.utils.skid.rise.RiseGrimUtils.sendSlotSwap
import net.airplus.utils.skid.rise.RiseGrimUtils.sendViaUseItem
import net.airplus.utils.skid.rise.RiseGrimUtils.strafe
import net.airplus.utils.client.MinecraftInstance
import net.airplus.utils.client.chat
import net.airplus.utils.inventory.SilentHotbar
import net.airplus.utils.rotation.Rotation
import net.airplus.utils.rotation.RotationUtils
import net.airplus.viaversion.vialoadingbase.ViaLoadingBase
import net.minecraft.item.Item
import net.minecraft.item.ItemBow
import net.minecraft.item.ItemBucketMilk
import net.minecraft.item.ItemFood
import net.minecraft.item.ItemPotion
import net.minecraft.item.ItemSword
import net.minecraft.network.play.client.C0FPacketConfirmTransaction
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion

/**
 * Rise NoSlow（Ported from Rise GrimNoSlow / Grim19NoSlow / Grim30NoSlow）。
 *
 * NoSlow 模块按物品类别分三个模式选择（SwordMode / ConsumeMode / BowMode），
 * 选择 "Rise" 后由本对象按 [NoSlow.riseMode] 分派：
 * - Grim   ：PreMotion（MotionEvent PRE）时对正在使用的物品做"换手重置"（两个 C09 槽位包）；
 * - Grim19 ：PreMotion 时通过 ViaVersion 发送 1.9+ 的 USE_ITEM 包（非 1.9 提示）；
 * - Grim30 ：UpdateEvent 45° 旋转 + web strafe(0.64) + moveFlying 微加速 + 强制疾跑 +
 *            Heypixel C0F 取消 + SlowDown 奇偶取消。
 *
 * Rise 原实现的 food/potion/sword/bow 四个布尔在 airplus 中由"对应类别的模式是否为
 * Rise"（[isRiseCategory]）代替。
 */
object NoSlowRise : Listenable, MinecraftInstance {

    // Grim19
    private var useTicks = 0

    // Grim30
    private var usingItemTicks = 0

    private val active
        get() = NoSlow.handleEvents() &&
            (NoSlow.swordMode == "Rise" || NoSlow.consumeMode == "Rise" || NoSlow.bowPacket == "Rise")

    private val isGrim get() = NoSlow.riseMode == "Grim"
    private val isGrim19 get() = NoSlow.riseMode == "Grim19"
    private val isGrim30 get() = NoSlow.riseMode == "Grim30"

    /** 当前手持物品对应的类别是否选择了 Rise 模式（对应 Rise 的 food/potion/sword/bow 布尔）。 */
    private fun isRiseCategory(item: Item?): Boolean = when (item) {
        is ItemSword -> NoSlow.swordMode == "Rise"
        is ItemFood, is ItemPotion, is ItemBucketMilk -> NoSlow.consumeMode == "Rise"
        is ItemBow -> NoSlow.bowPacket == "Rise"
        else -> false
    }

    private val onMotion = handler<MotionEvent> { event ->
        if (!active || event.eventState != EventState.PRE)
            return@handler

        val player = mc.thePlayer ?: return@handler
        val heldItem = player.heldItem ?: return@handler

        when {
            // Grim：换手重置（8+1 槽位循环 C09 包）
            isGrim && player.isUsingItem && isRiseCategory(heldItem.item) ->
                sendSlotSwap(SilentHotbar.currentSlot)

            // Grim19：1.9+ 的 Via USE_ITEM 包
            isGrim19 && player.isUsingItem && isRiseCategory(heldItem.item) -> {
                val version = ViaLoadingBase.getInstance().targetVersion
                if (version.isNewerThanOrEqualTo(ProtocolVersion.v1_9)) {
                    sendViaUseItem(0)
                    useTicks++
                } else {
                    if (player.ticksExisted % 5 == 0)
                        chat("use 1.9+ for this bypass")

                    useTicks = 0
                }
            }
        }
    }

    private val onUpdate = handler<UpdateEvent> {
        if (!active || !isGrim30)
            return@handler

        val player = mc.thePlayer ?: return@handler
        val heldItem = player.heldItem ?: return@handler

        // Rise 的 SprintEvent 近似：使用物品且向前移动时强制疾跑
        if (player.isUsingItem && player.moveForward > 0.0f)
            player.isSprinting = true

        // 空中使用物品且未按左右键：yaw + 45° 旋转规避
        if (player.isUsingItem && !player.onGround &&
            !mc.gameSettings.keyBindRight.isKeyDown && !mc.gameSettings.keyBindLeft.isKeyDown
        ) {
            RotationUtils.setTargetRotation(
                Rotation(player.rotationYaw + 45f, player.rotationPitch),
                NoSlow.riseOptions
            )
        }

        // 蛛网中保持移动
        if (player.isInWeb)
            strafe(0.64)

        // 使用物品 + 击退抗性期间：微加速（Speed 启用时减小步长避免冲突）
        if (player.isUsingItem && player.hurtResistantTime > 1 && !mc.gameSettings.keyBindJump.isKeyDown) {
            if (!Speed.handleEvents())
                moveFlying(2.0e-4)
            else
                moveFlying(1.0e-4)

            if (!mc.gameSettings.keyBindRight.isKeyDown &&
                !mc.gameSettings.keyBindLeft.isKeyDown &&
                heldItem.item !is ItemBow
            ) {
                RotationUtils.setTargetRotation(
                    Rotation(player.rotationYaw + 45f, player.rotationPitch),
                    NoSlow.riseOptions
                )
            }
        }
    }

    private val onPacket = handler<PacketEvent> { event ->
        if (!active || !isGrim30)
            return@handler

        // Heypixel：使用食物/药水/弓时扣住 C0F Transaction
        if (NoSlow.riseHeypixel && event.packet is C0FPacketConfirmTransaction) {
            val player = mc.thePlayer ?: return@handler
            val item = player.heldItem?.item ?: return@handler

            if (player.isUsingItem && (item is ItemFood || item is ItemPotion || item is ItemBow))
                event.cancelEvent()
        }
    }

    /**
     * 由 [NoSlow] 的 SlowDownEvent handler 顶部调用。
     * 命中时把减速设置成 1f（完全取消减速）并返回 true，让调用方跳过默认乘数逻辑。
     */
    fun handleSlowDown(event: SlowDownEvent): Boolean {
        val player = mc.thePlayer ?: return false
        if (!active)
            return false

        val item = player.heldItem?.item ?: return false
        if (!isRiseCategory(item))
            return false

        return when (NoSlow.riseMode) {
            "Grim", "Grim19" -> {
                if (!player.isUsingItem)
                    return false

                event.forward = 1f
                event.strafe = 1f
                true
            }

            "Grim30" -> {
                if (player.isUsingItem) {
                    usingItemTicks++

                    if (!Speed.handleEvents())
                        moveFlying(1.0e-4)
                } else {
                    usingItemTicks = 0
                }

                // 奇偶交替取消减速（hurtResistantTime / ticksExisted 的奇偶性）
                val parity = player.hurtResistantTime == 1 ||
                    (player.ticksExisted % 2 == 0 && !player.onGround) ||
                    (player.hurtResistantTime % 2 == 1 && player.onGround)

                if (parity && player.isUsingItem) {
                    event.forward = 1f
                    event.strafe = 1f
                    true
                } else false
            }

            else -> false
        }
    }
}
