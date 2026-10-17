package com.naqqa.auth.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

public class MutableClock extends Clock {

    private final AtomicLong offsetMillis = new AtomicLong();

    public void advance(Duration d) {
        offsetMillis.addAndGet(d.toMillis());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return Instant.now().plusMillis(offsetMillis.get());
    }

    @Override
    public long millis() {
        return instant().toEpochMilli();
    }
}
