package com.allegrohelper.core;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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
    void anUnknownOrientationIsLeftUntouchedRatherThanGuessed() {
        BufferedImage img = fixture();
        assertSame(img, Exif.applyOrientation(img, 0));
        assertSame(img, Exif.applyOrientation(img, 9));
        assertSame(img, Exif.applyOrientation(img, -1));
    }
}
