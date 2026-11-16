package com.nodegrid.node;

import com.nodegrid.settings.NodeSettings;
import com.nodegrid.settings.SettingsService;
import com.nodegrid.worker.MasterHeartbeat;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Endpoints every node (master or worker) exposes about itself.
 */
@RestController
@RequestMapping("/api/node")
public class NodeController {

    private final ResourceCollector collector;
    private final SettingsService settings;
    private final ObjectProvider<MasterHeartbeat> heartbeat;
    private final Instant startedAt = Instant.now();
    private final String version;

    public NodeController(ResourceCollector collector, SettingsService settings,
                          ObjectProvider<MasterHeartbeat> heartbeat, ObjectProvider<BuildProperties> build) {
        this.collector = collector;
        this.settings = settings;
        this.heartbeat = heartbeat;
        BuildProperties bp = build.getIfAvailable();
        this.version = bp != null ? bp.getVersion() : "dev";
    }

    @GetMapping("/info")
    public NodeInfo info() {
        MasterHeartbeat hb = heartbeat.getIfAvailable();
        return new NodeInfo(
                settings.nodeId(),
                settings.role(),
                settings.get().nodeName(),
                settings.effectiveAdvertisedUrl(),
                version,
                startedAt,
                hb != null ? hb.status() : null);
    }

    @GetMapping("/resources")
    public ResourceSnapshot resources() {
        return collector.collect();
    }

    @GetMapping("/settings")
    public NodeSettings getSettings() {
        return settings.get();
    }

    @PutMapping("/settings")
    public NodeSettings updateSettings(@Valid @RequestBody NodeSettings updated) {
        NodeSettings result = settings.update(updated);
        MasterHeartbeat hb = heartbeat.getIfAvailable();
        if (hb != null) {
            hb.triggerNow();
        }
        return result;
    }

    @DeleteMapping("/settings")
    public NodeSettings resetSettings() {
        return settings.resetToDefaults();
    }
}
