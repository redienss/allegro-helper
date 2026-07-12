package com.allegrohelper.cli;

import com.allegrohelper.core.Config;
import com.allegrohelper.core.Reporter;
import com.allegrohelper.core.Workflow;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Headless command-line entry point:
 * {@code import | match | whitebalance | brightness | contrast | autocrop | ocr | describe | all}
 * ({@code retouch} is an alias for the three retouching steps). Useful for
 * scripting and for running the pipeline without a display. The brightness and
 * contrast strengths are the {@code BRIGHTNESS_STRENGTH} / {@code CONTRAST_STRENGTH}
 * config keys — the sliders that set them live in the UI, and headless runs have
 * no other way to reach them.
 */
public final class Cli {

    /** Not instantiable: the class is a namespace for {@link #run}. */
    private Cli() {
    }

    /**
     * Parses the step name, builds the config from the base directory and runs
     * the selected steps, printing the log to stdout.
     *
     * @param args {@code <step> [baseDir]}; the base directory defaults to the
     *             working directory
     * @return the process exit code: 0 on success, 1 if the run aborted,
     *         2 on a usage error (missing or unknown step)
     */
    public static int run(String[] args) {
        if (args.length == 0) {
            System.err.println(
                    "Usage: allegro-helper --cli "
                    + "<import|match|whitebalance|brightness|contrast|autocrop|ocr|describe|all> [baseDir]\n"
                    + "       (retouch = whitebalance + brightness + contrast)");
            return 2;
        }
        String step = args[0].toLowerCase();
        Path baseDir = args.length > 1 ? Path.of(args[1]) : Path.of(System.getProperty("user.dir"));
        Config cfg = Config.forBaseDir(baseDir);

        List<Workflow.Step> steps = switch (step) {
            case "import" -> List.of(Workflow.Step.IMPORT);
            case "match" -> List.of(Workflow.Step.MATCH);
            case "whitebalance", "white-balance", "wb" -> List.of(Workflow.Step.WHITE_BALANCE);
            case "brightness" -> List.of(Workflow.Step.BRIGHTNESS);
            // The autocontrast spellings are what the step was called before it
            // became a strength dial; scripts passing them keep working.
            case "contrast", "autocontrast", "auto-contrast" -> List.of(Workflow.Step.CONTRAST);
            // Pre-split alias: retouch used to be one step doing all the retouching.
            case "retouch" -> List.of(Workflow.Step.WHITE_BALANCE, Workflow.Step.BRIGHTNESS,
                    Workflow.Step.CONTRAST);
            case "autocrop", "auto-crop" -> List.of(Workflow.Step.AUTOCROP);
            case "ocr" -> List.of(Workflow.Step.OCR);
            case "describe" -> List.of(Workflow.Step.DESCRIBE);
            case "all" -> List.of(Workflow.Step.IMPORT, Workflow.Step.MATCH,
                    Workflow.Step.WHITE_BALANCE, Workflow.Step.BRIGHTNESS, Workflow.Step.CONTRAST,
                    Workflow.Step.AUTOCROP, Workflow.Step.OCR, Workflow.Step.DESCRIBE);
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
