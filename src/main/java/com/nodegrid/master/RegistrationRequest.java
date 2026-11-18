package com.nodegrid.master;

import com.nodegrid.node.ResourceSnapshot;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Sent by a worker to the master on startup and on every heartbeat. Workers always open the connection; the master
 * never connects to a worker (commands reach workers over the worker's long-poll, see {@code CommandQueue}).
 *
 * @param url                      where the worker's own UI can be opened (informational only, may be empty)
 * @param heartbeatIntervalSeconds how often the worker reports, so the master knows when it is overdue
 * @param resources                the worker's current resource snapshot
 * @param commandTypes             command types the worker can execute
 */
public record RegistrationRequest(@NotBlank String nodeId,
                                  @NotBlank String nodeName,
                                  String url,
                                  int heartbeatIntervalSeconds,
                                  ResourceSnapshot resources,
                                  List<String> commandTypes) {
}
