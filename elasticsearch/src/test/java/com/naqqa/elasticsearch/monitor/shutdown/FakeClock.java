package com.naqqa.elasticsearch.monitor.shutdown;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class FakeClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public FakeClock(Instant instant) {
        this(instant, ZoneOffset.UTC);
    }

    public FakeClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public void advance(long millis) {
        instant = instant.plusMillis(millis);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new FakeClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
