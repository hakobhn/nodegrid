package com.nodegrid.master;

import com.nodegrid.node.ResourceSnapshot;

import java.time.Instant;

/**
 * Master-side, mutable state of one worker. All mutation goes through synchronized methods;
 * readers use {@link #view()} to get an immutable copy.
 */
class WorkerNode {

    enum Source { REGISTERED, MANUAL }

    private final String id;
    private final Instant addedAt;
    private String name;
    private String url;
    private Source source;
    private WorkerStatus status = WorkerStatus.UNKNOWN;
    private Instant lastSeen;
    private Instant lastPollAt;
    private Instant lastHeartbeat;
    private String lastError;
    private int consecutiveFailures;
    private ResourceSnapshot resources;

    WorkerNode(String id, String name, String url, Source source, Instant addedAt) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.source = source;
        this.addedAt = addedAt;
    }

    String id() {
        return id;
    }

    synchronized String url() {
        return url;
    }

    synchronized void updateIdentity(String name, String url, Source source) {
        this.name = name;
        this.url = url;
        if (source == Source.MANUAL) {
            this.source = Source.MANUAL;
        }
    }

    synchronized void heartbeat(Instant at) {
        this.lastHeartbeat = at;
    }

    synchronized void pollSucceeded(ResourceSnapshot snapshot, Instant at) {
        this.resources = snapshot;
        this.lastPollAt = at;
        this.lastSeen = at;
        this.lastError = null;
        this.consecutiveFailures = 0;
        this.status = WorkerStatus.ONLINE;
    }

    synchronized void pollFailed(String error, Instant at, int offlineAfterFailures) {
        this.lastPollAt = at;
        this.lastError = error;
        this.consecutiveFailures++;
        this.status = consecutiveFailures >= offlineAfterFailures ? WorkerStatus.OFFLINE : WorkerStatus.UNREACHABLE;
    }

    synchronized WorkerView view() {
        return new WorkerView(id, name, url, source.name(), status, addedAt, lastSeen, lastPollAt, lastHeartbeat,
                lastError, consecutiveFailures, resources);
    }

    synchronized PersistedWorker persisted() {
        return new PersistedWorker(id, name, url, source, addedAt);
    }

    record PersistedWorker(String id, String name, String url, Source source, Instant addedAt) {
    }
}
