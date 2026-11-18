package com.nodegrid.command;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Worker-side handler for one command type. Register an implementation as a Spring bean on worker nodes and the
 * worker advertises its {@link #type()} to the master and executes matching commands.
 */
public interface CommandHandler {

    /** Command type this handler executes, e.g. {@code "ping"}. */
    String type();

    /**
     * Executes the command. The return value is serialized to JSON and sent back as the result;
     * throwing marks the command FAILED with the exception message.
     */
    Object handle(JsonNode payload) throws Exception;
}
