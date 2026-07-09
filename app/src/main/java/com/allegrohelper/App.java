package com.allegrohelper;

import com.allegrohelper.cli.Cli;
import com.allegrohelper.ui.MainWindow;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;

/**
 * Entry point for Allegro Helper.
 *
 * <p>With no arguments (or a directory argument) it launches the Swing desktop
 * UI. With {@code --cli <step> [baseDir]} it runs the pipeline headlessly,
 * mirroring the Python {@code main.py}.
 */
public final class App {

    private App() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--cli")) {
            String[] rest = new String[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            System.exit(Cli.run(rest));
        }

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
}
