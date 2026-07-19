package com.allegrohelper.ui;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.prefs.Preferences;

/**
 * The base directory the UI opens on, remembered across launches.
 *
 * <p>Every other configurable value lives in the base directory's {@code .env}.
 * This one cannot: {@code .env} is found <em>by way of</em> the base directory,
 * so storing the base directory inside it would be circular. Like the theme and
 * the language it is a per-user preference about how the app starts, so it goes
 * in {@link Preferences}.
 *
 * <p>Precedence when the UI launches: an explicit command-line argument, then
 * this saved default, then the working directory. The argument stays on top
 * because {@code allegro-helper /some/dir} has to mean that directory — a saved
 * default that quietly overrode it would make the argument a lie.
 */
public final class BaseDir {

    private static final String PREF_KEY = "baseDir";

    /** Not instantiable: the class is a namespace for the two accessors. */
    private BaseDir() {
    }

    /**
     * The saved default, or {@code fallback} when none is saved or the saved
     * one no longer exists — a directory that has since been renamed or was on
     * a drive that is not mounted must not strand the user in a base directory
     * whose every derived path is broken.
     */
    public static Path load(Path fallback) {
        String saved = prefs().get(PREF_KEY, "");
        if (saved.isBlank()) {
            return fallback;
        }
        try {
            Path path = Path.of(saved);
            return Files.isDirectory(path) ? path : fallback;
        } catch (InvalidPathException e) {
            return fallback;
        }
    }

    /** Saves the default, or clears it when {@code path} is null. */
    public static void save(Path path) {
        if (path == null) {
            prefs().remove(PREF_KEY);
        } else {
            prefs().put(PREF_KEY, path.toAbsolutePath().normalize().toString());
        }
    }

    private static Preferences prefs() {
        return Preferences.userNodeForPackage(BaseDir.class);
    }
}
