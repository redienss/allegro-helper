package com.allegrohelper.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The QR Code step: idempotence, the "no {@code qr.json} means nothing to do"
 * skip that leaves {@code qr_coded/} uncreated (mirroring {@link AutoCrop}'s
 * decline behavior), the {@code qr.json} sidecar round-trip, and the
 * compositing geometry (quiet zone, position anchoring, shrink-to-fit).
 */
class QrCodeTest {

    @TempDir
    Path offerDir;

    private static BufferedImage plainPhoto(int w, int h, Color color) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static void writeJpeg(BufferedImage img, Path dest) throws IOException {
        ImageIO.write(img, "jpg", dest.toFile());
    }

    private Path photosDir() throws IOException {
        return Files.createDirectories(offerDir.resolve("photos"));
    }

    private static long count(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.count();
        }
    }

    // -------------------------------------------------------------- qrCodeOffer

    @Test
    void skipsAnOfferWithNoQrJsonAndLeavesNoOutputDir() throws IOException {
        Path photos = photosDir();
        writeJpeg(plainPhoto(400, 300, Color.GRAY), photos.resolve("a.jpg"));

        QrCode.qrCodeOffer(offerDir, Reporter.stdout());

        assertFalse(Files.isDirectory(offerDir.resolve("qr_coded")),
                "no qr.json means nothing to do, not an empty output dir");
    }

    @Test
    void skipsAnOfferWhoseQrJsonHasNoUrl() throws IOException {
        Path photos = photosDir();
        writeJpeg(plainPhoto(400, 300, Color.GRAY), photos.resolve("a.jpg"));
        QrCode.writeSettings(offerDir, new QrCode.QrSettings("", "Label", 100, 40, 0, 24, QrCode.LabelPosition.BELOW, QrCode.Position.SE, 0));

        QrCode.qrCodeOffer(offerDir, Reporter.stdout());

        assertFalse(Files.isDirectory(offerDir.resolve("qr_coded")));
    }

    @Test
    void stampsTheConfiguredPhotoAndCopiesTheRestUnchanged() throws IOException {
        Path photos = photosDir();
        writeJpeg(plainPhoto(400, 300, Color.GRAY), photos.resolve("a.jpg"));
        writeJpeg(plainPhoto(400, 300, Color.GRAY), photos.resolve("b.jpg"));
        QrCode.writeSettings(offerDir, new QrCode.QrSettings(
                "https://example.com/x", "Label", 120, 40, 0, 24, QrCode.LabelPosition.BELOW, QrCode.Position.SE, 1));

        QrCode.qrCodeOffer(offerDir, Reporter.stdout());

        Path out = offerDir.resolve("qr_coded");
        assertTrue(Files.isDirectory(out));
        assertEquals(2, count(out));
        assertArrayEquals(Files.readAllBytes(photos.resolve("a.jpg")), Files.readAllBytes(out.resolve("a.jpg")),
                "the photo that was not targeted must be a byte-identical copy");

        BufferedImage stamped = ImageIO.read(out.resolve("b.jpg").toFile());
        assertEquals(400, stamped.getWidth());
        assertEquals(300, stamped.getHeight());
        assertFalse(imagesEqual(plainPhoto(400, 300, Color.GRAY), stamped), "the targeted photo must have changed");
    }

    @Test
    void idempotentWhenOutputAlreadyMatchesInputCountAndSettings() throws IOException {
        Path photos = photosDir();
        writeJpeg(plainPhoto(200, 200, Color.WHITE), photos.resolve("a.jpg"));
        QrCode.writeSettings(offerDir, new QrCode.QrSettings(
                "https://example.com/x", "", 80, 40, 0, 24, QrCode.LabelPosition.BELOW, QrCode.Position.CENTER, 0));

        QrCode.qrCodeOffer(offerDir, Reporter.stdout());
        Path out = offerDir.resolve("qr_coded");
        byte[] before = Files.readAllBytes(out.resolve("a.jpg"));
        QrCode.qrCodeOffer(offerDir, Reporter.stdout());
        byte[] after = Files.readAllBytes(out.resolve("a.jpg"));
        assertArrayEquals(before, after, "already-done output must be left alone when settings are unchanged");
    }

    @Test
    void reappliesWhenSettingsChangeEvenThoughOutputCountStillMatches() throws IOException {
        Path photos = photosDir();
        writeJpeg(plainPhoto(200, 200, Color.WHITE), photos.resolve("a.jpg"));
        QrCode.writeSettings(offerDir, new QrCode.QrSettings(
                "https://example.com/x", "First label", 80, 40, 0, 24, QrCode.LabelPosition.BELOW, QrCode.Position.CENTER, 0));
        QrCode.qrCodeOffer(offerDir, Reporter.stdout());
        Path out = offerDir.resolve("qr_coded");
        byte[] first = Files.readAllBytes(out.resolve("a.jpg"));

        QrCode.writeSettings(offerDir, new QrCode.QrSettings(
                "https://example.com/x", "A very different second label", 80, 40, 0, 24, QrCode.LabelPosition.BELOW, QrCode.Position.CENTER, 0));
        QrCode.qrCodeOffer(offerDir, Reporter.stdout());
        byte[] second = Files.readAllBytes(out.resolve("a.jpg"));

        assertFalse(java.util.Arrays.equals(first, second),
                "changed qr.json settings must re-stamp the photo, not be skipped as already-applied");
    }

    /**
     * Regression test: re-running into an already-populated {@code qr_coded/}
     * used to throw {@code FileAlreadyExistsException} on the photos copied
     * through <em>unchanged</em> (the plain {@code Files.copy}, unlike the
     * stamped photo's {@code Retouch.writeJpeg}, did not pass {@code
     * REPLACE_EXISTING}) — invisible with a single-photo offer, since there
     * were no unchanged copies to collide with.
     */
    @Test
    void reappliesAcrossMultiplePhotosWithoutErrorWhenSettingsChange() throws IOException {
        Path photos = photosDir();
        writeJpeg(plainPhoto(200, 200, Color.WHITE), photos.resolve("a.jpg"));
        writeJpeg(plainPhoto(200, 200, Color.WHITE), photos.resolve("b.jpg"));
        QrCode.writeSettings(offerDir, new QrCode.QrSettings(
                "https://example.com/x", "First label", 80, 40, 0, 24, QrCode.LabelPosition.BELOW, QrCode.Position.CENTER, 1));
        QrCode.qrCodeOffer(offerDir, Reporter.stdout());

        QrCode.writeSettings(offerDir, new QrCode.QrSettings(
                "https://example.com/x", "A very different second label", 80, 40, 0, 24, QrCode.LabelPosition.BELOW, QrCode.Position.CENTER, 1));
        QrCode.qrCodeOffer(offerDir, Reporter.stdout());

        Path out = offerDir.resolve("qr_coded");
        assertEquals(2, count(out));
        assertArrayEquals(Files.readAllBytes(photos.resolve("a.jpg")), Files.readAllBytes(out.resolve("a.jpg")),
                "the untargeted photo must still be a byte-identical copy after the re-run");
    }

    @Test
    void clampsAnOutOfRangePhotoIndexToTheLastPhoto() throws IOException {
        Path photos = photosDir();
        writeJpeg(plainPhoto(300, 300, Color.GRAY), photos.resolve("a.jpg"));
        writeJpeg(plainPhoto(300, 300, Color.GRAY), photos.resolve("b.jpg"));
        QrCode.writeSettings(offerDir, new QrCode.QrSettings(
                "https://example.com/x", "", 100, 40, 0, 24, QrCode.LabelPosition.BELOW, QrCode.Position.SE, 99));

        QrCode.qrCodeOffer(offerDir, Reporter.stdout());

        Path out = offerDir.resolve("qr_coded");
        assertArrayEquals(Files.readAllBytes(photos.resolve("a.jpg")), Files.readAllBytes(out.resolve("a.jpg")));
        BufferedImage stampedB = ImageIO.read(out.resolve("b.jpg").toFile());
        assertFalse(imagesEqual(plainPhoto(300, 300, Color.GRAY), stampedB),
                "the last photo must have been stamped instead of the out-of-range index");
    }

    // -------------------------------------------------------------- qr.json

    @Test
    void writeSettingsRoundTripsThroughReadSettings() throws IOException {
        QrCode.QrSettings settings = new QrCode.QrSettings(
                "https://youtu.be/xElxEl5m9Wo", "YouTube 360º video", 250, 18, 6, 32, QrCode.LabelPosition.BELOW, QrCode.Position.NW, 3);
        QrCode.writeSettings(offerDir, settings);
        assertEquals(settings, QrCode.readSettings(offerDir));
    }

    @Test
    void readSettingsReturnsNullWhenTheFileIsMissing() {
        assertNull(QrCode.readSettings(offerDir));
    }

    @Test
    void readSettingsReturnsNullForMalformedJson() throws IOException {
        Files.writeString(offerDir.resolve("qr.json"), "{ not json");
        assertNull(QrCode.readSettings(offerDir));
    }

    /**
     * A {@code qr.json} written before {@code paddingPx}/{@code borderPx}
     * existed must still load, defaulting to no border and the padding that
     * approximates this step's old always-4-modules quiet zone.
     */
    @Test
    void readSettingsDefaultsPaddingAndBorderForAPreExistingFile() throws IOException {
        Files.writeString(offerDir.resolve("qr.json"), """
                {
                  "url": "https://example.com/x",
                  "label": "",
                  "sizePx": 200,
                  "labelFontSize": 24,
                  "labelPosition": "BELOW",
                  "position": "SE",
                  "photoIndex": 0
                }
                """);

        QrCode.QrSettings loaded = QrCode.readSettings(offerDir);

        assertEquals(QrCode.DEFAULT_PADDING_PX, loaded.paddingPx());
        assertEquals(0, loaded.borderPx());
    }

    // -------------------------------------------------------------- composite()

    @Test
    void compositeDrawsAWhiteQuietZoneAndDarkModules() {
        BufferedImage photo = plainPhoto(800, 600, new Color(128, 128, 128));
        QrCode.QrSettings settings = new QrCode.QrSettings(
                "https://example.com/product/12345", "", 200, 40, 0, 24, QrCode.LabelPosition.BELOW, QrCode.Position.CENTER, 0);

        BufferedImage result = QrCode.composite(photo, settings);

        boolean sawWhite = false;
        boolean sawBlack = false;
        for (int y = 0; y < result.getHeight(); y++) {
            for (int x = 0; x < result.getWidth(); x++) {
                int rgb = result.getRGB(x, y) & 0xFFFFFF;
                sawWhite |= rgb == 0xFFFFFF;
                sawBlack |= rgb == 0x000000;
            }
        }
        assertTrue(sawWhite, "the white backing plate/quiet zone must be visible");
        assertTrue(sawBlack, "the QR code's dark modules must be visible");
    }

    @Test
    void compositeNeverMutatesTheInputImage() {
        BufferedImage photo = plainPhoto(500, 400, Color.RED);
        int before = photo.getRGB(10, 10);
        QrCode.composite(photo, new QrCode.QrSettings("https://example.com", "", 150, 40, 0, 24, QrCode.LabelPosition.BELOW, QrCode.Position.SE, 0));
        assertEquals(before, photo.getRGB(10, 10), "composite must return a copy, not mutate the input");
    }

    @Test
    void positionAnchorsThePlateToTheRequestedCorner() {
        BufferedImage photo = plainPhoto(1000, 800, new Color(128, 128, 128));
        QrCode.QrSettings nw = new QrCode.QrSettings("https://example.com/nw", "", 150, 40, 0, 24, QrCode.LabelPosition.BELOW, QrCode.Position.NW, 0);
        QrCode.QrSettings se = new QrCode.QrSettings("https://example.com/se", "", 150, 40, 0, 24, QrCode.LabelPosition.BELOW, QrCode.Position.SE, 0);

        BufferedImage resultNw = QrCode.composite(photo, nw);
        BufferedImage resultSe = QrCode.composite(photo, se);

        assertTrue(hasWhitePixelNear(resultNw, 60, 60), "NW plate should sit near the top-left corner");
        assertFalse(hasWhitePixelNear(resultNw, photo.getWidth() - 60, photo.getHeight() - 60),
                "NW plate should not reach the opposite corner");
        assertTrue(hasWhitePixelNear(resultSe, photo.getWidth() - 60, photo.getHeight() - 60),
                "SE plate should sit near the bottom-right corner");
        assertFalse(hasWhitePixelNear(resultSe, 60, 60),
                "SE plate should not reach the opposite corner");
    }

    @Test
    void shrinksThePlateRatherThanOverflowingATinyPhoto() {
        BufferedImage photo = plainPhoto(120, 100, new Color(128, 128, 128));
        QrCode.QrSettings settings = new QrCode.QrSettings(
                "https://example.com/x", "A label that would not otherwise fit", 5000, 40, 0, 24,
                QrCode.LabelPosition.BELOW, QrCode.Position.CENTER, 0);

        BufferedImage result = QrCode.composite(photo, settings);

        assertEquals(120, result.getWidth());
        assertEquals(100, result.getHeight());
    }

    @Test
    void labelFontSizeIsIndependentOfQrSize() {
        BufferedImage photo = plainPhoto(1600, 1200, new Color(128, 128, 128));
        QrCode.QrSettings small = new QrCode.QrSettings(
                "https://example.com/x", "Scan me", 300, 40, 0, 12, QrCode.LabelPosition.BELOW, QrCode.Position.CENTER, 0);
        QrCode.QrSettings large = new QrCode.QrSettings(
                "https://example.com/x", "Scan me", 300, 40, 0, 80, QrCode.LabelPosition.BELOW, QrCode.Position.CENTER, 0);

        int[] smallBox = whiteBoundingBox(QrCode.composite(photo, small));
        int[] largeBox = whiteBoundingBox(QrCode.composite(photo, large));

        assertTrue(largeBox[0] > smallBox[0], "a larger label font size must widen the backing plate");
        assertTrue(largeBox[1] > smallBox[1], "a larger label font size must heighten the backing plate");
    }

    @Test
    void labelPositionMovesTheCaptionAboveOrBelowTheQrCode() {
        BufferedImage photo = plainPhoto(800, 800, new Color(128, 128, 128));
        QrCode.QrSettings below = new QrCode.QrSettings(
                "https://example.com/x", "Scan me", 200, 40, 0, 24, QrCode.LabelPosition.BELOW, QrCode.Position.CENTER, 0);
        QrCode.QrSettings above = new QrCode.QrSettings(
                "https://example.com/x", "Scan me", 200, 40, 0, 24, QrCode.LabelPosition.ABOVE, QrCode.Position.CENTER, 0);

        int[] belowQrTop = blackBoundingBox(QrCode.composite(photo, below));
        int[] aboveQrTop = blackBoundingBox(QrCode.composite(photo, above));

        assertTrue(aboveQrTop[1] > belowQrTop[1],
                "moving the label above the QR code must push the QR modules further down");
    }

    @Test
    void borderDrawsABlackFrameCloserToThePlateEdgeThanTheModulesAlone() {
        BufferedImage photo = plainPhoto(800, 600, new Color(128, 128, 128));
        QrCode.QrSettings noBorder = new QrCode.QrSettings(
                "https://example.com/product/12345", "", 200, 40, 0, 24, QrCode.LabelPosition.BELOW, QrCode.Position.CENTER, 0);
        QrCode.QrSettings withBorder = new QrCode.QrSettings(
                "https://example.com/product/12345", "", 200, 40, 8, 24, QrCode.LabelPosition.BELOW, QrCode.Position.CENTER, 0);

        int[] noBorderBlack = blackBoundingBox(QrCode.composite(photo, noBorder));
        int[] withBorderBlack = blackBoundingBox(QrCode.composite(photo, withBorder));

        assertTrue(withBorderBlack[1] < noBorderBlack[1],
                "an outer border must put black pixels closer to the plate's top edge than the QR modules alone, "
                        + "which sit behind the (unchanged) inner padding");
    }

    /**
     * Regression test: the border's rounded rect used to reuse the plate's
     * own corner radius on its own (smaller, inset) bounds, which over-rounds
     * that smaller rect and pulls its corner in past the plate's own — a
     * sliver of the white plate then shows outside the black stroke, right at
     * the rounded corner. Anchored to {@code NW} so the plate's top-left true
     * corner sits exactly on the diagonal scanned below.
     *
     * <p>Checks the transition trends toward black rather than requiring it
     * to land on pure black: the plate/border edges are antialiased (see
     * {@link QrCode#composite}), so the first pixel past the background is a
     * blend, not a flat color — but a blend leaking white in would read
     * lighter than the (mid-gray) background, never darker.
     */
    @Test
    void borderFullyEnclosesTheRoundedCornerWithNoWhiteGapAtTheDiagonal() {
        BufferedImage photo = plainPhoto(800, 600, new Color(128, 128, 128));
        QrCode.QrSettings settings = new QrCode.QrSettings(
                "https://e.co", "", 600, 40, 12, 24, QrCode.LabelPosition.BELOW, QrCode.Position.NW, 0);

        BufferedImage result = QrCode.composite(photo, settings);

        int background = new Color(128, 128, 128).getRGB() & 0xFFFFFF;
        for (int i = 0; i < Math.min(result.getWidth(), result.getHeight()); i++) {
            int rgb = result.getRGB(i, i) & 0xFFFFFF;
            if (rgb != background) {
                int red = (rgb >> 16) & 0xFF;
                assertTrue(red <= 128,
                        "the first pixel reached along the diagonal into the plate's rounded corner must trend "
                                + "toward the black border, not the white plate leaking out past it (red=" + red
                                + ")");
                return;
            }
        }
        fail("the diagonal scan never left the photo's background color before reaching the image edge");
    }

    /**
     * Anchored to {@code NW} rather than {@code CENTER}: centering
     * re-derives the plate's top-left corner from its (now larger) size, so
     * a wider padding pushes the corner up by exactly as much as it pushes
     * the modules down, canceling out. Anchoring to a fixed corner isolates
     * the effect padding is actually supposed to have.
     */
    @Test
    void paddingWidensTheQuietZoneBetweenTheModulesAndThePlateEdge() {
        BufferedImage photo = plainPhoto(800, 600, new Color(128, 128, 128));
        QrCode.QrSettings tightPadding = new QrCode.QrSettings(
                "https://example.com/product/12345", "", 200, 4, 0, 24, QrCode.LabelPosition.BELOW, QrCode.Position.NW, 0);
        QrCode.QrSettings widePadding = new QrCode.QrSettings(
                "https://example.com/product/12345", "", 200, 60, 0, 24, QrCode.LabelPosition.BELOW, QrCode.Position.NW, 0);

        int[] tightBlack = blackBoundingBox(QrCode.composite(photo, tightPadding));
        int[] wideBlack = blackBoundingBox(QrCode.composite(photo, widePadding));

        assertTrue(wideBlack[1] > tightBlack[1],
                "a wider inner padding must push the QR modules further from the plate's top edge");
    }

    /** The {left, top} of the smallest box enclosing every pure-black pixel — the QR modules' footprint. */
    private static int[] blackBoundingBox(BufferedImage img) {
        int minX = img.getWidth();
        int minY = img.getHeight();
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) == 0x000000) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                }
            }
        }
        return new int[] {minX, minY};
    }

    /** The {width, height} of the smallest box enclosing every pure-white pixel — the backing plate's footprint. */
    private static int[] whiteBoundingBox(BufferedImage img) {
        int minX = img.getWidth();
        int minY = img.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) == 0xFFFFFF) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return new int[] {maxX - minX + 1, maxY - minY + 1};
    }

    private static boolean hasWhitePixelNear(BufferedImage img, int cx, int cy) {
        for (int dy = -40; dy <= 40; dy++) {
            for (int dx = -40; dx <= 40; dx++) {
                int x = cx + dx;
                int y = cy + dy;
                if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
                    continue;
                }
                if ((img.getRGB(x, y) & 0xFFFFFF) == 0xFFFFFF) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean imagesEqual(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }
}
