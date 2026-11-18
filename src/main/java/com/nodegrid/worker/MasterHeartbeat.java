package com.nodegrid.worker;

import com.nodegrid.client.NodeClient;
import com.nodegrid.master.RegistrationRequest;
import com.nodegrid.node.NodeInfo;
import com.nodegrid.node.ResourceCollector;
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
 * Worker side: reports to the master on startup and on every heartbeat, including the current resource snapshot.
 * Together with {@link CommandRunner}'s long-poll, all traffic is worker → master, so only the master has to be
 * reachable.
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

    private final ResourceCollector resources;
    private final CommandRunner commands;

    public MasterHeartbeat(NodeClient client, SettingsService settings, TaskScheduler scheduler,
                           ResourceCollector resources, CommandRunner commands) {
        this.client = client;
        this.settings = settings;
        this.scheduler = scheduler;
        this.resources = resources;
        this.commands = commands;
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
                    new RegistrationRequest(settings.nodeId(), s.nodeName(), settings.effectiveAdvertisedUrl(),
                            s.heartbeatIntervalSeconds(), resources.collect(), commands.commandTypes()));
            lastSuccess = lastAttempt;
            lastError = null;
            if (!connected) {
                log.info("Registered with master at {}", s.masterUrl());
                connected = true;
            }
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "")
                    + hint(e);
            if (connected) {
                log.warn("Lost connection to master at {}: {}", s.masterUrl(), lastError);
                connected = false;
            } else {
                log.debug("Master at {} not reachable: {}", s.masterUrl(), lastError);
            }
        }
    }

    /** Plain-language explanation for the common network failures. */
    static String hint(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException || t instanceof java.net.NoRouteToHostException
                    || t instanceof java.net.http.HttpConnectTimeoutException) {
                return " — the master did not answer. Check that the master URL is the address shown on the "
                        + "master's Overview page, that both machines are on the same network, and that the "
                        + "master's firewall allows inbound TCP on its port.";
            }
            if (t instanceof java.net.ConnectException) {
                return " — the master machine is reachable but nothing is listening on that port. "
                        + "Check the port and that the master is running.";
            }
            if (t instanceof java.net.UnknownHostException) {
                return " — the master host name could not be resolved. Use its IP address instead.";
            }
        }
        return "";
    }
}
