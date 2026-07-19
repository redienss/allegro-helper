package com.allegrohelper.ui;

import com.allegrohelper.core.Config;
import com.allegrohelper.core.GenerateDescription;
import com.allegrohelper.core.SeriesRecognition;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.JFileChooser;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The File &gt; Settings dialog: a PhpStorm-style two-pane layout — the page
 * list on the left, the selected page's card on the right, OK/Cancel/Apply
 * below. Pages: Appearance ({@link Theme}), Language ({@link Language}),
 * Photos (the default series recognition mode) and OpenAI API (API key, model
 * and the description prompts).
 *
 * <p>Apply (and OK) takes effect immediately: it installs the look and feel
 * and/or language, persists the choices, restyles and retranslates every open
 * window, and then runs the caller's hook so {@link MainWindow} can re-apply
 * the styling its build baked in. No restart involved.
 *
 * <p>Theme and language are per-user UI preferences and live in
 * {@link java.util.prefs.Preferences}; the OpenAI values are pipeline
 * configuration ({@link Config} keys), so they are written to the base
 * directory's {@code .env} instead — and only when they differ from the
 * built-in defaults, so {@code .env} carries just the overrides. The page
 * shows the <em>effective</em> values, so a real environment variable (which
 * outranks {@code .env}) shows up here and keeps winning after a save.
 *
 * <p>Built entirely from English literals like every window, then passed
 * through {@link I18n#retranslate}; the page list and combos render their
 * (English) values through {@link I18n#t} at paint time, so a language switch
 * only needs a repaint.
 */
final class SettingsDialog extends JDialog {

    private static final String PAGE_APPEARANCE = "Appearance";
    private static final String PAGE_LANGUAGE = "Language";
    private static final String PAGE_PHOTOS = "Photos";
    private static final String PAGE_OPENAI = "OpenAI API";

    /** Suggestions only — the combo is editable, any model id can be typed. */
    private static final String[] OPENAI_MODELS = {
            "gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1", "gpt-5-mini", "gpt-5"};

    /** Where a user without a key gets one; linked under the API Key field. */
    private static final String API_KEYS_URL = "https://platform.openai.com/api-keys";

    private final JComboBox<Theme> themeCombo = new JComboBox<>(Theme.values());
    private final JComboBox<Language> languageCombo = new JComboBox<>(Language.values());
    /**
     * The same items as {@link MainWindow}'s series dropdown, in
     * {@link SeriesRecognition.Mode} order, so an index is a mode. Kept as
     * English keys and translated by the renderer at paint time, like there.
     */
    private final JTextField baseDirField = new JTextField();
    private final JTextField photoDirField = new JTextField();
    private final JComboBox<String> seriesModeCombo = new JComboBox<>(new String[]{
            "AUTO - Auto detect photo series",
            "SINGLE - All photos in the directory as one item",
            "SUBFOLDERS - Each subfolder as a separate item"});
    private final JPasswordField apiKeyField = new JPasswordField();
    private final JComboBox<String> modelCombo = new JComboBox<>(OPENAI_MODELS);
    private final JTextArea systemPromptArea = new JTextArea();
    private final JTextArea userPromptArea = new JTextArea();
    private final JLabel apiKeyLink = buildApiKeyLink();
    private final JButton applyButton = new JButton("Apply");
    private final Runnable onSettingsApplied;
    private final Consumer<Applied> onDefaultsApplied;

    /**
     * The base directory whose effective configuration the pages display.
     * Settings are <em>written</em> to {@link Config#globalEnvPath()}, never
     * here — the base directory is itself one of them, and a per-directory file
     * could not hold it. Only reads need a base directory at all, to resolve
     * the paths derived from one.
     */
    private final Path baseDir;

    /** The effective OpenAI values as of the last load/save, for dirty checks. */
    private String savedApiKey = "";
    private String savedModel = "";
    private String savedSystemPrompt = "";
    private String savedUserPrompt = "";

    /** The effective series mode as of the last load/save, for the dirty check. */
    private SeriesRecognition.Mode savedSeriesMode = SeriesRecognition.Mode.AUTO;

    /** The effective photo directory as of the last load/save, for the dirty check. */
    private String savedPhotoDir = "";

    /** The base directory as of the last load/save; compared against the field. */
    private String savedBaseDir = "";

    /**
     * What an Apply changed, for {@link MainWindow} to adopt into its own
     * controls. A null field means that value did not change — the main window
     * must not have a setting it did not touch reset underneath it just because
     * someone opened this dialog to change the theme.
     */
    record Applied(Path baseDir, String photoDir, SeriesRecognition.Mode seriesMode) {
    }

    /**
     * @param baseDir the base directory whose {@code .env} the OpenAI settings
     *                are read from and written back to
     * @param onSettingsApplied run after a theme or language change, so
     *                          {@link MainWindow} can re-apply the styling its
     *                          build baked in
     * @param onDefaultsApplied handed only the pipeline defaults that actually
     *                          changed, so the main window's own controls follow
     *                          them without the untouched ones being reset
     */
    SettingsDialog(JFrame owner, Path baseDir, Runnable onSettingsApplied,
                   Consumer<Applied> onDefaultsApplied) {
        super(owner, "Settings", true);
        this.onSettingsApplied = onSettingsApplied;
        this.onDefaultsApplied = onDefaultsApplied;
        this.baseDir = baseDir;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        JList<String> pages = new JList<>(
                new String[]{PAGE_APPEARANCE, PAGE_LANGUAGE, PAGE_PHOTOS, PAGE_OPENAI});
        pages.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        pages.setSelectedIndex(0);
        pages.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        // The values stay English keys (they also key the cards); translate at paint time.
        pages.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                Object shown = value == null ? null : I18n.t(value.toString());
                return super.getListCellRendererComponent(list, shown, index, isSelected, cellHasFocus);
            }
        });
        JScrollPane pageList = new JScrollPane(pages);
        pageList.setPreferredSize(new Dimension(220, 0));

        CardLayout cards = new CardLayout();
        JPanel content = new JPanel(cards);
        content.add(buildPage(PAGE_APPEARANCE, "Theme:", themeCombo), PAGE_APPEARANCE);
        content.add(buildPage(PAGE_LANGUAGE, "Language:", languageCombo), PAGE_LANGUAGE);
        content.add(buildPhotosPage(), PAGE_PHOTOS);
        content.add(buildOpenAiPage(), PAGE_OPENAI);
        pages.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && pages.getSelectedValue() != null) {
                cards.show(content, pages.getSelectedValue());
            }
        });

        add(pageList, BorderLayout.WEST);
        add(content, BorderLayout.CENTER);
        add(buildButtonBar(), BorderLayout.SOUTH);

        themeCombo.setSelectedItem(Theme.current());
        languageCombo.setSelectedItem(Language.current());
        loadOpenAiSettings();
        loadPhotoSettings();
        applyButton.setEnabled(false);
        themeCombo.addActionListener(e -> updateApplyEnabled());
        languageCombo.addActionListener(e -> updateApplyEnabled());
        seriesModeCombo.addActionListener(e -> updateApplyEnabled());
        watchDocument(baseDirField);
        watchDocument(photoDirField);
        seriesModeCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                Object shown = value == null ? null : I18n.t(value.toString());
                return super.getListCellRendererComponent(list, shown, index, isSelected, cellHasFocus);
            }
        });
        modelCombo.addActionListener(e -> updateApplyEnabled());
        watchDocument(apiKeyField);
        watchDocument((JTextComponent) modelCombo.getEditor().getEditorComponent());
        watchDocument(systemPromptArea);
        watchDocument(userPromptArea);

        I18n.retranslate(this);
        UiStyle.standardizeFonts(getRootPane());
        UiStyle.recolorCarets(getRootPane());
        // Wide enough for the API-key hint row in Polish too, whose label runs
        // some 60px longer than the English one and would otherwise push the
        // link off the edge of the page.
        setPreferredSize(new Dimension(900, 560));
        pack();
        setLocationRelativeTo(owner);
    }

    /** One settings page: a bold header, then a single labeled combo, pinned to the top-left. */
    private static JPanel buildPage(String header, String label, JComboBox<?> combo) {
        JPanel page = new JPanel(new GridBagLayout());
        page.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(0, 0, 16, 0);
        JLabel headerLabel = new JLabel(header);
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD));
        page.add(headerLabel, c);

        c.gridy = 1;
        c.gridwidth = 1;
        c.insets = new Insets(0, 0, 0, 8);
        page.add(new JLabel(label), c);
        c.gridx = 1;
        c.insets = new Insets(0, 0, 0, 0);
        page.add(combo, c);

        // Glue in the outer corner keeps the controls pinned to the top-left.
        c.gridx = 2;
        c.gridy = 2;
        c.weightx = 1;
        c.weighty = 1;
        page.add(Box.createGlue(), c);
        return page;
    }

    /**
     * The Photos page: where the app starts (base directory), where photos come
     * from (the MTP glob) and how they are grouped into offers.
     *
     * <p>These are the same three controls the main window carries at the top —
     * here they are the <em>defaults</em> those controls start from, so the
     * values a user works with every day stop being a per-launch chore.
     */
    private JPanel buildPhotosPage() {
        JPanel page = new JPanel(new GridBagLayout());
        page.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 3;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(0, 0, 16, 0);
        JLabel headerLabel = new JLabel(PAGE_PHOTOS);
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD));
        page.add(headerLabel, c);

        addDirRow(page, 1, "Base directory:", baseDirField, e -> chooseBaseDir());
        addDirRow(page, 2, "Photo directory:", photoDirField, e -> choosePhotoDir());

        c.gridy = 3;
        c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        c.insets = new Insets(10, 0, 0, 8);
        page.add(new JLabel("Series recognition:"), c);
        c.gridx = 1;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(10, 0, 0, 0);
        page.add(seriesModeCombo, c);

        // Glue below keeps the rows pinned to the top.
        c.gridx = 0;
        c.gridy = 4;
        c.weighty = 1;
        page.add(Box.createGlue(), c);
        return page;
    }

    /** One directory row on the Photos page: label, field, Browse. */
    private static void addDirRow(JPanel page, int row, String label, JTextField field,
                                  java.awt.event.ActionListener browse) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(row == 1 ? 0 : 6, 0, 0, 8);
        page.add(new JLabel(label), c);

        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(row == 1 ? 0 : 6, 0, 0, 8);
        page.add(field, c);

        c.gridx = 2;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        c.insets = new Insets(row == 1 ? 0 : 6, 0, 0, 0);
        JButton browseButton = new JButton("Browse…");
        browseButton.addActionListener(browse);
        page.add(browseButton, c);
    }

    /** Picks the default base directory; the .env-backed pages follow it. */
    private void chooseBaseDir() {
        JFileChooser chooser = new JFileChooser(baseDirField.getText().strip());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            baseDirField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    /** Picks the default photo source directory, replacing the MTP glob. */
    private void choosePhotoDir() {
        // The field usually holds the MTP glob, which no chooser can open;
        // start from the deepest existing prefix of it, as the main window does.
        Path start = OfferFiles.deepestExistingDir(photoDirField.getText().strip());
        JFileChooser chooser = new JFileChooser(start == null ? null : start.toString());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            photoDirField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    /** The OpenAI API page: key, model and the two description prompts. */
    private JPanel buildOpenAiPage() {
        modelCombo.setEditable(true);
        for (JTextArea area : new JTextArea[]{systemPromptArea, userPromptArea}) {
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
        }

        JPanel page = new JPanel(new GridBagLayout());
        page.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(0, 0, 16, 0);
        JLabel headerLabel = new JLabel(PAGE_OPENAI);
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD));
        page.add(headerLabel, c);

        c.gridwidth = 1;
        addRow(page, c, 1, "API Key:", apiKeyField, GridBagConstraints.HORIZONTAL, 0);
        // Under the field, where someone who has no key is looking anyway.
        addRow(page, c, 2, "", buildApiKeyHint(), GridBagConstraints.HORIZONTAL, 0);
        addRow(page, c, 3, "Model:", modelCombo, GridBagConstraints.HORIZONTAL, 0);
        // The system prompt is the long one; give it most of the stretch.
        addRow(page, c, 4, "System Prompt:", new JScrollPane(systemPromptArea),
                GridBagConstraints.BOTH, 0.7);
        addRow(page, c, 5, "User Prompt:", new JScrollPane(userPromptArea),
                GridBagConstraints.BOTH, 0.3);
        return page;
    }

    /**
     * The "no key yet?" hint and the link to OpenAI's API keys page, as one row.
     *
     * <p>Laid out along the X axis rather than with a {@link FlowLayout}: flow
     * wraps when the row runs out of width, and the wrapped line then falls
     * outside the GridBag row's height and is clipped — the link simply vanishes.
     * The trailing glue keeps both parts left-aligned.
     */
    private JPanel buildApiKeyHint() {
        JPanel hint = new JPanel();
        hint.setLayout(new BoxLayout(hint, BoxLayout.X_AXIS));
        hint.add(new JLabel("No API key yet?"));
        hint.add(Box.createHorizontalStrut(6));
        hint.add(apiKeyLink);
        hint.add(Box.createHorizontalGlue());
        return hint;
    }

    /**
     * The API keys page as a clickable link: underlined, hand cursor, and
     * painted in {@link UiStyle#linkColor()} so it reads as a link against
     * either theme's background.
     */
    private JLabel buildApiKeyLink() {
        JLabel link = new JLabel("<html><u>" + API_KEYS_URL + "</u></html>");
        link.setForeground(UiStyle.linkColor());
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setToolTipText(API_KEYS_URL);
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                Desktops.browse(API_KEYS_URL,
                        () -> { }, // the browser opening is its own feedback
                        message -> JOptionPane.showMessageDialog(SettingsDialog.this,
                                I18n.t("Could not open {0}: {1}", API_KEYS_URL, message),
                                I18n.t("Settings"), JOptionPane.ERROR_MESSAGE));
            }
        });
        return link;
    }


    /**
     * Adds one labeled row to the OpenAI page.
     *
     * @param weighty share of the page's spare vertical space the field takes —
     *                what makes the prompt areas grow with the dialog while the
     *                key and model rows stay single-line
     */
    private static void addRow(JPanel page, GridBagConstraints c, int row,
                               String label, Component field, int fill, double weighty) {
        c.gridx = 0;
        c.gridy = row;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        c.weighty = 0;
        c.insets = new Insets(0, 0, 8, 8);
        page.add(new JLabel(label), c);
        c.gridx = 1;
        c.fill = fill;
        c.weightx = 1;
        c.weighty = weighty;
        c.insets = new Insets(0, 0, 8, 0);
        page.add(field, c);
    }

    /**
     * (Re)loads the OpenAI page from the effective configuration and resets
     * the dirty baseline. Also run after a save, so values that fell back to
     * a default (a cleared prompt) reappear as that default.
     */
    private void loadOpenAiSettings() {
        Config cfg = Config.forBaseDir(baseDir);
        savedApiKey = cfg.openaiApiKey;
        savedModel = cfg.openaiModel;
        savedSystemPrompt = cfg.openaiSystemPrompt;
        savedUserPrompt = cfg.openaiUserPrompt;
        apiKeyField.setText(cfg.openaiApiKey);
        modelCombo.setSelectedItem(cfg.openaiModel);
        systemPromptArea.setText(cfg.openaiSystemPrompt);
        systemPromptArea.setCaretPosition(0);
        userPromptArea.setText(cfg.openaiUserPrompt);
        userPromptArea.setCaretPosition(0);
    }

    /**
     * Fills the Photos page from the effective configuration, so a real
     * environment variable (which outranks {@code .env}) shows up here and
     * keeps winning after a save, exactly like the OpenAI page.
     */
    private void loadPhotoSettings() {
        Config cfg = Config.forBaseDir(baseDir);
        // The saved startup default, which is the base directory in use when
        // none has been saved yet.
        savedBaseDir = BaseDir.load(baseDir).toString();
        savedPhotoDir = cfg.mtpGlobPattern;
        savedSeriesMode = cfg.seriesRecognition;
        baseDirField.setText(savedBaseDir);
        photoDirField.setText(savedPhotoDir);
        seriesModeCombo.setSelectedIndex(savedSeriesMode.ordinal());
    }

    /** The mode the combo is showing; index and ordinal are the same order. */
    private SeriesRecognition.Mode selectedSeriesMode() {
        int i = seriesModeCombo.getSelectedIndex();
        return i < 0 ? SeriesRecognition.Mode.AUTO : SeriesRecognition.Mode.values()[i];
    }

    /**
     * Persists the Photos page: the base directory as a user preference, the
     * other two into that directory's {@code .env}. A value equal to its
     * built-in default is removed rather than written, so {@code .env} carries
     * only the overrides.
     *
     * @return what changed, for the main window to adopt; null if saving failed
     */
    private Applied savePhotoSettings() {
        SeriesRecognition.Mode mode = selectedSeriesMode();
        String photoDir = photoDirField.getText().strip();
        String typedBaseDir = baseDirField.getText().strip();
        boolean baseDirChanged = !typedBaseDir.equals(savedBaseDir);
        boolean photoDirChanged = !photoDir.equals(savedPhotoDir);
        boolean modeChanged = mode != savedSeriesMode;

        Map<String, String> values = new LinkedHashMap<>();
        if (photoDirChanged) {
            // Blank means "back to the built-in glob", which is the absence of
            // the key — not an empty value that would match no device at all.
            values.put("MTP_GLOB_PATTERN",
                    photoDir.isEmpty()
                            || photoDir.equals(Config.defaultMtpGlobPattern(
                                    Config.forBaseDir(baseDir).mtpUid))
                            ? null : photoDir);
        }
        if (modeChanged) {
            values.put("SERIES_RECOGNITION", mode == SeriesRecognition.Mode.AUTO ? null : mode.key);
        }
        try {
            if (!values.isEmpty()) {
                Config.updateDotenv(values);
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    I18n.t("Failed to save settings to {0}: {1}",
                            Config.globalEnvPath(), e.getMessage()),
                    I18n.t("Settings"), JOptionPane.ERROR_MESSAGE);
            return null;
        }
        Path newBaseDir = null;
        if (baseDirChanged) {
            newBaseDir = baseDirOrNull(typedBaseDir);
            BaseDir.save(newBaseDir); // null clears it: back to the working directory
        }
        loadPhotoSettings();
        // Report the *effective* values, not the typed ones: an environment
        // variable outranks the settings file, so what was asked for is not
        // always what a run will use — and the main window must show what a run
        // will use.
        return new Applied(newBaseDir,
                photoDirChanged ? savedPhotoDir : null,
                modeChanged ? savedSeriesMode : null);
    }

    /** The typed path when it names a real directory, else null. */
    private static Path baseDirOrNull(String typed) {
        if (typed.isEmpty()) {
            return null;
        }
        try {
            Path candidate = Path.of(typed).toAbsolutePath().normalize();
            return Files.isDirectory(candidate) ? candidate : null;
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    /** Whether anything on the Photos page differs from what is in effect. */
    private boolean photoSettingsDirty() {
        return selectedSeriesMode() != savedSeriesMode
                || !photoDirField.getText().strip().equals(savedPhotoDir)
                || !baseDirField.getText().strip().equals(savedBaseDir);
    }

    /** The model id as typed, not just as last committed by the editable combo. */
    private String currentModel() {
        Object item = modelCombo.getEditor().getItem();
        return item == null ? "" : item.toString().strip();
    }

    /** Whether any OpenAI field was edited since the last load or save. */
    private boolean openaiDirty() {
        return !new String(apiKeyField.getPassword()).strip().equals(savedApiKey)
                || !currentModel().equals(savedModel)
                || !systemPromptArea.getText().equals(savedSystemPrompt)
                || !userPromptArea.getText().equals(savedUserPrompt);
    }

    /**
     * Re-evaluates the Apply button on every keystroke in a text component —
     * an {@code ActionListener} would only fire on commit, leaving Apply
     * disabled while the user types.
     */
    private void watchDocument(JTextComponent component) {
        component.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateApplyEnabled();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateApplyEnabled();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateApplyEnabled();
            }
        });
    }

    /**
     * Writes the OpenAI settings to the base directory's {@code .env}. A value
     * equal to its built-in default (or blank) is removed rather than written,
     * so {@code .env} only ever carries the overrides.
     */
    private boolean saveOpenAiSettings() {
        String apiKey = new String(apiKeyField.getPassword()).strip();
        String model = currentModel();
        String systemPrompt = systemPromptArea.getText();
        String userPrompt = userPromptArea.getText();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("OPENAI_API_KEY", apiKey.isEmpty() ? null : apiKey);
        values.put("OPENAI_MODEL",
                model.isEmpty() || model.equals(Config.DEFAULT_OPENAI_MODEL) ? null : model);
        values.put("OPENAI_SYSTEM_PROMPT",
                systemPrompt.isBlank() || systemPrompt.equals(GenerateDescription.SYSTEM_PROMPT)
                        ? null : systemPrompt);
        values.put("OPENAI_USER_PROMPT",
                userPrompt.isBlank() || userPrompt.equals(GenerateDescription.USER_PROMPT)
                        ? null : userPrompt);
        try {
            Config.updateDotenv(values);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    I18n.t("Failed to save settings to {0}: {1}",
                            Config.globalEnvPath(), e.getMessage()),
                    I18n.t("Settings"), JOptionPane.ERROR_MESSAGE);
            return false;
        }
        loadOpenAiSettings();
        return true;
    }

    /** The OK / Cancel / Apply bar. OK is the default button, so Enter accepts the dialog. */
    private JPanel buildButtonBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton ok = new JButton("OK");
        ok.addActionListener(e -> {
            applySelection();
            dispose();
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        applyButton.addActionListener(e -> applySelection());
        bar.add(ok);
        bar.add(cancel);
        bar.add(applyButton);
        getRootPane().setDefaultButton(ok);
        return bar;
    }

    /** Enables Apply exactly when something on any page differs from what is in effect. */
    private void updateApplyEnabled() {
        applyButton.setEnabled(themeCombo.getSelectedItem() != Theme.current()
                || languageCombo.getSelectedItem() != Language.current()
                || photoSettingsDirty()
                || openaiDirty());
    }

    /**
     * Installs and persists the selected theme, language and OpenAI settings,
     * restyling and retranslating every open window (this dialog included).
     */
    private void applySelection() {
        Theme theme = (Theme) themeCombo.getSelectedItem();
        Language language = (Language) languageCombo.getSelectedItem();
        boolean themeChanged = theme != null && theme != Theme.current();
        boolean languageChanged = language != null && language != Language.current();
        if (openaiDirty()) {
            saveOpenAiSettings();
        }
        if (photoSettingsDirty()) {
            Applied applied = savePhotoSettings();
            if (applied != null) {
                onDefaultsApplied.accept(applied);
            }
        }
        if (!themeChanged && !languageChanged) {
            updateApplyEnabled();
            return;
        }
        if (themeChanged) {
            Theme.apply(theme);
            Theme.save(theme);
        }
        if (languageChanged) {
            Language.apply(language);
            Language.save(language);
        }
        for (Window window : Window.getWindows()) {
            if (themeChanged) {
                SwingUtilities.updateComponentTreeUI(window);
            }
            if (languageChanged) {
                I18n.retranslate(window);
            }
        }
        onSettingsApplied.run();
        // updateComponentTreeUI reset this dialog's fonts and caret colors, and
        // the link blue is picked per theme, so it has to be re-picked here.
        UiStyle.standardizeFonts(getRootPane());
        UiStyle.recolorCarets(getRootPane());
        apiKeyLink.setForeground(UiStyle.linkColor());
        updateApplyEnabled();
    }
}
