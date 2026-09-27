package com.naqqa.elasticsearch.search.aggs;

import com.naqqa.elasticsearch.search.aggs.metrics.AvgAggregator;
import com.naqqa.elasticsearch.search.aggs.metrics.InternalAvg;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.TooManyBucketsException;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.HashMap;
import java.util.Map;

public final class FrameworkTest {

    @Test
    public void avgAggregatorAccumulatesPerBucket() {
        Map<Integer, double[]> docValues = new HashMap<>();
        docValues.put(0, new double[]{10});
        docValues.put(1, new double[]{20});
        docValues.put(2, new double[]{30});
        FakeValuesLookup lookup = new FakeValuesLookup().putDoubles("price", docValues);
        AvgAggregator agg = new AvgAggregator("avg_price", lookup.doubleValues("price"));
        agg.collect(0, 0);
        agg.collect(1, 0);
        agg.collect(2, 1);
        InternalAvg bucket0 = (InternalAvg) agg.buildAggregation(0);
        InternalAvg bucket1 = (InternalAvg) agg.buildAggregation(1);
        Assert.assertEquals(15.0, bucket0.value(), 1e-9);
        Assert.assertEquals(30.0, bucket1.value(), 1e-9);
    }

    @Test
    public void multiBucketConsumerThrowsWhenLimitExceeded() {
        MultiBucketConsumer consumer = new MultiBucketConsumer(3);
        consumer.accept(1);
        consumer.accept(1);
        consumer.accept(1);
        Assert.assertThrows(TooManyBucketsException.class, () -> consumer.accept(1));
    }

    @Test
    public void internalAggregationsReduceMergesByName() {
        InternalAvg a1 = new InternalAvg("x", 10, 2, null);
        InternalAvg a2 = new InternalAvg("x", 20, 3, null);
        InternalAggregations part1 = new InternalAggregations(java.util.List.of(a1));
        InternalAggregations part2 = new InternalAggregations(java.util.List.of(a2));
        InternalAggregations reduced = InternalAggregations.reduceAll(java.util.List.of(part1, part2), ReduceContext.forFinalReduction());
        InternalAvg merged = (InternalAvg) reduced.get("x");
        Assert.assertEquals(30.0 / 5.0, merged.value(), 1e-9);
    }

    @Test
    public void aggregatorFactoriesBuildsTreeFromMap() {
        Map<Integer, double[]> docValues = new HashMap<>();
        docValues.put(0, new double[]{5});
        docValues.put(1, new double[]{15});
        FakeValuesLookup lookup = new FakeValuesLookup().putDoubles("price", docValues);
        Map<String, Object> aggsClause = Map.of(
            "my_avg", Map.of("avg", Map.of("field", "price"))
        );
        Aggregator top = AggregatorFactories.createTopLevel(aggsClause, lookup, new MultiBucketConsumer(1000));
        top.collect(0, 0);
        top.collect(1, 0);
        InternalAggregations result = (InternalAggregations) top.buildAggregation(0);
        InternalAvg avg = (InternalAvg) result.get("my_avg");
        Assert.assertEquals(10.0, avg.value(), 1e-9);
    }
}
