package com.allegrohelper.core;

/**
 * Sink for a pipeline step's output: log lines and a 0..1 progress fraction
 * that reflects how far the current step has advanced.
 */
public interface Reporter {

    void log(String line);

    /** Reports progress within the current step, from 0.0 to 1.0. */
    void stepProgress(double fraction);

    /** A reporter that prints log lines to stdout and ignores progress. */
    static Reporter stdout() {
        return new Reporter() {
            @Override
            public void log(String line) {
                System.out.println(line);
            }

            @Override
            public void stepProgress(double fraction) {
                // no-op
            }
        };
    }
}
