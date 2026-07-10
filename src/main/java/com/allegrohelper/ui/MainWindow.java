package com.allegrohelper.ui;

import com.allegrohelper.core.AllegroForm;
import com.allegrohelper.core.Config;
import com.allegrohelper.core.PhoneScan;
import com.allegrohelper.core.Reporter;
import com.allegrohelper.core.PhotoSeries;
import com.allegrohelper.core.SeriesRecognition;
import com.allegrohelper.core.Workflow;
import com.allegrohelper.util.Json;

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
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
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
import javax.swing.JWindow;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Image;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DragSource;
import java.awt.dnd.DragSourceAdapter;
import java.awt.dnd.DragSourceDragEvent;
import java.awt.dnd.DragSourceDropEvent;
import java.awt.dnd.DragSourceEvent;
import java.awt.dnd.DragSourceMotionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.URL;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Allegro Helper main window: photo series detected on the phone, the
 * editable offer grid, the workflow selector, a progress bar and a log.
 */
public final class MainWindow {

    /** Text caret color — white so it's visible against the dark theme. */
    private static final Color CARET_COLOR = Color.WHITE;

    /** Tab title colors: the selected tab is bright with an accent underline, others are dimmed. */
    private static final Color TAB_SELECTED_FG = Color.WHITE;
    private static final Color TAB_UNSELECTED_FG = new Color(0x9E, 0x9E, 0x9E);
    private static final Color TAB_ACCENT = new Color(0xF2, 0x6B, 0x1F); // orange, from the logo

    /** One font size for the whole window, so all text reads at a similar (larger) size. */
    private static final int UI_FONT_SIZE = 16;

    /** Height the logo is scaled to (aspect ratio preserved). */
    private static final int LOGO_HEIGHT = 150;

    /** Right-panel tab indices. */
    private static final int TAB_DESCRIPTION_INPUT = 0;   // more_data_<N>.txt editor
    private static final int TAB_DESCRIPTION_OUTPUT = 1;  // description.txt editor
    private static final int TAB_PHOTOS_INPUT = 2;   // original photos gallery
    private static final int TAB_PHOTOS_OUTPUT = 3;  // retouched photos gallery
    private static final int TAB_OCR = 4;            // ocr.txt editor
    private static final int TAB_ALLEGRO_FORM = 5;   // copy helper for the Allegro Lokalnie form

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

    private static final String ALLEGRO_FORM_URL = "https://allegrolokalnie.pl/o/oferty/wystaw";

    private final JFrame frame = new JFrame("Allegro Helper");
    private final JTextField baseDirField = new JTextField();
    private final JTextField photoDirField = new JTextField();
    /** Item order mirrors {@link SeriesRecognition.Mode#values()}. */
    private final JComboBox<String> seriesModeCombo = new JComboBox<>(new String[]{
            "Auto detect photo series",
            "All photos in dir as one item",
            "Each subfolder as separate item"});
    private final DefaultListModel<String> photosModel = new DefaultListModel<>();
    private final OfferTableModel offerModel = new OfferTableModel();
    private final JTable offerTable = new JTable(offerModel);

    private final JCheckBox importBox = new JCheckBox("Import", true);
    private final JCheckBox matchBox = new JCheckBox("Match", true);
    private final JCheckBox whiteBalanceBox = new JCheckBox("White balance", true);
    private final JCheckBox autoContrastBox = new JCheckBox("Auto-contrast", true);
    private final JCheckBox autoCropBox = new JCheckBox("Auto-crop", true);
    private final JCheckBox ocrBox = new JCheckBox("OCR", true);
    private final JCheckBox describeBox = new JCheckBox("Describe", true);

    private final JButton startButton = new JButton("Start");
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
    private final Gallery photosInputGallery = new Gallery(THUMB_SIZE, 0);
    private final Gallery photosOutputGallery = new Gallery(THUMB_SIZE, 0);
    private final Gallery formGallery = new Gallery(FORM_THUMB_SIZE, ALLEGRO_MAX_PHOTOS);

    // Allegro Lokalnie Form tab: copy sources for the listing form.
    private final JTextField formTitleField = new JTextField();
    private final JTextPane formDescriptionArea = new JTextPane();

    private JButton deleteButton;
    private JButton clearButton;
    private JButton saveButton;
    private JButton openPhotoDirButton;
    /** Bottom bar swapped per tab: editor buttons vs. the photo-gallery button. */
    private JPanel bottomBars;
    private static final String CARD_EDITOR = "editor";
    private static final String CARD_PHOTOS = "photos";

    /** Offer directory of the selected row, or null when none / not matched yet. */
    private Path currentOfferDir;

    // Save targets for the currently selected offer row (null when nothing is selected /
    // no offer directory exists yet).
    private Path moreDataTarget;
    private Path descriptionTarget;
    private Path ocrTarget;

    private volatile boolean running = false;

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

    /** Selects the given offer row (0-based), updating the details panel. */
    public void selectOfferRow(int index) {
        if (index >= 0 && index < offerModel.getRowCount()) {
            offerTable.setRowSelectionInterval(index, index);
        }
    }

    private void build() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
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

        // Enlarge and unify fonts across the whole window before measuring, so the
        // fixed-height sections are sized for the final (larger) text.
        standardizeFonts(frame.getRootPane());
        updateTabStyles(); // re-assert tab bold/dim after fonts are standardized
        offerTable.setRowHeight(offerTable.getFontMetrics(offerTable.getFont()).getHeight() + 6);
        for (JPanel section : List.of(dirsPanel, photosPanel, topArea, offerPanel, workflowPanel, progressPanel)) {
            capHeight(section);
        }

        frame.pack();
        frame.setLocationRelativeTo(null);
        SwingUtilities.invokeLater(() -> split.setDividerLocation(0.5));
    }

    /** Caps a section's height at its preferred size so it fills width but not extra vertical space. */
    private static void capHeight(JPanel panel) {
        Dimension pref = panel.getPreferredSize();
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    /** Sets every component (and titled-border header) in the tree to {@link #UI_FONT_SIZE}, keeping family/style. */
    private void standardizeFonts(Component c) {
        Font font = c.getFont();
        if (font != null) {
            c.setFont(font.deriveFont((float) UI_FONT_SIZE));
        }
        if (c instanceof JComponent jc) {
            resizeTitledBorderFont(jc.getBorder());
        }
        // The table header and in-cell editors aren't in the tree yet at this point
        // (the header is attached to the scroll pane on addNotify), so size them explicitly.
        if (c instanceof JTable table) {
            if (table.getTableHeader() != null) {
                Font hf = table.getTableHeader().getFont();
                if (hf != null) {
                    table.getTableHeader().setFont(hf.deriveFont((float) UI_FONT_SIZE));
                }
            }
            resizeCellEditorFont(table.getDefaultEditor(Object.class));
            for (int col = 0; col < table.getColumnCount(); col++) {
                resizeCellEditorFont(table.getColumnModel().getColumn(col).getCellEditor());
            }
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                standardizeFonts(child);
            }
        }
    }

    private void resizeTitledBorderFont(Border border) {
        if (border instanceof TitledBorder tb) {
            Font f = tb.getTitleFont();
            if (f == null) {
                f = UIManager.getFont("TitledBorder.font");
            }
            if (f == null) {
                f = new Font(Font.DIALOG, Font.BOLD, UI_FONT_SIZE);
            }
            tb.setTitleFont(f.deriveFont((float) UI_FONT_SIZE));
        } else if (border instanceof CompoundBorder cb) {
            resizeTitledBorderFont(cb.getOutsideBorder());
            resizeTitledBorderFont(cb.getInsideBorder());
        }
    }

    private void resizeCellEditorFont(javax.swing.table.TableCellEditor editor) {
        if (editor instanceof javax.swing.DefaultCellEditor dce) {
            Component comp = dce.getComponent();
            if (comp != null && comp.getFont() != null) {
                comp.setFont(comp.getFont().deriveFont((float) UI_FONT_SIZE));
            }
            if (comp instanceof JComboBox<?> combo
                    && combo.getEditor().getEditorComponent() instanceof JTextField field
                    && field.getFont() != null) {
                field.setFont(field.getFont().deriveFont((float) UI_FONT_SIZE));
            }
        }
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
        field.setCaretColor(CARET_COLOR);
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

    private JPanel buildPhotosPanel() {
        JPanel panel = titled("Photos");
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
        buttons.add(seriesModeCombo);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildOfferPanel() {
        JPanel panel = titled("Offer Data");
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
        configureInpostColumn();
        // White caret for the default in-cell text editor.
        if (offerTable.getDefaultEditor(Object.class) instanceof javax.swing.DefaultCellEditor editor
                && editor.getComponent() instanceof JTextField field) {
            field.setCaretColor(CARET_COLOR);
        }
        JScrollPane scroll = new JScrollPane(offerTable);
        scroll.setPreferredSize(new Dimension(880, 150));
        panel.add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton load = new JButton("Load CSV…");
        load.addActionListener(e -> loadCsvViaChooser());
        JButton save = new JButton("Save CSV");
        save.addActionListener(e -> saveCsvToBaseDir());
        JButton addRow = new JButton("Add Row");
        addRow.addActionListener(e -> offerModel.addEmptyRow());
        JButton removeRow = new JButton("Remove Row");
        removeRow.addActionListener(e -> removeSelectedRow());
        buttons.add(load);
        buttons.add(save);
        buttons.add(addRow);
        buttons.add(removeRow);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private void configureInpostColumn() {
        int col = indexOfKey("inpost_size");
        if (col < 0) {
            return;
        }
        TableColumn column = offerTable.getColumnModel().getColumn(col);
        JComboBox<String> combo = new JComboBox<>(new DefaultComboBoxModel<>(new String[]{"A", "B", "C"}));
        combo.setEditable(true);
        if (combo.getEditor().getEditorComponent() instanceof JTextField field) {
            field.setCaretColor(CARET_COLOR);
        }
        column.setCellEditor(new javax.swing.DefaultCellEditor(combo));
    }

    private JPanel buildWorkflowPanel() {
        JPanel panel = titled("Workflow");
        panel.setLayout(new BorderLayout(6, 6));

        // Tight gap: all seven boxes must fit one row at the default window size,
        // or the height-capped section cuts off whatever wraps to a second row.
        JPanel boxes = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        boxes.add(importBox);
        boxes.add(matchBox);
        boxes.add(whiteBalanceBox);
        boxes.add(autoContrastBox);
        boxes.add(autoCropBox);
        boxes.add(ocrBox);
        boxes.add(describeBox);
        panel.add(boxes, BorderLayout.CENTER);

        // Start on the left; the destructive buttons on the far right so a quick
        // reach for Start cannot land on them.
        JPanel startRow = new JPanel(new BorderLayout());
        JPanel startSide = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
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

    private JPanel buildProgressPanel() {
        JPanel panel = titled("Progress");
        panel.setLayout(new BorderLayout(6, 6));
        progressBar.setStringPainted(true);
        panel.add(progressBar, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildLogPanel() {
        JPanel panel = titled("Log");
        panel.setLayout(new BorderLayout(6, 6));
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new java.awt.Font("monospaced", java.awt.Font.PLAIN, 12));
        logArea.setCaretColor(CARET_COLOR);
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return panel;
    }

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
            pane.setCaretColor(CARET_COLOR);
            installEmojiRendering(pane);
        }
        rightTabs.addTab("Description (Input)", new JScrollPane(moreDataArea));
        rightTabs.addTab("Description (Output)", new JScrollPane(detailsArea));
        rightTabs.addTab("Photos (Input)", photosInputGallery.component());
        rightTabs.addTab("Photos (Output)", photosOutputGallery.component());
        rightTabs.addTab("OCR", new JScrollPane(ocrArea));
        rightTabs.addTab("Allegro Lokalnie Form", buildAllegroFormTab());
        // Render tab titles as custom labels we fully control, so the selected tab
        // stays clearly highlighted regardless of the (dark) look and feel.
        for (int i = 0; i < rightTabs.getTabCount(); i++) {
            rightTabs.setTabComponentAt(i, new JLabel(rightTabs.getTitleAt(i)));
        }
        rightTabs.addChangeListener(e -> {
            updateTabStyles();
            updateBottomBar();
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

        top.add(sectionLabel("Link to Allegro Lokalnie form"));
        // The default <a> blue is unreadable on the dark theme, hence the explicit color.
        JLabel link = new JLabel("<html><a href=\"" + ALLEGRO_FORM_URL
                + "\"><font color=\"#FFD75E\">" + ALLEGRO_FORM_URL + "</font></a></html>");
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openFormUrl();
            }
        });
        JButton openUrlButton = new JButton("Open URL");
        openUrlButton.addActionListener(e -> openFormUrl());
        JButton copyAllButton = new JButton("Copy all to Allegro");
        copyAllButton.setToolTipText("Open the form in Chrome and fill in the selected photos,"
                + " the title and the description. You review and submit it yourself.");
        copyAllButton.addActionListener(e -> copyAllToAllegro(copyAllButton));
        JPanel linkRow = flowRow();
        linkRow.add(link);
        linkRow.add(openUrlButton);
        linkRow.add(copyAllButton);
        top.add(linkRow);

        top.add(sectionLabel("Photos"));
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

        top.add(sectionLabel("Title"));
        JPanel titleRow = new JPanel(new BorderLayout(6, 0));
        titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        formTitleField.setCaretColor(CARET_COLOR);
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
        descHeader.add(sectionLabel("Description"), BorderLayout.WEST);
        JButton copyDescriptionButton = new JButton("Copy Description");
        copyDescriptionButton.addActionListener(e ->
                copyToClipboard(formDescriptionArea.getText(), "description"));
        descHeader.add(copyDescriptionButton, BorderLayout.EAST);
        descSection.add(descHeader, BorderLayout.NORTH);
        descSection.add(new JScrollPane(formDescriptionArea), BorderLayout.CENTER);
        panel.add(descSection, BorderLayout.CENTER);

        return panel;
    }

    private static JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JPanel flowRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height + 40));
        return row;
    }

    private void openFormUrl() {
        new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI.create(ALLEGRO_FORM_URL));
                } else {
                    new ProcessBuilder("xdg-open", ALLEGRO_FORM_URL).start();
                }
                SwingUtilities.invokeLater(() -> appendLog("Opened " + ALLEGRO_FORM_URL));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() ->
                        error("Could not open " + ALLEGRO_FORM_URL + ": " + e.getMessage()));
            }
        }, "open-url").start();
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
            error("Nothing to copy: select an offer with photos, a title or a description first.");
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
                        error("Copy to Allegro failed: " + e.getMessage()));
            } finally {
                SwingUtilities.invokeLater(() -> button.setEnabled(true));
            }
        }, "allegro-form-fill").start();
    }

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
                ? outputPhotoDir(currentOfferDir)
                : currentOfferDir.resolve("photos");
        return Files.isDirectory(dir) ? dir : null;
    }

    /**
     * The final photos of an offer: the output of the latest pipeline step that
     * has run — cropped, else auto-contrasted, else white-balanced, else the
     * pre-split {@code retouched/} kept for offers processed before the retouch
     * step was split in two.
     */
    private static Path outputPhotoDir(Path offerDir) {
        for (String dirName : new String[] {"cropped", "contrasted", "white_balanced", "retouched"}) {
            Path dir = offerDir.resolve(dirName);
            if (Files.isDirectory(dir)) {
                return dir;
            }
        }
        return offerDir.resolve("contrasted"); // nonexistent: the gallery shows "Not available yet."
    }

    private boolean isEditorTab() {
        int i = rightTabs.getSelectedIndex();
        return i == TAB_DESCRIPTION_INPUT || i == TAB_DESCRIPTION_OUTPUT || i == TAB_OCR;
    }

    /** The file backing the active editor tab, or null when it has none yet. */
    private Path activeEditorTarget() {
        return switch (rightTabs.getSelectedIndex()) {
            case TAB_DESCRIPTION_INPUT -> moreDataTarget;
            case TAB_DESCRIPTION_OUTPUT -> descriptionTarget;
            case TAB_OCR -> ocrTarget;
            default -> null;
        };
    }

    /** The text pane of the active editor tab, or null on non-editor tabs. */
    private JTextPane activeEditorPane() {
        return switch (rightTabs.getSelectedIndex()) {
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

    /** Highlights the selected tab (bold, bright, accent underline) and dims the rest. */
    private void updateTabStyles() {
        int selected = rightTabs.getSelectedIndex();
        for (int i = 0; i < rightTabs.getTabCount(); i++) {
            if (!(rightTabs.getTabComponentAt(i) instanceof JLabel label)) {
                continue;
            }
            boolean active = i == selected;
            label.setFont(label.getFont().deriveFont(active ? Font.BOLD : Font.PLAIN));
            label.setForeground(active ? TAB_SELECTED_FG : TAB_UNSELECTED_FG);
            // Accent underline on the active tab; matching padding keeps heights equal.
            label.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, active ? 2 : 0, 0, TAB_ACCENT),
                    BorderFactory.createEmptyBorder(3, 8, active ? 3 : 5, 8)));
        }
    }

    private JPanel titled(String title) {
        JPanel panel = new JPanel();
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(6, 8, 0, 8),
                BorderFactory.createTitledBorder(title)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    /**
     * Loads the selected offer's files into the editor tabs:
     * Description (Input) from {@code more_data_<N>.txt} next to offers.csv, and
     * Description (Output) / OCR from {@code description.txt} / {@code ocr.txt}
     * in the offer directory.
     */
    private void loadSelectedOffer() {
        int viewRow = offerTable.getSelectedRow();
        if (viewRow < 0) {
            detailsHeader.setText("Select an offer in the grid.");
            moreDataArea.setText("");
            detailsArea.setText("");
            ocrArea.setText("");
            moreDataTarget = null;
            descriptionTarget = null;
            ocrTarget = null;
            currentOfferDir = null;
            photosInputGallery.message("Select an offer in the grid.");
            photosOutputGallery.message("Select an offer in the grid.");
            formGallery.message("Select an offer in the grid.");
            formTitleField.setText("");
            formDescriptionArea.setText("");
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
        moreDataArea.setText(readIfExists(moreDataTarget));
        moreDataArea.setCaretPosition(0);

        // Description (Output) + galleries live in the resolved offer directory.
        Path offerDir = resolveOfferDir(cfg, name, modelRow);
        currentOfferDir = offerDir;
        if (offerDir == null) {
            descriptionTarget = null;
            ocrTarget = null;
            detailsArea.setText("");
            ocrArea.setText("");
            detailsHeader.setText("<html><b>" + escapeHtml(name) + "</b><br>row " + rowNumber
                    + " — <i>not matched yet (photos and Description output appear after Match)</i></html>");
            photosInputGallery.message("Not matched yet — run Match.");
            photosOutputGallery.message("Not retouched yet — run White balance or Auto-contrast.");
            formGallery.message("Not matched yet — run Match.");
            formDescriptionArea.setText("");
        } else {
            descriptionTarget = offerDir.resolve("description.txt");
            detailsArea.setText(readIfExists(descriptionTarget));
            ocrTarget = offerDir.resolve("ocr.txt");
            ocrArea.setText(readIfExists(ocrTarget));
            detailsHeader.setText("<html><b>" + escapeHtml(name) + "</b><br>row " + rowNumber
                    + " — " + escapeHtml(offerDir.getFileName().toString()) + "</html>");
            photosInputGallery.show(offerDir.resolve("photos"));
            photosOutputGallery.show(outputPhotoDir(offerDir));
            formGallery.show(outputPhotoDir(offerDir));
            formDescriptionArea.setText(readIfExists(descriptionTarget));
        }
        formTitleField.setText(name);
        formTitleField.setCaretPosition(0);
        detailsArea.setCaretPosition(0);
        ocrArea.setCaretPosition(0);
        formDescriptionArea.setCaretPosition(0);
        updateBottomBar();
    }

    /** Saves the currently active editor tab to its backing file. */
    private void saveActiveTab() {
        if (!isEditorTab()) {
            return;
        }
        if (offerTable.getSelectedRow() < 0 || moreDataTarget == null) {
            error("Select an offer in the grid first.");
            return;
        }
        Path target = activeEditorTarget();
        if (target == null) {
            // Only reachable for the offer-directory tabs before the offer is matched.
            error("No offer directory yet — run Match first.");
            return;
        }
        String content = activeEditorPane().getText();
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
            appendLog("Saved " + target);
        } catch (IOException e) {
            error("Failed to save " + target + ": " + e.getMessage());
        }
    }

    /** Deletes the file backing the active tab, after confirmation, and clears its editor. */
    private void deleteActiveFile() {
        if (!isEditorTab()) {
            return;
        }
        if (offerTable.getSelectedRow() < 0 || moreDataTarget == null) {
            error("Select an offer in the grid first.");
            return;
        }
        Path target = activeEditorTarget();
        if (target == null) {
            error("No offer directory yet — run Match first.");
            return;
        }
        if (!Files.exists(target)) {
            JOptionPane.showMessageDialog(frame, "There is no file to delete yet:\n" + target,
                    "Allegro Helper", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int choice = JOptionPane.showConfirmDialog(frame,
                "Delete this file? This cannot be undone.\n\n" + target,
                "Delete file", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            Files.delete(target);
            activeEditorPane().setText("");
            appendLog("Deleted " + target);
        } catch (IOException e) {
            error("Failed to delete " + target + ": " + e.getMessage());
        }
    }

    /** Opens the directory backing the active Photos tab in the system file manager. */
    private void openActivePhotoDir() {
        Path dir = activePhotoDir();
        if (dir == null) {
            error(currentOfferDir == null
                    ? "No offer directory yet — run Match first."
                    : "That photo directory does not exist yet.");
            return;
        }
        openInSystem(dir);
    }

    /**
     * Opens a file or directory with the system default handler (the image viewer
     * or file manager). Launching it can block briefly, so it runs off the EDT.
     */
    private void openInSystem(Path target) {
        new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(target.toFile());
                } else {
                    new ProcessBuilder("xdg-open", target.toString()).start();
                }
                SwingUtilities.invokeLater(() -> appendLog("Opened " + target));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() ->
                        error("Could not open " + target + ": " + e.getMessage()));
            }
        }, "open-in-system").start();
    }

    /** Clears the active editor only; the file is unchanged until Save is clicked. */
    private void clearActiveEditor() {
        if (!isEditorTab()) {
            return;
        }
        activeEditorPane().setText("");
    }

    private static String readIfExists(Path file) {
        if (file != null && Files.isRegularFile(file)) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "Could not read " + file + ": " + e.getMessage();
            }
        }
        return "";
    }

    /**
     * Finds the offer directory for a grid row: first by matching the
     * {@code name} stored in each offer's data.json, then falling back to the
     * row's position among the sorted offer directories (which is the order the
     * match step assigns them).
     */
    private Path resolveOfferDir(Config cfg, String name, int index) {
        if (!Files.isDirectory(cfg.offersDir)) {
            return null;
        }
        List<Path> dirs = new ArrayList<>();
        try (var stream = Files.list(cfg.offersDir)) {
            stream.filter(Files::isDirectory).forEach(dirs::add);
        } catch (IOException e) {
            return null;
        }
        dirs.sort(Comparator.comparing(p -> p.getFileName().toString()));

        String target = name == null ? "" : name.strip();
        if (!target.isEmpty()) {
            for (Path dir : dirs) {
                Path dataJson = dir.resolve("data.json");
                if (!Files.isRegularFile(dataJson)) {
                    continue;
                }
                try {
                    Map<String, Object> data = Json.parseObject(
                            Files.readString(dataJson, StandardCharsets.UTF_8));
                    Object nm = data.get("name");
                    if (nm != null && nm.toString().strip().equals(target)) {
                        return dir;
                    }
                } catch (Exception ignored) {
                    // Skip unreadable/invalid data.json.
                }
            }
        }
        return index >= 0 && index < dirs.size() ? dirs.get(index) : null;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static boolean isJpeg(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg");
    }

    /**
     * A scrollable, wrapping grid of photo thumbnails (a {@link JList} in
     * horizontal-wrap mode). Thumbnails are loaded off the EDT; a token guards
     * against a slower load from a previously selected offer overwriting a newer
     * one.
     *
     * <p>Thumbnails can be dragged out of the app — e.g. onto a browser's upload
     * form. The drag exports the underlying files as
     * {@link DataFlavor#javaFileListFlavor}, which AWT translates to a native
     * {@code text/uri-list} drag on Linux, so a drop target sees the same thing
     * a file-manager drag would give it.
     */
    private final class Gallery {
        private final DefaultListModel<Object> model = new DefaultListModel<>();
        private final JList<Object> list = new JList<>(model);
        private final JScrollPane scroll = new JScrollPane(list);
        private final AtomicInteger token = new AtomicInteger();
        /** Files behind the thumbnails, kept index-aligned with {@link #model}. */
        private final List<Path> loadedFiles = new ArrayList<>();
        private final int thumbSize;
        /** When > 0, the first this-many thumbnails are selected after each load. */
        private final int preselect;

        Gallery(int thumbSize, int preselect) {
            this.thumbSize = thumbSize;
            this.preselect = preselect;
            list.setLayoutOrientation(JList.HORIZONTAL_WRAP);
            list.setVisibleRowCount(0);
            list.setFixedCellWidth(thumbSize + 16);
            list.setFixedCellHeight(thumbSize + 16);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            list.setToolTipText("Double-click a photo to open it in the default viewer. "
                    + "Drag photos onto another app (e.g. a browser upload form); Ctrl/Shift-click selects several.");
            list.setDragEnabled(true);
            list.setTransferHandler(new TransferHandler() {
                @Override
                public int getSourceActions(JComponent c) {
                    return COPY;
                }

                @Override
                protected Transferable createTransferable(JComponent c) {
                    List<File> files = new ArrayList<>();
                    for (int index : list.getSelectedIndices()) {
                        if (index < loadedFiles.size()) { // skip the status/"Loading…" element
                            files.add(loadedFiles.get(index).toFile());
                        }
                    }
                    return files.isEmpty() ? null : new FileListTransferable(files);
                }
            });
            new DragGhost(list); // source-side drag feedback; see the class javadoc
            list.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) {
                        return;
                    }
                    int index = list.locationToIndex(e.getPoint());
                    if (index < 0 || index >= loadedFiles.size()) {
                        return; // no thumbnails (e.g. a status message is shown)
                    }
                    Rectangle cell = list.getCellBounds(index, index);
                    if (cell != null && cell.contains(e.getPoint())) {
                        openInSystem(loadedFiles.get(index));
                    }
                }
            });
        }

        JScrollPane component() {
            return scroll;
        }

        /** The selected photos, in gallery order. */
        List<Path> selectedFiles() {
            List<Path> files = new ArrayList<>();
            for (int index : list.getSelectedIndices()) {
                if (index < loadedFiles.size()) { // skip the status/"Loading…" element
                    files.add(loadedFiles.get(index));
                }
            }
            return files;
        }

        /** Shows a single status line instead of thumbnails. */
        void message(String text) {
            token.incrementAndGet();
            model.clear();
            loadedFiles.clear();
            model.addElement(text);
        }

        /** Loads thumbnails for every JPEG in {@code dir}, progressively. */
        void show(Path dir) {
            int my = token.incrementAndGet();
            model.clear();
            loadedFiles.clear();
            if (dir == null || !Files.isDirectory(dir)) {
                model.addElement("Not available yet.");
                return;
            }
            List<Path> files;
            try (var stream = Files.list(dir)) {
                files = stream.filter(MainWindow::isJpeg)
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .toList();
            } catch (IOException e) {
                model.addElement("Could not read " + dir + ": " + e.getMessage());
                return;
            }
            if (files.isEmpty()) {
                model.addElement("No photos.");
                return;
            }
            model.addElement("Loading " + files.size() + " thumbnails…");
            galleryLoader.submit(() -> {
                boolean[] cleared = {false};
                for (Path file : files) {
                    if (token.get() != my) {
                        return; // a newer selection superseded this load
                    }
                    ImageIcon icon = Thumbnails.load(file, thumbSize);
                    SwingUtilities.invokeLater(() -> {
                        if (token.get() != my) {
                            return;
                        }
                        if (!cleared[0]) {
                            model.clear(); // drop the "Loading…" placeholder on first result
                            cleared[0] = true;
                        }
                        if (icon != null) {
                            model.addElement(icon);
                            loadedFiles.add(file); // stays index-aligned with the model
                        }
                    });
                }
                if (preselect > 0) {
                    SwingUtilities.invokeLater(() -> {
                        if (token.get() == my && !loadedFiles.isEmpty()) {
                            list.setSelectionInterval(0,
                                    Math.min(preselect, loadedFiles.size()) - 1);
                        }
                    });
                }
            });
        }
    }

    /**
     * Source-side visual feedback for gallery drags: a "copy" cursor plus a
     * floating stack of the dragged thumbnails that follows the pointer.
     *
     * <p>This exists because X11's drag protocol (XDND — what Java uses even
     * under Wayland, via XWayland) has no drag-image support:
     * {@link DragSource#isDragImageSupported()} is false and
     * {@link TransferHandler#setDragImage} is silently ignored, so without it a
     * drag shows nothing but the plain arrow. The workaround is a small
     * always-on-top window moved from {@link DragSourceMotionListener}, which
     * keeps firing over external apps because the drag source holds the pointer
     * grab. The window trails the hotspot by a fixed offset so the pointer is
     * never over it — otherwise the XDND target search would find our own
     * window instead of the real drop target.
     */
    private static final class DragGhost extends DragSourceAdapter implements DragSourceMotionListener {

        /** Gap between the pointer hotspot and the ghost window (px). */
        private static final int POINTER_OFFSET = 18;
        /** Longest side of a thumbnail in the ghost (px). */
        private static final int GHOST_THUMB = 96;
        /** X/Y shift between stacked thumbnails (px). */
        private static final int GHOST_STEP = 12;
        /** At most this many thumbnails are stacked; a badge shows the true count. */
        private static final int GHOST_MAX_STACK = 3;

        private final JList<Object> list;
        private JWindow window;

        DragGhost(JList<Object> list) {
            this.list = list;
            // The default DragSource is shared, so events from every drag in the
            // app arrive here; mine() filters down to this gallery's list.
            DragSource ds = DragSource.getDefaultDragSource();
            ds.addDragSourceListener(this);
            ds.addDragSourceMotionListener(this);
        }

        private boolean mine(DragSourceEvent e) {
            return e.getDragSourceContext().getComponent() == list;
        }

        @Override
        public void dragMouseMoved(DragSourceDragEvent e) {
            if (!mine(e)) {
                return;
            }
            if (window == null) {
                window = createWindow();
                // A custom cursor disables the automatic drop-feedback cursor,
                // which never signals anything useful across the XWayland
                // bridge anyway; a constant "copy" cursor beats a plain arrow.
                e.getDragSourceContext().setCursor(DragSource.DefaultCopyDrop);
            }
            window.setLocation(e.getX() + POINTER_OFFSET, e.getY() + POINTER_OFFSET);
            if (!window.isVisible()) {
                window.setVisible(true);
            }
        }

        @Override
        public void dragDropEnd(DragSourceDropEvent e) {
            if (mine(e) && window != null) {
                window.dispose();
                window = null;
            }
        }

        private JWindow createWindow() {
            JWindow w = new JWindow();
            w.setType(Window.Type.POPUP); // override-redirect: no WM decoration or focus
            w.setAlwaysOnTop(true);
            w.setFocusableWindowState(false);
            BufferedImage image = ghostImage();
            GraphicsDevice device = w.getGraphicsConfiguration().getDevice();
            if (device.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
                w.setBackground(new Color(0, 0, 0, 0));
            }
            JLabel label = new JLabel(new ImageIcon(image));
            ((JComponent) w.getContentPane()).setOpaque(false);
            w.add(label);
            w.pack();
            return w;
        }

        /** Renders the selected thumbnails as a slightly offset stack with a count badge. */
        private BufferedImage ghostImage() {
            List<Image> thumbs = new ArrayList<>();
            for (Object value : list.getSelectedValuesList()) {
                if (value instanceof ImageIcon icon && thumbs.size() < GHOST_MAX_STACK) {
                    thumbs.add(icon.getImage());
                }
            }
            int count = (int) list.getSelectedValuesList().stream().filter(v -> v instanceof ImageIcon).count();
            int size = GHOST_THUMB + GHOST_STEP * (Math.max(thumbs.size(), 1) - 1);
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            for (int i = thumbs.size() - 1; i >= 0; i--) { // bottom of the stack first
                Image t = thumbs.get(i);
                double scale = (double) GHOST_THUMB / Math.max(t.getWidth(null), t.getHeight(null));
                int tw = (int) Math.round(t.getWidth(null) * scale);
                int th = (int) Math.round(t.getHeight(null) * scale);
                int x = i * GHOST_STEP;
                int y = i * GHOST_STEP;
                g.drawImage(t, x, y, tw, th, null);
                g.setColor(Color.WHITE);
                g.drawRect(x, y, tw - 1, th - 1);
            }
            if (count > 1) {
                String text = String.valueOf(count);
                g.setFont(g.getFont().deriveFont(Font.BOLD, 14f));
                int d = 22; // badge diameter
                int bx = size - d - 2;
                g.setColor(TAB_ACCENT);
                g.fillOval(bx, 2, d, d);
                g.setColor(Color.WHITE);
                var fm = g.getFontMetrics();
                g.drawString(text, bx + (d - fm.stringWidth(text)) / 2, 2 + (d + fm.getAscent() - fm.getDescent()) / 2);
            }
            g.dispose();
            return image;
        }
    }

    /** A drag payload of files, exposed only as {@link DataFlavor#javaFileListFlavor}. */
    private static final class FileListTransferable implements Transferable {
        private final List<File> files;

        FileListTransferable(List<File> files) {
            this.files = files;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] {DataFlavor.javaFileListFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return files;
        }
    }

    // ----------------------------------------------------------------- actions

    private void chooseBaseDir() {
        JFileChooser chooser = new JFileChooser(baseDirField.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            baseDirField.setText(chooser.getSelectedFile().getAbsolutePath());
            loadOffersFromBaseDir();
        }
    }

    private void choosePhotoDir() {
        // The field usually holds the MTP glob, which no chooser can open;
        // start from the deepest existing prefix of it instead.
        Path start = deepestExistingDir(photoDirField.getText().strip());
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

    /** The deepest existing directory along the given path, stopping at any glob segment. */
    private static Path deepestExistingDir(String pattern) {
        if (!pattern.startsWith("/")) {
            return null;
        }
        Path result = null;
        Path current = Path.of("/");
        for (String segment : pattern.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (segment.indexOf('*') >= 0 || segment.indexOf('?') >= 0) {
                break;
            }
            current = current.resolve(segment);
            if (!Files.isDirectory(current)) {
                break;
            }
            result = current;
        }
        return result;
    }

    private void loadOffersFromBaseDir() {
        Config cfg = currentConfig();
        try {
            offerModel.loadFromCsvIfPresent(cfg.csvPath);
        } catch (IOException e) {
            appendLog("Could not read " + cfg.csvPath + ": " + e.getMessage());
        }
        loadSelectedOffer();
    }

    private void loadCsvViaChooser() {
        JFileChooser chooser = new JFileChooser(baseDirField.getText());
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            try {
                offerModel.loadFromCsv(chooser.getSelectedFile().toPath());
                appendLog("Loaded offers from " + chooser.getSelectedFile());
                loadSelectedOffer();
            } catch (IOException e) {
                error("Failed to load CSV: " + e.getMessage());
            }
        }
    }

    private void saveCsvToBaseDir() {
        stopCellEditing();
        Config cfg = currentConfig();
        try {
            int n = offerModel.saveToCsv(cfg.csvPath);
            appendLog("Saved " + n + " offers to " + cfg.csvPath);
        } catch (IOException e) {
            error("Failed to save CSV: " + e.getMessage());
        }
    }

    private void removeSelectedRow() {
        int viewRow = offerTable.getSelectedRow();
        if (viewRow >= 0) {
            offerModel.removeRow(offerTable.convertRowIndexToModel(viewRow));
        }
    }

    private void refreshPhotos() {
        Config cfg = currentConfig();
        refreshPhotosButton.setEnabled(false);
        photosModel.clear();
        photosModel.addElement("Scanning phone…");
        new Thread(() -> {
            String message;
            List<String> entries = new ArrayList<>();
            try {
                PhoneScan.Result result = PhoneScan.scan(cfg);
                if (result.series().isEmpty()) {
                    message = "No photo series found in " + result.sourceDir();
                } else {
                    message = null;
                    for (PhotoSeries s : result.series()) {
                        entries.add(s.label() + " | " + s.count() + "x series of photos to import");
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
            error("Refusing to delete " + offersDir
                    + ": it contains offers.csv or raw_photos (check OFFERS_DIR).");
            return;
        }

        String title = restart ? "Clean & Restart" : "Delete Output Files";
        int choice = JOptionPane.showConfirmDialog(frame,
                "This deletes all generated offer directories under:\n" + offersDir
                        + "\n\nSources are kept: photos on the phone, offers.csv, more_data_<N>.txt."
                        + "\nRe-importing the photos requires the phone to be connected."
                        + (restart ? "\n\nDelete and start the selected workflow steps?" : "\n\nDelete?"),
                title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }

        if (Files.isDirectory(offersDir)) {
            int deleted = 0;
            try {
                try (var stream = Files.list(offersDir)) {
                    for (Path entry : stream.toList()) {
                        deleteRecursively(entry);
                        deleted++;
                    }
                }
            } catch (IOException e) {
                error("Failed to delete output files: " + e.getMessage());
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

    private static void deleteRecursively(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private void startWorkflow() {
        if (running) {
            return;
        }
        stopCellEditing();

        List<Workflow.Step> steps = selectedSteps();
        if (steps.isEmpty()) {
            error("Select at least one workflow step.");
            return;
        }

        Config cfg = currentConfig();

        // The match step consumes offers.csv, so persist the grid first.
        if (steps.contains(Workflow.Step.MATCH)) {
            try {
                int n = offerModel.saveToCsv(cfg.csvPath);
                appendLog("Saved " + n + " offers to " + cfg.csvPath);
            } catch (IOException e) {
                error("Failed to save offers.csv before matching: " + e.getMessage());
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
        if (autoContrastBox.isSelected()) {
            steps.add(Workflow.Step.AUTO_CONTRAST);
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

    private Config currentConfig() {
        Map<String, String> overrides = new HashMap<>();
        String photoDir = photoDirField.getText().strip();
        if (!photoDir.isEmpty()) {
            overrides.put("MTP_GLOB_PATTERN", photoDir);
        }
        overrides.put("SERIES_RECOGNITION",
                SeriesRecognition.Mode.values()[seriesModeCombo.getSelectedIndex()].key);
        return Config.forBaseDir(Path.of(baseDirField.getText().strip()), overrides);
    }

    private void setRunning(boolean value) {
        running = value;
        startButton.setEnabled(!value);
        startButton.setText(value ? "Running…" : "Start");
        deleteOutputsButton.setEnabled(!value);
        cleanRestartButton.setEnabled(!value);
    }

    private void stopCellEditing() {
        if (offerTable.isEditing()) {
            offerTable.getCellEditor().stopCellEditing();
        }
    }

    private int indexOfKey(String key) {
        for (int i = 0; i < OfferTableModel.KEYS.length; i++) {
            if (OfferTableModel.KEYS[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private void appendLog(String line) {
        logArea.append(line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(frame, message, "Allegro Helper", JOptionPane.ERROR_MESSAGE);
    }
}
