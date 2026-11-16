package com.nodegrid.node;

import com.nodegrid.config.NodeRole;

import java.time.Instant;

/**
 * Identity of a node, as exposed by {@code GET /api/node/info}.
 *
 * @param masterLink only set on worker nodes – state of the connection to the master
 */
public record NodeInfo(
        String nodeId,
        NodeRole role,
        String nodeName,
        String advertisedUrl,
        String version,
        Instant startedAt,
        MasterLink masterLink) {

    public record MasterLink(String masterUrl, Instant lastSuccess, Instant lastAttempt, String lastError) {
    }
}
