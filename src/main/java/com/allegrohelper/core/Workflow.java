package com.allegrohelper.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the enabled pipeline steps in sequence (import → match → white balance →
 * contrast → auto-crop → ocr → describe), emitting a section header per step and
 * reporting overall progress as
 * {@code (completedSteps + currentStepFraction) / totalSteps}.
 */
public final class Workflow {

    /** The pipeline steps, in execution order. */
    public enum Step {
        IMPORT("import"),
        MATCH("match"),
        WHITE_BALANCE("white balance"),
        CONTRAST("contrast"),
        AUTOCROP("auto-crop"),
        OCR("ocr"),
        DESCRIBE("describe");

        /** The step name in the log's {@code == <label> ==} header. */
        public final String label;

        Step(String label) {
            this.label = label;
        }
    }

    /** Receives log output and overall (0..1) progress for the whole run. */
    public interface Listener {
        /** Appends one line to the run log — step headers included. */
        void log(String line);

        /** Reports progress across the whole run, from 0.0 to 1.0. */
        void overallProgress(double fraction);

        /** Called once the run finishes, whether it succeeded or aborted. */
        void finished(boolean success);
    }

    /** Not instantiable: the class is a namespace for {@link #run}. */
    private Workflow() {
    }

    /**
     * Runs {@code steps} in the given order, logging a header per step and
     * scaling each step's own 0..1 progress into the run's overall progress.
     *
     * <p>Aborts on the first failure — a {@link PipelineException} is a step
     * refusing to guess and is reported as {@code Aborted: <message>} — and
     * always ends by calling {@link Listener#finished}. Errors are reported to
     * the listener rather than thrown, since both front ends run this off the
     * UI thread.
     */
    public static void run(Config cfg, List<Step> steps, Listener listener) {
        List<Step> enabled = new ArrayList<>(steps);
        int total = enabled.size();
        if (total == 0) {
            listener.log("No steps selected.");
            listener.overallProgress(1.0);
            listener.finished(true);
            return;
        }

        listener.overallProgress(0.0);
        for (int i = 0; i < total; i++) {
            Step step = enabled.get(i);
            final int stepIndex = i;
            listener.log("== " + step.label + " ==");

            Reporter reporter = new Reporter() {
                @Override
                public void log(String line) {
                    listener.log(line);
                }

                @Override
                public void stepProgress(double fraction) {
                    double clamped = Math.max(0.0, Math.min(1.0, fraction));
                    listener.overallProgress((stepIndex + clamped) / total);
                }
            };

            try {
                runStep(cfg, step, reporter);
            } catch (PipelineException e) {
                listener.log("Aborted: " + e.getMessage());
                listener.finished(false);
                return;
            } catch (IOException e) {
                listener.log("Error during " + step.label + ": " + e.getMessage());
                listener.finished(false);
                return;
            } catch (RuntimeException e) {
                listener.log("Unexpected error during " + step.label + ": " + e);
                listener.finished(false);
                return;
            }
            listener.overallProgress((double) (i + 1) / total);
        }
        listener.finished(true);
    }

    /**
     * Dispatches one step to its implementation. The switch is exhaustive over
     * {@link Step}, so adding an enum constant makes the compiler point here.
     */
    private static void runStep(Config cfg, Step step, Reporter reporter)
            throws IOException, PipelineException {
        switch (step) {
            case IMPORT -> ImportPhotos.run(cfg, reporter);
            case MATCH -> GroupAndMatch.run(cfg, reporter);
            case WHITE_BALANCE -> Retouch.runAll(cfg, Retouch.Mode.WHITE_BALANCE, reporter);
            case CONTRAST -> Retouch.runAll(cfg, Retouch.Mode.CONTRAST, reporter);
            case AUTOCROP -> AutoCrop.runAll(cfg, reporter);
            case OCR -> Ocr.runAll(cfg, reporter);
            case DESCRIBE -> GenerateDescription.runAll(cfg, reporter);
        }
    }
}
