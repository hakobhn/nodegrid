package com.nodegrid.master;

import com.nodegrid.node.ResourceSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Master-side, mutable state of one worker. All mutation goes through synchronized methods;
 * readers use {@link #view} to get an immutable copy. Status is derived from how long ago the worker last reported.
 */
class WorkerNode {

    /** Extra slack on top of the heartbeat interval before a report counts as missed. */
    private static final long GRACE_SECONDS = 5;

    private final String id;
    private final Instant addedAt;
    private String name;
    private String url;
    private Instant lastHeartbeat;
    private int heartbeatIntervalSeconds;
    private ResourceSnapshot resources;
    private List<String> commandTypes = List.of();

    WorkerNode(String id, String name, String url, Instant addedAt) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.addedAt = addedAt;
    }

    String id() {
        return id;
    }

    synchronized void reported(RegistrationRequest r, String url, Instant at) {
        this.name = r.nodeName();
        this.url = url;
        this.heartbeatIntervalSeconds = Math.max(1, r.heartbeatIntervalSeconds());
        this.commandTypes = r.commandTypes() == null ? List.of() : List.copyOf(r.commandTypes());
        if (r.resources() != null) {
            this.resources = r.resources();
        }
        this.lastHeartbeat = at;
    }

    synchronized WorkerView view(Instant now, int offlineAfterMissed) {
        WorkerStatus status;
        int missed = 0;
        String error = null;
        if (lastHeartbeat == null) {
            status = WorkerStatus.UNKNOWN;
            error = "Has not reported since the master started";
        } else {
            long age = Duration.between(lastHeartbeat, now).toSeconds();
            if (age <= heartbeatIntervalSeconds + GRACE_SECONDS) {
                status = WorkerStatus.ONLINE;
            } else {
                missed = (int) Math.max(1, (age - GRACE_SECONDS) / heartbeatIntervalSeconds);
                status = missed >= offlineAfterMissed ? WorkerStatus.OFFLINE : WorkerStatus.UNREACHABLE;
                error = "No heartbeat for " + age + "s";
            }
        }
        return new WorkerView(id, name, url, status, addedAt, lastHeartbeat, heartbeatIntervalSeconds, missed, error,
                commandTypes, resources);
    }

    synchronized PersistedWorker persisted() {
        return new PersistedWorker(id, name, url, addedAt);
    }

    record PersistedWorker(String id, String name, String url, Instant addedAt) {
    }
}
