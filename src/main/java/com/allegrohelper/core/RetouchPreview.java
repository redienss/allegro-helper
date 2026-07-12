package com.allegrohelper.core;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Renders what the retouching steps would do to an offer, without touching the
 * filesystem: the UI's Retouch Preview tab shows the offer's first photo as it
 * is now next to the same photo with the ticked steps applied.
 *
 * <p>It deliberately reuses {@link Retouch} and {@link AutoCrop} rather than
 * approximating them — a preview that drifts from the pipeline is worse than
 * none. The steps are chained in memory in pipeline order (white balance →
 * auto-contrast → auto-crop), which is what the pipeline does through its
 * intermediate directories; the only difference is the JPEG re-encode between
 * them, invisible at quality 90.
 *
 * <p>Auto-crop's box is detected from the <em>originals</em>, because that is
 * all that exists before a run. A real run detects it on the retouched photos,
 * where the luminance ranges are stretched slightly — so the previewed box can
 * differ from the final one by a few pixels. Retouching cannot make a
 * detectable item undetectable, so the "will it crop at all" answer, the one
 * the preview is there to give, holds.
 */
public final class RetouchPreview {

    /** The two images the tab shows; both already scaled down for display. */
    public record Result(BufferedImage before, BufferedImage after) {
    }

    /** Not instantiable: the class is a namespace for {@link #render}. */
    private RetouchPreview() {
    }

    /**
     * Renders the before/after pair for an offer's first photo, or null when the
     * offer has no photos yet.
     *
     * @param maxSize longest side of the returned images, in pixels — they are
     *                only ever shown scaled to fit a panel, and full-resolution
     *                copies would cost memory and repaint time for nothing
     */
    public static Result render(Path offerDir, boolean whiteBalance, boolean autoContrast,
                                boolean autoCrop, int maxSize) throws IOException {
        Path photosDir = offerDir.resolve("photos");
        if (!Files.isDirectory(photosDir)) {
            return null;
        }
        List<Path> photos = ImportPhotos.listJpegs(photosDir);
        if (photos.isEmpty()) {
            return null;
        }

        Path first = photos.get(0);
        BufferedImage original = ImageIO.read(first.toFile());
        if (original == null) {
            throw new IOException("Could not read image " + first);
        }
        original = Exif.applyOrientation(original, Exif.readOrientation(first));

        BufferedImage after = original;
        if (whiteBalance) {
            after = Retouch.apply(after, Retouch.Mode.WHITE_BALANCE);
        }
        if (autoContrast) {
            after = Retouch.apply(after, Retouch.Mode.AUTO_CONTRAST);
        }
        if (autoCrop) {
            int[] box = AutoCrop.detectBox(photos);
            // A null box is the step declining to crop: the preview then shows
            // the uncropped photo, exactly as a run would leave it.
            if (box != null && box[0] + box[2] <= after.getWidth()
                    && box[1] + box[3] <= after.getHeight()) {
                after = after.getSubimage(box[0], box[1], box[2], box[3]);
            }
        }

        return new Result(scaleToFit(original, maxSize), scaleToFit(after, maxSize));
    }

    /** Scales an image down so its longest side is {@code maxSize}; never scales up. */
    private static BufferedImage scaleToFit(BufferedImage img, int maxSize) {
        double scale = maxSize / (double) Math.max(img.getWidth(), img.getHeight());
        if (scale >= 1.0) {
            return img;
        }
        int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
