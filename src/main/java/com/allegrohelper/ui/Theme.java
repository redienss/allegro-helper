package com.allegrohelper.ui;

import javax.swing.UIManager;
import java.awt.Color;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * The UI theme, selected in File &gt; Settings &gt; Appearance.
 *
 * <p>SYSTEM installs the platform look and feel (GTK on Linux), so the app
 * follows the desktop theme. LIGHT and DARK install Nimbus — the JDK's own
 * cross-platform look and feel — because the zero-dependency rule rules out
 * third-party theme libraries, and Nimbus is the only stock look and feel
 * whose entire palette derives from a handful of base colors, which is what
 * makes a dark variant possible by overriding just those keys.
 *
 * <p>The choice is stored in {@link Preferences}, not the base directory's
 * {@code .env}: it is a per-user UI preference rather than pipeline
 * configuration, and it must be applied at startup before any base directory
 * is known.
 */
public enum Theme {
    SYSTEM("System"),
    DARK("Dark"),
    LIGHT("Light");

    private final String label;

    Theme(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label; // rendered directly by the Settings combo box
    }

    private static final String PREF_KEY = "theme";

    /** What {@link #apply} last installed successfully; SYSTEM matches the pre-theme startup behavior. */
    private static Theme current = SYSTEM;

    /**
     * The Nimbus base colors overridden for the dark variant (Darcula-like
     * tones). Everything else — buttons, tabs, tables, scrollbars — derives
     * from these. The key set doubles as the list of user defaults to clear
     * when switching away from DARK, because {@link UIManager#put} entries
     * shadow look-and-feel defaults and survive a look-and-feel switch.
     */
    private static final Map<String, Color> DARK_NIMBUS = Map.ofEntries(
            Map.entry("control", new Color(0x3C, 0x3F, 0x41)),                // panel background
            Map.entry("info", new Color(0x3C, 0x3F, 0x41)),                   // tooltip background
            Map.entry("nimbusBase", new Color(0x1E, 0x20, 0x22)),             // buttons, tabs, scrollbars
            Map.entry("nimbusBlueGrey", new Color(0x44, 0x48, 0x4C)),         // borders, control gradients
            Map.entry("nimbusLightBackground", new Color(0x2B, 0x2B, 0x2B)),  // text fields, lists, tables
            Map.entry("nimbusFocus", new Color(0x4B, 0x6E, 0xAF)),
            Map.entry("nimbusSelectionBackground", new Color(0x4B, 0x6E, 0xAF)),
            Map.entry("nimbusSelectedText", Color.WHITE),
            Map.entry("nimbusDisabledText", new Color(0x80, 0x80, 0x80)),
            Map.entry("nimbusInfoBlue", new Color(0x42, 0x8B, 0xDD)),
            Map.entry("text", new Color(0xBB, 0xBB, 0xBB)));

    /** The saved theme, defaulting to SYSTEM (also on an unrecognized stored value). */
    public static Theme load() {
        try {
            return valueOf(prefs().get(PREF_KEY, SYSTEM.name()));
        } catch (IllegalArgumentException e) {
            return SYSTEM;
        }
    }

    public static void save(Theme theme) {
        prefs().put(PREF_KEY, theme.name());
    }

    /** The theme currently in effect (what {@link #apply} last succeeded with). */
    public static Theme current() {
        return current;
    }

    /**
     * Installs the theme's look and feel. Best-effort: on failure the active
     * look and feel stays, matching the old startup behavior. Windows that
     * already exist are not restyled here — callers do that with
     * {@link javax.swing.SwingUtilities#updateComponentTreeUI}.
     */
    public static void apply(Theme theme) {
        for (String key : DARK_NIMBUS.keySet()) {
            UIManager.put(key, null);
        }
        try {
            switch (theme) {
                case SYSTEM -> UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                case LIGHT -> UIManager.setLookAndFeel(nimbusClassName());
                case DARK -> {
                    DARK_NIMBUS.forEach(UIManager::put);
                    UIManager.setLookAndFeel(nimbusClassName());
                }
            }
            current = theme;
        } catch (Exception ignored) {
            // Keep whatever look and feel is active.
        }
    }

    /**
     * Whether the active look and feel paints on a dark background — the cue
     * for the hand-picked colors (carets, tab titles) that no look-and-feel
     * key covers. Judged from the actual panel color rather than
     * {@link #current}, because SYSTEM can be either.
     */
    public static boolean isDark() {
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) {
            bg = UIManager.getColor("control");
        }
        if (bg == null) {
            return true; // the app historically assumed a dark desktop theme
        }
        return (bg.getRed() * 299 + bg.getGreen() * 587 + bg.getBlue() * 114) / 1000 < 128;
    }

    private static String nimbusClassName() {
        for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
            if ("Nimbus".equals(info.getName())) {
                return info.getClassName();
            }
        }
        return UIManager.getCrossPlatformLookAndFeelClassName(); // Metal, should Nimbus ever be absent
    }

    private static Preferences prefs() {
        return Preferences.userNodeForPackage(Theme.class);
    }
}
