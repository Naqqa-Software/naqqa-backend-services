package com.naqqa.elasticsearch.snapshots.repository;

import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationToken {

    public static final CancellationToken NONE = new CancellationToken();

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
