package com.allegrohelper.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Estimating one white balance for a whole series, from its near-neutral pixels.
 *
 * <p>The case that forced this: turntable shots on a white backdrop plus one
 * close-up of green packaging. Estimated per photo, the green frame asked for a
 * 40% red boost and a 22% green cut, turning its white background magenta and
 * clipping 12% of the frame.
 *
 * <p>Note what the first attempt got wrong, since these tests exist to stop it
 * coming back: averaging <em>every</em> pixel of the series fixed the green
 * frame but dragged the three neutral frames 13 levels red-over-green, a visible
 * pink tint on photos that had been fine. Only the neutral pixels are evidence
 * about the light.
 */
class WhiteBalanceEstimateTest {

    @TempDir
    Path dir;

    /**
     * A photo shaped like a real one: a backdrop filling most of the frame, with
     * a subject patch over the middle third.
     */
    private Path photo(String name, int[] backdrop, int[] subject) throws IOException {
        BufferedImage img = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 600; y++) {
            for (int x = 0; x < 600; x++) {
                int[] c = (subject != null && x > 200 && x < 400 && y > 200 && y < 400)
                        ? subject : backdrop;
                img.setRGB(x, y, (c[0] << 16) | (c[1] << 8) | c[2]);
            }
        }
        Path p = dir.resolve(name);
        ImageIO.write(img, "jpg", p.toFile());
        return p;
    }

    private static final int[] WHITE = {210, 210, 210};
    private static final int[] GREEN = {50, 150, 60};

    @Test
    void aNeutralSeriesAsksForNoCorrection() throws IOException {
        List<Path> photos = List.of(
                photo("a.jpg", WHITE, null),
                photo("b.jpg", new int[] {180, 180, 180}, null));
        assertTrue(Retouch.estimateWhiteBalance(photos).isNeutral(),
                "already neutral, got " + Retouch.estimateWhiteBalance(photos));
    }

    @Test
    void aCastOnTheBackdropIsCorrected() throws IOException {
        // A slightly blue backdrop - a real cast, low saturation, so it counts
        // as evidence about the light.
        List<Path> photos = List.of(
                photo("a.jpg", new int[] {195, 205, 220}, null),
                photo("b.jpg", new int[] {195, 205, 220}, null));
        Retouch.WhiteBalance wb = Retouch.estimateWhiteBalance(photos);
        assertTrue(wb.red() > 1.01, "red lifted, got " + wb);
        assertTrue(wb.blue() < 0.99, "blue pulled down, got " + wb);
    }

    @Test
    void aSaturatedSubjectIsNotMistakenForTheLight() throws IOException {
        // The heart of it: a green subject on a neutral backdrop must leave the
        // estimate neutral. The subject is evidence about the subject.
        List<Path> photos = List.of(photo("green-on-white.jpg", WHITE, GREEN));
        Retouch.WhiteBalance wb = Retouch.estimateWhiteBalance(photos);
        assertTrue(wb.isNeutral(),
                "a green subject on a white backdrop should not tint anything, got " + wb);
    }

    @Test
    void oneStronglyColouredFrameDoesNotShiftTheSeries() throws IOException {
        // The reported bug, in miniature: one frame dominated by green packaging
        // among three neutral ones.
        List<Path> photos = List.of(
                photo("green.jpg", GREEN, null),
                photo("white1.jpg", WHITE, null),
                photo("white2.jpg", WHITE, null),
                photo("white3.jpg", WHITE, null));
        Retouch.WhiteBalance wb = Retouch.estimateWhiteBalance(photos);
        assertTrue(wb.isNeutral(),
                "the three neutral frames should decide the estimate, got " + wb);
    }

    @Test
    void everyPhotoOfASeriesGetsTheSameGains() throws IOException {
        // The illuminant is a constant, so two frames of one series must not be
        // corrected differently - a latent inconsistency in per-photo estimation
        // even when no frame was extreme.
        int[] castA = {195, 205, 220};
        int[] castB = {146, 154, 165};
        List<Path> photos = List.of(
                photo("a.jpg", castA, null),
                photo("b.jpg", castB, null));
        Retouch.WhiteBalance wb = Retouch.estimateWhiteBalance(photos);
        double[] gains = {wb.red(), wb.green(), wb.blue()};

        int[][] in = {castA, castB};
        for (int frame = 0; frame < 2; frame++) {
            BufferedImage out = Retouch.process(photos.get(frame),
                    Retouch.Mode.WHITE_BALANCE, Retouch.NEUTRAL_STRENGTH, wb);
            int p = out.getRGB(20, 20);
            int[] actual = {(p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF};
            for (int c = 0; c < 3; c++) {
                double expected = in[frame][c] * gains[c];
                assertTrue(Math.abs(actual[c] - expected) <= 3, // JPEG is lossy
                        "frame " + frame + " channel " + c + ": expected about "
                                + Math.round(expected) + ", got " + actual[c]);
            }
        }
    }

    @Test
    void aSeriesWithNoNeutralPixelsIsLeftAloneRatherThanGuessed() throws IOException {
        // Nothing in frame to read the light off. Bail rather than guess, the
        // same rule AutoCrop follows.
        List<Path> photos = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            photos.add(photo("all-green" + i + ".jpg", GREEN, null));
        }
        assertEquals(Retouch.WhiteBalance.NEUTRAL, Retouch.estimateWhiteBalance(photos),
                "no neutral evidence, so no correction");
    }

    @Test
    void aGreenBoxOnAWhiteBackdropKeepsItsColour() throws IOException {
        // The case a plain series average cannot handle: the box dominates the
        // frame, but the backdrop around it is what the light is read from.
        List<Path> photos = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            photos.add(photo("box" + i + ".jpg", WHITE, GREEN));
        }
        Retouch.WhiteBalance wb = Retouch.estimateWhiteBalance(photos);
        assertTrue(wb.isNeutral(), "the box's green must survive, got " + wb);
    }

    @Test
    void gainsAreClampedAsABackstop() throws IOException {
        // A severe cast, still low-saturation enough to count as light.
        List<Path> photos = List.of(photo("severe.jpg", new int[] {120, 132, 138}, null));
        Retouch.WhiteBalance wb = Retouch.estimateWhiteBalance(photos);
        assertTrue(wb.red() <= 1.25 + 1e-9, "red gain clamped, got " + wb);
        assertTrue(wb.green() >= 1.0 / 1.25 - 1e-9, "green gain clamped, got " + wb);
        assertTrue(wb.blue() >= 1.0 / 1.25 - 1e-9, "blue gain clamped, got " + wb);
    }

    @Test
    void anEmptySeriesIsNeutralRatherThanAFailure() {
        assertEquals(Retouch.WhiteBalance.NEUTRAL, Retouch.estimateWhiteBalance(List.of()));
    }

    @Test
    void anUnreadablePhotoIsSkippedRatherThanFailingTheOffer() throws IOException {
        Path broken = dir.resolve("broken.jpg");
        Files.writeString(broken, "this is not a JPEG");
        List<Path> photos = List.of(broken, photo("ok.jpg", new int[] {195, 205, 220}, null));

        Retouch.WhiteBalance wb = Retouch.estimateWhiteBalance(photos);
        assertTrue(wb.red() > 1.01, "estimated from the readable photo, got " + wb);
    }

    @Test
    void anEntirelyUnreadableSeriesFallsBackToNeutral() throws IOException {
        Path broken = dir.resolve("broken.jpg");
        Files.writeString(broken, "this is not a JPEG");
        assertEquals(Retouch.WhiteBalance.NEUTRAL,
                Retouch.estimateWhiteBalance(List.of(broken)));
    }
}
