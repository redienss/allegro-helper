package com.allegrohelper.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * Lays the two Retouch Preview halves side by side at equal width — before and
 * after must be compared at the same scale — but only as tall as their images
 * need, pinned to the top of the tab. A {@code GridLayout} would stretch the
 * titled borders to the tab's full height and frame a band of empty space above
 * and below each photo.
 *
 * <p>The two images always share a shape (auto-crop expands its box back to the
 * source aspect ratio), so the taller requirement decides the row and neither
 * half is letterboxed in practice. A panel with no image to show keeps the
 * shape of the last one it had, so the row does not resize while a render is
 * in flight — see {@link ImagePanel#aspect}.
 */
final class PreviewRowLayout implements LayoutManager {

    private final int gap;

    PreviewRowLayout(int gap) {
        this.gap = gap;
    }

    @Override
    public void addLayoutComponent(String name, Component comp) {
    }

    @Override
    public void removeLayoutComponent(Component comp) {
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        return new Dimension(0, 0); // the row takes whatever width it is given
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        return new Dimension(0, 0);
    }

    @Override
    public void layoutContainer(Container parent) {
        Insets insets = parent.getInsets();
        int width = parent.getWidth() - insets.left - insets.right;
        int available = parent.getHeight() - insets.top - insets.bottom;
        int half = halfWidth(width);
        if (half <= 0 || available <= 0) {
            return;
        }
        int height = Math.min(neededHeight(parent, width), available);

        int x = insets.left;
        for (Component c : parent.getComponents()) {
            c.setBounds(x, insets.top, half, height);
            x += half + gap;
        }
    }

    /**
     * The height the row wants when laid out {@code width} wide: the taller of
     * the two halves at that width. {@link PreviewTabLayout} asks so it can put
     * the checkbox column directly beneath the photos instead of at the bottom
     * of the tab.
     */
    int neededHeight(Container parent, int width) {
        int half = halfWidth(width);
        if (half <= 0) {
            return 0;
        }
        int height = 0;
        for (Component c : parent.getComponents()) {
            if (c instanceof ImagePanel panel) {
                height = Math.max(height, panel.heightFor(half));
            }
        }
        return height;
    }

    int halfWidth(int width) {
        return (width - gap) / 2;
    }
}
