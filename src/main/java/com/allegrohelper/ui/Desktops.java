package com.allegrohelper.ui;

import javax.swing.SwingUtilities;
import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Handing a URL or a file to the desktop environment.
 *
 * <p>Both entry points follow the same shape for the same two reasons:
 * launching a browser or a file manager can block for a noticeable moment, so
 * it happens off the EDT; and the Desktop API is unavailable on a bare X
 * session and some Linux desktops, so each falls back to {@code xdg-open}.
 * Results come back through callbacks rather than a return value because the
 * work is asynchronous — the callbacks are invoked on the EDT, so they may
 * touch Swing directly.
 */
final class Desktops {

    /** Not instantiable: the class is a namespace for its static helpers. */
    private Desktops() {
    }

    /**
     * Opens a URL in the user's default browser, falling back to {@code xdg-open}
     * where the Desktop API is unavailable (a bare X session, some Linux
     * desktops). Runs off the EDT, since launching a browser can block; both
     * callbacks are invoked back on the EDT.
     *
     * @param onOpened  run once the browser was launched
     * @param onFailure given the failure message
     */
    static void browse(String url, Runnable onOpened, Consumer<String> onFailure) {
        new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI.create(url));
                } else {
                    new ProcessBuilder("xdg-open", url).start();
                }
                SwingUtilities.invokeLater(onOpened);
            } catch (Exception e) {
                String message = e.getMessage();
                SwingUtilities.invokeLater(() -> onFailure.accept(message));
            }
        }, "open-url").start();
    }

    /**
     * Opens a file or directory with the system default handler (the image viewer
     * or file manager). Launching it can block briefly, so it runs off the EDT.
     *
     * @param onOpened  given the opened path, once the handler was launched
     * @param onFailure given the failure message
     */
    static void open(Path target, Consumer<Path> onOpened, Consumer<String> onFailure) {
        new Thread(() -> {
            try {
                if (Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(target.toFile());
                } else {
                    new ProcessBuilder("xdg-open", target.toString()).start();
                }
                SwingUtilities.invokeLater(() -> onOpened.accept(target));
            } catch (Exception e) {
                String message = e.getMessage();
                SwingUtilities.invokeLater(() -> onFailure.accept(message));
            }
        }, "open-in-system").start();
    }
}
