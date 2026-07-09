package com.allegrohelper.ui;

import com.allegrohelper.core.Exif;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.ImageIcon;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * Loads photo thumbnails for the galleries. Large source JPEGs (e.g. 4000×3000)
 * are read at a reduced resolution via subsampling so we never decode the full
 * image, then scaled to fit and rotated per EXIF orientation (originals are
 * often stored upside-down; retouched copies are already upright).
 */
final class Thumbnails {

    private Thumbnails() {
    }

    /** Returns a thumbnail icon fitting a {@code maxSize} box, or {@code null} on failure. */
    static ImageIcon load(Path file, int maxSize) {
        try (InputStream in = Files.newInputStream(file);
             ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            if (iis == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                int subsample = Math.max(1, Math.max(w, h) / Math.max(1, maxSize));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(subsample, subsample, 0, 0);
                BufferedImage img = reader.read(0, param);
                img = Exif.applyOrientation(img, Exif.readOrientation(file));
                return new ImageIcon(scaleToFit(img, maxSize));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Image scaleToFit(BufferedImage img, int maxSize) {
        int w = img.getWidth();
        int h = img.getHeight();
        double scale = Math.min(maxSize / (double) w, maxSize / (double) h);
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        return img.getScaledInstance(nw, nh, Image.SCALE_SMOOTH);
    }
}
