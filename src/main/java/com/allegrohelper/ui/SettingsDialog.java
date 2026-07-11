package com.allegrohelper.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;

/**
 * The File &gt; Settings dialog: a PhpStorm-style two-pane layout — the page
 * list on the left, the selected page's card on the right, OK/Cancel/Apply
 * below. Pages: Appearance ({@link Theme}) and Language ({@link Language}).
 *
 * <p>Apply (and OK) takes effect immediately: it installs the look and feel
 * and/or language, persists the choices, restyles and retranslates every open
 * window, and then runs the caller's hook so {@link MainWindow} can re-apply
 * the styling its build baked in. No restart involved.
 *
 * <p>Built entirely from English literals like every window, then passed
 * through {@link I18n#retranslate}; the page list and combos render their
 * (English) values through {@link I18n#t} at paint time, so a language switch
 * only needs a repaint.
 */
final class SettingsDialog extends JDialog {

    private static final String PAGE_APPEARANCE = "Appearance";
    private static final String PAGE_LANGUAGE = "Language";

    private final JComboBox<Theme> themeCombo = new JComboBox<>(Theme.values());
    private final JComboBox<Language> languageCombo = new JComboBox<>(Language.values());
    private final JButton applyButton = new JButton("Apply");
    private final Runnable onSettingsApplied;

    SettingsDialog(JFrame owner, Runnable onSettingsApplied) {
        super(owner, "Settings", true);
        this.onSettingsApplied = onSettingsApplied;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        JList<String> pages = new JList<>(new String[]{PAGE_APPEARANCE, PAGE_LANGUAGE});
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
        applyButton.setEnabled(false);
        themeCombo.addActionListener(e -> updateApplyEnabled());
        languageCombo.addActionListener(e -> updateApplyEnabled());

        I18n.retranslate(this);
        MainWindow.standardizeFonts(getRootPane());
        setPreferredSize(new Dimension(760, 480));
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

    private void updateApplyEnabled() {
        applyButton.setEnabled(themeCombo.getSelectedItem() != Theme.current()
                || languageCombo.getSelectedItem() != Language.current());
    }

    /**
     * Installs and persists the selected theme and language, restyling and
     * retranslating every open window (this dialog included).
     */
    private void applySelection() {
        Theme theme = (Theme) themeCombo.getSelectedItem();
        Language language = (Language) languageCombo.getSelectedItem();
        boolean themeChanged = theme != null && theme != Theme.current();
        boolean languageChanged = language != null && language != Language.current();
        if (!themeChanged && !languageChanged) {
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
        MainWindow.standardizeFonts(getRootPane()); // updateComponentTreeUI reset this dialog's fonts
        updateApplyEnabled();
    }
}
