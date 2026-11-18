package com.nodegrid.master;

import com.fasterxml.jackson.databind.JsonNode;
import com.nodegrid.command.Command;
import com.nodegrid.command.CommandResult;
import com.nodegrid.command.CommandStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Master → worker requests without the master ever connecting to a worker.
 *
 * <p>The master queues commands per worker. Each worker keeps a long-poll open to
 * {@code GET /api/master/workers/{id}/commands/next}; queued commands are handed out on that request as soon as
 * they arrive, and the worker posts the outcome to {@code .../commands/{commandId}/result}.
 * Only the master has to be reachable, exactly like the heartbeat.
 */
@Service
@ConditionalOnProperty(prefix = "nodegrid", name = "role", havingValue = "master", matchIfMissing = true)
public class CommandQueue {

    private static final Logger log = LoggerFactory.getLogger(CommandQueue.class);
    private static final int HISTORY_PER_WORKER = 100;
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;

    private final WorkerRegistry registry;
    private final Map<String, BlockingQueue<Entry>> pending = new ConcurrentHashMap<>();
    private final Map<String, Entry> commands = new ConcurrentHashMap<>();

    public CommandQueue(WorkerRegistry registry) {
        this.registry = registry;
    }

    /** Snapshot of one command as shown by the master API. */
    public record CommandView(String id, String workerId, String type, JsonNode payload, CommandStatus status,
                              int timeoutSeconds, Instant createdAt, Instant deliveredAt, Instant completedAt,
                              JsonNode result, String error) {
    }

    private static final class Entry {
        final String id = UUID.randomUUID().toString();
        final String workerId;
        final String type;
        final JsonNode payload;
        final int timeoutSeconds;
        final Instant createdAt = Instant.now();
        CommandStatus status = CommandStatus.QUEUED;
        Instant deliveredAt;
        Instant completedAt;
        JsonNode result;
        String error;

        Entry(String workerId, String type, JsonNode payload, int timeoutSeconds) {
            this.workerId = workerId;
            this.type = type;
            this.payload = payload;
            this.timeoutSeconds = timeoutSeconds;
        }

        synchronized CommandView view(Instant now) {
            Instant from = deliveredAt != null ? deliveredAt : createdAt;
            if (!status.finished() && Duration.between(from, now).toSeconds() > timeoutSeconds) {
                error = status == CommandStatus.QUEUED ? "Worker did not pick up the command" : "No result from worker";
                status = CommandStatus.TIMED_OUT;
                completedAt = now;
            }
            return new CommandView(id, workerId, type, payload, status, timeoutSeconds, createdAt, deliveredAt,
                    completedAt, result, error);
        }
    }

    /** Queues a command for a worker. Fails if the worker is unknown or doesn't support the type. */
    public CommandView submit(String workerId, String type, JsonNode payload, Integer timeoutSeconds) {
        WorkerView worker = registry.get(workerId);
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Command type is required");
        }
        if (!worker.commandTypes().isEmpty() && !worker.commandTypes().contains(type)) {
            throw new IllegalArgumentException("Worker " + worker.name() + " does not support command '" + type
                    + "'. Supported: " + String.join(", ", worker.commandTypes()));
        }
        int timeout = timeoutSeconds == null || timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        Entry e = new Entry(workerId, type.trim(), payload, timeout);
        commands.put(e.id, e);
        queue(workerId).add(e);
        trimHistory(workerId);
        log.info("Command {} ({}) queued for worker {}", e.id, e.type, worker.name());
        return e.view(Instant.now());
    }

    /**
     * Called by the worker's long-poll: waits up to {@code wait} for at least one command, then returns everything
     * queued for that worker and marks it DELIVERED. Returns an empty list on timeout.
     */
    public List<Command> next(String workerId, Duration wait) throws InterruptedException {
        BlockingQueue<Entry> q = queue(workerId);
        List<Entry> taken = new ArrayList<>();
        Entry first = q.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
        if (first != null) {
            taken.add(first);
            q.drainTo(taken);
        }
        Instant now = Instant.now();
        List<Command> out = new ArrayList<>();
        for (Entry e : taken) {
            synchronized (e) {
                if (e.status != CommandStatus.QUEUED) {
                    continue; // timed out while waiting in the queue
                }
                e.status = CommandStatus.DELIVERED;
                e.deliveredAt = now;
            }
            out.add(new Command(e.id, e.type, e.payload));
        }
        return out;
    }

    /** Called by the worker with the outcome of a command. */
    public void complete(String workerId, String commandId, CommandResult result) {
        Entry e = commands.get(commandId);
        if (e == null || !e.workerId.equals(workerId)) {
            throw new NoSuchElementException("Unknown command " + commandId);
        }
        synchronized (e) {
            if (e.status.finished() && e.status != CommandStatus.TIMED_OUT) {
                return; // duplicate report
            }
            e.status = result.success() ? CommandStatus.SUCCEEDED : CommandStatus.FAILED;
            e.completedAt = Instant.now();
            e.result = result.result();
            e.error = result.error();
        }
        log.info("Command {} ({}) on worker {}: {}", e.id, e.type, workerId, e.status);
    }

    public CommandView get(String commandId) {
        Entry e = commands.get(commandId);
        if (e == null) {
            throw new NoSuchElementException("Unknown command " + commandId);
        }
        return e.view(Instant.now());
    }

    /** Recent commands of one worker, newest first. */
    public List<CommandView> list(String workerId) {
        Instant now = Instant.now();
        return commands.values().stream()
                .filter(e -> e.workerId.equals(workerId))
                .map(e -> e.view(now))
                .sorted(Comparator.comparing(CommandView::createdAt).reversed())
                .toList();
    }

    /** Drops everything belonging to a removed worker. */
    public void forget(String workerId) {
        pending.remove(workerId);
        commands.values().removeIf(e -> e.workerId.equals(workerId));
    }

    private BlockingQueue<Entry> queue(String workerId) {
        return pending.computeIfAbsent(workerId, id -> new LinkedBlockingQueue<>());
    }

    private void trimHistory(String workerId) {
        List<Entry> mine = commands.values().stream()
                .filter(e -> e.workerId.equals(workerId))
                .sorted(Comparator.comparing((Entry e) -> e.createdAt).reversed())
                .toList();
        mine.stream().skip(HISTORY_PER_WORKER).filter(e -> {
            synchronized (e) {
                return e.status.finished();
            }
        }).forEach(e -> commands.remove(e.id));
    }
}
