package com.allegrohelper.ui;

import javax.swing.BorderFactory;
import javax.swing.UIManager;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

/**
 * The luminance histogram of one preview photo: how many pixels sit at each
 * brightness, shadows on the left and highlights on the right. It answers the
 * question the photo itself answers badly on a screen — is this exposed right,
 * or are the highlights already clipped? — and it is what the Brightness and
 * Contrast sliders steer: brightness slides the whole shape sideways, contrast
 * spreads or gathers it around its middle.
 *
 * <p>The curve is scaled by the <em>square root</em> of each count, not the count
 * itself. On a turntable shot the pale, unchanging background is one enormous
 * spike; against it, linear bars would flatten every tone of the item into a line
 * along the bottom. The square root compresses the spike and keeps the item's
 * tones legible: the shape stays honest even though the proportions do not, and
 * it is the shape that is being read.
 */
final class HistogramPanel extends JPanel {

    /** Height of a histogram under a preview photo. */
    private static final int HISTOGRAM_HEIGHT = 70;

    private Exposure exposure;

    HistogramPanel() {
        setBorder(BorderFactory.createLineBorder(UIManager.getColor("controlShadow")));
        setPreferredSize(new Dimension(0, HISTOGRAM_HEIGHT));
        setToolTipText(I18n.t(
                "Brightness of the photo's pixels: shadows left, highlights right."));
    }

    /** Shows a photo's histogram, or nothing when {@code exposure} is null. */
    void setExposure(Exposure exposure) {
        this.exposure = exposure;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (exposure == null) {
            return;
        }
        Insets insets = getInsets();
        int w = getWidth() - insets.left - insets.right;
        int h = getHeight() - insets.top - insets.bottom;
        if (w <= 0 || h <= 0) {
            return;
        }
        int[] bins = exposure.bins();
        double peak = 0;
        for (int count : bins) {
            peak = Math.max(peak, Math.sqrt(count));
        }
        if (peak <= 0) {
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        // One filled shape rather than 256 bars, so it reads as a curve at any width.
        Path2D shape = new Path2D.Double();
        shape.moveTo(insets.left, insets.top + h);
        for (int i = 0; i < Exposure.BINS; i++) {
            double x = insets.left + (i + 0.5) * w / (double) Exposure.BINS;
            double y = insets.top + h - Math.sqrt(bins[i]) / peak * h;
            shape.lineTo(x, y);
        }
        shape.lineTo(insets.left + w, insets.top + h);
        shape.closePath();
        Color fg = getForeground();
        g2.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 150));
        g2.fill(shape);

        // How much is clipped, at the end of the histogram it is clipped at, in
        // the color the photo's blinkies mark it in. Absent when there is none.
        paintClipped(g2, exposure.label(exposure.shadowFraction()),
                new Color(Exposure.SHADOW_MARK, true), insets.left + 4, insets.top + 4, false);
        paintClipped(g2, exposure.label(exposure.highlightFraction()),
                new Color(Exposure.HIGHLIGHT_MARK, true), insets.left + w - 4, insets.top + 4,
                true);
        g2.dispose();
    }

    /** Draws a clipped-percentage tag, right-aligned at {@code x} when {@code rightAlign}. */
    private void paintClipped(Graphics2D g2, String label, Color color, int x, int y,
                              boolean rightAlign) {
        if (label == null) {
            return;
        }
        g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue()));
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(label, rightAlign ? x - fm.stringWidth(label) : x, y + fm.getAscent());
    }
}
