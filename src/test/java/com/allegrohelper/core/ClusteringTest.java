package com.allegrohelper.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Series grouping by capture time. Photo dirs mix cameras, so the two things
 * worth pinning down are which filenames yield a timestamp and what happens to
 * the ones that don't.
 */
class ClusteringTest {

    @TempDir
    Path dir;

    /** An empty file whose mtime is set, so mtime-based paths are deterministic. */
    private Path photo(String name, LocalDateTime mtime) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, "x");
        Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.from(
                mtime.atZone(ZoneId.systemDefault()).toInstant()));
        return p;
    }

    @Test
    void parsesOpenCameraNames() {
        assertEquals(LocalDateTime.of(2026, 7, 15, 16, 50, 30),
                Clustering.parseTimestamp(Path.of("IMG_20260715_165030.jpg")));
    }

    @Test
    void parsesNamesWithoutTheImgPrefix() {
        // The regression that started this: a second camera writes bare names.
        assertEquals(LocalDateTime.of(2026, 7, 15, 16, 50, 37),
                Clustering.parseTimestamp(Path.of("20260715_165037.jpg")));
    }

    @Test
    void parsesNamesWithAnyPrefixAndTrailingMilliseconds() {
        // Pixel: PXL_<date>_<time><millis>. The time must not swallow the millis.
        assertEquals(LocalDateTime.of(2026, 7, 15, 17, 20, 0),
                Clustering.parseTimestamp(Path.of("PXL_20260715_172000123.jpg")));
    }

    @Test
    void returnsNullRatherThanThrowingForNamesWithoutATimestamp() {
        assertNull(Clustering.parseTimestamp(Path.of("holiday-snap.jpg")));
        assertNull(Clustering.parseTimestamp(Path.of("scan001.jpg")));
    }

    @Test
    void returnsNullForDigitsThatAreNotARealDate() {
        // Right shape, impossible month/day - indistinguishable from no timestamp.
        assertNull(Clustering.parseTimestamp(Path.of("20261332_000000.jpg")));
        assertNull(Clustering.parseTimestamp(Path.of("20260715_996000.jpg")));
    }

    @Test
    void doesNotMatchTheTailOfALongerDigitRun() {
        // A lookbehind guards this: 9 leading digits are not a date.
        assertNull(Clustering.parseTimestamp(Path.of("12345678901234_120000.jpg")));
    }

    @Test
    void splitsSeriesOnAGapAndKeepsShortPausesTogether() throws IOException {
        List<Path> photos = new ArrayList<>();
        for (String t : List.of("165030", "165032", "165034")) {
            photos.add(photo("IMG_20260715_" + t + ".jpg", LocalDateTime.now()));
        }
        for (String t : List.of("170512", "170514")) {
            photos.add(photo("IMG_20260715_" + t + ".jpg", LocalDateTime.now()));
        }

        List<PhotoSeries> series = Clustering.cluster(photos, Duration.ofSeconds(60));

        assertEquals(2, series.size(), "a >60s gap starts a new series");
        assertEquals(3, series.get(0).count());
        assertEquals(2, series.get(1).count());
        assertEquals("20260715_1650", series.get(0).label());
        assertEquals("20260715_1705", series.get(1).label());
    }

    @Test
    void fallsBackToMtimeForNamesWithoutATimestamp() throws IOException {
        LocalDateTime base = LocalDateTime.of(2026, 7, 15, 18, 30, 0);
        List<Path> photos = List.of(
                photo("holiday-snap.jpg", base),
                photo("scan001.jpg", base.plusSeconds(3)));

        List<PhotoSeries> series = Clustering.cluster(photos, Duration.ofSeconds(60));

        assertEquals(1, series.size(), "close mtimes are one series");
        assertEquals(2, series.get(0).count());
        assertEquals(base, series.get(0).start());
        assertEquals("20260715_1830", series.get(0).label());
    }

    @Test
    void groupsMixedFilenameStylesByTimeAlone() throws IOException {
        // The user's actual dir: OpenCamera plus a second camera plus oddities.
        List<Path> photos = List.of(
                photo("IMG_20260715_165030.jpg", LocalDateTime.now()),
                photo("20260715_170512.jpg", LocalDateTime.now()),
                photo("PXL_20260715_172000123.jpg", LocalDateTime.now()),
                photo("holiday-snap.jpg", LocalDateTime.of(2026, 7, 15, 18, 30, 0)));

        List<PhotoSeries> series = Clustering.cluster(photos, Duration.ofSeconds(60));

        assertEquals(4, series.size(), "four distant capture times, four series");
        List<String> labels = series.stream().map(PhotoSeries::label).toList();
        assertEquals(List.of("20260715_1650", "20260715_1705", "20260715_1720", "20260715_1830"),
                labels);
    }

    @Test
    void ordersSeriesByTimeRegardlessOfInputOrder() throws IOException {
        List<Path> photos = List.of(
                photo("IMG_20260715_180000.jpg", LocalDateTime.now()),
                photo("IMG_20260715_090000.jpg", LocalDateTime.now()),
                photo("IMG_20260715_130000.jpg", LocalDateTime.now()));

        List<PhotoSeries> series = Clustering.cluster(photos, Duration.ofSeconds(60));

        assertEquals(List.of("20260715_0900", "20260715_1300", "20260715_1800"),
                series.stream().map(PhotoSeries::label).toList());
    }

    @Test
    void handlesAnEmptyPhotoList() throws IOException {
        assertTrue(Clustering.cluster(List.of(), Duration.ofSeconds(60)).isEmpty());
    }

    @Test
    void dirLabelIsTheOfferDirectoryConvention() {
        assertNotNull(Clustering.dirLabel(LocalDateTime.of(2026, 7, 15, 16, 50, 30)));
        assertEquals("20260715_1650", Clustering.dirLabel(LocalDateTime.of(2026, 7, 15, 16, 50, 30)));
    }
}
