package com.nodegrid.worker;

import com.nodegrid.client.NodeClient;
import com.nodegrid.master.RegistrationRequest;
import com.nodegrid.node.NodeInfo;
import com.nodegrid.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Worker side: registers with the master on startup and re-registers on every heartbeat, so the master
 * learns about the worker (and re-learns it after a master restart) without manual configuration.
 */
@Component
@ConditionalOnProperty(prefix = "nodegrid", name = "role", havingValue = "worker")
public class MasterHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(MasterHeartbeat.class);

    private final NodeClient client;
    private final SettingsService settings;
    private final TaskScheduler scheduler;

    private volatile Instant lastSuccess;
    private volatile Instant lastAttempt;
    private volatile String lastError;
    private volatile boolean connected;

    public MasterHeartbeat(NodeClient client, SettingsService settings, TaskScheduler scheduler) {
        this.client = client;
        this.settings = settings;
        this.scheduler = scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        scheduler.schedule(this::beat, ctx -> {
            Instant last = ctx.lastCompletion();
            return last == null ? Instant.now() : last.plusSeconds(settings.get().heartbeatIntervalSeconds());
        });
    }

    /** Called after settings change so a new master URL is picked up immediately. */
    public void triggerNow() {
        scheduler.schedule(this::beat, Instant.now());
    }

    public NodeInfo.MasterLink status() {
        return new NodeInfo.MasterLink(settings.get().masterUrl(), lastSuccess, lastAttempt, lastError);
    }

    synchronized void beat() {
        var s = settings.get();
        if (s.masterUrl() == null || s.masterUrl().isBlank()) {
            lastError = "Master URL is not configured";
            return;
        }
        lastAttempt = Instant.now();
        try {
            client.register(s.masterUrl(),
                    new RegistrationRequest(settings.nodeId(), s.nodeName(), settings.effectiveAdvertisedUrl()));
            lastSuccess = lastAttempt;
            lastError = null;
            if (!connected) {
                log.info("Registered with master at {}", s.masterUrl());
                connected = true;
            }
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            if (connected) {
                log.warn("Lost connection to master at {}: {}", s.masterUrl(), lastError);
                connected = false;
            } else {
                log.debug("Master at {} not reachable: {}", s.masterUrl(), lastError);
            }
        }
    }
}
