package com.allegrohelper.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Imports photos from the phone (mounted via gvfs-mtp) into the local working
 * directory. Files are only copied - the originals on the phone are left
 * untouched.
 */
public final class ImportPhotos {

    /** Not instantiable: the class is a namespace for {@link #run}. */
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

    /**
     * Copies every photo from the phone into {@code raw_photos/}.
     *
     * <p>Idempotent: a photo whose destination already exists is skipped, so
     * the step can be re-run freely. A copy is verified by size and deleted if
     * it comes up short — an MTP mount can truncate a transfer — and a failure
     * on one photo is logged and does not abort the import. In per-subfolder
     * mode the subfolder structure is preserved, because there it <em>is</em>
     * the series grouping.
     *
     * <p><b>A photo already matched into an offer is not staged again.</b>
     * {@code raw_photos/} is a staging area, not an archive: Match <em>moves</em>
     * out of it, so after a run it is empty. But the originals stay on the phone
     * by design, so the next import re-copied them — and Match, finding the offer
     * directory already there, skipped it and left them stranded in
     * {@code raw_photos/}. They then joined the *next* item's series, which is how
     * two photos of a new item became one six-photo offer mixing it with the
     * previous listing. Skipping anything already sitting in an offer's
     * {@code photos/} closes that loop at the source; deleting the offer directory
     * still re-imports, because the check is only ever about what exists now.
     */
    public static void run(Config cfg, Reporter reporter) throws IOException {
        Path sourceDir = findSourceDir(cfg, reporter);
        Files.createDirectories(cfg.rawPhotosDir);

        // Source -> destination, resolved up front so both layouts share one
        // copy loop. In subfolder mode the structure is the series grouping,
        // so it must survive the trip into raw_photos.
        Map<Path, Path> photos = new LinkedHashMap<>();
        if (cfg.seriesRecognition == SeriesRecognition.Mode.SUBFOLDERS) {
            for (Path sub : SeriesRecognition.listSubdirs(sourceDir)) {
                Path destDir = cfg.rawPhotosDir.resolve(sub.getFileName().toString());
                for (Path src : listJpegs(sub)) {
                    photos.put(src, destDir.resolve(src.getFileName().toString()));
                }
            }
        } else {
            for (Path src : listJpegs(sourceDir)) {
                photos.put(src, cfg.rawPhotosDir.resolve(src.getFileName().toString()));
            }
        }

        if (photos.isEmpty()) {
            reporter.log("No photos to import in " + sourceDir
                    + (cfg.seriesRecognition == SeriesRecognition.Mode.SUBFOLDERS
                            ? " (series recognition is per-subfolder, so only its subfolders were searched)."
                            : "."));
            reporter.stepProgress(1.0);
            return;
        }

        Set<String> alreadyMatched = matchedPhotoNames(cfg.offersDir);

        int copied = 0;
        int skipped = 0;
        int matched = 0;
        int failed = 0;
        int total = photos.size();
        int index = 0;
        for (Map.Entry<Path, Path> entry : photos.entrySet()) {
            Path src = entry.getKey();
            Path dest = entry.getValue();
            try {
                if (alreadyMatched.contains(src.getFileName().toString())) {
                    matched++;
                    reporter.stepProgress((double) (++index) / total);
                    continue;
                }
                Files.createDirectories(dest.getParent());
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
                "Import finished: copied %d, skipped (already present) %d, "
                        + "skipped (already in an offer) %d, failed %d. "
                        + "Originals remain on the phone.",
                copied, skipped, matched, failed));
    }

    /**
     * The file names of every photo already matched into an offer, i.e. sitting
     * in some {@code offers/<label>/photos/}. Names only: the phone's are unique
     * timestamps, and the offer label a photo landed under says nothing about
     * where it would land now. A missing offers directory yields an empty set,
     * which is exactly right for a first run.
     */
    static Set<String> matchedPhotoNames(Path offersDir) throws IOException {
        Set<String> names = new HashSet<>();
        if (!Files.isDirectory(offersDir)) {
            return names;
        }
        try (var offers = Files.list(offersDir)) {
            for (Path offer : (Iterable<Path>) offers.sorted()::iterator) {
                Path photos = offer.resolve("photos");
                if (!Files.isDirectory(photos)) {
                    continue;
                }
                for (Path photo : listJpegs(photos)) {
                    names.add(photo.getFileName().toString());
                }
            }
        }
        return names;
    }

    /** The JPEGs directly in {@code dir}, sorted by name; empty if it is not a directory. */
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

    /** Whether the file name ends in {@code .jpg}/{@code .jpeg} (case-insensitive). */
    static boolean isJpeg(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg");
    }
}
