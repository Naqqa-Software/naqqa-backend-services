package com.naqqa.elasticsearch.monitor.health;

import java.util.List;

public final class ShardsAvailabilityHealthIndicator implements HealthIndicatorSource {

    public record Input(int unassignedPrimaryShards, int unassignedReplicaShards, int initializingShards,
            int totalShards) {
    }

    private final Input input;

    public ShardsAvailabilityHealthIndicator(Input input) {
        this.input = input;
    }

    @Override
    public String name() {
        return "shards_availability";
    }

    @Override
    public HealthIndicatorResult calculate() {
        if (input.unassignedPrimaryShards() > 0) {
            return new HealthIndicatorResult(name(), HealthStatus.RED,
                    input.unassignedPrimaryShards() + " primary shard(s) are unassigned",
                    List.of("check allocation deciders and add nodes with sufficient capacity"));
        }
        if (input.unassignedReplicaShards() > 0) {
            return new HealthIndicatorResult(name(), HealthStatus.YELLOW,
                    input.unassignedReplicaShards() + " replica shard(s) are unassigned",
                    List.of("wait for allocation or add more nodes for replicas"));
        }
        return new HealthIndicatorResult(name(), HealthStatus.GREEN, "all shards are available", List.of());
    }
}
