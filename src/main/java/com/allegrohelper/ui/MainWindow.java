package com.allegrohelper.ui;

import com.allegrohelper.core.AllegroForm;
import com.allegrohelper.core.Config;
import com.allegrohelper.core.PhoneScan;
import com.allegrohelper.core.Reporter;
import com.allegrohelper.core.Retouch;
import com.allegrohelper.core.RetouchPreview;
import com.allegrohelper.core.PhotoSeries;
import com.allegrohelper.core.SeriesRecognition;
import com.allegrohelper.core.Workflow;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.ImageIcon;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Image;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.net.URL;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Allegro Helper main window: photo series detected on the phone, the
 * editable offer grid, the workflow selector, a progress bar and a log.
 */
public final class MainWindow {

    /** Height the logo is scaled to (aspect ratio preserved). */
    private static final int LOGO_HEIGHT = 150;

    /** Right-panel tab indices. */
    private static final int TAB_DESCRIPTION_INPUT = 0;   // more_data_<N>.txt editor
    private static final int TAB_PHOTOS_INPUT = 1;    // original photos gallery
    private static final int TAB_RETOUCH_PREVIEW = 2; // before/after of the retouching steps
    private static final int TAB_PHOTOS_OUTPUT = 3;   // retouched photos gallery
    private static final int TAB_OCR = 4;             // ocr.txt editor
    private static final int TAB_DESCRIPTION_OUTPUT = 5;  // description.txt editor
    private static final int TAB_ALLEGRO_FORM = 6;    // copy helper for the Allegro Lokalnie form

    /**
     * Thumbnail box size (px) for the Photos (Input)/(Output) galleries, sized
     * so 4 fit per row on the user's maximized 1920x1080 laptop (right panel ≈
     * 1000–1080 px inside; a cell is the thumbnail + 16 px, so 4×226 fits with
     * room to spare and a 5th can't squeeze in even without the scrollbar).
     */
    private static final int THUMB_SIZE = 210;

    /**
     * Smaller thumbnails on the Allegro form tab, sized so 8 fit per row on the
     * user's 1920x1080 laptop (right panel ≈ 900–940 px inside; a cell is the
     * thumbnail + 16 px, so 8×112 fits and a 9th doesn't).
     */
    private static final int FORM_THUMB_SIZE = 96;

    /** Allegro Lokalnie allows at most this many photos per offer. */
    private static final int ALLEGRO_MAX_PHOTOS = 16;

    /**
     * Longest side (px) of the Retouch Preview images. They are only ever painted
     * scaled to fit half the right panel, so keeping full-resolution copies of a
     * 12-megapixel photo around would cost memory and repaint time for nothing.
     */
    private static final int PREVIEW_MAX_SIZE = 1200;

    /** Gap between the Retouch Preview's two halves, shared by the photos and their histograms. */
    static final int PREVIEW_HALF_GAP = 6;

    /** Pipeline steps, i.e. checkboxes in the Workflow section — {@link Workflow.Step}'s count. */
    private static final int WORKFLOW_STEPS = Workflow.Step.values().length;

    /** How many step checkboxes the Workflow section puts on one row. */
    private static final int WORKFLOW_BOX_COLUMNS = 4;

    private static final String ALLEGRO_FORM_URL = "https://allegrolokalnie.pl/o/oferty/wystaw";

    private final JFrame frame = new JFrame("Allegro Helper");
    private final JTextField baseDirField = new JTextField();
    private final JTextField photoDirField = new JTextField();
    /**
     * Item order mirrors {@link SeriesRecognition.Mode#values()}; the short prefix
     * names the mode. Items stay these English keys — a renderer translates at
     * paint time, so a language switch only needs a repaint.
     */
    private final JComboBox<String> seriesModeCombo = new JComboBox<>(new String[]{
            "AUTO - Auto detect photo series",
            "SINGLE - All photos in the directory as one item",
            "SUBFOLDERS - Each subfolder as a separate item"});
    private final DefaultListModel<String> photosModel = new DefaultListModel<>();
    private final OfferTableModel offerModel = new OfferTableModel();
    private final JTable offerTable = new JTable(offerModel);

    private final JCheckBox importBox = new JCheckBox("Import", true);
    private final JCheckBox matchBox = new JCheckBox("Match", true);
    private final JCheckBox whiteBalanceBox = new JCheckBox("White balance", true);
    private final JCheckBox brightnessBox = new JCheckBox("Brightness", true);
    private final JCheckBox contrastBox = new JCheckBox("Contrast", true);
    private final JCheckBox autoCropBox = new JCheckBox("Auto-crop", true);
    private final JCheckBox ocrBox = new JCheckBox("OCR", true);
    private final JCheckBox describeBox = new JCheckBox("Describe", true);

    private final JButton importMatchButton = new JButton("Import & Match");
    private final JButton startButton = new JButton("Start Workflow");
    private final JButton deleteOutputsButton = new JButton("Delete Output Files");
    private final JButton cleanRestartButton = new JButton("Clean & Restart");
    private final JButton refreshPhotosButton = new JButton("Refresh");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JTextArea logArea = new JTextArea();

    private final JLabel detailsHeader = new JLabel();
    private final JTabbedPane rightTabs = new JTabbedPane();
    // Styled panes (not JTextArea) so emoji can be painted as color images.
    private final JTextPane moreDataArea = new JTextPane();   // Description (Input) -> more_data_<N>.txt
    private final JTextPane detailsArea = new JTextPane();    // Description (Output) -> description.txt
    private final JTextPane ocrArea = new JTextPane();        // OCR -> ocr.txt

    // Photo galleries; a single background thread loads their thumbnails.
    private final ExecutorService galleryLoader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gallery-loader");
        t.setDaemon(true);
        return t;
    });
    private final Gallery photosInputGallery =
            new Gallery(THUMB_SIZE, 0, galleryLoader, this::openInSystem);
    private final Gallery photosOutputGallery =
            new Gallery(THUMB_SIZE, 0, galleryLoader, this::openInSystem);
    private final Gallery formGallery =
            new Gallery(FORM_THUMB_SIZE, ALLEGRO_MAX_PHOTOS, galleryLoader, this::openInSystem);

    // Retouch Preview tab: the offer's first photo before and after the ticked
    // retouching steps, with checkboxes mirroring their Workflow twins. Rendering
    // decodes and processes a full-size photo (and, for auto-crop, scans the whole
    // series), so it runs on its own thread rather than blocking the galleries'.
    private final ImagePanel beforePanel = new ImagePanel("Before");
    private final ImagePanel afterPanel = new ImagePanel("After");
    private final HistogramPanel beforeHistogram = new HistogramPanel();
    private final HistogramPanel afterHistogram = new HistogramPanel();
    private final JCheckBox previewWhiteBalanceBox = new JCheckBox("White balance", true);
    private final JCheckBox previewBrightnessBox = new JCheckBox("Brightness", true);
    private final JCheckBox previewContrastBox = new JCheckBox("Contrast", true);
    private final JCheckBox previewAutoCropBox = new JCheckBox("Auto-crop", true);
    /**
     * Every strength dial on the tab. {@code StrengthDial.DialRowLayout} measures
     * across them, so the sliders start and end on the same two columns however
     * wide each step's name happens to be.
     */
    private final List<StrengthDial> dials = new ArrayList<>();
    private final StrengthDial brightnessDial = new StrengthDial(previewBrightnessBox,
            dials, this::refreshRetouchPreview,
            Retouch.DEFAULT_BRIGHTNESS,
            "1.00x leaves the photo as it is; less darkens it, more brightens it.");
    private final StrengthDial contrastDial = new StrengthDial(previewContrastBox,
            dials, this::refreshRetouchPreview,
            Retouch.DEFAULT_CONTRAST,
            "1.00x leaves the photo as it is; less flattens it, more deepens it.");
    private final ExecutorService previewLoader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "retouch-preview");
        t.setDaemon(true);
        return t;
    });
    /** Rising counter identifying the newest preview render; older ones drop their result. */
    private final AtomicInteger previewToken = new AtomicInteger();
    /** Set when the preview would have to be re-rendered but its tab is not showing. */
    private boolean previewStale = true;

    // Stepping through the offer's photos, under the Before panel. The count is not
    // known until a render has listed the photo directory, so the label and the
    // buttons are driven by the Result, not by the click that asked for it.
    /**
     * Marks the clipped pixels on both photos. Off by default: the marks cover the
     * photo they are diagnosing, so they are a thing to reach for when a histogram
     * looks piled against an edge, not a permanent overlay. Not one of the step
     * checkboxes — it changes nothing a run would do — so it sits under the After
     * photo, opposite the stepper, rather than in the column of things that will
     * happen.
     */
    private final JCheckBox showClippingBox = new JCheckBox("Show clipping", false);

    private final JButton firstPhotoButton = new JButton("|<");
    private final JButton previousPhotoButton = new JButton("< Prev");
    private final JButton nextPhotoButton = new JButton("Next >");
    private final JButton lastPhotoButton = new JButton(">|");
    private final JLabel photoIndexLabel = new JLabel();
    /** Which photo of the selected offer the preview shows, 0-based. */
    private int previewPhotoIndex;
    /** Photos in the selected offer, as of the last render; 0 before there has been one. */
    private int previewPhotoCount;

    // Allegro Lokalnie Form tab: copy sources for the listing form.
    private final JTextField formTitleField = new JTextField();
    private final JTextPane formDescriptionArea = new JTextPane();

    private JButton deleteButton;
    private JButton clearButton;
    private JButton saveButton;
    private JButton openPhotoDirButton;
    /** The clickable Allegro form URL; its color is re-picked on a theme change. */
    private JLabel formUrlLink;
    /** Bottom bar swapped per tab: editor buttons vs. the photo-gallery button. */
    private JPanel bottomBars;
    private static final String CARD_EDITOR = "editor";
    private static final String CARD_PHOTOS = "photos";

    /** The height-capped left-panel sections, kept so a theme change can re-measure them. */
    private final List<JPanel> cappedSections = new ArrayList<>();

    /** Offer directory of the selected row, or null when none / not matched yet. */
    private Path currentOfferDir;

    // Save targets for the currently selected offer row (null when nothing is selected /
    // no offer directory exists yet).
    private Path moreDataTarget;
    private Path descriptionTarget;
    private Path ocrTarget;

    /**
     * Which editor tabs hold edits not yet written to their file, indexed by
     * {@code TAB_*}. Only the three editor tabs are ever set. A run reloads the
     * editors from disk, so without the marker an unsaved description vanished
     * on Start with nothing having warned about it.
     */
    private final boolean[] editorDirty = new boolean[7];

    /**
     * Set while the editors are being filled from disk, so the document
     * listeners can tell a programmatic reload from the user typing.
     */
    private boolean loadingEditors;

    /** Why the last {@link #saveTab} returned false; only read right after one did. */
    private String lastSaveError;

    private volatile boolean running = false;

    /**
     * Builds the window for a base directory and loads its offers. Nothing is
     * shown until {@link #show()}.
     */
    public MainWindow(Path initialBaseDir) {
        baseDirField.setText(initialBaseDir.toAbsolutePath().normalize().toString());
        // Seed with the configured photo source (MTP_GLOB_PATTERN or the default
        // phone glob) so the user sees — and can change — where photos come from.
        Config initial = Config.forBaseDir(initialBaseDir);
        photoDirField.setText(initial.mtpGlobPattern);
        seriesModeCombo.setSelectedIndex(initial.seriesRecognition.ordinal());
        build();
        loadOffersFromBaseDir();
    }

    /** Shows the window and scans the phone once, so Photos is populated without a first click. */
    public void show() {
        frame.setVisible(true);
        // Scan the phone on launch so the Photos section is populated without
        // the user having to click Refresh the first time.
        refreshPhotos();
    }

    /** The underlying frame (exposed for embedding and for offscreen rendering in tests). */
    public JFrame getFrame() {
        return frame;
    }

    /**
     * Adopts the defaults Settings just saved. Only the values that actually
     * changed arrive non-null, so a control the user set for this session
     * survives an unrelated trip through the dialog.
     *
     * <p>Order matters: the base directory goes first, because the photo
     * directory and the series mode were read from <em>its</em> {@code .env}
     * and the offers to load come from it too. Setting the series mode fires
     * the combo's listener, which re-scans the photo source — which is what
     * should happen, so that re-scan is left to run last and pick up the new
     * photo directory.
     */
    private void applyDefaults(SettingsDialog.Applied applied) {
        if (applied.baseDir() != null) {
            baseDirField.setText(applied.baseDir().toString());
            loadOffersFromBaseDir();
        }
        if (applied.photoDir() != null) {
            photoDirField.setText(applied.photoDir());
        }
        if (applied.seriesMode() != null) {
            seriesModeCombo.setSelectedIndex(applied.seriesMode().ordinal());
        } else if (applied.photoDir() != null || applied.baseDir() != null) {
            refreshPhotos(); // no combo event to do it for us
        }
    }

    /** Selects the given offer row (0-based), updating the details panel. */
    public void selectOfferRow(int index) {
        if (index >= 0 && index < offerModel.getRowCount()) {
            offerTable.setRowSelectionInterval(index, index);
        }
    }

    /**
     * Assembles the whole window: the control stack and log on the left, the
     * tabbed offer details on the right. Styling that the look and feel does not
     * cover (fonts, carets, tab colors) is applied at the end, which is why
     * {@link #onSettingsApplied()} has to redo it after a theme change.
     */
    private void build() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setJMenuBar(buildMenuBar());
        frame.setPreferredSize(new Dimension(1600, 920));
        frame.setLayout(new BorderLayout(8, 8));

        // Left panel: the full control stack on top (each section keeps its
        // preferred height but fills the available width) with the log below.
        JPanel dirsPanel = buildDirsPanel();
        JPanel photosPanel = buildPhotosPanel();
        JPanel offerPanel = buildOfferPanel();
        JPanel workflowPanel = buildWorkflowPanel();
        JPanel progressPanel = buildProgressPanel();

        // Top area: the application logo in the upper-left corner, with the base
        // directory and Photos section shifted to its right.
        JPanel topRight = new JPanel();
        topRight.setLayout(new BoxLayout(topRight, BoxLayout.Y_AXIS));
        dirsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        photosPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        topRight.add(dirsPanel);
        topRight.add(photosPanel);

        JPanel topArea = new JPanel(new BorderLayout());
        topArea.add(buildLogoLabel(), BorderLayout.WEST);
        topArea.add(topRight, BorderLayout.CENTER);

        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        for (JPanel section : List.of(topArea, offerPanel, workflowPanel, progressPanel)) {
            section.setAlignmentX(Component.LEFT_ALIGNMENT);
            controls.add(section);
        }

        JPanel left = new JPanel(new BorderLayout(8, 8));
        left.add(controls, BorderLayout.NORTH);
        left.add(buildLogPanel(), BorderLayout.CENTER);

        // Right panel: details of the offer selected in the grid.
        JPanel right = buildDetailsPanel();

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.5); // keep the two halves equal-width on resize
        frame.add(split, BorderLayout.CENTER);

        setWindowIcon();
        installSaveShortcut();

        // Translate the built (English) texts before measuring; a no-op under English.
        I18n.retranslate(frame);
        // Enlarge and unify fonts across the whole window before measuring, so the
        // fixed-height sections are sized for the final (larger) text.
        UiStyle.standardizeFonts(frame.getRootPane());
        updateTabStyles(); // re-assert tab bold/dim after fonts are standardized
        offerTable.setRowHeight(offerTable.getFontMetrics(offerTable.getFont()).getHeight() + 6);
        cappedSections.addAll(List.of(dirsPanel, photosPanel, topArea, offerPanel, workflowPanel, progressPanel));
        for (JPanel section : cappedSections) {
            UiStyle.capHeight(section);
        }

        frame.pack();
        frame.setLocationRelativeTo(null);
        SwingUtilities.invokeLater(() -> split.setDividerLocation(0.5));
    }

    /**
     * Binds Ctrl+S (Cmd+S on macOS) to saving the active editor tab. The binding
     * lives on the root pane with {@code WHEN_IN_FOCUSED_WINDOW}, so it fires
     * while the caret is in a text pane — where the user actually is when they
     * want to save — rather than only when some particular component has focus.
     * On a non-editor tab {@link #saveActiveTab()} is a no-op.
     */
    private void installSaveShortcut() {
        KeyStroke saveKey = KeyStroke.getKeyStroke(KeyEvent.VK_S,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        JRootPane root = frame.getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(saveKey, "save-active-tab");
        root.getActionMap().put("save-active-tab", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveActiveTab();
            }
        });
    }

    /** The top menu bar: File > Settings… / Exit. */
    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem settings = new JMenuItem("Settings…");
        settings.addActionListener(e -> new SettingsDialog(frame,
                Path.of(baseDirField.getText().strip()), this::onSettingsApplied,
                this::applyDefaults).setVisible(true));
        JMenuItem exit = new JMenuItem("Exit");
        // Close via the window event so it takes the same path as the title-bar X.
        exit.addActionListener(e -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
        file.add(settings);
        file.addSeparator();
        file.add(exit);
        bar.add(file);
        return bar;
    }

    /**
     * Re-applies the styling {@link #build()} baked in for the previous theme
     * or language, after the Settings dialog changed either at runtime: caret
     * colors, the grid's cell editors (updateComponentTreeUI does not reach
     * editors that aren't in the component tree), fonts on the recreated
     * editors, and the tab highlight. The dialog already retranslated the
     * component tree; texts set at runtime (galleries, the photos list, the
     * details header) catch up on their next refresh.
     */
    private void onSettingsApplied() {
        UiStyle.recolorCarets(frame.getRootPane());
        styleGridEditors();
        UiStyle.standardizeFonts(frame.getRootPane());
        updateTabStyles();
        if (formUrlLink != null) {
            formUrlLink.setForeground(UiStyle.linkColor()); // the link blue is picked per theme
        }
        // Re-measure the height-capped sections: component heights differ
        // between look and feels, and stale caps clip rows.
        offerTable.setRowHeight(offerTable.getFontMetrics(offerTable.getFont()).getHeight() + 6);
        for (JPanel section : cappedSections) {
            UiStyle.capHeight(section);
        }
        frame.revalidate();
        frame.repaint();
    }

    /** The application logo (top-left), scaled to {@link #LOGO_HEIGHT}; also sets the window icon. */
    private JLabel buildLogoLabel() {
        JLabel label = new JLabel();
        label.setVerticalAlignment(SwingConstants.TOP);
        label.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));

        URL url = getClass().getResource("logo.png");
        if (url == null) {
            return label; // logo not bundled; leave an empty spacer
        }
        ImageIcon original = new ImageIcon(url);

        int w = original.getIconWidth();
        int h = original.getIconHeight();
        if (h > 0) {
            int scaledWidth = Math.round(w * (LOGO_HEIGHT / (float) h));
            Image scaled = original.getImage().getScaledInstance(scaledWidth, LOGO_HEIGHT, Image.SCALE_SMOOTH);
            label.setIcon(new ImageIcon(scaled));
        } else {
            label.setIcon(original);
        }
        return label;
    }

    /** Sets the window/taskbar icon from the bundled app icon, at several sizes. */
    private void setWindowIcon() {
        URL url = getClass().getResource("app-icon.png");
        if (url == null) {
            return;
        }
        Image source = new ImageIcon(url).getImage();
        List<Image> images = new ArrayList<>();
        for (int size : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            images.add(source.getScaledInstance(size, size, Image.SCALE_SMOOTH));
        }
        images.add(source); // full resolution for high-DPI
        frame.setIconImages(images);
    }

    /** Base + photo directory rows; GridBagLayout keeps their labels and fields aligned. */
    private JPanel buildDirsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        addDirRow(panel, 0, "Base directory:", baseDirField, e -> chooseBaseDir(), null);
        addDirRow(panel, 1, "Photo directory:", photoDirField, e -> choosePhotoDir(),
                e -> resetPhotoDir());
        return panel;
    }

    /**
     * Adds one directory row: label, field, Browse, and an optional reset button.
     *
     * @param reset null for a row without one — the field then spans that column
     *              as well, so the Browse buttons of all rows stay aligned
     */
    private static void addDirRow(JPanel panel, int row, String label,
                                  JTextField field, java.awt.event.ActionListener browse,
                                  java.awt.event.ActionListener reset) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        int top = row == 0 ? 0 : 6;
        c.insets = new Insets(top, 0, 0, 6);
        c.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel(label), c);
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        // A row without a reset button lets the field span its column too,
        // keeping the Browse buttons of all rows aligned.
        c.gridwidth = reset == null ? 2 : 1;
        field.setCaretColor(UiStyle.caretColor());
        panel.add(field, c);
        c.weightx = 0;
        c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;
        JButton browseButton = new JButton("Browse…");
        browseButton.addActionListener(browse);
        if (reset != null) {
            c.insets = new Insets(top, 0, 0, 4);
            // The ⟲ glyph comes from a fallback font with taller line metrics,
            // so follow the Browse button's height instead of the glyph's.
            JButton resetButton = new JButton("⟲") {
                @Override
                public Dimension getPreferredSize() {
                    Dimension d = super.getPreferredSize();
                    d.height = browseButton.getPreferredSize().height;
                    return d;
                }
            };
            resetButton.setToolTipText("Restore the default photo directory");
            resetButton.setMargin(new Insets(2, 6, 2, 6));
            resetButton.addActionListener(reset);
            panel.add(resetButton, c);
        }
        c.insets = new Insets(top, 0, 0, 0);
        panel.add(browseButton, c);
    }

    /**
     * The Photos section: the detected series awaiting import, plus Refresh and
     * the recognition-mode combo. Changing the mode re-scans, so the list always
     * previews what the match step would actually do.
     */
    private JPanel buildPhotosPanel() {
        JPanel panel = UiStyle.titled("Photos");
        panel.setLayout(new BorderLayout(6, 6));

        JList<String> list = new JList<>(photosModel);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(880, 84));
        panel.add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        refreshPhotosButton.addActionListener(e -> refreshPhotos());
        buttons.add(refreshPhotosButton);
        // Changing the recognition mode re-scans, so the list always previews
        // what the match step would do with the current settings.
        seriesModeCombo.addActionListener(e -> refreshPhotos());
        seriesModeCombo.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                Object shown = value == null ? null : I18n.t(value.toString());
                return super.getListCellRendererComponent(list, shown, index, isSelected, cellHasFocus);
            }
        });
        buttons.add(seriesModeCombo);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    /** The Offer Data section: the editable CSV grid and its load/save/row buttons. */
    private JPanel buildOfferPanel() {
        JPanel panel = UiStyle.titled("Offer Data");
        panel.setLayout(new BorderLayout(6, 6));

        offerTable.setFillsViewportHeight(true);
        offerTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        offerTable.getTableHeader().setReorderingAllowed(false);
        offerTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        offerTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                loadSelectedOffer();
            }
        });
        styleGridEditors();
        JScrollPane scroll = new JScrollPane(offerTable);
        scroll.setPreferredSize(new Dimension(880, 150));
        panel.add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton load = new JButton("Load CSV…");
        load.addActionListener(e -> loadCsvViaChooser());
        JButton save = new JButton("Save CSV");
        save.addActionListener(e -> saveCsvToBaseDir());
        JButton openInEditor = new JButton("Open CSV in Editor");
        openInEditor.setToolTipText("Open the offers CSV in the system's default .csv application");
        openInEditor.addActionListener(e -> openCsvInEditor());
        JButton reload = new JButton("Reload CSV");
        reload.setToolTipText("Re-read the offers CSV from disk, e.g. after editing it in an external program");
        reload.addActionListener(e -> reloadCsvFromDisk());
        JButton addRow = new JButton("Add Row");
        addRow.addActionListener(e -> offerModel.addEmptyRow());
        JButton removeRow = new JButton("Remove Row");
        removeRow.addActionListener(e -> removeSelectedRow());
        buttons.add(load);
        buttons.add(save);
        buttons.add(openInEditor);
        buttons.add(reload);
        buttons.add(addRow);
        buttons.add(removeRow);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    /**
     * (Re)installs the grid's cell editors with theme-matching carets. Called
     * from build and again on a theme change, because the editors live outside
     * the component tree until a cell is edited.
     */
    private void styleGridEditors() {
        configureInpostColumn();
        if (offerTable.getDefaultEditor(Object.class) instanceof javax.swing.DefaultCellEditor editor
                && editor.getComponent() instanceof JTextField field) {
            field.setCaretColor(UiStyle.caretColor());
        }
    }

    /**
     * Gives the InPost size column a combo editor offering A/B/C. Editable, so
     * a value outside those three can still be typed.
     */
    private void configureInpostColumn() {
        int col = indexOfKey("inpost_size");
        if (col < 0) {
            return;
        }
        TableColumn column = offerTable.getColumnModel().getColumn(col);
        JComboBox<String> combo = new JComboBox<>(new DefaultComboBoxModel<>(new String[]{"A", "B", "C"}));
        combo.setEditable(true);
        if (combo.getEditor().getEditorComponent() instanceof JTextField field) {
            field.setCaretColor(UiStyle.caretColor());
        }
        column.setCellEditor(new javax.swing.DefaultCellEditor(combo));
    }

    /** The Workflow section: one checkbox per pipeline step, Start, and the destructive buttons. */
    private JPanel buildWorkflowPanel() {
        JPanel panel = UiStyle.titled("Workflow");
        panel.setLayout(new BorderLayout(6, 6));

        // A fixed grid, in pipeline order, reading left to right and top to bottom.
        // A FlowLayout would wrap whatever does not fit the window's width onto a
        // row the height-capped section then cuts off — invisibly, which is how the
        // eighth step could go missing. WORKFLOW_BOX_COLUMNS is the only thing to
        // change when a step is added.
        int rows = (int) Math.ceil(WORKFLOW_STEPS / (double) WORKFLOW_BOX_COLUMNS);
        JPanel boxes = new JPanel(new GridLayout(rows, WORKFLOW_BOX_COLUMNS, 5, 2));
        boxes.add(importBox);
        boxes.add(matchBox);
        boxes.add(whiteBalanceBox);
        boxes.add(brightnessBox);
        boxes.add(contrastBox);
        boxes.add(autoCropBox);
        boxes.add(ocrBox);
        boxes.add(describeBox);
        panel.add(boxes, BorderLayout.CENTER);

        // Start on the left; the destructive buttons on the far right so a quick
        // reach for Start cannot land on them.
        JPanel startRow = new JPanel(new BorderLayout());
        JPanel startSide = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        // Import & Match sits before Start because it is the first thing a fresh
        // install needs: until the photos have been imported and matched into
        // offers/, Photos (Input) and Retouch Preview have nothing to show, and
        // the only way to get there used to be to untick six boxes, press Start,
        // and tick them again. It ignores the checkboxes by design.
        importMatchButton.setToolTipText(I18n.t(
                "Run only Import and Match, to get the photos into the offer dirs "
                        + "so Photos (Input) and Retouch Preview have something to show."));
        importMatchButton.addActionListener(e ->
                runWorkflow(List.of(Workflow.Step.IMPORT, Workflow.Step.MATCH)));
        startSide.add(importMatchButton);
        // "Start Workflow", not "Start Entire Workflow": it runs the *ticked*
        // steps, so promising the entire pipeline would be a lie the moment the
        // user unticks one. The tooltip spells that out.
        startButton.setToolTipText(I18n.t(
                "Run every ticked step above, in pipeline order, on all offers in the grid."));
        startButton.addActionListener(e -> startWorkflow());
        startSide.add(startButton);
        JPanel cleanSide = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        deleteOutputsButton.addActionListener(e -> deleteOutputs(false));
        cleanRestartButton.addActionListener(e -> deleteOutputs(true));
        cleanSide.add(deleteOutputsButton);
        cleanSide.add(cleanRestartButton);
        startRow.add(startSide, BorderLayout.WEST);
        startRow.add(cleanSide, BorderLayout.EAST);
        panel.add(startRow, BorderLayout.SOUTH);
        return panel;
    }

    /** The Progress section: the run's overall progress bar. */
    private JPanel buildProgressPanel() {
        JPanel panel = UiStyle.titled("Progress");
        panel.setLayout(new BorderLayout(6, 6));
        progressBar.setStringPainted(true);
        panel.add(progressBar, BorderLayout.CENTER);
        return panel;
    }

    /** The Log section: the read-only, monospaced pipeline log (deliberately untranslated). */
    private JPanel buildLogPanel() {
        JPanel panel = UiStyle.titled("Log");
        panel.setLayout(new BorderLayout(6, 6));
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new java.awt.Font("monospaced", java.awt.Font.PLAIN, 12));
        logArea.setCaretColor(UiStyle.caretColor());
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return panel;
    }

    /**
     * The right panel: the seven {@code TAB_*} tabs over the selected offer, with a
     * {@link CardLayout} button bar below that swaps per tab. Tab titles are
     * custom labels so the selected one stays visibly highlighted whatever the
     * look and feel does.
     */
    private JPanel buildDetailsPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(6, 8, 8, 8),
                BorderFactory.createTitledBorder("Selected Offer")));

        detailsHeader.setText("Select an offer in the grid.");
        detailsHeader.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        panel.add(detailsHeader, BorderLayout.NORTH);

        java.awt.Font mono = new java.awt.Font("monospaced", java.awt.Font.PLAIN, 13);
        for (JTextPane pane : new JTextPane[]{moreDataArea, detailsArea, ocrArea, formDescriptionArea}) {
            pane.setEditable(true); // JTextPane wraps by default
            pane.setFont(mono);
            pane.setCaretColor(UiStyle.caretColor());
            installEmojiRendering(pane);
        }
        // In pipeline order, so the tabs read left to right the way a run
        // executes: the notes that feed the description, the imported photos,
        // the retouching, its output, the text read off it, the description
        // generated from all of that, and finally the form it is pasted into.
        // The TAB_* constants above must stay in step with this order.
        rightTabs.addTab("Description (Input)", new JScrollPane(moreDataArea));
        rightTabs.addTab("Photos (Input)", photosInputGallery.component());
        rightTabs.addTab("Retouch Preview", buildRetouchPreviewTab());
        rightTabs.addTab("Photos (Output)", photosOutputGallery.component());
        rightTabs.addTab("OCR", new JScrollPane(ocrArea));
        rightTabs.addTab("Description (Output)", new JScrollPane(detailsArea));
        rightTabs.addTab("Allegro Lokalnie Form", buildAllegroFormTab());
        // Render tab titles as custom labels we fully control, so the selected tab
        // stays clearly highlighted regardless of the (dark) look and feel.
        for (int i = 0; i < rightTabs.getTabCount(); i++) {
            rightTabs.setTabComponentAt(i, new JLabel(rightTabs.getTitleAt(i)));
        }
        installDirtyTracking(moreDataArea, TAB_DESCRIPTION_INPUT);
        installDirtyTracking(detailsArea, TAB_DESCRIPTION_OUTPUT);
        installDirtyTracking(ocrArea, TAB_OCR);
        rightTabs.addChangeListener(e -> {
            updateTabStyles();
            updateBottomBar();
            if (previewStale) {
                refreshRetouchPreview(); // deferred while the preview tab was hidden
            }
        });
        updateTabStyles();
        panel.add(rightTabs, BorderLayout.CENTER);

        // Description tabs: destructive actions (Delete/Clear) sit in the lower-left
        // corner, away from Save in the lower-right, to avoid accidental clicks.
        deleteButton = new JButton("Delete");
        deleteButton.addActionListener(e -> deleteActiveFile());
        clearButton = new JButton("Clear");
        clearButton.addActionListener(e -> clearActiveEditor());
        JPanel leftButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        leftButtons.add(deleteButton);
        leftButtons.add(clearButton);

        saveButton = new JButton("Save");
        saveButton.setToolTipText("Save the active tab to its file (Ctrl+S)");
        saveButton.addActionListener(e -> saveActiveTab());
        JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        rightButtons.add(saveButton);

        JPanel editorButtonBar = new JPanel(new BorderLayout());
        editorButtonBar.add(leftButtons, BorderLayout.WEST);
        editorButtonBar.add(rightButtons, BorderLayout.EAST);

        // Photos tabs: a single button in the lower-right corner.
        openPhotoDirButton = new JButton("Open photo dir");
        openPhotoDirButton.addActionListener(e -> openActivePhotoDir());
        JPanel openRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        openRow.add(openPhotoDirButton);
        JPanel photoButtonBar = new JPanel(new BorderLayout());
        photoButtonBar.add(openRow, BorderLayout.EAST);

        bottomBars = new JPanel(new CardLayout());
        bottomBars.add(editorButtonBar, CARD_EDITOR);
        bottomBars.add(photoButtonBar, CARD_PHOTOS);
        panel.add(bottomBars, BorderLayout.SOUTH);

        updateBottomBar();
        return panel;
    }

    /**
     * The Retouch Preview tab: the selected offer's first photo as it is now next
     * to the same photo with the ticked retouching steps applied, so the user can
     * judge them before committing a run to disk. The rendering is
     * {@link RetouchPreview} — the pipeline's own code, not a lookalike.
     *
     * <p>Its four checkboxes are the Workflow section's, mirrored: ticking one
     * here ticks its twin there and vice versa, so there is only one truth about
     * which steps will run. Only {@link #linkRetouchBoxes} writes the twin, and
     * {@code setSelected} does not fire an {@code ActionListener}, so the mirror
     * cannot loop back. The strength sliders have no twins — this tab is the only
     * place they live, and {@link #currentConfig} passes their values to the run.
     */
    private JComponent buildRetouchPreviewTab() {
        // Equal halves, so before and after are compared at the same scale.
        JPanel images = new JPanel(new PreviewRowLayout(PREVIEW_HALF_GAP));
        images.add(beforePanel);
        images.add(afterPanel);

        // Each photo's luminance histogram, in the same two columns as the photos.
        JPanel histograms = new JPanel(new GridLayout(1, 2, PREVIEW_HALF_GAP, 0));
        histograms.add(beforeHistogram);
        histograms.add(afterHistogram);

        linkRetouchBoxes(previewWhiteBalanceBox, whiteBalanceBox);
        linkRetouchBoxes(previewBrightnessBox, brightnessBox);
        linkRetouchBoxes(previewContrastBox, contrastBox);
        linkRetouchBoxes(previewAutoCropBox, autoCropBox);

        // One step per row, in pipeline order, so each strength slider sits with the
        // box it belongs to instead of trailing a row of unrelated boxes.
        Config cfg = Config.forBaseDir(Path.of(baseDirField.getText().strip()));
        JPanel boxes = new JPanel();
        boxes.setLayout(new BoxLayout(boxes, BoxLayout.Y_AXIS));
        boxes.add(PreviewRow.leftRow(previewWhiteBalanceBox));
        boxes.add(brightnessDial.row(cfg.brightnessStrength));
        boxes.add(contrastDial.row(cfg.contrastStrength));
        boxes.add(PreviewRow.leftRow(previewAutoCropBox));

        // The photo row, its histograms, the photo stepper, then the steps right
        // under them — a BorderLayout would put the last at the bottom of the tab, a
        // long way below photos that only take the height they need.
        JPanel panel = new JPanel(new PreviewTabLayout(6));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        panel.add(images);
        panel.add(histograms);
        panel.add(photoStepper());
        panel.add(clippingToggle());
        panel.add(boxes);
        return panel;
    }

    /** The clipping toggle, centered under the After photo. */
    private JPanel clippingToggle() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        showClippingBox.setToolTipText(I18n.t(
                "Marks pixels clipped to black or white — detail no slider can bring back."));
        showClippingBox.addActionListener(e -> {
            beforePanel.setShowClipping(showClippingBox.isSelected());
            afterPanel.setShowClipping(showClippingBox.isSelected());
        });
        row.add(showClippingBox);
        return row;
    }

    /**
     * The photo stepper under the Before panel:
     * {@code [|<] [< Prev] 3/20 [Next >] [>|]}, which photo of the offer's series the
     * preview is showing. Both panels show the same photo — before and after are only
     * worth comparing on one — so the stepper sits under Before, where the eye
     * starts.
     *
     * <p>Stepping only re-renders; nothing is written, and the photo the pipeline
     * would process is unaffected by which one is on screen.
     */
    private JPanel photoStepper() {
        JPanel row = new JPanel(new StepperLayout(6));
        firstPhotoButton.addActionListener(e -> showPreviewPhoto(0));
        previousPhotoButton.addActionListener(e -> showPreviewPhoto(previewPhotoIndex - 1));
        nextPhotoButton.addActionListener(e -> showPreviewPhoto(previewPhotoIndex + 1));
        lastPhotoButton.addActionListener(e -> showPreviewPhoto(previewPhotoCount - 1));
        firstPhotoButton.setToolTipText(I18n.t("First photo"));
        lastPhotoButton.setToolTipText(I18n.t("Last photo"));
        // StepperLayout addresses these by position: ends outermost, counter middle.
        row.add(firstPhotoButton);
        row.add(previousPhotoButton);
        row.add(photoIndexLabel);
        row.add(nextPhotoButton);
        row.add(lastPhotoButton);
        showPhotoIndex();
        return row;
    }

    /** Steps the preview to another photo of the offer, within the series. */
    private void showPreviewPhoto(int index) {
        if (index < 0 || index >= previewPhotoCount) {
            return;
        }
        previewPhotoIndex = index;
        showPhotoIndex();
        refreshRetouchPreview();
    }

    /**
     * Shows which photo of the series is on screen, and greys the buttons out at the
     * ends of it — and entirely while there is no offer, when the count is 0 and
     * there is nothing to step through.
     */
    private void showPhotoIndex() {
        photoIndexLabel.setText(previewPhotoCount == 0
                ? "–" : (previewPhotoIndex + 1) + "/" + previewPhotoCount);
        // Hold the width the longest counter of this offer would need ("20/20"), so
        // stepping from 9/20 to 10/20 does not nudge the buttons sideways.
        photoIndexLabel.setHorizontalAlignment(SwingConstants.CENTER);
        String widest = previewPhotoCount + "/" + previewPhotoCount;
        photoIndexLabel.setPreferredSize(new Dimension(
                photoIndexLabel.getFontMetrics(photoIndexLabel.getFont()).stringWidth(widest),
                photoIndexLabel.getPreferredSize().height));
        boolean more = previewPhotoIndex < previewPhotoCount - 1;
        firstPhotoButton.setEnabled(previewPhotoIndex > 0);
        previousPhotoButton.setEnabled(previewPhotoIndex > 0);
        nextPhotoButton.setEnabled(more);
        lastPhotoButton.setEnabled(more);
    }

    /**
     * Keeps a Retouch Preview checkbox and its Workflow twin ticked alike, both
     * ways. Both listeners also refresh the contrast slider's enabled state: the
     * twin is written with {@code setSelected}, which fires no event of its own,
     * so unticking Contrast in the Workflow section would otherwise leave a live
     * slider next to a dead checkbox.
     */
    private void linkRetouchBoxes(JCheckBox preview, JCheckBox workflow) {
        preview.addActionListener(e -> {
            workflow.setSelected(preview.isSelected());
            showDialValues();
            refreshRetouchPreview();
        });
        workflow.addActionListener(e -> {
            preview.setSelected(workflow.isSelected());
            showDialValues();
            refreshRetouchPreview();
        });
    }

    /** Both dials follow their checkbox, whichever of the mirrored pair was clicked. */
    private void showDialValues() {
        brightnessDial.showValue();
        contrastDial.showValue();
    }

    /**
     * Re-renders the preview for the selected offer and the ticked steps, off the
     * EDT. Rendering costs a full-size decode plus a scan of the whole series, so
     * it is skipped while the tab is hidden — {@link #previewStale} then has the
     * tab's change listener catch up when it comes into view. Results carrying a
     * superseded {@link #previewToken} are dropped, so a fast click-through of
     * offers cannot paint an older render over a newer one.
     */
    private void refreshRetouchPreview() {
        if (rightTabs.getSelectedIndex() != TAB_RETOUCH_PREVIEW) {
            previewStale = true;
            return;
        }
        previewStale = false;
        int my = previewToken.incrementAndGet();

        Path offerDir = currentOfferDir;
        if (offerDir == null) {
            previewPhotoCount = 0; // nothing to step through
            showPhotoIndex();
            showPreviewStatus(offerTable.getSelectedRow() < 0
                    ? I18n.t("Select an offer in the grid.")
                    : I18n.t("Not matched yet — run Match."));
            return;
        }

        int photoIndex = previewPhotoIndex;
        RetouchPreview.Settings steps = new RetouchPreview.Settings(
                previewWhiteBalanceBox.isSelected(),
                previewBrightnessBox.isSelected(), brightnessDial.strength(),
                previewContrastBox.isSelected(), contrastDial.strength(),
                previewAutoCropBox.isSelected());
        showPreviewStatus(I18n.t("Rendering the preview…"));
        previewLoader.submit(() -> {
            String failure = null;
            RetouchPreview.Result result = null;
            try {
                result = RetouchPreview.render(offerDir, photoIndex, steps, PREVIEW_MAX_SIZE);
            } catch (IOException e) {
                failure = I18n.t("Could not render the preview: {0}", e.getMessage());
            }
            RetouchPreview.Result rendered = result;
            // Reading every pixel belongs on this thread, not the EDT.
            Exposure beforeExposure = rendered == null ? null : Exposure.of(rendered.before());
            Exposure afterExposure = rendered == null ? null : Exposure.of(rendered.after());
            String message = failure;
            SwingUtilities.invokeLater(() -> {
                if (previewToken.get() != my) {
                    return; // a newer render superseded this one
                }
                if (message != null) {
                    showPreviewStatus(message);
                } else if (rendered == null) {
                    previewPhotoCount = 0;
                    showPhotoIndex();
                    showPreviewStatus(I18n.t("No photos."));
                } else {
                    beforePanel.setImage(rendered.before(), beforeExposure.clipped());
                    afterPanel.setImage(rendered.after(), afterExposure.clipped());
                    beforeHistogram.setExposure(beforeExposure);
                    afterHistogram.setExposure(afterExposure);
                    // The render is the only thing that has counted the photos, and
                    // it clamps the index — so take both back from it.
                    previewPhotoCount = rendered.count();
                    previewPhotoIndex = rendered.index();
                    showPhotoIndex();
                }
            });
        });
    }

    /**
     * Shows the same status line in both preview panels, in place of the images, and
     * empties the histograms — they describe a photo, and there is none to describe.
     */
    private void showPreviewStatus(String text) {
        beforePanel.setStatus(text);
        afterPanel.setStatus(text);
        beforeHistogram.setExposure(null);
        afterHistogram.setExposure(null);
    }

    /**
     * The Allegro Lokalnie Form tab: everything the listing form at
     * {@link #ALLEGRO_FORM_URL} needs, laid out for copying — the finished
     * photos (drag them onto the form's gallery dropzone), the title and the
     * generated description (copy buttons). Nothing here is persisted; the
     * editable sources live in the other tabs.
     */
    private JComponent buildAllegroFormTab() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        top.add(UiStyle.sectionLabel("Link to Allegro Lokalnie form"));
        // Underline only: the color comes from setForeground, so it can follow
        // the theme (a color baked into the HTML could not).
        formUrlLink = new JLabel("<html><u>" + ALLEGRO_FORM_URL + "</u></html>");
        formUrlLink.setForeground(UiStyle.linkColor());
        formUrlLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        formUrlLink.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openFormUrl();
            }
        });
        JLabel link = formUrlLink;
        JButton openUrlButton = new JButton("Open URL");
        openUrlButton.addActionListener(e -> openFormUrl());
        JButton copyAllButton = new JButton("Copy all to Allegro");
        copyAllButton.setToolTipText("Open the form in Chrome and fill in the selected photos,"
                + " the title and the description. You review and submit it yourself.");
        copyAllButton.addActionListener(e -> copyAllToAllegro(copyAllButton));
        JPanel linkRow = UiStyle.flowRow();
        linkRow.add(link);
        linkRow.add(openUrlButton);
        linkRow.add(copyAllButton);
        top.add(linkRow);

        top.add(UiStyle.sectionLabel("Photos"));
        JLabel photosHint = new JLabel("Select the photos to use (Allegro allows "
                + ALLEGRO_MAX_PHOTOS + "; the first " + ALLEGRO_MAX_PHOTOS
                + " are preselected), then drag the selection onto the form.");
        photosHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(photosHint);
        JScrollPane galleryScroll = formGallery.component();
        // Three 8-thumbnail rows (a 20-photo series); more photos scroll inside.
        Dimension gallerySize = new Dimension(10, 3 * (FORM_THUMB_SIZE + 16) + 12);
        galleryScroll.setPreferredSize(gallerySize);
        galleryScroll.setMinimumSize(gallerySize);
        galleryScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, gallerySize.height));
        galleryScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(galleryScroll);

        top.add(UiStyle.sectionLabel("Title"));
        JPanel titleRow = new JPanel(new BorderLayout(6, 0));
        titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        formTitleField.setCaretColor(UiStyle.caretColor());
        titleRow.add(formTitleField, BorderLayout.CENTER);
        JButton copyTitleButton = new JButton("Copy Title");
        copyTitleButton.addActionListener(e ->
                copyToClipboard(formTitleField.getText(), "title"));
        titleRow.add(copyTitleButton, BorderLayout.EAST);
        titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                titleRow.getPreferredSize().height));
        top.add(titleRow);

        panel.add(top, BorderLayout.NORTH);

        JPanel descSection = new JPanel(new BorderLayout(6, 2));
        JPanel descHeader = new JPanel(new BorderLayout());
        descHeader.add(UiStyle.sectionLabel("Description"), BorderLayout.WEST);
        JButton copyDescriptionButton = new JButton("Copy Description");
        copyDescriptionButton.addActionListener(e ->
                copyToClipboard(formDescriptionArea.getText(), "description"));
        descHeader.add(copyDescriptionButton, BorderLayout.EAST);
        descSection.add(descHeader, BorderLayout.NORTH);
        descSection.add(new JScrollPane(formDescriptionArea), BorderLayout.CENTER);
        panel.add(descSection, BorderLayout.CENTER);

        return panel;
    }

    /**
     * Opens the Allegro form in the user's <em>default</em> browser (not the
     * app-driven Chrome — that is {@link #copyAllToAllegro}).
     */
    private void openFormUrl() {
        Desktops.browse(ALLEGRO_FORM_URL,
                () -> appendLog("Opened " + ALLEGRO_FORM_URL),
                message -> error(I18n.t("Could not open {0}: {1}", ALLEGRO_FORM_URL, message)));
    }

    /**
     * Opens the Allegro form in the app-driven Chrome and fills the selected
     * photos, title and description into it ({@link AllegroForm}). Runs off
     * the EDT; the button stays disabled until the fill finishes, since the
     * wait for the form (and a possible manual login) can take minutes.
     */
    private void copyAllToAllegro(JButton button) {
        List<Path> photos = formGallery.selectedFiles();
        String title = formTitleField.getText().strip();
        String description = formDescriptionArea.getText();
        if (photos.isEmpty() && title.isBlank() && description.isBlank()) {
            error(I18n.t("Nothing to copy: select an offer with photos, a title or a description first."));
            return;
        }
        Config cfg = currentConfig();
        button.setEnabled(false);
        appendLog("== copy to Allegro ==");
        new Thread(() -> {
            try {
                AllegroForm.fill(cfg, ALLEGRO_FORM_URL, photos, title, description,
                        new Reporter() {
                            @Override
                            public void log(String line) {
                                SwingUtilities.invokeLater(() -> appendLog(line));
                            }

                            @Override
                            public void stepProgress(double fraction) {
                                // No step progress for a single form fill.
                            }
                        });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() ->
                        error(I18n.t("Copy to Allegro failed: {0}", e.getMessage())));
            } finally {
                SwingUtilities.invokeLater(() -> button.setEnabled(true));
            }
        }, "allegro-form-fill").start();
    }

    /**
     * Puts text on the system clipboard and notes it in the log.
     *
     * @param what what was copied, for the log line
     */
    private void copyToClipboard(String text, String what) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
        appendLog("Copied the " + what + " to the clipboard.");
    }

    /**
     * Swaps the bottom bar for the active tab: Delete/Clear/Save on the Description
     * tabs, "Open photo dir" on the Photos tabs (disabled when there's no directory).
     */
    private void updateBottomBar() {
        if (bottomBars == null) {
            return;
        }
        ((CardLayout) bottomBars.getLayout())
                .show(bottomBars, isEditorTab() ? CARD_EDITOR : CARD_PHOTOS);
        if (openPhotoDirButton != null) {
            openPhotoDirButton.setEnabled(activePhotoDir() != null);
        }
    }

    /** The directory shown by the active Photos tab, or null if it doesn't exist yet. */
    private Path activePhotoDir() {
        if (currentOfferDir == null) {
            return null;
        }
        int tab = rightTabs.getSelectedIndex();
        Path dir = tab == TAB_PHOTOS_OUTPUT || tab == TAB_ALLEGRO_FORM
                ? OfferFiles.outputPhotoDir(currentOfferDir)
                : currentOfferDir.resolve("photos");
        return Files.isDirectory(dir) ? dir : null;
    }

    /** Whether the active tab is a text editor (rather than a gallery or the form). */
    private boolean isEditorTab() {
        int i = rightTabs.getSelectedIndex();
        return i == TAB_DESCRIPTION_INPUT || i == TAB_DESCRIPTION_OUTPUT || i == TAB_OCR;
    }

    /** The file backing the active editor tab, or null when it has none yet. */
    private Path activeEditorTarget() {
        return editorTarget(rightTabs.getSelectedIndex());
    }

    /** The file backing an editor tab, or null when it has none (yet). */
    private Path editorTarget(int tab) {
        return switch (tab) {
            case TAB_DESCRIPTION_INPUT -> moreDataTarget;
            case TAB_DESCRIPTION_OUTPUT -> descriptionTarget;
            case TAB_OCR -> ocrTarget;
            default -> null;
        };
    }

    /** The text pane of the active editor tab, or null on non-editor tabs. */
    private JTextPane activeEditorPane() {
        return editorPane(rightTabs.getSelectedIndex());
    }

    /** The text pane of an editor tab, or null on non-editor tabs. */
    private JTextPane editorPane(int tab) {
        return switch (tab) {
            case TAB_DESCRIPTION_INPUT -> moreDataArea;
            case TAB_DESCRIPTION_OUTPUT -> detailsArea;
            case TAB_OCR -> ocrArea;
            default -> null;
        };
    }

    /** Repaints emoji as color images whenever the pane's text changes. */
    private void installEmojiRendering(JTextPane pane) {
        pane.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                schedule();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                schedule();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // Attribute-only change (that's us) - nothing to do.
            }

            private void schedule() {
                // A document must not be restyled from inside its own notification.
                SwingUtilities.invokeLater(() -> restyleEmoji(pane));
            }
        });
    }

    /**
     * Replaces each emoji codepoint's glyph with a color image, by marking that
     * text run as an icon element. The document text itself is untouched, so
     * {@code getText()} (and therefore Save) still yields the original emoji.
     * Java2D cannot rasterize color fonts, hence {@link ColorEmoji}.
     */
    private void restyleEmoji(JTextPane pane) {
        StyledDocument doc = pane.getStyledDocument();
        int length = doc.getLength();
        if (length == 0) {
            return;
        }
        String text;
        try {
            text = doc.getText(0, length);
        } catch (BadLocationException e) {
            return;
        }
        // Reset first, so text typed next to an emoji doesn't inherit its icon.
        doc.setCharacterAttributes(0, length, SimpleAttributeSet.EMPTY, true);

        int iconHeight = Math.round(pane.getFont().getSize() * 1.25f);
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            int run = Character.charCount(codePoint);
            if (i + run < text.length() && text.charAt(i + run) == '\uFE0F') {
                run++; // fold the variation selector (U+FE0F) into the same run
            }
            ImageIcon icon = ColorEmoji.icon(codePoint, iconHeight);
            if (icon != null) {
                SimpleAttributeSet attrs = new SimpleAttributeSet();
                attrs.addAttribute(AbstractDocument.ElementNameAttribute, StyleConstants.IconElementName);
                StyleConstants.setIcon(attrs, icon);
                doc.setCharacterAttributes(i, run, attrs, false);
            }
            i += run;
        }
    }

    /**
     * Marks an editor tab as holding unsaved edits (or not), refreshing the tab
     * labels when that actually changed.
     */
    private void setEditorDirty(int tab, boolean dirty) {
        if (editorDirty[tab] != dirty) {
            editorDirty[tab] = dirty;
            updateTabStyles();
        }
    }

    /** Clears the unsaved-edits marker on all three editor tabs. */
    private void clearAllEditorDirty() {
        Arrays.fill(editorDirty, false);
        updateTabStyles();
    }

    /**
     * Flags {@code tab} as unsaved as soon as the user changes {@code pane}'s
     * text. Attribute-only changes are ignored: those are the emoji restyling
     * (see {@link #installEmojiRendering}), not an edit.
     */
    private void installDirtyTracking(JTextPane pane, int tab) {
        pane.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                mark();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                mark();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // Attribute-only change (the emoji restyling) - not an edit.
            }

            private void mark() {
                if (!loadingEditors) {
                    setEditorDirty(tab, true);
                }
            }
        });
    }

    /**
     * Highlights the selected tab (bold, bright, accent underline) and dims the
     * rest. Also re-renders the tab labels, which is where the unsaved-edits
     * marker is applied: the label text is built from the tabbed pane's own
     * title, so it survives a language change (which rewrites those titles but
     * cannot match a label carrying the marker).
     */
    private void updateTabStyles() {
        int selected = rightTabs.getSelectedIndex();
        for (int i = 0; i < rightTabs.getTabCount(); i++) {
            if (!(rightTabs.getTabComponentAt(i) instanceof JLabel label)) {
                continue;
            }
            boolean active = i == selected;
            boolean dirty = editorDirty[i];
            label.setText((dirty ? "* " : "") + rightTabs.getTitleAt(i));
            label.setFont(label.getFont().deriveFont(active ? Font.BOLD : Font.PLAIN));
            // Amber wins over both the selected and the dimmed color: an unsaved
            // tab must stand out even while the user is looking at another one.
            label.setForeground(dirty ? UiStyle.tabDirtyFg() : active ? UiStyle.tabSelectedFg() : UiStyle.tabUnselectedFg());
            // Accent underline on the active tab; matching padding keeps heights equal.
            label.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, active ? 2 : 0, 0, UiStyle.TAB_ACCENT),
                    BorderFactory.createEmptyBorder(3, 8, active ? 3 : 5, 8)));
        }
    }

    /**
     * Loads the selected offer's files into the editor tabs:
     * Description (Input) from {@code more_data_<N>.txt} next to offers.csv, and
     * Description (Output) / OCR from {@code description.txt} / {@code ocr.txt}
     * in the offer directory.
     */
    private void loadSelectedOffer() {
        loadingEditors = true;
        try {
            loadSelectedOfferInto();
        } finally {
            loadingEditors = false;
        }
        // The editors now mirror what is on disk.
        clearAllEditorDirty();
    }

    /** @see #loadSelectedOffer() — the body, run with the dirty tracking muted. */
    private void loadSelectedOfferInto() {
        int viewRow = offerTable.getSelectedRow();
        if (viewRow < 0) {
            detailsHeader.setText(I18n.t("Select an offer in the grid."));
            moreDataArea.setText("");
            detailsArea.setText("");
            ocrArea.setText("");
            moreDataTarget = null;
            descriptionTarget = null;
            ocrTarget = null;
            currentOfferDir = null;
            photosInputGallery.message(I18n.t("Select an offer in the grid."));
            photosOutputGallery.message(I18n.t("Select an offer in the grid."));
            formGallery.message(I18n.t("Select an offer in the grid."));
            formTitleField.setText("");
            formDescriptionArea.setText("");
            refreshRetouchPreview();
            updateBottomBar();
            return;
        }
        int modelRow = offerTable.convertRowIndexToModel(viewRow);
        int rowNumber = modelRow + 1; // 1-based, as in more_data_<N>.txt
        String name = String.valueOf(offerModel.getValueAt(modelRow, 0));
        Config cfg = currentConfig();

        // Description (Input): more_data_<N>.txt next to offers.csv.
        Path csvParent = cfg.csvPath.getParent();
        moreDataTarget = (csvParent == null ? Path.of(".") : csvParent)
                .resolve("more_data_" + rowNumber + ".txt");
        moreDataArea.setText(OfferFiles.readIfExists(moreDataTarget));
        moreDataArea.setCaretPosition(0);

        // Description (Output) + galleries live in the resolved offer directory.
        Path offerDir = OfferFiles.resolveOfferDir(cfg, name, modelRow);
        if (!Objects.equals(offerDir, currentOfferDir)) {
            previewPhotoIndex = 0; // another offer, another series: back to its first photo
        }
        currentOfferDir = offerDir;
        if (offerDir == null) {
            descriptionTarget = null;
            ocrTarget = null;
            detailsArea.setText("");
            ocrArea.setText("");
            detailsHeader.setText("<html><b>" + OfferFiles.escapeHtml(name) + "</b><br>" + I18n.t(
                    "row {0} — <i>not matched yet (photos and Description output appear after Match)</i>",
                    rowNumber) + "</html>");
            photosInputGallery.message(I18n.t("Not matched yet — run Match."));
            photosOutputGallery.message(I18n.t("Not retouched yet — run a retouching step."));
            formGallery.message(I18n.t("Not matched yet — run Match."));
            formDescriptionArea.setText("");
        } else {
            descriptionTarget = offerDir.resolve("description.txt");
            detailsArea.setText(OfferFiles.readIfExists(descriptionTarget));
            ocrTarget = offerDir.resolve("ocr.txt");
            ocrArea.setText(OfferFiles.readIfExists(ocrTarget));
            detailsHeader.setText("<html><b>" + OfferFiles.escapeHtml(name) + "</b><br>" + I18n.t("row {0} — {1}",
                    rowNumber, OfferFiles.escapeHtml(offerDir.getFileName().toString())) + "</html>");
            photosInputGallery.show(offerDir.resolve("photos"));
            photosOutputGallery.show(OfferFiles.outputPhotoDir(offerDir));
            formGallery.show(OfferFiles.outputPhotoDir(offerDir));
            formDescriptionArea.setText(OfferFiles.readIfExists(descriptionTarget));
        }
        formTitleField.setText(name);
        formTitleField.setCaretPosition(0);
        detailsArea.setCaretPosition(0);
        ocrArea.setCaretPosition(0);
        formDescriptionArea.setCaretPosition(0);
        refreshRetouchPreview();
        updateBottomBar();
    }

    /** Saves the currently active editor tab to its backing file. */
    private void saveActiveTab() {
        if (!isEditorTab()) {
            return;
        }
        if (offerTable.getSelectedRow() < 0 || moreDataTarget == null) {
            error(I18n.t("Select an offer in the grid first."));
            return;
        }
        Path target = activeEditorTarget();
        if (target == null) {
            // Only reachable for the offer-directory tabs before the offer is matched.
            error(I18n.t("No offer directory yet — run Match first."));
            return;
        }
        if (!saveTab(rightTabs.getSelectedIndex())) {
            error(I18n.t("Failed to save {0}: {1}", target, lastSaveError));
        }
    }

    /**
     * Writes one editor tab to its file and clears its unsaved-edits marker,
     * reporting success rather than showing a dialog — callers saving several
     * tabs at once want to collect the failures, not stack up message boxes.
     * The message behind a false is in {@link #lastSaveError}.
     */
    private boolean saveTab(int tab) {
        Path target = editorTarget(tab);
        JTextPane pane = editorPane(tab);
        if (target == null || pane == null) {
            lastSaveError = I18n.t("No offer directory yet — run Match first.");
            return false;
        }
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, pane.getText(), StandardCharsets.UTF_8);
            setEditorDirty(tab, false);
            appendLog("Saved " + target);
            return true;
        } catch (IOException e) {
            lastSaveError = e.getMessage();
            return false;
        }
    }

    /** Deletes the file backing the active tab, after confirmation, and clears its editor. */
    private void deleteActiveFile() {
        if (!isEditorTab()) {
            return;
        }
        if (offerTable.getSelectedRow() < 0 || moreDataTarget == null) {
            error(I18n.t("Select an offer in the grid first."));
            return;
        }
        Path target = activeEditorTarget();
        if (target == null) {
            error(I18n.t("No offer directory yet — run Match first."));
            return;
        }
        if (!Files.exists(target)) {
            JOptionPane.showMessageDialog(frame, I18n.t("There is no file to delete yet:\n{0}", target),
                    "Allegro Helper", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int choice = JOptionPane.showConfirmDialog(frame,
                I18n.t("Delete this file? This cannot be undone.\n\n{0}", target),
                I18n.t("Delete file"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            Files.delete(target);
            activeEditorPane().setText("");
            // The file is gone and the editor is empty: nothing left unsaved.
            setEditorDirty(rightTabs.getSelectedIndex(), false);
            appendLog("Deleted " + target);
        } catch (IOException e) {
            error(I18n.t("Failed to delete {0}: {1}", target, e.getMessage()));
        }
    }

    /** Opens the directory backing the active Photos tab in the system file manager. */
    private void openActivePhotoDir() {
        Path dir = activePhotoDir();
        if (dir == null) {
            error(currentOfferDir == null
                    ? I18n.t("No offer directory yet — run Match first.")
                    : I18n.t("That photo directory does not exist yet."));
            return;
        }
        openInSystem(dir);
    }

    /** Opens a file or directory with the system handler, logging the outcome. */
    private void openInSystem(Path target) {
        Desktops.open(target,
                opened -> appendLog("Opened " + opened),
                message -> error(I18n.t("Could not open {0}: {1}", target, message)));
    }

    /** Clears the active editor only; the file is unchanged until Save is clicked. */
    private void clearActiveEditor() {
        if (!isEditorTab()) {
            return;
        }
        activeEditorPane().setText("");
    }

    // ----------------------------------------------------------------- actions

    /** Picks a new base directory and reloads its offers — every path derives from it. */
    private void chooseBaseDir() {
        JFileChooser chooser = new JFileChooser(baseDirField.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            baseDirField.setText(chooser.getSelectedFile().getAbsolutePath());
            loadOffersFromBaseDir();
        }
    }

    /** Picks a photo source directory (replacing the MTP glob) and re-scans it. */
    private void choosePhotoDir() {
        // The field usually holds the MTP glob, which no chooser can open;
        // start from the deepest existing prefix of it instead.
        Path start = OfferFiles.deepestExistingDir(photoDirField.getText().strip());
        JFileChooser chooser = new JFileChooser(start == null ? null : start.toString());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            photoDirField.setText(chooser.getSelectedFile().getAbsolutePath());
            refreshPhotos();
        }
    }

    /** Restores the configured photo source (the MTP glob unless .env/env override it). */
    private void resetPhotoDir() {
        // Deliberately not currentConfig(): that would re-apply the field's own
        // value as the override, making this a no-op.
        Config cfg = Config.forBaseDir(Path.of(baseDirField.getText().strip()));
        photoDirField.setText(cfg.mtpGlobPattern);
        refreshPhotos();
    }

    /** Loads the base directory's offers.csv into the grid; a missing file just leaves it empty. */
    private void loadOffersFromBaseDir() {
        Config cfg = currentConfig();
        try {
            offerModel.loadFromCsvIfPresent(cfg.csvPath);
        } catch (IOException e) {
            appendLog("Could not read " + cfg.csvPath + ": " + e.getMessage());
        }
        selectFirstOfferRow();
    }

    /**
     * Selects the first grid row, so the right panel shows that offer's files
     * instead of "Select an offer in the grid." on launch. Without it every tab
     * starts blank and nothing hints that a click is what fills them.
     *
     * <p>Falls through to {@link #loadSelectedOffer()} when the grid is empty or
     * a row is already selected: the selection listener only fires on a *change*,
     * so relying on it alone would leave the panel stale in both cases.
     */
    private void selectFirstOfferRow() {
        if (offerTable.getRowCount() > 0 && offerTable.getSelectedRow() < 0) {
            offerTable.setRowSelectionInterval(0, 0); // fires the listener -> loadSelectedOffer()
        } else {
            loadSelectedOffer();
        }
    }

    /** Loads offers from a CSV picked by the user (any delimiter; columns match by header name). */
    private void loadCsvViaChooser() {
        JFileChooser chooser = new JFileChooser(baseDirField.getText());
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            try {
                offerModel.loadFromCsv(chooser.getSelectedFile().toPath());
                appendLog("Loaded offers from " + chooser.getSelectedFile());
                selectFirstOfferRow();
            } catch (IOException e) {
                error(I18n.t("Failed to load CSV: {0}", e.getMessage()));
            }
        }
    }

    /**
     * Writes the grid to the base directory's offers.csv. Commits the cell being
     * edited first, so the value in an open editor is not lost.
     */
    private void saveCsvToBaseDir() {
        stopCellEditing();
        Config cfg = currentConfig();
        try {
            int n = offerModel.saveToCsv(cfg.csvPath);
            appendLog("Saved " + n + " offers to " + cfg.csvPath);
        } catch (IOException e) {
            error(I18n.t("Failed to save CSV: {0}", e.getMessage()));
        }
    }

    /** Opens offers.csv in the system's default .csv application. */
    private void openCsvInEditor() {
        Path csv = currentConfig().csvPath;
        if (!Files.exists(csv)) {
            error(I18n.t("CSV file does not exist yet: {0} — Save CSV first.", csv));
            return;
        }
        openInSystem(csv);
    }

    /** Re-reads the offers CSV from disk, discarding unsaved edits in the grid. */
    private void reloadCsvFromDisk() {
        stopCellEditing();
        Path csv = currentConfig().csvPath;
        if (!Files.exists(csv)) {
            error(I18n.t("CSV file does not exist yet: {0} — Save CSV first.", csv));
            return;
        }
        try {
            offerModel.loadFromCsv(csv);
            appendLog("Reloaded offers from " + csv);
            selectFirstOfferRow();
        } catch (IOException e) {
            error(I18n.t("Failed to reload CSV: {0}", e.getMessage()));
        }
    }

    /** Removes the selected grid row; does nothing when no row is selected. */
    private void removeSelectedRow() {
        int viewRow = offerTable.getSelectedRow();
        if (viewRow >= 0) {
            offerModel.removeRow(offerTable.convertRowIndexToModel(viewRow));
        }
    }

    /**
     * Re-scans the photo source and lists the series waiting to be imported.
     * Off the EDT: the scan reaches over MTP to the phone, which can stall.
     */
    private void refreshPhotos() {
        Config cfg = currentConfig();
        refreshPhotosButton.setEnabled(false);
        photosModel.clear();
        photosModel.addElement(I18n.t("Scanning phone…"));
        new Thread(() -> {
            String message;
            List<String> entries = new ArrayList<>();
            try {
                PhoneScan.Result result = PhoneScan.scan(cfg);
                if (result.series().isEmpty()) {
                    message = I18n.t("No photo series found in {0}", result.sourceDir());
                } else {
                    message = null;
                    for (PhotoSeries s : result.series()) {
                        entries.add(I18n.t("{0} | {1}x series of photos to import", s.label(), s.count()));
                    }
                }
            } catch (IOException e) {
                message = e.getMessage();
            }
            String finalMessage = message;
            SwingUtilities.invokeLater(() -> {
                photosModel.clear();
                if (finalMessage != null) {
                    for (String line : finalMessage.split("\n")) {
                        photosModel.addElement(line);
                    }
                } else {
                    entries.forEach(photosModel::addElement);
                }
                refreshPhotosButton.setEnabled(true);
            });
        }, "phone-scan").start();
    }

    /**
     * Deletes every entry under the offers directory — all generated files —
     * after confirmation, keeping the sources (photos on the phone, offers.csv,
     * more_data_&lt;N&gt;.txt, raw_photos/). With {@code restart} it then presses
     * Start. Note the offer photos live under offers/ too (Match moves them
     * there), so a full re-run needs the phone connected to re-import them.
     */
    private void deleteOutputs(boolean restart) {
        if (running) {
            return;
        }
        Config cfg = currentConfig();
        Path offersDir = cfg.offersDir.toAbsolutePath().normalize();

        // A misconfigured OFFERS_DIR could put the sources inside the deletion
        // root (e.g. OFFERS_DIR=.). Refuse rather than guess.
        Path csv = cfg.csvPath.toAbsolutePath().normalize();
        Path raw = cfg.rawPhotosDir.toAbsolutePath().normalize();
        if (csv.startsWith(offersDir) || raw.startsWith(offersDir)) {
            error(I18n.t("Refusing to delete {0}: it contains offers.csv or raw_photos (check OFFERS_DIR).",
                    offersDir));
            return;
        }

        String title = I18n.t(restart ? "Clean & Restart" : "Delete Output Files");
        int choice = JOptionPane.showConfirmDialog(frame,
                I18n.t("This deletes all generated offer directories under:\n{0}"
                        + "\n\nSources are kept: photos on the phone, offers.csv, more_data_<N>.txt."
                        + "\nRe-importing the photos requires the phone to be connected.", offersDir)
                        + I18n.t(restart ? "\n\nDelete and start the selected workflow steps?" : "\n\nDelete?"),
                title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }

        if (Files.isDirectory(offersDir)) {
            int deleted = 0;
            try {
                try (var stream = Files.list(offersDir)) {
                    for (Path entry : stream.toList()) {
                        OfferFiles.deleteRecursively(entry);
                        deleted++;
                    }
                }
            } catch (IOException e) {
                error(I18n.t("Failed to delete output files: {0}", e.getMessage()));
                return; // don't restart on a half-finished clean
            }
            appendLog("Deleted " + deleted + " entries under " + offersDir);
        } else {
            appendLog("Nothing to delete: " + offersDir + " does not exist.");
        }
        loadSelectedOffer(); // the selected offer's files are gone; refresh the right panel

        if (restart) {
            startWorkflow();
        }
    }

    /**
     * Offers to save, discard, or cancel when a run is about to reload editor
     * tabs holding unsaved edits. Returns whether the run may proceed: false on
     * Cancel, and also when saving was asked for but failed — silently running
     * on would destroy exactly the text the user just chose to keep.
     *
     * <p>Nothing dirty means no dialog, so the common case is unchanged.
     */
    private boolean confirmUnsavedEditors() {
        List<Integer> dirty = new ArrayList<>();
        for (int tab : new int[]{TAB_DESCRIPTION_INPUT, TAB_DESCRIPTION_OUTPUT, TAB_OCR}) {
            if (editorDirty[tab]) {
                dirty.add(tab);
            }
        }
        if (dirty.isEmpty()) {
            return true;
        }
        StringBuilder names = new StringBuilder();
        for (int tab : dirty) {
            names.append("\n    • ").append(rightTabs.getTitleAt(tab));
        }
        Object[] options = {
            I18n.t("Save changes"), I18n.t("Discard changes"), I18n.t("Cancel")};
        int choice = JOptionPane.showOptionDialog(frame,
                I18n.t("These tabs have unsaved changes:\n{0}\n\n"
                        + "A run reloads them from their files, so unsaved text will be lost.",
                        names.toString()),
                I18n.t("Unsaved changes"), JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (choice == 0) {
            List<String> failures = new ArrayList<>();
            for (int tab : dirty) {
                if (!saveTab(tab)) {
                    failures.add(rightTabs.getTitleAt(tab) + ": " + lastSaveError);
                }
            }
            if (!failures.isEmpty()) {
                error(I18n.t("Could not save before starting:\n{0}", String.join("\n", failures)));
                return false;
            }
            return true;
        }
        if (choice == 1) {
            appendLog("Discarded unsaved editor changes.");
            return true;
        }
        return false; // Cancel, Esc, or the dialog's close button
    }

    /** Runs the ticked steps. @see #runWorkflow(List) */
    private void startWorkflow() {
        if (running) {
            return;
        }
        List<Workflow.Step> steps = selectedSteps();
        if (steps.isEmpty()) {
            error(I18n.t("Select at least one workflow step."));
            return;
        }
        runWorkflow(steps);
    }

    /**
     * Runs the given steps on a background thread, streaming the log and
     * progress back to the EDT. Saves the grid to offers.csv first when the
     * match step is among them — that step reads the file, not the grid.
     *
     * <p>The steps are a parameter rather than always {@link #selectedSteps()}
     * because Import &amp; Match runs a fixed pair regardless of the checkboxes.
     */
    private void runWorkflow(List<Workflow.Step> steps) {
        if (running) {
            return;
        }
        stopCellEditing();

        // A run reloads the editors from disk when it finishes, so unsaved text
        // would be lost without a word. Ask before that happens, not after.
        if (!confirmUnsavedEditors()) {
            return;
        }

        Config cfg = currentConfig();

        // The match step consumes offers.csv, so persist the grid first.
        if (steps.contains(Workflow.Step.MATCH)) {
            try {
                int n = offerModel.saveToCsv(cfg.csvPath);
                appendLog("Saved " + n + " offers to " + cfg.csvPath);
            } catch (IOException e) {
                error(I18n.t("Failed to save offers.csv before matching: {0}", e.getMessage()));
                return;
            }
        }

        setRunning(true);
        progressBar.setValue(0);
        logArea.setText("");

        new Thread(() -> Workflow.run(cfg, steps, new Workflow.Listener() {
            @Override
            public void log(String line) {
                SwingUtilities.invokeLater(() -> appendLog(line));
            }

            @Override
            public void overallProgress(double fraction) {
                int pct = (int) Math.round(Math.max(0, Math.min(1, fraction)) * 100);
                SwingUtilities.invokeLater(() -> progressBar.setValue(pct));
            }

            @Override
            public void finished(boolean success) {
                SwingUtilities.invokeLater(() -> {
                    appendLog(success ? "== done ==" : "== stopped ==");
                    setRunning(false);
                    // A run may have created/updated the selected offer's files.
                    loadSelectedOffer();
                });
            }
        }), "workflow").start();
    }

    // ------------------------------------------------------------------ helpers

    /** The ticked steps, in pipeline order. */
    private List<Workflow.Step> selectedSteps() {
        List<Workflow.Step> steps = new ArrayList<>();
        if (importBox.isSelected()) {
            steps.add(Workflow.Step.IMPORT);
        }
        if (matchBox.isSelected()) {
            steps.add(Workflow.Step.MATCH);
        }
        if (whiteBalanceBox.isSelected()) {
            steps.add(Workflow.Step.WHITE_BALANCE);
        }
        if (brightnessBox.isSelected()) {
            steps.add(Workflow.Step.BRIGHTNESS);
        }
        if (contrastBox.isSelected()) {
            steps.add(Workflow.Step.CONTRAST);
        }
        if (autoCropBox.isSelected()) {
            steps.add(Workflow.Step.AUTOCROP);
        }
        if (ocrBox.isSelected()) {
            steps.add(Workflow.Step.OCR);
        }
        if (describeBox.isSelected()) {
            steps.add(Workflow.Step.DESCRIBE);
        }
        return steps;
    }

    /**
     * The config for the current base directory, with the photo directory, the
     * recognition mode and the two retouch strengths the user set in the UI
     * overriding {@code .env} and the environment — they are UI controls, so what is
     * on screen must win. The strengths in particular are the ones the Retouch
     * Preview rendered, so a run reproduces the preview rather than some other
     * value.
     */
    private Config currentConfig() {
        Map<String, String> overrides = new HashMap<>();
        String photoDir = photoDirField.getText().strip();
        if (!photoDir.isEmpty()) {
            overrides.put("MTP_GLOB_PATTERN", photoDir);
        }
        overrides.put("SERIES_RECOGNITION",
                SeriesRecognition.Mode.values()[seriesModeCombo.getSelectedIndex()].key);
        overrides.put("BRIGHTNESS_STRENGTH", String.valueOf(brightnessDial.strength()));
        overrides.put("CONTRAST_STRENGTH", String.valueOf(contrastDial.strength()));
        return Config.forBaseDir(Path.of(baseDirField.getText().strip()), overrides);
    }

    /** Marks a run as (not) in progress, disabling Start and the destructive buttons meanwhile. */
    private void setRunning(boolean value) {
        running = value;
        importMatchButton.setEnabled(!value);
        startButton.setEnabled(!value);
        startButton.setText(I18n.t(value ? "Running…" : "Start Workflow"));
        deleteOutputsButton.setEnabled(!value);
        cleanRestartButton.setEnabled(!value);
    }

    /** Commits the cell being edited, so its value is not lost when the grid is read. */
    private void stopCellEditing() {
        if (offerTable.isEditing()) {
            offerTable.getCellEditor().stopCellEditing();
        }
    }

    /** The grid column showing a CSV key, or -1 when the schema has no such column. */
    private int indexOfKey(String key) {
        for (int i = 0; i < OfferTableModel.KEYS.length; i++) {
            if (OfferTableModel.KEYS[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Appends a line to the log from outside the package — for startup notices
     * that happen before the window exists, such as the one-time copy of the
     * settings file. Must be called on the EDT.
     */
    public void log(String line) {
        appendLog(line);
    }

    /** Appends a line to the log and scrolls to it. Must be called on the EDT. */
    private void appendLog(String line) {
        logArea.append(line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    /** Shows an error dialog. The message is expected to be already translated. */
    private void error(String message) {
        JOptionPane.showMessageDialog(frame, message, "Allegro Helper", JOptionPane.ERROR_MESSAGE);
    }
}
