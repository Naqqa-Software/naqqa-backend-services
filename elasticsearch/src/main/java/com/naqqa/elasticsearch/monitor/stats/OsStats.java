package com.naqqa.elasticsearch.monitor.stats;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.LinkedHashMap;
import java.util.Map;

public record OsStats(int availableProcessors, double systemLoadAverage, double cpuPercent, long totalMemoryBytes,
        long freeMemoryBytes) {

    public static OsStats capture() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        int processors = osBean.getAvailableProcessors();
        double loadAverage = osBean.getSystemLoadAverage();
        double cpuPercent = -1;
        long totalMemory = -1;
        long freeMemory = -1;
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            double cpuLoad = sunBean.getCpuLoad();
            cpuPercent = cpuLoad < 0 ? -1 : cpuLoad * 100;
            totalMemory = sunBean.getTotalMemorySize();
            freeMemory = sunBean.getFreeMemorySize();
        }
        return new OsStats(processors, loadAverage, cpuPercent, totalMemory, freeMemory);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("available_processors", availableProcessors);
        Map<String, Object> cpu = new LinkedHashMap<>();
        cpu.put("percent", cpuPercent);
        cpu.put("load_average", systemLoadAverage);
        map.put("cpu", cpu);
        Map<String, Object> mem = new LinkedHashMap<>();
        mem.put("total_in_bytes", totalMemoryBytes);
        mem.put("free_in_bytes", freeMemoryBytes);
        mem.put("used_in_bytes", totalMemoryBytes >= 0 && freeMemoryBytes >= 0 ? totalMemoryBytes - freeMemoryBytes : -1);
        map.put("mem", mem);
        return map;
    }
}
