package com.naqqa.elasticsearch.monitor.stats;

import java.util.List;

public interface CircuitBreakerStatsSource {

    List<BreakerEntry> getBreakers();

    record BreakerEntry(String name, long limitInBytes, long estimatedInBytes, double overhead, long tripped) {
    }
}
