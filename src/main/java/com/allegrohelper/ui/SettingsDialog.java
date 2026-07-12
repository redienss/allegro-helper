package com.allegrohelper.ui;

import com.allegrohelper.core.Config;
import com.allegrohelper.core.GenerateDescription;

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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The File &gt; Settings dialog: a PhpStorm-style two-pane layout — the page
 * list on the left, the selected page's card on the right, OK/Cancel/Apply
 * below. Pages: Appearance ({@link Theme}), Language ({@link Language}) and
 * OpenAI API (API key, model and the description prompts).
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
    private static final String PAGE_OPENAI = "OpenAI API";

    /** Suggestions only — the combo is editable, any model id can be typed. */
    private static final String[] OPENAI_MODELS = {
            "gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1", "gpt-5-mini", "gpt-5"};

    /** Where a user without a key gets one; linked under the API Key field. */
    private static final String API_KEYS_URL = "https://platform.openai.com/api-keys";

    private final JComboBox<Theme> themeCombo = new JComboBox<>(Theme.values());
    private final JComboBox<Language> languageCombo = new JComboBox<>(Language.values());
    private final JPasswordField apiKeyField = new JPasswordField();
    private final JComboBox<String> modelCombo = new JComboBox<>(OPENAI_MODELS);
    private final JTextArea systemPromptArea = new JTextArea();
    private final JTextArea userPromptArea = new JTextArea();
    private final JLabel apiKeyLink = buildApiKeyLink();
    private final JButton applyButton = new JButton("Apply");
    private final Runnable onSettingsApplied;
    private final Path baseDir;

    /** The effective OpenAI values as of the last load/save, for dirty checks. */
    private String savedApiKey = "";
    private String savedModel = "";
    private String savedSystemPrompt = "";
    private String savedUserPrompt = "";

    /**
     * @param baseDir the base directory whose {@code .env} the OpenAI settings
     *                are read from and written back to
     * @param onSettingsApplied run after a theme or language change, so
     *                          {@link MainWindow} can re-apply the styling its
     *                          build baked in
     */
    SettingsDialog(JFrame owner, Path baseDir, Runnable onSettingsApplied) {
        super(owner, "Settings", true);
        this.onSettingsApplied = onSettingsApplied;
        this.baseDir = baseDir;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        JList<String> pages = new JList<>(new String[]{PAGE_APPEARANCE, PAGE_LANGUAGE, PAGE_OPENAI});
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
        applyButton.setEnabled(false);
        themeCombo.addActionListener(e -> updateApplyEnabled());
        languageCombo.addActionListener(e -> updateApplyEnabled());
        modelCombo.addActionListener(e -> updateApplyEnabled());
        watchDocument(apiKeyField);
        watchDocument((JTextComponent) modelCombo.getEditor().getEditorComponent());
        watchDocument(systemPromptArea);
        watchDocument(userPromptArea);

        I18n.retranslate(this);
        MainWindow.standardizeFonts(getRootPane());
        MainWindow.recolorCarets(getRootPane());
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
     * painted in {@link MainWindow#linkColor()} so it reads as a link against
     * either theme's background.
     */
    private JLabel buildApiKeyLink() {
        JLabel link = new JLabel("<html><u>" + API_KEYS_URL + "</u></html>");
        link.setForeground(MainWindow.linkColor());
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setToolTipText(API_KEYS_URL);
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                MainWindow.browse(API_KEYS_URL,
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
            Config.updateDotenv(baseDir, values);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    I18n.t("Failed to save settings to {0}: {1}",
                            baseDir.resolve(".env"), e.getMessage()),
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
        MainWindow.standardizeFonts(getRootPane());
        MainWindow.recolorCarets(getRootPane());
        apiKeyLink.setForeground(MainWindow.linkColor());
        updateApplyEnabled();
    }
}
