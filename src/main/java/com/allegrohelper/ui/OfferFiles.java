package com.allegrohelper.ui;

import com.allegrohelper.core.Config;
import com.allegrohelper.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Locating and reading the files behind an offer, for the UI.
 *
 * <p>These are the questions the window asks the filesystem — which directory
 * holds an offer's finished photos, which offer directory a grid row refers to,
 * what a text file contains — with no Swing in sight. They live apart from
 * {@link MainWindow} because they are pure functions of the filesystem, which
 * makes them the only part of the window that can be unit-tested directly; see
 * {@code OfferFilesTest}.
 */
final class OfferFiles {

    /** Not instantiable: the class is a namespace for its static helpers. */
    private OfferFiles() {
    }

    /**
     * The final photos of an offer: the output of the latest pipeline step that
     * has run — cropped, else contrasted, else brightened, else white-balanced,
     * else the pre-split {@code retouched/} kept for offers processed before the
     * retouch step was split up.
     *
     * <p>The single source of truth for "the finished photos": the galleries and
     * the <em>Open photo dir</em> button all call it, so they cannot drift.
     * {@code core/Ocr} and {@code core/AutoCrop} walk the same chain — theirs
     * additionally ends at {@code photos/}, since they must still work when
     * every retouching step was unticked, whereas a gallery showing
     * "Not available yet." is the right answer here.
     */
    static Path outputPhotoDir(Path offerDir) {
        for (String dirName : new String[] {
                "cropped", "contrasted", "brightened", "white_balanced", "retouched"}) {
            Path dir = offerDir.resolve(dirName);
            if (Files.isDirectory(dir)) {
                return dir;
            }
        }
        return offerDir.resolve("contrasted"); // nonexistent: the gallery shows "Not available yet."
    }

    /**
     * Finds the offer directory for a grid row: first by matching the row's
     * {@code name} against each {@code data.json}, then falling back to the
     * row's position among the sorted offer directories (which is the order the
     * match step assigns them).
     *
     * <p>The name match comes first because rows can be reordered in the grid
     * after a run, which would make position alone point at the wrong offer.
     */
    static Path resolveOfferDir(Config cfg, String name, int index) {
        if (!Files.isDirectory(cfg.offersDir)) {
            return null;
        }
        List<Path> dirs = new ArrayList<>();
        try (var stream = Files.list(cfg.offersDir)) {
            stream.filter(Files::isDirectory).forEach(dirs::add);
        } catch (IOException e) {
            return null;
        }
        dirs.sort(Comparator.comparing(p -> p.getFileName().toString()));

        String target = name == null ? "" : name.strip();
        if (!target.isEmpty()) {
            for (Path dir : dirs) {
                Path dataJson = dir.resolve("data.json");
                if (!Files.isRegularFile(dataJson)) {
                    continue;
                }
                try {
                    Map<String, Object> data = Json.parseObject(
                            Files.readString(dataJson, StandardCharsets.UTF_8));
                    Object nm = data.get("name");
                    if (nm != null && nm.toString().strip().equals(target)) {
                        return dir;
                    }
                } catch (Exception ignored) {
                    // Skip unreadable/invalid data.json.
                }
            }
        }
        return index >= 0 && index < dirs.size() ? dirs.get(index) : null;
    }

    /**
     * A file's contents, or an empty string when it does not exist yet — an
     * offer that has not reached a step simply shows an empty editor. A read
     * error is returned as the text, so it is visible rather than silent.
     */
    static String readIfExists(Path file) {
        if (file != null && Files.isRegularFile(file)) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "Could not read " + file + ": " + e.getMessage();
            }
        }
        return "";
    }

    /** Escapes text for the HTML-rendered details header, so an offer name cannot break its markup. */
    static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Whether the file name ends in {@code .jpg}/{@code .jpeg} (case-insensitive). */
    static boolean isJpeg(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg");
    }

    /** The deepest existing directory along the given path, stopping at any glob segment. */
    static Path deepestExistingDir(String pattern) {
        if (!pattern.startsWith("/")) {
            return null;
        }
        Path result = null;
        Path current = Path.of("/");
        for (String segment : pattern.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (segment.indexOf('*') >= 0 || segment.indexOf('?') >= 0) {
                break;
            }
            current = current.resolve(segment);
            if (!Files.isDirectory(current)) {
                break;
            }
            result = current;
        }
        return result;
    }

    /** Deletes a tree depth-first. Unlike the OCR step's, failures here are surfaced, not ignored. */
    static void deleteRecursively(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
