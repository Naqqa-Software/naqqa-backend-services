package com.naqqa.elasticsearch.monitor.stats.fake;

import com.naqqa.elasticsearch.monitor.stats.CircuitBreakerStatsSource;
import java.util.ArrayList;
import java.util.List;

public final class FakeCircuitBreakerStatsSource implements CircuitBreakerStatsSource {

    public final List<BreakerEntry> entries = new ArrayList<>();

    @Override
    public List<BreakerEntry> getBreakers() {
        return entries;
    }
}
