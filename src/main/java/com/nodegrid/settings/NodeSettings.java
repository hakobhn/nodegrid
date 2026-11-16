package com.nodegrid.settings;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Settings that can be edited at runtime from the web UI.
 */
public record NodeSettings(
        @NotBlank String nodeName,
        String advertisedUrl,
        String masterUrl,
        @Min(1) @Max(3600) int pollIntervalSeconds,
        @Min(1) @Max(3600) int heartbeatIntervalSeconds,
        @Min(1) @Max(300) int requestTimeoutSeconds,
        @Min(1) @Max(100) int offlineAfterFailures) {
}
