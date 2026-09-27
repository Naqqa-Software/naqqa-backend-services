package com.naqqa.elasticsearch.monitor.health;

import java.util.List;

public final class ClusterCoordinationHealthIndicator implements HealthIndicatorSource {

    public record Input(boolean hasActiveMaster, boolean unstableMasterHistory) {
    }

    private final Input input;

    public ClusterCoordinationHealthIndicator(Input input) {
        this.input = input;
    }

    @Override
    public String name() {
        return "cluster_coordination";
    }

    @Override
    public HealthIndicatorResult calculate() {
        if (!input.hasActiveMaster()) {
            return new HealthIndicatorResult(name(), HealthStatus.RED, "no master node is elected",
                    List.of("elect a master-eligible node or check discovery configuration"));
        }
        if (input.unstableMasterHistory()) {
            return new HealthIndicatorResult(name(), HealthStatus.YELLOW,
                    "the cluster has recently changed master nodes",
                    List.of("investigate node stability and network partitions"));
        }
        return new HealthIndicatorResult(name(), HealthStatus.GREEN, "the cluster has a stable master node",
                List.of());
    }
}
