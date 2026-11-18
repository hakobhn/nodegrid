package com.nodegrid.command;

import com.fasterxml.jackson.databind.JsonNode;

/** Outcome of a command, reported by the worker back to the master. */
public record CommandResult(boolean success, JsonNode result, String error) {
}
