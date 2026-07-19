package com.allegrohelper.ui;

import com.allegrohelper.core.Config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The filesystem questions the UI asks. The first tests of the {@code ui}
 * package: everything else in it needs a window, but these are pure functions
 * of a directory tree, so they are hermetic and headless like the rest of the
 * suite.
 */
class OfferFilesTest {

    @TempDir
    Path dir;

    private void mkdirs(String... names) throws IOException {
        for (String name : names) {
            Files.createDirectories(dir.resolve(name));
        }
    }

    // ---------------------------------------------------------- outputPhotoDir

    @Test
    void outputPhotoDirPrefersTheMostProcessedDirectory() throws IOException {
        mkdirs("photos", "white_balanced", "brightened", "contrasted", "cropped");
        assertEquals(dir.resolve("cropped"), OfferFiles.outputPhotoDir(dir));
    }

    @Test
    void outputPhotoDirWalksTheRetouchChainInOrder() throws IOException {
        mkdirs("photos", "white_balanced", "brightened", "contrasted", "cropped");
        assertEquals(dir.resolve("cropped"), OfferFiles.outputPhotoDir(dir));

        Files.delete(dir.resolve("cropped"));
        assertEquals(dir.resolve("contrasted"), OfferFiles.outputPhotoDir(dir));

        Files.delete(dir.resolve("contrasted"));
        assertEquals(dir.resolve("brightened"), OfferFiles.outputPhotoDir(dir));

        Files.delete(dir.resolve("brightened"));
        assertEquals(dir.resolve("white_balanced"), OfferFiles.outputPhotoDir(dir));
    }

    @Test
    void outputPhotoDirStillRecognizesTheLegacyRetouchedDirectory() throws IOException {
        // Never written any more, but offers processed before the retouch step
        // was split up must keep showing their photos.
        mkdirs("photos", "retouched");
        assertEquals(dir.resolve("retouched"), OfferFiles.outputPhotoDir(dir));
    }

    @Test
    void outputPhotoDirDoesNotFallBackToTheOriginals() throws IOException {
        // Deliberately unlike Ocr/AutoCrop: with nothing retouched the gallery
        // should say "Not available yet.", not show the untouched originals.
        mkdirs("photos");
        Path result = OfferFiles.outputPhotoDir(dir);
        assertEquals(dir.resolve("contrasted"), result);
        assertFalse(Files.exists(result), "the returned path is deliberately nonexistent");
    }

    // --------------------------------------------------------- resolveOfferDir

    /** A Config rooted at {@code base}, which is all resolveOfferDir reads. */
    private static Config configFor(Path base) {
        return Config.forBaseDir(base);
    }

    private void offer(String dirName, String name) throws IOException {
        Path offer = dir.resolve("offers").resolve(dirName);
        Files.createDirectories(offer);
        if (name != null) {
            Files.writeString(offer.resolve("data.json"), "{\"name\": \"" + name + "\"}");
        }
    }

    @Test
    void resolveOfferDirMatchesByNameBeforePosition() throws IOException {
        offer("20260101_0900", "Kettle");
        offer("20260102_1000", "Armchair");
        // Armchair is row 0 in the grid but the *second* directory by name: a
        // name match must win, or a reordered grid points at the wrong offer.
        assertEquals(dir.resolve("offers").resolve("20260102_1000"),
                OfferFiles.resolveOfferDir(configFor(dir), "Armchair", 0));
    }

    @Test
    void resolveOfferDirFallsBackToRowPositionWhenTheNameIsUnknown() throws IOException {
        offer("20260101_0900", "Kettle");
        offer("20260102_1000", "Armchair");
        assertEquals(dir.resolve("offers").resolve("20260102_1000"),
                OfferFiles.resolveOfferDir(configFor(dir), "Not a listed name", 1));
    }

    @Test
    void resolveOfferDirFallsBackWhenTheNameIsBlank() throws IOException {
        offer("20260101_0900", "Kettle");
        assertEquals(dir.resolve("offers").resolve("20260101_0900"),
                OfferFiles.resolveOfferDir(configFor(dir), "   ", 0));
        assertEquals(dir.resolve("offers").resolve("20260101_0900"),
                OfferFiles.resolveOfferDir(configFor(dir), null, 0));
    }

    @Test
    void resolveOfferDirIgnoresAMalformedDataJson() throws IOException {
        Path broken = dir.resolve("offers").resolve("20260101_0900");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve("data.json"), "{ this is not json");
        offer("20260102_1000", "Armchair");
        // The broken one must be skipped rather than aborting the search.
        assertEquals(dir.resolve("offers").resolve("20260102_1000"),
                OfferFiles.resolveOfferDir(configFor(dir), "Armchair", 0));
    }

    @Test
    void resolveOfferDirReturnsNullWhenThereAreNoOffers() throws IOException {
        assertNull(OfferFiles.resolveOfferDir(configFor(dir), "Anything", 0),
                "no offers directory at all");
        Files.createDirectories(dir.resolve("offers"));
        assertNull(OfferFiles.resolveOfferDir(configFor(dir), "Anything", 0),
                "offers directory exists but is empty");
    }

    @Test
    void resolveOfferDirReturnsNullForAnOutOfRangeRow() throws IOException {
        offer("20260101_0900", "Kettle");
        assertNull(OfferFiles.resolveOfferDir(configFor(dir), "", 5));
        assertNull(OfferFiles.resolveOfferDir(configFor(dir), "", -1));
    }

    // ------------------------------------------------------------ readIfExists

    @Test
    void readIfExistsReturnsEmptyForAMissingFile() {
        assertEquals("", OfferFiles.readIfExists(dir.resolve("nope.txt")));
        assertEquals("", OfferFiles.readIfExists(null));
    }

    @Test
    void readIfExistsRoundTripsUtf8() throws IOException {
        Path file = dir.resolve("description.txt");
        String polish = "Ładowarka ścienna — używany, brak uszkodzeń";
        Files.writeString(file, polish);
        assertEquals(polish, OfferFiles.readIfExists(file));
    }

    @Test
    void readIfExistsReturnsEmptyForADirectory() throws IOException {
        mkdirs("photos");
        assertEquals("", OfferFiles.readIfExists(dir.resolve("photos")));
    }

    // -------------------------------------------------------------- escapeHtml

    @Test
    void escapeHtmlProtectsTheDetailsHeaderMarkup() {
        // The header is rendered as HTML, so an offer name is a real injection
        // path - "A & B <x>" would otherwise break the layout.
        assertEquals("A &amp; B &lt;x&gt;", OfferFiles.escapeHtml("A & B <x>"));
    }

    @Test
    void escapeHtmlEscapesTheAmpersandFirst() {
        // Order matters: escaping < before & would yield "&amp;lt;".
        assertEquals("&amp;lt;", OfferFiles.escapeHtml("&lt;"));
    }

    // ------------------------------------------------------------------ isJpeg

    @Test
    void isJpegAcceptsBothExtensionsInAnyCase() {
        assertTrue(OfferFiles.isJpeg(Path.of("IMG_20260715_165030.jpg")));
        assertTrue(OfferFiles.isJpeg(Path.of("photo.JPEG")));
        assertTrue(OfferFiles.isJpeg(Path.of("/a/b/c.Jpg")));
        assertFalse(OfferFiles.isJpeg(Path.of("data.json")));
        assertFalse(OfferFiles.isJpeg(Path.of("notes.txt")));
    }

    // ------------------------------------------------------ deepestExistingDir

    @Test
    void deepestExistingDirStopsAtTheFirstGlobSegment() throws IOException {
        mkdirs("run/user/1000/gvfs");
        Path deepest = OfferFiles.deepestExistingDir(
                dir.resolve("run/user/1000/gvfs").toString() + "/mtp:host=*/*/DCIM");
        assertEquals(dir.resolve("run/user/1000/gvfs"), deepest);
    }

    @Test
    void deepestExistingDirStopsWhereTheTreeEnds() throws IOException {
        mkdirs("run/user");
        assertEquals(dir.resolve("run/user"),
                OfferFiles.deepestExistingDir(dir.resolve("run/user/1000/gvfs").toString()));
    }

    @Test
    void deepestExistingDirRejectsARelativePattern() {
        assertNull(OfferFiles.deepestExistingDir("relative/path"));
    }

    // ------------------------------------------------------- deleteRecursively

    @Test
    void deleteRecursivelyRemovesANestedTree() throws IOException {
        mkdirs("offers/20260101_0900/photos", "offers/20260101_0900/cropped");
        Files.writeString(dir.resolve("offers/20260101_0900/photos/a.jpg"), "x");
        Files.writeString(dir.resolve("offers/20260101_0900/data.json"), "{}");

        OfferFiles.deleteRecursively(dir.resolve("offers"));

        assertFalse(Files.exists(dir.resolve("offers")));
    }

    @Test
    void deleteRecursivelySurfacesAFailureRatherThanSwallowingIt() {
        // Unlike the OCR step's cleanup, a failure here must be visible.
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> OfferFiles.deleteRecursively(dir.resolve("does-not-exist")));
    }
}
