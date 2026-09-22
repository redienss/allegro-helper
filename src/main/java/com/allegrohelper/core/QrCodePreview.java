package com.allegrohelper.core;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.nio.file.Path;

/**
 * Renders what the QR Code step would do to one photo, without touching the
 * filesystem — the UI's QR Code tab's live preview.
 *
 * <p>Reuses {@link QrCode#composite} rather than approximating it, same as
 * {@link RetouchPreview} reuses {@link Retouch}/{@link AutoCrop}: a preview
 * that drifts from the pipeline is worse than none. The photo is decoded
 * <em>subsampled</em> to display size (see {@link RetouchPreview} for why
 * that is safe), and the requested QR size, padding, border and label font
 * size are all scaled down to match — a caller passing full-resolution
 * settings against a quarter-size preview image would otherwise see a QR
 * code (and its padding and border) four times too large for the frame.
 */
public final class QrCodePreview {

    /** The rendered photo, plus where it sits in the offer — same shape as {@link RetouchPreview.Result}. */
    public record Result(BufferedImage image, int index, int count) {
    }

    /** Not instantiable: the class is a namespace for {@link #render}. */
    private QrCodePreview() {
    }

    /**
     * Renders one of an offer's photos with {@code liveSettings}' QR code
     * applied, or null when the offer has no photos yet.
     *
     * @param photoIndex   which photo of the series to preview, 0-based; clamped into range
     * @param liveSettings the tab's current field values — rendered even before Save,
     *                     so typing shows its effect immediately; a blank URL renders
     *                     the plain photo, same as an offer with no {@code qr.json}
     * @param maxSize      longest side of the returned image, in pixels
     */
    public static Result render(Path offerDir, int photoIndex, QrCode.QrSettings liveSettings, int maxSize)
            throws IOException {
        Path inputDir = QrCode.qrCodeInput(offerDir);
        if (inputDir == null) {
            return null;
        }
        List<Path> photos = ImportPhotos.listJpegs(inputDir);
        if (photos.isEmpty()) {
            return null;
        }

        int index = Math.max(0, Math.min(photoIndex, photos.size() - 1));
        Sample sample = decodeSampled(photos.get(index), maxSize);
        BufferedImage image = sample.image();

        if (liveSettings != null && liveSettings.url() != null && !liveSettings.url().isBlank()) {
            QrCode.QrSettings scaled = new QrCode.QrSettings(
                    liveSettings.url(), liveSettings.label(),
                    Math.max(1, (int) Math.round(liveSettings.sizePx() * sample.scale())),
                    Math.max(0, (int) Math.round(liveSettings.paddingPx() * sample.scale())),
                    Math.max(0, (int) Math.round(liveSettings.borderPx() * sample.scale())),
                    Math.max(1, (int) Math.round(liveSettings.labelFontSize() * sample.scale())),
                    liveSettings.labelPosition(), liveSettings.position(), liveSettings.photoIndex());
            image = QrCode.composite(image, scaled);
        }
        return new Result(image, index, photos.size());
    }

    /** A decoded photo and how much smaller than the original it is. */
    private record Sample(BufferedImage image, double scale) {
    }

    /** Decodes {@code src} at roughly {@code maxSize} on its longest side, upright — see {@link RetouchPreview}. */
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

                double scale = img.getWidth() / (double) width;
                return new Sample(Exif.applyOrientation(img, Exif.readOrientation(src)), scale);
            } finally {
                reader.dispose();
            }
        }
    }
}
