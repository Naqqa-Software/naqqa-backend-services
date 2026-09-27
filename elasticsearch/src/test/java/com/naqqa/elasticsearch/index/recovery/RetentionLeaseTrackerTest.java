package com.naqqa.elasticsearch.index.recovery;

import com.naqqa.elasticsearch.test.Test;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertThrows;

public final class RetentionLeaseTrackerTest {

    @Test
    public void minimumRetainedSeqNoReflectsLowestLease() {
        RetentionLeaseTracker tracker = new RetentionLeaseTracker();
        assertEquals(Long.MAX_VALUE, tracker.minimumRetainedSeqNo());
        tracker.addOrRenew("a", 100, "replica-1");
        tracker.addOrRenew("b", 50, "replica-2");
        assertEquals(50L, tracker.minimumRetainedSeqNo());
        tracker.renew("b", 80);
        assertEquals(80L, tracker.minimumRetainedSeqNo());
        tracker.remove("b");
        assertEquals(100L, tracker.minimumRetainedSeqNo());
    }

    @Test
    public void applyToTrimPointNeverGoesBelowLeasedSeqNo() {
        RetentionLeaseTracker tracker = new RetentionLeaseTracker();
        tracker.addOrRenew("recovery-1", 42, "peer-recovery");
        assertEquals(42L, tracker.applyToTrimPoint(1000));
        assertEquals(10L, tracker.applyToTrimPoint(10));
    }

    @Test
    public void renewingUnknownLeaseThrows() {
        RetentionLeaseTracker tracker = new RetentionLeaseTracker();
        assertThrows(IllegalArgumentException.class, () -> tracker.renew("missing", 1));
    }
}
