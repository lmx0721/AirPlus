/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.render

import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.minecraft.client.model.ModelPig
import net.minecraft.entity.Entity
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.util.ResourceLocation

/**
 * ModelChanger —— 玩家模型修改（纯客户端视觉，不动逻辑与碰撞箱）：
 *
 * - Self Model / Other Model：Normal（原样）/ Baby（幼年模型渲染）/ Pig（猪模型 + 猪贴图）；
 *   Pig 模式下隐藏玩家的披风、手持物品与盔甲层；Baby 模式下隐藏披风；
 * - Self/Other Width·Height·Length：模型宽 / 高 / 长缩放，百分比 0..200
 *   （100 = 原始大小，0 = 该轴完全压没）。
 *
 * 应用点（全部由 mixin 调用，主线程渲染期执行，均为 O(1) 查表）：
 * - MixinRendererLivingEntity#renderModel：按模式换用猪模型渲染，并用 pushMatrix/scale
 *   包裹整段渲染实现三轴缩放；
 * - MixinLayerArmorBase#renderLayer：Pig 模式跳过盔甲层；
 * - MixinLayerHeldItem#doRenderLayer：Pig 模式跳过手持物品层；
 * - MixinAbstractClientPlayer#getLocationCape：Baby/Pig 模式返回 null 隐藏披风。
 */
object ModelChanger : Module("ModelChanger", Category.RENDER) {

    enum class ModelType { NORMAL, BABY, PIG }

    /** 猪模型贴图（原版 pig.png，1.8.9 资源实际路径为 textures/entity/pig/pig.png） */
    val PIG_TEXTURE = ResourceLocation("textures/entity/pig/pig.png")

    // 共享猪模型实例（仅渲染线程使用，惰性创建）
    val pigModel: ModelPig by lazy { ModelPig() }

    private val selfModel by choices("Self Model", arrayOf("Normal", "Baby", "Pig"), "Normal")
    private val otherModel by choices("Other Model", arrayOf("Normal", "Baby", "Pig"), "Normal")

    // 模型三轴缩放：宽 / 高 / 长，百分比 0..200（100 = 原始大小）
    private val selfWidth by int("Self Width", 100, 0..200)
    private val selfHeight by int("Self Height", 100, 0..200)
    private val selfLength by int("Self Length", 100, 0..200)
    private val otherWidth by int("Other Width", 100, 0..200)
    private val otherHeight by int("Other Height", 100, 0..200)
    private val otherLength by int("Other Length", 100, 0..200)

    /** 该玩家的模型类型（模块关闭 / 非玩家 / Normal 返回 null，mixin 侧走原版逻辑）。 */
    fun modelType(entity: Entity?): ModelType? {
        if (entity == null || !handleEvents() || entity !is EntityPlayer) return null
        val self = mc.thePlayer ?: return null
        val mode = if (entity === self) selfModel else otherModel
        return when (mode) {
            "Baby" -> ModelType.BABY
            "Pig" -> ModelType.PIG
            else -> null
        }
    }

    /** 模型 X 轴缩放系数（宽 / 100）。 */
    fun scaleX(entity: Entity?): Float = scale(entity) { selfWidth to otherWidth }

    /** 模型 Y 轴缩放系数（高 / 100）。 */
    fun scaleY(entity: Entity?): Float = scale(entity) { selfHeight to otherHeight }

    /** 模型 Z 轴缩放系数（长 / 100）。 */
    fun scaleZ(entity: Entity?): Float = scale(entity) { selfLength to otherLength }

    private inline fun scale(entity: Entity?, value: () -> Pair<Int, Int>): Float {
        if (entity == null || !handleEvents() || entity !is EntityPlayer) return 1F
        val self = mc.thePlayer ?: return 1F
        val percent = if (entity === self) value().first else value().second
        return percent.coerceIn(0, 200) / 100F
    }

    /** 是否隐藏披风（Baby / Pig 模式）。 */
    fun hideCape(entity: Entity?): Boolean =
        modelType(entity) == ModelType.BABY || modelType(entity) == ModelType.PIG

    /** 是否隐藏手持物品（Pig 模式，猪拿不了东西）。 */
    fun hideHeldItem(entity: Entity?): Boolean = modelType(entity) == ModelType.PIG

    /** 是否隐藏盔甲层（Pig 模式，盔甲按人形骨架挂点渲染在猪模型上会错位）。 */
    fun hideArmor(entity: Entity?): Boolean = modelType(entity) == ModelType.PIG

    override val tag
        get() = mutableListOf<String>().apply {
            if (selfModel != "Normal") add("Self:$selfModel")
            if (otherModel != "Normal") add("Other:$otherModel")
            if (selfWidth != 100 || selfHeight != 100 || selfLength != 100) add("Self:Scale")
            if (otherWidth != 100 || otherHeight != 100 || otherLength != 100) add("Other:Scale")
        }.joinToString(",")
}
