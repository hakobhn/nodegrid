package com.nodegrid.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nodegrid.config.NodeProperties;
import com.nodegrid.config.NodeRole;
import com.nodegrid.node.HostAddressResolver;
import com.nodegrid.node.ResourceCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Holds the node identity and the runtime-editable settings. Edits are persisted to
 * {@code <data-dir>/<role>-<port>/node.json}, so they survive restarts and take precedence over application.yml
 * until "reset to defaults" is used.
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final NodeProperties props;
    private final ObjectMapper mapper;
    private final ResourceCollector resources;
    private final int serverPort;
    private final Path dataDir;
    private final Path stateFile;
    private final String nodeId;
    private volatile NodeSettings current;

    record StoredState(String nodeId, NodeSettings settings) {
    }

    public SettingsService(NodeProperties props, ObjectMapper mapper, ResourceCollector resources,
                           @Value("${server.port:8080}") int serverPort) {
        this.props = props;
        this.mapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.resources = resources;
        this.serverPort = serverPort;
        this.dataDir = Path.of(props.dataDir(), props.role().name().toLowerCase() + "-" + serverPort);
        this.stateFile = dataDir.resolve("node.json");

        StoredState stored = load();
        this.nodeId = stored != null && stored.nodeId() != null ? stored.nodeId() : UUID.randomUUID().toString();
        this.current = stored != null && stored.settings() != null ? stored.settings() : defaults();
        save();
        log.info("Node {} started as {} (id={}), data dir {}", current.nodeName(), role(), nodeId, dataDir.toAbsolutePath());
    }

    public String nodeId() {
        return nodeId;
    }

    public NodeRole role() {
        return props.role();
    }

    public Path dataDir() {
        return dataDir;
    }

    public NodeSettings get() {
        return current;
    }

    /** URL other nodes should use to reach this node. */
    public String effectiveAdvertisedUrl() {
        String url = current.advertisedUrl();
        if (url != null && !url.isBlank()) {
            return stripSlash(url.trim());
        }
        return "http://" + HostAddressResolver.resolve() + ":" + serverPort;
    }

    public synchronized NodeSettings update(NodeSettings updated) {
        if (role() == NodeRole.WORKER && (updated.masterUrl() == null || updated.masterUrl().isBlank())) {
            throw new IllegalArgumentException("masterUrl is required for a worker node");
        }
        this.current = new NodeSettings(
                updated.nodeName().trim(),
                blankToEmpty(updated.advertisedUrl()),
                stripSlash(blankToEmpty(updated.masterUrl())),
                updated.heartbeatIntervalSeconds(),
                updated.requestTimeoutSeconds(),
                updated.offlineAfterFailures());
        save();
        log.info("Settings updated: {}", current);
        return current;
    }

    public synchronized NodeSettings resetToDefaults() {
        this.current = defaults();
        save();
        log.info("Settings reset to defaults: {}", current);
        return current;
    }

    private NodeSettings defaults() {
        String name = props.nodeName() == null || props.nodeName().isBlank()
                ? resources.hostname() + ":" + serverPort
                : props.nodeName();
        return new NodeSettings(
                name,
                blankToEmpty(props.advertisedUrl()),
                stripSlash(blankToEmpty(props.masterUrl())),
                props.heartbeatIntervalSeconds(),
                props.requestTimeoutSeconds(),
                props.offlineAfterFailures());
    }

    private StoredState load() {
        if (!Files.exists(stateFile)) {
            return null;
        }
        try {
            return mapper.readValue(stateFile.toFile(), StoredState.class);
        } catch (IOException e) {
            log.warn("Could not read {} – falling back to defaults: {}", stateFile, e.getMessage());
            return null;
        }
    }

    private void save() {
        try {
            Files.createDirectories(dataDir);
            mapper.writeValue(stateFile.toFile(), new StoredState(nodeId, current));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not persist settings to " + stateFile, e);
        }
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
