package com.nodegrid.master;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Master-only API: receives worker heartbeats and serves the worker list. The master never calls out to workers;
 * see {@link CommandController} for sending requests to them.
 */
@RestController
@RequestMapping("/api/master")
@ConditionalOnProperty(prefix = "nodegrid", name = "role", havingValue = "master", matchIfMissing = true)
public class MasterController {

    private final WorkerRegistry registry;
    private final CommandQueue commands;

    public MasterController(WorkerRegistry registry, CommandQueue commands) {
        this.registry = registry;
        this.commands = commands;
    }

    @GetMapping("/workers")
    public List<WorkerView> workers() {
        return registry.list();
    }

    @GetMapping("/workers/{id}")
    public WorkerView worker(@PathVariable String id) {
        return registry.get(id);
    }

    /** Called by workers on startup and on every heartbeat; carries the worker's resources. */
    @PostMapping("/workers/register")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(@Valid @RequestBody RegistrationRequest request) {
        registry.report(request);
    }

    @DeleteMapping("/workers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String id) {
        registry.remove(id);
        commands.forget(id);
    }

    @GetMapping("/summary")
    public ClusterSummary summary() {
        List<WorkerView> all = registry.list();
        int online = 0, unreachable = 0, offline = 0, unknown = 0, cores = 0, cpuSamples = 0;
        long totalMem = 0, freeMem = 0;
        double cpuSum = 0;
        for (WorkerView w : all) {
            switch (w.status()) {
                case ONLINE -> online++;
                case UNREACHABLE -> unreachable++;
                case OFFLINE -> offline++;
                case UNKNOWN -> unknown++;
            }
            if (w.status() == WorkerStatus.ONLINE && w.resources() != null) {
                var r = w.resources();
                cores += r.availableProcessors();
                totalMem += r.totalMemoryBytes();
                freeMem += r.freeMemoryBytes();
                if (r.systemCpuLoadPercent() >= 0) {
                    cpuSum += r.systemCpuLoadPercent();
                    cpuSamples++;
                }
            }
        }
        double avgCpu = cpuSamples == 0 ? -1 : Math.round(cpuSum / cpuSamples * 10.0) / 10.0;
        return new ClusterSummary(all.size(), online, unreachable, offline, unknown, cores, totalMem, freeMem, avgCpu);
    }
}
