package com.nodegrid.master;

import java.time.Instant;

/** Aggregated numbers over all known workers (resource totals cover ONLINE workers only). */
public record ClusterSummary(
        int totalWorkers,
        int online,
        int unreachable,
        int offline,
        int unknown,
        int totalCores,
        long totalMemoryBytes,
        long freeMemoryBytes,
        double averageCpuLoadPercent,
        Instant lastPollCycle) {
}
