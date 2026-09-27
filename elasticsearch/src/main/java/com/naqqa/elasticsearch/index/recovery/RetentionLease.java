package com.naqqa.elasticsearch.index.recovery;

public record RetentionLease(String id, long retainingSeqNo, long timestamp, String source) {

    public RetentionLease renew(long newRetainingSeqNo, long newTimestamp) {
        return new RetentionLease(id, newRetainingSeqNo, newTimestamp, source);
    }
}
