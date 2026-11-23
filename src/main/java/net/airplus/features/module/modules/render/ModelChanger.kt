/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.render

import net.airplus.config.ListValue
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.file.FileManager
import net.airplus.utils.render.AnimatedImage
import net.airplus.utils.render.drawWithTessellatorWorldRenderer
import net.minecraft.client.model.ModelPig
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.entity.Entity
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.GL11
import java.awt.Color
import java.io.File

/**
 * ModelChanger —— 玩家模型修改（纯客户端视觉，不动逻辑与碰撞箱）：
 *
 * - Self Model / Other Model：Normal（原样）/ Baby（幼年模型渲染）/ Pig（猪模型 + 猪贴图）/
 *   Image（图片 billboard）/ GIF（动图 billboard）；
 *   Pig 模式下隐藏玩家的披风、手持物品与盔甲层；Baby 模式下隐藏披风；
 *   Image/GIF 模式整体取消 doRender（模型/层/名牌全隐藏），改为在实体脚下
 *   绘制一块始终面向相机（仅偏航）的平面 billboard；
 * - Self/Other Width·Height·Length：模型宽 / 高 / 长缩放，百分比 0..200
 *   （100 = 原始大小，0 = 该轴完全压没）。billboard 是平面，只消费
 *   Width/Height（高 = 1.8 格 × Height%，宽 = 高 × 图片宽高比 × Width%），
 *   Length 无对应维度被忽略；
 * - Rotation：billboard 绕竖直轴的额外旋转角（-180°..180°，叠加在朝向玩家之上）；
 * - Color：贴图染色（含 alpha，png/gif 透明通道按源像素与染色 alpha 混合），
 *   并按环境亮度调制，避免黑暗处全亮突兀；
 * - GIF 选择 / 图片 选择：MusicPlayer 同款「下拉框 + 目录扫描」——扫描客户端数据
 *   目录下 Image 文件夹（5s 节流 + 启用/选择变化时重扫，文件消失自动回退内置），
 *   GIF 项从 .gif 文件选、图片项从 .png/.jpg/.jpeg 文件选；不选则用 jar 内置资源
 *   （tomorin.gif / deobf.jpg），png/gif 保留 alpha。
 *
 * 应用点（全部由 mixin 调用，主线程渲染期执行，均为 O(1) 查表）：
 * - MixinRendererLivingEntity#doRender HEAD：Image/GIF 模式画 billboard 并 cancel；
 * - MixinRendererLivingEntity#renderModel：按模式换用猪模型渲染，并用 pushMatrix/scale
 *   包裹整段渲染实现三轴缩放；
 * - MixinLayerArmorBase#renderLayer：Pig 模式跳过盔甲层；
 * - MixinLayerHeldItem#doRenderLayer：Pig 模式跳过手持物品层；
 * - MixinAbstractClientPlayer#getLocationCape：Baby/Pig/Image/GIF 模式返回 null 隐藏披风。
 */
object ModelChanger : Module("ModelChanger", Category.RENDER) {

    enum class ModelType { NORMAL, BABY, PIG, IMAGE, GIF }

    /** 猪模型贴图（原版 pig.png，1.8.9 资源实际路径为 textures/entity/pig/pig.png） */
    val PIG_TEXTURE = ResourceLocation("textures/entity/pig/pig.png")

    // 共享猪模型实例（仅渲染线程使用，惰性创建）
    val pigModel: ModelPig by lazy { ModelPig() }

    private val selfModel by choices("Self Model", arrayOf("Normal", "Baby", "Pig", "Image", "GIF"), "Normal")
    private val otherModel by choices("Other Model", arrayOf("Normal", "Baby", "Pig", "Image", "GIF"), "Normal")

    // 模型三轴缩放：宽 / 高 / 长，百分比 0..200（100 = 原始大小）
    private val selfWidth by int("Self Width", 100, 0..200)
    private val selfHeight by int("Self Height", 100, 0..200)
    private val selfLength by int("Self Length", 100, 0..200)
    private val otherWidth by int("Other Width", 100, 0..200)
    private val otherHeight by int("Other Height", 100, 0..200)
    private val otherLength by int("Other Length", 100, 0..200)

    // billboard 设置
    private val rotation by float("Rotation", 0F, -180F..180F, "°")
    private val colorValue by color("Color", Color.WHITE)

    /** jar 内置默认资源 */
    private val DEFAULT_GIF_LOCATION = ResourceLocation("minecraft", "airplus/gif/tomorin.gif")
    private val DEFAULT_IMAGE_LOCATION = ResourceLocation("minecraft", "airplus/image/deobf.jpg")

    /** 内置资源在下拉框中的显示名（与扫描文件名区分） */
    private const val BUILTIN_GIF = "默认 GIF"
    private const val BUILTIN_IMAGE = "默认 图片"

    /** 扫描目录：客户端数据目录下的 Image 文件夹（MusicPlayer 同款扫描思路） */
    private val imageDir: File by lazy {
        File(FileManager.dir, "Image").apply { if (!exists()) mkdirs() }
    }

    private val imageExtensions = setOf("gif", "png", "jpg", "jpeg")

    private var gifFiles: List<File> = emptyList()
    private var imageFiles: List<File> = emptyList()
    private var currentGifPick = BUILTIN_GIF
    private var currentImagePick = BUILTIN_IMAGE
    private var loadedGifPick: String? = null
    private var loadedImagePick: String? = null
    private var lastScanTime = 0L

    private lateinit var gifPickValue: ListValue
    private lateinit var imagePickValue: ListValue

    init {
        // MusicPlayer 同款：先赋值保住 ListValue 静态类型，再挂 onChanged（链式会丢失类型）
        gifPickValue = choices("GIF 选择", arrayOf(BUILTIN_GIF), BUILTIN_GIF)
        imagePickValue = choices("图片 选择", arrayOf(BUILTIN_IMAGE), BUILTIN_IMAGE)
        gifPickValue.onChanged {
            currentGifPick = it
            reloadGifSource()
        }
        imagePickValue.onChanged {
            currentImagePick = it
            reloadImageSource()
        }
    }

    // billboard 贴图源（GIF / 静态图片各一份，Self 与 Other 共用），解码在 AnimatedImage 后台线程
    private val gifImage = AnimatedImage()
    private val stillImage = AnimatedImage()

    /** 该玩家的模型类型（模块关闭 / 非玩家返回 null，mixin 侧走原版逻辑）。 */
    fun modelType(entity: Entity?): ModelType? {
        if (entity == null || !handleEvents() || entity !is EntityPlayer) return null
        val self = mc.thePlayer ?: return null
        val mode = if (entity === self) selfModel else otherModel
        return when (mode) {
            "Baby" -> ModelType.BABY
            "Pig" -> ModelType.PIG
            "Image" -> ModelType.IMAGE
            "GIF" -> ModelType.GIF
            else -> null
        }
    }

    /** Image/GIF billboard 模式（mixin doRender HEAD 检测）。 */
    fun shouldRenderBillboard(entity: Entity?): Boolean {
        val type = modelType(entity)
        return type == ModelType.IMAGE || type == ModelType.GIF
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

    /**
     * 在实体脚下绘制面向相机的 billboard（仅偏航朝向 + Rotation 偏转）。
     * 由 MixinRendererLivingEntity#doRender HEAD 调用，调用后 cancel 整个 doRender。
     */
    fun renderBillboard(entity: Entity, x: Double, y: Double, z: Double, partialTicks: Float) {
        val type = modelType(entity)
        if (type != ModelType.IMAGE && type != ModelType.GIF) return

        rescanSources()
        val image = if (type == ModelType.GIF) gifImage else stillImage
        image.update()
        val resource = image.resource ?: return
        if (image.imageWidth <= 0 || image.imageHeight <= 0) return

        // billboard 只消费 Width/Height（平面没有「长」），宽按图片宽高比保持不拉伸
        val height = 1.8F * scaleY(entity)
        val width = height * (image.imageWidth.toFloat() / image.imageHeight.toFloat()) * scaleX(entity)
        if (height < 0.05F || width < 0.05F) return

        // 染色 × 环境亮度（保持与场景明暗一致）
        val base = colorValue
        val brightness = entity.getBrightness(partialTicks)
        val r = base.red / 255F * brightness
        val g = base.green / 255F * brightness
        val b = base.blue / 255F * brightness
        val a = base.alpha / 255F

        // GL 纪律：混合/深度/光照用裸 GL + 真实状态读取（glIsEnabled）保存恢复，
        // 不写 GlStateManager 缓存；lightmap 单元摘除必须走 GlStateManager 对称调用
        //（vanilla enableLightmap 是缓存守卫，裸写会让缓存失步、后续实体丢光照）；
        // 当前颜色用两步法写/恢复，防止缓存守卫静默跳过染色与残留染色。
        val prevDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
        val prevBlend = GL11.glIsEnabled(GL11.GL_BLEND)
        val prevAlpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
        val prevLighting = GL11.glIsEnabled(GL11.GL_LIGHTING)

        GlStateManager.pushMatrix()
        GlStateManager.translate(x, y, z)
        // 面向玩家：局部 +Z 法线经 rotate(180-θ) 后正对相机方向（θ = playerViewY），
        // 局部 +X 与相机右向量重合 → 贴图不镜像；此前用 -θ 会让 billboard 背对玩家、
        // 贴图左右反过来了
        GlStateManager.rotate(180F - mc.renderManager.playerViewY + rotation, 0F, 1F, 0F)

        GL11.glDisable(GL11.GL_LIGHTING)
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit)
        GlStateManager.disableTexture2D()
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit)

        GL11.glEnable(GL11.GL_DEPTH_TEST)
        GL11.glEnable(GL11.GL_BLEND)
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL11.glEnable(GL11.GL_ALPHA_TEST)
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F)

        GlStateManager.color(0F, 0F, 0F, 0F)
        GlStateManager.color(r, g, b, a)

        mc.textureManager.bindTexture(resource)
        // 世界坐标 y 向上，而 GUI 贴图助手（drawModalRectWithCustomSizedTexture）按
        // y 向下映射（y+h 顶点配 v+h）→ 世界里直接用会上下颠倒；手动绘制、只翻 V：
        // 底边(y=0)配图片底(v=1)、顶边(y=h)配图片顶(v=0)，绕向不变（法线仍朝 +Z）
        drawWithTessellatorWorldRenderer {
            begin(7, DefaultVertexFormats.POSITION_TEX)
            pos((-width / 2).toDouble(), 0.0, 0.0).tex(0.0, 1.0).endVertex()
            pos((width / 2).toDouble(), 0.0, 0.0).tex(1.0, 1.0).endVertex()
            pos((width / 2).toDouble(), height.toDouble(), 0.0).tex(1.0, 0.0).endVertex()
            pos((-width / 2).toDouble(), height.toDouble(), 0.0).tex(0.0, 0.0).endVertex()
        }

        GlStateManager.color(0F, 0F, 0F, 0F)
        GlStateManager.color(1F, 1F, 1F, 1F)

        if (prevAlpha) GL11.glEnable(GL11.GL_ALPHA_TEST) else GL11.glDisable(GL11.GL_ALPHA_TEST)
        if (prevBlend) GL11.glEnable(GL11.GL_BLEND) else GL11.glDisable(GL11.GL_BLEND)
        if (prevDepth) GL11.glEnable(GL11.GL_DEPTH_TEST) else GL11.glDisable(GL11.GL_DEPTH_TEST)
        if (prevLighting) GL11.glEnable(GL11.GL_LIGHTING)

        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit)
        GlStateManager.enableTexture2D()
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit)
        GlStateManager.popMatrix()
    }

    /** 扫描 Image 目录并刷新下拉框（5s 节流）；选择失效自动回退内置，文件新增后自动生效。 */
    private fun rescanSources(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastScanTime < 5000L) return
        lastScanTime = now

        val files = imageDir.walk()
            .filter { it.isFile && it.extension.lowercase() in imageExtensions }
            .sortedBy { it.nameWithoutExtension.lowercase() }
            .toList()
        gifFiles = files.filter { it.extension.equals("gif", true) }
        imageFiles = files.filter { !it.extension.equals("gif", true) }

        val gifNames = mutableListOf(BUILTIN_GIF).apply { addAll(gifFiles.map { it.name }) }
        val imageNames = mutableListOf(BUILTIN_IMAGE).apply { addAll(imageFiles.map { it.name }) }
        gifPickValue.updateValues(gifNames.toTypedArray())
        imagePickValue.updateValues(imageNames.toTypedArray())

        // 选择失效（config 加载后文件被删）时回退内置；set 会触发 onChanged 完成重载。
        // currentGifPick 由 onChanged 跟踪（Value.value 为 protected，不直接读）
        if (currentGifPick !in gifNames) {
            gifPickValue.set(BUILTIN_GIF, saveImmediately = false)
        }
        if (currentGifPick != loadedGifPick) reloadGifSource()

        if (currentImagePick !in imageNames) {
            imagePickValue.set(BUILTIN_IMAGE, saveImmediately = false)
        }
        if (currentImagePick != loadedImagePick) reloadImageSource()
    }

    private fun reloadGifSource() {
        loadedGifPick = currentGifPick
        val file = gifFiles.firstOrNull { it.name == currentGifPick }
        if (file != null) gifImage.loadFile(file) else gifImage.loadResource(DEFAULT_GIF_LOCATION)
    }

    private fun reloadImageSource() {
        loadedImagePick = currentImagePick
        val file = imageFiles.firstOrNull { it.name == currentImagePick }
        if (file != null) stillImage.loadFile(file) else stillImage.loadResource(DEFAULT_IMAGE_LOCATION)
    }

    override fun onEnable() {
        // 启用时立即扫描一次 Image 目录（不依赖 billboard 渲染帧的 5s 节流）
        rescanSources(force = true)
    }

    override val tag
        get() = mutableListOf<String>().apply {
            if (selfModel != "Normal") add("Self:$selfModel")
            if (otherModel != "Normal") add("Other:$otherModel")
            if (selfWidth != 100 || selfHeight != 100 || selfLength != 100) add("Self:Scale")
            if (otherWidth != 100 || otherHeight != 100 || otherLength != 100) add("Other:Scale")
        }.joinToString(",")
}
