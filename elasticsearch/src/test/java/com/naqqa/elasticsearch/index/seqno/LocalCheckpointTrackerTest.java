package com.naqqa.elasticsearch.index.seqno;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

public final class LocalCheckpointTrackerTest {

    @Test
    public void inOrderCompletionAdvancesCheckpoint() {
        LocalCheckpointTracker tracker = new LocalCheckpointTracker();
        Assert.assertEquals(SequenceNumbers.NO_OPS_PERFORMED, tracker.getCheckpoint());
        tracker.markSeqNoAsProcessed(0);
        Assert.assertEquals(0L, tracker.getCheckpoint());
        tracker.markSeqNoAsProcessed(1);
        tracker.markSeqNoAsProcessed(2);
        Assert.assertEquals(2L, tracker.getCheckpoint());
    }

    @Test
    public void outOfOrderCompletionConverges() {
        LocalCheckpointTracker tracker = new LocalCheckpointTracker();
        tracker.markSeqNoAsProcessed(3);
        tracker.markSeqNoAsProcessed(1);
        tracker.markSeqNoAsProcessed(2);
        Assert.assertEquals(SequenceNumbers.NO_OPS_PERFORMED, tracker.getCheckpoint());
        Assert.assertTrue(tracker.hasProcessed(3));
        Assert.assertFalse(tracker.hasProcessed(0));
        tracker.markSeqNoAsProcessed(0);
        Assert.assertEquals(3L, tracker.getCheckpoint());
        Assert.assertEquals(3L, tracker.getMaxSeqNo());
    }

    @Test
    public void manyOutOfOrderSeqNosConverge() {
        LocalCheckpointTracker tracker = new LocalCheckpointTracker();
        int n = 500;
        java.util.List<Integer> order = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        java.util.Collections.shuffle(order, new java.util.Random(42));
        for (int seqNo : order) {
            tracker.markSeqNoAsProcessed(seqNo);
        }
        Assert.assertEquals((long) (n - 1), tracker.getCheckpoint());
        Assert.assertEquals((long) (n - 1), tracker.getMaxSeqNo());
    }

    @Test
    public void generateSeqNoIncrements() {
        LocalCheckpointTracker tracker = new LocalCheckpointTracker();
        long a = tracker.generateSeqNo();
        long b = tracker.generateSeqNo();
        long c = tracker.generateSeqNo();
        Assert.assertEquals(0L, a);
        Assert.assertEquals(1L, b);
        Assert.assertEquals(2L, c);
    }
}
