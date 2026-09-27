package com.naqqa.elasticsearch.monitor.health;

import java.util.List;
import java.util.Map;

public final class DiskHealthIndicator implements HealthIndicatorSource {

    public record Input(Map<String, Double> nodeDiskUsedPercent, double highWatermarkPercent,
            double floodStagePercent) {
    }

    private final Input input;

    public DiskHealthIndicator(Input input) {
        this.input = input;
    }

    @Override
    public String name() {
        return "disk";
    }

    @Override
    public HealthIndicatorResult calculate() {
        String worstNode = null;
        double worstUsage = -1;
        for (Map.Entry<String, Double> entry : input.nodeDiskUsedPercent().entrySet()) {
            if (entry.getValue() > worstUsage) {
                worstUsage = entry.getValue();
                worstNode = entry.getKey();
            }
        }
        if (worstNode == null) {
            return new HealthIndicatorResult(name(), HealthStatus.GREEN, "no disk usage data available", List.of());
        }
        if (worstUsage >= input.floodStagePercent()) {
            return new HealthIndicatorResult(name(), HealthStatus.RED,
                    "node " + worstNode + " has exceeded the flood-stage disk watermark",
                    List.of("free up disk space on " + worstNode + " or add capacity"));
        }
        if (worstUsage >= input.highWatermarkPercent()) {
            return new HealthIndicatorResult(name(), HealthStatus.YELLOW,
                    "node " + worstNode + " has exceeded the high disk watermark",
                    List.of("free up disk space on " + worstNode));
        }
        return new HealthIndicatorResult(name(), HealthStatus.GREEN, "disk usage is within limits on all nodes",
                List.of());
    }
}
