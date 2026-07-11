package com.allegrohelper.ui;

import java.util.prefs.Preferences;

/**
 * The UI language, selected in File &gt; Settings &gt; Language. Persisted the
 * same way (and for the same reasons) as {@link Theme}: a per-user UI
 * preference in {@link Preferences}, applied at startup before any base
 * directory is known. The actual translations live in {@link I18n}.
 */
public enum Language {
    ENGLISH("English"),
    POLISH("Polish");

    private final String label;

    Language(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return I18n.t(label); // rendered by the Settings combo — "Polish" shows as "Polski" under Polish
    }

    private static final String PREF_KEY = "language";

    /**
     * Makes this the active language for all subsequently rendered text
     * (mirrors {@link Theme#apply}). Windows that already exist are not
     * restyled here — callers do that with {@link I18n#retranslate}.
     */
    public static void apply(Language language) {
        I18n.setLanguage(language);
    }

    /** The language currently in effect. */
    public static Language current() {
        return I18n.language();
    }

    /** The saved language, defaulting to ENGLISH (also on an unrecognized stored value). */
    public static Language load() {
        try {
            return valueOf(prefs().get(PREF_KEY, ENGLISH.name()));
        } catch (IllegalArgumentException e) {
            return ENGLISH;
        }
    }

    public static void save(Language language) {
        prefs().put(PREF_KEY, language.name());
    }

    private static Preferences prefs() {
        return Preferences.userNodeForPackage(Language.class);
    }
}
