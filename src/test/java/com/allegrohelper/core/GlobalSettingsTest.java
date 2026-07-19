package com.allegrohelper.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settings live in {@code ~/.allegro-helper/.env}, not in the working
 * directory: they belong to the user, must survive reinstalling the app or
 * pointing it elsewhere, and the base directory is itself one of them — a file
 * found by way of the base directory cannot say what the base directory is.
 *
 * <p>Every test redirects the location, so the suite never reads or writes the
 * real one.
 */
class GlobalSettingsTest {

    @AfterEach
    void clearRedirect() {
        System.clearProperty("allegrohelper.config.dir");
    }

    private static Path redirect(Path tmp) {
        Path dir = tmp.resolve("config");
        System.setProperty("allegrohelper.config.dir", dir.toString());
        return dir;
    }

    private static void writeEnv(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void globalSettingsAreReadWhenTheBaseDirectoryHasNone(@TempDir Path tmp) throws IOException {
        Path config = redirect(tmp);
        writeEnv(config.resolve(".env"), "OPENAI_MODEL=gpt-4.1\n");

        assertEquals("gpt-4.1", Config.forBaseDir(tmp.resolve("work")).openaiModel);
    }

    @Test
    void globalSettingsWinOverABaseDirectoryFile(@TempDir Path tmp) throws IOException {
        // Otherwise a stale project-local file would shadow a save made in
        // Settings, and the save would look like it had done nothing.
        Path config = redirect(tmp);
        Path base = tmp.resolve("work");
        writeEnv(base.resolve(".env"), "OPENAI_MODEL=from-base-dir\n");
        writeEnv(config.resolve(".env"), "OPENAI_MODEL=from-global\n");

        assertEquals("from-global", Config.forBaseDir(base).openaiModel);
    }

    @Test
    void aBaseDirectoryFileStillSuppliesKeysTheGlobalOneDoesNot(@TempDir Path tmp)
            throws IOException {
        Path config = redirect(tmp);
        Path base = tmp.resolve("work");
        writeEnv(base.resolve(".env"), "OPENAI_MODEL=from-base-dir\nOCR_LANGUAGES=deu\n");
        writeEnv(config.resolve(".env"), "OPENAI_MODEL=from-global\n");

        Config cfg = Config.forBaseDir(base);
        assertEquals("from-global", cfg.openaiModel);
        assertEquals("deu", cfg.ocrLanguages, "an unrelated key must keep working");
    }

    @Test
    void callerOverridesStillWinOverEverything(@TempDir Path tmp) throws IOException {
        Path config = redirect(tmp);
        writeEnv(config.resolve(".env"), "SERIES_RECOGNITION=subfolders\n");

        Config cfg = Config.forBaseDir(tmp, Map.of("SERIES_RECOGNITION", "single"));
        assertEquals(SeriesRecognition.Mode.SINGLE_ITEM, cfg.seriesRecognition,
                "the UI's own controls outrank the saved settings");
    }

    @Test
    void updatingWritesToTheGlobalFileAndCreatesItsDirectory(@TempDir Path tmp) throws IOException {
        Path config = redirect(tmp);
        Map<String, String> values = new HashMap<>();
        values.put("SERIES_RECOGNITION", "single");

        Config.updateDotenv(values);

        assertTrue(Files.isRegularFile(config.resolve(".env")));
        assertEquals(SeriesRecognition.Mode.SINGLE_ITEM,
                Config.forBaseDir(tmp.resolve("anywhere")).seriesRecognition,
                "the setting must apply whatever directory the app is pointed at");
    }

    @Test
    void theSettingsFileIsNotWorldReadable(@TempDir Path tmp) throws IOException {
        // It holds an OpenAI API key; the default umask is not enough.
        Path config = redirect(tmp);
        Config.updateDotenv(Map.of("OPENAI_API_KEY", "sk-not-a-real-key"));

        var perms = Files.getPosixFilePermissions(config.resolve(".env"));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(perms));
    }

    @Test
    void migrationSeedsTheGlobalFileFromTheBaseDirectory(@TempDir Path tmp) throws IOException {
        Path config = redirect(tmp);
        Path base = tmp.resolve("work");
        writeEnv(base.resolve(".env"), "OPENAI_API_KEY=sk-existing\nOPENAI_MODEL=gpt-4o\n");

        Path copiedFrom = Config.migrateLegacyDotenv(base);

        assertNotNull(copiedFrom, "an existing install must keep its key");
        assertEquals("sk-existing", Config.forBaseDir(base).openaiApiKey);
        assertTrue(Files.isRegularFile(base.resolve(".env")),
                "the original is the user's file and must survive the copy");
    }

    @Test
    void migrationNeverOverwritesExistingSettings(@TempDir Path tmp) throws IOException {
        Path config = redirect(tmp);
        Path base = tmp.resolve("work");
        writeEnv(config.resolve(".env"), "OPENAI_MODEL=already-configured\n");
        writeEnv(base.resolve(".env"), "OPENAI_MODEL=stale-project-copy\n");

        assertNull(Config.migrateLegacyDotenv(base));
        assertEquals("already-configured", Config.forBaseDir(base).openaiModel);
    }

    @Test
    void migrationIsANoOpWithoutALegacyFile(@TempDir Path tmp) throws IOException {
        redirect(tmp);

        assertNull(Config.migrateLegacyDotenv(tmp.resolve("work")));
        assertNull(Config.migrateLegacyDotenv(tmp.resolve("does-not-exist")));
    }
}
