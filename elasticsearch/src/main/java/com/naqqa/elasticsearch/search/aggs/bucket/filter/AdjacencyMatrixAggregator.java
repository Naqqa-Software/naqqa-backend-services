package com.naqqa.elasticsearch.search.aggs.bucket.filter;

import com.naqqa.elasticsearch.search.aggs.AggParseContext;
import com.naqqa.elasticsearch.search.aggs.Aggregator;
import com.naqqa.elasticsearch.search.aggs.BucketsAggregator;
import com.naqqa.elasticsearch.search.aggs.InternalAggregation;
import com.naqqa.elasticsearch.search.aggs.InternalAggregations;
import com.naqqa.elasticsearch.search.aggs.support.MultiBucketConsumer;
import com.naqqa.elasticsearch.search.aggs.support.ParamsHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

public final class AdjacencyMatrixAggregator extends BucketsAggregator {

    private final List<String> names;
    private final List<IntPredicate> predicates;
    private final String separator;

    public AdjacencyMatrixAggregator(String name, Aggregator[] subAggregators, MultiBucketConsumer bucketConsumer, List<String> names,
                                      List<IntPredicate> predicates, String separator) {
        super(name, subAggregators, bucketConsumer);
        this.names = names;
        this.predicates = predicates;
        this.separator = separator;
    }

    @SuppressWarnings("unchecked")
    public static Aggregator parse(AggParseContext ctx) {
        Map<String, Object> filters = ParamsHelper.asMap(ctx.params().get("filters"));
        List<String> names = new ArrayList<>();
        List<IntPredicate> predicates = new ArrayList<>();
        for (Map.Entry<String, Object> e : filters.entrySet()) {
            names.add(e.getKey());
            predicates.add((IntPredicate) e.getValue());
        }
        String separator = ParamsHelper.getString(ctx.params(), "separator", "&");
        return new AdjacencyMatrixAggregator(ctx.name(), ctx.subAggregators(), ctx.bucketConsumer(), names, predicates, separator);
    }

    private long pairKey(int i, int j) {
        return (long) names.size() + (long) i * names.size() + j;
    }

    @Override
    public void collect(int doc, long bucketOrd) {
        List<Integer> matched = new ArrayList<>();
        for (int i = 0; i < predicates.size(); i++) {
            if (predicates.get(i).test(doc)) {
                matched.add(i);
                collectBucket(doc, bucketOrd, i);
            }
        }
        for (int a = 0; a < matched.size(); a++) {
            for (int b = a + 1; b < matched.size(); b++) {
                collectBucket(doc, bucketOrd, pairKey(matched.get(a), matched.get(b)));
            }
        }
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrd) {
        List<FiltersBucket> buckets = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            long bucketOrd = bucketOrds.find(owningBucketOrd, i);
            InternalAggregations subAggs = buildSubAggsForBucket(bucketOrd);
            buckets.add(new FiltersBucket(names.get(i), bucketDocCount(bucketOrd), subAggs));
        }
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                long bucketOrd = bucketOrds.find(owningBucketOrd, pairKey(i, j));
                if (bucketOrd < 0) {
                    continue;
                }
                InternalAggregations subAggs = buildSubAggsForBucket(bucketOrd);
                buckets.add(new FiltersBucket(names.get(i) + separator + names.get(j), bucketDocCount(bucketOrd), subAggs));
            }
        }
        return new InternalFilters(name, buckets, true, null);
    }
}
