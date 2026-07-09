package com.allegrohelper.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Project configuration, mirroring {@code allegro_bot/config.py}.
 *
 * <p>Every value is derived from the base directory but can be overridden via
 * environment variables or a {@code .env} file in the base directory. As in
 * python-dotenv, a real environment variable takes precedence over a value in
 * {@code .env}.
 */
public final class Config {

    public final Path baseDir;
    public final Path csvPath;
    public final Path rawPhotosDir;
    public final Path offersDir;
    public final String mtpUid;
    public final String mtpGlobPattern;
    public final int photosPerOffer;
    public final int seriesGapThresholdSeconds;
    public final String openaiApiKey;
    public final String openaiModel;
    public final String openaiBaseUrl;

    private Config(Path baseDir, Map<String, String> env) {
        this.baseDir = baseDir;
        this.csvPath = pathOrDefault(env, "CSV_PATH", baseDir.resolve("offers.csv"));
        this.rawPhotosDir = pathOrDefault(env, "RAW_PHOTOS_DIR", baseDir.resolve("raw_photos"));
        this.offersDir = pathOrDefault(env, "OFFERS_DIR", baseDir.resolve("offers"));
        this.mtpUid = env.getOrDefault("MTP_UID", detectUid());
        this.mtpGlobPattern = env.getOrDefault(
                "MTP_GLOB_PATTERN",
                "/run/user/" + mtpUid + "/gvfs/mtp:host=*/*/DCIM/OpenCamera");
        this.photosPerOffer = intOrDefault(env, "PHOTOS_PER_OFFER", 20);
        this.seriesGapThresholdSeconds = intOrDefault(env, "SERIES_GAP_THRESHOLD_SECONDS", 60);
        this.openaiApiKey = env.getOrDefault("OPENAI_API_KEY", "");
        this.openaiModel = env.getOrDefault("OPENAI_MODEL", "gpt-4o-mini");
        this.openaiBaseUrl = stripTrailingSlash(
                env.getOrDefault("OPENAI_BASE_URL", "https://api.openai.com/v1"));
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Builds a configuration for the given base directory, loading {@code .env} if present. */
    public static Config forBaseDir(Path baseDir) {
        Path base = baseDir.toAbsolutePath().normalize();
        Map<String, String> env = new HashMap<>(loadDotenv(base.resolve(".env")));
        // Real environment variables win over .env, matching python-dotenv defaults.
        env.putAll(System.getenv());
        return new Config(base, env);
    }

    private static Map<String, String> loadDotenv(Path envFile) {
        Map<String, String> values = new HashMap<>();
        if (!Files.isRegularFile(envFile)) {
            return values;
        }
        try {
            List<String> lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);
            for (String raw : lines) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring("export ".length()).strip();
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                value = stripQuotes(value);
                values.put(key, value);
            }
        } catch (IOException e) {
            // A malformed .env should not prevent the app from starting.
        }
        return values;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static Path pathOrDefault(Map<String, String> env, String key, Path fallback) {
        String v = env.get(key);
        return (v == null || v.isBlank()) ? fallback : Path.of(v);
    }

    private static int intOrDefault(Map<String, String> env, String key, int fallback) {
        String v = env.get(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String detectUid() {
        try {
            Object uid = Files.getAttribute(Path.of("/proc/self"), "unix:uid");
            if (uid != null) {
                return uid.toString();
            }
        } catch (Exception ignored) {
            // Non-Linux or restricted /proc; fall back to a sensible default.
        }
        return "1000";
    }
}
