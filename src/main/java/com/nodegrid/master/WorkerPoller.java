package com.nodegrid.master;

import com.nodegrid.client.NodeClient;
import com.nodegrid.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Periodically pulls {@code /api/node/resources} from every known worker, in parallel.
 * The interval is re-read from the settings after every cycle, so changes from the UI apply without restart.
 */
@Component
@ConditionalOnProperty(prefix = "nodegrid", name = "role", havingValue = "master", matchIfMissing = true)
public class WorkerPoller {

    private static final Logger log = LoggerFactory.getLogger(WorkerPoller.class);

    private final WorkerRegistry registry;
    private final NodeClient client;
    private final SettingsService settings;
    private final TaskScheduler scheduler;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile Instant lastCycle;

    public WorkerPoller(WorkerRegistry registry, NodeClient client, SettingsService settings, TaskScheduler scheduler) {
        this.registry = registry;
        this.client = client;
        this.settings = settings;
        this.scheduler = scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        scheduler.schedule(this::pollAll, ctx -> {
            Instant last = ctx.lastCompletion();
            return last == null
                    ? Instant.now().plusSeconds(1)
                    : last.plusSeconds(settings.get().pollIntervalSeconds());
        });
        log.info("Worker polling started (every {}s)", settings.get().pollIntervalSeconds());
    }

    public void pollAll() {
        var futures = registry.nodes().stream()
                .map(node -> CompletableFuture.runAsync(() -> poll(node), executor))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(futures).join();
        lastCycle = Instant.now();
    }

    public WorkerView pollNow(String id) {
        WorkerNode node = registry.node(id);
        poll(node);
        return node.view();
    }

    public Instant lastCycle() {
        return lastCycle;
    }

    private void poll(WorkerNode node) {
        try {
            var snapshot = client.fetchResources(node.url());
            node.pollSucceeded(snapshot, Instant.now());
        } catch (Exception e) {
            String msg = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            node.pollFailed(msg, Instant.now(), settings.get().offlineAfterFailures());
            log.debug("Poll of worker {} at {} failed: {}", node.id(), node.url(), msg);
        }
    }
}
