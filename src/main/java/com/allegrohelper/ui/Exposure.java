package com.allegrohelper.ui;

import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * What a preview photo's pixels are doing: the luminance histogram, how much of
 * the frame is clipped at each end, and where — one pass over the photo, off the
 * EDT, feeding both the {@link HistogramPanel} under it and the blinkies over it.
 *
 * <p>Clipping is the reason this exists. Brightness scales channels and saturates
 * them at 255, and on a white item against a pale backdrop that destroys detail
 * you cannot get back by dialling the slider down again — the pixels are already
 * flat white. The histogram shows it as a pile against the right edge, but only
 * if you are reading the histogram; the mask paints it on the photo, where you
 * cannot miss it.
 */
record Exposure(int[] bins, double shadowFraction, double highlightFraction,
                        BufferedImage clipped) {

    /** One bin per 8-bit luminance level. */
    static final int BINS = 256;

    /**
     * A channel this high counts as blown, this low as crushed. Not 255/0: JPEG
     * quantization moves a truly clipped channel a level or two off the rail, and
     * a pixel that came back 253 is just as gone as one that came back 255.
     */
    private static final int HIGHLIGHT_CLIP = 253;
    private static final int SHADOW_CLIP = 2;

    /** Blown highlights, in the red every camera marks them in. */
    static final int HIGHLIGHT_MARK = 0xB0FF3B30;
    /** Crushed shadows, in a blue that reads against a dark item. */
    static final int SHADOW_MARK = 0xB0308BFF;

    /** Below this share of the frame, clipping is a stray pixel and not worth a number. */
    private static final double NEGLIGIBLE = 0.0005; // 0.05%

    /** Measures a photo. Call off the EDT: it reads every pixel. */
    static Exposure of(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        int[] bins = new int[BINS];
        int[] marks = new int[px.length]; // transparent where nothing is clipped
        int shadows = 0;
        int highlights = 0;

        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            // The luminance weights the retouching steps themselves use.
            bins[(int) Math.round(0.299 * r + 0.587 * g + 0.114 * b)]++;

            // Any channel, not the luminance: a blown red channel is lost detail
            // even where the pixel as a whole is not especially bright.
            if (r >= HIGHLIGHT_CLIP || g >= HIGHLIGHT_CLIP || b >= HIGHLIGHT_CLIP) {
                marks[i] = HIGHLIGHT_MARK;
                highlights++;
            } else if (r <= SHADOW_CLIP && g <= SHADOW_CLIP && b <= SHADOW_CLIP) {
                // All three, or every deep shadow with a color cast would light up.
                marks[i] = SHADOW_MARK;
                shadows++;
            }
        }

        BufferedImage clipped = null;
        if (shadows > 0 || highlights > 0) {
            clipped = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            clipped.setRGB(0, 0, w, h, marks, 0, w);
        }
        double n = px.length;
        return new Exposure(bins, shadows / n, highlights / n, clipped);
    }

    /** The clipped share as a percentage, or null when it is not worth reporting. */
    String label(double fraction) {
        return fraction < NEGLIGIBLE ? null
                : String.format(Locale.ROOT, "%.1f%%", fraction * 100);
    }
}
