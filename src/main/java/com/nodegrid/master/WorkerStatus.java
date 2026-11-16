package com.nodegrid.master;

public enum WorkerStatus {
    /** Known but not polled yet. */
    UNKNOWN,
    /** Last poll succeeded. */
    ONLINE,
    /** Recent polls failed, but fewer than the offline threshold. */
    UNREACHABLE,
    /** Failed polls reached the offline threshold. */
    OFFLINE
}
