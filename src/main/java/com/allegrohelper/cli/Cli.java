package com.allegrohelper.cli;

import com.allegrohelper.core.Config;
import com.allegrohelper.core.Reporter;
import com.allegrohelper.core.Workflow;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Headless command-line entry point:
 * {@code import | match | whitebalance | autocontrast | autocrop | describe | all}
 * ({@code retouch} is an alias for white balance + auto-contrast). Useful for
 * scripting and for running the pipeline without a display.
 */
public final class Cli {

    private Cli() {
    }

    public static int run(String[] args) {
        if (args.length == 0) {
            System.err.println(
                    "Usage: allegro-helper --cli <import|match|whitebalance|autocontrast|autocrop|describe|all> [baseDir]\n"
                    + "       (retouch = whitebalance + autocontrast)");
            return 2;
        }
        String step = args[0].toLowerCase();
        Path baseDir = args.length > 1 ? Path.of(args[1]) : Path.of(System.getProperty("user.dir"));
        Config cfg = Config.forBaseDir(baseDir);

        List<Workflow.Step> steps = switch (step) {
            case "import" -> List.of(Workflow.Step.IMPORT);
            case "match" -> List.of(Workflow.Step.MATCH);
            case "whitebalance", "white-balance", "wb" -> List.of(Workflow.Step.WHITE_BALANCE);
            case "autocontrast", "auto-contrast" -> List.of(Workflow.Step.AUTO_CONTRAST);
            // Pre-split alias: retouch used to be one step doing both.
            case "retouch" -> List.of(Workflow.Step.WHITE_BALANCE, Workflow.Step.AUTO_CONTRAST);
            case "autocrop", "auto-crop" -> List.of(Workflow.Step.AUTOCROP);
            case "describe" -> List.of(Workflow.Step.DESCRIBE);
            case "all" -> List.of(Workflow.Step.IMPORT, Workflow.Step.MATCH,
                    Workflow.Step.WHITE_BALANCE, Workflow.Step.AUTO_CONTRAST,
                    Workflow.Step.AUTOCROP, Workflow.Step.DESCRIBE);
            default -> null;
        };
        if (steps == null) {
            System.err.println("Unknown step: " + step);
            return 2;
        }

        AtomicBoolean ok = new AtomicBoolean(true);
        Workflow.run(cfg, steps, new Workflow.Listener() {
            @Override
            public void log(String line) {
                System.out.println(line);
            }

            @Override
            public void overallProgress(double fraction) {
                // Not shown in CLI mode.
            }

            @Override
            public void finished(boolean success) {
                ok.set(success);
            }
        });
        return ok.get() ? 0 : 1;
    }

    /** Convenience used by tests/self-checks: run a single step with a stdout reporter. */
    public static Reporter stdoutReporter() {
        return Reporter.stdout();
    }
}
