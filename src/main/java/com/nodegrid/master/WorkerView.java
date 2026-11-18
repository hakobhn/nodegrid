package com.nodegrid.master;

import com.nodegrid.node.ResourceSnapshot;

import java.time.Instant;
import java.util.List;

/** Read-only view of a worker as returned by the master API. */
public record WorkerView(
        String id,
        String name,
        String url,
        WorkerStatus status,
        Instant addedAt,
        Instant lastHeartbeat,
        int heartbeatIntervalSeconds,
        int missedHeartbeats,
        String lastError,
        List<String> commandTypes,
        ResourceSnapshot resources) {
}
