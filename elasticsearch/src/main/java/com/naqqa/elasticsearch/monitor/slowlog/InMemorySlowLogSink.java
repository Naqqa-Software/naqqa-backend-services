package com.naqqa.elasticsearch.monitor.slowlog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class InMemorySlowLogSink implements SlowLogSink {

    public record Entry(SlowLogLevel level, String category, String line) {
    }

    private final List<Entry> entries = new ArrayList<>();

    @Override
    public synchronized void write(SlowLogLevel level, String category, String line) {
        entries.add(new Entry(level, category, line));
    }

    public synchronized List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized void clear() {
        entries.clear();
    }
}
