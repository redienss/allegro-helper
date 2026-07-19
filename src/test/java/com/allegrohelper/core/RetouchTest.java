package com.allegrohelper.core;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retouching maths. These pin PIL's semantics, which the pipeline
 * deliberately reproduces: deviating from them is a behavior change, not a
 * cleanup, so a refactor that quietly alters the curve should fail here.
 */
class RetouchTest {

    private static BufferedImage solid(int r, int g, int b) {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    private static int[] rgb(BufferedImage img, int x, int y) {
        int p = img.getRGB(x, y);
        return new int[] {(p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF};
    }

    @Test
    void neutralStrengthIsANoOpForBrightness() {
        BufferedImage out = Retouch.apply(solid(120, 90, 60),
                Retouch.Mode.BRIGHTNESS, Retouch.NEUTRAL_STRENGTH);
        assertArrayEqualsRgb(new int[] {120, 90, 60}, rgb(out, 0, 0));
    }

    @Test
    void neutralStrengthIsANoOpForContrast() {
        BufferedImage out = Retouch.apply(solid(120, 90, 60),
                Retouch.Mode.CONTRAST, Retouch.NEUTRAL_STRENGTH);
        assertArrayEqualsRgb(new int[] {120, 90, 60}, rgb(out, 0, 0));
    }

    @Test
    void brightnessScalesEveryChannelSoHueIsPreserved() {
        // PIL's ImageEnhance.Brightness scales rather than offsets, which is
        // what keeps a brightened white item white instead of drifting gray.
        BufferedImage out = Retouch.apply(solid(100, 50, 25), Retouch.Mode.BRIGHTNESS, 1.5);
        assertArrayEqualsRgb(new int[] {150, 75, 38}, rgb(out, 0, 0));
    }

    @Test
    void brightnessBelowOneDarkens() {
        BufferedImage out = Retouch.apply(solid(100, 50, 25), Retouch.Mode.BRIGHTNESS, 0.5);
        assertArrayEqualsRgb(new int[] {50, 25, 13}, rgb(out, 0, 0));
    }

    @Test
    void brightnessClipsAtWhiteRatherThanWrapping() {
        BufferedImage out = Retouch.apply(solid(200, 220, 240), Retouch.Mode.BRIGHTNESS, 2.0);
        assertArrayEqualsRgb(new int[] {255, 255, 255}, rgb(out, 0, 0));
    }

    @Test
    void contrastPivotsOnTheImagesOwnMeanLuminance() {
        // Half black, half white: mean luminance is mid-gray, so raising
        // contrast pushes each half further towards its own end.
        BufferedImage img = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0x000000);
        img.setRGB(1, 0, 0xFFFFFF);
        BufferedImage out = Retouch.apply(img, Retouch.Mode.CONTRAST, 1.5);
        assertArrayEqualsRgb(new int[] {0, 0, 0}, rgb(out, 0, 0));
        assertArrayEqualsRgb(new int[] {255, 255, 255}, rgb(out, 1, 0));
    }

    @Test
    void contrastPullsTowardsTheMeanBelowOne() {
        // A uniform image is its own mean, so reducing contrast cannot move it;
        // use a two-tone image where the pull is observable.
        BufferedImage img = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0x323232);   // 50
        img.setRGB(1, 0, 0xC8C8C8);   // 200
        BufferedImage out = Retouch.apply(img, Retouch.Mode.CONTRAST, 0.5);
        int dark = rgb(out, 0, 0)[0];
        int light = rgb(out, 1, 0)[0];
        assertTrue(dark > 50, "the dark tone should rise towards the mean, was " + dark);
        assertTrue(light < 200, "the light tone should fall towards the mean, was " + light);
        assertTrue(light - dark < 150, "the tonal range should narrow");
    }

    @Test
    void strengthIsClampedToTheSlidersRange() {
        assertEquals(Retouch.MIN_STRENGTH, Retouch.clampStrength(0.1));
        assertEquals(Retouch.MAX_STRENGTH, Retouch.clampStrength(9.0));
        assertEquals(Retouch.MIN_STRENGTH, Retouch.clampStrength(-3.0));
        assertEquals(1.3, Retouch.clampStrength(1.3), 1e-9);
    }

    @Test
    void aGarbledStrengthFallsBackToNeutralRatherThanWreckingThePhoto() {
        assertEquals(Retouch.NEUTRAL_STRENGTH, Retouch.clampStrength(Double.NaN));
    }

    @Test
    void anOutOfRangeStrengthCannotWreckAPhoto() {
        // clampStrength is applied inside apply(), not only at the call sites.
        BufferedImage out = Retouch.apply(solid(100, 100, 100), Retouch.Mode.BRIGHTNESS, 100.0);
        assertArrayEqualsRgb(new int[] {200, 200, 200}, rgb(out, 0, 0));
    }

    @Test
    void whiteBalanceAppliesTheSeriesGainsToEveryChannel() {
        // The gains come from the series, not the photo, so apply() takes them.
        BufferedImage img = solid(100, 120, 180);
        BufferedImage out = Retouch.apply(img, Retouch.Mode.WHITE_BALANCE, 1.0,
                new Retouch.WhiteBalance(1.2, 1.0, 0.8));
        assertArrayEqualsRgb(new int[] {120, 120, 144}, rgb(out, 0, 0));
    }

    @Test
    void neutralGainsLeaveThePhotoAlone() {
        BufferedImage out = Retouch.apply(solid(100, 120, 180), Retouch.Mode.WHITE_BALANCE, 1.0,
                Retouch.WhiteBalance.NEUTRAL);
        assertArrayEqualsRgb(new int[] {100, 120, 180}, rgb(out, 0, 0));
    }

    @Test
    void whiteBalanceGainsClipAtWhiteRatherThanWrapping() {
        BufferedImage out = Retouch.apply(solid(240, 250, 200), Retouch.Mode.WHITE_BALANCE, 1.0,
                new Retouch.WhiteBalance(1.25, 1.25, 1.0));
        assertArrayEqualsRgb(new int[] {255, 255, 200}, rgb(out, 0, 0));
    }

    @Test
    void everyModeMapsToItsOwnOutputDirectory() {
        // Downstream steps walk these names backwards to find their input, so a
        // rename here silently breaks the chain.
        assertEquals("white_balanced", Retouch.Mode.WHITE_BALANCE.dirName);
        assertEquals("brightened", Retouch.Mode.BRIGHTNESS.dirName);
        assertEquals("contrasted", Retouch.Mode.CONTRAST.dirName);
    }

    private static void assertArrayEqualsRgb(int[] expected, int[] actual) {
        assertEquals(expected[0], actual[0], "red");
        assertEquals(expected[1], actual[1], "green");
        assertEquals(expected[2], actual[2], "blue");
    }
}
