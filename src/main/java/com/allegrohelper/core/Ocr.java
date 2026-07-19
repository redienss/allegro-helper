package com.allegrohelper.core;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Extracts text visible in the offer photos (labels, nameplates, packaging)
 * into {@code ocr.txt}, which the describe step then feeds to the model.
 *
 * <p>OCR is done by shelling out to the {@code tesseract} CLI. The project's
 * zero-dependency rule is about Java libraries; tesseract is an external
 * program the app invokes, like the gvfs-mtp mount the import step relies on.
 * A vision LLM would read product photos better, but it costs a paid call per
 * offer — tesseract is free and local, which matches this app's
 * keep-the-overhead-low philosophy.
 *
 * <p>Tesseract is built for scans, not photographs, so each frame gets some
 * preparation (measured on real turntable series, where plain
 * {@code tesseract photo.jpg} returned only noise):
 *
 * <ul>
 *   <li><b>Two rotations, best kept.</b> An item often stands with its label
 *       upside down to the camera. Tesseract's own orientation detection
 *       (OSD) is unreliable on photos that are mostly object and little text,
 *       so both 0° and 180° are OCRed and the higher-confidence result wins.
 *       (90°/270° are skipped: on a turntable, horizontal text stays
 *       horizontal.)</li>
 *   <li><b>2x upscale.</b> Label text is small in frame; doubling roughly
 *       takes the effective resolution past tesseract's preferred 300 DPI and
 *       turned "no text" into confident reads in testing.</li>
 *   <li><b>Confidence filtering.</b> Words below confidence 60 are dropped,
 *       and a frame counts as textless unless some word reaches confidence 75
 *       with 4+ characters — glossy surfaces and textures otherwise produce
 *       an endless drizzle of confident two- and three-letter "words".</li>
 * </ul>
 *
 * <p>A frame takes seconds, so per-photo feedback matters: each result is
 * logged and appended to {@code ocr.txt} as it is recognized. A
 * {@code .ocr-in-progress} marker sits next to the file while an offer is
 * being written and is removed on completion — that is what separates a
 * finished {@code ocr.txt} (skip on re-run) from one an interrupted run left
 * behind (redo).
 *
 * <p>Photos are read from the most-processed directory available, the same
 * chain the gallery and auto-crop walk: {@code cropped/}, else
 * {@code contrasted/}, else {@code brightened/}, else {@code white_balanced/},
 * else the legacy {@code retouched/}, else the originals in {@code photos/}.
 * Ending at the originals is what lets OCR run with every retouching step
 * unticked — the step reads text off a photo, and none of the retouching is a
 * precondition for that. Originals still carry EXIF orientation, so the decode
 * applies it (see {@link #ocrPhoto}); without that a sideways frame would be
 * OCRed sideways, which is why this chain once stopped short of them.
 */
public final class Ocr {

    /**
     * Most-processed first; matches MainWindow.outputPhotoDir and AutoCrop's
     * chain, down to ending at the originals. Named through {@link Retouch.Mode}
     * so a renamed output directory cannot silently drop out of the chain.
     */
    private static final String[] INPUT_DIRS = {
        "cropped", Retouch.Mode.CONTRAST.dirName, Retouch.Mode.BRIGHTNESS.dirName,
        Retouch.Mode.WHITE_BALANCE.dirName, "retouched", "photos"};

    /** Kill a tesseract run that hangs on a pathological image. */
    private static final int TESSERACT_TIMEOUT_SECONDS = 120;

    /** Upscale factor applied before OCR. */
    private static final int SCALE = 2;

    /** Words below this tesseract confidence (0-100) are dropped. */
    private static final double WORD_CONF_MIN = 60;

    /** A frame only counts as having text if some word reaches this confidence... */
    private static final double GATE_CONF = 75;

    /** ...with at least this many characters. */
    private static final int GATE_LEN = 4;

    /** Not instantiable: the class is a namespace for {@link #runAll}. */
    private Ocr() {
    }

    /**
     * OCRs every offer's photos into {@code ocr.txt}, working in a temp
     * directory that is removed afterwards.
     *
     * @throws PipelineException if tesseract is missing, or if an offer's every
     *         photo failed — that means a broken setup, not textless photos
     */
    public static void runAll(Config cfg, Reporter reporter) throws IOException, PipelineException {
        requireTesseract();

        if (!Files.isDirectory(cfg.offersDir)) {
            reporter.log("Directory " + cfg.offersDir + " does not exist, no offers to OCR.");
            reporter.stepProgress(1.0);
            return;
        }

        List<Path> offerDirs = listSubdirs(cfg.offersDir);
        Path tmpDir = Files.createTempDirectory("allegro-helper-ocr");
        try {
            int total = offerDirs.size();
            int index = 0;
            for (Path offerDir : offerDirs) {
                final int offerIndex = index++;
                final int offerCount = total;
                ocrOffer(cfg, offerDir, tmpDir, reporter,
                        f -> reporter.stepProgress((offerIndex + f) / offerCount));
            }
        } finally {
            deleteRecursively(tmpDir);
        }
        reporter.stepProgress(1.0);
    }

    /** Per-offer progress callback (0..1 within the offer). */
    private interface OfferProgress {
        /** Reports how far this offer has got; the caller scales it into the step's progress. */
        void at(double fraction);
    }

    /** Fails the run up front, with an install hint, when tesseract is not available. */
    private static void requireTesseract() throws PipelineException {
        try {
            Process process = new ProcessBuilder("tesseract", "--version")
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new PipelineException("'tesseract --version' failed; check the installation.");
            }
        } catch (IOException e) {
            throw new PipelineException("The OCR step needs the tesseract CLI, which was not found.\n"
                    + "Install it with: sudo apt install tesseract-ocr tesseract-ocr-pol");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PipelineException("OCR was interrupted.");
        }
    }

    /**
     * OCRs one offer's photos, appending each frame's text to {@code ocr.txt}
     * as it goes and skipping text already read off an earlier frame — a
     * turntable series shows the same label many times over.
     *
     * <p>Idempotent through the {@code .ocr-in-progress} marker: a finished
     * {@code ocr.txt} (even an empty one) means the offer is done, while one
     * left behind by an interrupted run is redone from scratch.
     */
    private static void ocrOffer(Config cfg, Path offerDir, Path tmpDir, Reporter reporter,
                                 OfferProgress progress) throws IOException, PipelineException {
        Path ocrPath = offerDir.resolve("ocr.txt");
        // ocr.txt is appended to photo by photo (so a slow offer visibly makes
        // progress); the marker distinguishes a finished file from one left
        // behind by an interrupted run, which must be redone, not skipped.
        Path marker = offerDir.resolve(".ocr-in-progress");
        boolean interrupted = Files.exists(marker);
        if (Files.exists(ocrPath) && !interrupted) {
            reporter.log(offerDir.getFileName() + ": ocr.txt already exists, skipping.");
            progress.at(1.0);
            return;
        }
        Path inputDir = inputDir(offerDir);
        if (inputDir == null) {
            // Now only reachable when the offer has no photos at all: the chain
            // ends at photos/, so retouching is no longer a precondition.
            reporter.log(offerDir.getFileName() + ": no photos to OCR, skipping.");
            progress.at(1.0);
            return;
        }
        if (interrupted) {
            reporter.log(offerDir.getFileName()
                    + ": a previous OCR run was interrupted, starting this offer over.");
        }

        List<Path> photos = ImportPhotos.listJpegs(inputDir);
        Files.writeString(marker, "", StandardCharsets.UTF_8);
        Files.writeString(ocrPath, "", StandardCharsets.UTF_8);
        // A turntable series shows the same labels on many frames; keep one copy.
        Set<String> seenBlocks = new LinkedHashSet<>();
        int failed = 0;
        int done = 0;
        for (Path photo : photos) {
            String label = offerDir.getFileName() + ": OCR " + photo.getFileName()
                    + " (" + (done + 1) + "/" + photos.size() + "): ";
            try {
                String text = ocrPhoto(cfg, photo, tmpDir);
                if (text.isEmpty()) {
                    reporter.log(label + "no text.");
                } else if (!seenBlocks.add(normalize(text))) {
                    reporter.log(label + "same text as an earlier frame.");
                } else {
                    Files.writeString(ocrPath,
                            "[" + photo.getFileName() + "]\n" + text + "\n\n",
                            StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                    reporter.log(label + "found \"" + snippet(text) + "\"");
                }
            } catch (IOException e) {
                failed++;
                reporter.log(label + "failed: " + e.getMessage());
            }
            progress.at((double) (++done) / photos.size());
        }
        if (!photos.isEmpty() && failed == photos.size()) {
            // Every single frame failing means a broken setup, not textless
            // photos. The marker stays behind, so the next run redoes the offer
            // instead of trusting the (likely empty) ocr.txt.
            throw new PipelineException("OCR failed on all " + failed + " photos of "
                    + offerDir.getFileName() + "; see the log above.");
        }

        // Kept even when empty, so re-runs skip the offer (idempotence).
        Files.delete(marker);
        reporter.log(offerDir.getFileName() + ": " + (seenBlocks.isEmpty()
                ? "no text found in " + photos.size() + " photos, wrote empty ocr.txt."
                : "wrote ocr.txt (" + seenBlocks.size() + " unique text blocks from "
                        + photos.size() + " photos)."));
    }

    /** The first recognized line, shortened — enough for the log to show liveness. */
    private static String snippet(String text) {
        String first = text.lines().findFirst().orElse("");
        String more = text.lines().count() > 1 ? " …" : "";
        return (first.length() <= 40 ? first : first.substring(0, 40) + "…") + more;
    }

    /** The most-processed photo directory of the offer, or null if none exists yet. */
    static Path inputDir(Path offerDir) {
        for (String name : INPUT_DIRS) {
            Path dir = offerDir.resolve(name);
            if (Files.isDirectory(dir)) {
                return dir;
            }
        }
        return null;
    }

    /**
     * OCRs one photo at 0° and 180° (upscaled), returning the better result's
     * text.
     *
     * <p>EXIF orientation is applied on decode, unconditionally: with every
     * retouching step unticked the input is the untouched original, which still
     * carries the tag that ImageIO ignores, and tesseract would then read a
     * sideways frame. Pipeline-written inputs carry no EXIF metadata, so the
     * call is a no-op for them — the same reasoning as {@code Retouch.process}.
     */
    private static String ocrPhoto(Config cfg, Path photo, Path tmpDir) throws IOException {
        BufferedImage source = ImageIO.read(photo.toFile());
        if (source == null) {
            throw new IOException("could not decode the image");
        }
        source = Exif.applyOrientation(source, Exif.readOrientation(photo));
        Variant best = Variant.EMPTY;
        for (int quadrants : new int[]{0, 2}) {
            Path prepared = tmpDir.resolve("frame-rot" + (quadrants * 90) + ".jpg");
            writeJpeg(render(source, quadrants), prepared);
            Variant variant = parseTsv(runTesseractTsv(cfg, prepared));
            if (variant.score > best.score) {
                best = variant;
            }
        }
        return best.text;
    }

    /** Upscales by {@link #SCALE} and rotates by the given number of quadrants. */
    private static BufferedImage render(BufferedImage source, int quadrants) {
        int w = source.getWidth() * SCALE;
        int h = source.getHeight() * SCALE;
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        AffineTransform t = new AffineTransform();
        if (quadrants != 0) {
            t.quadrantRotate(quadrants, w / 2.0, h / 2.0);
        }
        t.scale(SCALE, SCALE);
        g.drawImage(source, t, null);
        g.dispose();
        return result;
    }

    /** Writes a high-quality JPEG (default quality visibly blurs small label text). */
    private static void writeJpeg(BufferedImage image, Path target) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.9f);
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    /**
     * Runs the tesseract CLI on one image and returns its TSV output. Killed
     * after {@link #TESSERACT_TIMEOUT_SECONDS}, so one pathological frame cannot
     * hang the run; its stderr (DPI and quality warnings on every photo) is
     * discarded.
     */
    private static String runTesseractTsv(Config cfg, Path image) throws IOException {
        Process process = new ProcessBuilder(
                "tesseract", image.toString(), "stdout", "-l", cfg.ocrLanguages, "tsv")
                .redirectError(ProcessBuilder.Redirect.DISCARD) // DPI/quality warnings
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!process.waitFor(TESSERACT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("tesseract timed out after " + TESSERACT_TIMEOUT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("interrupted");
        }
        if (process.exitValue() != 0) {
            throw new IOException("tesseract exit code " + process.exitValue());
        }
        return output;
    }

    /** One rotation's reading of a frame: the text kept, and the score it is judged by. */
    private record Variant(String text, double score) {
        /** No usable text — also the starting point the two rotations are compared against. */
        static final Variant EMPTY = new Variant("", 0);
    }

    /**
     * Reconstructs confident text from tesseract's TSV output (level 5 rows are
     * words; columns: level, page, block, par, line, word, x, y, w, h, conf,
     * text). The score — summed confidence×length of kept words — is what the
     * two rotations of a frame are compared by.
     */
    private static Variant parseTsv(String tsv) {
        Map<String, StringBuilder> lines = new LinkedHashMap<>();
        double score = 0;
        boolean gatePassed = false;
        for (String row : tsv.split("\n")) {
            String[] f = row.split("\t", -1);
            if (f.length < 12 || !"5".equals(f[0])) {
                continue;
            }
            double conf;
            try {
                conf = Double.parseDouble(f[10]);
            } catch (NumberFormatException e) {
                continue;
            }
            String word = f[11].strip();
            if (conf < WORD_CONF_MIN || word.codePoints().noneMatch(Character::isLetterOrDigit)) {
                continue;
            }
            score += conf * word.length();
            if (conf >= GATE_CONF && word.length() >= GATE_LEN) {
                gatePassed = true;
            }
            StringBuilder line = lines.computeIfAbsent(
                    f[2] + ":" + f[3] + ":" + f[4], k -> new StringBuilder());
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(word);
        }
        if (!gatePassed) {
            return Variant.EMPTY;
        }
        StringBuilder text = new StringBuilder();
        for (StringBuilder line : lines.values()) {
            text.append(line).append('\n');
        }
        return new Variant(text.toString().strip(), score);
    }

    /** Case- and whitespace-insensitive form, for deduplicating repeated frames. */
    private static String normalize(String text) {
        return text.toLowerCase().replaceAll("\\s+", " ");
    }

    /** Removes the step's temp directory; leftovers there are harmless, so failures are ignored. */
    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // Leftovers in the temp dir are harmless.
                }
            });
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
