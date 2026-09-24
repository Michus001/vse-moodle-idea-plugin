package cz.vse.moodle.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.UIManager;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.Toolkit;

/**
 * Accepts only printable ASCII (space to {@code ~}) in a text field; anything else, typed or pasted, is dropped
 * with a beep. Submitted files must stay ASCII because VPL jails may compile with {@code US-ASCII}.
 */
final class AsciiDocumentFilter extends DocumentFilter {
    @Override
    public void insertString(FilterBypass fb, int offset, String text, AttributeSet attrs) throws BadLocationException {
        super.insertString(fb, offset, filter(text), attrs);
    }

    @Override
    public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
        super.replace(fb, offset, length, filter(text), attrs);
    }

    private static @Nullable String filter(@Nullable String text) {
        if (text == null) return null;
        String ascii = keepAscii(text);
        if (ascii.length() != text.length() && UIManager.getBoolean("Application.useSystemBeep") != Boolean.FALSE) {
            Toolkit.getDefaultToolkit().beep();
        }
        return ascii;
    }

    static @NotNull String keepAscii(@NotNull String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x20 && c <= 0x7E) out.append(c);
        }
        return out.toString();
    }
}
