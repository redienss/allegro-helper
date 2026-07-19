package com.allegrohelper.core;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * The three photo-retouching steps: gray-world white balance, brightness and
 * contrast, each saved as JPEG quality 90. They are separate pipeline steps so
 * any can run without the others; each reads the most-processed input available
 * ({@link #inputDir}), so unticking one simply drops it out of the chain. Every
 * step re-encodes the JPEG — at quality 90 the extra generation loss is
 * negligible, and it buys per-step control.
 * Cropping is a separate step ({@link AutoCrop}); background removal is
 * deliberately left manual.
 *
 * <p>Brightness and contrast are <em>strength dials</em>, not automatic
 * corrections: the user sets them on the Retouch Preview tab's sliders (or
 * {@code BRIGHTNESS_STRENGTH} / {@code CONTRAST_STRENGTH}) and sees the result
 * before committing a run. Contrast used to reproduce PIL's
 * {@code ImageOps.autocontrast(cutoff=1)}, which adapts to each photo's own
 * histogram and therefore cannot be dialled up or down — a knob the user can
 * turn is worth more here than one that guesses.
 *
 * <p>Brightness runs <em>before</em> contrast, the order a darkroom works in:
 * contrast pivots on the photo's mean luminance, so brightening afterwards would
 * shift the pivot the user just judged the contrast against.
 *
 * <p>White balance is estimated <em>once per series</em>, from its near-neutral
 * pixels — see {@link #estimateWhiteBalance}. Both restrictions were learned from
 * a real series: per-photo estimation turned a green close-up's white backdrop
 * magenta, and estimating from every pixel instead of the neutral ones fixed that
 * frame by tinting three good ones pink.
 */
public final class Retouch {

    /**
     * One retouching operation; each writes its own directory in the offer. The
     * constants are declared in pipeline order, which {@link #inputDir} walks
     * backwards to find a step's input.
     */
    public enum Mode {
        /** Gray-world white balance, into {@code white_balanced/}. */
        WHITE_BALANCE("white_balanced", "white balance"),
        /** Brightness at {@code BRIGHTNESS_STRENGTH}, into {@code brightened/}. */
        BRIGHTNESS("brightened", "brightness"),
        /**
         * Contrast at {@code CONTRAST_STRENGTH}, into {@code contrasted/}. The
         * directory name predates the rename from auto-contrast and stays, so
         * offers processed before it keep working.
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

    /** A strength that leaves the photo untouched, for either dial. */
    public static final double NEUTRAL_STRENGTH = 1.0;
    /** The flattest/darkest either slider (and its config key) allows. */
    public static final double MIN_STRENGTH = 0.5;
    /** The punchiest/brightest either slider (and its config key) allows. */
    public static final double MAX_STRENGTH = 2.0;
    /** A mild boost: enough that ticking Contrast does something visible. */
    public static final double DEFAULT_CONTRAST = 1.2;
    /**
     * Neutral. Unlike contrast, a photo off the turntable is usually exposed about
     * right, so the step defaults to changing nothing and waits to be dialled.
     */
    public static final double DEFAULT_BRIGHTNESS = NEUTRAL_STRENGTH;

    private static final float JPEG_QUALITY = 0.90f;

    /**
     * How far white balance may push one channel. Gray-world assumes the average
     * scene is neutral; for a series that genuinely is one strong color — a green
     * box photographed from twenty angles — the assumption is simply false, and
     * an unbounded correction would "fix" the object's real color away to gray.
     * A normal series lands far inside this (a real one measured 0.995/1.000/1.005),
     * so the limit only bites once the estimate has stopped being meaningful.
     */
    private static final double MAX_WB_GAIN = 1.25;

    /** Width the white-balance estimate decodes to; channel means need no more. */
    private static final int WB_SAMPLE_WIDTH = 400;

    /**
     * How colorful a pixel may be and still count as evidence about the light,
     * as {@code (max - min) / max}. At 0.15 a real turntable series kept about
     * three quarters of its pixels — the backdrop, the table and the item's
     * neutral parts — while excluding a green packaging front.
     */
    private static final double WB_MAX_SATURATION = 0.15;

    /** Below this the channel ratios are mostly noise. */
    private static final int WB_MIN_LEVEL = 30;

    /** Above this a channel may already have clipped, losing the ratio. */
    private static final int WB_MAX_LEVEL = 245;

    /**
     * How much of the series must be near-neutral before the estimate is
     * trusted. Under this there is no backdrop to read the light off — a frame
     * filled edge to edge with one saturated color — and guessing would be
     * worse than leaving the photos alone.
     */
    private static final double WB_MIN_NEUTRAL_FRACTION = 0.02;

    /**
     * The per-channel multipliers a white balance applies, estimated once for a
     * whole series.
     *
     * <p>Estimating them per photo — as this step used to — is a mistake of
     * principle, not just of robustness: a series is one item under one lamp, so
     * the illuminant is a <em>constant</em>, and re-estimating it per frame both
     * gives each frame of the same item a slightly different cast and lets a
     * single unusual frame wreck itself. A close-up of green packaging measured
     * gains of 1.403/0.777/1.000 on its own, turning the white backdrop magenta
     * and clipping 12% of the frame; estimated across its series it gets
     * 0.995/1.000/1.005 and clips 0.2%, keeping its green.
     */
    public record WhiteBalance(double red, double green, double blue) {

        /** Leaves the photo alone — what an empty or unreadable series falls back to. */
        public static final WhiteBalance NEUTRAL = new WhiteBalance(1.0, 1.0, 1.0);

        /** Whether these gains would visibly change a photo at 8-bit precision. */
        public boolean isNeutral() {
            return Math.abs(red - 1.0) < 0.002
                    && Math.abs(green - 1.0) < 0.002
                    && Math.abs(blue - 1.0) < 0.002;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "%.3f/%.3f/%.3f", red, green, blue);
        }
    }

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

        double strength = strengthFor(cfg, mode);
        if (mode != Mode.WHITE_BALANCE) {
            reporter.log(mode.label + " strength: " + strength + "x");
        }
        List<Path> offerDirs = listSubdirs(cfg.offersDir);
        int total = offerDirs.size();
        int index = 0;
        for (Path offerDir : offerDirs) {
            retouchOffer(offerDir, mode, strength, reporter);
            reporter.stepProgress(total == 0 ? 1.0 : (double) (++index) / total);
        }
        if (total == 0) {
            reporter.stepProgress(1.0);
        }
    }

    /** The dial {@code mode} runs at; white balance has none and ignores it. */
    public static double strengthFor(Config cfg, Mode mode) {
        return switch (mode) {
            case WHITE_BALANCE -> NEUTRAL_STRENGTH;
            case BRIGHTNESS -> cfg.brightnessStrength;
            case CONTRAST -> cfg.contrastStrength;
        };
    }

    /**
     * Retouches one offer. Idempotent: an output directory already holding one
     * entry per input photo counts as done and is skipped, so re-running the
     * step is safe. A skip ignores {@code strength} — output already on disk is
     * never silently rewritten; use Delete Output Files to redo it at a new
     * strength.
     */
    public static void retouchOffer(Path offerDir, Mode mode, double strength,
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

        // One estimate for the whole series, before a single photo is written:
        // the illuminant is the same in every frame, so estimating it per photo
        // would give each frame a different cast and let one odd frame wreck itself.
        WhiteBalance wb = WhiteBalance.NEUTRAL;
        if (mode == Mode.WHITE_BALANCE) {
            wb = estimateWhiteBalance(photos);
            reporter.log(offerDir.getFileName() + ": white balance gains " + wb
                    + (wb.isNeutral() ? " (already neutral)" : "")
                    + ", from " + photos.size() + " photos.");
        }

        Files.createDirectories(outputDir);
        for (Path photo : photos) {
            writeJpeg(process(photo, mode, strength, wb),
                    outputDir.resolve(photo.getFileName().toString()));
        }

        reporter.log(offerDir.getFileName() + ": " + mode.label + " applied to "
                + photos.size() + " photos.");
    }

    /**
     * A step's input: the output of the latest earlier step that has run, the
     * originals in {@code photos/} otherwise. Walking {@link Mode} backwards from
     * {@code mode} is what lets any of the three be unticked — the chain simply
     * closes over the gap.
     */
    private static Path inputDir(Path offerDir, Mode mode) {
        Mode[] modes = Mode.values();
        for (int i = mode.ordinal() - 1; i >= 0; i--) {
            Path dir = offerDir.resolve(modes[i].dirName);
            if (Files.isDirectory(dir)) {
                return dir;
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
    public static BufferedImage process(Path src, Mode mode, double strength)
            throws IOException {
        return process(src, mode, strength, WhiteBalance.NEUTRAL);
    }

    /** @param wb the series' gains; see {@link #apply(BufferedImage, Mode, double, WhiteBalance)} */
    public static BufferedImage process(Path src, Mode mode, double strength, WhiteBalance wb)
            throws IOException {
        BufferedImage img = ImageIO.read(src.toFile());
        if (img == null) {
            throw new IOException("Could not read image " + src);
        }
        return apply(Exif.applyOrientation(img, Exif.readOrientation(src)), mode, strength, wb);
    }

    /**
     * Applies the mode's operation to an already-decoded, already-upright image
     * and returns the result; {@code img} is left untouched. Split out of
     * {@link #process} so the UI's retouch preview can chain the modes in memory,
     * without writing the intermediates to disk as the pipeline does.
     *
     * @param strength the mode's dial ({@link #strengthFor}); white balance has
     *                 none and ignores it
     */
    public static BufferedImage apply(BufferedImage img, Mode mode, double strength) {
        return apply(img, mode, strength, WhiteBalance.NEUTRAL);
    }

    /**
     * @param wb the series' gains, used only by {@link Mode#WHITE_BALANCE}; the
     *           other modes ignore it. Estimated by
     *           {@link #estimateWhiteBalance} once per series, never per photo
     */
    public static BufferedImage apply(BufferedImage img, Mode mode, double strength,
                                      WhiteBalance wb) {
        int w = img.getWidth();
        int h = img.getHeight();
        int n = w * h;
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);

        switch (mode) {
            case WHITE_BALANCE -> applyWhiteBalance(px, wb);
            case BRIGHTNESS -> brightness(px, clampStrength(strength));
            case CONTRAST -> contrast(px, n, clampStrength(strength));
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, px, 0, w);
        return out;
    }

    /** Holds a strength inside the range the sliders offer; a garbled config cannot wreck a photo. */
    public static double clampStrength(double strength) {
        if (Double.isNaN(strength)) {
            return NEUTRAL_STRENGTH;
        }
        return Math.max(MIN_STRENGTH, Math.min(MAX_STRENGTH, strength));
    }

    /**
     * Brightness by a strength factor, reproducing PIL's
     * {@code ImageEnhance.Brightness}: every channel is scaled towards black, so
     * 1.0 is a no-op, below it darkens and above it brightens. Scaling (rather
     * than adding an offset) is what keeps the operation neutral in hue — the
     * three channels keep their ratios, so a brightened white item stays white
     * instead of drifting towards gray.
     *
     * <p>Highlights clip at 255, as they do in any exposure push: a blown-out
     * background cannot be brought back by dialling the slider down again, which
     * is exactly why the preview exists.
     */
    private static void brightness(int[] px, double strength) {
        int[] lut = new int[256];
        for (int v = 0; v < 256; v++) {
            lut[v] = clamp((int) Math.round(v * strength));
        }
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = lut[(p >> 16) & 0xFF];
            int g = lut[(p >> 8) & 0xFF];
            int b = lut[p & 0xFF];
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    /**
     * Estimates one white balance for a whole series: gray-world over the
     * <em>near-neutral</em> pixels of every photo, clamped to
     * {@link #MAX_WB_GAIN}.
     *
     * <p>Two restrictions, and both are load-bearing.
     *
     * <p><b>Across the series, not per photo.</b> The illuminant is a constant —
     * one item, one lamp — so estimating it per frame re-estimates a constant,
     * gives each frame of the same item a slightly different cast, and lets one
     * unusual frame wreck itself.
     *
     * <p><b>Only near-neutral pixels.</b> Gray-world is usually stated as "the
     * average scene is gray", but what it actually needs is that the
     * <em>neutral</em> parts of the scene average to gray; a strongly colored
     * subject is evidence about the subject, not about the light. Averaging every
     * pixel lets one green close-up drag the whole series warm — measured on a
     * real series, plain averaging left the three white-backdrop frames 13 levels
     * red-over-green, a visible pink tint on what had been neutral. Restricting to
     * low-saturation pixels dropped that to under 1 level while still fixing the
     * green frame. It also handles the case the series average cannot: a green box
     * shot from twenty angles is estimated from the white backdrop around it, so
     * the box keeps its color.
     *
     * <p>Pixels are also required to be mid-range: near-black ones carry mostly
     * noise in their ratios, and near-clipped ones have already lost the channel
     * that clipped.
     *
     * <p>The photos are decoded heavily subsampled ({@link #WB_SAMPLE_WIDTH}); a
     * channel mean over millions of pixels is the same number to three decimals
     * whether every pixel or every sixteenth is counted, and it keeps this pass
     * cheap enough to run before each offer and inside the preview.
     *
     * <p>Falls back to {@link WhiteBalance#NEUTRAL} rather than guessing when
     * there is too little neutral evidence to estimate from — the same
     * bail-conservatively rule {@link AutoCrop} follows. A photo that will not
     * decode is skipped, so one unreadable frame cannot cost the offer its white
     * balance.
     */
    public static WhiteBalance estimateWhiteBalance(List<Path> photos) {
        long sumR = 0;
        long sumG = 0;
        long sumB = 0;
        long neutral = 0;
        long total = 0;
        for (Path photo : photos) {
            try {
                BufferedImage img = readSubsampled(photo, WB_SAMPLE_WIDTH);
                int w = img.getWidth();
                int h = img.getHeight();
                int[] px = img.getRGB(0, 0, w, h, null, 0, w);
                total += px.length;
                for (int p : px) {
                    int r = (p >> 16) & 0xFF;
                    int g = (p >> 8) & 0xFF;
                    int b = p & 0xFF;
                    int max = Math.max(r, Math.max(g, b));
                    int min = Math.min(r, Math.min(g, b));
                    if (max < WB_MIN_LEVEL || max > WB_MAX_LEVEL) {
                        continue; // too dark to be reliable, or already clipping
                    }
                    if ((max - min) > WB_MAX_SATURATION * max) {
                        continue; // a colored subject, not the light
                    }
                    sumR += r;
                    sumG += g;
                    sumB += b;
                    neutral++;
                }
            } catch (IOException e) {
                // Skipped: see the javadoc.
            }
        }
        if (total == 0 || neutral < total * WB_MIN_NEUTRAL_FRACTION) {
            return WhiteBalance.NEUTRAL;
        }
        double mr = sumR / (double) neutral;
        double mg = sumG / (double) neutral;
        double mb = sumB / (double) neutral;
        double gray = (mr + mg + mb) / 3.0;
        return new WhiteBalance(
                clampGain(mr > 0 ? gray / mr : 1.0),
                clampGain(mg > 0 ? gray / mg : 1.0),
                clampGain(mb > 0 ? gray / mb : 1.0));
    }

    /** Holds one channel's gain within {@link #MAX_WB_GAIN} either way. */
    private static double clampGain(double gain) {
        if (Double.isNaN(gain) || Double.isInfinite(gain)) {
            return 1.0;
        }
        return Math.max(1.0 / MAX_WB_GAIN, Math.min(MAX_WB_GAIN, gain));
    }

    /** Decodes a JPEG subsampled to roughly {@code targetWidth}, upright. */
    private static BufferedImage readSubsampled(Path file, int targetWidth) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) {
                throw new IOException("Could not open image " + file);
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("No image reader for " + file);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                ImageReadParam param = reader.getDefaultReadParam();
                int sub = Math.max(1, reader.getWidth(0) / targetWidth);
                param.setSourceSubsampling(sub, sub, 0, 0);
                BufferedImage img = reader.read(0, param);
                return Exif.applyOrientation(img, Exif.readOrientation(file));
            } finally {
                reader.dispose();
            }
        }
    }

    /** Scales each channel by the series' gains. */
    private static void applyWhiteBalance(int[] px, WhiteBalance wb) {
        int[] lutR = gainLut(wb.red());
        int[] lutG = gainLut(wb.green());
        int[] lutB = gainLut(wb.blue());
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            px[i] = 0xFF000000
                    | (lutR[(p >> 16) & 0xFF] << 16)
                    | (lutG[(p >> 8) & 0xFF] << 8)
                    | lutB[p & 0xFF];
        }
    }

    /** One LUT per channel: 256 multiplications instead of one per pixel. */
    private static int[] gainLut(double gain) {
        int[] lut = new int[256];
        for (int v = 0; v < 256; v++) {
            lut[v] = clamp((int) Math.round(v * gain));
        }
        return lut;
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
