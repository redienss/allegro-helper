package com.allegrohelper.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Project configuration.
 *
 * <p>Every value is derived from the base directory but can be overridden via
 * environment variables or a {@code .env} file in the base directory. A real
 * environment variable takes precedence over a value in {@code .env}.
 */
public final class Config {

    public static final String DEFAULT_OPENAI_MODEL = "gpt-4o-mini";

    public final Path baseDir;
    public final Path csvPath;
    public final Path rawPhotosDir;
    public final Path offersDir;
    public final String mtpUid;
    public final String mtpGlobPattern;
    public final int seriesGapThresholdSeconds;
    public final SeriesRecognition.Mode seriesRecognition;
    /** The brightness step's strength dial; see {@link Retouch#DEFAULT_BRIGHTNESS}. */
    public final double brightnessStrength;
    /** The contrast step's strength dial; see {@link Retouch#DEFAULT_CONTRAST}. */
    public final double contrastStrength;
    public final String ocrLanguages;
    public final String openaiApiKey;
    public final String openaiModel;
    public final String openaiBaseUrl;
    public final String openaiSystemPrompt;
    public final String openaiUserPrompt;
    public final String chromeBin;
    public final Path chromeProfileDir;

    /**
     * The phone's photo directory as mounted by gvfs-mtp, for a given user id.
     * Public so File &gt; Settings can tell a real override from the built-in
     * default and leave {@code .env} carrying only the former.
     */
    public static String defaultMtpGlobPattern(String mtpUid) {
        return "/run/user/" + mtpUid + "/gvfs/mtp:host=*/*/DCIM/OpenCamera";
    }

    /**
     * @param env the merged lookup — {@code .env}, overridden by the real
     *            environment, overridden by caller-supplied values
     */
    private Config(Path baseDir, Map<String, String> env) {
        this.baseDir = baseDir;
        this.csvPath = pathOrDefault(env, "CSV_PATH", baseDir.resolve("offers.csv"));
        this.rawPhotosDir = pathOrDefault(env, "RAW_PHOTOS_DIR", baseDir.resolve("raw_photos"));
        this.offersDir = pathOrDefault(env, "OFFERS_DIR", baseDir.resolve("offers"));
        this.mtpUid = env.getOrDefault("MTP_UID", detectUid());
        this.mtpGlobPattern = env.getOrDefault("MTP_GLOB_PATTERN", defaultMtpGlobPattern(mtpUid));
        this.seriesGapThresholdSeconds = intOrDefault(env, "SERIES_GAP_THRESHOLD_SECONDS", 60);
        this.seriesRecognition = SeriesRecognition.Mode.parse(env.get("SERIES_RECOGNITION"));
        this.brightnessStrength = Retouch.clampStrength(
                doubleOrDefault(env, "BRIGHTNESS_STRENGTH", Retouch.DEFAULT_BRIGHTNESS));
        this.contrastStrength = Retouch.clampStrength(
                doubleOrDefault(env, "CONTRAST_STRENGTH", Retouch.DEFAULT_CONTRAST));
        this.ocrLanguages = env.getOrDefault("OCR_LANGUAGES", "pol+eng");
        this.openaiApiKey = env.getOrDefault("OPENAI_API_KEY", "");
        this.openaiModel = stringOrDefault(env, "OPENAI_MODEL", DEFAULT_OPENAI_MODEL);
        this.openaiBaseUrl = stripTrailingSlash(
                env.getOrDefault("OPENAI_BASE_URL", "https://api.openai.com/v1"));
        // Blank falls back to the default: an empty prompt is never useful.
        this.openaiSystemPrompt =
                stringOrDefault(env, "OPENAI_SYSTEM_PROMPT", GenerateDescription.SYSTEM_PROMPT);
        this.openaiUserPrompt =
                stringOrDefault(env, "OPENAI_USER_PROMPT", GenerateDescription.USER_PROMPT);
        this.chromeBin = env.getOrDefault("CHROME_BIN", "");
        // A dedicated profile: Chrome only exposes DevTools on a fresh instance,
        // and the Allegro login session persists in it between runs.
        this.chromeProfileDir =
                pathOrDefault(env, "CHROME_PROFILE_DIR", baseDir.resolve(".chrome-profile"));
    }

    /** Drops a trailing slash so the endpoint paths can be appended verbatim. */
    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Builds a configuration for the given base directory, loading {@code .env} if present. */
    public static Config forBaseDir(Path baseDir) {
        return forBaseDir(baseDir, Map.of());
    }

    /**
     * Like {@link #forBaseDir(Path)}, with caller-supplied overrides (e.g. values
     * the user typed into the UI) that win over everything else.
     *
     * <p>Lowest to highest: the base directory's {@code .env}, the user's
     * {@link #globalEnvPath() global settings}, real environment variables,
     * then the overrides. The global file wins over the base directory's
     * because it is what File &gt; Settings writes — a save that a stale
     * project-local file could shadow would look like it had done nothing.
     */
    public static Config forBaseDir(Path baseDir, Map<String, String> overrides) {
        Path base = baseDir.toAbsolutePath().normalize();
        Map<String, String> env = new HashMap<>(loadDotenv(base.resolve(".env")));
        env.putAll(loadDotenv(globalEnvPath()));
        // Real environment variables win over any file.
        env.putAll(System.getenv());
        env.putAll(overrides);
        return new Config(base, env);
    }

    /**
     * The user's own settings file, {@code ~/.allegro-helper/.env}.
     *
     * <p>Settings live here rather than in the base directory because they
     * belong to the user, not to a folder of photos: they have to survive
     * reinstalling the app, deleting the working directory, or pointing the app
     * at a different one. It also breaks a circularity — the base directory is
     * itself a setting, and a file found by way of the base directory cannot
     * tell you what the base directory is.
     *
     * <p>{@code ALLEGRO_HELPER_CONFIG_DIR} overrides the location (the tests
     * use the {@code allegrohelper.config.dir} property for the same reason:
     * the suite must never read or write the real one).
     */
    public static Path globalEnvPath() {
        return configDir().resolve(".env");
    }

    private static Path configDir() {
        // The property is checked first on purpose: only the build sets it, and
        // it is what keeps the test suite off the developer's own settings. An
        // environment variable able to override it would put that isolation at
        // the mercy of whatever happens to be exported.
        String fromProperty = System.getProperty("allegrohelper.config.dir");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return Path.of(fromProperty);
        }
        String fromEnv = System.getenv("ALLEGRO_HELPER_CONFIG_DIR");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Path.of(fromEnv);
        }
        return Path.of(System.getProperty("user.home"), ".allegro-helper");
    }

    /**
     * The base directory saved in File &gt; Settings, or {@code fallback} when
     * none is saved or the saved one no longer exists — a directory since
     * renamed, or on a drive that is not mounted, must not strand the user
     * somewhere every derived path is broken.
     *
     * <p>It lives in the settings file like everything else. It could not,
     * while settings lived in the base directory: a file found by way of the
     * base directory cannot say what the base directory is. Once they moved to
     * {@link #globalEnvPath()} that knot came undone, and keeping it in
     * {@link java.util.prefs.Preferences} instead would only have meant one
     * setting the user cannot see next to the others.
     *
     * <p>An explicit command-line argument still outranks this: {@code
     * allegro-helper /some/dir} has to mean that directory.
     */
    public static Path savedBaseDir(Path fallback) {
        Map<String, String> env = new HashMap<>(loadDotenv(globalEnvPath()));
        env.putAll(System.getenv());
        String saved = env.get("BASE_DIR");
        if (saved == null || saved.isBlank()) {
            return fallback;
        }
        try {
            Path path = Path.of(saved);
            return Files.isDirectory(path) ? path : fallback;
        } catch (java.nio.file.InvalidPathException e) {
            return fallback;
        }
    }

    /**
     * Seeds the global settings from a base directory's {@code .env} the first
     * time, so an existing install keeps its API key and prompts without the
     * user re-entering them.
     *
     * <p>Copies rather than moves: the original is the user's file, it may hold
     * keys this app never wrote, and a base directory that is also a git
     * checkout should not appear to have lost a file. Does nothing once the
     * global file exists, so it cannot overwrite newer settings.
     *
     * @return the path copied from, or null when there was nothing to do
     */
    public static Path migrateLegacyDotenv(Path baseDir) throws IOException {
        Path global = globalEnvPath();
        if (Files.exists(global)) {
            return null;
        }
        Path legacy = baseDir.toAbsolutePath().normalize().resolve(".env");
        if (!Files.isRegularFile(legacy)) {
            return null;
        }
        createConfigDir();
        Files.copy(legacy, global);
        restrictPermissions(global);
        return legacy;
    }

    /**
     * Written at the top of a settings file this app creates. English like
     * every other comment in the project, and it describes <em>this</em> file:
     * a copy of {@code .env.example} would open with "copy this file to .env",
     * which is advice for a project checkout and nonsense in the user's
     * settings directory.
     */
    private static final List<String> SETTINGS_HEADER = List.of(
            "# Allegro Helper settings, shared by every base directory.",
            "# Written by File > Settings; editing by hand works too.",
            "# A real environment variable of the same name overrides what is here.",
            "");

    private static void createConfigDir() throws IOException {
        Path dir = configDir();
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
            restrictPermissions(dir);
        }
    }

    /**
     * Locks a settings file (or its directory) to the owner. It holds an
     * OpenAI API key, and the default umask on a shared machine leaves it
     * world-readable. Best-effort: silently skipped on a filesystem without
     * POSIX permissions.
     */
    private static void restrictPermissions(Path path) {
        try {
            boolean directory = Files.isDirectory(path);
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                    directory ? "rwx------" : "rw-------"));
        } catch (IOException | UnsupportedOperationException e) {
            // Not POSIX, or the filesystem refuses: the settings still work.
        }
    }

    /**
     * Parses a {@code .env} file into key/value pairs, tolerating comments,
     * blank lines, {@code export } prefixes and quoted values. A malformed or
     * unreadable file yields whatever parsed, rather than failing startup — the
     * app must still open so the user can fix the file in Settings.
     */
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

    /**
     * Removes surrounding quotes. Double-quoted values additionally decode
     * {@code \n}, {@code \r}, {@code \t}, {@code \"} and {@code \\} — the
     * standard dotenv semantics that let a multi-line value (an OpenAI prompt
     * edited in File &gt; Settings &gt; OpenAI API) live on one {@code .env}
     * line. Single-quoted values stay literal.
     */
    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return unescape(value.substring(1, value.length() - 1));
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    default -> out.append(next); // covers \" and \\
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Updates keys in the user's {@link #globalEnvPath() global settings},
     * creating the file if needed and preserving every unrelated line (comments
     * included). A null value removes the key. Values are encoded as the
     * counterpart of {@link #stripQuotes}: double-quoted with escapes when they
     * contain newlines, quotes or other characters the line-based format cannot
     * carry verbatim.
     */
    public static void updateDotenv(Map<String, String> values) throws IOException {
        createConfigDir();
        Path envFile = globalEnvPath();
        List<String> lines = Files.isRegularFile(envFile)
                ? new ArrayList<>(Files.readAllLines(envFile, StandardCharsets.UTF_8))
                : new ArrayList<>(SETTINGS_HEADER);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            // Drop every assignment of the key (loadDotenv lets the last one
            // win, so duplicates must not survive) and put the new one where
            // the first used to be.
            int insertAt = -1;
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (isAssignmentOf(lines.get(i), entry.getKey())) {
                    lines.remove(i);
                    insertAt = i;
                }
            }
            if (entry.getValue() != null) {
                String line = entry.getKey() + "=" + encodeValue(entry.getValue());
                lines.add(insertAt == -1 ? lines.size() : insertAt, line);
            }
        }
        Files.write(envFile, lines, StandardCharsets.UTF_8);
        restrictPermissions(envFile);
    }

    private static boolean isAssignmentOf(String rawLine, String key) {
        String line = rawLine.strip();
        if (line.startsWith("export ")) {
            line = line.substring("export ".length()).strip();
        }
        int eq = line.indexOf('=');
        return eq > 0 && line.substring(0, eq).strip().equals(key);
    }

    private static String encodeValue(String value) {
        boolean needsQuoting = value.chars().anyMatch(
                c -> c == '"' || c == '\'' || c == '\\' || c == '#' || c == '\n' || c == '\r' || c == '\t')
                || (!value.isEmpty() && (Character.isWhitespace(value.charAt(0))
                || Character.isWhitespace(value.charAt(value.length() - 1))));
        if (!needsQuoting) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    /** The value as a path; the fallback if absent or blank. */
    private static Path pathOrDefault(Map<String, String> env, String key, Path fallback) {
        String v = env.get(key);
        return (v == null || v.isBlank()) ? fallback : Path.of(v);
    }

    /** The value as-is; the fallback if absent or blank. */
    private static String stringOrDefault(Map<String, String> env, String key, String fallback) {
        String v = env.get(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    /** The value as an int; the fallback if absent, blank or not a number. */
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

    /** The value as a double; the fallback if absent, blank or not a number. */
    private static double doubleOrDefault(Map<String, String> env, String key, double fallback) {
        String v = env.get(key);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(v.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * The current user's uid, which the default MTP glob needs to find the
     * gvfs mount under {@code /run/user/<uid>}. Falls back to 1000 (the usual
     * first user) off Linux or with a restricted {@code /proc}.
     */
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
