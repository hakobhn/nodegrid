package com.nodegrid.master;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nodegrid.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The master's list of workers. Workers join by reporting to the master (they are configured with the master's
 * URL); the master never connects to a worker. The list is persisted so known workers are shown after a restart.
 */
@Service
@ConditionalOnProperty(prefix = "nodegrid", name = "role", havingValue = "master", matchIfMissing = true)
public class WorkerRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkerRegistry.class);

    private final Map<String, WorkerNode> workers = new ConcurrentHashMap<>();
    private final SettingsService settings;
    private final ObjectMapper mapper;
    private final Path file;

    public WorkerRegistry(ObjectMapper mapper, SettingsService settings) {
        this.settings = settings;
        this.mapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.file = settings.dataDir().resolve("workers.json");
        load();
    }

    /** Records a heartbeat (with resources) from a worker. */
    public void report(RegistrationRequest request) {
        String url = request.url() == null ? "" : stripSlash(request.url().trim());
        Instant now = Instant.now();
        boolean[] changed = {false};
        WorkerNode node = workers.compute(request.nodeId(), (id, existing) -> {
            if (existing == null) {
                log.info("Worker registered: {} ({})", request.nodeName(), id);
                changed[0] = true;
                return new WorkerNode(id, request.nodeName(), url, now);
            }
            var before = existing.persisted();
            changed[0] = !Objects.equals(before.name(), request.nodeName()) || !Objects.equals(before.url(), url);
            return existing;
        });
        node.reported(request, url, now);
        if (changed[0]) {
            persist();
        }
    }

    public void remove(String id) {
        if (workers.remove(id) == null) {
            throw new NoSuchElementException("Unknown worker " + id);
        }
        log.info("Worker removed: {}", id);
        persist();
    }

    public List<WorkerView> list() {
        Instant now = Instant.now();
        int offlineAfter = settings.get().offlineAfterFailures();
        return workers.values().stream()
                .map(w -> w.view(now, offlineAfter))
                .sorted(Comparator.comparing(WorkerView::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public WorkerView get(String id) {
        WorkerNode node = workers.get(id);
        if (node == null) {
            throw new NoSuchElementException("Unknown worker " + id);
        }
        return node.view(Instant.now(), settings.get().offlineAfterFailures());
    }

    private synchronized void persist() {
        List<WorkerNode.PersistedWorker> data = new ArrayList<>();
        workers.values().forEach(w -> data.add(w.persisted()));
        try {
            Files.createDirectories(file.getParent());
            mapper.writeValue(file.toFile(), data);
        } catch (IOException e) {
            log.warn("Could not save worker list to {}: {}", file, e.getMessage());
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<WorkerNode.PersistedWorker> data = mapper.readValue(file.toFile(), new TypeReference<>() { });
            data.forEach(p -> workers.put(p.id(), new WorkerNode(p.id(), p.name(), p.url(), p.addedAt())));
            log.info("Loaded {} known worker(s) from {}", workers.size(), file);
        } catch (IOException e) {
            log.warn("Could not read worker list {}: {}", file, e.getMessage());
        }
    }

    private static String stripSlash(String u) {
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}
