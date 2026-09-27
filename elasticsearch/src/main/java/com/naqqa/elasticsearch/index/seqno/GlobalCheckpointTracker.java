package com.naqqa.elasticsearch.index.seqno;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class GlobalCheckpointTracker {

    private final Object mutex = new Object();
    private final Map<String, Long> localCheckpoints = new HashMap<>();
    private final Set<String> inSyncAllocationIds = new HashSet<>();
    private long globalCheckpoint = SequenceNumbers.NO_OPS_PERFORMED;

    public void markAllocationIdAsInSync(String allocationId) {
        synchronized (mutex) {
            inSyncAllocationIds.add(allocationId);
            localCheckpoints.putIfAbsent(allocationId, SequenceNumbers.NO_OPS_PERFORMED);
            recompute();
        }
    }

    public void removeAllocationId(String allocationId) {
        synchronized (mutex) {
            inSyncAllocationIds.remove(allocationId);
            localCheckpoints.remove(allocationId);
            recompute();
        }
    }

    public void updateLocalCheckpoint(String allocationId, long localCheckpoint) {
        synchronized (mutex) {
            Long previous = localCheckpoints.get(allocationId);
            if (previous == null || localCheckpoint > previous) {
                localCheckpoints.put(allocationId, localCheckpoint);
            }
            recompute();
        }
    }

    public long getLocalCheckpoint(String allocationId) {
        synchronized (mutex) {
            return localCheckpoints.getOrDefault(allocationId, SequenceNumbers.UNASSIGNED_SEQ_NO);
        }
    }

    public boolean isInSync(String allocationId) {
        synchronized (mutex) {
            return inSyncAllocationIds.contains(allocationId);
        }
    }

    public long getGlobalCheckpoint() {
        synchronized (mutex) {
            return globalCheckpoint;
        }
    }

    private void recompute() {
        if (inSyncAllocationIds.isEmpty()) {
            globalCheckpoint = SequenceNumbers.NO_OPS_PERFORMED;
            return;
        }
        long min = Long.MAX_VALUE;
        for (String allocationId : inSyncAllocationIds) {
            long checkpoint = localCheckpoints.getOrDefault(allocationId, SequenceNumbers.NO_OPS_PERFORMED);
            if (checkpoint < min) {
                min = checkpoint;
            }
        }
        globalCheckpoint = min;
    }
}
