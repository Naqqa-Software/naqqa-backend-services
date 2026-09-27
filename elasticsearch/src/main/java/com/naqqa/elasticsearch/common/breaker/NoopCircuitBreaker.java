package com.naqqa.elasticsearch.common.breaker;

public final class NoopCircuitBreaker implements CircuitBreaker {

    private final String name;

    public NoopCircuitBreaker(String name) {
        this.name = name;
    }

    @Override
    public void circuitBreak(String fieldName, long bytesNeeded) {
    }

    @Override
    public double addEstimateBytesAndMaybeBreak(long bytes, String label) {
        return 0;
    }

    @Override
    public long addWithoutBreaking(long bytes) {
        return 0;
    }

    @Override
    public long getUsed() {
        return 0;
    }

    @Override
    public long getLimit() {
        return -1;
    }

    @Override
    public double getOverhead() {
        return 0;
    }

    @Override
    public long getTrippedCount() {
        return 0;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Durability getDurability() {
        return Durability.PERMANENT;
    }
}
