package com.allegrohelper.core;

/**
 * Signals a condition that aborts the current step (and the rest of the run),
 * such as a mismatch between photo series and CSV rows. The message is
 * user-facing.
 */
public class PipelineException extends Exception {

    public PipelineException(String message) {
        super(message);
    }
}
