package com.naqqa.elasticsearch.monitor.health;

public enum HealthStatus {
    GREEN,
    YELLOW,
    RED;

    public static HealthStatus worstOf(HealthStatus a, HealthStatus b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
