package com.nodegrid.command;

import com.fasterxml.jackson.databind.JsonNode;

/** A command as delivered to a worker (master → worker, over the worker's long-poll). */
public record Command(String id, String type, JsonNode payload) {
}
