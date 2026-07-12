package com.allegrohelper.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns the photo directory's contents into {@link PhotoSeries} according to
 * the user-selected recognition mode. {@link Mode#AUTO} is the original
 * turntable workflow (cluster by the timestamps encoded in OpenCamera
 * filenames). The other two modes exist for photos that were <em>not</em>
 * taken in one sitting — different cameras, times of day, or no turntable —
 * whose filenames may carry no parseable timestamp at all, so they derive the
 * series start/end from file modification times instead of filenames.
 */
public final class SeriesRecognition {

    /** How the photos in the photo directory are grouped into offers. */
    public enum Mode {
        /** Cluster by filename timestamps; a time gap starts a new series. */
        AUTO("auto"),
        /** Every photo in the directory is one offer (only the first CSV row is used). */
        SINGLE_ITEM("single"),
        /** Each subfolder holds one offer's photos; subfolders in name order match CSV rows. */
        SUBFOLDERS("subfolders");

        /** The value used in {@code SERIES_RECOGNITION} (env var / .env). */
        public final String key;

        Mode(String key) {
            this.key = key;
        }

        /** Parses a {@code SERIES_RECOGNITION} value; unknown or empty means {@link #AUTO}. */
        public static Mode parse(String value) {
            String v = value == null ? "" : value.strip();
            for (Mode m : values()) {
                if (m.key.equalsIgnoreCase(v)) {
                    return m;
                }
            }
            return AUTO;
        }
    }

    /** Not instantiable: the class is a namespace for {@link #recognize}. */
    private SeriesRecognition() {
    }

    /**
     * Recognizes the photo series present in {@code dir} according to
     * {@code mode}. The single entry point for grouping, used by both the match
     * step and the UI's Photos preview, so the preview always shows exactly
     * what a run would produce.
     *
     * @param gapThreshold only meaningful for {@link Mode#AUTO}: the pause that
     *                     separates two series
     */
    public static List<PhotoSeries> recognize(Mode mode, Path dir, Duration gapThreshold)
            throws IOException {
        return switch (mode) {
            case AUTO -> Clustering.cluster(ImportPhotos.listJpegs(dir), gapThreshold);
            case SINGLE_ITEM -> {
                List<Path> photos = ImportPhotos.listJpegs(dir);
                yield photos.isEmpty() ? List.of() : List.of(mtimeSeries(null, photos));
            }
            case SUBFOLDERS -> {
                List<PhotoSeries> series = new ArrayList<>();
                for (Path sub : listSubdirs(dir)) {
                    List<Path> photos = ImportPhotos.listJpegs(sub);
                    // A subfolder without photos is not an offer; skipping it keeps
                    // re-runs idempotent after the match step moved the photos out.
                    if (!photos.isEmpty()) {
                        series.add(mtimeSeries(sub.getFileName().toString(), photos));
                    }
                }
                yield series;
            }
        };
    }

    /** Immediate subdirectories of {@code dir}, sorted by name (the offer order). */
    static List<Path> listSubdirs(Path dir) throws IOException {
        List<Path> subs = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return subs;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isDirectory).forEach(subs::add);
        }
        subs.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return subs;
    }

    /**
     * Builds a series whose start/end come from file modification times. A null
     * label falls back to the {@code yyyyMMdd_HHmm} of the series start, the
     * same convention {@link Clustering} uses for offer directory names.
     */
    private static PhotoSeries mtimeSeries(String label, List<Path> photos) throws IOException {
        LocalDateTime start = null;
        LocalDateTime end = null;
        for (Path p : photos) {
            LocalDateTime ts = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(p).toInstant(), ZoneId.systemDefault()).withNano(0);
            if (start == null || ts.isBefore(start)) {
                start = ts;
            }
            if (end == null || ts.isAfter(end)) {
                end = ts;
            }
        }
        return new PhotoSeries(label != null ? label : Clustering.dirLabel(start), start, end, photos);
    }
}
