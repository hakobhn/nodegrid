package com.nodegrid.master;

import jakarta.validation.constraints.NotBlank;

/** Sent by a worker to the master on startup and on every heartbeat. */
public record RegistrationRequest(@NotBlank String nodeId, @NotBlank String nodeName, @NotBlank String url) {
}
