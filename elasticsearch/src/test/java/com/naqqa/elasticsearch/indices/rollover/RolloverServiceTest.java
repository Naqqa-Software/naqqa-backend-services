package com.naqqa.elasticsearch.indices.rollover;

import com.naqqa.elasticsearch.common.unit.ByteSizeValue;
import com.naqqa.elasticsearch.common.unit.TimeValue;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.concurrent.TimeUnit;

public final class RolloverServiceTest {

    private final RolloverService service = new RolloverService();

    @Test
    public void maxAgeConditionAloneMatches() {
        RolloverConditions conditions = RolloverConditions.builder().maxAge(new TimeValue(1, TimeUnit.DAYS)).build();
        IndexStatsSnapshot stats = new IndexStatsSnapshot(TimeValue.timeValueHours(25).millis(), 10, 10, 10);
        RolloverResult result = service.evaluateConditions(stats, conditions);
        Assert.assertTrue(result.shouldRollover());
        Assert.assertEquals(1, result.matchedConditions().size());
        Assert.assertEquals("max_age", result.matchedConditions().get(0));
    }

    @Test
    public void maxDocsConditionAloneMatches() {
        RolloverConditions conditions = RolloverConditions.builder().maxDocs(1000L).build();
        IndexStatsSnapshot stats = new IndexStatsSnapshot(0, 1500, 10, 10);
        RolloverResult result = service.evaluateConditions(stats, conditions);
        Assert.assertTrue(result.shouldRollover());
        Assert.assertEquals("max_docs", result.matchedConditions().get(0));
    }

    @Test
    public void maxSizeConditionAloneMatches() {
        RolloverConditions conditions = RolloverConditions.builder().maxSize(ByteSizeValue.ofGb(1)).build();
        IndexStatsSnapshot stats = new IndexStatsSnapshot(0, 10, ByteSizeValue.ofGb(2).getBytes(), 10);
        RolloverResult result = service.evaluateConditions(stats, conditions);
        Assert.assertTrue(result.shouldRollover());
        Assert.assertEquals("max_size", result.matchedConditions().get(0));
    }

    @Test
    public void maxPrimaryShardSizeConditionAloneMatches() {
        RolloverConditions conditions = RolloverConditions.builder().maxPrimaryShardSize(ByteSizeValue.ofMb(50)).build();
        IndexStatsSnapshot stats = new IndexStatsSnapshot(0, 10, 10, ByteSizeValue.ofMb(60).getBytes());
        RolloverResult result = service.evaluateConditions(stats, conditions);
        Assert.assertTrue(result.shouldRollover());
        Assert.assertEquals("max_primary_shard_size", result.matchedConditions().get(0));
    }

    @Test
    public void noConditionMatchesWhenBelowThresholds() {
        RolloverConditions conditions = RolloverConditions.builder()
            .maxAge(new TimeValue(1, TimeUnit.DAYS))
            .maxDocs(1000L)
            .maxSize(ByteSizeValue.ofGb(1))
            .maxPrimaryShardSize(ByteSizeValue.ofMb(50))
            .build();
        IndexStatsSnapshot stats = new IndexStatsSnapshot(TimeValue.timeValueHours(1).millis(), 10,
            ByteSizeValue.ofMb(10).getBytes(), ByteSizeValue.ofMb(5).getBytes());
        RolloverResult result = service.evaluateConditions(stats, conditions);
        Assert.assertFalse(result.shouldRollover());
        Assert.assertTrue(result.matchedConditions().isEmpty());
    }

    @Test
    public void combinedConditionsAllMatch() {
        RolloverConditions conditions = RolloverConditions.builder()
            .maxAge(new TimeValue(1, TimeUnit.DAYS))
            .maxDocs(1000L)
            .maxSize(ByteSizeValue.ofGb(1))
            .maxPrimaryShardSize(ByteSizeValue.ofMb(50))
            .build();
        IndexStatsSnapshot stats = new IndexStatsSnapshot(TimeValue.timeValueHours(48).millis(), 5000,
            ByteSizeValue.ofGb(3).getBytes(), ByteSizeValue.ofMb(100).getBytes());
        RolloverResult result = service.evaluateConditions(stats, conditions);
        Assert.assertTrue(result.shouldRollover());
        Assert.assertEquals(4, result.matchedConditions().size());
    }
}
