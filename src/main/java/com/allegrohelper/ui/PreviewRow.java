package com.allegrohelper.ui;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.LayoutManager;

/**
 * The rows of the Retouch Preview's control column.
 *
 * <p>Shared between {@link StrengthDial#row} and the tab builder in
 * {@link MainWindow}, which is why they live in neither: a dial builds its own
 * row, and the tab builds the rows around it, and both need the same shape.
 */
final class PreviewRow {

    /** Vertical padding inside a preview control row. */
    static final int PREVIEW_ROW_GAP = 8;

    /** Not instantiable: the class is a namespace for its static helpers. */
    private PreviewRow() {
    }

    /** One row of the preview's control column: its components, left-aligned. */
    static JPanel leftRow(JComponent... components) {
        JPanel row = previewRow(new FlowLayout(FlowLayout.LEFT, 6, PREVIEW_ROW_GAP));
        for (JComponent c : components) {
            row.add(c);
        }
        return row;
    }

    /**
     * A row of the preview's control column: as wide as the column, only as tall
     * as its contents. Without the height cap {@link BoxLayout} would stretch the
     * rows to their maximum and spread them down the column instead of stacking
     * them — and the cap is computed on demand rather than frozen at build time,
     * so a look-and-feel switch (which can change how tall a slider wants to be)
     * cannot leave the row too short and clip what it holds.
     */
    static JPanel previewRow(LayoutManager layout) {
        JPanel row = new JPanel(layout) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }
}
