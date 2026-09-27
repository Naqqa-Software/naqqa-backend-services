package com.naqqa.elasticsearch.monitor.slowlog;

import java.util.EnumMap;
import java.util.Map;

public final class SlowLogThresholds {

    private final Map<SlowLogLevel, Long> thresholdsMillis;

    public SlowLogThresholds() {
        this.thresholdsMillis = new EnumMap<>(SlowLogLevel.class);
    }

    public SlowLogThresholds set(SlowLogLevel level, long millis) {
        thresholdsMillis.put(level, millis);
        return this;
    }

    public long get(SlowLogLevel level) {
        return thresholdsMillis.getOrDefault(level, -1L);
    }

    public SlowLogLevel matchLevel(long tookMillis) {
        for (SlowLogLevel level : SlowLogLevel.values()) {
            long threshold = get(level);
            if (threshold >= 0 && tookMillis >= threshold) {
                return level;
            }
        }
        return null;
    }
}
