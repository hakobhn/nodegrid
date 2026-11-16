package com.nodegrid.master;

import com.nodegrid.node.ResourceSnapshot;

import java.time.Instant;

/** Read-only view of a worker as returned by the master API. */
public record WorkerView(
        String id,
        String name,
        String url,
        String source,
        WorkerStatus status,
        Instant addedAt,
        Instant lastSeen,
        Instant lastPollAt,
        Instant lastHeartbeat,
        String lastError,
        int consecutiveFailures,
        ResourceSnapshot resources) {
}
