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
 * The two photo-retouching steps: gray-world white balance and a contrast
 * adjustment, each saved as JPEG quality 90. They are separate pipeline steps
 * so either can run without the other; contrast reads the white-balanced photos
 * when that step has run, the originals otherwise. Running both re-encodes the
 * JPEG twice — at quality 90 the extra generation loss is negligible, and it
 * buys per-step control.
 * Cropping is a separate step ({@link AutoCrop}); background removal is
 * deliberately left manual.
 *
 * <p>Contrast is a <em>strength dial</em>, not an automatic stretch: the user
 * sets it on the Retouch Preview tab's slider (or {@code CONTRAST_STRENGTH})
 * and sees the result before committing a run. The earlier step reproduced
 * PIL's {@code ImageOps.autocontrast(cutoff=1)}, which adapts to each photo's
 * own histogram and therefore cannot be dialled up or down — a knob the user
 * can turn is worth more here than one that guesses.
 */
public final class Retouch {

    /** One retouching operation; each writes its own directory in the offer. */
    public enum Mode {
        /** Gray-world white balance, from {@code photos/} into {@code white_balanced/}. */
        WHITE_BALANCE("white_balanced", "white balance"),
        /**
         * Contrast, from {@code white_balanced/} (else {@code photos/}) into
         * {@code contrasted/}. The directory name predates the rename from
         * auto-contrast and stays, so offers processed before it keep working.
         */
        CONTRAST("contrasted", "contrast");

        /** Output directory name inside the offer directory. */
        public final String dirName;
        /** Human-readable name for log lines. */
        public final String label;

        Mode(String dirName, String label) {
            this.dirName = dirName;
            this.label = label;
        }
    }

    /** Contrast strength that leaves the photo untouched. */
    public static final double NEUTRAL_CONTRAST = 1.0;
    /** Flattest contrast the slider (and {@code CONTRAST_STRENGTH}) allow. */
    public static final double MIN_CONTRAST = 0.5;
    /** Punchiest contrast the slider (and {@code CONTRAST_STRENGTH}) allow. */
    public static final double MAX_CONTRAST = 2.0;
    /** A mild boost: enough that ticking the step does something visible. */
    public static final double DEFAULT_CONTRAST = 1.2;

    private static final float JPEG_QUALITY = 0.90f;

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

        if (mode == Mode.CONTRAST) {
            reporter.log("Contrast strength: " + cfg.contrastStrength + "x");
        }
        List<Path> offerDirs = listSubdirs(cfg.offersDir);
        int total = offerDirs.size();
        int index = 0;
        for (Path offerDir : offerDirs) {
            retouchOffer(offerDir, mode, cfg.contrastStrength, reporter);
            reporter.stepProgress(total == 0 ? 1.0 : (double) (++index) / total);
        }
        if (total == 0) {
            reporter.stepProgress(1.0);
        }
    }

    /**
     * Retouches one offer. Idempotent: an output directory already holding one
     * entry per input photo counts as done and is skipped, so re-running the
     * step is safe. A skip ignores {@code contrastStrength} — output already on
     * disk is never silently rewritten; use Delete Output Files to redo it at a
     * new strength.
     */
    public static void retouchOffer(Path offerDir, Mode mode, double contrastStrength,
                                    Reporter reporter) throws IOException {
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
            writeJpeg(process(photo, mode, contrastStrength),
                    outputDir.resolve(photo.getFileName().toString()));
        }

        reporter.log(offerDir.getFileName() + ": " + mode.label + " applied to "
                + photos.size() + " photos.");
    }

    /** Each step's input: the previous step's output when it has run, the originals otherwise. */
    private static Path inputDir(Path offerDir, Mode mode) {
        if (mode == Mode.CONTRAST) {
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
    public static BufferedImage process(Path src, Mode mode, double contrastStrength)
            throws IOException {
        BufferedImage img = ImageIO.read(src.toFile());
        if (img == null) {
            throw new IOException("Could not read image " + src);
        }
        return apply(Exif.applyOrientation(img, Exif.readOrientation(src)), mode, contrastStrength);
    }

    /**
     * Applies the mode's operation to an already-decoded, already-upright image
     * and returns the result; {@code img} is left untouched. Split out of
     * {@link #process} so the UI's retouch preview can chain the two modes in
     * memory, without writing the intermediate to disk as the pipeline does.
     *
     * @param contrastStrength the {@link Mode#CONTRAST} dial; ignored by the
     *                         other modes
     */
    public static BufferedImage apply(BufferedImage img, Mode mode, double contrastStrength) {
        int w = img.getWidth();
        int h = img.getHeight();
        int n = w * h;
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);

        switch (mode) {
            case WHITE_BALANCE -> grayWorldWhiteBalance(px, n);
            case CONTRAST -> contrast(px, n, clampStrength(contrastStrength));
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, px, 0, w);
        return out;
    }

    /** Holds a strength inside the range the slider offers; a garbled config cannot wreck a photo. */
    public static double clampStrength(double strength) {
        if (Double.isNaN(strength)) {
            return DEFAULT_CONTRAST;
        }
        return Math.max(MIN_CONTRAST, Math.min(MAX_CONTRAST, strength));
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

    /**
     * Contrast by a strength factor, reproducing PIL's
     * {@code ImageEnhance.Contrast}: every pixel is pushed away from (or pulled
     * towards) the photo's own mean luminance by {@code strength}, so 1.0 is a
     * no-op, below it flattens and above it deepens. Pivoting on the mean rather
     * than a fixed mid-gray is what keeps a photo's overall brightness where it
     * was — a turntable shot of a pale item on a pale background sits well above
     * mid-gray, and pivoting there would darken the whole frame.
     *
     * <p>The pivot is one gray value for all three channels (PIL blends against
     * a flat gray image), so the operation cannot introduce a color cast; that
     * is white balance's job, and the two steps stay independent.
     */
    private static void contrast(int[] px, int n, double strength) {
        // PIL's L conversion, and it rounds the mean to an integer before blending.
        double sum = 0;
        for (int p : px) {
            sum += 0.299 * ((p >> 16) & 0xFF) + 0.587 * ((p >> 8) & 0xFF) + 0.114 * (p & 0xFF);
        }
        int mean = clamp((int) Math.round(sum / n));

        // One LUT per 8-bit level: 256 blends instead of one per pixel.
        int[] lut = new int[256];
        for (int v = 0; v < 256; v++) {
            lut[v] = clamp((int) Math.round(mean + strength * (v - mean)));
        }
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = lut[(p >> 16) & 0xFF];
            int g = lut[(p >> 8) & 0xFF];
            int b = lut[p & 0xFF];
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
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
