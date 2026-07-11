package com.allegrohelper.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
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
 * below. The only page so far is Appearance, which picks the {@link Theme}.
 *
 * <p>Apply (and OK) takes effect immediately: it installs the look and feel,
 * persists the choice, restyles every open window, and then runs the caller's
 * hook so {@link MainWindow} can re-apply the styling its build baked in for
 * the previous theme. No restart involved.
 */
final class SettingsDialog extends JDialog {

    private static final String PAGE_APPEARANCE = "Appearance";

    private final JComboBox<Theme> themeCombo = new JComboBox<>(Theme.values());
    private final JButton applyButton = new JButton("Apply");
    private final Runnable onThemeApplied;

    SettingsDialog(JFrame owner, Runnable onThemeApplied) {
        super(owner, "Settings", true);
        this.onThemeApplied = onThemeApplied;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        JList<String> pages = new JList<>(new String[]{PAGE_APPEARANCE});
        pages.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        pages.setSelectedIndex(0);
        pages.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JScrollPane pageList = new JScrollPane(pages);
        pageList.setPreferredSize(new Dimension(220, 0));

        CardLayout cards = new CardLayout();
        JPanel content = new JPanel(cards);
        content.add(buildAppearancePage(), PAGE_APPEARANCE);
        pages.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && pages.getSelectedValue() != null) {
                cards.show(content, pages.getSelectedValue());
            }
        });

        add(pageList, BorderLayout.WEST);
        add(content, BorderLayout.CENTER);
        add(buildButtonBar(), BorderLayout.SOUTH);

        themeCombo.setSelectedItem(Theme.current());
        applyButton.setEnabled(false);
        themeCombo.addActionListener(e ->
                applyButton.setEnabled(themeCombo.getSelectedItem() != Theme.current()));

        MainWindow.standardizeFonts(getRootPane());
        setPreferredSize(new Dimension(760, 480));
        pack();
        setLocationRelativeTo(owner);
    }

    private JPanel buildAppearancePage() {
        JPanel page = new JPanel(new GridBagLayout());
        page.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(0, 0, 16, 0);
        JLabel header = new JLabel(PAGE_APPEARANCE);
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        page.add(header, c);

        c.gridy = 1;
        c.gridwidth = 1;
        c.insets = new Insets(0, 0, 0, 8);
        page.add(new JLabel("Theme:"), c);
        c.gridx = 1;
        c.insets = new Insets(0, 0, 0, 0);
        page.add(themeCombo, c);

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

    /** Installs and persists the selected theme, restyling every open window (this dialog included). */
    private void applySelection() {
        Theme selected = (Theme) themeCombo.getSelectedItem();
        if (selected == null || selected == Theme.current()) {
            return;
        }
        Theme.apply(selected);
        Theme.save(selected);
        for (Window window : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
        }
        onThemeApplied.run();
        MainWindow.standardizeFonts(getRootPane()); // updateComponentTreeUI reset this dialog's fonts
        applyButton.setEnabled(false);
    }
}
