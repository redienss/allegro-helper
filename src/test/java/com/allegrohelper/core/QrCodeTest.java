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
        QrCode.writeSettings(offerDir, new QrCode.QrSettings("", "Label", 100, QrCode.Position.SE, 0));

        QrCode.qrCodeOffer(offerDir, Reporter.stdout());

        assertFalse(Files.isDirectory(offerDir.resolve("qr_coded")));
    }

    @Test
    void stampsTheConfiguredPhotoAndCopiesTheRestUnchanged() throws IOException {
        Path photos = photosDir();
        writeJpeg(plainPhoto(400, 300, Color.GRAY), photos.resolve("a.jpg"));
        writeJpeg(plainPhoto(400, 300, Color.GRAY), photos.resolve("b.jpg"));
        QrCode.writeSettings(offerDir, new QrCode.QrSettings(
                "https://example.com/x", "Label", 120, QrCode.Position.SE, 1));

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
    void idempotentWhenOutputAlreadyMatchesInputCount() throws IOException {
        Path photos = photosDir();
        writeJpeg(plainPhoto(200, 200, Color.WHITE), photos.resolve("a.jpg"));
        Path out = Files.createDirectories(offerDir.resolve("qr_coded"));
        writeJpeg(plainPhoto(200, 200, Color.WHITE), out.resolve("a.jpg"));
        QrCode.writeSettings(offerDir, new QrCode.QrSettings(
                "https://example.com/x", "", 80, QrCode.Position.CENTER, 0));

        byte[] before = Files.readAllBytes(out.resolve("a.jpg"));
        QrCode.qrCodeOffer(offerDir, Reporter.stdout());
        byte[] after = Files.readAllBytes(out.resolve("a.jpg"));
        assertArrayEquals(before, after, "already-done output must be left alone");
    }

    @Test
    void clampsAnOutOfRangePhotoIndexToTheLastPhoto() throws IOException {
        Path photos = photosDir();
        writeJpeg(plainPhoto(300, 300, Color.GRAY), photos.resolve("a.jpg"));
        writeJpeg(plainPhoto(300, 300, Color.GRAY), photos.resolve("b.jpg"));
        QrCode.writeSettings(offerDir, new QrCode.QrSettings(
                "https://example.com/x", "", 100, QrCode.Position.SE, 99));

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
                "https://youtu.be/xElxEl5m9Wo", "YouTube 360º video", 250, QrCode.Position.NW, 3);
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

    // -------------------------------------------------------------- composite()

    @Test
    void compositeDrawsAWhiteQuietZoneAndDarkModules() {
        BufferedImage photo = plainPhoto(800, 600, new Color(128, 128, 128));
        QrCode.QrSettings settings = new QrCode.QrSettings(
                "https://example.com/product/12345", "", 200, QrCode.Position.CENTER, 0);

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
        QrCode.composite(photo, new QrCode.QrSettings("https://example.com", "", 150, QrCode.Position.SE, 0));
        assertEquals(before, photo.getRGB(10, 10), "composite must return a copy, not mutate the input");
    }

    @Test
    void positionAnchorsThePlateToTheRequestedCorner() {
        BufferedImage photo = plainPhoto(1000, 800, new Color(128, 128, 128));
        QrCode.QrSettings nw = new QrCode.QrSettings("https://example.com/nw", "", 150, QrCode.Position.NW, 0);
        QrCode.QrSettings se = new QrCode.QrSettings("https://example.com/se", "", 150, QrCode.Position.SE, 0);

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
                "https://example.com/x", "A label that would not otherwise fit", 5000,
                QrCode.Position.CENTER, 0);

        BufferedImage result = QrCode.composite(photo, settings);

        assertEquals(120, result.getWidth());
        assertEquals(100, result.getHeight());
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
