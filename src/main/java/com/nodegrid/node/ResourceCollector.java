package com.nodegrid.node;

import org.springframework.stereotype.Component;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads local resources through the JDK management beans (no native dependencies).
 */
@Component
public class ResourceCollector {

    private final com.sun.management.OperatingSystemMXBean os =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    private final String hostname = resolveHostname();

    public ResourceSnapshot collect() {
        var heap = memory.getHeapMemoryUsage();
        return new ResourceSnapshot(
                Instant.now(),
                hostname,
                os.getName(),
                os.getVersion(),
                os.getArch(),
                Runtime.version().toString(),
                os.getAvailableProcessors(),
                toPercent(os.getCpuLoad()),
                toPercent(os.getProcessCpuLoad()),
                os.getSystemLoadAverage(),
                os.getTotalMemorySize(),
                os.getFreeMemorySize(),
                os.getTotalSwapSpaceSize(),
                os.getFreeSwapSpaceSize(),
                heap.getUsed(),
                heap.getMax(),
                runtime.getUptime(),
                disks());
    }

    public String hostname() {
        return hostname;
    }

    private static List<ResourceSnapshot.DiskInfo> disks() {
        List<ResourceSnapshot.DiskInfo> result = new ArrayList<>();
        File[] roots = File.listRoots();
        if (roots == null) {
            return result;
        }
        for (File root : roots) {
            long total = root.getTotalSpace();
            if (total > 0) {
                result.add(new ResourceSnapshot.DiskInfo(root.getPath(), total, root.getUsableSpace()));
            }
        }
        return result;
    }

    private static double toPercent(double load) {
        return load < 0 ? -1 : Math.round(load * 1000.0) / 10.0;
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            String env = System.getenv("COMPUTERNAME");
            return env != null ? env : System.getenv().getOrDefault("HOSTNAME", "unknown");
        }
    }
}
