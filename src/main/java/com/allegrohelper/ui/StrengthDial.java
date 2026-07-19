package com.allegrohelper.ui;

import com.allegrohelper.core.Retouch;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;

import static com.allegrohelper.ui.PreviewRow.PREVIEW_ROW_GAP;
import static com.allegrohelper.ui.PreviewRow.previewRow;

/**
 * One retouching step's strength dial: its Retouch Preview checkbox, a slider
 * across all the width the tab has left, and the value it is set to
 * ("1.20x"). Brightness and contrast each get one — same widget, same
 * behavior, different {@link Retouch} mode.
 *
 * <p>The slider re-renders the preview only once its value settles: a render
 * costs a decode and a series scan, and every value the knob passes through on
 * the way would queue one. A drag says when it has settled
 * ({@code getValueIsAdjusting}); the wheel cannot, so {@link #wheelSettle}
 * calls it settled once the scrolling stops.
 */
final class StrengthDial {

    private final JCheckBox box;
    /**
     * Every dial on the tab, including this one. {@link DialRowLayout} measures
     * across the whole list so the sliders line up under each other — the
     * columns are as wide as the widest label in <em>any</em> dial's row.
     */
    private final List<StrengthDial> peers;
    /** Re-renders the preview once the slider has settled. */
    private final Runnable onSettled;
    private final JSlider slider = new JSlider(
            scaledStrength(Retouch.MIN_STRENGTH),
            scaledStrength(Retouch.MAX_STRENGTH),
            scaledStrength(Retouch.NEUTRAL_STRENGTH));
    private final JLabel valueLabel = new JLabel();
    /**
     * Ends the "adjusting" state a wheel notch put the slider in, once the wheel
     * has been still for {@link #WHEEL_SETTLE_MS}. That fires the slider's own
     * change event with nothing adjusting any more, so a scroll re-renders the
     * preview exactly once, through the same path a finished drag takes.
     */
    private final Timer wheelSettle = new Timer(WHEEL_SETTLE_MS,
            e -> slider.setValueIsAdjusting(false));

    /**
     * @param box the step's Retouch Preview checkbox; the dial greys out with it
     * @param initial the strength to show until {@link #row} reads the config
     * @param tooltip what the numbers mean, in English for {@link I18n}
     */
    StrengthDial(JCheckBox box, List<StrengthDial> peers, Runnable onSettled,
            double initial, String tooltip) {
        this.box = box;
        this.peers = peers;
        this.onSettled = onSettled;
        peers.add(this);
        slider.setValue(scaledStrength(initial));
        slider.setMajorTickSpacing(scaledStrength(Retouch.NEUTRAL_STRENGTH)
                - scaledStrength(Retouch.MIN_STRENGTH));
        slider.setPaintTicks(true);
        slider.setToolTipText(I18n.t(tooltip));

        wheelSettle.setRepeats(false); // one shot per scroll, restarted by each notch
        slider.addMouseWheelListener(e -> {
            if (!slider.isEnabled()) {
                return;
            }
            // Wheel up (a negative rotation) means more, the direction the knob
            // moves under the same gesture.
            slider.setValueIsAdjusting(true); // no render per notch
            slider.setValue(slider.getValue() - e.getWheelRotation() * WHEEL_STEP);
            wheelSettle.restart();
        });
        slider.addChangeListener(e -> {
            showValue();
            if (!slider.getValueIsAdjusting()) {
                onSettled.run();
            }
        });
    }

    /**
     * The dial's row, set to {@code strength} — the config's, so a
     * {@code *_STRENGTH} in {@code .env} is what the user sees. Laid out by
     * {@link DialRowLayout}, which gives the checkbox and the value the same
     * width in every dial's row, so the sliders line up under each other and
     * come out the same length.
     */
    JPanel row(double strength) {
        slider.setValue(scaledStrength(strength));
        JPanel row = previewRow(new DialRowLayout(6));
        // leftRow's FlowLayout gaps, so the rows are evenly spaced.
        row.setBorder(BorderFactory.createEmptyBorder(PREVIEW_ROW_GAP, 5, PREVIEW_ROW_GAP, 5));
        row.add(box);
        row.add(slider);
        row.add(valueLabel);
        showValue();
        return row;
    }

    /** The strength the slider is set to. */
    double strength() {
        return slider.getValue() / 100.0;
    }

    /**
     * Shows the setting beside the slider, as the multiplier it stands for, and
     * greys the pair out while the step is unticked. {@code Locale.ROOT} keeps
     * the decimal point a point in both languages: it is the same number the
     * {@code *_STRENGTH} config key takes, which is not localized.
     */
    void showValue() {
        valueLabel.setText(String.format(Locale.ROOT, "%.2fx", strength()));
        valueLabel.setEnabled(box.isSelected());
        slider.setEnabled(box.isSelected());
    }

    /**
     * A dial's row: {@code [x] Brightness ——[]———— 1.00x}. The checkbox column is as
     * wide as the widest step name across <em>all</em> the dials, and the value
     * column as wide as the widest value, so every slider starts and ends on the
     * same two columns — one exactly under the other, and all the same length. A
     * plain {@code BorderLayout} sizes each row's edges from its own contents, which
     * left the contrast slider longer than the brightness one by the difference
     * between the two words.
     *
     * <p>The columns are measured at layout time, not frozen, so File &gt; Settings
     * &gt; Language re-measures them for the Polish names.
     */
    final class DialRowLayout implements LayoutManager {

        private final int gap;

        DialRowLayout(int gap) {
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
            Insets insets = parent.getInsets();
            int height = 0;
            for (Component c : parent.getComponents()) {
                height = Math.max(height, c.getPreferredSize().height);
            }
            // The width is whatever the tab gives the row; only the height is its own.
            return new Dimension(0, height + insets.top + insets.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void layoutContainer(Container parent) {
            if (parent.getComponentCount() < 3) {
                return;
            }
            Component box = parent.getComponent(0);
            Component slider = parent.getComponent(1);
            Component value = parent.getComponent(2);

            Insets insets = parent.getInsets();
            int width = parent.getWidth() - insets.left - insets.right;
            int height = parent.getHeight() - insets.top - insets.bottom;
            int boxWidth = columnWidth(dial -> dial.box.getPreferredSize().width);
            int valueWidth = columnWidth(dial -> dial.valueLabel.getPreferredSize().width);
            int sliderWidth = Math.max(0, width - boxWidth - valueWidth - 2 * gap);

            place(box, insets.left, boxWidth, insets.top, height);
            place(slider, insets.left + boxWidth + gap, sliderWidth, insets.top, height);
            place(value, insets.left + boxWidth + gap + sliderWidth + gap, valueWidth,
                    insets.top, height);
        }

        /** How wide that part of the row wants to be, across every dial on the tab. */
        private int columnWidth(ToIntFunction<StrengthDial> part) {
            int width = 0;
            for (StrengthDial dial : peers) {
                width = Math.max(width, part.applyAsInt(dial));
            }
            return width;
        }

        /** Puts {@code c} at {@code x} with {@code w}, vertically centered in the row. */
        private void place(Component c, int x, int w, int top, int height) {
            int h = c.getPreferredSize().height;
            c.setBounds(x, top + Math.max(0, (height - h) / 2), w, h);
        }
    }

    /** A strength in the slider's units: hundredths, because a {@link JSlider} only speaks int. */
    static int scaledStrength(double strength) {
        return (int) Math.round(strength * 100);
    }

    /** Ends the "adjusting" state a wheel notch put the slider in. */
    private static final int WHEEL_SETTLE_MS = 300;

    /** How many slider units one wheel notch moves the knob. */
    private static final int WHEEL_STEP = 5;
}
