package com.nodegrid.worker.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.nodegrid.command.CommandHandler;
import com.nodegrid.node.ResourceCollector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Commands every worker supports out of the box. Add new work types by registering more {@link CommandHandler}
 * beans on the worker.
 */
@Configuration
@ConditionalOnProperty(prefix = "nodegrid", name = "role", havingValue = "worker")
public class BuiltInHandlers {

    /** {@code ping}: round-trip check; echoes the payload back. */
    @Bean
    CommandHandler pingHandler() {
        return new CommandHandler() {
            @Override
            public String type() {
                return "ping";
            }

            @Override
            public Object handle(JsonNode payload) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("pong", true);
                out.put("at", Instant.now().toString());
                if (payload != null && !payload.isNull()) {
                    out.put("echo", payload);
                }
                return out;
            }
        };
    }

    /** {@code resources}: fresh resource snapshot, taken when the command runs. */
    @Bean
    CommandHandler resourcesHandler(ResourceCollector collector) {
        return new CommandHandler() {
            @Override
            public String type() {
                return "resources";
            }

            @Override
            public Object handle(JsonNode payload) {
                return collector.collect();
            }
        };
    }
}
