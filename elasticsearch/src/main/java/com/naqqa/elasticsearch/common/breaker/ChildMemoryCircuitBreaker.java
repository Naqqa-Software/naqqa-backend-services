package com.naqqa.elasticsearch.common.breaker;

import com.naqqa.elasticsearch.common.exception.CircuitBreakingException;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public final class ChildMemoryCircuitBreaker implements CircuitBreaker {

    private final String name;
    private volatile long limit;
    private final double overhead;
    private final AtomicLong used = new AtomicLong();
    private final AtomicLong trippedCount = new AtomicLong();
    private final Durability durability;
    private final LongSupplier parentUsedSupplier;
    private final long parentLimit;

    public ChildMemoryCircuitBreaker(String name, long limit, double overhead, Durability durability) {
        this(name, limit, overhead, durability, null, 0);
    }

    public ChildMemoryCircuitBreaker(
        String name,
        long limit,
        double overhead,
        Durability durability,
        LongSupplier parentUsedSupplier,
        long parentLimit
    ) {
        this.name = name;
        this.limit = limit;
        this.overhead = overhead;
        this.durability = durability;
        this.parentUsedSupplier = parentUsedSupplier;
        this.parentLimit = parentLimit;
    }

    @Override
    public void circuitBreak(String fieldName, long bytesNeeded) {
        trippedCount.incrementAndGet();
        throw new CircuitBreakingException(
            "[" + name + "] Data too large, data for [" + fieldName + "] would be [" + bytesNeeded + "/" + bytesNeeded
                + "b], which is larger than the limit of [" + limit + "/" + limit + "b]",
            bytesNeeded,
            limit,
            name
        );
    }

    @Override
    public double addEstimateBytesAndMaybeBreak(long bytes, String label) {
        long adjusted = (long) (bytes * overhead);
        long newUsed = used.addAndGet(adjusted);
        if (limit >= 0 && newUsed > limit) {
            used.addAndGet(-adjusted);
            trippedCount.incrementAndGet();
            throw new CircuitBreakingException(
                "[" + name + "] Data too large, data for [" + label + "] would be [" + newUsed + "/" + newUsed
                    + "b], which is larger than the limit of [" + limit + "/" + limit + "b]",
                newUsed,
                limit,
                name
            );
        }
        if (parentUsedSupplier != null && parentLimit >= 0) {
            long parentUsed = parentUsedSupplier.getAsLong();
            if (parentUsed > parentLimit) {
                used.addAndGet(-adjusted);
                trippedCount.incrementAndGet();
                throw new CircuitBreakingException(
                    "[parent] Data too large, data for [" + label + "] would be [" + parentUsed + "/" + parentUsed
                        + "b], which is larger than the limit of [" + parentLimit + "/" + parentLimit + "b]",
                    parentUsed,
                    parentLimit,
                    "parent"
                );
            }
        }
        return newUsed;
    }

    @Override
    public long addWithoutBreaking(long bytes) {
        long adjusted = (long) (bytes * overhead);
        return used.addAndGet(adjusted);
    }

    @Override
    public long getUsed() {
        return used.get();
    }

    @Override
    public long getLimit() {
        return limit;
    }

    public void setLimit(long limit) {
        this.limit = limit;
    }

    @Override
    public double getOverhead() {
        return overhead;
    }

    @Override
    public long getTrippedCount() {
        return trippedCount.get();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Durability getDurability() {
        return durability;
    }
}
