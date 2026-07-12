package com.allegrohelper.core;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

/**
 * Renders what the retouching steps would do to an offer, without touching the
 * filesystem: the UI's Retouch Preview tab shows the offer's first photo as it
 * is now next to the same photo with the ticked steps applied.
 *
 * <p>It deliberately reuses {@link Retouch} and {@link AutoCrop} rather than
 * approximating them — a preview that drifts from the pipeline is worse than
 * none. The steps are chained in memory in pipeline order (white balance →
 * brightness → contrast → auto-crop), which is what the pipeline does through its
 * intermediate directories; the only difference is the JPEG re-encode between
 * them, invisible at quality 90.
 *
 * <p>The photo is decoded <em>subsampled</em> to display size rather than at its
 * full 12 megapixels, which is what makes dragging the contrast slider feel
 * immediate: retouching the sample costs ~0.15s against ~1.2s for the full frame,
 * and the full-resolution result was only going to be scaled away anyway.
 * The retouching operations survive the sampling because both are <em>global</em>
 * — white balance scales each channel by its mean, contrast pivots on the mean
 * luminance — and a mean over every ninth pixel is the same mean to within a
 * rounding step. A pipeline run of course still works at full size; only the
 * preview samples.
 *
 * <p>Auto-crop's box is detected from the <em>originals</em>, because that is
 * all that exists before a run. A real run detects it on the retouched photos,
 * where the luminance ranges are stretched slightly — so the previewed box can
 * differ from the final one by a few pixels. Retouching cannot make a
 * detectable item undetectable, so the "will it crop at all" answer, the one
 * the preview is there to give, holds.
 */
public final class RetouchPreview {

    /**
     * The two images the tab shows, both already scaled down for display, plus where
     * the rendered photo sits in the offer: {@code index} is 0-based and clamped
     * into the series, {@code count} is how many photos the offer has. The tab's
     * "1/20" reads them — nothing else has listed the photo directory, so nothing
     * else knows the count.
     */
    public record Result(BufferedImage before, BufferedImage after, int index, int count) {
    }

    /**
     * Which retouching steps to preview and at what strength — the Retouch Preview
     * tab's checkboxes and sliders, as one value. A record rather than six
     * parameters because the booleans and their dials belong together, and a
     * six-argument call is where a caller silently swaps two of them.
     */
    public record Settings(boolean whiteBalance, boolean brightness, double brightnessStrength,
                           boolean contrast, double contrastStrength, boolean autoCrop) {
    }

    /** Not instantiable: the class is a namespace for {@link #render}. */
    private RetouchPreview() {
    }

    /**
     * Renders the before/after pair for one of an offer's photos, or null when the
     * offer has no photos yet.
     *
     * @param photoIndex which photo of the series, 0-based; clamped into range, so
     *                   an index left over from a longer offer cannot fail a render
     * @param steps the ticked steps and their slider strengths, so the user sees
     *              what a run would produce at those settings
     * @param maxSize longest side of the returned images, in pixels — also the size
     *                the photo is decoded at, since it is only ever shown scaled to
     *                fit a panel
     */
    public static Result render(Path offerDir, int photoIndex, Settings steps, int maxSize)
            throws IOException {
        Path photosDir = offerDir.resolve("photos");
        if (!Files.isDirectory(photosDir)) {
            return null;
        }
        List<Path> photos = ImportPhotos.listJpegs(photosDir);
        if (photos.isEmpty()) {
            return null;
        }

        int index = Math.max(0, Math.min(photoIndex, photos.size() - 1));
        Sample original = decodeSampled(photos.get(index), maxSize);

        BufferedImage after = original.image();
        if (steps.whiteBalance()) {
            after = Retouch.apply(after, Retouch.Mode.WHITE_BALANCE, Retouch.NEUTRAL_STRENGTH);
        }
        if (steps.brightness()) {
            after = Retouch.apply(after, Retouch.Mode.BRIGHTNESS, steps.brightnessStrength());
        }
        if (steps.contrast()) {
            after = Retouch.apply(after, Retouch.Mode.CONTRAST, steps.contrastStrength());
        }
        if (steps.autoCrop()) {
            // detectBox reports full-resolution pixels; the sample is a fraction of
            // that size, so the box has to shrink with it.
            int[] box = scaleBox(AutoCrop.detectBox(photos), original.scale());
            // A null box is the step declining to crop: the preview then shows
            // the uncropped photo, exactly as a run would leave it.
            if (box != null && box[0] + box[2] <= after.getWidth()
                    && box[1] + box[3] <= after.getHeight()) {
                after = after.getSubimage(box[0], box[1], box[2], box[3]);
            }
        }

        return new Result(original.image(), after, index, photos.size());
    }

    /**
     * A decoded photo and how much smaller than the original it is, so a box
     * measured in full-resolution pixels can be mapped onto it.
     */
    private record Sample(BufferedImage image, double scale) {
    }

    /**
     * Decodes {@code src} at roughly {@code maxSize} on its longest side, upright.
     * The decoder subsamples while it reads (point-sampling every n-th pixel), so
     * the full frame is never held or scanned — that is the whole speed-up.
     *
     * <p>Subsampling can only divide, so the result is the first size at or below
     * {@code maxSize}, and a photo already smaller comes back untouched.
     */
    private static Sample decodeSampled(Path src, int maxSize) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(src.toFile())) {
            if (in == null) {
                throw new IOException("Could not read image " + src);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("Could not read image " + src);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                int step = Math.max(1, Math.max(width, height) / Math.max(1, maxSize));

                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage img = reader.read(0, param);

                // The scale is the decoder's actual one, not 1/step: subsampling
                // rounds the size up, so a 4000px side at step 3 comes back 1334px.
                double scale = img.getWidth() / (double) width;
                return new Sample(Exif.applyOrientation(img, Exif.readOrientation(src)), scale);
            } finally {
                reader.dispose();
            }
        }
    }

    /** A full-resolution box in the sample's pixels, or null if there is no box. */
    private static int[] scaleBox(int[] box, double scale) {
        if (box == null) {
            return null;
        }
        int[] scaled = new int[4];
        for (int i = 0; i < 4; i++) {
            scaled[i] = (int) Math.round(box[i] * scale);
        }
        // A box that rounds away to nothing would make getSubimage throw.
        scaled[2] = Math.max(1, scaled[2]);
        scaled[3] = Math.max(1, scaled[3]);
        return scaled;
    }

}
