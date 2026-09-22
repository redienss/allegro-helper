package com.allegrohelper.ui;

import com.allegrohelper.core.Config;
import com.allegrohelper.core.GenerateDescription;
import com.allegrohelper.core.QrCode;
import com.allegrohelper.core.SeriesRecognition;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
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
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
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
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The File &gt; Settings dialog: a PhpStorm-style two-pane layout — the page
 * list on the left, the selected page's card on the right, OK/Cancel/Apply
 * below. Pages: Appearance ({@link Theme}), Language ({@link Language}),
 * Media (the default series recognition mode) and OpenAI API (API key, model
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
    private static final String PAGE_PHOTOS = "Media";
    private static final String PAGE_OPENAI = "OpenAI API";
    private static final String PAGE_QR_CODE = "QR Code";

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
            "SUBFOLDERS - Each subfolder as a separate item",
            "VIDEO - Extract frames from each video"});
    private final JTextField videoIntervalField = new JTextField();
    /** Item order mirrors {@link QrCode.LabelPosition#values()} — index maps straight to ordinal, like {@link MainWindow}'s own combo. */
    private final JTextField qrDefaultUrlField = new JTextField();
    private final JTextField qrDefaultLabelField = new JTextField();
    private final JTextField qrDefaultFontSizeField = new JTextField();
    private final JComboBox<String> qrDefaultLabelPositionCombo = new JComboBox<>(new String[]{
            "Above the QR code", "Below the QR code"});
    private final JTextField qrDefaultSizeField = new JTextField();
    private final JTextField qrDefaultPaddingField = new JTextField();
    private final JTextField qrDefaultBorderField = new JTextField();
    private final Map<QrCode.Position, JToggleButton> qrDefaultPositionButtons = new EnumMap<>(QrCode.Position.class);
    /** Placeholder only — overwritten by {@link #loadQrDefaultsSettings()} before the dialog is shown. */
    private QrCode.Position qrDefaultSelectedPosition = QrCode.Position.NE;
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

    /** The effective video frame interval as of the last load/save, for the dirty check. */
    private double savedVideoFrameInterval = Config.DEFAULT_VIDEO_FRAME_INTERVAL_SECONDS;

    /** The base directory as of the last load/save; compared against the field. */
    private String savedBaseDir = "";

    /** The effective QR Code default values as of the last load/save, for the dirty check. */
    private String savedQrUrl = "";
    private String savedQrLabel = "";
    private int savedQrLabelFontSize = Config.DEFAULT_QR_LABEL_FONT_SIZE;
    private QrCode.LabelPosition savedQrLabelPosition = Config.DEFAULT_QR_LABEL_POSITION;
    private int savedQrSizePx = Config.DEFAULT_QR_SIZE_PX;
    private int savedQrPaddingPx = Config.DEFAULT_QR_PADDING_PX;
    private int savedQrBorderPx = Config.DEFAULT_QR_BORDER_PX;
    private QrCode.Position savedQrPosition = Config.DEFAULT_QR_POSITION;

    /**
     * What an Apply changed, for {@link MainWindow} to adopt into its own
     * controls. A null field means that value did not change — the main window
     * must not have a setting it did not touch reset underneath it just because
     * someone opened this dialog to change the theme.
     */
    record Applied(Path baseDir, String photoDir, SeriesRecognition.Mode seriesMode,
                   Double videoFrameInterval) {
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
                new String[]{PAGE_APPEARANCE, PAGE_LANGUAGE, PAGE_PHOTOS, PAGE_OPENAI, PAGE_QR_CODE});
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
        content.add(buildQrCodePage(), PAGE_QR_CODE);
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
        loadQrDefaultsSettings();
        applyButton.setEnabled(false);
        themeCombo.addActionListener(e -> updateApplyEnabled());
        languageCombo.addActionListener(e -> updateApplyEnabled());
        seriesModeCombo.addActionListener(e -> updateApplyEnabled());
        watchDocument(baseDirField);
        watchDocument(photoDirField);
        watchDocument(videoIntervalField);
        watchDocument(qrDefaultUrlField);
        watchDocument(qrDefaultLabelField);
        watchDocument(qrDefaultFontSizeField);
        watchDocument(qrDefaultSizeField);
        watchDocument(qrDefaultPaddingField);
        watchDocument(qrDefaultBorderField);
        qrDefaultLabelPositionCombo.addActionListener(e -> updateApplyEnabled());
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
     * The Media page: where the app starts (base directory), where media comes
     * from (the MTP glob), how it is grouped into offers, and — in video mode —
     * how far apart extracted frames are.
     *
     * <p>These are the same controls the main window carries at the top — here
     * they are the <em>defaults</em> those controls start from, so the values a
     * user works with every day stop being a per-launch chore.
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
        addDirRow(page, 2, "Media directory:", photoDirField, e -> choosePhotoDir());

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

        c.gridx = 0;
        c.gridy = 4;
        c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        c.insets = new Insets(6, 0, 0, 8);
        page.add(new JLabel("Frame interval (s):"), c);
        c.gridx = 1;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(6, 0, 0, 0);
        page.add(videoIntervalField, c);

        // Glue below keeps the rows pinned to the top.
        c.gridx = 0;
        c.gridy = 5;
        c.weighty = 1;
        page.add(Box.createGlue(), c);
        return page;
    }

    /** One directory row on the Media page: label, field, Browse. */
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
     * The QR Code page: the same fields as the QR Code tab's own form, but as
     * the tab's <em>starting point</em> for an offer with nothing saved yet —
     * the built-in values are sized for a 4000x3000px photo, which overshoots
     * a lower-resolution source (e.g. FHD video frames) badly enough that the
     * QR code and its label no longer fit the padding around them.
     */
    private JPanel buildQrCodePage() {
        qrDefaultFontSizeField.setColumns(8);
        qrDefaultSizeField.setColumns(8);
        qrDefaultPaddingField.setColumns(8);
        qrDefaultBorderField.setColumns(8);
        // Nimbus indents a combo box's own text less than a text field's, so
        // without this the two visibly don't line up in the form — the same
        // fix the QR Code tab's own label-position combo uses.
        Insets textFieldInsets = UIManager.getInsets("TextField.contentMargins");
        int qrComboLeftPad = (textFieldInsets != null ? textFieldInsets.left : 6) + 4;
        qrDefaultLabelPositionCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                Object shown = value == null ? null : I18n.t(value.toString());
                Component c = super.getListCellRendererComponent(list, shown, index, isSelected, cellHasFocus);
                if (c instanceof JComponent jc) {
                    jc.setBorder(BorderFactory.createEmptyBorder(0, qrComboLeftPad, 0, 0));
                }
                return c;
            }
        });

        JPanel page = new JPanel(new GridBagLayout());
        page.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(0, 0, 16, 0);
        JLabel headerLabel = new JLabel(PAGE_QR_CODE);
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD));
        page.add(headerLabel, c);

        c.gridwidth = 1; // each field row below is only its own column, not the header's span
        addRow(page, c, 1, "URL:", qrDefaultUrlField, GridBagConstraints.HORIZONTAL, 0);
        addRow(page, c, 2, "Label:", qrDefaultLabelField, GridBagConstraints.HORIZONTAL, 0);
        addRow(page, c, 3, "Label font size (px):", qrDefaultFontSizeField, GridBagConstraints.NONE, 0);
        addRow(page, c, 4, "Label position:", qrDefaultLabelPositionCombo, GridBagConstraints.NONE, 0);
        addRow(page, c, 5, "Size (px):", qrDefaultSizeField, GridBagConstraints.NONE, 0);
        addRow(page, c, 6, "Inner padding (px):", qrDefaultPaddingField, GridBagConstraints.NONE, 0);
        addRow(page, c, 7, "Outer border (px):", qrDefaultBorderField, GridBagConstraints.NONE, 0);

        c.gridx = 0;
        c.gridy = 8;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        c.weighty = 0;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(0, 0, 0, 8);
        page.add(new JLabel(I18n.t("Position:")), c);
        c.gridx = 1;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 0, 0);
        page.add(qrDefaultPositionGrid(), c);

        // Glue below keeps the rows pinned to the top.
        c.gridx = 0;
        c.gridy = 9;
        c.weighty = 1;
        page.add(Box.createGlue(), c);
        return page;
    }

    /**
     * The nine-position picker for the QR Code page — the same layout as
     * {@code MainWindow.qrPositionGrid()}, writing to this dialog's own
     * selection instead of the QR Code tab's live one.
     */
    private JPanel qrDefaultPositionGrid() {
        JPanel grid = new JPanel(new GridLayout(3, 3, 2, 2));
        ButtonGroup group = new ButtonGroup();
        QrCode.Position[] order = {
            QrCode.Position.NW, QrCode.Position.N, QrCode.Position.NE,
            QrCode.Position.W, QrCode.Position.CENTER, QrCode.Position.E,
            QrCode.Position.SW, QrCode.Position.S, QrCode.Position.SE,
        };
        String[] symbols = {"↖", "↑", "↗", "←", "•", "→", "↙", "↓", "↘"};
        String[] tooltips = {
            "Top-left", "Top", "Top-right", "Left", "Center", "Right", "Bottom-left", "Bottom", "Bottom-right",
        };
        for (int i = 0; i < order.length; i++) {
            QrCode.Position position = order[i];
            JToggleButton button = new JToggleButton(symbols[i]);
            button.setToolTipText(I18n.t(tooltips[i]));
            button.setSelected(position == qrDefaultSelectedPosition);
            button.addActionListener(e -> {
                qrDefaultSelectedPosition = position;
                updateApplyEnabled();
            });
            group.add(button);
            qrDefaultPositionButtons.put(position, button);
            grid.add(button);
        }
        return grid;
    }

    private void selectQrDefaultPosition(QrCode.Position position) {
        qrDefaultSelectedPosition = position;
        JToggleButton button = qrDefaultPositionButtons.get(position);
        if (button != null) {
            button.setSelected(true);
        }
    }

    /** The label position combo's current selection, translated back from its list index. */
    private QrCode.LabelPosition selectedQrDefaultLabelPosition() {
        return QrCode.LabelPosition.values()[qrDefaultLabelPositionCombo.getSelectedIndex()];
    }

    /**
     * (Re)loads the QR Code page from the effective configuration and resets
     * the dirty baseline. Also run after a save, so a value that fell back to
     * its built-in default (e.g. a cleared URL) reappears as that default.
     */
    private void loadQrDefaultsSettings() {
        Config cfg = Config.forBaseDir(baseDir);
        savedQrUrl = cfg.qrDefaultUrl;
        savedQrLabel = cfg.qrDefaultLabel;
        savedQrLabelFontSize = cfg.qrDefaultLabelFontSize;
        savedQrLabelPosition = cfg.qrDefaultLabelPosition;
        savedQrSizePx = cfg.qrDefaultSizePx;
        savedQrPaddingPx = cfg.qrDefaultPaddingPx;
        savedQrBorderPx = cfg.qrDefaultBorderPx;
        savedQrPosition = cfg.qrDefaultPosition;
        qrDefaultUrlField.setText(savedQrUrl);
        qrDefaultLabelField.setText(savedQrLabel);
        qrDefaultFontSizeField.setText(String.valueOf(savedQrLabelFontSize));
        qrDefaultLabelPositionCombo.setSelectedIndex(savedQrLabelPosition.ordinal());
        qrDefaultSizeField.setText(String.valueOf(savedQrSizePx));
        qrDefaultPaddingField.setText(String.valueOf(savedQrPaddingPx));
        qrDefaultBorderField.setText(String.valueOf(savedQrBorderPx));
        selectQrDefaultPosition(savedQrPosition);
    }

    /** Whether anything on the QR Code page differs from what is in effect. */
    private boolean qrDefaultsDirty() {
        return !qrDefaultUrlField.getText().strip().equals(savedQrUrl)
                || !qrDefaultLabelField.getText().strip().equals(savedQrLabel)
                || !qrDefaultFontSizeField.getText().strip().equals(String.valueOf(savedQrLabelFontSize))
                || selectedQrDefaultLabelPosition() != savedQrLabelPosition
                || !qrDefaultSizeField.getText().strip().equals(String.valueOf(savedQrSizePx))
                || !qrDefaultPaddingField.getText().strip().equals(String.valueOf(savedQrPaddingPx))
                || !qrDefaultBorderField.getText().strip().equals(String.valueOf(savedQrBorderPx))
                || qrDefaultSelectedPosition != savedQrPosition;
    }

    /**
     * Writes the QR Code defaults to the global settings file. A value equal
     * to its built-in default (or, for the URL, blank) is removed rather than
     * written, so {@code .env} only ever carries the overrides — the same
     * idiom {@link #saveOpenAiSettings()} uses.
     */
    private boolean saveQrDefaultsSettings() {
        String url = qrDefaultUrlField.getText().strip();
        String label = qrDefaultLabelField.getText().strip();
        int fontSize = parsePositiveIntOrDefault(
                qrDefaultFontSizeField.getText(), Config.DEFAULT_QR_LABEL_FONT_SIZE);
        int sizePx = parsePositiveIntOrDefault(qrDefaultSizeField.getText(), Config.DEFAULT_QR_SIZE_PX);
        int paddingPx = parseNonNegativeIntOrDefault(
                qrDefaultPaddingField.getText(), Config.DEFAULT_QR_PADDING_PX);
        int borderPx = parseNonNegativeIntOrDefault(qrDefaultBorderField.getText(), Config.DEFAULT_QR_BORDER_PX);
        QrCode.LabelPosition labelPosition = selectedQrDefaultLabelPosition();

        Map<String, String> values = new LinkedHashMap<>();
        values.put("QR_DEFAULT_URL", url.isEmpty() || url.equals(Config.DEFAULT_QR_URL) ? null : url);
        values.put("QR_DEFAULT_LABEL", label.equals(Config.DEFAULT_QR_LABEL) ? null : label);
        values.put("QR_DEFAULT_LABEL_FONT_SIZE",
                fontSize == Config.DEFAULT_QR_LABEL_FONT_SIZE ? null : String.valueOf(fontSize));
        values.put("QR_DEFAULT_LABEL_POSITION",
                labelPosition == Config.DEFAULT_QR_LABEL_POSITION ? null : labelPosition.name());
        values.put("QR_DEFAULT_SIZE_PX", sizePx == Config.DEFAULT_QR_SIZE_PX ? null : String.valueOf(sizePx));
        values.put("QR_DEFAULT_PADDING_PX",
                paddingPx == Config.DEFAULT_QR_PADDING_PX ? null : String.valueOf(paddingPx));
        values.put("QR_DEFAULT_BORDER_PX",
                borderPx == Config.DEFAULT_QR_BORDER_PX ? null : String.valueOf(borderPx));
        values.put("QR_DEFAULT_POSITION",
                qrDefaultSelectedPosition == Config.DEFAULT_QR_POSITION ? null : qrDefaultSelectedPosition.name());
        try {
            Config.updateDotenv(values);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    I18n.t("Failed to save settings to {0}: {1}",
                            Config.globalEnvPath(), e.getMessage()),
                    I18n.t("Settings"), JOptionPane.ERROR_MESSAGE);
            return false;
        }
        loadQrDefaultsSettings();
        return true;
    }

    /** Tolerant of blank/invalid/non-positive text, like {@code Config}'s own numeric parsing. */
    private static int parsePositiveIntOrDefault(String typed, int fallback) {
        try {
            int value = Integer.parseInt(typed.strip());
            return value > 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Tolerant of blank/invalid/negative text, like {@code Config}'s own numeric parsing. */
    private static int parseNonNegativeIntOrDefault(String typed, int fallback) {
        try {
            int value = Integer.parseInt(typed.strip());
            return value >= 0 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
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
     * Fills the Media page from the effective configuration, so a real
     * environment variable (which outranks {@code .env}) shows up here and
     * keeps winning after a save, exactly like the OpenAI page.
     */
    private void loadPhotoSettings() {
        Config cfg = Config.forBaseDir(baseDir);
        // The saved startup default, which is the base directory in use when
        // none has been saved yet.
        savedBaseDir = Config.savedBaseDir(baseDir).toString();
        savedPhotoDir = cfg.mtpGlobPattern;
        savedSeriesMode = cfg.seriesRecognition;
        savedVideoFrameInterval = cfg.videoFrameIntervalSeconds;
        baseDirField.setText(savedBaseDir);
        photoDirField.setText(savedPhotoDir);
        seriesModeCombo.setSelectedIndex(savedSeriesMode.ordinal());
        videoIntervalField.setText(String.valueOf(savedVideoFrameInterval));
    }

    /** The mode the combo is showing; index and ordinal are the same order. */
    private SeriesRecognition.Mode selectedSeriesMode() {
        int i = seriesModeCombo.getSelectedIndex();
        return i < 0 ? SeriesRecognition.Mode.AUTO : SeriesRecognition.Mode.values()[i];
    }

    /**
     * Persists the Media page: the base directory as a user preference, the
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
        double interval = parseIntervalOrDefault(videoIntervalField.getText());
        boolean baseDirChanged = !typedBaseDir.equals(savedBaseDir);
        boolean photoDirChanged = !photoDir.equals(savedPhotoDir);
        boolean modeChanged = mode != savedSeriesMode;
        boolean intervalChanged = interval != savedVideoFrameInterval;

        Map<String, String> values = new LinkedHashMap<>();
        if (baseDirChanged) {
            // A path that does not name a directory clears the key rather than
            // being saved: the app would open on it next launch and every
            // derived path would be broken.
            Path resolved = baseDirOrNull(typedBaseDir);
            values.put("BASE_DIR", resolved == null ? null : resolved.toString());
        }
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
        if (intervalChanged) {
            values.put("VIDEO_FRAME_INTERVAL_SECONDS",
                    interval == Config.DEFAULT_VIDEO_FRAME_INTERVAL_SECONDS
                            ? null : String.valueOf(interval));
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
        Path newBaseDir = baseDirChanged ? baseDirOrNull(typedBaseDir) : null;
        loadPhotoSettings();
        // Report the *effective* values, not the typed ones: an environment
        // variable outranks the settings file, so what was asked for is not
        // always what a run will use — and the main window must show what a run
        // will use.
        return new Applied(newBaseDir,
                photoDirChanged ? savedPhotoDir : null,
                modeChanged ? savedSeriesMode : null,
                intervalChanged ? savedVideoFrameInterval : null);
    }

    /** Tolerant of blank/invalid text, like {@code Config}'s own numeric parsing. */
    private static double parseIntervalOrDefault(String typed) {
        String text = typed.strip();
        if (text.isEmpty()) {
            return Config.DEFAULT_VIDEO_FRAME_INTERVAL_SECONDS;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return Config.DEFAULT_VIDEO_FRAME_INTERVAL_SECONDS;
        }
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

    /** Whether anything on the Media page differs from what is in effect. */
    private boolean photoSettingsDirty() {
        return selectedSeriesMode() != savedSeriesMode
                || !photoDirField.getText().strip().equals(savedPhotoDir)
                || !baseDirField.getText().strip().equals(savedBaseDir)
                || parseIntervalOrDefault(videoIntervalField.getText()) != savedVideoFrameInterval;
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
                || openaiDirty()
                || qrDefaultsDirty());
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
        if (qrDefaultsDirty()) {
            saveQrDefaultsSettings();
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
