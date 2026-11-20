package net.airplus.music.bm.ui;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;

/**
 * One line of editable text for the glass screens: caret, selection, word jumps and the
 * clipboard, like a vanilla text field but without drawing (the screen draws it).
 * Characters from an input method arrive as key 0 and are typed like any other.
 */
public final class TextEdit {
    private final StringBuilder text = new StringBuilder();
    private final int max;
    private int caret, anchor;
    /** Horizontal scroll in units, kept between frames by the drawing code. */
    float scroll;

    public TextEdit(int max) {
        this.max = max;
    }

    public int length() {
        return text.length();
    }

    public boolean isEmpty() {
        return text.length() == 0;
    }

    @Override
    public String toString() {
        return text.toString();
    }

    public int caret() {
        return caret;
    }

    public int selectionStart() {
        return Math.min(caret, anchor);
    }

    public int selectionEnd() {
        return Math.max(caret, anchor);
    }

    public boolean hasSelection() {
        return caret != anchor;
    }

    /** Replace everything; the caret goes to the end. */
    public void set(String value) {
        text.setLength(0);
        caret = anchor = 0;
        scroll = 0;
        if (value != null) insert(value);
    }

    public void clear() {
        set("");
    }

    /** Select everything (typing then replaces it). */
    public void selectAll() {
        anchor = 0;
        caret = text.length();
    }

    /** Put the caret at the end, without a selection. */
    public void caretToEnd() {
        caret = anchor = text.length();
    }

    /** Type {@code value} at the caret, replacing the selection; disallowed characters are dropped. */
    public void insert(String value) {
        deleteSelection();
        for (int i = 0; i < value.length() && text.length() < max; i++) {
            char c = value.charAt(i);
            if (!ChatAllowedCharacters.isAllowedCharacter(c) || Character.isSurrogate(c)) continue;
            text.insert(caret++, c);
        }
        anchor = caret;
    }

    /**
     * Handle a key press. Returns true when it was an editing key (typed, deleted, moved,
     * clipboard), false when the screen should treat it as something else.
     */
    public boolean key(char typed, int key) {
        boolean shift = GuiScreen.isShiftKeyDown(), ctrl = GuiScreen.isCtrlKeyDown();
        if (GuiScreen.isKeyComboCtrlA(key)) {
            selectAll();
            return true;
        }
        if (GuiScreen.isKeyComboCtrlC(key) || GuiScreen.isKeyComboCtrlX(key)) {
            if (hasSelection()) {
                GuiScreen.setClipboardString(text.substring(selectionStart(), selectionEnd()));
                if (GuiScreen.isKeyComboCtrlX(key)) deleteSelection();
            }
            return true;
        }
        if (GuiScreen.isKeyComboCtrlV(key)) {
            insert(GuiScreen.getClipboardString());
            return true;
        }
        switch (key) {
            case Keyboard.KEY_BACK:
                if (!deleteSelection() && caret > 0) delete(ctrl ? wordLeft() : caret - 1, caret);
                return true;
            case Keyboard.KEY_DELETE:
                if (!deleteSelection() && caret < text.length()) delete(caret, ctrl ? wordRight() : caret + 1);
                return true;
            case Keyboard.KEY_LEFT:
                move(hasSelection() && !shift ? selectionStart() : ctrl ? wordLeft() : caret - 1, shift);
                return true;
            case Keyboard.KEY_RIGHT:
                move(hasSelection() && !shift ? selectionEnd() : ctrl ? wordRight() : caret + 1, shift);
                return true;
            case Keyboard.KEY_HOME:
                move(0, shift);
                return true;
            case Keyboard.KEY_END:
                move(text.length(), shift);
                return true;
            default:
                break;
        }
        // No Ctrl check: AltGr is Ctrl+Alt, and Ctrl+letter arrives as a control character anyway.
        if (ChatAllowedCharacters.isAllowedCharacter(typed) && !Character.isSurrogate(typed)) {
            insert(String.valueOf(typed));
            return true;
        }
        return false;
    }

    private void move(int to, boolean select) {
        caret = Math.max(0, Math.min(to, text.length()));
        if (!select) anchor = caret;
    }

    private boolean deleteSelection() {
        if (!hasSelection()) return false;
        delete(selectionStart(), selectionEnd());
        return true;
    }

    private void delete(int from, int to) {
        from = Math.max(0, from);
        to = Math.min(text.length(), to);
        if (to <= from) return;
        text.delete(from, to);
        caret = anchor = from;
    }

    private int wordLeft() {
        int i = caret;
        while (i > 0 && text.charAt(i - 1) == ' ') i--;
        while (i > 0 && text.charAt(i - 1) != ' ') i--;
        return i;
    }

    private int wordRight() {
        int i = caret, n = text.length();
        while (i < n && text.charAt(i) != ' ') i++;
        while (i < n && text.charAt(i) == ' ') i++;
        return i;
    }
}
