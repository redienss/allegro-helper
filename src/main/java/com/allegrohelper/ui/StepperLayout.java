package com.allegrohelper.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * The photo stepper's five parts — {@code [|<] [< Prev] 3/20 [Next >] [>|]} —
 * with the <em>counter</em> centered in the row and a matched pair of buttons
 * either side of it. A {@code FlowLayout} would center the group instead, so the
 * wider button would push the counter off the photo's middle.
 *
 * <p>Both the centering and each pair's shared width are measured from the
 * components, not baked in, so File &gt; Settings &gt; Language swapping the
 * labels for longer Polish ones re-centers them instead of clipping.
 */
final class StepperLayout implements LayoutManager {

    /** The five children, in the order {@link #photoStepper} adds them. */
    private static final int FIRST = 0;
    private static final int PREVIOUS = 1;
    private static final int COUNTER = 2;
    private static final int NEXT = 3;
    private static final int LAST = 4;

    private final int gap;

    StepperLayout(int gap) {
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
        if (parent.getComponentCount() < 5) {
            return new Dimension(0, 0);
        }
        Insets insets = parent.getInsets();
        int counter = parent.getComponent(COUNTER).getPreferredSize().width;
        int height = 0;
        for (Component c : parent.getComponents()) {
            height = Math.max(height, c.getPreferredSize().height);
        }
        // Each pair at its wider member's width, on both sides of the counter.
        return new Dimension(
                2 * (jumpWidth(parent) + stepWidth(parent) + 2 * gap) + counter
                        + insets.left + insets.right,
                height + insets.top + insets.bottom);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        return preferredLayoutSize(parent);
    }

    @Override
    public void layoutContainer(Container parent) {
        if (parent.getComponentCount() < 5) {
            return;
        }
        Insets insets = parent.getInsets();
        int width = parent.getWidth() - insets.left - insets.right;
        int height = parent.getHeight() - insets.top - insets.bottom;
        int step = stepWidth(parent);
        int jump = jumpWidth(parent);
        int counterWidth = parent.getComponent(COUNTER).getPreferredSize().width;

        // Out from the centered counter, so the pairs stay mirror images and the
        // number keeps sitting on the photo's middle.
        int counterX = insets.left + (width - counterWidth) / 2;
        place(parent.getComponent(COUNTER), counterX, counterWidth, insets.top, height);
        place(parent.getComponent(PREVIOUS), counterX - gap - step, step, insets.top, height);
        place(parent.getComponent(FIRST), counterX - gap - step - gap - jump, jump,
                insets.top, height);
        int right = counterX + counterWidth + gap;
        place(parent.getComponent(NEXT), right, step, insets.top, height);
        place(parent.getComponent(LAST), right + step + gap, jump, insets.top, height);
    }

    /** One width for [< Prev] and [Next >], whichever of the two labels is longer. */
    private static int stepWidth(Container parent) {
        return Math.max(parent.getComponent(PREVIOUS).getPreferredSize().width,
                parent.getComponent(NEXT).getPreferredSize().width);
    }

    /** One width for the [|<] and [>|] jumps. */
    private static int jumpWidth(Container parent) {
        return Math.max(parent.getComponent(FIRST).getPreferredSize().width,
                parent.getComponent(LAST).getPreferredSize().width);
    }

    /** Puts {@code c} at {@code x} with {@code w}, vertically centered in the row. */
    private static void place(Component c, int x, int w, int top, int height) {
        int h = c.getPreferredSize().height;
        c.setBounds(x, top + Math.max(0, (height - h) / 2), w, h);
    }
}
