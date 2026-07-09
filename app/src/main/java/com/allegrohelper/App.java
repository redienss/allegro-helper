package com.allegrohelper;

import com.allegrohelper.cli.Cli;
import com.allegrohelper.ui.MainWindow;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.lang.reflect.Field;
import java.nio.file.Path;

/**
 * Entry point for Allegro Helper.
 *
 * <p>With no arguments (or a directory argument) it launches the Swing desktop
 * UI. With {@code --cli <step> [baseDir]} it runs the pipeline headlessly,
 * mirroring the Python {@code main.py}.
 */
public final class App {

    /** WM class advertised on X11 so a .desktop launcher's StartupWMClass can match this window. */
    public static final String WM_CLASS = "AllegroHelper";

    private App() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--cli")) {
            String[] rest = new String[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            System.exit(Cli.run(rest));
        }

        setLinuxWmClass(WM_CLASS);

        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("No display available. Run headlessly with:");
            System.err.println("  allegro-helper --cli <import|match|retouch|describe|all> [baseDir]");
            System.exit(2);
        }

        Path baseDir = args.length > 0 ? Path.of(args[0]) : Path.of(System.getProperty("user.dir"));
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // Fall back to the default look and feel.
            }
            new MainWindow(baseDir).show();
        });
    }

    /**
     * On Linux/X11 the window's WM_CLASS defaults to the main class name, which
     * prevents a desktop launcher from matching the running window (so the dock
     * shows a generic icon). Override it to {@link #WM_CLASS}. Best-effort: this
     * reflects into the X11 toolkit and needs
     * {@code --add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED} (see run.sh); it
     * is a no-op on other platforms/toolkits.
     */
    private static void setLinuxWmClass(String name) {
        try {
            Toolkit toolkit = Toolkit.getDefaultToolkit();
            Field field = toolkit.getClass().getDeclaredField("awtAppClassName");
            field.setAccessible(true);
            field.set(null, name);
        } catch (Throwable ignored) {
            // Not X11, or reflective access denied: fall back to the default WM class.
        }
    }
}
