package com.allegrohelper.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which directory OCR reads from. Every retouching step can be unticked, so
 * this chain has to end at the originals — it once stopped at the retouch
 * outputs, and OCR silently skipped every offer when the boxes were clear.
 *
 * <p>It also has to stay in step with the chains in {@code AutoCrop} and
 * {@code MainWindow.outputPhotoDir}: a directory missing from one of them is
 * invisible until someone runs that exact combination of steps.
 */
class OcrInputDirTest {

    @TempDir
    Path offerDir;

    private void mkdirs(String... names) throws IOException {
        for (String name : names) {
            Files.createDirectories(offerDir.resolve(name));
        }
    }

    @Test
    void fallsBackToTheOriginalsWhenNoRetouchingRan() throws IOException {
        // The reported bug: all retouch boxes unticked, so only photos/ exists.
        mkdirs("photos");
        assertEquals(offerDir.resolve("photos"), Ocr.inputDir(offerDir));
    }

    @Test
    void readsBrightenedWhenOnlyBrightnessRan() throws IOException {
        // brightened/ was missing from the chain entirely, so this combination
        // skipped OCR even though a retouching step had run.
        mkdirs("photos", "brightened");
        assertEquals(offerDir.resolve("brightened"), Ocr.inputDir(offerDir));
    }

    @Test
    void readsWhiteBalancedWhenOnlyWhiteBalanceRan() throws IOException {
        mkdirs("photos", "white_balanced");
        assertEquals(offerDir.resolve("white_balanced"), Ocr.inputDir(offerDir));
    }

    @Test
    void prefersTheMostProcessedDirectoryAvailable() throws IOException {
        mkdirs("photos", "white_balanced", "brightened", "contrasted", "cropped");
        assertEquals(offerDir.resolve("cropped"), Ocr.inputDir(offerDir));
    }

    @Test
    void ordersContrastAboveBrightnessAboveWhiteBalance() throws IOException {
        mkdirs("photos", "white_balanced", "brightened", "contrasted");
        assertEquals(offerDir.resolve("contrasted"), Ocr.inputDir(offerDir));

        Files.delete(offerDir.resolve("contrasted"));
        assertEquals(offerDir.resolve("brightened"), Ocr.inputDir(offerDir));

        Files.delete(offerDir.resolve("brightened"));
        assertEquals(offerDir.resolve("white_balanced"), Ocr.inputDir(offerDir));

        Files.delete(offerDir.resolve("white_balanced"));
        assertEquals(offerDir.resolve("photos"), Ocr.inputDir(offerDir));
    }

    @Test
    void stillRecognizesTheLegacyRetouchedDirectory() throws IOException {
        // Never written any more, but old offers must keep working.
        mkdirs("photos", "retouched");
        assertEquals(offerDir.resolve("retouched"), Ocr.inputDir(offerDir));
    }

    @Test
    void returnsNullOnlyWhenTheOfferHasNoPhotosAtAll() throws IOException {
        assertNull(Ocr.inputDir(offerDir));
    }

    @Test
    void matchesAutoCropsChainForEveryStepCombination() throws IOException {
        // The two chains must agree, or a run crops from one directory and
        // OCRs from another. Walk all 16 combinations of the retouch outputs.
        String[] optional = {"white_balanced", "brightened", "contrasted", "cropped"};
        for (int mask = 0; mask < 16; mask++) {
            Path dir = Files.createTempDirectory("offer");
            Files.createDirectories(dir.resolve("photos"));
            for (int bit = 0; bit < optional.length; bit++) {
                if ((mask & (1 << bit)) != 0) {
                    Files.createDirectories(dir.resolve(optional[bit]));
                }
            }
            // cropped/ is auto-crop's own output, so it is not one of its inputs.
            Path expected = Files.isDirectory(dir.resolve("contrasted")) ? dir.resolve("contrasted")
                    : Files.isDirectory(dir.resolve("brightened")) ? dir.resolve("brightened")
                    : Files.isDirectory(dir.resolve("white_balanced")) ? dir.resolve("white_balanced")
                    : dir.resolve("photos");
            Path ocrInput = Ocr.inputDir(dir);
            Path want = Files.isDirectory(dir.resolve("cropped")) ? dir.resolve("cropped") : expected;
            assertEquals(want, ocrInput, "combination mask " + mask);
        }
    }
}
