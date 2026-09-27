package com.naqqa.elasticsearch.index.seqno;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

public final class GlobalCheckpointTrackerTest {

    @Test
    public void globalCheckpointIsMinOfInSyncLocals() {
        GlobalCheckpointTracker tracker = new GlobalCheckpointTracker();
        tracker.markAllocationIdAsInSync("primary");
        tracker.markAllocationIdAsInSync("replica-1");
        tracker.markAllocationIdAsInSync("replica-2");

        tracker.updateLocalCheckpoint("primary", 10);
        tracker.updateLocalCheckpoint("replica-1", 8);
        tracker.updateLocalCheckpoint("replica-2", 5);

        Assert.assertEquals(5L, tracker.getGlobalCheckpoint());

        tracker.updateLocalCheckpoint("replica-2", 12);
        Assert.assertEquals(8L, tracker.getGlobalCheckpoint());
    }

    @Test
    public void notYetInSyncReplicaDoesNotCountTowardMin() {
        GlobalCheckpointTracker tracker = new GlobalCheckpointTracker();
        tracker.markAllocationIdAsInSync("primary");
        tracker.updateLocalCheckpoint("primary", 20);
        tracker.updateLocalCheckpoint("initializing-replica", 0);
        Assert.assertEquals(20L, tracker.getGlobalCheckpoint());
        Assert.assertFalse(tracker.isInSync("initializing-replica"));
    }

    @Test
    public void removingAllocationRecomputes() {
        GlobalCheckpointTracker tracker = new GlobalCheckpointTracker();
        tracker.markAllocationIdAsInSync("a");
        tracker.markAllocationIdAsInSync("b");
        tracker.updateLocalCheckpoint("a", 3);
        tracker.updateLocalCheckpoint("b", 100);
        Assert.assertEquals(3L, tracker.getGlobalCheckpoint());
        tracker.removeAllocationId("a");
        Assert.assertEquals(100L, tracker.getGlobalCheckpoint());
    }

    @Test
    public void emptyInSyncSetYieldsNoOpsPerformed() {
        GlobalCheckpointTracker tracker = new GlobalCheckpointTracker();
        Assert.assertEquals(SequenceNumbers.NO_OPS_PERFORMED, tracker.getGlobalCheckpoint());
    }
}
