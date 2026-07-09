package com.allegrohelper.ui;

import com.allegrohelper.core.Config;
import com.allegrohelper.core.PhoneScan;
import com.allegrohelper.core.PhotoSeries;
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
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Image;
import java.net.URL;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
    private static final int TAB_PHOTOS_INPUT = 0;   // original photos gallery
    private static final int TAB_PHOTOS_OUTPUT = 1;  // retouched photos gallery
    private static final int TAB_DESCRIPTION_INPUT = 2;   // more_data_<N>.txt editor
    private static final int TAB_DESCRIPTION_OUTPUT = 3;  // description.txt editor

    /** Thumbnail box size (px) for the photo galleries. */
    private static final int THUMB_SIZE = 140;

    private final JFrame frame = new JFrame("Allegro Helper");
    private final JTextField baseDirField = new JTextField();
    private final DefaultListModel<String> photosModel = new DefaultListModel<>();
    private final OfferTableModel offerModel = new OfferTableModel();
    private final JTable offerTable = new JTable(offerModel);

    private final JCheckBox importBox = new JCheckBox("Import", true);
    private final JCheckBox matchBox = new JCheckBox("Match", true);
    private final JCheckBox retouchBox = new JCheckBox("Retouch", true);
    private final JCheckBox describeBox = new JCheckBox("Describe", true);

    private final JButton startButton = new JButton("Start");
    private final JButton refreshPhotosButton = new JButton("Refresh");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JTextArea logArea = new JTextArea();

    private final JLabel detailsHeader = new JLabel();
    private final JTabbedPane rightTabs = new JTabbedPane();
    private final JTextArea moreDataArea = new JTextArea();   // Description (Input) -> more_data_<N>.txt
    private final JTextArea detailsArea = new JTextArea();    // Description (Output) -> description.txt

    // Photo galleries; a single background thread loads their thumbnails.
    private final ExecutorService galleryLoader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gallery-loader");
        t.setDaemon(true);
        return t;
    });
    private final Gallery photosInputGallery = new Gallery();
    private final Gallery photosOutputGallery = new Gallery();

    private JButton deleteButton;
    private JButton clearButton;
    private JButton saveButton;
    private JPanel editorButtonBar;

    // Save targets for the currently selected offer row (null when nothing is selected /
    // no offer directory exists yet).
    private Path moreDataTarget;
    private Path descriptionTarget;

    private volatile boolean running = false;

    public MainWindow(Path initialBaseDir) {
        baseDirField.setText(initialBaseDir.toAbsolutePath().normalize().toString());
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
        JPanel baseDirPanel = buildBaseDirPanel();
        JPanel photosPanel = buildPhotosPanel();
        JPanel offerPanel = buildOfferPanel();
        JPanel workflowPanel = buildWorkflowPanel();
        JPanel progressPanel = buildProgressPanel();

        // Top area: the application logo in the upper-left corner, with the base
        // directory and Photos section shifted to its right.
        JPanel topRight = new JPanel();
        topRight.setLayout(new BoxLayout(topRight, BoxLayout.Y_AXIS));
        baseDirPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        photosPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        topRight.add(baseDirPanel);
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
        for (JPanel section : List.of(baseDirPanel, photosPanel, topArea, offerPanel, workflowPanel, progressPanel)) {
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

    private JPanel buildBaseDirPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        panel.add(new JLabel("Base directory:"), BorderLayout.WEST);
        baseDirField.setCaretColor(CARET_COLOR);
        panel.add(baseDirField, BorderLayout.CENTER);
        JButton browse = new JButton("Browse…");
        browse.addActionListener(e -> chooseBaseDir());
        panel.add(browse, BorderLayout.EAST);
        return panel;
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
        buttons.add(new JLabel("Photo series recognized on the connected phone."));
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

        JPanel boxes = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 2));
        boxes.add(importBox);
        boxes.add(matchBox);
        boxes.add(retouchBox);
        boxes.add(describeBox);
        panel.add(boxes, BorderLayout.CENTER);

        JPanel startRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        startButton.addActionListener(e -> startWorkflow());
        startRow.add(startButton);
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
        for (JTextArea area : new JTextArea[]{moreDataArea, detailsArea}) {
            area.setEditable(true);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setFont(mono);
            area.setCaretColor(CARET_COLOR);
        }
        rightTabs.addTab("Photos (Input)", photosInputGallery.component());
        rightTabs.addTab("Photos (Output)", photosOutputGallery.component());
        rightTabs.addTab("Description (Input)", new JScrollPane(moreDataArea));
        rightTabs.addTab("Description (Output)", new JScrollPane(detailsArea));
        // Render tab titles as custom labels we fully control, so the selected tab
        // stays clearly highlighted regardless of the (dark) look and feel.
        for (int i = 0; i < rightTabs.getTabCount(); i++) {
            rightTabs.setTabComponentAt(i, new JLabel(rightTabs.getTitleAt(i)));
        }
        rightTabs.addChangeListener(e -> {
            updateTabStyles();
            updateEditorButtons();
        });
        updateTabStyles();
        panel.add(rightTabs, BorderLayout.CENTER);

        // Destructive actions (Delete/Clear) sit in the lower-left corner, away from
        // Save in the lower-right, to avoid accidental clicks. They act on the active
        // Description tab, so they're disabled while a Photos gallery is shown.
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

        editorButtonBar = new JPanel(new BorderLayout());
        editorButtonBar.add(leftButtons, BorderLayout.WEST);
        editorButtonBar.add(rightButtons, BorderLayout.EAST);
        panel.add(editorButtonBar, BorderLayout.SOUTH);

        updateEditorButtons();
        return panel;
    }

    /** Shows the Delete/Clear/Save bar only while a Description (editor) tab is active. */
    private void updateEditorButtons() {
        if (editorButtonBar == null) {
            return;
        }
        editorButtonBar.setVisible(isEditorTab());
        editorButtonBar.revalidate();
        editorButtonBar.repaint();
    }

    private boolean isEditorTab() {
        int i = rightTabs.getSelectedIndex();
        return i == TAB_DESCRIPTION_INPUT || i == TAB_DESCRIPTION_OUTPUT;
    }

    private boolean isDescriptionInputTab() {
        return rightTabs.getSelectedIndex() == TAB_DESCRIPTION_INPUT;
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
     * Loads the selected offer's files into the two editor tabs:
     * More Data (Input) from {@code more_data_<N>.txt} next to offers.csv, and
     * Offer Details (Output) from {@code description.txt} in the offer directory.
     */
    private void loadSelectedOffer() {
        int viewRow = offerTable.getSelectedRow();
        if (viewRow < 0) {
            detailsHeader.setText("Select an offer in the grid.");
            moreDataArea.setText("");
            detailsArea.setText("");
            moreDataTarget = null;
            descriptionTarget = null;
            photosInputGallery.message("Select an offer in the grid.");
            photosOutputGallery.message("Select an offer in the grid.");
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
        if (offerDir == null) {
            descriptionTarget = null;
            detailsArea.setText("");
            detailsHeader.setText("<html><b>" + escapeHtml(name) + "</b><br>row " + rowNumber
                    + " — <i>not matched yet (photos and Description output appear after Match)</i></html>");
            photosInputGallery.message("Not matched yet — run Match.");
            photosOutputGallery.message("Not retouched yet — run Retouch.");
        } else {
            descriptionTarget = offerDir.resolve("description.txt");
            detailsArea.setText(readIfExists(descriptionTarget));
            detailsHeader.setText("<html><b>" + escapeHtml(name) + "</b><br>row " + rowNumber
                    + " — " + escapeHtml(offerDir.getFileName().toString()) + "</html>");
            photosInputGallery.show(offerDir.resolve("photos"));
            photosOutputGallery.show(offerDir.resolve("retouched"));
        }
        detailsArea.setCaretPosition(0);
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
        boolean moreDataTab = isDescriptionInputTab();
        Path target = moreDataTab ? moreDataTarget : descriptionTarget;
        String content = moreDataTab ? moreDataArea.getText() : detailsArea.getText();

        if (target == null) {
            // Only reachable for the Offer Details tab before the offer is matched.
            error("No offer directory yet — run Match first, then Describe.");
            return;
        }
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
        boolean moreDataTab = isDescriptionInputTab();
        Path target = moreDataTab ? moreDataTarget : descriptionTarget;
        if (target == null) {
            error("No offer directory yet — run Match first, then Describe.");
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
            (moreDataTab ? moreDataArea : detailsArea).setText("");
            appendLog("Deleted " + target);
        } catch (IOException e) {
            error("Failed to delete " + target + ": " + e.getMessage());
        }
    }

    /** Clears the active editor only; the file is unchanged until Save is clicked. */
    private void clearActiveEditor() {
        if (!isEditorTab()) {
            return;
        }
        (isDescriptionInputTab() ? moreDataArea : detailsArea).setText("");
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
     */
    private final class Gallery {
        private final DefaultListModel<Object> model = new DefaultListModel<>();
        private final JList<Object> list = new JList<>(model);
        private final JScrollPane scroll = new JScrollPane(list);
        private final AtomicInteger token = new AtomicInteger();

        Gallery() {
            list.setLayoutOrientation(JList.HORIZONTAL_WRAP);
            list.setVisibleRowCount(0);
            list.setFixedCellWidth(THUMB_SIZE + 16);
            list.setFixedCellHeight(THUMB_SIZE + 16);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
        }

        JScrollPane component() {
            return scroll;
        }

        /** Shows a single status line instead of thumbnails. */
        void message(String text) {
            token.incrementAndGet();
            model.clear();
            model.addElement(text);
        }

        /** Loads thumbnails for every JPEG in {@code dir}, progressively. */
        void show(Path dir) {
            int my = token.incrementAndGet();
            model.clear();
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
                    ImageIcon icon = Thumbnails.load(file, THUMB_SIZE);
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
                        }
                    });
                }
            });
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
        if (retouchBox.isSelected()) {
            steps.add(Workflow.Step.RETOUCH);
        }
        if (describeBox.isSelected()) {
            steps.add(Workflow.Step.DESCRIBE);
        }
        return steps;
    }

    private Config currentConfig() {
        return Config.forBaseDir(Path.of(baseDirField.getText().strip()));
    }

    private void setRunning(boolean value) {
        running = value;
        startButton.setEnabled(!value);
        startButton.setText(value ? "Running…" : "Start");
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
