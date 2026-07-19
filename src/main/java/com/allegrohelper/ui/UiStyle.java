package com.allegrohelper.ui;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.TitledBorder;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;

/**
 * The window's hand-picked colors and the font-normalizing tree walks.
 *
 * <p>These exist because the look and feel offers no key for them: the tab
 * titles are custom labels, the caret color has to follow the theme by hand,
 * and Swing has no "one font size for everything" switch. They are collected
 * here rather than in {@link Theme} because {@code Theme} decides *which* look
 * and feel is active, while these decide how this application paints on top of
 * whichever one it is — and because {@link SettingsDialog} needs them too, so
 * they should not live inside {@link MainWindow}.
 *
 * <p>Every color is a method, not a constant: they are read after a theme
 * switch, so they must be recomputed rather than captured at class-init.
 */
final class UiStyle {

    /** Accent color (orange, from the logo) for the active tab underline and count badges. */
    static final Color TAB_ACCENT = new Color(0xF2, 0x6B, 0x1F);

    /** One font size for the whole window, so all text reads at a similar (larger) size. */
    static final int UI_FONT_SIZE = 16;

    /** Not instantiable: the class is a namespace for its static helpers. */
    private UiStyle() {
    }

    /** Text caret color — methods, not constants, because they follow the active {@link Theme}. */
    static Color caretColor() {
        return Theme.isDark() ? Color.WHITE : Color.BLACK;
    }

    /** Tab title colors: the selected tab is bright with an accent underline, others are dimmed. */
    static Color tabSelectedFg() {
        return Theme.isDark() ? Color.WHITE : new Color(0x1A, 0x1A, 0x1A);
    }

    /** @see #tabSelectedFg() */
    static Color tabUnselectedFg() {
        return Theme.isDark() ? new Color(0x9E, 0x9E, 0x9E) : new Color(0x6E, 0x6E, 0x6E);
    }

    /**
     * Tab title color for unsaved edits — amber, to read as a warning next to
     * the plain titles without shouting like red. Two shades, because one hue
     * cannot span both backgrounds: measured against the themes' actual panel
     * colors, the bright amber scores 7.8:1 on System and 5.9:1 on Dark, and
     * the deep one 4.8:1 on Light, so both clear WCAG AA for normal text.
     * Inverting them would land at 1.3:1 and 2.3:1 — invisible.
     */
    static Color tabDirtyFg() {
        return Theme.isDark() ? new Color(0xFF, 0xB3, 0x00) : new Color(0x8A, 0x4B, 0x00);
    }

    /**
     * Hyperlink color, picked per theme for contrast: a light blue on the dark
     * background, a deep blue on the light one. The look and feel offers no key
     * for this, HTML's default {@code <a>} blue is unreadable on the dark theme,
     * and a single fixed color would wash out against one of the two backgrounds.
     * Both clear WCAG AA against their background (≈6:1 and ≈7:1).
     */
    static Color linkColor() {
        return Theme.isDark() ? new Color(0x8C, 0xC8, 0xFF) : new Color(0x0A, 0x3D, 0x91);
    }

    /** Re-applies the theme-dependent caret color to every text component under {@code c}. */
    static void recolorCarets(Component c) {
        if (c instanceof JTextComponent text) {
            text.setCaretColor(caretColor());
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                recolorCarets(child);
            }
        }
    }

    /** Caps a section's height at its preferred size so it fills width but not extra vertical space. */
    static void capHeight(JPanel panel) {
        Dimension pref = panel.getPreferredSize();
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    /** Sets every component (and titled-border header) in the tree to {@link #UI_FONT_SIZE}, keeping family/style. */
    static void standardizeFonts(Component c) {
        Font font = c.getFont();
        if (font != null) {
            c.setFont(font.deriveFont((float) UI_FONT_SIZE));
        }
        if (c instanceof JComponent jc) {
            resizeTitledBorderFont(jc.getBorder());
        }
        // The table header and in-cell editors aren't in the tree yet at this point
        // (the header is attached to the scroll pane on addNotify), so size them explicitly.
        if (c instanceof JTable table) {
            if (table.getTableHeader() != null) {
                Font hf = table.getTableHeader().getFont();
                if (hf != null) {
                    table.getTableHeader().setFont(hf.deriveFont((float) UI_FONT_SIZE));
                }
            }
            resizeCellEditorFont(table.getDefaultEditor(Object.class));
            for (int col = 0; col < table.getColumnCount(); col++) {
                resizeCellEditorFont(table.getColumnModel().getColumn(col).getCellEditor());
            }
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                standardizeFonts(child);
            }
        }
    }

    /** Resizes a titled border's title font, descending into compound borders. */
    private static void resizeTitledBorderFont(Border border) {
        if (border instanceof TitledBorder tb) {
            Font f = tb.getTitleFont();
            if (f == null) {
                f = UIManager.getFont("TitledBorder.font");
            }
            if (f == null) {
                f = new Font(Font.DIALOG, Font.BOLD, UI_FONT_SIZE);
            }
            tb.setTitleFont(f.deriveFont((float) UI_FONT_SIZE));
        } else if (border instanceof CompoundBorder cb) {
            resizeTitledBorderFont(cb.getOutsideBorder());
            resizeTitledBorderFont(cb.getInsideBorder());
        }
    }

    /**
     * Resizes the font of a grid cell editor's component — including the text
     * field inside a combo editor. Editors are not part of the component tree,
     * so the font walk cannot reach them on its own.
     */
    private static void resizeCellEditorFont(javax.swing.table.TableCellEditor editor) {
        if (editor instanceof javax.swing.DefaultCellEditor dce) {
            Component comp = dce.getComponent();
            if (comp != null && comp.getFont() != null) {
                comp.setFont(comp.getFont().deriveFont((float) UI_FONT_SIZE));
            }
            if (comp instanceof JComboBox<?> combo
                    && combo.getEditor().getEditorComponent() instanceof JTextField field
                    && field.getFont() != null) {
                field.setFont(field.getFont().deriveFont((float) UI_FONT_SIZE));
            }
        }
    }

    /** An empty panel with a titled border — the shell every left-hand section is built in. */
    static JPanel titled(String title) {
        JPanel panel = new JPanel();
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(6, 8, 0, 8),
                BorderFactory.createTitledBorder(title)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    /** A bold heading inside the Allegro form tab. */
    static JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    /**
     * A left-aligned row of controls whose height is capped, so it cannot stretch
     * inside the form tab's vertical box layout.
     */
    static JPanel flowRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height + 40));
        return row;
    }
}
