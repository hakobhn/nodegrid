package com.nodegrid.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nodegrid.client.NodeClient;
import com.nodegrid.command.Command;
import com.nodegrid.command.CommandHandler;
import com.nodegrid.command.CommandResult;
import com.nodegrid.settings.SettingsService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker side of master → worker requests. Keeps a long-poll open to the master; every command it receives is run
 * by the matching {@link CommandHandler} on its own virtual thread and the result is posted back to the master.
 * The worker opens all connections, so the master never needs to reach the worker.
 */
@Component
@ConditionalOnProperty(prefix = "nodegrid", name = "role", havingValue = "worker")
public class CommandRunner {

    private static final Logger log = LoggerFactory.getLogger(CommandRunner.class);
    private static final long RETRY_DELAY_MS = 5_000;

    private final NodeClient client;
    private final SettingsService settings;
    private final ObjectMapper mapper;
    private final Map<String, CommandHandler> handlers = new TreeMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicLong executed = new AtomicLong();

    private volatile boolean running;
    private volatile Thread loop;
    private volatile Instant lastCommandAt;

    public CommandRunner(NodeClient client, SettingsService settings, ObjectMapper mapper,
                         List<CommandHandler> handlerBeans) {
        this.client = client;
        this.settings = settings;
        this.mapper = mapper;
        for (CommandHandler h : handlerBeans) {
            CommandHandler previous = handlers.put(h.type(), h);
            if (previous != null) {
                throw new IllegalStateException("Two handlers for command type '" + h.type() + "'");
            }
        }
    }

    /** Command types this worker can execute; reported to the master with every heartbeat. */
    public List<String> commandTypes() {
        return List.copyOf(handlers.keySet());
    }

    public long executedCount() {
        return executed.get();
    }

    public Instant lastCommandAt() {
        return lastCommandAt;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        loop = Thread.ofVirtual().name("command-long-poll").start(this::pollLoop);
        log.info("Command runner started, handlers: {}", handlers.keySet());
    }

    @PreDestroy
    public void stop() {
        running = false;
        Thread t = loop;
        if (t != null) {
            t.interrupt();
        }
        executor.shutdownNow();
    }

    private void pollLoop() {
        while (running) {
            String master = settings.get().masterUrl();
            if (master == null || master.isBlank()) {
                sleep(RETRY_DELAY_MS);
                continue;
            }
            try {
                for (Command c : client.nextCommands(master, settings.nodeId())) {
                    executor.submit(() -> run(master, c));
                }
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                // Heartbeat already reports connection problems in the UI; just back off here.
                log.debug("Command long-poll to {} failed: {}", master, e.toString());
                sleep(RETRY_DELAY_MS);
            }
        }
    }

    private void run(String master, Command c) {
        lastCommandAt = Instant.now();
        CommandResult result;
        CommandHandler handler = handlers.get(c.type());
        if (handler == null) {
            result = new CommandResult(false, null, "Unsupported command type '" + c.type() + "'");
        } else {
            try {
                Object out = handler.handle(c.payload());
                result = new CommandResult(true, mapper.valueToTree(out), null);
            } catch (Exception e) {
                log.warn("Command {} ({}) failed: {}", c.id(), c.type(), e.toString());
                result = new CommandResult(false, null,
                        e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            }
        }
        executed.incrementAndGet();
        report(master, c, result);
    }

    /** Posts the result, retrying a few times so a brief network blip doesn't lose it. */
    private void report(String master, Command c, CommandResult result) {
        for (int attempt = 1; attempt <= 5 && running; attempt++) {
            try {
                client.reportResult(master, settings.nodeId(), c.id(), result);
                return;
            } catch (Exception e) {
                log.debug("Reporting result of {} failed (attempt {}): {}", c.id(), attempt, e.toString());
                sleep(RETRY_DELAY_MS);
            }
        }
        log.warn("Could not report result of command {} ({}) to {}", c.id(), c.type(), master);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
