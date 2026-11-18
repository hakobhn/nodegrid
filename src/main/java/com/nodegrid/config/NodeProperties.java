package com.nodegrid.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Startup configuration ({@code nodegrid.*}). Values here are the defaults; most of them can be
 * changed at runtime from the Settings page (see {@link com.nodegrid.settings.SettingsService}).
 */
@ConfigurationProperties(prefix = "nodegrid")
public record NodeProperties(
        @DefaultValue("master") NodeRole role,
        @DefaultValue("") String nodeName,
        @DefaultValue("") String advertisedUrl,
        @DefaultValue("http://localhost:8080") String masterUrl,
        @DefaultValue("15") int heartbeatIntervalSeconds,
        @DefaultValue("5") int requestTimeoutSeconds,
        @DefaultValue("3") int offlineAfterFailures,
        @DefaultValue("data") String dataDir) {
}
