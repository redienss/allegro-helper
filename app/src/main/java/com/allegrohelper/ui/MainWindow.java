package com.allegrohelper.ui;

import com.allegrohelper.core.Config;
import com.allegrohelper.core.PhoneScan;
import com.allegrohelper.core.PhotoSeries;
import com.allegrohelper.core.Workflow;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The Allegro Helper main window: photo series detected on the phone, the
 * editable offer grid, the workflow selector, a progress bar and a log.
 */
public final class MainWindow {

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

    private volatile boolean running = false;

    public MainWindow(Path initialBaseDir) {
        baseDirField.setText(initialBaseDir.toAbsolutePath().normalize().toString());
        build();
        loadOffersFromBaseDir();
    }

    public void show() {
        frame.setVisible(true);
    }

    /** The underlying frame (exposed for embedding and for offscreen rendering in tests). */
    public JFrame getFrame() {
        return frame;
    }

    private void build() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setPreferredSize(new Dimension(940, 880));
        frame.setLayout(new BorderLayout(8, 8));

        // Fixed control stack on top; each section keeps its preferred height but
        // fills the available width. The log fills the remaining space below.
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.add(fixHeight(buildBaseDirPanel()));
        controls.add(fixHeight(buildPhotosPanel()));
        controls.add(fixHeight(buildOfferPanel()));
        controls.add(fixHeight(buildWorkflowPanel()));
        controls.add(fixHeight(buildProgressPanel()));

        frame.add(controls, BorderLayout.NORTH);
        frame.add(buildLogPanel(), BorderLayout.CENTER);

        frame.pack();
        frame.setLocationRelativeTo(null);
    }

    /** Caps a section's height at its preferred size so it fills width but not extra vertical space. */
    private static JPanel fixHeight(JPanel panel) {
        Dimension pref = panel.getPreferredSize();
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private JPanel buildBaseDirPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        panel.add(new JLabel("Base directory:"), BorderLayout.WEST);
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
        configureInpostColumn();
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
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return panel;
    }

    private JPanel titled(String title) {
        JPanel panel = new JPanel();
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(6, 8, 0, 8),
                BorderFactory.createTitledBorder(title)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
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
    }

    private void loadCsvViaChooser() {
        JFileChooser chooser = new JFileChooser(baseDirField.getText());
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            try {
                offerModel.loadFromCsv(chooser.getSelectedFile().toPath());
                appendLog("Loaded offers from " + chooser.getSelectedFile());
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
