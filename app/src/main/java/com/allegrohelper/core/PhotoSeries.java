package com.allegrohelper.core;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A series of photos taken close together in time - one item on the turntable,
 * i.e. one offer. The {@link #label()} matches the offer directory name
 * ({@code yyyyMMdd_HHmm} of the first photo).
 */
public record PhotoSeries(String label, LocalDateTime start, LocalDateTime end, List<Path> photos) {

    public int count() {
        return photos.size();
    }
}
