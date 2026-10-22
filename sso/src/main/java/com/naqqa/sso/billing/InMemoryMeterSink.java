package com.naqqa.sso.billing;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryMeterSink implements MeterSink {

    private final List<UsageEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void accept(UsageEvent event) {
        events.add(event);
    }

    public List<UsageEvent> events() {
        return List.copyOf(events);
    }
}
