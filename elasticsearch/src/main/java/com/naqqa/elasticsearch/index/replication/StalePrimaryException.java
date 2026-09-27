package com.naqqa.elasticsearch.index.replication;

public final class StalePrimaryException extends RuntimeException {

    private final long incomingTerm;
    private final long currentTerm;

    public StalePrimaryException(long incomingTerm, long currentTerm) {
        super("rejecting write from stale primary: incoming primary term [" + incomingTerm
            + "] is older than current known primary term [" + currentTerm + "]");
        this.incomingTerm = incomingTerm;
        this.currentTerm = currentTerm;
    }

    public long incomingTerm() {
        return incomingTerm;
    }

    public long currentTerm() {
        return currentTerm;
    }
}
