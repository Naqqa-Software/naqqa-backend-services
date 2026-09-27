package com.naqqa.elasticsearch.index.translog;

import com.naqqa.elasticsearch.common.exception.EsRejectedExecutionException;

import java.util.concurrent.atomic.AtomicLong;

public final class IndexingPressure {

    private final long primaryLimitBytes;
    private final long replicaLimitBytes;
    private final AtomicLong currentPrimaryBytes = new AtomicLong();
    private final AtomicLong currentReplicaBytes = new AtomicLong();
    private final AtomicLong totalPrimaryRejections = new AtomicLong();
    private final AtomicLong totalReplicaRejections = new AtomicLong();

    public IndexingPressure(long primaryLimitBytes, long replicaLimitBytes) {
        this.primaryLimitBytes = primaryLimitBytes;
        this.replicaLimitBytes = replicaLimitBytes;
    }

    public Releasable markPrimaryOperationStarted(long bytes, boolean forceExecution) {
        long newTotal = currentPrimaryBytes.addAndGet(bytes);
        if (!forceExecution && newTotal > primaryLimitBytes) {
            currentPrimaryBytes.addAndGet(-bytes);
            totalPrimaryRejections.incrementAndGet();
            throw new EsRejectedExecutionException(
                "rejecting indexing request, primary bytes in-flight [{}] would exceed limit [{}]", newTotal, primaryLimitBytes);
        }
        return () -> currentPrimaryBytes.addAndGet(-bytes);
    }

    public Releasable markReplicaOperationStarted(long bytes, boolean forceExecution) {
        long newTotal = currentReplicaBytes.addAndGet(bytes);
        if (!forceExecution && newTotal > replicaLimitBytes) {
            currentReplicaBytes.addAndGet(-bytes);
            totalReplicaRejections.incrementAndGet();
            throw new EsRejectedExecutionException(
                "rejecting indexing request, replica bytes in-flight [{}] would exceed limit [{}]", newTotal, replicaLimitBytes);
        }
        return () -> currentReplicaBytes.addAndGet(-bytes);
    }

    public Releasable markIndexingOperationStarted(long bytes) {
        return markPrimaryOperationStarted(bytes, false);
    }

    public long currentPrimaryBytes() {
        return currentPrimaryBytes.get();
    }

    public long currentReplicaBytes() {
        return currentReplicaBytes.get();
    }

    public long currentCombinedBytes() {
        return currentPrimaryBytes.get() + currentReplicaBytes.get();
    }

    public long totalPrimaryRejections() {
        return totalPrimaryRejections.get();
    }

    public long totalReplicaRejections() {
        return totalReplicaRejections.get();
    }

    public long primaryLimitBytes() {
        return primaryLimitBytes;
    }

    public long replicaLimitBytes() {
        return replicaLimitBytes;
    }
}
