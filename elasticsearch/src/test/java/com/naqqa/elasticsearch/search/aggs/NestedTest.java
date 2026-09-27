package com.naqqa.elasticsearch.search.aggs;

import com.naqqa.elasticsearch.search.aggs.bucket.filter.InternalSingleBucketAggregation;
import com.naqqa.elasticsearch.search.aggs.bucket.nested.RelatedDocsAggregator;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

public final class NestedTest {

    @Test
    public void nestedAggregatorCollectsAllChildrenOfMatchedParents() {
        Map<Integer, int[]> childrenOfParent = Map.of(
            0, new int[]{100, 101, 102},
            1, new int[]{103}
        );
        IntFunction<int[]> childrenOf = childrenOfParent::get;
        RelatedDocsAggregator agg = new RelatedDocsAggregator("comments", "nested", new Aggregator[0],
            new com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer(10000), childrenOf);
        agg.collect(0, 0);
        agg.collect(1, 0);
        InternalSingleBucketAggregation result = (InternalSingleBucketAggregation) agg.buildAggregation(0);
        Assert.assertEquals(4L, result.docCount());
    }

    @Test
    public void reverseNestedAggregatorCollectsDistinctParentsWithDuplicateCollectionPerChild() {
        IntUnaryOperator parentOf = childDoc -> childDoc < 103 ? 0 : 1;
        RelatedDocsAggregator agg = new RelatedDocsAggregator("back_to_parent", "reverse_nested", new Aggregator[0],
            new com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer(10000), doc -> new int[]{parentOf.applyAsInt(doc)});
        agg.collect(100, 0);
        agg.collect(101, 0);
        agg.collect(102, 0);
        agg.collect(103, 0);
        InternalSingleBucketAggregation result = (InternalSingleBucketAggregation) agg.buildAggregation(0);
        Assert.assertEquals(4L, result.docCount());
    }

    @Test
    public void reduceMergesNestedDocCountsAcrossShards() {
        IntFunction<int[]> childrenOf = doc -> new int[]{doc * 10, doc * 10 + 1};
        RelatedDocsAggregator shard1 = new RelatedDocsAggregator("n", "nested", new Aggregator[0],
            new com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer(10000), childrenOf);
        RelatedDocsAggregator shard2 = new RelatedDocsAggregator("n", "nested", new Aggregator[0],
            new com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer(10000), childrenOf);
        shard1.collect(0, 0);
        shard2.collect(1, 0);
        shard2.collect(2, 0);
        InternalSingleBucketAggregation part1 = (InternalSingleBucketAggregation) shard1.buildAggregation(0);
        InternalSingleBucketAggregation part2 = (InternalSingleBucketAggregation) shard2.buildAggregation(0);
        InternalSingleBucketAggregation merged = (InternalSingleBucketAggregation) part1.reduce(java.util.List.of(part1, part2), ReduceContext.forFinalReduction());
        Assert.assertEquals(6L, merged.docCount());
    }
}
