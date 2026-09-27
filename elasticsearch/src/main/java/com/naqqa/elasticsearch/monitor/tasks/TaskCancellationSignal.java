package com.naqqa.elasticsearch.monitor.tasks;

import java.util.concurrent.atomic.AtomicBoolean;

public final class TaskCancellationSignal {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile String reason;

    public boolean cancel(String reason) {
        if (cancelled.compareAndSet(false, true)) {
            this.reason = reason;
            return true;
        }
        return false;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public String reason() {
        return reason;
    }

    public void checkCancelled() {
        if (isCancelled()) {
            throw new TaskCancelledException(reason);
        }
    }

    public static final class TaskCancelledException extends RuntimeException {
        public TaskCancelledException(String reason) {
            super(reason == null ? "task cancelled" : reason);
        }
    }
}
