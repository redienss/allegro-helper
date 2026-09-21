package com.allegrohelper.core;

import com.allegrohelper.util.Json;
import com.allegrohelper.util.QrEncoder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stamps a scannable QR code — linking to a 360° video, an extended photo
 * gallery, anything a marketplace's own photo cap leaves out — onto one photo
 * of an offer.
 *
 * <p>Unlike every other retouching step, this one is <b>per-offer</b>, not
 * global: different items link to different videos or galleries, so each
 * offer keeps its own URL/label/size/position/target-photo in a sidecar file,
 * {@code qr.json}, written by the UI's QR Code tab and read only here — never
 * touched by Match or Import, so re-running those cannot lose it. An offer
 * with no {@code qr.json} is left untouched, the same as {@link AutoCrop}
 * declining a series it cannot confidently crop: a missing configuration is
 * not an error, just nothing to do.
 */
public final class QrCode {

    /** Not instantiable: the class is a namespace for {@link #runAll}. */
    private QrCode() {
    }

    /** One of the nine positions the QR code's backing plate can anchor to, as (x, y) fractions of the free space. */
    public enum Position {
        NW(0, 0), N(0.5, 0), NE(1, 0),
        W(0, 0.5), CENTER(0.5, 0.5), E(1, 0.5),
        SW(0, 1), S(0.5, 1), SE(1, 1);

        final double xFrac;
        final double yFrac;

        Position(double xFrac, double yFrac) {
            this.xFrac = xFrac;
            this.yFrac = yFrac;
        }
    }

    /**
     * One offer's QR configuration, as saved to {@code qr.json}.
     *
     * @param sizePx    the QR code's own module area, in pixels — the quiet
     *                  zone and any label sit outside this
     * @param photoIndex which photo of the offer's series (in the same order
     *                   {@link ImportPhotos#listJpegs} lists them) gets stamped
     */
    public record QrSettings(String url, String label, int sizePx, Position position, int photoIndex) {
    }

    // ------------------------------------------------------------- pipeline step

    /** Stamps every offer under {@code offers/} that has a saved {@code qr.json}. */
    public static void runAll(Config cfg, Reporter reporter) throws IOException {
        if (!Files.isDirectory(cfg.offersDir)) {
            reporter.log("Directory " + cfg.offersDir + " does not exist, no offers to QR-code.");
            reporter.stepProgress(1.0);
            return;
        }

        List<Path> offerDirs = listSubdirs(cfg.offersDir);
        int total = offerDirs.size();
        int index = 0;
        for (Path offerDir : offerDirs) {
            qrCodeOffer(offerDir, reporter);
            reporter.stepProgress(total == 0 ? 1.0 : (double) (++index) / total);
        }
        if (total == 0) {
            reporter.stepProgress(1.0);
        }
    }

    /**
     * Stamps one offer's configured photo into its {@code qr_coded/}
     * directory, copying the rest of the series through unchanged.
     * Idempotent: a {@code qr_coded/} already holding one entry per photo is
     * left alone. An offer with no {@code qr.json} (or an unreadable one) is
     * skipped without creating {@code qr_coded/} at all, so downstream steps
     * fall back to {@code cropped/} exactly as if this step had never run.
     */
    public static void qrCodeOffer(Path offerDir, Reporter reporter) throws IOException {
        Path inputDir = qrCodeInput(offerDir);
        Path outputDir = offerDir.resolve("qr_coded");
        String name = offerDir.getFileName().toString();

        List<Path> photos = inputDir == null ? List.of() : ImportPhotos.listJpegs(inputDir);
        if (photos.isEmpty()) {
            reporter.log(name + ": no photos to QR-code.");
            return;
        }
        if (Files.isDirectory(outputDir) && countEntries(outputDir) == photos.size()) {
            reporter.log(name + ": QR code already applied, skipping.");
            return;
        }

        QrSettings settings = readSettings(offerDir);
        if (settings == null) {
            reporter.log(name + ": no QR code configured, skipping.");
            return;
        }
        if (settings.url() == null || settings.url().isBlank()) {
            reporter.log(name + ": qr.json has no URL, skipping.");
            return;
        }

        int photoIndex = settings.photoIndex();
        if (photoIndex < 0 || photoIndex >= photos.size()) {
            reporter.log(name + ": configured photo #" + (photoIndex + 1) + " is out of range for "
                    + photos.size() + " photos, using the last one instead.");
            photoIndex = photos.size() - 1;
        }

        Files.createDirectories(outputDir);
        for (int i = 0; i < photos.size(); i++) {
            Path photo = photos.get(i);
            Path dest = outputDir.resolve(photo.getFileName().toString());
            if (i != photoIndex) {
                Files.copy(photo, dest, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                continue;
            }
            BufferedImage img = ImageIO.read(photo.toFile());
            if (img == null) {
                throw new IOException("Could not read image " + photo);
            }
            img = Exif.applyOrientation(img, Exif.readOrientation(photo));
            BufferedImage stamped = composite(img, settings);
            Retouch.writeJpeg(stamped, dest);
        }
        reporter.log(name + ": stamped a QR code onto photo #" + (photoIndex + 1) + " of "
                + photos.size() + ".");
    }

    /**
     * The most-processed input available, same chain {@link AutoCrop} and
     * {@link Ocr} walk: cropped, else contrasted, brightened, white-balanced,
     * the legacy {@code retouched/}, else the originals — this step runs
     * right after auto-crop, so {@code cropped/} is checked first.
     */
    static Path qrCodeInput(Path offerDir) {
        for (String dirName : new String[] {
                "cropped", Retouch.Mode.CONTRAST.dirName, Retouch.Mode.BRIGHTNESS.dirName,
                Retouch.Mode.WHITE_BALANCE.dirName, "retouched", "photos"}) {
            Path dir = offerDir.resolve(dirName);
            if (Files.isDirectory(dir)) {
                return dir;
            }
        }
        return null;
    }

    // ------------------------------------------------------------- qr.json

    /** Reads {@code offers/<id>/qr.json}, or null if it does not exist or cannot be parsed. */
    public static QrSettings readSettings(Path offerDir) {
        Path file = offerDir.resolve("qr.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            Map<String, Object> data = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            String url = String.valueOf(data.getOrDefault("url", ""));
            String label = String.valueOf(data.getOrDefault("label", ""));
            int sizePx = ((Number) data.getOrDefault("sizePx", 300.0)).intValue();
            Position position = Position.valueOf(String.valueOf(data.getOrDefault("position", "SE")));
            int photoIndex = ((Number) data.getOrDefault("photoIndex", 0.0)).intValue();
            return new QrSettings(url, label, sizePx, position, photoIndex);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Writes {@code offers/<id>/qr.json} — the UI's QR Code tab Save button. */
    public static void writeSettings(Path offerDir, QrSettings settings) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", settings.url());
        data.put("label", settings.label());
        data.put("sizePx", settings.sizePx());
        data.put("position", settings.position().name());
        data.put("photoIndex", settings.photoIndex());
        Files.writeString(offerDir.resolve("qr.json"), Json.write(data, true), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------- compositing

    /**
     * Draws {@code settings}'s QR code (plus a white backing plate, quiet
     * zone and optional label caption) onto a copy of {@code img}. Never
     * mutates {@code img}; never draws outside its bounds — the plate shrinks
     * to fit rather than overflow a photo too small for the requested size.
     */
    public static BufferedImage composite(BufferedImage img, QrSettings settings) {
        boolean[][] modules = QrEncoder.encode(settings.url());
        int n = modules.length;
        String label = settings.label() == null ? "" : settings.label().strip();

        int marginPx = (int) Math.round(0.03 * Math.min(img.getWidth(), img.getHeight()));
        int maxW = Math.max(4 * n, img.getWidth() - 2 * marginPx);
        int maxH = Math.max(4 * n, img.getHeight() - 2 * marginPx);

        int moduleSize = Math.max(1, settings.sizePx() / n);
        Plate plate = layoutPlate(n, moduleSize, label);
        while ((plate.width > maxW || plate.height > maxH) && moduleSize > 1) {
            moduleSize--;
            plate = layoutPlate(n, moduleSize, label);
        }

        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, null);

        int availW = Math.max(0, img.getWidth() - plate.width - 2 * marginPx);
        int availH = Math.max(0, img.getHeight() - plate.height - 2 * marginPx);
        int px = marginPx + (int) Math.round(settings.position().xFrac * availW);
        int py = marginPx + (int) Math.round(settings.position().yFrac * availH);
        px = Math.max(0, Math.min(px, img.getWidth() - plate.width));
        py = Math.max(0, Math.min(py, img.getHeight() - plate.height));

        g.setColor(Color.WHITE);
        int radius = Math.max(2, moduleSize);
        g.fillRoundRect(px, py, plate.width, plate.height, radius, radius);

        g.setColor(Color.BLACK);
        int quiet = moduleSize * 4;
        int qrOriginX = px + quiet;
        int qrOriginY = py + quiet;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                if (modules[r][c]) {
                    g.fillRect(qrOriginX + c * moduleSize, qrOriginY + r * moduleSize, moduleSize, moduleSize);
                }
            }
        }

        if (!label.isEmpty()) {
            g.setFont(plate.font);
            FontMetrics fm = g.getFontMetrics();
            int textY = qrOriginY + plate.qrPx + quiet + fm.getAscent();
            int textX = px + (plate.width - fm.stringWidth(label)) / 2;
            g.drawString(label, textX, textY);
        }
        g.dispose();
        return out;
    }

    /** The backing plate's geometry and (when a label is present) its font, for a given module pixel size. */
    private record Plate(int width, int height, int qrPx, Font font) {
    }

    private static Plate layoutPlate(int n, int moduleSize, String label) {
        int quiet = moduleSize * 4;
        int qrPx = moduleSize * n;
        int width = qrPx + quiet * 2;
        int height = qrPx + quiet * 2;
        Font font = null;
        if (!label.isEmpty()) {
            int fontSize = Math.max(8, Math.min(60, (int) Math.round(moduleSize * 2.2)));
            font = new Font(Font.SANS_SERIF, Font.PLAIN, fontSize);
            FontMetrics fm = fontMetrics(font);
            width = Math.max(width, fm.stringWidth(label) + quiet * 2);
            height += quiet + fm.getHeight();
        }
        return new Plate(width, height, qrPx, font);
    }

    private static FontMetrics fontMetrics(Font font) {
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scratch.createGraphics();
        try {
            g.setFont(font);
            return g.getFontMetrics();
        } finally {
            g.dispose();
        }
    }

    // ------------------------------------------------------------- filesystem helpers

    /** How many entries {@code dir} holds — the idempotence check's "already done" signal. */
    private static long countEntries(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.count();
        }
    }

    /** The offer directories under {@code dir}, in name order. */
    private static List<Path> listSubdirs(Path dir) throws IOException {
        List<Path> dirs = new java.util.ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory).forEach(dirs::add);
        }
        dirs.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
        return dirs;
    }
}
