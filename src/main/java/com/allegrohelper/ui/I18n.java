package com.allegrohelper.ui;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.TableColumn;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.util.HashMap;
import java.util.Map;

/**
 * UI translations (File &gt; Settings &gt; Language). Hand-rolled instead of
 * {@link java.util.ResourceBundle} so the strings ship inside the classes
 * build.sh already produces — no resource files to copy onto the classpath.
 *
 * <p>The code keeps every UI string as an <b>English literal</b> (the repo
 * convention), so English needs no lookups and a missing translation degrades
 * to English rather than to a bare key. Polish comes from the map here, two
 * ways:
 *
 * <ul>
 *   <li>{@link #t} translates at the call site — for strings composed at
 *       runtime (dialog messages, list entries) with {@code {0}}-style
 *       placeholders, and for strings painted through renderers.</li>
 *   <li>{@link #retranslate} walks a live component tree and swaps every text
 *       it recognizes (labels, buttons, menus, tab titles, titled borders,
 *       tooltips, table headers). It first normalizes each text back to
 *       English via the reverse map, so it converts in both directions and is
 *       idempotent. Unknown strings — user data, paths — are left untouched.</li>
 * </ul>
 *
 * <p>Adding UI text? Write it in English in the code and add an entry to
 * {@link #TRANSLATIONS}; strings shown via a component's plain text property
 * are then handled by the walk, composed strings need {@link #t}. Polish
 * values must stay unique — the reverse map depends on it. Pipeline log
 * output stays English by convention and is deliberately not translated.
 */
final class I18n {

    /** Not instantiable: the class is a namespace for {@link #t} and {@link #retranslate}. */
    private I18n() {
    }

    private static volatile Language language = Language.ENGLISH;

    /** English → Polish, keyed by the exact literals in the UI code. */
    private static final Map<String, String> TRANSLATIONS = new HashMap<>();
    /** Polish → English, for normalizing live texts before re-translating. */
    private static final Map<String, String> REVERSE = new HashMap<>();

    static {
        // Menu bar
        put("File", "Plik");
        put("Settings…", "Ustawienia…");
        put("Exit", "Zakończ");

        // Directories
        put("Base directory:", "Katalog bazowy:");
        put("Photo directory:", "Katalog zdjęć:");
        put("Browse…", "Przeglądaj…");
        put("Restore the default photo directory", "Przywróć domyślny katalog zdjęć");

        // Photos section
        put("Photos", "Zdjęcia");
        put("Refresh", "Odśwież");
        put("AUTO - Auto detect photo series",
                "AUTO - Automatyczne wykrywanie serii zdjęć");
        // No longer than its English key: the longest item sets the combo width,
        // and a wider combo wraps out of the height-capped Photos row.
        put("SINGLE - All photos in the directory as one item",
                "SINGLE - Cały katalog zdjęć jako jeden przedmiot");
        put("SUBFOLDERS - Each subfolder as a separate item",
                "SUBFOLDERS - Każdy podkatalog jako osobny przedmiot");
        put("Scanning phone…", "Skanowanie telefonu…");
        put("No photo series found in {0}", "Nie znaleziono serii zdjęć w {0}");
        put("{0} | {1}x series of photos to import", "{0} | seria {1} zdjęć do importu");

        // Offer data grid
        put("Offer Data", "Dane ofert");
        put("Name", "Nazwa");
        put("Brand", "Marka");
        put("Condition", "Stan");
        put("Damage", "Uszkodzenia");
        put("Quantity", "Ilość");
        put("Price", "Cena");
        put("InPost Size", "Rozmiar InPost");
        put("Load CSV…", "Wczytaj CSV…");
        put("Save CSV", "Zapisz CSV");
        put("Open CSV in Editor", "Otwórz CSV w edytorze");
        put("Reload CSV", "Odśwież CSV");
        put("Add Row", "Dodaj wiersz");
        put("Remove Row", "Usuń wiersz");
        put("Open the offers CSV in the system's default .csv application",
                "Otwórz plik CSV ofert w domyślnym programie dla plików .csv");
        put("Re-read the offers CSV from disk, e.g. after editing it in an external program",
                "Wczytaj ponownie plik CSV ofert z dysku, np. po edycji w zewnętrznym programie");
        put("CSV file does not exist yet: {0} — Save CSV first.",
                "Plik CSV jeszcze nie istnieje: {0} — najpierw użyj Zapisz CSV.");
        put("Failed to load CSV: {0}", "Nie udało się wczytać CSV: {0}");
        put("Failed to save CSV: {0}", "Nie udało się zapisać CSV: {0}");
        put("Failed to reload CSV: {0}", "Nie udało się odświeżyć CSV: {0}");

        // Workflow section ("Import", "OCR" and "Start" read the same in Polish)
        put("Workflow", "Przebieg pracy");
        put("Match", "Dopasuj");
        put("White balance", "Balans bieli");
        put("Contrast", "Kontrast");
        put("Auto-crop", "Kadrowanie");
        put("Describe", "Opisz"); // not "Opis" — that's the form tab's Description label
        put("Running…", "Działa…");
        put("Delete Output Files", "Usuń pliki wynikowe");
        put("Clean & Restart", "Wyczyść i uruchom ponownie");
        put("Select at least one workflow step.", "Wybierz co najmniej jeden krok przebiegu pracy.");
        put("Failed to save offers.csv before matching: {0}",
                "Nie udało się zapisać offers.csv przed dopasowaniem: {0}");
        put("Progress", "Postęp");

        // Right panel
        put("Selected Offer", "Wybrana oferta");
        put("Select an offer in the grid.", "Wybierz ofertę w tabeli.");
        put("Description (Input)", "Opis (wejście)");
        put("Description (Output)", "Opis (wynik)");
        put("Photos (Input)", "Zdjęcia (wejście)");
        put("Retouch Preview", "Podgląd retuszu");
        put("Before", "Przed");
        put("After", "Po");
        put("Rendering the preview…", "Tworzenie podglądu…");
        put("< Prev", "< Wstecz");
        put("Next >", "Dalej >");
        put("1.00x leaves the photo as it is; less flattens it, more deepens it.",
                "1,00x zostawia zdjęcie bez zmian; mniej spłaszcza, więcej pogłębia.");
        put("Could not render the preview: {0}", "Nie udało się utworzyć podglądu: {0}");
        put("Photos (Output)", "Zdjęcia (wynik)");
        put("Allegro Lokalnie Form", "Formularz Allegro Lokalnie");
        put("Delete", "Usuń");
        put("Clear", "Wyczyść");
        put("Save", "Zapisz");
        put("Open photo dir", "Otwórz katalog zdjęć");
        put("row {0} — <i>not matched yet (photos and Description output appear after Match)</i>",
                "wiersz {0} — <i>jeszcze niedopasowany (zdjęcia i Opis (wynik) pojawią się po kroku Dopasuj)</i>");
        put("row {0} — {1}", "wiersz {0} — {1}");
        put("Select an offer in the grid first.", "Najpierw wybierz ofertę w tabeli.");
        put("No offer directory yet — run Match first.",
                "Nie ma jeszcze katalogu oferty — najpierw uruchom krok Dopasuj.");
        put("Not available yet.", "Jeszcze niedostępne.");
        put("No photos.", "Brak zdjęć.");
        put("Not matched yet — run Match.", "Jeszcze niedopasowane — uruchom krok Dopasuj.");
        put("Not retouched yet — run White balance or Contrast.",
                "Jeszcze nieretuszowane — uruchom Balans bieli lub Kontrast.");
        put("That photo directory does not exist yet.", "Ten katalog zdjęć jeszcze nie istnieje.");
        put("There is no file to delete yet:\n{0}", "Nie ma jeszcze pliku do usunięcia:\n{0}");
        put("Delete this file? This cannot be undone.\n\n{0}",
                "Usunąć ten plik? Tej operacji nie można cofnąć.\n\n{0}");
        put("Delete file", "Usuwanie pliku");
        put("Failed to delete {0}: {1}", "Nie udało się usunąć {0}: {1}");
        put("Failed to save {0}: {1}", "Nie udało się zapisać {0}: {1}");
        put("Loading {0} thumbnails…", "Wczytywanie {0} miniatur…");
        put("Could not read {0}: {1}", "Nie można odczytać {0}: {1}");
        put("Could not open {0}: {1}", "Nie można otworzyć {0}: {1}");
        put("Double-click a photo to open it in the default viewer. "
                        + "Drag photos onto another app (e.g. a browser upload form); Ctrl/Shift-click selects several.",
                "Kliknij dwukrotnie zdjęcie, aby otworzyć je w domyślnej przeglądarce. Przeciągnij zdjęcia do innej"
                        + " aplikacji (np. pola wysyłania w przeglądarce); Ctrl/Shift-klik zaznacza wiele.");

        // Allegro form tab (the 16s mirror MainWindow.ALLEGRO_MAX_PHOTOS — the
        // compiler folds that constant into the literal the walk sees)
        put("Link to Allegro Lokalnie form", "Link do formularza Allegro Lokalnie");
        put("Title", "Tytuł");
        put("Description", "Opis");
        put("Open URL", "Otwórz URL");
        put("Copy all to Allegro", "Skopiuj wszystko do Allegro");
        put("Open the form in Chrome and fill in the selected photos,"
                        + " the title and the description. You review and submit it yourself.",
                "Otwiera formularz w Chrome i wypełnia go wybranymi zdjęciami, tytułem i opisem."
                        + " Formularz przeglądasz i wysyłasz samodzielnie.");
        put("Select the photos to use (Allegro allows 16; the first 16"
                        + " are preselected), then drag the selection onto the form.",
                "Wybierz zdjęcia do użycia (Allegro pozwala na 16; pierwsze 16 jest wstępnie zaznaczonych),"
                        + " a następnie przeciągnij zaznaczenie na formularz.");
        put("Copy Title", "Kopiuj tytuł");
        put("Copy Description", "Kopiuj opis");
        put("Nothing to copy: select an offer with photos, a title or a description first.",
                "Nie ma nic do skopiowania: najpierw wybierz ofertę ze zdjęciami, tytułem lub opisem.");
        put("Copy to Allegro failed: {0}", "Kopiowanie do Allegro nie powiodło się: {0}");

        // Delete Output Files / Clean & Restart
        put("Refusing to delete {0}: it contains offers.csv or raw_photos (check OFFERS_DIR).",
                "Odmowa usunięcia {0}: zawiera offers.csv lub raw_photos (sprawdź OFFERS_DIR).");
        put("This deletes all generated offer directories under:\n{0}"
                        + "\n\nSources are kept: photos on the phone, offers.csv, more_data_<N>.txt."
                        + "\nRe-importing the photos requires the phone to be connected.",
                "To usunie wszystkie wygenerowane katalogi ofert w:\n{0}"
                        + "\n\nŹródła zostaną zachowane: zdjęcia w telefonie, offers.csv, more_data_<N>.txt."
                        + "\nPonowny import zdjęć wymaga podłączenia telefonu.");
        put("\n\nDelete and start the selected workflow steps?", "\n\nUsunąć i uruchomić wybrane kroki?");
        put("\n\nDelete?", "\n\nUsunąć?");
        put("Failed to delete output files: {0}", "Nie udało się usunąć plików wynikowych: {0}");

        // Settings dialog
        put("Settings", "Ustawienia");
        put("Appearance", "Wygląd");
        put("Language", "Język");
        put("Theme:", "Motyw:");
        put("Language:", "Język:");
        put("Cancel", "Anuluj");
        put("Apply", "Zastosuj");
        put("System", "Systemowy");
        put("Dark", "Ciemny");
        put("Light", "Jasny");
        put("English", "Angielski");
        put("Polish", "Polski");
        // "OpenAI API" (page name/header) and "Model:" read the same in Polish.
        put("API Key:", "Klucz API:");
        put("No API key yet?", "Nie masz klucza API?");
        put("System Prompt:", "Prompt systemowy:");
        put("User Prompt:", "Prompt użytkownika:");
        put("Failed to save settings to {0}: {1}",
                "Nie udało się zapisać ustawień do {0}: {1}");
    }

    /**
     * Registers one translation in both directions. The Polish values must stay
     * unique, or the reverse map would normalize a displayed text back to the
     * wrong English key.
     */
    private static void put(String en, String pl) {
        TRANSLATIONS.put(en, pl);
        REVERSE.put(pl, en);
    }

    /** The active language. */
    static Language language() {
        return language;
    }

    /**
     * Sets the active language for {@link #t} and {@link #retranslate}, and
     * localizes the JOptionPane buttons (the JDK ships no Polish Swing
     * resources). Callers retranslate open windows themselves.
     */
    static void setLanguage(Language newLanguage) {
        language = newLanguage;
        boolean pl = newLanguage == Language.POLISH;
        UIManager.put("OptionPane.yesButtonText", pl ? "Tak" : null);
        UIManager.put("OptionPane.noButtonText", pl ? "Nie" : null);
        UIManager.put("OptionPane.cancelButtonText", pl ? "Anuluj" : null);
        UIManager.put("OptionPane.okButtonText", pl ? "OK" : null);
    }

    /** Translates an English UI string, substituting {@code {0}}, {@code {1}}, … with {@code args}. */
    static String t(String key, Object... args) {
        String s = language == Language.POLISH ? TRANSLATIONS.getOrDefault(key, key) : key;
        for (int i = 0; i < args.length; i++) {
            s = s.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return s;
    }

    /** Re-translates every recognized text under {@code root} into the active language. */
    static void retranslate(Component root) {
        if (root instanceof Frame f) {
            f.setTitle(translate(f.getTitle()));
        } else if (root instanceof Dialog d) {
            d.setTitle(translate(d.getTitle()));
        }
        walk(root);
        // Text setters revalidate themselves, but TitledBorder titles and table
        // headers don't; one revalidate at the root covers them all.
        root.revalidate();
        root.repaint();
    }

    /** Normalizes a live text back to English, then into the active language; unknown texts pass through. */
    private static String translate(String s) {
        if (s == null) {
            return null;
        }
        String en = REVERSE.getOrDefault(s, s);
        return language == Language.POLISH ? TRANSLATIONS.getOrDefault(en, en) : en;
    }

    /**
     * Recursively translates every text property the walk knows about. A menu's
     * items live in its popup rather than among its children, so {@link JMenu}
     * is descended into explicitly.
     */
    private static void walk(Component c) {
        if (c instanceof JComponent jc) {
            jc.setToolTipText(translate(jc.getToolTipText()));
            translateBorder(jc.getBorder());
        }
        if (c instanceof JLabel label) {
            label.setText(translate(label.getText()));
        }
        if (c instanceof AbstractButton button) { // buttons, checkboxes, menus, menu items
            button.setText(translate(button.getText()));
        }
        if (c instanceof JTabbedPane tabs) {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                tabs.setTitleAt(i, translate(tabs.getTitleAt(i)));
            }
        }
        if (c instanceof JTable table) {
            for (int i = 0; i < table.getColumnModel().getColumnCount(); i++) {
                TableColumn column = table.getColumnModel().getColumn(i);
                if (column.getHeaderValue() instanceof String header) {
                    column.setHeaderValue(translate(header));
                }
            }
            if (table.getTableHeader() != null) {
                table.getTableHeader().repaint();
            }
        }
        if (c instanceof JMenu menu) { // a menu's items live in its popup, outside the component tree
            for (Component item : menu.getMenuComponents()) {
                walk(item);
            }
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                walk(child);
            }
        }
    }

    /** Translates a titled border's title, descending into compound borders. */
    private static void translateBorder(Border border) {
        if (border instanceof TitledBorder titled) {
            titled.setTitle(translate(titled.getTitle()));
        } else if (border instanceof CompoundBorder compound) {
            translateBorder(compound.getOutsideBorder());
            translateBorder(compound.getInsideBorder());
        }
    }
}
