package net.airplus.ui.client.clickgui.opai.widget

import net.airplus.ui.client.clickgui.opai.OpaiCompat.IMEFocusHelper
import net.airplus.ui.client.clickgui.opai.OpaiPalette
import net.airplus.ui.client.clickgui.opai.OpaiStyle
import net.airplus.ui.client.clickgui.opai.OpaiSurface
import net.minecraft.client.gui.GuiScreen
import org.lwjgl.input.Keyboard
import java.util.function.Predicate

/**
 * Samsara Opai 风格输入框：field 圆角底 + fieldLine/accent 下划线，
 * 光标为 1px 竖线。仅负责输入逻辑与绘制，宿主负责布局。
 */
class OpaiTextField(val maxLength: Int, val inputFilter: Predicate<String>? = Predicate { true }) {

    var focused = false
    var text = ""
    var cursor = 0

    fun draw(surface: OpaiSurface, palette: OpaiPalette, x: Float, y: Float, width: Float, height: Float, placeholder: String = "") {
        surface.rounded(x, y, width, height, 2f, palette.field)
        surface.rect(x + 1f, y + height - 1f, width - 2f, 1f, if (focused) palette.enabled else palette.fieldLine)
        val showPlaceholder = text.isEmpty() && !focused
        if (showPlaceholder) {
            surface.plainText(placeholder, x + 6f, y + height / 2f, OpaiStyle.ROW_TEXT_SIZE, width - 12f,
                OpaiStyle.multiplyOpacity(palette.text, 0.45f))
        } else {
            surface.plainText(text, x + 6f, y + height / 2f, OpaiStyle.ROW_TEXT_SIZE, width - 12f, palette.text)
        }
        if (focused) {
            val safeCursor = cursor.coerceIn(0, text.length)
            val caretX = x + 6f + surface.plainTextWidth(text.substring(0, safeCursor), OpaiStyle.ROW_TEXT_SIZE)
            surface.rect(caretX, y + height / 2f - 4.5f, 1f, 9f, palette.text)
            IMEFocusHelper.updateCursorPos(caretX, y + height / 2f)
        }
    }

    fun focusIfContains(mouseX: Double, mouseY: Double, x: Float, y: Float, width: Float, height: Float): Boolean {
        if (mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + height) {
            return false
        }
        focused = true
        cursor = text.length
        IMEFocusHelper.activate()
        return true
    }

    fun blur() {
        if (focused) {
            focused = false
            IMEFocusHelper.deactivate()
        }
    }

    fun keyPressed(keyCode: Int): Boolean {
        if (!focused) return false
        if (GuiScreen.isCtrlKeyDown()) {
            return handleControlShortcut(keyCode)
        }
        when (keyCode) {
            Keyboard.KEY_BACK -> {
                if (cursor > 0 && text.isNotEmpty()) {
                    text = text.substring(0, cursor - 1) + text.substring(cursor)
                    cursor--
                }
                return true
            }
            Keyboard.KEY_DELETE -> {
                if (cursor < text.length) {
                    text = text.substring(0, cursor) + text.substring(cursor + 1)
                }
                return true
            }
            Keyboard.KEY_LEFT -> {
                cursor = 0.coerceAtLeast(cursor - 1)
                return true
            }
            Keyboard.KEY_RIGHT -> {
                cursor = text.length.coerceAtMost(cursor + 1)
                return true
            }
            Keyboard.KEY_HOME -> {
                cursor = 0
                return true
            }
            Keyboard.KEY_END -> {
                cursor = text.length
                return true
            }
            else -> return false
        }
    }

    fun charTyped(typedChar: Char): Boolean {
        if (!focused) return false
        insertText(typedChar.toString())
        return true
    }

    private fun insertText(inserted: String?) {
        if (inserted == null || inserted.isEmpty()) return
        val accepted = StringBuilder()
        for (c in inserted.toCharArray()) {
            val candidate = c.toString()
            if (inputFilter?.test(candidate) != false) accepted.append(candidate)
        }
        if (accepted.isEmpty()) return
        val available = maxLength - text.length
        if (available <= 0) return
        val safe = if (accepted.length > available) accepted.substring(0, available) else accepted.toString()
        text = text.substring(0, cursor) + safe + text.substring(cursor)
        cursor += safe.length
    }

    private fun handleControlShortcut(keyCode: Int): Boolean {
        when (keyCode) {
            Keyboard.KEY_A -> {
                cursor = text.length
                return true
            }
            Keyboard.KEY_V -> {
                insertText(GuiScreen.getClipboardString())
                return true
            }
            else -> return false
        }
    }

    fun clear() {
        text = ""
        cursor = 0
    }

}
