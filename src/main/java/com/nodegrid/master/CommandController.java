package com.nodegrid.master;

import com.fasterxml.jackson.databind.JsonNode;
import com.nodegrid.command.Command;
import com.nodegrid.command.CommandResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/** Master-only API for sending commands to workers and for workers to fetch them / report results. */
@RestController
@RequestMapping("/api/master")
@ConditionalOnProperty(prefix = "nodegrid", name = "role", havingValue = "master", matchIfMissing = true)
public class CommandController {

    /** Upper bound for a worker's long-poll, kept below typical proxy/idle timeouts. */
    static final int MAX_WAIT_SECONDS = 30;

    private final CommandQueue queue;

    public CommandController(CommandQueue queue) {
        this.queue = queue;
    }

    public record SubmitCommandRequest(@NotBlank String type, JsonNode payload, Integer timeoutSeconds) {
    }

    /** Send a command to a worker. It is delivered on the worker's next (or currently open) long-poll. */
    @PostMapping("/workers/{id}/commands")
    @ResponseStatus(HttpStatus.CREATED)
    public CommandQueue.CommandView submit(@PathVariable String id, @Valid @RequestBody SubmitCommandRequest request) {
        return queue.submit(id, request.type(), request.payload(), request.timeoutSeconds());
    }

    @GetMapping("/workers/{id}/commands")
    public List<CommandQueue.CommandView> list(@PathVariable String id) {
        return queue.list(id);
    }

    @GetMapping("/commands/{commandId}")
    public CommandQueue.CommandView get(@PathVariable String commandId) {
        return queue.get(commandId);
    }

    /** Worker long-poll: blocks until a command is queued for this worker or {@code wait} seconds pass. */
    @GetMapping("/workers/{id}/commands/next")
    public List<Command> next(@PathVariable String id, @RequestParam(defaultValue = "25") int wait)
            throws InterruptedException {
        int seconds = Math.clamp(wait, 0, MAX_WAIT_SECONDS);
        return queue.next(id, Duration.ofSeconds(seconds));
    }

    /** Worker reports the outcome of a command. */
    @PostMapping("/workers/{id}/commands/{commandId}/result")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void result(@PathVariable String id, @PathVariable String commandId, @RequestBody CommandResult result) {
        queue.complete(id, commandId, result);
    }
}
