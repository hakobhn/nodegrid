package com.nodegrid.master;

public enum WorkerStatus {
    /** Known (e.g. loaded from disk) but has not reported since the master started. */
    UNKNOWN,
    /** Reported within its heartbeat interval. */
    ONLINE,
    /** Missed at least one heartbeat, but fewer than the offline threshold. */
    UNREACHABLE,
    /** Missed heartbeats reached the offline threshold. */
    OFFLINE
}
