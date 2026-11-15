/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 * skid edmoonlight
 * Ported from Moonlight EdMoonlight ArmorBreakerTrace (NewArmorBreaker dependency).
 * 破甲模块的调试日志：把 tick 状态、规划窗口、发出/收到的关键包写入 .log 文件，便于复盘。
 */
package net.airplus.features.module.modules.combat.armorbreak

import net.airplus.utils.client.MinecraftInstance
import net.minecraft.entity.EntityLivingBase
import net.minecraft.item.ItemStack
import net.minecraft.network.Packet
import net.minecraft.network.play.client.C02PacketUseEntity
import net.minecraft.network.play.client.C03PacketPlayer
import net.minecraft.network.play.client.C07PacketPlayerDigging
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement
import net.minecraft.network.play.client.C09PacketHeldItemChange
import net.minecraft.network.play.client.C0BPacketEntityAction
import net.minecraft.network.play.client.C0EPacketClickWindow
import net.minecraft.network.play.server.S04PacketEntityEquipment
import net.minecraft.network.play.server.S0BPacketAnimation
import net.minecraft.network.play.server.S12PacketEntityVelocity
import net.minecraft.network.play.server.S19PacketEntityStatus
import net.minecraft.util.AxisAlignedBB
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Comparator
import java.util.Date

internal object ArmorBreakerTrace : MinecraftInstance {
    @Volatile
    var enabled = false

    @Volatile
    var targetId = -1

    private var nextLabel: String? = null
    private var tick = 0

    @Volatile
    private var selfId = -1
    private val selfArmor = intArrayOf(Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)

    private const val MAX_BYTES = 8388608L
    private const val KEEP_FILES = 3
    private var out: BufferedWriter? = null
    private var lastFlush = 0L
    private var written = 0L
    private var stopped = false

    fun label(text: String?) {
        nextLabel = text
    }

    private fun write(text: String) {
        if (enabled && !stopped) {
            try {
                val now = System.currentTimeMillis()
                var writer = out
                if (writer == null) {
                    val dir = File(mc.mcDataDir, "AirPlus/ab-trace")
                    dir.mkdirs()
                    prune(dir)
                    writer = BufferedWriter(
                        OutputStreamWriter(
                            FileOutputStream(File(dir, SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()))),
                            StandardCharsets.UTF_8
                        )
                    )
                    out = writer
                    written = 0L
                }

                val line = "$now $text"
                writer.write(line)
                writer.newLine()
                written += line.length + 2
                if (written >= MAX_BYTES) {
                    writer.write("$now END size limit")
                    writer.newLine()
                    close()
                    stopped = true
                    return
                }

                if (now - lastFlush > 1000L) {
                    writer.flush()
                    lastFlush = now
                }
            } catch (e: IOException) {
                close()
                stopped = true
            }
        }
    }

    private fun prune(dir: File) {
        val files = dir.listFiles { _, name -> name.endsWith(".log") }
        if (files != null && files.size >= KEEP_FILES) {
            Arrays.sort(files, Comparator.comparing { file: File -> file.name })
            for (i in 0..files.size - KEEP_FILES) {
                files[i].delete()
            }
        }
    }

    fun close() {
        stopped = false
        out?.let { writer ->
            try {
                writer.close()
            } catch (ignored: IOException) {
            }
        }
        out = null
    }

    fun tick(tickValue: Int, preTick: Int, target: EntityLivingBase?) {
        tick = tickValue
        if (!enabled && out != null) {
            close()
        }

        if (enabled && targetId >= 0) {
            val player = mc.thePlayer
            if (target != null && player != null) {
                val boundingBox = target.entityBoundingBox
                val x = player.posX
                val y = player.posY + player.getEyeHeight()
                val z = player.posZ
                val dx = x - x.coerceIn(boundingBox.minX, boundingBox.maxX)
                val dy = y - y.coerceIn(boundingBox.minY, boundingBox.maxY)
                val dz = z - z.coerceIn(boundingBox.minZ, boundingBox.maxZ)
                write(
                    String.format(
                        "T %d %d %.3f %d %d %d %.4f %.4f %d %.4f %.4f %d",
                        tickValue,
                        preTick,
                        Math.sqrt(dx * dx + dy * dy + dz * dz),
                        if (player.isSprinting) 1 else 0,
                        if (player.serverSprintState) 1 else 0,
                        target.hurtTime,
                        player.posY,
                        player.motionY,
                        if (player.onGround) 1 else 0,
                        player.fallDistance,
                        ArmorBreakerServerModel.wireFallDistance(),
                        if (ArmorBreakerServerModel.lastGround()) 1 else 0
                    )
                )
            } else {
                write("T $tickValue $preTick")
            }

            if (player != null) {
                selfId = player.entityId
                for (i in 0 until 4) {
                    val stack = player.inventory.armorInventory[i]
                    val damage = if (stack == null) -1 else stack.itemDamage
                    if (damage != selfArmor[i]) {
                        selfArmor[i] = damage
                        write("SELF ${i + 1} $damage")
                    }
                }
            }
        }
    }

    fun onWire(packet: Packet<*>, attr: Float, heldAttr: Float) {
        if (enabled && targetId >= 0) {
            when (packet) {
                is C03PacketPlayer -> write(
                    String.format(
                        "C03 %d %d %d %.2f %.2f",
                        tick,
                        if (packet.isMoving) 1 else 0,
                        if (packet.rotating) 1 else 0,
                        attr,
                        heldAttr
                    )
                )

                is C0EPacketClickWindow -> write(
                    String.format("C0E %d %d %d %.2f", tick, packet.slotId, packet.usedButton, heldAttr)
                )

                is C08PacketPlayerBlockPlacement ->
                    if (packet.placedBlockDirection == 255) {
                        write("C08 $tick")
                    }

                is C07PacketPlayerDigging -> write("C07 $tick ${packet.status}")

                is C09PacketHeldItemChange -> write("C09 $tick ${packet.slotId}")

                is C0BPacketEntityAction -> write("C0B $tick ${packet.action}")
            }
        }
    }

    fun forecast(tickValue: Int, flag: Char, actual: BooleanArray, expected: BooleanArray, horizon: Int, extra: Int) {
        if (enabled && targetId >= 0) {
            val actualStr = StringBuilder(horizon)
            val expectedStr = StringBuilder(horizon)
            for (i in 0 until horizon) {
                actualStr.append(if (actual[i]) 'C' else '.')
                expectedStr.append(if (expected[i]) 'C' else '.')
            }

            write("F $tickValue $flag $actualStr $expectedStr $extra")
        }
    }

    fun onWireAttack(packet: C02PacketUseEntity, entityId: Int, attr: Float, crit: Boolean, base: Float, amount: Float) {
        val text = nextLabel
        nextLabel = null
        if (enabled && entityId == targetId) {
            write(
                String.format(
                    "C02 %d %d %.2f %d %.2f %.2f %s",
                    tick,
                    entityId,
                    attr,
                    if (crit) 1 else 0,
                    base,
                    amount,
                    text ?: "ext"
                )
            )
        }
    }

    fun onRawReceive(packet: Packet<*>) {
        val id = targetId
        if (enabled && id >= 0) {
            when (packet) {
                is S0BPacketAnimation ->
                    if (packet.entityID == id && (packet.animationType == 4 || packet.animationType == 5)) {
                        write("S0B $id ${packet.animationType}")
                    } else if (packet.entityID == selfId && (packet.animationType == 4 || packet.animationType == 5)) {
                        write("S0BSELF ${packet.animationType}")
                    }

                is S12PacketEntityVelocity -> {
                    if (packet.entityID == id) {
                        write(
                            String.format(
                                "S12T %.3f %.3f %.3f",
                                packet.getMotionX() / 8000.0,
                                packet.getMotionY() / 8000.0,
                                packet.getMotionZ() / 8000.0
                            )
                        )
                    } else if (packet.entityID == selfId) {
                        write(
                            String.format(
                                "S12SELF %.3f %.3f %.3f",
                                packet.getMotionX() / 8000.0,
                                packet.getMotionY() / 8000.0,
                                packet.getMotionZ() / 8000.0
                            )
                        )
                    }
                }

                is S19PacketEntityStatus ->
                    if (packet.entityId == selfId && packet.opCode.toInt() == 2) {
                        write("S19SELF")
                    }

                is S04PacketEntityEquipment ->
                    if (packet.entityID == id && packet.equipmentSlot >= 1) {
                        write(
                            "S04 $id ${packet.equipmentSlot} ${if (packet.getItemStack() == null) -1 else packet.getItemStack().itemDamage}"
                        )
                    }
            }
        }
    }
}
