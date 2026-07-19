package com.allegrohelper.core;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EXIF orientation. ImageIO ignores the tag, so every decode in the pipeline
 * goes through here to reach upright coordinates; getting a case wrong rotates
 * or mirrors a whole offer's photos.
 *
 * <p>The fixture is a 3x2 image with a distinct value per pixel, so any
 * transform is identified by where the corners land:
 *
 * <pre>
 *   1 2 3
 *   4 5 6
 * </pre>
 */
class ExifTest {

    private static BufferedImage fixture() {
        BufferedImage img = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        int v = 1;
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                img.setRGB(x, y, v++);
            }
        }
        return img;
    }

    /** The image's pixels row by row, as the small integers the fixture uses. */
    private static String render(BufferedImage img) {
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                sb.append(img.getRGB(x, y) & 0xFFFFFF);
            }
            if (y < img.getHeight() - 1) {
                sb.append('/');
            }
        }
        return sb.toString();
    }

    @Test
    void orientationOneIsLeftUntouched() {
        BufferedImage img = fixture();
        assertSame(img, Exif.applyOrientation(img, 1), "no transform, no copy");
        assertEquals("123/456", render(img));
    }

    @Test
    void orientationTwoMirrorsHorizontally() {
        assertEquals("321/654", render(Exif.applyOrientation(fixture(), 2)));
    }

    @Test
    void orientationThreeRotates180() {
        assertEquals("654/321", render(Exif.applyOrientation(fixture(), 3)));
    }

    @Test
    void orientationFourMirrorsVertically() {
        assertEquals("456/123", render(Exif.applyOrientation(fixture(), 4)));
    }

    @Test
    void orientationSixRotates90ClockwiseAndSwapsDimensions() {
        BufferedImage out = Exif.applyOrientation(fixture(), 6);
        assertEquals(2, out.getWidth(), "3x2 becomes 2x3");
        assertEquals(3, out.getHeight());
        assertEquals("41/52/63", render(out));
    }

    @Test
    void orientationEightRotates90CounterClockwise() {
        BufferedImage out = Exif.applyOrientation(fixture(), 8);
        assertEquals(2, out.getWidth());
        assertEquals(3, out.getHeight());
        assertEquals("36/25/14", render(out));
    }

    @Test
    void theTwoRotationsAreInverses() {
        BufferedImage roundTrip = Exif.applyOrientation(Exif.applyOrientation(fixture(), 6), 8);
        assertEquals(render(fixture()), render(roundTrip));
    }

    @Test
    void readsTheOrientationTagOffARealJpeg() throws java.io.IOException {
        // OCR now reads untouched originals, which still carry this tag, so the
        // parsing path matters and not just the transform.
        java.nio.file.Path jpeg = java.nio.file.Files.createTempFile("oriented", ".jpg");
        javax.imageio.ImageIO.write(fixture(), "jpg", jpeg.toFile());
        byte[] plain = java.nio.file.Files.readAllBytes(jpeg);

        // A minimal EXIF APP1: big-endian TIFF, one entry, Orientation = 6.
        byte[] tiff = new byte[] {
            'M', 'M', 0, 42, 0, 0, 0, 8,          // header, IFD at offset 8
            0, 1,                                  // one entry
            1, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, // Orientation (SHORT) = 6
            0, 0, 0, 0};                           // no next IFD
        byte[] app1 = new byte[6 + tiff.length];
        System.arraycopy(new byte[] {'E', 'x', 'i', 'f', 0, 0}, 0, app1, 0, 6);
        System.arraycopy(tiff, 0, app1, 6, tiff.length);

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(plain, 0, 2);                                  // SOI
        out.write(0xFF);
        out.write(0xE1);
        out.write((app1.length + 2) >> 8);
        out.write((app1.length + 2) & 0xFF);
        out.write(app1);
        out.write(plain, 2, plain.length - 2);
        java.nio.file.Files.write(jpeg, out.toByteArray());

        assertEquals(6, Exif.readOrientation(jpeg));
        java.nio.file.Files.deleteIfExists(jpeg);
    }

    @Test
    void aJpegWithoutExifReportsNoRotation() throws java.io.IOException {
        // Pipeline-written files have no EXIF, which is why applying the
        // orientation unconditionally is safe.
        java.nio.file.Path jpeg = java.nio.file.Files.createTempFile("plain", ".jpg");
        javax.imageio.ImageIO.write(fixture(), "jpg", jpeg.toFile());
        assertTrue(Exif.readOrientation(jpeg) <= 1,
                "no EXIF should mean no transform, got " + Exif.readOrientation(jpeg));
        java.nio.file.Files.deleteIfExists(jpeg);
    }

    @Test
    void anUnknownOrientationIsLeftUntouchedRatherThanGuessed() {
        BufferedImage img = fixture();
        assertSame(img, Exif.applyOrientation(img, 0));
        assertSame(img, Exif.applyOrientation(img, 9));
        assertSame(img, Exif.applyOrientation(img, -1));
    }
}
