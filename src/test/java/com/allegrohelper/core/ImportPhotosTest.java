package com.allegrohelper.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the staging contract of the import step: {@code raw_photos/} holds what
 * is waiting to be matched, and nothing else. The originals stay on the phone,
 * so without a guard every import re-stages photos that Match has already moved
 * into an offer — and those leftovers join the next item's series.
 */
class ImportPhotosTest {

    /** A config whose phone, staging and offer directories are all under {@code tmp}. */
    private static Config config(Path tmp) {
        return Config.forBaseDir(tmp, Map.of(
                "MTP_GLOB_PATTERN", tmp.resolve("phone").toString(),
                "RAW_PHOTOS_DIR", tmp.resolve("raw_photos").toString(),
                "OFFERS_DIR", tmp.resolve("offers").toString()));
    }

    /** Collects the step's log lines; the pipeline never prints from core/. */
    private static final class Log implements Reporter {
        final List<String> lines = new ArrayList<>();

        @Override
        public void log(String line) {
            lines.add(line);
        }

        @Override
        public void stepProgress(double fraction) {
        }

        String text() {
            return String.join("\n", lines);
        }
    }

    private static void photo(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), name);
    }

    private static List<String> names(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void copiesPhotosThatHaveNotBeenImportedYet(@TempDir Path tmp) throws IOException {
        photo(tmp.resolve("phone"), "20260719_145955.jpg");
        photo(tmp.resolve("phone"), "20260719_150007.jpg");

        Log log = new Log();
        ImportPhotos.run(config(tmp), log);

        assertEquals(List.of("20260719_145955.jpg", "20260719_150007.jpg"),
                names(tmp.resolve("raw_photos")));
        assertTrue(log.text().contains("copied 2"), log.text());
    }

    @Test
    void doesNotRestagePhotosAlreadyMatchedIntoAnOffer(@TempDir Path tmp) throws IOException {
        // The previous listing: photos still on the phone, already matched.
        photo(tmp.resolve("phone"), "IMG_20260715_163723.jpg");
        photo(tmp.resolve("offers/20260715_1637/photos"), "IMG_20260715_163723.jpg");
        // The new item, freshly shot.
        photo(tmp.resolve("phone"), "20260719_145955.jpg");

        Log log = new Log();
        ImportPhotos.run(config(tmp), log);

        assertEquals(List.of("20260719_145955.jpg"), names(tmp.resolve("raw_photos")),
                "a photo already matched into an offer must not be staged again");
        assertTrue(log.text().contains("skipped (already in an offer) 1"), log.text());
    }

    @Test
    void restagesOnceTheOfferDirectoryIsDeleted(@TempDir Path tmp) throws IOException {
        // Delete Output Files removes offers/, so the photo is no longer
        // anywhere: re-importing it is the whole point of pressing that button.
        photo(tmp.resolve("phone"), "IMG_20260715_163723.jpg");

        ImportPhotos.run(config(tmp), new Log());

        assertEquals(List.of("IMG_20260715_163723.jpg"), names(tmp.resolve("raw_photos")));
    }

    @Test
    void aSecondImportOfTheSameStagedPhotoIsStillANoOp(@TempDir Path tmp) throws IOException {
        photo(tmp.resolve("phone"), "20260719_145955.jpg");

        ImportPhotos.run(config(tmp), new Log());
        Log second = new Log();
        ImportPhotos.run(config(tmp), second);

        assertEquals(List.of("20260719_145955.jpg"), names(tmp.resolve("raw_photos")));
        assertTrue(second.text().contains("skipped (already present) 1"), second.text());
    }

    @Test
    void matchedNamesIgnoreAnOfferWithoutAPhotosDirectory(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve("offers/20260715_1637"));
        photo(tmp.resolve("offers/20260719_1459/photos"), "20260719_145955.jpg");

        var found = ImportPhotos.matchedPhotoNames(tmp.resolve("offers"));

        assertEquals(java.util.Set.of("20260719_145955.jpg"), found);
    }

    @Test
    void matchedNamesAreEmptyWhenNothingHasBeenMatched(@TempDir Path tmp) throws IOException {
        assertTrue(ImportPhotos.matchedPhotoNames(tmp.resolve("offers")).isEmpty());
        assertFalse(Files.exists(tmp.resolve("offers")), "the check must not create the directory");
    }
}
