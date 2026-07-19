package com.allegrohelper.ui;

import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import java.awt.Rectangle;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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
final class Gallery {
    private final DefaultListModel<Object> model = new DefaultListModel<>();
    private final JList<Object> list = new JList<>(model);
    private final JScrollPane scroll = new JScrollPane(list);
    private final AtomicInteger token = new AtomicInteger();
    /** Files behind the thumbnails, kept index-aligned with {@link #model}. */
    private final List<Path> loadedFiles = new ArrayList<>();
    private final int thumbSize;
    /** When > 0, the first this-many thumbnails are selected after each load. */
    private final int preselect;
    /**
     * Shared by every gallery, and deliberately so: one single-threaded
     * executor means a newer offer's thumbnails queue behind the older one's,
     * which is the ordering {@link #token} is written to guard.
     */
    private final ExecutorService loader;
    /** What a double-clicked thumbnail does — the window logs and opens it. */
    private final Consumer<Path> onOpen;

    Gallery(int thumbSize, int preselect, ExecutorService loader, Consumer<Path> onOpen) {
        this.thumbSize = thumbSize;
        this.preselect = preselect;
        this.loader = loader;
        this.onOpen = onOpen;
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
                    onOpen.accept(loadedFiles.get(index));
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
            model.addElement(I18n.t("Not available yet."));
            return;
        }
        List<Path> files;
        try (var stream = Files.list(dir)) {
            files = stream.filter(OfferFiles::isJpeg)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            model.addElement(I18n.t("Could not read {0}: {1}", dir, e.getMessage()));
            return;
        }
        if (files.isEmpty()) {
            model.addElement(I18n.t("No photos."));
            return;
        }
        model.addElement(I18n.t("Loading {0} thumbnails…", files.size()));
        loader.submit(() -> {
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
