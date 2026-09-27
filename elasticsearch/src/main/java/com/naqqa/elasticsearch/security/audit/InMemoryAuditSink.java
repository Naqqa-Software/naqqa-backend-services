package com.naqqa.elasticsearch.security.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class InMemoryAuditSink implements AuditSink {

    private final List<String> lines = new ArrayList<>();

    @Override
    public synchronized void write(String jsonLine) {
        lines.add(jsonLine);
    }

    public synchronized List<String> lines() {
        return Collections.unmodifiableList(new ArrayList<>(lines));
    }
}
