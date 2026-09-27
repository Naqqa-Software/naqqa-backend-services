package com.naqqa.elasticsearch.index.recovery;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RetentionLeaseTracker {

    private final Map<String, RetentionLease> leases = new ConcurrentHashMap<>();

    public RetentionLease addOrRenew(String id, long retainingSeqNo, String source) {
        RetentionLease lease = new RetentionLease(id, retainingSeqNo, System.currentTimeMillis(), source);
        leases.put(id, lease);
        return lease;
    }

    public RetentionLease renew(String id, long newRetainingSeqNo) {
        RetentionLease existing = leases.get(id);
        if (existing == null) {
            throw new IllegalArgumentException("no retention lease with id [" + id + "]");
        }
        RetentionLease renewed = existing.renew(newRetainingSeqNo, System.currentTimeMillis());
        leases.put(id, renewed);
        return renewed;
    }

    public void remove(String id) {
        leases.remove(id);
    }

    public RetentionLease get(String id) {
        return leases.get(id);
    }

    public Map<String, RetentionLease> leases() {
        return new LinkedHashMap<>(leases);
    }

    public long minimumRetainedSeqNo() {
        long min = Long.MAX_VALUE;
        for (RetentionLease lease : leases.values()) {
            if (lease.retainingSeqNo() < min) {
                min = lease.retainingSeqNo();
            }
        }
        return min;
    }

    public long applyToTrimPoint(long desiredTrimPoint) {
        return Math.min(desiredTrimPoint, minimumRetainedSeqNo());
    }
}
