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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Matches imported photos ({@code raw_photos/}) to rows in {@code offers.csv},
 * splitting them into per-offer directories.
 */
public final class GroupAndMatch {

    private GroupAndMatch() {
    }

    public static void run(Config cfg, Reporter reporter) throws IOException, PipelineException {
        List<Map<String, String>> offers = loadOffers(cfg.csvPath);
        List<Path> photos = ImportPhotos.listJpegs(cfg.rawPhotosDir);

        if (photos.isEmpty()) {
            reporter.log("No photos in " + cfg.rawPhotosDir + " to match.");
            reporter.stepProgress(1.0);
            return;
        }

        Duration gap = Duration.ofSeconds(cfg.seriesGapThresholdSeconds);
        List<PhotoSeries> clusters = Clustering.cluster(photos, gap);

        if (clusters.size() != offers.size()) {
            StringBuilder sb = new StringBuilder();
            sb.append("The number of detected photo series (").append(clusters.size())
                    .append(") does not match the number of rows in ").append(cfg.csvPath)
                    .append(" (").append(offers.size()).append("). Nothing was moved.\n")
                    .append("Detected series:\n");
            int i = 1;
            for (PhotoSeries c : clusters) {
                sb.append("  series ").append(i++).append(": ").append(c.count())
                        .append(" photos, ").append(c.start()).append(" -> ").append(c.end())
                        .append('\n');
            }
            sb.append("Check SERIES_GAP_THRESHOLD_SECONDS (currently ")
                    .append(cfg.seriesGapThresholdSeconds)
                    .append("s) or the order/count of rows in the CSV.");
            reporter.log(sb.toString());
            throw new PipelineException("Photo series / CSV row count mismatch; nothing was moved.");
        }

        Files.createDirectories(cfg.offersDir);

        int total = offers.size();
        for (int index = 0; index < total; index++) {
            Map<String, String> offer = offers.get(index);
            PhotoSeries cluster = clusters.get(index);

            if (cluster.count() != cfg.photosPerOffer) {
                reporter.log("Warning: the series for offer '" + offer.getOrDefault("name", "")
                        + "' has " + cluster.count() + " photos (expected " + cfg.photosPerOffer + ").");
            }

            String offerDirName = cluster.label();
            Path offerDir = cfg.offersDir.resolve(offerDirName);

            if (Files.exists(offerDir)) {
                reporter.log("Directory " + offerDir
                        + " already exists, skipping (assuming already processed).");
                reporter.stepProgress((double) (index + 1) / total);
                continue;
            }

            Path photosDir = offerDir.resolve("photos");
            Files.createDirectories(photosDir);

            for (Path photo : cluster.photos()) {
                Files.move(photo, photosDir.resolve(photo.getFileName().toString()));
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

            writeDataJson(offerDir.resolve("data.json"), offer, cluster);

            reporter.log("Created offer " + offerDirName + " (" + cluster.count()
                    + " photos) for '" + offer.getOrDefault("name", "") + "'.");
            reporter.stepProgress((double) (index + 1) / total);
        }
    }

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

    private static void writeDataJson(Path path, Map<String, String> offer, PhotoSeries cluster)
            throws IOException {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>(offer);
        data.put("photo_count", cluster.count());
        List<String> names = new ArrayList<>();
        for (Path p : cluster.photos()) {
            names.add(p.getFileName().toString());
        }
        data.put("photos", names);
        data.put("created_at", LocalDateTime.now().withNano(0).toString());
        Files.writeString(path, Json.write(data, true), StandardCharsets.UTF_8);
    }
}
