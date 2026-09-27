package com.naqqa.elasticsearch.index.translog;

import com.naqqa.elasticsearch.common.exception.EsRejectedExecutionException;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

public final class IndexingPressureTest {

    @Test
    public void tracksInFlightBytesAndReleases() {
        IndexingPressure pressure = new IndexingPressure(1000L, 1000L);
        Releasable r = pressure.markPrimaryOperationStarted(400L, false);
        Assert.assertEquals(400L, pressure.currentPrimaryBytes());
        r.close();
        Assert.assertEquals(0L, pressure.currentPrimaryBytes());
    }

    @Test
    public void rejectsWhenOverLimit() {
        IndexingPressure pressure = new IndexingPressure(500L, 500L);
        Releasable r1 = pressure.markPrimaryOperationStarted(400L, false);
        Assert.assertThrows(EsRejectedExecutionException.class, () -> pressure.markPrimaryOperationStarted(200L, false));
        Assert.assertEquals(400L, pressure.currentPrimaryBytes());
        Assert.assertEquals(1L, pressure.totalPrimaryRejections());
        r1.close();
        Assert.assertEquals(0L, pressure.currentPrimaryBytes());
    }

    @Test
    public void primaryAndReplicaBytesAreTrackedSeparately() {
        IndexingPressure pressure = new IndexingPressure(1000L, 1000L);
        Releasable primary = pressure.markPrimaryOperationStarted(100L, false);
        Releasable replica = pressure.markReplicaOperationStarted(200L, false);
        Assert.assertEquals(100L, pressure.currentPrimaryBytes());
        Assert.assertEquals(200L, pressure.currentReplicaBytes());
        Assert.assertEquals(300L, pressure.currentCombinedBytes());
        primary.close();
        replica.close();
        Assert.assertEquals(0L, pressure.currentCombinedBytes());
    }

    @Test
    public void forceExecutionBypassesLimitButStillTracksBytes() {
        IndexingPressure pressure = new IndexingPressure(10L, 10L);
        Releasable r = pressure.markPrimaryOperationStarted(1000L, true);
        Assert.assertEquals(1000L, pressure.currentPrimaryBytes());
        Assert.assertEquals(0L, pressure.totalPrimaryRejections());
        r.close();
    }
}
