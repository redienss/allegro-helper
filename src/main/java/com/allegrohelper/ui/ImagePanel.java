package com.allegrohelper.ui;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * One half of the Retouch Preview: a titled panel painting an image scaled to
 * fit, or a status line while there is no image to show. It scales on paint
 * rather than keeping a pre-scaled copy, so the preview follows the split
 * pane as the user drags it.
 */
final class ImagePanel extends JPanel {

    /** The shape of a panel that has never shown an image: a camera's usual 4:3. */
    private static final double DEFAULT_ASPECT = 4.0 / 3.0;

    private BufferedImage image;
    /** Clipped pixels of {@link #image}, painted over it while {@link #showClipping}. */
    private BufferedImage clipped;
    private boolean showClipping;
    private String status = "";
    /**
     * Width/height of the last image shown, which the panel keeps its shape at
     * while it has no image. Without it the panel would take the tab's whole
     * height whenever it shows a status line — "Select an offer in the grid.",
     * or "Rendering the preview…" between two offers — and the layout would
     * jump every time a render lands.
     */
    private double aspect = DEFAULT_ASPECT;

    /** @param title the border title ("Before" / "After"); {@link I18n} translates it in place */
    ImagePanel(String title) {
        setBorder(BorderFactory.createTitledBorder(title));
    }

    /** @param clipped the photo's clipped pixels, or null when it has none */
    void setImage(BufferedImage img, BufferedImage clipped) {
        image = img;
        this.clipped = clipped;
        status = "";
        aspect = img.getWidth() / (double) img.getHeight();
        revalidate(); // the row's height follows the image's shape
        repaint();
    }

    /** Whether clipped pixels are marked on the photo. */
    void setShowClipping(boolean show) {
        showClipping = show;
        repaint();
    }

    /** Shows {@code text} instead of an image (already translated), at the current shape. */
    void setStatus(String text) {
        image = null;
        clipped = null;
        status = text;
        revalidate();
        repaint();
    }

    /** The height at which an image of the panel's shape, drawn this wide, fills it exactly. */
    int heightFor(int width) {
        Insets insets = getInsets();
        int content = width - insets.left - insets.right;
        if (content <= 0) {
            return 0;
        }
        return (int) Math.round(content / aspect) + insets.top + insets.bottom;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Insets insets = getInsets();
        int w = getWidth() - insets.left - insets.right;
        int h = getHeight() - insets.top - insets.bottom;
        if (w <= 0 || h <= 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        if (image == null) {
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(getForeground());
            g2.drawString(status,
                    insets.left + Math.max(0, (w - fm.stringWidth(status)) / 2),
                    insets.top + h / 2);
        } else {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            double scale = Math.min(w / (double) image.getWidth(),
                    h / (double) image.getHeight());
            int iw = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int ih = Math.max(1, (int) Math.round(image.getHeight() * scale));
            int x = insets.left + (w - iw) / 2;
            int y = insets.top + (h - ih) / 2;
            g2.drawImage(image, x, y, iw, ih, null);
            if (showClipping && clipped != null) {
                // Nearest-neighbour, so a thin run of blown pixels stays visible
                // instead of being blended away by the downscale.
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g2.drawImage(clipped, x, y, iw, ih, null);
            }
        }
        g2.dispose();
    }
}
