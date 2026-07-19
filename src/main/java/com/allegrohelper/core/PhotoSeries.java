package com.allegrohelper.core;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The photos of one offer. With auto-detection that is a series taken close
 * together in time (one item on the turntable); the other recognition modes
 * ({@link SeriesRecognition.Mode}) group by directory instead. The
 * {@link #label()} matches the offer directory name: {@code yyyyMMdd_HHmm} of
 * the series start, or the subfolder name in per-subfolder mode.
 */
public record PhotoSeries(String label, LocalDateTime start, LocalDateTime end, List<Path> photos) {

    /** How many photos the series holds; any count is valid. */
    public int count() {
        return photos.size();
    }
}
