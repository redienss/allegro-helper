package com.allegrohelper.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the enabled pipeline steps in sequence (import → match → retouch →
 * auto-crop → describe), emitting a section header per step and reporting overall progress
 * as {@code (completedSteps + currentStepFraction) / totalSteps}.
 */
public final class Workflow {

    /** The pipeline steps, in execution order. */
    public enum Step {
        IMPORT("import"),
        MATCH("match"),
        RETOUCH("retouch"),
        AUTOCROP("auto-crop"),
        DESCRIBE("describe");

        public final String label;

        Step(String label) {
            this.label = label;
        }
    }

    /** Receives log output and overall (0..1) progress for the whole run. */
    public interface Listener {
        void log(String line);

        void overallProgress(double fraction);

        /** Called once the run finishes, whether it succeeded or aborted. */
        void finished(boolean success);
    }

    private Workflow() {
    }

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

    private static void runStep(Config cfg, Step step, Reporter reporter)
            throws IOException, PipelineException {
        switch (step) {
            case IMPORT -> ImportPhotos.run(cfg, reporter);
            case MATCH -> GroupAndMatch.run(cfg, reporter);
            case RETOUCH -> Retouch.runAll(cfg, reporter);
            case AUTOCROP -> AutoCrop.runAll(cfg, reporter);
            case DESCRIBE -> GenerateDescription.runAll(cfg, reporter);
        }
    }
}
