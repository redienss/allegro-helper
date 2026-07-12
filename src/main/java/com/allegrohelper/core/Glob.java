package com.allegrohelper.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Expands a filesystem glob pattern whose wildcards ({@code *}) may appear in
 * individual path segments - used to locate the DCIM/OpenCamera directory on a
 * phone mounted via gvfs-mtp, e.g.
 * {@code /run/user/1000/gvfs/mtp:host=*}{@code /*}{@code /DCIM/OpenCamera}.
 */
public final class Glob {

    /** Not instantiable: the class is a namespace for {@link #expand}. */
    private Glob() {
    }

    /**
     * Returns matching existing paths, sorted lexicographically.
     *
     * <p>Walks the pattern segment by segment, so a wildcard is matched against
     * the directory entries at that level rather than against the whole path —
     * which is what makes the multi-wildcard MTP pattern work. Unreadable
     * directories are skipped rather than failing the expansion, since a phone
     * can vanish mid-scan.
     */
    public static List<Path> expand(String pattern) {
        boolean absolute = pattern.startsWith("/");
        String[] segments = pattern.split("/");

        List<Path> current = new ArrayList<>();
        current.add(absolute ? Path.of("/") : Path.of("."));

        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            List<Path> next = new ArrayList<>();
            if (segment.indexOf('*') < 0 && segment.indexOf('?') < 0) {
                for (Path base : current) {
                    Path candidate = base.resolve(segment);
                    if (Files.exists(candidate)) {
                        next.add(candidate);
                    }
                }
            } else {
                for (Path base : current) {
                    if (!Files.isDirectory(base)) {
                        continue;
                    }
                    try (DirectoryStream<Path> stream = Files.newDirectoryStream(base, segment)) {
                        for (Path child : stream) {
                            next.add(child);
                        }
                    } catch (IOException e) {
                        // Skip directories we cannot read.
                    }
                }
            }
            current = next;
            if (current.isEmpty()) {
                break;
            }
        }

        Collections.sort(current);
        return current;
    }
}
