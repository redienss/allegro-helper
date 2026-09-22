package com.allegrohelper.core;

import com.allegrohelper.util.Csv;
import com.allegrohelper.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Matches imported photos ({@code raw_photos/}) to rows in {@code offers.csv},
 * splitting them into per-offer directories.
 */
public final class GroupAndMatch {

    /** Not instantiable: the class is a namespace for {@link #run}. */
    private GroupAndMatch() {
    }

    /**
     * Groups {@code raw_photos/} into series, pairs each with a CSV row
     * <em>by position</em> (so row order is load-bearing) and moves the photos
     * into {@code offers/<label>/photos/}, writing {@code data.json} alongside.
     *
     * <p>Refuses to move anything when the series count differs from the row
     * count: a positional match that is off by one would mislabel every
     * following offer, and the photos have already left the phone's ordering
     * behind — so it logs what it detected and aborts the run. Idempotent: an
     * offer directory that already exists is left alone.
     */
    public static void run(Config cfg, Reporter reporter) throws IOException, PipelineException {
        List<Map<String, String>> offers = loadOffers(cfg.csvPath);
        if (cfg.seriesRecognition == SeriesRecognition.Mode.SINGLE_ITEM && offers.size() > 1) {
            reporter.log("Series recognition is 'all photos as one item': using only the first"
                    + " of the " + offers.size() + " CSV rows.");
            offers = offers.subList(0, 1);
        }

        Duration gap = Duration.ofSeconds(cfg.seriesGapThresholdSeconds);
        List<PhotoSeries> clusters =
                SeriesRecognition.recognize(cfg.seriesRecognition, cfg.rawPhotosDir, gap);

        if (clusters.isEmpty()) {
            reporter.log("No photos in " + cfg.rawPhotosDir + " to match.");
            reporter.stepProgress(1.0);
            return;
        }

        boolean video = cfg.seriesRecognition == SeriesRecognition.Mode.VIDEO;
        if (clusters.size() != offers.size()) {
            boolean subfolders = cfg.seriesRecognition == SeriesRecognition.Mode.SUBFOLDERS;
            StringBuilder sb = new StringBuilder();
            sb.append(subfolders
                            ? "The number of photo subfolders ("
                            : video
                                    ? "The number of video files found ("
                                    : "The number of detected photo series (")
                    .append(clusters.size())
                    .append(") does not match the number of rows in ").append(cfg.csvPath)
                    .append(" (").append(offers.size()).append("). Nothing was moved.\n")
                    .append("Detected series:\n");
            int i = 1;
            for (PhotoSeries c : clusters) {
                sb.append("  series ").append(i++).append(" (").append(c.label()).append("): ")
                        .append(c.count()).append(video ? " video" : " photos").append(", ")
                        .append(c.start()).append(" -> ").append(c.end())
                        .append('\n');
            }
            sb.append(subfolders
                    ? "Check the subfolders in " + cfg.rawPhotosDir
                            + " or the order/count of rows in the CSV."
                    : video
                            ? "Check the video files in " + cfg.rawPhotosDir
                                    + " or the order/count of rows in the CSV."
                            : "Check SERIES_GAP_THRESHOLD_SECONDS (currently "
                                    + cfg.seriesGapThresholdSeconds
                                    + "s) or the order/count of rows in the CSV.");
            reporter.log(sb.toString());
            throw new PipelineException("Photo series / CSV row count mismatch; nothing was moved.");
        }

        Files.createDirectories(cfg.offersDir);
        if (video) {
            VideoFrames.requireFfmpeg();
        }

        int total = offers.size();
        for (int index = 0; index < total; index++) {
            Map<String, String> offer = offers.get(index);
            PhotoSeries cluster = clusters.get(index);

            String offerDirName = cluster.label();
            Path offerDir = cfg.offersDir.resolve(offerDirName);
            // Written before extraction, removed only on success — a run
            // interrupted mid-extraction (missing ffmpeg, killed process, a
            // corrupt video) must not leave a half-populated offer that the
            // exists-check below silently treats as finished.
            Path extractMarker = offerDir.resolve(".video-extract-in-progress");
            boolean interrupted = video && Files.exists(offerDir) && Files.exists(extractMarker);

            if (Files.exists(offerDir) && !interrupted) {
                // The source stays in raw_photos/, where the next run will
                // group it with whatever else is there — that is how photos
                // (or a video) of a previous listing end up inside a new
                // offer. Say so, rather than let it be discovered later.
                reporter.log("Directory " + offerDir
                        + " already exists, skipping (assuming already processed). Its "
                        + cluster.count() + (video ? " source video stays in " : " photos stay in ")
                        + cfg.rawPhotosDir
                        + " and will be grouped with the next import unless removed.");
                reporter.stepProgress((double) (index + 1) / total);
                continue;
            }

            Path photosDir = offerDir.resolve("photos");
            if (interrupted) {
                reporter.log(offerDirName + ": a previous frame extraction was interrupted,"
                        + " redoing it.");
                deleteRecursively(photosDir);
            }
            Files.createDirectories(photosDir);

            List<String> photoNames;
            if (video) {
                Path sourceVideo = cluster.photos().get(0);
                Files.writeString(extractMarker, "", StandardCharsets.UTF_8);
                VideoFrames.extract(sourceVideo, photosDir, cfg.videoFrameIntervalSeconds, reporter);
                Files.delete(extractMarker);
                // Consumed: only the local raw_photos/ copy is removed, never
                // the phone's original, which Import only ever copies from.
                Files.delete(sourceVideo);
                List<Path> frames = ImportPhotos.listJpegs(photosDir);
                photoNames = new ArrayList<>();
                for (Path frame : frames) {
                    photoNames.add(frame.getFileName().toString());
                }
            } else {
                photoNames = new ArrayList<>();
                for (Path photo : cluster.photos()) {
                    Files.move(photo, photosDir.resolve(photo.getFileName().toString()));
                    photoNames.add(photo.getFileName().toString());
                }
            }

            Path moreDataSrc = cfg.csvPath.getParent() == null
                    ? Path.of("more_data_" + (index + 1) + ".txt")
                    : cfg.csvPath.getParent().resolve("more_data_" + (index + 1) + ".txt");
            if (Files.exists(moreDataSrc)) {
                Files.copy(moreDataSrc, offerDir.resolve("more_data.txt"),
                        StandardCopyOption.COPY_ATTRIBUTES);
                reporter.log("Copied " + moreDataSrc.getFileName() + " -> "
                        + offerDirName + "/more_data.txt");
            }

            writeDataJson(offerDir.resolve("data.json"), offer, photoNames);

            reporter.log("Created offer " + offerDirName + " (" + photoNames.size()
                    + " photos) for '" + offer.getOrDefault("name", "") + "'.");
            reporter.stepProgress((double) (index + 1) / total);
        }
    }

    /** Deletes {@code dir} and everything under it; a missing directory is a no-op. */
    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Reads the offer rows from the CSV.
     *
     * @throws PipelineException if the file is missing or holds no rows — with
     *         nothing to match against, guessing would be worse than stopping
     */
    static List<Map<String, String>> loadOffers(Path csvPath) throws IOException, PipelineException {
        if (!Files.isRegularFile(csvPath)) {
            throw new PipelineException("CSV file " + csvPath + " does not exist.");
        }
        List<Map<String, String>> rows = Csv.read(csvPath);
        if (rows.isEmpty()) {
            throw new PipelineException("File " + csvPath + " contains no offer rows.");
        }
        return rows;
    }

    /**
     * Writes the offer's {@code data.json}: every CSV column of the row, plus
     * the photo count, the photo file names and a creation timestamp. This file
     * is what every later step reads the offer's facts from.
     *
     * <p>Takes the final photo file names rather than the {@link PhotoSeries}
     * itself: in video mode {@code cluster.photos()} holds the source video, a
     * placeholder for what {@link VideoFrames#extract} actually wrote.
     */
    private static void writeDataJson(Path path, Map<String, String> offer, List<String> photoNames)
            throws IOException {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>(offer);
        data.put("photo_count", photoNames.size());
        data.put("photos", photoNames);
        data.put("created_at", LocalDateTime.now().withNano(0).toString());
        Files.writeString(path, Json.write(data, true), StandardCharsets.UTF_8);
    }
}
