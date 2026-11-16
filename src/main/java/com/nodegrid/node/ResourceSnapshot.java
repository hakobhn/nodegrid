package com.nodegrid.node;

import java.time.Instant;
import java.util.List;

/**
 * Point-in-time view of a node's hardware and JVM resources.
 * CPU values are percentages (0-100) or -1 when the platform can't report them.
 */
public record ResourceSnapshot(
        Instant timestamp,
        String hostname,
        String osName,
        String osVersion,
        String osArch,
        String javaVersion,
        int availableProcessors,
        double systemCpuLoadPercent,
        double processCpuLoadPercent,
        double systemLoadAverage,
        long totalMemoryBytes,
        long freeMemoryBytes,
        long totalSwapBytes,
        long freeSwapBytes,
        long jvmHeapUsedBytes,
        long jvmHeapMaxBytes,
        long jvmUptimeMillis,
        List<DiskInfo> disks) {

    public record DiskInfo(String path, long totalBytes, long usableBytes) {
    }
}
