package com.allegrohelper.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * The Retouch Preview tab: the photo row at the top, only as tall as the photos
 * need; each photo's histogram beneath it; the photo stepper and the clipping
 * toggle under the left and right halves; the step checkboxes below that.
 * Whatever height is left over stays empty at the bottom.
 *
 * <p>Neither {@code BorderLayout} nor {@code BoxLayout} can do this, because the
 * photo row's height depends on the width it is given (the halves keep their
 * images' aspect ratio) and a Swing preferred size cannot express that. So the
 * width is settled first, and {@link PreviewRowLayout#neededHeight} is asked
 * afterwards. The stepper and the checkboxes get their preferred height first,
 * so a short tab shrinks the photos rather than pushing the controls out of
 * sight.
 */
final class PreviewTabLayout implements LayoutManager {

    private final int gap;

    PreviewTabLayout(int gap) {
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
        if (parent.getComponentCount() < 5) {
            return;
        }
        Container images = (Container) parent.getComponent(0);
        Component histograms = parent.getComponent(1);
        Component stepper = parent.getComponent(2);
        Component clipping = parent.getComponent(3);
        Component boxes = parent.getComponent(4);

        Insets insets = parent.getInsets();
        int width = parent.getWidth() - insets.left - insets.right;
        int available = parent.getHeight() - insets.top - insets.bottom;
        if (width <= 0 || available <= 0) {
            return;
        }

        // The controls and the histograms claim their height first; the photos
        // take what is left, so a short tab shrinks them rather than pushing the
        // sliders out of sight.
        int histogramsHeight = histograms.getPreferredSize().height;
        int stepperHeight = Math.max(stepper.getPreferredSize().height,
                clipping.getPreferredSize().height);
        int boxesHeight = boxes.getPreferredSize().height;
        int room = Math.max(0, available - histogramsHeight - stepperHeight
                - boxesHeight - 3 * gap);
        int imagesHeight = images.getLayout() instanceof PreviewRowLayout row
                ? Math.min(row.neededHeight(images, width), room)
                : room;

        int y = insets.top;
        images.setBounds(insets.left, y, width, imagesHeight);
        y += imagesHeight + gap;
        histograms.setBounds(insets.left, y, width, histogramsHeight);
        y += histogramsHeight + gap;
        // The stepper under the Before half, the clipping toggle under the After
        // half; the halves are whatever the photo row's layout made them.
        int half = images.getLayout() instanceof PreviewRowLayout row
                ? row.halfWidth(width) : width;
        stepper.setBounds(insets.left, y, half, stepperHeight);
        clipping.setBounds(insets.left + half + MainWindow.PREVIEW_HALF_GAP, y, half, stepperHeight);
        y += stepperHeight + gap;
        boxes.setBounds(insets.left, y, width, boxesHeight);
    }
}
