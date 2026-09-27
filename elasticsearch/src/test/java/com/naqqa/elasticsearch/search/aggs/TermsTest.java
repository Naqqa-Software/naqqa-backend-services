package com.naqqa.elasticsearch.search.aggs;

import com.naqqa.elasticsearch.search.aggs.bucket.terms.InternalTerms;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.TermsAggregator;
import com.naqqa.elasticsearch.search.aggs.bucket.terms.TermsBucket;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TermsTest {

    private static FakeValuesLookup terms(String... values) {
        Map<Integer, String[]> docs = new HashMap<>();
        for (int i = 0; i < values.length; i++) {
            docs.put(i, new String[]{values[i]});
        }
        return new FakeValuesLookup().putStrings("f", docs);
    }

    @Test
    public void ordersByCountDescendingByDefault() {
        FakeValuesLookup lookup = terms("a", "a", "a", "b", "b", "c");
        TermsAggregator agg = TermsAggregatorFactory(lookup, 10, 10, 1);
        for (int i = 0; i < 6; i++) {
            agg.collect(i, 0);
        }
        InternalTerms result = (InternalTerms) agg.buildAggregation(0);
        List<?> buckets = result.getBuckets();
        Assert.assertEquals(3, buckets.size());
        TermsBucket first = (TermsBucket) buckets.get(0);
        Assert.assertEquals("a", first.getKey());
        Assert.assertEquals(3L, first.getDocCount());
    }

    @Test
    public void minDocCountFiltersRareTerms() {
        FakeValuesLookup lookup = terms("a", "a", "b");
        TermsAggregator agg = TermsAggregatorFactory(lookup, 10, 10, 2);
        agg.collect(0, 0);
        agg.collect(1, 0);
        agg.collect(2, 0);
        InternalTerms shard = (InternalTerms) agg.buildAggregation(0);
        InternalTerms finalResult = (InternalTerms) shard.reduce(List.of(shard), ReduceContext.forFinalReduction());
        Assert.assertEquals(1, finalResult.getBuckets().size());
        Assert.assertEquals("a", finalResult.getBuckets().get(0).getKey());
    }

    @Test
    public void includeExcludeFiltersTerms() {
        FakeValuesLookup lookup = terms("apple", "banana", "avocado", "cherry");
        Map<String, Object> params = new HashMap<>();
        params.put("field", "f");
        params.put("include", "a.*");
        Aggregator aggregator = build(lookup, params);
        for (int i = 0; i < 4; i++) {
            aggregator.collect(i, 0);
        }
        InternalTerms result = (InternalTerms) aggregator.buildAggregation(0);
        Assert.assertEquals(2, result.getBuckets().size());
        for (Object b : result.getBuckets()) {
            String key = (String) ((TermsBucket) b).getKey();
            Assert.assertTrue(key.startsWith("a"), "unexpected key: " + key);
        }
    }

    @Test
    public void reduceMergesPartialShardsToSameResultAsUnsplit() {
        FakeValuesLookup lookup = terms("a", "a", "a", "b", "b", "c");
        TermsAggregator full = TermsAggregatorFactory(lookup, 10, 10, 1);
        for (int i = 0; i < 6; i++) {
            full.collect(i, 0);
        }
        InternalTerms unsplit = (InternalTerms) full.buildAggregation(0);

        TermsAggregator shard1 = TermsAggregatorFactory(lookup, 10, 10, 1);
        TermsAggregator shard2 = TermsAggregatorFactory(lookup, 10, 10, 1);
        for (int i = 0; i < 3; i++) {
            shard1.collect(i, 0);
        }
        for (int i = 3; i < 6; i++) {
            shard2.collect(i, 0);
        }
        InternalTerms part1 = (InternalTerms) shard1.buildAggregation(0);
        InternalTerms part2 = (InternalTerms) shard2.buildAggregation(0);
        InternalTerms merged = (InternalTerms) part1.reduce(List.of(part1, part2), ReduceContext.forFinalReduction());

        Map<Object, Long> unsplitCounts = new HashMap<>();
        for (Object b : unsplit.getBuckets()) {
            unsplitCounts.put(((TermsBucket) b).getKey(), ((TermsBucket) b).getDocCount());
        }
        Map<Object, Long> mergedCounts = new HashMap<>();
        for (Object b : merged.getBuckets()) {
            mergedCounts.put(((TermsBucket) b).getKey(), ((TermsBucket) b).getDocCount());
        }
        Assert.assertEquals(unsplitCounts, mergedCounts);
    }

    private static TermsAggregator TermsAggregatorFactory(FakeValuesLookup lookup, int size, int shardSize, long minDocCount) {
        return new TermsAggregator("t", new Aggregator[0], new com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer(10000),
            lookup.bytesValues("f"), null, false, s -> true, size, shardSize, minDocCount,
            com.naqqa.elasticsearch.search.aggs.bucket.terms.TermsOrder.count(false), false);
    }

    private static Aggregator build(FakeValuesLookup lookup, Map<String, Object> params) {
        Map<String, Object> aggsClause = Map.of("t", Map.of("terms", params));
        Aggregator top = AggregatorFactories.createTopLevel(aggsClause, lookup, new com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer(10000));
        return top.subAggregators()[0];
    }
}
