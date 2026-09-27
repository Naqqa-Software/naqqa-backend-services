package com.naqqa.elasticsearch.index.translog;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

public final class LiveVersionMapTest {

    @Test
    public void putAndGetUnderLock() {
        LiveVersionMap map = new LiveVersionMap();
        VersionValue v1 = VersionValue.index(1L, 0L, 1L, null);
        map.putUnderLock("a", v1);
        Assert.assertSame(v1, map.getUnderLock("a"));
        Assert.assertNull(map.getUnderLock("missing"));
    }

    @Test
    public void refreshTransitionPreservesLookupsUntilAfterRefresh() {
        LiveVersionMap map = new LiveVersionMap();
        VersionValue v1 = VersionValue.index(1L, 0L, 1L, null);
        map.putUnderLock("a", v1);

        map.beforeRefresh();
        Assert.assertSame(v1, map.getUnderLock("a"));

        VersionValue v2 = VersionValue.index(2L, 1L, 1L, null);
        map.putUnderLock("b", v2);
        Assert.assertSame(v1, map.getUnderLock("a"));
        Assert.assertSame(v2, map.getUnderLock("b"));

        map.afterRefresh();
        Assert.assertSame(v2, map.getUnderLock("b"));
        Assert.assertNull(map.getUnderLock("a"));
    }

    @Test
    public void deleteTombstoneSurvivesRefreshUntilPruned() {
        LiveVersionMap map = new LiveVersionMap();
        VersionValue tombstone = VersionValue.tombstone(3L, 2L, 1L, null);
        map.putUnderLock("a", tombstone);
        map.beforeRefresh();
        map.afterRefresh();
        VersionValue found = map.getUnderLock("a");
        Assert.assertNotNull(found);
        Assert.assertTrue(found.isDelete());
    }

    @Test
    public void pruneTombstonesByAgeAndCount() throws InterruptedException {
        LiveVersionMap map = new LiveVersionMap();
        for (int i = 0; i < 5; i++) {
            map.putUnderLock("id" + i, VersionValue.tombstone(1L, i, 1L, null));
        }
        Assert.assertEquals(5, map.tombstoneCount());
        int pruned = map.pruneTombstones(0L, 2);
        Assert.assertTrue(pruned >= 3);
        Assert.assertTrue(map.tombstoneCount() <= 2);
    }
}
