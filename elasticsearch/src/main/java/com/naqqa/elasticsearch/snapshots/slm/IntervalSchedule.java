package com.naqqa.elasticsearch.snapshots.slm;

import java.time.Duration;
import java.time.Instant;

public final class IntervalSchedule implements SlmSchedule {

    private final Duration interval;

    public IntervalSchedule(Duration interval) {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        this.interval = interval;
    }

    @Override
    public Instant nextFireTime(Instant after) {
        return after.plus(interval);
    }

    public Duration interval() {
        return interval;
    }
}
