package com.allegrohelper;

import com.allegrohelper.cli.Cli;
import com.allegrohelper.ui.Language;
import com.allegrohelper.core.Config;
import com.allegrohelper.ui.BaseDir;
import com.allegrohelper.ui.MainWindow;
import com.allegrohelper.ui.Theme;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.lang.reflect.Field;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Entry point for Allegro Helper.
 *
 * <p>With no arguments (or a directory argument) it launches the Swing desktop
 * UI. With {@code --cli <step> [baseDir]} it runs the pipeline headlessly.
 */
public final class App {

    /** WM class advertised on X11 so a .desktop launcher's StartupWMClass can match this window. */
    public static final String WM_CLASS = "AllegroHelper";

    /** Not instantiable: the class is a namespace for {@link #main}. */
    private App() {
    }

    /**
     * Dispatches to the CLI or the desktop UI.
     *
     * @param args {@code --cli <step> [baseDir]} for a headless run; otherwise
     *             an optional base directory for the UI, defaulting to the
     *             working directory
     */
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

        // An explicit argument wins; otherwise the base directory saved in
        // File > Settings > Photos, falling back to the working directory.
        Path baseDir = args.length > 0
                ? Path.of(args[0])
                : BaseDir.load(Path.of(System.getProperty("user.dir")));
        // First run after settings moved out of the working directory: seed them
        // from the base directory's .env so an existing install keeps its API
        // key and prompts. Copies, never moves — see Config.migrateLegacyDotenv.
        Path migratedFrom = null;
        try {
            migratedFrom = Config.migrateLegacyDotenv(baseDir);
        } catch (IOException e) {
            System.err.println("Could not copy " + baseDir.resolve(".env") + " to "
                    + Config.globalEnvPath() + ": " + e.getMessage());
        }

        Path copiedFrom = migratedFrom;
        SwingUtilities.invokeLater(() -> {
            // Saved theme and language (File > Settings), defaulting to the
            // system look and feel and English; best-effort inside apply.
            Theme.apply(Theme.load());
            Language.apply(Language.load());
            MainWindow window = new MainWindow(baseDir);
            window.show();
            if (copiedFrom != null) {
                window.log("Copied settings from " + copiedFrom + " to " + Config.globalEnvPath()
                        + " — settings now live there and survive reinstalling the app.");
            }
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
