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
 * The two photo-retouching steps: gray-world white balance and per-channel
 * auto-contrast with a 1% cutoff, each saved as JPEG quality 90. They are
 * separate pipeline steps so either can run without the other; auto-contrast
 * reads the white-balanced photos when that step has run, the originals
 * otherwise. Running both re-encodes the JPEG twice — at quality 90 the extra
 * generation loss is negligible, and it buys per-step control.
 * Cropping is a separate step ({@link AutoCrop}); background removal is
 * deliberately left manual.
 */
public final class Retouch {

    /** One retouching operation; each writes its own directory in the offer. */
    public enum Mode {
        /** Gray-world white balance, from {@code photos/} into {@code white_balanced/}. */
        WHITE_BALANCE("white_balanced", "white balance"),
        /** Auto-contrast, from {@code white_balanced/} (else {@code photos/}) into {@code contrasted/}. */
        AUTO_CONTRAST("contrasted", "auto-contrast");

        /** Output directory name inside the offer directory. */
        public final String dirName;
        /** Human-readable name for log lines. */
        public final String label;

        Mode(String dirName, String label) {
            this.dirName = dirName;
            this.label = label;
        }
    }

    private static final float JPEG_QUALITY = 0.90f;
    private static final int AUTOCONTRAST_CUTOFF = 1;

    /** Not instantiable: the class is a namespace for its static steps. */
    private Retouch() {
    }

    /** Applies {@code mode} to every offer under {@code offers/}. */
    public static void runAll(Config cfg, Mode mode, Reporter reporter) throws IOException {
        if (!Files.isDirectory(cfg.offersDir)) {
            reporter.log("Directory " + cfg.offersDir + " does not exist, no offers to retouch.");
            reporter.stepProgress(1.0);
            return;
        }

        List<Path> offerDirs = listSubdirs(cfg.offersDir);
        int total = offerDirs.size();
        int index = 0;
        for (Path offerDir : offerDirs) {
            retouchOffer(offerDir, mode, reporter);
            reporter.stepProgress(total == 0 ? 1.0 : (double) (++index) / total);
        }
        if (total == 0) {
            reporter.stepProgress(1.0);
        }
    }

    /**
     * Retouches one offer. Idempotent: an output directory already holding one
     * entry per input photo counts as done and is skipped, so re-running the
     * step is safe.
     */
    public static void retouchOffer(Path offerDir, Mode mode, Reporter reporter) throws IOException {
        Path inputDir = inputDir(offerDir, mode);
        Path outputDir = offerDir.resolve(mode.dirName);

        List<Path> photos = ImportPhotos.listJpegs(inputDir);
        if (photos.isEmpty()) {
            return;
        }

        if (Files.isDirectory(outputDir) && countEntries(outputDir) == photos.size()) {
            reporter.log(offerDir.getFileName() + ": " + mode.label + " already done, skipping.");
            return;
        }

        Files.createDirectories(outputDir);
        for (Path photo : photos) {
            writeJpeg(process(photo, mode), outputDir.resolve(photo.getFileName().toString()));
        }

        reporter.log(offerDir.getFileName() + ": " + mode.label + " applied to "
                + photos.size() + " photos.");
    }

    /** Each step's input: the previous step's output when it has run, the originals otherwise. */
    private static Path inputDir(Path offerDir, Mode mode) {
        if (mode == Mode.AUTO_CONTRAST) {
            Path whiteBalanced = offerDir.resolve(Mode.WHITE_BALANCE.dirName);
            if (Files.isDirectory(whiteBalanced)) {
                return whiteBalanced;
            }
        }
        return offerDir.resolve("photos");
    }

    /**
     * Reads {@code src}, applies EXIF orientation and the mode's operation, and
     * returns the resulting image (before JPEG encoding). Pipeline-written
     * inputs carry no EXIF metadata, so the orientation step is a no-op for
     * them — the call stays unconditional.
     */
    public static BufferedImage process(Path src, Mode mode) throws IOException {
        BufferedImage img = ImageIO.read(src.toFile());
        if (img == null) {
            throw new IOException("Could not read image " + src);
        }
        img = Exif.applyOrientation(img, Exif.readOrientation(src));

        int w = img.getWidth();
        int h = img.getHeight();
        int n = w * h;
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);

        switch (mode) {
            case WHITE_BALANCE -> grayWorldWhiteBalance(px, n);
            case AUTO_CONTRAST -> autoContrast(px, n);
        }

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

    /**
     * Builds one channel's contrast lookup table the way PIL does: trim the
     * cutoff percentile off each end of the histogram, then linearly stretch
     * what remains across 0..255. The truncation (rather than rounding) below
     * is PIL's, and matching it is why the output is bit-comparable with the
     * Python original — deviating here is a behavior change, not a cleanup.
     */
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

    /** Clamps a channel value into 0..255. */
    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /** How many entries {@code dir} holds — the idempotence check's "already done" signal. */
    private static long countEntries(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.count();
        }
    }

    /** The offer directories under {@code dir}, in name order. */
    private static List<Path> listSubdirs(Path dir) throws IOException {
        List<Path> dirs = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory).forEach(dirs::add);
        }
        dirs.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return dirs;
    }
}
