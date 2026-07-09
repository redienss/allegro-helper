package com.allegrohelper.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Imports photos from the phone (mounted via gvfs-mtp) into the local working
 * directory. Files are only copied - the originals on the phone are left
 * untouched. Mirrors {@code allegro_bot/import_photos.py}.
 */
public final class ImportPhotos {

    private ImportPhotos() {
    }

    /** Locates the DCIM/OpenCamera directory on the phone, or throws if not found. */
    public static Path findSourceDir(Config cfg, Reporter reporter) throws IOException {
        List<Path> matches = Glob.expand(cfg.mtpGlobPattern);
        if (matches.isEmpty()) {
            throw new IOException(
                    "Could not find the DCIM/OpenCamera directory on the phone.\n"
                            + "Pattern searched: " + cfg.mtpGlobPattern + "\n"
                            + "Make sure the phone is connected and mounted (check with: gio mount -l).");
        }
        if (matches.size() > 1) {
            reporter.log("Warning: found more than one matching device, using the first one: "
                    + matches.get(0));
        }
        return matches.get(0);
    }

    public static void run(Config cfg, Reporter reporter) throws IOException {
        Path sourceDir = findSourceDir(cfg, reporter);
        Files.createDirectories(cfg.rawPhotosDir);

        List<Path> photos = listJpegs(sourceDir);
        if (photos.isEmpty()) {
            reporter.log("No photos to import in " + sourceDir + ".");
            reporter.stepProgress(1.0);
            return;
        }

        int copied = 0;
        int skipped = 0;
        int failed = 0;
        int total = photos.size();
        int index = 0;
        for (Path src : photos) {
            Path dest = cfg.rawPhotosDir.resolve(src.getFileName().toString());
            try {
                if (!Files.exists(dest)) {
                    Files.copy(src, dest, StandardCopyOption.COPY_ATTRIBUTES);
                    if (Files.size(dest) != Files.size(src)) {
                        Files.deleteIfExists(dest);
                        throw new IOException("copied file size does not match the original");
                    }
                    copied++;
                } else {
                    skipped++;
                }
            } catch (IOException exc) {
                failed++;
                reporter.log("Error importing " + src.getFileName() + ": " + exc.getMessage());
            }
            reporter.stepProgress((double) (++index) / total);
        }

        reporter.log(String.format(
                "Import finished: copied %d, skipped (already present) %d, failed %d. "
                        + "Originals remain on the phone.",
                copied, skipped, failed));
    }

    static List<Path> listJpegs(Path dir) throws IOException {
        List<Path> photos = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return photos;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(ImportPhotos::isJpeg).forEach(photos::add);
        }
        photos.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return photos;
    }

    static boolean isJpeg(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg");
    }
}
