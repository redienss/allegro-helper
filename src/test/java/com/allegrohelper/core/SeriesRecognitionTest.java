package com.allegrohelper.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three grouping modes. This is the single entry point both the match step
 * and the UI's Photos preview go through, so a change here silently desyncs the
 * preview from what a run would actually do.
 */
class SeriesRecognitionTest {

    @TempDir
    Path dir;

    private Path photo(Path parent, String name, LocalDateTime mtime) throws IOException {
        Files.createDirectories(parent);
        Path p = parent.resolve(name);
        Files.writeString(p, "x");
        Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.from(
                mtime.atZone(ZoneId.systemDefault()).toInstant()));
        return p;
    }

    @Test
    void autoModeClustersByCaptureTime() throws IOException {
        LocalDateTime now = LocalDateTime.now();
        photo(dir, "IMG_20260715_100000.jpg", now);
        photo(dir, "IMG_20260715_100002.jpg", now);
        photo(dir, "IMG_20260715_120000.jpg", now);

        List<PhotoSeries> series =
                SeriesRecognition.recognize(SeriesRecognition.Mode.AUTO, dir, Duration.ofSeconds(60));

        assertEquals(2, series.size());
        assertEquals(2, series.get(0).count());
        assertEquals(1, series.get(1).count());
    }

    @Test
    void singleModeTreatsTheWholeDirectoryAsOneOffer() throws IOException {
        LocalDateTime base = LocalDateTime.of(2026, 7, 15, 9, 0, 0);
        // Deliberately far apart in time: single mode must ignore the gap.
        photo(dir, "a.jpg", base);
        photo(dir, "b.jpg", base.plusHours(5));
        photo(dir, "c.jpg", base.plusHours(9));

        List<PhotoSeries> series = SeriesRecognition.recognize(
                SeriesRecognition.Mode.SINGLE_ITEM, dir, Duration.ofSeconds(60));

        assertEquals(1, series.size(), "one offer regardless of time gaps");
        assertEquals(3, series.get(0).count());
        assertEquals(base, series.get(0).start(), "start is the earliest mtime");
        assertEquals(base.plusHours(9), series.get(0).end(), "end is the latest mtime");
    }

    @Test
    void singleModeOnAnEmptyDirectoryYieldsNoOffers() throws IOException {
        assertTrue(SeriesRecognition.recognize(
                SeriesRecognition.Mode.SINGLE_ITEM, dir, Duration.ofSeconds(60)).isEmpty());
    }

    @Test
    void subfolderModeMakesEachSubfolderOneOfferInNameOrder() throws IOException {
        LocalDateTime now = LocalDateTime.now();
        photo(dir.resolve("kettle"), "1.jpg", now);
        photo(dir.resolve("kettle"), "2.jpg", now);
        photo(dir.resolve("armchair"), "1.jpg", now);
        photo(dir.resolve("book"), "1.jpg", now);
        photo(dir.resolve("book"), "2.jpg", now);
        photo(dir.resolve("book"), "3.jpg", now);

        List<PhotoSeries> series = SeriesRecognition.recognize(
                SeriesRecognition.Mode.SUBFOLDERS, dir, Duration.ofSeconds(60));

        assertEquals(List.of("armchair", "book", "kettle"),
                series.stream().map(PhotoSeries::label).toList(),
                "name order, because CSV rows match series by position");
        assertEquals(1, series.get(0).count());
        assertEquals(3, series.get(1).count());
        assertEquals(2, series.get(2).count());
    }

    @Test
    void subfolderModeSkipsEmptySubfolders() throws IOException {
        // Skipping them is what keeps a re-run idempotent once match has moved
        // the photos out, leaving the directory behind.
        photo(dir.resolve("full"), "1.jpg", LocalDateTime.now());
        Files.createDirectories(dir.resolve("emptied"));

        List<PhotoSeries> series = SeriesRecognition.recognize(
                SeriesRecognition.Mode.SUBFOLDERS, dir, Duration.ofSeconds(60));

        assertEquals(List.of("full"), series.stream().map(PhotoSeries::label).toList());
    }

    @Test
    void modeParsesFromItsConfigKey() {
        assertEquals(SeriesRecognition.Mode.AUTO, SeriesRecognition.Mode.parse("auto"));
        assertEquals(SeriesRecognition.Mode.SINGLE_ITEM, SeriesRecognition.Mode.parse("single"));
        assertEquals(SeriesRecognition.Mode.SUBFOLDERS, SeriesRecognition.Mode.parse("subfolders"));
        assertEquals(SeriesRecognition.Mode.SUBFOLDERS, SeriesRecognition.Mode.parse("  SubFolders "));
    }

    @Test
    void anUnknownModeFallsBackToAutoRatherThanFailing() {
        assertEquals(SeriesRecognition.Mode.AUTO, SeriesRecognition.Mode.parse("nonsense"));
        assertEquals(SeriesRecognition.Mode.AUTO, SeriesRecognition.Mode.parse(""));
        assertEquals(SeriesRecognition.Mode.AUTO, SeriesRecognition.Mode.parse(null));
    }
}
