/*
 * skid https://github.com/xiaozhuhou233/moon-rebase
 */
/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.render

import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.minecraft.entity.Entity
import net.minecraft.entity.player.EntityPlayer

/**
 * ModelChanger —— 玩家模型标志修改（移植自 moon 的 ModelModifierModule，纯客户端视觉，
 * 不动逻辑与碰撞箱）：为「自己 / 其他玩家」分别提供三个渲染标志：
 *
 * - Baby：强制 mainModel.isChild = true（幼年模型渲染）；
 * - Riding：强制 mainModel.isRiding = true（坐姿渲染）；
 * - HideArmor：隐藏盔甲层渲染。
 *
 * 应用点（全部由 mixin 调用，主线程渲染期执行，均为 O(1) 查表）：
 * - MixinRendererLivingEntity#renderModel：按标志覆写 mainModel.isChild / isRiding，
 *   渲染结束后恢复原值，避免污染后续实体；
 * - MixinLayerArmorBase#renderLayer：HideArmor 时跳过盔甲层。
 */
object ModelChanger : Module("ModelChanger", Category.RENDER) {

    enum class Flag { BABY, RIDING, HIDE_ARMOR }

    private val selfBaby by boolean("Self Baby", false)
    private val selfRiding by boolean("Self Riding", false)
    private val selfHideArmor by boolean("Self HideArmor", false)
    private val otherBaby by boolean("Other Baby", false)
    private val otherRiding by boolean("Other Riding", false)
    private val otherHideArmor by boolean("Other HideArmor", false)

    /** 该玩家是否命中指定标志（moon 的 modelModifierFlag 同语义） */
    fun flag(entity: Entity, flag: Flag): Boolean {
        if (!handleEvents() || entity !is EntityPlayer) return false
        val self = mc.thePlayer ?: return false
        val isSelf = entity === self
        return when (flag) {
            Flag.BABY -> if (isSelf) selfBaby else otherBaby
            Flag.RIDING -> if (isSelf) selfRiding else otherRiding
            Flag.HIDE_ARMOR -> if (isSelf) selfHideArmor else otherHideArmor
        }
    }

    override val tag
        get() = mutableListOf<String>().apply {
            if (selfBaby || otherBaby) add("Baby")
            if (selfRiding || otherRiding) add("Riding")
            if (selfHideArmor || otherHideArmor) add("HideArmor")
        }.joinToString(",")
}
