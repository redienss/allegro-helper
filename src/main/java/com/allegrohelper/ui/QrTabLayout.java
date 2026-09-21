package com.allegrohelper.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * The QR Code tab: the photo preview at the top, only as tall as its width
 * leaves room for once the stepper and the form below have taken their
 * preferred height — the same idea as {@link PreviewTabLayout}, simplified to
 * one photo instead of a before/after pair, so there is no {@link
 * PreviewRowLayout} to delegate the image's height to.
 */
final class QrTabLayout implements LayoutManager {

    private final int gap;

    QrTabLayout(int gap) {
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
        return new Dimension(0, 0); // a tab takes whatever the tabbed pane gives it
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        return new Dimension(0, 0);
    }

    @Override
    public void layoutContainer(Container parent) {
        if (parent.getComponentCount() < 3) {
            return;
        }
        Component image = parent.getComponent(0);
        Component stepper = parent.getComponent(1);
        Component form = parent.getComponent(2);

        Insets insets = parent.getInsets();
        int width = parent.getWidth() - insets.left - insets.right;
        int available = parent.getHeight() - insets.top - insets.bottom;
        if (width <= 0 || available <= 0) {
            return;
        }

        // The stepper and the form claim their height first, so a short tab
        // shrinks the photo rather than pushing the form out of sight.
        int stepperHeight = stepper.getPreferredSize().height;
        int formHeight = form.getPreferredSize().height;
        int room = Math.max(0, available - stepperHeight - formHeight - 2 * gap);
        int imageHeight = image instanceof ImagePanel panel ? Math.min(panel.heightFor(width), room) : room;

        int y = insets.top;
        image.setBounds(insets.left, y, width, imageHeight);
        y += imageHeight + gap;
        stepper.setBounds(insets.left, y, width, stepperHeight);
        y += stepperHeight + gap;
        form.setBounds(insets.left, y, width, formHeight);
    }
}
