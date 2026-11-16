package com.nodegrid.master;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

/** Master-only API for managing and monitoring workers. */
@RestController
@RequestMapping("/api/master")
@ConditionalOnProperty(prefix = "nodegrid", name = "role", havingValue = "master", matchIfMissing = true)
public class MasterController {

    private final WorkerRegistry registry;
    private final WorkerPoller poller;

    public MasterController(WorkerRegistry registry, WorkerPoller poller) {
        this.registry = registry;
        this.poller = poller;
    }

    public record AddWorkerRequest(@NotBlank String url) {
    }

    @GetMapping("/workers")
    public List<WorkerView> workers() {
        return registry.list();
    }

    @GetMapping("/workers/{id}")
    public WorkerView worker(@PathVariable String id) {
        return registry.get(id);
    }

    /** Manually add a worker by URL. The master contacts it immediately to verify it. */
    @PostMapping("/workers")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkerView add(@Valid @RequestBody AddWorkerRequest request) {
        WorkerView added = registry.addManual(request.url());
        return poller.pollNow(added.id());
    }

    /** Called by workers on startup and on every heartbeat. */
    @PostMapping("/workers/register")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(@Valid @RequestBody RegistrationRequest request) {
        registry.register(request);
    }

    @PostMapping("/workers/{id}/poll")
    public WorkerView pollNow(@PathVariable String id) {
        return poller.pollNow(id);
    }

    @DeleteMapping("/workers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String id) {
        registry.remove(id);
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
        return new ClusterSummary(all.size(), online, unreachable, offline, unknown, cores, totalMem, freeMem,
                avgCpu, poller.lastCycle());
    }
}
