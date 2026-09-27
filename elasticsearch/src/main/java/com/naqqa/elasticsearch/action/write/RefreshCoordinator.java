package com.naqqa.elasticsearch.action.write;

import com.naqqa.elasticsearch.index.shard.IndexShard;

import java.io.IOException;

public final class RefreshCoordinator {

    public static final long DEFAULT_WAIT_FOR_BOUND_MILLIS = 200L;
    private static final long POLL_INTERVAL_MILLIS = 10L;

    private RefreshCoordinator() {
    }

    public static void apply(RefreshPolicy policy, IndexShard shard) throws IOException {
        apply(policy, shard, DEFAULT_WAIT_FOR_BOUND_MILLIS);
    }

    public static void apply(RefreshPolicy policy, IndexShard shard, long waitForBoundMillis) throws IOException {
        switch (policy) {
            case NONE -> {
            }
            case IMMEDIATE -> shard.refresh();
            case WAIT_FOR -> waitForRefresh(shard, waitForBoundMillis);
        }
    }

    private static void waitForRefresh(IndexShard shard, long waitForBoundMillis) throws IOException {
        int segmentCountAtStart = shard.segmentCount();
        long deadlineNanos = System.nanoTime() + waitForBoundMillis * 1_000_000L;
        while (System.nanoTime() < deadlineNanos) {
            if (shard.segmentCount() != segmentCountAtStart) {
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        shard.refresh();
    }
}
