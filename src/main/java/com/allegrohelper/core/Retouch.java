package com.allegrohelper.core;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Photo retouching: automatic gray-world white balance followed by
 * per-channel auto-contrast with a 1% cutoff, saved as JPEG quality 90.
 * Cropping is a separate step ({@link AutoCrop}); background removal is
 * deliberately left manual.
 */
public final class Retouch {

    private static final float JPEG_QUALITY = 0.90f;
    private static final int AUTOCONTRAST_CUTOFF = 1;

    private Retouch() {
    }

    public static void runAll(Config cfg, Reporter reporter) throws IOException {
        if (!Files.isDirectory(cfg.offersDir)) {
            reporter.log("Directory " + cfg.offersDir + " does not exist, no offers to retouch.");
            reporter.stepProgress(1.0);
            return;
        }

        List<Path> offerDirs = listSubdirs(cfg.offersDir);
        int total = offerDirs.size();
        int index = 0;
        for (Path offerDir : offerDirs) {
            retouchOffer(offerDir, reporter);
            reporter.stepProgress(total == 0 ? 1.0 : (double) (++index) / total);
        }
        if (total == 0) {
            reporter.stepProgress(1.0);
        }
    }

    public static void retouchOffer(Path offerDir, Reporter reporter) throws IOException {
        Path photosDir = offerDir.resolve("photos");
        Path retouchedDir = offerDir.resolve("retouched");

        List<Path> photos = ImportPhotos.listJpegs(photosDir);
        if (photos.isEmpty()) {
            return;
        }

        if (Files.isDirectory(retouchedDir) && countEntries(retouchedDir) == photos.size()) {
            reporter.log(offerDir.getFileName() + ": retouching already done, skipping.");
            return;
        }

        Files.createDirectories(retouchedDir);
        for (Path photo : photos) {
            retouchImage(photo, retouchedDir.resolve(photo.getFileName().toString()));
        }

        reporter.log(offerDir.getFileName() + ": retouched " + photos.size() + " photos.");
    }

    static void retouchImage(Path src, Path dest) throws IOException {
        writeJpeg(process(src), dest);
    }

    /**
     * Reads {@code src}, applies EXIF orientation, gray-world white balance and
     * auto-contrast, and returns the resulting image (before JPEG encoding).
     */
    public static BufferedImage process(Path src) throws IOException {
        BufferedImage img = ImageIO.read(src.toFile());
        if (img == null) {
            throw new IOException("Could not read image " + src);
        }
        img = Exif.applyOrientation(img, Exif.readOrientation(src));

        int w = img.getWidth();
        int h = img.getHeight();
        int n = w * h;
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);

        grayWorldWhiteBalance(px, n);
        autoContrast(px, n);

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, px, 0, w);
        return out;
    }

    /** Scales each channel so its mean matches the overall gray mean. */
    private static void grayWorldWhiteBalance(int[] px, int n) {
        long sumR = 0;
        long sumG = 0;
        long sumB = 0;
        for (int p : px) {
            sumR += (p >> 16) & 0xFF;
            sumG += (p >> 8) & 0xFF;
            sumB += p & 0xFF;
        }
        double mr = sumR / (double) n;
        double mg = sumG / (double) n;
        double mb = sumB / (double) n;
        double gray = (mr + mg + mb) / 3.0;
        double sr = mr > 0 ? gray / mr : 1.0;
        double sg = mg > 0 ? gray / mg : 1.0;
        double sb = mb > 0 ? gray / mb : 1.0;

        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = clamp((int) Math.round(((p >> 16) & 0xFF) * sr));
            int g = clamp((int) Math.round(((p >> 8) & 0xFF) * sg));
            int b = clamp((int) Math.round((p & 0xFF) * sb));
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    /** Per-channel auto-contrast reproducing PIL's {@code ImageOps.autocontrast(cutoff=1)}. */
    private static void autoContrast(int[] px, int n) {
        int[] histR = new int[256];
        int[] histG = new int[256];
        int[] histB = new int[256];
        for (int p : px) {
            histR[(p >> 16) & 0xFF]++;
            histG[(p >> 8) & 0xFF]++;
            histB[p & 0xFF]++;
        }
        int[] lutR = buildLut(histR, n);
        int[] lutG = buildLut(histG, n);
        int[] lutB = buildLut(histB, n);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = lutR[(p >> 16) & 0xFF];
            int g = lutG[(p >> 8) & 0xFF];
            int b = lutB[p & 0xFF];
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    private static int[] buildLut(int[] histIn, int n) {
        int[] h = histIn.clone();

        // Trim CUTOFF% of the darkest pixels.
        int cut = n * AUTOCONTRAST_CUTOFF / 100;
        for (int lo = 0; lo < 256; lo++) {
            if (cut > h[lo]) {
                cut -= h[lo];
                h[lo] = 0;
            } else {
                h[lo] -= cut;
                break;
            }
        }
        // Trim CUTOFF% of the lightest pixels.
        cut = n * AUTOCONTRAST_CUTOFF / 100;
        for (int hi = 255; hi >= 0; hi--) {
            if (cut > h[hi]) {
                cut -= h[hi];
                h[hi] = 0;
            } else {
                h[hi] -= cut;
                break;
            }
        }

        int lo = 0;
        while (lo < 256 && h[lo] == 0) {
            lo++;
        }
        int hi = 255;
        while (hi >= 0 && h[hi] == 0) {
            hi--;
        }

        int[] lut = new int[256];
        if (hi <= lo) {
            for (int ix = 0; ix < 256; ix++) {
                lut[ix] = ix;
            }
        } else {
            double scale = 255.0 / (hi - lo);
            double offset = -lo * scale;
            for (int ix = 0; ix < 256; ix++) {
                int v = (int) (ix * scale + offset); // PIL truncates toward zero
                lut[ix] = clamp(v);
            }
        }
        return lut;
    }

    /** Writes {@code img} as JPEG at the pipeline's quality. Shared with {@link AutoCrop}. */
    static void writeJpeg(BufferedImage img, Path dest) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(Files.newOutputStream(dest))) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    private static long countEntries(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.count();
        }
    }

    private static List<Path> listSubdirs(Path dir) throws IOException {
        List<Path> dirs = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory).forEach(dirs::add);
        }
        dirs.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return dirs;
    }
}
