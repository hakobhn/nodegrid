package com.nodegrid.master;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nodegrid.client.NodeClient;
import com.nodegrid.config.NodeRole;
import com.nodegrid.node.NodeInfo;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The master's list of workers. Workers join either by registering themselves (heartbeat) or by being
 * added manually by URL from the UI. The list is persisted so manually added workers survive restarts.
 */
@Service
@ConditionalOnProperty(prefix = "nodegrid", name = "role", havingValue = "master", matchIfMissing = true)
public class WorkerRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkerRegistry.class);

    private final Map<String, WorkerNode> workers = new ConcurrentHashMap<>();
    private final NodeClient client;
    private final ObjectMapper mapper;
    private final Path file;

    public WorkerRegistry(NodeClient client, ObjectMapper mapper, SettingsService settings) {
        this.client = client;
        this.mapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.file = settings.dataDir().resolve("workers.json");
        load();
    }

    public void register(RegistrationRequest request) {
        String url = normalizeUrl(request.url());
        WorkerNode node = workers.compute(request.nodeId(), (id, existing) -> {
            if (existing == null) {
                log.info("Worker registered: {} ({}) at {}", request.nodeName(), id, url);
                return new WorkerNode(id, request.nodeName(), url, WorkerNode.Source.REGISTERED, Instant.now());
            }
            existing.updateIdentity(request.nodeName(), url, WorkerNode.Source.REGISTERED);
            return existing;
        });
        node.heartbeat(Instant.now());
        persist();
    }

    /** Contacts the worker at {@code url}, verifies it is a worker and adds it. */
    public WorkerView addManual(String rawUrl) {
        String url = normalizeUrl(rawUrl);
        NodeInfo info;
        try {
            info = client.fetchInfo(url);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot reach a node at " + url + ": " + e.getMessage());
        }
        if (info == null || info.role() != NodeRole.WORKER) {
            throw new IllegalArgumentException("Node at " + url + " is not a worker");
        }
        WorkerNode node = workers.compute(info.nodeId(), (id, existing) -> {
            if (existing == null) {
                return new WorkerNode(id, info.nodeName(), url, WorkerNode.Source.MANUAL, Instant.now());
            }
            existing.updateIdentity(info.nodeName(), url, WorkerNode.Source.MANUAL);
            return existing;
        });
        log.info("Worker added manually: {} ({}) at {}", info.nodeName(), info.nodeId(), url);
        persist();
        return node.view();
    }

    public void remove(String id) {
        if (workers.remove(id) == null) {
            throw new NoSuchElementException("Unknown worker " + id);
        }
        log.info("Worker removed: {}", id);
        persist();
    }

    public List<WorkerView> list() {
        return workers.values().stream()
                .map(WorkerNode::view)
                .sorted(Comparator.comparing(WorkerView::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public WorkerView get(String id) {
        return node(id).view();
    }

    WorkerNode node(String id) {
        WorkerNode node = workers.get(id);
        if (node == null) {
            throw new NoSuchElementException("Unknown worker " + id);
        }
        return node;
    }

    Collection<WorkerNode> nodes() {
        return List.copyOf(workers.values());
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
            data.forEach(p -> workers.put(p.id(), new WorkerNode(p.id(), p.name(), p.url(), p.source(), p.addedAt())));
            log.info("Loaded {} known worker(s) from {}", workers.size(), file);
        } catch (IOException e) {
            log.warn("Could not read worker list {}: {}", file, e.getMessage());
        }
    }

    private static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Worker URL is required");
        }
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "http://" + u;
        }
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}
