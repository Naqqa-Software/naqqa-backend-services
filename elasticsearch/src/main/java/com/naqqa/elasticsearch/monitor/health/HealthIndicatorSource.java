package com.naqqa.elasticsearch.monitor.health;

public interface HealthIndicatorSource {

    String name();

    HealthIndicatorResult calculate();
}
