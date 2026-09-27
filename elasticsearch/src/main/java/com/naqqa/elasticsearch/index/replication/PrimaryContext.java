package com.naqqa.elasticsearch.index.replication;

import java.util.concurrent.atomic.AtomicBoolean;

public final class PrimaryContext {

    private final Object mutex = new Object();
    private long currentTerm;
    private final AtomicBoolean steppedDown = new AtomicBoolean(false);

    public PrimaryContext(long initialTerm) {
        this.currentTerm = initialTerm;
    }

    public long currentTerm() {
        synchronized (mutex) {
            return currentTerm;
        }
    }

    public void assertNotStale(long incomingTerm) {
        synchronized (mutex) {
            if (incomingTerm < currentTerm) {
                throw new StalePrimaryException(incomingTerm, currentTerm);
            }
            if (incomingTerm > currentTerm) {
                currentTerm = incomingTerm;
            }
        }
    }

    public long advanceTerm() {
        synchronized (mutex) {
            currentTerm++;
            steppedDown.set(false);
            return currentTerm;
        }
    }

    public void stepDown() {
        steppedDown.set(true);
    }

    public boolean isSteppedDown() {
        return steppedDown.get();
    }
}
