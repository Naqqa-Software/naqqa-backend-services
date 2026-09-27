package com.naqqa.elasticsearch.search.aggs;

import com.naqqa.elasticsearch.search.aggs.bucket.composite.CompositeBucket;
import com.naqqa.elasticsearch.search.aggs.bucket.composite.InternalComposite;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CompositeTest {

    @Test
    public void afterKeyPaginationCoversAllBucketsWithoutOverlap() {
        Map<Integer, String[]> docs = new HashMap<>();
        String[] terms = {"a", "b", "c", "d", "e", "f", "g"};
        for (int i = 0; i < terms.length; i++) {
            docs.put(i, new String[]{terms[i]});
        }
        FakeValuesLookup lookup = new FakeValuesLookup().putStrings("f", docs);

        Map<String, Object> aggsClause = Map.of(
            "composite_agg", Map.of("composite", Map.of(
                "size", 3,
                "sources", List.of(Map.of("term_source", Map.of("terms", Map.of("field", "f"))))
            ))
        );

        Set<Object> seenKeys = new HashSet<>();
        Map<String, Object> afterKey = null;
        int pages = 0;
        while (true) {
            Map<String, Object> clause = afterKey == null ? aggsClause : withAfter(aggsClause, afterKey);
            Aggregator top = AggregatorFactories.createTopLevel(clause, lookup, new com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer(10000));
            for (int i = 0; i < terms.length; i++) {
                top.collect(i, 0);
            }
            InternalAggregations result = (InternalAggregations) top.buildAggregation(0);
            InternalComposite composite = (InternalComposite) result.get("composite_agg");
            List<?> buckets = composite.getBuckets();
            if (buckets.isEmpty()) {
                break;
            }
            for (Object b : buckets) {
                Object key = ((CompositeBucket) b).getKey();
                Assert.assertTrue(seenKeys.add(key), "key seen twice across pages: " + key);
            }
            afterKey = composite.afterKey();
            pages++;
            Assert.assertTrue(pages < 10, "too many pages, pagination not converging");
        }
        Assert.assertEquals(terms.length, seenKeys.size());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> withAfter(Map<String, Object> aggsClause, Map<String, Object> afterKey) {
        Map<String, Object> compositeAgg = (Map<String, Object>) aggsClause.get("composite_agg");
        Map<String, Object> compositeParams = new HashMap<>((Map<String, Object>) compositeAgg.get("composite"));
        compositeParams.put("after", afterKey);
        Map<String, Object> newCompositeAgg = new HashMap<>();
        newCompositeAgg.put("composite", compositeParams);
        Map<String, Object> newClause = new HashMap<>();
        newClause.put("composite_agg", newCompositeAgg);
        return newClause;
    }
}
