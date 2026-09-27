package com.naqqa.elasticsearch.monitor.stats;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.LinkedHashMap;
import java.util.Map;

public record ProcessStats(long openFileDescriptors, long maxFileDescriptors, double processCpuPercent,
        long processCpuTimeInMillis, long virtualMemorySizeInBytes) {

    public static ProcessStats capture() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        long openFds = -1;
        long maxFds = -1;
        double cpuPercent = -1;
        long cpuTimeMillis = -1;
        long virtualMemory = -1;
        if (osBean instanceof com.sun.management.UnixOperatingSystemMXBean unixBean) {
            openFds = unixBean.getOpenFileDescriptorCount();
            maxFds = unixBean.getMaxFileDescriptorCount();
        }
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            double load = sunBean.getProcessCpuLoad();
            cpuPercent = load < 0 ? -1 : load * 100;
            long cpuTimeNanos = sunBean.getProcessCpuTime();
            cpuTimeMillis = cpuTimeNanos < 0 ? -1 : cpuTimeNanos / 1_000_000L;
            virtualMemory = sunBean.getCommittedVirtualMemorySize();
        }
        return new ProcessStats(openFds, maxFds, cpuPercent, cpuTimeMillis, virtualMemory);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, Object> cpu = new LinkedHashMap<>();
        cpu.put("percent", processCpuPercent);
        cpu.put("total_in_millis", processCpuTimeInMillis);
        map.put("cpu", cpu);
        map.put("open_file_descriptors", openFileDescriptors);
        map.put("max_file_descriptors", maxFileDescriptors);
        Map<String, Object> mem = new LinkedHashMap<>();
        mem.put("total_virtual_in_bytes", virtualMemorySizeInBytes);
        map.put("mem", mem);
        return map;
    }
}
