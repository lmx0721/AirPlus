package net.airplus.ui.client.clickgui.opai

import net.airplus.config.BlockValue
import net.airplus.config.BoolValue
import net.airplus.config.ColorValue
import net.airplus.config.FloatRangeValue
import net.airplus.config.FloatValue
import net.airplus.config.FontValue
import net.airplus.config.IntRangeValue
import net.airplus.config.IntValue
import net.airplus.config.ListValue
import net.airplus.config.RangeSlider
import net.airplus.config.TextValue
import net.airplus.config.Value
import net.airplus.features.module.Category
import net.airplus.features.module.Module
import net.airplus.features.module.ModuleManager
import net.airplus.ui.client.clickgui.opai.OpaiCompat.IMEFocusHelper
import net.airplus.ui.client.clickgui.opai.OpaiContentLayout.Entry
import net.airplus.ui.client.clickgui.opai.OpaiContentLayout.Kind
import net.airplus.ui.client.clickgui.opai.OpaiContentLayout.Rect
import net.airplus.ui.client.clickgui.opai.widget.OpaiTextField
import net.airplus.utils.render.ColorUtils.withAlpha
import net.airplus.utils.render.InternalBlurShader
import net.minecraft.client.gui.GuiScreen
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import java.awt.Color
import java.io.IOException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Opai 风格 ClickGUI，1:1 移植自 Samsara 的 OpaiClickGuiScreen：
 * 单窗口多列、每分类一列、弹性缩放开场动画、指数衰减动效与 ripple 点击反馈。
 * 渲染全部走 [OpaiCanvas]（AirPlus 遗留 GL），未引入 NanoVG。
 */
class OpaiScreen : GuiScreen() {

    private val columns = ArrayList<Column>()
    private val canvas = OpaiCanvas(this)
    private val searchField = OpaiTextField(32)
    private val menuAnim = OpaiMotion(0f, OpaiGUI.getAnimRate(30.0))

    private var initialized = false
    private var detached = true
    private var openedAt = 0.0
    private var lastFrameTime = 0.0
    private var menuOpacity = 0f
    private var menuScale = 1f
    private var closing = false
    private var closeStarted = 0.0
    private var closingAlpha = 0f
    private var closingScale = 1f
    private var transformCenterY = 0f
    private var maxBodyH = 0f
    private var horizontalScroll = 0f
    private var maxHorizontalScroll = 0f
    private var shiftDown = false
    private var layoutWidth = 0
    private var layoutHeight = 0

    // 反变换后的 GUI 坐标（抵消开场缩放），交互判定一律用它。
    private var mouseX = 0.0
    private var mouseY = 0.0

    private var draggedColumn: Column? = null
    private var dragX = 0f
    private var dragY = 0f
    private var draggedScrollbar: Column? = null
    private var scrollbarGrab = 0f
    private var draggingSlider: Value<*>? = null
    private var sliderRow: Row? = null
    private var sliderColumn: Column? = null
    private var draggingRange: Pair<Value<*>, RangeSlider>? = null
    private var draggingColor: Pair<ColorValue, ColorZone>? = null
    private var bindingRow: Row? = null

    private val toggleAnims = HashMap<Any, OpaiMotion>()
    private val sliderAnims = HashMap<Value<*>, OpaiMotion>()
    private val controlFeedback = HashMap<Any, OpaiFeedback>()
    private val textFields = HashMap<TextValue, OpaiTextField>()
    private var activeField: OpaiTextField? = null

    override fun initGui() {
        super.initGui()
        if (initialized) {
            val layout = OpaiLayout.viewport(width, height, columns.size)
            val previous = OpaiLayout.viewport(layoutWidth, layoutHeight, columns.size)
            val previousScroll = horizontalScroll
            maxBodyH = layout.maxBodyHeight
            transformCenterY = layout.top + (OpaiLayout.HEADER_H + layout.maxBodyHeight) / 2f
            maxHorizontalScroll = OpaiLayout.horizontalOverflow(width, columns.size, layout)
            horizontalScroll = horizontalScroll.coerceIn(0f, maxHorizontalScroll)
            if (width != layoutWidth || height != layoutHeight) {
                for (column in columns) {
                    column.x = (column.x + layout.left - previous.left + previousScroll - horizontalScroll)
                        .coerceIn(4f - maxHorizontalScroll, max(4f, width - OpaiLayout.COL_W - 4f + maxHorizontalScroll))
                    column.y = (column.y + layout.top - previous.top)
                        .coerceIn(4f, max(4f, height - OpaiLayout.HEADER_H - OpaiLayout.ROW_H - 8f))
                }
            }
            if (detached) replayOpening()
            detached = false
            closing = false
            lastFrameTime = OpaiMotion.now()
            layoutWidth = width
            layoutHeight = height
            refreshLayouts()
            return
        }
        buildColumns()
        bindingRow = null
        clearDrag()
        val layout = OpaiLayout.viewport(width, height, columns.size)
        maxBodyH = layout.maxBodyHeight
        horizontalScroll = 0f
        maxHorizontalScroll = OpaiLayout.horizontalOverflow(width, columns.size, layout)
        shiftDown = false
        transformCenterY = layout.top + (OpaiLayout.HEADER_H + layout.maxBodyHeight) / 2f
        initialized = true
        replayOpening()
        lastFrameTime = OpaiMotion.now()
        closing = false
        detached = false
        layoutWidth = width
        layoutHeight = height
        refreshLayouts()
    }

    private fun buildColumns() {
        columns.clear()
        val categories = Category.values()
        val layout = OpaiLayout.viewport(width, height, categories.size)
        for ((index, category) in categories.withIndex()) {
            val column = Column(category)
            for (module in ModuleManager) {
                // 隐藏模块仍保留在列里，配置重载后可重新显示（与 Samsara 一致）。
                if (module.category == category) column.rows.add(Row(module))
            }
            column.rows.sortWith(compareBy { it.module.name.lowercase(Locale.US) })
            val defaultX = layout.left + index * (OpaiLayout.COL_W + OpaiLayout.COL_GAP)
            val position = OpaiLayoutState.loadColumn(column.id, defaultX, layout.top)
            column.x = position.first
            column.y = position.second.coerceIn(4f, max(4f, height - OpaiLayout.HEADER_H - OpaiLayout.ROW_H - 8f))
            columns.add(column)
        }
    }

    /** 重新附着（窗口尺寸变化/再次打开）时重放开场弹性动画。 */
    private fun replayOpening() {
        openedAt = OpaiMotion.now()
        menuAnim.snap(0f, openedAt)
    }

    private fun settingKind(value: Value<*>): Kind? = when (value) {
        is BoolValue -> Kind.BOOLEAN
        is IntValue, is FloatValue, is IntRangeValue, is FloatRangeValue, is BlockValue -> Kind.NUMBER
        is ListValue -> Kind.MODE
        is ColorValue -> Kind.COLOR
        is FontValue -> Kind.FONT
        is TextValue -> Kind.TEXT
        else -> null
    }

    private fun refreshLayouts() {
        val now = OpaiMotion.now()
        val query = searchField.text.trim()
        for (column in columns) {
            val columnFactor = column.expand.approach(if (column.expanded) 1f else 0f, now)
            val content = OpaiContentLayout<Target>()
            for (row in column.rows) {
                if (row.module.isHidden) continue
                if (query.isNotEmpty() && !row.matches(query)) continue
                content.add(Target(row, null), Kind.MODULE)
                val rowFactor = row.expand.approach(if (row.expanded) 1f else 0f, now)
                for (value in row.module.values) {
                    if (value is ListValue) {
                        row.modes.computeIfAbsent(value) { OpaiMotion(0f, OpaiGUI.getAnimRate(40.0)) }.approach(
                            if (row.expanded && column.expanded && value.shouldRender() && row.openMode === value) 1f else 0f, now)
                    }
                }
                if (rowFactor <= 0f) continue
                val openMode = row.openMode
                if (openMode != null && !openMode.shouldRender()) row.openMode = null
                val section = OpaiContentLayout<Target>()
                for (value in row.module.values) {
                    val kind = settingKind(value) ?: continue
                    if (!value.shouldRender()) continue
                    section.add(Target(row, value), kind)
                    // 展开的颜色行在布局内追加取色器小节（随行展开动画 reveal）。
                    if (kind == Kind.COLOR && row.openMode === value) section.add(Target(row, value), Kind.COLOR_PICKER)
                }
                content.reveal(section, rowFactor)
            }
            column.content = content
            val available = max(OpaiLayout.ROW_H + OpaiLayout.FOOTER_H, height - column.y - OpaiLayout.HEADER_H - 8f)
            column.targetBodyH = content.bodyHeight(min(maxBodyH, available))
            column.bodyH = column.targetBodyH * columnFactor
            column.maxScroll = content.maxScroll(column.targetBodyH)
            if (columnFactor <= 0.01f && !column.expanded) {
                column.scroll = 0f
                column.scrollTarget = 0f
            }
            column.scrollTarget = column.scrollTarget.coerceIn(0f, column.maxScroll)
            column.dropdowns = layoutDropdowns(column)
        }
        val slider = draggingSlider
        val sliderOwner = sliderRow
        if (slider != null && (sliderOwner == null || !sliderOwner.expanded || sliderOwner.module.isHidden || !slider.shouldRender())) {
            clearDrag()
        }
    }

    override fun drawScreen(rawX: Int, rawY: Int, partialTicks: Float) {
        val now = OpaiMotion.now()
        val dt = max(0.0, now - lastFrameTime).toFloat()
        lastFrameTime = now
        val smoothing = 1f - exp(-dt * 0.014f)
        val alpha = if (closing) closingAlpha * exp(-30.0 * (now - closeStarted) / 1000.0).toFloat()
        else menuAnim.approach(1f, now)
        menuOpacity = alpha
        menuScale = if (closing) closingScale + .6f * (1f - alpha / max(0.001f, closingAlpha))
        else OpaiMotion.openingScale(now - openedAt)
        if (alpha <= 0.01f) {
            if (closing) {
                detached = true
                mc.displayGuiScreen(null as GuiScreen?)
            }
            return
        }
        setMouse(rawX.toDouble(), rawY.toDouble())
        for (column in columns) {
            column.scroll += (column.scrollTarget - column.scroll) * smoothing
        }
        refreshLayouts()
        val palette = OpaiGUI.currentPalette()
        canvas.beginFrame(alpha)
        canvas.setTransform(menuScale, width / 2f, transformCenterY)
        if (OpaiGUI.backgroundBlur > 0.01f) {
            InternalBlurShader.blurArea(0f, 0f, width.toFloat(), height.toFloat(), OpaiGUI.backgroundBlur * alpha)
        }
        canvas.scale(menuScale, width / 2f, transformCenterY) {
            drawColumns(canvas, palette, now)
            drawSearch(canvas, palette)
        }
    }

    private fun drawColumns(surface: OpaiSurface, palette: OpaiPalette, now: Double) {
        for (column in columns) {
            val bodyY = column.y + OpaiLayout.HEADER_H
            if (column.bodyH <= 0.5f) {
                surface.rounded(column.x, column.y, OpaiLayout.COL_W, OpaiLayout.HEADER_H, OpaiLayout.RADIUS, palette.header)
                surface.text(column.category.displayName, column.x + OpaiLayout.TEXT_PAD, column.y + OpaiLayout.HEADER_H / 2f,
                    OpaiStyle.HEADER_TEXT_SIZE, OpaiLayout.COL_W - OpaiLayout.TEXT_PAD * 2, palette.text)
                continue
            }
            surface.panel(column.x, bodyY, OpaiLayout.COL_W, column.bodyH, false, palette.body)
            surface.panel(column.x, column.y, OpaiLayout.COL_W, OpaiLayout.HEADER_H, true, palette.header)
            surface.text(column.category.displayName, column.x + OpaiLayout.TEXT_PAD, column.y + OpaiLayout.HEADER_H / 2f,
                OpaiStyle.HEADER_TEXT_SIZE, OpaiLayout.COL_W - OpaiLayout.TEXT_PAD * 2, palette.text)
            if (column.bodyH > OpaiLayout.FOOTER_H + 0.5f) {
                surface.clip(column.x + 1f, bodyY, OpaiLayout.COL_W - 2f, column.bodyH - OpaiLayout.FOOTER_H) {
                    for (entry in column.content.entries()) {
                        val y = bodyY + entry.top - column.scroll
                        if (entry.visible() && bodyY + entry.visibleBottom() - column.scroll > bodyY
                            && bodyY + entry.visibleTop() - column.scroll < bodyY + column.bodyH - OpaiLayout.FOOTER_H) {
                            surface.clip(column.x, bodyY + entry.visibleTop() - column.scroll, OpaiLayout.COL_W,
                                entry.visibleBottom() - entry.visibleTop()) {
                                drawEntry(surface, palette, column, entry, y, now)
                            }
                        }
                    }
                }
            }
            if (column.maxScroll > 0f) {
                val bar = OpaiContentLayout.scrollbar(column.bodyH, column.content.height(), column.scroll)
                surface.rounded(column.x + OpaiLayout.COL_W - 3.5f, bodyY + bar.top, 2.5f, bar.height, 1.25f, palette.scrollbar)
            }
            drawDropdowns(surface, palette, column, now)
        }
    }

    /** 下拉选项悬浮于后续控件之上，其展开从不参与列布局（与 Samsara 一致）。 */
    private fun layoutDropdowns(column: Column): List<Dropdown> {
        val dropdowns = ArrayList<Dropdown>()
        for (entry in column.content.entries()) {
            if (entry.kind != Kind.MODE) continue
            val choices = entry.target.value as? ListValue ?: continue
            val row = entry.target.row
            val motion = row.modes[choices] ?: continue
            if (motion.value() <= 0f || choices.values.isEmpty()) continue
            val y = column.y + OpaiLayout.HEADER_H + entry.top - column.scroll
            val field = OpaiContentLayout.field(column.x, y, OpaiLayout.COL_W)
            if (entry.visibleBottom() < entry.top + OpaiLayout.FIELD_Y + OpaiLayout.FIELD_H - .5f
                || field.y < column.y + OpaiLayout.HEADER_H
                || field.y + OpaiLayout.FIELD_H > column.y + OpaiLayout.HEADER_H + column.bodyH - OpaiLayout.FOOTER_H) continue
            val fullHeight = min(choices.values.size * OpaiLayout.OPTION_H, max(0f, height - 4f - field.y - OpaiLayout.FIELD_H))
            if (fullHeight <= 0f) continue
            val scroll = (row.modeScroll[choices] ?: 0f)
                .coerceIn(0f, choices.values.size * OpaiLayout.OPTION_H - fullHeight)
            dropdowns.add(Dropdown(column, row, choices,
                Rect(field.x, field.y + OpaiLayout.FIELD_H, field.width, fullHeight * motion.value()), scroll, fullHeight))
        }
        return dropdowns
    }

    private fun dropdownAt(column: Column): Dropdown? {
        for (menu in column.dropdowns.reversed()) {
            if (menu.bounds.contains(mouseX, mouseY)) return menu
        }
        return null
    }

    private fun drawDropdowns(surface: OpaiSurface, palette: OpaiPalette, column: Column, now: Double) {
        for (menu in column.dropdowns) {
            val bounds = menu.bounds
            surface.rounded(bounds.x, bounds.y, bounds.width, bounds.height, 2f, palette.field)
            surface.clip(bounds.x, bounds.y, bounds.width, bounds.height) {
                for (option in menu.mode.values.indices) {
                    val y = bounds.y + option * OpaiLayout.OPTION_H - menu.scroll
                    if (y + OpaiLayout.OPTION_H <= bounds.y || y >= bounds.y + bounds.height) continue
                    val entry = Entry(Target(menu.row, menu.mode), Kind.OPTION, 0f, OpaiLayout.OPTION_H, option, 0f, OpaiLayout.OPTION_H)
                    drawEntry(surface, palette, column, entry, y, now)
                }
            }
        }
    }

    private fun drawEntry(surface: OpaiSurface, palette: OpaiPalette, column: Column, entry: Entry<Target>, y: Float, now: Double) {
        val row = entry.target.row
        val x = column.x
        val labelW = OpaiLayout.COL_W - OpaiLayout.TEXT_PAD * 2
        val overlay = dropdownAt(column)
        val hovered = columnAt() == column &&
            (if (entry.kind == Kind.OPTION) overlay != null && overlay.mode === entry.target.value
                && Rect(x + OpaiLayout.TEXT_PAD - 2f, y, labelW + 4f, OpaiLayout.OPTION_H).contains(mouseX, mouseY)
            else overlay == null && column.content.hit(mouseY - column.y - OpaiLayout.HEADER_H, column.scroll, column.bodyH) === entry)
        if (entry.kind == Kind.MODULE) {
            val enabled = row.enabled.approach(if (row.module.state) 1f else 0f, now)
            val hover = row.hover.approach(if (hovered && draggedColumn == null) 1f else 0f, now)
            if (hover > 0.01f && enabled < 0.99f) {
                surface.rect(x, y, OpaiLayout.COL_W, OpaiLayout.ROW_H,
                    OpaiStyle.multiplyOpacity(palette.hover, hover * (1f - enabled)))
            }
            if (enabled > 0.01f) {
                surface.rect(x, y, OpaiLayout.COL_W, OpaiLayout.ROW_H,
                    OpaiStyle.multiplyOpacity(OpaiStyle.mix(palette.enabled, palette.enabledHover, hover), enabled))
            }
            row.feedback.paint(surface, x, y, OpaiLayout.COL_W, OpaiLayout.ROW_H, 0f, palette.text, now)
            surface.text(if (row === bindingRow) "Press a key" else row.name,
                x + OpaiLayout.TEXT_PAD, y + OpaiLayout.ROW_H / 2f, OpaiStyle.ROW_TEXT_SIZE, labelW,
                OpaiStyle.mix(palette.text, palette.enabledText, enabled))
            return
        }
        val value = entry.target.value ?: return
        when (entry.kind) {
            Kind.BOOLEAN -> {
                val bool = value as BoolValue
                surface.text(OpaiLayout.displayName(value.name), x + OpaiLayout.TEXT_PAD, y + OpaiLayout.BOOLEAN_H / 2f,
                    OpaiStyle.SETTING_TEXT_SIZE, labelW - 30f, palette.text)
                drawToggle(surface, palette, x + OpaiLayout.COL_W - OpaiLayout.TEXT_PAD - 26f,
                    y + (OpaiLayout.BOOLEAN_H - 16f) / 2f, bool.get(), feedbackFor(bool),
                    toggleAnims.computeIfAbsent(bool) { OpaiMotion(if (bool.get()) 1f else 0f, OpaiGUI.getAnimRate(30.0)) }, now)
            }
            Kind.NUMBER -> drawNumber(surface, palette, column, value, x, y, labelW, now)
            Kind.MODE -> {
                val choices = value as ListValue
                surface.text(OpaiLayout.displayName(value.name), x + OpaiLayout.TEXT_PAD, y + 8f,
                    OpaiStyle.SETTING_TEXT_SIZE, labelW, palette.text)
                val field = OpaiContentLayout.field(x, y, OpaiLayout.COL_W)
                val motion = row.modes[choices]
                val open = row.openMode === value || (motion != null && motion.value() > 0f)
                surface.rounded(field.x, field.y, field.width, field.height, 2f, palette.field)
                if (open) surface.rect(field.x, field.y + 3f, field.width, OpaiLayout.MODE_H - OpaiLayout.FIELD_Y - 3f, palette.field)
                surface.text(OpaiLayout.displayName(choices.get()), field.x + 6f, field.y + OpaiLayout.FIELD_H / 2f,
                    OpaiStyle.SETTING_TEXT_SIZE, field.width - 12f, palette.text)
                surface.rect(field.x + 1f, field.y + OpaiLayout.FIELD_H - 1f, field.width - 2f, 1f,
                    if (open) palette.enabled else palette.fieldLine)
                feedbackFor(value).paint(surface, field.x, field.y, field.width, field.height, 2f, palette.accent, now)
            }
            Kind.OPTION -> {
                val choices = value as ListValue
                val options = choices.values
                if (entry.option >= options.size) return
                val optionName = options[entry.option]
                val selected = choices.get().equals(optionName, ignoreCase = true)
                val left = x + OpaiLayout.TEXT_PAD - 2f
                val width = labelW + 4f
                if (selected || hovered) {
                    surface.rect(left + 1f, y, width - 2f, OpaiLayout.OPTION_H, if (selected) palette.selected else palette.hover)
                }
                if (selected) surface.check(left + 5f, y + 7f, 6f, palette.text)
                surface.text(OpaiLayout.displayName(optionName), left + (if (selected) 17f else 6f), y + OpaiLayout.OPTION_H / 2f,
                    OpaiStyle.SETTING_TEXT_SIZE, width - (if (selected) 23f else 12f), palette.text)
                row.optionFeedback.computeIfAbsent(OptionKey(value, entry.option)) { OpaiFeedback() }
                    .paint(surface, left, y, width, OpaiLayout.OPTION_H, 0f, palette.accent, now)
            }
            Kind.COLOR -> {
                val color = value as ColorValue
                surface.text(OpaiLayout.displayName(value.name), x + OpaiLayout.TEXT_PAD, y + OpaiLayout.BOOLEAN_H / 2f,
                    OpaiStyle.SETTING_TEXT_SIZE, labelW - 30f, palette.text)
                val swatchX = x + OpaiLayout.COL_W - OpaiLayout.TEXT_PAD - 26f
                val swatchY = y + (OpaiLayout.BOOLEAN_H - 16f) / 2f
                surface.rounded(swatchX, swatchY, 26f, 16f, 8f, palette.toggleOutline)
                surface.rounded(swatchX + 1f, swatchY + 1f, 24f, 14f, 7f, color.selectedColor().rgb)
            }
            Kind.COLOR_PICKER -> drawColorPicker(surface, palette, value as ColorValue, x, y, now)
            Kind.FONT -> {
                val font = value as FontValue
                val valueText = OpaiLayout.displayName(font.displayName.removePrefix("Font: "))
                val valueWidth = min(labelW * 0.45f, surface.textWidth(valueText, OpaiStyle.SETTING_TEXT_SIZE))
                surface.text(OpaiLayout.displayName(value.name), x + OpaiLayout.TEXT_PAD, y + OpaiLayout.BOOLEAN_H / 2f,
                    OpaiStyle.SETTING_TEXT_SIZE, labelW - valueWidth - 4f, palette.text)
                surface.text(valueText, x + OpaiLayout.COL_W - OpaiLayout.TEXT_PAD - valueWidth, y + OpaiLayout.BOOLEAN_H / 2f,
                    OpaiStyle.SETTING_TEXT_SIZE, valueWidth, palette.text)
                feedbackFor(value).paint(surface, x, y, OpaiLayout.COL_W, OpaiLayout.BOOLEAN_H, 0f, palette.accent, now)
            }
            Kind.TEXT -> {
                val text = value as TextValue
                surface.text(OpaiLayout.displayName(value.name), x + OpaiLayout.TEXT_PAD, y + 8f,
                    OpaiStyle.SETTING_TEXT_SIZE, labelW, palette.text)
                val field = OpaiContentLayout.field(x, y, OpaiLayout.COL_W)
                val field0 = textFields.computeIfAbsent(text) { OpaiTextField(256) }
                if (!field0.focused && field0.text != text.get()) field0.text = text.get()
                field0.draw(surface, palette, field.x, field.y, field.width, field.height)
            }
            else -> { }
        }
    }

    private fun drawNumber(surface: OpaiSurface, palette: OpaiPalette, column: Column, value: Value<*>, x: Float, y: Float, labelW: Float, now: Double) {
        val valueText = formatValue(value)
        val valueWidth = min(labelW * 0.45f, surface.textWidth(valueText, OpaiStyle.SETTING_TEXT_SIZE))
        surface.text(OpaiLayout.displayName(value.name), x + OpaiLayout.TEXT_PAD, y + 6.5f,
            OpaiStyle.SETTING_TEXT_SIZE, labelW - valueWidth - 4f, palette.text)
        surface.text(valueText, x + OpaiLayout.COL_W - OpaiLayout.TEXT_PAD - valueWidth, y + 6.5f,
            OpaiStyle.SETTING_TEXT_SIZE, valueWidth, palette.text)
        val trackW = OpaiLayout.COL_W - OpaiLayout.TEXT_PAD * 2
        val trackY = y + OpaiLayout.SLIDER_Y
        if (value is IntRangeValue || value is FloatRangeValue) {
            val firstFraction = rangeFraction(value, RangeSlider.LEFT)
            val lastFraction = rangeFraction(value, RangeSlider.RIGHT)
            surface.rounded(x + OpaiLayout.TEXT_PAD, trackY - 1.25f, trackW, 2.5f, 1.25f, palette.track)
            val lo = trackW * firstFraction
            val hi = trackW * lastFraction
            surface.rounded(x + OpaiLayout.TEXT_PAD + min(lo, hi), trackY - 1.25f, abs(hi - lo).coerceAtLeast(0.5f), 2.5f, 1.25f, palette.accent)
            for (fraction in floatArrayOf(firstFraction, lastFraction)) {
                val knobX = x + OpaiLayout.TEXT_PAD + trackW * fraction
                val pulse = feedbackFor(value).press(now)
                if (pulse > 0f) surface.rounded(knobX - 7f, trackY - 7f, 14f, 14f, 7f, OpaiStyle.multiplyOpacity(palette.accent, .25f * pulse))
                surface.rounded(knobX - 4.5f, trackY - 3.5f, 9f, 9f, 4.5f, 0x33000000)
                surface.rounded(knobX - 4f, trackY - 4f, 8f, 8f, 4f, palette.accent)
            }
        } else {
            val range = numericMaximum(value) - numericMinimum(value)
            val fraction = if (range <= 0f) 0f else ((numericCurrent(value) - numericMinimum(value)) / range).coerceIn(0f, 1f)
            val slider = sliderAnims.computeIfAbsent(value) { OpaiMotion(fraction, OpaiGUI.getAnimRate(35.0)) }
            if (draggingSlider === value) slider.snap(fraction, OpaiMotion.now())
            val displayed = slider.approach(fraction, now)
            surface.rounded(x + OpaiLayout.TEXT_PAD, trackY - 1.25f, trackW, 2.5f, 1.25f, palette.track)
            surface.rounded(x + OpaiLayout.TEXT_PAD, trackY - 1.25f, trackW * displayed, 2.5f, 1.25f, palette.accent)
            val knobX = x + OpaiLayout.TEXT_PAD + trackW * displayed
            val pulse = feedbackFor(value).press(now)
            if (pulse > 0f) surface.rounded(knobX - 7f, trackY - 7f, 14f, 14f, 7f, OpaiStyle.multiplyOpacity(palette.accent, .25f * pulse))
            surface.rounded(knobX - 4.5f, trackY - 3.5f, 9f, 9f, 4.5f, 0x33000000)
            surface.rounded(knobX - 4f, trackY - 4f, 8f, 8f, 4f, palette.accent)
        }
    }

    private fun drawToggle(surface: OpaiSurface, palette: OpaiPalette, x: Float, y: Float, state: Boolean,
                           feedback: OpaiFeedback, anim: OpaiMotion, now: Double) {
        val t = anim.approach(if (state) 1f else 0f, now)
        surface.rounded(x, y, 26f, 16f, 8f, OpaiStyle.mix(palette.toggleOutline, palette.accent, t))
        if (t < 1f) {
            surface.rounded(x + 1f, y + 1f, 24f, 14f, 7f, OpaiStyle.multiplyOpacity(palette.field, 1f - t))
        }
        val stretch = 3f * feedback.press(now)
        val knobX = x + 2f + (10f - stretch) * t
        surface.rounded(knobX, y + 2f, 12f + stretch, 12f, 6f, OpaiStyle.mix(palette.fieldLine, palette.enabledText, t))
        feedback.paint(surface, x, y, 26f, 16f, 8f, palette.accent, now)
        if (t > 0.01f) surface.check(knobX + 3f, y + 5f, 6f, OpaiStyle.multiplyOpacity(palette.accent, t))
    }

    private fun drawColorPicker(surface: OpaiSurface, palette: OpaiPalette, color: ColorValue, x: Float, y: Float, now: Double) {
        val geo = pickerGeometry(x, y)
        surface.rounded(geo.boxX, geo.boxY, geo.boxW, geo.boxH, 2f, palette.field)
        // SB 方块：色相底色 + 白→透明横向渐变 + 透明→黑纵向渐变（分层叠加近似 NanoVG 渐变）。
        val hueBase = 0xFF000000.toInt() or Color.HSBtoRGB(color.hueSliderY, 1f, 1f)
        surface.rect(geo.sb.x, geo.sb.y, geo.sb.width, geo.sb.height, hueBase)
        val columns = 24
        for (i in 0 until columns) {
            val fx = i / (columns - 1f)
            val a = (255f * (1f - fx)).roundToInt()
            surface.rect(geo.sb.x + geo.sb.width * fx, geo.sb.y, geo.sb.width / columns + 1f, geo.sb.height, (a shl 24) or 0xFFFFFF)
        }
        val rows = 30
        for (j in 0 until rows) {
            val fy = j / (rows - 1f)
            val a = (255f * fy).roundToInt()
            surface.rect(geo.sb.x, geo.sb.y + geo.sb.height * fy, geo.sb.width, geo.sb.height / rows + 1f, a shl 24)
        }
        val knobX = geo.sb.x + geo.sb.width * color.colorPickerPos.x
        val knobY = geo.sb.y + geo.sb.height * color.colorPickerPos.y
        surface.rounded(knobX - 2.5f, knobY - 2.5f, 5f, 5f, 2.5f, 0xFFFFFFFF.toInt())
        surface.rounded(knobX - 1.5f, knobY - 1.5f, 4f, 4f, 2f, 0xFF000000.toInt())
        // 色相条
        val segments = 12
        for (i in 0 until segments) {
            val hue = i / (segments - 1f)
            surface.rect(geo.hue.x + geo.hue.width * hue, geo.hue.y, geo.hue.width / segments + 1f, geo.hue.height,
                0xFF000000.toInt() or Color.HSBtoRGB(hue, 1f, 1f))
        }
        surface.rounded(geo.hue.x + geo.hue.width * color.hueSliderY - 2f, geo.hue.y - 2f, 4f, geo.hue.height + 4f, 2f, 0xFFFFFFFF.toInt())
        // Rainbow 开关（沿用 toggle 视觉）
        surface.plainText("Rainbow", geo.rainbow.x, geo.rainbow.y + geo.rainbow.height / 2f, OpaiStyle.ROW_TEXT_SIZE,
            geo.rainbow.width - 30f, palette.text)
        drawToggle(surface, palette, geo.rainbow.x + geo.rainbow.width - 26f, geo.rainbow.y + (geo.rainbow.height - 16f) / 2f,
            color.rainbow, feedbackFor(color),
            toggleAnims.computeIfAbsent(color) { OpaiMotion(if (color.rainbow) 1f else 0f, OpaiGUI.getAnimRate(30.0)) }, now)
        // 不透明度条：底色 + 当前色 alpha 渐变
        surface.rounded(geo.opacity.x, geo.opacity.y, geo.opacity.width, geo.opacity.height, 2f, 0xFF202024.toInt())
        val current = color.selectedColor()
        val steps = 12
        for (i in 0 until steps) {
            val fx = i / (steps - 1f)
            val a = (255f * fx).roundToInt()
            surface.rect(geo.opacity.x + geo.opacity.width * fx, geo.opacity.y, geo.opacity.width / steps + 1f, geo.opacity.height,
                (a shl 24) or (current.rgb and 0xFFFFFF))
        }
        surface.rounded(geo.opacity.x + geo.opacity.width * color.opacitySliderY - 2f, geo.opacity.y - 2f, 4f, geo.opacity.height + 4f, 2f, 0xFFFFFFFF.toInt())
    }

    private class PickerGeometry(val boxX: Float, val boxY: Float, val boxW: Float, val boxH: Float,
                                 val sb: Rect, val hue: Rect, val rainbow: Rect, val opacity: Rect)

    private fun pickerGeometry(x: Float, y: Float): PickerGeometry {
        val boxX = x + OpaiLayout.TEXT_PAD - 2f
        val boxW = OpaiLayout.COL_W - OpaiLayout.TEXT_PAD * 2 + 4f
        val boxY = y + 4f
        val boxH = OpaiLayout.COLOR_PICKER_H - 8f
        val pad = 6f
        val innerX = boxX + pad
        val innerW = boxW - pad * 2
        val sb = Rect(innerX, boxY + pad, innerW, 36f)
        val hue = Rect(innerX, sb.y + sb.height + 5f, innerW, 8f)
        val rainbow = Rect(innerX, hue.y + hue.height + 4f, innerW, 16f)
        val opacity = Rect(innerX, rainbow.y + rainbow.height + 4f, innerW, 8f)
        return PickerGeometry(boxX, boxY, boxW, boxH, sb, hue, rainbow, opacity)
    }

    private fun drawSearch(surface: OpaiSurface, palette: OpaiPalette) {
        val rect = searchRect()
        searchField.draw(surface, palette, rect.x, rect.y, rect.width, rect.height, "Search...")
    }

    private fun searchRect(): Rect {
        val w = min(200f, max(140f, width - 40f))
        return Rect(20f, height - 40f, w, 20f)
    }

    // ---------- 输入 ----------

    private fun setMouse(x: Double, y: Double) {
        mouseX = width / 2.0 + (x - width / 2.0) / menuScale
        mouseY = transformCenterY + (y - transformCenterY) / menuScale
    }

    private fun columnAt(): Column? {
        for (i in columns.indices.reversed()) {
            val column = columns[i]
            if (dropdownAt(column) != null ||
                Rect(column.x, column.y, OpaiLayout.COL_W, OpaiLayout.HEADER_H + column.bodyH).contains(mouseX, mouseY)) {
                return column
            }
        }
        return null
    }

    @Throws(IOException::class)
    override fun mouseClicked(rawX: Int, rawY: Int, mouseButton: Int) {
        if (closing) return
        setMouse(rawX.toDouble(), rawY.toDouble())
        shiftDown = GuiScreen.isShiftKeyDown()
        blurActiveField()
        val searchRect = searchRect()
        if (searchField.focusIfContains(mouseX, mouseY, searchRect.x, searchRect.y, searchRect.width, searchRect.height)) {
            activeField = searchField
            refreshLayouts()
            return
        }
        val column = columnAt()
        if (column == null) {
            closeDropdowns()
            refreshLayouts()
            return
        }
        columns.remove(column)
        columns.add(column)
        val overlay = dropdownAt(column)
        if (overlay != null) {
            handleDropdownClick(overlay, mouseButton)
            return
        }
        if (mouseY < column.y + OpaiLayout.HEADER_H) {
            handleHeaderClick(column, mouseButton)
            return
        }
        if (!column.expanded) return
        val localY = (mouseY - column.y - OpaiLayout.HEADER_H).toFloat()
        if (beginScrollbarDrag(column, mouseButton, localY)) return
        val entry = column.content.hit(localY.toDouble(), column.scroll, column.bodyH) ?: return
        val row = entry.target.row
        val setting = entry.target.value
        val action = actionFor(entry.kind, mouseButton)
        if (entry.kind == Kind.MODULE) handleModuleClick(column, entry, row, action)
        else if (action != Action.NONE && row.expanded && setting != null && setting.shouldRender()) {
            handleSettingClick(column, entry, row, setting, action)
        }
        refreshLayouts()
    }

    private fun actionFor(kind: Kind, button: Int): Action = when (kind) {
        Kind.MODULE -> when (button) {
            0 -> Action.TOGGLE
            1 -> Action.EXPAND
            2 -> Action.BIND
            else -> Action.NONE
        }
        Kind.MODE, Kind.COLOR, Kind.TEXT -> if (button == 0) Action.EXPAND else Action.NONE
        Kind.BOOLEAN -> if (button == 0) Action.TOGGLE else Action.NONE
        Kind.NUMBER -> if (button == 0) Action.SLIDE else Action.NONE
        Kind.COLOR_PICKER -> if (button == 0) Action.SLIDE else Action.NONE
        Kind.OPTION -> if (button == 0) Action.SELECT else Action.NONE
        Kind.FONT -> when (button) {
            0 -> Action.TOGGLE
            1 -> Action.SELECT
            else -> Action.NONE
        }
        else -> Action.NONE
    }

    private fun handleHeaderClick(column: Column, button: Int) {
        val action = if (button == 0) Action.DRAG else if (button == 1) Action.EXPAND else Action.NONE
        if (action == Action.EXPAND) {
            clearDrag()
            column.expanded = !column.expanded
            for (row in column.rows) {
                if (bindingRow === row) bindingRow = null
            }
            refreshLayouts()
        } else if (action == Action.DRAG) {
            clearDrag()
            draggedColumn = column
            dragX = (mouseX - column.x).toFloat()
            dragY = (mouseY - column.y).toFloat()
        }
    }

    private fun beginScrollbarDrag(column: Column, button: Int, localY: Float): Boolean {
        if (button != 0 || column.maxScroll <= 0f ||
            !Rect(column.x + OpaiLayout.COL_W - 6f, column.y + OpaiLayout.HEADER_H, 6f, column.bodyH - OpaiLayout.FOOTER_H).contains(mouseX, mouseY)) {
            return false
        }
        clearDrag()
        draggedScrollbar = column
        val bar = OpaiContentLayout.scrollbar(column.bodyH, column.content.height(), column.scroll)
        scrollbarGrab = if (localY >= bar.top && localY < bar.top + bar.height) localY - bar.top else bar.height / 2f
        updateScrollbar()
        return true
    }

    private fun handleModuleClick(column: Column, entry: Entry<Target>, row: Row, action: Action) {
        when (action) {
            Action.TOGGLE -> {
                val now = OpaiMotion.now()
                row.feedback.click((mouseX - column.x).toFloat(),
                    (mouseY - column.y - OpaiLayout.HEADER_H - entry.top + column.scroll).toFloat(),
                    OpaiLayout.COL_W, OpaiLayout.ROW_H, now)
                row.enabled.approach(if (row.module.state) 1f else 0f, now)
                row.module.toggle()
                row.enabled.approach(if (row.module.state) 1f else 0f, now)
            }
            Action.EXPAND -> {
                clearDrag()
                row.expanded = !row.expanded
                if (bindingRow === row && !row.expanded) bindingRow = null
                refreshLayouts()
                if (row.expanded) {
                    val visible = column.targetBodyH - OpaiLayout.FOOTER_H
                    val reveal = min(column.content.height(), entry.top + OpaiLayout.ROW_H + OpaiLayout.MODE_H)
                    column.scrollTarget = max(column.scroll, min(entry.top, reveal - visible)).coerceIn(0f, column.maxScroll)
                }
            }
            Action.BIND -> bindingRow = row
            else -> { }
        }
    }

    private fun handleSettingClick(column: Column, entry: Entry<Target>, row: Row, setting: Value<*>, action: Action) {
        val y = column.y + OpaiLayout.HEADER_H + entry.top - column.scroll
        if (entry.kind == Kind.COLOR_PICKER) {
            if (action == Action.SLIDE) pickerClick(column, setting as ColorValue, y)
            return
        }
        when (action) {
            Action.TOGGLE -> when (setting) {
                is BoolValue -> {
                    val now = OpaiMotion.now()
                    val toggle = toggleAnims.computeIfAbsent(setting) { OpaiMotion(if (setting.get()) 1f else 0f, OpaiGUI.getAnimRate(30.0)) }
                    toggle.approach(if (setting.get()) 1f else 0f, now)
                    feedbackFor(setting).click((mouseX - column.x - OpaiLayout.COL_W + OpaiLayout.TEXT_PAD + 26f).toFloat(),
                        (mouseY - y - (OpaiLayout.BOOLEAN_H - 16f) / 2f).toFloat(), 26f, 16f, now)
                    setting.set(!setting.get())
                    toggle.approach(if (setting.get()) 1f else 0f, now)
                }
                is FontValue -> setting.next()
                else -> { }
            }
            Action.SELECT -> if (setting is FontValue) setting.previous()
            Action.SLIDE -> {
                if (setting is IntRangeValue || setting is FloatRangeValue) {
                    beginRangeDrag(column, setting, y)
                } else if (OpaiContentLayout.sliderHit(column.x, y, OpaiLayout.COL_W).contains(mouseX, mouseY)) {
                    feedbackFor(setting).click((mouseX - column.x - OpaiLayout.TEXT_PAD).toFloat(), 4f,
                        OpaiLayout.COL_W - OpaiLayout.TEXT_PAD * 2, 8f, OpaiMotion.now())
                    clearDrag()
                    draggingSlider = setting
                    sliderRow = row
                    sliderColumn = column
                    column.scrollTarget = column.scroll
                    updateSlider()
                }
            }
            Action.EXPAND -> when (setting) {
                is ListValue -> {
                    val field = OpaiContentLayout.field(column.x, y, OpaiLayout.COL_W)
                    if (field.contains(mouseX, mouseY)) {
                        feedbackFor(setting).click((mouseX - field.x).toFloat(), (mouseY - field.y).toFloat(),
                            field.width, field.height, OpaiMotion.now())
                        val wasOpen = row.openMode === setting
                        closeDropdowns()
                        row.openMode = if (wasOpen) null else setting
                    }
                }
                is ColorValue -> {
                    if (Rect(column.x, y, OpaiLayout.COL_W, OpaiLayout.BOOLEAN_H).contains(mouseX, mouseY)) {
                        feedbackFor(setting).click((mouseX - column.x).toFloat(), (mouseY - y).toFloat(),
                            OpaiLayout.COL_W, OpaiLayout.BOOLEAN_H, OpaiMotion.now())
                        val wasOpen = row.openMode === setting
                        closeDropdowns()
                        row.openMode = if (wasOpen) null else setting
                    }
                }
                is TextValue -> {
                    val field = OpaiContentLayout.field(column.x, y, OpaiLayout.COL_W)
                    if (field.contains(mouseX, mouseY)) {
                        val field0 = textFields.computeIfAbsent(setting) { OpaiTextField(256) }
                        if (field0.text != setting.get()) field0.text = setting.get()
                        focusField(field0, field)
                    }
                }
                else -> { }
            }
            else -> { }
        }
    }

    private fun beginRangeDrag(column: Column, value: Value<*>, y: Float) {
        if (!OpaiContentLayout.sliderHit(column.x, y, OpaiLayout.COL_W).contains(mouseX, mouseY)) return
        feedbackFor(value).click((mouseX - column.x - OpaiLayout.TEXT_PAD).toFloat(), 4f,
            OpaiLayout.COL_W - OpaiLayout.TEXT_PAD * 2, 8f, OpaiMotion.now())
        clearDrag()
        sliderColumn = column
        val trackX = column.x + OpaiLayout.TEXT_PAD
        val trackW = OpaiLayout.COL_W - OpaiLayout.TEXT_PAD * 2
        val firstX = trackX + trackW * rangeFraction(value, RangeSlider.LEFT)
        val lastX = trackX + trackW * rangeFraction(value, RangeSlider.RIGHT)
        val left = abs(mouseX - firstX) <= abs(mouseX - lastX)
        val side = if (left) RangeSlider.LEFT else RangeSlider.RIGHT
        draggingRange = value to side
        updateRangeSlider(value, side)
    }

    private fun pickerClick(column: Column, color: ColorValue, y: Float) {
        val geo = pickerGeometry(column.x, y)
        when {
            geo.sb.contains(mouseX, mouseY) -> {
                draggingColor = color to ColorZone.SB
                applyColorDrag(color, ColorZone.SB)
            }
            geo.hue.contains(mouseX, mouseY) -> {
                draggingColor = color to ColorZone.HUE
                applyColorDrag(color, ColorZone.HUE)
            }
            geo.opacity.contains(mouseX, mouseY) -> {
                draggingColor = color to ColorZone.OPACITY
                applyColorDrag(color, ColorZone.OPACITY)
            }
            geo.rainbow.contains(mouseX, mouseY) -> color.rainbow = !color.rainbow
        }
    }

    private fun handleDropdownClick(overlay: Dropdown, button: Int) {
        if (button == 0 && overlay.row.openMode === overlay.mode) {
            val option = ((mouseY - overlay.bounds.y + overlay.scroll) / OpaiLayout.OPTION_H).toInt()
            if (option in overlay.mode.values.indices) {
                overlay.mode.set(overlay.mode.values[option])
                overlay.row.optionFeedback.computeIfAbsent(OptionKey(overlay.mode, option)) { OpaiFeedback() }
                    .click((mouseX - overlay.bounds.x).toFloat(),
                        (mouseY - overlay.bounds.y + overlay.scroll - option * OpaiLayout.OPTION_H).toFloat(),
                        overlay.bounds.width, OpaiLayout.OPTION_H, OpaiMotion.now())
                overlay.row.openMode = null
                refreshLayouts()
            }
        } else if (button == 1) {
            closeDropdowns()
            refreshLayouts()
        }
    }

    private fun updateSlider() {
        val value = draggingSlider ?: return
        val column = sliderColumn ?: return
        val fraction = OpaiContentLayout.sliderFraction(mouseX, column.x, OpaiLayout.COL_W)
        when (value) {
            is IntValue -> value.set(value.minimum + (fraction * (value.maximum - value.minimum)).roundToInt())
            is BlockValue -> value.set(value.minimum + (fraction * (value.maximum - value.minimum)).roundToInt())
            is FloatValue -> value.set(value.minimum + fraction * (value.maximum - value.minimum))
            else -> { }
        }
    }

    private fun updateRangeSlider(value: Value<*>, side: RangeSlider) {
        val column = sliderColumn ?: sliderColumnFor(value) ?: return
        val fraction = OpaiContentLayout.sliderFraction(mouseX, column.x, OpaiLayout.COL_W)
        when (value) {
            is IntRangeValue -> {
                val raw = value.minimum + (fraction * (value.maximum - value.minimum)).roundToInt()
                if (side == RangeSlider.LEFT) value.setFirst(raw.coerceAtMost(value.get().last))
                else value.setLast(raw.coerceAtLeast(value.get().first))
            }
            is FloatRangeValue -> {
                val raw = value.minimum + fraction * (value.maximum - value.minimum)
                if (side == RangeSlider.LEFT) value.setFirst(raw.toFloat().coerceAtMost(value.get().endInclusive))
                else value.setLast(raw.toFloat().coerceAtLeast(value.get().start))
            }
            else -> { }
        }
    }

    /** 范围拖拽未经过 sliderColumn（未设置）时，按当前布局反查所属列。 */
    private fun sliderColumnFor(value: Value<*>): Column? {
        for (column in columns) {
            for (entry in column.content.entries()) {
                if (entry.target.value === value) return column
            }
        }
        return null
    }

    private fun updateScrollbar() {
        val column = draggedScrollbar ?: return
        val thumb = (mouseY - column.y - OpaiLayout.HEADER_H - scrollbarGrab).toFloat()
        val next = OpaiContentLayout.scrollFromThumb(thumb, column.bodyH, column.content.height())
        column.scroll = next
        column.scrollTarget = next
    }

    private fun clearDrag() {
        draggedColumn = null
        draggedScrollbar = null
        draggingSlider = null
        sliderRow = null
        sliderColumn = null
        draggingRange = null
        draggingColor = null
    }

    private fun closeDropdowns(): Boolean {
        var closed = false
        for (column in columns) {
            for (row in column.rows) {
                closed = closed || row.openMode != null
                row.openMode = null
            }
        }
        return closed
    }

    override fun mouseReleased(rawX: Int, rawY: Int, state: Int) {
        val wasDraggingColumn = draggedColumn != null
        clearDrag()
        if (wasDraggingColumn) saveLayout()
    }

    override fun mouseClickMove(rawX: Int, rawY: Int, clickedMouseButton: Int, timeSinceLastClick: Long) {
        if (closing) return
        setMouse(rawX.toDouble(), rawY.toDouble())
        val dragged = draggedColumn
        if (dragged != null) {
            dragged.x = (mouseX - dragX).toFloat().coerceIn(4f, max(4f, width - OpaiLayout.COL_W - 4f))
            dragged.y = (mouseY - dragY).toFloat().coerceIn(4f, max(4f, height - OpaiLayout.HEADER_H - dragged.bodyH - 8f))
            refreshLayouts()
            return
        }
        if (draggedScrollbar != null) {
            updateScrollbar()
            return
        }
        val slider = draggingSlider
        if (slider != null) {
            val owner = sliderRow
            if (owner != null && owner.expanded && slider.shouldRender()) {
                updateSlider()
                refreshLayouts()
            } else {
                clearDrag()
            }
            return
        }
        val range = draggingRange
        if (range != null) {
            updateRangeSlider(range.first, range.second)
            refreshLayouts()
            return
        }
        val color = draggingColor
        if (color != null) {
            applyColorDrag(color.first, color.second)
            refreshLayouts()
        }
    }

    @Throws(IOException::class)
    override fun handleMouseInput() {
        super.handleMouseInput()
        if (closing) return
        val wheel = Mouse.getEventDWheel()
        if (wheel == 0) return
        val direction = if (wheel > 0) 1f else -1f
        val column = columnAt()
        val overlay = column?.let { dropdownAt(it) }
        if (overlay != null) {
            val choices = overlay.mode
            overlay.row.modeScroll[choices] = ((overlay.row.modeScroll[choices] ?: 0f) - direction * OpaiLayout.OPTION_H * 2f)
                .coerceIn(0f, choices.values.size * OpaiLayout.OPTION_H - overlay.fullHeight)
            column?.let { it.dropdowns = layoutDropdowns(it) }
            return
        }
        if (shiftDown || column == null) {
            if (maxHorizontalScroll <= 0f) return
            val next = (horizontalScroll - direction * OpaiLayout.ROW_H * 2f).coerceIn(0f, maxHorizontalScroll)
            val delta = next - horizontalScroll
            for (item in columns) item.x -= delta
            horizontalScroll = next
            return
        }
        column.scrollTarget = (column.scrollTarget - direction * OpaiLayout.ROW_H * 2f).coerceIn(0f, column.maxScroll)
    }

    @Throws(IOException::class)
    override fun keyTyped(typedChar: Char, keyCode: Int) {
        if (closing) return
        shiftDown = keyCode == Keyboard.KEY_LSHIFT || keyCode == Keyboard.KEY_RSHIFT || GuiScreen.isShiftKeyDown()
        val binding = bindingRow
        if (binding != null) {
            binding.module.keyBind = if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_BACK) 0 else keyCode
            bindingRow = null
            return
        }
        val field = activeField
        if (field != null && field.focused) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                blurActiveField()
                return
            }
            if (field.keyPressed(keyCode)) {
                syncFieldValues(field)
                return
            }
            if (typedChar.code != 0) {
                field.charTyped(typedChar)
                syncFieldValues(field)
                return
            }
            return
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (closeDropdowns()) {
                refreshLayouts()
            } else {
                closeGui()
            }
            return
        }
        super.keyTyped(typedChar, keyCode)
    }

    private fun syncFieldValues(field: OpaiTextField) {
        if (field === searchField) return
        for ((value, field0) in textFields) {
            if (field0 === field) {
                value.set(field.text)
                return
            }
        }
    }

    private fun focusField(field: OpaiTextField, rect: Rect) {
        if (field.focusIfContains(mouseX, mouseY, rect.x, rect.y, rect.width, rect.height)) {
            activeField = field
        }
    }

    private fun blurActiveField() {
        val field = activeField
        if (field != null) {
            field.blur()
            activeField = null
        }
    }

    /** ESC/OpaiGUI 绑定键触发的动画收场（与 Samsara onClose 一致）。 */
    private fun closeGui() {
        if (closing) return
        clearDrag()
        bindingRow = null
        blurActiveField()
        closing = true
        closeStarted = OpaiMotion.now()
        closingAlpha = menuOpacity
        closingScale = menuScale
    }

    override fun onGuiClosed() {
        super.onGuiClosed()
        blurActiveField()
        saveLayout()
        detached = true
        bindingRow = null
        clearDrag()
        IMEFocusHelper.deactivate()
    }

    private fun saveLayout() {
        OpaiLayoutState.saveColumns(columns.associate { it.id to (it.x to it.y) })
    }

    override fun doesGuiPauseGame(): Boolean = false

    // ---------- 工具 ----------

    private fun feedbackFor(key: Any): OpaiFeedback = controlFeedback.computeIfAbsent(key) { OpaiFeedback() }

    private fun numericMinimum(value: Value<*>): Float = when (value) {
        is IntValue -> value.minimum.toFloat()
        is BlockValue -> value.minimum.toFloat()
        is FloatValue -> value.minimum
        else -> 0f
    }

    private fun numericMaximum(value: Value<*>): Float = when (value) {
        is IntValue -> value.maximum.toFloat()
        is BlockValue -> value.maximum.toFloat()
        is FloatValue -> value.maximum
        else -> 1f
    }

    private fun numericCurrent(value: Value<*>): Float = when (value) {
        is IntValue -> value.get().toFloat()
        is BlockValue -> value.get().toFloat()
        is FloatValue -> value.get()
        else -> 0f
    }

    private fun rangeFraction(value: Value<*>, side: RangeSlider): Float = when (value) {
        is IntRangeValue -> {
            val span = (value.maximum - value.minimum).coerceAtLeast(1)
            val current = if (side == RangeSlider.LEFT) value.get().first else value.get().last
            ((current - value.minimum).toFloat() / span).coerceIn(0f, 1f)
        }
        is FloatRangeValue -> {
            val span = (value.maximum - value.minimum).takeIf { it > 0f } ?: 1f
            val current = if (side == RangeSlider.LEFT) value.get().start else value.get().endInclusive
            ((current - value.minimum) / span).coerceIn(0f, 1f)
        }
        else -> 0f
    }

    private fun formatValue(value: Value<*>): String = when (value) {
        is IntValue -> "${value.get()}${value.suffix ?: ""}"
        is BlockValue -> "${value.get()}${value.suffix ?: ""}"
        is FloatValue -> "${trimFloat(value.get())}${value.suffix ?: ""}"
        is IntRangeValue -> "${value.get().first}..${value.get().last}${value.suffix ?: ""}"
        is FloatRangeValue -> "${trimFloat(value.get().start)}..${trimFloat(value.get().endInclusive)}${value.suffix ?: ""}"
        else -> ""
    }

    private fun trimFloat(v: Float): String {
        val rounded = (v * 100f).roundToInt() / 100f
        return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString()
        else String.format(Locale.US, "%.2f", rounded).trimEnd('0').trimEnd('.')
    }

    private fun applyColorDrag(color: ColorValue, zone: ColorZone) {
        var owner: Column? = null
        var pickerY = 0f
        outer@ for (column in columns) {
            for (entry in column.content.entries()) {
                if (entry.kind == Kind.COLOR_PICKER && entry.target.value === color) {
                    owner = column
                    pickerY = column.y + OpaiLayout.HEADER_H + entry.top - column.scroll
                    break@outer
                }
            }
        }
        val column = owner ?: return
        val geo = pickerGeometry(column.x, pickerY)
        when (zone) {
            ColorZone.SB -> color.colorPickerPos.set(
                ((mouseX - geo.sb.x) / geo.sb.width).toFloat().coerceIn(0f, 1f),
                ((mouseY - geo.sb.y) / geo.sb.height).toFloat().coerceIn(0f, 1f))
            ColorZone.HUE -> color.hueSliderY = ((mouseX - geo.hue.x) / geo.hue.width).toFloat().coerceIn(0f, 1f)
            ColorZone.OPACITY -> color.opacitySliderY = ((mouseY - geo.opacity.y) / geo.opacity.height).toFloat().coerceIn(0f, 1f)
        }
        pushColor(color)
    }

    private fun pushColor(color: ColorValue) {
        val rgb = Color.HSBtoRGB(color.hueSliderY, color.colorPickerPos.x, 1f - color.colorPickerPos.y)
        color.set(Color(rgb, true).withAlpha((color.opacitySliderY * 255f).roundToInt()))
    }

    // ColorValue 不在 ColorValue 里存 picker 交互状态，这里只记录拖拽区域。
    private enum class ColorZone { SB, HUE, OPACITY }

    private enum class Action { NONE, DRAG, EXPAND, TOGGLE, SLIDE, SELECT, BIND }

    private class Target(val row: Row, val value: Value<*>?)

    private data class OptionKey(val value: Value<*>, val index: Int)

    private class Dropdown(val column: Column, val row: Row, val mode: ListValue, val bounds: Rect, val scroll: Float, val fullHeight: Float)

    private class Column(val category: Category) {
        val id = "opai:${category.displayName}"
        val rows = ArrayList<Row>()
        val expand = OpaiMotion(1f, OpaiGUI.getAnimRate(40.0))
        var content: OpaiContentLayout<Target> = OpaiContentLayout()
        var dropdowns: List<Dropdown> = emptyList()
        var x = 0f
        var y = 0f
        var scroll = 0f
        var scrollTarget = 0f
        var maxScroll = 0f
        var bodyH = 0f
        var targetBodyH = 0f
        var expanded = true
    }

    private class Row(val module: Module) {
        var openMode: Value<*>? = null
        val name = OpaiLayout.displayName(module.getName(true))
        val hover = OpaiMotion(0f, OpaiGUI.getAnimRate(30.0))
        val expand = OpaiMotion(0f, OpaiGUI.getAnimRate(40.0))
        val enabled = OpaiMotion(if (module.state) 1f else 0f, OpaiGUI.getAnimRate(30.0))
        val feedback = OpaiFeedback()
        val modes = HashMap<ListValue, OpaiMotion>()
        val modeScroll = HashMap<ListValue, Float>()
        val optionFeedback = HashMap<OptionKey, OpaiFeedback>()
        var expanded = false

        fun matches(query: String): Boolean =
            module.name.contains(query, ignoreCase = true) || module.spacedName.contains(query, ignoreCase = true)
    }

    companion object {
        val INSTANCE = OpaiScreen()
    }
}
