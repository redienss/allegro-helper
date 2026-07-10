package com.allegrohelper.core;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Detects the photo series present in the photo directory (before import), so
 * the UI can show what's waiting to be imported. Uses the same series
 * recognition as the match step, so the preview matches what a run would do.
 */
public final class PhoneScan {

    public record Result(Path sourceDir, List<PhotoSeries> series) {
    }

    private PhoneScan() {
    }

    /**
     * Scans the photo directory and groups its photos into series.
     *
     * @throws IOException if the phone/DCIM directory cannot be found or read
     */
    public static Result scan(Config cfg) throws IOException {
        List<Path> matches = Glob.expand(cfg.mtpGlobPattern);
        if (matches.isEmpty()) {
            throw new IOException(
                    "No phone detected. Searched: " + cfg.mtpGlobPattern
                            + "\nConnect the phone and mount it (gio mount -l).");
        }
        Path sourceDir = matches.get(0);
        List<PhotoSeries> series = SeriesRecognition.recognize(cfg.seriesRecognition, sourceDir,
                Duration.ofSeconds(cfg.seriesGapThresholdSeconds));
        return new Result(sourceDir, series);
    }
}
