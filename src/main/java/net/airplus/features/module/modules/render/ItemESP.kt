/*
 * AirPlus Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/lmx0721/AirPlus
 */
package net.airplus.features.module.modules.render

import net.airplus.event.Render2DEvent
import net.airplus.event.Render3DEvent
import net.airplus.event.WorldEvent
import net.airplus.event.handler
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.modules.player.InventoryCleaner
import net.airplus.ui.font.Fonts
import net.airplus.ui.font.GameFontRenderer
import net.airplus.utils.attack.EntityUtils.isLookingOnEntities
import net.airplus.utils.client.EntityLookup
import net.airplus.utils.extensions.*
import net.airplus.utils.render.ESPUtil
import net.airplus.utils.render.HudBlur
import net.airplus.utils.render.RenderUtils.disableGlCap
import net.airplus.utils.render.RenderUtils.drawEntityBox
import net.airplus.utils.render.RenderUtils.enableGlCap
import net.airplus.utils.render.RenderUtils.resetCaps
import net.airplus.utils.render.RoundedUtil
import net.airplus.utils.render.animation.AnimationUtil
import net.airplus.utils.render.shader.shaders.GlowShader
import net.airplus.utils.rotation.RotationUtils.isEntityHeightVisible
import net.minecraft.client.gui.ScaledResolution
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.RenderHelper
import net.minecraft.enchantment.Enchantment
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.entity.item.EntityItem
import net.minecraft.init.Items
import net.minecraft.item.Item
import net.minecraft.item.ItemAppleGold
import net.minecraft.item.ItemArmor
import net.minecraft.item.ItemBow
import net.minecraft.item.ItemBucketMilk
import net.minecraft.item.ItemEnchantedBook
import net.minecraft.item.ItemEnderPearl
import net.minecraft.item.ItemPotion
import net.minecraft.item.ItemSkull
import net.minecraft.item.ItemSword
import net.minecraft.item.ItemTool
import net.minecraft.item.ItemStack
import org.lwjgl.opengl.GL11.*
import java.awt.Color
import kotlin.math.*

object ItemESP : Module("ItemESP", Category.RENDER) {
    private val mode by choices("Mode", arrayOf("Box", "OtherBox", "Glow", "Moon"), "Box")

    private val itemText by boolean("ItemText", false)

    private val glowRenderScale by float("Glow-Renderscale", 1f, 0.5f..2f) { mode == "Glow" }
    private val glowRadius by int("Glow-Radius", 4, 1..5) { mode == "Glow" }
    private val glowFade by int("Glow-Fade", 10, 0..30) { mode == "Glow" }
    private val glowTargetAlpha by float("Glow-Target-Alpha", 0f, 0f..1f) { mode == "Glow" }

    private val color by color("Color", Color.GREEN)

    // ===== Moon 模式（移植自 moonlight ItemESP：屏幕空间物品标签）=====
    private val moonItems by choices("Items", arrayOf("Useful", "All"), "Useful") { mode == "Moon" }
    private val moonScale by float("Moon-Scale", 1f, 0.5f..2f) { mode == "Moon" }
    private val moonMerge by boolean("Merge Stacks", true) { mode == "Moon" }
    private val moonEnchants by boolean("Enchants", true) { mode == "Moon" }
    private val moonDistance by boolean("Distance", false) { mode == "Moon" }
    private val moonGroundRing by boolean("Ground Ring", true) { mode == "Moon" }
    private val moonBackground by boolean("Background", true) { mode == "Moon" }
    private val moonBackgroundColor by color("Background Color", Color(0, 0, 0, 200)) { mode == "Moon" && moonBackground }
    private val moonBgBlur by boolean("Background Blur", false) { mode == "Moon" }
    private val moonCompact by boolean("Compact Piles", true) { mode == "Moon" }
    private val moonPileRadius by float("Pile Radius", 2.5f, 1f..6f) { mode == "Moon" && moonCompact }
    private val moonPileMin by int("Pile Min", 3, 2..8) { mode == "Moon" && moonCompact }
    private val moonExpandOnAim by boolean("Expand On Aim", true) { mode == "Moon" }
    private val moonMaxStack by int("Max Stack", 5, 1..20) { mode == "Moon" }

    // Moon 渲染状态（Render3D 分组/投影 → Render2D 布局/绘制，每帧重建）
    private val moonGroups = ArrayList<MoonGroup>()
    private val moonPiles = ArrayList<MoonGroup>()
    private val moonLayout = ArrayList<MoonGroup>()
    private val moonStates = HashMap<Int, MoonState>()
    private var moonLayoutDirty = true
    private var moonScreenWidth = 0f
    private var moonScreenHeight = 0f
    private var moonPixel = 0.5f
    private var moonFrame = 0

    private val maxRenderDistance by int("MaxRenderDistance", 50, 1..200).onChanged { value ->
        maxRenderDistanceSq = value.toDouble().pow(2)
    }

    private val scale by float("Scale", 3F, 1F..5F) { itemText }
    private val itemCounts by boolean("ItemCounts", true) { itemText }
    private val font by font("Font", Fonts.fontSemibold40) { itemText }
    private val fontShadow by boolean("Shadow", true) { itemText }

    private var maxRenderDistanceSq = 0.0
        set(value) {
            field = if (value <= 0.0) maxRenderDistance.toDouble().pow(2.0) else value
        }

    private val onLook by boolean("OnLook", false)
    private val maxAngleDifference by float("MaxAngleDifference", 90f, 5.0f..90f) { onLook }

    private val thruBlocks by boolean("ThruBlocks", true)

    private val itemEntities by EntityLookup<EntityItem>()
        .filter { mc.thePlayer.getDistanceSqToEntity(it) <= maxRenderDistanceSq }
        .filter { !onLook || isLookingOnEntities(it, maxAngleDifference.toDouble()) }
        .filter { thruBlocks || isEntityHeightVisible(it) }

    val onRender3D = handler<Render3DEvent> {
        if (mc.theWorld == null || mc.thePlayer == null)
            return@handler

        if (mode == "Moon") {
            renderMoon3D(it.partialTicks)
            return@handler
        }

        for (entityItem in itemEntities) {
            val isUseful =
                InventoryCleaner.handleEvents() && InventoryCleaner.highlightUseful && InventoryCleaner.isStackUseful(
                    entityItem.entityItem,
                    mc.thePlayer.openContainer.inventory,
                    mapOf(entityItem.entityItem to entityItem)
                )

            if (itemText) {
                renderEntityText(entityItem, if (isUseful) Color.green else color)
            }

            if (mode == "Glow")
                continue

            // Only render green boxes on useful items, if ItemESP is enabled, render boxes of ItemESP.color on useless items as well
            drawEntityBox(entityItem, if (isUseful) Color.green else color, mode == "Box")
        }
    }

    val onRender2D = handler<Render2DEvent> { event ->
        if (mode == "Moon") {
            renderMoon2D()
            return@handler
        }

        if (mode != "Glow")
            return@handler

        for (entityItem in itemEntities) {
            val isUseful =
                InventoryCleaner.handleEvents() && InventoryCleaner.highlightUseful && InventoryCleaner.isStackUseful(
                    entityItem.entityItem,
                    mc.thePlayer.openContainer.inventory,
                    mapOf(entityItem.entityItem to entityItem)
                )

            GlowShader.startDraw(event.partialTicks, glowRenderScale)

            mc.renderManager.renderEntityStatic(entityItem, event.partialTicks, true)

            // Only render green boxes on useful items, if ItemESP is enabled, render boxes of ItemESP.color on useless items as well
            GlowShader.stopDraw(if (isUseful) Color.green else color, glowRadius, glowFade, glowTargetAlpha)
        }
    }

    private fun renderEntityText(entity: EntityItem, color: Color) {
        val thePlayer = mc.thePlayer ?: return
        val renderManager = mc.renderManager
        val rotateX = if (mc.gameSettings.thirdPersonView == 2) -1.0f else 1.0f

        glPushAttrib(GL_ENABLE_BIT)
        glPushMatrix()

        // Translate to entity position
        val (x, y, z) = entity.interpolatedPosition(entity.lastTickPos) - renderManager.renderPos

        glTranslated(x, y, z)

        glRotatef(-renderManager.playerViewY, 0F, 1F, 0F)
        glRotatef(renderManager.playerViewX * rotateX, 1F, 0F, 0F)

        disableGlCap(GL_LIGHTING, GL_DEPTH_TEST)
        enableGlCap(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        val fontRenderer = font

        // Scale
        val scale = ((thePlayer.getDistanceToEntity(entity) / 4F).coerceAtLeast(1F) / 150F) * scale
        glScalef(-scale, -scale, scale)

        val itemStack = entity.entityItem
        val text = itemStack.displayName + if (itemCounts) " (${itemStack.stackSize})" else ""

        // Draw text
        val width = fontRenderer.getStringWidth(text) * 0.5f
        fontRenderer.drawString(
            text, 1F + -width, if (fontRenderer == Fonts.minecraftFont) 1F else 1.5F, color.rgb, fontShadow
        )

        resetCaps()
        glPopMatrix()
        glPopAttrib()
    }

    // ==================== Moon 模式实现（移植自 moonlight ItemESP） ====================

    // Moon 调色板（tier 分级颜色）
    private val moonGold = Color(255, 196, 0)
    private val moonPurple = Color(191, 90, 242)
    private val moonCyan = Color(100, 210, 255)
    private val moonGreen = Color(52, 199, 89)
    private val moonWhite = Color(235, 235, 245)
    private val moonOrange = Color(255, 159, 10)
    private val moonRed = Color(255, 69, 58)
    private const val moonTextPrimary = 16777215                     // 白色文字
    private const val moonMergeRadiusSq = 1.5625                     // Merge Stacks 合并半径² (1.25²)
    private const val moonMaxNameWidth = 120f                        // 名称截断宽度
    private const val moonMaxPileIcons = 6                           // Pile 最多展示图标数
    private const val moonPileGap = 2.5f                             // Pile 图标间距
    private val moonEnchantOrder = intArrayOf(                       // 附魔显示优先级
        16, 0, 48, 20, 19, 49, 50, 51, 4, 3, 1, 2, 7, 34, 32, 8, 5, 6, 17, 18, 21, 33, 35, 61, 62
    )
    private val moonGlowStops = floatArrayOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f) // 地面光圈渐变分段

    override fun onDisable() {
        super.onDisable()
        moonGroups.clear()
        moonPiles.clear()
        moonLayout.clear()
        moonStates.clear()
    }

    val onMoonWorld = handler<WorldEvent> {
        moonGroups.clear()
        moonPiles.clear()
        moonLayout.clear()
        moonStates.clear()
    }

    /** Render3D：收集地面物品 → Merge Stacks 分组 → projectOn2D 投影 → Ground Ring。 */
    private fun renderMoon3D(partialTicks: Float) {
        moonGroups.clear()
        moonPiles.clear()
        moonLayoutDirty = true
        val world = mc.theWorld ?: return
        mc.thePlayer ?: return

        moonFrame++
        val sr = ScaledResolution(mc)
        moonScreenWidth = sr.scaledWidth.toFloat()
        moonScreenHeight = sr.scaledHeight.toFloat()
        moonPixel = 1f / max(1, sr.scaleFactor)

        val renderManager = mc.renderManager
        val viewerX = renderManager.viewerPosX
        val viewerY = renderManager.viewerPosY
        val viewerZ = renderManager.viewerPosZ
        val filterAll = moonItems == "All"

        for (entityObj in world.loadedEntityList) {
            val entity = entityObj as? EntityItem ?: continue
            if (entity.isDead) continue
            val stack = entity.entityItem ?: continue
            if (!filterAll && !isMoonUseful(stack)) continue

            val x = moonInterp(entity.prevPosX, entity.posX, partialTicks)
            val y = moonInterp(entity.prevPosY, entity.posY, partialTicks)
            val z = moonInterp(entity.prevPosZ, entity.posZ, partialTicks)
            val dx = x - viewerX
            val dy = y - viewerY
            val dz = z - viewerZ
            if (dx * dx + dy * dy + dz * dz > maxRenderDistanceSq) continue

            var group: MoonGroup? = null
            if (moonMerge) {
                for (other in moonGroups) {
                    val head = other.members[0]
                    val hx = moonInterp(head.prevPosX, head.posX, partialTicks) - x
                    val hy = moonInterp(head.prevPosY, head.posY, partialTicks) - y
                    val hz = moonInterp(head.prevPosZ, head.posZ, partialTicks) - z
                    if (hx * hx + hy * hy + hz * hz <= moonMergeRadiusSq && isMoonSameKind(other.stack, stack)) {
                        group = other
                        break
                    }
                }
            }

            val target = group ?: MoonGroup(entity).also { moonGroups.add(it) }
            target.members.add(entity)
            target.key = min(target.key, entity.entityId)
            target.count += max(1, stack.stackSize)
            target.x += x
            target.y += y
            target.z += z
        }

        moonProjectGroups(moonGroundRing, viewerX, viewerY, viewerZ, sr.scaleFactor)
        moonStates.values.removeAll { it.seen != moonFrame }
    }

    /** 组坐标平均 → 投影到屏幕（ESPUtil.projectOn2D）→ 地面光圈 → Compact Piles。 */
    private fun moonProjectGroups(ring: Boolean, viewerX: Double, viewerY: Double, viewerZ: Double, scaleFactor: Int) {
        for (group in moonGroups) {
            val size = group.members.size
            group.x /= size
            group.y /= size
            group.z /= size
            val dx = group.x - viewerX
            val dy = group.y - viewerY
            val dz = group.z - viewerZ
            group.distance = sqrt(dx * dx + dy * dy + dz * dz).toFloat()
            group.tier = moonTierColor(group.stack)
            val state = moonStates.getOrPut(group.key) { MoonState() }
            group.state = state
            state.seen = moonFrame
            val point = ESPUtil.projectOn2D(dx.toFloat(), (dy + 0.55).toFloat(), dz.toFloat(), scaleFactor)
            if (point != null && point.z >= 0f && point.z < 1f) {
                group.screenX = point.x
                group.screenY = point.y
                group.visible = true
            }
            if (ring) {
                moonDrawGroundRing(group, dx, dy, dz)
            }
        }

        if (moonCompact) {
            moonBuildPiles(viewerX, viewerY, viewerZ, scaleFactor)
        }
    }

    /** Compact Piles：地面散落组按 Pile Radius 洪水填充合并成堆（数量 ≥ Pile Min 才成堆）。 */
    private fun moonBuildPiles(viewerX: Double, viewerY: Double, viewerZ: Double, scaleFactor: Int) {
        val size = moonGroups.size
        val pileMin = moonPileMin
        if (size < pileMin) return

        val radiusSq = (moonPileRadius * moonPileRadius).toDouble()
        val visited = BooleanArray(size)
        val bucket = ArrayList<MoonGroup>()

        for (i in 0 until size) {
            if (visited[i]) continue
            visited[i] = true
            bucket.clear()
            bucket.add(moonGroups[i])

            var anchor = 0
            while (anchor < bucket.size) {
                val head = bucket[anchor]
                anchor++
                for (k in 0 until size) {
                    if (visited[k]) continue
                    val other = moonGroups[k]
                    val dx = head.x - other.x
                    val dy = head.y - other.y
                    val dz = head.z - other.z
                    if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                        visited[k] = true
                        bucket.add(other)
                    }
                }
            }

            if (bucket.size < pileMin) continue

            bucket.sortWith(::moonCompareImportance)
            val pile = MoonGroup(bucket[0].stack, 0)
            pile.children = ArrayList(bucket)
            pile.tier = bucket[0].tier
            pile.distance = Float.MAX_VALUE
            var minKey = Int.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            for (child in bucket) {
                child.parent = pile
                minKey = min(minKey, child.key)
                pile.x += child.x
                pile.z += child.z
                pile.count += child.count
                pile.distance = min(pile.distance, child.distance)
                maxY = max(maxY, child.y)
            }
            pile.x /= bucket.size
            pile.z /= bucket.size
            pile.y = maxY
            pile.key = minKey.inv() // 取反避免与 entity id 冲突
            val state = moonStates.getOrPut(pile.key) { MoonState() }
            pile.state = state
            state.seen = moonFrame
            val point = ESPUtil.projectOn2D(
                (pile.x - viewerX).toFloat(),
                (pile.y - viewerY + 0.55).toFloat(),
                (pile.z - viewerZ).toFloat(),
                scaleFactor
            )
            if (point != null && point.z >= 0f && point.z < 1f) {
                pile.screenX = point.x
                pile.screenY = point.y
                pile.visible = true
            }
            moonPiles.add(pile)
        }
    }

    /** Render2D：布局 + 标签绘制（Expand On Aim 时两遍绘制保证展开标签在最上层）。 */
    private fun renderMoon2D() {
        moonEnsureLayout()

        // Background Blur：标签背景后的屏幕空间模糊（与 NameTags Opai 同款 HudBlur 管线）
        if (moonBgBlur) {
            for (group in moonLayout) {
                if (group.alpha > 0.6f) {
                    val bx = group.drawX()
                    val by = group.drawY()
                    HudBlur.blur(bx, by, bx + group.width, by + group.height, group.height / 2f)
                }
            }
        }

        // moonlight 的 Shader2DEvent 黑底描边在 AirPlus 无对应 outline pass，
        // 退化为正式绘制前先画一圈外扩黑底（外扩 1 物理像素）。
        if (moonBackground) {
            for (group in moonLayout) {
                if (group.alpha > 0.6f) {
                    val x = group.drawX() - moonPixel
                    val y = group.drawY() - moonPixel
                    RoundedUtil.drawRound(
                        x, y, group.width + moonPixel * 2f, group.height + moonPixel * 2f,
                        group.height / 2f + moonPixel, Color.BLACK
                    )
                }
            }
        }

        val expandOnAim = moonExpandOnAim
        val passes = if (expandOnAim) 2 else 1
        for (pass in 0 until passes) {
            for (i in moonLayout.size - 1 downTo 0) {
                val group = moonLayout[i]
                if (!expandOnAim || (pass == 1) == (group.children == null && group.expand > 0f)) {
                    moonRenderLabel(group)
                }
            }
        }

        GlStateManager.enableTexture2D()
        GlStateManager.enableBlend()
        GlStateManager.color(1f, 1f, 1f, 1f)
    }

    /** 布局：Expand On Aim 状态机 + 标签构建 + 防重叠堆叠（Max Stack 上限）+ 位移动画。 */
    private fun moonEnsureLayout() {
        if (!moonLayoutDirty) return
        moonLayoutDirty = false
        moonLayout.clear()

        val expandOnAim = moonExpandOnAim
        val roots = ArrayList<MoonGroup>()
        for (group in moonGroups) {
            if (group.parent == null) roots.add(group)
        }
        roots.addAll(moonPiles)
        val focus = if (expandOnAim) moonPickFocus(roots) else null

        for (group in roots) {
            val expandedNow = if (expandOnAim) group === focus else group.children == null
            val state = group.state
            if (state.expanded != expandedNow) {
                state.expanded = expandedNow
                if (group.children != null) {
                    state.alpha = 0f
                    state.placed = false
                    if (expandedNow) {
                        for (child in group.children!!) {
                            child.state.alpha = 0f
                            child.state.placed = false
                            child.state.expand = 0f
                        }
                    }
                }
            }
            state.hasBounds = false
            group.token = expandOnAim && !expandedNow
        }

        for (group in moonGroups) {
            group.leftAnchored = expandOnAim
            val state = group.state
            if (!expandOnAim) {
                state.expand = 1f
            } else {
                val target =
                    if (if (group.parent == null) !state.expanded else !group.parent!!.state.expanded) 0f else 1f
                state.expand = moonAnimate(state.expand, target, 12f)
                if (abs(state.expand - target) < 0.005f) state.expand = target
            }
        }

        val candidates = ArrayList<MoonGroup>()
        for (group in moonGroups) {
            if (group.visible && (group.parent == null || group.parent!!.state.expanded || group.state.expand > 0f)) {
                moonPrepare(group, candidates)
            }
        }
        for (pile in moonPiles) {
            if (!pile.visible) continue
            val children = pile.children!!
            var childExpanding = false
            for (child in children) {
                childExpanding = childExpanding or (child.state.expand > 0f)
            }
            if (!pile.state.expanded && !childExpanding) {
                moonPrepare(pile, candidates)
            } else {
                moonBuildLabel(pile)
            }
        }

        candidates.sortWith(Comparator { a, b ->
            val byDistance = a.distance.toInt().compareTo(b.distance.toInt())
            if (byDistance != 0) byDistance else a.key.compareTo(b.key)
        })

        val occupied = ArrayList<FloatArray>()
        val gap = 1.5f * moonScale
        val maxStack = moonMaxStack

        for (group in candidates) {
            val occupiedWidth =
                if (expandOnAim && group.parent == null && group.children == null) group.height else group.width
            val drawX = group.drawX()
            var top = group.naturalTop
            var moved = true
            var bumps = 0
            var iterations = 0
            while (moved && iterations++ < 64) {
                moved = false
                for (rect in occupied) {
                    if (drawX < rect[0] + rect[2] && drawX + occupiedWidth > rect[0] &&
                        top < rect[1] + rect[3] && top + group.height > rect[1]
                    ) {
                        top = rect[1] - group.height - gap
                        moved = true
                        bumps++
                    }
                }
            }

            val isFocus = focus != null && (group === focus || group.parent === focus)
            if (bumps >= maxStack && !isFocus) {
                group.state.alpha = 0f
                group.state.placed = false
            } else {
                occupied.add(floatArrayOf(drawX, top, occupiedWidth, group.height))
                val state = group.state
                val offset = top - group.naturalTop
                if (!state.placed) {
                    state.offset = offset
                    state.placed = true
                } else {
                    state.offset = moonAnimate(state.offset, offset, 14f)
                    if (abs(state.offset - offset) < 0.25f) state.offset = offset
                }

                val drawY = group.drawY()
                if (drawX + group.width >= 0f && drawX <= moonScreenWidth &&
                    drawY + group.height >= 0f && drawY <= moonScreenHeight
                ) {
                    moonLayout.add(group)
                }
            }
        }

        if (expandOnAim) {
            for (group in roots) {
                if (!group.visible) continue
                val state = group.state
                var x0 = group.drawX()
                var y0 = if (state.placed) group.drawY() else group.naturalTop
                var x1 = x0 + group.width
                var y1 = y0 + group.height
                if (state.expanded && group.children != null) {
                    for (child in group.children!!) {
                        if (child.visible && child.state.placed) {
                            x0 = min(x0, child.drawX())
                            y0 = min(y0, child.drawY())
                            x1 = max(x1, child.drawX() + child.width)
                            y1 = max(y1, child.drawY() + child.height)
                        }
                    }
                }
                val pad = (if (state.expanded) 8f else 4f) * moonScale
                state.bx0 = x0 - pad
                state.by0 = y0 - pad
                state.bx1 = x1 + pad
                state.by1 = y1 + pad
                state.hasBounds = true
            }
        }
    }

    /** Expand On Aim：准星（屏幕中心）指向的标签展开，其余收起为圆盘 + 计数。 */
    private fun moonPickFocus(candidates: List<MoonGroup>): MoonGroup? {
        val centerX = moonScreenWidth / 2f
        val centerY = moonScreenHeight / 2f
        var best: MoonGroup? = null
        var bestDist = Float.MAX_VALUE
        for (group in candidates) {
            val state = group.state
            if (!group.visible || !state.hasBounds) continue
            if (centerX < state.bx0 || centerX > state.bx1 || centerY < state.by0 || centerY > state.by1) continue
            if (state.expanded) return group
            val cx = (state.bx0 + state.bx1) / 2f - centerX
            val cy = (state.by0 + state.by1) / 2f - centerY
            val dist = cx * cx + cy * cy
            if (dist < bestDist) {
                bestDist = dist
                best = group
            }
        }
        return best
    }

    private fun moonPrepare(group: MoonGroup, candidates: MutableList<MoonGroup>) {
        val state = group.state
        state.alpha = moonAnimate(state.alpha, 1f, 10f)
        if (state.alpha > 0.995f) state.alpha = 1f
        moonBuildLabel(group)
        if (group.alpha > 0.02f) candidates.add(group)
    }

    private fun moonBuildLabel(group: MoonGroup) {
        val fit = moonFit(group)
        if (group.children != null) {
            moonBuildPile(group, fit)
            return
        }

        group.badgeFont = moonFont(8f, fit)
        group.nameFont = moonFont(12f, fit)
        group.countFont = moonFont(12f, fit)
        group.infoFont = moonFont(10f, fit)
        group.name = moonTrim(group.nameFont, group.stack.displayName, moonMaxNameWidth * fit)
        group.nameWidth = group.nameFont.getStringWidth(group.name).toFloat()
        group.countText = if (group.count > 1) "x${group.count}" else null
        group.countWidth = group.countText?.let { group.countFont.getStringWidth(it).toFloat() } ?: 0f

        var info: String? = if (moonEnchants) moonDescribe(group.stack) else null
        if (moonDistance) {
            val dist = "${group.distance.roundToInt()}m"
            info = if (info == null) dist else "$info  $dist"
        }
        group.infoText = info
        group.infoWidth = info?.let { group.infoFont.getStringWidth(it).toFloat() } ?: 0f

        group.height = 21f * fit
        group.inset = 2f * fit
        group.disk = group.height - group.inset * 2f
        group.left = group.inset + group.disk + 5f * fit
        var contentWidth = group.nameWidth
        if (group.countText != null) contentWidth += 3.5f * fit + group.countWidth
        if (group.infoText != null) contentWidth += 5f * fit + group.infoWidth
        group.fullWidth = group.left + contentWidth + 7.5f * fit
        group.expand = group.state.expand
        group.width = group.height + (group.fullWidth - group.height) * moonEaseInOut(group.expand)
        group.naturalTop = group.screenY - group.height
    }

    /** 标签缩放：距离越远越小 + 入场 alpha 缓动；标签 alpha 叠加距离边缘衰减。 */
    private fun moonFit(group: MoonGroup): Float {
        val range = maxRenderDistance.toFloat()
        val distFade = 1f - 0.25f * moonClamp01((group.distance - 6f) / max(1f, range * 0.6f))
        val alphaEase = 0.85f + 0.15f * moonEaseOut(group.state.alpha)
        val s = moonScale * distFade * alphaEase
        group.s = s
        val edge = moonClamp01((range - group.distance) / max(1f, range * 0.15f))
        group.alpha = group.state.alpha * edge
        return s
    }

    private fun moonBuildPile(group: MoonGroup, fit: Float) {
        val children = group.children!!
        group.height = 21f * fit
        group.inset = 2f * fit
        group.disk = group.height - group.inset * 2f
        group.shown = min(moonMaxPileIcons, children.size)
        group.hidden = children.size - group.shown
        group.nameFont = moonFont(11f, fit)
        group.countFont = moonFont(8f, fit)
        group.infoFont = moonFont(10f, fit)
        group.name = if (group.hidden > 0) "+${group.hidden}" else null
        group.nameWidth = group.name?.let { group.nameFont.getStringWidth(it).toFloat() } ?: 0f
        group.infoText = if (moonDistance && !group.token) "${group.distance.roundToInt()}m" else null
        group.infoWidth = group.infoText?.let { group.infoFont.getStringWidth(it).toFloat() } ?: 0f

        val icons = group.inset + group.shown * group.disk + (group.shown - 1) * moonPileGap * fit
        group.left = icons + 5f * fit
        var textWidth = group.nameWidth
        if (group.infoText != null) {
            textWidth += (if (group.name != null) 5f * fit else 0f) + group.infoWidth
        }
        group.width = if (textWidth > 0f) group.left + textWidth + 7.5f * fit else icons + group.inset
        group.naturalTop = group.screenY - group.height
    }

    private fun moonRenderLabel(group: MoonGroup) {
        val s = group.s
        val alpha = group.alpha
        val x = group.drawX()
        val y = group.drawY()

        if (moonBackground) {
            val bg = moonBackgroundColor
            RoundedUtil.drawRound(
                x, y, group.width, group.height, group.height / 2f,
                Color(bg.red, bg.green, bg.blue, (bg.alpha * alpha).toInt())
            )
        }

        if (group.children != null) {
            moonRenderPile(group, x, y, s, alpha)
            return
        }

        val tier = group.tier
        val expand = group.expand
        moonDrawDisk(group.stack, tier, x + group.inset, y + group.inset, group.disk, s, alpha)

        val badgeAlpha = alpha * (1f - moonClamp01(expand * 2.5f))
        if (badgeAlpha > 0.02f) {
            moonDrawCount(group.badgeFont, group.count, x + group.inset, y + group.inset, group.disk, s, badgeAlpha)
        }

        val textAlpha = alpha * moonClamp01((expand - 0.1f) / 0.6f)
        if (textAlpha <= 0.02f) return

        val clipped = expand < 1f
        if (clipped) {
            moonStartClip(x + group.inset + group.disk, y, group.width - group.inset - group.disk - group.height * 0.25f, group.height)
        }

        val nameFont = group.nameFont
        val capTop = moonCapTop(nameFont)
        val capHeight = moonCapBottom(nameFont) - capTop
        val textTop = y + (group.height - capHeight) / 2f - capTop
        val textBaseline = textTop + moonCapBottom(nameFont)
        var cursor = x + group.left - (1f - moonEaseOut(expand)) * 8f * s
        nameFont.drawStringWithShadow(group.name, cursor, textTop, moonArgb(moonTextPrimary, textAlpha))
        cursor += group.nameWidth

        if (group.countText != null) {
            cursor += 3.5f * s
            group.countFont.drawString(
                group.countText, cursor, textBaseline - moonCapBottom(group.countFont),
                moonArgb(moonTextPrimary, 0.7f * textAlpha)
            )
            cursor += group.countWidth
        }

        if (group.infoText != null) {
            cursor += 5f * s
            val infoColor =
                if (tier === moonWhite) moonArgb(moonTextPrimary, 0.55f * textAlpha)
                else moonArgb(moonSoften(tier).rgb and 16777215, 0.95f * textAlpha)
            group.infoFont.drawString(
                group.infoText, cursor, textBaseline - moonCapBottom(group.infoFont), infoColor
            )
        }

        if (clipped) {
            moonStopClip()
        }
    }

    /** 收起状态的圆盘 + 计数（Expand On Aim 下的 token 形态）。 */
    private fun moonDrawCount(font: GameFontRenderer, count: Int, x: Float, y: Float, disk: Float, s: Float, alpha: Float) {
        if (count <= 1 || alpha <= 0.2f) return
        val text = if (count > 99) "99+" else count.toString()
        val textWidth = font.getStringWidth(text).toFloat()
        font.drawStringWithShadow(
            text, x + disk - textWidth + 0.5f * s, y + disk + 0.5f * s - moonCapBottom(font),
            moonArgb(moonTextPrimary, alpha)
        )
    }

    /** 圆盘图标：tier 着色圆底 + 耐久环 / 白环 + 物品贴图。 */
    private fun moonDrawDisk(stack: ItemStack, tier: Color, x: Float, y: Float, disk: Float, s: Float, alpha: Float) {
        val centerX = x + disk / 2f
        val centerY = y + disk / 2f
        RoundedUtil.drawRound(x, y, disk, disk, disk / 2f, Color(tier.red, tier.green, tier.blue, (46f * alpha).toInt()))

        val durability = moonDurability(stack)
        val thickness: Float
        if (durability < 0f) {
            thickness = max(moonPixel * 1.5f, 1f * s)
            moonDrawArc(centerX, centerY, (disk - thickness) / 2f, thickness, 1f, tier, 0.9f * alpha)
        } else {
            thickness = max(moonPixel * 2f, 1.5f * s)
            val radius = (disk - thickness) / 2f
            moonDrawArc(centerX, centerY, radius, thickness, 1f, Color.WHITE, 0.16f * alpha)
            moonDrawArc(centerX, centerY, radius, thickness, durability, moonDurabilityColor(durability), 0.95f * alpha)
        }

        if (alpha > 0.2f) {
            val inner = disk - thickness * 2f
            val iconSize = inner * 0.7f * (0.6f + 0.4f * alpha)
            moonRenderItemStack(stack, centerX - iconSize / 2f, centerY - iconSize / 2f, iconSize / 16f)
            GlStateManager.disableDepth()
            GlStateManager.disableLighting()
            GlStateManager.enableBlend()
        }
    }

    /** Ground Ring：物品底部 3D 渐变光圈（多段 GL_QUAD_STRIP 圆环带）。 */
    private fun moonDrawGroundRing(group: MoonGroup, dx: Double, dy: Double, dz: Double) {
        val range = maxRenderDistance.toFloat()
        val alpha = moonClamp01((range - group.distance) / max(1f, range * 0.15f)) * max(0.15f, group.state.alpha)
        if (alpha <= 0.01f) return

        val tier = group.tier
        val radius = 0.42 + min(0.2, (group.members.size - 1) * 0.07)
        val intensity = 0.3f * alpha
        val y = dy + 0.02

        GlStateManager.pushMatrix()
        GlStateManager.disableTexture2D()
        GlStateManager.disableLighting()
        GlStateManager.disableCull()
        GlStateManager.disableAlpha()
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        GlStateManager.shadeModel(GL_SMOOTH)
        GlStateManager.enableDepth()
        GlStateManager.depthMask(false)

        for (i in 0 until moonGlowStops.size - 1) {
            val inner = moonGlowStops[i]
            val outer = moonGlowStops[i + 1]
            moonBand3D(dx, y, dz, radius * inner, radius * outer, tier, intensity * moonGlowFalloff(inner), intensity * moonGlowFalloff(outer))
        }

        GlStateManager.depthMask(true)
        GlStateManager.shadeModel(GL_FLAT)
        GlStateManager.enableAlpha()
        GlStateManager.enableCull()
        GlStateManager.enableTexture2D()
        glColor4f(1f, 1f, 1f, 1f)
        GlStateManager.resetColor()
        GlStateManager.popMatrix()
    }

    private fun moonBand3D(x: Double, y: Double, z: Double, innerRadius: Double, outerRadius: Double, color: Color, innerAlpha: Float, outerAlpha: Float) {
        val r = color.red / 255f
        val g = color.green / 255f
        val b = color.blue / 255f
        glBegin(GL_QUAD_STRIP)
        for (i in 0..48) {
            val angle = (Math.PI * 2) * i / 48.0
            val cos = cos(angle)
            val sin = sin(angle)
            glColor4f(r, g, b, innerAlpha)
            glVertex3d(x + cos * innerRadius, y, z + sin * innerRadius)
            glColor4f(r, g, b, outerAlpha)
            glVertex3d(x + cos * outerRadius, y, z + sin * outerRadius)
        }
        glEnd()
    }

    /** 2D 圆环（耐久环）：三条渐变带 + 端点圆点。 */
    private fun moonDrawArc(centerX: Float, centerY: Float, radius: Float, thickness: Float, progress: Float, color: Color, alpha: Float) {
        val sweep = 360f * moonClamp01(progress)
        if (sweep <= 0f) return
        val segments = max(8, ceil(sweep / 9f).toInt())
        val mid = thickness / 2f
        val glow = moonPixel * 0.75f
        val radii = floatArrayOf(radius - mid - glow, radius - mid, radius + mid, radius + mid + glow)
        val factors = floatArrayOf(0f, 1f, 1f, 0f)
        val r = color.red / 255f
        val g = color.green / 255f
        val b = color.blue / 255f

        GlStateManager.enableBlend()
        GlStateManager.disableTexture2D()
        GlStateManager.disableCull()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        GlStateManager.shadeModel(GL_SMOOTH)

        for (band in 0..2) {
            glBegin(GL_QUAD_STRIP)
            for (i in 0..segments) {
                val angle = Math.toRadians(-90.0 + sweep.toDouble() * i / segments)
                val cos = cos(angle).toFloat()
                val sin = sin(angle).toFloat()
                glColor4f(r, g, b, alpha * factors[band])
                glVertex2f(centerX + cos * radii[band], centerY + sin * radii[band])
                glColor4f(r, g, b, alpha * factors[band + 1])
                glVertex2f(centerX + cos * radii[band + 1], centerY + sin * radii[band + 1])
            }
            glEnd()
        }

        GlStateManager.shadeModel(GL_FLAT)
        GlStateManager.enableTexture2D()
        GlStateManager.enableCull()
        glColor4f(1f, 1f, 1f, 1f)
        GlStateManager.resetColor()

        if (sweep < 359f) {
            val capColor = Color(color.red, color.green, color.blue, alpha.toInt().coerceIn(0, 255))
            val endAngle = Math.toRadians(-90.0 + sweep.toDouble())
            RoundedUtil.drawRound(centerX - mid, centerY - radius - mid, thickness, thickness, mid, capColor)
            RoundedUtil.drawRound(
                centerX + cos(endAngle).toFloat() * radius - mid,
                centerY + sin(endAngle).toFloat() * radius - mid,
                thickness, thickness, mid, capColor
            )
        }
    }

    private fun moonRenderPile(group: MoonGroup, x: Float, y: Float, s: Float, alpha: Float) {
        val gap = moonPileGap * s
        var iconX = x + group.inset
        val iconY = y + group.inset
        val children = group.children!!
        for (i in 0 until group.shown) {
            val child = children[i]
            moonDrawDisk(child.stack, child.tier, iconX, iconY, group.disk, s, alpha)
            moonDrawCount(group.countFont, child.count, iconX, iconY, group.disk, s, alpha)
            iconX += group.disk + gap
        }

        val nameFont = group.nameFont
        val capHeight = moonCapBottom(nameFont) - moonCapTop(nameFont)
        val textTop = y + (group.height - capHeight) / 2f - moonCapTop(nameFont)
        val textBaseline = textTop + moonCapBottom(nameFont)
        var cursor = x + group.left
        if (group.name != null) {
            nameFont.drawStringWithShadow(group.name, cursor, textTop, moonArgb(moonTextPrimary, 0.85f * alpha))
            cursor += group.nameWidth + 5f * s
        }
        if (group.infoText != null) {
            group.infoFont.drawString(
                group.infoText, cursor, textBaseline - moonCapBottom(group.infoFont),
                moonArgb(moonTextPrimary, 0.55f * alpha)
            )
        }
    }

    /** 展开动画中的文字裁剪（物理像素 scissor）。 */
    private fun moonStartClip(x: Float, y: Float, width: Float, height: Float) {
        val inv = 1f / moonPixel
        val x0 = floor(x * inv).toInt()
        val x1 = ceil((x + max(0f, width)) * inv).toInt()
        val y0 = floor(y * inv).toInt()
        val y1 = ceil((y + height) * inv).toInt()
        GlStateManager.pushMatrix()
        glEnable(GL_SCISSOR_TEST)
        glScissor(x0, mc.displayHeight - y1, max(0, x1 - x0), y1 - y0)
    }

    private fun moonStopClip() {
        GlStateManager.popMatrix()
        glDisable(GL_SCISSOR_TEST)
    }

    private fun moonRenderItemStack(stack: ItemStack, x: Float, y: Float, scale: Float) {
        GlStateManager.pushMatrix()
        GlStateManager.translate(x.toDouble(), y.toDouble(), 0.0)
        GlStateManager.scale(scale, scale, scale)
        GlStateManager.enableRescaleNormal()
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0)
        RenderHelper.enableGUIStandardItemLighting()
        mc.renderItem.renderItemAndEffectIntoGUI(stack, 0, 0)
        RenderHelper.disableStandardItemLighting()
        GlStateManager.disableRescaleNormal()
        GlStateManager.disableBlend()
        GlStateManager.popMatrix()
    }

    // ------------------- Moon 工具方法 -------------------

    private fun moonInterp(prev: Double, now: Double, partialTicks: Float): Double =
        prev + (now - prev) * partialTicks

    /** moonlight RenderUtil.animate → AnimationUtil.base（帧率无关插值）。
     *  base 在 speed/(fps/60) > 2 时会越过目标发散震荡（高帧率下 Expand On Aim 标签闪烁的根源），
     *  这里把结果钳制回 current/target 区间，与 moonlight Shader2D 动画的收敛语义一致。 */
    private fun moonAnimate(current: Float, target: Float, speed: Float): Float {
        val next = AnimationUtil.base(current.toDouble(), target.toDouble(), speed.toDouble()).toFloat()
        return if (current < target) next.coerceIn(current, target) else next.coerceIn(target, current)
    }

    // moonlight capTop/capBottom 公式；-2.75f 为 AirPlus GameFontRenderer（baseY=y-3 + 内部平移）
    // 相对 moonlight 字体管线的原点补偿，保证文字在胶囊内垂直位置 1:1。
    private fun moonCapTop(font: GameFontRenderer): Float = -5.76f + 0.131f * (font.size / 2f)
    private fun moonCapBottom(font: GameFontRenderer): Float = -5.77f + 0.507f * (font.size / 2f)

    /** moonlight Fonts.xxx.get(size) → 客户端主字体（跟随 Client 模块字体选择），
     *  字号 ×2 对齐原 fontMoonlight 管线（AirPlus glScaled 0.25 vs MoonLight glScaled 0.5）。 */
    private fun moonFont(size: Float, fit: Float): GameFontRenderer =
        Fonts.mainFontDynamic(max(6f, (size * fit).roundToInt().toFloat()).toInt() * 2)

    private fun moonArgb(rgb: Int, alpha: Float): Int {
        val a = (alpha * 255f).toInt().coerceIn(4, 255)
        return (a shl 24) or (rgb and 16777215)
    }

    private fun moonSoften(color: Color): Color = Color(
        color.red + (255 - color.red) / 4,
        color.green + (255 - color.green) / 4,
        color.blue + (255 - color.blue) / 4
    )

    private fun moonEaseInOut(value: Float): Float {
        val v = moonClamp01(value)
        return v * v * (3f - 2f * v)
    }

    private fun moonEaseOut(value: Float): Float {
        val v = 1f - moonClamp01(value)
        return 1f - v * v * v
    }

    private fun moonClamp01(value: Float): Float = value.coerceIn(0f, 1f)

    private fun moonGlowFalloff(stop: Float): Float {
        val v = 1f - moonClamp01(stop)
        return v * v
    }

    private fun moonDurability(stack: ItemStack): Float =
        if (stack.isItemStackDamageable && stack.maxDamage > 0)
            moonClamp01(1f - stack.itemDamage.toFloat() / stack.maxDamage)
        else -1f

    private fun moonDurabilityColor(durability: Float): Color =
        if (durability > 0.5f) moonGreen else if (durability > 0.25f) moonOrange else moonRed

    private fun moonTrim(font: GameFontRenderer, text: String, maxWidth: Float): String {
        if (font.getStringWidth(text) <= maxWidth) return text
        val ellipsis = "..."
        val limit = maxWidth - font.getStringWidth(ellipsis)
        var length = text.length
        while (length > 1 && font.getStringWidth(text.substring(0, length)) > limit) length--
        if (length > 0 && text[length - 1] == '\u00a7') length--
        return text.substring(0, length) + ellipsis
    }

    private fun isMoonSameKind(a: ItemStack, b: ItemStack): Boolean =
        a.item === b.item && a.metadata == b.metadata && ItemStack.areItemStackTagsEqual(a, b)

    /** moonlight 的 Useful 过滤：附魔 / 非皮革护甲 / 武器 / 金苹果 / 药水 / 奶 / 珍珠 / 头颅 / 箭 / 苹果 / 矿物。 */
    private fun isMoonUseful(stack: ItemStack): Boolean {
        val item = stack.item
        if (stack.isItemEnchanted || item is ItemEnchantedBook) return true
        if (item is ItemArmor) return item.armorMaterial != ItemArmor.ArmorMaterial.LEATHER
        return item is ItemSword || item is ItemBow || item is ItemAppleGold || item is ItemPotion ||
            item is ItemBucketMilk || item is ItemEnderPearl || item is ItemSkull ||
            item === Items.arrow || item === Items.apple || item === Items.diamond ||
            item === Items.emerald || item === Items.gold_ingot || item === Items.gold_nugget ||
            item === Items.iron_ingot
    }

    private fun moonTierColor(stack: ItemStack): Color {
        val item = stack.item
        if (item is ItemPotion) return moonSoften(Color(item.getColorFromDamage(stack.metadata)))
        if (item is ItemAppleGold) return if (stack.metadata > 0) moonGold else moonGreen
        if (item is ItemSkull) return moonGold
        if (stack.isItemEnchanted || item is ItemEnchantedBook) return if (moonIsDiamond(item)) moonGold else moonPurple
        if (moonIsDiamond(item) || item === Items.diamond || item is ItemEnderPearl) return moonCyan
        return if (item !== Items.gold_ingot && item !== Items.gold_nugget && item !== Items.iron_ingot &&
            item !== Items.emerald && item !is ItemArmor && item !is ItemSword && item !is ItemBow
        ) moonWhite else moonGreen
    }

    private fun moonIsDiamond(item: Item): Boolean = when (item) {
        is ItemArmor -> item.armorMaterial == ItemArmor.ArmorMaterial.DIAMOND
        is ItemSword -> "EMERALD" == item.getToolMaterialName()
        is ItemTool -> item.toolMaterial == Item.ToolMaterial.EMERALD
        else -> false
    }

    /** 附魔摘要：优先级前 3 项缩写 + 罗马数字，多余显示 +N。 */
    private fun moonDescribe(stack: ItemStack): String? {
        if (stack.item is ItemAppleGold && stack.metadata > 0) return "Enchanted"
        val enchantments = EnchantmentHelper.getEnchantments(stack)
        if (enchantments.isEmpty()) return null
        val entries = enchantments.entries.sortedBy { moonEnchantOrder.indexOf(it.key).let { i -> if (i >= 0) i else 99 } }
        val builder = StringBuilder()
        val shown = min(3, entries.size)
        for (i in 0 until shown) {
            if (i > 0) builder.append(" · ")
            val id = entries[i].key
            val level = entries[i].value
            builder.append(moonShortName(id))
            val enchantment = Enchantment.getEnchantmentById(id)
            if (enchantment == null || enchantment.maxLevel > 1 || level > 1) {
                builder.append(' ').append(moonRoman(level))
            }
        }
        if (entries.size > shown) builder.append("  +").append(entries.size - shown)
        return builder.toString()
    }

    private fun moonShortName(id: Int): String = when (id) {
        0 -> "Prot"
        1 -> "Fire Prot"
        2 -> "Feather"
        3 -> "Blast"
        4 -> "Proj"
        5 -> "Resp"
        6 -> "Aqua"
        7 -> "Thorns"
        8 -> "Depth"
        16 -> "Sharp"
        17 -> "Smite"
        18 -> "Bane"
        19 -> "KB"
        20 -> "Fire"
        21 -> "Loot"
        32 -> "Eff"
        33 -> "Silk"
        34 -> "Unb"
        35 -> "Fort"
        48 -> "Power"
        49 -> "Punch"
        50 -> "Flame"
        51 -> "Inf"
        61 -> "Luck"
        62 -> "Lure"
        else -> "#$id"
    }

    private fun moonRoman(level: Int): String = when (level) {
        1 -> "I"
        2 -> "II"
        3 -> "III"
        4 -> "IV"
        5 -> "V"
        else -> level.toString()
    }

    private fun moonCompareImportance(a: MoonGroup, b: MoonGroup): Int {
        val byTier = moonTierRank(a.tier).compareTo(moonTierRank(b.tier))
        if (byTier != 0) return byTier
        val byCount = b.count.compareTo(a.count)
        return if (byCount != 0) byCount else a.key.compareTo(b.key)
    }

    private fun moonTierRank(color: Color): Int = when (color) {
        moonGold -> 0
        moonPurple -> 1
        moonCyan -> 2
        moonGreen -> 3
        moonWhite -> 5
        else -> 4
    }

    /**
     * Moon 标签组：单个/合并堆叠（members 计数）或 Compact Pile（children）。
     * 移植自 moonlight ItemESP.Group。
     */
    private class MoonGroup(val stack: ItemStack, var key: Int) {
        constructor(entity: EntityItem) : this(entity.entityItem, entity.entityId)

        val members = ArrayList<EntityItem>()
        var count = 0
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var distance = 0f
        var screenX = 0f
        var screenY = 0f
        var visible = false
        lateinit var state: MoonState
        lateinit var tier: Color
        var s = 1f
        var alpha = 1f
        lateinit var nameFont: GameFontRenderer
        lateinit var countFont: GameFontRenderer
        lateinit var infoFont: GameFontRenderer
        lateinit var badgeFont: GameFontRenderer
        var name: String? = null
        var countText: String? = null
        var infoText: String? = null
        var nameWidth = 0f
        var countWidth = 0f
        var infoWidth = 0f
        var width = 0f
        var height = 0f
        var inset = 0f
        var disk = 0f
        var left = 0f
        var naturalTop = 0f
        var children: ArrayList<MoonGroup>? = null
        var parent: MoonGroup? = null
        var shown = 0
        var hidden = 0
        var token = false
        var leftAnchored = false
        var fullWidth = 0f
        var expand = 1f

        fun drawX(): Float = if (leftAnchored) screenX - height / 2f else screenX - width / 2f

        fun drawY(): Float = naturalTop + state.offset
    }

    /** Moon 标签动画状态（alpha / 位移 / 展开宽度），跨帧保持。 */
    private class MoonState {
        var alpha = 0f
        var offset = 0f
        var placed = false
        var seen = 0
        var expand = 0f
        var expanded = false
        var hasBounds = false
        var bx0 = 0f
        var by0 = 0f
        var bx1 = 0f
        var by1 = 0f
    }

    override fun handleEvents() =
        super.handleEvents() || (InventoryCleaner.handleEvents() && InventoryCleaner.highlightUseful)
}
