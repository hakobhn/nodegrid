package com.nodegrid.command;

/** Lifecycle of a command sent from the master to a worker. */
public enum CommandStatus {
    /** Waiting for the worker to pick it up. */
    QUEUED,
    /** Handed to the worker, result not reported yet. */
    DELIVERED,
    SUCCEEDED,
    FAILED,
    /** The worker did not report a result within the command's timeout. */
    TIMED_OUT;

    public boolean finished() {
        return this == SUCCEEDED || this == FAILED || this == TIMED_OUT;
    }
}
