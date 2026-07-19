package com.allegrohelper.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Groups photos into series based on capture time. A gap larger than the
 * threshold marks the start of a new series.
 *
 * <p>The time comes from the timestamp encoded in the filename when there is
 * one — OpenCamera writes {@code IMG_YYYYMMDD_HHMMSS.jpg}, and other cameras
 * use the same date_time run of digits with a different (or no) prefix, so the
 * prefix is not required. A name that carries no such timestamp falls back to
 * the file's mtime rather than aborting the run: a photo dir may mix cameras,
 * and the mtime is the same signal the non-auto recognition modes rely on.
 */
public final class Clustering {

    /**
     * {@code YYYYMMDD_HHMMSS} anywhere in the name, with any prefix or none.
     * The lookbehind keeps the date from matching the tail of a longer digit
     * run; no lookahead follows the time, because some cameras append
     * milliseconds to it (Pixel: {@code PXL_20260715_165037123.jpg}).
     */
    private static final Pattern FILENAME_RE = Pattern.compile("(?<!\\d)(\\d{8})_(\\d{6})");
    private static final DateTimeFormatter TS_PARSE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DIR_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    /** Not instantiable: the class is a namespace for its static helpers. */
    private Clustering() {
    }

    /**
     * The capture time encoded in a file name, or null when the name carries
     * none — or carries digits in the right shape that are not a real date
     * ({@code 20261332_000000}), which is indistinguishable from having none.
     * Callers fall back to the file's mtime.
     */
    public static LocalDateTime parseTimestamp(Path path) {
        Matcher m = FILENAME_RE.matcher(path.getFileName().toString());
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDateTime.parse(m.group(1) + m.group(2), TS_PARSE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * The capture time of a photo: the file name's timestamp when it has one,
     * else the file's mtime. Copying preserves mtimes (see {@code ImportPhotos}),
     * so for an imported photo this is still the moment it was taken.
     */
    private static LocalDateTime captureTime(Path path) throws IOException {
        LocalDateTime fromName = parseTimestamp(path);
        if (fromName != null) {
            return fromName;
        }
        return LocalDateTime.ofInstant(
                Files.getLastModifiedTime(path).toInstant(), ZoneId.systemDefault()).withNano(0);
    }

    /** The offer directory name for a series starting at {@code start}: {@code yyyyMMdd_HHmm}. */
    public static String dirLabel(LocalDateTime start) {
        return start.format(DIR_FORMAT);
    }

    /**
     * Clusters photos into time-contiguous series, ordered by time: sorted by
     * timestamp, then split wherever the gap to the previous photo exceeds
     * {@code gapThreshold} — the pause while the next item is put on the
     * turntable.
     */
    public static List<PhotoSeries> cluster(List<Path> photos, Duration gapThreshold)
            throws IOException {
        record Stamped(LocalDateTime ts, Path path) {
        }
        List<Stamped> stamped = new ArrayList<>();
        for (Path p : photos) {
            stamped.add(new Stamped(captureTime(p), p));
        }
        stamped.sort(Comparator.comparing(Stamped::ts));

        List<List<Stamped>> clusters = new ArrayList<>();
        for (Stamped s : stamped) {
            List<Stamped> last = clusters.isEmpty() ? null : clusters.get(clusters.size() - 1);
            if (last != null
                    && Duration.between(last.get(last.size() - 1).ts(), s.ts()).compareTo(gapThreshold) <= 0) {
                last.add(s);
            } else {
                List<Stamped> fresh = new ArrayList<>();
                fresh.add(s);
                clusters.add(fresh);
            }
        }

        List<PhotoSeries> series = new ArrayList<>();
        for (List<Stamped> cluster : clusters) {
            List<Path> paths = new ArrayList<>();
            for (Stamped s : cluster) {
                paths.add(s.path());
            }
            LocalDateTime start = cluster.get(0).ts();
            LocalDateTime end = cluster.get(cluster.size() - 1).ts();
            series.add(new PhotoSeries(dirLabel(start), start, end, paths));
        }
        return series;
    }
}
