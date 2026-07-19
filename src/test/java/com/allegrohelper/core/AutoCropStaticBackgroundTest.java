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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The premise behind auto-crop: a fixed camera, so the background is the same
 * pixels in every frame and only the item moves. When that does not hold there
 * is no background for Otsu to separate, and the largest changing blob is an
 * arbitrary patch — which is how a handheld pair of a box and its manual came
 * out cropped through the packaging.
 */
class AutoCropStaticBackgroundTest {

    private static final int W = 600;
    private static final int H = 800;

    /**
     * A textured backdrop, so "unchanged" means unchanged rather than
     * featureless. The texture is deliberately non-repeating: a periodic one
     * (stripes) realigns with itself when the frame shifts, which makes a
     * handheld series look static and is an artefact of the fixture, not of
     * any real scene.
     */
    private static void paintBackground(Graphics2D g, int offsetX, int offsetY) {
        Random noise = new Random(1234);
        for (int y = -80; y < H + 80; y += 8) {
            for (int x = -80; x < W + 80; x += 8) {
                int v = 150 + noise.nextInt(90);
                g.setColor(new Color(v, v - 8, v - 18));
                g.fillRect(x + offsetX, y + offsetY, 8, 8);
            }
        }
    }

    /** One frame: a dark item near the centre, drawn at a varying angle. */
    private static Path frame(Path dir, String name, int itemPhase, int cameraOffset)
            throws IOException {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        paintBackground(g, cameraOffset, cameraOffset);
        // The item: same footprint every frame, different face towards us —
        // exactly what a turntable produces.
        g.setColor(new Color(40, 40, 45));
        g.fillRect(200 + cameraOffset, 280 + cameraOffset, 200, 240);
        g.setColor(new Color(90 + itemPhase * 40, 90, 100));
        g.fillRect(230 + cameraOffset, 320 + cameraOffset, 140 - itemPhase * 20, 160);
        g.dispose();

        Path p = dir.resolve(name);
        ImageIO.write(img, "jpg", p.toFile());
        return p;
    }

    private static List<Path> series(Path dir, int count, boolean cameraMoves) throws IOException {
        Files.createDirectories(dir);
        List<Path> photos = new ArrayList<>();
        Random rnd = new Random(7);
        for (int i = 0; i < count; i++) {
            // Handheld: the whole frame shifts. Tripod: it does not, at all.
            int offset = cameraMoves ? 30 + rnd.nextInt(60) : 0;
            photos.add(frame(dir, String.format("%02d.jpg", i), i % 3, offset));
        }
        return photos;
    }

    @Test
    void aFixedCameraSeriesIsCropped(@TempDir Path tmp) throws IOException {
        List<Path> photos = series(tmp.resolve("tripod"), 6, false);

        int[] box = AutoCrop.detectBox(photos);

        assertNotNull(box, "a static background is exactly what the method needs");
        assertTrue(box[2] < W, "the crop should be narrower than the frame");
    }

    @Test
    void aMovingCameraSeriesIsLeftAlone(@TempDir Path tmp) throws IOException {
        List<Path> photos = series(tmp.resolve("handheld"), 6, true);

        assertNull(AutoCrop.detectBox(photos),
                "with the whole frame moving there is no background to separate the item from");
    }

    @Test
    void staticFractionMeasuresTheUnchangedPartOfTheFrame() {
        int[] allStill = new int[100];                 // every pixel identical
        int[] halfMoving = new int[100];
        for (int i = 0; i < 50; i++) {
            halfMoving[i] = 90;                        // half the frame changing hard
        }
        int[] jpegJitter = new int[100];
        java.util.Arrays.fill(jpegJitter, 3);          // quantization, not movement

        assertEquals(1.0, AutoCrop.staticFraction(allStill));
        assertEquals(0.5, AutoCrop.staticFraction(halfMoving));
        assertEquals(1.0, AutoCrop.staticFraction(jpegJitter),
                "a couple of levels of JPEG noise is not the camera moving");
        assertEquals(0.0, AutoCrop.staticFraction(new int[0]));
    }
}
