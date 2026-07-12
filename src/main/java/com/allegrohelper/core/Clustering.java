package com.allegrohelper.core;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Groups photos into series based on the timestamp encoded in the filename
 * (OpenCamera convention: {@code IMG_YYYYMMDD_HHMMSS.jpg}). A gap larger than
 * the threshold marks the start of a new series.
 */
public final class Clustering {

    private static final Pattern FILENAME_RE = Pattern.compile("IMG_(\\d{8})_(\\d{6})");
    private static final DateTimeFormatter TS_PARSE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DIR_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    /** Not instantiable: the class is a namespace for its static helpers. */
    private Clustering() {
    }

    /**
     * Reads the capture time out of an OpenCamera file name.
     *
     * @throws IllegalArgumentException if the name does not carry a timestamp —
     *         photos from another camera have none, which is why the non-auto
     *         recognition modes use file mtimes instead of this
     */
    public static LocalDateTime parseTimestamp(Path path) {
        Matcher m = FILENAME_RE.matcher(path.getFileName().toString());
        if (!m.find()) {
            throw new IllegalArgumentException(
                    "File name " + path.getFileName()
                            + " does not match the IMG_YYYYMMDD_HHMMSS.jpg pattern");
        }
        return LocalDateTime.parse(m.group(1) + m.group(2), TS_PARSE);
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
    public static List<PhotoSeries> cluster(List<Path> photos, Duration gapThreshold) {
        record Stamped(LocalDateTime ts, Path path) {
        }
        List<Stamped> stamped = new ArrayList<>();
        for (Path p : photos) {
            stamped.add(new Stamped(parseTimestamp(p), p));
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
